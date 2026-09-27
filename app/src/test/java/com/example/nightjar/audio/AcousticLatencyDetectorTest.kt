package com.example.nightjar.audio

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class AcousticLatencyDetectorTest {
    private fun reference(): FloatArray {
        val random = Random(42)
        return FloatArray(128) { random.nextFloat() * 0.2f - 0.1f }
    }
    private fun good(delay: Int) = AcousticLatencyDetector.Trial(delay, 0.9, 3.0, 30.0, 0.0)

    @Test fun `finds a delayed inverted attenuated signal with DC offset`() {
        val probe = reference()
        val captured = FloatArray(900) { 0.00001f }
        probe.forEachIndexed { i, value -> captured[350 + i] = -value * 0.6f + 0.01f }
        val result = AcousticLatencyDetector.detect(probe, captured, 128, 600)!!
        assertEquals(350, result.delayFrames)
        assertTrue(result.correlation > 0.99)
        assertTrue(result.peakRatio > 1.5)
    }

    @Test fun `silence and nonfinite capture cannot become an accepted result`() {
        val silence = AcousticLatencyDetector.detect(reference(), FloatArray(900), 128, 600)!!
        assertEquals(0.0, silence.correlation, 0.0)
        assertNull(AcousticLatencyDetector.combine(List(7) { silence }, 1000, 1.0))
        val invalid = FloatArray(900).also { it[300] = Float.NaN }
        assertNull(AcousticLatencyDetector.detect(reference(), invalid, 128, 600))
    }

    @Test fun `independent echo prevents an ambiguous peak passing`() {
        val probe = reference()
        val capture = FloatArray(900)
        probe.copyInto(capture, 200); probe.copyInto(capture, 500)
        val result = AcousticLatencyDetector.detect(probe, capture, 128, 600)!!
        assertTrue(result.peakRatio < 1.5)
    }

    @Test fun `stable repeated trials reject an isolated outlier`() {
        val result = AcousticLatencyDetector.combine(listOf(100, 101, 99, 100, 102, 100, 400).map(::good), 1000, 2.0)!!
        assertEquals(100.0, result.delayFrames, 0.0)
        assertEquals(6, result.acceptedTrials)
        assertEquals(1, result.rejectedTrials)
    }

    @Test fun `second coherent cluster and excessive mapper uncertainty are rejected`() {
        assertNull(AcousticLatencyDetector.combine(listOf(100, 100, 101, 100, 99, 300, 301).map(::good), 1000, 2.0))
        assertNull(AcousticLatencyDetector.combine(List(7) { good(100) }, 1000, 3.1))
        assertNull(AcousticLatencyDetector.combine(List(7) { good(100) }, 1000, Double.NaN))
    }

    @Test fun `weak clipped noisy and insufficient measurements are rejected`() {
        assertNull(AcousticLatencyDetector.combine(List(7) { good(100).copy(clippedFraction = 0.1) }, 1000, 1.0))
        assertNull(AcousticLatencyDetector.combine(List(7) { good(100).copy(signalToNoiseDb = 5.0) }, 1000, 1.0))
        assertNull(AcousticLatencyDetector.combine(List(7) { good(100).copy(correlation = 0.2) }, 1000, 1.0))
        assertNull(AcousticLatencyDetector.combine(List(4) { good(100) }, 1000, 1.0))
    }
}
