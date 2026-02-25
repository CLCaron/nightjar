# CLAUDE.md — Nightjar

## What is this project?

Nightjar is a zero-friction audio capture app for Android, built for musicians, songwriters, and anyone who needs to catch an idea before it's gone. Named after a nocturnal songbird, the app is designed around late-night creativity — open the app, hit record, come back later to expand on it in a lightweight multi-track studio.

Intended for public release on the Google Play Store. Should reflect professional standards in code quality, architecture, UI/UX, and reliability.

## Tech Stack

- **Language**: Kotlin (100%)
- **UI**: Jetpack Compose (Material 3)
- **DI**: Hilt
- **Database**: Room (SQLite), version 4 with migrations
- **Async**: Coroutines + Flow
- **Navigation**: Navigation Compose
- **Playback**: Media3 ExoPlayer (one instance per track, coordinated by monotonic clock)
- **Recording**: AudioRecord (16-bit PCM WAV via `WavRecorder` for both Record screen and Studio overdubs)
- **Build**: Gradle with Kotlin DSL (.kts)
- **Min SDK**: 24 (Android 7.0)
- **Target SDK**: 36

## Architecture

- Single-module Android app (`app/`)
- MVVM: ViewModels expose state via `StateFlow`, Compose screens observe it
- Sealed interfaces for actions (user intent), effects (one-shot events), and UI state
- Repository pattern: `IdeaRepository` (ideas, tags, library), `StudioRepository` (tracks, timeline edits)
- Audio logic in dedicated classes: `WavRecorder`, `PlaybackViewModel`, `StudioPlaybackManager`
- `RecordingStorage` handles app-private file management
- Navigation Compose handles screen routing: Record → Library → Overview → Studio

### Architecture principles

- Clean separation: UI → ViewModel → Repository → Data Source
- ViewModels never hold direct references to Android framework classes (Context, Activity)
- Hilt for all dependency injection
- Single source of truth: state flows down, events flow up
- Effects via `SharedFlow` for one-shot navigation/error events

## Screens

### Record
The app's landing page and primary capture screen. Three entry points for creating ideas:
- **Record button** (crescent moon) — tap to start/stop audio recording. Live waveform visualization during recording. After stopping, the waveform remains visible with "Done" (→ Overview) and "Open in Studio" (→ Studio) options. Tapping record again starts a completely new idea — the user can always capture a separate thought immediately.
- **"Write" button** — creates an idea with no audio, opens Overview for notes/title entry. Zero-friction text capture alongside audio capture.
- **"Studio" button** — creates an empty idea and opens Studio directly for building from scratch.

Handles mic permission, backgrounding mid-recording, and errors. Starfield background with breathing ring animation.

### Library
Scrollable list of saved ideas. Horizontal tag chip bar for filtering, sort controls (newest/oldest/favorites). Tap a card to open Overview.

### Overview
Single idea home screen. ExoPlayer playback with custom scrubber, editable title/notes (600ms debounce auto-save), tag management, favorite toggle, share (FileProvider), delete (with confirmation). "Studio" button to open the multi-track workspace.

### Studio
Multi-track DAW workspace. Horizontally scrollable timeline with:
- Time ruler (tick marks every 1s, labels every 5s)
- Stacked track lanes with waveform visualization
- Gold playhead line, auto-scroll during playback
- Drag-to-reposition tracks (long-press + horizontal drag)
- Trim handles on track edges (non-destructive)
- Overdub recording: captures WAV via AudioRecord while ExoPlayer plays existing tracks, with a three-phase sync protocol to eliminate pre-roll desync
- Per-track volume slider and mute toggle (via track settings bottom sheet)
- Track rename and reorder
- Track labels — a simple per-track string for instrument category (e.g. "Guitars", "Vocals", "Keys"). Not the idea-level tag system — just a nullable `label` column on `TrackEntity`. Preset suggestions offered but freeform entry allowed. Used later for stem export folder grouping.
- Track delete with confirmation (via track settings bottom sheet)
- FAB to add new tracks

