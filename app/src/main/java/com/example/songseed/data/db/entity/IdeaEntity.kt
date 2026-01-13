package com.example.songseed.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ideas")
data class IdeaEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val audioFileName: String,
    val title: String,
    val notes: String = "",
    val isFavorite: Boolean = false,
    val createdAtEpochMs: Long = System.currentTimeMillis()
)