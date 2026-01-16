# Nightjar

**Nightjar** is a zero-friction audio capture app for Android: open the app → hit record.  
When you're ready, open a recording into a simple workspace to title it, take notes, favorite it, and play it back.

This app is ideal for capturing ideas in the moment before they are lost so that you can explore them later.
Nightjar is being built as a clean, easy-to-use Android app with a path toward deeper creative tools over time.

---

## Why Nightjar?

Most voice memo / audio recorder apps feel bloated when you’re trying to catch an idea fast. Nightjar is designed to be:

- **Instant**: record immediately on launch (no setup screens or friction between opening the app and hitting record)
- **Safe**: auto-save recordings to avoid losing ideas
- **Organized**: a library view with metadata and favorites
- **Expandable**: a simple foundation for now, with a vision for a creative workspace to explore creative ideas in the future

---

## Current Features

- One-tap recording flow (MediaRecorder)
- Auto-save recordings to app-private storage
- Room database for Idea metadata
    - title
    - notes
    - favorite flag
    - created timestamp
- Library list of saved ideas
- Workspace screen:
    - playback
    - edit title/notes
    - favorite toggle

---

## Tech Stack

- Kotlin
- Jetpack Compose
- Room (SQLite)
- Coroutines + Flow
- Navigation Compose
- Android media APIs (MediaRecorder / MediaPlayer)

---

## Project Goals

Right now the focus is making the **single-track experience feel excellent**:

- **UI/UX polish pass**: simplify screens, improve spacing/typography, reduce friction, add clear empty/error states
- **Playback UX refinement**: improve the scrub experience and overall feel of seeking/dragging during playback
- **Waveform scrubbing**: generate and display an audio waveform for each recording and use it as the primary way to scrub/seek
    - library thumbnails (small waveform preview)
    - workspace waveform (full width, interactive scrub)

Longer-term, the “ultimate” direction is a lightweight creative workspace:
multi-take / multi-track idea layering, simple timing alignment, metronome/tempo, simple MIDI instrument choices
