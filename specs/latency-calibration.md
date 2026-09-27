# Latency Calibration

## Status

Approved for incremental implementation by Chris, September 27, 2026. Replaces the September 26 seed. Numerical thresholds are engineering gates, not device results.

User clarification, September 27: place the optional check in Settings; provide microphone selection; prefer the previous compatible Bluetooth measurement with a recalibration heads-up; do not end recording because an accessory disconnects. Show a non-blocking notice explaining the actual consequences. These choices supersede the earlier SYNC shortcuts, estimate-on-every-Bluetooth-reopen and stop-recording-on-output-change proposals. Chris authorized implementation and repository backup, then requested continuing local work until the phone is connected.

### Implementation checkpoint

September 27 acoustic validation checkpoint: resumed `feat/latency-calibration` in `docs/worktrees/screen-off-capture` by fast-forwarding the compressed importer at `7acc0d4`, preserving Explore/Record/import work. Added a temporary same-stream acoustic session: preallocated 20-second input/history buffers, fixed low-level probes in the output callback, emitted frame positions, warmup and repeat/confirmation trials, and off-callback matched filtering with common decimation. Settings exposes fixed Check and Stop controls, microphone permission, setup instructions, foreground cancellation and transient audio focus. Cancellation closes temporary input and waits for output quiescence; no Idea, raw WAV, schema or timing placement is changed. Any displayed delay is a diagnostic last-check result, not an accepted profile or Checked claim. The existing conservative callback uncertainty gate remains; dense history is collected but has not proved a replacement mapper. The next physical checkpoint must establish actual route identity, probe audibility/detection, cancellation and callback/acoustic clock behavior before saved profiles and measured Record/Studio integration. Chris will connect the phone once the validation build is ready; earbuds are charging. Obtain a fresh Capture Test data/APK backup before installation. Main stays unchanged.

Local acoustic validation checks: arm64 `:app:assembleCaptureTest` passed; focused audio JVM suite passed 63 tests with zero failures/errors, including deterministic bounded/faded probe generation, independent references, matched-filter recovery after attenuation/polarity inversion, shared downsampling phase and the existing capture/import/confidence regressions. Three pre-existing obsolete test sources were temporarily moved for compilation and restored unchanged. The full test suite and new acoustic behavior on physical hardware have not passed. Native callback review found only fixed-buffer writes, atomics and bounded signal processing for this protocol; control-thread allocation, JNI and stream lifecycle remain outside callbacks.

Repository checkpoint: acoustic validation stage committed and pushed on `feat/latency-calibration` at `36d15985f45e4f90327bb92261a4df93c47b5d93`. Tracked worktree is clean. Capture Test still has the earlier foundation installation; this build is not installed. Next action is fresh phone backup, in-place Capture Test update and the acoustic validation steps below. Main and protected `00fce22` ref remain unchanged.

September 27 install checkpoint: acoustic validation application `36d1598` installed in place on SM-F936U / RFCT80WF2QJ. Fresh pre-install backup `docs/device-backups/20260927-162429` and post-install snapshot `docs/device-backups/20260927-162751` both pass database integrity checking at schema 17. All existing application-table rows and all 97 private-file hashes match; 30 Ideas, 68 takes and two imported sources preserved. Native backing-loop/input regression passed on this build. Four compressed-import device tests passed, including the exact private extensionless Joe 1 source after verified transfer; source bytes, decoded duration and working PCM header/non-silence validated. Temporary test source removed from phone cache and staging. Corrected two instrumentation fixture issues only: non-void inferred JUnit return and wrong filename expectation for file URI without display-name metadata. App production source remains `36d1598`; physical acoustic and mic-route validation are next. Original Nightjar and main remain unchanged.

Next user checkpoint, after fresh backup and in-place Capture Test install: (1) no accessory, phone mic, quiet room, run Check twice and note delay/error plus Details route; (2) Stop halfway and then record/listen, verifying no calibration Idea was created; (3) start Check and leave Settings or lock the screen, then verify ordinary recording; (4) connect Bluetooth, choose phone mic and hold one removed earbud close to it, repeat twice; (5) repeat with headset mic when selectable and verify the actual input in Details; (6) disconnect the earbud during Check, expect cancellation with recording still available. Wired/USB and independent mapper/residual proof follow. This is calibration-session cancellation, not yet the recording disconnect-continuity feature.

September 27 first acoustic result: Chris reports phone-speaker/phone-mic Check displayed 22.6 ms delay from seven trials. Interrupting a subsequent Check with Stop returned to Timing Estimated; ordinary recording and listening still work. Device log confirms seven detected trials and both independent confirmations passing (`confirmed=true`). The conservative software callback mapper bound remains 4.35374149659864 ms, above the <=3 ms gate; this result does not authorize Checked status or measured recording correction. A second completed phone-speaker result, actual route isolation, screen departure/lock cancellation, Bluetooth with phone versus headset microphone, wired/USB, and independent physical mapper/residual proof remain pending. Do not treat Stop returning Estimated as a regression; no persistent profile is enabled in this validation build.

Before continuing acceptance, September 27 availability bug: Chris reported Check remained disabled after recording/listening until app restart. Audit found `OboeAudioEngine.pause()` changed native transport but left its published `isPlaying` flow stale once the audition poll job was cancelled. Settings used that cached flag as a calibration gate. The fix publishes transport state immediately on play/pause and queries current native playback for Settings availability and action guards; active capture, pending saves and loading remain guarded. Settings now explains a disabled check and gives clearer earbud-placement instructions without claiming a specific phone microphone location. Added a native regression that polls active playback, pauses without another poll, asserts published idle status, starts an unarmed check, then verifies temporary input release. Verification/install results follow in the active tracker.

