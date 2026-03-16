# TASKS.md — Nightjar

Active task tracking. For architecture, tech stack, and coding standards, see `CLAUDE.md`.

---

## Blockers — Cannot ship v1.0 without these

Priority order within blockers (agreed approach):

1. Bug fixes and quick foundation fixes (immediate quality)
2. Foundational UX improvements (makes everything feel right)
3. Audio engine precision (sounds right)
4. Unified clip/track editing model (the big architectural win)
5. Remaining blockers (Record, Library, Cross-Screen)

---

### Sprint 1: Bug Fixes + Quick Foundations

- [x] **[Bug] MIDI clips not draggable** — Fixed: modifier ordering had padding before pointerInput, shrinking touch target. Restructured to match drum clip pattern.
- [x] **[Bug] MIDI clip highlight color** — Fixed: replaced full-card yellow background with gold outline border matching drum clips.
- [x] **[Feature] Per-pattern drum resolution** — Each drum pattern owns its resolution (1/8, 1/16, 1/32) via +/- picker in the drum drawer. New patterns default to 1/16. Time signature changes derive per-pattern resolution correctly. Step remapping preserves beat positions when switching resolution.
- [x] **[Bug] Drum sequencer 3/4 time signature** — Fixed via per-pattern resolution derivation in setTimeSignature().
- [x] **[Bug] Drum clip timeline alignment drift** — Fixed: horizontal padding on clip Box made Canvas narrower than timeline-aligned width, compressing proportional step positions. Removed horizontal padding.

### Sprint 2: Foundational UX

- [x] **[Feature] Snap during drag** — Fixed: piano roll note move and resize now snap to grid positions in real-time during drag. Track, drum clip, and MIDI clip drag already snapped during drag.
- [x] **[Feature] Sub-beat timeline rulers** — Ruler draws sub-beat ticks at grid resolution density (1/4 through 1/32). Visual hierarchy: bar lines heaviest, beat ticks medium, sub-beat ticks lightest. BeatGridOverlay also draws sub-beat grid lines behind track lanes. Density gating hides ticks when pixel spacing < 4px.

### Sprint 3: Audio Engine Precision

- [x] **[Feature] Sample-accurate MIDI timing** — MIDI and drum events currently fire at chunk boundaries (~23ms granularity at 1024 frames). Implement sub-buffer scheduling: split the FluidSynth render call within each chunk at event frame positions so noteOn/noteOff land at the exact sample. Applies to both MidiSequencer and StepSequencer tick paths in SynthEngine::renderThreadFunc().

### Sprint 4: Unified Clip/Track Editing Model

The MIDI piano roll and drum sequencer should follow the same two-level editing pattern. Clips are time regions on the track -- notes/steps placed within a clip's range belong to that clip. The user gets two ways to edit:

1. **Full-screen editor** (tap Edit on track): Shows the entire track with all clips visible as distinct regions. User can place/edit/move/delete notes anywhere. Notes auto-assign to the clip whose time range contains them. Placing a note in a gap auto-creates a new clip.
2. **Drawer-level editor** (select a clip, open drawer): Drawer expands with an inline editor scoped to just that clip. Quick edits without leaving Studio.

Both levels stay in sync -- edits in the drawer reflect in the full-screen editor and vice versa. Future features (merge/split clips, velocity, humanize) benefit from the full-screen real estate.

- [x] **[Feature] Full-screen drum editor** — New screen for drum tracks (like piano roll for MIDI). Shows all clips as one continuous grid. Clip boundaries visible as vertical dividers. All cells tappable regardless of which clip they belong to. Replaces the current clip-selector-in-drawer model.
- [x] **[Feature] Drawer-level clip editing (drums)** — When a drum clip is selected on the timeline, the track drawer expands to show an inline pattern editor scoped to that single clip. Edits sync to the track level.
- [x] **[Feature] Full-track piano roll** — Piano roll shows the entire track with all clips visible as shaded regions. Notes can be placed/edited in any clip. Notes in gaps auto-create new clips. Clip navigation bar or mini-map for jumping between clips.
- [x] **[Feature] Drawer-level clip editing (MIDI)** — MiniPianoRoll: inline editor in MidiTrackDrawer shows 2 octaves at 18dp/row with NjButton octave arrows + slide animation. Tap to place, drag to move, double-tap to delete. Auto-selects clip on timeline tap. Horizontal scroll for time, octave buttons for pitch navigation.

