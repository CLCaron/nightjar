package com.example.nightjar.data.repository

import android.media.MediaMetadataRetriever
import androidx.room.withTransaction
import com.example.nightjar.data.db.NightjarDatabase
import com.example.nightjar.data.db.dao.AudioClipDao
import com.example.nightjar.data.db.dao.TakeDao
import com.example.nightjar.data.db.dao.TrackDao
import com.example.nightjar.data.db.entity.AudioClipEntity
import com.example.nightjar.data.db.entity.TakeEntity
import com.example.nightjar.data.db.entity.TrackEntity
import com.example.nightjar.data.events.PulseBus
import com.example.nightjar.data.storage.RecordingStorage
import com.example.nightjar.ui.studio.ClipLinkage
import com.example.nightjar.ui.studio.GroupKey
import java.io.File

/**
 * Repository for multi-track Studio projects.
 *
 * Handles project initialization, track CRUD, clip-based audio arrangement,
 * timeline edits (move, trim, reorder), and mix controls (mute, volume).
 * All operations are non-destructive -- audio files are never modified in place.
 *
 * ## Linked clips
 * Clips with `sourceClipId != null` are instances that share content with
 * their source. Content reads and writes go through [resolveSourceId] so
 * that instance mutations are routed to the source; propagating edits emit
 * a [GroupKey.Audio] onto [PulseBus] so sibling UIs can flash.
 */
