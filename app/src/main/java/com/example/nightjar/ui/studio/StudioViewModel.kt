package com.example.nightjar.ui.studio

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.nightjar.audio.AudioLatencyEstimator
import com.example.nightjar.audio.MusicalTimeConverter
import com.example.nightjar.audio.OboeAudioEngine
import com.example.nightjar.audio.SoundFontManager
import com.example.nightjar.audio.WavSplitter
import com.example.nightjar.data.db.entity.TakeEntity
import com.example.nightjar.data.db.entity.MidiNoteEntity
import com.example.nightjar.data.repository.DrumRepository
import com.example.nightjar.data.repository.MidiRepository
import com.example.nightjar.data.repository.StudioRepository
import com.example.nightjar.data.repository.IdeaRepository
import com.example.nightjar.data.storage.RecordingStorage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToLong
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * ViewModel for the Studio (multi-track workspace) screen.
 *
 * Coordinates overdub recording and multi-track playback via [OboeAudioEngine],
 * and timeline editing (drag-to-reposition, trim).
 *
 * Recording synchronization follows this protocol:
 * 1. Start Oboe recording and wait for the audio pipeline to become hot.
 * 2. Open write gate and start playback.
 * 3. Measure pre-roll for latency compensation.
 * 4. On stop, save the new track at the captured timeline offset.
 *
 * ## Arm + Record + Takes
 * Tracks can be "armed" via the drawer. The Record button in the button panel
 * starts recording on the armed track. When no tracks exist, Record creates
 * the first track automatically. Recordings are saved as takes on the armed
 * track. When loop is active during recording, the continuous recording is
 * split at loop boundaries into individual takes on stop.
 */
