package com.example.nightjar.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.nightjar.data.db.entity.ImportedSourceEntity

@Dao
interface ImportedSourceDao {
    @Insert suspend fun insert(source: ImportedSourceEntity)
    @Query("SELECT * FROM imported_sources WHERE ideaId = :ideaId")
    suspend fun forIdea(ideaId: Long): List<ImportedSourceEntity>
}
