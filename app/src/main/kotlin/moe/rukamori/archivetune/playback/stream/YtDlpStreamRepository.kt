/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.stream

import moe.rukamori.archivetune.utils.YTPlayerUtils
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class YtDlpStreamRepository
    @Inject
    constructor(
        private val runtime: YtDlpRuntime,
    ) : AudioStreamRepository {
        override suspend fun resolve(request: AudioStreamRequest): ResolvedAudioStream {
            val authState = YTPlayerUtils.ensureWebPoTokensForSubtitles(request.mediaId)
            return runtime.resolve(request, authState)
        }
    }
