/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.stream

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

object YtDlpRuntimeStore {
    const val BUNDLED_VERSION = "2026.08.19"

    @Volatile
    private var initialized = false

    @Volatile
    private var activeFile: File? = null

    @Volatile
    var revision: String = BUNDLED_VERSION
        private set

    @Synchronized
    fun initializeForProcess(context: Context) {
        if (initialized) return
        val preferences = preferences(context)
        val pendingVersion = preferences.getString(KEY_PENDING_VERSION, null)
        val pendingSha256 = preferences.getString(KEY_PENDING_SHA256, null)
        if (pendingVersion != null && pendingSha256 != null) {
            val pendingFile = runtimeFile(context, pendingVersion)
            if (pendingFile.isFile && pendingFile.sha256().equals(pendingSha256, ignoreCase = true)) {
                val previousVersion = preferences.getString(KEY_ACTIVE_VERSION, null)
                val previousSha256 = preferences.getString(KEY_ACTIVE_SHA256, null)
                preferences.edit()
                    .putString(KEY_PREVIOUS_VERSION, previousVersion)
                    .putString(KEY_PREVIOUS_SHA256, previousSha256)
                    .putString(KEY_ACTIVE_VERSION, pendingVersion)
                    .putString(KEY_ACTIVE_SHA256, pendingSha256)
                    .remove(KEY_PENDING_VERSION)
                    .remove(KEY_PENDING_SHA256)
                    .apply()
            } else {
                preferences.edit()
                    .remove(KEY_PENDING_VERSION)
                    .remove(KEY_PENDING_SHA256)
                    .apply()
                pendingFile.delete()
            }
        }
        activeFile = validatedActiveFile(context)
        if (activeFile == null && preferences.getString(KEY_ACTIVE_VERSION, null) != null) {
            rollback(context)
            activeFile = validatedActiveFile(context)
        }
        revision = preferences.getString(KEY_ACTIVE_VERSION, null).takeIf { activeFile != null } ?: BUNDLED_VERSION
        initialized = true
    }

    fun activeArchive(context: Context): File? {
        initializeForProcess(context)
        return activeFile
    }

    @Synchronized
    fun stage(
        context: Context,
        version: String,
        sha256: String,
        archive: ByteArray,
    ) {
        val directory = runtimeDirectory(context, version).apply { mkdirs() }
        val destination = File(directory, ARCHIVE_FILE_NAME)
        val temporary = File(directory, "$ARCHIVE_FILE_NAME.tmp")
        FileOutputStream(temporary).use { output ->
            output.write(archive)
            output.fd.sync()
        }
        check(temporary.sha256().equals(sha256, ignoreCase = true))
        runCatching {
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.getOrElse {
            if (destination.exists()) {
                check(destination.delete())
            }
            check(temporary.renameTo(destination))
        }
        preferences(context).edit()
            .putString(KEY_PENDING_VERSION, version)
            .putString(KEY_PENDING_SHA256, sha256.lowercase())
            .apply()
        prune(context, setOfNotNull(version, currentActiveVersion(context), previousVersion(context)))
    }

    @Synchronized
    fun rollback(context: Context) {
        val preferences = preferences(context)
        val failedVersion = preferences.getString(KEY_ACTIVE_VERSION, null)
        val previousVersion = preferences.getString(KEY_PREVIOUS_VERSION, null)
        val previousSha256 = preferences.getString(KEY_PREVIOUS_SHA256, null)
        preferences.edit()
            .putString(KEY_ACTIVE_VERSION, previousVersion)
            .putString(KEY_ACTIVE_SHA256, previousSha256)
            .remove(KEY_PREVIOUS_VERSION)
            .remove(KEY_PREVIOUS_SHA256)
            .apply()
        failedVersion?.let { runtimeDirectory(context, it).deleteRecursively() }
        activeFile = validatedActiveFile(context)
        revision = previousVersion.takeIf { activeFile != null } ?: BUNDLED_VERSION
    }

    fun newestInstalledVersion(context: Context): String =
        listOfNotNull(
            BUNDLED_VERSION,
            currentActiveVersion(context),
            preferences(context).getString(KEY_PENDING_VERSION, null),
        ).maxWithOrNull { first, second -> compareVersions(first, second) } ?: BUNDLED_VERSION

    private fun validatedActiveFile(context: Context): File? {
        val preferences = preferences(context)
        val version = preferences.getString(KEY_ACTIVE_VERSION, null) ?: return null
        val expectedSha256 = preferences.getString(KEY_ACTIVE_SHA256, null) ?: return null
        return runtimeFile(context, version)
            .takeIf { file ->
                file.isFile &&
                    file.length() in MIN_ARCHIVE_BYTES..MAX_ARCHIVE_BYTES &&
                    file.sha256().equals(expectedSha256, ignoreCase = true)
            }
    }

    private fun currentActiveVersion(context: Context): String? =
        preferences(context).getString(KEY_ACTIVE_VERSION, null)

    private fun previousVersion(context: Context): String? =
        preferences(context).getString(KEY_PREVIOUS_VERSION, null)

    private fun prune(
        context: Context,
        retainedVersions: Set<String>,
    ) {
        runtimeRoot(context).listFiles()?.forEach { candidate ->
            if (candidate.isDirectory && candidate.name !in retainedVersions) {
                candidate.deleteRecursively()
            }
        }
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private fun runtimeRoot(context: Context): File = File(context.noBackupFilesDir, "yt_dlp")

    private fun runtimeDirectory(
        context: Context,
        version: String,
    ): File = File(runtimeRoot(context), version)

    private fun runtimeFile(
        context: Context,
        version: String,
    ): File = File(runtimeDirectory(context, version), ARCHIVE_FILE_NAME)

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun compareVersions(
        first: String,
        second: String,
    ): Int {
        val firstParts = first.split('.').mapNotNull(String::toIntOrNull)
        val secondParts = second.split('.').mapNotNull(String::toIntOrNull)
        repeat(maxOf(firstParts.size, secondParts.size)) { index ->
            val comparison = (firstParts.getOrNull(index) ?: 0).compareTo(secondParts.getOrNull(index) ?: 0)
            if (comparison != 0) return comparison
        }
        return 0
    }

    private const val PREFERENCES_NAME = "yt_dlp_runtime"
    private const val KEY_ACTIVE_VERSION = "active_version"
    private const val KEY_ACTIVE_SHA256 = "active_sha256"
    private const val KEY_PREVIOUS_VERSION = "previous_version"
    private const val KEY_PREVIOUS_SHA256 = "previous_sha256"
    private const val KEY_PENDING_VERSION = "pending_version"
    private const val KEY_PENDING_SHA256 = "pending_sha256"
    private const val ARCHIVE_FILE_NAME = "yt-dlp"
    private const val MIN_ARCHIVE_BYTES = 512L * 1024L
    private const val MAX_ARCHIVE_BYTES = 20L * 1024L * 1024L
}
