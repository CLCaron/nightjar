package com.example.nightjar.data.repository

import com.example.nightjar.data.db.dao.MidiClipDao
import com.example.nightjar.data.db.dao.MidiNoteDao
import com.example.nightjar.data.db.dao.TrackDao
import com.example.nightjar.data.db.entity.MidiClipEntity
import com.example.nightjar.data.db.entity.MidiNoteEntity
import kotlinx.coroutines.flow.Flow

/**
 * Repository for MIDI clip and note CRUD, and instrument configuration.
 *
 * Each MIDI track has one or more [MidiClipEntity] clips. Notes within
 * a clip use positions relative to the clip start (0ms = clip beginning).
 * Instrument selection (program/channel) lives on [TrackEntity].
 */
class MidiRepository(
    private val midiClipDao: MidiClipDao,
    private val midiNoteDao: MidiNoteDao,
    private val trackDao: TrackDao
) {

    // -- Clips --

    suspend fun getClipsForTrack(trackId: Long): List<MidiClipEntity> =
        midiClipDao.getClipsForTrack(trackId)

    fun observeClipsForTrack(trackId: Long): Flow<List<MidiClipEntity>> =
        midiClipDao.observeClipsForTrack(trackId)

    suspend fun getClipById(clipId: Long): MidiClipEntity? =
        midiClipDao.getClipById(clipId)

    /**
     * Ensure a MIDI track has at least one clip. Creates a default clip
     * at offset 0 if none exist. Returns all clips for the track.
     */
    suspend fun ensureClipExists(trackId: Long): List<MidiClipEntity> {
        val existing = midiClipDao.getClipsForTrack(trackId)
        if (existing.isNotEmpty()) return existing

        midiClipDao.insertClip(
            MidiClipEntity(trackId = trackId, offsetMs = 0L, sortIndex = 0)
        )
        return midiClipDao.getClipsForTrack(trackId)
    }

    /**
     * Duplicate a clip and all its notes. The new clip is placed immediately
     * after the source clip. [clipDurationMs] determines the offset gap.
     */
    suspend fun duplicateClip(clipId: Long, clipDurationMs: Long): MidiClipEntity? {
        val source = midiClipDao.getClipById(clipId) ?: return null
        val maxSort = midiClipDao.getMaxSortIndex(source.trackId) ?: 0
        val newOffset = source.offsetMs + clipDurationMs

        val newClipId = midiClipDao.insertClip(
            MidiClipEntity(
                trackId = source.trackId,
                offsetMs = newOffset,
                sortIndex = maxSort + 1
            )
        )

        // Copy all notes to the new clip (same relative positions)
        val notes = midiNoteDao.getNotesForClip(clipId)
        for (note in notes) {
            midiNoteDao.insertNote(
                note.copy(id = 0L, clipId = newClipId)
            )
        }

        return midiClipDao.getClipById(newClipId)
    }

    suspend fun moveClip(clipId: Long, newOffsetMs: Long) {
        midiClipDao.updateClipOffset(clipId, newOffsetMs)
    }

    suspend fun deleteClip(clipId: Long) {
        // Notes cascade-delete via FK
        midiClipDao.deleteClip(clipId)
    }

    suspend fun insertClip(clip: MidiClipEntity): Long =
        midiClipDao.insertClip(clip)

    // -- Notes (clip-scoped) --

    suspend fun getNotesForClip(clipId: Long): List<MidiNoteEntity> =
        midiNoteDao.getNotesForClip(clipId)

    fun observeNotesForClip(clipId: Long): Flow<List<MidiNoteEntity>> =
        midiNoteDao.observeNotesForClip(clipId)

    /** Add a note to a specific clip. [startMs] is relative to clip start. */
    suspend fun addNote(
        clipId: Long,
        trackId: Long,
        pitch: Int,
        startMs: Long,
        durationMs: Long,
        velocity: Float = 0.8f
    ): Long {
        return midiNoteDao.insertNote(
            MidiNoteEntity(
                trackId = trackId,
                clipId = clipId,
                pitch = pitch,
                startMs = startMs,
                durationMs = durationMs,
                velocity = velocity
            )
        )
    }

    // -- Notes (track-scoped, for engine push and backwards compat) --

    suspend fun getNotesForTrack(trackId: Long): List<MidiNoteEntity> =
        midiNoteDao.getNotesForTrack(trackId)

    fun observeNotes(trackId: Long): Flow<List<MidiNoteEntity>> =
        midiNoteDao.observeNotesForTrack(trackId)

    suspend fun updateNoteTiming(noteId: Long, startMs: Long, durationMs: Long) {
        midiNoteDao.updateNoteTiming(noteId, startMs, durationMs)
    }

    suspend fun updateNotePitch(noteId: Long, pitch: Int) {
        midiNoteDao.updateNotePitch(noteId, pitch)
    }

    suspend fun updateNoteVelocity(noteId: Long, velocity: Float) {
        midiNoteDao.updateNoteVelocity(noteId, velocity)
    }

    suspend fun deleteNote(noteId: Long) {
        midiNoteDao.deleteNote(noteId)
    }

    // -- Instrument --

    suspend fun setInstrument(trackId: Long, program: Int) {
        trackDao.updateMidiProgram(trackId, program)
    }

    // -- Channel assignment --

    /** Reserved MIDI channels: 9 = drums, 15 = preview. */
    private val reservedChannels = setOf(9, 15)

    /**
     * Find the next available MIDI channel for a new track in this idea.
     * Scans existing MIDI tracks and skips reserved channels.
     * Returns channel 0 with a warning if all 14 slots are used.
     */
    suspend fun nextAvailableChannel(ideaId: Long): Int {
        val tracks = trackDao.getTracksForIdea(ideaId)
        val usedChannels = tracks
            .filter { it.isMidi }
            .map { it.midiChannel }
            .toSet()

        for (ch in 0..15) {
            if (ch !in reservedChannels && ch !in usedChannels) return ch
        }
        // All 14 slots used -- reuse channel 0
        return 0
    }

    // -- BPM scaling --

    suspend fun scaleNotePositions(ideaId: Long, scaleFactor: Double) {
        midiNoteDao.scaleNotePositions(ideaId, scaleFactor)
    }

    // -- Track duration --

    /**
     * Recompute and persist the track's durationMs from all its MIDI clips and notes.
     * Duration = max(clip.offsetMs + note.startMs + note.durationMs) across all clips.
     */
    suspend fun recomputeTrackDuration(trackId: Long) {
        val clips = midiClipDao.getClipsForTrack(trackId)
        var maxEnd = 0L
        for (clip in clips) {
            val notes = midiNoteDao.getNotesForClip(clip.id)
            val clipEnd = notes.maxOfOrNull { clip.offsetMs + it.startMs + it.durationMs } ?: clip.offsetMs
            if (clipEnd > maxEnd) maxEnd = clipEnd
        }
        trackDao.updateDuration(trackId, maxEnd)
    }
}