Additional user measurements: phone speaker/microphone labels confirmed, 21.9 ms from seven trials and 22.0 ms from six trials, following the earlier 22.6 ms/seven. Bluetooth earbud runs returned 275.8 ms/seven and 270.0 ms/six after placement retries. Logs confirm all four successful runs passed independent confirmations. Phone conservative mapper bound remains 4.35 ms; Bluetooth bound is 6.53 ms. Acoustic detection repeatability is encouraging, but actual Bluetooth route-label/microphone isolation, reopened-profile compatibility and independent physical clock/placement proof remain unverified. Poor placement and a route-change attempt were correctly rejected, as seen in calibration-only logs. No measured correction is enabled.

Availability fix verification/install: arm64 application and instrumentation builds passed; 63 focused audio JVM tests passed with zero failures/errors and all three pre-existing excluded fixtures restored unchanged. The new pause/no-poll regression failed with the exact stale-state assertion against installed `36d1598`, then passed after updating the application. Both native device tests pass: continuous input over backing loops and paused transport releasing a silent timing check and its microphone. Fresh pre-fix backup `docs/device-backups/20260927-164912` and post-fix snapshot `docs/device-backups/20260927-165353` preserve every existing application row and all 99 private-file hashes: schema 17, 31 Ideas, 69 takes, two imported sources. Original Nightjar and main unchanged. Capture Test reopened; user should verify Record/Stop/listen/Stop then Settings Check without restart, and confirm Bluetooth input/output labels before headset-mic acceptance.

Active resume baseline: `feat/latency-calibration`, pushed and installed `0dcb2a6bb5f897a1492d4687dc579c54eee77306`, protected worktree `docs/worktrees/screen-off-capture`, tracked worktree clean. Chris clarified the Bluetooth run labels were not inspected. Treat the reported delays as unassigned acoustic diagnostics until one repeat confirms the actual input/output pair. Next user step combines availability acceptance with one Bluetooth/phone-mic check and route-label report. Main unchanged.

Latest post-fix route confirmation: Chris reports Listening through Bluetooth headphones: Christopher's Buds FE, Recording with Last used: Phone microphone. This confirms the latest check's actual output/input readout; Last used is expected because temporary calibration input is closed after completion. Calibration log for the updated application reports six detected trials with independent confirmations passing and 6.53 ms conservative mapper bound. Latest numeric delay and explicit user confirmation of Check availability after Record/Stop/Play/Stop without restarting are still awaiting feedback. Do not retroactively assign the earlier unlabeled Bluetooth measurements to this verified pair.

September 27 next-phase direction: Chris confirmed the post-fix Buds FE output / phone-microphone check returned approximately 275 ms and Check was available after recording without an app restart. He requested moving on with implementation instead of further headset tests. Record the availability regression as accepted by user report; headset-mic, lock-screen/call cancellation, wired/USB and independent clock/placement acceptance remain unverified, not waived release gates.

Measurement persistence implementation: additive v17-to-v18 migration creates only `calibration_measurements` plus its route index. It does not edit old rows, phases, trims or audio files. Revisions are insert-only diagnostic evidence, deliberately a different type/table from accepted correction profiles. Persist actual opened pair/configuration, identity scope, epochs/session, delay frames/rate, accepted/rejected count, MAD/range, full per-trial quality and emitted positions, software mapper evidence/bound, confirmations, validity reason and engine/mapper/probe versions. Stable built-ins and observable Bluetooth MAC identity can form durable keys; ambiguous built-ins, unknown identities and USB bus/port-only identity are session-scoped. Do not use product names or runtime IDs alone across sessions. Configuration keys include both formats, buffers/bursts, backend, channels/rates, sharing/performance, preset, mode, transport and platform revision. Compare known identity/configuration while checking; unknown pairs can produce an explicitly unsaved diagnostic result.

Saved diagnostic history appears in Settings Details with the exact pair and Not applied status. A failed confirmation is explicitly unconfirmed. No diagnostic row can be selected by the accepted-profile policy or silently change Record/Studio compensation. Cancel leaves existing history untouched; save failure surfaces and preserves earlier revisions. Confidence checks now inspect raw clipping before decimation and use maximum callback block sizes over the captured history for the conservative bound. Output/input anchors must be warm before sound emission. Synthetic migration/storage/reopen tests and device migration/hash verification are required to accept this checkpoint; installation requires a fresh verified backup. Accepted-profile persistence, mapped timing/source ownership and Record/Studio correction remain subsequent work after independent mapper proof.

Local persistence verification: arm64 Capture Test application and instrumentation APK builds pass; 66 focused audio unit tests pass with zero failures/errors. Four new device storage tests compile but have not run. Applying the exact v17-to-v18 SQL to a disposable copy of the latest preserved phone database matches Room's generated table schema and preserves all 224 existing rows across 17 tables, with integrity checking passing. This host check does not replace device migration or hash acceptance. The phone is currently disconnected; no new installation or main merge. Next action is clock-mapper audit and independent timing evidence, followed by fresh backup and the device storage/migration checkpoint when available.

### Historical foundation checkpoint

Work is on `feat/latency-calibration` in the protected Capture Test worktree, based on `00fce22`. Local foundation adds opened input/output stream evidence, callback frame anchors/epochs, dropped-frame telemetry, Settings > Audio Sync route readouts and stopped-only microphone selection. External selections are process-scoped because runtime IDs are not persistent device identities. Explicit selection is verified after input opens. The estimator now categorizes the opened output instead of an unrelated connected accessory; its existing compensation formula remains.

