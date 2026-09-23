package com.example.nightjar.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.nightjar.data.db.entity.CaptureBackingEntity

@Dao
interface CaptureBackingDao {
    @Insert suspend fun insert(backing: CaptureBackingEntity)

    @Query("SELECT * FROM capture_backings WHERE takeId = :takeId")
    suspend fun forTake(takeId: Long): List<CaptureBackingEntity>
}
