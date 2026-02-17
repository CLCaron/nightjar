package com.example.nightjar.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max

/**
 * Decodes an audio file to PCM and returns a normalized amplitude list
 * suitable for waveform rendering.  Runs entirely on [Dispatchers.IO].
 *
 * @param file   The audio file (M4A/AAC or any format supported by MediaCodec).
 * @param bars   The desired number of amplitude bars in the output.
 * @return       A [FloatArray] of size [bars] with values in 0f..1f,
 *               or an empty array if decoding fails.
 */
suspend fun extractWaveform(file: File, bars: Int = 120): FloatArray =
    withContext(Dispatchers.IO) {
        if (!file.exists() || bars <= 0) return@withContext FloatArray(0)

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val (trackIndex, format) = selectAudioTrack(extractor)
                ?: return@withContext FloatArray(0)

            extractor.selectTrack(trackIndex)
            decodeToBars(extractor, format, bars)
        } catch (_: Exception) {
            FloatArray(0)
        } finally {
            extractor.release()
        }
    }

private fun selectAudioTrack(extractor: MediaExtractor): Pair<Int, MediaFormat>? {
    for (i in 0 until extractor.trackCount) {
        val fmt = extractor.getTrackFormat(i)
        val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
        if (mime.startsWith("audio/")) return i to fmt
    }
    return null
}

private suspend fun decodeToBars(
    extractor: MediaExtractor,
    format: MediaFormat,
    bars: Int
): FloatArray = withContext(Dispatchers.IO) {

    val mime = format.getString(MediaFormat.KEY_MIME)!!
    val codec = MediaCodec.createDecoderByType(mime)

    try {
        codec.configure(format, null, null, 0)
        codec.start()

        val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1

        // Collect peak amplitudes per sample frame, then bucket into bars.
        val peaks = mutableListOf<Float>()
        val info = MediaCodec.BufferInfo()
        var inputDone = false

        while (true) {
            ensureActive()

            // Feed input buffers.
            if (!inputDone) {
                val inIdx = codec.dequeueInputBuffer(5_000)
                if (inIdx >= 0) {
                    val buf = codec.getInputBuffer(inIdx)!!
                    val read = extractor.readSampleData(buf, 0)
                    if (read < 0) {
                        codec.queueInputBuffer(
                            inIdx, 0, 0, 0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIdx, 0, read, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            // Drain output buffers.
            val outIdx = codec.dequeueOutputBuffer(info, 5_000)
            if (outIdx >= 0) {
                val outBuf = codec.getOutputBuffer(outIdx)
                if (outBuf != null && info.size > 0) {
                    collectPeaks(outBuf, info.size, channels, peaks)
                }
                codec.releaseOutputBuffer(outIdx, false)

                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        }

        bucketize(peaks, bars)
    } finally {
        codec.stop()
        codec.release()
    }
}

/**
 * Reads 16-bit PCM samples from [buffer], takes the absolute peak across
 * channels for each sample frame, and appends to [peaks] as 0f..1f.
 */
private fun collectPeaks(
    buffer: ByteBuffer,
    size: Int,
    channels: Int,
    peaks: MutableList<Float>
) {
    buffer.order(ByteOrder.LITTLE_ENDIAN)
    buffer.rewind()
    val shortCount = size / 2
    val frameCount = shortCount / channels

    for (f in 0 until frameCount) {
        var peak = 0
        for (c in 0 until channels) {
            val sample = abs(buffer.short.toInt())
            peak = max(peak, sample)
        }
        peaks.add(peak / 32768f)
    }
}

/**
 * Downsamples [peaks] into [bars] buckets by taking the max in each bucket.
 * Normalizes the result so the loudest bucket = 1f.
 */
private fun bucketize(peaks: List<Float>, bars: Int): FloatArray {
    if (peaks.isEmpty()) return FloatArray(bars) { 0f }

    val result = FloatArray(bars)
    val samplesPerBar = peaks.size.toFloat() / bars

    for (i in 0 until bars) {
        val start = (i * samplesPerBar).toInt()
        val end = ((i + 1) * samplesPerBar).toInt().coerceAtMost(peaks.size)
        var max = 0f
        for (j in start until end) {
            if (peaks[j] > max) max = peaks[j]
        }
        result[i] = max
    }

    // Normalize to 0..1 so quiet recordings still look decent.
    val globalMax = result.max()
    if (globalMax > 0f) {
        for (i in result.indices) result[i] = result[i] / globalMax
    }

    return result
}