Pure Kotlin foundations cover acoustic peak detection/rejection, repeated-trial confidence, compatibility policy and signed timing math. These are not yet connected to a probe session, saved profiles or recording correction. The callback mapper is provisional, with a reported conservative uncertainty bound; it has not passed the physical clock gate. No acoustically Checked claim, calibrated placement, database migration, route-loss capture behavior or Studio retiming is enabled. Historical files/phases/trims remain untouched.

Capture Test is installed and its import database migration is verified. Physical microphone isolation, uncalibrated screen-lock capture and mapper validation remain pending. Complete Phase 1 physical proof before dependent measured integration. Feature branch pushes are authorized backups; merging to main remains gated. Older root Explore/importer/migration work is preserved separately.

September 27 phone checkpoint: Capture Test foundation `f55e7fc` installed in place on Galaxy Z Fold4 (SM-F936U), serial RFCT80WF2QJ, after verified backup of installed APK/private data. Backup: root `docs/device-backups/20260927-090329`, database v16, 26 Ideas, 62 takes, 83 recording files. Nine isolated device tests pass: eight CaptureBatch tests including synthetic v16-to-v17 migration/rollback and one native continuous input/backing-loop test. Fixture fix saved/pushed at `6ae58f7` excludes Android's pre-created android_metadata table. Original Nightjar remains untouched. After Chris opened Library, real v16-to-v17 migration passed integrity checking. Post-migration snapshot `docs/device-backups/20260927-092052` matches every existing application-table row and all 83 recording hashes, with zero changed/missing recordings. Hands-on import, listening, microphone and lock-screen acceptance remain pending.

Native smoke evidence: both streams opened at 44.1 kHz with 96-frame callbacks, zero observed dropped frames at the snapshot; input device ID 22, output ID 3. These runtime IDs do not prove physical mic identity. The provisional mapper's two-block uncertainty bound is about 4.35 ms, exceeding the <=3 ms measured-mode gate. Do not enable measured correction from this test. Physical route isolation, reopen stability, acoustic residuals and lock-screen capture still require acceptance.

Local verification: arm64 Capture Test app and instrumentation APK builds passed with JDK 21. Focused `:app:testCaptureTestUnitTest --tests 'com.example.nightjar.audio.*' -Pnightjar.testBuildType=captureTest` passed 56 tests, including 16 new foundation tests and existing capture/import tests. Three pre-existing incompatible test sources (LibraryViewModelTest, OverviewViewModelTest and instrumented TrackDaoTest) were temporarily isolated for compilation and restored unchanged. Full-suite verification has not passed: those API mismatches block ordinary compilation, and an attempted broader run exhausted memory in existing StudioViewModel tests. Instrumentation was compiled, not run. No physical timing results exist.

Record remains available without calibration, accessories, or musical knowledge. Calibration improves future overdub placement; it cannot remove wireless monitoring delay or infer a performer's intended timing.

## Protected baseline and handoff

- Audited worktree: `C:/Users/chris/AndroidStudioProjects/Nightjar/docs/worktrees/screen-off-capture`, branch `feat/import-record-workspace`, exact HEAD `00fce22048fa2172fae2d57fa2dbff3fb3ecfc2a`. Clean tracked worktree. Preserve import, Record/Write, groups/latches, manual takes, raw recordings, saved phase and screen-lock support. Never reset this baseline to main.
- Root: `feat/explore-sections` at `410d47dee22fbe6a0d2fa03fa5da132d4f346d3d`. Protected dirty AudioImporter.kt, NightjarDatabase.kt, TrackDaoTest.kt, untracked LegacyGrooveMidiMigrationTest.kt and androidTest data/repository files; leave remote attachments untouched.
- Prior checks, not rerun here: two Capture Test APK builds and 38 focused JVM tests. Import phone backup, v16-to-v17 migration, install, instrumentation and listening checks remain pending. Original Nightjar installation stays untouched.
- Paused checkpoint `feat/record-workspace` at `ae0e8cc`: related-pair code is in the import lineage, but device checks, selection timing and Studio bridge remain pending per `specs/explore.md`.
- Actual baseline is database v17, Oboe 1.9.0, requested engine rate 44.1 kHz. Do not use the older generic v12 description for migrations.
- This root spec is canonical; root `docs/TASKS.md` is the protected local tracker. The implementation branch carries a reviewable spec snapshot. Refresh it when the canonical plan changes.

## Current code audit

Paths below are under the audited worktree's `app/src/main/`. These are static findings, not evidence of the phone's live route.

