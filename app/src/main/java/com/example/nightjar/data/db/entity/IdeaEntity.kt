package com.example.nightjar.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Pure metadata container for a creative capture in Nightjar.
 *
 * All audio data lives in [TrackEntity] rows linked via `ideaId`.
 * An idea is created atomically with its first track when the user
 * records from the Record screen.
 *
 * @property title         User-editable title, auto-generated on creation (e.g. "Idea Feb 17, 3:42 PM").
 * @property notes         Free-form text for lyrics, chords, or other notes.
 * @property isFavorite    Pinned to the top of the library when sorting by favorites.
 * @property bpm           Project-level tempo in beats per minute (default 120.0).
 *                         Used by drum sequencer, metronome, snap-to-grid, and MIDI tracks.
 * @property timeSignatureNumerator Beats per measure (top number of time signature, default 4).
 * @property timeSignatureDenominator Beat unit (bottom number of time signature, default 4).
 * @property gridResolution Sub-beat grid density for snap and editing (default 16 = sixteenth notes).
 *                          Valid values: 4 (quarter), 8 (eighth), 16 (sixteenth), 32 (thirty-second).
 * @property scaleRoot      Root note of the project scale (0=C through 11=B, default 0).
 * @property scaleType      Scale type name matching [com.example.nightjar.audio.MusicalScaleHelper.ScaleType].
 *                          Default "MAJOR". Stored as the enum name, not display name.
 * @property createdAtEpochMs Unix timestamp of when the idea was first recorded.
 */
@Entity(tableName = "ideas")
data class IdeaEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val title: String,
    val notes: String = "",
    val isFavorite: Boolean = false,
    val bpm: Double = 120.0,
    val timeSignatureNumerator: Int = 4,
    val timeSignatureDenominator: Int = 4,
    val gridResolution: Int = 16,
    val scaleRoot: Int = 0,
    val scaleType: String = "MAJOR",
    val createdAtEpochMs: Long = System.currentTimeMillis()
)