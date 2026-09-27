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
import java.nio.charset.StandardCharsets
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
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
            try {
                val durationMs = convertWaveToNightjarWav(temp, output)
                ImportedAudio(output, durationMs, sourceName)
            } catch (e: Exception) {
                output.delete()
                throw e
            }
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

    private fun convertWaveToNightjarWav(source: File, output: File): Long {
        RandomAccessFile(source, "r").use { raf ->
            val header = readWaveHeader(raf)
            require(header.encoding != WaveSampleEncoding.UNSUPPORTED) {
                "Choose an uncompressed PCM or floating-point WAV file."
            }
            require(header.channels in 1..MAX_CHANNELS) {
                "Only WAV files with $MAX_CHANNELS or fewer channels are supported."
            }
            require(header.dataSize % header.blockAlign == 0L) {
                "Could not read WAV audio data."
            }

            val sourceFrameCount = header.dataSize / header.blockAlign
            require(sourceFrameCount in 1..Int.MAX_VALUE) {
                "That audio file is too large to import on this device."
            }

            val monoSamples = readMonoSamples(raf, header, sourceFrameCount.toInt())
            val outputFrameCount = (sourceFrameCount * TARGET_SAMPLE_RATE.toDouble() /
                header.sampleRate.toDouble()).roundToLong().coerceAtLeast(1L)
            val outputDataSize = outputFrameCount * BYTES_PER_SAMPLE
            require(outputDataSize <= Int.MAX_VALUE) {
                "That audio file is too large to import on this device."
            }

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
                var sum = 0.0
                repeat(header.channels) { channel ->
                    sum += readSampleUnit(buffer, offset + channel * header.bytesPerSample, header)
                }
                val mono = (sum / header.channels.toDouble()).coerceIn(-1.0, 1.0)
                result[written++] = (mono * Short.MAX_VALUE)
                    .roundToInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
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
        require(
            String(riff, 0, 4, StandardCharsets.US_ASCII) == "RIFF" &&
                String(riff, 8, 4, StandardCharsets.US_ASCII) == "WAVE"
        ) {
            "Choose a WAV file."
        }

        var encoding = WaveSampleEncoding.UNSUPPORTED
        var channels = 0
        var sampleRate = 0
        var blockAlign = 0
        var bitsPerSample = 0
        var validBitsPerSample = 0
        var dataOffset = 0L
        var dataSize = 0L

        while (raf.filePointer + 8 <= raf.length()) {
            val chunkIdBytes = ByteArray(4)
            raf.readFully(chunkIdBytes)
            val chunkId = String(chunkIdBytes, StandardCharsets.US_ASCII)
            val chunkSize = Integer.toUnsignedLong(readIntLe(raf))
            val chunkStart = raf.filePointer

            when (chunkId) {
                "fmt " -> {
                    require(chunkSize in MIN_FMT_CHUNK_SIZE..MAX_FMT_CHUNK_SIZE) {
                        "Could not read WAV format data."
                    }
                    val fmt = ByteArray(chunkSize.toInt())
                    raf.readFully(fmt)
                    val audioFormat = readShortLe(fmt, 0).toInt() and 0xFFFF
                    channels = readShortLe(fmt, 2).toInt() and 0xFFFF
                    sampleRate = readIntLe(fmt, 4)
                    blockAlign = readShortLe(fmt, 12).toInt() and 0xFFFF
                    bitsPerSample = readShortLe(fmt, 14).toInt() and 0xFFFF
                    validBitsPerSample = bitsPerSample
                    encoding = when (audioFormat) {
                        WAVE_FORMAT_PCM -> WaveSampleEncoding.PCM
                        WAVE_FORMAT_IEEE_FLOAT -> WaveSampleEncoding.IEEE_FLOAT
                        WAVE_FORMAT_EXTENSIBLE -> {
                            require(fmt.size >= EXTENSIBLE_FMT_SIZE) {
                                "Could not read WAV format data."
                            }
                            validBitsPerSample = (readShortLe(fmt, 18).toInt() and 0xFFFF)
                                .takeIf { it > 0 } ?: bitsPerSample
                            when (readIntLe(fmt, 24)) {
                                WAVE_FORMAT_PCM -> WaveSampleEncoding.PCM
                                WAVE_FORMAT_IEEE_FLOAT -> WaveSampleEncoding.IEEE_FLOAT
                                else -> WaveSampleEncoding.UNSUPPORTED
                            }
                        }
                        else -> WaveSampleEncoding.UNSUPPORTED
                    }
                    raf.seek(chunkStart + chunkSize + (chunkSize % 2L))
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
        require(bitsPerSample > 0 && blockAlign >= channels * ((bitsPerSample + 7) / 8)) {
            "Could not read WAV format data."
        }
        require(isSupportedSampleDepth(encoding, bitsPerSample, validBitsPerSample)) {
            "Choose a WAV file with 8, 16, 24, or 32-bit PCM, or 32/64-bit floating-point audio."
        }

        return WaveHeader(
            encoding = encoding,
            channels = channels,
            sampleRate = sampleRate,
            blockAlign = blockAlign,
            bitsPerSample = bitsPerSample,
            validBitsPerSample = validBitsPerSample,
            dataOffset = dataOffset,
            dataSize = dataSize
        )
    }

    private fun readSampleUnit(bytes: ByteArray, offset: Int, header: WaveHeader): Double =
        when (header.encoding) {
            WaveSampleEncoding.PCM -> readPcmSampleUnit(bytes, offset, header)
            WaveSampleEncoding.IEEE_FLOAT -> readFloatSampleUnit(bytes, offset, header)
            WaveSampleEncoding.UNSUPPORTED -> 0.0
        }

    private fun readPcmSampleUnit(bytes: ByteArray, offset: Int, header: WaveHeader): Double {
        if (header.bitsPerSample == 8) {
            return (((bytes[offset].toInt() and 0xFF) - 128).toDouble() / 128.0)
                .coerceIn(-1.0, 1.0)
        }

        val raw = readSignedLe(bytes, offset, header.bytesPerSample)
        val shift = (header.bitsPerSample - header.validBitsPerSample).coerceAtLeast(0)
        val aligned = if (shift > 0) raw shr shift else raw
        val scaleBits = header.validBitsPerSample.coerceIn(2, 32)
        val scale = (1L shl (scaleBits - 1)).toDouble()
        return (aligned.toDouble() / scale).coerceIn(-1.0, 1.0)
    }

    private fun readFloatSampleUnit(bytes: ByteArray, offset: Int, header: WaveHeader): Double {
        val value = when (header.bitsPerSample) {
            32 -> Float.fromBits(readIntLe(bytes, offset)).toDouble()
            64 -> Double.fromBits(readLongLe(bytes, offset))
            else -> 0.0
        }
        return if (value.isFinite()) value.coerceIn(-1.0, 1.0) else 0.0
    }

    private fun isSupportedSampleDepth(
        encoding: WaveSampleEncoding,
        bitsPerSample: Int,
        validBitsPerSample: Int
    ): Boolean = when (encoding) {
        WaveSampleEncoding.PCM -> bitsPerSample in setOf(8, 16, 24, 32) &&
            validBitsPerSample in 1..bitsPerSample
        WaveSampleEncoding.IEEE_FLOAT -> bitsPerSample == 32 || bitsPerSample == 64
        WaveSampleEncoding.UNSUPPORTED -> false
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

    private fun readLongLe(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xFFL) or
            ((bytes[offset + 1].toLong() and 0xFFL) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFFL) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFFL) shl 24) or
            ((bytes[offset + 4].toLong() and 0xFFL) shl 32) or
            ((bytes[offset + 5].toLong() and 0xFFL) shl 40) or
            ((bytes[offset + 6].toLong() and 0xFFL) shl 48) or
            ((bytes[offset + 7].toLong() and 0xFFL) shl 56)

    private fun readShortLe(bytes: ByteArray, offset: Int): Short =
        (((bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8))).toShort()

    private fun readSignedLe(bytes: ByteArray, offset: Int, byteCount: Int): Int {
        var value = 0
        for (i in 0 until byteCount) {
            value = value or ((bytes[offset + i].toInt() and 0xFF) shl (i * 8))
        }
        val shift = (Int.SIZE_BYTES - byteCount) * 8
        return (value shl shift) shr shift
    }

    private data class WaveHeader(
        val encoding: WaveSampleEncoding,
        val channels: Int,
        val sampleRate: Int,
        val blockAlign: Int,
        val bitsPerSample: Int,
        val validBitsPerSample: Int,
        val dataOffset: Long,
        val dataSize: Long
    ) {
        val bytesPerSample: Int = (bitsPerSample + 7) / 8
    }

    private enum class WaveSampleEncoding {
        PCM,
        IEEE_FLOAT,
        UNSUPPORTED
    }

    private companion object {
        const val TARGET_SAMPLE_RATE = 44_100
        const val BYTES_PER_SAMPLE = 2
        const val WAV_HEADER_SIZE = 44
        const val MAX_CHANNELS = 8
        const val MIN_FMT_CHUNK_SIZE = 16L
        const val MAX_FMT_CHUNK_SIZE = 1024L
        const val EXTENSIBLE_FMT_SIZE = 40
        const val WAVE_FORMAT_PCM = 0x0001
        const val WAVE_FORMAT_IEEE_FLOAT = 0x0003
        const val WAVE_FORMAT_EXTENSIBLE = 0xFFFE
    }
}
