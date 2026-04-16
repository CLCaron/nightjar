package com.example.nightjar.ui.studio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the [ClipLinkage] + [GroupKey] abstraction that unifies
 * audio/MIDI (sourceClipId) vs drum (patternId) link semantics.
 */
class ClipLinkageTest {

    @Test
    fun `group of one is not linked`() {
        val audio = ClipLinkage.Audio(GroupKey.Audio(sourceId = 5L), groupSize = 1)
        assertFalse(audio.isLinked)
    }

    @Test
    fun `group of two is linked`() {
        val audio = ClipLinkage.Audio(GroupKey.Audio(sourceId = 5L), groupSize = 2)
        assertTrue(audio.isLinked)
    }

    @Test
    fun `siblings share groupKey`() {
        val source = ClipLinkage.Audio(GroupKey.Audio(sourceId = 7L), groupSize = 3)
        val instance = ClipLinkage.Audio(GroupKey.Audio(sourceId = 7L), groupSize = 3)
        assertEquals(source.groupKey, instance.groupKey)
    }

    @Test
    fun `audio and midi with same numeric id produce distinct group keys`() {
        val audio = GroupKey.Audio(sourceId = 1L)
        val midi = GroupKey.Midi(sourceId = 1L)
        val drum = GroupKey.Drum(patternId = 1L)
        assertFalse(audio == midi)
        assertFalse(audio == drum)
        assertFalse(midi == drum)
    }

    @Test
    fun `drum linkage keys by patternId`() {
        val a = ClipLinkage.Drum(GroupKey.Drum(patternId = 42L), groupSize = 4)
        val b = ClipLinkage.Drum(GroupKey.Drum(patternId = 42L), groupSize = 4)
        assertEquals(a.groupKey, b.groupKey)
        assertTrue(a.isLinked)
    }
}
