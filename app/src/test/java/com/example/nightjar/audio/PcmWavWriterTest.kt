package com.example.nightjar.audio

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class PcmWavWriterTest {
    private fun files(test: (File, File) -> Unit) {
        val source = File.createTempFile("decoded-test", ".wav")
        val output = File.createTempFile("normalized-test", ".wav")
        try { test(source, output) } finally { source.delete(); output.delete() }
    }

    @Test fun `split decoder buffers respect offsets and produce normal WAV`() = files { source, output ->
        val data = ByteBuffer.allocate(2004).order(ByteOrder.LITTLE_ENDIAN)
        data.putInt(0x12345678)
        repeat(500) { data.putShort(16384); data.putShort(-8192) }
        PcmWavWriter(source, 44100, 2, false).use {
            it.append(data, 4, 3)
            it.append(data, 7, 1997)
            it.finish()
        }
        assertEquals(11L, WavImportConverter.convert(source, output))
        val normalized = ByteBuffer.wrap(output.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(44100, normalized.getInt(24))
        assertEquals(1, normalized.getShort(22).toInt())
        assertEquals(4096, normalized.getShort(44).toInt())
        assertEquals(1044L, output.length())
    }

    @Test fun `float decoder output reuses resampling and clipping checks`() = files { source, output ->
        val data = ByteBuffer.allocate(480 * 4).order(ByteOrder.LITTLE_ENDIAN)
        repeat(480) { data.putFloat(-0.5f) }
        PcmWavWriter(source, 48000, 1, true).use { it.append(data, 0, data.capacity()); it.finish() }
        assertEquals(10L, WavImportConverter.convert(source, output))
        assertEquals(44L + 441 * 2, output.length())
        assertEquals(-16384, ByteBuffer.wrap(output.readBytes()).order(ByteOrder.LITTLE_ENDIAN).getShort(44).toInt())
    }

    @Test fun `empty incomplete and invalid decoder ranges fail`() = files { source, _ ->
        PcmWavWriter(source, 44100, 2, false).use { writer ->
            assertThrows(IllegalArgumentException::class.java) { writer.finish() }
            assertThrows(IllegalArgumentException::class.java) { writer.append(ByteBuffer.allocate(8), 6, 4) }
            writer.append(ByteBuffer.allocate(3), 0, 3)
            assertThrows(IllegalArgumentException::class.java) { writer.finish() }
        }
    }

    @Test fun `writer refuses unsupported PCM layouts`() = files { source, _ ->
        assertThrows(IllegalArgumentException::class.java) { PcmWavWriter(source, 0, 1, false) }
        assertThrows(IllegalArgumentException::class.java) { PcmWavWriter(source, 44100, 0, false) }
    }
}