#### Studio entrance experience
The transition into Studio should feel like a distinct, memorable moment — warm, inviting, and slightly magical. The feeling: "ah, yes — here we are." Key elements:
- **Shared element transition**: when entering via "Open in Studio" from the Record screen, the captured waveform animates directly into Track 1's lane. The idea physically travels into the studio.
- **Timeline unfurl**: track lanes cascade in from the left with staggered timing, the ruler sweeps across. Smooth and deliberate, not flashy.
- **Golden playhead pulse**: a single warm pulse when the Studio settles — the studio is alive and ready.
- **Ambient warmth**: a subtle glow on the track area that fades to steady state, like the room lights coming up to a warm dim.
- **FAB breath**: the add-track FAB does a single gentle scale pulse, drawing the eye.
- Animations should be brief (~400–600ms total) and identical every time — ritual, not spectacle. Never feel like they're in the way, but always register.
- The cold-open path (Studio button from Record screen, no recording) gets the same entrance sequence minus the shared waveform transition.

## Audio

### Recording
- **All recording** uses `WavRecorder` wrapping `AudioRecord` → 16-bit PCM mono WAV at 44.1 kHz
- **Record screen**: calls the three-phase protocol in quick succession (no playback to sync with)
- **Studio overdubs**: three-phase sync protocol — (1) start AudioRecord + discard buffers, (2) await first buffer (pipeline hot), (3) start ExoPlayer + await rendering confirmation + reset clock, (4) open write gate — audio captures in sync with playback
- **Permission enforcement**: lives in the UI layer (Compose screens gate recording behind mic permission). `WavRecorder` catches `SecurityException` as a safety net but does not check permissions itself. Playback never requires mic permission.

### Playback
- **Overview**: `PlaybackViewModel` wraps a single ExoPlayer instance, position polled at 200ms
- **Studio**: `StudioPlaybackManager` — one ExoPlayer per track, trim via `ClippingMediaSource`, monotonic clock drives global timeline at ~30 fps. Delayed-start coroutine jobs for tracks with `offsetMs > 0`

### Waveform
- `WaveformExtractor` decodes any platform-supported format to PCM via `MediaExtractor` + `MediaCodec`, buckets peak amplitudes, normalizes to 0–1
- `NjWaveform` composable renders bar waveforms on Canvas, resampled to canvas width at draw time

## Data Model

### IdeaEntity (table: `ideas`)
Pure metadata container: `id`, `title`, `notes`, `isFavorite`, `createdAtEpochMs`. All audio lives in TrackEntity.

### TrackEntity (table: `tracks`)
Audio layer belonging to an idea: `id`, `ideaId` (FK, cascade delete), `audioFileName`, `displayName`, `sortIndex`, `offsetMs`, `trimStartMs`, `trimEndMs`, `durationMs`, `isMuted`, `volume`, `createdAtEpochMs`. Planned: nullable `label` column (instrument category string, e.g. "Guitars") for stem export grouping.

### TagEntity (table: `tags`) + IdeaTagCrossRef (table: `idea_tags`)
Reusable labels linked to ideas. Normalized name for deduplication.

Schema: v1 (ideas) → v2 (tags + junction) → v3 (tracks) → v4 (remove audioFileName from ideas). Migrations implemented.

## Design & Theme

The visual identity is built around a **night sky metaphor** — Nightjar is for late-night ideas.

- **Dark-first**: deep midnight blues and warm near-blacks
- **The moon**: the Record button is a crescent moon in muted gold that fills to a full moon when recording, then morphs to a stop icon. Gold is reserved for the Record button and the Studio playhead — these are the only "bright" elements
- **The night**: everything else recedes — dusty lavender for subtle accents, starlight silver-blue for waveforms, quiet surface tones for buttons. Nothing competes with the moon
- **Color palette**: all tokens defined in `ui/theme/Color.kt` — single file, change hex values and rebuild
- **Edge-to-edge**: transparent system bars, app background bleeds to screen edges

- **Outdoor vs. indoor night**: Record, Library, and Overview are the **cool midnight** — open sky, deep blues, silver starlight. Studio is **warm midnight** — same darkness, same palette family, but the temperature shifts a few degrees warmer. Think: stepping inside a dimly lit room during the night. The deep blues tilt slightly toward indigo/warm gray, surfaces carry a faint amber undertone. Gold (`NjAccent`) appears more in the Studio (playhead, recording indicator), so the space naturally feels warmer. Possibly one Studio-specific warm variant of `NjMidnight2` for track lanes — not a second theme, just a subtle shift.

