package com.example.nightjar.audio

import org.junit.Assert.*
import org.junit.Test

class CalibrationTimingTest {
    private val route = CalibrationRouteKey("phone-mic", "headphones-identity", 44100, 44100,
        1, 2, 2, 2, 192, 192, 9, 0, "a2dp", 1, "android-test")
    private val profile = CalibrationProfile("revision-1", route, 100, 2, 3, true, 1000, "session-1")

    @Test fun `compatible Bluetooth reconnect reuses measurement with suggestion`() {
        val result = CalibrationProfilePolicy.select(route, 4, 5, listOf(profile))
        assertEquals(profile, result.profile)
        assertEquals(CalibrationMethod.REUSED_MEASUREMENT, result.method)
        assertTrue(result.suggestRecheck)
    }

    @Test fun `same session is measured but changed mic mode and buffer cannot reuse`() {
        assertEquals(CalibrationMethod.MEASURED,
            CalibrationProfilePolicy.select(route, 2, 3, listOf(profile), sessionId = "session-1").method)
        listOf(route.copy(inputIdentity = "headset-mic"), route.copy(transportProfile = "sco"),
            route.copy(outputBuffer = 384), route.copy(mapperVersion = 2),
            route.copy(platformVersion = "new-os")).forEach {
            assertNull(CalibrationProfilePolicy.select(it, 2, 3, listOf(profile)).profile)
        }
    }

    @Test fun `matching epochs after process restart do not claim a current check`() {
        assertEquals(CalibrationMethod.REUSED_MEASUREMENT,
            CalibrationProfilePolicy.select(route, 2, 3, listOf(profile), sessionId = "new-session").method)
        val wired = route.copy(transportProfile = "wired")
        assertNull(CalibrationProfilePolicy.select(wired, 2, 3,
            listOf(profile.copy(route = wired)), sessionId = "new-session").profile)
    }

    @Test fun `unknown identity rejected candidate and failed recheck cannot be measured`() {
        assertNull(CalibrationProfilePolicy.select(route.copy(inputIdentity = ""), 2, 3, listOf(profile)).profile)
        assertNull(CalibrationProfilePolicy.select(route, 2, 3, listOf(profile.copy(accepted = false))).profile)
        assertNull(CalibrationProfilePolicy.select(route, 2, 3, listOf(profile), failedRecheck = true).profile)
    }

    @Test fun `manual boundary uses one correction and negative adjustment shifts earlier`() {
        val timing = CaptureTimingSnapshot(1000, 200, 0, CalibrationMethod.MEASURED)
        assertEquals(1300L, timing.alignedFrame(500))
        assertEquals(10800L, timing.alignedFrame(10000))
        assertEquals(10750L, timing.copy(manualAdjustmentFrames = -50).alignedFrame(10000))
        assertEquals(950L, timing.copy(renderOriginFrame = 0).phase(150, 0, 1000))
    }

    @Test fun `new profile does not change a saved timing snapshot`() {
        val saved = CaptureTimingSnapshot(1000, profile.correctionOutputFrames, 0,
            CalibrationMethod.MEASURED, profile.revision)
        val revised = profile.copy(revision = "revision-2", correctionOutputFrames = 500)
        assertEquals(1400L, saved.alignedFrame(500))
        assertNotEquals(revised.revision, saved.profileRevision)
    }
}
