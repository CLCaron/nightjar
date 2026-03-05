package com.example.nightjar.data.repository

import com.example.nightjar.data.db.dao.DrumPatternDao
import com.example.nightjar.data.db.entity.DrumClipEntity
import com.example.nightjar.data.db.entity.DrumPatternEntity
import com.example.nightjar.data.db.entity.DrumStepEntity
import kotlinx.coroutines.flow.Flow

/**
 * Repository for drum pattern CRUD and step toggling.
 *
 * Each drum track has exactly one [DrumPatternEntity] containing
 * [DrumStepEntity] rows for active steps. BPM is project-level
 * (on IdeaEntity), not per-pattern.
 */
class DrumRepository(
    private val drumPatternDao: DrumPatternDao
) {

    // -- Pattern lifecycle --

    /**
     * Ensure a drum track has a pattern. Creates a default 16-step,
     * 1-bar pattern if none exists. Returns the pattern.
     */
    suspend fun ensurePatternExists(trackId: Long): DrumPatternEntity {
        val existing = drumPatternDao.getPatternForTrack(trackId)
        if (existing != null) return existing

        val id = drumPatternDao.insertPattern(
            DrumPatternEntity(trackId = trackId)
        )
        return drumPatternDao.getPatternForTrack(trackId)
            ?: DrumPatternEntity(id = id, trackId = trackId)
    }

    suspend fun updatePatternGrid(patternId: Long, stepsPerBar: Int, bars: Int) {
        drumPatternDao.updatePatternGrid(patternId, stepsPerBar, bars)
    }

    // -- Steps --

    suspend fun getSteps(patternId: Long): List<DrumStepEntity> =
        drumPatternDao.getStepsForPattern(patternId)

    fun observeSteps(patternId: Long): Flow<List<DrumStepEntity>> =
        drumPatternDao.observeStepsForPattern(patternId)

    /**
     * Toggle a step on/off. Returns true if the step is now active.
     */
    suspend fun toggleStep(
        patternId: Long,
        stepIndex: Int,
        drumNote: Int,
        velocity: Float = 0.8f
    ): Boolean = drumPatternDao.toggleStep(patternId, stepIndex, drumNote, velocity)

    // -- Clips --

    suspend fun getClips(patternId: Long): List<DrumClipEntity> =
        drumPatternDao.getClipsForPattern(patternId)

    /**
     * Ensure a pattern has at least one clip. Creates a default clip
     * at offset 0 if none exist. Returns all clips.
     */
    suspend fun ensureClipsExist(patternId: Long): List<DrumClipEntity> {
        val existing = drumPatternDao.getClipsForPattern(patternId)
        if (existing.isNotEmpty()) return existing

        drumPatternDao.insertClip(
            DrumClipEntity(patternId = patternId, offsetMs = 0L, sortIndex = 0)
        )
        return drumPatternDao.getClipsForPattern(patternId)
    }

    /**
     * Duplicate a clip. The new clip is placed immediately after the source
     * clip (offset = source offset + pattern duration in ms).
     * The caller must provide patternDurationMs for positioning.
     */
    suspend fun duplicateClip(clipId: Long, patternDurationMs: Long = 0L) {
        val source = drumPatternDao.getClipById(clipId) ?: return
        val maxSort = drumPatternDao.getMaxClipSortIndex(source.patternId) ?: 0
        val newOffset = source.offsetMs + patternDurationMs
        drumPatternDao.insertClip(
            DrumClipEntity(
                patternId = source.patternId,
                offsetMs = newOffset,
                sortIndex = maxSort + 1
            )
        )
    }

    suspend fun moveClip(clipId: Long, newOffsetMs: Long) {
        drumPatternDao.updateClipOffset(clipId, newOffsetMs)
    }

    suspend fun deleteClip(clipId: Long) {
        drumPatternDao.deleteClip(clipId)
    }
}