Key palette tokens: `NjBg`, `NjSurface`, `NjSurface2` (backgrounds), `NjPrimary`/`NjPrimary2` (lavender accents), `NjAccent` (gold), `NjMidnight`/`NjMidnight2` (studio track lanes), `NjStarlight` (waveforms), `NjOnBg`/`NjOnSurface`/`NjMuted`/`NjMuted2` (text hierarchy)

## Coding Standards

### General
- Jetpack Compose for all UI — no XML layouts
- Follow existing MVVM patterns: ViewModel → StateFlow → Compose
- Idiomatic Kotlin: data classes, sealed interfaces, extension functions
- Small, focused composables over monolithic screen functions
- Handle all edge cases: empty states, error states, loading states, permission denials

### Audio
- Audio logic in dedicated manager/repository classes, not in ViewModels directly
- Handle lifecycle properly — release resources in `onCleared`/`onDispose`
- Handle audio focus correctly (request/abandon per Android guidelines)
- Overdub sync is critical — any changes to the recording pipeline must preserve the three-phase protocol

### Error handling
- Never swallow exceptions silently — log at minimum, surface to user when appropriate
- Sealed interfaces to propagate errors as effects
- Validate user inputs and file operations
- Handle storage edge cases: disk full, file not found, permission revoked

### Testing
- Unit tests for ViewModels and Repository logic (MockK + Turbine + coroutines-test)
- Instrumented tests for DAOs (Room in-memory database)
- Audio features must be tested on a real device (emulator audio is unreliable)
- Known issue: Kotlin K2 compiler bug with MockK mocking `@Inject` + `@ApplicationContext` classes — tracked in test TODOs

### Performance
- Avoid unnecessary recompositions — stable types, `remember` expensive calculations
- Audio processing off the main thread
- Waveform rendering must maintain 60fps scrolling

## File Conventions

- Source: `app/src/main/java/com/example/nightjar/`
- UI screens: `ui/record/`, `ui/library/`, `ui/overview/`, `ui/studio/`
- Shared components: `ui/components/` (prefixed `Nj` — `NjPrimaryButton`, `NjScrubber`, `NjWaveform`, etc.)
- Theme: `ui/theme/` (`Color.kt`, `Theme.kt`, `Type.kt`)
- Audio: `audio/` (`WavRecorder`, `WaveformExtractor`)
- Playback: `player/` (`PlaybackViewModel`, `StudioPlaybackManager`)
- Data: `data/db/` (entities, DAOs, database), `data/repository/`, `data/storage/`
- DI: `di/AppModule.kt`
- Tests: `src/test/` (unit), `src/androidTest/` (instrumented)

---

## Roadmap

### Completed
- ~~Mute / volume controls per track in Studio~~
- ~~Track delete UI in Studio~~
- ~~Switch Record screen to WavRecorder~~
- ~~Record creates Idea + first Track atomically~~
- ~~Remove `audioFileName` from IdeaEntity~~ (schema migration v3→v4)
- ~~App icon with adaptive foreground/background/monochrome~~
- ~~Splash screen dark background~~
- ~~Delete deprecated `AudioRecorder` class~~
- ~~Show duration on Library cards~~
- ~~Fix drag/trim gesture conflict in Studio~~ (unified touch-zone gesture handler)
- ~~Record screen: post-recording flow~~ (waveform preview, Done/Open in Studio, orphaned file fix)
- ~~Primary palette shift~~ (lavender → deep steel blue)
- ~~Record screen: live waveform~~ (real-time waveform visualization during recording)
- ~~Denser starfield with beacon stars~~ (doubled density, rare twinkling beacons)
- ~~Record screen: Write + Studio buttons~~ (create empty ideas → Overview or Studio)
- ~~Waveform on Overview screen~~ (WaveformExtractor + NjWaveform above scrubber)

### MVP — Play Store v1.0

The minimum version worth publishing. The bar: if a musician downloads this, uses it for a week, and tells a friend — what does it need to have?

**Not in MVP:** Widget, export/share, MIDI instruments, metronome, onboarding walkthrough, audio cues on Studio entrance, Studio entrance animation, Studio warm palette.

#### MVP status by screen

