package com.example.nightjar.data.repository

import android.media.MediaMetadataRetriever
import com.example.nightjar.data.db.dao.AudioClipDao
import com.example.nightjar.data.db.dao.TakeDao
import com.example.nightjar.data.db.dao.TrackDao
import com.example.nightjar.data.db.entity.AudioClipEntity
import com.example.nightjar.data.db.entity.TakeEntity
import com.example.nightjar.data.db.entity.TrackEntity
import com.example.nightjar.data.storage.RecordingStorage
import java.io.File

/**
 * Repository for multi-track Studio projects.
 *
 * Handles project initialization, track CRUD, clip-based audio arrangement,
 * timeline edits (move, trim, reorder), and mix controls (mute, volume).
 * All operations are non-destructive -- audio files are never modified in place.
 */
class StudioRepository(
    private val trackDao: TrackDao,
    private val audioClipDao: AudioClipDao,
    private val takeDao: TakeDao,
    private val storage: RecordingStorage
) {

    // ── Project lifecycle ───────────────────────────────────────────────

    /**
     * Returns existing tracks for the idea, fixing up any zero-duration
     * tracks left behind by the v3->v4 migration.
     */
    suspend fun ensureProjectInitialized(ideaId: Long): List<TrackEntity> {
        val existing = trackDao.getTracksForIdea(ideaId)
        if (existing.isEmpty()) return emptyList()

        // Fix up tracks with unknown duration (from v3->v4 migration)
        var needsRefresh = false
        for (track in existing) {
            if (track.durationMs == 0L && track.audioFileName != null) {
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

    /**
     * Add a new audio track with a clip and take.
     * This is the standard path for creating the first recording.
     */
    suspend fun addTrack(
        ideaId: Long,
        audioFile: File,
        durationMs: Long,
        offsetMs: Long = 0L,
        trimStartMs: Long = 0L
    ): Long {
        val nextIndex = trackDao.getTrackCount(ideaId)
        val track = TrackEntity(
            ideaId = ideaId,
            audioFileName = audioFile.name,
            displayName = "Track ${nextIndex + 1}",
            sortIndex = nextIndex,
            durationMs = durationMs,
            offsetMs = offsetMs,
            trimStartMs = trimStartMs
        )
        val trackId = trackDao.insertTrack(track)

        // Create a clip + take for the new audio track
        val clipId = audioClipDao.insertClip(AudioClipEntity(
            trackId = trackId,
            offsetMs = offsetMs,
            displayName = "Clip 1",
            sortIndex = 0
        ))
        takeDao.insertTake(TakeEntity(
            clipId = clipId,
            audioFileName = audioFile.name,
            displayName = "Take 1",
            sortIndex = 0,
            durationMs = durationMs,
            trimStartMs = trimStartMs
        ))

        recomputeAudioTrackDuration(trackId)
        return trackId
    }

    suspend fun deleteTrackAndAudio(trackId: Long) {
        val track = trackDao.getTrackById(trackId) ?: return

        // Collect audio files from clips/takes before cascade delete
        if (track.isAudio) {
            val clips = audioClipDao.getClipsForTrack(trackId)
            val clipIds = clips.map { it.id }
            if (clipIds.isNotEmpty()) {
                val takes = takeDao.getTakesForClips(clipIds)
                for (take in takes) {
                    storage.deleteAudioFile(take.audioFileName)
                }
            }
            // Also delete track's own audioFileName if it exists
            track.audioFileName?.let { storage.deleteAudioFile(it) }
        } else {
            track.audioFileName?.let { storage.deleteAudioFile(it) }
        }

        trackDao.deleteTrackById(trackId)
    }

    suspend fun renameTrack(trackId: Long, name: String) {
        trackDao.updateDisplayName(trackId, name)
    }

    /** Create a new drum track for the given idea. Returns the track ID. */
    suspend fun addDrumTrack(ideaId: Long): Long {
        val nextIndex = trackDao.getTrackCount(ideaId)
        val track = TrackEntity(
            ideaId = ideaId,
            trackType = "drum",
            audioFileName = null,
            displayName = "Drums ${nextIndex + 1}",
            sortIndex = nextIndex,
            durationMs = 0L
        )
        return trackDao.insertTrack(track)
    }

    /** Create a new MIDI instrument track. Returns the track ID. */
    suspend fun addMidiTrack(ideaId: Long, midiChannel: Int, midiProgram: Int = 0): Long {
        val nextIndex = trackDao.getTrackCount(ideaId)
        val track = TrackEntity(
            ideaId = ideaId,
            trackType = "midi",
            audioFileName = null,
            displayName = "MIDI ${nextIndex + 1}",
            sortIndex = nextIndex,
            durationMs = 0L,
            midiChannel = midiChannel,
            midiProgram = midiProgram
        )
        return trackDao.insertTrack(track)
    }

    // ── Timeline edits ────────────────────────────────────────────────────

    suspend fun moveTrack(trackId: Long, newOffsetMs: Long) {
        trackDao.updateOffset(trackId, newOffsetMs)
    }

    suspend fun trimTrack(trackId: Long, trimStartMs: Long, trimEndMs: Long) {
        trackDao.updateTrim(trackId, trimStartMs, trimEndMs)
    }

    // ── Mix controls ──────────────────────────────────────────────────────

    suspend fun setTrackMuted(trackId: Long, muted: Boolean) {
        trackDao.updateMuted(trackId, muted)
    }

    suspend fun setTrackVolume(trackId: Long, volume: Float) {
        trackDao.updateVolume(trackId, volume)
    }

    // ── Reads ────────────────────────────────────────────────────────────

    suspend fun getTracks(ideaId: Long): List<TrackEntity> =
        trackDao.getTracksForIdea(ideaId)

    // ── Audio Clip CRUD ──────────────────────────────────────────────────

    suspend fun getClipsForTrack(trackId: Long): List<AudioClipEntity> =
        audioClipDao.getClipsForTrack(trackId)

    suspend fun getClipsForTracks(trackIds: List<Long>): List<AudioClipEntity> =
        if (trackIds.isEmpty()) emptyList() else audioClipDao.getClipsForTracks(trackIds)

    /** Create a new audio clip. Returns the clip ID. */
    suspend fun addClip(trackId: Long, offsetMs: Long, displayName: String? = null): Long {
        val nextIndex = (audioClipDao.getMaxSortIndex(trackId) ?: -1) + 1
        val name = displayName ?: "Clip ${nextIndex + 1}"
        return audioClipDao.insertClip(AudioClipEntity(
            trackId = trackId,
            offsetMs = offsetMs,
            displayName = name,
            sortIndex = nextIndex
        ))
    }

    suspend fun moveClip(clipId: Long, newOffsetMs: Long) {
        audioClipDao.updateClipOffset(clipId, newOffsetMs)
    }

    suspend fun renameClip(clipId: Long, name: String) {
        audioClipDao.updateDisplayName(clipId, name)
    }

    suspend fun setClipMuted(clipId: Long, muted: Boolean) {
        audioClipDao.updateMuted(clipId, muted)
    }

    /** Delete a clip and its audio files. Cascade handles take DB rows. */
    suspend fun deleteClipAndAudio(clipId: Long) {
        val takes = takeDao.getTakesForClip(clipId)
        for (take in takes) {
            storage.deleteAudioFile(take.audioFileName)
        }
        audioClipDao.deleteClip(clipId)
    }

    // ── Clip-aware Take methods ──────────────────────────────────────────

    /**
     * Add a take to a clip, marking it as active and deactivating the previous.
     * Returns the new take's ID.
     */
    suspend fun addTakeToClip(
        clipId: Long,
        audioFile: File,
        durationMs: Long,
        trimStartMs: Long = 0L
    ): Long {
        val nextIndex = takeDao.getTakeCount(clipId)
        val take = TakeEntity(
            clipId = clipId,
            audioFileName = audioFile.name,
            displayName = "Take ${nextIndex + 1}",
            sortIndex = nextIndex,
            durationMs = durationMs,
            trimStartMs = trimStartMs
        )
        val takeId = takeDao.insertTake(take)
        // Set this new take as active, deactivate others
        takeDao.setActiveTake(clipId, takeId)
        return takeId
    }

    suspend fun setActiveTake(clipId: Long, takeId: Long) {
        takeDao.setActiveTake(clipId, takeId)
    }

    suspend fun deleteTakeAndAudio(takeId: Long) {
        val take = takeDao.getTakeById(takeId) ?: return
        val clipId = take.clipId
        val wasActive = take.isActive

        takeDao.deleteTakeById(takeId)
        storage.deleteAudioFile(take.audioFileName)

        // If the deleted take was active, activate the next remaining take
        if (wasActive) {
            val remaining = takeDao.getTakesForClip(clipId)
            if (remaining.isNotEmpty()) {
                takeDao.setActiveTake(clipId, remaining.first().id)
            } else {
                // No takes left -- delete the empty clip and its audio
                audioClipDao.deleteClip(clipId)
            }
        }
    }

    suspend fun renameTake(takeId: Long, name: String) {
        takeDao.updateDisplayName(takeId, name)
    }

    suspend fun getTakesForClip(clipId: Long): List<TakeEntity> =
        takeDao.getTakesForClip(clipId)

    suspend fun getTakesForClips(clipIds: List<Long>): List<TakeEntity> =
        if (clipIds.isEmpty()) emptyList() else takeDao.getTakesForClips(clipIds)

    suspend fun getActiveTake(clipId: Long): TakeEntity? =
        takeDao.getActiveTakeForClip(clipId)

    suspend fun updateTakeTrim(takeId: Long, trimStartMs: Long, trimEndMs: Long) {
        takeDao.updateTrim(takeId, trimStartMs, trimEndMs)
    }

    // ── Recording helpers ────────────────────────────────────────────────

    /**
     * Find a clip at a given position on a track.
     * Returns the clip whose range contains [positionMs], or null.
     */
    suspend fun findClipAtPosition(trackId: Long, positionMs: Long): AudioClipEntity? {
        val clips = audioClipDao.getClipsForTrack(trackId)
        for (clip in clips) {
            val activeTake = takeDao.getActiveTakeForClip(clip.id) ?: continue
            val effectiveDuration = activeTake.durationMs - activeTake.trimStartMs - activeTake.trimEndMs
            if (positionMs >= clip.offsetMs && positionMs < clip.offsetMs + effectiveDuration) {
                return clip
            }
        }
        return null
    }

    /**
     * Find the first clip on a track whose offsetMs is strictly after [positionMs].
     * Returns null if no clip exists after that position.
     */
    suspend fun findNextClipAfterPosition(trackId: Long, positionMs: Long): AudioClipEntity? {
        val clips = audioClipDao.getClipsForTrack(trackId)
        return clips
            .filter { it.offsetMs > positionMs }
            .minByOrNull { it.offsetMs }
    }

    /**
     * Atomic creation of a new audio track with its first clip and take.
     * Used for first-track recording in Studio.
     */
    suspend fun addTrackWithClipAndTake(
        ideaId: Long,
        audioFile: File,
        durationMs: Long,
        offsetMs: Long = 0L,
        trimStartMs: Long = 0L
    ): Long {
        return addTrack(ideaId, audioFile, durationMs, offsetMs, trimStartMs)
    }

    /**
     * Recompute an audio track's duration from its clips.
     * Duration = MAX(clip.offsetMs + activeTake.effectiveDuration) across all clips.
     */
    suspend fun recomputeAudioTrackDuration(trackId: Long) {
        val clips = audioClipDao.getClipsForTrack(trackId)
        var maxEndMs = 0L
        for (clip in clips) {
            val activeTake = takeDao.getActiveTakeForClip(clip.id) ?: continue
            val effectiveDuration = activeTake.durationMs - activeTake.trimStartMs - activeTake.trimEndMs
            val endMs = clip.offsetMs + effectiveDuration
            if (endMs > maxEndMs) maxEndMs = endMs
        }
        trackDao.updateDuration(trackId, maxEndMs)
    }

    // ── Playback slot helper ─────────────────────────────────────────────

    /**
     * Data class for a flattened audio playback slot.
     * Used by Library/Overview preview to load active takes into the engine.
     */
    data class AudioPlaybackSlot(
        val audioFileName: String,
        val offsetMs: Long,
        val durationMs: Long,
        val trimStartMs: Long,
        val trimEndMs: Long,
        val volume: Float,
        val isMuted: Boolean
    )

    /**
     * Returns a flat list of active audio slots for an idea.
     * Each slot represents one clip's active take with its absolute position.
     */
    suspend fun getActiveAudioSlotsForIdea(ideaId: Long): List<AudioPlaybackSlot> {
        val tracks = trackDao.getTracksForIdea(ideaId).filter { it.isAudio }
        if (tracks.isEmpty()) return emptyList()

        val trackIds = tracks.map { it.id }
        val allClips = audioClipDao.getClipsForTracks(trackIds)
        if (allClips.isEmpty()) return emptyList()

        val clipIds = allClips.map { it.id }
        val allTakes = takeDao.getTakesForClips(clipIds)
        val activeTakesByClip = allTakes.filter { it.isActive }.associateBy { it.clipId }
        val tracksById = tracks.associateBy { it.id }

        val slots = mutableListOf<AudioPlaybackSlot>()
        for (clip in allClips) {
            val activeTake = activeTakesByClip[clip.id] ?: continue
            val track = tracksById[clip.trackId] ?: continue
            slots.add(AudioPlaybackSlot(
                audioFileName = activeTake.audioFileName,
                offsetMs = clip.offsetMs,
                durationMs = activeTake.durationMs,
                trimStartMs = activeTake.trimStartMs,
                trimEndMs = activeTake.trimEndMs,
                volume = activeTake.volume * track.volume,
                isMuted = track.isMuted || clip.isMuted
            ))
        }
        return slots
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
