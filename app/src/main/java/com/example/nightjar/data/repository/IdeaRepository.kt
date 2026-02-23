package com.example.nightjar.data.repository

import androidx.room.withTransaction
import com.example.nightjar.data.db.IdeaTagCrossRef
import com.example.nightjar.data.db.NightjarDatabase
import com.example.nightjar.data.db.dao.IdeaDao
import com.example.nightjar.data.db.dao.TagDao
import com.example.nightjar.data.db.dao.TrackDao
import com.example.nightjar.data.db.entity.IdeaEntity
import com.example.nightjar.data.db.entity.TagEntity
import com.example.nightjar.data.db.entity.TrackEntity
import com.example.nightjar.data.storage.RecordingStorage
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Central repository for idea lifecycle operations.
 *
 * Bridges the Record, Overview, and Library screens to the underlying
 * [IdeaDao], [TagDao], and [RecordingStorage]. All database and file
 * operations are suspend functions safe to call from a ViewModel scope.
 */
class IdeaRepository(
    private val ideaDao: IdeaDao,
    private val tagDao: TagDao,
    private val trackDao: TrackDao,
    private val storage: RecordingStorage,
    private val database: NightjarDatabase
) {

    // ── Record ──────────────────────────────────────────────────────────

    /**
     * Creates an [IdeaEntity] and its first [TrackEntity] in a single transaction.
     *
     * Both the idea and track reference the same [audioFile] — the idea keeps
     * `audioFileName` for backward compatibility with Overview/Library until
     * Phase 1 Step 3 removes it.
     */
    suspend fun createIdeaWithTrack(audioFile: File, durationMs: Long): Long {
        val title = defaultTitle()
        return database.withTransaction {
            val idea = IdeaEntity(
                audioFileName = audioFile.name,
                title = title,
                notes = "",
                isFavorite = false,
                createdAtEpochMs = System.currentTimeMillis()
            )
            val ideaId = ideaDao.insertIdea(idea)

            val track = TrackEntity(
                ideaId = ideaId,
                audioFileName = audioFile.name,
                displayName = "Track 1",
                sortIndex = 0,
                durationMs = durationMs
            )
            trackDao.insertTrack(track)

            ideaId
        }
    }

    private fun defaultTitle(): String {
        val fmt = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
        return "Idea ${fmt.format(Date())}"
    }

    // ── Overview ─────────────────────────────────────────────────────────

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

    // ── Library ──────────────────────────────────────────────────────────

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
