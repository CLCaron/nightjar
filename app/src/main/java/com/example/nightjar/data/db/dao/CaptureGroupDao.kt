package com.example.nightjar.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.nightjar.data.db.entity.CaptureGroupEntity

@Dao
interface CaptureGroupDao {
    @Insert suspend fun insert(group: CaptureGroupEntity): Long

    @Query("SELECT * FROM capture_groups WHERE ideaId = :ideaId ORDER BY sortIndex, id")
    suspend fun forIdea(ideaId: Long): List<CaptureGroupEntity>

    @Query("SELECT * FROM capture_groups WHERE clipId = :clipId LIMIT 1")
    suspend fun forClip(clipId: Long): CaptureGroupEntity?

    @Query("SELECT * FROM capture_groups WHERE id = :id LIMIT 1")
    suspend fun forId(id: Long): CaptureGroupEntity?

    @Query("UPDATE capture_groups SET displayName = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("UPDATE capture_groups SET latchedTakeId = :takeId WHERE id = :id")
    suspend fun latch(id: Long, takeId: Long?)

    @Query("UPDATE capture_groups SET inStudio = 1 WHERE id = :id")
    suspend fun addToStudio(id: Long)

    @Query("""
        UPDATE capture_groups SET latchedTakeId = NULL
        WHERE latchedTakeId IS NOT NULL AND NOT EXISTS (
            SELECT 1 FROM takes WHERE takes.id = capture_groups.latchedTakeId
                AND takes.clipId = capture_groups.clipId
        )
    """)
    suspend fun clearMissingLatches()
}
