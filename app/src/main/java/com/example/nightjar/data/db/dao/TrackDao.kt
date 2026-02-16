package com.example.nightjar.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.nightjar.data.db.entity.TrackEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {

    @Insert
    suspend fun insertTrack(track: TrackEntity): Long

    @Query("UPDATE tracks SET displayName = :name WHERE id = :id")
    suspend fun updateDisplayName(id: Long, name: String)

    @Query("UPDATE tracks SET offsetMs = :offsetMs WHERE id = :id")
    suspend fun updateOffset(id: Long, offsetMs: Long)

    @Query("UPDATE tracks SET trimStartMs = :startMs, trimEndMs = :endMs WHERE id = :id")
    suspend fun updateTrim(id: Long, startMs: Long, endMs: Long)

    @Query("UPDATE tracks SET sortIndex = :index WHERE id = :id")
    suspend fun updateSortIndex(id: Long, index: Int)

    @Query("UPDATE tracks SET isMuted = :muted WHERE id = :id")
    suspend fun updateMuted(id: Long, muted: Boolean)

    @Query("UPDATE tracks SET volume = :volume WHERE id = :id")
    suspend fun updateVolume(id: Long, volume: Float)

    @Query("DELETE FROM tracks WHERE id = :id")
    suspend fun deleteTrackById(id: Long)

    @Query("SELECT * FROM tracks WHERE ideaId = :ideaId ORDER BY sortIndex ASC")
    fun observeTracksForIdea(ideaId: Long): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE ideaId = :ideaId ORDER BY sortIndex ASC")
    suspend fun getTracksForIdea(ideaId: Long): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun getTrackById(id: Long): TrackEntity?

    @Query("SELECT COUNT(*) FROM tracks WHERE ideaId = :ideaId")
    suspend fun getTrackCount(ideaId: Long): Int
}
