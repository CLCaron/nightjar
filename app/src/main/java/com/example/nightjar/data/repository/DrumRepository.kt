package com.example.nightjar.data.repository

import com.example.nightjar.data.db.dao.DrumPatternDao
import com.example.nightjar.data.db.entity.DrumClipEntity
import com.example.nightjar.data.db.entity.DrumPatternEntity
import com.example.nightjar.data.db.entity.DrumStepEntity
import com.example.nightjar.data.events.PulseBus
import com.example.nightjar.ui.studio.ClipLinkage
import com.example.nightjar.ui.studio.GroupKey
import kotlinx.coroutines.flow.Flow

/**
 * Repository for drum pattern CRUD and step toggling.
 *
 * Each drum track has one or more [DrumPatternEntity] rows, each with a
 * corresponding set of [DrumStepEntity] rows for active steps.
 *
 * ## Linked clips
 * Drum clips link by sharing a `patternId` -- two clips pointing at the
 * same pattern are siblings. Pattern-level edits (step toggles, resolution
 * changes) propagate to every clip that references the pattern and emit a
 * [GroupKey.Drum] on [PulseBus]. Per-clip state (offsetMs) does not emit.
 */
class DrumRepository(
    private val drumPatternDao: DrumPatternDao,
    private val pulseBus: PulseBus
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
        pulseBus.emit(GroupKey.Drum(patternId))
    }

    suspend fun remapPatternResolution(
        patternId: Long,
        oldStepsPerBar: Int,
        newStepsPerBar: Int,
        bars: Int
    ) {
        drumPatternDao.remapPatternResolution(patternId, oldStepsPerBar, newStepsPerBar, bars)
        pulseBus.emit(GroupKey.Drum(patternId))
    }

    // -- Steps --

    suspend fun getSteps(patternId: Long): List<DrumStepEntity> =
        drumPatternDao.getStepsForPattern(patternId)

    fun observeSteps(patternId: Long): Flow<List<DrumStepEntity>> =
        drumPatternDao.observeStepsForPattern(patternId)

    /**
     * Toggle a step on/off. Returns true if the step is now active. Emits a
     * sibling pulse on the pattern's group so every clip sharing this pattern
     * flashes.
     */
    suspend fun toggleStep(
        patternId: Long,
        stepIndex: Int,
        drumNote: Int,
        velocity: Float = 0.8f
    ): Boolean {
        val now = drumPatternDao.toggleStep(patternId, stepIndex, drumNote, velocity)
        pulseBus.emit(GroupKey.Drum(patternId))
        return now
    }

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
     * Duplicate a drum clip. Placed immediately after the source.
     *
     * - `linked = true`: the new clip shares the source's `patternId`.
     *   Pattern-level edits propagate across the group.
     * - `linked = false`: a new pattern is created from the source's steps,
     *   so the new clip is independent.
     */
    suspend fun duplicateClip(
        clipId: Long,
        patternDurationMs: Long = 0L,
        linked: Boolean = true
    ): DrumClipEntity? {
        val source = drumPatternDao.getClipById(clipId) ?: return null
        val maxSort = drumPatternDao.getMaxClipSortIndex(source.patternId) ?: 0
        val newOffset = source.offsetMs + patternDurationMs

        return if (linked) {
            val id = drumPatternDao.insertClip(
                DrumClipEntity(
                    patternId = source.patternId,
                    offsetMs = newOffset,
                    sortIndex = maxSort + 1
                )
            )
            pulseBus.emit(GroupKey.Drum(source.patternId))
            drumPatternDao.getClipById(id)
        } else {
            val sourcePattern = drumPatternDao.getPatternById(source.patternId) ?: return null
            val sourceSteps = drumPatternDao.getStepsForPattern(sourcePattern.id)
            val newPatternId = drumPatternDao.insertPattern(
                DrumPatternEntity(
                    trackId = sourcePattern.trackId,
                    stepsPerBar = sourcePattern.stepsPerBar,
                    bars = sourcePattern.bars
                )
            )
            if (sourceSteps.isNotEmpty()) {
                drumPatternDao.insertSteps(
                    sourceSteps.map { it.copy(id = 0L, patternId = newPatternId) }
                )
            }
            val id = drumPatternDao.insertClip(
                DrumClipEntity(
                    patternId = newPatternId,
                    offsetMs = newOffset,
                    sortIndex = maxSort + 1
                )
            )
            drumPatternDao.getClipById(id)
        }
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

    // -- Linked-clip helpers --

    /** Number of drum clips sharing [patternId] (i.e. group size). */
    suspend fun getGroupSize(patternId: Long): Int =
        drumPatternDao.getClipCountForPattern(patternId).coerceAtLeast(1)

    suspend fun getLinkage(clip: DrumClipEntity): ClipLinkage.Drum {
        val size = drumPatternDao.getClipCountForPattern(clip.patternId).coerceAtLeast(1)
        return ClipLinkage.Drum(GroupKey.Drum(clip.patternId), size)
    }

    /**
     * Split a drum clip at [sourceSplitStepIndex] (step index into the source
     * pattern). Creates a new pattern containing the right-half steps and a
     * new clip pointing at it. Every clip sharing the source pattern gets a
     * paired right-half clip so linked groups stay coherent.
     *
     * [stepDurationMs] converts step index to a timeline ms offset for clip
     * placement.
     */
    suspend fun splitClip(
        clipId: Long,
        sourceSplitStepIndex: Int,
        stepDurationMs: Long
    ): DrumClipEntity? {
        val clip = drumPatternDao.getClipById(clipId) ?: return null
        val pattern = drumPatternDao.getPatternById(clip.patternId) ?: return null
        val total = pattern.stepsPerBar * pattern.bars
        if (sourceSplitStepIndex <= 0 || sourceSplitStepIndex >= total) return null

        val steps = drumPatternDao.getStepsForPattern(pattern.id)
        val leftSteps = steps.filter { it.stepIndex < sourceSplitStepIndex }
        val rightSteps = steps.filter { it.stepIndex >= sourceSplitStepIndex }
        val siblings = drumPatternDao.getClipsForPattern(pattern.id)
        val leftOffsetMs = sourceSplitStepIndex * stepDurationMs

        // 1. Rewrite source pattern with only left-half steps.
        drumPatternDao.deleteAllStepsForPattern(pattern.id)
        if (leftSteps.isNotEmpty()) {
            drumPatternDao.insertSteps(leftSteps.map { it.copy(id = 0L, patternId = pattern.id) })
        }

        // 2. Create right-half pattern with remaining steps shifted to origin.
        val rightPatternId = drumPatternDao.insertPattern(
            DrumPatternEntity(
                trackId = pattern.trackId,
                stepsPerBar = pattern.stepsPerBar,
                bars = pattern.bars
            )
        )
        if (rightSteps.isNotEmpty()) {
            drumPatternDao.insertSteps(rightSteps.map {
                it.copy(id = 0L, patternId = rightPatternId, stepIndex = it.stepIndex - sourceSplitStepIndex)
            })
        }

        // 3. Pair every clip sharing the source pattern with a right-half clip.
        var newClipId = 0L
        for (sibling in siblings) {
            val sortIndex = (drumPatternDao.getMaxClipSortIndex(rightPatternId) ?: -1) + 1
            val id = drumPatternDao.insertClip(
                DrumClipEntity(
                    patternId = rightPatternId,
                    offsetMs = sibling.offsetMs + leftOffsetMs,
                    sortIndex = sortIndex
                )
            )
            if (sibling.id == clip.id) newClipId = id
        }

        pulseBus.emit(GroupKey.Drum(pattern.id))
        pulseBus.emit(GroupKey.Drum(rightPatternId))
        return if (newClipId != 0L) drumPatternDao.getClipById(newClipId) else null
    }

    /**
     * Unlink a drum clip from its pattern-sharing group by creating a fresh
     * pattern copy and pointing this clip at it. A no-op for standalone clips.
     *
     * Implementation note: Room doesn't expose a direct patternId update on
     * DrumClipEntity, so we delete+reinsert. Callers that held the old clip id
     * must re-query by track/pattern.
     */
    suspend fun unlinkClip(clipId: Long) {
        val clip = drumPatternDao.getClipById(clipId) ?: return
        val pattern = drumPatternDao.getPatternById(clip.patternId) ?: return
        if (drumPatternDao.getClipCountForPattern(pattern.id) < 2) return

        val steps = drumPatternDao.getStepsForPattern(pattern.id)
        val newPatternId = drumPatternDao.insertPattern(
            DrumPatternEntity(
                trackId = pattern.trackId,
                stepsPerBar = pattern.stepsPerBar,
                bars = pattern.bars
            )
        )
        if (steps.isNotEmpty()) {
            drumPatternDao.insertSteps(steps.map { it.copy(id = 0L, patternId = newPatternId) })
        }
        drumPatternDao.deleteClip(clipId)
        drumPatternDao.insertClip(
            DrumClipEntity(
                patternId = newPatternId,
                offsetMs = clip.offsetMs,
                sortIndex = clip.sortIndex
            )
        )
        pulseBus.emit(GroupKey.Drum(pattern.id))
    }
}
