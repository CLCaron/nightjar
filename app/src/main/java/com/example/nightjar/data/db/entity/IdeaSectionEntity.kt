package com.example.nightjar.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** A named song region captured from the current Studio loop bounds. */
@Entity(
    tableName = "idea_sections",
    foreignKeys = [ForeignKey(
        entity = IdeaEntity::class,
        parentColumns = ["id"],
        childColumns = ["ideaId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("ideaId")]
)
data class IdeaSectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val ideaId: Long,
    val displayName: String,
    val startMs: Long,
    val endMs: Long,
    val colorIndex: Int = 0,
    val sortIndex: Int = 0,
    val createdAtEpochMs: Long = System.currentTimeMillis()
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
}
