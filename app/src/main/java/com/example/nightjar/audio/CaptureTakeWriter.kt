package com.example.nightjar.audio

import com.example.nightjar.data.storage.RecordingStorage
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class CaptureAudio(val file: File, val durationMs: Long)

/** Partitions our native PCM WAV without rounding away tail frames or modifying the source. */
class CaptureTakeWriter @Inject constructor(private val storage: RecordingStorage) {
    suspend fun write(source: File, boundariesMs: List<Long>): List<CaptureAudio> =
        withContext(Dispatchers.IO) { partition(source, boundariesMs) { storage.createRecordingFile() } }

    companion object {
        internal fun partition(source: File, boundariesMs: List<Long>, reserve: () -> File): List<CaptureAudio> {
            val outputs = mutableListOf<File>()
            try {
                return RandomAccessFile(source, "r").use { input ->
                    val header = ByteArray(44)
                    input.readFully(header)
                    val h = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                    require(String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                        String(header, 8, 4, Charsets.US_ASCII) == "WAVE" &&
                        String(header, 12, 4, Charsets.US_ASCII) == "fmt " && h.getInt(16) == 16 &&
                        h.getShort(20).toInt() == 1 &&
                        String(header, 36, 4, Charsets.US_ASCII) == "data") { "Unsupported capture WAV" }
                    val rate = h.getInt(24).toLong()
                    val alignment = h.getShort(32).toInt()
                    require(rate > 0 && alignment > 0)
                    val bytes = input.length() - 44
                    require(bytes >= 0 && bytes % alignment == 0L && bytes <= 0xffffffffL - 36)
                    val frames = bytes / alignment
                    require(boundariesMs.all { it >= 0 } && boundariesMs.zipWithNext().all { (a, b) -> a <= b })
                    val cuts = listOf(0L) + boundariesMs.map { ms ->
                        // Clamp before multiplication to avoid overflow for malformed positions.
                        if (ms >= frames * 1000 / rate + 1) frames else (ms * rate / 1000).coerceAtMost(frames)
                    } + frames
                    val buffer = ByteArray(64 * 1024)
                    cuts.zipWithNext().map { (start, end) ->
                        val file = reserve()
                        require(file.canonicalFile != source.canonicalFile) { "Source cannot be a take destination" }
                        outputs.add(file)
                        val length = (end - start) * alignment
                        RandomAccessFile(file, "rw").use { output ->
                            output.setLength(0)
                            val takeHeader = header.copyOf()
                            ByteBuffer.wrap(takeHeader).order(ByteOrder.LITTLE_ENDIAN).apply {
                                putInt(4, (36 + length).toInt()); putInt(40, length.toInt())
                            }
                            output.write(takeHeader)
                            input.seek(44 + start * alignment)
                            var remaining = length
                            while (remaining > 0) {
                                val count = minOf(remaining, buffer.size.toLong()).toInt()
                                input.readFully(buffer, 0, count)
                                output.write(buffer, 0, count)
                                remaining -= count
                            }
                            output.fd.sync()
                        }
                        CaptureAudio(file, (end - start) * 1000 / rate)
                    }
                }
            } catch (e: Exception) {
                // Only incomplete derived copies are discarded. The raw source is never changed.
                outputs.forEach { file -> if (file.exists() && !file.delete()) e.addSuppressed(java.io.IOException("Could not remove partial ${file.name}")) }
                throw e
            }
        }
    }
}
