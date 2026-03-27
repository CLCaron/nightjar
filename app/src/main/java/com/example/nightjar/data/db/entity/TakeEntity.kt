package com.example.nightjar.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A single audio take within an [AudioClipEntity].
 *
 * Each clip can have multiple takes (e.g. from loop recording or re-recording).
 * Exactly one take per clip has [isActive] = true; that take's audio is what
 * plays and what the clip's waveform displays. Inactive takes are alternatives
 * that can be activated by the user.
 *
 * @property clipId        Foreign key to the parent [AudioClipEntity].
 * @property audioFileName Filename of the WAV audio file in the recordings directory.
 * @property displayName   User-visible label (e.g. "Take 1", "Take 2").
 * @property sortIndex     Ordering within the clip (0 = first take).
 * @property durationMs    Total duration of the underlying audio file.
 * @property trimStartMs   Milliseconds trimmed from the start (non-destructive).
 * @property trimEndMs     Milliseconds trimmed from the end (non-destructive).
 * @property isActive      When true, this is the clip's currently selected take.
 * @property volume        Playback volume multiplier (0.0-1.0).
 */
@Entity(
    tableName = "takes",
    foreignKeys = [ForeignKey(
        entity = AudioClipEntity::class,
        parentColumns = ["id"],
        childColumns = ["clipId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("clipId")]
)
data class TakeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val clipId: Long,
    val audioFileName: String,
    val displayName: String,
    val sortIndex: Int,
    val durationMs: Long,
    val trimStartMs: Long = 0L,
    val trimEndMs: Long = 0L,
    val isActive: Boolean = true,
    val volume: Float = 1.0f,
    val createdAtEpochMs: Long = System.currentTimeMillis()
)
