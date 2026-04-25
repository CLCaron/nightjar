#include "audio_engine.h"
#include "oboe_recording_stream.h"
#include "oboe_playback_stream.h"
#include "track_mixer.h"
#include "synth_engine.h"
#include "step_sequencer.h"
#include "midi_sequencer.h"
#include "atomic_transport.h"
#include "common.h"

namespace nightjar {

AudioEngine::AudioEngine() = default;

AudioEngine::~AudioEngine() {
    shutdown();
}

bool AudioEngine::initialize() {
    if (initialized_.load(std::memory_order_acquire)) {
        LOGW("AudioEngine already initialized");
        return true;
    }

    LOGD("AudioEngine initializing (sampleRate=%d, outputChannels=%d)",
         kSampleRate, kOutputChannelCount);

    recordingStream_ = std::make_unique<OboeRecordingStream>();
    transport_ = std::make_unique<AtomicTransport>();
    mixer_ = std::make_unique<TrackMixer>();
    synthEngine_ = std::make_unique<SynthEngine>(*transport_);
    playbackStream_ = std::make_unique<OboePlaybackStream>(*mixer_, *transport_, synthEngine_.get());

    // Start the output stream — it sits idle (outputting silence) until play()
    if (!playbackStream_->start()) {
        LOGE("AudioEngine: failed to start playback stream");
        // Non-fatal — playback won't work but recording still can
    }

    initialized_.store(true, std::memory_order_release);
    LOGD("AudioEngine initialized successfully");
    return true;
}

void AudioEngine::shutdown() {
    if (!initialized_.load(std::memory_order_acquire)) return;

    LOGD("AudioEngine shutting down");

    if (recordingStream_ && recordingStream_->isActive()) {
        recordingStream_->stop();
    }

    if (playbackStream_) {
        playbackStream_->stop();
    }

    if (synthEngine_) {
        synthEngine_->stop();
    }

    mixer_.reset();
    synthEngine_.reset();
    transport_.reset();
    playbackStream_.reset();
    recordingStream_.reset();

    initialized_.store(false, std::memory_order_release);
    LOGD("AudioEngine shut down");
}

// ── Recording API ──────────────────────────────────────────────────────

bool AudioEngine::startRecording(const char* filePath) {
    if (!initialized_.load(std::memory_order_acquire)) {
        LOGE("AudioEngine: startRecording called but not initialized");
        return false;
    }
    if (!recordingStream_) {
        recordingStream_ = std::make_unique<OboeRecordingStream>();
    }
    return recordingStream_->start(std::string(filePath));
}

bool AudioEngine::awaitFirstBuffer(int timeoutMs) {
    if (!recordingStream_) return false;
    return recordingStream_->awaitFirstBuffer(timeoutMs);
}

void AudioEngine::openWriteGate() {
    if (recordingStream_) {
        recordingStream_->openWriteGate();
    }
}

int64_t AudioEngine::stopRecording() {
    if (!recordingStream_) return -1;
    return recordingStream_->stop();
}

bool AudioEngine::isRecordingActive() const {
    if (!recordingStream_) return false;
    return recordingStream_->isActive();
}

float AudioEngine::getLatestPeakAmplitude() const {
    if (!recordingStream_) return 0.0f;
    return recordingStream_->getLatestPeakAmplitude();
}

int64_t AudioEngine::getRecordedDurationMs() const {
    if (!recordingStream_) return 0;
    return recordingStream_->getRecordedDurationMs();
}

// ── Playback API ───────────────────────────────────────────────────────

bool AudioEngine::addTrack(int trackId, const char* filePath,
                           int64_t durationMs, int64_t offsetMs,
                           int64_t trimStartMs, int64_t trimEndMs,
                           float volume, bool muted) {
    if (!mixer_) return false;
    bool ok = mixer_->addTrack(trackId, std::string(filePath),
                               durationMs, offsetMs, trimStartMs, trimEndMs,
                               volume, muted);
    if (ok) {
        recomputeTotalFrames();
    }
    return ok;
}

void AudioEngine::removeTrack(int trackId) {
    if (!mixer_) return;
    mixer_->removeTrack(trackId);
    recomputeTotalFrames();
}

void AudioEngine::removeAllTracks() {
    if (!mixer_) return;
    mixer_->removeAllTracks();
    recomputeTotalFrames();
    if (transport_) {
        transport_->posFrames.store(0, std::memory_order_relaxed);
        transport_->playing.store(false, std::memory_order_relaxed);
    }
}

void AudioEngine::play() {
    if (!transport_) return;

    int64_t countIn = countInFrames_.exchange(0, std::memory_order_relaxed);

    int64_t pos = transport_->posFrames.load(std::memory_order_relaxed);
    int64_t total = transport_->totalFrames.load(std::memory_order_relaxed);

    // If at the end, restart from loop start or 0
    if (pos >= total && total > 0) {
        int64_t loopStart = transport_->loopStartFrames.load(std::memory_order_relaxed);
        pos = (loopStart >= 0) ? loopStart : 0;
    }

    int64_t startPos = pos - countIn;  // negative if count-in > 0

    transport_->posFrames.store(startPos, std::memory_order_relaxed);
    transport_->pendingStartPos.store(startPos, std::memory_order_release);
    // Defensive: always request a flush before transitioning to playing.
    // The flush branch in the synth render thread re-aligns the cursor
    // against the start position, all-sounds-off any leftover voices,
    // and re-issues per-channel program changes -- the latter closes a
    // race where the first scheduled noteOn after start could land
    // before FluidSynth has applied the program from updateMidiTracks,
    // making the first pass sound as Acoustic Grand regardless of the
    // selected instrument. Also closes the suspected pause->seek->play
    // silence race where the wasPlaying transition was missed.
    if (synthEngine_) synthEngine_->requestFlush();
    transport_->playing.store(true, std::memory_order_release);
    LOGD("AudioEngine: play (pos=%lldms, countIn=%lldms)",
         (long long)framesToMs(startPos), (long long)framesToMs(countIn));
}

void AudioEngine::pause() {
    if (!transport_) return;
    transport_->playing.store(false, std::memory_order_release);
    LOGD("AudioEngine: pause (pos=%lldms)",
         (long long)framesToMs(transport_->posFrames.load(std::memory_order_relaxed)));
}

void AudioEngine::seekTo(int64_t positionMs) {
    if (!transport_) return;
    int64_t frames = msToFrames(positionMs);
    int64_t total = transport_->totalFrames.load(std::memory_order_relaxed);
    frames = std::max((int64_t)0, std::min(frames, total));
    transport_->posFrames.store(frames, std::memory_order_relaxed);
}

bool AudioEngine::isPlaying() const {
    if (!transport_) return false;
    return transport_->playing.load(std::memory_order_acquire);
}

int64_t AudioEngine::getPositionMs() const {
    if (!transport_) return 0;
    return framesToMs(transport_->posFrames.load(std::memory_order_relaxed));
}

int64_t AudioEngine::getTotalDurationMs() const {
    if (!transport_) return 0;
    return framesToMs(transport_->totalFrames.load(std::memory_order_relaxed));
}

// ── Per-track controls ─────────────────────────────────────────────────

void AudioEngine::setTrackVolume(int trackId, float volume) {
    if (mixer_) mixer_->setTrackVolume(trackId, volume);
}

void AudioEngine::setTrackMuted(int trackId, bool muted) {
    if (mixer_) mixer_->setTrackMuted(trackId, muted);
}

// ── Loop ───────────────────────────────────────────────────────────────

void AudioEngine::setLoopRegion(int64_t startMs, int64_t endMs) {
    if (!transport_) return;
    transport_->loopStartFrames.store(msToFrames(startMs), std::memory_order_relaxed);
    transport_->loopEndFrames.store(msToFrames(endMs), std::memory_order_relaxed);
    LOGD("AudioEngine: setLoopRegion %lld-%lldms", (long long)startMs, (long long)endMs);
}

void AudioEngine::clearLoopRegion() {
    if (!transport_) return;
    transport_->loopStartFrames.store(-1, std::memory_order_relaxed);
    transport_->loopEndFrames.store(-1, std::memory_order_relaxed);
    LOGD("AudioEngine: clearLoopRegion");
}

// ── Overdub support ────────────────────────────────────────────────────

void AudioEngine::setRecording(bool active) {
    if (transport_) {
        transport_->recording.store(active, std::memory_order_relaxed);
    }
}

// ── Loop reset tracking ─────────────────────────────────────────────

int64_t AudioEngine::getLoopResetCount() const {
    if (!transport_) return 0;
    return transport_->loopResetCount.load(std::memory_order_acquire);
}

// ── Synth API ──────────────────────────────────────────────────────────

bool AudioEngine::loadSoundFont(const char* path) {
    if (!synthEngine_) return false;
    bool ok = synthEngine_->loadSoundFont(std::string(path));
    if (ok) {
        synthEngine_->start();
        LOGD("AudioEngine: SoundFont loaded, synth render thread started");
    }
    return ok;
}

void AudioEngine::synthNoteOn(int channel, int note, int velocity) {
    if (synthEngine_) synthEngine_->noteOn(channel, note, velocity);
}

void AudioEngine::synthNoteOff(int channel, int note) {
    if (synthEngine_) synthEngine_->noteOff(channel, note);
}

void AudioEngine::synthProgramChange(int channel, int program) {
    if (synthEngine_) synthEngine_->programChange(channel, program);
}

void AudioEngine::synthRequestPreviewFlush() {
    if (synthEngine_) synthEngine_->requestPreviewFlush();
}

void AudioEngine::setSynthVolume(float volume) {
    if (synthEngine_) synthEngine_->setVolume(volume);
}

void AudioEngine::synthAllSoundsOff() {
    if (synthEngine_) synthEngine_->allSoundsOff();
}

// ── Drum sequencer API ──────────────────────────────────────────────

void AudioEngine::updateDrumPattern(int stepsPerBar, int bars, int64_t offsetMs,
                                     float volume, bool muted,
                                     const int* stepIndices, const int* drumNotes,
                                     const float* velocities, int hitCount,
                                     const int64_t* clipOffsetsMs, int clipCount,
                                     int beatsPerBar) {
    if (!synthEngine_) return;

    std::vector<DrumHit> hits;
    hits.reserve(hitCount);
    for (int i = 0; i < hitCount; ++i) {
        hits.push_back({
            stepIndices[i],
            drumNotes[i],
            static_cast<int>(velocities[i] * 127.0f)
        });
    }

    // Convert clip offsets from ms to frames
    std::vector<int64_t> clipOffsetFrames;
    if (clipOffsetsMs != nullptr && clipCount > 0) {
        clipOffsetFrames.reserve(clipCount);
        for (int i = 0; i < clipCount; ++i) {
            clipOffsetFrames.push_back(msToFrames(clipOffsetsMs[i]));
        }
    }

    synthEngine_->updateDrumPattern(
        stepsPerBar, bars, msToFrames(offsetMs), volume, muted, hits, clipOffsetFrames,
        beatsPerBar);

    // Recompute total frames to include drum pattern end
    if (transport_) {
        double bpm = transport_->bpm.load(std::memory_order_relaxed);
        int64_t drumEnd = synthEngine_->getSequencerMaxEndFrame(bpm);
        drumEndFrames_.store(drumEnd, std::memory_order_relaxed);
        recomputeTotalFrames();
    }
}

void AudioEngine::updateDrumPatternClips(float volume, bool muted,
                                          const int* clipStepsPerBar, const int* clipTotalSteps,
                                          const int* clipBeatsPerBar, const int64_t* clipOffsetsMs,
                                          const int* clipHitCounts, int clipCount,
                                          const int* hitStepIndices, const int* hitDrumNotes,
                                          const float* hitVelocities) {
    if (!synthEngine_) return;

    std::vector<StepSequencer::ClipSlot> clips;
    clips.reserve(clipCount);
    int hitOffset = 0;

    for (int c = 0; c < clipCount; ++c) {
        StepSequencer::ClipSlot slot;
        slot.stepsPerBar = clipStepsPerBar[c];
        slot.totalSteps = clipTotalSteps[c];
        slot.beatsPerBar = clipBeatsPerBar[c] > 0 ? clipBeatsPerBar[c] : 4;
        slot.offsetFrames = msToFrames(clipOffsetsMs[c]);

        int hc = clipHitCounts[c];
        slot.hits.reserve(hc);
        for (int h = 0; h < hc; ++h) {
            int idx = hitOffset + h;
            slot.hits.push_back({
                hitStepIndices[idx],
                hitDrumNotes[idx],
                static_cast<int>(hitVelocities[idx] * 127.0f)
            });
        }
        hitOffset += hc;

        clips.push_back(std::move(slot));
    }

    synthEngine_->updateDrumPatternClips(volume, muted, clips);

    if (transport_) {
        double bpm = transport_->bpm.load(std::memory_order_relaxed);
        int64_t drumEnd = synthEngine_->getSequencerMaxEndFrame(bpm);
        drumEndFrames_.store(drumEnd, std::memory_order_relaxed);
        recomputeTotalFrames();
    }
}

void AudioEngine::setBpm(double bpm) {
    if (transport_) {
        transport_->bpm.store(bpm, std::memory_order_relaxed);
        LOGD("AudioEngine: setBpm %.1f", bpm);

        // BPM change affects drum pattern duration -- recompute
        if (synthEngine_) {
            int64_t drumEnd = synthEngine_->getSequencerMaxEndFrame(bpm);
            drumEndFrames_.store(drumEnd, std::memory_order_relaxed);
            recomputeTotalFrames();
        }
    }
}

void AudioEngine::setDrumSequencerEnabled(bool enabled) {
    if (synthEngine_) synthEngine_->setSequencerEnabled(enabled);
}

// ── MIDI sequencer API ─────────────────────────────────────────────

void AudioEngine::updateMidiTracks(const int* channels, const int* programs,
                                    const float* volumes, const bool* muted, int trackCount,
                                    const int* trackEventCounts,
                                    const int64_t* eventFrames, const int* eventChannels,
                                    const int* eventNotes, const int* eventVelocities,
                                    int totalEventCount) {
    if (!synthEngine_) return;

    // Reconstruct MidiTrackData from flat arrays
    std::vector<MidiTrackData> tracks;
    tracks.reserve(trackCount);

    int eventOffset = 0;
    for (int t = 0; t < trackCount; ++t) {
        MidiTrackData td;
        td.channel = channels[t];
        td.program = programs[t];
        td.volume = volumes[t];
        td.muted = muted[t];

        int eventCount = trackEventCounts[t];
        td.events.reserve(eventCount);
        for (int e = 0; e < eventCount; ++e) {
            int idx = eventOffset + e;
            MidiEvent me;
            me.framePos = eventFrames[idx];
            me.channel = eventChannels[idx];
            me.note = eventNotes[idx];
            me.velocity = eventVelocities[idx];
            td.events.push_back(me);
        }
        eventOffset += eventCount;

        tracks.push_back(std::move(td));
    }

    synthEngine_->updateMidiTracks(tracks);

    // Update MIDI end frames for timeline length
    int64_t midiEnd = synthEngine_->getMidiMaxEndFrame();
    midiEndFrames_.store(midiEnd, std::memory_order_relaxed);
    recomputeTotalFrames();
}

void AudioEngine::setMidiSequencerEnabled(bool enabled) {
    if (synthEngine_) synthEngine_->setMidiSequencerEnabled(enabled);
}

// ── Count-in API ────────────────────────────────────────────────────

void AudioEngine::setCountIn(int bars, int beatsPerBar) {
    if (!transport_ || bars <= 0 || beatsPerBar <= 0) {
        countInFrames_.store(0, std::memory_order_relaxed);
        return;
    }
    double bpm = transport_->bpm.load(std::memory_order_relaxed);
    if (bpm <= 0.0) {
        countInFrames_.store(0, std::memory_order_relaxed);
        return;
    }
    double framesPerBeat = (60.0 / bpm) * static_cast<double>(kSampleRate);
    auto frames = static_cast<int64_t>(bars * beatsPerBar * framesPerBeat);
    countInFrames_.store(frames, std::memory_order_relaxed);
    LOGD("AudioEngine: setCountIn bars=%d bpb=%d -> %lld frames", bars, beatsPerBar,
         (long long)frames);
}

// ── Metronome API ───────────────────────────────────────────────────

void AudioEngine::setMetronomeEnabled(bool enabled) {
    if (synthEngine_) synthEngine_->setMetronomeEnabled(enabled);
}

void AudioEngine::setMetronomeVolume(float volume) {
    if (synthEngine_) synthEngine_->setMetronomeVolume(volume);
}

void AudioEngine::setMetronomeBeatsPerBar(int beatsPerBar) {
    if (synthEngine_) synthEngine_->setMetronomeBeatsPerBar(beatsPerBar);
}

int64_t AudioEngine::getLastMetronomeBeatFrame() const {
    if (!synthEngine_) return -1;
    return synthEngine_->getLastMetronomeBeatFrame();
}

// ── Hardware latency measurement ────────────────────────────────────

int64_t AudioEngine::getOutputLatencyMs() const {
    if (!playbackStream_) return -1;
    return playbackStream_->getOutputLatencyMs();
}

int64_t AudioEngine::getInputLatencyMs() const {
    if (!recordingStream_) return -1;
    return recordingStream_->getInputLatencyMs();
}

// ── Internal helpers ────────────────────────────────────────────────

void AudioEngine::recomputeTotalFrames() {
    if (!transport_) return;
    int64_t mixerFrames = mixer_ ? mixer_->computeTotalFrames() : 0;
    int64_t drumFrames = drumEndFrames_.load(std::memory_order_relaxed);
    int64_t midiFrames = midiEndFrames_.load(std::memory_order_relaxed);
    int64_t total = std::max({mixerFrames, drumFrames, midiFrames});
    transport_->totalFrames.store(total, std::memory_order_relaxed);
}

}  // namespace nightjar
