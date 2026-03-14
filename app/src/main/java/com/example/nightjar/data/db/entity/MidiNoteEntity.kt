package com.example.nightjar.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A single MIDI note event within a [MidiClipEntity].
 *
 * Notes are stored as (pitch, startMs, durationMs) tuples with positions
 * relative to the parent clip start (0ms = clip beginning). The Kotlin layer
 * applies clip offsets when converting to absolute frame positions for the
 * C++ [MidiSequencer].
 *
 * @property trackId    Foreign key to the parent [TrackEntity] (must be trackType = "midi").
 * @property clipId     Foreign key to the parent [MidiClipEntity].
 * @property pitch      MIDI note number (0-127). Middle C = 60.
 * @property startMs    Start time relative to clip start in milliseconds.
 * @property durationMs Duration of the note in milliseconds.
 * @property velocity   Note velocity (0.0-1.0), mapped to MIDI velocity 0-127.
 */
@Entity(
    tableName = "midi_notes",
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = MidiClipEntity::class,
            parentColumns = ["id"],
            childColumns = ["clipId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("trackId"), Index("clipId")]
)
data class MidiNoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val trackId: Long,
    val clipId: Long,
    val pitch: Int,
    val startMs: Long,
    val durationMs: Long,
    val velocity: Float = 0.8f
)
