package com.example.nightjar.audio

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CaptureTakeWriterTest {
    @get:Rule val folder = TemporaryFolder()

    private fun source(rate: Int = 44100, frames: Int = 100003): File {
        val pcm = ByteArray(frames * 2) { (it % 251).toByte() }
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + pcm.size); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(rate); putInt(rate * 2)
            putShort(2); putShort(16); put("data".toByteArray()); putInt(pcm.size)
        }.array()
        return folder.newFile().apply { writeBytes(header + pcm) }
    }

    @Test fun `fractional milliseconds rapid boundaries and tail preserve every PCM byte`() {
        val raw = source()
        val original = raw.readBytes()
        val parts = CaptureTakeWriter.partition(raw, listOf(0, 17, 17, 1000, 2000)) { folder.newFile() }
        assertEquals(6, parts.size)
        assertEquals(44L, parts[0].file.length())
        assertEquals(44L, parts[2].file.length())
        assertArrayEquals(original.drop(44).toByteArray(), parts.flatMap { it.file.readBytes().drop(44) }.toByteArray())
        parts.forEach { part ->
            val bytes = part.file.readBytes()
            val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(bytes.size - 8, header.getInt(4))
            assertEquals(bytes.size - 44, header.getInt(40))
            assertEquals(0, (bytes.size - 44) % 2)
        }
        assertArrayEquals(original, raw.readBytes())
    }

    @Test fun `reservation failure removes derived copies but keeps source`() {
        val raw = source()
        val original = raw.readBytes()
        val first = folder.newFile()
        var count = 0
        try {
            CaptureTakeWriter.partition(raw, listOf(100)) {
                if (count++ == 0) first else throw java.io.IOException("full")
            }
            fail("Expected failure")
        } catch (_: java.io.IOException) { }
        assertFalse(first.exists())
        assertArrayEquals(original, raw.readBytes())
    }

    @Test fun `empty native WAV and out of range boundary are retained as empty takes`() {
        val raw = source(48000, 0)
        val parts = CaptureTakeWriter.partition(raw, listOf(0, Long.MAX_VALUE)) { folder.newFile() }
        assertEquals(3, parts.size)
        assertTrue(parts.all { it.file.length() == 44L && it.durationMs == 0L })
    }
}
