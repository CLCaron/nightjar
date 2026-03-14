package com.example.nightjar.ui.studio

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.nightjar.audio.MusicalTimeConverter
import com.example.nightjar.audio.OboeAudioEngine
import com.example.nightjar.data.db.entity.DrumStepEntity
import com.example.nightjar.data.repository.DrumRepository
import com.example.nightjar.data.repository.IdeaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Snapshot of a single clip for the full-screen drum editor. */
data class DrumEditorClip(
    val clipId: Long,
    val patternId: Long,
    val offsetMs: Long,
    val stepsPerBar: Int,
    val bars: Int,
    val steps: List<DrumStepEntity>
) {
    val totalSteps: Int get() = stepsPerBar * bars
}

/** UI state for the full-screen drum editor. */
data class DrumEditorState(
    val trackId: Long = 0L,
    val ideaId: Long = 0L,
    val trackName: String = "",
    val clips: List<DrumEditorClip> = emptyList(),
    val highlightClipId: Long = 0L,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val totalDurationMs: Long = 0L,
    val bpm: Double = 120.0,
    val timeSignatureNumerator: Int = 4,
    val timeSignatureDenominator: Int = 4,
    val gridResolution: Int = 16,
    val isSnapEnabled: Boolean = true,
    val isLoading: Boolean = true
)

/** User-initiated actions on the drum editor. */
sealed interface DrumEditorAction {
    data class ToggleStep(val clipId: Long, val patternId: Long, val stepIndex: Int, val drumNote: Int) : DrumEditorAction
    data object ToggleSnap : DrumEditorAction
    data object CycleGridResolution : DrumEditorAction
    data object Play : DrumEditorAction
    data object Pause : DrumEditorAction
    data class SeekTo(val positionMs: Long) : DrumEditorAction
}

/** One-shot effects from the drum editor. */
sealed interface DrumEditorEffect {
    data class ShowError(val message: String) : DrumEditorEffect
}

@HiltViewModel
class DrumEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val drumRepo: DrumRepository,
    private val ideaRepo: IdeaRepository,
    private val audioEngine: OboeAudioEngine
) : ViewModel() {

    private val trackId: Long = savedStateHandle["trackId"] ?: 0L
    private val ideaId: Long = savedStateHandle["ideaId"] ?: 0L
    private val navClipId: Long = savedStateHandle["clipId"] ?: 0L

    companion object {
        private const val TAG = "DrumEditorVM"
        private const val TICK_MS = 16L
    }

    private val _state = MutableStateFlow(DrumEditorState())
    val state = _state.asStateFlow()

    private val _effects = MutableSharedFlow<DrumEditorEffect>()
    val effects = _effects.asSharedFlow()

    private var tickJob: Job? = null
    /** Per-clip observation jobs, keyed by patternId. */
    private val observeJobs = mutableMapOf<Long, Job>()

    init {
        load()
        startTick()
    }

    private fun load() {
        viewModelScope.launch {
            try {
                val idea = ideaRepo.getIdeaById(ideaId)

                // Load all clips for this drum track
                val clipEntities = drumRepo.getClipsForTrack(trackId)
                val editorClips = clipEntities.map { clip ->
                    val pattern = drumRepo.getPatternById(clip.patternId)
                    val steps = drumRepo.getSteps(clip.patternId)
                    DrumEditorClip(
                        clipId = clip.id,
                        patternId = clip.patternId,
                        offsetMs = clip.offsetMs,
                        stepsPerBar = pattern?.stepsPerBar ?: 16,
                        bars = pattern?.bars ?: 1,
                        steps = steps
                    )
                }

                _state.update {
                    it.copy(
                        trackId = trackId,
                        ideaId = ideaId,
                        clips = editorClips,
                        highlightClipId = navClipId,
                        bpm = idea?.bpm ?: 120.0,
                        timeSignatureNumerator = idea?.timeSignatureNumerator ?: 4,
                        timeSignatureDenominator = idea?.timeSignatureDenominator ?: 4,
                        gridResolution = idea?.gridResolution ?: 16,
                        isLoading = false
                    )
                }

                // Observe steps for each clip
                editorClips.forEach { clip ->
                    observeClipSteps(clip.patternId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load drum editor", e)
                _effects.emit(DrumEditorEffect.ShowError(e.message ?: "Failed to load"))
            }
        }
    }

    private fun observeClipSteps(patternId: Long) {
        observeJobs[patternId]?.cancel()
        observeJobs[patternId] = viewModelScope.launch {
            drumRepo.observeSteps(patternId).collect { updatedSteps ->
                _state.update { state ->
                    state.copy(
                        clips = state.clips.map { clip ->
                            if (clip.patternId == patternId) clip.copy(steps = updatedSteps)
                            else clip
                        }
                    )
                }
            }
        }
    }

    private fun startTick() {
        tickJob?.cancel()
        tickJob = viewModelScope.launch {
            while (isActive) {
                audioEngine.pollState()
                _state.update {
                    it.copy(
                        isPlaying = audioEngine.isPlaying.value,
                        positionMs = audioEngine.positionMs.value,
                        totalDurationMs = audioEngine.totalDurationMs.value
                    )
                }
                delay(TICK_MS)
            }
        }
    }

    fun onAction(action: DrumEditorAction) {
        when (action) {
            is DrumEditorAction.ToggleStep -> toggleStep(action.clipId, action.patternId, action.stepIndex, action.drumNote)
            DrumEditorAction.ToggleSnap -> _state.update { it.copy(isSnapEnabled = !it.isSnapEnabled) }
            DrumEditorAction.CycleGridResolution -> cycleGridResolution()
            DrumEditorAction.Play -> audioEngine.play()
            DrumEditorAction.Pause -> audioEngine.pause()
            is DrumEditorAction.SeekTo -> audioEngine.seekTo(action.positionMs)
        }
    }

    private fun toggleStep(clipId: Long, patternId: Long, stepIndex: Int, drumNote: Int) {
        viewModelScope.launch {
            try {
                drumRepo.toggleStep(patternId, stepIndex, drumNote)
                // Flow observer handles UI update
            } catch (e: Exception) {
                _effects.emit(DrumEditorEffect.ShowError("Failed to toggle step"))
            }
        }
    }

    private fun cycleGridResolution() {
        val presets = listOf(4, 8, 16, 32)
        val current = _state.value.gridResolution
        val nextIndex = (presets.indexOf(current) + 1) % presets.size
        _state.update { it.copy(gridResolution = presets[nextIndex]) }
    }

    override fun onCleared() {
        super.onCleared()
        tickJob?.cancel()
        observeJobs.values.forEach { it.cancel() }
    }
}