| Area | Existing behavior | Implication |
| --- | --- | --- |
| `java/com/example/nightjar/audio/AudioLatencyEstimator.kt` | Scans available outputs, prioritizes Bluetooth, then USB/wired/built-in, with deprecated Bluetooth flag fallback. SCO/A2DP/LE/hearing aids share a category. | Connected is not routed; actual mic is unidentified. |
| `cpp/oboe_recording_stream.cpp::start` | Requests mono float, 44100, exclusive low latency, Unprocessed, no selected device. | Android chooses input; requested preset does not prove processing. |
| `cpp/oboe_playback_stream.cpp::openStream` | Requests stereo float, 44100, Media, exclusive low latency, no selected device. | Inspect both actual streams after input opens. |
| Native latency getters | `calculateLatencyMillis`, integer-ms truncation, -1 on error. Kotlin uses positive values or buffer estimates; Bluetooth adds 250 ms with a 150-500 ms clamp. | Useful estimates, not guaranteed acoustic totals. Comments promising Bluetooth codec coverage must change. |
| `computeCompensationMs` | `max(0, preRoll + outputIfBacking + input - manualOffset)`. Global +/-500 ms manual setting; negative means earlier. | Measured total must replace estimates, never stack with them. |
| `audio/CaptureSession.kt::foregroundReady/save` | Starts input, awaits callback, opens gate; snapshots correction only with backing. First accepted native buffer reads transport position; save adds manual boundary offsets. | Start is a callback-time render observation, not sound at the ear. Count-in uses coroutine delay. Silent capture has no backing correction. |
| `data/repository/IdeaRepository.kt::CaptureBackingContext` | Saves `floorMod(transportStartFrame - correctionFrames, backingLoopFrames)` with `estimated` method. | Preserve historical phase; new take boundaries must not apply another correction. |
| `cpp/oboe_playback_stream.cpp::onAudioReady` | Render counter advances by buffers and wraps at loops; output can run while transport pauses. | Wrapped transport is not a common input/output clock. |
| `ui/studio/StudioViewModel.kt::startRecordingAfterPermission` | Starts input/playback, delays count-in, opens gate, then subtracts nearly adjacent nanoTime reads as pre-roll. Saves intended cursor and trim correction. | Pre-roll does not measure actual retained input versus playback boundary. Unmuted-track test misses metronome-only timing and is not proof of audible backing. |
| Studio stop / `cpp/track_mixer.cpp` | Bounded compensation becomes trimStartMs; source = trimStart + timeline - offset. | Synchronization and editing trim are mixed; signed placement and short takes need handling. |
| Studio loop save | Splits by polled loop reset times, compensates first segment only, deletes continuous original after successful splits. | New calibrated loop takes need durable source ownership and frame-based mapping. Deleted historical originals cannot be reconstructed. |
| Error handling | Output reopens automatically; input marks inactive; Record drains/finalizes on interruption. | A reopened output can invalidate correction while input continues. |

No setDeviceId, setCommunicationDevice, startBluetoothSco, setPreferredDevice or AudioDeviceCallback usage was found in baseline main source. Actual phone/headset microphone identity must be exposed and physically verified in Phase 1.

## Proposed UX

Place **Audio Sync** in the existing **Settings** menu, including microphone selection. Do not add a dedicated SYNC control to Record or Studio. Reconcile Studio's existing Audio Sync entry with the shared Settings destination during implementation, avoiding duplicate settings. No launch wizard, mandatory prompt or automatic test sound. Keep primary capture controls unchanged. Use Nj components with color parameters, theme tokens, Space Grotesk and IBM Plex Mono captions. Status uses text plus LED, never color alone.

Panel shows **Listening through**, **Recording with**, and **Timing**. Input and output names are independent actual routes. Idle shows `Last used` or `Not checked yet`, never pretends a mic stream is open. Status: `Estimated`, `Checked`, `Previously checked`, `Check again`, `Delay varies`. Previously checked means a compatible stored measurement is being reused without current-session acoustic verification. Milliseconds stay in details.

Fixed controls: **CHECK**, **STOP**, **DONE**, **DETAILS**. CHECK repeats after results; STOP cancels. Disabled controls remain visible. Details has fixed EARLIER/LATER/RESET controls for route-scoped adjustment; preview sound starts only after Play.

Before Check: `We'll play a few short sounds and listen for them. You can record without doing this.` Ask microphone permission only for Check or Record. Testing requires stopped, foreground state, no capture/save/import/Idea playback. Entry preserves latches and destination. Back, Done, Stop, focus loss, screen lock or background cancels, releases test input, restores owned routing preferences and selections, leaves playback stopped. No automatic tone on return.

| Actual output / input | Setup |
| --- | --- |
| Phone speaker / phone mic | `Set your phone down somewhere quiet.` No accessory needed. |
| Wired or Bluetooth earpiece / phone mic | `Take one earpiece out and hold it close to your phone's microphone.` |
| Earpiece / headset mic | `Hold an earpiece close to the headset microphone.` Inaccessible/suppressed mic means fallback, never silent substitution of phone mic. |
| USB output / phone mic | Actual audible speaker/earpiece near phone mic; distinct profile from USB input. |
| USB output / USB microphone | Speaker/earpiece near the actual USB microphone. |
| USB line/instrument input | Needs a connected mic for acoustic test. Electrical loopback only with user-provided compatible, level-safe connection; no instruction to connect powered output to unspecified mic/instrument input. Otherwise estimate/manual. |
| Unknown or multiple outputs | Explain the setup cannot be checked reliably; no fabricated identity or recording block. |

Probes use conservative fixed digital level and fades. Ask for comfortable device volume; do not raise system volume, override hearing protection or monitor mic to output. Quiet/clipped signal stops with advice to move closer or adjust volume manually. No automatic escalation.

Success: `Timing checked for this setup. New recordings will use it.` Failure: `Couldn't get a clear result. You can still record.` Variable: `This connection's delay varies. Timing will use an estimate.` Keep previous profile history, but never label invalid evidence Checked.

After a compatible Bluetooth reconnect/reopen: `Using your previous timing check. Recalibration is suggested for this connection.` Show a non-blocking heads-up once per connection session, not on every Record press or internal stream reopen. Keep a persistent explanation in Settings. No automatic test, modal gate or changed transport buttons.

