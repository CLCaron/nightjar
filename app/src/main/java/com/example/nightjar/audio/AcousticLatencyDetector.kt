package com.example.nightjar.audio

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** Off-callback matched-filter detector. Its results require route/clock/device proof. */
object AcousticLatencyDetector {
    data class Trial(val delayFrames: Int, val correlation: Double, val peakRatio: Double,
                     val signalToNoiseDb: Double, val clippedFraction: Double)
    data class Result(val delayFrames: Double, val madFrames: Double, val rangeFrames: Int,
                      val acceptedTrials: Int, val rejectedTrials: Int)

    fun detect(reference: FloatArray, capture: FloatArray, from: Int, to: Int): Trial? {
        require(reference.size >= 8 && from >= 0 && to >= from && to + reference.size <= capture.size)
        if (reference.any { !it.isFinite() } || capture.any { !it.isFinite() }) return null
        val mean = reference.average()
        val centered = DoubleArray(reference.size) { reference[it] - mean }
        val refEnergy = centered.sumOf { it * it }
        if (refEnergy <= 1e-12) return null
        val scores = DoubleArray(to - from + 1)
        var best = 0
        for (lag in from..to) {
            var sum = 0.0
            var sumSq = 0.0
            var dot = 0.0
            for (i in reference.indices) {
                val sample = capture[lag + i].toDouble()
                sum += sample
                sumSq += sample * sample
                dot += centered[i] * sample
            }
            val energy = max(0.0, sumSq - sum * sum / reference.size)
            scores[lag - from] = if (energy > 1e-12) abs(dot) / sqrt(refEnergy * energy) else 0.0
            if (scores[lag - from] > scores[best]) best = lag - from
        }
        // Ignore the reference main-lobe neighborhood; separated echoes remain competitors.
        val guard = max(2, reference.size / 20)
        val competitor = scores.indices.filter { abs(it - best) > guard }.maxOfOrNull { scores[it] } ?: 0.0
        val offset = from + best
        var signal = 0.0
        var clipped = 0
        for (i in reference.indices) {
            val sample = capture[offset + i].toDouble()
            signal += sample * sample
            if (abs(sample) >= 0.999) clipped++
        }
        val noiseCount = minOf(from, reference.size)
        val noise = if (noiseCount > 0) (from - noiseCount until from).sumOf {
            capture[it].toDouble() * capture[it]
        } / noiseCount else 0.0
        val snr = 10 * kotlin.math.log10(max(signal / reference.size, 1e-12) / max(noise, 1e-12))
        return Trial(offset, scores[best], scores[best] / max(competitor, 1e-9), snr,
            clipped.toDouble() / reference.size)
    }

    fun combine(trials: List<Trial?>, sampleRate: Int, mapperUncertaintyMs: Double): Result? {
        require(sampleRate > 0)
        if (!mapperUncertaintyMs.isFinite() || mapperUncertaintyMs < 0 || mapperUncertaintyMs > 3) return null
        val valid = trials.filterNotNull().filter {
            it.correlation.isFinite() && it.peakRatio.isFinite() && it.signalToNoiseDb.isFinite() &&
                it.clippedFraction.isFinite() && it.correlation >= 0.65 && it.peakRatio >= 1.5 &&
                it.signalToNoiseDb >= 12 && it.clippedFraction in 0.0..<0.001 && it.delayFrames >= 0
        }
        if (valid.size < 5) return null
        val median = median(valid.map { it.delayFrames.toDouble() })
        val mad = median(valid.map { abs(it.delayFrames - median) })
        val limit = max(3 * mad, sampleRate * 0.005)
        val accepted = valid.filter { abs(it.delayFrames - median) <= limit }
        // Reject a second coherent cluster rather than concealing a changing path.
        val outliers = valid.filterNot { it in accepted }.sortedBy { it.delayFrames }
        if (outliers.zipWithNext().any { (a, b) -> abs(a.delayFrames - b.delayFrames) <= sampleRate * 0.005 }) return null
        if (accepted.size < 5) return null
        val delays = accepted.map { it.delayFrames.toDouble() }
        val finalMedian = median(delays)
        val finalMad = median(delays.map { abs(it - finalMedian) })
        val range = accepted.maxOf { it.delayFrames } - accepted.minOf { it.delayFrames }
        if (range > sampleRate * 0.010 || finalMad > sampleRate * 0.003) return null
        return Result(finalMedian, finalMad, range, accepted.size, trials.size - accepted.size)
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        return (sorted[(sorted.size - 1) / 2] + sorted[sorted.size / 2]) / 2
    }
}
