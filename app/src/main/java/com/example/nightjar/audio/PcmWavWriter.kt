package com.example.nightjar.audio

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/** Offline PCM spool. The caller deletes unfinished output; memory stays bounded. */
internal class PcmWavWriter(
    file: File,
    val sampleRate: Int,
    val channels: Int,
    val floatSamples: Boolean
) : Closeable {
    private val bytesPerFrame = channels * if (floatSamples) 4 else 2
    private val scratch = ByteArray(16384)
    private val output: RandomAccessFile
    private var bytes = 0L
    private var finished = false

    init {
        require(sampleRate in 8000..192000 && channels in 1..8) { "Unsupported decoded audio layout." }
        output = RandomAccessFile(file, "rw")
        try {
            output.setLength(0)
            output.write(ByteArray(44))
        } catch (error: Throwable) {
            output.close()
            throw error
        }
    }

    fun append(buffer: ByteBuffer, offset: Int, size: Int) {
        check(!finished)
        require(offset >= 0 && size >= 0 && offset.toLong() + size <= buffer.capacity()) {
            "The decoder returned an invalid audio buffer."
        }
        require(bytes + size <= Int.MAX_VALUE - 44L) { "The decoded audio is too large to import." }
        val data = buffer.duplicate().apply { clear(); position(offset); limit(offset + size) }
        while (data.hasRemaining()) {
            val count = minOf(scratch.size, data.remaining())
            data.get(scratch, 0, count)
            output.write(scratch, 0, count)
        }
        bytes += size
    }

    fun finish() {
        check(!finished)
        require(bytes > 0 && bytes % bytesPerFrame == 0L) { "The file has no complete decoded audio frames." }
        output.seek(0)
        fun le16(value: Int) = output.writeShort(java.lang.Short.reverseBytes(value.toShort()).toInt())
        fun le32(value: Int) = output.writeInt(java.lang.Integer.reverseBytes(value))
        output.writeBytes("RIFF"); le32((bytes + 36).toInt()); output.writeBytes("WAVEfmt ")
        le32(16); le16(if (floatSamples) 3 else 1); le16(channels)
        le32(sampleRate); le32(sampleRate * bytesPerFrame); le16(bytesPerFrame)
        le16(if (floatSamples) 32 else 16); output.writeBytes("data"); le32(bytes.toInt())
        finished = true
    }

    override fun close() = output.close()
}