On output disconnect while mic still works, show an accessible non-modal popup/banner: `Headphones disconnected. Still recording. Backing stopped; timing may have changed.` Adapt device wording to the actual event. No confirmation needed to keep capturing; do not cover Record/Stop or steal Write focus. Keep an interruption note available after the popup and in the saved performance. While screen-locked, reflect the event through the existing capture notification and show the explanation on return. Never claim Still recording unless input callbacks continue. Input interruption instead says `Microphone disconnected. Audio saved so far; recording is interrupted.` Recovery updates must report the actual microphone and any gap.

## Actual routing and microphone choice

An audio-layer route monitor shared by Record, Studio and calibration exposes immutable StateFlow snapshots. Keep AudioManager/native references out of ViewModels. Discover inventory with Android, resolve actual device ID from each opened Oboe stream and join to metadata. Check pinned Oboe 1.9.0 and Android API capabilities; latest online APIs are not automatically available. Unknown remains unknown. Device callbacks request inspection, not route inference.

Snapshot after both streams warm, before profile selection, after reopen and during capture via non-real-time monitoring. Opening Bluetooth input can change output. Include route generation, stream epoch/instance, input/output IDs/types and identity evidence, actual rates, channels/maps, format/conversion, backend, sharing/performance mode, buffer/burst, usage/preset, audio mode and observable Bluetooth transport/profile. Mark unavailable fields. Serialize query/close/reopen on the engine control thread; no stale stream pointers or callback JNI.

Microphone selection in Settings > Audio Sync is required: System default, Phone microphone, available external inputs. Default follows system policy. Change only while stopped; reopen and verify the actual pair. Requested selection is not proof. Show fallback explicitly and never save a profile under an unused requested mic. Runtime IDs/product names alone do not establish persistent identity; ambiguous same-model devices need recheck.

Preserve Media output plus phone mic where supported. A2DP playback does not establish headset mic capture. Explicit headset-mic selection may require communication/SCO with different quality and latency: explain `Using the headset microphone may change playback quality` before that choice. Implement supported version-specific paths, verify both streams, separate A2DP/SCO/LE/hearing-aid profiles. Never silently force communication mode to make calibration work. Unsupported pairs show actual route and default/phone alternatives. Release app-owned mode/route requests on ownership end, respect OS override. Bluetooth permission denial cannot block ordinary phone-mic recording.

## Repeated measurement and confidence

Use the same native final signal path/configuration/buffer policy as recording, not a separate Java recorder. Warm both streams silently and verify stability. No Idea/take rows for tests.

Initial design: 500 ms noise observation, two excluded warm-up probes, seven scored probes. Approximately 40 ms band-limited coded noise/chirp with fades, distinguishable deterministic sequences and irregular spacing. Search up to 1 second of lag without confusing adjacent probes. Target 10-15 seconds, hard deadline 20 seconds. Preallocated bounded sample/telemetry buffers. Callback copies samples and atomic records only: no allocation, locks, I/O, logging, JNI, exceptions or correlation.

Detector:

1. Remove DC, matched band limiting, normalized cross-correlation with either polarity. Retain known emitted reference after relevant deterministic processing.
2. Starting signal gates: correlation >=0.65, peak/next independent peak >=1.5, signal >=12 dB above noise, <0.1% clipped samples. Exclude main lobe from competing peak search. Validate on fixtures, especially Bluetooth processing; thresholds are proposals.
3. Reject missing/ambiguous peaks, echoes, xruns, dropped frames and route changes. Median/MAD outlier gate max(3*MAD, 5 ms); reject bimodal runs rather than hiding path jumps. At least five of seven valid trials.
4. Checked candidate: accepted range <=10 ms, MAD <=3 ms, mapper uncertainty <=3 ms, stable known route. Two fresh confirmation probes after correction must each have absolute residual <=10 ms. Failure means no Checked result. Bluetooth gets no looser badge.
5. Persist trial lags/reasons, spread, uncertainty/configuration. Correlation alone is not route/timing confidence; do not show an unvalidated percentage.

Acoustic travel is included: keep separation short. Combined measurement cannot separate input/output delay. Reflections, suppression, direct-monitor loops or effects can make it unusable. One clean Bluetooth test does not guarantee later session delay.

## Clock and correction contract

Do not insert measured milliseconds directly into existing trim code. Prove this contract using synthetic frame tests and independent physical residuals first.

- r = immutable captured source frame, zero at first actually retained sample, not gate request.
- q = monotonic unwrapped output-render frame, with separate piecewise map into timeline/backing across loops and seeks. Reopen creates a new epoch.
- M(r) = uncorrected input-to-output-render map from coherent counters and common CLOCK_MONOTONIC software delivery/submission reference, including startup offset/rate conversion. Do not equate independently zeroed counters or use latest wrapped transport as sample time.
- Publish callback block start/count and monotonic anchors using an RT-safe clock and preallocated atomic records; fit mapper off callback. Hardware timestamps support validation but cannot silently change reference to physical capture/presentation. Include scheduling/interpolation uncertainty. Phase 1 must document exact counter conventions and prove <=3 ms mapper uncertainty before measured mode. Unsupported backends remain Estimated.
- Probe emitted at q_j and found at r_j: D_j = M(r_j) - q_j. Save median D_j, its rate and mapper/probe versions. A changed mapper requires new evidence.
- Normal capture: alignedQ(r) = M(r) - D + U, signed adjustment U in output frames, negative earlier. Startup/gate position is already in M. Never add pre-roll or input/output estimates again. A mapper already subtracting hardware latency would need a separately measured residual; do not mix conventions.
- Fallback D must use the same convention, from validated timestamps or heuristics. Until that conversion is proven, retain the existing compensation as an explicit legacy path. No measured profile across incompatible clocks.
- Equal-rate example: M(r)=1000+r, D=200, U=0 places source frame 500 at 1300. Manual take at source 10000 begins at 10800, without another subtraction. U=-50 shifts both earlier by 50.
- Backing phase = floorMod(alignedQ(r)-backingOriginQ, loopFrames), with saved backing epoch. Studio derives placement from the same mapping. No beat quantization or timing inference.
- Combined D applies with audible app backing/metronome on that route. Silent free capture keeps its beginning, without arbitrary round-trip shift. Input-only timing is not identifiable from D. Metronome is an audible source even without tracks; validate synth queue separately before extending WAV calibration claims to clicks/MIDI/drums.
- Rate mismatch/drift is distinct from offset. Explicit conversion must match calibration. No automatic time stretching; detected out-of-gate drift downgrades confidence, preserving audio.

