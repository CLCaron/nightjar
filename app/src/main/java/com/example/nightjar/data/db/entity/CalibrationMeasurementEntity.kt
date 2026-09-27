package com.example.nightjar.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Append-only diagnostic evidence. It is deliberately separate from accepted correction profiles. */
@Entity(tableName = "calibration_measurements", indices = [Index("routeFingerprint")])
data class CalibrationMeasurementEntity(
    @PrimaryKey val revision: String,
    val routeFingerprint: String,
    val routeConfiguration: String,
    val inputLabel: String,
    val outputLabel: String,
    val identityScope: String,
    val sessionId: String,
    val inputEpoch: Long,
    val outputEpoch: Long,
    val delayOutputFrames: Double,
    val outputRate: Int,
    val acceptedTrials: Int,
    val rejectedTrials: Int,
    val madFrames: Double,
    val rangeFrames: Int,
    val mapperUncertaintyMs: Double,
    val confirmationsPassed: Boolean,
    val validityReason: String,
    val probeVersion: Int,
    val mapperVersion: Int,
    val engineVersion: Int,
    val mapperEvidence: String,
    val trialEvidence: String,
    val createdAtEpochMs: Long
)
