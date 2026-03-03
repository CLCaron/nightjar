package com.example.nightjar.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A placement of a [DrumPatternEntity] on the timeline.
 *
 * Multiple clips can reference the same pattern, each at a different
 * timeline position. This allows users to duplicate and arrange pattern
 * blocks on the timeline like audio clips.
 *
 * @property patternId Foreign key to the parent [DrumPatternEntity].
 * @property offsetMs  Timeline position in milliseconds where this clip starts.
 * @property sortIndex Ordering among clips of the same pattern (for display).
 */
@Entity(
    tableName = "drum_clips",
    foreignKeys = [ForeignKey(
        entity = DrumPatternEntity::class,
        parentColumns = ["id"],
        childColumns = ["patternId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("patternId")]
)
data class DrumClipEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val patternId: Long,
    val offsetMs: Long = 0L,
    val sortIndex: Int = 0
)
