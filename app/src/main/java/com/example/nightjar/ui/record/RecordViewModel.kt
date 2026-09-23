package com.example.nightjar.ui.record

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.nightjar.audio.MetronomePreferences
import com.example.nightjar.audio.OboeAudioEngine
import com.example.nightjar.audio.CaptureSession
import com.example.nightjar.audio.CaptureOptions
import com.example.nightjar.audio.CapturePhase
import com.example.nightjar.data.repository.IdeaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Record screen.
 *
 * Manages the record → stop → post-recording → navigate flow.
 * After stopping, the user stays on the Record screen and can choose
 * to open Overview, open Studio, or start a new recording.
 * CaptureSession owns recording and saving across screen and ViewModel lifetimes.
 */
@HiltViewModel
class RecordViewModel @Inject constructor(
    private val audioEngine: OboeAudioEngine,
    private val repo: IdeaRepository,
    private val metronomePrefs: MetronomePreferences,
    private val capture: CaptureSession
) : ViewModel() {

    private val _state = MutableStateFlow(RecordUiState())
    val state = _state.asStateFlow()

    private val _effects = MutableSharedFlow<RecordEffect>()
    val effects = _effects.asSharedFlow()

    // Tap tempo tracking
    private val tapTimestamps = mutableListOf<Long>()
    private var wordsJob: kotlinx.coroutines.Job? = null
    private var leaveIdeaJob: kotlinx.coroutines.Job? = null
    private var document: com.example.nightjar.data.repository.NotesSession.Document? = null

    init {
        // Load persisted metronome settings
        _state.value = _state.value.copy(
            isMetronomeEnabled = metronomePrefs.isEnabled,
            metronomeVolume = metronomePrefs.volume,
            countInBars = metronomePrefs.countInBars
        )
        viewModelScope.launch {
            capture.state.collect { session ->
                _state.value = _state.value.copy(
                    capture = session,
                    isRecording = session.recording,
                    isCountingIn = session.countingIn,
                    isSaving = session.phase == CapturePhase.SAVING,
                    liveAmplitudes = session.amplitudes.toFloatArray(),
                    postRecording = if (session.busy || session.pendingSave) null
                        else session.ideaId?.let { id -> session.file?.let { PostRecordingState(id, it) } },
                    errorMessage = session.error
                )
            }
        }
    }

    fun onAction(action: RecordAction) {
        when (action) {
            RecordAction.CreateGroup -> capture.createGroup()
            is RecordAction.OpenGroup -> capture.selectGroup(action.id)
            is RecordAction.RenameGroup -> capture.renameGroup(action.id, action.name)
            is RecordAction.AddGroupToStudio -> capture.addGroupToStudio(action.id)
            is RecordAction.UnlatchGroup -> capture.unlatchGroup(action.id)
            RecordAction.NewIdea -> startNewIdea()
            RecordAction.ShowSound -> _state.value = _state.value.copy(isWriting = false)
            is RecordAction.WordsChanged -> document?.edit(action.value)
            RecordAction.RetryWords -> document?.retry()
            RecordAction.PlayTake -> capture.playSelected()
            RecordAction.LeaveScreen -> {
                _state.value = _state.value.copy(isTempoDrawerOpen = false)
                capture.stopAudition()
            }
            is RecordAction.SelectTake -> capture.selectTake(action.id)
            RecordAction.StartRecording -> startRecording()
            RecordAction.StopAndSave -> capture.stop()
            RecordAction.GoToOverview -> goToOverview()
            RecordAction.GoToStudio -> goToStudio()
            RecordAction.CreateWriteIdea -> createWriteIdea()
            RecordAction.CreateStudioIdea -> createStudioIdea()
            RecordAction.ToggleMetronome -> toggleMetronome()
            is RecordAction.SetMetronomeVolume -> setMetronomeVolume(action.volume)
            is RecordAction.SetMetronomeBpm -> setMetronomeBpm(action.bpm)
            is RecordAction.SetCountInBars -> setCountInBars(action.bars)
            RecordAction.ToggleTempoDrawer -> {
                _state.value = _state.value.copy(
                    isTempoDrawerOpen = !_state.value.isTempoDrawerOpen
                )
            }
            RecordAction.DismissTempoDrawer ->
                _state.value = _state.value.copy(isTempoDrawerOpen = false)
            RecordAction.TapTempo -> tapTempo()
        }
    }

    fun startRecording() {
        val current = _state.value
        _state.value = current.copy(isWriting = false, isTempoDrawerOpen = false)
        capture.start(CaptureOptions(
            metronome = current.isMetronomeEnabled,
            volume = current.metronomeVolume,
            bpm = current.metronomeBpm,
            countInBars = current.countInBars
        ))
    }

    private fun goToOverview() {
        val post = _state.value.postRecording ?: return
        capture.stopAudition()
        viewModelScope.launch { _effects.emit(RecordEffect.OpenOverview(post.ideaId)) }
    }

    private fun goToStudio() {
        val ideaId = capture.state.value.ideaId ?: return
        capture.stopAudition()
        viewModelScope.launch { _effects.emit(RecordEffect.OpenStudio(ideaId)) }
    }

    private fun createWriteIdea() {
        if (capture.state.value.busy || capture.state.value.pendingSave) return
        val next = capture.writingDocument()
        _state.value = _state.value.copy(isWriting = true, isTempoDrawerOpen = false,
            writingFocusRequest = _state.value.writingFocusRequest + 1, words = next.state.value)
        if (document === next) return
        document = next
        wordsJob?.cancel()
        wordsJob = viewModelScope.launch {
            next.state.collect { words -> _state.value = _state.value.copy(words = words) }
        }
    }

    private fun createStudioIdea() {
        if (capture.state.value.busy || capture.state.value.pendingSave || leaveIdeaJob?.isActive == true) return
        leaveIdeaJob = viewModelScope.launch {
            try {
                if (document?.flush() == false) {
                    _effects.emit(RecordEffect.ShowError(
                        document?.state?.value?.error ?: "Words are still being saved. Try again."
                    ))
                    return@launch
                }
                capture.state.value.ideaId?.let { ideaId ->
                    capture.stopAudition()
                    _effects.emit(RecordEffect.OpenStudio(ideaId))
                    return@launch
                }
                val ideaId = repo.createEmptyIdea()
                _effects.emit(RecordEffect.OpenStudio(ideaId))
            } catch (e: Exception) {
                val msg = e.message ?: "Failed to create idea."
                _effects.emit(RecordEffect.ShowError(msg))
            }
        }
    }

    fun openIdea(ideaId: Long) {
        wordsJob?.cancel()
        document = null
        _state.value = _state.value.copy(isWriting = false,
            words = com.example.nightjar.data.repository.NotesState(ready = false))
        capture.openIdea(ideaId)
        viewModelScope.launch {
            val loaded = capture.state.first { !it.loadingIdea }
            if (loaded.ideaId != ideaId) return@launch
            val next = capture.writingDocument()
            document = next
            wordsJob?.cancel()
            wordsJob = viewModelScope.launch {
                next.state.collect { words -> _state.value = _state.value.copy(words = words) }
            }
        }
    }

    private fun startNewIdea() {
        if (capture.state.value.busy || capture.state.value.pendingSave || leaveIdeaJob?.isActive == true) return
        leaveIdeaJob = viewModelScope.launch {
            if (document?.flush() == false) {
                _effects.emit(RecordEffect.ShowError(
                    document?.state?.value?.error ?: "Words are still being saved. Try again."
                ))
                return@launch
            }
            if (!capture.canStartNewIdea()) return@launch
            capture.clearCompleted()
            wordsJob?.cancel()
            document = null
            _state.value = _state.value.copy(
                isWriting = false,
                words = com.example.nightjar.data.repository.NotesState(ready = true),
                isTempoDrawerOpen = false
            )
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

}
