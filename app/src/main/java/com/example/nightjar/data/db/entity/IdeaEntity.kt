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
 * @property createdAtEpochMs Unix timestamp of when the idea was first recorded.
 */
@Entity(tableName = "ideas")
data class IdeaEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val title: String,
    val notes: String = "",
    val isFavorite: Boolean = false,
    val createdAtEpochMs: Long = System.currentTimeMillis()
)