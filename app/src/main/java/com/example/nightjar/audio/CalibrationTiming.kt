package com.example.nightjar.audio

/** Immutable route compatibility. Device inventory IDs are deliberately not identity keys. */
data class CalibrationRouteKey(
    val inputIdentity: String,
    val outputIdentity: String,
    val inputRate: Int,
    val outputRate: Int,
    val inputChannels: Int,
    val outputChannels: Int,
    val inputBackend: Int,
    val outputBackend: Int,
    val inputBuffer: Int,
    val outputBuffer: Int,
    val inputPreset: Int,
    val audioMode: Int,
    val transportProfile: String,
    val mapperVersion: Int,
    val platformVersion: String
) {
    val identifiable: Boolean get() = inputIdentity.isNotBlank() && outputIdentity.isNotBlank()
}

data class CalibrationProfile(
    val revision: String,
    val route: CalibrationRouteKey,
    val correctionOutputFrames: Long,
    val inputEpoch: Long,
    val outputEpoch: Long,
    val accepted: Boolean,
    val createdAtEpochMs: Long,
    val sessionId: String
)

enum class CalibrationMethod { NONE, LEGACY, ESTIMATED, MEASURED, REUSED_MEASUREMENT }

data class CalibrationChoice(val profile: CalibrationProfile?, val method: CalibrationMethod,
                             val suggestRecheck: Boolean)

object CalibrationProfilePolicy {
    fun select(current: CalibrationRouteKey, inputEpoch: Long, outputEpoch: Long,
               profiles: List<CalibrationProfile>, failedRecheck: Boolean = false,
               sessionId: String = "", reopenValidated: Boolean = false): CalibrationChoice {
        if (!current.identifiable || failedRecheck) return CalibrationChoice(null, CalibrationMethod.ESTIMATED, true)
        val profile = profiles.filter { it.accepted && it.route == current }
            .maxByOrNull { it.createdAtEpochMs }
            ?: return CalibrationChoice(null, CalibrationMethod.ESTIMATED, true)
        val sameSession = sessionId.isNotBlank() && sessionId == profile.sessionId &&
            inputEpoch == profile.inputEpoch && outputEpoch == profile.outputEpoch
        val wireless = current.transportProfile in setOf("a2dp", "sco", "le")
        if (!sameSession && !wireless && !reopenValidated) {
            return CalibrationChoice(null, CalibrationMethod.ESTIMATED, true)
        }
        return CalibrationChoice(profile,
            if (sameSession) CalibrationMethod.MEASURED else CalibrationMethod.REUSED_MEASUREMENT,
            suggestRecheck = !sameSession)
    }
}

/** Future persistence contract; not used to reinterpret existing saved offsets or trims. */
data class CaptureTimingSnapshot(
    val renderOriginFrame: Long,
    val correctionFrames: Long,
    val manualAdjustmentFrames: Long,
    val method: CalibrationMethod,
    val profileRevision: String? = null
) {
    fun alignedFrame(sourceFrame: Long): Long = Math.addExact(
        Math.subtractExact(Math.addExact(renderOriginFrame, sourceFrame), correctionFrames),
        manualAdjustmentFrames)

    fun phase(sourceFrame: Long, backingOriginFrame: Long, loopFrames: Long): Long {
        require(loopFrames > 0)
        return Math.floorMod(Math.subtractExact(alignedFrame(sourceFrame), backingOriginFrame), loopFrames)
    }
}
