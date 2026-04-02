# Piano Roll Pinch-to-Zoom -- Polish Pass

## Context

The initial pinch-to-zoom implementation on the piano roll full-screen editor is functional but has gesture reliability issues under real-world finger movements. The core approach (PointerEventPass.Initial interception, focal-point scroll adjustment, independent axis zoom) is sound, but the span-based scale calculation breaks down at edge cases.

## Current Implementation

`detectPinchZoom()` in `PianoRollScreen.kt`:
- Tracks all pressed pointers each frame
- Computes horizontal span (`max(x) - min(x)`) and vertical span (`max(y) - min(y)`)
- Derives `scaleX = currentSpanX / previousSpanX` and `scaleY = currentSpanY / previousSpanY`
- Reports scale factors to `onPinchZoom` callback which multiplies into `horizontalZoom`/`verticalZoom`
- Jitter filter: ignores scale changes < 0.5%

## Known Issues

### Issue 1: Finger-crossing snap-back

**Symptom:** Zoom out by bringing two fingers together vertically. If the fingers physically cross (top finger goes below bottom finger or vice versa), the zoom snaps back to fully zoomed in.

**Root cause:** When fingers cross, the span doesn't go to zero and reverse -- it hits a minimum (the `coerceAtLeast(1f)` floor) and then grows again. The growing span after crossing looks identical to a spread gesture, so the algorithm reports a large positive scale factor. Since zoom is multiplicative, a single frame of `scaleY = 5.0` can undo many frames of gradual 0.95 zoom-out.

**Expected behavior:** Once the user starts zooming out, crossing fingers should clamp to the minimum zoom and stay there, not reverse direction.

### Issue 2: Horizontal pinch feels clunky

**Symptom:** Pinching toward a vertical line (left-right finger movement) sometimes:
- Snaps scroll position to an unexpected location
- Accidentally places a note on the grid
- Gets misinterpreted as vertical zoom, causing rapid unintended zoom in/out

**Root causes (suspected):**
- **Accidental note placement:** When the pinch ends (one finger lifts), the remaining finger's up event may leak through to the Canvas pointerInput as a tap, triggering note placement.
- **Cross-axis contamination:** With small vertical spans (fingers nearly horizontally aligned), even tiny vertical finger movement produces a huge `scaleY` because `spanY` starts near the 1px floor. A 5px vertical wobble on a 2px initial span = 3.5x vertical zoom.
- **Scroll snap:** The focal-point scroll formula `(centroid + scroll) * (newZoom / oldZoom) - centroid` may overshoot when the zoom ratio changes rapidly in a single frame, especially on the axis with a very small span.

## Decisions

### 1. Proportional damping (not axis locking)

Each axis's zoom influence is scaled by how spread the fingers are on that axis at pinch start. Soft threshold of ~48dp. If initial vertical span is 10px and threshold is ~144px (at 3x density), damping factor = 0.07 -- vertical axis barely participates. Diagonal pinch works naturally. Horizontal-only pinch doesn't accidentally zoom vertically.

Formula: `effectiveScale = 1.0 + (rawScale - 1.0) * clamp(initialSpan / threshold, 0, 1)`

### 2. Direction-aware crossing prevention (option a)

Track the two pointer IDs from pinch start. Record which finger started higher/further-right using the signed difference. On each frame, check if the sign has flipped. If flipped (fingers crossed), freeze zoom on that axis by holding the span at the previous value (scale = 1.0). On uncross, reset the baseline span to the current span so there's no accumulated jump.

### 3. Pointer consumption on pinch end (no cooldown needed)

When the pinch ends (2 fingers to 1), drain all remaining pointer events until all fingers are lifted. This prevents the remaining finger's up event from leaking through to tap/drag handlers as an accidental note placement. No artificial delay needed.

### 4. Soft threshold of 48dp

Used as the denominator for proportional damping. Below ~48dp of initial span, an axis's contribution diminishes toward zero. Not a hard cutoff -- damping is smooth and continuous.

## Implementation

All changes in `detectPinchZoom()` within `PianoRollScreen.kt`:

1. Replace anonymous pointer tracking (filter all pressed, compute min/max) with explicit two-pointer ID tracking from pinch start
2. Compute signed span per axis using tracked pointer positions
3. Record initial sign and initial span at pinch start for crossing detection and damping
4. Per-frame: detect crossing by sign flip, freeze zoom on crossed axis, detect uncross transition to reset baseline
5. Apply proportional damping to scale factors before reporting
6. On pinch end (2->1 finger or tracked pointer lost): consume-and-drain until all pointers are up
7. Remove `canStart` consumption (let events pass through to drag handlers when pinch is suppressed)
