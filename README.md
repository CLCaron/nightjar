# Nightjar

**Nightjar** is a zero-friction audio capture app for Android, built for musicians, songwriters, and anyone who needs to catch an idea before it's gone.

Open the app. Hit record. That's it.

When you're ready, come back to title it, tag it, take notes, and — when inspiration strikes — layer new recordings on top in a lightweight multi-track studio.

---

## Screens

### Record
The app opens straight to recording. One tap to start, one tap to stop. The recording is saved automatically and you're taken to the Overview.

### Library
Browse all your saved ideas. Sort by newest, oldest, or favorites. Filter by tag.

### Overview
The home screen for a single idea. Play it back, edit the title and notes, add tags, toggle favorite, share, or delete. Jump into the Studio when you're ready to build on it.

### Studio
A simplified multi-track workspace. Your original recording appears as Track 1 on a horizontal timeline with a waveform. From here you can:

- **Overdub**: record a new audio layer while the existing tracks play back
- **Reposition**: long-press and drag a track to shift its timing on the timeline
- **Trim**: drag handles on the edges of a track to non-destructively trim the start or end
- **Scrub**: drag the playhead or use the scrubber to seek through the project

---

## Tech Stack

- Kotlin, Jetpack Compose (Material 3)
- Room (SQLite) with migrations
- Hilt dependency injection
- Coroutines + Flow
- Navigation Compose
- Media3 ExoPlayer (multi-track playback)
- AudioRecord (WAV capture for overdub sync)
- MediaRecorder (quick M4A capture on the Record screen)

---

## Audio

- **Record screen** captures M4A (AAC, 44.1 kHz) via MediaRecorder for fast, lightweight saves.
- **Studio overdubs** capture 16-bit PCM WAV at 44.1 kHz via AudioRecord, with a three-phase sync protocol to eliminate pre-roll desync between playback and recording.
- **Waveform visualization** decodes any supported format to PCM using MediaCodec, buckets peak amplitudes, and renders bar waveforms on Canvas.
- **Multi-track playback** runs one ExoPlayer instance per track, with trim applied via ClippingMediaSource and a monotonic clock driving a shared timeline position at ~30 fps.

---

## Data Model

- **Idea** — a container for a creative capture: title, notes, favorite flag, tags, timestamps
- **Track** — an audio layer belonging to an idea: filename, display name, offset, trim bounds, duration, mute, volume, sort order
- **Tag** — reusable labels linked to ideas via a junction table

---

## Roadmap

Near-term:
- Mute / solo / volume controls per track in Studio
- Track rename and delete in Studio
- Waveform scrubbing on the Overview screen
- WAV capture on the Record screen (replace M4A)
- Export / mix-down of multi-track projects

Longer-term:
- Metronome / tap tempo
- MIDI instrument tracks
- Cloud backup
- Play Store release
