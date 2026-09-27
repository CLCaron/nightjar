package com.example.nightjar.audio

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises simultaneous native playback and microphone capture on a real device. */
@RunWith(AndroidJUnit4::class)
class CaptureLoopEngineTest {
    @Test fun pausedTransportReleasesTimingCheckWithoutAnotherPoll() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = OboeAudioEngine(AudioInputPreferences(context))
        try {
            assertTrue(engine.initialize())
            engine.pause()
            engine.removeAllTracks()
            engine.setEndlessPlayback(true)
            engine.play()
            engine.pollState()
            assertTrue("Test must begin with the published playback flag set", engine.isPlaying.value)
            engine.pause()
            assertFalse("Pause must clear the published flag without a later screen poll", engine.isPlaying.value)
            // Unarmed calibration captures evidence but emits no sounds and creates no file.
            assertTrue("Stopped transport must allow a timing check", engine.startAcousticCheck(AcousticProbePlan.generate()))
            assertTrue(engine.awaitFirstBuffer())
            assertTrue(engine.stopAcousticCheck())
            assertFalse("Timing check must release temporary microphone input", engine.isRecordingActive())
            assertTrue("Silent check must collect input before cancellation", engine.acousticCheckSamples().isNotEmpty())
        } finally {
            engine.stopAcousticCheck()
            engine.pause()
            engine.setEndlessPlayback(false)
        }
    }

    @Test fun inputContinuesAcrossSeveralBackingLoops() = runBlocking {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val loop = File(cache, "capture-loop-test.wav")
        val recording = File(cache, "capture-loop-input-test.wav")
        val engine = OboeAudioEngine(AudioInputPreferences(
            InstrumentationRegistry.getInstrumentation().targetContext))
        val frames = 22050
        val dataBytes = frames * 2
        val wav = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII)); putInt(36 + dataBytes)
            put("WAVEfmt ".toByteArray(Charsets.US_ASCII)); putInt(16)
            putShort(1); putShort(1); putInt(44100); putInt(88200)
            putShort(2); putShort(16)
            put("data".toByteArray(Charsets.US_ASCII)); putInt(dataBytes)
        }
        loop.writeBytes(wav.array())
        try {
            assertTrue(engine.initialize())
            engine.pause()
            engine.removeAllTracks()
            engine.setEndlessPlayback(true)
            assertTrue(engine.addLoopingTrack(-101, loop.absolutePath, 500L))
            engine.seekTo(0)
            engine.play()
            Thread.sleep(250)
            assertTrue(engine.startRecording(recording.absolutePath))
            assertTrue(engine.awaitFirstBuffer())
            val routes = engine.getStreamEvidence()
            assertTrue("Input stream evidence missing", routes.input.open && routes.input.hasAnchor)
            assertTrue("Output stream evidence missing", routes.output.open && routes.output.hasAnchor)
            assertTrue("Stream epochs missing", routes.input.epoch > 0 && routes.output.epoch > 0)
            android.util.Log.i("CaptureLoopEngineTest", "Actual stream pair: $routes")
            engine.openWriteGate()
            Thread.sleep(3200)
            engine.pollState()
            assertTrue("Playback clock wrapped at the source boundary", engine.positionMs.value > 2500)
            assertTrue("Input did not continue across loop boundaries", engine.getCapturedFrames() > 88200)
            assertTrue("Missing input-to-playback clock snapshot", engine.getCaptureStartPlaybackFrame() > 0)
            assertTrue("Input file is too short", engine.stopRecording() > 2000)
            assertTrue(recording.length() > 44 + 88200 * 2)
        } finally {
            if (engine.isRecordingActive()) engine.stopRecording()
            engine.pause()
            engine.removeAllTracks()
            engine.setEndlessPlayback(false)
            loop.delete()
            recording.delete()
        }
    }
}
