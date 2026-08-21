/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.stream

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.bouncycastle.openpgp.PGPCompressedData
import org.bouncycastle.openpgp.PGPObjectFactory
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection
import org.bouncycastle.openpgp.PGPSignatureList
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentVerifierBuilderProvider
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.json.JSONObject
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class YtDlpUpdateWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            try {
                updateRuntime()
                Result.success()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                Timber.tag(TAG).w(throwable, "Signed yt-dlp runtime update failed")
                Result.retry()
            }
        }

    private fun updateRuntime() {
        val release = JSONObject(fetch(LATEST_RELEASE_URL, MAX_METADATA_BYTES).decodeToString())
        val version = release.getString("tag_name")
        require(STABLE_VERSION.matches(version))
        if (compareVersions(version, YtDlpRuntimeStore.newestInstalledVersion(applicationContext)) <= 0) return

        val assets = release.getJSONArray("assets")
        val assetUrls =
            buildMap {
                repeat(assets.length()) { index ->
                    val asset = assets.getJSONObject(index)
                    put(asset.getString("name"), asset.getString("browser_download_url"))
                }
            }
        val archiveUrl = requireOfficialAssetUrl(assetUrls.getValue(ARCHIVE_NAME), version, ARCHIVE_NAME)
        val sumsUrl = requireOfficialAssetUrl(assetUrls.getValue(SUMS_NAME), version, SUMS_NAME)
        val signatureUrl = requireOfficialAssetUrl(assetUrls.getValue(SIGNATURE_NAME), version, SIGNATURE_NAME)
        val sums = fetch(sumsUrl, MAX_SUMS_BYTES)
        val signature = fetch(signatureUrl, MAX_SIGNATURE_BYTES)
        verifySignature(sums, signature)

        val expectedSha256 =
            sums.decodeToString()
                .lineSequence()
                .map(String::trim)
                .firstOrNull { line -> line.endsWith("  $ARCHIVE_NAME") || line.endsWith(" *$ARCHIVE_NAME") }
                ?.substringBefore(' ')
                ?.lowercase()
                ?.takeIf { SHA256.matches(it) }
                ?: error("Official checksum does not contain yt-dlp")
        val archive = fetch(archiveUrl, MAX_ARCHIVE_BYTES)
        require(archive.size >= MIN_ARCHIVE_BYTES)
        require(archive.sha256() == expectedSha256)
        YtDlpRuntimeStore.stage(applicationContext, version, expectedSha256, archive)
    }

    private fun fetch(
        url: String,
        maximumBytes: Int,
    ): ByteArray {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful)
            require(response.request.url.isAllowedDownloadHost())
            val body = checkNotNull(response.body)
            val declaredLength = body.contentLength()
            require(declaredLength < 0L || declaredLength <= maximumBytes)
            val output = ByteArrayOutputStream(minOf(maximumBytes, 64 * 1024))
            body.byteStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    require(total <= maximumBytes)
                    output.write(buffer, 0, read)
                }
            }
            return output.toByteArray()
        }
    }

    private fun verifySignature(
        content: ByteArray,
        signatureBytes: ByteArray,
    ) {
        val keyCollection =
            applicationContext.assets.open(PUBLIC_KEY_ASSET).use { input ->
                PGPPublicKeyRingCollection(PGPUtil.getDecoderStream(input), JcaKeyFingerprintCalculator())
            }
        val signatureFactory =
            PGPObjectFactory(
                PGPUtil.getDecoderStream(ByteArrayInputStream(signatureBytes)),
                JcaKeyFingerprintCalculator(),
            )
        val firstObject = signatureFactory.nextObject()
        val signatureList =
            when (firstObject) {
                is PGPSignatureList -> firstObject
                is PGPCompressedData ->
                    PGPObjectFactory(firstObject.dataStream, JcaKeyFingerprintCalculator()).nextObject() as PGPSignatureList
                else -> error("Unsupported signature container")
            }
        check(signatureList.size() > 0) { "Missing signature" }
        val signature = signatureList.get(0)
        val publicKey = keyCollection.getPublicKey(signature.keyID) ?: error("Unknown signing key")
        signature.init(
            JcaPGPContentVerifierBuilderProvider().setProvider(BouncyCastleProvider()),
            publicKey,
        )
        signature.update(content)
        require(signature.verify())
    }

    private fun requireOfficialAssetUrl(
        url: String,
        version: String,
        name: String,
    ): String {
        val expected = "https://github.com/yt-dlp/yt-dlp/releases/download/$version/$name"
        require(url == expected)
        return url
    }

    private fun okhttp3.HttpUrl.isAllowedDownloadHost(): Boolean =
        isHttps && host in ALLOWED_DOWNLOAD_HOSTS

    private fun ByteArray.sha256(): String =
        MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }

    private fun compareVersions(
        first: String,
        second: String,
    ): Int {
        val firstParts = first.split('.').map(String::toInt)
        val secondParts = second.split('.').map(String::toInt)
        repeat(maxOf(firstParts.size, secondParts.size)) { index ->
            val comparison = (firstParts.getOrNull(index) ?: 0).compareTo(secondParts.getOrNull(index) ?: 0)
            if (comparison != 0) return comparison
        }
        return 0
    }

    private companion object {
        const val TAG = "YtDlpUpdate"
        const val LATEST_RELEASE_URL = "https://api.github.com/repos/yt-dlp/yt-dlp/releases/latest"
        const val ARCHIVE_NAME = "yt-dlp"
        const val SUMS_NAME = "SHA2-256SUMS"
        const val SIGNATURE_NAME = "SHA2-256SUMS.sig"
        const val PUBLIC_KEY_ASSET = "yt_dlp_public_key.asc"
        const val USER_AGENT = "ArchiveTune yt-dlp updater"
        const val MIN_ARCHIVE_BYTES = 512 * 1024
        const val MAX_ARCHIVE_BYTES = 20 * 1024 * 1024
        const val MAX_METADATA_BYTES = 1024 * 1024
        const val MAX_SUMS_BYTES = 128 * 1024
        const val MAX_SIGNATURE_BYTES = 64 * 1024
        val STABLE_VERSION = Regex("^\\d{4}\\.\\d{2}\\.\\d{2}(?:\\.\\d+)?$")
        val SHA256 = Regex("^[0-9a-f]{64}$")
        val ALLOWED_DOWNLOAD_HOSTS =
            setOf(
                "api.github.com",
                "github.com",
                "objects.githubusercontent.com",
                "release-assets.githubusercontent.com",
            )
        val client =
            OkHttpClient.Builder()
                .followRedirects(true)
                .followSslRedirects(true)
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .callTimeout(60, TimeUnit.SECONDS)
                .build()
    }
}

object YtDlpUpdateScheduler {
    private const val PERIODIC_WORK_NAME = "yt_dlp_stable_update"
    private const val INITIAL_WORK_NAME = "yt_dlp_initial_update"

    fun schedule(context: Context) {
        val constraints =
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()
        val workManager = WorkManager.getInstance(context)
        workManager.enqueueUniqueWork(
            INITIAL_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<YtDlpUpdateWorker>()
                .setConstraints(constraints)
                .build(),
        )
        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<YtDlpUpdateWorker>(24, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build(),
        )
    }
}
