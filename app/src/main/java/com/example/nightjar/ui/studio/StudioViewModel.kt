package com.example.nightjar.ui.studio

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.nightjar.audio.AudioLatencyEstimator
import com.example.nightjar.audio.MetronomePreferences
import com.example.nightjar.audio.MusicalTimeConverter
import com.example.nightjar.audio.StudioPreferences
import com.example.nightjar.audio.OboeAudioEngine
import com.example.nightjar.audio.SoundFontManager
import com.example.nightjar.audio.WavSplitter
import com.example.nightjar.data.db.entity.AudioClipEntity
import com.example.nightjar.data.db.entity.MidiNoteEntity
import com.example.nightjar.data.db.entity.TakeEntity
import com.example.nightjar.data.events.PulseBus
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
import kotlin.math.abs
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
    private val soundFontManager: SoundFontManager,
    private val metronomePrefs: MetronomePreferences,
    private val studioPrefs: StudioPreferences,
    private val pulseBus: PulseBus
) : ViewModel() {

    init {
        // Collect sibling-pulse events from the repository layer and bump the
        // per-group tick counter so clip composables can flash their indicator.
        viewModelScope.launch {
            pulseBus.pulses.collect { key ->
                _state.update { s ->
                    val next = (s.pulseTicks[key] ?: 0L) + 1L
                    s.copy(pulseTicks = s.pulseTicks + (key to next))
                }
            }
        }
    }

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
    // Auto-punch-out boundary (ms) -- recording stops when playhead reaches this
    private var autoPunchOutMs: Long? = null

    init {
        // Load persisted settings
        _state.update {
            it.copy(
                isMetronomeEnabled = metronomePrefs.isEnabled,
                metronomeVolume = metronomePrefs.volume,
                countInBars = metronomePrefs.countInBars,
                returnToCursor = studioPrefs.returnToCursor
            )
        }
        audioEngine.setMetronomeVolume(metronomePrefs.volume)

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
                // Poll metronome beat frame for LED pulse animation
                val beatFrame = if (_state.value.isMetronomeEnabled) {
                    audioEngine.getLastMetronomeBeatFrame()
                } else -1L

                _state.update {
                    it.copy(
                        isPlaying = audioEngine.isPlaying.value,
                        globalPositionMs = audioEngine.positionMs.value,
                        totalDurationMs = effectiveDuration,
                        lastBeatFrame = beatFrame
                    )
                }

                // Auto-punch-out: stop recording when playhead reaches boundary
                val punchOut = autoPunchOutMs
                if (st.isRecording && punchOut != null &&
                    audioEngine.positionMs.value >= punchOut
                ) {
                    autoPunchOutMs = null
                    stopRecording()
                    _effects.emit(StudioEffect.ShowStatus("Punched out"))
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
            StudioAction.NavigateBack -> navigateBack()
            is StudioAction.Load -> load(action.ideaId)
            StudioAction.ToggleAddTrackDrawer -> {
                _state.update { it.copy(isAddTrackDrawerOpen = !it.isAddTrackDrawerOpen) }
            }
            is StudioAction.SelectNewTrackType -> {
                _state.update { it.copy(isAddTrackDrawerOpen = false) }
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
            StudioAction.Play -> {
                if (!_state.value.isPlaying) {
                    audioEngine.seekTo(_state.value.cursorPositionMs)
                }
                audioEngine.play()
                syncMetronomeToEngine()
            }
            StudioAction.Pause -> {
                audioEngine.pause()
                syncMetronomeToEngine()
                if (_state.value.returnToCursor) {
                    audioEngine.seekTo(_state.value.cursorPositionMs)
                }
            }
            StudioAction.RestartPlayback -> {
                audioEngine.pause()
                audioEngine.seekTo(_state.value.cursorPositionMs)
            }
            is StudioAction.SeekTo -> audioEngine.seekTo(action.positionMs)
            is StudioAction.SeekFinished -> {
                audioEngine.seekTo(action.positionMs)
                setCursorPosition(action.positionMs)
            }

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
                    it.copy(expandedTrackIds = newSet, expandedClipState = null)
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

            // Audio clip actions
            is StudioAction.StartDragAudioClip -> startDragAudioClip(action.trackId, action.clipId)
            is StudioAction.UpdateDragAudioClip -> updateDragAudioClip(action.previewOffsetMs)
            is StudioAction.FinishDragAudioClip -> finishDragAudioClip(action.trackId, action.clipId, action.newOffsetMs)
            StudioAction.CancelDragAudioClip -> cancelDragAudioClip()
            is StudioAction.StartTrimAudioClip -> startTrimAudioClip(action.clipId, action.trackId, action.edge)
            is StudioAction.UpdateTrimAudioClip -> updateTrimAudioClip(action.previewTrimStartMs, action.previewTrimEndMs)
            is StudioAction.FinishTrimAudioClip -> finishTrimAudioClip(action.clipId, action.trackId, action.trimStartMs, action.trimEndMs)
            StudioAction.CancelTrimAudioClip -> cancelTrimAudioClip()
            is StudioAction.ActivateTake -> activateTake(action.clipId, action.takeId, action.trackId)
            is StudioAction.DeleteAudioClip -> deleteAudioClip(action.trackId, action.clipId)

            // Take rename / delete (clip-scoped)
            is StudioAction.RequestRenameTake -> {
                _state.update {
                    it.copy(
                        renamingTakeId = action.takeId,
                        renamingTakeClipId = action.clipId,
                        renamingTakeCurrentName = action.currentName
                    )
                }
            }
            is StudioAction.ConfirmRenameTake -> confirmRenameTake(action.takeId, action.newName)
            StudioAction.DismissRenameTake -> {
                _state.update {
                    it.copy(
                        renamingTakeId = null,
                        renamingTakeClipId = null,
                        renamingTakeCurrentName = ""
                    )
                }
            }
            is StudioAction.RequestDeleteTake -> {
                _state.update {
                    it.copy(
                        confirmingDeleteTakeId = action.takeId,
                        confirmingDeleteTakeClipId = action.clipId
                    )
                }
            }
            StudioAction.DismissDeleteTake -> {
                _state.update {
                    it.copy(confirmingDeleteTakeId = null, confirmingDeleteTakeClipId = null)
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
            is StudioAction.SetGridResolution -> setGridResolution(action.resolution)

            // Drum sequencer
            is StudioAction.ToggleDrumStep -> toggleDrumStep(
                action.trackId, action.stepIndex, action.drumNote
            )
            is StudioAction.SetBpm -> setBpm(action.bpm)
            is StudioAction.SetPatternBars -> setPatternBars(action.trackId, action.bars)
            is StudioAction.SetPatternResolution -> setPatternResolution(action.trackId, action.resolution)
            is StudioAction.SelectDrumClip -> selectDrumClip(action.trackId, action.clipIndex)

            // Clip creation
            is StudioAction.CreateDrumClip -> createDrumClip(action.trackId, action.offsetMs)
            is StudioAction.CreateMidiClip -> createMidiClip(action.trackId, action.offsetMs)

            // Drum clips
            is StudioAction.DuplicateClip -> duplicateClip(action.trackId, action.clipId, action.linked)
            is StudioAction.MoveClip -> moveClip(action.trackId, action.clipId, action.newOffsetMs)
            is StudioAction.DeleteClip -> deleteClip(action.trackId, action.clipId)

            // Drum clip drag-to-reposition
            is StudioAction.StartDragClip -> startDragClip(action.trackId, action.clipId)
            is StudioAction.UpdateDragClip -> updateDragClip(action.previewOffsetMs)
            is StudioAction.FinishDragClip -> finishDragClip(action.trackId, action.clipId, action.newOffsetMs)
            StudioAction.CancelDragClip -> cancelDragClip()

            // Full-screen editors
            is StudioAction.OpenDrumEditor -> openDrumEditor(action.trackId, action.clipId)
            is StudioAction.OpenPianoRoll -> openPianoRoll(action.trackId, action.clipId)
            is StudioAction.ShowInstrumentPicker -> {
                _state.update { it.copy(showInstrumentPickerForTrackId = action.trackId) }
            }
            StudioAction.DismissInstrumentPicker -> {
                _state.update { it.copy(showInstrumentPickerForTrackId = null) }
            }
            is StudioAction.SetMidiInstrument -> setMidiInstrument(action.trackId, action.program)
            is StudioAction.PreviewInstrument -> previewInstrument(action.program)

            // MIDI clips
            is StudioAction.DuplicateMidiClip -> duplicateMidiClip(action.trackId, action.clipId, action.linked)
            is StudioAction.MoveMidiClip -> moveMidiClip(action.trackId, action.clipId, action.newOffsetMs)
            is StudioAction.DeleteMidiClip -> deleteMidiClip(action.trackId, action.clipId)
            is StudioAction.StartDragMidiClip -> startDragMidiClip(action.trackId, action.clipId)
            is StudioAction.UpdateDragMidiClip -> updateDragMidiClip(action.previewOffsetMs)
            is StudioAction.FinishDragMidiClip -> finishDragMidiClip(action.trackId, action.clipId, action.newOffsetMs)
            StudioAction.CancelDragMidiClip -> cancelDragMidiClip()

            // Clip action panel
            is StudioAction.TapClip -> tapClip(action.trackId, action.clipId, action.clipType)
            StudioAction.DismissClipPanel -> dismissClipPanel()

            // Cursor / Transport
            is StudioAction.SetCursorPosition -> setCursorPosition(action.positionMs)
            StudioAction.ToggleReturnToCursor -> toggleReturnToCursor()

            // Metronome
            StudioAction.ToggleMetronome -> toggleMetronome()
            is StudioAction.SetMetronomeVolume -> setMetronomeVolume(action.volume)
            is StudioAction.SetCountInBars -> setCountInBars(action.bars)
            StudioAction.ToggleControlsDrawer -> {
                _state.update { it.copy(isControlsDrawerOpen = !it.isControlsDrawerOpen) }
            }

            // Track header collapse
            is StudioAction.ToggleTrackHeaderCollapse -> {
                _state.update { s ->
                    if (action.trackId in s.collapsedHeaderTrackIds) {
                        // Expand this header
                        s.copy(collapsedHeaderTrackIds = s.collapsedHeaderTrackIds - action.trackId)
                    } else {
                        // Collapse this header — also close drawer and deselect clip if on this track
                        s.copy(
                            collapsedHeaderTrackIds = s.collapsedHeaderTrackIds + action.trackId,
                            expandedTrackIds = s.expandedTrackIds - action.trackId,
                            expandedClipState = if (s.expandedClipState?.trackId == action.trackId) null else s.expandedClipState
                        )
                    }
                }
            }
            StudioAction.ToggleAllTrackHeaders -> {
                _state.update { s ->
                    val allTrackIds = s.tracks.map { it.id }.toSet()
                    if (!s.headersCollapsedMode) {
                        // Enter collapsed mode: collapse all headers
                        s.copy(
                            headersCollapsedMode = true,
                            collapsedHeaderTrackIds = allTrackIds,
                            expandedTrackIds = emptySet(),
                            expandedClipState = null
                        )
                    } else {
                        // Exit collapsed mode: expand all headers
                        s.copy(
                            headersCollapsedMode = false,
                            collapsedHeaderTrackIds = emptySet()
                        )
                    }
                }
            }

            // Inline MiniPianoRoll
            is StudioAction.SelectMidiClip -> selectMidiClip(action.trackId, action.clipId)
            is StudioAction.InlinePlaceNote -> inlinePlaceNote(action.trackId, action.clipId, action.pitch, action.startMs, action.durationMs)
            is StudioAction.InlineMoveNote -> inlineMoveNote(action.trackId, action.noteId, action.newStartMs, action.newPitch)
            is StudioAction.InlineResizeNote -> inlineResizeNote(action.trackId, action.noteId, action.newDurationMs)
            is StudioAction.InlineDeleteNote -> inlineDeleteNote(action.trackId, action.noteId)

            // Audio clip duplicate (pillrocker: unlinked | linked)
            is StudioAction.DuplicateAudioClip ->
                duplicateAudioClip(action.trackId, action.clipId, action.linked)

            // Split / Unlink / Rename (uniform across clip types)
            is StudioAction.StartSplitMode -> startSplitMode(action.clipId, action.clipType)
            is StudioAction.UpdateSplitPosition -> updateSplitPosition(action.timelinePositionMs)
            is StudioAction.ConfirmSplit -> confirmSplit()
            is StudioAction.CancelSplit -> cancelSplit()
            is StudioAction.UnlinkClip -> unlinkClip(action.clipId, action.clipType)
            is StudioAction.RequestRenameClip -> {
                _state.update {
                    it.copy(
                        renamingClipId = action.clipId,
                        renamingClipType = action.clipType,
                        renamingClipCurrentName = action.currentName
                    )
                }
            }
            is StudioAction.ConfirmRenameClip -> confirmRenameClip(action.newName)
            is StudioAction.DismissRenameClip -> {
                _state.update {
                    it.copy(
                        renamingClipId = null,
                        renamingClipType = null,
                        renamingClipCurrentName = ""
                    )
                }
            }
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
                        timeSignatureDenominator = idea.timeSignatureDenominator,
                        gridResolution = idea.gridResolution
                    )
                }

                // Set project BPM and metronome config in the native engine
                audioEngine.setBpm(idea.bpm)
                audioEngine.setMetronomeBeatsPerBar(idea.timeSignatureNumerator)

                // Load audio clips for all audio tracks
                loadAudioClips(tracks)

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

    private fun navigateBack() {
        val ideaId = currentIdeaId ?: run {
            viewModelScope.launch { _effects.emit(StudioEffect.NavigateBack) }
            return
        }

        viewModelScope.launch {
            ideaRepo.deleteIdeaIfEmpty(ideaId)
            _effects.emit(StudioEffect.NavigateBack)
        }
    }

    /**
     * Load tracks into the native engine using clip-based audio arrangement.
     * For each audio track, loads the active take from each unmuted clip.
     * The engine sees flat track slots (one per clip) -- no C++ changes needed.
     */
    private fun loadTracksIntoEngine(
        tracks: List<com.example.nightjar.data.db.entity.TrackEntity>
    ) {
        audioEngine.removeAllTracks()
        val clipsMap = _state.value.audioClips

        for (track in tracks) {
            // Drum and MIDI tracks are handled by the synth engine
            if (!track.isAudio) continue

            val clips = clipsMap[track.id] ?: continue
            for (clip in clips) {
                val activeTake = clip.activeTake ?: continue
                val effectivelyMuted = track.isMuted || clip.isMuted
                val file = getAudioFile(activeTake.audioFileName)
                audioEngine.addTrack(
                    trackId = clip.clipId.toInt(),
                    filePath = file.absolutePath,
                    durationMs = activeTake.durationMs,
                    offsetMs = clip.offsetMs,
                    trimStartMs = activeTake.trimStartMs,
                    trimEndMs = activeTake.trimEndMs,
                    volume = activeTake.volume * track.volume,
                    isMuted = effectivelyMuted
                )
            }
        }
    }

    // ── Arm ────────────────────────────────────────────────────────────────

    private fun toggleArm(trackId: Long) {
        // Only audio tracks can be armed (not drum or MIDI)
        val track = _state.value.tracks.find { it.id == trackId } ?: return
        if (!track.isAudio) return

        val currentArmed = _state.value.armedTrackId
        if (currentArmed == trackId) {
            _state.update { it.copy(armedTrackId = null) }
        } else {
            _state.update { it.copy(armedTrackId = trackId) }
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
     * If count-in is configured, plays metronome for N measures first.
     */
    private fun startRecordingAfterPermission() {
        val ideaId = currentIdeaId ?: return
        val st = _state.value

        // Determine if loop recording should be active
        isLoopRecording = st.isLoopEnabled && st.hasLoopRegion
        loopResetTimestampsMs.clear()
        lastLoopResetCount = audioEngine.getLoopResetCount()

        viewModelScope.launch {
            try {
                val countInBars = st.countInBars
                val hasCountIn = countInBars > 0 && st.isMetronomeEnabled

                if (hasCountIn) {
                    _state.update { it.copy(isCountingIn = true) }
                }

                // Configure metronome and count-in before starting anything
                syncMetronomeToEngine()
                if (hasCountIn) {
                    audioEngine.setCountIn(countInBars, st.timeSignatureNumerator)
                }

                val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val file = recordingStorage.getAudioFile("nightjar_overdub_$ts.wav")
                recordingFile = file

                val intendedStartMs = _state.value.cursorPositionMs

                // Phase 1: Start input stream
                val started = audioEngine.startRecording(file.absolutePath)
                if (!started) {
                    _state.update { it.copy(isCountingIn = false) }
                    _effects.emit(StudioEffect.ShowError("Failed to start recording."))
                    return@launch
                }
                // Phase 2: Pipeline hot
                audioEngine.awaitFirstBuffer()

                // Seek to cursor position before starting playback for overdub context
                audioEngine.seekTo(intendedStartMs)

                val hasPlayableTracks = st.tracks.any { !it.isMuted }
                audioEngine.setRecording(true)
                audioEngine.play()

                if (hasCountIn) {
                    // Wait for count-in duration (metronome-only, mixer silent at negative pos)
                    val beatDurationMs = 60_000.0 / st.bpm
                    val countInMs = (countInBars * st.timeSignatureNumerator * beatDurationMs).toLong()
                    delay(countInMs)
                    _state.update { it.copy(isCountingIn = false) }
                }

                // Phase 3: Open write gate -- recording starts as position crosses 0
                audioEngine.openWriteGate()
                val writeGateNanos = System.nanoTime()

                val preRollMs = (System.nanoTime() - writeGateNanos) / 1_000_000L
                val compensation = latencyEstimator.computeCompensationMs(
                    preRollMs = preRollMs,
                    hasPlayableTracks = hasPlayableTracks
                )

                recordingStartGlobalMs = intendedStartMs
                recordingTrimStartMs = compensation
                recordingStartNanos = System.nanoTime()

                // Auto-punch-out: if recording into a gap, stop at the next clip
                autoPunchOutMs = null
                if (recordingArmedTrackId != null && !isFirstTrackRecording) {
                    val existingClip = studioRepo.findClipAtPosition(
                        recordingArmedTrackId!!, intendedStartMs
                    )
                    if (existingClip == null) {
                        val nextClip = studioRepo.findNextClipAfterPosition(
                            recordingArmedTrackId!!, intendedStartMs
                        )
                        if (nextClip != null) {
                            autoPunchOutMs = nextClip.offsetMs
                            Log.d(TAG, "Auto-punch-out set at ${nextClip.offsetMs}ms")
                        }
                    }
                }

                Log.d(TAG, "Recording started: intendedStart=${intendedStartMs}ms, " +
                    "preRoll=${preRollMs}ms, compensation=${compensation}ms, " +
                    "hasPlayableTracks=$hasPlayableTracks, " +
                    "isLoopRecording=$isLoopRecording, " +
                    "armedTrackId=$recordingArmedTrackId, " +
                    "isFirstTrack=$isFirstTrackRecording, " +
                    "countInBars=$countInBars")

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
                _state.update { it.copy(isCountingIn = false) }
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
        autoPunchOutMs = null

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
                    // Case 1: Create a new track with clip + take
                    studioRepo.addTrackWithClipAndTake(
                        ideaId = ideaId,
                        audioFile = file,
                        durationMs = durationMs,
                        offsetMs = recordingStartGlobalMs,
                        trimStartMs = safeTrimStartMs
                    )
                } else if (armedId != null && wasLoopRecording && loopResets.isNotEmpty()) {
                    // Case 3: Loop recording -- split into takes within a clip
                    saveLoopRecordingAsTakes(
                        armedTrackId = armedId,
                        file = file,
                        totalDurationMs = durationMs,
                        trimStartMs = safeTrimStartMs,
                        loopResetTimestampsMs = loopResets
                    )
                } else if (armedId != null) {
                    // Case 2: Simple recording -- find or create clip at playhead
                    val existingClip = studioRepo.findClipAtPosition(
                        armedId, recordingStartGlobalMs
                    )
                    if (existingClip != null) {
                        // Record into existing clip as a new take
                        studioRepo.addTakeToClip(
                            clipId = existingClip.id,
                            audioFile = file,
                            durationMs = durationMs,
                            trimStartMs = safeTrimStartMs
                        )
                    } else {
                        // Create new clip at playhead + first take
                        val clipId = studioRepo.addClip(armedId, recordingStartGlobalMs)
                        studioRepo.addTakeToClip(
                            clipId = clipId,
                            audioFile = file,
                            durationMs = durationMs,
                            trimStartMs = safeTrimStartMs
                        )
                    }
                    studioRepo.recomputeAudioTrackDuration(armedId)
                    reloadAudioClips(armedId)
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
     * Split a continuous loop recording into individual takes within a clip.
     * The recording file is split at the loop reset timestamps,
     * creating one take per loop cycle in the same clip.
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

        // Find or create clip at playhead
        val existingClip = studioRepo.findClipAtPosition(armedTrackId, recordingStartGlobalMs)
        val clipId = existingClip?.id ?: studioRepo.addClip(armedTrackId, recordingStartGlobalMs)

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
            studioRepo.addTakeToClip(
                clipId = clipId,
                audioFile = file,
                durationMs = totalDurationMs,
                trimStartMs = trimStartMs
            )
        } else {
            // Save each segment as a separate take within the clip
            for ((index, segFile) in splitFiles.withIndex()) {
                val segDurationMs = getFileDurationMs(segFile)
                val segTrimStart = if (index == 0) trimStartMs else 0L

                studioRepo.addTakeToClip(
                    clipId = clipId,
                    audioFile = segFile,
                    durationMs = segDurationMs,
                    trimStartMs = segTrimStart
                )
            }

            // Delete the original unsplit file
            withContext(Dispatchers.IO) {
                file.delete()
            }
        }

        studioRepo.recomputeAudioTrackDuration(armedTrackId)
        reloadAudioClips(armedTrackId)
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

    // ── Audio Clips ────────────────────────────────────────────────────────

    /** Load audio clips for all audio tracks and update state. */
    private suspend fun loadAudioClips(
        tracks: List<com.example.nightjar.data.db.entity.TrackEntity>
    ) {
        val audioTracks = tracks.filter { it.isAudio }
        if (audioTracks.isEmpty()) {
            _state.update {
                it.copy(audioClips = emptyMap(), audioClipLinkage = emptyMap())
            }
            return
        }

        val trackIds = audioTracks.map { it.id }
        val allClips = studioRepo.getClipsForTracks(trackIds)

        val clipsByTrack = mutableMapOf<Long, List<AudioClipUiState>>()
        val linkageByClip = mutableMapOf<Long, ClipLinkage.Audio>()

        if (allClips.isNotEmpty()) {
            val clipIds = allClips.map { it.id }
            val allTakes = studioRepo.getTakesForClips(clipIds)
            val takesByClip = allTakes.groupBy { it.clipId }

            // Precompute group sizes from in-memory clips (O(N)) instead of
            // per-clip DB COUNT queries. Audio groups key by resolved sourceId.
            val sourceCounts = allClips.groupingBy { it.sourceClipId ?: it.id }.eachCount()
            for (clip in allClips) {
                val sourceId = clip.sourceClipId ?: clip.id
                linkageByClip[clip.id] = ClipLinkage.Audio(
                    GroupKey.Audio(sourceId),
                    sourceCounts[sourceId] ?: 1
                )
            }

            for ((trackId, clips) in allClips.groupBy { it.trackId }) {
                clipsByTrack[trackId] = clips.map { clip ->
                    val takes = takesByClip[clip.id] ?: emptyList()
                    AudioClipUiState(
                        clipId = clip.id,
                        trackId = clip.trackId,
                        offsetMs = clip.offsetMs,
                        displayName = clip.displayName,
                        isMuted = clip.isMuted,
                        activeTake = takes.find { it.isActive },
                        takeCount = takes.size,
                        takes = takes
                    )
                }
            }
        }

        _state.update {
            it.copy(audioClips = clipsByTrack, audioClipLinkage = linkageByClip)
        }
    }

    /** Reload audio clips for a single track. */
    private suspend fun reloadAudioClips(trackId: Long) {
        val clips = studioRepo.getClipsForTrack(trackId)
        val clipIds = clips.map { it.id }
        val allTakes = if (clipIds.isNotEmpty()) studioRepo.getTakesForClips(clipIds) else emptyList()
        val takesByClip = allTakes.groupBy { it.clipId }

        val clipStates = clips.map { clip ->
            val takes = takesByClip[clip.id] ?: emptyList()
            AudioClipUiState(
                clipId = clip.id,
                trackId = clip.trackId,
                offsetMs = clip.offsetMs,
                displayName = clip.displayName,
                isMuted = clip.isMuted,
                activeTake = takes.find { it.isActive },
                takeCount = takes.size,
                takes = takes
            )
        }

        // Reloading a single track only sees its own clips. For accurate
        // group sizing across tracks we'd need a global query; for MVP,
        // cross-track links aren't created (duplicate places on same track).
        val sourceCounts = clips.groupingBy { it.sourceClipId ?: it.id }.eachCount()
        val linkage = clips.associate { clip ->
            val sourceId = clip.sourceClipId ?: clip.id
            clip.id to ClipLinkage.Audio(
                GroupKey.Audio(sourceId),
                sourceCounts[sourceId] ?: 1
            )
        }

        _state.update {
            it.copy(
                audioClips = it.audioClips + (trackId to clipStates),
                audioClipLinkage = it.audioClipLinkage
                    .filterKeys { id -> clipStates.none { it.clipId == id } }
                    .let { filtered ->
                        // Remove stale entries for this track, then overlay fresh ones.
                        filtered + linkage
                    }
            )
        }
    }

    /** Reload audio clips for all currently loaded audio tracks. */
    private suspend fun reloadAllAudioClips() {
        val trackIds = _state.value.audioClips.keys.toList()
        for (trackId in trackIds) {
            reloadAudioClips(trackId)
        }
    }

    private fun startDragAudioClip(trackId: Long, clipId: Long) {
        val clips = _state.value.audioClips[trackId] ?: return
        val clip = clips.find { it.clipId == clipId } ?: return
        _state.update {
            it.copy(audioClipDragState = AudioClipDragState(trackId, clipId, clip.offsetMs, clip.offsetMs))
        }
    }

    private fun updateDragAudioClip(previewOffsetMs: Long) {
        _state.update { st ->
            st.audioClipDragState?.let { drag ->
                val snapped = snapIfEnabled(previewOffsetMs).coerceAtLeast(0L)
                st.copy(audioClipDragState = drag.copy(previewOffsetMs = snapped))
            } ?: st
        }
    }

    private fun finishDragAudioClip(trackId: Long, clipId: Long, newOffsetMs: Long) {
        _state.update { it.copy(audioClipDragState = null) }
        val snapped = snapIfEnabled(newOffsetMs).coerceAtLeast(0L)
        viewModelScope.launch {
            try {
                studioRepo.moveClip(clipId, snapped)
                studioRepo.recomputeAudioTrackDuration(trackId)
                reloadAudioClips(trackId)
                reloadAndPrepare()
            } catch (e: Exception) {
                _effects.emit(StudioEffect.ShowError(e.message ?: "Failed to move clip."))
            }
        }
    }

    private fun cancelDragAudioClip() {
        _state.update { it.copy(audioClipDragState = null) }
    }

    private fun startTrimAudioClip(clipId: Long, trackId: Long, edge: TrimEdge) {
        val clips = _state.value.audioClips[trackId] ?: return
        val clip = clips.find { it.clipId == clipId } ?: return
        val take = clip.activeTake ?: return
        _state.update {
            it.copy(audioClipTrimState = AudioClipTrimState(
                clipId = clipId,
                trackId = trackId,
                edge = edge,
                takeId = take.id,
                originalTrimStartMs = take.trimStartMs,
                originalTrimEndMs = take.trimEndMs,
                previewTrimStartMs = take.trimStartMs,
                previewTrimEndMs = take.trimEndMs
            ))
        }
    }

    private fun updateTrimAudioClip(previewTrimStartMs: Long, previewTrimEndMs: Long) {
        _state.update { st ->
            val trim = st.audioClipTrimState ?: return@update st
            val clips = st.audioClips[trim.trackId] ?: return@update st
            val clip = clips.find { it.clipId == trim.clipId } ?: return@update st
            val take = clip.activeTake ?: return@update st
            val maxTrimStart = take.durationMs - previewTrimEndMs - MIN_EFFECTIVE_DURATION_MS
            val maxTrimEnd = take.durationMs - previewTrimStartMs - MIN_EFFECTIVE_DURATION_MS

            val clampedStart = previewTrimStartMs.coerceIn(0L, maxTrimStart.coerceAtLeast(0L))
            val clampedEnd = previewTrimEndMs.coerceIn(0L, maxTrimEnd.coerceAtLeast(0L))
            val snappedStart = snapIfEnabled(clampedStart)
            val snappedEnd = snapIfEnabled(clampedEnd)

            st.copy(audioClipTrimState = trim.copy(
                previewTrimStartMs = snappedStart,
                previewTrimEndMs = snappedEnd
            ))
        }
    }

    private fun finishTrimAudioClip(clipId: Long, trackId: Long, trimStartMs: Long, trimEndMs: Long) {
        val trim = _state.value.audioClipTrimState ?: return
        _state.update { it.copy(audioClipTrimState = null) }
        val snappedStart = snapIfEnabled(trimStartMs)
        val snappedEnd = snapIfEnabled(trimEndMs)
        viewModelScope.launch {
            try {
                // Adjust clip offset if left trim changed
                val clips = _state.value.audioClips[trackId] ?: return@launch
                val clip = clips.find { it.clipId == clipId } ?: return@launch
                val take = clip.activeTake ?: return@launch

                if (snappedStart != take.trimStartMs) {
                    val offsetDelta = snappedStart - take.trimStartMs
                    val newOffsetMs = (clip.offsetMs + offsetDelta).coerceAtLeast(0L)
                    studioRepo.moveClip(clipId, newOffsetMs)
                }

                studioRepo.updateTakeTrim(trim.takeId, snappedStart, snappedEnd)
                studioRepo.recomputeAudioTrackDuration(trackId)
                reloadAudioClips(trackId)
                reloadAndPrepare()
            } catch (e: Exception) {
                _effects.emit(StudioEffect.ShowError(e.message ?: "Failed to trim clip."))
            }
        }
    }

    private fun cancelTrimAudioClip() {
        _state.update { it.copy(audioClipTrimState = null) }
    }

    private fun activateTake(clipId: Long, takeId: Long, trackId: Long) {
        viewModelScope.launch {
            try {
                studioRepo.setActiveTake(clipId, takeId)
                studioRepo.recomputeAudioTrackDuration(trackId)
                reloadAudioClips(trackId)
                reloadAndPrepare()
            } catch (e: Exception) {
                _effects.emit(StudioEffect.ShowError(e.message ?: "Failed to activate take."))
            }
        }
    }

    private fun deleteAudioClip(trackId: Long, clipId: Long) {
        viewModelScope.launch {
            try {
                studioRepo.deleteClipAndAudio(clipId)
                _state.update {
                    val clearClip = it.expandedClipState?.clipId == clipId
                    it.copy(expandedClipState = if (clearClip) null else it.expandedClipState)
                }
                studioRepo.recomputeAudioTrackDuration(trackId)
                reloadAudioClips(trackId)
                reloadAndPrepare()
            } catch (e: Exception) {
                _effects.emit(StudioEffect.ShowError(e.message ?: "Failed to delete clip."))
            }
        }
    }

    private fun confirmRenameTake(takeId: Long, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isBlank()) return
        val clipId = _state.value.renamingTakeClipId
        _state.update {
            it.copy(
                renamingTakeId = null,
                renamingTakeClipId = null,
                renamingTakeCurrentName = ""
            )
        }
        viewModelScope.launch {
            try {
                studioRepo.renameTake(takeId, trimmed)
                reloadAllAudioClips()
            } catch (e: Exception) {
                _effects.emit(StudioEffect.ShowError(e.message ?: "Failed to rename take."))
            }
        }
    }

    private fun executeDeleteTake() {
        val takeId = _state.value.confirmingDeleteTakeId ?: return
        val clipId = _state.value.confirmingDeleteTakeClipId ?: return
        _state.update {
            it.copy(
                confirmingDeleteTakeId = null,
                confirmingDeleteTakeClipId = null
            )
        }
        viewModelScope.launch {
            try {
                // Find the track ID for this clip
                val trackId = _state.value.audioClips.entries
                    .find { (_, clips) -> clips.any { it.clipId == clipId } }?.key
                studioRepo.deleteTakeAndAudio(takeId)
                if (trackId != null) {
                    studioRepo.recomputeAudioTrackDuration(trackId)
                    reloadAudioClips(trackId)
                    reloadAndPrepare()
                }
            } catch (e: Exception) {
                _effects.emit(StudioEffect.ShowError(e.message ?: "Failed to delete take."))
            }
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

    // ── Cursor helpers ─────────────────────────────────────────────────

    private fun setCursorPosition(positionMs: Long) {
        if (_state.value.isRecording) return // cursor locked during recording
        val snapped = snapIfEnabled(positionMs.coerceAtLeast(0L))
        val inSplitMode = _state.value.splitModeClipId != null
        _state.update {
            // In split mode, keep the selected clip so the controls drawer
            // stays visible; outside split mode, tapping the timeline clears
            // the clip selection as before.
            if (inSplitMode) it.copy(cursorPositionMs = snapped)
            else it.copy(cursorPositionMs = snapped, expandedClipState = null)
        }
        if (!_state.value.isPlaying) {
            audioEngine.seekTo(snapped)
        }
        // If split mode is active, track cursor changes as the proposed split.
        if (inSplitMode) updateSplitPosition(snapped)
    }

    private fun toggleReturnToCursor() {
        val newValue = !_state.value.returnToCursor
        studioPrefs.returnToCursor = newValue
        _state.update { it.copy(returnToCursor = newValue) }
    }

    // ── Snap helper ─────────────────────────────────────────────────────

    /** Snap a ms value to the nearest grid step if snap is enabled. */
    private fun snapIfEnabled(ms: Long): Long {
        val st = _state.value
        if (!st.isSnapEnabled) return ms
        return MusicalTimeConverter.snapToGrid(
            ms, st.bpm, st.gridResolution, st.timeSignatureDenominator
        )
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
            studioRepo.moveTrack(trackId, snapped)
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
                val clips = st.audioClips[track.id]
                if (clips != null) {
                    for (clip in clips) {
                        audioEngine.setTrackMuted(
                            clip.clipId.toInt(),
                            trackMuted || clip.isMuted
                        )
                    }
                }
            } else if (track.isDrum) {
                // Re-push pattern with updated mute state
                val pattern = st.drumPatterns[track.id] ?: continue
                pushDrumClipsToEngine(track.copy(isMuted = trackMuted), pattern)
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
                pushDrumClipsToEngine(track.copy(volume = clamped), pattern)
            }
        } else if (track != null && track.isAudio) {
            // Update native engine immediately for instant feedback
            val clips = _state.value.audioClips[trackId]
            if (clips != null) {
                for (clip in clips) {
                    val activeTake = clip.activeTake ?: continue
                    audioEngine.setTrackVolume(clip.clipId.toInt(), activeTake.volume * clamped)
                }
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
                audioClips = it.audioClips - trackId,
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
                val st = _state.value
                // Always default to 1/16 resolution (industry standard)
                val defaultRes = 16
                val stepsPerBar = MusicalTimeConverter.stepsPerBar(
                    defaultRes, st.timeSignatureNumerator, st.timeSignatureDenominator
                )
                val pattern = drumRepo.ensurePatternExists(trackId, stepsPerBar)
                val clipEntities = drumRepo.ensureClipsExist(pattern.id)
                val clipUiStates = clipEntities.map {
                    DrumClipUiState(
                        clipId = it.id, offsetMs = it.offsetMs,
                        patternId = pattern.id,
                        stepsPerBar = pattern.stepsPerBar,
                        bars = pattern.bars
                    )
                }

                _state.update {
                    it.copy(
                        drumPatterns = it.drumPatterns + (trackId to DrumPatternUiState(
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
     * Loads per-clip pattern data and starts Flow observation for each track's
     * selected clip so step changes push to the engine.
     */
    private suspend fun loadDrumPatterns(
        tracks: List<com.example.nightjar.data.db.entity.TrackEntity>
    ) {
        val drumTracks = tracks.filter { it.isDrum }
        if (drumTracks.isEmpty()) return

        if (!ensureSoundFontLoaded()) return

        val patterns = mutableMapOf<Long, DrumPatternUiState>()
        for (track in drumTracks) {
            // Load all clips for this track (across all patterns)
            val clipEntities = drumRepo.getClipsForTrack(track.id)
            val clipUiStates = if (clipEntities.isEmpty()) {
                // Ensure at least one pattern + clip exists
                val pattern = drumRepo.ensurePatternExists(track.id)
                val clips = drumRepo.ensureClipsExist(pattern.id)
                val steps = drumRepo.getSteps(pattern.id)
                clips.map {
                    DrumClipUiState(
                        clipId = it.id, offsetMs = it.offsetMs,
                        patternId = pattern.id,
                        stepsPerBar = pattern.stepsPerBar,
                        bars = pattern.bars, steps = steps
                    )
                }
            } else {
                // Load each clip with its own pattern and steps
                clipEntities.map { clip ->
                    val pat = drumRepo.getPatternById(clip.patternId)
                    val steps = drumRepo.getSteps(clip.patternId)
                    DrumClipUiState(
                        clipId = clip.id, offsetMs = clip.offsetMs,
                        patternId = clip.patternId,
                        stepsPerBar = pat?.stepsPerBar ?: 16,
                        bars = pat?.bars ?: 1, steps = steps
                    )
                }
            }

            // Derive initial view resolution from the first clip's stepsPerBar
            val currentState = _state.value
            val firstClip = clipUiStates.firstOrNull()
            val initialViewRes = if (firstClip != null && currentState.timeSignatureNumerator > 0) {
                (firstClip.stepsPerBar * currentState.timeSignatureDenominator) / currentState.timeSignatureNumerator
            } else 16
            val drumState = DrumPatternUiState(
                clips = clipUiStates,
                viewResolution = initialViewRes
            )
            patterns[track.id] = drumState
            pushDrumClipsToEngine(track, drumState)
            // Observe the first clip's pattern for step changes
            if (clipUiStates.isNotEmpty()) {
                observeDrumPattern(track.id, clipUiStates.first().patternId)
            }
        }

        _state.update { it.copy(drumPatterns = it.drumPatterns + patterns) }
        audioEngine.setDrumSequencerEnabled(true)
    }

    /** Start observing the selected clip's pattern steps via Flow. */
    private fun observeDrumPattern(trackId: Long, patternId: Long) {
        drumPatternJobs[trackId]?.cancel()
        drumPatternJobs[trackId] = viewModelScope.launch {
            drumRepo.observeSteps(patternId).collect { steps ->
                val drumState = _state.value.drumPatterns[trackId] ?: return@collect
                val selectedIdx = drumState.selectedClipIndex
                val updatedClips = drumState.clips.toMutableList()
                if (selectedIdx in updatedClips.indices &&
                    updatedClips[selectedIdx].patternId == patternId) {
                    updatedClips[selectedIdx] = updatedClips[selectedIdx].copy(steps = steps)
                }
                _state.update {
                    it.copy(
                        drumPatterns = it.drumPatterns + (trackId to drumState.copy(clips = updatedClips))
                    )
                }
                val track = _state.value.tracks.find { it.id == trackId } ?: return@collect
                pushDrumClipsToEngine(track, _state.value.drumPatterns[trackId]!!)
            }
        }
    }

    /** Push per-clip drum pattern data to the C++ step sequencer. */
    private fun pushDrumClipsToEngine(
        track: com.example.nightjar.data.db.entity.TrackEntity,
        drumState: DrumPatternUiState
    ) {
        val clips = drumState.clips
        if (clips.isEmpty()) return

        val clipStepsPerBar = IntArray(clips.size)
        val clipBars = IntArray(clips.size)
        val clipBeatsPerBar = IntArray(clips.size)
        val clipOffsetsMs = LongArray(clips.size)
        val clipHitCounts = IntArray(clips.size)

        val allStepIndices = mutableListOf<Int>()
        val allDrumNotes = mutableListOf<Int>()
        val allVelocities = mutableListOf<Float>()

        val beatsPerBar = _state.value.timeSignatureNumerator

        for (i in clips.indices) {
            val clip = clips[i]
            clipStepsPerBar[i] = clip.stepsPerBar
            clipBars[i] = clip.bars
            clipBeatsPerBar[i] = beatsPerBar
            clipOffsetsMs[i] = clip.offsetMs
            clipHitCounts[i] = clip.steps.size

            for (step in clip.steps) {
                allStepIndices.add(step.stepIndex)
                allDrumNotes.add(step.drumNote)
                allVelocities.add(step.velocity)
            }
        }

        audioEngine.updateDrumPatternClips(
            volume = track.volume,
            muted = track.isMuted,
            clipStepsPerBar = clipStepsPerBar,
            clipBars = clipBars,
            clipBeatsPerBar = clipBeatsPerBar,
            clipOffsetsMs = clipOffsetsMs,
            clipHitCounts = clipHitCounts,
            hitStepIndices = allStepIndices.toIntArray(),
            hitDrumNotes = allDrumNotes.toIntArray(),
            hitVelocities = allVelocities.toFloatArray()
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
        val st = _state.value
        val oldNum = st.timeSignatureNumerator
        val oldDen = st.timeSignatureDenominator

        _state.update {
            it.copy(
                timeSignatureNumerator = numerator,
                timeSignatureDenominator = denominator
            )
        }
        audioEngine.setMetronomeBeatsPerBar(numerator)
        val ideaId = currentIdeaId ?: return
        viewModelScope.launch {
            try {
                ideaRepo.updateTimeSignature(ideaId, numerator, denominator)

                // Derive each pattern's conceptual resolution from its current stepsPerBar,
                // then recalculate for the new time signature
                val updatedPatterns = mutableMapOf<Long, DrumPatternUiState>()
                for ((trackId, drumState) in st.drumPatterns) {
                    val updatedClips = drumState.clips.map { clip ->
                        val conceptualRes = if (oldNum > 0) {
                            (clip.stepsPerBar * oldDen) / oldNum
                        } else 16
                        val newStepsPerBar = MusicalTimeConverter.stepsPerBar(
                            conceptualRes, numerator, denominator
                        )
                        drumRepo.updatePatternGrid(clip.patternId, newStepsPerBar, clip.bars)
                        clip.copy(stepsPerBar = newStepsPerBar)
                    }
                    updatedPatterns[trackId] = drumState.copy(clips = updatedClips)
                }
                _state.update { it.copy(drumPatterns = it.drumPatterns + updatedPatterns) }

                // Re-push all drum patterns with new beatsPerBar
                val latest = _state.value
                for ((trackId, drumState) in latest.drumPatterns) {
                    val track = latest.tracks.find { it.id == trackId } ?: continue
                    pushDrumClipsToEngine(track, drumState)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to persist time signature: ${e.message}")
            }
        }
    }

    /** Update project grid resolution (sub-beat density for snap). */
    private fun setGridResolution(resolution: Int) {
        val clamped = resolution.coerceIn(4, 32)
        _state.update { it.copy(gridResolution = clamped) }
        val ideaId = currentIdeaId ?: return
        viewModelScope.launch {
            try {
                ideaRepo.updateGridResolution(ideaId, clamped)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to persist grid resolution: ${e.message}")
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

    /** Change the number of bars for the selected clip's pattern. */
    private fun setPatternBars(trackId: Long, bars: Int) {
        val clamped = bars.coerceIn(1, 8)
        val drumState = _state.value.drumPatterns[trackId] ?: return
        val selectedClip = drumState.selectedClip ?: return
        viewModelScope.launch {
            try {
                drumRepo.updatePatternGrid(selectedClip.patternId, selectedClip.stepsPerBar, clamped)
                // Update local state immediately
                val updatedClips = drumState.clips.toMutableList()
                val idx = drumState.selectedClipIndex
                if (idx in updatedClips.indices) {
                    updatedClips[idx] = updatedClips[idx].copy(bars = clamped)
                }
                _state.update {
                    it.copy(
                        drumPatterns = it.drumPatterns + (trackId to drumState.copy(clips = updatedClips))
                    )
                }
                // Re-push to engine with new bar count
                val track = _state.value.tracks.find { it.id == trackId } ?: return@launch
                pushDrumClipsToEngine(track, _state.value.drumPatterns[trackId]!!)
            } catch (e: Exception) {
                _effects.emit(StudioEffect.ShowError(e.message ?: "Failed to update bar count."))
            }
        }
    }

    /**
     * Change the view resolution for a drum track. Resolution is a view filter:
     * the DB stores the highest-ever resolution. Lowering resolution just changes
     * which columns are visible (stride increases). Raising resolution upscales
     * any clips whose stepsPerBar is too small (lossless integer scaling).
     */
    private fun setPatternResolution(trackId: Long, resolution: Int) {
        val st = _state.value
        val drumState = st.drumPatterns[trackId] ?: return
        val viewStepsPerBar = MusicalTimeConverter.stepsPerBar(
            resolution, st.timeSignatureNumerator, st.timeSignatureDenominator
        )

        viewModelScope.launch {
            try {
                // Upscale any clips whose storage resolution is below the new view
                val updatedClips = drumState.clips.toMutableList()
                var needsEnginePush = false
                for (i in updatedClips.indices) {
                    val clip = updatedClips[i]
                    if (clip.stepsPerBar < viewStepsPerBar) {
                        drumRepo.remapPatternResolution(
                            clip.patternId, clip.stepsPerBar, viewStepsPerBar, clip.bars
                        )
                        updatedClips[i] = clip.copy(stepsPerBar = viewStepsPerBar)
                        needsEnginePush = true
                    }
                }
                // Update view resolution (always, even if no upscale)
                _state.update {
                    it.copy(
                        drumPatterns = it.drumPatterns + (trackId to drumState.copy(
                            clips = updatedClips,
                            viewResolution = resolution
                        ))
                    )
                }
                if (needsEnginePush) {
                    val track = _state.value.tracks.find { it.id == trackId } ?: return@launch
                    pushDrumClipsToEngine(track, _state.value.drumPatterns[trackId]!!)
                }
            } catch (e: Exception) {
                _effects.emit(StudioEffect.ShowError(e.message ?: "Failed to change resolution."))
            }
        }
    }

    /** Switch the selected drum clip for a track and re-observe its pattern. */
    private fun selectDrumClip(trackId: Long, clipIndex: Int) {
        val drumState = _state.value.drumPatterns[trackId] ?: return
        val clamped = clipIndex.coerceIn(0, (drumState.clips.size - 1).coerceAtLeast(0))
        _state.update {
            it.copy(
                drumPatterns = it.drumPatterns + (trackId to drumState.copy(selectedClipIndex = clamped))
            )
        }
        val selectedClip = drumState.clips.getOrNull(clamped) ?: return
        observeDrumPattern(trackId, selectedClip.patternId)
    }

    // ── Clip creation ────────────────────────────────────────────────────

    private fun createDrumClip(trackId: Long, offsetMs: Long) {
        _state.update { it.copy(expandedClipState = null) }
        viewModelScope.launch {
            try {
                val snapped = snapIfEnabled(offsetMs).coerceAtLeast(0L)
                drumRepo.createEmptyClip(trackId, snapped)
                reloadClipsForTrack(trackId)
            } catch (e: Exception) {
                _effects.emit(StudioEffect.ShowError(e.message ?: "Failed to create clip"))
            }
        }
    }

    private fun createMidiClip(trackId: Long, offsetMs: Long) {
        _state.update { it.copy(expandedClipState = null) }
        viewModelScope.launch {
            try {
                val snapped = snapIfEnabled(offsetMs).coerceAtLeast(0L)
                midiRepo.insertClip(
                    com.example.nightjar.data.db.entity.MidiClipEntity(
                        trackId = trackId,
                        offsetMs = snapped,
                        sortIndex = (_state.value.midiTracks[trackId]?.clips?.size ?: 0)
                    )
                )
                midiRepo.recomputeTrackDuration(trackId)
                reloadMidiTrackState(trackId)
            } catch (e: Exception) {
                _effects.emit(StudioEffect.ShowError(e.message ?: "Failed to create clip"))
            }
        }
    }

    // ── Drum clips ──────────────────────────────────────────────────────

    private fun duplicateClip(trackId: Long, clipId: Long, linked: Boolean) {
        viewModelScope.launch {
            try {
                val drumState = _state.value.drumPatterns[trackId]
                val clip = drumState?.clips?.find { it.clipId == clipId }
                val st = _state.value
                val bars = clip?.bars ?: 1
                val patternDurationMs = com.example.nightjar.audio.MusicalTimeConverter
                    .msPerMeasure(st.bpm, st.timeSignatureNumerator, st.timeSignatureDenominator)
                    .toLong() * bars
                drumRepo.duplicateClip(clipId, patternDurationMs, linked = linked)
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
                val drumState = st.drumPatterns[drag.trackId]
                val clamped = if (drumState != null) {
                    val clip = drumState.clips.find { it.clipId == drag.clipId }
                    val clipDurationMs = MusicalTimeConverter
                        .msPerMeasure(st.bpm, st.timeSignatureNumerator, st.timeSignatureDenominator)
                        .toLong() * (clip?.bars ?: 1)
                    val siblings = drumState.clips
                        .filter { it.clipId != drag.clipId }
                        .map { sib ->
                            val sibDur = MusicalTimeConverter
                                .msPerMeasure(st.bpm, st.timeSignatureNumerator, st.timeSignatureDenominator)
                                .toLong() * sib.bars
                            sib.offsetMs to sib.offsetMs + sibDur
                        }
                    clampToNoOverlap(drag.clipId, snapped, clipDurationMs, siblings)
                } else snapped
                st.copy(clipDragState = drag.copy(previewOffsetMs = clamped))
            } ?: st
        }
    }

    private fun finishDragClip(trackId: Long, clipId: Long, newOffsetMs: Long) {
        val st = _state.value
        val drumState = st.drumPatterns[trackId]
        val finalOffset = if (drumState != null) {
            val snapped = snapIfEnabled(newOffsetMs).coerceAtLeast(0L)
            val clip = drumState.clips.find { it.clipId == clipId }
            val clipDurationMs = MusicalTimeConverter
                .msPerMeasure(st.bpm, st.timeSignatureNumerator, st.timeSignatureDenominator)
                .toLong() * (clip?.bars ?: 1)
            val siblings = drumState.clips
                .filter { it.clipId != clipId }
                .map { sib ->
                    val sibDur = MusicalTimeConverter
                        .msPerMeasure(st.bpm, st.timeSignatureNumerator, st.timeSignatureDenominator)
                        .toLong() * sib.bars
                    sib.offsetMs to sib.offsetMs + sibDur
                }
            clampToNoOverlap(clipId, snapped, clipDurationMs, siblings)
        } else newOffsetMs
        _state.update { it.copy(clipDragState = null) }
        moveClip(trackId, clipId, finalOffset)
    }

    private fun cancelDragClip() {
        _state.update { it.copy(clipDragState = null) }
    }

    /**
     * Clamp a proposed offset so the dragged clip never overlaps its siblings.
     * Snaps to the nearest gap edge when an overlap would occur.
     */
    private fun clampToNoOverlap(
        draggedClipId: Long,
        proposedOffsetMs: Long,
        draggedDurationMs: Long,
        siblingBounds: List<Pair<Long, Long>>
    ): Long {
        var clamped = proposedOffsetMs.coerceAtLeast(0L)
        val draggedEnd = clamped + draggedDurationMs
        for ((sibStart, sibEnd) in siblingBounds.sortedBy { it.first }) {
            if (clamped < sibEnd && draggedEnd > sibStart) {
                val snapBefore = sibStart - draggedDurationMs
                val snapAfter = sibEnd
                clamped = if (abs(clamped - snapBefore) < abs(clamped - snapAfter))
                    snapBefore.coerceAtLeast(0L) else snapAfter
            }
        }
        return clamped
    }

    /** Reload all clips for a drum track (per-clip pattern data) and re-push to engine. */
    private suspend fun reloadClipsForTrack(trackId: Long) {
        val clipEntities = drumRepo.getClipsForTrack(trackId)
        val clipUiStates = clipEntities.map { clip ->
            val pat = drumRepo.getPatternById(clip.patternId)
            val steps = drumRepo.getSteps(clip.patternId)
            DrumClipUiState(
                clipId = clip.id, offsetMs = clip.offsetMs,
                patternId = clip.patternId,
                stepsPerBar = pat?.stepsPerBar ?: 16,
                bars = pat?.bars ?: 1, steps = steps
            )
        }

        val oldState = _state.value.drumPatterns[trackId]
        val selectedIdx = (oldState?.selectedClipIndex ?: 0)
            .coerceIn(0, (clipUiStates.size - 1).coerceAtLeast(0))

        val newDrumState = DrumPatternUiState(clips = clipUiStates, selectedClipIndex = selectedIdx)

        // Linkage: drum clips link via shared patternId. Group size = count
        // of clips sharing that pattern across this track (cross-track shared
        // patterns are not created by any current flow).
        val patternCounts = clipEntities.groupingBy { it.patternId }.eachCount()
        val linkage = clipEntities.associate { clip ->
            clip.id to ClipLinkage.Drum(
                GroupKey.Drum(clip.patternId),
                patternCounts[clip.patternId] ?: 1
            )
        }

        _state.update {
            it.copy(
                drumPatterns = it.drumPatterns + (trackId to newDrumState),
                drumClipLinkage = it.drumClipLinkage
                    .filterKeys { id -> clipEntities.none { it.id == id } } + linkage
            )
        }

        // Re-observe the selected clip's pattern
        if (clipUiStates.isNotEmpty()) {
            observeDrumPattern(trackId, clipUiStates[selectedIdx].patternId)
        }

        // Re-push to engine
        val track = _state.value.tracks.find { it.id == trackId } ?: return
        pushDrumClipsToEngine(track, newDrumState)
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
                // Create a default clip at offset 0 for the new track
                midiRepo.ensureClipExists(trackId)

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
     * Load MIDI clip and note data for all MIDI tracks. Called once during [load].
     * Starts Flow observation for each track so note changes push to the engine.
     */
    private suspend fun loadMidiTracks(
        tracks: List<com.example.nightjar.data.db.entity.TrackEntity>
    ) {
        val midiTracks = tracks.filter { it.isMidi }
        if (midiTracks.isEmpty()) return

        if (!ensureSoundFontLoaded()) return

        val midiTrackStates = mutableMapOf<Long, MidiTrackUiState>()
        val linkage = mutableMapOf<Long, ClipLinkage.Midi>()
        for (track in midiTracks) {
            midiTrackStates[track.id] = buildMidiTrackUiState(track)
            val rawClips = midiRepo.getClipsForTrack(track.id)
            val sourceCounts = rawClips.groupingBy { it.sourceClipId ?: it.id }.eachCount()
            for (clip in rawClips) {
                val sourceId = clip.sourceClipId ?: clip.id
                linkage[clip.id] = ClipLinkage.Midi(
                    GroupKey.Midi(sourceId),
                    sourceCounts[sourceId] ?: 1
                )
            }
            observeMidiNotes(track.id)
        }

        _state.update {
            it.copy(
                midiTracks = it.midiTracks + midiTrackStates,
                midiClipLinkage = it.midiClipLinkage + linkage
            )
        }
        pushAllMidiToEngine()
        audioEngine.setMidiSequencerEnabled(true)
    }

    /** Build a full MidiTrackUiState from DB data. */
    private suspend fun buildMidiTrackUiState(
        track: com.example.nightjar.data.db.entity.TrackEntity
    ): MidiTrackUiState {
        val clips = midiRepo.getClipsForTrack(track.id)
        val clipStates = clips.map { clip ->
            MidiClipUiState(
                clipId = clip.id,
                offsetMs = clip.offsetMs,
                notes = midiRepo.getNotesForClip(clip.id)
            )
        }
        val allNotes = clipStates.flatMap { it.notes }
        return MidiTrackUiState(
            notes = allNotes,
            clips = clipStates,
            midiProgram = track.midiProgram,
            instrumentName = gmInstrumentName(track.midiProgram)
        )
    }

    /** Start observing MIDI notes for a track via Flow. Rebuilds clip state on change. */
    private fun observeMidiNotes(trackId: Long) {
        midiNoteJobs[trackId]?.cancel()
        midiNoteJobs[trackId] = viewModelScope.launch {
            midiRepo.observeNotes(trackId).collect {
                // Rebuild the full track state including clips, preserving selectedClipId
                val track = _state.value.tracks.find { t -> t.id == trackId } ?: return@collect
                val existing = _state.value.midiTracks[trackId]
                val updatedState = buildMidiTrackUiState(track)
                    .copy(selectedClipId = existing?.selectedClipId)
                _state.update { st ->
                    st.copy(
                        midiTracks = st.midiTracks + (trackId to updatedState)
                    )
                }
                pushAllMidiToEngine()
            }
        }
    }

    /** Reload MIDI clip data for a single track (after clip operations). */
    private fun reloadMidiTrackState(trackId: Long) {
        viewModelScope.launch {
            val track = _state.value.tracks.find { it.id == trackId } ?: return@launch
            val existing = _state.value.midiTracks[trackId]
            val updatedState = buildMidiTrackUiState(track)
                .copy(selectedClipId = existing?.selectedClipId)
            val rawClips = midiRepo.getClipsForTrack(trackId)
            val sourceCounts = rawClips.groupingBy { it.sourceClipId ?: it.id }.eachCount()
            val linkage = rawClips.associate { clip ->
                val sourceId = clip.sourceClipId ?: clip.id
                clip.id to ClipLinkage.Midi(
                    GroupKey.Midi(sourceId),
                    sourceCounts[sourceId] ?: 1
                )
            }
            _state.update { st ->
                st.copy(
                    midiTracks = st.midiTracks + (trackId to updatedState),
                    midiClipLinkage = st.midiClipLinkage
                        .filterKeys { id -> rawClips.none { it.id == id } } + linkage
                )
            }
            pushAllMidiToEngine()
        }
    }

    /**
     * Convert all MIDI tracks to flat arrays and push to the C++ engine.
     * Applies clip offsets so notes play at their absolute timeline positions.
     */
    private fun pushAllMidiToEngine() {
        val st = _state.value
        val midiTrackEntries = st.tracks.filter { it.isMidi }
        if (midiTrackEntries.isEmpty()) {
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

        val allFrames = mutableListOf<Long>()
        val allChannels = mutableListOf<Int>()
        val allNotes = mutableListOf<Int>()
        val allVelocities = mutableListOf<Int>()

        for (i in midiTrackEntries.indices) {
            val track = midiTrackEntries[i]
            val midiState = st.midiTracks[track.id]

            channels[i] = track.midiChannel
            programs[i] = track.midiProgram
            volumes[i] = track.volume
            muted[i] = track.isMuted ||
                (anySoloed && track.id !in st.soloedTrackIds)

            // Generate events from clips with absolute positions
            val events = generateMidiEventsFromClips(
                midiState?.clips ?: emptyList(),
                track.midiChannel
            )
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
     * Generate sorted noteOn/noteOff event pairs from MIDI clips.
     * Applies clip offsets to convert clip-relative positions to absolute timeline positions.
     */
    private fun generateMidiEventsFromClips(
        clips: List<MidiClipUiState>,
        channel: Int
    ): List<MidiEventFlat> {
        val events = mutableListOf<MidiEventFlat>()
        val sampleRate = 44100L

        for (clip in clips) {
            for (note in clip.notes) {
                val absoluteStartMs = clip.offsetMs + note.startMs
                val absoluteEndMs = absoluteStartMs + note.durationMs
                val startFrame = (absoluteStartMs * sampleRate) / 1000
                val endFrame = (absoluteEndMs * sampleRate) / 1000
                val velocity = (note.velocity * 127).toInt().coerceIn(1, 127)

                events.add(MidiEventFlat(startFrame, channel, note.pitch, velocity))
                events.add(MidiEventFlat(endFrame, channel, note.pitch, 0))
            }
        }

        events.sortWith(
            compareBy<MidiEventFlat> { it.framePos }
                .thenBy { if (it.velocity == 0) 0 else 1 }
        )
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

    /** Open full-screen drum editor for a drum track. */
    private fun openDrumEditor(trackId: Long, clipId: Long? = null) {
        viewModelScope.launch {
            _effects.emit(StudioEffect.NavigateToDrumEditor(trackId, clipId ?: 0L))
        }
    }

    /** Open piano roll for a track, optionally targeting a specific clip. */
    private fun openPianoRoll(trackId: Long, clipId: Long?) {
        viewModelScope.launch {
            // If no clip specified, use the first clip for the track
            val resolvedClipId = clipId ?: run {
                val clips = midiRepo.ensureClipExists(trackId)
                clips.first().id
            }
            _effects.emit(StudioEffect.NavigateToPianoRoll(trackId, resolvedClipId))
        }
    }

    // ── MIDI clip operations ──────────────────────────────────────────────

    private fun duplicateMidiClip(trackId: Long, clipId: Long, linked: Boolean) {
        viewModelScope.launch {
            try {
                val clip = midiRepo.getClipById(clipId) ?: return@launch
                val clips = _state.value.midiTracks[trackId]?.clips ?: return@launch
                val clipState = clips.find { it.clipId == clipId } ?: return@launch
                val durationMs = clipState.contentDurationMs.coerceAtLeast(
                    MusicalTimeConverter.msPerMeasure(
                        _state.value.bpm,
                        _state.value.timeSignatureNumerator,
                        _state.value.timeSignatureDenominator
                    ).toLong()
                )
                midiRepo.duplicateClip(clipId, durationMs, linked = linked)
                midiRepo.recomputeTrackDuration(trackId)
                reloadMidiTrackState(trackId)
            } catch (e: Exception) {
                _effects.emit(StudioEffect.ShowError("Failed to duplicate clip"))
            }
        }
    }

    private fun moveMidiClip(trackId: Long, clipId: Long, newOffsetMs: Long) {
        viewModelScope.launch {
            try {
                val snapped = snapIfEnabled(newOffsetMs).coerceAtLeast(0L)
                midiRepo.moveClip(clipId, snapped)
                midiRepo.recomputeTrackDuration(trackId)
                reloadMidiTrackState(trackId)
            } catch (e: Exception) {
                _effects.emit(StudioEffect.ShowError("Failed to move clip"))
            }
        }
    }

    private fun deleteMidiClip(trackId: Long, clipId: Long) {
        viewModelScope.launch {
            try {
                midiRepo.deleteClip(clipId)
                // Auto-create a new empty clip if that was the last one
                midiRepo.ensureClipExists(trackId)
                midiRepo.recomputeTrackDuration(trackId)
                reloadMidiTrackState(trackId)
            } catch (e: Exception) {
                _effects.emit(StudioEffect.ShowError("Failed to delete clip"))
            }
        }
    }

    private fun startDragMidiClip(trackId: Long, clipId: Long) {
        val midiState = _state.value.midiTracks[trackId] ?: return
        val clip = midiState.clips.find { it.clipId == clipId } ?: return
        _state.update {
            it.copy(
                midiClipDragState = MidiClipDragState(
                    trackId = trackId,
                    clipId = clipId,
                    originalOffsetMs = clip.offsetMs,
                    previewOffsetMs = clip.offsetMs
                )
            )
        }
    }

    private fun updateDragMidiClip(previewOffsetMs: Long) {
        _state.update { st ->
            val ds = st.midiClipDragState ?: return@update st
            val snapped = snapIfEnabled(previewOffsetMs).coerceAtLeast(0L)
            val midiState = st.midiTracks[ds.trackId]
            val measureMs = MusicalTimeConverter
                .msPerMeasure(st.bpm, st.timeSignatureNumerator, st.timeSignatureDenominator).toLong()
            val clamped = if (midiState != null) {
                val clip = midiState.clips.find { it.clipId == ds.clipId }
                val clipDurationMs = clip?.contentDurationMs?.coerceAtLeast(measureMs) ?: measureMs
                val siblings = midiState.clips
                    .filter { it.clipId != ds.clipId }
                    .map { sib ->
                        val sibDur = sib.contentDurationMs.coerceAtLeast(measureMs)
                        sib.offsetMs to sib.offsetMs + sibDur
                    }
                clampToNoOverlap(ds.clipId, snapped, clipDurationMs, siblings)
            } else snapped
            st.copy(midiClipDragState = ds.copy(previewOffsetMs = clamped))
        }
    }

    private fun finishDragMidiClip(trackId: Long, clipId: Long, newOffsetMs: Long) {
        val st = _state.value
        val midiState = st.midiTracks[trackId]
        val measureMs = MusicalTimeConverter
            .msPerMeasure(st.bpm, st.timeSignatureNumerator, st.timeSignatureDenominator).toLong()
        val finalOffset = if (midiState != null) {
            val snapped = snapIfEnabled(newOffsetMs).coerceAtLeast(0L)
            val clip = midiState.clips.find { it.clipId == clipId }
            val clipDurationMs = clip?.contentDurationMs?.coerceAtLeast(measureMs) ?: measureMs
            val siblings = midiState.clips
                .filter { it.clipId != clipId }
                .map { sib ->
                    val sibDur = sib.contentDurationMs.coerceAtLeast(measureMs)
                    sib.offsetMs to sib.offsetMs + sibDur
                }
            clampToNoOverlap(clipId, snapped, clipDurationMs, siblings)
        } else newOffsetMs
        _state.update { it.copy(midiClipDragState = null) }
        moveMidiClip(trackId, clipId, finalOffset)
    }

    private fun cancelDragMidiClip() {
        _state.update { it.copy(midiClipDragState = null) }
    }

    // ── Clip action panel ─────────────────────────────────────────────────

    private fun tapClip(trackId: Long, clipId: Long, clipType: String) {
        val current = _state.value.expandedClipState

        when {
            // Same clip, not flipped -> flip to action buttons
            current != null && current.trackId == trackId && current.clipId == clipId && !current.isFlipped -> {
                _state.update { it.copy(expandedClipState = current.copy(isFlipped = true)) }
            }
            // Same clip, flipped -> unflip (keep selected)
            current != null && current.trackId == trackId && current.clipId == clipId && current.isFlipped -> {
                _state.update { it.copy(expandedClipState = current.copy(isFlipped = false)) }
            }
            // No selection or different clip -> select with isFlipped = false
            else -> {
                _state.update {
                    it.copy(expandedClipState = ExpandedClipState(trackId, clipId, clipType, isFlipped = false))
                }
            }
        }

        // Auto-select drum clip for the drawer's pattern editor
        if (clipType == "drum") {
            val drumState = _state.value.drumPatterns[trackId] ?: return
            val clipIndex = drumState.clips.indexOfFirst { it.clipId == clipId }
            if (clipIndex >= 0 && clipIndex != drumState.selectedClipIndex) {
                selectDrumClip(trackId, clipIndex)
            }
        }

        // Auto-select MIDI clip for the drawer's inline piano roll
        if (clipType == "midi") {
            selectMidiClip(trackId, clipId)
        }
    }

    private fun selectMidiClip(trackId: Long, clipId: Long) {
        _state.update { st ->
            val current = st.midiTracks[trackId] ?: return@update st
            st.copy(midiTracks = st.midiTracks + (trackId to current.copy(selectedClipId = clipId)))
        }
    }

    private fun inlinePlaceNote(trackId: Long, clipId: Long, pitch: Int, startMs: Long, durationMs: Long) {
        viewModelScope.launch {
            midiRepo.addNote(clipId, trackId, pitch, startMs, durationMs)
            midiRepo.recomputeTrackDuration(trackId)
        }
    }

    private fun inlineMoveNote(trackId: Long, noteId: Long, newStartMs: Long, newPitch: Int) {
        viewModelScope.launch {
            midiRepo.updateNotePitch(noteId, newPitch.coerceIn(0, 127))
            val note = _state.value.midiTracks.values
                .flatMap { it.clips }
                .flatMap { it.notes }
                .find { it.id == noteId }
            val duration = note?.durationMs ?: 250L
            midiRepo.updateNoteTiming(noteId, newStartMs.coerceAtLeast(0), duration)
            midiRepo.recomputeTrackDuration(trackId)
        }
    }

    private fun inlineResizeNote(trackId: Long, noteId: Long, newDurationMs: Long) {
        viewModelScope.launch {
            val note = _state.value.midiTracks.values
                .flatMap { it.clips }
                .flatMap { it.notes }
                .find { it.id == noteId } ?: return@launch
            midiRepo.updateNoteTiming(noteId, note.startMs, newDurationMs.coerceAtLeast(50))
            midiRepo.recomputeTrackDuration(trackId)
        }
    }

    private fun inlineDeleteNote(trackId: Long, noteId: Long) {
        viewModelScope.launch {
            midiRepo.deleteNote(noteId)
            midiRepo.recomputeTrackDuration(trackId)
        }
    }

    private fun dismissClipPanel() {
        _state.update { it.copy(expandedClipState = null) }
    }

    private suspend fun reloadAndPrepare() {
        val ideaId = currentIdeaId ?: return
        val tracks = studioRepo.getTracks(ideaId)
        _state.update { it.copy(tracks = tracks) }
        loadAudioClips(tracks)
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
        audioEngine.setMetronomeEnabled(false)
        audioEngine.synthAllSoundsOff()
        audioEngine.removeAllTracks()
    }

    // ── Metronome ─────────────────────────────────────────────────────────

    private fun toggleMetronome() {
        val newEnabled = !_state.value.isMetronomeEnabled
        _state.update { it.copy(isMetronomeEnabled = newEnabled) }
        metronomePrefs.isEnabled = newEnabled
        syncMetronomeToEngine()
    }

    private fun setMetronomeVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        _state.update { it.copy(metronomeVolume = clamped) }
        audioEngine.setMetronomeVolume(clamped)
        metronomePrefs.volume = clamped
    }

    private fun setCountInBars(bars: Int) {
        _state.update { it.copy(countInBars = bars) }
        metronomePrefs.countInBars = bars
    }

    /**
     * Sync metronome config to the engine.
     * The C++ render thread already gates on transport_.playing,
     * so we only need to reflect the user's enabled preference here.
     */
    private fun syncMetronomeToEngine() {
        val st = _state.value
        audioEngine.setMetronomeEnabled(st.isMetronomeEnabled)
        audioEngine.setMetronomeVolume(st.metronomeVolume)
        audioEngine.setMetronomeBeatsPerBar(st.timeSignatureNumerator)
    }

    // ── Audio-clip Duplicate / Split / Unlink / Rename ───────────────────

    private fun duplicateAudioClip(trackId: Long, clipId: Long, linked: Boolean) {
        viewModelScope.launch {
            try {
                studioRepo.duplicateAudioClip(clipId, linked)
                studioRepo.recomputeAudioTrackDuration(trackId)
                reloadAudioClips(trackId)
            } catch (e: Exception) {
                _effects.emit(StudioEffect.ShowError(e.message ?: "Failed to duplicate clip."))
            }
        }
    }

    private fun startSplitMode(clipId: Long, clipType: String) {
        _state.update {
            it.copy(
                splitModeClipId = clipId,
                splitModeClipType = clipType,
                splitPositionMs = it.cursorPositionMs,
                splitValid = false
            )
        }
        updateSplitPosition(_state.value.cursorPositionMs)
    }

    private fun cancelSplit() {
        _state.update {
            it.copy(
                splitModeClipId = null,
                splitModeClipType = null,
                splitPositionMs = null,
                splitValid = false
            )
        }
    }

    private fun updateSplitPosition(timelinePositionMs: Long) {
        val st = _state.value
        val clipId = st.splitModeClipId ?: return
        val type = st.splitModeClipType ?: return
        val snapped = if (st.isSnapEnabled) snapIfEnabled(timelinePositionMs) else timelinePositionMs

        val (valid, clipStart, clipEnd) = resolveClipWindow(type, clipId) ?: run {
            _state.update { it.copy(splitPositionMs = snapped, splitValid = false) }
            return
        }
        val isValid = snapped > clipStart && snapped < clipEnd
        _state.update { it.copy(splitPositionMs = snapped, splitValid = isValid) }
    }

    private fun resolveClipWindow(clipType: String, clipId: Long): Triple<Boolean, Long, Long>? {
        val st = _state.value
        return when (clipType) {
            "audio" -> {
                val clip = st.audioClips.values.flatten().firstOrNull { it.clipId == clipId } ?: return null
                val end = clip.offsetMs + clip.effectiveDurationMs
                Triple(true, clip.offsetMs, end)
            }
            "midi" -> {
                val clip = st.midiTracks.values.flatMap { it.clips }
                    .firstOrNull { it.clipId == clipId } ?: return null
                val end = clip.offsetMs + clip.contentDurationMs
                Triple(true, clip.offsetMs, end)
            }
            "drum" -> {
                val clip = st.drumPatterns.values.flatMap { it.clips }
                    .firstOrNull { it.clipId == clipId } ?: return null
                val measure = com.example.nightjar.audio.MusicalTimeConverter
                    .msPerMeasure(st.bpm, st.timeSignatureNumerator, st.timeSignatureDenominator)
                    .toLong() * clip.bars
                Triple(true, clip.offsetMs, clip.offsetMs + measure)
            }
            else -> null
        }
    }

    private fun confirmSplit() {
        val st = _state.value
        val clipId = st.splitModeClipId ?: return
        val type = st.splitModeClipType ?: return
        val timelinePos = st.splitPositionMs ?: return
        if (!st.splitValid) return

        viewModelScope.launch {
            try {
                when (type) {
                    "audio" -> splitAudioClipAt(clipId, timelinePos)
                    "midi" -> splitMidiClipAt(clipId, timelinePos)
                    "drum" -> splitDrumClipAt(clipId, timelinePos)
                }
                cancelSplit()
            } catch (e: Exception) {
                _effects.emit(StudioEffect.ShowError(e.message ?: "Failed to split clip."))
                cancelSplit()
            }
        }
    }

    private suspend fun splitAudioClipAt(clipId: Long, timelinePos: Long) {
        val clip = _state.value.audioClips.values.flatten().firstOrNull { it.clipId == clipId } ?: return
        val take = clip.activeTake ?: return
        val sourceSplitMs = (timelinePos - clip.offsetMs) + take.trimStartMs
        studioRepo.splitAudioClip(clipId, sourceSplitMs)
        studioRepo.recomputeAudioTrackDuration(clip.trackId)
        reloadAudioClips(clip.trackId)
    }

    private suspend fun splitMidiClipAt(clipId: Long, timelinePos: Long) {
        val trackEntry = _state.value.midiTracks.entries
            .firstOrNull { entry -> entry.value.clips.any { it.clipId == clipId } } ?: return
        val trackId = trackEntry.key
        val clip = trackEntry.value.clips.first { it.clipId == clipId }
        val sourceSplitMs = timelinePos - clip.offsetMs
        midiRepo.splitMidiClip(clipId, sourceSplitMs)
        midiRepo.recomputeTrackDuration(trackId)
        reloadMidiTrackState(trackId)
    }

    private suspend fun splitDrumClipAt(clipId: Long, timelinePos: Long) {
        val trackEntry = _state.value.drumPatterns.entries
            .firstOrNull { entry -> entry.value.clips.any { it.clipId == clipId } } ?: return
        val trackId = trackEntry.key
        val clip = trackEntry.value.clips.first { it.clipId == clipId }
        val st = _state.value
        val msPerStep = com.example.nightjar.audio.MusicalTimeConverter
            .msPerMeasure(st.bpm, st.timeSignatureNumerator, st.timeSignatureDenominator)
            .toLong() / clip.stepsPerBar.coerceAtLeast(1)
        val stepOffset = ((timelinePos - clip.offsetMs) / msPerStep.coerceAtLeast(1L)).toInt()
        drumRepo.splitClip(clipId, stepOffset, msPerStep)
        reloadClipsForTrack(trackId)
    }

    private fun unlinkClip(clipId: Long, clipType: String) {
        viewModelScope.launch {
            try {
                when (clipType) {
                    "audio" -> {
                        val trackId = _state.value.audioClips.entries
                            .firstOrNull { entry -> entry.value.any { it.clipId == clipId } }?.key
                        studioRepo.unlinkAudioClip(clipId)
                        trackId?.let { reloadAudioClips(it) }
                    }
                    "midi" -> {
                        val trackId = _state.value.midiTracks.entries
                            .firstOrNull { entry -> entry.value.clips.any { it.clipId == clipId } }?.key
                        midiRepo.unlinkClip(clipId)
                        trackId?.let { reloadMidiTrackState(it) }
                    }
                    "drum" -> {
                        val trackId = _state.value.drumPatterns.entries
                            .firstOrNull { entry -> entry.value.clips.any { it.clipId == clipId } }?.key
                        drumRepo.unlinkClip(clipId)
                        trackId?.let { reloadClipsForTrack(it) }
                    }
                }
                _effects.emit(StudioEffect.ShowStatus("Unlinked from group."))
            } catch (e: Exception) {
                _effects.emit(StudioEffect.ShowError(e.message ?: "Failed to unlink clip."))
            }
        }
    }

    private fun confirmRenameClip(newName: String) {
        val st = _state.value
        val clipId = st.renamingClipId ?: return
        val type = st.renamingClipType ?: return
        viewModelScope.launch {
            try {
                when (type) {
                    "audio" -> {
                        studioRepo.renameClip(clipId, newName)
                        val trackId = _state.value.audioClips.entries
                            .firstOrNull { entry -> entry.value.any { it.clipId == clipId } }?.key
                        trackId?.let { reloadAudioClips(it) }
                    }
                    // MIDI and drum clips don't currently carry a displayName
                    // field; rename is a no-op until those entities gain one.
                }
            } catch (e: Exception) {
                _effects.emit(StudioEffect.ShowError(e.message ?: "Failed to rename clip."))
            } finally {
                _state.update {
                    it.copy(
                        renamingClipId = null,
                        renamingClipType = null,
                        renamingClipCurrentName = ""
                    )
                }
            }
        }
    }
}
