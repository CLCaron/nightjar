package com.example.nightjar.audio

/** Software callback evidence only. None of these fields prove acoustic alignment. */
data class AudioStreamEvidence(
    val open: Boolean = false,
    val deviceId: Int = 0,
    val sampleRate: Int = 0,
    val channels: Int = 0,
    val backend: Int = 0,
    val format: Int = 0,
    val sharingMode: Int = 0,
    val performanceMode: Int = 0,
    val burstFrames: Int = 0,
    val bufferFrames: Int = 0,
    val inputPreset: Int = 0,
    val callbackFrame: Long = 0,
    val callbackNanos: Long = 0,
    val callbackFrames: Int = 0,
    val timelineFrame: Long = 0,
    val droppedFrames: Long = 0,
    val epoch: Long = 0
) {
    val hasAnchor: Boolean get() = open && callbackNanos > 0 && sampleRate > 0 && callbackFrames > 0
}

data class AudioRouteEvidence(
    val input: AudioStreamEvidence = AudioStreamEvidence(),
    val output: AudioStreamEvidence = AudioStreamEvidence(),
    val outputInterruptions: Long = 0
) {
    companion object {
        fun decode(values: LongArray): AudioRouteEvidence {
            if (values.size != 35) return AudioRouteEvidence()
            fun stream(at: Int, epoch: Long) = AudioStreamEvidence(
                open = values[at] == 1L, deviceId = values[at + 1].toInt(),
                sampleRate = values[at + 2].toInt(), channels = values[at + 3].toInt(),
                backend = values[at + 4].toInt(), format = values[at + 5].toInt(),
                sharingMode = values[at + 6].toInt(), performanceMode = values[at + 7].toInt(),
                burstFrames = values[at + 8].toInt(), bufferFrames = values[at + 9].toInt(),
                inputPreset = values[at + 10].toInt(), callbackFrame = values[at + 11],
                callbackNanos = values[at + 12], callbackFrames = values[at + 13].toInt(),
                timelineFrame = values[at + 14], droppedFrames = values[at + 15], epoch = epoch
            )
            return AudioRouteEvidence(stream(0, values[32]), stream(16, values[33]), values[34])
        }
    }
}

/** Never assigns an acoustic-time origin to independently zeroed stream counters. */
object CallbackClockMapper {
    data class Estimate(val inputToOutputFrames: Double, val uncertaintyMs: Double)

    fun estimate(input: AudioStreamEvidence, output: AudioStreamEvidence): Estimate? {
        if (!input.hasAnchor || !output.hasAnchor) return null
        if (kotlin.math.abs(input.callbackNanos - output.callbackNanos) > 100_000_000) return null
        // Input callback delivers a completed block; output callback submits a new block.
        // A one-block uncertainty on each side is conservative. It is NOT device proof.
        val ratio = output.sampleRate.toDouble() / input.sampleRate
        val inputBlockStartNanos = input.callbackNanos - input.callbackFrames * 1e9 / input.sampleRate
        val outputAtInput = output.callbackFrame +
            (inputBlockStartNanos - output.callbackNanos) * output.sampleRate / 1e9
        val offset = outputAtInput - input.callbackFrame * ratio
        val uncertainty = 1000.0 * input.callbackFrames / input.sampleRate +
            1000.0 * output.callbackFrames / output.sampleRate
        return Estimate(offset, uncertainty)
    }
}