The mapper proof is an implementation prerequisite, not a claim that current callback transport snapshots satisfy it. Failure blocks Checked integration, never recording.

## Persistence and reversible correction

Additive migration from actual v17, provisionally v18 if still next. No destructive fallback. Phase 1 defines schema and fixtures before dependent integration:

- Immutable profile revisions: route/configuration and identity confidence, engine/mapper/probe versions, delay frames/rate, trial statistics, uncertainty, date and validity reason.
- Durable capture source ownership: continuous raw file, format/frame count, derived take references. Save failure retains recoverable ownership. Reuse existing mechanism only if compatible; imported_sources alone does not describe mic capture.
- Immutable performance timing snapshot: source/ranges, route generations, epochs/map anchors, applied D/U, profile revision, method measured/reused-measurement/estimated/manual/legacy/none, confidence and discontinuity flags. Manual takes share this snapshot. Playback must not depend on mutable profile rows. Record route/backing interruption events at native source boundaries; a single take can contain backed and unbacked regions. Input recovery can attach separate source segments with explicit gap metadata to the same user-visible take.
- Separate synchronization offset from editing trim. Support signed placement/pre-zero source access. A short take cannot disappear when delay exceeds duration. Preserve every captured tail; offsets cannot manufacture audio beyond Stop.
- New Studio loop takes reference continuous source at exact native frame boundaries. Never delete original. Apply mapping to all ranges, preserving delayed input around boundaries. Derived WAVs can remain compatibility caches with source links.
- Migration leaves old offsets/trims/phases/takes/hashes identical. Mark legacy where needed; never infer which old trim portion was compensation. New calibration affects future performances only.
- Existing-take adjustment requires explicit selection, preview, Apply and Reset. Save reversible metadata, never rewrite WAVs or assume today's route describes yesterday's take. Profile save never bulk-retimes history.
- Record pairing, Studio, Overview and Library read consistent saved mapping. Solo remains solo. Future Studio bridge consumes this contract but remains separate scope.

Keep global manual preference for legacy/fallback with `Previous manual adjustment` label and per-take snapshot. First accepted measured profile starts with zero additional adjustment and states `The check replaces your previous timing adjustment for this setup.` Preserve the old setting recoverably; never invisibly stack it onto D. Future manual changes are route-scoped and future-only unless a historical take is explicitly selected.

## Reuse and route changes

| Event | Policy |
| --- | --- |
| Known built-in/wired pair reopens | Revalidate actual pair/configuration/mapper. Checked reuse requires passing reopen device gates; otherwise Check again and estimate. |
| Bluetooth reopens/reconnects with the same identifiable input/output pair and compatible configuration/mapper | Continue using the previous accepted measurement, labeled Previously checked with suggested recalibration. Snapshot reused-measurement provenance. Do not present it as freshly Checked or play probes automatically. |
| Bluetooth mic/profile/mode changes or identity is ambiguous | Old correction is incompatible, not merely stale. Use a profile belonging to the actual compatible pair if available, otherwise estimate and explain why. Never transfer phone-mic measurement to headset mic or A2DP to SCO. |
| USB reconnect/ambiguous identity | Recheck; reuse needs proven identity/configuration/reopen stability. IDs/names alone insufficient. |
| Rate/channel/buffer/backend/preset/processing/mode/mapper change | Invalidate and estimate. OS update invalidates. Unobservable firmware/codec changes cannot be promised detectable; Check always available. |
| Volume change | Store diagnostically; not necessarily latency change. Recheck observable processing changes, no claim about invisible DSP. |
| Unknown route/timestamp failure | Recording remains usable with honest estimate; never another route's Checked profile. |
| Route change during check | Stop probes, reject run, show actual new pair before Check again. |
| Output changes during backing capture while input continues | Keep microphone and current take recording. Pause backing before automatic fallback can play through the phone speaker; no automatic resume on reconnect. Show the non-blocking notice. Save exact last known backing position and interruption/uncertainty boundary, preserving correction for the backed prefix. Do not extrapolate heard backing through the unbacked remainder, jump correction, create a new user take or require Record again. |
| Input loss/error caused by disconnect | Keep the capture session in an explicit interrupted/recovering state, drain/finalize the affected source and attempt bounded recovery through the normal authorized routing policy. Report actual resumed mic and gap; never claim uninterrupted samples. An explicitly chosen missing external mic must not silently become the phone mic; offer a captioned Use phone mic action. Recovery source segments remain in the same user-visible take with gap metadata. If recovery fails, keep saved audio and show that input is unavailable with retry/phone-mic options. Stop remains reachable. |
| Ring overrun while input remains active | Preserve captured audio, record dropped-frame/gap evidence, continue where possible and downgrade timing confidence. Repeated unrecoverable errors use interrupted/recovering state, not a false recording indicator. |
| Silent capture/output-only event | Continue unchanged input. Unrelated connected-device inventory changes do not interrupt capture. |