class StudioRepository(
    private val trackDao: TrackDao,
    private val audioClipDao: AudioClipDao,
    private val takeDao: TakeDao,
    private val storage: RecordingStorage,
    private val database: NightjarDatabase,
    private val pulseBus: PulseBus
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

    /**
     * Rename a clip. For linked clips, writes to the source's displayName so
     * all siblings render the new name. Emits a pulse on the group.
     */
    suspend fun renameClip(clipId: Long, name: String) {
        val sourceId = resolveSourceId(clipId)
        audioClipDao.updateDisplayName(sourceId, name)
        pulseBus.emit(GroupKey.Audio(sourceId))
    }

    suspend fun setClipMuted(clipId: Long, muted: Boolean) {
        // Per-instance mute -- does NOT propagate.
        audioClipDao.updateMuted(clipId, muted)
    }

    /**
     * Delete a clip and its audio files.
     *
     * - Instance deletion: trivial; remove the clip row only (takes stay on source).
     * - Source with no instances: delete takes' audio files, then the clip row
     *   (CASCADE removes take rows).
     * - Source with live instances: promote the earliest instance to source,
     *   re-point all takes, re-parent remaining siblings; audio is preserved.
     */
    suspend fun deleteClipAndAudio(clipId: Long) {
        val clip = audioClipDao.getClipById(clipId) ?: return
        if (clip.sourceClipId != null) {
            // Instance deletion. Takes live on the source and are unaffected.
            audioClipDao.deleteClip(clipId)
            return
        }
        // Source deletion.
        val instances = audioClipDao.getInstancesOf(clipId)
        if (instances.isEmpty()) {
            val takes = takeDao.getTakesForClip(clipId)
            for (take in takes) {
                storage.deleteAudioFile(take.audioFileName)
            }
            audioClipDao.deleteClip(clipId)
        } else {
            promoteSourceOnDelete(clipId, instances)
        }
    }

    // ── Clip-aware Take methods ──────────────────────────────────────────

    /**
     * Add a take to a clip, marking it as active and deactivating the previous.
     * If the clip is an instance of a linked group, the take is written to the
     * source so every sibling sees it. Returns the new take's ID.
     */
    suspend fun addTakeToClip(
        clipId: Long,
        audioFile: File,
        durationMs: Long,
        trimStartMs: Long = 0L
    ): Long {
        val sourceId = resolveSourceId(clipId)
        val nextIndex = takeDao.getTakeCount(sourceId)
        val take = TakeEntity(
            clipId = sourceId,
            audioFileName = audioFile.name,
            displayName = "Take ${nextIndex + 1}",
            sortIndex = nextIndex,
            durationMs = durationMs,
            trimStartMs = trimStartMs
        )
        val takeId = takeDao.insertTake(take)
        takeDao.setActiveTake(sourceId, takeId)
        pulseBus.emit(GroupKey.Audio(sourceId))
        return takeId
    }

    suspend fun setActiveTake(clipId: Long, takeId: Long) {
        val sourceId = resolveSourceId(clipId)
        takeDao.setActiveTake(sourceId, takeId)
        pulseBus.emit(GroupKey.Audio(sourceId))
    }

    suspend fun deleteTakeAndAudio(takeId: Long) {
        val take = takeDao.getTakeById(takeId) ?: return
        val sourceId = take.clipId // takes always live on the source
        val wasActive = take.isActive

        takeDao.deleteTakeById(takeId)
        storage.deleteAudioFile(take.audioFileName)

        if (wasActive) {
            val remaining = takeDao.getTakesForClip(sourceId)
            if (remaining.isNotEmpty()) {
                takeDao.setActiveTake(sourceId, remaining.first().id)
            } else {
                // No content left on the source. Delete instances first so
                // the FK on sourceClipId doesn't block the source deletion.
                val instances = audioClipDao.getInstancesOf(sourceId)
                database.withTransaction {
                    for (instance in instances) audioClipDao.deleteClip(instance.id)
                    audioClipDao.deleteClip(sourceId)
                }
            }
        }
        pulseBus.emit(GroupKey.Audio(sourceId))
    }

    suspend fun renameTake(takeId: Long, name: String) {
        val take = takeDao.getTakeById(takeId) ?: return
        takeDao.updateDisplayName(takeId, name)
        pulseBus.emit(GroupKey.Audio(take.clipId))
    }

    suspend fun getTakesForClip(clipId: Long): List<TakeEntity> =
        takeDao.getTakesForClip(resolveSourceId(clipId))

    suspend fun getTakesForClips(clipIds: List<Long>): List<TakeEntity> {
        if (clipIds.isEmpty()) return emptyList()
        val resolvedIds = clipIds.map { resolveSourceId(it) }.distinct()
        return takeDao.getTakesForClips(resolvedIds)
    }

    suspend fun getActiveTake(clipId: Long): TakeEntity? =
        takeDao.getActiveTakeForClip(resolveSourceId(clipId))

    suspend fun updateTakeTrim(takeId: Long, trimStartMs: Long, trimEndMs: Long) {
        val take = takeDao.getTakeById(takeId) ?: return
        takeDao.updateTrim(takeId, trimStartMs, trimEndMs)
        pulseBus.emit(GroupKey.Audio(take.clipId))
    }

    // ── Recording helpers ────────────────────────────────────────────────

    /**
     * Find a clip at a given position on a track.
     * Returns the clip whose range contains [positionMs], or null.
     */
    suspend fun findClipAtPosition(trackId: Long, positionMs: Long): AudioClipEntity? {
        val clips = audioClipDao.getClipsForTrack(trackId)
        for (clip in clips) {
            val sourceId = clip.sourceClipId ?: clip.id
            val activeTake = takeDao.getActiveTakeForClip(sourceId) ?: continue
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
            val sourceId = clip.sourceClipId ?: clip.id
            val activeTake = takeDao.getActiveTakeForClip(sourceId) ?: continue
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
     *
     * Resolves each clip's `sourceClipId` so instance clips pull their
     * content from the source's active take. Instance-only state (offsetMs,
     * isMuted) stays on the instance itself.
     */
    suspend fun getActiveAudioSlotsForIdea(ideaId: Long): List<AudioPlaybackSlot> {
        val tracks = trackDao.getTracksForIdea(ideaId).filter { it.isAudio }
        if (tracks.isEmpty()) return emptyList()

        val trackIds = tracks.map { it.id }
        val allClips = audioClipDao.getClipsForTracks(trackIds)
        if (allClips.isEmpty()) return emptyList()

        val sourceIds = allClips.map { it.sourceClipId ?: it.id }.distinct()
        val allTakes = takeDao.getTakesForClips(sourceIds)
        val activeTakesByClip = allTakes.filter { it.isActive }.associateBy { it.clipId }
        val tracksById = tracks.associateBy { it.id }

        val slots = mutableListOf<AudioPlaybackSlot>()
        for (clip in allClips) {
            val sourceId = clip.sourceClipId ?: clip.id
            val activeTake = activeTakesByClip[sourceId] ?: continue
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

    // ── Duplicate / Split / Unlink / Rename ──────────────────────────────

    /**
     * Duplicate an audio clip. Place the new clip immediately after the source.
     *
     * When [linked] is true, the new clip becomes an instance that shares
     * content with the source (no take copy). When false, takes are cloned
     * so the new clip is independent (audio files are reused, not duplicated).
     *
     * The new clip is returned. Returns null if [clipId] doesn't exist.
     */
    suspend fun duplicateAudioClip(clipId: Long, linked: Boolean): AudioClipEntity? {
        val source = audioClipDao.getClipById(clipId) ?: return null
        val sourceId = source.sourceClipId ?: source.id
        val sourceClip = audioClipDao.getClipById(sourceId) ?: return null
        val activeTake = takeDao.getActiveTakeForClip(sourceId)
        val effectiveLen = activeTake?.let {
            (it.durationMs - it.trimStartMs - it.trimEndMs).coerceAtLeast(0L)
        } ?: 0L
        val nextIndex = (audioClipDao.getMaxSortIndex(source.trackId) ?: -1) + 1

        return if (linked) {
            val newId = audioClipDao.insertClip(
                sourceClip.copy(
                    id = 0L,
                    offsetMs = source.offsetMs + effectiveLen,
                    sortIndex = nextIndex,
                    createdAtEpochMs = System.currentTimeMillis(),
                    sourceClipId = sourceId
                )
            )
            pulseBus.emit(GroupKey.Audio(sourceId))
            audioClipDao.getClipById(newId)
        } else {
            database.withTransaction {
                val newId = audioClipDao.insertClip(
                    sourceClip.copy(
                        id = 0L,
                        offsetMs = source.offsetMs + effectiveLen,
                        sortIndex = nextIndex,
                        createdAtEpochMs = System.currentTimeMillis(),
                        sourceClipId = null
                    )
                )
                // Deep-copy takes from source so the new clip is independent.
                val sourceTakes = takeDao.getTakesForClip(sourceId)
                for (t in sourceTakes) {
                    takeDao.insertTake(t.copy(id = 0L, clipId = newId))
                }
                newId
            }.let { audioClipDao.getClipById(it) }
        }
    }

    /**
     * Split an audio clip at [sourceSplitMs] (milliseconds into the source's
     * content window, inclusive of any active-take trimStartMs offset).
     *
     * Operates on the active take only. Inactive takes stay on the left-half
     * source. If the clip is part of a linked group, every sibling spawns a
     * paired right-half instance; the original group becomes two groups of
     * the same size.
     *
     * Returns the new right-half source clip, or null on invalid input.
     */
    suspend fun splitAudioClip(clipId: Long, sourceSplitMs: Long): AudioClipEntity? {
        val clip = audioClipDao.getClipById(clipId) ?: return null
        val sourceId = clip.sourceClipId ?: clip.id
        val source = audioClipDao.getClipById(sourceId) ?: return null
        val activeTake = takeDao.getActiveTakeForClip(sourceId) ?: return null

        // sourceSplitMs must lie strictly inside the active take's trim window.
        if (sourceSplitMs <= activeTake.trimStartMs) return null
        if (sourceSplitMs >= activeTake.durationMs - activeTake.trimEndMs) return null

        val leftEffectiveLen = sourceSplitMs - activeTake.trimStartMs
        val instances = audioClipDao.getInstancesOf(sourceId)
        val now = System.currentTimeMillis()

        val newSourceId = database.withTransaction {
            // 1. Insert right-half source, positioned after the left half.
            val bId = audioClipDao.insertClip(
                source.copy(
                    id = 0L,
                    offsetMs = source.offsetMs + leftEffectiveLen,
                    sortIndex = source.sortIndex + 1,
                    createdAtEpochMs = now,
                    sourceClipId = null
                )
            )
            // 2. Right-half's active take: same audio file, trimStart=splitMs.
            takeDao.insertTake(
                activeTake.copy(
                    id = 0L,
                    clipId = bId,
                    trimStartMs = sourceSplitMs,
                    isActive = true,
                    sortIndex = 0
                )
            )
            // 3. Shrink the left-half source's active take.
            takeDao.updateTrim(
                activeTake.id,
                activeTake.trimStartMs,
                (activeTake.durationMs - sourceSplitMs).coerceAtLeast(0L)
            )
            // 4. Pair every instance of the left-half group.
            for (inst in instances) {
                audioClipDao.insertClip(
                    inst.copy(
                        id = 0L,
                        offsetMs = inst.offsetMs + leftEffectiveLen,
                        sortIndex = inst.sortIndex + 1,
                        createdAtEpochMs = now,
                        sourceClipId = bId
                    )
                )
            }
            bId
        }

        pulseBus.emit(GroupKey.Audio(sourceId))
        pulseBus.emit(GroupKey.Audio(newSourceId))
        return audioClipDao.getClipById(newSourceId)
    }

    /**
     * Unlink an instance clip from its group by deep-copying the source's
     * takes onto the instance. The instance becomes its own source.
     * No-op for source clips or standalone clips.
     */
    suspend fun unlinkAudioClip(clipId: Long) {
        val clip = audioClipDao.getClipById(clipId) ?: return
        val sourceId = clip.sourceClipId ?: return

        database.withTransaction {
            val sourceTakes = takeDao.getTakesForClip(sourceId)
            for (t in sourceTakes) {
                takeDao.insertTake(t.copy(id = 0L, clipId = clipId))
            }
            audioClipDao.updateSourceClipId(clipId, null)
        }
        pulseBus.emit(GroupKey.Audio(sourceId))
    }

    // ── Linked-clip helpers ──────────────────────────────────────────────

    /**
     * Returns the canonical source clip id for [clipId]. Returns [clipId]
     * itself if the clip is a source or has been deleted.
     */
    suspend fun resolveSourceId(clipId: Long): Long =
        audioClipDao.resolveSourceId(clipId) ?: clipId

    /** Number of clips in this clip's linked group (1 when standalone). */
    suspend fun getGroupSize(clipId: Long): Int =
        audioClipDao.getGroupSize(clipId).coerceAtLeast(1)

    /** Typed [ClipLinkage] for a clip, keyed by its resolved source id. */
    suspend fun getLinkage(clip: AudioClipEntity): ClipLinkage.Audio {
        val sourceId = clip.sourceClipId ?: clip.id
        val size = audioClipDao.getGroupSize(clip.id).coerceAtLeast(1)
        return ClipLinkage.Audio(GroupKey.Audio(sourceId), size)
    }

    /**
     * Promote the earliest (by createdAtEpochMs) instance of [sourceId] into
     * the new source. Re-points takes, updates remaining instances, deletes
     * the old source row. Atomic.
     */
    private suspend fun promoteSourceOnDelete(
        sourceId: Long,
        instances: List<AudioClipEntity>
    ) {
        if (instances.isEmpty()) return
        val promoted = instances.minByOrNull { it.createdAtEpochMs } ?: instances.first()

        database.withTransaction {
            // 1. Re-point every take from the dying source onto the promotee.
            takeDao.repointTakes(oldClipId = sourceId, newClipId = promoted.id)
            // 2. Promotee becomes a source.
            audioClipDao.updateSourceClipId(promoted.id, null)
            // 3. Every other instance now points at the promotee.
            audioClipDao.rewriteSourceClipId(oldSourceId = sourceId, newSourceId = promoted.id)
            // 4. Safe to drop the old source row (no remaining FK references).
            audioClipDao.deleteClip(sourceId)
        }
        pulseBus.emit(GroupKey.Audio(promoted.id))
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
