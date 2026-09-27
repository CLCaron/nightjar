package com.example.nightjar.data.repository

import com.example.nightjar.audio.CalibrationRouteSnapshot
import com.example.nightjar.audio.AudioRouteEvidence
import com.example.nightjar.audio.AcousticLatencyDetector
import com.example.nightjar.data.db.NightjarDatabase
import com.example.nightjar.data.db.entity.CalibrationMeasurementEntity
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Diagnostic revisions cannot be selected by CalibrationProfilePolicy as a correction. */
@Singleton
class CalibrationMeasurementRepository @Inject constructor(database: NightjarDatabase) {
    private val dao = database.calibrationMeasurementDao()
    val latest = dao.latest()

    suspend fun save(route: CalibrationRouteSnapshot, streams: AudioRouteEvidence,
        result: AcousticLatencyDetector.Result, uncertaintyMs: Double, confirmed: Boolean,
        mapperEvidence: String, trialEvidence: String): CalibrationMeasurementEntity {
        require(result.delayFrames.isFinite() && result.delayFrames >= 0)
        require(uncertaintyMs.isFinite() && uncertaintyMs >= 0)
        require(streams.input.open && streams.output.open)
        require(streams.input.sampleRate == route.key.inputRate && streams.output.sampleRate == route.key.outputRate &&
            streams.input.channels == route.key.inputChannels && streams.output.channels == route.key.outputChannels)
        require(route.key.identifiable && streams.output.sampleRate > 0 && route.sessionId.isNotBlank())
        val measurement = CalibrationMeasurementEntity(
            revision = UUID.randomUUID().toString(), routeFingerprint = route.key.fingerprint(),
            routeConfiguration = route.key.encodedConfiguration(), inputLabel = route.inputLabel,
            outputLabel = route.outputLabel, identityScope = route.identityScope, sessionId = route.sessionId,
            inputEpoch = streams.input.epoch, outputEpoch = streams.output.epoch,
            delayOutputFrames = result.delayFrames, outputRate = streams.output.sampleRate,
            acceptedTrials = result.acceptedTrials, rejectedTrials = result.rejectedTrials,
            madFrames = result.madFrames, rangeFrames = result.rangeFrames,
            mapperUncertaintyMs = uncertaintyMs, confirmationsPassed = confirmed,
            validityReason = if (!confirmed) "CONFIRMATION_FAILED" else "CLOCK_VERIFICATION_PENDING",
            probeVersion = 1, mapperVersion = route.key.mapperVersion, engineVersion = route.key.engineVersion,
            mapperEvidence = mapperEvidence, trialEvidence = trialEvidence,
            createdAtEpochMs = System.currentTimeMillis())
        try {
            dao.insert(measurement)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            android.util.Log.w("CalibrationMeasurements", "Cannot save diagnostic revision", error)
            throw java.io.IOException("Could not save the timing check. You can still record.", error)
        }
        return measurement
    }
}
