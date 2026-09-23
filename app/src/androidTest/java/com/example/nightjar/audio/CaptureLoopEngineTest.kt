package com.example.nightjar.audio

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises simultaneous native playback and microphone capture on a real device. */
@RunWith(AndroidJUnit4::class)
class CaptureLoopEngineTest {
    @Test fun inputContinuesAcrossSeveralBackingLoops() = runBlocking {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val loop = File(cache, "capture-loop-test.wav")
        val recording = File(cache, "capture-loop-input-test.wav")
        val engine = OboeAudioEngine()
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
