package com.example.nightjar.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A single MIDI note event within a MIDI [TrackEntity].
 *
 * Notes are stored as (pitch, startMs, durationMs) tuples. The Kotlin layer
 * converts these to paired noteOn/noteOff events sorted by frame position
 * before pushing to the C++ [MidiSequencer].
 *
 * @property trackId    Foreign key to the parent [TrackEntity] (must be trackType = "midi").
 * @property pitch      MIDI note number (0-127). Middle C = 60.
 * @property startMs    Start time on the timeline in milliseconds.
 * @property durationMs Duration of the note in milliseconds.
 * @property velocity   Note velocity (0.0-1.0), mapped to MIDI velocity 0-127.
 */
@Entity(
    tableName = "midi_notes",
    foreignKeys = [ForeignKey(
        entity = TrackEntity::class,
        parentColumns = ["id"],
        childColumns = ["trackId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("trackId")]
)
data class MidiNoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val trackId: Long,
    val pitch: Int,
    val startMs: Long,
    val durationMs: Long,
    val velocity: Float = 0.8f
)
