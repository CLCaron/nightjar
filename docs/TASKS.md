# Active Tasks

## Piano Roll Pinch-to-Zoom Polish (In Progress)

**Spec:** `specs/piano-roll-pinch-zoom-polish.md`

**Status:** Initial implementation landed. Gesture has reliability and feel issues under real-world finger movements.

**What works:**
- Pinch spread/squeeze zooms horizontally and vertically with focal-point tracking
- Single-finger scroll still works after zooming
- Tap/drag/resize note interactions work at all zoom levels
- Long-press grid resolution button resets zoom to 1x
- Piano key rows stay aligned with grid rows
- Dynamic canvas clamping prevents GPU texture overflow

**Known issues:**
1. **Finger-crossing snap-back:** When zooming out (e.g. vertical pinch), if the two fingers cross past each other the zoom snaps back to fully zoomed in instead of staying zoomed out. The span calculation inverts when fingers cross, producing a sudden large scale factor in the opposite direction.
2. **Horizontal pinch clunkiness:** Pinching toward a vertical line (one finger left, one right) sometimes feels jumpy -- scroll position snaps unexpectedly, notes get accidentally placed, or the gesture is misinterpreted as vertical zoom causing rapid unintended zoom changes.

**Files involved:**
- `ui/studio/PianoRollScreen.kt` -- `detectPinchZoom()` function and pinch handler wiring

## Collapsible Track Headers -- Individual Overlay (In Progress)

**Spec:** `specs/studio-collapsible-headers.md`

**Status:** Partially complete. Master collapse/expand all works. Individual header overlay in collapsed mode does not render correctly.

**What works:**
- Master chevron button collapses/expands all headers with staggered cascade animation
- Color tabs visible on every track, correct colors, armed override
- Spring animations and haptic feedback on all transitions
- Column width animates between full and narrow
- Drawer/clip state cleanup on collapse

**What needs fixing:**
- In collapsed mode, tapping a single color tab should slide that header out as an overlay on top of the timeline. The animation logic and custom layout are in place (`Timeline.kt` TrackHeader collapsed-mode branch), but the overlay doesn't visually appear above the timeline content.
- Root cause is likely clipping from parent containers or `zIndex` not elevating above sibling content in the Row.
- Possible approaches: `wrapContentWidth(unbounded = true)`, `Popup`, `SubcomposeLayout`, or restructuring the Row so the header overlay sits in a layer above the timeline.

**Files touched:**
- `ui/studio/Timeline.kt` -- TrackHeader composable (two branches: normal vs collapsed mode), MasterCollapseHandle, TimelinePanel params
- `ui/studio/StudioUiState.kt` -- `collapsedHeaderTrackIds`, `headersCollapsedMode` fields + actions
- `ui/studio/StudioViewModel.kt` -- action handlers for toggle/toggleAll
- `ui/studio/StudioScreen.kt` -- passes new state to TimelinePanel
