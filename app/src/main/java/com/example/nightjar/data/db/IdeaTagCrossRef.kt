package com.example.nightjar.data.db

import androidx.room.Entity

/** Junction table implementing the many-to-many relationship between ideas and tags. */
@Entity(
    tableName = "idea_tags",
    primaryKeys = ["ideaId", "tagId"]
)
data class IdeaTagCrossRef(
    val ideaId: Long,
    val tagId: Long
)
