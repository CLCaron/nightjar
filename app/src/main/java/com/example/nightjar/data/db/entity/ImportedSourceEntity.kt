package com.example.nightjar.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Original bytes remain owned by the Idea even if its playback take is removed. */
@Entity(tableName = "imported_sources", foreignKeys = [ForeignKey(entity = IdeaEntity::class,
    parentColumns = ["id"], childColumns = ["ideaId"], onDelete = ForeignKey.CASCADE)], indices = [Index("ideaId")])
data class ImportedSourceEntity(@PrimaryKey val playbackFileName: String, val originalFileName: String, val ideaId: Long)
