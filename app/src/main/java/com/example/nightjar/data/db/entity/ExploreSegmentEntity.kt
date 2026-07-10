package com.example.nightjar.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** One accepted or experimental time range in a live sketch. */
@Entity(
    tableName = "explore_segments",
    foreignKeys = [ForeignKey(
        entity = ExploreSketchEntity::class,
        parentColumns = ["id"],
        childColumns = ["sketchId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("sketchId"), Index("selectedCandidateId")]
)
data class ExploreSegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val sketchId: Long,
    val startMs: Long,
    val endMs: Long,
    val status: String = ExploreSegmentStatus.KEEP,
    val selectedCandidateId: Long? = null,
    val sortIndex: Int = 0,
    val createdAtEpochMs: Long = System.currentTimeMillis()
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
}

object ExploreSegmentStatus {
    const val KEEP = "keep"
    const val TRY = "try"

    fun normalize(status: String): String =
        if (status == TRY) TRY else KEEP
}
