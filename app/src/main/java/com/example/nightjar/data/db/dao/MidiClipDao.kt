package com.example.nightjar.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.nightjar.data.db.entity.MidiClipEntity
import kotlinx.coroutines.flow.Flow

/** Data access object for [MidiClipEntity] -- MIDI clip CRUD and observation. */
@Dao
interface MidiClipDao {

    @Insert
    suspend fun insertClip(clip: MidiClipEntity): Long

    @Query("SELECT * FROM midi_clips WHERE trackId = :trackId ORDER BY sortIndex, offsetMs")
    suspend fun getClipsForTrack(trackId: Long): List<MidiClipEntity>

    @Query("SELECT * FROM midi_clips WHERE trackId = :trackId ORDER BY sortIndex, offsetMs")
    fun observeClipsForTrack(trackId: Long): Flow<List<MidiClipEntity>>

    @Query("SELECT * FROM midi_clips WHERE id = :clipId")
    suspend fun getClipById(clipId: Long): MidiClipEntity?

    @Query("UPDATE midi_clips SET offsetMs = :offsetMs WHERE id = :clipId")
    suspend fun updateClipOffset(clipId: Long, offsetMs: Long)

    @Query("DELETE FROM midi_clips WHERE id = :clipId")
    suspend fun deleteClip(clipId: Long)

    @Query("SELECT MAX(sortIndex) FROM midi_clips WHERE trackId = :trackId")
    suspend fun getMaxSortIndex(trackId: Long): Int?

    @Query("SELECT COUNT(*) FROM midi_clips WHERE trackId = :trackId")
    suspend fun getClipCount(trackId: Long): Int
}
