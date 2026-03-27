package com.example.nightjar.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.nightjar.data.db.entity.AudioClipEntity

/** Data access object for [AudioClipEntity] -- audio clip CRUD. */
@Dao
interface AudioClipDao {

    @Insert
    suspend fun insertClip(clip: AudioClipEntity): Long

    @Query("SELECT * FROM audio_clips WHERE trackId = :trackId ORDER BY sortIndex, offsetMs")
    suspend fun getClipsForTrack(trackId: Long): List<AudioClipEntity>

    @Query("SELECT * FROM audio_clips WHERE trackId IN (:trackIds) ORDER BY trackId, sortIndex, offsetMs")
    suspend fun getClipsForTracks(trackIds: List<Long>): List<AudioClipEntity>

    @Query("SELECT * FROM audio_clips WHERE id = :clipId")
    suspend fun getClipById(clipId: Long): AudioClipEntity?

    @Query("UPDATE audio_clips SET offsetMs = :offsetMs WHERE id = :clipId")
    suspend fun updateClipOffset(clipId: Long, offsetMs: Long)

    @Query("UPDATE audio_clips SET displayName = :name WHERE id = :clipId")
    suspend fun updateDisplayName(clipId: Long, name: String)

    @Query("UPDATE audio_clips SET isMuted = :muted WHERE id = :clipId")
    suspend fun updateMuted(clipId: Long, muted: Boolean)

    @Query("DELETE FROM audio_clips WHERE id = :clipId")
    suspend fun deleteClip(clipId: Long)

    @Query("SELECT MAX(sortIndex) FROM audio_clips WHERE trackId = :trackId")
    suspend fun getMaxSortIndex(trackId: Long): Int?

    @Query("SELECT COUNT(*) FROM audio_clips WHERE trackId = :trackId")
    suspend fun getClipCount(trackId: Long): Int
}
