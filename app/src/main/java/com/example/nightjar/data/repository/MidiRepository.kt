package com.example.nightjar.data.repository

import com.example.nightjar.data.db.dao.MidiNoteDao
import com.example.nightjar.data.db.dao.TrackDao
import com.example.nightjar.data.db.entity.MidiNoteEntity
import kotlinx.coroutines.flow.Flow

/**
 * Repository for MIDI note CRUD and instrument configuration.
 *
 * Each MIDI track stores its notes as [MidiNoteEntity] rows.
 * Instrument selection (program/channel) lives on [TrackEntity].
 */
class MidiRepository(
    private val midiNoteDao: MidiNoteDao,
    private val trackDao: TrackDao
) {

    // -- Notes --

    suspend fun getNotesForTrack(trackId: Long): List<MidiNoteEntity> =
        midiNoteDao.getNotesForTrack(trackId)

    fun observeNotes(trackId: Long): Flow<List<MidiNoteEntity>> =
        midiNoteDao.observeNotesForTrack(trackId)

    suspend fun addNote(
        trackId: Long,
        pitch: Int,
        startMs: Long,
        durationMs: Long,
        velocity: Float = 0.8f
    ): Long {
        return midiNoteDao.insertNote(
            MidiNoteEntity(
                trackId = trackId,
                pitch = pitch,
                startMs = startMs,
                durationMs = durationMs,
                velocity = velocity
            )
        )
    }

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
     * Recompute and persist the track's durationMs from its MIDI notes.
     * Duration = max(startMs + durationMs) across all notes.
     */
    suspend fun recomputeTrackDuration(trackId: Long) {
        val notes = midiNoteDao.getNotesForTrack(trackId)
        val maxEnd = notes.maxOfOrNull { it.startMs + it.durationMs } ?: 0L
        trackDao.updateDuration(trackId, maxEnd)
    }
}
