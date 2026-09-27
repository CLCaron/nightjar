# Import Audio To Begin An Idea

## Status

Active priority as of September 26, 2026. Implementation is underway on `feat/import-record-workspace` in `docs/worktrees/screen-off-capture`, branched from the preserved Capture Test commit `ae0e8cc`. The current contract below supersedes the older first-slice design wherever they differ. Model/session switches are optional recommendations under the revised AGENTS.md; the user authorized implementation in this session. The older `feat/import-audio-idea` / `C:\tmp\Nightjar-import-audio` reference is historical and must be inspected before any reuse.

## September 26 pivot: imported song straight into exploration

### September 27 approved expansion: common audio formats

Chris approved automatic conversion of supported received audio to the working WAV format, with original bytes preserved. Implement on `feat/compressed-audio-import` from current Capture Test `dae2fd7` in the same clean worktree. This retains Explore/Record/import and the calibration foundation; do not substitute the separate older Explore checkout or reset to main. No recording engine, compensation or database changes are required. Main release remains gated by existing device acceptance.

Keep the existing PCM/float WAV parser and normalizer. Detect RIFF/WAVE by bytes, independent of extension/MIME. Other audio uses MediaExtractor and synchronous MediaCodec decoding on the existing import IO coroutine. Target M4A/AAC, MP3, FLAC and Ogg where Android's device extractor/decoder supports them. Unsupported, corrupt, protected and non-audio files fail clearly; do not promise every file format. Decoder-selected sample rate, channel count and PCM encoding govern interpretation. Drain end-of-stream, bound decoder stalls and output size, check cancellation each iteration, release codec/extractor on all paths, and clean temporary decoded WAV and provisional imported files. A midstream PCM layout change fails rather than corrupting audio.

Decode in bounded buffers to a temporary PCM WAV, then reuse existing normalization to mono PCM16/44.1 kHz. This uses extra temporary disk space but avoids loading whole songs in RAM and preserves the tested WAV path. Original byte storage and atomic imported source/Backing/Vocals creation remain unchanged. No sound starts during import; the source URI can disappear afterward. The UI continues its stable Import control and IMPORTING state; no new format chooser or musical terms.

Verification: JVM checks for streamed WAV finalization, decoder buffer ranges/alignment, signed PCM/float, malformed/empty data, bounds and existing conversion regressions. Device tests use generated short AAC/M4A plus the user's extensionless `Joe 1` regression file kept outside Git; verify normalized header/duration/non-silent signal, original hash, success hierarchy and failure/cancellation cleanup. Real-device import/listening and Play/Record/manual take/Stop/re-entry remain acceptance checks. Fresh verified Capture Test backup before in-place update. Export format selection remains independent future scope under Export / share as mix-down or stems in TASKS.md.

Local checkpoint: AudioImporter now dispatches to PlatformAudioImportConverter; PcmWavWriter spools actual decoder PCM before the existing normalizer. Decoder input/output EOS, output layouts, range checks, size/stall bounds, resources and cancellation are handled off the recording path. Existing source ownership/atomic workspace creation remain unchanged, with no schema or native audio edits. Sixty focused audio JVM tests pass, including four new spool tests. CompressedAudioImportTest compiles with generated extensionless AAC, provisional-file failure cleanup, decoder cancellation and optional private full-song regression (including original SHA-256 and duration comparison). The phone disconnected before this work; these decoder tests and the new build have not been run/installed on it. Do not mark compressed-format/device acceptance complete until they pass. Existing three obsolete test sources are temporarily isolated only for focused compilation and restored unchanged; this is not a full-suite pass.

Prior WAV device/user gate: schema v16-to-v17 migrated successfully on Galaxy Z Fold4 after backup, preserving all application-table rows and 83 recording hashes. Nine existing capture/import/native-loop instrumented tests passed. Chris reports Cakewalk WAV import and the basic Record exploration flow working. This does not cover the new compressed path. Current installed build remains the preceding calibration foundation.

The immediate use case is a bandmate sending a WAV song without vocals. Import must create a new Idea and make it immediately usable for writing and recording vocals in the existing Record workspace. Overview or Studio must not be a required intermediate step.

### Priority and preserved baseline

- Import is the next implementation priority; automatic latency calibration follows it, before expanding playback combinations or the Studio reconstruction bridge.
- Preserve `feat/record-workspace` at commit `ae0e8cc` in `docs/worktrees/screen-off-capture`. It includes the user's confirmed single-backing capture and the locally tested related-pair playback slice. Do not reset to `main` or substitute the dirty root checkout.
- The root checkout contains unrelated migrations and a modified AudioImporter. Inspect useful code read-only, do not overwrite, commit, or silently transplant its uncommitted changes. The current Capture Test checkout has no AudioImporter; the older import UI is not available there.
- Keep the separate `com.example.nightjar.capturetest` installation and all Ideas, groups, takes, writing, repeated Record presses, and screen-lock behavior. Back up before installing.
- The pinned Explore resume instructions are in `specs/explore.md` and `docs/TASKS.md`. Finish import, then calibration, then return to that checkpoint.

