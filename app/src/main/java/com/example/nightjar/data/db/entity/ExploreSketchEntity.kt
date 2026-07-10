package com.example.nightjar.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** A live, editable sketch lane for one section on one audio track. */
@Entity(
    tableName = "explore_sketches",
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
    indices = [
        Index("ideaId"),
        Index("sectionId"),
        Index("trackId"),
        Index(value = ["sectionId", "trackId"], unique = true)
    ]
)
data class ExploreSketchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val ideaId: Long,
    val sectionId: Long,
    val trackId: Long,
    val displayName: String,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)
