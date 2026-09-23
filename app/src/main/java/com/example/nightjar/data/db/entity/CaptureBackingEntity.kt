package com.example.nightjar.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** Immutable context for a take recorded while a source loop was audible. */
@Entity(
    tableName = "capture_backings",
    primaryKeys = ["takeId", "backingTakeId"],
    foreignKeys = [ForeignKey(
        entity = TakeEntity::class,
        parentColumns = ["id"],
        childColumns = ["takeId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("backingTakeId")]
)
data class CaptureBackingEntity(
    val takeId: Long,
    val backingTakeId: Long,
    val backingLoopFrames: Long,
    val transportStartFrame: Long,
    val correctionFrames: Long,
    val sourcePhaseFrame: Long,
    val syncMethod: String
)
