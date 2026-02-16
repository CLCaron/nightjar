package com.example.nightjar.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/**
 * Low-level WAV recorder using [AudioRecord].
 *
 * Records 16-bit PCM, 44.1 kHz, mono and writes a standard WAV file.
 * Designed for overdub recording in the Explore screen where simultaneous
 * playback + recording is needed (MediaRecorder can't do this reliably).
 *
 * Thread-safe start/stop via [AtomicBoolean].
 */
class WavRecorder @Inject constructor() {

    private val isRecordingFlag = AtomicBoolean(false)

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private var outputFile: File? = null
    private var totalBytesWritten: Long = 0L

    fun start(file: File) {
        if (isRecordingFlag.getAndSet(true)) return

        outputFile = file
        totalBytesWritten = 0L

        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT
        ).coerceAtLeast(BUFFER_SIZE)

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize
        )

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            isRecordingFlag.set(false)
            throw IllegalStateException("AudioRecord failed to initialize")
        }

        audioRecord = recorder

        recorder.startRecording()

        recordingThread = Thread({
            val buffer = ByteArray(bufferSize)
            FileOutputStream(file).use { fos ->
                // Write placeholder WAV header (44 bytes)
                fos.write(ByteArray(WAV_HEADER_SIZE))

                while (isRecordingFlag.get()) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        fos.write(buffer, 0, read)
                        totalBytesWritten += read
                    }
                }
            }
        }, "WavRecorder-IO").also { it.start() }
    }

    fun stop(): WavRecordingResult? {
        if (!isRecordingFlag.getAndSet(false)) return null

        recordingThread?.join(STOP_TIMEOUT_MS)
        recordingThread = null

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        val file = outputFile ?: return null
        outputFile = null

        if (totalBytesWritten == 0L) {
            file.delete()
            return null
        }

        patchWavHeader(file, totalBytesWritten)

        val durationMs = totalBytesWritten * 1000L /
            (SAMPLE_RATE * CHANNELS * BYTES_PER_SAMPLE)

        return WavRecordingResult(file = file, durationMs = durationMs)
    }

    fun isActive(): Boolean = isRecordingFlag.get()

    fun release() {
        if (isRecordingFlag.getAndSet(false)) {
            recordingThread?.join(STOP_TIMEOUT_MS)
            recordingThread = null
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        }
    }

    private fun patchWavHeader(file: File, dataSize: Long) {
        RandomAccessFile(file, "rw").use { raf ->
            val totalFileSize = dataSize + WAV_HEADER_SIZE
            raf.seek(0)
            raf.write(buildWavHeader(dataSize, totalFileSize))
        }
    }

    private fun buildWavHeader(dataSize: Long, totalFileSize: Long): ByteArray {
        val byteRate = SAMPLE_RATE * CHANNELS * BYTES_PER_SAMPLE
        val blockAlign = CHANNELS * BYTES_PER_SAMPLE

        return ByteArray(WAV_HEADER_SIZE).apply {
            // RIFF chunk
            set(0, 'R'.code.toByte())
            set(1, 'I'.code.toByte())
            set(2, 'F'.code.toByte())
            set(3, 'F'.code.toByte())
            writeIntLE(this, 4, (totalFileSize - 8).toInt())
            set(8, 'W'.code.toByte())
            set(9, 'A'.code.toByte())
            set(10, 'V'.code.toByte())
            set(11, 'E'.code.toByte())

            // fmt sub-chunk
            set(12, 'f'.code.toByte())
            set(13, 'm'.code.toByte())
            set(14, 't'.code.toByte())
            set(15, ' '.code.toByte())
            writeIntLE(this, 16, 16) // sub-chunk size
            writeShortLE(this, 20, 1) // PCM format
            writeShortLE(this, 22, CHANNELS)
            writeIntLE(this, 24, SAMPLE_RATE)
            writeIntLE(this, 28, byteRate)
            writeShortLE(this, 32, blockAlign)
            writeShortLE(this, 34, BYTES_PER_SAMPLE * 8) // bits per sample

            // data sub-chunk
            set(36, 'd'.code.toByte())
            set(37, 'a'.code.toByte())
            set(38, 't'.code.toByte())
            set(39, 'a'.code.toByte())
            writeIntLE(this, 40, dataSize.toInt())
        }
    }

    private fun writeIntLE(arr: ByteArray, offset: Int, value: Int) {
        arr[offset] = (value and 0xFF).toByte()
        arr[offset + 1] = (value shr 8 and 0xFF).toByte()
        arr[offset + 2] = (value shr 16 and 0xFF).toByte()
        arr[offset + 3] = (value shr 24 and 0xFF).toByte()
    }

    private fun writeShortLE(arr: ByteArray, offset: Int, value: Int) {
        arr[offset] = (value and 0xFF).toByte()
        arr[offset + 1] = (value shr 8 and 0xFF).toByte()
    }

    companion object {
        private const val SAMPLE_RATE = 44100
        private const val CHANNELS = 1
        private const val BYTES_PER_SAMPLE = 2
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_SIZE = 4096
        private const val WAV_HEADER_SIZE = 44
        private const val STOP_TIMEOUT_MS = 2000L
    }
}

data class WavRecordingResult(val file: File, val durationMs: Long)
