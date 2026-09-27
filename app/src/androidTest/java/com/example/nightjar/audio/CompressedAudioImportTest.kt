package com.example.nightjar.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.nightjar.data.storage.RecordingStorage
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Generated fixtures are disposable. No test opens the installed Ideas database. */
@RunWith(AndroidJUnit4::class)
class CompressedAudioImportTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun importer() = AudioImporter(context, RecordingStorage(context))
    private fun digest(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val bytes = ByteArray(65536)
            while (true) { val n = input.read(bytes); if (n < 0) break; hash.update(bytes, 0, n) }
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }
    private fun checkWorkingAudio(file: File) {
        val header = file.inputStream().use { input -> ByteArray(44).also { assertEquals(44, input.read(it)) } }
        val data = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", header.copyOfRange(0, 4).toString(Charsets.US_ASCII))
        assertEquals(1, data.getShort(20).toInt())
        assertEquals(1, data.getShort(22).toInt())
        assertEquals(44100, data.getInt(24))
        assertEquals(16, data.getShort(34).toInt())
        assertEquals(file.length() - 44, data.getInt(40).toLong())
        var nonzero = false
        file.inputStream().buffered().use { input ->
            input.skip(44)
            val block = ByteArray(8192)
            while (!nonzero) {
                val n = input.read(block)
                if (n < 0) break
                nonzero = (0 until n).any { block[it] != 0.toByte() }
            }
        }
        assertTrue("Decoded working copy is silent", nonzero)
    }

    @Test fun extensionlessAacImportsAndPreservesOriginal() = runBlocking {
        val source = File.createTempFile("received-song-", "", context.cacheDir)
        encodeAac(source)
        var imported: AudioImporter.ImportedAudio? = null
        try {
            val result = importer().import(Uri.fromFile(source))
            imported = result
            assertEquals(digest(source), digest(result.original))
            assertTrue(result.durationMs in 80..300)
            assertEquals(source.name, result.title)
            checkWorkingAudio(result.file)
            assertTrue(result.file.parentFile!!.listFiles().orEmpty().none { it.name.startsWith("decoded_import_") })
        } finally { imported?.file?.delete(); imported?.original?.delete(); source.delete() }
    }

    @Test fun invalidAudioCleansProvisionalFiles() = runBlocking {
        val source = File.createTempFile("invalid-import-", ".mp3", context.cacheDir)
        source.writeText("This is not audio")
        val directory = RecordingStorage(context).createRecordingFile("test-marker").let { it.delete(); it.parentFile!! }
        val before = directory.list().orEmpty().toSet()
        try {
            try { importer().import(Uri.fromFile(source)); fail("Invalid audio must fail") }
            catch (expected: java.io.IOException) { assertTrue(expected.message!!.contains("convert")) }
            assertEquals(before, directory.list().orEmpty().toSet())
        } finally { source.delete() }
    }

    @Test fun cancelledDecodeRemovesTemporaryWav() {
        val source = File.createTempFile("cancel-source-", "", context.cacheDir)
        val output = File.createTempFile("cancel-output-", ".wav", context.cacheDir)
        encodeAac(source)
        val before = context.cacheDir.list().orEmpty().toSet()
        try {
            var calls = 0
            assertThrows(CancellationException::class.java) {
                PlatformAudioImportConverter.convert(source, output) {
                    if (++calls > 2) throw CancellationException("Test cancellation")
                }
            }
            assertEquals(before, context.cacheDir.list().orEmpty().toSet())
        } finally { source.delete(); output.delete() }
    }

    @Test fun privateReceivedSongRegression() = runBlocking {
        val source = File(context.cacheDir, "joe-import-regression")
        assumeTrue("Private fixture is supplied separately, never bundled or committed", source.exists())
        var imported: AudioImporter.ImportedAudio? = null
        try {
            val result = importer().import(Uri.fromFile(source))
            imported = result
            assertEquals(digest(source), digest(result.original))
            assertTrue(result.durationMs > 1000)
            val metadata = android.media.MediaMetadataRetriever()
            try {
                metadata.setDataSource(source.absolutePath)
                val duration = metadata.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
                assertTrue("Decoder truncated the received song", kotlin.math.abs(result.durationMs - duration) <= 250)
            } finally { metadata.release() }
            checkWorkingAudio(result.file)
            android.util.Log.i("CompressedAudioImportTest", "Private M4A decoded: ${result.durationMs} ms, ${result.file.length()} bytes")
        } finally { imported?.file?.delete(); imported?.original?.delete() }
    }

    private fun encodeAac(output: File) {
        val rate = 48000
        val pcm = ByteBuffer.allocate(4800 * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(4800) { frame ->
                val value = (kotlin.math.sin(frame * 2.0 * Math.PI * 440 / rate) * 12000).toInt().toShort()
                putShort(value); putShort(value)
            }
        }.array()
        val codec = MediaCodec.createEncoderByType("audio/mp4a-latm")
        var muxer: MediaMuxer? = null
        var started = false
        try {
            val format = MediaFormat.createAudioFormat("audio/mp4a-latm", rate, 2).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 128000)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val destination = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = destination
            var cursor = 0
            var inputEnded = false
            var ended = false
            var track = -1
            val info = MediaCodec.BufferInfo()
            val deadline = android.os.SystemClock.elapsedRealtime() + 10000
            while (!ended) {
                check(android.os.SystemClock.elapsedRealtime() < deadline) { "AAC fixture encoder stalled" }
                if (!inputEnded) {
                    val index = codec.dequeueInputBuffer(10000)
                    if (index >= 0) {
                        val input = codec.getInputBuffer(index)!!.apply { clear() }
                        val count = minOf(input.remaining() / 4 * 4, pcm.size - cursor)
                        input.put(pcm, cursor, count)
                        val timestamp = cursor / 4 * 1000000L / rate
                        cursor += count
                        inputEnded = cursor == pcm.size
                        codec.queueInputBuffer(index, 0, count, timestamp,
                            if (inputEnded) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                    }
                }
                val index = codec.dequeueOutputBuffer(info, 10000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = destination.addTrack(codec.outputFormat)
                    destination.start(); started = true
                } else if (index >= 0) {
                    try {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            check(started)
                            destination.writeSampleData(track, codec.getOutputBuffer(index)!!, info)
                        }
                        ended = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    } finally { codec.releaseOutputBuffer(index, false) }
                }
            }
        } finally {
            try { if (started) muxer?.stop() } finally {
                try { muxer?.release() } finally { codec.release() }
            }
        }
    }
}