### Sprint 5: Remaining Blockers

#### Studio - General

- [x] **[Feature] Auto-follow playhead** — Timeline should scroll to keep the playhead visible during playback.
- [ ] **[Feature] Takes order and auto-mute** — After loop recording, latest take should be on top and all except the most recent auto-muted.
- [ ] **[Feature] Revisit audio recording takes** — We should revisit the way audio recording "takes" are handled. Currently, if you loop and record an initial audio recording (no track to arm. Simply hit the record button while looping with a 1 measure drum track, for example) it will simply combine all of those "takes" in to one continuous take. We should treat these similar to MIDI instrument and drum-track, where we can have multiple clips on a track and each of those clips can have their own takes which are presented if you tap on the clip. Let's discuss details before planning and implementation
- [ ] **[UX] Replace FAB with Add Track button** — Let the user choose track type (Audio, Drum, MIDI) instead of a generic FAB.
- [x] **[Feature] Clip action card-flip animation** — Tapping a clip triggers an X-axis card-flip animation (FlippableClip composable, 300ms tween) to reveal icon-only action buttons (ContentCopy/Edit/Delete) on the back face. Applied to drum, MIDI, and audio clips. Drag-across no longer accidentally selects clips (distance check vs touchSlop).
- [ ] **[Bug] Clip deselect not working** — Tapping empty timeline space or the back-face background of a flipped clip should dismiss the selection (flip back), but currently does not. The `detectTapGestures` on the timeline background Box and the `clickable` on the ClipActionButtons Row are not receiving events, likely because clip-level `awaitFirstDown(requireUnconsumed = false)` intercepts all pointer events first. Needs a different approach -- possibly a global tap observer at the TimelinePanel level, or switching clips to consume-based gesture handling so taps can fall through.
- [ ] **[Feature] Sticky note length in piano roll** — Default note length starts at 1 beat. After the user trims a note, subsequent placements use the trimmed length as the new default. Current sticky length shown in the piano roll toolbar (e.g. "1/16"). Tapping the label allows manual length selection. Session-scoped (resets on exit).
- [ ] **[Feature] Piano roll pinch-to-zoom** — Horizontal pinch-to-zoom on the piano roll grid to spread/compress the time axis. Piano key panel stays fixed width. Solves the difficulty of tapping accurately at 1/32 resolution.

#### Studio - MIDI / Piano Roll

- [ ] **[Bug] Timeline scrub ignores MIDI/drums** — Scrubbing the timeline only affects audio tracks. Drum and MIDI tracks continue playing regardless of scrub position.

#### Record

- [ ] **[Feature] Smarter idea creation** — "Studio" and "Write" should only persist the idea if the user actually does something. No empty ghost ideas in the library.

#### Library

- [ ] **[Bug] Refresh after delete** — Deleted ideas still appear in the list until next load. Must reflect state immediately.

#### Cross-Screen

