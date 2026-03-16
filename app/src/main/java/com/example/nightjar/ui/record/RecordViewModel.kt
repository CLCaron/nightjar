package com.example.nightjar.ui.record

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.nightjar.audio.MetronomePreferences
import com.example.nightjar.audio.OboeAudioEngine
import com.example.nightjar.audio.SoundFontManager
import com.example.nightjar.data.repository.IdeaRepository
import com.example.nightjar.data.storage.RecordingStorage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

/**
 * ViewModel for the Record screen.
 *
 * Manages the record → stop → post-recording → navigate flow.
 * After stopping, the user stays on the Record screen and can choose
 * to open Overview, open Studio, or start a new recording.
 * Handles lifecycle edge cases like the app being backgrounded
 * mid-recording.
 */
@HiltViewModel
class RecordViewModel @Inject constructor(
    private val audioEngine: OboeAudioEngine,
    private val recordingStorage: RecordingStorage,
    private val repo: IdeaRepository,
    private val metronomePrefs: MetronomePreferences,
    private val soundFontManager: SoundFontManager
) : ViewModel() {

    private val _state = MutableStateFlow(RecordUiState())
    val state = _state.asStateFlow()

    private val _effects = MutableSharedFlow<RecordEffect>()
    val effects = _effects.asSharedFlow()

    private var startJob: Job? = null
    private var amplitudeTickJob: Job? = null
    private val amplitudeBuffer = ArrayList<Float>()
    private var recordingFile: File? = null

    // Tap tempo tracking
    private val tapTimestamps = mutableListOf<Long>()
    // SoundFont loading (one-time, needed for metronome sounds)
    private var soundFontLoaded: Boolean = false

    init {
        // Load persisted metronome settings
        _state.value = _state.value.copy(
            isMetronomeEnabled = metronomePrefs.isEnabled,
            metronomeVolume = metronomePrefs.volume,
            countInBars = metronomePrefs.countInBars
        )
        audioEngine.setMetronomeVolume(metronomePrefs.volume)
    }

    fun onAction(action: RecordAction) {
        when (action) {
            RecordAction.StartRecording -> startRecording()
            RecordAction.StopAndSave -> stopAndSave()
            RecordAction.StopForBackground -> stopForBackground()
            RecordAction.GoToOverview -> goToOverview()
            RecordAction.GoToStudio -> goToStudio()
            RecordAction.CreateWriteIdea -> createWriteIdea()
            RecordAction.CreateStudioIdea -> createStudioIdea()
            RecordAction.ToggleMetronome -> toggleMetronome()
            is RecordAction.SetMetronomeVolume -> setMetronomeVolume(action.volume)
            is RecordAction.SetMetronomeBpm -> setMetronomeBpm(action.bpm)
            is RecordAction.SetCountInBars -> setCountInBars(action.bars)
            RecordAction.ToggleMetronomeSettings -> {
                _state.value = _state.value.copy(
                    isMetronomeSettingsOpen = !_state.value.isMetronomeSettingsOpen
                )
            }
            RecordAction.TapTempo -> tapTempo()
        }
    }

    fun startRecording() {
        // Clear any post-recording state -- starting fresh
        amplitudeBuffer.clear()
        recordingFile = null
        val prev = _state.value
        _state.value = RecordUiState(
            isRecording = true,
            isMetronomeEnabled = prev.isMetronomeEnabled,
            metronomeVolume = prev.metronomeVolume,
            metronomeBpm = prev.metronomeBpm,
            countInBars = prev.countInBars
        )
        startJob = viewModelScope.launch {
            try {
                val st = _state.value
                val countInBars = st.countInBars
                val metronomeOn = st.isMetronomeEnabled
                val hasCountIn = countInBars > 0 && metronomeOn

                if (metronomeOn) {
                    ensureSoundFontLoaded()
                    audioEngine.setBpm(st.metronomeBpm)
                    audioEngine.setMetronomeEnabled(true)
                    audioEngine.setMetronomeBeatsPerBar(4) // default 4/4 for Record

                    if (hasCountIn) {
                        _state.value = _state.value.copy(isCountingIn = true)
                        audioEngine.setCountIn(countInBars, 4)
                    }

                    // Reset position so metronome starts fresh each recording
                    audioEngine.seekTo(0)
                    audioEngine.play()
                }

                val file = recordingStorage.createRecordingFile()
                recordingFile = file

                // Phase 1: Start input stream
                val started = audioEngine.startRecording(file.absolutePath)
                if (!started) {
                    recordingFile = null
                    if (metronomeOn) {
                        audioEngine.pause()
                        audioEngine.setMetronomeEnabled(false)
                    }
                    _state.value = _state.value.copy(
                        isRecording = false,
                        isCountingIn = false,
                        errorMessage = "Failed to start recording."
                    )
                    return@launch
                }
                // Phase 2: Pipeline hot
                audioEngine.awaitFirstBuffer()

                if (hasCountIn) {
                    // Wait for count-in to finish (metronome clicks, no recording yet)
                    val beatDurationMs = 60_000.0 / st.metronomeBpm
                    val countInMs = (countInBars * 4 * beatDurationMs).toLong()
                    delay(countInMs)
                    _state.value = _state.value.copy(isCountingIn = false)
                }

                // Phase 3: Open write gate -- recording starts
                audioEngine.openWriteGate()
                startAmplitudeTicker()
                // Metronome continues clicking throughout the recording
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                recordingFile = null
                audioEngine.setMetronomeEnabled(false)
                _state.value = _state.value.copy(
                    isRecording = false,
                    isCountingIn = false,
                    errorMessage = e.message ?: "Failed to start recording."
                )
            }
        }
    }

    private suspend fun ensureSoundFontLoaded() {
        if (soundFontLoaded) return
        val path = soundFontManager.getSoundFontPath()
        if (path != null) {
            audioEngine.loadSoundFont(path)
            soundFontLoaded = true
        }
    }

    private fun startAmplitudeTicker() {
        amplitudeTickJob?.cancel()
        amplitudeTickJob = viewModelScope.launch {
            while (isActive) {
                val peak = audioEngine.getLatestPeakAmplitude()
                amplitudeBuffer.add(peak)
                _state.value = _state.value.copy(
                    liveAmplitudes = amplitudeBuffer.toFloatArray()
                )
                delay(AMPLITUDE_TICK_MS)
            }
        }
    }

    private fun stopAmplitudeTicker() {
        amplitudeTickJob?.cancel()
        amplitudeTickJob = null
    }

    private fun stopAndSave() {
        stopAmplitudeTicker()
        startJob?.cancel()
        startJob = null
        _state.value = _state.value.copy(errorMessage = null)

        // Stop metronome and playback
        audioEngine.setMetronomeEnabled(false)
        audioEngine.pause()

        val file = recordingFile
        recordingFile = null

        val durationMs = try {
            audioEngine.stopRecording()
        } catch (e: Exception) {
            val msg = e.message ?: "Failed to stop/save recording."
            _state.value = _state.value.copy(isRecording = false, isCountingIn = false, errorMessage = msg)
            viewModelScope.launch { _effects.emit(RecordEffect.ShowError(msg)) }
            return
        }

        _state.value = _state.value.copy(isRecording = false, isCountingIn = false)

        if (durationMs <= 0 || file == null) {
            _state.value = _state.value.copy(errorMessage = "Failed to save audio.")
            return
        }

        viewModelScope.launch {
            try {
                val ideaId = repo.createIdeaWithTrack(file, durationMs)
                _state.value = _state.value.copy(
                    liveAmplitudes = FloatArray(0),
                    postRecording = PostRecordingState(
                        ideaId = ideaId,
                        audioFile = file
                    )
                )
            } catch (e: Exception) {
                val msg = e.message ?: "Saved audio, but failed to create idea."
                _state.value = _state.value.copy(errorMessage = msg)
                _effects.emit(RecordEffect.ShowError(msg))
            }
        }
    }

    private fun stopForBackground() {
        if (!_state.value.isRecording) return

        stopAmplitudeTicker()
        startJob?.cancel()
        startJob = null

        // Stop metronome and playback
        audioEngine.setMetronomeEnabled(false)
        audioEngine.pause()

        val file = recordingFile
        recordingFile = null

        val durationMs = audioEngine.stopRecording()
        _state.value = _state.value.copy(isRecording = false, isCountingIn = false, liveAmplitudes = FloatArray(0))

        if (durationMs > 0 && file != null) {
            viewModelScope.launch {
                try {
                    val ideaId = repo.createIdeaWithTrack(file, durationMs)
                    _state.value = _state.value.copy(
                        postRecording = PostRecordingState(
                            ideaId = ideaId,
                            audioFile = file
                        )
                    )
                } catch (_: Exception) {
                    // Best-effort — file is saved on disk even if DB insert fails
                }
            }
        }
    }

    private fun goToOverview() {
        val post = _state.value.postRecording ?: return
        _state.value = RecordUiState() // reset to idle
        viewModelScope.launch { _effects.emit(RecordEffect.OpenOverview(post.ideaId)) }
    }

    private fun goToStudio() {
        val post = _state.value.postRecording ?: return
        _state.value = RecordUiState() // reset to idle
        viewModelScope.launch { _effects.emit(RecordEffect.OpenStudio(post.ideaId)) }
    }

    private fun createWriteIdea() {
        viewModelScope.launch {
            try {
                val ideaId = repo.createEmptyIdea()
                _effects.emit(RecordEffect.OpenOverview(ideaId))
            } catch (e: Exception) {
                val msg = e.message ?: "Failed to create idea."
                _effects.emit(RecordEffect.ShowError(msg))
            }
        }
    }

    private fun createStudioIdea() {
        viewModelScope.launch {
            try {
                val ideaId = repo.createEmptyIdea()
                _effects.emit(RecordEffect.OpenStudio(ideaId))
            } catch (e: Exception) {
                val msg = e.message ?: "Failed to create idea."
                _effects.emit(RecordEffect.ShowError(msg))
            }
        }
    }

    // ── Metronome ─────────────────────────────────────────────────────────

    private fun toggleMetronome() {
        val newEnabled = !_state.value.isMetronomeEnabled
        _state.value = _state.value.copy(isMetronomeEnabled = newEnabled)
        metronomePrefs.isEnabled = newEnabled
    }

    private fun setMetronomeVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        _state.value = _state.value.copy(metronomeVolume = clamped)
        audioEngine.setMetronomeVolume(clamped)
        metronomePrefs.volume = clamped
    }

    private fun setMetronomeBpm(bpm: Double) {
        val clamped = bpm.coerceIn(30.0, 300.0)
        _state.value = _state.value.copy(metronomeBpm = clamped)
    }

    private fun setCountInBars(bars: Int) {
        _state.value = _state.value.copy(countInBars = bars)
        metronomePrefs.countInBars = bars
    }

    private fun tapTempo() {
        val now = System.currentTimeMillis()

        // Discard taps older than 3 seconds
        tapTimestamps.removeAll { now - it > 3000 }
        tapTimestamps.add(now)

        if (tapTimestamps.size >= 2) {
            // Calculate average interval between taps
            val intervals = tapTimestamps.zipWithNext { a, b -> b - a }
            val avgInterval = intervals.average()
            if (avgInterval > 0) {
                val bpm = (60_000.0 / avgInterval).coerceIn(30.0, 300.0)
                _state.value = _state.value.copy(metronomeBpm = bpm)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopAmplitudeTicker()
        startJob?.cancel()
        audioEngine.setMetronomeEnabled(false)
        // Engine is app-scoped singleton — don't release it here.
        // Just stop recording if it's still in progress.
        if (audioEngine.isRecordingActive()) {
            audioEngine.stopRecording()
        }
    }

    private companion object {
        const val AMPLITUDE_TICK_MS = 16L // ~60fps
    }
}
