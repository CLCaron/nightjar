package com.example.songseed.data.repository

import android.content.Context
import com.example.songseed.data.db.IdeaTagCrossRef
import com.example.songseed.data.db.SongSeedDatabase
import com.example.songseed.data.db.entity.IdeaEntity
import com.example.songseed.data.db.entity.TagEntity
import com.example.songseed.data.storage.RecordingStorage
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class IdeaRepository(context: Context) {

    private val db = SongSeedDatabase.getInstance(context)
    private val ideaDao = db.ideaDao()
    private val tagDao = db.tagDao()
    private val storage = RecordingStorage(context)

    /* ---------- Record ---------- */

    suspend fun createIdeaForRecordingFile(saved: File): Long {
        val title = defaultTitle()
        val idea = IdeaEntity(
            audioFileName = saved.name,
            title = title,
            notes = "",
            isFavorite = false,
            createdAtEpochMs = System.currentTimeMillis()
        )
        return ideaDao.insertIdea(idea)
    }

    private fun defaultTitle(): String {
        val fmt = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
        return "Idea ${fmt.format(Date())}"
    }

    /* ---------- Workspace ---------- */

    suspend fun getIdeaById(id: Long): IdeaEntity? =
        ideaDao.getIdeaById(id)

    suspend fun getTagsForIdea(ideaId: Long): List<TagEntity> =
        tagDao.getTagsForIdea(ideaId)

    suspend fun updateTitle(id: Long, title: String) =
        ideaDao.updateTitle(id, title)

    suspend fun updateNotes(id: Long, notes: String) =
        ideaDao.updateNotes(id, notes)

    suspend fun updateFavorite(id: Long, isFavorite: Boolean) =
        ideaDao.updateFavorite(id, isFavorite)

    suspend fun addTagToIdea(ideaId: Long, rawName: String) {
        val name = rawName.trim()
        if (name.isBlank()) return

        val normalized = name.lowercase()

        val existing = tagDao.getTagByNormalized(normalized)
        val tagId: Long =
            if (existing != null) existing.id
            else {
                val inserted = tagDao.insertTag(TagEntity(name = name, nameNormalized = normalized))
                if (inserted != -1L) inserted else tagDao.getTagByNormalized(normalized)!!.id
            }

        tagDao.addTagToIdea(IdeaTagCrossRef(ideaId = ideaId, tagId = tagId))
    }

    suspend fun removeTagFromIdea(ideaId: Long, tagId: Long) =
        tagDao.removeTagFromIdea(ideaId, tagId)

    suspend fun deleteIdeaAndAudio(id: Long) {
        val idea = ideaDao.getIdeaById(id) ?: return
        ideaDao.deleteIdeaById(id)
        storage.deleteAudioFile(idea.audioFileName)
    }

    fun getAudioFile(name: String): File =
        storage.getAudioFile(name)

    /* ---------- Library (non-Flow) ---------- */

    suspend fun getAllUsedTags(): List<TagEntity> =
        tagDao.getAllUsedTags()

    suspend fun getIdeasNewest(): List<IdeaEntity> =
        ideaDao.getIdeasNewest()

    suspend fun getIdeasOldestFirst(): List<IdeaEntity> =
        ideaDao.getIdeasOldestFirst()

    suspend fun getIdeasFavoritesFirst(): List<IdeaEntity> =
        ideaDao.getIdeasFavoritesFirst()

    suspend fun getIdeasForTag(tagNormalized: String): List<IdeaEntity> =
        ideaDao.getIdeasForTag(tagNormalized)
}
