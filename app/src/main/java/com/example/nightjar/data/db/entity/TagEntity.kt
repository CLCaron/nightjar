package com.example.nightjar.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A user-created tag for categorizing ideas (e.g. "chorus", "sad", "120bpm").
 *
 * Tags are linked to ideas via [com.example.nightjar.data.db.IdeaTagCrossRef].
 * Uniqueness is enforced on [nameNormalized] (lowercase) so "Folk" and "folk"
 * resolve to the same tag.
 *
 * @property name           Display name preserving the user's original casing.
 * @property nameNormalized Lowercased name used for uniqueness and filtering.
 */
@Entity(
    tableName = "tags",
    indices = [Index(value = ["nameNormalized"], unique = true)]
)
data class TagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val nameNormalized: String
)