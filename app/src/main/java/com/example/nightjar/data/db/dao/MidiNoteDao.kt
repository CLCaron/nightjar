package com.example.nightjar.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.nightjar.data.db.entity.MidiNoteEntity
import kotlinx.coroutines.flow.Flow

/** Data access object for [MidiNoteEntity] -- MIDI note CRUD and observation. */
@Dao
interface MidiNoteDao {

    @Insert
    suspend fun insertNote(note: MidiNoteEntity): Long

    @Insert
    suspend fun insertNotes(notes: List<MidiNoteEntity>): List<Long>

    @Query("SELECT * FROM midi_notes WHERE trackId = :trackId ORDER BY startMs, pitch")
    suspend fun getNotesForTrack(trackId: Long): List<MidiNoteEntity>

    @Query("SELECT * FROM midi_notes WHERE trackId = :trackId ORDER BY startMs, pitch")
    fun observeNotesForTrack(trackId: Long): Flow<List<MidiNoteEntity>>

    @Query("SELECT * FROM midi_notes WHERE clipId = :clipId ORDER BY startMs, pitch")
    suspend fun getNotesForClip(clipId: Long): List<MidiNoteEntity>

    @Query("SELECT * FROM midi_notes WHERE clipId = :clipId ORDER BY startMs, pitch")
    fun observeNotesForClip(clipId: Long): Flow<List<MidiNoteEntity>>

    @Query("SELECT * FROM midi_notes WHERE id = :noteId")
    suspend fun getNoteById(noteId: Long): MidiNoteEntity?

    @Query("UPDATE midi_notes SET startMs = :startMs, durationMs = :durationMs WHERE id = :noteId")
    suspend fun updateNoteTiming(noteId: Long, startMs: Long, durationMs: Long)

    @Query("UPDATE midi_notes SET pitch = :pitch WHERE id = :noteId")
    suspend fun updateNotePitch(noteId: Long, pitch: Int)

    @Query("UPDATE midi_notes SET velocity = :velocity WHERE id = :noteId")
    suspend fun updateNoteVelocity(noteId: Long, velocity: Float)

    @Query("DELETE FROM midi_notes WHERE id = :noteId")
    suspend fun deleteNote(noteId: Long)

    @Query("DELETE FROM midi_notes WHERE id IN (:noteIds)")
    suspend fun deleteNotes(noteIds: List<Long>)

    @Query("SELECT * FROM midi_notes WHERE id IN (:noteIds)")
    suspend fun getNotesByIds(noteIds: List<Long>): List<MidiNoteEntity>

    @Query("DELETE FROM midi_notes WHERE trackId = :trackId")
    suspend fun deleteAllNotesForTrack(trackId: Long)

    @Query("DELETE FROM midi_notes WHERE clipId = :clipId")
    suspend fun deleteAllNotesForClip(clipId: Long)

    @Query("SELECT COUNT(*) FROM midi_notes WHERE trackId = :trackId")
    suspend fun getNoteCount(trackId: Long): Int

    @Query("SELECT COUNT(*) FROM midi_notes WHERE clipId = :clipId")
    suspend fun getNoteCountForClip(clipId: Long): Int

    @Query("""
        UPDATE midi_notes
        SET startMs = CAST(ROUND(startMs * :scaleFactor) AS INTEGER),
            durationMs = CAST(ROUND(durationMs * :scaleFactor) AS INTEGER)
        WHERE trackId IN (SELECT id FROM tracks WHERE ideaId = :ideaId AND trackType = 'midi')
    """)
    suspend fun scaleNotePositions(ideaId: Long, scaleFactor: Double)
}
