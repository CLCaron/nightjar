package com.example.nightjar.audio

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Offline streaming conversion. Memory use is independent of song length. */
object WavImportConverter {
    fun convert(source: File, output: File, checkCancelled: () -> Unit = {}): Long {
        RandomAccessFile(source, "r").use { input ->
            fun text(n: Int) = ByteArray(n).also { input.readFully(it) }.toString(Charsets.US_ASCII)
            fun u16(): Int = java.lang.Short.reverseBytes(input.readShort()).toInt() and 0xffff
            fun u32(): Long = java.lang.Integer.reverseBytes(input.readInt()).toLong() and 0xffffffffL
            require(text(4) == "RIFF") { "Choose an uncompressed WAV file." }
            val riffEnd = u32() + 8
            require(text(4) == "WAVE" && riffEnd <= input.length()) { "The WAV file is incomplete." }
            var encoding = 0; var channels = 0; var rate = 0L; var align = 0; var bits = 0
            var start = -1L; var bytes = 0L
            while (input.filePointer + 8 <= riffEnd) {
                checkCancelled()
                val id = text(4); val size = u32(); val body = input.filePointer
                require(size <= riffEnd - body) { "The WAV file is incomplete." }
                if (id == "fmt ") {
                    require(size >= 16) { "Invalid WAV format." }
                    encoding = u16(); channels = u16(); rate = u32(); u32(); align = u16(); bits = u16()
                    if (encoding == 0xfffe) {
                        require(size >= 40 && u16() >= 22) { "Unsupported WAV format." }
                        val validBits = u16(); u32()
                        require(validBits == bits) { "Unsupported WAV sample layout." }
                        encoding = u16()
                        val tail = ByteArray(14).also { input.readFully(it) }
                        require(tail.contentEquals(byteArrayOf(0, 0, 0, 0, 16, 0, -128, 0, 0, -86, 0, 56, -101, 113))) {
                            "Unsupported WAV encoding."
                        }
                    }
                } else if (id == "data" && start < 0) { start = body; bytes = size }
                input.seek(body + size + (size and 1))
            }
            require(channels in 1..8 && rate in 8000..192000 && bits in listOf(8, 16, 24, 32) &&
                (encoding == 1 || encoding == 3 && bits == 32) && align == channels * (bits / 8)) {
                "Choose a PCM or 32-bit floating-point WAV file."
            }
            require(start >= 0 && bytes > 0 && bytes % align == 0L) { "The WAV file has no complete audio frames." }
            val frames = bytes / align
            val count = (frames * 44100.0 / rate).roundToLong().coerceAtLeast(1)
            require(count >= 45 && count * 2 <= Int.MAX_VALUE - 44L) { "This WAV file is too short or too large." }
            input.seek(start)
            java.io.BufferedInputStream(java.io.FileInputStream(source).apply { channel.position(start) }).use { pcmInput ->
                val frame = ByteArray(align)
                fun sample(): Double {
                    var read = 0
                    while (read < frame.size) {
                        val count = pcmInput.read(frame, read, frame.size - read)
                        require(count > 0) { "The WAV file is incomplete." }
                        read += count
                    }
                    var sum = 0.0
                    for (channel in 0 until channels) {
                        val offset = channel * bits / 8
                        var value = 0
                        for (b in 0 until bits / 8) value = value or ((frame[offset + b].toInt() and 255) shl (8 * b))
                        val decoded = if (encoding == 3) Float.fromBits(value).toDouble() else when (bits) {
                            8 -> (value - 128) / 128.0
                            16 -> value.toShort() / 32768.0
                            24 -> (value shl 8 shr 8) / 8388608.0
                            else -> value / 2147483648.0
                        }
                        require(decoded.isFinite()) { "The WAV file contains invalid audio samples." }
                        sum += decoded
                    }
                    return sum / channels
                }
                RandomAccessFile(output, "rw").use { out ->
                    out.setLength(0)
                    fun le16(value: Int) = out.writeShort(java.lang.Short.reverseBytes(value.toShort()).toInt())
                    fun le32(value: Int) = out.writeInt(java.lang.Integer.reverseBytes(value))
                    out.writeBytes("RIFF"); le32((36 + count * 2).toInt()); out.writeBytes("WAVEfmt ")
                    le32(16); le16(1); le16(1); le32(44100); le32(88200); le16(2); le16(16)
                    out.writeBytes("data"); le32((count * 2).toInt())
                    var index = 0L; var left = sample(); var right = if (frames > 1) sample() else left
                    val buffer = ByteArray(8192); var used = 0
                    for (i in 0 until count) {
                        if (i % 4096 == 0L) checkCancelled()
                        val position = i * rate.toDouble() / 44100
                        val target = position.toLong().coerceAtMost(frames - 1)
                        while (index < target) {
                            left = right; index++
                            right = if (index + 1 < frames) sample() else left
                        }
                        val value = ((left + (right - left) * (position - target).coerceIn(0.0, 1.0))
                            .coerceIn(-1.0, 1.0) * 32768).roundToInt().coerceIn(-32768, 32767)
                        buffer[used++] = value.toByte(); buffer[used++] = (value shr 8).toByte()
                        if (used == buffer.size) { out.write(buffer); used = 0 }
                    }
                    if (used > 0) out.write(buffer, 0, used)
                }
                return count * 1000 / 44100
            }
        }
    }
}
