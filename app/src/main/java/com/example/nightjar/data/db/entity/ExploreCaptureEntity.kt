package com.example.nightjar.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Source audio recorded while exploring a section or a TRY region. */
@Entity(
    tableName = "explore_captures",
    foreignKeys = [
        ForeignKey(
            entity = IdeaEntity::class,
            parentColumns = ["id"],
            childColumns = ["ideaId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = IdeaSectionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sectionId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("ideaId"), Index("sectionId"), Index("trackId")]
)
data class ExploreCaptureEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val ideaId: Long,
    val sectionId: Long,
    val trackId: Long,
    val audioFileName: String,
    val displayName: String,
    val durationMs: Long,
    val capturedStartMs: Long,
    val capturedEndMs: Long,
    val trimStartMs: Long = 0L,
    val createdAtEpochMs: Long = System.currentTimeMillis()
)
