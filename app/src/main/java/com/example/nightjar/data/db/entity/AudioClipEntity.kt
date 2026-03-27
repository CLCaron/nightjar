package com.example.nightjar.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * An arrangeable audio clip on the timeline.
 *
 * Each audio track has one or more clips. Clips own their timeline position
 * (`offsetMs`) and contain one or more [TakeEntity] takes, exactly one of
 * which is active at a time. The clip's effective duration is the active
 * take's `durationMs - trimStartMs - trimEndMs`.
 *
 * @property trackId      Foreign key to the parent audio [TrackEntity].
 * @property offsetMs     Timeline position in milliseconds where this clip starts.
 * @property displayName  User-visible label (e.g. "Clip 1", "Verse").
 * @property sortIndex    Ordering among clips of the same track (for display).
 * @property isMuted      When true, this clip is silenced during playback.
 */
@Entity(
    tableName = "audio_clips",
    foreignKeys = [ForeignKey(
        entity = TrackEntity::class,
        parentColumns = ["id"],
        childColumns = ["trackId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("trackId")]
)
data class AudioClipEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val trackId: Long,
    val offsetMs: Long = 0L,
    val displayName: String = "",
    val sortIndex: Int = 0,
    val isMuted: Boolean = false,
    val createdAtEpochMs: Long = System.currentTimeMillis()
)
