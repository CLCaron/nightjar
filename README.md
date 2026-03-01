# Nightjar

Ideas don't wait. They show up in the middle of the night, in the shower, between conversations -- and they leave just as fast. **Nightjar** is a zero-friction idea capture app for Android, built for musicians, songwriters, and anyone whose best ideas arrive at the worst times.

Sing it. Say it. Write it down. Build it up. However the idea comes, Nightjar catches it -- and gives you a place to develop it before you ever open a DAW.

Named after a nocturnal songbird, this app is designed around late-night creativity but there for you any time of day. The warm plum-tinted palette and hardware-inspired controls create a calm, inviting space -- like a pocket recorder you'd pull out in a dim room.

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
- **Overdub** -- record new layers while existing tracks play back, with hardware-compensated sync
- **Takes** -- arm a track and record multiple takes. Each take is an independent audio clip on the timeline. Tap to mute, long-press for rename/delete. Drag and layer takes freely.
- **Loop recording** -- with a loop region active, record continuously. On stop, the recording is automatically split into individual takes at each loop boundary -- zero gaps, no manual slicing.
- **Loop playback** -- drag on the ruler to define a loop region, with draggable handles
- **Drag to reposition** -- long-press a track and slide it along the timeline
- **Non-destructive trim** -- drag handles on track edges
- **Per-track controls** -- volume knob, arm (R), solo (S), mute (M), takes (T), rename, delete via inline track drawer. Responsive layout adapts to narrow screens (e.g. Galaxy Fold cover display).
- **Hardware-style controls** -- beveled toggle buttons with LED glow, rotary knob with haptic detents, consistent across all screens

---

## Organize it

The **Library** is where ideas live long-term. Sort by newest, oldest, or favorites. Filter by tag. Tap any idea to open its Overview.

---

## Tech Stack

- Kotlin + C++17, Jetpack Compose (Material 3)
- Room (SQLite) with schema migrations
- Hilt dependency injection
- Coroutines + Flow
- Navigation Compose
- Oboe audio engine (C++ via NDK) — lock-free, callback-based recording and multi-track playback with hardware timestamp latency compensation

---

## Status

Nightjar is in active development, working toward a v1.0 Play Store release.

**What's working:**
- Full capture → save → organize → multi-track workflow
- Three entry points for idea creation (record, write, studio)
- Live waveform during recording with post-recording preview
- Native C++ audio engine (Oboe) — low-latency recording and multi-track playback
- Multi-track overdub with hardware timestamp latency compensation
- Per-track takes with arm toggle, loop recording with auto-split into takes
- Drag-to-reposition, non-destructive trim, per-track volume/mute/solo/delete
- Loop playback with ruler-based region selection and toggle controls
- Record button in the Studio button panel (alongside Loop and Play/Pause)
- Inline track drawer with hardware-style controls (arm, solo, mute, takes, volume knob, rename, delete)
- Take mini-drawer with rename and delete (long-press take header to open)
- Library with tag filtering and sort options
- Overview with playback, waveform visualization, auto-saving notes, tags, sharing, and delete

**What's next:**
- Track reorder, track labels
- Library long-press menu
- Contextual gesture hints for discoverability
- Play Store release prep
