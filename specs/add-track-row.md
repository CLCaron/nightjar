# Spec: Inline Add-Track Row

## Summary

Replace the floating action button (FAB) and bottom sheet for adding tracks in Studio with an inline "add track" row at the bottom of the track list. The row contains an NjButton header that toggles open a horizontal drawer with three track-type buttons.

## Motivation

The FAB feels disconnected from the timeline. An inline row is more spatial, more discoverable, and consistent with the hardware aesthetic. Track creation happens *in* the timeline, not floating above it.

## What to Remove

- **FAB** in `StudioScreen.kt` (lines ~186-196) -- the `FloatingActionButton` and its visibility logic
- **`AddTrackBottomSheet.kt`** -- the entire file. The `ModalBottomSheet` with `NewTrackType` list is no longer needed
- **`ShowAddTrackSheet` / `HideAddTrackSheet` actions** in `StudioUiState.kt` -- the `showAddTrackSheet` boolean and related action handling in the ViewModel
- **`NewTrackType` enum** can stay (it's useful for the new buttons) or be inlined -- implementer's choice

## The Add-Track Row

### Layout

Always present as the **last row** in the track list, below all real tracks. Uses the same row structure as regular tracks:

- **Header area** (100dp wide, 56dp tall): Contains a single NjButton with an `Icons.Default.Add` icon. No text label on this button -- just the plus icon, centered.
- **Lane area** (remaining width): Empty when the drawer is closed. When open, contains the track-type drawer.

### Header Button

- Standard `NjButton` with `Icons.Default.Add` icon
- Uses `NjMechanicalToggle` for three-state feel (raised / latched / deep press)
- Toggle behavior: tap to open drawer (latches active at depth 0.5), tap again to close (returns to raised at depth 0.0)
- Color: same as regular track headers -- should not look "ghostly" or muted. It's a real button on the timeline.

### Track-Type Drawer

When the header button is toggled active, a drawer slides **horizontally to the right** from the header edge into the lane area.

**Animation:** The drawer is a single panel holding all three buttons. It slides in from the left edge of the lane (x = 0) to its full width. Use `animateFloatAsState` or `Animatable` with a smooth spring or decelerate easing, ~250-300ms. The three buttons are physically grouped -- they move as one unit, like a tray sliding out of a slot.

**Drawer contents:** Three `NjButton` components side by side, each **56dp wide x 56dp tall** (full track row height, square):

| Button | Icon | Label | Action |
|--------|------|-------|--------|
| Audio Recording | `Icons.Default.Mic` | "Audio" | `StudioAction.RequestMicPermissionForNewTrack` (existing flow) |
| MIDI Instrument | `Icons.Default.Piano` | "MIDI" | `StudioAction.AddMidiTrack` (or equivalent) |
| Drum Sequencer | `Icons.Default.GridOn` | "Drums" | `StudioAction.AddDrumTrack` (or equivalent) |

**Button layout (inside each 56dp square):**
- Icon: ~24dp, centered horizontally, upper portion
- Label: ~10sp, centered horizontally, below icon
- Tight vertical padding to fit within 56dp
- Standard NjButton beveled edges and press feel

**Closing the drawer:** Tapping the `+` header button again slides the drawer back to the left (reverse of the open animation) and the button returns to its raised state.

## Track Creation Animation

When the user taps one of the three type buttons, the following animation sequence plays:

1. **Slide out (exit):** The entire add-track row -- header + expanded drawer -- slides **left** off-screen (~300ms, decelerate easing). The row translates on the X axis until fully off the left edge.

2. **Brief pause:** ~100ms overlap or gap. The track is being created in the ViewModel during this time.

3. **Slide in (enter):** The newly created track row slides in from the **right** edge of the screen (~300ms, decelerate easing). It translates from off-screen right to its final resting position. This creates the feeling of the add-track row *transforming* into a real track -- like slotting a tape into a deck.

4. **Fresh add-track row:** A new add-track row fades in below the new track (simple `fadeIn`, ~200ms). The drawer on this new row is closed (just the `+` button visible). No slide animation -- it's always conceptually "there."

**Important:** The mic permission flow for Audio Recording must complete *before* the creation animation plays. If the user denies permission, no animation occurs and the drawer remains open.

## State Management

### New UI State

Add to `StudioUiState`:

```kotlin
val isAddTrackDrawerOpen: Boolean = false
```

### New Actions

```kotlin
// Replace ShowAddTrackSheet / HideAddTrackSheet
data object ToggleAddTrackDrawer : StudioAction
```

The existing track creation actions (`AddDrumTrack`, `AddMidiTrack`, mic permission flow for audio) remain unchanged. After a track is successfully created, the ViewModel sets `isAddTrackDrawerOpen = false`.

### Animation State

The slide-out/slide-in animation is **UI-local** (Compose-side), not ViewModel state. The Compose layer:
1. Observes track list changes
2. When a new track appears at the end of the list AND the add-track drawer was just open, triggers the exit/enter animation sequence
3. Uses `rememberCoroutineScope` + `Animatable` for the choreographed sequence

## Edge Cases

- **Zero tracks:** The add-track row is the only row. Behaves identically -- just the `+` button, tap to open drawer. No special "hint" state.
- **Scrolling:** If the track list is long enough to scroll, the add-track row scrolls with the list like any other row. It is part of the track list, not pinned.
- **During recording:** The add-track row should be hidden or the `+` button disabled while recording is active (same logic that currently hides the FAB).
- **During playback:** Adding tracks during playback is allowed (current behavior). The animation still plays.

## Future Direction (Not in This Task)

**All track headers as NjButtons:** The add-track row establishes the pattern of a header being a pressable NjButton. In a future pass, all track headers will be converted to NjButtons that toggle their drawers open/closed. This will complete the hardware metaphor -- every header is a physical button. The current tap-to-toggle behavior already works this way functionally; it just needs the visual treatment.

## Files to Modify

- `StudioScreen.kt` -- Remove FAB, add the add-track row to the track list
- `Timeline.kt` -- Add the `AddTrackRow` composable (header + drawer + animations) as the last item in the track column
- `StudioUiState.kt` -- Add `isAddTrackDrawerOpen`, `ToggleAddTrackDrawer` action, remove `showAddTrackSheet` and related actions
- `StudioViewModel.kt` -- Handle `ToggleAddTrackDrawer`, remove bottom sheet handling, close drawer on track creation
- `AddTrackBottomSheet.kt` -- Delete this file

## Acceptance Criteria

1. FAB and bottom sheet are completely removed
2. Add-track row is always the last row in the track list
3. `+` NjButton in the header toggles a horizontal drawer with three type buttons
4. Drawer slides smoothly from the left with spring/decelerate animation
5. Type buttons are 56dp square NjButtons with icon + label, full track row height
6. Selecting a type triggers the slide-out-left / slide-in-right animation sequence
7. New add-track row fades in below the new track
8. Toggle `+` again closes the drawer
9. Mic permission still gates Audio Recording creation
10. Row hidden or disabled during recording
11. Works correctly with zero tracks (empty state)
