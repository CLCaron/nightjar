# Audio Clips -- Design Spec

## Summary

Restructure audio tracks from a flat takes model (Track -> Takes) to a clip-based model (Track -> Clips -> Takes), aligning audio tracks with how MIDI and drum tracks already work. This gives users DAW-style song structure within a single audio track (e.g., verse/chorus/bridge clips on a "Main Vocals" track) while supporting multiple takes per clip for auditioning alternatives.

## Current Architecture (what we're replacing)

- `TrackEntity` has `audioFileName` (original recording)
- `TakeEntity` belongs to a track via `trackId` FK
- Each take has its own `offsetMs`, `trimStartMs`, `trimEndMs`, `volume`, `isMuted`
- All unmuted takes play simultaneously (layered in the C++ mixer)
- Lazy promotion: tracks start with 0 takes; first arm promotes track audio to "Take 1"
- Track waveform renders from `track.audioFileName` only -- does NOT reflect later takes
- Takes are shown in a separate expandable drawer toggled by [T] button in track drawer
- Engine IDs: `trackId * 1000 + takeSortIndex`

### Known bugs this fixes
- **Track waveform only shows initial recording**: `TimelineTrackLane` renders `NjWaveform(audioFile = getAudioFile(track.audioFileName))`. New takes at different positions are invisible at the track level. Track lane width is based on the original recording duration, not the actual extent of all takes.

## New Architecture

### Data Model

```
AudioClipEntity (new table: audio_clips)
  id: Long (PK, auto-generate)
  trackId: Long (FK to tracks, cascade delete)
  offsetMs: Long          -- timeline position
  displayName: String     -- "Clip 1", "Verse", etc.
  sortIndex: Int          -- ordering among clips in the track
  isMuted: Boolean        -- mute the entire clip
  createdAtEpochMs: Long

TakeEntity (modified table: takes)
  id: Long (PK, auto-generate)
  clipId: Long (FK to audio_clips, cascade delete)  -- CHANGED from trackId
  audioFileName: String
  displayName: String     -- "Take 1", "Take 2", etc.
  sortIndex: Int          -- ordering within clip
  durationMs: Long
  trimStartMs: Long
  trimEndMs: Long
  isActive: Boolean       -- only ONE active per clip (replaces isMuted)
  volume: Float
  createdAtEpochMs: Long
  -- REMOVED: offsetMs (clip owns position)
  -- REMOVED: isMuted (replaced by isActive)
```

**Key rules:**
- Exactly one take per clip has `isActive = true`
- Clips on the same track do NOT overlap
- A clip's effective duration = active take's `durationMs - trimStartMs - trimEndMs`
- A track's total timeline extent = max(`clip.offsetMs + clip.effectiveDuration`) across all clips

### Schema Migration (v12)

1. Create `audio_clips` table
2. For each existing audio track that has takes:
   - For each take: create an `AudioClipEntity` with `offsetMs = take.offsetMs`, insert the take under it with `isActive = true`, remove `offsetMs` from take
3. For each existing audio track with no takes but with `audioFileName`:
   - Create one clip at `track.offsetMs`, create one take from `track.audioFileName` with `isActive = true`
4. Modify `takes` table: add `clipId` FK, add `isActive`, drop `offsetMs`, drop `isMuted`
5. Each migrated take becomes a single-take clip, preserving all existing behavior

### Engine Loading

The C++ TrackMixer does not need to know about clips. The Kotlin layer flattens:

```
For each audio track:
  For each unmuted clip:
    Find the active take
    engineId = clipId  (each clip produces exactly one audio stream)
    audioEngine.addTrack(
      trackId = engineId,
      filePath = activeTake.audioFileName,
      durationMs = activeTake.durationMs,
      offsetMs = clip.offsetMs,
      trimStartMs = activeTake.trimStartMs,
      trimEndMs = activeTake.trimEndMs,
      volume = activeTake.volume * track.volume,
      muted = track.isMuted || clip.isMuted
    )
```

No C++ changes required. The mixer sees flat track slots as before.

### Recording Workflow

**Playhead inside an existing clip:**
- Recording adds a new take to that clip
- New take's `isActive` is set to true, previous active take set to false
- Loop recording: each loop pass = one take within the clip

