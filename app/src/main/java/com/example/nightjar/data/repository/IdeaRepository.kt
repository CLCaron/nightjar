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
     * The idea is a pure metadata container; only the track references [audioFile].
     */
    suspend fun createIdeaWithTrack(audioFile: File, durationMs: Long): Long {
        val title = defaultTitle()
        return database.withTransaction {
            val idea = IdeaEntity(
                title = title,
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
        val tracks = trackDao.getTracksForIdea(id)
        ideaDao.deleteIdeaById(id) // cascade deletes track rows
        tracks.forEach { storage.deleteAudioFile(it.audioFileName) }
    }

    /**
     * Returns the audio file for the first track (by sort index) of the given idea,
     * or null if the idea has no tracks.
     */
    suspend fun getFirstTrackFile(ideaId: Long): File? {
        val tracks = trackDao.getTracksForIdea(ideaId)
        val first = tracks.minByOrNull { it.sortIndex } ?: return null
        return storage.getAudioFile(first.audioFileName)
    }

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

    /** Returns a map of idea ID → total playback duration in milliseconds. */
    suspend fun getIdeaDurations(): Map<Long, Long> =
        trackDao.getIdeaDurations().associate { it.ideaId to it.durationMs }
}