No network/account needed. Permission denial uses existing mic UX. Missing timestamps, old Android routing limits, noise, inaccessible mics and variable Bluetooth preserve record-now fallback. Never promise sample accuracy or identical results on all routes.

Reusing a compatible measurement is a convenience policy, not proof of current accuracy. Explicit failed/unstable recalibration, known configuration incompatibility or failed mapper checks disables reuse and explains the estimate fallback. A cancelled test alone does not discard a compatible prior correction.

For a take continued after backing loss, retain the raw sample clock for the whole performance. Only the backed interval claims measured alignment. Playback context records that backing ended at the event; replaying that context does not silently supply backing the user never heard. Solo playback remains complete. Studio may retain continuous sample spacing on the timeline while marking the post-event interval uncertain. Do not enable automatic backing resume mid-capture in this slice; Stop then Play remains available. This is distinct from stopping the microphone.

## Incremental implementation and user gates

Implement on `feat/latency-calibration` from protected `00fce22` lineage in the same worktree, accounting for subsequent import verification commits. This requested baseline overrides generic start-from-main guidance. Preserve import and paused checkpoint refs. Push checkpoints for backup as Chris requested. No merge before device gates.

1. **Route and clock evidence.** Serialized route monitoring, exact frame epochs/anchors, bounded RT telemetry, legacy characterization tests. Resolve mapper convention and uncertainty while preserving old compensation. User gate: physical mic/output isolation, correct names, uncalibrated capture and screen lock. No accurate-timing claim yet.
2. **Measurement service.** Bounded probes, detector rejection/confidence, profile persistence and cancellation. Synthetic tests: known lags, echo, polarity, conversion, noise, clipping, missing probes, multiple clusters, xruns, absent timestamps. User gate: acoustic runs and independent residuals across available pairs; reopen gate before measured integration.
3. **Persistence and Record.** Additive migration, raw ownership, timing snapshots/events, mapped backing phase/manual frame boundaries, historical compatibility, continued input after output loss and explicit input recovery. User gate: imported full song, mid-loop Record, repeated Record, disconnect without ending take, accurate notices/gaps, solo/pair, Write, Stop/re-entry; old data/hash comparison. Import verification remains separate before phone installation.
4. **Studio and shared UX.** Reversible placement, continuous loop-source retention, count-in/punch/cursor/negative starts, synth/metronome evidence, shared captioned panel, optional existing-take adjustment. User gate: Record/Studio/Library/Overview consistency, loops, short takes, adjustment/reset. Do not implement unrelated Studio bridge.
5. **Release evidence.** Complete matrix, record unavailable hardware, update spec/TASKS. Section commits on feature branch; README before requested commit work, no Codex files. Merge/push only after required verification under project workflow.

## Real-device acceptance tests

Before install: fresh verified Capture Test backup, retained APK, isolated migration tests, pending import v16-to-v17 checks. Original Nightjar stays untouched. Build/emulator/synthetic passes are not audio acceptance.

Minimum matrix: baseline SM-F936U plus another OEM; oldest supported API path on physical hardware where available; built-in speaker/phone mic; wired headphones/phone mic; TRRS headset mic; USB output/phone mic; USB input/output with actual mic; classic Bluetooth output/phone mic; supported SCO and LE headset mic paths. Missing hardware is **not tested**. Unsupported combinations must pass fallback/recordability tests and be listed as unsupported for measured calibration.

For each measurable pair:

- Physically isolate mic/output and verify IDs, including after input opens. Include connected-but-unused Bluetooth, simultaneous wired/USB/BT, OS overrides and two similar devices.
- Three calibration runs, ten recordings across at least five reopen/reconnect cycles. Held-out acoustic markers: >=95% absolute residual <=10 ms, none >20 ms, no clipping/dropouts. At least 20 markers per route across beginning/middle/end. Use independent validation signals, not reused fit probes. Save signed residuals/spread/uncertainty. Failures block Checked eligibility, not capture; Bluetooth gets no relaxed badge.
- Ten-minute backing: first-to-last residual change <=10 ms for Checked. Detect/report drift/jumps. Analyze immutable raw against known backing markers, with external reference rig/OboeTester comparison where available. Human tapping is not ground truth.
- Mid-loop/full-song start, manual takes across wraps: no missing/duplicated source frames, correction once per performance, correct saved pairing after restart, no automatic latching or sound.
- Studio zero/nonzero cursor, count-in off/on, metronome alone, WAV, MIDI/drums, loops/punch and takes shorter than delay. Verify earlier/later signs, pre-zero access, tails, trim independence, Reset. Synth-specific offset failure blocks that signal path's claim.
- Output disconnect with working phone mic: continuous source frames and same user-visible take, backing paused, non-blocking notice, no surprise speaker output, no automatic restart on reconnect. Continue singing/Write/mark manual take/Stop; save/reopen with correct backed prefix and complete solo audio.
- Headset/USB input disconnect: explicit interrupted/recovering state, preserved prefix, bounded recovery, accurate mic identity and gap metadata. No false Still recording notice and no silent replacement of explicitly selected external mic. Test Use phone mic, retry and Stop while recovering.
- Compatible Bluetooth reconnect retains prior correction as Previously checked, snapshots reused provenance and shows a deduplicated suggestion. Incompatible mic/profile/mapper changes do not reuse it. Cancelled recheck preserves eligible history; a failed unstable check does not falsely restore Checked. Compare held-out residuals without presenting reused measurements as current-session verification.
- Notification Stop; screen lock/timeout; app switching; Samsung power saving on/off. Capture continues where input is available, route notices remain recoverable on return, actual Stop finalizes safely, no surprise probes.
- Quiet/noisy/reverberant rooms, muted/low/high volume, blocked mic, suppression, codec/profile changes, calls/focus loss, permission denial, Cancel/Back, low storage/save failure. False Checked is failure. Recording without calibration remains usable.
- Migration v16->17->new and v17->new: old phases/trims/offsets identical, row counts/file hashes retained, imported originals intact, new continuous sources reachable after loop saves. Profile deletion/recheck cannot change history. Crash during source/index saving retains recoverable ownership; coordinate broader recovery work.