### First useful journey

1. On Record, press a stable captioned Import control and choose an audio file using Android's picker. No microphone permission or musical setup is required.
2. Copy/convert the file into private storage and create a normal Idea transactionally. Suggest the file stem as the title; the user can rename it later.
3. Open that Idea on the current Record workspace. Its first group is `Backing`, containing the imported take, selected for playback. A separate empty `Vocals` group is the open recording destination. Names remain editable; this is normal group/take data, not a special project type.
4. Playback and microphone remain stopped. Press Play to hear the imported song; press Record to record into Vocals without restarting the song. Record again manually begins the next take; Stop ends capture and playback.
5. Write stays available alongside the backing. Leaving and reopening restores the imported audio and any vocals/words; neither playback nor capture restarts automatically.
6. Latch a saved vocal alone to hear it alone. Latch its exact imported backing as well to use the existing related-pair playback path. Existing limitations on unrelated combinations and recording over multiple selected sources still apply.

The whole imported file is the first backing loop. It plays from its beginning after Play and repeats at its actual end; it does not change tempo or split vocal recordings. Seeking, auditioning a verse in isolation, and setting a shorter section are follow-ups tracked under Direct waveform part selection, not requirements to complete import.

### Reliability and architecture contract

- Keep import/conversion in dedicated audio classes and persistence in repositories; ViewModel coordinates state/effects without handling decoder internals.
- Start with uncompressed PCM and floating-point WAV, including common 48 kHz stereo/24-bit exports. Normalize the playback copy to 16-bit mono 44.1 kHz to match the engine. Report unsupported formats clearly; other Android-decodable formats follow under the task tracker.
- Preserve the original imported bytes in private storage as well as the normalized playback copy. The source URI may disappear after import. Define durable ownership and delete both only with explicit Idea deletion; do not leave an untracked original in cache.
- Stream conversion with bounded memory for full songs; do not load a whole multiminute WAV into a FloatArray. Validate headers, frame alignment, channel/format support, finite samples, nonempty audio, and available output limits. Compute duration from output frames.
- Keep UI controls stable, expose an honest importing state, prevent overlapping record/import/play actions, and serialize workspace replacement with pending recording saves and writing saves. Cancellation or failure must leave the current Idea intact, clean partial files, log the failure, and show a useful message.
- Create the imported source, playback take, Backing group/latch, and empty Vocals group as one consistent result. A database failure must clean provisional files or retain explicit recoverable ownership. Raw source preservation may require an additive ownership model; inspect the actual schema before choosing it.
- Reuse existing backing timing and latency compensation. Import does not solve acoustic latency: keep calibration confidence honest, retain raw vocals and correction, and do not claim measured synchronization from an estimated route delay.
- Studio remains optional and must be able to access the imported backing through normal audio track/clip/take data. Existing explicit group inclusion rules still apply to vocal groups.

### Incremental delivery and gates

Implementation ownership decision: database v17 adds `imported_sources`, keyed by the normalized playback filename with the original filename and an Idea foreign key. Source ownership outlives removal of a playback take and cascades only with its Idea. Import creates source ownership, backing/active take/latch, and empty vocal group in one Room transaction. Explicit Idea deletion collects original and playback files before cascading rows. No native recording or compensation changes are required for import.

September 26 implementation checkpoint: core commit `6ca8ac9` and workspace commit `00fce22` are saved on `feat/import-record-workspace`; the worktree is clean. Capture Test and Capture Test instrumentation APKs build successfully. The focused JVM run passes 38 tests: 7 WAV conversion, 25 CaptureSession (including import success/failure), and 6 RecordViewModel regressions. The converter covers PCM 8/16/24/32-bit, stereo downmix, float/48 kHz conversion, changing-signal interpolation, truncation, invalid floats, and cancellation. Device tests for atomic imported hierarchy, rollback, retained original ownership, and v16-to-v17 migration compile but have not run. ADB sees no connected phone, and neither the new APK nor the migration has been installed on the phone. The root checkout's unrelated dirty files are untouched; AGENTS.md was updated locally in both checkouts and remains uncommitted per its Codex-file policy.

