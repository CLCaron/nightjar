package com.example.nightjar.audio

import com.example.nightjar.data.db.entity.CaptureBackingEntity
import com.example.nightjar.data.db.entity.TakeEntity
import org.junit.Assert.*
import org.junit.Test

class CapturePlaybackPlannerTest {
    private val guitar = take(1, 8000)
    private val melody = take(2, 41317)
    private val context = CaptureBackingEntity(2, 1, 352800, 1000000, 4410, 88200, "estimated")
    private fun take(id: Long, duration: Long) = TakeEntity(id = id, clipId = id,
        audioFileName = "$id.wav", displayName = "Take", sortIndex = 0, durationMs = duration)

    @Test fun `melody alone never adds its backing`() {
        val plan = CapturePlaybackPlanner.plan(listOf(melody), mapOf(2L to listOf(context)))
        assertEquals(listOf(2L), plan.slots.map { it.take.id })
        assertEquals(0L, plan.slots.single().phaseFrames)
    }

    @Test fun `rough performance duration determines pair cycle regardless of latch order`() {
        val first = CapturePlaybackPlanner.plan(listOf(guitar, melody), mapOf(2L to listOf(context)))
        val reversed = CapturePlaybackPlanner.plan(listOf(melody, guitar), mapOf(2L to listOf(context)))
        assertEquals(first, reversed)
        assertEquals(1822079L, first.slots.first().cycleFrames)
        assertEquals(first.slots.first().cycleFrames, first.slots.last().cycleFrames)
        assertEquals(88200L, first.slots.last().phaseFrames)
    }

    @Test fun `shorter performance still leads its original backing`() {
        val plan = CapturePlaybackPlanner.plan(listOf(guitar, melody.copy(durationMs = 3000)),
            mapOf(2L to listOf(context)))
        assertTrue(plan.slots.all { it.cycleFrames == 132300L })
    }

    @Test fun `different backing revision is not treated as related`() {
        assertThrows(IllegalArgumentException::class.java) {
            CapturePlaybackPlanner.plan(listOf(guitar.copy(id = 3), melody), mapOf(2L to listOf(context)))
        }
    }

    @Test fun `conflicting relationships do not silently pick a leader`() {
        assertThrows(IllegalArgumentException::class.java) {
            CapturePlaybackPlanner.plan(listOf(guitar, melody), mapOf(2L to listOf(context),
                1L to listOf(context.copy(takeId = 1, backingTakeId = 2))))
        }
    }
}