**Playhead in empty space (no clip at that position):**
- Creates a new `AudioClipEntity` at playhead position
- Recording creates the first take within it (`isActive = true`)
- Loop recording: first pass creates the clip + Take 1, subsequent passes add takes

**First recording on a new track (no armed track):**
- Creates a new track + one clip at the recording start position + one take

**Arming behavior:**
- Arm toggle remains on the track (track drawer [R] button)
- When armed and recording starts, the system checks: is there a clip at the playhead? If yes, record into it. If no, create a new clip.

### Track Waveform Display

Replace the current single-file waveform with per-clip blocks:

```
Track lane on timeline:
  For each clip (sorted by offsetMs):
    Position block at clip.offsetMs
    Width = active take's effective duration
    Render NjWaveform from active take's audioFileName
    Show clip boundaries (subtle outline or gap between clips)
```

This naturally fixes the waveform bug: every clip's active take is rendered at its correct position. The track lane spans from the leftmost clip start to the rightmost clip end.

Visual example:
```
Track: Main Vocals
  [===verse===]         [===chorus===]    [==bridge==]
  0s          12s       20s          35s   40s       52s
```

Clips with multiple takes show a small indicator (badge, dot, or number) so users know there are alternatives to explore.

### UI Interactions

**Timeline clip gestures:**

| Gesture | Action |
|---------|--------|
| Tap | Select clip; if 2+ takes, expand takes list below |
| Tap elsewhere | Deselect clip, collapse takes list |
| Long-press + drag | Move clip on timeline (snap-to-beat if enabled) |
| Drag edge | Trim clip (non-destructive, adjusts active take's trim) |

**Takes list (expanded below selected clip):**

| Gesture | Action |
|---------|--------|
| Tap | Activate this take (deactivates previous) |
| Long-press | Rename take (inline text field) |
| Swipe left | Delete take (with confirmation) |

**Removed UI elements:**
- [T] takes toggle button in track drawer -- eliminated
- Long-press mini-drawer on takes -- replaced by swipe + long-press gestures above
- `isMuted` per take -- replaced by `isActive` (one active, rest inactive)

### Track Drawer Changes

The track drawer (opened by tapping track header) keeps:
- Volume NjKnob
- Arm [R] toggle (coral LED)
- Solo [S] toggle
- Mute [M] toggle
- Rename button
- Delete button

Removed:
- Takes [T] toggle (clips handle this now)

### Files That Need Changes

**New files:**
- `data/db/entity/AudioClipEntity.kt`
- `data/db/dao/AudioClipDao.kt`

**Modified files:**
- `data/db/entity/TakeEntity.kt` -- clipId FK, isActive, remove offsetMs/isMuted
- `data/db/dao/TakeDao.kt` -- queries by clipId instead of trackId
- `data/db/NightjarDatabase.kt` -- add AudioClipEntity, migration v12
- `data/repository/StudioRepository.kt` -- clip CRUD, recording-into-clip logic
- `ui/studio/StudioViewModel.kt` -- clip selection state, recording workflow, engine loading
- `ui/studio/StudioUiState.kt` -- clip state, selected clip, takes per clip
- `ui/studio/StudioActions.kt` -- new clip/take actions
- `ui/studio/Timeline.kt` -- per-clip waveform rendering, clip selection, takes expansion, gestures
- `ui/studio/TrackDrawerPanel.kt` -- remove [T] button

**Unchanged:**
- C++ audio engine (no changes needed)
- `audio/OboeAudioEngine.kt` (no changes needed)
- `audio/WaveformExtractor.kt` (no changes needed)
- `ui/components/NjWaveform.kt` (no changes needed)

### Migration Safety

- All existing projects must sound identical after migration
- Each existing take becomes a clip with one take (preserves positions and audio)
- Tracks with no takes get one clip from `track.audioFileName`
- Round-trip: if a user never uses multi-take clips, everything works like before

### Future Considerations

- **Comping**: The clip+takes model naturally supports comping (selecting portions of different takes to build a composite). Not in this implementation but the architecture allows it.
- **Clip splitting**: Split a clip at the playhead into two clips. Standard DAW feature, easy to add later.
- **Clip duplication**: Copy a clip (with its active take) to another position. Same pattern as drum/MIDI clip duplication.
- **Cross-clip drag**: Move a take from one clip to another. Future feature.
