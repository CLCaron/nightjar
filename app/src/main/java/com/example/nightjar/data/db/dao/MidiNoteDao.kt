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

    @Query("SELECT * FROM midi_notes WHERE trackId = :trackId ORDER BY startMs, pitch")
    suspend fun getNotesForTrack(trackId: Long): List<MidiNoteEntity>

    @Query("SELECT * FROM midi_notes WHERE trackId = :trackId ORDER BY startMs, pitch")
    fun observeNotesForTrack(trackId: Long): Flow<List<MidiNoteEntity>>

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

    @Query("DELETE FROM midi_notes WHERE trackId = :trackId")
    suspend fun deleteAllNotesForTrack(trackId: Long)

    @Query("SELECT COUNT(*) FROM midi_notes WHERE trackId = :trackId")
    suspend fun getNoteCount(trackId: Long): Int
}