Next action when the phone reconnects: force-stop and create a fresh verified Capture Test data backup; install both APKs in place; run `com.example.nightjar.data.repository.CaptureBatchTest` against its isolated test databases; launch Capture Test so its real v16 database migrates, then compare pre/post counts and existing recording hashes. Ask the user to import a real full song through the picker, verify Backing selected/Vocals open/no automatic sound, press Play then Record then Record then Stop, try Write, leave/reopen, and listen to solo vocals and the exact backing pair. Confirm permission-free import, picker cancellation, and malformed-file failure leave existing work intact. Do not claim acoustic latency calibration from these checks. Latency calibration is the next feature after import verification; the Explore resume checkpoint stays pinned.

1. Inspect baseline, importer history, schema/file ownership, and tests; choose the smallest additive source-ownership design and document it before implementation.
2. Implement bounded WAV conversion and original preservation; test representative PCM/float bit depths, stereo downmix, 48 kHz resampling, malformed/truncated input, and cleanup.
3. Implement atomic imported-Idea/group creation and session attachment; test exact titles, destinations, latches, source ownership, failure cleanup, and re-entry.
4. Add picker and stable Import control in portrait/landscape and the no-microphone-permission state; test busy/cancel/failure behavior and existing recording regressions.
5. Build Capture Test and run relevant tests/typechecking after each meaningful step. Back up and install in place, then import a real song, play/write/record manual takes, stop/reopen, and verify existing data remains. Audio needs real-device and user listening validation.
6. Promote automatic latency calibration as the next active task, then resume the paused Explore checkpoint. Do not treat successful import as proof of calibrated alignment.

### Deferred scope pointers

All follow-ups live in `docs/TASKS.md`: Android share/open-with audio entry; stereo-preserving playback; import into an existing Idea or Studio track; percentage progress/cancellation/background queue; tempo detection; stretching/warping; extended original metadata; Direct waveform part selection. Compressed import is now in scope under the September 27 expansion. Original byte preservation is in scope, while extended metadata is deferred.

## Historical first-slice design

The remainder documents the earlier Overview/Studio-centered proposal. Use the September 26 contract above for current implementation, including direct workspace entry and original-source preservation.

## Why this matters

Nightjar should be useful when a song already exists somewhere else. A realistic workflow:

- User mixes a Cakewalk Sonar project down to a WAV.
- The file is on their phone.
- They import it into Nightjar.
- Nightjar creates an Idea with that audio playable in Overview and Studio.
- The user can demo vocals or new parts without being at the home studio.

This fits the app better than a generic file manager feature. Import is another zero-friction capture path, next to Record, Write, and Studio.

## Product shape

The first slice adds an `Import` hardware-style action on the Record screen. The action opens Android's file picker for audio files, copies/decodes the chosen file into app-private storage, creates an Idea, and lands in the same post-capture state used by recordings.

The imported audio should become normal Nightjar content:

- `IdeaEntity`
- first audio `TrackEntity`
- one `AudioClipEntity`
- one active `TakeEntity`
- app-private WAV file in `recordings/`

No special imported-project model in v1. The user should be able to open Overview or Studio exactly like a fresh recording.

## First-slice scope

### UX

- Add an `Import` action to the Record screen action bay.
- Use the existing tactile feature-button language: beveled body, LED accent, icon plus caption.
- Keep button footprints stable. Import should disable while recording, saving, or importing rather than appearing/disappearing.
- Pressing Import opens Android's system picker with `audio/*`.
- While importing:
  - LCD reads `IMPORTING`.
  - Record/write/studio/import actions are disabled.
  - A snackbar surfaces failures.
- On success:
  - LCD reads `SAVED` or `IMPORTED` depending on the smallest clean UI change.
  - The waveform panel uses the imported WAV and follows the existing tap-to-Overview behavior.
  - Studio action opens the created Idea in Studio.
- Import should not require microphone permission. If microphone permission is missing, the user still needs a path to Import and Library. Recording can continue to request mic permission when the user records.

### Audio import

Add a Kotlin-side importer, likely `AudioImporter` in `audio/`, injected into `RecordViewModel`.

Responsibilities:

- Accept a `Uri` from the Android picker.
- Decode with Android platform APIs (`MediaExtractor` + `MediaCodec`) so common phone formats work.
- Supported input target: any audio format Android can decode on the device, including WAV, MP3, M4A/AAC, FLAC, and OGG when supported by the OS/provider.
- Convert decoded PCM to Nightjar's internal playback format:
  - WAV container
  - PCM 16-bit
  - mono
  - 44.1 kHz
- Write to `RecordingStorage.createRecordingFile(prefix = "import", extension = "wav")`.
- Compute duration from written output frames, not from metadata.
- Clean up partial output files on failure or cancellation.
- Return an `ImportedAudio` result:
  - output `File`
  - durationMs
  - suggested title from display name/file stem when available
  - optional source display name for track/take labels

