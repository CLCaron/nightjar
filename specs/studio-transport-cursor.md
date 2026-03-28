# Studio Transport & Cursor Model

## Problem

The current transport model uses a single playhead for both "current playback position" and "where the next action starts." This conflation causes broken UX for clip-based recording:

- Recording at a position partway through an existing clip creates a take instead of a new clip
- No way to precisely set where recording will land before hitting record
- Timeline doesn't extend past existing content, limiting where users can record
- No auto-punch-out when recording reaches an existing clip
- Clips can overlap when recording into gaps

## Core Concepts

### Cursor (new)
A persistent marker on the timeline that defines:
- Where playback starts when Play is pressed
- Where a new clip will be placed when recording starts
- The "return to" point when playback stops (configurable)

**Visual:** Downward-pointing triangle at the top of the timeline, sitting on the ruler. Color: teal/cyan family (`NjCursorTeal`), distinct from the amber playhead. Per-theme variant needed.

**Interaction:** Tap the ruler to set cursor position. Snaps to grid when snap is enabled. Draggable for fine positioning.

### Playhead (existing, refined)
The moving indicator showing the current playback/recording position. Continues to be the amber vertical line.

**Key change:** The playhead no longer determines where recording lands. The cursor does.

### Return-to-Cursor Preference
When playback stops, the playhead either:
- **Returns to cursor** (default) -- standard DAW behavior, good for repeated takes
- **Stays at stop position** -- useful for "listen, then continue from here"

Stored in `SharedPreferences` via a new `StudioPreferences` helper (same pattern as `MetronomePreferences`). Toggled via a small control in the project controls bar or the latency setup dialog.

---

## Data Model Changes

### StudioUiState
```kotlin
// New fields
val cursorPositionMs: Long = 0L,
val returnToCursor: Boolean = true,  // preference
```

### StudioAction
```kotlin
data class SetCursorPosition(val positionMs: Long) : StudioAction
data class ToggleReturnToCursor : StudioAction
```

### StudioPreferences (new file)
```kotlin
class StudioPreferences(context: Context) {
    // SharedPreferences wrapper, same pattern as MetronomePreferences
    var returnToCursor: Boolean  // get/set
}
```

Provided via Hilt, injected into StudioViewModel.

---

## Transport Behavior

### Play
1. If not playing: start playback from `cursorPositionMs`
2. Playhead begins moving from the cursor position
3. If playing: no change (already playing)

### Pause
1. Stop playback
2. If `returnToCursor` is true: playhead snaps back to `cursorPositionMs`
3. If `returnToCursor` is false: playhead stays at current position

### Restart
1. Seek to `cursorPositionMs` (or 0 if cursor is at 0)
2. If currently playing, continue playing from there

### Ruler Tap
1. Set `cursorPositionMs` to tapped position
2. If snap is enabled, snap to nearest grid line
3. If currently playing: playhead continues uninterrupted (cursor moves silently)
4. If not playing: playhead also moves to cursor position

### Ruler Drag (scrub)
Current scrub behavior is preserved as-is. Scrubbing moves the playhead for auditioning. On scrub finish, cursor also updates to the final scrub position.

---

## Recording Rules

### Determining Clip vs Take

When the user presses Record with a track armed:

1. **Cursor inside an existing clip** on the armed track:
   - Recording creates a **new take** for that clip
   - The take replaces the clip's audio entirely (not from cursor midpoint)
   - Playback of other tracks starts from the clip's start position for context
   - This is the "retake" workflow

2. **Cursor outside any clip** on the armed track:
   - Recording creates a **new clip** starting at the cursor position
   - Playback of other tracks starts from the cursor position

3. **No tracks exist:**
   - Recording creates a new track + clip + take (existing first-track behavior)

### Auto-Punch-Out

When recording a new clip (case 2 above):
- If the playhead reaches the start of the **next clip** on the armed track, recording automatically stops
- The new clip is saved with its duration ending at the next clip's boundary
- Existing clips are never moved or trimmed by recording
- The user hears the next clip's audio begin playing, confirming the punch-out
- A brief visual indicator (e.g., status text "Punched out") confirms the auto-stop

If no subsequent clip exists, recording continues until the user manually stops.

### Loop Recording

Loop recording behavior:
- Only available when the loop region is set
- Each loop cycle creates a new take within the clip. If it is an initial recording loop, create a new clip and place all takes within that clip.
- All takes are preserved; user picks the best one to keep "unmuted"

### First-Track Recording

Unchanged: creates track + clip + take. Cursor position determines the clip's start offset.

---

## Timeline Changes

### Extended Timeline
The timeline must extend beyond existing content to allow recording past the last clip:
- The timeline should mirror that of a typical DAW. The user should be able to scroll ahead even if there is no track there

### Cursor Visual
- Downward triangle on the ruler row, rendered as a small Canvas path
- Vertical dashed line extending through all track lanes (subtle, `NjCursorTeal` at low alpha)
- Triangle is draggable (same gesture as loop handle drag)
- Tap on the ruler sets cursor position (replaces current scrub-on-tap)

### Playhead vs Cursor Distinction
- **Playhead:** solid amber vertical line (existing)
- **Cursor:** teal triangle + dashed vertical line
- When not playing, both are at the same position (cursor line hidden behind playhead)
- During playback, playhead moves right while cursor stays fixed

---

## Snap Integration

Cursor positioning respects the snap grid:
- When snap is enabled, `SetCursorPosition` snaps to the nearest beat/sub-beat via `MusicalTimeConverter.snapToBeat()`
- When snap is disabled, cursor can be placed at any millisecond position
- Visual cursor triangle sits precisely on the snapped position

---

## UI for Return-to-Cursor Toggle
**Latency setup dialog:** Add a section for transport preferences

---

## Implementation Phases

### Phase 1: Cursor Data + Transport Logic
- Add `cursorPositionMs` to `StudioUiState`
- Add `StudioPreferences` for `returnToCursor`
- Update play/pause/restart to use cursor
- Update ruler tap to set cursor instead of scrub
- Update recording start to use cursor position

### Phase 2: Cursor Visual
- Render cursor triangle on ruler
- Render dashed cursor line through track lanes
- Make cursor triangle draggable
- Distinct from playhead visual

### Phase 3: Recording Rules
- Implement cursor-inside-clip detection (retake vs new clip)
- Implement auto-punch-out
- Remove old `findClipAtPosition` recording logic
- Update `stopRecording` for new rules

### Phase 4: Timeline Extension + Polish
- Dynamic timeline end padding
- Return-to-cursor toggle in project controls bar
- Status text for punch-out events
- Snap integration for cursor

---

## Open Questions

1. **Retake scope:** When re-recording a clip, should the entire clip's duration be re-recorded, or should the user be able to record a shorter take? (Proposal: allow shorter -- the take's duration is however long they record. The clip's effective duration becomes the active take's duration.)

2. **Clip selection visual:** Should the cursor-is-inside-clip state show a visual highlight on that clip? (Proposal: yes, subtle glow or border change to confirm "you're about to retake this clip.")

3. **Through-recording (future):** Should recording ever continue through existing clips, splitting/trimming them? (Proposal: not for v1. Auto-punch-out is simpler and safer. Through-recording can be added as an advanced mode later.)
