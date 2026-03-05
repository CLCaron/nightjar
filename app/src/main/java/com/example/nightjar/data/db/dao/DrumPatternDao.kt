package com.example.nightjar.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.example.nightjar.data.db.entity.DrumClipEntity
import com.example.nightjar.data.db.entity.DrumPatternEntity
import com.example.nightjar.data.db.entity.DrumStepEntity
import kotlinx.coroutines.flow.Flow

/** Data access object for drum patterns and steps. */
@Dao
interface DrumPatternDao {

    // -- Pattern CRUD --

    @Insert
    suspend fun insertPattern(pattern: DrumPatternEntity): Long

    @Query("SELECT * FROM drum_patterns WHERE trackId = :trackId")
    suspend fun getPatternForTrack(trackId: Long): DrumPatternEntity?

    @Query("UPDATE drum_patterns SET stepsPerBar = :stepsPerBar, bars = :bars WHERE id = :id")
    suspend fun updatePatternGrid(id: Long, stepsPerBar: Int, bars: Int)

    @Query("DELETE FROM drum_patterns WHERE id = :id")
    suspend fun deletePattern(id: Long)

    // -- Step CRUD --

    @Insert
    suspend fun insertStep(step: DrumStepEntity): Long

    @Query("SELECT * FROM drum_steps WHERE patternId = :patternId ORDER BY drumNote, stepIndex")
    suspend fun getStepsForPattern(patternId: Long): List<DrumStepEntity>

    @Query("SELECT * FROM drum_steps WHERE patternId = :patternId ORDER BY drumNote, stepIndex")
    fun observeStepsForPattern(patternId: Long): Flow<List<DrumStepEntity>>

    @Query(
        """DELETE FROM drum_steps
           WHERE patternId = :patternId AND stepIndex = :stepIndex AND drumNote = :drumNote"""
    )
    suspend fun deleteStep(patternId: Long, stepIndex: Int, drumNote: Int)

    @Query(
        """SELECT COUNT(*) FROM drum_steps
           WHERE patternId = :patternId AND stepIndex = :stepIndex AND drumNote = :drumNote"""
    )
    suspend fun stepExists(patternId: Long, stepIndex: Int, drumNote: Int): Int

    /**
     * Toggle a step: insert if absent, delete if present.
     * Returns true if the step is now active (was inserted).
     */
    @Transaction
    suspend fun toggleStep(patternId: Long, stepIndex: Int, drumNote: Int, velocity: Float): Boolean {
        val exists = stepExists(patternId, stepIndex, drumNote) > 0
        if (exists) {
            deleteStep(patternId, stepIndex, drumNote)
            return false
        } else {
            insertStep(DrumStepEntity(patternId = patternId, stepIndex = stepIndex, drumNote = drumNote, velocity = velocity))
            return true
        }
    }

    // -- Clip CRUD --

    @Insert
    suspend fun insertClip(clip: DrumClipEntity): Long

    @Query("SELECT * FROM drum_clips WHERE patternId = :patternId ORDER BY sortIndex, offsetMs")
    suspend fun getClipsForPattern(patternId: Long): List<DrumClipEntity>

    @Query("SELECT * FROM drum_clips WHERE id = :clipId")
    suspend fun getClipById(clipId: Long): DrumClipEntity?

    @Query("UPDATE drum_clips SET offsetMs = :offsetMs WHERE id = :clipId")
    suspend fun updateClipOffset(clipId: Long, offsetMs: Long)

    @Query("DELETE FROM drum_clips WHERE id = :clipId")
    suspend fun deleteClip(clipId: Long)

    @Query("SELECT MAX(sortIndex) FROM drum_clips WHERE patternId = :patternId")
    suspend fun getMaxClipSortIndex(patternId: Long): Int?
}
