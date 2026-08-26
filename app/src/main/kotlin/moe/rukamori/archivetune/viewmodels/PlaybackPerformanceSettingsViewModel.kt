/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.viewmodels

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.playback.preload.ObservePlaybackPerformanceSettingsUseCase
import moe.rukamori.archivetune.playback.preload.SetLowDataModeUseCase
import moe.rukamori.archivetune.playback.preload.SetPreloadNextSongUseCase
import javax.inject.Inject

sealed interface PlaybackPerformanceSettingsUiState {
    data object Loading : PlaybackPerformanceSettingsUiState

    data class Success(
        val data: PlaybackPerformanceSettingsUiData,
    ) : PlaybackPerformanceSettingsUiState

    data object Empty : PlaybackPerformanceSettingsUiState

    data class Error(
        @StringRes val messageRes: Int,
    ) : PlaybackPerformanceSettingsUiState
}

@Immutable
data class PlaybackPerformanceSettingsUiData(
    val lowDataModeEnabled: Boolean,
    val preloadNextSongEnabled: Boolean,
    val preloadNextSongAvailable: Boolean,
)

@HiltViewModel
class PlaybackPerformanceSettingsViewModel
    @Inject
    constructor(
        private val observePlaybackPerformanceSettings: ObservePlaybackPerformanceSettingsUseCase,
        private val setLowDataMode: SetLowDataModeUseCase,
        private val setPreloadNextSong: SetPreloadNextSongUseCase,
    ) : ViewModel() {
        private val mutableUiState =
            MutableStateFlow<PlaybackPerformanceSettingsUiState>(PlaybackPerformanceSettingsUiState.Loading)
        val uiState: StateFlow<PlaybackPerformanceSettingsUiState> = mutableUiState.asStateFlow()

        private var observeJob: Job? = null
        private var updateJob: Job? = null

        init {
            observeSettings()
        }

        fun retry() {
            if (observeJob?.isActive == true) return

            mutableUiState.value = PlaybackPerformanceSettingsUiState.Loading
            observeSettings()
        }

        fun onLowDataModeChange(enabled: Boolean) {
            updateSettings {
                setLowDataMode(enabled)
            }
        }

        fun onPreloadNextSongChange(enabled: Boolean) {
            updateSettings {
                setPreloadNextSong(enabled)
            }
        }

        private fun observeSettings() {
            observeJob =
                viewModelScope.launch {
                    try {
                        observePlaybackPerformanceSettings().collect { settings ->
                            mutableUiState.value =
                                if (settings.hasPersistedValue) {
                                    PlaybackPerformanceSettingsUiState.Success(
                                        PlaybackPerformanceSettingsUiData(
                                            lowDataModeEnabled = settings.lowDataModeEnabled,
                                            preloadNextSongEnabled = settings.preloadNextSongEnabled,
                                            preloadNextSongAvailable = !settings.lowDataModeEnabled,
                                        ),
                                    )
                                } else {
                                    PlaybackPerformanceSettingsUiState.Empty
                                }
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Throwable) {
                        mutableUiState.value = PlaybackPerformanceSettingsUiState.Error(R.string.error_unknown)
                    } finally {
                        observeJob = null
                    }
                }
        }

        private fun updateSettings(update: suspend () -> Unit) {
            updateJob?.cancel()
            updateJob =
                viewModelScope.launch {
                    try {
                        update()
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Throwable) {
