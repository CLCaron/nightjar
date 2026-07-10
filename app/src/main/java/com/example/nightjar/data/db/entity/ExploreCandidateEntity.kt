package com.example.nightjar.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** A candidate source range for a sketch segment. */
@Entity(
    tableName = "explore_candidates",
    foreignKeys = [
        ForeignKey(
            entity = ExploreSegmentEntity::class,
            parentColumns = ["id"],
            childColumns = ["segmentId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = ExploreCaptureEntity::class,
            parentColumns = ["id"],
            childColumns = ["captureId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("segmentId"), Index("captureId")]
)
data class ExploreCandidateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val segmentId: Long,
    val captureId: Long,
    val sourceStartMs: Long,
    val sourceEndMs: Long,
    val displayName: String,
    val sortIndex: Int = 0,
    val createdAtEpochMs: Long = System.currentTimeMillis()
) {
    val durationMs: Long get() = (sourceEndMs - sourceStartMs).coerceAtLeast(0L)
}