| Screen | What works | What's missing |
|--------|-----------|----------------|
| **Record** | One-tap record, visual feedback (starfield + breathing ring), auto-save, mic permission, post-recording flow (waveform preview, Done/Open in Studio), live waveform during recording, Write button (→ Overview), Studio button (→ Studio) | Verify <2s cold launch |
| **Library** | Title, date, duration, favorite, tag filtering, sort (newest/oldest/favorites) | Long-press menu (rename/delete), sort by last modified |
| **Overview** | Play/pause, scrubber, waveform visualization, title/notes editing, tags, favorite, share, delete, Studio button | Loop a section during playback |
| **Studio** | Track 1 from recording, overdub, waveforms, drag-to-reposition, trim, mute, volume, delete, playhead, loop playback | Solo toggle, track rename/reorder, track labels, loop recording with auto-takes, free tier track limit |

---

### Phase 1: MVP features
Close the gaps between current state and the MVP spec.

**Critical workflow: Loop + Takes** — The loop → record → take cycle is the core creative workflow in Studio. A musician records a guitar progression, loops that section, then records vocal takes over it — each loop cycle creates a new take. Steps A → B → C below build this incrementally. Step A (loop playback) is useful on its own; Steps B + C complete the vision.

1. ~~**Clean drag/trim**~~ — Fixed. Unified touch-zone gesture handler in `Timeline.kt`.
2. ~~**Record screen: post-recording flow**~~ — Done. Post-recording state with waveform preview, Done/Open in Studio buttons, orphaned file bug fixed.
3. ~~**Record screen: live waveform**~~ — Done. Real-time waveform visualization while recording via `NjLiveWaveform`.
4. ~~**Record screen: Write + Studio buttons**~~ — Done. "Write" creates idea with no audio → Overview. "Studio" creates empty idea → Studio.
5. ~~**Waveform on Overview screen**~~ — Done. Existing `WaveformExtractor` + `NjWaveform` renders the first track's waveform above the scrubber.
6. **Solo toggle in Studio** — runtime-only state (no DB field needed). When a track is soloed, mute all others during playback. Add toggle to track settings bottom sheet.
7. **Track rename and reorder** — rename via track settings bottom sheet (dialog or inline). Reorder via drag-to-reorder in header column or up/down controls in settings sheet.
8. **Track labels** — nullable `label` column on `TrackEntity` (schema migration). Preset suggestions ("Guitars", "Vocals", "Keys", "Bass", "Drums", "Strings", "Brass", "Synth") plus freeform entry. UI in track settings bottom sheet.
9. **Library long-press menu** — long-press a card to show rename / delete options. Rename inline or via dialog; delete with confirmation.
10. **Sort by last modified** — add `updatedAtEpochMs` to IdeaEntity (schema v4→v5, default to `createdAtEpochMs`). Update on title/notes/favorite changes. Add sort option to Library.
11. **Clean up warnings and version updates** -- There are several warnings and notifications to update versions of different things we have used in the app development. Example: The unit test that is failing
12. ~~**Loop playback in Studio (Step A)**~~ — Done. Drag on time ruler to define loop region, gold overlay with draggable handles, loop toggle + clear buttons, playhead loops between start/end. Loop disabled during overdub recording.
13. **Take data model (Step B)** — `TakeEntity` table (trackId FK, audioFileName, durationMs, isActive, createdAt). A track can have multiple takes; only the active take plays. UI: take selector in track settings bottom sheet. Schema migration v5.
14. **Loop recording with auto-takes (Step C)** — When recording with loop active, each loop cycle automatically saves the captured audio as a new take on the recording track. The previous cycle's take is kept. After stopping, the user picks the best take. This completes the core creative workflow.
15. **Gesture feedback polish** — Trim handles and loop region edges need tactile feedback so the user knows they've "grabbed" them. Trim handles: visual pop (scale up, color shift, or glow) on grab, gentle haptic pulse. Loop region: handles/triangles should visually enlarge or highlight when touched, gentle haptic on grab and on edge snap. All feedback should feel immediate and subtle — confirmation, not distraction.

### Phase 2: Play Store release
Everything needed to actually publish.

