package com.example.songseed.data.db

import androidx.room.Entity

@Entity(
    tableName = "idea_tags",
    primaryKeys = ["ideaId", "tagId"]
)
data class IdeaTagCrossRef(
    val ideaId: Long,
    val tagId: Long
)
