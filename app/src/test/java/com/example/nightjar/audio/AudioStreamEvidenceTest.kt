package com.example.nightjar.audio

import org.junit.Assert.*
import org.junit.Test

class AudioStreamEvidenceTest {
    @Test fun `unknown or malformed native evidence never invents a route`() {
        assertEquals(AudioRouteEvidence(), AudioRouteEvidence.decode(longArrayOf(1)))
        assertFalse(AudioRouteEvidence.decode(LongArray(35)).input.open)
        assertNull(CallbackClockMapper.estimate(AudioStreamEvidence(), AudioStreamEvidence()))
    }

    @Test fun `input and output ids epochs and dropped frames stay separate`() {
        val raw = LongArray(35)
        raw[0] = 1; raw[1] = 42; raw[2] = 44100; raw[3] = 1; raw[15] = 11
        raw[16] = 1; raw[17] = 86; raw[18] = 48000; raw[19] = 2
        raw[32] = 3; raw[33] = 8; raw[34] = 2
        val actual = AudioRouteEvidence.decode(raw)
        assertEquals(42, actual.input.deviceId)
        assertEquals(86, actual.output.deviceId)
        assertEquals(11, actual.input.droppedFrames)
        assertEquals(3, actual.input.epoch)
        assertEquals(8, actual.output.epoch)
        assertEquals(2, actual.outputInterruptions)
    }

    @Test fun `callback map accounts for completed input block and rate conversion`() {
        val input = AudioStreamEvidence(open = true, sampleRate = 24000,
            callbackFrame = 24000, callbackFrames = 24, callbackNanos = 2_000_000_000)
        val output = AudioStreamEvidence(open = true, sampleRate = 48000,
            callbackFrame = 48000, callbackFrames = 48, callbackNanos = 2_000_000_000)
        val result = CallbackClockMapper.estimate(input, output)!!
        assertEquals(-48.0, result.inputToOutputFrames, 0.001)
        assertEquals(2.0, result.uncertaintyMs, 0.001)
    }

    @Test fun `stale anchors and closed streams are rejected`() {
        val input = AudioStreamEvidence(open = true, sampleRate = 44100,
            callbackFrames = 192, callbackNanos = 1_000_000_000)
        val output = input.copy(callbackNanos = 1_200_000_000)
        assertNull(CallbackClockMapper.estimate(input, output))
        assertNull(CallbackClockMapper.estimate(input.copy(open = false), input))
    }
}
