#include "midi_sequencer.h"
#include "common.h"
#include <algorithm>

namespace nightjar {

MidiSequencer::MidiSequencer() = default;

void MidiSequencer::updateTracks(const std::vector<MidiTrackData>& tracks) {
    std::lock_guard<std::mutex> lock(editMutex_);

    // Detect tracks that just became muted -- need all-notes-off on their channels
    Snapshot* active = active_.load(std::memory_order_acquire);
    uint16_t silenceMask = 0;
    for (size_t i = 0; i < tracks.size() && i < active->tracks.size(); ++i) {
        if (tracks[i].muted && !active->tracks[i].muted) {
            silenceMask |= static_cast<uint16_t>(1 << tracks[i].channel);
        }
    }
    if (silenceMask) {
        silenceChannelMask_.fetch_or(silenceMask, std::memory_order_release);
    }

    // Write to the inactive snapshot
    Snapshot* inactive = (active == &snapshotA_) ? &snapshotB_ : &snapshotA_;

    inactive->tracks = tracks;
    inactive->nextEventIndex.resize(tracks.size(), 0);
    // Reset cursors
    for (size_t i = 0; i < tracks.size(); ++i) {
        inactive->nextEventIndex[i] = 0;
    }

    // Atomic swap
    active_.store(inactive, std::memory_order_release);
    LOGD("MidiSequencer: updated %zu tracks", tracks.size());
}

const std::vector<NoteEvent>& MidiSequencer::tick(int64_t renderPos, int32_t chunkFrames) {
    pendingEvents_.clear();

    // Emit all-notes-off for channels that were just muted (note = -1 sentinel)
    uint16_t silenceMask = silenceChannelMask_.exchange(0, std::memory_order_acq_rel);
    if (silenceMask) {
        for (int ch = 0; ch < 16; ++ch) {
            if (silenceMask & (1 << ch)) {
                NoteEvent ne;
                ne.channel = ch;
                ne.note = -1;       // sentinel: all notes off on this channel
                ne.velocity = 0;
                pendingEvents_.push_back(ne);
            }
        }
    }

    Snapshot* snap = active_.load(std::memory_order_acquire);
    int64_t chunkEnd = renderPos + chunkFrames;

    for (size_t t = 0; t < snap->tracks.size(); ++t) {
        const MidiTrackData& track = snap->tracks[t];
        if (track.muted || track.events.empty()) continue;

        size_t& idx = snap->nextEventIndex[t];
        const auto& events = track.events;

        // Fire all events whose framePos falls within [renderPos, chunkEnd)
        while (idx < events.size() && events[idx].framePos < chunkEnd) {
            const MidiEvent& e = events[idx];
            if (e.framePos >= renderPos) {
                NoteEvent ne;
                ne.channel = e.channel;
                ne.note = e.note;
                // Scale noteOn velocity by track volume
                if (e.velocity > 0) {
                    ne.velocity = std::max(1, static_cast<int>(e.velocity * track.volume));
                } else {
                    ne.velocity = 0;
                }
                pendingEvents_.push_back(ne);
            }
            ++idx;
        }
    }

    return pendingEvents_;
}

void MidiSequencer::reset() {
    Snapshot* snap = active_.load(std::memory_order_acquire);
    for (size_t i = 0; i < snap->nextEventIndex.size(); ++i) {
        snap->nextEventIndex[i] = 0;
    }
}

void MidiSequencer::resetToPosition(int64_t posFrames) {
    Snapshot* snap = active_.load(std::memory_order_acquire);

    for (size_t t = 0; t < snap->tracks.size(); ++t) {
        const auto& events = snap->tracks[t].events;
        if (events.empty()) {
            snap->nextEventIndex[t] = 0;
            continue;
        }

        // Binary search: find first event at or after posFrames
        size_t lo = 0, hi = events.size();
        while (lo < hi) {
            size_t mid = lo + (hi - lo) / 2;
            if (events[mid].framePos < posFrames) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        snap->nextEventIndex[t] = lo;
    }
}

int64_t MidiSequencer::getMaxEndFrame() const {
    const Snapshot* snap = active_.load(std::memory_order_acquire);
    int64_t maxFrame = 0;

    for (const auto& track : snap->tracks) {
        if (!track.events.empty()) {
            // Last event's frame position (noteOff marks the true end)
            int64_t endFrame = track.events.back().framePos;
            maxFrame = std::max(maxFrame, endFrame);
        }
    }

    return maxFrame;
}

}  // namespace nightjar
