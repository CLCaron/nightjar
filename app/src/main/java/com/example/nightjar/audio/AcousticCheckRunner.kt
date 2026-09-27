package com.example.nightjar.audio

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Owns temporary input only. It never creates a recording or changes compensation. */
@Singleton
class AcousticCheckRunner @Inject constructor(private val engine: OboeAudioEngine,
    private val focus: AcousticCheckFocus) {
    private val control = Mutex()
    data class Measurement(val delayMs: Double, val acceptedTrials: Int,
        val mapperUncertaintyMs: Double, val reliable: Boolean)

    suspend fun measure(): Measurement = control.withLock { withContext(Dispatchers.IO) {
        val probes = AcousticProbePlan.generate()
        check(engine.startAcousticCheck(probes)) { "Stop playback and recording before checking timing." }
        lateinit var evidence: AudioRouteEvidence
        var focusLease: AutoCloseable? = null
        try {
            val job = currentCoroutineContext()[Job]
            focusLease = focus.acquire { job?.cancel() }
            withTimeout(20_000) {
                check(engine.awaitFirstBuffer()) { "The microphone did not start. You can still record without a check." }
                evidence = engine.getStreamEvidence()
                check(evidence.input.sampleRate == 44100 && evidence.output.sampleRate == 44100 &&
                    evidence.input.channels == 1 && evidence.output.channels == 2) {
                    "This audio route cannot run this timing check. Recording is still available."
                }
                delay(500)
                engine.armAcousticCheck()
                while (true) {
                    val progress = engine.acousticCheckProgress()
                    val route = engine.getStreamEvidence()
                    check(progress.size == 3 && progress[1] == 0L && route.input.open && route.output.open &&
                        route.input.epoch == evidence.input.epoch && route.output.epoch == evidence.output.epoch &&
                        route.outputInterruptions == evidence.outputInterruptions) {
                        "The audio route changed during the check. Reconnect and try again."
                    }
                    if (progress[0] == 1L) break
                    delay(25)
                }
            }
        } finally {
            withContext(NonCancellable) {
                try {
                    check(engine.stopAcousticCheck()) { "The timing check did not release audio cleanly." }
                } finally {
                    focusLease?.close()
                }
            }
        }
        val capture = AcousticProbePlan.downsample(engine.acousticCheckSamples())
        val history = engine.acousticCheckEvidence()
        check(history.size >= 14) { "Timing evidence is unavailable. Try the check again." }
        val inputRows = history[1].toInt()
        val outputRows = history[2].toInt()
        check(inputRows > 10 && outputRows > 10 && history.size == 14 + 3 * (inputRows + outputRows)) {
            "Timing evidence is incomplete. Try the check again."
        }
        // Use the established software delivery/submission convention. A physical-device
        // proof is still required before this measurement can replace recording compensation.
        fun offset(start: Int, rows: Int, input: Boolean): Double {
            val values = DoubleArray(rows) { row ->
                val at = start + row * 3
                history[at + 1] * 44100.0 / 1e9 - history[at] - if (input) history[at + 2].toDouble() else 0.0
            }.sorted()
            return values[values.size / 2]
        }
        val inputOffset = offset(14, inputRows, true)
        val outputOffset = offset(14 + inputRows * 3, outputRows, false)
        val trials = (0 until AcousticProbePlan.count).map { probe ->
            currentCoroutineContext().ensureActive()
            val emitted = history[3 + probe]
            check(emitted >= 0) { "A test sound was interrupted. Try again." }
            val expectedInput = emitted + outputOffset - inputOffset
            val from = ((expectedInput - history[0]) / 4).roundToInt().coerceAtLeast(0)
            val reference = AcousticProbePlan.reference(probes, probe)
            val to = minOf(from + 11025, capture.size - reference.size)
            check(to >= from) { "The check ended before all test sounds were captured." }
            AcousticLatencyDetector.detect(reference, capture, from, to)?.let { trial ->
                trial.copy(delayFrames = (trial.delayFrames * 4 + history[0] - expectedInput).roundToInt())
            }
        }
        val scored = trials.subList(2, 9)
        val uncertainty = CallbackClockMapper.estimate(evidence.input, evidence.output)?.uncertaintyMs
            ?: Double.POSITIVE_INFINITY
        // Report a diagnostic delay even if the conservative clock bound rejects correction.
        val combined = AcousticLatencyDetector.combine(scored, 44100, 0.0)
            ?: error("The sounds were not clear or consistent enough. Move the output closer to the selected microphone and try again.")
        val confirmed = trials.takeLast(2).all { trial -> trial != null && trial.correlation >= .65 &&
            trial.peakRatio >= 1.5 && trial.signalToNoiseDb >= 12 && trial.clippedFraction < .001 &&
            kotlin.math.abs(trial.delayFrames - combined.delayFrames) <= 441 }
        Log.i("AcousticCheck", "Detected ${combined.acceptedTrials} trials; mapper bound=$uncertainty ms; confirmed=$confirmed")
        Measurement(combined.delayFrames / 44.1, combined.acceptedTrials, uncertainty,
            confirmed && uncertainty <= 3)
    } }
}
