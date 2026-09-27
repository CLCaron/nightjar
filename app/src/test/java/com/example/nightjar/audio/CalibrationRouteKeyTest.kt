package com.example.nightjar.audio

import org.junit.Assert.*
import org.junit.Test

class CalibrationRouteKeyTest {
    private fun route() = CalibrationRouteKey("phone", "earbud", 44100, 44100, 1, 2,
        1, 1, 96, 192, 9, 0, "a2dp", 1, "platform")

    @Test fun `identity separators cannot alias another route`() {
        val a = route().copy(inputIdentity = "a:b", outputIdentity = "c")
        val b = route().copy(inputIdentity = "a", outputIdentity = "b:c")
        assertNotEquals(a.fingerprint(), b.fingerprint())
        assertEquals(a.fingerprint(), a.copy().fingerprint())
    }

    @Test fun `processing configuration and revisions invalidate a route key`() {
        val original = route()
        listOf(original.copy(inputBuffer = 192), original.copy(inputPreset = 7),
            original.copy(outputRate = 48000), original.copy(audioMode = 3),
            original.copy(outputSharing = 2), original.copy(outputFormat = 2),
            original.copy(outputPerformance = 2), original.copy(outputBurst = 256),
            original.copy(mapperVersion = 2), original.copy(engineVersion = 2),
            original.copy(platformVersion = "updated"), original.copy(transportProfile = "sco"),
            original.copy(inputIdentity = "headset")).forEach {
            assertNotEquals(original.fingerprint(), it.fingerprint())
        }
    }
}
