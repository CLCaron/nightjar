package com.example.nightjar.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Recording source owned by an Idea. Studio placement is an explicit choice. */
@Entity(
    tableName = "capture_groups",
    foreignKeys = [
        ForeignKey(entity = IdeaEntity::class, parentColumns = ["id"], childColumns = ["ideaId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = AudioClipEntity::class, parentColumns = ["id"], childColumns = ["clipId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("ideaId"), Index(value = ["clipId"], unique = true)]
)
data class CaptureGroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ideaId: Long,
    val clipId: Long,
    val displayName: String,
    val sortIndex: Int,
    val latchedTakeId: Long? = null,
    val inStudio: Boolean = false
)
