package com.example.nightjar.data.repository

import androidx.room.withTransaction
import com.example.nightjar.data.db.IdeaTagCrossRef
import com.example.nightjar.data.db.NightjarDatabase
import com.example.nightjar.data.db.dao.AudioClipDao
import com.example.nightjar.data.db.dao.IdeaDao
import com.example.nightjar.data.db.dao.TagDao
import com.example.nightjar.data.db.dao.TakeDao
import com.example.nightjar.data.db.dao.TrackDao
import com.example.nightjar.data.db.entity.AudioClipEntity
import com.example.nightjar.data.db.entity.IdeaEntity
import com.example.nightjar.data.db.entity.TagEntity
import com.example.nightjar.data.db.entity.TakeEntity
import com.example.nightjar.data.db.entity.TrackEntity
import com.example.nightjar.data.storage.RecordingStorage
import kotlinx.coroutines.flow.Flow
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
    private val audioClipDao: AudioClipDao,
    private val takeDao: TakeDao,
    private val storage: RecordingStorage,
    private val database: NightjarDatabase
) {

    // ── Record ──────────────────────────────────────────────────────────

    /**
     * Creates an [IdeaEntity] and its first [TrackEntity] with a clip + active
     * take in a single transaction.
     *
     * The idea is a pure metadata container. Audio playback in Studio and
     * Overview reads from the clip/take layer, so the take row is what makes
     * the recording audible — without it the file exists on disk but no
     * playback slot is produced. The legacy [TrackEntity.audioFileName] field
     * stays populated so the composite waveform extractor (which reads it
     * directly) can render thumbnails.
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
            val trackId = trackDao.insertTrack(track)

            val clipId = audioClipDao.insertClip(AudioClipEntity(
                trackId = trackId,
                offsetMs = 0L,
                displayName = "Clip 1",
                sortIndex = 0
            ))
            takeDao.insertTake(TakeEntity(
                clipId = clipId,
                audioFileName = audioFile.name,
                displayName = "Take 1",
                sortIndex = 0,
                durationMs = durationMs,
                isActive = true
            ))

            ideaId
        }
    }

    private fun defaultTitle(): String {
        val fmt = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
        return "Idea ${fmt.format(Date())}"
    }

    /** Creates an [IdeaEntity] with no tracks — used by Write and Studio shortcuts. */
    suspend fun createEmptyIdea(): Long {
        val idea = IdeaEntity(
            title = defaultTitle(),
            createdAtEpochMs = System.currentTimeMillis()
        )
        return ideaDao.insertIdea(idea)
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

    suspend fun updateBpm(id: Long, bpm: Double) =
        ideaDao.updateBpm(id, bpm)

    suspend fun updateTimeSignature(id: Long, numerator: Int, denominator: Int) =
        ideaDao.updateTimeSignature(id, numerator, denominator)

    suspend fun updateGridResolution(id: Long, gridResolution: Int) =
        ideaDao.updateGridResolution(id, gridResolution)

    suspend fun updateScale(id: Long, root: Int, type: String) =
        ideaDao.updateScale(id, root, type)

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
        tracks.forEach { it.audioFileName?.let { name -> storage.deleteAudioFile(name) } }
    }

    /**
     * Deletes the idea if it has no meaningful content -- no tracks, blank
     * notes, no tags, and not favorited. Returns true if deleted.
     *
     * Used for cleanup when the user navigates back without doing anything
     * after tapping "Write" or "Studio" on the Record screen.
     */
    suspend fun deleteIdeaIfEmpty(id: Long): Boolean {
        val idea = ideaDao.getIdeaById(id) ?: return false
        if (idea.isFavorite) return false
        if (idea.notes.isNotBlank()) return false

        val tracks = trackDao.getTracksForIdea(id)
        if (tracks.isNotEmpty()) return false

        val tags = tagDao.getTagsForIdea(id)
        if (tags.isNotEmpty()) return false

        ideaDao.deleteIdeaById(id)
        return true
    }

    /**
     * Returns the audio file for the first track (by sort index) of the given idea,
     * or null if the idea has no tracks.
     */
    suspend fun getFirstTrackFile(ideaId: Long): File? {
        val tracks = trackDao.getTracksForIdea(ideaId)
        val first = tracks.filter { it.isAudio }.minByOrNull { it.sortIndex } ?: return null
        return first.audioFileName?.let { storage.getAudioFile(it) }
    }

    /** Returns all audio tracks for the given idea, sorted by sort index. */
    suspend fun getAudioTracksForIdea(ideaId: Long): List<TrackEntity> =
        trackDao.getTracksForIdea(ideaId).filter { it.isAudio }

    /** Returns the audio file for the given file name. */
    fun getAudioFile(fileName: String): File =
        storage.getAudioFile(fileName)

    // ── Library ──────────────────────────────────────────────────────────

    suspend fun getAllUsedTags(): List<TagEntity> =
        tagDao.getAllUsedTags()

    fun observeIdeasNewest(): Flow<List<IdeaEntity>> =
        ideaDao.observeIdeas()

    fun observeIdeasOldestFirst(): Flow<List<IdeaEntity>> =
        ideaDao.observeIdeasOldestFirst()

    fun observeIdeasFavoritesFirst(): Flow<List<IdeaEntity>> =
        ideaDao.observeIdeasFavoritesFirst()

    fun observeIdeasForTag(tagNormalized: String): Flow<List<IdeaEntity>> =
        ideaDao.observeIdeasForTag(tagNormalized)

    /** Returns a map of idea ID → total playback duration in milliseconds. */
    suspend fun getIdeaDurations(): Map<Long, Long> =
        trackDao.getIdeaDurations().associate { it.ideaId to it.durationMs }
}
