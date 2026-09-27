package com.example.nightjar.audio

import org.junit.Assert.*
import org.junit.Test

class AcousticProbePlanTest {
    @Test fun `raw clipping is detected even when decimation would hide it`() {
        val raw = floatArrayOf(1f, -1f, 0f, 0f, .2f, .1f, .1f, .1f)
        assertEquals(.25, AcousticProbePlan.clippedFraction(raw, 0, raw.size), 0.0)
        assertTrue(AcousticProbePlan.downsample(raw).all { kotlin.math.abs(it) < .999f })
    }
    @Test fun `probes are deterministic bounded faded and independent`() {
        val probes = AcousticProbePlan.generate()
        assertArrayEquals(probes, AcousticProbePlan.generate(), 0f)
        assertEquals(11 * 1764, probes.size)
        assertTrue(probes.all { it.isFinite() && kotlin.math.abs(it) <= .1f })
        assertEquals(0f, probes[0], 0f)
        assertFalse(AcousticProbePlan.reference(probes, 0).contentEquals(AcousticProbePlan.reference(probes, 1)))
    }

    @Test fun `filtered test sound can be recovered after attenuation and polarity inversion`() {
        val reference = AcousticProbePlan.reference(AcousticProbePlan.generate(), 3)
        val capture = FloatArray(2500) { .000001f }
        reference.forEachIndexed { i, value -> capture[700 + i] = -value * .35f }
        val trial = AcousticLatencyDetector.detect(reference, capture, 300, 1600)!!
        assertEquals(700, trial.delayFrames)
        assertTrue(trial.correlation >= .65)
        assertTrue(trial.peakRatio >= 1.5)
        assertTrue(trial.signalToNoiseDb >= 12)
    }

    @Test fun `downsampling uses the same phase and averages each complete block`() {
        assertArrayEquals(floatArrayOf(2.5f, 6.5f),
            AcousticProbePlan.downsample(floatArrayOf(1f,2f,3f,4f,5f,6f,7f,8f,9f)), 0f)
    }
}