@HiltViewModel
class StudioViewModel @Inject constructor(
    private val ideaRepo: IdeaRepository,
    private val studioRepo: StudioRepository,
    private val drumRepo: DrumRepository,
    private val midiRepo: MidiRepository,
    private val audioEngine: OboeAudioEngine,
    private val recordingStorage: RecordingStorage,
    private val latencyEstimator: AudioLatencyEstimator,
    private val soundFontManager: SoundFontManager
) : ViewModel() {

    private var currentIdeaId: Long? = null

    companion object {
        private const val TAG = "StudioVM"
        private const val MIN_EFFECTIVE_DURATION_MS = 200L
        private const val MIN_LOOP_DURATION_MS = 500L
        private const val TICK_MS = 16L // ~60fps
    }

    private val _state = MutableStateFlow(StudioUiState())
    val state = _state.asStateFlow()

    private val _effects = MutableSharedFlow<StudioEffect>()
    val effects = _effects.asSharedFlow()

    private var recordingStartGlobalMs: Long = 0L
    private var recordingStartNanos: Long = 0L
    private var recordingTrimStartMs: Long = 0L
    private var recordingTickJob: Job? = null
    private var recordingFile: File? = null
    private var tickJob: Job? = null

    // Live waveform amplitude buffer (mirrors RecordViewModel pattern)
    private val amplitudeBuffer = ArrayList<Float>()

    // Loop recording: track loop reset timestamps for post-split
    private var loopResetTimestampsMs: MutableList<Long> = mutableListOf()
    private var lastLoopResetCount: Long = 0L
    private var isLoopRecording: Boolean = false
    // SoundFont loading (one-time)
    private var soundFontLoaded: Boolean = false
    // Active drum pattern observation jobs per track
    private val drumPatternJobs: MutableMap<Long, Job> = mutableMapOf()
    // Active MIDI note observation jobs per track
    private val midiNoteJobs: MutableMap<Long, Job> = mutableMapOf()
    // Preview channel for instrument audition
    private val previewChannel = 15
    private var previewNoteOffJob: Job? = null
    // Track ID that was armed when recording started (for saving takes)
    private var recordingArmedTrackId: Long? = null
    // Whether this is a first-track-creation recording (no armed track)
    private var isFirstTrackRecording: Boolean = false

    init {
        startTick()
    }

    /** Poll native engine state at ~60fps and update UI StateFlows. */
    private fun startTick() {
        tickJob?.cancel()
        tickJob = viewModelScope.launch {
            while (isActive) {
                audioEngine.pollState()
                val engineDuration = audioEngine.totalDurationMs.value
                val st = _state.value
                // Extend timeline width while recording so the waveform has room to grow
                val effectiveDuration = if (st.isRecording && st.recordingStartGlobalMs != null) {
                    maxOf(engineDuration, st.recordingStartGlobalMs + st.recordingElapsedMs)
                } else {
                    engineDuration
                }
                _state.update {
                    it.copy(
                        isPlaying = audioEngine.isPlaying.value,
                        globalPositionMs = audioEngine.positionMs.value,
                        totalDurationMs = effectiveDuration
                    )
                }

                // Track loop resets during recording for post-split
                if (isLoopRecording) {
                    val currentCount = audioEngine.getLoopResetCount()
                    if (currentCount > lastLoopResetCount) {
                        val elapsedMs = (System.nanoTime() - recordingStartNanos) / 1_000_000L
                        for (i in lastLoopResetCount until currentCount) {
                            loopResetTimestampsMs.add(elapsedMs)
                            Log.d(TAG, "Loop reset #${loopResetTimestampsMs.size} at ${elapsedMs}ms")
                        }
                        lastLoopResetCount = currentCount
                    }
                }

                delay(TICK_MS)
            }
        }
    }

    fun onAction(action: StudioAction) {
        when (action) {
            is StudioAction.Load -> load(action.ideaId)
            StudioAction.ShowAddTrackSheet -> {
                _state.update { it.copy(showAddTrackSheet = true) }
            }
            StudioAction.DismissAddTrackSheet -> {
                _state.update { it.copy(showAddTrackSheet = false) }
            }
            is StudioAction.SelectNewTrackType -> {
                _state.update { it.copy(showAddTrackSheet = false) }
                when (action.type) {
                    NewTrackType.AUDIO_RECORDING -> {
                        viewModelScope.launch {
                            _effects.emit(StudioEffect.RequestMicPermission)
                        }
                    }
                    NewTrackType.DRUM_SEQUENCER -> addDrumTrack()
                    NewTrackType.MIDI_INSTRUMENT -> addMidiTrack()
                }
            }
            StudioAction.MicPermissionGranted -> startRecordingAfterPermission()
            StudioAction.StopOverdubRecording -> stopRecording()
            StudioAction.Play -> audioEngine.play()
            StudioAction.Pause -> audioEngine.pause()
            is StudioAction.SeekTo -> audioEngine.seekTo(action.positionMs)
            is StudioAction.SeekFinished -> audioEngine.seekTo(action.positionMs)

            // Drag-to-reposition
            is StudioAction.StartDragTrack -> startDragTrack(action.trackId)
            is StudioAction.UpdateDragTrack -> updateDragTrack(action.previewOffsetMs)
            is StudioAction.FinishDragTrack -> finishDragTrack(action.trackId, action.newOffsetMs)
            StudioAction.CancelDrag -> cancelDrag()

            // Trim
            is StudioAction.StartTrim -> startTrim(action.trackId, action.edge)
            is StudioAction.UpdateTrim -> updateTrim(
                action.previewTrimStartMs,
                action.previewTrimEndMs
            )
            is StudioAction.FinishTrim -> finishTrim(
                action.trackId,
                action.trimStartMs,
                action.trimEndMs
            )
            StudioAction.CancelTrim -> cancelTrim()

            // Delete
            is StudioAction.ConfirmDeleteTrack -> {
                _state.update { it.copy(confirmingDeleteTrackId = action.trackId) }
            }
            StudioAction.DismissDeleteTrack -> {
                _state.update { it.copy(confirmingDeleteTrackId = null) }
            }
            StudioAction.ExecuteDeleteTrack -> executeDeleteTrack()

            // Track drawer (inline settings panel)
            is StudioAction.OpenTrackSettings -> {
                _state.update {
                    val newSet = if (action.trackId in it.expandedTrackIds) {
                        it.expandedTrackIds - action.trackId
                    } else {
                        it.expandedTrackIds + action.trackId
                    }
                    it.copy(expandedTrackIds = newSet)
                }
            }
            is StudioAction.SetTrackMuted -> setTrackMuted(action.trackId, action.muted)
            is StudioAction.SetTrackVolume -> setTrackVolume(action.trackId, action.volume)
            is StudioAction.ToggleSolo -> toggleSolo(action.trackId)

            // Loop
            is StudioAction.SetLoopRegion -> setLoopRegion(action.startMs, action.endMs)
            StudioAction.ClearLoopRegion -> clearLoopRegion()
            StudioAction.ToggleLoop -> toggleLoop()
            is StudioAction.UpdateLoopRegionStart -> updateLoopRegionStart(action.startMs)
            is StudioAction.UpdateLoopRegionEnd -> updateLoopRegionEnd(action.endMs)

            // Latency setup
            StudioAction.ShowLatencySetup -> {
                val diagnostics = latencyEstimator.getDiagnostics()
                _state.update {
                    it.copy(
                        showLatencySetupDialog = true,
                        latencyDiagnostics = diagnostics,
                        manualOffsetMs = diagnostics.manualOffsetMs
                    )
                }
            }
            StudioAction.DismissLatencySetup -> {
                _state.update { it.copy(showLatencySetupDialog = false) }
            }
            is StudioAction.SetManualOffset -> {
                latencyEstimator.saveManualOffsetMs(action.offsetMs)
                _state.update { it.copy(manualOffsetMs = action.offsetMs) }
            }
            StudioAction.ClearManualOffset -> {
                latencyEstimator.clearManualOffset()
                _state.update { it.copy(manualOffsetMs = 0L) }
            }

            // Arm / Record
            is StudioAction.ToggleArm -> toggleArm(action.trackId)
            StudioAction.StartRecording -> requestRecording()
            StudioAction.StopRecording -> stopRecording()

            // Track rename
            is StudioAction.RequestRenameTrack -> {
                _state.update {
                    it.copy(
                        renamingTrackId = action.trackId,
                        renamingTrackCurrentName = action.currentName
                    )
                }
            }
            is StudioAction.ConfirmRenameTrack -> confirmRenameTrack(action.trackId, action.newName)
            StudioAction.DismissRenameTrack -> {
                _state.update { it.copy(renamingTrackId = null, renamingTrackCurrentName = "") }
            }

            // Takes
            is StudioAction.ToggleTakesView -> toggleTakesView(action.trackId)
            is StudioAction.RenameTake -> renameTake(action.takeId, action.name)
            is StudioAction.DeleteTake -> deleteTake(action.takeId, action.trackId)
            is StudioAction.SetTakeMuted -> setTakeMuted(action.takeId, action.trackId, action.muted)
            is StudioAction.DragTake -> dragTake(action.takeId, action.newOffsetMs)

            // Take drawer / rename / delete
            is StudioAction.ToggleTakeDrawer -> {
                _state.update {
                    val newSet = if (action.takeId in it.expandedTakeDrawerIds) {
                        it.expandedTakeDrawerIds - action.takeId
                    } else {
                        it.expandedTakeDrawerIds + action.takeId
                    }
                    it.copy(expandedTakeDrawerIds = newSet)
                }
            }
            is StudioAction.RequestRenameTake -> {
                _state.update {
                    it.copy(
                        renamingTakeId = action.takeId,
                        renamingTakeTrackId = action.trackId,
                        renamingTakeCurrentName = action.currentName
                    )
                }
            }
            is StudioAction.ConfirmRenameTake -> confirmRenameTake(action.takeId, action.newName)
            StudioAction.DismissRenameTake -> {
                _state.update {
                    it.copy(
                        renamingTakeId = null,
                        renamingTakeTrackId = null,
                        renamingTakeCurrentName = ""
                    )
                }
            }
            is StudioAction.RequestDeleteTake -> {
                _state.update {
                    it.copy(
                        confirmingDeleteTakeId = action.takeId,
                        confirmingDeleteTakeTrackId = action.trackId
                    )
                }
            }
            StudioAction.DismissDeleteTake -> {
                _state.update {
                    it.copy(confirmingDeleteTakeId = null, confirmingDeleteTakeTrackId = null)
                }
            }
            StudioAction.ExecuteDeleteTake -> executeDeleteTake()

            // Time signature / Snap
            is StudioAction.SetTimeSignature -> setTimeSignature(
                action.numerator, action.denominator
            )
            StudioAction.ToggleSnap -> {
                _state.update { it.copy(isSnapEnabled = !it.isSnapEnabled) }
            }

            // Drum sequencer
            is StudioAction.ToggleDrumStep -> toggleDrumStep(
                action.trackId, action.stepIndex, action.drumNote
            )
            is StudioAction.SetBpm -> setBpm(action.bpm)
            is StudioAction.SetPatternBars -> setPatternBars(action.trackId, action.bars)

            // Drum clips
            is StudioAction.DuplicateClip -> duplicateClip(action.trackId, action.clipId)
            is StudioAction.MoveClip -> moveClip(action.trackId, action.clipId, action.newOffsetMs)
            is StudioAction.DeleteClip -> deleteClip(action.trackId, action.clipId)

            // Drum clip drag-to-reposition
            is StudioAction.StartDragClip -> startDragClip(action.trackId, action.clipId)
            is StudioAction.UpdateDragClip -> updateDragClip(action.previewOffsetMs)
            is StudioAction.FinishDragClip -> finishDragClip(action.trackId, action.clipId, action.newOffsetMs)
            StudioAction.CancelDragClip -> cancelDragClip()

            // MIDI instrument tracks
            is StudioAction.OpenPianoRoll -> {
                viewModelScope.launch {
                    _effects.emit(StudioEffect.NavigateToPianoRoll(action.trackId))
                }
            }
            is StudioAction.ShowInstrumentPicker -> {
                _state.update { it.copy(showInstrumentPickerForTrackId = action.trackId) }
            }
            StudioAction.DismissInstrumentPicker -> {
                _state.update { it.copy(showInstrumentPickerForTrackId = null) }
            }
            is StudioAction.SetMidiInstrument -> setMidiInstrument(action.trackId, action.program)
            is StudioAction.PreviewInstrument -> previewInstrument(action.program)
        }
    }

    private fun load(ideaId: Long) {
        if (currentIdeaId == ideaId) return
        currentIdeaId = ideaId

        viewModelScope.launch {
            try {
                val idea = ideaRepo.getIdeaById(ideaId)
                if (idea == null) {
                    _state.update {
                        it.copy(isLoading = false, errorMessage = "Idea not found.")
                    }
                    return@launch
                }

                val tracks = studioRepo.ensureProjectInitialized(ideaId)

                _state.update {
                    it.copy(
                        ideaTitle = idea.title,
                        tracks = tracks,
                        isLoading = false,
                        errorMessage = null,
                        bpm = idea.bpm,
                        timeSignatureNumerator = idea.timeSignatureNumerator,
                        timeSignatureDenominator = idea.timeSignatureDenominator
                    )
                }

                // Set project BPM in the native engine
                audioEngine.setBpm(idea.bpm)

                // Load takes for all audio tracks so the engine can load them correctly
                val audioTrackIds = tracks.filter { it.isAudio }.map { it.id }
                if (audioTrackIds.isNotEmpty()) {
                    val allTakes = studioRepo.getTakesForTracks(audioTrackIds)
                    val grouped = allTakes.groupBy { it.trackId }
                    _state.update { it.copy(trackTakes = grouped) }
                }

                loadTracksIntoEngine(tracks)

                // Load drum patterns for any drum tracks
                loadDrumPatterns(tracks)

                // Load MIDI tracks
                loadMidiTracks(tracks)
            } catch (e: Exception) {
                val msg = e.message ?: "Failed to load project."
                _state.update { it.copy(isLoading = false, errorMessage = msg) }
                _effects.emit(StudioEffect.ShowError(msg))
            }
        }
    }

    /** Compute the engine track ID for a take within a track. */
    private fun engineIdForTake(trackId: Long, takeSortIndex: Int): Int =
        (trackId * 1000 + takeSortIndex).toInt()

    /**
     * Load tracks into the native engine. If a track has takes in [trackTakes],
     * load each unmuted take as a separate engine track. Otherwise load the
     * track's own audio directly (backwards compatible).
     */
    private fun loadTracksIntoEngine(
        tracks: List<com.example.nightjar.data.db.entity.TrackEntity>
    ) {
        audioEngine.removeAllTracks()
        val takesMap = _state.value.trackTakes

        for (track in tracks) {
            // Drum tracks are handled by the synth engine, not the WAV mixer
            if (!track.isAudio) continue

            val takes = takesMap[track.id]
            if (takes != null && takes.isNotEmpty()) {
                // Load each unmuted take as a separate engine track
                for (take in takes) {
                    val effectivelyMuted = take.isMuted || track.isMuted
                    val file = getAudioFile(take.audioFileName)
                    val engineId = engineIdForTake(track.id, take.sortIndex)
                    audioEngine.addTrack(
                        trackId = engineId,
                        filePath = file.absolutePath,
                        durationMs = take.durationMs,
                        offsetMs = take.offsetMs,
                        trimStartMs = take.trimStartMs,
                        trimEndMs = take.trimEndMs,
                        volume = take.volume * track.volume,
                        isMuted = effectivelyMuted
                    )
                }
            } else if (track.audioFileName != null) {
                // No takes -- load track audio directly
                val file = getAudioFile(track.audioFileName)
                audioEngine.addTrack(
                    trackId = track.id.toInt(),
                    filePath = file.absolutePath,
                    durationMs = track.durationMs,
                    offsetMs = track.offsetMs,
                    trimStartMs = track.trimStartMs,
                    trimEndMs = track.trimEndMs,
                    volume = track.volume,
                    isMuted = track.isMuted
                )
            }
        }
    }

    // ── Arm ────────────────────────────────────────────────────────────────

    private fun toggleArm(trackId: Long) {
        // Only audio tracks can be armed (not drum or MIDI)
        val track = _state.value.tracks.find { it.id == trackId } ?: return
        if (!track.isAudio) return

        viewModelScope.launch {
            val currentArmed = _state.value.armedTrackId
            if (currentArmed == trackId) {
                // Unarm
                _state.update { it.copy(armedTrackId = null) }
            } else {
                // Arm this track -- lazy promote to ensure it has takes
                val takes = studioRepo.ensureTrackHasTakes(trackId)
                _state.update {
                    it.copy(
                        armedTrackId = trackId,
                        trackTakes = it.trackTakes + (trackId to takes)
                    )
                }
            }
        }
    }

    // ── Record button ──────────────────────────────────────────────────────

    /**
     * Called when the user taps the Record button.
     * - If already recording, stops.
     * - If a track is armed, records a take into that track.
     * - Otherwise (no tracks, or tracks but none armed), creates a new track.
     */
    private fun requestRecording() {
        val st = _state.value
        if (st.isRecording) {
            stopRecording()
            return
        }

        if (st.armedTrackId != null) {
            // Armed track exists -- record a take on it
            isFirstTrackRecording = false
            recordingArmedTrackId = st.armedTrackId
        } else {
            // No armed track -- create a new track (works whether tracks list
            // is empty or not; the "first track" path handles new-track creation)
            isFirstTrackRecording = true
            recordingArmedTrackId = null
        }

        viewModelScope.launch {
            _effects.emit(StudioEffect.RequestMicPermission)
        }
    }

    /**
     * Called after mic permission is granted. Starts the actual recording.
     * Handles both new-track creation (no armed track) and armed-track take recording.
     */
    private fun startRecordingAfterPermission() {
        val ideaId = currentIdeaId ?: return
        val st = _state.value

        // Determine if loop recording should be active
        isLoopRecording = st.isLoopEnabled && st.hasLoopRegion
        loopResetTimestampsMs.clear()
        lastLoopResetCount = audioEngine.getLoopResetCount()

        // Keep loop enabled during recording (no longer disable it)

        viewModelScope.launch {
            try {
                val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val file = recordingStorage.getAudioFile("nightjar_overdub_$ts.wav")
                recordingFile = file

                val intendedStartMs = audioEngine.positionMs.value

                // Pre-roll capture protocol
                val started = audioEngine.startRecording(file.absolutePath)
                if (!started) {
                    _effects.emit(StudioEffect.ShowError("Failed to start recording."))
                    return@launch
                }
                audioEngine.awaitFirstBuffer()

                audioEngine.openWriteGate()
                val writeGateNanos = System.nanoTime()

                val hasPlayableTracks = st.tracks.any { !it.isMuted }
                audioEngine.setRecording(true)
                audioEngine.play()

                val preRollMs = (System.nanoTime() - writeGateNanos) / 1_000_000L
                val compensation = latencyEstimator.computeCompensationMs(
                    preRollMs = preRollMs,
                    hasPlayableTracks = hasPlayableTracks
                )

                recordingStartGlobalMs = intendedStartMs
                recordingTrimStartMs = compensation
                recordingStartNanos = System.nanoTime()

                Log.d(TAG, "Recording started: intendedStart=${intendedStartMs}ms, " +
                    "preRoll=${preRollMs}ms, compensation=${compensation}ms, " +
                    "hasPlayableTracks=$hasPlayableTracks, " +
                    "isLoopRecording=$isLoopRecording, " +
                    "armedTrackId=$recordingArmedTrackId, " +
                    "isFirstTrack=$isFirstTrackRecording")

                amplitudeBuffer.clear()
                _state.update {
                    it.copy(
                        isRecording = true,
                        recordingElapsedMs = 0L,
                        liveAmplitudes = FloatArray(0),
                        recordingStartGlobalMs = intendedStartMs,
                        recordingTargetTrackId = recordingArmedTrackId
                    )
                }

                // Single tick job for both elapsed time and amplitude polling (~60fps)
                recordingTickJob = viewModelScope.launch {
                    while (true) {
                        delay(TICK_MS)
                        val elapsed = (System.nanoTime() - recordingStartNanos) / 1_000_000L
                        amplitudeBuffer.add(audioEngine.getLatestPeakAmplitude())
                        _state.update {
                            it.copy(
                                recordingElapsedMs = elapsed,
                                liveAmplitudes = amplitudeBuffer.toFloatArray()
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                _effects.emit(
                    StudioEffect.ShowError(e.message ?: "Failed to start recording.")
                )
            }
        }
    }

    /**
     * Stop recording and save the result. Handles three cases:
     * 1. First track creation (no armed track)
     * 2. Simple take recording (armed track, no loop)
     * 3. Loop recording with post-split (armed track + loop)
     */
    private fun stopRecording() {
        val ideaId = currentIdeaId ?: return

        audioEngine.setRecording(false)
        audioEngine.pause()
        recordingTickJob?.cancel()
        recordingTickJob = null

        val durationMs = audioEngine.stopRecording()
        val file = recordingFile
        recordingFile = null

        val wasLoopRecording = isLoopRecording
        val loopResets = loopResetTimestampsMs.toList()
        val armedId = recordingArmedTrackId
        val wasFirstTrack = isFirstTrackRecording

        // Reset recording state
        isLoopRecording = false
        loopResetTimestampsMs.clear()
        recordingArmedTrackId = null
        isFirstTrackRecording = false

        _state.update {
            it.copy(
                isRecording = false,
                recordingElapsedMs = 0L,
                liveAmplitudes = FloatArray(0),
                recordingStartGlobalMs = null,
                recordingTargetTrackId = null
            )
        }

        if (durationMs <= 0 || file == null) {
            viewModelScope.launch {
                _effects.emit(StudioEffect.ShowError("Recording failed -- no audio captured."))
            }
            return
        }

        val maxTrim = (durationMs - MIN_EFFECTIVE_DURATION_MS).coerceAtLeast(0L)
        val safeTrimStartMs = recordingTrimStartMs.coerceIn(0L, maxTrim)

        Log.d(TAG, "Recording stopped: file=${file.name}, " +
            "durationMs=$durationMs, offsetMs=$recordingStartGlobalMs, " +
            "trimStartMs=$safeTrimStartMs (raw=${recordingTrimStartMs}), " +
            "loopResets=${loopResets.size}, wasFirstTrack=$wasFirstTrack")

        viewModelScope.launch {
            try {
                if (wasFirstTrack) {
                    // Case 1: Create a new track with the recording
                    studioRepo.addTrack(
                        ideaId = ideaId,
                        audioFile = file,
                        durationMs = durationMs,
                        offsetMs = recordingStartGlobalMs,
                        trimStartMs = safeTrimStartMs
                    )
                } else if (armedId != null && wasLoopRecording && loopResets.isNotEmpty()) {
                    // Case 3: Loop recording -- split into takes
                    saveLoopRecordingAsTakes(
                        armedTrackId = armedId,
                        file = file,
                        totalDurationMs = durationMs,
                        trimStartMs = safeTrimStartMs,
                        loopResetTimestampsMs = loopResets
                    )
                } else if (armedId != null) {
                    // Case 2: Simple take recording
                    studioRepo.addTake(
                        trackId = armedId,
                        audioFile = file,
                        durationMs = durationMs,
                        offsetMs = recordingStartGlobalMs,
                        trimStartMs = safeTrimStartMs
                    )
                    reloadTakesForTrack(armedId)
                }

                reloadAndPrepare()
            } catch (e: Exception) {
                _effects.emit(
                    StudioEffect.ShowError(e.message ?: "Failed to save recording.")
                )
            }
        }
    }

    /**
     * Split a continuous loop recording into individual takes.
     * The recording file is split at the loop reset timestamps,
     * creating one take per loop cycle.
     */
    private suspend fun saveLoopRecordingAsTakes(
        armedTrackId: Long,
        file: File,
        totalDurationMs: Long,
        trimStartMs: Long,
        loopResetTimestampsMs: List<Long>
    ) {
        val st = _state.value
        val loopStartMs = st.loopStartMs ?: recordingStartGlobalMs
        val outputDir = file.parentFile ?: return

        // Split the WAV file at loop reset points
        val splitFiles = withContext(Dispatchers.IO) {
            WavSplitter.split(
                sourceFile = file,
                splitPointsMs = loopResetTimestampsMs,
                outputDir = outputDir,
                namePrefix = file.nameWithoutExtension + "_take"
            )
        }

        if (splitFiles.isEmpty()) {
            // Split failed or only one segment -- save as a single take
            Log.w(TAG, "WAV split returned no files, saving as single take")
            studioRepo.addTake(
                trackId = armedTrackId,
                audioFile = file,
                durationMs = totalDurationMs,
                offsetMs = recordingStartGlobalMs,
                trimStartMs = trimStartMs
            )
        } else {
            // Save each segment as a separate take
            for ((index, segFile) in splitFiles.withIndex()) {
                val segDurationMs = getFileDurationMs(segFile)
                val segOffsetMs = if (index == 0) {
                    // First segment starts at the original recording position
                    recordingStartGlobalMs
                } else {
                    // Subsequent segments start at loop region start
                    loopStartMs
                }
                val segTrimStart = if (index == 0) trimStartMs else 0L

                studioRepo.addTake(
                    trackId = armedTrackId,
                    audioFile = segFile,
                    durationMs = segDurationMs,
                    offsetMs = segOffsetMs,
                    trimStartMs = segTrimStart
                )
            }

            // Delete the original unsplit file
            withContext(Dispatchers.IO) {
                file.delete()
            }
        }

        reloadTakesForTrack(armedTrackId)
    }

    /** Get the duration of a WAV file in ms from its header. */
    private fun getFileDurationMs(file: File): Long {
        if (!file.exists() || file.length() < 44) return 0L
        // For PCM WAV at 44100Hz, 16-bit mono: bytesPerMs = 44100 * 2 / 1000 = 88.2
        // Read from header for accuracy
        val dataSize = file.length() - 44
        val bytesPerSample = 2 // 16-bit
        val channels = 1 // mono
        val sampleRate = 44100
        val bytesPerMs = (sampleRate.toLong() * bytesPerSample * channels) / 1000L
        return if (bytesPerMs > 0) dataSize / bytesPerMs else 0L
    }

    // ── Takes ──────────────────────────────────────────────────────────────

    private fun toggleTakesView(trackId: Long) {
        viewModelScope.launch {
            // Ensure takes are loaded
            if (trackId !in _state.value.trackTakes) {
                val takes = studioRepo.ensureTrackHasTakes(trackId)
                _state.update {
                    it.copy(trackTakes = it.trackTakes + (trackId to takes))
                }
            }

            _state.update {
                val newSet = if (trackId in it.expandedTakeTrackIds) {
                    it.expandedTakeTrackIds - trackId
                } else {
                    it.expandedTakeTrackIds + trackId
                }
                it.copy(expandedTakeTrackIds = newSet)
            }
        }
    }

    private fun renameTake(takeId: Long, name: String) {
        viewModelScope.launch {
            studioRepo.renameTake(takeId, name)
            reloadAllTakes()
        }
    }

    private fun confirmRenameTake(takeId: Long, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isBlank()) return
        _state.update {
            it.copy(
                renamingTakeId = null,
                renamingTakeTrackId = null,
                renamingTakeCurrentName = ""
            )
        }
        viewModelScope.launch {
            try {
                studioRepo.renameTake(takeId, trimmed)
                reloadAllTakes()
            } catch (e: Exception) {
                _effects.emit(StudioEffect.ShowError(e.message ?: "Failed to rename take."))
            }
        }
    }

    private fun deleteTake(takeId: Long, trackId: Long) {
        viewModelScope.launch {
            try {
                studioRepo.deleteTakeAndAudio(takeId)
                _state.update {
                    it.copy(expandedTakeDrawerIds = it.expandedTakeDrawerIds - takeId)
                }
                reloadTakesForTrack(trackId)
                reloadAndPrepare()
            } catch (e: Exception) {
                _effects.emit(StudioEffect.ShowError(e.message ?: "Failed to delete take."))
            }
        }
    }

    private fun executeDeleteTake() {
        val takeId = _state.value.confirmingDeleteTakeId ?: return
        val trackId = _state.value.confirmingDeleteTakeTrackId ?: return
        _state.update {
            it.copy(
                confirmingDeleteTakeId = null,
                confirmingDeleteTakeTrackId = null,
                expandedTakeDrawerIds = it.expandedTakeDrawerIds - takeId
            )
        }
        viewModelScope.launch {
            try {
                studioRepo.deleteTakeAndAudio(takeId)
                reloadTakesForTrack(trackId)
                reloadAndPrepare()
            } catch (e: Exception) {
                _effects.emit(StudioEffect.ShowError(e.message ?: "Failed to delete take."))
            }
        }
    }

    private fun setTakeMuted(takeId: Long, trackId: Long, muted: Boolean) {
        viewModelScope.launch {
            try {
                studioRepo.setTakeMuted(takeId, muted)
                reloadTakesForTrack(trackId)
                reloadAndPrepare()
            } catch (e: Exception) {
                _effects.emit(StudioEffect.ShowError(e.message ?: "Failed to update take."))
            }
        }
    }

    private fun dragTake(takeId: Long, newOffsetMs: Long) {
        viewModelScope.launch {
            try {
                val snapped = snapIfEnabled(newOffsetMs).coerceAtLeast(0L)
                studioRepo.moveTake(takeId, snapped)
                reloadAllTakes()
                reloadAndPrepare()
            } catch (e: Exception) {
                _effects.emit(StudioEffect.ShowError(e.message ?: "Failed to move take."))
            }
        }
    }

    private suspend fun reloadTakesForTrack(trackId: Long) {
        val takes = studioRepo.getTakesForTrack(trackId)
        _state.update {
            it.copy(trackTakes = it.trackTakes + (trackId to takes))
        }
    }

    private suspend fun reloadAllTakes() {
        val trackIds = _state.value.trackTakes.keys.toList()
        if (trackIds.isEmpty()) return
        val allTakes = studioRepo.getTakesForTracks(trackIds)
        val grouped = allTakes.groupBy { it.trackId }
        _state.update {
            it.copy(trackTakes = it.trackTakes + grouped)
        }
    }

    // ── Track rename ─────────────────────────────────────────────────────

    private fun confirmRenameTrack(trackId: Long, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isBlank()) return
        _state.update { it.copy(renamingTrackId = null, renamingTrackCurrentName = "") }
        viewModelScope.launch {
            try {
                studioRepo.renameTrack(trackId, trimmed)
                reloadTracks()
            } catch (e: Exception) {
                _effects.emit(StudioEffect.ShowError(e.message ?: "Failed to rename track."))
            }
        }
    }

    // ── Snap helper ─────────────────────────────────────────────────────

    /** Snap a ms value to the nearest beat if snap is enabled. */
    private fun snapIfEnabled(ms: Long): Long {
        val st = _state.value
        if (!st.isSnapEnabled) return ms
        return MusicalTimeConverter.snapToBeat(ms, st.bpm, st.timeSignatureDenominator)
    }

    // ── Drag-to-reposition ────────────────────────────────────────────────

    private fun startDragTrack(trackId: Long) {
        val track = _state.value.tracks.find { it.id == trackId } ?: return
        _state.update {
            it.copy(
                dragState = TrackDragState(
                    trackId = trackId,
                    originalOffsetMs = track.offsetMs,
                    previewOffsetMs = track.offsetMs
                )
            )
        }
    }

    private fun updateDragTrack(previewOffsetMs: Long) {
        _state.update { st ->
            st.dragState?.let { drag ->
                val snapped = snapIfEnabled(previewOffsetMs).coerceAtLeast(0L)
                st.copy(dragState = drag.copy(previewOffsetMs = snapped))
            } ?: st
        }
    }

    private fun finishDragTrack(trackId: Long, newOffsetMs: Long) {
        _state.update { it.copy(dragState = null) }
        val snapped = snapIfEnabled(newOffsetMs).coerceAtLeast(0L)
        viewModelScope.launch {
            studioRepo.moveTrackWithTakes(trackId, snapped)
            reloadAndPrepare()
        }
    }

    private fun cancelDrag() {
        _state.update { it.copy(dragState = null) }
    }

    // ── Trim ─────────────────────────────────────────────────────────────

    private fun startTrim(trackId: Long, edge: TrimEdge) {
        val track = _state.value.tracks.find { it.id == trackId } ?: return
        _state.update {
            it.copy(
                trimState = TrackTrimState(
                    trackId = trackId,
                    edge = edge,
                    originalTrimStartMs = track.trimStartMs,
                    originalTrimEndMs = track.trimEndMs,
                    previewTrimStartMs = track.trimStartMs,
                    previewTrimEndMs = track.trimEndMs
                )
            )
        }
    }

    private fun updateTrim(previewTrimStartMs: Long, previewTrimEndMs: Long) {
        _state.update { st ->
            st.trimState?.let { trim ->
                val track = st.tracks.find { it.id == trim.trackId } ?: return@let st
                val maxTrimStart = track.durationMs - previewTrimEndMs - MIN_EFFECTIVE_DURATION_MS
                val maxTrimEnd = track.durationMs - previewTrimStartMs - MIN_EFFECTIVE_DURATION_MS

                val clampedStart = previewTrimStartMs.coerceIn(0L, maxTrimStart.coerceAtLeast(0L))
                val clampedEnd = previewTrimEndMs.coerceIn(0L, maxTrimEnd.coerceAtLeast(0L))

                val snappedStart = snapIfEnabled(clampedStart)
                val snappedEnd = snapIfEnabled(clampedEnd)

                st.copy(
                    trimState = trim.copy(
                        previewTrimStartMs = snappedStart,
                        previewTrimEndMs = snappedEnd
                    )
                )
            } ?: st
        }
    }

    private fun finishTrim(trackId: Long, trimStartMs: Long, trimEndMs: Long) {
        val track = _state.value.tracks.firstOrNull { it.id == trackId }
        _state.update { it.copy(trimState = null) }
        val snappedStart = snapIfEnabled(trimStartMs)
        val snappedEnd = snapIfEnabled(trimEndMs)
        viewModelScope.launch {
            if (track != null && snappedStart != track.trimStartMs) {
                val offsetDelta = snappedStart - track.trimStartMs
                val newOffsetMs = (track.offsetMs + offsetDelta).coerceAtLeast(0L)
                studioRepo.moveTrack(trackId, newOffsetMs)
            }
            studioRepo.trimTrack(trackId, snappedStart, snappedEnd)
            reloadAndPrepare()
        }
    }

    private fun cancelTrim() {
        _state.update { it.copy(trimState = null) }
    }

    // ── Track settings ─────────────────────────────────────────────────

    private fun setTrackMuted(trackId: Long, muted: Boolean) {
        viewModelScope.launch {
            try {
                studioRepo.setTrackMuted(trackId, muted)
                reloadTracks()
                reapplyEffectiveMute()
            } catch (e: Exception) {
                _effects.emit(StudioEffect.ShowError(e.message ?: "Failed to update track."))
            }
        }
    }

    private fun toggleSolo(trackId: Long) {
        _state.update { st ->
            val newSet = if (trackId in st.soloedTrackIds) {
                emptySet()
            } else {
                setOf(trackId)
            }
            st.copy(soloedTrackIds = newSet)
        }
        reapplyEffectiveMute()
    }

    private fun reapplyEffectiveMute() {
        val st = _state.value
        val anySoloed = st.soloedTrackIds.isNotEmpty()
        for (track in st.tracks) {
            val trackMuted = track.isMuted ||
                (anySoloed && track.id !in st.soloedTrackIds)
            if (track.isAudio) {
                val takes = st.trackTakes[track.id]
                if (takes != null && takes.isNotEmpty()) {
                    for (take in takes) {
                        val engineId = engineIdForTake(track.id, take.sortIndex)
                        audioEngine.setTrackMuted(engineId, trackMuted || take.isMuted)
                    }
                } else {
                    audioEngine.setTrackMuted(track.id.toInt(), trackMuted)
                }
            } else if (track.isDrum) {
                // Re-push pattern with updated mute state
                val pattern = st.drumPatterns[track.id] ?: continue
                pushDrumPatternToEngine(
                    track.copy(isMuted = trackMuted),
                    pattern.stepsPerBar,
                    pattern.bars,
                    pattern.steps,
                    pattern.clips.map { it.offsetMs }
                )
            }
        }
        // Re-push all MIDI tracks with effective mute state (bulk operation)
        if (st.tracks.any { it.isMidi }) {
            pushAllMidiToEngine()
        }
    }

    private fun setTrackVolume(trackId: Long, volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        val track = _state.value.tracks.find { it.id == trackId }
        if (track != null && track.isDrum) {
            // Re-push pattern with updated volume
            val pattern = _state.value.drumPatterns[trackId]
            if (pattern != null) {
                pushDrumPatternToEngine(
                    track.copy(volume = clamped),
                    pattern.stepsPerBar, pattern.bars, pattern.steps,
                    pattern.clips.map { it.offsetMs }
                )
            }
        } else {
            // Update native engine immediately for instant feedback
            val takes = _state.value.trackTakes[trackId]
            if (takes != null && takes.isNotEmpty()) {
                for (take in takes) {
                    val engineId = engineIdForTake(trackId, take.sortIndex)
                    audioEngine.setTrackVolume(engineId, take.volume * clamped)
                }
            } else {
                audioEngine.setTrackVolume(trackId.toInt(), clamped)
            }
        }
        viewModelScope.launch {
            try {
                studioRepo.setTrackVolume(trackId, clamped)
                reloadTracks()
            } catch (e: Exception) {
                _effects.emit(StudioEffect.ShowError(e.message ?: "Failed to update volume."))
            }
        }
    }

    // ── Delete ─────────────────────────────────────────────────────────

    private fun executeDeleteTrack() {
        val trackId = _state.value.confirmingDeleteTrackId ?: return
        _state.update {
            it.copy(
                confirmingDeleteTrackId = null,
                expandedTrackIds = it.expandedTrackIds - trackId,
                soloedTrackIds = it.soloedTrackIds - trackId,
                armedTrackId = if (it.armedTrackId == trackId) null else it.armedTrackId,
                trackTakes = it.trackTakes - trackId,
                expandedTakeTrackIds = it.expandedTakeTrackIds - trackId,
                drumPatterns = it.drumPatterns - trackId
            )
        }

        // Clean up drum observation if this is a drum track
        drumPatternJobs[trackId]?.cancel()
        drumPatternJobs.remove(trackId)

        viewModelScope.launch {
            try {
                audioEngine.pause()
                studioRepo.deleteTrackAndAudio(trackId)
                reloadAndPrepare()
                reapplyEffectiveMute()

                // Disable sequencer if no drum tracks remain
                val hasDrumTracks = _state.value.tracks.any { it.isDrum }
                if (!hasDrumTracks) {
                    audioEngine.setDrumSequencerEnabled(false)
                }
            } catch (e: Exception) {
                _effects.emit(
                    StudioEffect.ShowError(e.message ?: "Failed to delete track.")
                )
            }
        }
    }

    // ── Loop ───────────────────────────────────────────────────────────

    private fun setLoopRegion(startMs: Long, endMs: Long) {
        val total = _state.value.totalDurationMs
        val cStart = snapIfEnabled(startMs).coerceIn(0L, total)
        val cEnd = snapIfEnabled(endMs).coerceIn(0L, total)
        if (cEnd - cStart < MIN_LOOP_DURATION_MS) return
        _state.update { it.copy(loopStartMs = cStart, loopEndMs = cEnd, isLoopEnabled = true) }
        audioEngine.setLoopRegion(cStart, cEnd)
    }

    private fun clearLoopRegion() {
        _state.update { it.copy(loopStartMs = null, loopEndMs = null, isLoopEnabled = false) }
        audioEngine.clearLoopRegion()
    }

    private fun toggleLoop() {
        val current = _state.value
        if (current.isLoopEnabled) {
            _state.update { it.copy(isLoopEnabled = false) }
            audioEngine.clearLoopRegion()
        } else {
            val start = current.loopStartMs ?: 0L
            val end = current.loopEndMs ?: current.totalDurationMs
            if (end - start < MIN_LOOP_DURATION_MS) return
            _state.update { it.copy(loopStartMs = start, loopEndMs = end, isLoopEnabled = true) }
            audioEngine.setLoopRegion(start, end)
        }
    }

    private fun updateLoopRegionStart(startMs: Long) {
        val current = _state.value
        val end = current.loopEndMs ?: return
        val snapped = snapIfEnabled(startMs)
        val clamped = snapped.coerceIn(0L, (end - MIN_LOOP_DURATION_MS).coerceAtLeast(0L))
        _state.update { it.copy(loopStartMs = clamped) }
        if (current.isLoopEnabled) {
            audioEngine.setLoopRegion(clamped, end)
        }
    }

    private fun updateLoopRegionEnd(endMs: Long) {
        val current = _state.value
        val start = current.loopStartMs ?: return
        val total = current.totalDurationMs
        val snapped = snapIfEnabled(endMs)
        val clamped = snapped.coerceIn(start + MIN_LOOP_DURATION_MS, total)
        _state.update { it.copy(loopEndMs = clamped) }
        if (current.isLoopEnabled) {
            audioEngine.setLoopRegion(start, clamped)
        }
    }

    // ── Drum sequencer ─────────────────────────────────────────────────

    /** Load SoundFont if not already loaded. Returns true on success. */
    private suspend fun ensureSoundFontLoaded(): Boolean {
        if (soundFontLoaded) return true
        val path = soundFontManager.getSoundFontPath()
        if (path == null) {
            _effects.emit(StudioEffect.ShowError("Failed to load SoundFont."))
            return false
        }
        val ok = audioEngine.loadSoundFont(path)
        if (!ok) {
            _effects.emit(StudioEffect.ShowError("Failed to initialize synth."))
            return false
        }
        soundFontLoaded = true
        Log.d(TAG, "SoundFont loaded successfully")
        return true
    }

    /** Create a new drum track, initialize its pattern, and observe it. */
    private fun addDrumTrack() {
        val ideaId = currentIdeaId ?: return
        viewModelScope.launch {
            try {
                if (!ensureSoundFontLoaded()) return@launch

                val trackId = studioRepo.addDrumTrack(ideaId)
                val pattern = drumRepo.ensurePatternExists(trackId)
                val clips = drumRepo.ensureClipsExist(pattern.id)
                val clipUiStates = clips.map { DrumClipUiState(clipId = it.id, offsetMs = it.offsetMs) }

                _state.update {
                    it.copy(
                        drumPatterns = it.drumPatterns + (trackId to DrumPatternUiState(
                            patternId = pattern.id,
                            stepsPerBar = pattern.stepsPerBar,
                            bars = pattern.bars,
                            clips = clipUiStates
                        ))
                    )
                }

                audioEngine.setDrumSequencerEnabled(true)
                observeDrumPattern(trackId, pattern.id)
                reloadAndPrepare()
            } catch (e: Exception) {
                _effects.emit(
                    StudioEffect.ShowError(e.message ?: "Failed to add drum track.")
                )
            }
        }
    }

    /**
     * Load drum patterns for all drum tracks. Called once during [load].
     * Starts Flow observation for each pattern so step changes push to the engine.
     */
    private suspend fun loadDrumPatterns(
        tracks: List<com.example.nightjar.data.db.entity.TrackEntity>
    ) {
        val drumTracks = tracks.filter { it.isDrum }
        if (drumTracks.isEmpty()) return

        if (!ensureSoundFontLoaded()) return

        val patterns = mutableMapOf<Long, DrumPatternUiState>()
        for (track in drumTracks) {
            val pattern = drumRepo.ensurePatternExists(track.id)
            val steps = drumRepo.getSteps(pattern.id)
            val clips = drumRepo.ensureClipsExist(pattern.id)
            val clipUiStates = clips.map { DrumClipUiState(clipId = it.id, offsetMs = it.offsetMs) }
            val clipOffsetsMs = clipUiStates.map { it.offsetMs }

            patterns[track.id] = DrumPatternUiState(
                patternId = pattern.id,
                stepsPerBar = pattern.stepsPerBar,
                bars = pattern.bars,
                steps = steps,
                clips = clipUiStates
            )
            pushDrumPatternToEngine(track, pattern.stepsPerBar, pattern.bars, steps, clipOffsetsMs)
            observeDrumPattern(track.id, pattern.id)
        }

        _state.update { it.copy(drumPatterns = it.drumPatterns + patterns) }
        audioEngine.setDrumSequencerEnabled(true)
    }

    /** Start observing a drum pattern's steps via Flow. */
    private fun observeDrumPattern(trackId: Long, patternId: Long) {
        drumPatternJobs[trackId]?.cancel()
        drumPatternJobs[trackId] = viewModelScope.launch {
            drumRepo.observeSteps(patternId).collect { steps ->
                val pattern = _state.value.drumPatterns[trackId] ?: return@collect
                _state.update {
                    it.copy(
                        drumPatterns = it.drumPatterns + (trackId to pattern.copy(steps = steps))
                    )
                }
                val track = _state.value.tracks.find { it.id == trackId } ?: return@collect
                val clipOffsetsMs = pattern.clips.map { it.offsetMs }
                pushDrumPatternToEngine(track, pattern.stepsPerBar, pattern.bars, steps, clipOffsetsMs)
            }
        }
    }

    /** Push drum pattern data to the C++ step sequencer. */
    private fun pushDrumPatternToEngine(
        track: com.example.nightjar.data.db.entity.TrackEntity,
        stepsPerBar: Int,
        bars: Int,
        steps: List<com.example.nightjar.data.db.entity.DrumStepEntity>,
        clipOffsetsMs: List<Long> = emptyList()
    ) {
        val stepIndices: IntArray
        val drumNotes: IntArray
        val velocities: FloatArray

        if (steps.isEmpty()) {
            stepIndices = IntArray(0)
            drumNotes = IntArray(0)
            velocities = FloatArray(0)
        } else {
            stepIndices = IntArray(steps.size)
            drumNotes = IntArray(steps.size)
            velocities = FloatArray(steps.size)
            for (i in steps.indices) {
                stepIndices[i] = steps[i].stepIndex
                drumNotes[i] = steps[i].drumNote
                velocities[i] = steps[i].velocity
            }
        }

        audioEngine.updateDrumPattern(
            stepsPerBar = stepsPerBar,
            bars = bars,
            offsetMs = track.offsetMs,
            volume = track.volume,
            muted = track.isMuted,
            stepIndices = stepIndices,
            drumNotes = drumNotes,
            velocities = velocities,
            clipOffsetsMs = if (clipOffsetsMs.isNotEmpty()) {
                clipOffsetsMs.toLongArray()
            } else {
                LongArray(0)
            },
            beatsPerBar = _state.value.timeSignatureNumerator
        )
    }

    /** Toggle a step in a drum pattern. The Flow observer handles the UI update. */
    private fun toggleDrumStep(trackId: Long, stepIndex: Int, drumNote: Int) {
        val patternState = _state.value.drumPatterns[trackId] ?: return
        viewModelScope.launch {
            try {
                drumRepo.toggleStep(patternState.patternId, stepIndex, drumNote)
            } catch (e: Exception) {
                _effects.emit(StudioEffect.ShowError(e.message ?: "Failed to toggle step."))
            }
        }
    }

    /** Update project time signature on the idea and push to drum sequencer. */
    private fun setTimeSignature(numerator: Int, denominator: Int) {
        _state.update {
            it.copy(
                timeSignatureNumerator = numerator,
                timeSignatureDenominator = denominator
            )
        }
        val ideaId = currentIdeaId ?: return
        viewModelScope.launch {
            try {
                ideaRepo.updateTimeSignature(ideaId, numerator, denominator)
                // Re-push all drum patterns with new beatsPerBar
                val st = _state.value
                for ((trackId, pattern) in st.drumPatterns) {
                    val track = st.tracks.find { it.id == trackId } ?: continue
                    pushDrumPatternToEngine(
                        track, pattern.stepsPerBar, pattern.bars,
                        pattern.steps, pattern.clips.map { it.offsetMs }
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to persist time signature: ${e.message}")
            }
        }
    }

    /** Update project BPM on the idea and in the native engine, scaling MIDI note positions. */
    private fun setBpm(bpm: Double) {
        val clamped = bpm.coerceIn(30.0, 300.0)
        val oldBpm = _state.value.bpm
        if (clamped == oldBpm) return

        val scaleFactor = oldBpm / clamped

        // Atomic update: new BPM + scaled MIDI note positions
        _state.update { st ->
            st.copy(
                bpm = clamped,
                midiTracks = st.midiTracks.mapValues { (_, ms) ->
                    ms.copy(notes = ms.notes.map { n ->
                        n.copy(
                            startMs = (n.startMs * scaleFactor).roundToLong(),
                            durationMs = (n.durationMs * scaleFactor).roundToLong()
                        )
                    })
                }
            )
        }
        audioEngine.setBpm(clamped)
        pushAllMidiToEngine()

        val ideaId = currentIdeaId ?: return
        viewModelScope.launch {
            try {
                ideaRepo.updateBpm(ideaId, clamped)
                midiRepo.scaleNotePositions(ideaId, scaleFactor)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to persist BPM change: ${e.message}")
            }
        }
    }

    /** Change the number of bars for a drum pattern. */
    private fun setPatternBars(trackId: Long, bars: Int) {
        val clamped = bars.coerceIn(1, 8)
        val patternState = _state.value.drumPatterns[trackId] ?: return
        viewModelScope.launch {
            try {
                drumRepo.updatePatternGrid(patternState.patternId, patternState.stepsPerBar, clamped)
                // Update local state immediately
                _state.update {
                    it.copy(
                        drumPatterns = it.drumPatterns + (trackId to patternState.copy(bars = clamped))
                    )
                }
                // Re-push to engine with new bar count
                val track = _state.value.tracks.find { it.id == trackId } ?: return@launch
                val updatedPattern = _state.value.drumPatterns[trackId]
                val steps = updatedPattern?.steps ?: emptyList()
                val clipOffsetsMs = updatedPattern?.clips?.map { it.offsetMs } ?: emptyList()
                pushDrumPatternToEngine(track, patternState.stepsPerBar, clamped, steps, clipOffsetsMs)
            } catch (e: Exception) {
                _effects.emit(StudioEffect.ShowError(e.message ?: "Failed to update bar count."))
            }
        }
    }

    // ── Drum clips ──────────────────────────────────────────────────────

    private fun duplicateClip(trackId: Long, clipId: Long) {
        viewModelScope.launch {
            try {
                val patternState = _state.value.drumPatterns[trackId]
                val st = _state.value
                val bars = patternState?.bars ?: 1
                val patternDurationMs = com.example.nightjar.audio.MusicalTimeConverter
                    .msPerMeasure(st.bpm, st.timeSignatureNumerator, st.timeSignatureDenominator)
                    .toLong() * bars
                drumRepo.duplicateClip(clipId, patternDurationMs)
                reloadClipsForTrack(trackId)
            } catch (e: Exception) {
                _effects.emit(StudioEffect.ShowError(e.message ?: "Failed to duplicate clip."))
            }
        }
    }

    private fun moveClip(trackId: Long, clipId: Long, newOffsetMs: Long) {
        viewModelScope.launch {
            try {
                val snapped = snapIfEnabled(newOffsetMs).coerceAtLeast(0L)
                drumRepo.moveClip(clipId, snapped)
                reloadClipsForTrack(trackId)
            } catch (e: Exception) {
                _effects.emit(StudioEffect.ShowError(e.message ?: "Failed to move clip."))
            }
        }
    }

    private fun deleteClip(trackId: Long, clipId: Long) {
        viewModelScope.launch {
            try {
                drumRepo.deleteClip(clipId)
                reloadClipsForTrack(trackId)
            } catch (e: Exception) {
                _effects.emit(StudioEffect.ShowError(e.message ?: "Failed to delete clip."))
            }
        }
    }

    // ── Drum clip drag-to-reposition ────────────────────────────────────

    private fun startDragClip(trackId: Long, clipId: Long) {
        val pattern = _state.value.drumPatterns[trackId] ?: return
        val clip = pattern.clips.find { it.clipId == clipId } ?: return
        _state.update {
            it.copy(clipDragState = ClipDragState(trackId, clipId, clip.offsetMs, clip.offsetMs))
        }
    }

    private fun updateDragClip(previewOffsetMs: Long) {
        _state.update { st ->
            st.clipDragState?.let { drag ->
                val snapped = snapIfEnabled(previewOffsetMs).coerceAtLeast(0L)
                st.copy(clipDragState = drag.copy(previewOffsetMs = snapped))
            } ?: st
        }
    }

    private fun finishDragClip(trackId: Long, clipId: Long, newOffsetMs: Long) {
        _state.update { it.copy(clipDragState = null) }
        moveClip(trackId, clipId, newOffsetMs)
    }

    private fun cancelDragClip() {
        _state.update { it.copy(clipDragState = null) }
    }

    /** Reload clips for a drum track and re-push pattern to engine. */
    private suspend fun reloadClipsForTrack(trackId: Long) {
        val patternState = _state.value.drumPatterns[trackId] ?: return
        val clips = drumRepo.getClips(patternState.patternId)
        val clipUiStates = clips.map { DrumClipUiState(clipId = it.id, offsetMs = it.offsetMs) }

        _state.update {
            it.copy(
                drumPatterns = it.drumPatterns + (trackId to patternState.copy(clips = clipUiStates))
            )
        }

        // Re-push to engine with updated clip offsets
        val track = _state.value.tracks.find { it.id == trackId } ?: return
        val steps = patternState.steps
        pushDrumPatternToEngine(track, patternState.stepsPerBar, patternState.bars, steps,
            clipUiStates.map { it.offsetMs })
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    /** Reload tracks from DB and re-prepare the native engine. */
    // ── MIDI tracks ────────────────────────────────────────────────────────

    private fun addMidiTrack() {
        val ideaId = currentIdeaId ?: return
        viewModelScope.launch {
            try {
                if (!ensureSoundFontLoaded()) return@launch

                val channel = midiRepo.nextAvailableChannel(ideaId)
                val trackId = studioRepo.addMidiTrack(ideaId, channel, 0)

                _state.update {
                    it.copy(
                        midiTracks = it.midiTracks + (trackId to MidiTrackUiState(
                            midiProgram = 0,
                            instrumentName = gmInstrumentName(0)
                        ))
                    )
                }

                audioEngine.setMidiSequencerEnabled(true)
                observeMidiNotes(trackId)
                reloadAndPrepare()
            } catch (e: Exception) {
                _effects.emit(
                    StudioEffect.ShowError(e.message ?: "Failed to add MIDI track.")
                )
            }
        }
    }

    /**
     * Load MIDI note data for all MIDI tracks. Called once during [load].
     * Starts Flow observation for each track so note changes push to the engine.
     */
    private suspend fun loadMidiTracks(
        tracks: List<com.example.nightjar.data.db.entity.TrackEntity>
    ) {
        val midiTracks = tracks.filter { it.isMidi }
        if (midiTracks.isEmpty()) return

        if (!ensureSoundFontLoaded()) return

        val midiTrackStates = mutableMapOf<Long, MidiTrackUiState>()
        for (track in midiTracks) {
            val notes = midiRepo.getNotesForTrack(track.id)
            midiTrackStates[track.id] = MidiTrackUiState(
                notes = notes,
                midiProgram = track.midiProgram,
                instrumentName = gmInstrumentName(track.midiProgram)
            )
            observeMidiNotes(track.id)
        }

        _state.update { it.copy(midiTracks = it.midiTracks + midiTrackStates) }
        pushAllMidiToEngine()
        audioEngine.setMidiSequencerEnabled(true)
    }

    /** Start observing MIDI notes for a track via Flow. */
    private fun observeMidiNotes(trackId: Long) {
        midiNoteJobs[trackId]?.cancel()
        midiNoteJobs[trackId] = viewModelScope.launch {
            midiRepo.observeNotes(trackId).collect { notes ->
                _state.update { st ->
                    val existing = st.midiTracks[trackId] ?: MidiTrackUiState()
                    st.copy(
                        midiTracks = st.midiTracks + (trackId to existing.copy(notes = notes))
                    )
                }
                pushAllMidiToEngine()
            }
        }
    }

    /**
     * Convert all MIDI tracks to flat arrays and push to the C++ engine.
     * Called whenever notes change or tracks are added/removed.
     */
    private fun pushAllMidiToEngine() {
        val st = _state.value
        val midiTrackEntries = st.tracks.filter { it.isMidi }
        if (midiTrackEntries.isEmpty()) {
            // Push empty to clear
            audioEngine.updateMidiTracks(
                IntArray(0), IntArray(0), FloatArray(0), BooleanArray(0), IntArray(0),
                LongArray(0), IntArray(0), IntArray(0), IntArray(0)
            )
            return
        }

        val trackCount = midiTrackEntries.size
        val channels = IntArray(trackCount)
        val programs = IntArray(trackCount)
        val volumes = FloatArray(trackCount)
        val muted = BooleanArray(trackCount)
        val trackEventCounts = IntArray(trackCount)
        val anySoloed = st.soloedTrackIds.isNotEmpty()

        // Collect all events across all tracks
        val allFrames = mutableListOf<Long>()
        val allChannels = mutableListOf<Int>()
        val allNotes = mutableListOf<Int>()
        val allVelocities = mutableListOf<Int>()

        for (i in midiTrackEntries.indices) {
            val track = midiTrackEntries[i]
            val midiState = st.midiTracks[track.id]
            val notes = midiState?.notes ?: emptyList()

            channels[i] = track.midiChannel
            programs[i] = track.midiProgram
            volumes[i] = track.volume
            muted[i] = track.isMuted ||
                (anySoloed && track.id !in st.soloedTrackIds)

            // Generate noteOn/noteOff event pairs from notes, sorted by frame
            val events = generateMidiEvents(notes, track.midiChannel)
            trackEventCounts[i] = events.size

            for (event in events) {
                allFrames.add(event.framePos)
                allChannels.add(event.channel)
                allNotes.add(event.note)
                allVelocities.add(event.velocity)
            }
        }

        audioEngine.updateMidiTracks(
            channels = channels,
            programs = programs,
            volumes = volumes,
            muted = muted,
            trackEventCounts = trackEventCounts,
            eventFrames = allFrames.toLongArray(),
            eventChannels = allChannels.toIntArray(),
            eventNotes = allNotes.toIntArray(),
            eventVelocities = allVelocities.toIntArray()
        )
    }

    /**
     * Generate sorted noteOn/noteOff event pairs from MIDI note entities.
     * Each note produces two events: noteOn at startMs, noteOff at startMs + durationMs.
     */
    private fun generateMidiEvents(
        notes: List<MidiNoteEntity>,
        channel: Int
    ): List<MidiEventFlat> {
        val events = mutableListOf<MidiEventFlat>()
        val sampleRate = 44100L

        for (note in notes) {
            val startFrame = (note.startMs * sampleRate) / 1000
            val endFrame = ((note.startMs + note.durationMs) * sampleRate) / 1000
            val velocity = (note.velocity * 127).toInt().coerceIn(1, 127)

            events.add(MidiEventFlat(startFrame, channel, note.pitch, velocity))
            events.add(MidiEventFlat(endFrame, channel, note.pitch, 0))
        }

        // Sort by frame position, noteOff before noteOn at same position
        events.sortWith(compareBy<MidiEventFlat> { it.framePos }.thenBy { if (it.velocity == 0) 0 else 1 })
        return events
    }

    private data class MidiEventFlat(
        val framePos: Long,
        val channel: Int,
        val note: Int,
        val velocity: Int
    )

    /** Set instrument (GM program) for a MIDI track. */
    private fun setMidiInstrument(trackId: Long, program: Int) {
        viewModelScope.launch {
            try {
                midiRepo.setInstrument(trackId, program)
                _state.update { st ->
                    val existing = st.midiTracks[trackId] ?: MidiTrackUiState()
                    st.copy(
                        midiTracks = st.midiTracks + (trackId to existing.copy(
                            midiProgram = program,
                            instrumentName = gmInstrumentName(program)
                        )),
                        showInstrumentPickerForTrackId = null
                    )
                }
                // Reload tracks from DB to update track entity midiProgram
                reloadTracks()
                pushAllMidiToEngine()
            } catch (e: Exception) {
                _effects.emit(StudioEffect.ShowError("Failed to change instrument"))
            }
        }
    }

    /** Preview an instrument by playing a short note on the preview channel. */
    private fun previewInstrument(program: Int) {
        previewNoteOffJob?.cancel()
        // Program change on preview channel, then play middle C
        audioEngine.synthNoteOn(previewChannel, 60, 80)
        previewNoteOffJob = viewModelScope.launch {
            delay(300L)
            audioEngine.synthNoteOff(previewChannel, 60)
        }
    }

    private suspend fun reloadAndPrepare() {
        val ideaId = currentIdeaId ?: return
        val tracks = studioRepo.getTracks(ideaId)
        _state.update { it.copy(tracks = tracks) }
        loadTracksIntoEngine(tracks)

        // Clamp loop region if total duration changed
        val total = audioEngine.totalDurationMs.value
        val current = _state.value
        if (current.loopStartMs != null && current.loopEndMs != null) {
            val clampedEnd = current.loopEndMs.coerceAtMost(total)
            val clampedStart = current.loopStartMs.coerceAtMost(
                (clampedEnd - MIN_LOOP_DURATION_MS).coerceAtLeast(0L)
            )
            if (clampedEnd - clampedStart < MIN_LOOP_DURATION_MS) {
                clearLoopRegion()
            } else if (clampedStart != current.loopStartMs || clampedEnd != current.loopEndMs) {
                _state.update { it.copy(loopStartMs = clampedStart, loopEndMs = clampedEnd) }
                if (current.isLoopEnabled) {
                    audioEngine.setLoopRegion(clampedStart, clampedEnd)
                }
            } else if (current.isLoopEnabled) {
                audioEngine.setLoopRegion(clampedStart, clampedEnd)
            }
        }
    }

    /** Reload tracks from DB without re-preparing the engine. */
    private suspend fun reloadTracks() {
        val ideaId = currentIdeaId ?: return
        val tracks = studioRepo.getTracks(ideaId)
        _state.update { it.copy(tracks = tracks) }
    }

    fun getAudioFile(name: String): File = recordingStorage.getAudioFile(name)

    override fun onCleared() {
        super.onCleared()
        tickJob?.cancel()
        recordingTickJob?.cancel()
        previewNoteOffJob?.cancel()
        drumPatternJobs.values.forEach { it.cancel() }
        drumPatternJobs.clear()
        midiNoteJobs.values.forEach { it.cancel() }
        midiNoteJobs.clear()
        if (audioEngine.isRecordingActive()) {
            audioEngine.stopRecording()
        }
        // Order matters: pause stops the render thread from ticking,
        // disable prevents new note events, then silence kills remaining notes
        audioEngine.pause()
        audioEngine.setDrumSequencerEnabled(false)
        audioEngine.setMidiSequencerEnabled(false)
        audioEngine.synthAllSoundsOff()
        audioEngine.removeAllTracks()
    }
}
