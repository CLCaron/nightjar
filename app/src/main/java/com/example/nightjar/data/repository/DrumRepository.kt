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
    suspend fun ensurePatternExists(trackId: Long, stepsPerBar: Int = 16): DrumPatternEntity {
        val existing = drumPatternDao.getPatternForTrack(trackId)
        if (existing != null) return existing

        val id = drumPatternDao.insertPattern(
            DrumPatternEntity(trackId = trackId, stepsPerBar = stepsPerBar)
        )
        return drumPatternDao.getPatternForTrack(trackId)
            ?: DrumPatternEntity(id = id, trackId = trackId, stepsPerBar = stepsPerBar)
    }

    suspend fun updatePatternGrid(patternId: Long, stepsPerBar: Int, bars: Int) {
        drumPatternDao.updatePatternGrid(patternId, stepsPerBar, bars)
    }

    suspend fun remapPatternResolution(
        patternId: Long,
        oldStepsPerBar: Int,
        newStepsPerBar: Int,
        bars: Int
    ) {
        drumPatternDao.remapPatternResolution(patternId, oldStepsPerBar, newStepsPerBar, bars)
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

    // -- Per-clip pattern duplication --

    /**
     * Duplicate a clip along with its entire pattern and steps.
     * Creates a new pattern (same stepsPerBar/bars), copies all steps,
     * creates a new clip referencing the new pattern, placed after the source.
     */
    suspend fun duplicateClipWithPattern(clipId: Long, patternDurationMs: Long) {
        val sourceClip = drumPatternDao.getClipById(clipId) ?: return
        val sourcePattern = drumPatternDao.getPatternById(sourceClip.patternId) ?: return
        val sourceSteps = drumPatternDao.getStepsForPattern(sourcePattern.id)

        // Create new pattern with same grid dimensions
        val newPatternId = drumPatternDao.insertPattern(
            DrumPatternEntity(
                trackId = sourcePattern.trackId,
                stepsPerBar = sourcePattern.stepsPerBar,
                bars = sourcePattern.bars
            )
        )

        // Copy all steps to the new pattern
        if (sourceSteps.isNotEmpty()) {
            drumPatternDao.insertSteps(
                sourceSteps.map { it.copy(id = 0L, patternId = newPatternId) }
            )
        }

        // Create new clip referencing the new pattern
        val maxSort = drumPatternDao.getMaxClipSortIndex(sourceClip.patternId) ?: 0
        drumPatternDao.insertClip(
            DrumClipEntity(
                patternId = newPatternId,
                offsetMs = sourceClip.offsetMs + patternDurationMs,
                sortIndex = maxSort + 1
            )
        )
    }

    /**
     * Create a new empty clip with its own pattern at the given offset.
     * The pattern defaults to 16 steps, 1 bar, no active steps.
     */
    suspend fun createEmptyClip(trackId: Long, offsetMs: Long, stepsPerBar: Int = 16) {
        val newPatternId = drumPatternDao.insertPattern(
            DrumPatternEntity(trackId = trackId, stepsPerBar = stepsPerBar)
        )
        val maxSort = drumPatternDao.getMaxClipSortIndexForTrack(trackId) ?: 0
        drumPatternDao.insertClip(
            DrumClipEntity(
                patternId = newPatternId,
                offsetMs = offsetMs,
                sortIndex = maxSort + 1
            )
        )
    }

    // -- Per-track clip queries --

    suspend fun getClipsForTrack(trackId: Long): List<DrumClipEntity> =
        drumPatternDao.getClipsForTrack(trackId)

    suspend fun getPatternById(patternId: Long): DrumPatternEntity? =
        drumPatternDao.getPatternById(patternId)

    suspend fun getPatternsForTrack(trackId: Long): List<DrumPatternEntity> =
        drumPatternDao.getPatternsForTrack(trackId)
}