The 44.1 kHz normalization is important. The native `common.h` frame conversion uses a fixed `kSampleRate = 44100`, so importing a 48 kHz WAV directly would risk timing drift even if the header parsed.

### Repository

Avoid new schema for the first slice.

Extend `IdeaRepository.createIdeaWithTrack(...)` or add a sibling method so import can pass a suggested title and display labels while recordings keep today's default behavior.

Suggested shape:

```kotlin
suspend fun createIdeaWithTrack(
    audioFile: File,
    durationMs: Long,
    titleOverride: String? = null,
    trackDisplayName: String = "Track 1",
    clipDisplayName: String = "Clip 1",
    takeDisplayName: String = "Take 1"
): Long
```

For imports:

- Idea title: sanitized source file stem if available, otherwise default title.
- Track display name: `Import 1` or sanitized stem. Prefer the source file stem if it is short enough.
- Clip display name: `Imported Audio`.
- Take display name: source file stem or `Take 1`.

### Record ViewModel

Add import action/effect flow without disturbing recording sync:

- `RecordAction.ImportAudio(uri: Uri)`
- `RecordUiState.isImporting`
- Optional `PostRecordingState.source` only if needed for LCD copy.
- Import runs in `viewModelScope` on the importer/repository path.
- Starting a recording cancels or rejects import if one is in progress.
- Backgrounding during import should not call `stopRecording()` because no recording is active.
- Errors should be logged and surfaced with `RecordEffect.ShowError`.

### Record Screen

- Add an `ActivityResultContracts.OpenDocument()` launcher with `arrayOf("audio/*")`.
- Do not persist URI permission unless implementation proves it is needed. The app copies immediately into private storage.
- Wire Import button to launch the picker.
- Wire picker result to `RecordAction.ImportAudio(uri)`.
- Keep portrait and landscape layouts consistent.
- Consider making the no-mic-permission state less blocking:
  - Keep an obvious mic permission control.
  - Still expose Import and Library.
  - Do not force microphone permission before file import.

### Testing and verification

Automated:

- Unit tests for `RecordViewModel` import success and failure using a fake importer.
- Unit tests for repository title/label override if repository signature changes.
- Pure JVM tests for WAV header writing and resampling helpers if those pieces are factored out of Android media decoding.

Manual/device:

- Import a Cakewalk-style WAV, ideally 48 kHz stereo and/or 24-bit source.
- Import an MP3 and M4A from phone storage.
- Verify imported audio plays in Overview.
- Verify imported audio appears as Track 1 in Studio.
- Verify recording a vocal overdub against the imported track still uses existing latency compensation.
- Verify import works without microphone permission already granted.
- Verify canceling the picker leaves the screen unchanged.
- Verify failed/unsupported import deletes any partial file.

## Implementation phases

### Phase 1 - Spec and branch hygiene

- Keep dirty Groove work untouched.
- Use clean worktree `C:\tmp\Nightjar-import-audio` on `feat/import-audio-idea`.
- Update this spec and `docs/TASKS.md`.

### Phase 2 - Import core

- Add WAV writer/resampler helpers.
- Add `AudioImporter`.
- Add Hilt provider if constructor injection is not enough.
- Add tests for pure helpers.

### Phase 3 - Repository and ViewModel

- Add import action/state/effects.
- Add title/label override path.
- Add ViewModel tests with fake importer.

### Phase 4 - Record UI

- Add file picker launcher.
- Add Import control in portrait and landscape.
- Make no-mic state import-capable if the layout impact stays contained.

### Phase 5 - Verification

- Compile debug Kotlin.
- Run relevant unit tests.
- Device-test real imports and at least one imported-track overdub.

## Risks

- Android decoders vary by device and OS. The importer should report unsupported files cleanly.
- Large files can take time and storage. First slice can be synchronous from the user's perspective, but it needs a clear `IMPORTING` state and partial-file cleanup.
- Streaming resampling is trickier than loading all samples into memory. Prefer streaming if reasonably contained; otherwise cap errors must be explicit.
- Any change near recording must avoid the overdub sync path. Import should create files offline and then reuse the existing playback/recording model.

## Out of scope

- Stereo-preserving imported audio playback. The first slice downmixes imported files to mono to match the current native track source.
- Importing audio into an existing Studio project or onto a selected track. First slice creates a new Idea from the Record screen.
- Import progress percentages, cancellation UI, and background import queue for very large files.
- Beat/tempo detection from imported files.
- Time-stretching, warping, or tempo matching imported audio.
- Preserving original source metadata beyond a suggested title/display name.
