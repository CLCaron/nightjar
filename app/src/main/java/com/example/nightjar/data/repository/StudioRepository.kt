package com.example.nightjar.data.repository

import android.media.MediaMetadataRetriever
import com.example.nightjar.data.db.dao.TrackDao
import com.example.nightjar.data.db.entity.TrackEntity
import com.example.nightjar.data.storage.RecordingStorage
import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * Repository for multi-track Studio projects.
 *
 * Handles project initialization, track CRUD, timeline edits (move, trim,
 * reorder), and mix controls (mute, volume). All operations are
 * non-destructive — audio files are never modified in place.
 */
class StudioRepository(
    private val trackDao: TrackDao,
    private val storage: RecordingStorage
) {

    // ── Project lifecycle ───────────────────────────────────────────────

    /**
     * Returns existing tracks for the idea, fixing up any zero-duration
     * tracks left behind by the v3→v4 migration.
     */
    suspend fun ensureProjectInitialized(ideaId: Long): List<TrackEntity> {
        val existing = trackDao.getTracksForIdea(ideaId)
        if (existing.isEmpty()) return emptyList()

        // Fix up tracks with unknown duration (from v3→v4 migration)
        var needsRefresh = false
        for (track in existing) {
            if (track.durationMs == 0L) {
                val file = storage.getAudioFile(track.audioFileName)
                val duration = resolveFileDurationMs(file)
                if (duration > 0L) {
                    trackDao.updateDuration(track.id, duration)
                    needsRefresh = true
                }
            }
        }
        return if (needsRefresh) trackDao.getTracksForIdea(ideaId) else existing
    }

    // ── Track CRUD ────────────────────────────────────────────────────────

    suspend fun addTrack(
        ideaId: Long,
        audioFile: File,
        durationMs: Long,
        offsetMs: Long = 0L
    ): Long {
        val nextIndex = trackDao.getTrackCount(ideaId)
        val track = TrackEntity(
            ideaId = ideaId,
            audioFileName = audioFile.name,
            displayName = "Track ${nextIndex + 1}",
            sortIndex = nextIndex,
            durationMs = durationMs,
            offsetMs = offsetMs
        )
        return trackDao.insertTrack(track)
    }

    suspend fun deleteTrackAndAudio(trackId: Long) {
        val track = trackDao.getTrackById(trackId) ?: return
        trackDao.deleteTrackById(trackId)
        storage.deleteAudioFile(track.audioFileName)
    }

    suspend fun renameTrack(trackId: Long, name: String) {
        trackDao.updateDisplayName(trackId, name)
    }

    // ── Timeline edits ────────────────────────────────────────────────────

    suspend fun moveTrack(trackId: Long, newOffsetMs: Long) {
        trackDao.updateOffset(trackId, newOffsetMs)
    }

    suspend fun trimTrack(trackId: Long, trimStartMs: Long, trimEndMs: Long) {
        trackDao.updateTrim(trackId, trimStartMs, trimEndMs)
    }

    suspend fun reorderTracks(ideaId: Long, orderedTrackIds: List<Long>) {
        orderedTrackIds.forEachIndexed { index, trackId ->
            trackDao.updateSortIndex(trackId, index)
        }
    }

    // ── Mix controls ──────────────────────────────────────────────────────

    suspend fun setTrackMuted(trackId: Long, muted: Boolean) {
        trackDao.updateMuted(trackId, muted)
    }

    suspend fun setTrackVolume(trackId: Long, volume: Float) {
        trackDao.updateVolume(trackId, volume)
    }

    // ── Reads ────────────────────────────────────────────────────────────

    fun observeTracks(ideaId: Long): Flow<List<TrackEntity>> =
        trackDao.observeTracksForIdea(ideaId)

    suspend fun getTracks(ideaId: Long): List<TrackEntity> =
        trackDao.getTracksForIdea(ideaId)

    suspend fun getTrackAudioFile(trackId: Long): File? {
        val track = trackDao.getTrackById(trackId) ?: return null
        return storage.getAudioFile(track.audioFileName)
    }

    // ── Internal ─────────────────────────────────────────────────────────

    private fun resolveFileDurationMs(file: File): Long {
        if (!file.exists()) return 0L
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            retriever.release()
        }
    }
}
