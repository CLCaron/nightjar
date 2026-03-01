package com.example.nightjar.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A single audio take within a [TrackEntity].
 *
 * Tracks can have multiple takes (e.g. from loop recording). Each take
 * is an independent audio file that can be positioned, trimmed, muted,
 * and volume-adjusted independently. All unmuted takes in a track play
 * simultaneously (layered in the mixer).
 *
 * Takes are lazily promoted: existing tracks start with zero takes.
 * When the user first arms a track, [StudioRepository.ensureTrackHasTakes]
 * creates a single "Take 1" from the track's existing audio. Subsequent
 * recordings add new takes.
 *
 * @property trackId       Foreign key to the parent [TrackEntity].
 * @property audioFileName Filename of the WAV audio file in the recordings directory.
 * @property displayName   User-visible label (e.g. "Take 1", "Take 2").
 * @property sortIndex     Ordering within the track (0 = first take).
 * @property durationMs    Total duration of the underlying audio file.
 * @property offsetMs      Timeline offset for this take (each take draggable independently).
 * @property trimStartMs   Milliseconds trimmed from the start (non-destructive).
 * @property trimEndMs     Milliseconds trimmed from the end (non-destructive).
 * @property isMuted       When true, this take is silenced during playback.
 * @property volume        Playback volume multiplier (0.0-1.0).
 */
@Entity(
    tableName = "takes",
    foreignKeys = [ForeignKey(
        entity = TrackEntity::class,
        parentColumns = ["id"],
        childColumns = ["trackId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("trackId")]
)
data class TakeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val trackId: Long,
    val audioFileName: String,
    val displayName: String,
    val sortIndex: Int,
    val durationMs: Long,
    val offsetMs: Long = 0L,
    val trimStartMs: Long = 0L,
    val trimEndMs: Long = 0L,
    val isMuted: Boolean = false,
    val volume: Float = 1.0f,
    val createdAtEpochMs: Long = System.currentTimeMillis()
)
