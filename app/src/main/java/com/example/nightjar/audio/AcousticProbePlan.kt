package com.example.nightjar.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tanh
import kotlin.random.Random

/** Fixed 44.1 kHz test protocol. Two warmups, seven trials, two independent confirmations. */
internal object AcousticProbePlan {
    const val rate = 44100
    const val frames = 1764
    const val count = 11
    const val decimation = 4

    fun generate(): FloatArray {
        val result = FloatArray(frames * count)
        repeat(count) { probe ->
            val random = Random(9137 + probe * 997)
            val phases = DoubleArray(64) { random.nextDouble(0.0, 2 * PI) }
            val frequencies = DoubleArray(64) { random.nextDouble(900.0, 3300.0) }
            repeat(frames) { frame ->
                val fade = sin(PI * frame / (frames - 1)).let { it * it }
                val sample = phases.indices.sumOf { band ->
                    cos(2 * PI * frequencies[band] * frame / rate + phases[band])
                } / phases.size
                result[probe * frames + frame] = (0.09 * fade * sample).toFloat()
            }
            val peak = (0 until frames).maxOf { kotlin.math.abs(result[probe * frames + it]) }
            if (peak > 0f) repeat(frames) { frame -> result[probe * frames + frame] *= .09f / peak }
        }
        return result
    }

    fun reference(probes: FloatArray, index: Int): FloatArray =
        downsample(FloatArray(frames) { tanh(probes[index * frames + it]) })

    // Same averaging filter and phase for source and capture. Detection is off callback.
    fun downsample(samples: FloatArray): FloatArray = FloatArray(samples.size / decimation) { index ->
        (0 until decimation).sumOf { samples[index * decimation + it].toDouble() }
            .div(decimation).toFloat()
    }

    fun clippedFraction(samples: FloatArray, start: Int, length: Int): Double {
        require(start >= 0 && length > 0 && start <= samples.size - length)
        return (start until start + length).count { kotlin.math.abs(samples[it]) >= .999f }.toDouble() / length
    }
}
