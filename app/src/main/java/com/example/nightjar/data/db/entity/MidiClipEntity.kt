package com.example.nightjar.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * An arrangeable block of MIDI notes on the timeline.
 *
 * Each MIDI track has one or more clips. Notes within a clip use
 * positions relative to the clip start (0ms = clip beginning).
 * The absolute timeline position of a note is `clip.offsetMs + note.startMs`.
 *
 * @property trackId   Foreign key to the parent MIDI [TrackEntity].
 * @property offsetMs  Timeline position in milliseconds where this clip starts.
 * @property sortIndex Ordering among clips of the same track (for display).
 */
@Entity(
    tableName = "midi_clips",
    foreignKeys = [ForeignKey(
        entity = TrackEntity::class,
        parentColumns = ["id"],
        childColumns = ["trackId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("trackId")]
)
data class MidiClipEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val trackId: Long,
    val offsetMs: Long = 0L,
    val sortIndex: Int = 0
)