1. **Free tier track limit** — 2 tracks per idea for free users, premium unlocks unlimited. UI enforcement only for now (no billing integration yet — just a gate with "upgrade" messaging).
2. **Fix adaptive icon safe zone** — resize foreground art to fit inner 72dp of the 108dp canvas so third-party launchers (e.g. Niagara) display it correctly.
3. **Play Store listing** — screenshots, feature graphic, short/long description, category selection.
4. **Privacy policy** — required by Play Store. Simple page (no data collected, all audio stored on-device).
5. **Release build config** — app signing, R8/ProGuard minification, version code strategy.
6. **Final QA** — test full flow on 3+ real devices, verify cold launch <2s, check all edge cases.

---

### Post-MVP releases

#### v1.1 — Speed & Sharing
The two biggest gaps after MVP: getting in faster and getting ideas out.

- **Home screen widget** — record button + stop button, works without opening the app
- **Quick-launch recording** — notification shortcut, optional launch-to-record from app icon
- **Export & share** — mix down to WAV/MP3, share via Android share sheet, export individual stems (grouped into folders by track label when present)

#### v1.2 — Polish & Engagement
Response-to-feedback release. Expect this to shift based on real user data.

- **Waveform improvements** — pinch-to-zoom on timeline, background generation on save, prettier waveform
- **Library enhancements** — bulk select/delete, sort by last modified (tracks added in Studio)
- **Studio entrance animation** — timeline unfurl, playhead pulse, warm ambient glow, FAB breath, warm palette shift. ~400–600ms. (Stashed in git: `git stash list`)
- **Studio enhancements** — undo/redo for track operations, duplicate a track, snap-to-grid
- **Onboarding** — brief first-launch walkthrough (3–4 screens), tooltip hints on first Studio use

#### v2.0 — The Creative Toolkit
Nightjar evolves from "capture + layer" to "capture + create." Premium tier starts making real sense.

- **Track effects** — per-track audio effects (reverb, EQ, etc.). Start with Android's built-in `AudioEffect` API (`PresetReverb`, `EnvironmentalReverb`, `BassBoost`, `Equalizer`) — no licensing concerns for a paid app. Custom DSP via Oboe/C++ is the long-term path if built-ins feel limiting. UI: a button on each track opens an effects panel with knobs/sliders. Subtle haptic feedback (detent-style, like turning a real knob past notches) as values change — disableable in settings. Premium feature.
- **MIDI instrument tracks** — on-screen piano, record MIDI data as a track, basic built-in sound, export as `.mid`
- **Metronome & tempo** — tap tempo / manual BPM, click during recording (headphones only), timeline grid aligns to BPM
- **Advanced export** — structured project folder (stems + MIDI + metadata), cloud storage sharing

#### v2.x+ — Dream features
No promises, no timeline. Ideas that could make Nightjar exceptional.

- Additional instruments (drum pad, synth, guitar amp sim)
- Collaboration (share project links, add tracks, version history)
- Cloud & sync (backup, cross-device, web player)
- Audio intelligence (auto-detect BPM, chord suggestions, noise reduction)
- Platform expansion (iOS via KMP, Wear OS widget, tuner)

---

## Monetization

Nightjar is free with optional paid features. The core recording, multi-track, and editing experience should remain free. The app should never feel crippled without paying.

**MVP (v1.0):** Free tier limited to 2 tracks per idea. Premium unlocks unlimited tracks. No billing integration at launch — just UI enforcement with "upgrade" messaging.

**Future paid features:**
- Unlimited tracks per idea (free tier limited to 1 drum track, 1 MIDI instrument, 2 audio recordings)
- Unlimited takes per track (free tier limited to ~3)
- Cloud backup / sync
- Additional export formats
- Keys/scales for MIDI instruments

## How to work with me

- Explain what you're doing and why so I can learn
- If a task is large, break it into smaller steps and check in after each one
- Suggest improvements or refactors when you notice code that doesn't meet these standards
- Commit frequently with clear, conventional commit messages
- Do not leave comments like "Written with the help of Claude" in commit messages
- Do not add Co-Authored-By or any Claude/AI attribution to commits, PRs, or anything pushed to GitHub
- Do not commit any Claude-related files
- Do not commit .gitignore
- Help me learn proper Git workflow, by suggesting branch names as well as explaining how you know it's a good time to be committing, and also when we should push
- Always consider: "Would this pass code review at a professional Android shop?"
