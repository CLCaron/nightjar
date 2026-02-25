package com.example.nightjar.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Estimates audio pipeline latency for overdub compensation.
 *
 * **Tier 1 (auto-estimate)**: Uses [AudioManager] properties and
 * [AudioRecord] buffer sizes to estimate output and input pipeline
 * latency. Detects Bluetooth output and applies additional codec
 * latency. Applied automatically on every overdub.
 *
 * **Manual offset**: Users can apply an additional offset (±500ms) via
 * a slider in the Audio Sync dialog to fine-tune alignment for their
 * specific device and output configuration. Persisted in SharedPreferences.
 *
 * Compensation values are persisted in SharedPreferences per device.
 */
@Singleton
class AudioLatencyEstimator @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val audioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    // ── Device detection ─────────────────────────────────────────────────

    /** Detected output device category. */
    enum class OutputDeviceType(val displayName: String) {
        BUILTIN_SPEAKER("Built-in speaker"),
        WIRED_HEADPHONES("Wired headphones"),
        USB_AUDIO("USB audio"),
        BLUETOOTH_A2DP("Bluetooth headphones"),
        UNKNOWN("Unknown device")
    }

    /**
     * Queries [AudioManager] for the current output device type.
     *
     * Priority: Bluetooth > USB > Wired > Built-in speaker > Unknown.
     * Available since API 23 (our min SDK is 24).
     */
    fun detectOutputDeviceType(): OutputDeviceType {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        // Priority scan — return the highest-latency device found, since
        // that's the one most likely to be the active output route.
        for (device in devices) {
            when (device.type) {
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_BLE_HEADSET,  // BT LE Audio (API 31+)
                AudioDeviceInfo.TYPE_BLE_SPEAKER,  // BT LE Audio (API 31+)
                AudioDeviceInfo.TYPE_HEARING_AID   // BT-based (API 28+)
                    -> return OutputDeviceType.BLUETOOTH_A2DP
            }
        }

        // Fallback: check audio routing flags in case getDevices() missed
        // the BT device (observed on some OEMs). These methods are
        // deprecated in API 31 but still functional.
        @Suppress("DEPRECATION")
        if (audioManager.isBluetoothA2dpOn || audioManager.isBluetoothScoOn) {
            Log.d(TAG, "BT detected via AudioManager routing flag (not in getDevices)")
            return OutputDeviceType.BLUETOOTH_A2DP
        }

        for (device in devices) {
            when (device.type) {
                AudioDeviceInfo.TYPE_USB_DEVICE,
                AudioDeviceInfo.TYPE_USB_HEADSET ->
                    return OutputDeviceType.USB_AUDIO
            }
        }
        for (device in devices) {
            when (device.type) {
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_WIRED_HEADSET ->
                    return OutputDeviceType.WIRED_HEADPHONES
            }
        }
        for (device in devices) {
            when (device.type) {
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                AudioDeviceInfo.TYPE_BUILTIN_EARPIECE ->
                    return OutputDeviceType.BUILTIN_SPEAKER
            }
        }
        return OutputDeviceType.UNKNOWN
    }

    // ── Latency estimation ───────────────────────────────────────────────

    /**
     * Estimates the output pipeline latency (speaker/earphone side).
     *
     * Uses the device's native frames-per-buffer and sample rate from
     * [AudioManager]. For Bluetooth A2DP, adds codec latency on top of
     * the local buffer estimate.
     */
    fun estimateOutputLatencyMs(
        deviceType: OutputDeviceType = detectOutputDeviceType()
    ): Long {
        val framesPerBuffer = audioManager
            .getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)
            ?.toLongOrNull()
        val nativeSampleRate = audioManager
            .getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
            ?.toLongOrNull()

        val baseEstimateMs = if (framesPerBuffer == null || nativeSampleRate == null ||
            nativeSampleRate == 0L
        ) {
            Log.w(TAG, "AudioManager properties unavailable, using fallback output latency")
            FALLBACK_OUTPUT_MS
        } else {
            // Two buffer periods is a reasonable estimate for the output pipeline
            (framesPerBuffer * 2 * 1000) / nativeSampleRate
        }

        return if (deviceType == OutputDeviceType.BLUETOOTH_A2DP) {
            val btEstimate = baseEstimateMs + BT_CODEC_LATENCY_MS
            val clamped = btEstimate.coerceIn(MIN_BT_OUTPUT_MS, MAX_BT_OUTPUT_MS)
            Log.d(TAG, "BT output latency estimate: ${btEstimate}ms (clamped: ${clamped}ms) " +
                    "[base=${baseEstimateMs}ms, btCodec=${BT_CODEC_LATENCY_MS}ms]")
            clamped
        } else {
            val clamped = baseEstimateMs.coerceIn(MIN_OUTPUT_MS, MAX_OUTPUT_MS)
            Log.d(TAG, "Output latency estimate: ${baseEstimateMs}ms (clamped: ${clamped}ms) " +
                    "[framesPerBuffer=$framesPerBuffer, sampleRate=$nativeSampleRate]")
            clamped
        }
    }

    /**
     * Estimates the input pipeline latency (microphone side).
     *
     * Uses [AudioRecord.getMinBufferSize] to gauge how much audio the
     * input pipeline buffers before delivering to the app.
     */
    fun estimateInputLatencyMs(): Long {
        val minBufferBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        if (minBufferBytes <= 0) {
            Log.w(TAG, "AudioRecord.getMinBufferSize returned $minBufferBytes, using fallback")
            return FALLBACK_INPUT_MS
        }

        // bytes → duration: bytes / (sampleRate * bytesPerSample)
        val estimateMs = (minBufferBytes * 1000L) / (SAMPLE_RATE * BYTES_PER_SAMPLE)
        val clamped = estimateMs.coerceIn(MIN_INPUT_MS, MAX_INPUT_MS)
        Log.d(TAG, "Input latency estimate: ${estimateMs}ms (clamped: ${clamped}ms) " +
                "[minBufferBytes=$minBufferBytes]")
        return clamped
    }

    /**
     * Computes the total compensation to apply as `trimStartMs` on an
     * overdub track.
     *
     * Output latency is always applied when [hasPlayableTracks] is true.
     * The output estimate is the dominant latency component (especially
     * for Bluetooth at ~150-300ms) and must not be gated behind a
     * rendering confirmation — Android's audio pipeline introduces this
     * delay regardless of when ExoPlayer reports its playing state.
     *
     * @param preRollMs milliseconds of audio captured before playback
     *   started (the "safety buffer")
     * @param hasPlayableTracks true if there are non-muted tracks that
     *   will play during this recording session
     */
    fun computeCompensationMs(preRollMs: Long, hasPlayableTracks: Boolean): Long {
        val deviceType = detectOutputDeviceType()
        val outputMs = if (hasPlayableTracks) estimateOutputLatencyMs(deviceType) else 0L
        val inputMs = estimateInputLatencyMs()
        val manualOffset = getManualOffsetMs()

        // Manual offset uses DAW convention: negative = shift recording earlier.
        // Subtracting it increases compensation when the user enters a negative value.
        val compensation = preRollMs + outputMs + inputMs - manualOffset

        Log.d(TAG, "Compensation: ${compensation}ms " +
                "[preRoll=${preRollMs}ms, output=${outputMs}ms, input=${inputMs}ms, " +
                "manualOffset=${manualOffset}ms, device=${deviceType.displayName}, " +
                "hasPlayableTracks=$hasPlayableTracks]")
        return compensation.coerceAtLeast(0L)
    }

    // ── Manual offset persistence ────────────────────────────────────────

    /** Returns the user-configured manual offset in ms (default 0). */
    fun getManualOffsetMs(): Long =
        prefs.getLong(KEY_MANUAL_OFFSET, 0L)

    /**
     * Saves a manual offset, clamped to ±500ms.
     * Negative = shift recording earlier (if overdubs sound late).
     * Positive = shift recording later (if overdubs sound early).
     */
    fun saveManualOffsetMs(offsetMs: Long) {
        val clamped = offsetMs.coerceIn(MIN_MANUAL_OFFSET_MS, MAX_MANUAL_OFFSET_MS)
        prefs.edit().putLong(KEY_MANUAL_OFFSET, clamped).apply()
        Log.d(TAG, "Saved manual offset: ${clamped}ms")
    }

    /** Removes the manual offset (resets to 0). */
    fun clearManualOffset() {
        prefs.edit().remove(KEY_MANUAL_OFFSET).apply()
        Log.d(TAG, "Manual offset cleared")
    }

    // ── Diagnostics ──────────────────────────────────────────────────────

    /** Snapshot of current latency estimation values for the UI. */
    data class LatencyDiagnostics(
        val deviceType: OutputDeviceType,
        val estimatedOutputMs: Long,
        val estimatedInputMs: Long,
        val manualOffsetMs: Long,
        val totalAutoEstimateMs: Long
    )

    /** Builds a diagnostics snapshot for display in the Audio Sync dialog. */
    fun getDiagnostics(): LatencyDiagnostics {
        val deviceType = detectOutputDeviceType()
        val outputMs = estimateOutputLatencyMs(deviceType)
        val inputMs = estimateInputLatencyMs()
        val manualOffset = getManualOffsetMs()
        return LatencyDiagnostics(
            deviceType = deviceType,
            estimatedOutputMs = outputMs,
            estimatedInputMs = inputMs,
            manualOffsetMs = manualOffset,
            totalAutoEstimateMs = outputMs + inputMs
        )
    }

    companion object {
        private const val TAG = "AudioLatencyEst"
        private const val PREFS_NAME = "nightjar_audio_latency"
        private const val KEY_MANUAL_OFFSET = "manual_offset_ms"

        private const val SAMPLE_RATE = 44100
        private const val BYTES_PER_SAMPLE = 2

        // Non-BT output clamp range
        private const val FALLBACK_OUTPUT_MS = 40L
        private const val FALLBACK_INPUT_MS = 25L
        private const val MIN_OUTPUT_MS = 10L
        private const val MAX_OUTPUT_MS = 100L
        private const val MIN_INPUT_MS = 5L
        private const val MAX_INPUT_MS = 80L

        // Bluetooth A2DP additions — real-world BT latency varies widely
        // by codec: aptX ~80ms, AAC ~150ms, SBC ~200-400ms+. 250ms is a
        // middle-ground default that minimizes manual offset for most users.
        private const val BT_CODEC_LATENCY_MS = 250L
        private const val MIN_BT_OUTPUT_MS = 150L
        private const val MAX_BT_OUTPUT_MS = 500L

        // Manual offset bounds
        private const val MIN_MANUAL_OFFSET_MS = -500L
        private const val MAX_MANUAL_OFFSET_MS = 500L
    }
}
