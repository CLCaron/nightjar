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
 * ## Linking
 * `sourceClipId = null` — this clip is a **source**: its takes are the
 * authoritative content. `sourceClipId = X` — this clip is an **instance**
 * of source X; content lookups resolve to X's takes. Instances own only
 * position (`offsetMs`) and per-instance flags (`isMuted`). Deleting the
 * source requires promoting an instance (see StudioRepository).
 */
@Entity(
    tableName = "audio_clips",
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = AudioClipEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceClipId"],
            onDelete = ForeignKey.NO_ACTION
        )
    ],
    indices = [Index("trackId"), Index("sourceClipId")]
)
data class AudioClipEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val trackId: Long,
    val offsetMs: Long = 0L,
    val displayName: String = "",
    val sortIndex: Int = 0,
    val isMuted: Boolean = false,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val sourceClipId: Long? = null
)