## Out of scope: pointers to docs/TASKS.md

- **Calibration drift correction and resampling**: automatic stretching/clock drift repair beyond detecting/reporting it.
- **Calibration profile portability and fleet tuning**: cloud/cross-device profiles, codec tuning database, automatic firmware identity inference.
- **Calibration advanced interface channel routing**: arbitrary USB multichannel matrix, per-channel calibration, dedicated electrical-loopback wizard. Selected mono input and stereo-output path stay in scope.
- **Capture interruption recovery**: broader app-wide process-death recovery and raw cleanup. Durable ownership/save-failure preservation for new calibrated recordings stay in scope here.
- **Explore Build In Studio bridge**: full workspace reconstruction/related-pair expansion; this feature supplies compatible timing metadata.

Historical playback, new Studio loop-source retention, actual mic identity, no-double-compensation and honest fallback are required, not deferred.

## Approval and Sol handoff

User-reviewed direction: Settings > Audio Sync, required microphone choice, reuse a compatible prior Bluetooth measurement with a recalibration suggestion, and continue recording on output disconnect with a non-blocking explanation. Implementation detail: pause backing to prevent surprise speaker playback, preserve route/backing events, and honestly distinguish functioning input from interrupted/recovering input. Independent timing metadata and durable raw sources are engineering requirements. These clarified choices do not authorize implementation yet.

After approval use **GPT-6 Sol, medium reasoning**. It is available in this app and appropriate for incremental implementation against this contract. Keep this session for audit context; a fresh session is optional. No automatic model switch or new chat. Update approval status and changed baseline before activating the following prompt:

```text
Implement the approved Nightjar latency-calibration spec incrementally with GPT-6 Sol, medium reasoning. Read C:/Users/chris/AndroidStudioProjects/Nightjar/specs/latency-calibration.md, root docs/TASKS.md, docs/PRODUCT_VISION.md, docs/DESIGN.md, specs/explore.md and specs/import-audio-idea.md. Confirm the spec has been approved before implementation.

Use C:/Users/chris/AndroidStudioProjects/Nightjar/docs/worktrees/screen-off-capture. Protected baseline: feat/import-record-workspace at 00fce22048fa2172fae2d57fa2dbff3fb3ecfc2a. Inspect status and account for later import verification commits; create feat/latency-calibration from this lineage, never reset to main. Root feat/explore-sections has unrelated dirty importer/migration/test files and attachments: leave them untouched. Preserve feat/record-workspace at ae0e8cc and all app data.

Start Phase 1 route/clock evidence while retaining legacy compensation. Verify pinned Oboe 1.9.0, both actual routes after mic opens, exact mapper conventions and callback safety. Follow approved phases with focused Kotlin/native tests, additive migration fixtures, section commits and real-device gates. Estimates are never acoustically Checked. Record stays available without calibration. Preserve raw sources, import, Record/Write, groups/latches/manual takes, screen-lock capture and historical phases/trims. Apply measured correction once; never silently retime old takes.

Honor clarified UX: Settings > Audio Sync with microphone choice; reuse compatible previous Bluetooth measurements as Previously checked with a non-blocking recalibration suggestion. Output disconnect must not end the take: pause backing, keep available input recording and show an accurate notice. Preserve event boundaries and explicit gaps if microphone recovery is required; do not silently substitute an explicitly selected external mic.

Prior checks: two APK builds and 38 focused JVM tests, not a full-suite pass. Import phone backup, migration/install/instrumented/listening checks remain pending. Back up Capture Test before in-place installs, keep original Nightjar untouched and report unavailable hardware honestly. At each phase explain completed checks, what Chris must test and the next phase. Keep spec and targeted TASKS edits current; never overwrite TASKS.md or commit Codex files. Defer Explore Studio bridge and drift repair per TASKS. Push feature checkpoints for backup; no merge before required device verification.
```

## External evidence consulted September 27

- [Oboe stream reference](https://google.github.io/oboe/classoboe_1_1_audio_stream.html): timestamps are best-effort and cannot include unknown implementation delays. Check pinned 1.9.0; latest reference is not its API contract.
- [OboeTester usage](https://github.com/google/oboe/blob/main/apps/OboeTester/docs/Usage.md): loopback testing provides a device comparison. Nightjar thresholds above are proposed gates, not Oboe guarantees.
- [Android Bluetooth recording](https://developer.android.com/develop/connectivity/bluetooth/ble-audio/audio-recording): input preference is distinct and can be overridden. Its Java AudioRecord example does not establish Nightjar's Oboe route.
- [Android AudioManager](https://developer.android.com/reference/android/media/AudioManager): version-specific communication routing is distinct from media policy.
- [OpenAI code generation guidance](https://developers.openai.com/api/docs/guides/code-generation): supports Sol for Codex coding. Medium effort and session continuity are project recommendations, not account-allowance claims.
