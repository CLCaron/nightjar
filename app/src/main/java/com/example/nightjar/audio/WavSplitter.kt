package com.example.nightjar.audio

import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Splits a PCM WAV file at given millisecond boundaries.
 *
 * Reads the 44-byte WAV header to determine sample rate, bit depth,
 * and channel count. Computes byte offsets from ms timestamps and
 * writes each segment with a fresh WAV header + raw PCM slice.
 *
 * Only supports standard uncompressed PCM WAV (format code 1).
 */
object WavSplitter {

    private const val TAG = "WavSplitter"
    private const val WAV_HEADER_SIZE = 44

    /**
     * Split a PCM WAV file at the given millisecond boundaries.
     *
     * @param sourceFile    The continuous recording WAV file.
     * @param splitPointsMs List of ms timestamps where splits occur (relative to file start).
     *                      Must be sorted ascending. Values <= 0 or >= file duration are ignored.
     * @param outputDir     Directory to write split files into.
     * @param namePrefix    Filename prefix for split files (e.g. "nightjar_take").
     * @return List of split WAV files in order. Empty if splitting fails.
     */
    fun split(
        sourceFile: File,
        splitPointsMs: List<Long>,
        outputDir: File,
        namePrefix: String
    ): List<File> {
        if (!sourceFile.exists() || sourceFile.length() < WAV_HEADER_SIZE) {
            Log.e(TAG, "Source file missing or too small: ${sourceFile.absolutePath}")
            return emptyList()
        }

        val header = readWavHeader(sourceFile) ?: return emptyList()

        val totalDataBytes = sourceFile.length() - WAV_HEADER_SIZE
        val bytesPerMs = (header.sampleRate.toLong() * header.blockAlign) / 1000L
        val totalDurationMs = if (bytesPerMs > 0) totalDataBytes / bytesPerMs else 0L

        // Build segment boundaries: [0, split1, split2, ..., totalDurationMs]
        val boundaries = mutableListOf(0L)
        for (splitMs in splitPointsMs.sorted()) {
            if (splitMs > 0 && splitMs < totalDurationMs && splitMs != boundaries.last()) {
                boundaries.add(splitMs)
            }
        }
        boundaries.add(totalDurationMs)

        if (boundaries.size < 3) {
            // No valid split points -- nothing to split
            Log.d(TAG, "No valid split points, skipping split")
            return emptyList()
        }

        outputDir.mkdirs()
        val results = mutableListOf<File>()

        try {
            FileInputStream(sourceFile).use { fis ->
                // Skip the source header
                val headerBytes = ByteArray(WAV_HEADER_SIZE)
                fis.read(headerBytes)

                for (i in 0 until boundaries.size - 1) {
                    val segStartMs = boundaries[i]
                    val segEndMs = boundaries[i + 1]
                    val segStartBytes = segStartMs * bytesPerMs
                    val segEndBytes = segEndMs * bytesPerMs

                    // Align to block boundary
                    val alignedStart = (segStartBytes / header.blockAlign) * header.blockAlign
                    val alignedEnd = (segEndBytes / header.blockAlign) * header.blockAlign
                    val segDataSize = alignedEnd - alignedStart

                    if (segDataSize <= 0) continue

                    val outFile = File(outputDir, "${namePrefix}_${i + 1}.wav")
                    FileOutputStream(outFile).use { fos ->
                        fos.write(createWavHeader(header, segDataSize.toInt()))

                        // Read and write the PCM data slice
                        // We need to seek to the right position in the input
                        val currentPos = WAV_HEADER_SIZE + alignedStart
                        fis.channel.position(currentPos)

                        val buffer = ByteArray(8192)
                        var remaining = segDataSize
                        while (remaining > 0) {
                            val toRead = minOf(remaining, buffer.size.toLong()).toInt()
                            val read = fis.read(buffer, 0, toRead)
                            if (read <= 0) break
                            fos.write(buffer, 0, read)
                            remaining -= read
                        }
                    }

                    results.add(outFile)
                    Log.d(TAG, "Segment ${i + 1}: ${segStartMs}ms-${segEndMs}ms -> ${outFile.name}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to split WAV: ${e.message}", e)
            // Clean up partial files
            results.forEach { it.delete() }
            return emptyList()
        }

        return results
    }

    private data class WavHeader(
        val sampleRate: Int,
        val numChannels: Int,
        val bitsPerSample: Int,
        val blockAlign: Int
    )

    private fun readWavHeader(file: File): WavHeader? {
        try {
            FileInputStream(file).use { fis ->
                val header = ByteArray(WAV_HEADER_SIZE)
                if (fis.read(header) < WAV_HEADER_SIZE) return null

                val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

                // Verify RIFF header
                val riff = String(header, 0, 4)
                val wave = String(header, 8, 4)
                if (riff != "RIFF" || wave != "WAVE") {
                    Log.e(TAG, "Not a valid WAV file")
                    return null
                }

                // fmt chunk (starts at byte 12)
                val fmt = String(header, 12, 4)
                if (fmt != "fmt ") {
                    Log.e(TAG, "fmt chunk not found at expected position")
                    return null
                }

                val audioFormat = buf.getShort(20).toInt() and 0xFFFF
                if (audioFormat != 1) {
                    Log.e(TAG, "Unsupported audio format: $audioFormat (only PCM supported)")
                    return null
                }

                val numChannels = buf.getShort(22).toInt() and 0xFFFF
                val sampleRate = buf.getInt(24)
                val bitsPerSample = buf.getShort(34).toInt() and 0xFFFF
                val blockAlign = buf.getShort(32).toInt() and 0xFFFF

                return WavHeader(sampleRate, numChannels, bitsPerSample, blockAlign)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read WAV header: ${e.message}")
            return null
        }
    }

    private fun createWavHeader(source: WavHeader, dataSize: Int): ByteArray {
        val byteRate = source.sampleRate * source.blockAlign
        val totalSize = dataSize + 36 // total file size minus 8 bytes for RIFF header

        return ByteBuffer.allocate(WAV_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN).apply {
            // RIFF header
            put('R'.code.toByte()); put('I'.code.toByte())
            put('F'.code.toByte()); put('F'.code.toByte())
            putInt(totalSize)
            put('W'.code.toByte()); put('A'.code.toByte())
            put('V'.code.toByte()); put('E'.code.toByte())

            // fmt sub-chunk
            put('f'.code.toByte()); put('m'.code.toByte())
            put('t'.code.toByte()); put(' '.code.toByte())
            putInt(16) // sub-chunk size for PCM
            putShort(1) // audio format = PCM
            putShort(source.numChannels.toShort())
            putInt(source.sampleRate)
            putInt(byteRate)
            putShort(source.blockAlign.toShort())
            putShort(source.bitsPerSample.toShort())

            // data sub-chunk
            put('d'.code.toByte()); put('a'.code.toByte())
            put('t'.code.toByte()); put('a'.code.toByte())
            putInt(dataSize)
        }.array()
    }
}
