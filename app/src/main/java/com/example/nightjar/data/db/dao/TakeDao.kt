package com.example.nightjar.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.nightjar.data.db.entity.TakeEntity
import kotlinx.coroutines.flow.Flow

/** Data access object for [TakeEntity] -- per-track take management. */
@Dao
interface TakeDao {

    @Insert
    suspend fun insertTake(take: TakeEntity): Long

    @Query("SELECT * FROM takes WHERE trackId = :trackId ORDER BY sortIndex ASC")
    suspend fun getTakesForTrack(trackId: Long): List<TakeEntity>

    @Query("SELECT * FROM takes WHERE trackId = :trackId ORDER BY sortIndex ASC")
    fun observeTakesForTrack(trackId: Long): Flow<List<TakeEntity>>

    @Query("SELECT * FROM takes WHERE id = :id")
    suspend fun getTakeById(id: Long): TakeEntity?

    @Query("SELECT COUNT(*) FROM takes WHERE trackId = :trackId")
    suspend fun getTakeCount(trackId: Long): Int

    @Query("UPDATE takes SET displayName = :name WHERE id = :id")
    suspend fun updateDisplayName(id: Long, name: String)

    @Query("UPDATE takes SET isMuted = :muted WHERE id = :id")
    suspend fun updateMuted(id: Long, muted: Boolean)

    @Query("UPDATE takes SET volume = :volume WHERE id = :id")
    suspend fun updateVolume(id: Long, volume: Float)

    @Query("UPDATE takes SET offsetMs = :offsetMs WHERE id = :id")
    suspend fun updateOffset(id: Long, offsetMs: Long)

    @Query("DELETE FROM takes WHERE id = :id")
    suspend fun deleteTakeById(id: Long)

    @Query("SELECT * FROM takes WHERE trackId IN (:trackIds) ORDER BY trackId, sortIndex ASC")
    suspend fun getTakesForTracks(trackIds: List<Long>): List<TakeEntity>
}
