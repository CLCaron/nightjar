package com.example.nightjar.data.repository

import androidx.room.withTransaction
import com.example.nightjar.data.db.NightjarDatabase
import com.example.nightjar.data.db.dao.MidiClipDao
import com.example.nightjar.data.db.dao.MidiNoteDao
import com.example.nightjar.data.db.dao.TrackDao
import com.example.nightjar.data.db.entity.MidiClipEntity
import com.example.nightjar.data.db.entity.MidiNoteEntity
import com.example.nightjar.data.db.entity.TrackEntity
import com.example.nightjar.data.events.PulseBus
import com.example.nightjar.ui.studio.ClipLinkage
import com.example.nightjar.ui.studio.GroupKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * Repository for MIDI clip and note CRUD, and instrument configuration.
 *
 * Each MIDI track has one or more [MidiClipEntity] clips. Notes within
 * a clip use positions relative to the clip start (0ms = clip beginning).
 * Instrument selection (program/channel) lives on [TrackEntity].
 *
 * ## Linked clips
 * Instance clips (`sourceClipId != null`) share notes with their source.
 * Note reads and writes route through [resolveSourceId]; propagating
 * edits emit a [GroupKey.Midi] on [PulseBus].
 */
class MidiRepository(
    private val midiClipDao: MidiClipDao,
    private val midiNoteDao: MidiNoteDao,
    private val trackDao: TrackDao,
    private val database: NightjarDatabase,
    private val pulseBus: PulseBus
) {

    // -- Tracks --

    suspend fun getTrackById(trackId: Long): TrackEntity? =
        trackDao.getTrackById(trackId)

    // -- Clips --

    suspend fun getClipsForTrack(trackId: Long): List<MidiClipEntity> =
        midiClipDao.getClipsForTrack(trackId)

    fun observeClipsForTrack(trackId: Long): Flow<List<MidiClipEntity>> =
        midiClipDao.observeClipsForTrack(trackId)

    suspend fun getClipById(clipId: Long): MidiClipEntity? =
        midiClipDao.getClipById(clipId)

    /**
     * Ensure a MIDI track has at least one clip. Creates a default clip
     * at offset 0 if none exist. Returns all clips for the track.
     */
    suspend fun ensureClipExists(trackId: Long): List<MidiClipEntity> {
        val existing = midiClipDao.getClipsForTrack(trackId)
        if (existing.isNotEmpty()) return existing

        midiClipDao.insertClip(
            MidiClipEntity(trackId = trackId, offsetMs = 0L, sortIndex = 0)
        )
        return midiClipDao.getClipsForTrack(trackId)
    }

    /**
     * Duplicate a MIDI clip, placed immediately after the source.
     *
     * When [linked] is true, the new clip is an instance of the source (no
     * note copy). When false, notes are deep-copied so the new clip is
     * independent. Propagating on linked duplicates emits a group pulse.
     */
    suspend fun duplicateClip(
        clipId: Long,
        clipDurationMs: Long,
        linked: Boolean = false
    ): MidiClipEntity? {
        val clip = midiClipDao.getClipById(clipId) ?: return null
        val sourceId = clip.sourceClipId ?: clip.id
        val source = midiClipDao.getClipById(sourceId) ?: return null
        val maxSort = midiClipDao.getMaxSortIndex(clip.trackId) ?: 0
        val newOffset = clip.offsetMs + clipDurationMs

        return if (linked) {
            val newId = midiClipDao.insertClip(
                MidiClipEntity(
                    trackId = clip.trackId,
                    offsetMs = newOffset,
                    sortIndex = maxSort + 1,
                    sourceClipId = sourceId
                )
            )
            pulseBus.emit(GroupKey.Midi(sourceId))
            midiClipDao.getClipById(newId)
        } else {
            database.withTransaction {
                val newId = midiClipDao.insertClip(
                    MidiClipEntity(
                        trackId = clip.trackId,
                        offsetMs = newOffset,
                        sortIndex = maxSort + 1,
                        sourceClipId = null
                    )
                )
                val notes = midiNoteDao.getNotesForClip(sourceId)
                for (note in notes) {
                    midiNoteDao.insertNote(
                        note.copy(id = 0L, clipId = newId, trackId = clip.trackId)
                    )
                }
                newId
            }.let { midiClipDao.getClipById(it) }
        }
    }

    /**
     * Split a MIDI clip at [sourceSplitMs] (relative to source content start).
     *
     * Notes fully before the split point stay on the left source. Notes fully
     * after move to a new right source, with startMs shifted to the new origin.
     * Notes that cross the split are cut into two notes at the boundary. If
     * the clip is part of a linked group, instances pair automatically.
     */
    suspend fun splitMidiClip(clipId: Long, sourceSplitMs: Long): MidiClipEntity? {
        if (sourceSplitMs <= 0L) return null
        val clip = midiClipDao.getClipById(clipId) ?: return null
        val sourceId = clip.sourceClipId ?: clip.id
        val source = midiClipDao.getClipById(sourceId) ?: return null
        val instances = midiClipDao.getInstancesOf(sourceId)
        val sourceNotes = midiNoteDao.getNotesForClip(sourceId)

        val newId = database.withTransaction {
            val bId = midiClipDao.insertClip(
                MidiClipEntity(
                    trackId = source.trackId,
                    offsetMs = source.offsetMs + sourceSplitMs,
                    sortIndex = source.sortIndex + 1,
                    sourceClipId = null
                )
            )

            // Partition notes. Any note crossing the boundary becomes two.
            for (note in sourceNotes) {
                val end = note.startMs + note.durationMs
                when {
                    end <= sourceSplitMs -> {
                        // Left-only: stays on source.
                    }
                    note.startMs >= sourceSplitMs -> {
                        // Right-only: move to new source, shifted.
                        midiNoteDao.insertNote(
                            note.copy(
                                id = 0L,
                                clipId = bId,
                                trackId = source.trackId,
                                startMs = note.startMs - sourceSplitMs
                            )
                        )
                        midiNoteDao.deleteNote(note.id)
                    }
                    else -> {
                        // Crosses the boundary: left remainder shrinks; right copy starts at 0.
                        val leftDur = (sourceSplitMs - note.startMs).coerceAtLeast(0L)
                        val rightDur = (end - sourceSplitMs).coerceAtLeast(0L)
                        midiNoteDao.updateNoteTiming(note.id, note.startMs, leftDur)
                        midiNoteDao.insertNote(
                            note.copy(
                                id = 0L,
                                clipId = bId,
                                trackId = source.trackId,
                                startMs = 0L,
                                durationMs = rightDur
                            )
                        )
                    }
                }
            }

            for (inst in instances) {
                midiClipDao.insertClip(
                    MidiClipEntity(
                        trackId = inst.trackId,
                        offsetMs = inst.offsetMs + sourceSplitMs,
                        sortIndex = inst.sortIndex + 1,
                        sourceClipId = bId
                    )
                )
            }
            bId
        }

        pulseBus.emit(GroupKey.Midi(sourceId))
        pulseBus.emit(GroupKey.Midi(newId))
        return midiClipDao.getClipById(newId)
    }

    /**
     * Unlink an instance from its group by copying the source's notes onto
     * the instance and nulling out its sourceClipId.
     */
    suspend fun unlinkClip(clipId: Long) {
        val clip = midiClipDao.getClipById(clipId) ?: return
        val sourceId = clip.sourceClipId ?: return

        database.withTransaction {
            val sourceNotes = midiNoteDao.getNotesForClip(sourceId)
            for (n in sourceNotes) {
                midiNoteDao.insertNote(n.copy(id = 0L, clipId = clipId, trackId = clip.trackId))
            }
            midiClipDao.updateSourceClipId(clipId, null)
        }
        pulseBus.emit(GroupKey.Midi(sourceId))
    }

    suspend fun moveClip(clipId: Long, newOffsetMs: Long) {
        midiClipDao.updateClipOffset(clipId, newOffsetMs)
    }

    /**
     * Delete a clip.
     * - Instance: trivial; remove the row (notes are on the source).
     * - Source without instances: CASCADE deletes its notes.
     * - Source with instances: promote the earliest instance, re-point notes.
     */
    suspend fun deleteClip(clipId: Long) {
        val clip = midiClipDao.getClipById(clipId) ?: return
        if (clip.sourceClipId != null) {
            midiClipDao.deleteClip(clipId)
            return
        }
        val instances = midiClipDao.getInstancesOf(clipId)
        if (instances.isEmpty()) {
            midiClipDao.deleteClip(clipId)
        } else {
            promoteSourceOnDelete(clipId, instances)
        }
    }

    suspend fun insertClip(clip: MidiClipEntity): Long =
        midiClipDao.insertClip(clip)

    // -- Notes (clip-scoped) --

    suspend fun getNotesForClip(clipId: Long): List<MidiNoteEntity> =
        midiNoteDao.getNotesForClip(resolveSourceId(clipId))

    fun observeNotesForClip(clipId: Long): Flow<List<MidiNoteEntity>> = flow {
        // Resolve once per subscription; source membership is stable for an
        // existing clip (promotion on delete creates a new subscription).
        val sourceId = resolveSourceId(clipId)
        emitAll(midiNoteDao.observeNotesForClip(sourceId))
    }

    /**
     * Add a note. [clipId] may be a source or an instance; the note is
     * always written against the resolved source. Emits a pulse on the group.
     */
    suspend fun addNote(
        clipId: Long,
        trackId: Long,
        pitch: Int,
        startMs: Long,
        durationMs: Long,
        velocity: Float = 0.8f
    ): Long {
        val sourceId = resolveSourceId(clipId)
        val noteId = midiNoteDao.insertNote(
            MidiNoteEntity(
                trackId = trackId,
                clipId = sourceId,
                pitch = pitch,
                startMs = startMs,
                durationMs = durationMs,
                velocity = velocity
            )
        )
        pulseBus.emit(GroupKey.Midi(sourceId))
        return noteId
    }

    // -- Notes (track-scoped, for engine push and backwards compat) --

    suspend fun getNotesForTrack(trackId: Long): List<MidiNoteEntity> =
        midiNoteDao.getNotesForTrack(trackId)

    fun observeNotes(trackId: Long): Flow<List<MidiNoteEntity>> =
        midiNoteDao.observeNotesForTrack(trackId)

    suspend fun updateNoteTiming(noteId: Long, startMs: Long, durationMs: Long) {
        val note = midiNoteDao.getNoteById(noteId) ?: return
        midiNoteDao.updateNoteTiming(noteId, startMs, durationMs)
        pulseBus.emit(GroupKey.Midi(note.clipId))
    }

    suspend fun updateNotePitch(noteId: Long, pitch: Int) {
        val note = midiNoteDao.getNoteById(noteId) ?: return
        midiNoteDao.updateNotePitch(noteId, pitch)
        pulseBus.emit(GroupKey.Midi(note.clipId))
    }

    suspend fun updateNoteVelocity(noteId: Long, velocity: Float) {
        val note = midiNoteDao.getNoteById(noteId) ?: return
        midiNoteDao.updateNoteVelocity(noteId, velocity)
        pulseBus.emit(GroupKey.Midi(note.clipId))
    }

    suspend fun deleteNote(noteId: Long) {
        val note = midiNoteDao.getNoteById(noteId) ?: return
        midiNoteDao.deleteNote(noteId)
        pulseBus.emit(GroupKey.Midi(note.clipId))
    }

    suspend fun insertNotes(notes: List<MidiNoteEntity>): List<Long> =
        midiNoteDao.insertNotes(notes)

    suspend fun deleteNotes(noteIds: List<Long>) {
        midiNoteDao.deleteNotes(noteIds)
    }

    suspend fun getNotesByIds(noteIds: List<Long>): List<MidiNoteEntity> =
        midiNoteDao.getNotesByIds(noteIds)

    // -- Instrument --

    suspend fun setInstrument(trackId: Long, program: Int) {
        trackDao.updateMidiProgram(trackId, program)
    }

    // -- Channel assignment --

    /** Reserved MIDI channels: 9 = drums, 15 = preview. */
    private val reservedChannels = setOf(9, 15)

    /**
     * Find the next available MIDI channel for a new track in this idea.
     * Scans existing MIDI tracks and skips reserved channels.
     * Returns channel 0 with a warning if all 14 slots are used.
     */
    suspend fun nextAvailableChannel(ideaId: Long): Int {
        val tracks = trackDao.getTracksForIdea(ideaId)
        val usedChannels = tracks
            .filter { it.isMidi }
            .map { it.midiChannel }
            .toSet()

        for (ch in 0..15) {
            if (ch !in reservedChannels && ch !in usedChannels) return ch
        }
        // All 14 slots used -- reuse channel 0
        return 0
    }

    // -- BPM scaling --

    suspend fun scaleNotePositions(ideaId: Long, scaleFactor: Double) {
        midiNoteDao.scaleNotePositions(ideaId, scaleFactor)
    }

    // -- Track duration --

    /**
     * Recompute and persist the track's durationMs from all its MIDI clips and notes.
     * Duration = max(clip.offsetMs + note.startMs + note.durationMs) across all clips.
     * Instance clips resolve to their source's note list.
     */
    suspend fun recomputeTrackDuration(trackId: Long) {
        val clips = midiClipDao.getClipsForTrack(trackId)
        var maxEnd = 0L
        for (clip in clips) {
            val sourceId = clip.sourceClipId ?: clip.id
            val notes = midiNoteDao.getNotesForClip(sourceId)
            val clipEnd = notes.maxOfOrNull { clip.offsetMs + it.startMs + it.durationMs } ?: clip.offsetMs
            if (clipEnd > maxEnd) maxEnd = clipEnd
        }
        trackDao.updateDuration(trackId, maxEnd)
    }

    // -- Linked-clip helpers --

    /** Resolve a MIDI clip id to its source's id. */
    suspend fun resolveSourceId(clipId: Long): Long =
        midiClipDao.resolveSourceId(clipId) ?: clipId

    suspend fun getGroupSize(clipId: Long): Int =
        midiClipDao.getGroupSize(clipId).coerceAtLeast(1)

    suspend fun getLinkage(clip: MidiClipEntity): ClipLinkage.Midi {
        val sourceId = clip.sourceClipId ?: clip.id
        val size = midiClipDao.getGroupSize(clip.id).coerceAtLeast(1)
        return ClipLinkage.Midi(GroupKey.Midi(sourceId), size)
    }

    /**
     * Promote the earliest instance of [sourceId] into a new source.
     * Re-points notes, updates remaining instances, deletes the old source.
     */
    private suspend fun promoteSourceOnDelete(
        sourceId: Long,
        instances: List<MidiClipEntity>
    ) {
        if (instances.isEmpty()) return
        val promoted = instances.minByOrNull { it.id } ?: instances.first()

        database.withTransaction {
            midiNoteDao.repointNotes(oldClipId = sourceId, newClipId = promoted.id)
            midiClipDao.updateSourceClipId(promoted.id, null)
            midiClipDao.rewriteSourceClipId(oldSourceId = sourceId, newSourceId = promoted.id)
            midiClipDao.deleteClip(sourceId)
        }
        pulseBus.emit(GroupKey.Midi(promoted.id))
    }
}
