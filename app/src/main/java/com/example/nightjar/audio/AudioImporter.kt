package com.example.nightjar.audio

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.nightjar.data.storage.RecordingStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToLong

/** Imports reusable audio files into Nightjar's app-private WAV format. */
class AudioImporter(
    private val context: Context,
    private val storage: RecordingStorage
) {
    data class ImportedAudio(
        val file: File,
        val durationMs: Long,
        val displayName: String
    )

    suspend fun import(uri: Uri): ImportedAudio = withContext(Dispatchers.IO) {
        val sourceName = queryDisplayName(uri) ?: "Imported audio"
        val temp = File.createTempFile("nightjar_import_src", ".tmp", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            } ?: throw IllegalArgumentException("Could not open audio file.")

            val output = storage.createRecordingFile(prefix = "nightjar_import", extension = "wav")
            val durationMs = convertPcmWavToNightjarWav(temp, output)
            ImportedAudio(output, durationMs, sourceName)
        } finally {
            temp.delete()
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }

    private fun convertPcmWavToNightjarWav(source: File, output: File): Long {
        RandomAccessFile(source, "r").use { raf ->
            val header = readWaveHeader(raf)
            require(header.audioFormat == 1) {
                "Only uncompressed PCM WAV imports are supported in this build."
            }
            require(header.bitsPerSample == 16) {
                "Only 16-bit WAV imports are supported in this build."
            }
            require(header.channels == 1 || header.channels == 2) {
                "Only mono or stereo WAV imports are supported in this build."
            }

            val sourceFrameCount = header.dataSize / header.blockAlign
            require(sourceFrameCount in 1..Int.MAX_VALUE) {
                "That audio file is too large to import on this device."
            }

            val monoSamples = readMonoSamples(raf, header, sourceFrameCount.toInt())
            val outputFrameCount = (sourceFrameCount * TARGET_SAMPLE_RATE.toDouble() /
                header.sampleRate.toDouble()).roundToLong().coerceAtLeast(1L)
            val outputDataSize = outputFrameCount * BYTES_PER_SAMPLE

            FileOutputStream(output).use { fos ->
                fos.write(createWavHeader(outputDataSize))
                writeResampledMonoPcm(
                    samples = monoSamples,
                    sourceSampleRate = header.sampleRate,
                    outputFrameCount = outputFrameCount,
                    output = fos
                )
            }

            return (outputFrameCount * 1000L) / TARGET_SAMPLE_RATE
        }
    }

    private fun readMonoSamples(
        raf: RandomAccessFile,
        header: WaveHeader,
        frameCount: Int
    ): ShortArray {
        raf.seek(header.dataOffset)
        val result = ShortArray(frameCount)
        val framesPerChunk = (64 * 1024 / header.blockAlign).coerceAtLeast(1)
        val buffer = ByteArray(framesPerChunk * header.blockAlign)
        var written = 0

        while (written < frameCount) {
            val framesToRead = min(framesPerChunk, frameCount - written)
            val bytesToRead = framesToRead * header.blockAlign
            raf.readFully(buffer, 0, bytesToRead)

            var offset = 0
            repeat(framesToRead) {
                val mono = if (header.channels == 1) {
                    readShortLe(buffer, offset).toInt()
                } else {
                    val left = readShortLe(buffer, offset).toInt()
                    val right = readShortLe(buffer, offset + BYTES_PER_SAMPLE).toInt()
                    (left + right) / 2
                }
                result[written++] = mono.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    .toShort()
                offset += header.blockAlign
            }
        }

        return result
    }

    private fun writeResampledMonoPcm(
        samples: ShortArray,
        sourceSampleRate: Int,
        outputFrameCount: Long,
        output: FileOutputStream
    ) {
        val bytes = ByteArray(8192)
        var byteIndex = 0
        val lastIndex = samples.lastIndex

        for (outFrame in 0 until outputFrameCount) {
            val sourcePosition = outFrame.toDouble() * sourceSampleRate.toDouble() /
                TARGET_SAMPLE_RATE.toDouble()
            val baseIndex = floor(sourcePosition).toInt().coerceIn(0, lastIndex)
            val nextIndex = min(baseIndex + 1, lastIndex)
            val fraction = sourcePosition - baseIndex.toDouble()
            val value = samples[baseIndex] * (1.0 - fraction) + samples[nextIndex] * fraction
            val sample = value.roundToLong()
                .coerceIn(Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong())
                .toInt()

            bytes[byteIndex++] = (sample and 0xFF).toByte()
            bytes[byteIndex++] = ((sample ushr 8) and 0xFF).toByte()
            if (byteIndex >= bytes.size) {
                output.write(bytes, 0, byteIndex)
                byteIndex = 0
            }
        }

        if (byteIndex > 0) output.write(bytes, 0, byteIndex)
    }

    private fun readWaveHeader(raf: RandomAccessFile): WaveHeader {
        val riff = ByteArray(12)
        raf.seek(0L)
        raf.readFully(riff)
        require(String(riff, 0, 4) == "RIFF" && String(riff, 8, 4) == "WAVE") {
            "Choose a WAV file."
        }

        var audioFormat = 0
        var channels = 0
        var sampleRate = 0
        var blockAlign = 0
        var bitsPerSample = 0
        var dataOffset = 0L
        var dataSize = 0L

        while (raf.filePointer + 8 <= raf.length()) {
            val chunkIdBytes = ByteArray(4)
            raf.readFully(chunkIdBytes)
            val chunkId = String(chunkIdBytes)
            val chunkSize = Integer.toUnsignedLong(readIntLe(raf))
            val chunkStart = raf.filePointer

            when (chunkId) {
                "fmt " -> {
                    val fmt = ByteArray(chunkSize.toInt())
                    raf.readFully(fmt)
                    audioFormat = readShortLe(fmt, 0).toInt() and 0xFFFF
                    channels = readShortLe(fmt, 2).toInt() and 0xFFFF
                    sampleRate = readIntLe(fmt, 4)
                    blockAlign = readShortLe(fmt, 12).toInt() and 0xFFFF
                    bitsPerSample = readShortLe(fmt, 14).toInt() and 0xFFFF
                }
                "data" -> {
                    dataOffset = raf.filePointer
                    dataSize = chunkSize
                    raf.seek(chunkStart + chunkSize + (chunkSize % 2L))
                }
                else -> raf.seek(chunkStart + chunkSize + (chunkSize % 2L))
            }
        }

        require(dataOffset > 0L && dataSize > 0L && sampleRate > 0 && blockAlign > 0) {
            "Could not read WAV audio data."
        }

        return WaveHeader(
            audioFormat = audioFormat,
            channels = channels,
            sampleRate = sampleRate,
            blockAlign = blockAlign,
            bitsPerSample = bitsPerSample,
            dataOffset = dataOffset,
            dataSize = dataSize
        )
    }

    private fun createWavHeader(dataSize: Long): ByteArray {
        val byteRate = TARGET_SAMPLE_RATE * BYTES_PER_SAMPLE
        val totalSize = dataSize + 36L
        return ByteBuffer.allocate(WAV_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(totalSize.toInt())
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16)
            putShort(1)
            putShort(1)
            putInt(TARGET_SAMPLE_RATE)
            putInt(byteRate)
            putShort(BYTES_PER_SAMPLE.toShort())
            putShort(16)
            put("data".toByteArray())
            putInt(dataSize.toInt())
        }.array()
    }

    private fun readIntLe(raf: RandomAccessFile): Int {
        val b0 = raf.read()
        val b1 = raf.read()
        val b2 = raf.read()
        val b3 = raf.read()
        return (b0 and 0xFF) or
            ((b1 and 0xFF) shl 8) or
            ((b2 and 0xFF) shl 16) or
            ((b3 and 0xFF) shl 24)
    }

    private fun readIntLe(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun readShortLe(bytes: ByteArray, offset: Int): Short =
        (((bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8))).toShort()

    private data class WaveHeader(
        val audioFormat: Int,
        val channels: Int,
        val sampleRate: Int,
        val blockAlign: Int,
        val bitsPerSample: Int,
        val dataOffset: Long,
        val dataSize: Long
    )

    private companion object {
        const val TARGET_SAMPLE_RATE = 44_100
        const val BYTES_PER_SAMPLE = 2
        const val WAV_HEADER_SIZE = 44
    }
}
