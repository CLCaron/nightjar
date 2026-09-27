package com.example.nightjar.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.nightjar.data.db.entity.CalibrationMeasurementEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CalibrationMeasurementDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(measurement: CalibrationMeasurementEntity)

    @Query("SELECT * FROM calibration_measurements ORDER BY createdAtEpochMs DESC, revision DESC LIMIT 1")
    fun latest(): Flow<CalibrationMeasurementEntity?>

    @Query("SELECT * FROM calibration_measurements WHERE routeFingerprint = :fingerprint ORDER BY createdAtEpochMs DESC, revision DESC LIMIT 1")
    suspend fun latestForRoute(fingerprint: String): CalibrationMeasurementEntity?
}
