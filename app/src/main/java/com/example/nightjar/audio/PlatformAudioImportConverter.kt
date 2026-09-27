package com.example.nightjar.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.IOException
import java.nio.ByteOrder
import kotlinx.coroutines.CancellationException

/** Offline only. Native playback and microphone callbacks never run decoder work. */
object PlatformAudioImportConverter {
    fun convert(source: File, output: File, checkCancelled: () -> Unit = {}): Long {
        checkCancelled()
        val header = ByteArray(12)
        val length = source.inputStream().use { input ->
            var count = 0
            while (count < header.size) {
                val read = input.read(header, count, header.size - count)
                if (read < 0) break
                count += read
            }
            count
        }
        if (length == 12 && header.copyOfRange(0, 4).contentEquals("RIFF".toByteArray()) &&
            header.copyOfRange(8, 12).contentEquals("WAVE".toByteArray())) {
            return WavImportConverter.convert(source, output, checkCancelled)
        }
        val decoded = File.createTempFile("decoded_import_", ".wav", output.parentFile)
        try {
            decode(source, decoded, checkCancelled)
            return WavImportConverter.convert(decoded, output, checkCancelled)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.e("AudioImportConverter", "Audio conversion failed", error)
            if (error is AudioImportException) throw error
            throw AudioImportException("Could not convert this audio. It may be damaged or unsupported on this phone. Check available storage and try again.", error)
        } finally {
            if (decoded.exists() && !decoded.delete()) {
                Log.e("AudioImportConverter", "Could not remove temporary decoded audio")
            }
        }
    }

    private fun decode(source: File, output: File, checkCancelled: () -> Unit) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var writer: PcmWavWriter? = null
        try {
            extractor.setDataSource(source.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw AudioImportException("This file does not contain a supported audio track.")
            extractor.selectTrack(track)
            val inputFormat = extractor.getTrackFormat(track)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: throw AudioImportException("This file's audio format could not be identified.")
            // For raw PCM this key also describes INPUT bytes, so never overwrite it.
            // For compressed input request PCM16, then inspect the actual output below.
            if (mime != MediaFormat.MIMETYPE_AUDIO_RAW) {
                inputFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            }
            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()
            val info = MediaCodec.BufferInfo()
            var inputEnded = false
            var outputEnded = false
            var lastProgress = SystemClock.elapsedRealtime()

            fun acceptFormat(format: MediaFormat) {
                val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                val encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING))
                    format.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                if (encoding != AudioFormat.ENCODING_PCM_16BIT && encoding != AudioFormat.ENCODING_PCM_FLOAT) {
                    throw AudioImportException("This phone returned an unsupported decoded audio format.")
                }
                check(ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN)
                val current = writer
                if (current == null) writer = PcmWavWriter(output, rate, channels, encoding == AudioFormat.ENCODING_PCM_FLOAT)
                else if (current.sampleRate != rate || current.channels != channels ||
                    current.floatSamples != (encoding == AudioFormat.ENCODING_PCM_FLOAT)) {
                    throw AudioImportException("This file changes audio format midway through the song.")
                }
            }

            while (!outputEnded) {
                checkCancelled()
                if (!inputEnded) {
                    val index = decoder.dequeueInputBuffer(10000)
                    if (index >= 0) {
                        val input = checkNotNull(decoder.getInputBuffer(index)).apply { clear() }
                        val size = extractor.readSampleData(input, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED != 0) {
                                throw AudioImportException("Protected audio cannot be imported. Ask for an unprotected copy.")
                            }
                            decoder.queueInputBuffer(index, 0, size, extractor.sampleTime.coerceAtLeast(0), 0)
                            extractor.advance()
                        }
                        lastProgress = SystemClock.elapsedRealtime()
                    }
                }
                val index = decoder.dequeueOutputBuffer(info, 10000)
                when {
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        acceptFormat(decoder.outputFormat)
                    }
                    index >= 0 -> {
                        try {
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                acceptFormat(decoder.getOutputFormat(index))
                                writer!!.append(checkNotNull(decoder.getOutputBuffer(index)), info.offset, info.size)
                                lastProgress = SystemClock.elapsedRealtime()
                            }
                            outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        } finally {
                            decoder.releaseOutputBuffer(index, false)
                        }
                    }
                }
                if (SystemClock.elapsedRealtime() - lastProgress > 10000) {
                    throw AudioImportException("Audio decoding stopped responding. Try another copy of this file.")
                }
            }
            checkCancelled()
            (writer ?: throw AudioImportException("This file contains no decodable audio.")).finish()
        } finally {
            try {
                writer?.close()
            } finally {
                try {
                    codec?.release()
                } finally {
                    extractor.release()
                }
            }
        }
    }

    private class AudioImportException(message: String, cause: Throwable? = null) : IOException(message, cause)
}
