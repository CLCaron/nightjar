# Nightjar

Ideas don't wait. They show up in the middle of the night, in the shower, between conversations -- and they leave just as fast. **Nightjar** is a zero-friction idea capture app for Android, built for musicians, songwriters, and anyone whose best ideas arrive at the worst times.

Sing it. Say it. Write it down. Build it up. However the idea comes, Nightjar catches it -- and gives you a place to develop it before you ever open a DAW.

Named after a nocturnal songbird, this app is designed around late-night creativity but there for you any time of day. The warm indigo-tinted palette and hardware-inspired controls create a calm, inviting space -- like a pocket recorder you'd pull out in a dim room.

---

## How it works

Open the app. You're already on the capture screen. Three ways to start:

- **Record** -- tap the hardware-style record button, sing your melody, tap again. Done. Live waveform while you record, preview when you stop.
- **Write** -- got a lyric, a chord progression, a half-formed thought? Capture it as text. No audio needed.
- **Studio** -- want to sketch something layered from scratch? Jump straight into the multi-track workspace.

Every capture becomes an **idea** -- a container you can come back to later, title, tag, annotate, and build on.

---

## Develop it

When you're ready to do more with an idea, Nightjar gives you two workspaces:

### Overview
The home screen for a single idea. Play it back, edit the title and notes (auto-saved as you type), add tags, mark favorites, share, or delete.

### Studio
A lightweight multi-track workspace. Not a replacement for professional tools, but the bridge between "I just thought of something" and "let me sit down and produce this." Layer recordings on top of each other, shift their timing, trim the edges, adjust volume and mute per track. Enough to know whether an idea is worth pursuing. Enough to jumpstart true production later.

- **Auto-create tracks** -- tap Record any time, even with existing tracks. No armed track? Nightjar creates a new one automatically. Arm a track when you want to add takes to a specific layer.
- **Overdub** -- record new layers while existing tracks play back, with hardware-compensated sync. Live coral waveform grows in real time on the timeline during recording.
- **Takes** -- arm a track and record multiple takes. Each take is an independent audio clip on the timeline. Tap to mute, long-press for rename/delete. Drag and layer takes freely.
- **Loop recording** -- with a loop region active, record continuously. On stop, the recording is automatically split into individual takes at each loop boundary -- zero gaps, no manual slicing.
- **Loop playback** -- tap Loop to auto-create a full-timeline region, adjust with draggable handles on the ruler
- **Drag to reposition** -- long-press a track and slide it along the timeline
- **Non-destructive trim** -- drag handles on track edges
- **Measure/beat timeline** -- the ruler displays measure numbers with beat subdivisions instead of seconds. Supports time signatures (4/4, 3/4, 6/8, 2/4) and project-level BPM. Position readout shows current playhead as "measure.beat".
- **Snap-to-grid** -- track drag, trim, loop region, take drag, and drum clip drag all snap to beat boundaries in real time when enabled. Toggle snap on/off from the project controls bar. Visual beat grid lines on track lanes.
- **Drum sequencer** -- step-based drum patterns powered by FluidSynth (SoundFont synthesis). Tap cells in the pattern editor grid to place hits across 10 GM drum instruments. Adjust bar count (1-8), set project BPM. Patterns play as one-shot clips on the timeline -- long-press to drag, duplicate, and delete clips to arrange. Mini-grid visualization on the timeline with per-instrument colored dots. Beat boundaries adapt to time signature.
- **MIDI instrument tracks** -- compose melodies, basslines, and chord progressions with 128 General MIDI instruments. Full-screen piano roll editor with tap-to-place notes, long-press-to-delete, snap-to-beat, and live playback. Inline MiniPianoRoll in the track drawer for quick edits without leaving Studio (tap a clip on the timeline to edit it in-place). Curated instrument picker with audition preview. Compact note visualization on the Studio timeline. Powered by FluidSynth synthesis via a dedicated C++ MidiSequencer.
- **Clip card-flip actions** -- tap any clip (drum, MIDI, or audio) on the timeline to flip it over with a smooth X-axis rotation, revealing icon-only action buttons (duplicate, edit, delete) on the back face. Scrolling across clips no longer accidentally selects them.
- **Per-track controls** -- volume knob, arm (R), solo (S), mute (M), takes (T), rename, delete via inline track drawer. Drum tracks get a specialized drawer with pattern editor and bar count controls. MIDI tracks get instrument selection, edit notes, and playback controls. Responsive layout adapts to narrow screens (e.g. Galaxy Fold cover display).
- **Hardware-style controls** -- unified NjButton and NjCard components with beveled edges, LED glow, haptic feedback, and three-state mechanical latching feel (deep press, latched, raised). Rotary volume knob with haptic detents. Embossed text on buttons and titles. Consistent across all screens.

