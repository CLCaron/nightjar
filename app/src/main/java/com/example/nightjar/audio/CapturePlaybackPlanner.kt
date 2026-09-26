package com.example.nightjar.audio

import com.example.nightjar.data.db.entity.CaptureBackingEntity
import com.example.nightjar.data.db.entity.TakeEntity

data class CapturePlaybackSlot(val take: TakeEntity, val phaseFrames: Long, val cycleFrames: Long)
data class CapturePlaybackPlan(val slots: List<CapturePlaybackSlot>)

/** Reconstructs only the selected exact relationship. Never selects historical accompaniment. */
object CapturePlaybackPlanner {
    private const val SAMPLE_RATE = 44100L

    fun plan(takes: List<TakeEntity>, backings: Map<Long, List<CaptureBackingEntity>>): CapturePlaybackPlan {
        require(takes.size in 1..2 && takes.map { it.id }.distinct().size == takes.size) {
            "Select one take, or a recording and the backing take it was recorded over."
        }
        require(takes.all { it.durationMs > 0 }) { "One selected take has no recorded audio." }
        if (takes.size == 1) return CapturePlaybackPlan(listOf(
            CapturePlaybackSlot(takes.single(), 0, frames(takes.single()))))

        val selectedIds = takes.map { it.id }.toSet()
        val relationships = takes.flatMap { take -> backings[take.id].orEmpty().filter {
            it.takeId == take.id && it.backingTakeId != take.id && it.backingTakeId in selectedIds
        } }
        require(relationships.size == 1) {
            "These takes do not share one recorded backing relationship. Select one take to listen."
        }
        val relationship = relationships.single()
        val performance = takes.single { it.id == relationship.takeId }
        val backing = takes.single { it.id == relationship.backingTakeId }
        require(relationship.backingLoopFrames == frames(backing) &&
            relationship.sourcePhaseFrame in 0 until relationship.backingLoopFrames) {
            "The saved backing timing does not match this take. Select one take to listen."
        }
        val cycle = frames(performance)
        return CapturePlaybackPlan(listOf(
            CapturePlaybackSlot(performance, 0, cycle),
            CapturePlaybackSlot(backing, relationship.sourcePhaseFrame, cycle)
        ))
    }

    private fun frames(take: TakeEntity): Long = take.durationMs * SAMPLE_RATE / 1000
}
