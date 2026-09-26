package com.example.nightjar.audio

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WavImportConverterTest {
    @get:Rule val temp = TemporaryFolder()

    private fun fixture(bits: Int, channels: Int = 1, rate: Int = 44100, floating: Boolean = false,
        value: Int = 0, frames: Int = rate): File {
        val bytes = frames * channels * bits / 8
        val buffer = ByteBuffer.allocate(44 + bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()); buffer.putInt(36 + bytes); buffer.put("WAVEfmt ".toByteArray())
        buffer.putInt(16); buffer.putShort(if (floating) 3 else 1); buffer.putShort(channels.toShort())
        buffer.putInt(rate); buffer.putInt(rate * channels * bits / 8)
        buffer.putShort((channels * bits / 8).toShort()); buffer.putShort(bits.toShort())
        buffer.put("data".toByteArray()); buffer.putInt(bytes)
        repeat(frames * channels) { for (b in 0 until bits / 8) buffer.put((value shr (8 * b)).toByte()) }
        return temp.newFile().also { it.writeBytes(buffer.array()) }
    }

    @Test fun convertsCommonSampleDepthsWithoutChangingOriginal() {
        for ((bits, value) in listOf(8 to 192, 16 to 16384, 24 to 4194304, 32 to 1073741824)) {
            val source = fixture(bits); val half = fixture(bits, value = value)
            val before = half.readBytes(); val output = temp.newFile()
            assertEquals(1000L, WavImportConverter.convert(half, output))
            assertArrayEquals(before, half.readBytes())
            val pcm = ByteBuffer.wrap(output.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(16384, pcm.getShort(44).toInt())
            source.delete()
        }
    }

    @Test fun convertsStereo48kFloatToMono44100() {
        val input = fixture(32, channels = 2, rate = 48000, floating = true, value = 0.25f.toBits())
        val output = temp.newFile()
        assertEquals(1000L, WavImportConverter.convert(input, output))
        assertEquals(44L + 44100 * 2, output.length())
        assertEquals(8192, ByteBuffer.wrap(output.readBytes()).order(ByteOrder.LITTLE_ENDIAN).getShort(44).toInt())
    }

    @Test fun downmixCancelsOppositeStereoChannels() {
        val source = fixture(16, channels = 2, value = 16384)
        val bytes = ByteBuffer.wrap(source.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        for (offset in 46 until bytes.capacity() step 4) bytes.putShort(offset, -16384)
        source.writeBytes(bytes.array())
        val output = temp.newFile(); WavImportConverter.convert(source, output)
        assertTrue(output.readBytes().drop(44).all { it == 0.toByte() })
    }

    @Test fun resamplingPreservesElapsedTimeAndInterpolatesChangingAudio() {
        val source = fixture(16, rate = 48000, frames = 480)
        val bytes = ByteBuffer.wrap(source.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        repeat(480) { bytes.putShort(44 + it * 2, (it * 32).toShort()) }
        source.writeBytes(bytes.array())
        val output = temp.newFile()
        assertEquals(10L, WavImportConverter.convert(source, output))
        val pcm = ByteBuffer.wrap(output.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(441 * 2 + 44, pcm.capacity())
        assertEquals(3483, pcm.getShort(44 + 100 * 2).toInt())
    }

    @Test fun rejectsTruncatedInput() {
        val input = fixture(24); input.writeBytes(input.readBytes().dropLast(1).toByteArray())
        assertThrows(IllegalArgumentException::class.java) { WavImportConverter.convert(input, temp.newFile()) }
    }

    @Test fun rejectsNonfiniteFloatSamples() {
        assertThrows(IllegalArgumentException::class.java) {
            WavImportConverter.convert(fixture(32, floating = true, value = Float.NaN.toBits()), temp.newFile())
        }
    }

    @Test fun conversionHonorsCancellation() {
        var checks = 0
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            WavImportConverter.convert(fixture(16), temp.newFile()) {
                if (++checks > 5) throw java.util.concurrent.CancellationException()
            }
        }
    }
}