- [ ] **[Feature] Metronome** — BPM-synced click track during recording. Needs a visual pulse option for headphone-free recording (so click doesn't bleed into mic). Should feel Nightjar -- a pulsing ambient element, not a generic flashing bar.
- [ ] **[Feature] Latency calibrator** — Prompt user to hold earbud up to the mic; app measures round-trip latency automatically. Crucial for in-time overdub recording.
- [ ] **[Feature] Share -- fix or remove** — Currently shares only the first track. Either pull Share until v1.1 or implement as mix-down / per-track selection.

---

## High Priority Polish — Before or shortly after launch

### Studio - Piano Roll

- [ ] **[Bug] MiniPianoRoll resize inconsistent** — Dragging the right edge of a note to resize works but is unreliable. The right-half-of-note detection zone may not be triggering consistently, possibly due to gesture race conditions with horizontal scroll or stale hit-test data after notes are moved by the Flow observer. Needs on-device debugging.
- [ ] **[Bug] Snapping inaccuracy** — Trim snaps to the beat line before the intended one. Also need user-selectable snap resolution (1/8, 1/4, etc.).
- [ ] **[Bug] Restart button** — Resets the playhead but does not restart audio playback.
- [ ] **[Feature] Adaptive key widths** — Piano key panel (fixed 48dp) may be too narrow in portrait. Responsive widths via `BoxWithConstraints`.
- [ ] **[Feature] Notes visual redesign** — Flat rectangles need more character: subtle bevel/shadow, different color than amber/orange. Should feel like physical objects on the grid.
- [ ] **[Feature] Instrument picker redesign** — Current picker feels generic. Beveled tiles, LED-style selection, synth-bank switching feel.
- [ ] **[Feature] Haptic feedback on keys** — Tapping piano keys and placing/moving notes should give tactile feedback consistent with NjButton.

### Studio - Drum Sequencer

- [x] **[Feature] Sub-beat note resolution** — Per-pattern resolution picker (1/8, 1/16, 1/32) in drum drawer with step remapping.
- [ ] **[Bug] Portrait mode button layout** — Delete and Rename buttons are smushed. Should use the same responsive layout as track drawers.

### Studio - General

- [ ] **[Bug] Loop state persists across ideas** — Loop stuck "on" after navigating to a new idea. UI showed loop off with no visible region, but playback looped. State may not clear on navigation.
- [ ] **[Bug] Track lane drag highlight stuck** — After repositioning, track retains the highlight color instead of reverting on release.
- [ ] **[Feature] Header pins on scroll** — Top buttons should stay visible when scrolling through tracks.
- [ ] **[Feature] BPM press-and-hold + tap-to-type** — Hold +/- to accelerate. Tap the number to type directly.
- [ ] **[Feature] Zoomable timeline** — Pinch-to-zoom or zoom control for seeing more of the arrangement.
- [ ] **[Feature] Drag to reorder tracks** — Long-press track name, drag to new position.
- [ ] **[Feature] Inline idea rename** — Tap the title in Studio to rename without navigating away.
- [ ] **[Feature] Button size consistency** — MIDI drawer Edit/Instrument buttons are slightly larger than others. Unify.
- [ ] **[Feature] Rename button color** — Needs a distinct color in the track drawer so it reads as an action.

### Overview

- [ ] **[Feature] Play button consistency** — Should match Studio's style.

### Cross-Screen

- [ ] **[Feature] Hardware-style back button** — Use NjButton style instead of default Android arrows.

---

## Judgment Calls — Needs a decision

- [ ] **Gesture hints** — Important but generic implementation feels worse than nothing. Must be on-brand or defer to v1.1.
- [ ] **Help button** — Lightweight in-app reference for non-obvious interactions. Low cost if kept simple.
- [ ] **Delete all except favorites** — Bulk delete with serious confirmation (type "DELETE" + second prompt). Useful but needs careful UX.

---

## Canvas Screen — Freeform text surface

Parked on `feat/canvas` branch. Phase 1 complete (data model, navigation, basic rendering, coordinate fix, tap handling, BackHandler, empty placeholder). Remaining work by phase:

### Phase 2: Fragment interactions + paper aesthetic

- [ ] **Paper fragment visual** — Warm cream paper cards (`NjCanvasPaper`/`NjCanvasPaperText` tokens). Drop shadow, per-fragment rotation (1-2 deg, seeded by ID).
- [ ] **Selected fragment state** — Tap to select (show handles), tap again to edit, tap empty space to deselect.
- [ ] **Resize handles** — Corner/edge handles on selected fragment. Bottom-right drag resizes.
- [ ] **Strikethrough gesture** — Horizontal swipe toggles `isStrikethrough`. Crumple/scale animation.
- [ ] **Delete fragment trigger** — Long-press or swipe-to-delete UI for the existing `DeleteElement` action.

### Phase 3: Trash Can

- [ ] **Trash can visual** — Wastebasket at bottom-left (outside canvas transform). Glow when non-empty.
- [ ] **Drag-to-trash / long-press-to-trash** — Discard with crumple animation.
- [ ] **Trash panel** — View and restore trashed fragments.
- [ ] **Empty Trash** — Destructive action with confirmation. Only this truly deletes from DB.

### Phase 4: The Jar

- [ ] **Jar visual** — Glass jar at bottom-right. Glow when fragments inside.
- [ ] **Drag-to-jar** — Drop fragment into jar to stash. Hover animation on approach.
- [ ] **Jar panel** — Two tabs: "This Idea" (local) and "All Ideas" (universal). Tap to un-jar or copy.

### Phase 5: Sections (lasso grouping)

- [ ] **Lasso gesture** — Freeform draw around fragments to group into named sections (verse, chorus, bridge).
- [ ] **Section rendering** — Faint dashed outlines with floating title labels.
- [ ] **Auto-assign on drag** — Fragments auto-join/leave sections when dragged across boundaries.

### Phase 6: Overview integration (minimap)

- [ ] **Canvas minimap on Overview** — Thumbnail of fragments inside `NjRecessedPanel`. Tappable to open Canvas.

### Phase 7: Rich text formatting

- [ ] **Formatting toolbar** — Bold, italic, underline, font size. Floating bar above editing fragment.
- [ ] **Rich text rendering** — `formattingJson` to `AnnotatedString` with spans.

---

## Sound Engine Roadmap

### Pre-Launch: Custom Drum Kits

The single bundled GM drum kit (GeneralUser GS, Standard Kit) does not cover genre-specific sounds. No 808 kicks, trap hi-hats, china cymbals, electronic percussion, etc. This is a content problem, not an engine problem -- FluidSynth already supports multiple SoundFonts and kit switching.

**Goal**: Ship with 4-6 drum kits covering the major genres. Each kit is a small .sf2 file (2-5 MB) built from CC0 samples using Polyphone. Total added APK size: ~15-25 MB.

#### YOU (Chris) -- Content Creation

- [x] **Install Polyphone** -- Free SoundFont editor (https://www.polyphone.io/). This is the tool for assembling .sf2 files from WAV samples. Learn the basics: create an instrument, map samples to MIDI notes, set loop points, adjust envelopes, export as .sf2.
- [ ] **Source CC0 drum samples** -- Browse https://freesound.org with the "CC0" license filter. Search for one-shots: "808 kick", "trap hi-hat", "china cymbal", "electronic snare", "lo-fi drum", etc. Download individual WAV hits. CC0 = public domain, no attribution required, no restrictions. Safe for commercial use.
- [ ] **Build drum kit SoundFonts** -- One .sf2 per kit. Each kit can have as many or as few sounds as you want -- map each sample to a GM drum note number in Polyphone. The sequencer will auto-detect which notes have samples and create rows accordingly. Common GM drum note reference:
  - 35 = Kick 2 / Acoustic Bass Drum
  - 36 = Kick 1
  - 37 = Sidestick / Rimshot
  - 38 = Snare
  - 39 = Clap
  - 40 = Electric Snare
  - 41 = Low Floor Tom
  - 42 = Closed Hi-Hat
  - 43 = High Floor Tom
  - 44 = Pedal Hi-Hat
  - 45 = Low Tom
  - 46 = Open Hi-Hat
  - 47 = Low-Mid Tom
  - 48 = Hi-Mid Tom
  - 49 = Crash 1
  - 50 = High Tom
  - 51 = Ride
  - 52 = China
  - 53 = Ride Bell
  - 54 = Tambourine
  - 55 = Splash
  - 56 = Cowbell
  - 57 = Crash 2
  - You don't need to fill every note. A minimal kit might have 6 samples, a big metal kit might have 16+. Map whatever you want -- the sequencer adapts.
- [ ] **Target kits** (starting ideas -- adjust based on what sounds good):
  - 808 / Trap (808 sub kick, clap, closed/open trap hats, rimshot, crash)
  - Electronic (punchy synthetic kick, metallic hats, digital snare, zap, blip)
  - Rock (heavy kick, crack snare, china, ride bell, floor tom, crash)
  - Lo-Fi (dusty filtered kick, vinyl snare, soft hat, muted everything)
  - Percussion (conga, bongo, shaker, tambourine, cowbell, woodblock)
  - Metal (double kick, tight snare, china, splash, multiple toms, ride bell)
- [ ] **Test each kit** -- Load in Polyphone's built-in player to verify samples sound right at the mapped notes, velocities respond properly, and there are no clicks/pops at sample boundaries.
- [ ] **Create a kit manifest** -- For each bundled kit, write a small JSON file with custom row labels and colors. Example for an 808 kit:
  ```json
  {
    "name": "808 / Trap",
    "rows": [
      {"note": 36, "label": "808 Kick"},
      {"note": 38, "label": "Snare"},
      {"note": 39, "label": "Clap"},
      {"note": 42, "label": "Closed Hat"},
      {"note": 46, "label": "Open Hat"},
      {"note": 37, "label": "Rim"},
      {"note": 49, "label": "Crash"}
    ]
  }
  ```
  This lets you control exact row labels and order. Without a manifest (e.g. user-imported kits), the code falls back to scanning the SoundFont for mapped notes and using standard GM names.
- [ ] **Deliver .sf2 files + manifests** -- Place finished kits in `app/src/main/assets/soundfonts/drums/`. One `.sf2` and one `.json` per kit.

#### PROGRAMMING -- Per-Kit Row Definitions + Kit Picker

The current drum sequencer hardcodes 10 rows in `GM_DRUM_ROWS`. This must become dynamic so each kit defines its own rows (count, note mapping, labels, colors).

- [ ] **Per-kit row definition system** -- Replace hardcoded `GM_DRUM_ROWS` with a `DrumKitDefinition` data class: `name`, `rows: List<DrumRow>` where each `DrumRow` has `note`, `label`, `color`. The sequencer grid, timeline mini-dots, pattern editor, and C++ StepSequencer all read row count and note mapping from the active kit instead of the constant. GeneralUser GS "Standard Kit" becomes the default `DrumKitDefinition` with the current 10 rows, so nothing changes until a different kit is selected.
- [ ] **Kit manifest loader** -- Parse bundled JSON manifests from assets. For user-imported kits (v1.1), scan the SoundFont's drum preset (bank 128) via FluidSynth's preset zone iteration API to discover which MIDI notes have samples mapped. Generate a `DrumKitDefinition` automatically with GM note names as fallback labels.
- [ ] **Pattern migration on kit switch** -- When the user changes kits, existing step data uses MIDI note numbers (not row indices), so steps for notes that exist in the new kit stay; steps for notes absent in the new kit are preserved in the DB but hidden from the grid (they reappear if the user switches back). No data loss.
- [ ] **Drum kit picker in drum track drawer** -- Dropdown or horizontal chip bar to select kit. Stored on TrackEntity (new `drumKit` column, schema migration). Changing kit reloads the SoundFont and rebuilds the grid rows.
- [ ] **Load kit SoundFonts at playback** -- SoundFontManager loads the selected kit's .sf2 alongside GeneralUser GS. FluidSynth's `sfload()` supports multiple SoundFonts; drum channel 9 gets routed to the selected kit's SoundFont.
- [ ] **Kit preview/audition** -- Tapping a kit in the picker plays a quick preview (kick-snare-hat pattern from the kit) so the user can hear the difference before committing.
- [ ] **Row color cycling** -- Current `DRUM_ROW_COLORS` has 10 colors. Extend to cycle through however many rows a kit defines. Colors assigned by row index, wrapping if needed.

### Pre-Launch: FluidSynth LGPL Compliance (Required)

FluidSynth is LGPL-2.1. Nightjar currently statically links it (`BUILD_SHARED_LIBS OFF`), which is non-compliant for a closed-source commercial app. LGPL requires the end user to be able to replace the library.

- [ ] **Switch FluidSynth to shared linking** -- Set `BUILD_SHARED_LIBS ON` in CMakeLists.txt. FluidSynth ships as `libfluidsynth.so` alongside `libnightjar-audio.so`. This is the standard Android LGPL compliance approach.
- [ ] **Add license attribution screen** -- Include FluidSynth's LGPL-2.1 text and a link to the exact source commit used. Also include GeneralUser GS license text. Add to an "Open Source Licenses" screen accessible from Settings or About.

### v1.1: User-Loadable SoundFonts

Let power users import their own .sf2 files to expand the instrument and drum kit library. High-value feature at moderate engineering cost.

- [ ] **Import flow** -- "My SoundFonts" section in instrument picker with "+" button. Opens Android SAF file picker (`ACTION_OPEN_DOCUMENT`). Copies .sf2 to app-private storage. Scans for presets (instruments and drum kits) via FluidSynth API.
- [ ] **Persistence** -- Room entity for imported SoundFonts (id, displayName, localPath, sizeBytes, importedAt). Delete option to reclaim storage.
- [ ] **Preset browsing** -- After import, enumerate bank/program combinations. Display as selectable instruments alongside built-in ones.
- [ ] **Drum kit integration** -- Imported SoundFonts with drum presets (bank 128) appear in the drum kit picker automatically. Rows are auto-detected by scanning which MIDI notes have mapped samples. Labels fall back to standard GM drum names (e.g. note 52 = "China"). No manifest needed -- the SoundFont itself is the source of truth.

### v1.1: Downloadable Sound Packs

Host additional SoundFont packs for in-app download. Keeps initial APK lean while offering expanded content.

- [ ] **Download infrastructure** -- Host .sf2 packs on cloud storage. Download to app-private storage with progress indicator. Track installed packs in Room.
- [ ] **Sound Packs screen** -- List of available packs with name, description, size, download/delete buttons. Could be free or gated behind premium (Google Play Billing).
- [ ] **Play Asset Delivery** -- Alternatively, use Android's on-demand asset packs (up to 512 MB each) for packs distributed through the Play Store.

### v2.0: FM Synthesizer (MSFA, Apache 2.0)

The marquee v2 feature. Real synthesis from pure math -- no samples needed. Google's MSFA engine (https://github.com/google/music-synthesizer-for-android) was built for Android, is Apache 2.0 licensed (fully safe for commercial use), and produces the classic DX7 sound palette: electric pianos, basses, bells, pads, brass, evolving textures.

- [ ] **Integrate MSFA C++ code** into the render pipeline alongside FluidSynth. MSFA generates audio from 6-operator FM patches (128 bytes per preset). Wire noteOn/noteOff from MidiSequencer to MSFA when a track uses a synth instrument.
- [ ] **"Synth" instrument category** in MIDI track drawer. User picks from synth presets instead of GM SoundFont instruments. The piano roll, MIDI clips, and timeline stay identical.
- [ ] **Preset system** -- Ship 30-50 curated presets from free DX7 preset archives. Save/load as JSON. Users can tweak and "Save As" to create custom sounds.
- [ ] **Synth editor UI** -- Dedicated panel for FM parameters (operator levels, ratios, envelopes, feedback). Nightjar's hardware knob aesthetic fits perfectly here. Hidden behind an "Edit Sound" button so casual users never see it.

### v2.x: Subtractive Synthesizer (Soundpipe or LEAF, MIT)

A simpler, more intuitive synth alongside FM. Two oscillators, filter, envelope, LFO -- the classic "knobs and sliders" architecture. Built from MIT-licensed DSP primitives (Soundpipe: https://github.com/PaulBatchelor/Soundpipe or LEAF: https://github.com/spiricom/LEAF). Both are designed for constrained/embedded environments, ideal for mobile CPU budgets.

- [ ] **Build synth from DSP modules** -- Oscillators (saw, square, sine, triangle), resonant filter (LP/HP/BP), ADSR envelopes, LFO with multiple targets. All real-time parameter control via atomics.
- [ ] **Preset system** -- JSON parameter snapshots. Ship curated presets (bass, lead, pad, pluck, etc.).
- [ ] **Editor UI** -- The "hardware synth module" screen. NjKnobs for every parameter. This is where Nightjar's aesthetic pays off the most.

### Licensing Reference (All Clear, No GPL)

| Component | License | Commercial OK? | Notes |
|-----------|---------|---------------|-------|
| GeneralUser GS | Custom permissive | Yes | Current bundled SoundFont. Include license text. |
| FluidSynth | LGPL-2.1 | Yes (shared link) | Must ship as .so, not statically linked. |
| MSFA (Google FM) | Apache 2.0 | Yes | Include NOTICE file if modified. |
| Soundpipe | MIT | Yes | Include MIT license text. |
| LEAF | MIT | Yes | Include MIT license text. |
| CC0 samples | Public domain | Yes | No requirements at all. |
| **GPL anything** | GPL | **NO** | Never use. Requires open-sourcing entire app. |

---

## Deferred — Out of scope for v1.0

- Auto tempo detection (v1.1+)
- Home screen widget (v1.1)
- Export / share as mix-down or stems (v1.1)
- Studio entrance animation (stashed in git)
- Onboarding walkthrough
- Audio cues on Studio entrance
- Per-track effects / processing (v2.0)
- Undo/redo
- Automation lanes
- Cloud backup / sync
- Audio import (e.g., import MP3 backing track to record over)

---

## Chores

- [ ] Clean up compiler warnings and dependency version notifications
- [ ] Verify cold launch < 2s on target devices
- [ ] Fix adaptive icon safe zone (foreground art to inner 72dp of 108dp canvas)

---

## Recently Completed

- Sprint 3: Sample-accurate MIDI timing via sub-buffer scheduling in SynthEngine. NoteEvent carries frameOffset, FluidSynth render split at event boundaries for exact sample placement.
- Clip card-flip animation: FlippableClip composable with X-axis rotation, icon-only action buttons, audio track tap-to-select, drag-doesn't-select fix
- Sprint 2: Snap during drag (piano roll note move/resize), sub-beat timeline ruler ticks + BeatGridOverlay at grid resolution
- Sprint 1 bug fixes: MIDI clip drag (modifier ordering), MIDI clip highlight (gold outline), drum clip alignment drift (horizontal padding removal)
- Per-pattern drum resolution: decoupled from project grid, own 1/8/1/16/1/32 picker in drum drawer, step remapping on resolution change, correct time signature adaptation
- Sub-beat grid resolution picker (1/4, 1/8, 1/16, 1/32), MIDI clips, per-clip drum patterns, clip action panel (schema v10)
- Button cleanup: unified icons, static PlayPause, Studio entry button with perimeter trace glow
- Canvas Phase 1: data model, navigation, basic rendering, coordinate fix, tap-through, pan-during-drag, BackHandler, empty placeholder
- MIDI Instruments and Piano Roll: full-screen editor, 128 GM instruments, C++ MidiSequencer, schema v9
- Piano Roll note drag, resize, and timeline sync fix
- Landscape mode cleanup
- Inline drawers persist (multiple open simultaneously)
- Loop recording with auto-split into takes (WavSplitter)
- Studio recording live waveform
- FluidSynth + drum step sequencer (clip-based timeline)
- Measure/beat timeline with time signature support
- UI component overhaul (NjButton, NjCard, unified palette, HardwareRecordButton)
- Takes + Arm + Record (TakeEntity, lazy promotion)
- Oboe audio engine migration (C++ mixer, lock-free recording)
- Overdub latency compensation (hardware timestamps + BT detection)
- Library play button (NjLedDot, multi-track preview)
