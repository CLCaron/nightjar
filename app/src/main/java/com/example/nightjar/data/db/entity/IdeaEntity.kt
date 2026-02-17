package com.example.nightjar.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A saved audio idea — the core entity in Nightjar.
 *
 * Each idea represents a single recording captured from the Record screen.
 * The audio file lives in app-private storage; only the filename is persisted
 * here. An idea can optionally be expanded into a multi-track project via
 * the Explore screen (see [TrackEntity]).
 *
 * @property audioFileName Filename (not full path) of the M4A recording in the recordings directory.
 * @property title         User-editable title, auto-generated on creation (e.g. "Idea Feb 17, 3:42 PM").
 * @property notes         Free-form text for lyrics, chords, or other notes.
 * @property isFavorite    Pinned to the top of the library when sorting by favorites.
 * @property createdAtEpochMs Unix timestamp of when the idea was first recorded.
 */
@Entity(tableName = "ideas")
data class IdeaEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val audioFileName: String,
    val title: String,
    val notes: String = "",
    val isFavorite: Boolean = false,
    val createdAtEpochMs: Long = System.currentTimeMillis()
)