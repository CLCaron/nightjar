package com.example.nightjar.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.nightjar.data.db.entity.TakeEntity
import kotlinx.coroutines.flow.Flow

/** Data access object for [TakeEntity] -- per-clip take management. */
@Dao
interface TakeDao {

    @Insert
    suspend fun insertTake(take: TakeEntity): Long

    @Query("SELECT * FROM takes WHERE clipId = :clipId ORDER BY sortIndex ASC")
    suspend fun getTakesForClip(clipId: Long): List<TakeEntity>

    @Query("SELECT * FROM takes WHERE clipId = :clipId ORDER BY sortIndex ASC")
    fun observeTakesForClip(clipId: Long): Flow<List<TakeEntity>>

    @Query("SELECT * FROM takes WHERE clipId IN (:clipIds) ORDER BY clipId, sortIndex ASC")
    suspend fun getTakesForClips(clipIds: List<Long>): List<TakeEntity>

    @Query("SELECT * FROM takes WHERE id = :id")
    suspend fun getTakeById(id: Long): TakeEntity?

    @Query("SELECT COUNT(*) FROM takes WHERE clipId = :clipId")
    suspend fun getTakeCount(clipId: Long): Int

    @Query("SELECT * FROM takes WHERE clipId = :clipId AND isActive = 1 LIMIT 1")
    suspend fun getActiveTakeForClip(clipId: Long): TakeEntity?

    @Query("UPDATE takes SET isActive = CASE WHEN id = :takeId THEN 1 ELSE 0 END WHERE clipId = :clipId")
    suspend fun setActiveTake(clipId: Long, takeId: Long)

    @Query("UPDATE takes SET displayName = :name WHERE id = :id")
    suspend fun updateDisplayName(id: Long, name: String)

    @Query("UPDATE takes SET volume = :volume WHERE id = :id")
    suspend fun updateVolume(id: Long, volume: Float)

    @Query("UPDATE takes SET trimStartMs = :trimStartMs, trimEndMs = :trimEndMs WHERE id = :id")
    suspend fun updateTrim(id: Long, trimStartMs: Long, trimEndMs: Long)

    @Query("DELETE FROM takes WHERE id = :id")
    suspend fun deleteTakeById(id: Long)
}