---

## Organize it

The **Library** is where ideas live long-term. Sort by newest, oldest, or favorites. Filter by tag. Preview audio directly from the list with a tap on the play button. Tap any card to open its Overview.

---

## Tech Stack

- Kotlin + C++17, Jetpack Compose (Material 3)
- Room (SQLite) with schema migrations
- Hilt dependency injection
- Coroutines + Flow
- Navigation Compose
- Oboe audio engine (C++ via NDK) -- lock-free, callback-based recording and multi-track playback with hardware timestamp latency compensation
- FluidSynth (C++ via NDK) -- SoundFont-based synthesis for drum sequencer and MIDI instrument tracks

---

## Status

Nightjar is in active development, working toward a v1.0 Play Store release.

**What's working:**
- Full capture → save → organize → multi-track workflow
- Three entry points for idea creation (record, write, studio)
- Live waveform during recording with post-recording preview (Record screen and Studio timeline)
- Native C++ audio engine (Oboe) — low-latency recording and multi-track playback
- Multi-track overdub with hardware timestamp latency compensation
- Per-track takes with arm toggle, loop recording with auto-split into takes
- Drag-to-reposition, non-destructive trim, per-track volume/mute/solo/delete
- Loop playback with ruler scrub-to-seek and loop handle adjustment
- Timeline ruler as primary scrub surface (tap or drag to seek)
- Studio transport bar: Restart, Play/Pause, Rec (right cluster) with Loop/Clear rocker pill (left). Sticky pinned overlay when scrolled past.
- Inline track drawer with hardware-style controls (arm, solo, mute, takes, volume knob, rename, delete)
- Drum step sequencer with FluidSynth synthesis, pattern editor, clip-based timeline arrangement
- MIDI instrument tracks with full-screen piano roll editor, 128 GM instruments, and C++ MidiSequencer
- Sample-accurate MIDI and drum timing via sub-buffer scheduling (events fire at exact sample positions within render chunks)
- Measure/beat timeline with time signature support (4/4, 3/4, 6/8, 2/4) and snap-to-grid
- Project controls bar (BPM, time signature, snap toggle, position readout)
- Metronome with configurable count-in (1/2/4 bars), tap tempo, volume control. Plays continuously through recording on both Record and Studio screens. Seamless negative-position count-in with zero gap at the recording boundary.
- Take mini-drawer with rename and delete (long-press take header to open)
- Unified hardware-style UI components (NjButton, NjCard, NjRecessedPanel, NjLedDot) across all screens
- Unified indigo color palette (base and Studio tokens merged)
- Record screen with knurled record button, LCD status readout, transforming waveform panel, embossed text
- Library with tag filtering, sort options, and audio preview playback
- Overview with playback, waveform visualization, auto-saving notes, tags, sharing, and delete. Studio entry button with perimeter-tracing teal outline animation.

**What's next:**
- Track reorder, track labels
- Library long-press menu
- Contextual gesture hints for discoverability
- Play Store release prep
