package com.example.nightjar.data.repository

import androidx.room.withTransaction
import com.example.nightjar.data.db.NightjarDatabase
import com.example.nightjar.data.db.dao.AudioClipDao
import com.example.nightjar.data.db.dao.ExploreDao
import com.example.nightjar.data.db.dao.TakeDao
import com.example.nightjar.data.db.dao.TrackDao
import com.example.nightjar.data.db.entity.ExploreCandidateEntity
import com.example.nightjar.data.db.entity.ExploreCaptureEntity
import com.example.nightjar.data.db.entity.ExploreSegmentEntity
import com.example.nightjar.data.db.entity.ExploreSegmentStatus
import com.example.nightjar.data.db.entity.ExploreSketchEntity
import com.example.nightjar.data.db.entity.IdeaSectionEntity
import com.example.nightjar.data.db.entity.TrackEntity
import com.example.nightjar.data.storage.RecordingStorage
import java.io.File
import kotlin.math.max
import kotlin.math.min

/** Repository for section-focused, non-destructive Explore sketches. */
class ExploreRepository(
    private val exploreDao: ExploreDao,
    private val trackDao: TrackDao,
    private val audioClipDao: AudioClipDao,
    private val takeDao: TakeDao,
    private val storage: RecordingStorage,
    private val database: NightjarDatabase
) {

    data class PlaybackSlot(
        val playbackId: Int,
        val trackId: Long,
        val audioFileName: String,
        val offsetMs: Long,
        val durationMs: Long,
        val trimStartMs: Long,
        val trimEndMs: Long,
        val volume: Float,
        val isMuted: Boolean
    )

    suspend fun getSections(ideaId: Long): List<IdeaSectionEntity> =
        exploreDao.getSectionsForIdea(ideaId)

    suspend fun getSketches(ideaId: Long): List<ExploreSketchEntity> =
        exploreDao.getSketchesForIdea(ideaId)

    suspend fun getSketchesForSection(sectionId: Long): List<ExploreSketchEntity> =
        exploreDao.getSketchesForSection(sectionId)

    suspend fun getSegmentsForSketch(sketchId: Long): List<ExploreSegmentEntity> =
        exploreDao.getSegmentsForSketch(sketchId)

    suspend fun getCandidatesForSegments(
        segmentIds: List<Long>
    ): List<ExploreCandidateEntity> =
        if (segmentIds.isEmpty()) emptyList() else exploreDao.getCandidatesForSegments(segmentIds)

    suspend fun createSectionFromLoop(
        ideaId: Long,
        displayName: String,
        startMs: Long,
        endMs: Long
    ): Long {
        require(endMs > startMs) { "Section end must be after the start." }
        val name = displayName.trim().ifBlank { "Section" }
        val sortIndex = exploreDao.getMaxSectionSortIndex(ideaId) + 1
        return exploreDao.insertSection(
            IdeaSectionEntity(
                ideaId = ideaId,
                displayName = name,
                startMs = startMs,
                endMs = endMs,
                sortIndex = sortIndex
            )
        )
    }

    suspend fun createSketchLane(
        sectionId: Long,
        trackId: Long,
        displayName: String? = null
    ): Long {
        val section = exploreDao.getSection(sectionId)
            ?: throw IllegalArgumentException("Section not found.")
        val track = trackDao.getTrackById(trackId)
            ?: throw IllegalArgumentException("Track not found.")
        require(track.isAudio) { "Explore sketches can only use audio tracks." }
        val existing = exploreDao.getSketchForSectionTrack(sectionId, trackId)
        if (existing != null) return existing.id

        if (hasNormalClipOverlap(trackId, section.startMs, section.endMs)) {
            throw IllegalStateException(
                "That track already has audio clips in this section. Choose a blank track."
            )
        }

        val name = displayName?.trim()?.ifBlank { null } ?: "${track.displayName} Sketch"
        return exploreDao.insertSketch(
            ExploreSketchEntity(
                ideaId = section.ideaId,
                sectionId = sectionId,
                trackId = trackId,
                displayName = name
            )
        )
    }

    suspend fun seedFullSectionCapture(
        sketchId: Long,
        audioFile: File,
        durationMs: Long,
        trimStartMs: Long
    ): Long {
        val sketch = exploreDao.getSketch(sketchId)
            ?: throw IllegalArgumentException("Sketch not found.")
        val section = exploreDao.getSection(sketch.sectionId)
            ?: throw IllegalArgumentException("Section not found.")
        val audibleDuration = (durationMs - trimStartMs).coerceAtLeast(0L)
        val sourceEnd = min(section.durationMs, audibleDuration).coerceAtLeast(0L)

        return database.withTransaction {
            exploreDao.deleteSegmentsForSketch(sketchId)
            val captureId = exploreDao.insertCapture(
                ExploreCaptureEntity(
                    ideaId = sketch.ideaId,
                    sectionId = sketch.sectionId,
                    trackId = sketch.trackId,
                    audioFileName = audioFile.name,
                    displayName = "Scratch",
                    durationMs = durationMs,
                    capturedStartMs = section.startMs,
                    capturedEndMs = section.endMs,
                    trimStartMs = trimStartMs
                )
            )
            val segmentId = exploreDao.insertSegment(
                ExploreSegmentEntity(
                    sketchId = sketchId,
                    startMs = section.startMs,
                    endMs = section.endMs,
                    status = ExploreSegmentStatus.KEEP,
                    sortIndex = 0
                )
            )
            val candidateId = exploreDao.insertCandidate(
                ExploreCandidateEntity(
                    segmentId = segmentId,
                    captureId = captureId,
                    sourceStartMs = 0L,
                    sourceEndMs = sourceEnd,
                    displayName = "Scratch",
                    sortIndex = 0
                )
            )
            exploreDao.selectCandidate(segmentId, candidateId)
            segmentId
        }
    }

    suspend fun markRegion(
        sketchId: Long,
        startMs: Long,
        endMs: Long,
        status: String
    ): Long {
        val sketch = exploreDao.getSketch(sketchId)
            ?: throw IllegalArgumentException("Sketch not found.")
        val section = exploreDao.getSection(sketch.sectionId)
            ?: throw IllegalArgumentException("Section not found.")
        val targetStart = startMs.coerceIn(section.startMs, section.endMs)
        val targetEnd = endMs.coerceIn(section.startMs, section.endMs)
        require(targetEnd > targetStart) { "Choose a longer region first." }

        val normalizedStatus = ExploreSegmentStatus.normalize(status)
        val originalSegments = exploreDao.getSegmentsForSketch(sketchId)
        var selectedRegionSegmentId = 0L

        database.withTransaction {
            for (segment in originalSegments) {
                val overlapStart = max(segment.startMs, targetStart)
                val overlapEnd = min(segment.endMs, targetEnd)
                if (overlapEnd <= overlapStart) continue

                val selectedCandidate = segment.selectedCandidateId
                    ?.let { exploreDao.getCandidate(it) }

                exploreDao.deleteSegment(segment.id)

                val pieces = listOf(
                    Piece(segment.startMs, overlapStart, segment.status),
                    Piece(overlapStart, overlapEnd, normalizedStatus),
                    Piece(overlapEnd, segment.endMs, segment.status)
                )

                for (piece in pieces) {
                    if (piece.endMs - piece.startMs < 50L) continue
                    val newSegmentId = insertSegmentWithCandidateCopy(
                        oldSegment = segment,
                        oldCandidate = selectedCandidate,
                        piece = piece
                    )
                    if (piece.startMs == overlapStart && piece.endMs == overlapEnd) {
                        selectedRegionSegmentId = newSegmentId
                    }
                }
            }

            if (selectedRegionSegmentId == 0L) {
                selectedRegionSegmentId = exploreDao.insertSegment(
                    ExploreSegmentEntity(
                        sketchId = sketchId,
                        startMs = targetStart,
                        endMs = targetEnd,
                        status = normalizedStatus,
                        sortIndex = originalSegments.size
                    )
                )
            }
        }

        return selectedRegionSegmentId
    }

    suspend fun addCandidateForSegment(
        segmentId: Long,
        audioFile: File,
        durationMs: Long,
        trimStartMs: Long,
        select: Boolean = true
    ): Long {
        val segment = exploreDao.getSegment(segmentId)
            ?: throw IllegalArgumentException("Segment not found.")
        val sketch = exploreDao.getSketch(segment.sketchId)
            ?: throw IllegalArgumentException("Sketch not found.")
        val nextIndex = exploreDao.getMaxCandidateSortIndex(segmentId) + 1
        val audibleDuration = (durationMs - trimStartMs).coerceAtLeast(0L)
        val sourceEnd = min(segment.durationMs, audibleDuration).coerceAtLeast(0L)

        return database.withTransaction {
            val captureId = exploreDao.insertCapture(
                ExploreCaptureEntity(
                    ideaId = sketch.ideaId,
                    sectionId = sketch.sectionId,
                    trackId = sketch.trackId,
                    audioFileName = audioFile.name,
                    displayName = "Pass ${nextIndex + 1}",
                    durationMs = durationMs,
                    capturedStartMs = segment.startMs,
                    capturedEndMs = segment.endMs,
                    trimStartMs = trimStartMs
                )
            )
            val candidateId = exploreDao.insertCandidate(
                ExploreCandidateEntity(
                    segmentId = segmentId,
                    captureId = captureId,
                    sourceStartMs = 0L,
                    sourceEndMs = sourceEnd,
                    displayName = "Pass ${nextIndex + 1}",
                    sortIndex = nextIndex
                )
            )
            if (select) {
                exploreDao.selectCandidate(segmentId, candidateId)
                exploreDao.updateSegmentStatus(segmentId, ExploreSegmentStatus.KEEP)
            }
            candidateId
        }
    }

    suspend fun selectCandidate(segmentId: Long, candidateId: Long) {
        val candidate = exploreDao.getCandidate(candidateId)
            ?: throw IllegalArgumentException("Candidate not found.")
        require(candidate.segmentId == segmentId) { "Candidate belongs to another segment." }
        exploreDao.selectCandidate(segmentId, candidateId)
        exploreDao.updateSegmentStatus(segmentId, ExploreSegmentStatus.KEEP)
    }

    suspend fun getPlaybackSlotsForIdea(
        ideaId: Long,
        tracks: List<TrackEntity>
    ): List<PlaybackSlot> {
        val sketches = exploreDao.getSketchesForIdea(ideaId)
        if (sketches.isEmpty()) return emptyList()

        val segments = exploreDao.getSegmentsForSketches(sketches.map { it.id })
        val selectedCandidateIds = segments.mapNotNull { it.selectedCandidateId }
        if (selectedCandidateIds.isEmpty()) return emptyList()

        val candidates = exploreDao.getCandidates(selectedCandidateIds)
        val captures = exploreDao.getCaptures(candidates.map { it.captureId }.distinct())
        val tracksById = tracks.associateBy { it.id }
        val sketchesById = sketches.associateBy { it.id }
        val candidatesById = candidates.associateBy { it.id }
        val capturesById = captures.associateBy { it.id }

        return segments.mapNotNull { segment ->
            val candidate = segment.selectedCandidateId?.let { candidatesById[it] }
                ?: return@mapNotNull null
            val capture = capturesById[candidate.captureId] ?: return@mapNotNull null
            val sketch = sketchesById[segment.sketchId] ?: return@mapNotNull null
            val track = tracksById[sketch.trackId] ?: return@mapNotNull null

            val trimStart = (capture.trimStartMs + candidate.sourceStartMs)
                .coerceIn(0L, capture.durationMs)
            val rawSourceEnd = capture.trimStartMs + candidate.sourceEndMs
            val trimEnd = (capture.durationMs - rawSourceEnd).coerceAtLeast(0L)
            PlaybackSlot(
                playbackId = -((candidate.id % Int.MAX_VALUE).toInt().coerceAtLeast(1)),
                trackId = sketch.trackId,
                audioFileName = capture.audioFileName,
                offsetMs = segment.startMs,
                durationMs = capture.durationMs,
                trimStartMs = trimStart,
                trimEndMs = trimEnd,
                volume = track.volume,
                isMuted = track.isMuted
            )
        }
    }

    suspend fun cleanupUnusedCaptureFiles(ideaId: Long) {
        val captures = exploreDao.getCapturesForIdea(ideaId)
        val sketches = exploreDao.getSketchesForIdea(ideaId)
        val segments = if (sketches.isEmpty()) {
            emptyList()
        } else {
            exploreDao.getSegmentsForSketches(sketches.map { it.id })
        }
        val candidates = if (segments.isEmpty()) {
            emptyList()
        } else {
            exploreDao.getCandidatesForSegments(segments.map { it.id })
        }
        val usedCaptureIds = candidates.map { it.captureId }.toSet()
        captures.filter { it.id !in usedCaptureIds }.forEach {
            storage.deleteAudioFile(it.audioFileName)
        }
    }

    private suspend fun insertSegmentWithCandidateCopy(
        oldSegment: ExploreSegmentEntity,
        oldCandidate: ExploreCandidateEntity?,
        piece: Piece
    ): Long {
        val newSegmentId = exploreDao.insertSegment(
            ExploreSegmentEntity(
                sketchId = oldSegment.sketchId,
                startMs = piece.startMs,
                endMs = piece.endMs,
                status = piece.status,
                sortIndex = oldSegment.sortIndex
            )
        )
        if (oldCandidate != null) {
            val sourceStart = oldCandidate.sourceStartMs +
                (piece.startMs - oldSegment.startMs).coerceAtLeast(0L)
            val sourceEnd = (sourceStart + (piece.endMs - piece.startMs))
                .coerceAtMost(oldCandidate.sourceEndMs)
            val candidateId = exploreDao.insertCandidate(
                oldCandidate.copy(
                    id = 0L,
                    segmentId = newSegmentId,
                    sourceStartMs = sourceStart,
                    sourceEndMs = sourceEnd
                )
            )
            exploreDao.selectCandidate(newSegmentId, candidateId)
        }
        return newSegmentId
    }

    private suspend fun hasNormalClipOverlap(
        trackId: Long,
        sectionStartMs: Long,
        sectionEndMs: Long
    ): Boolean {
        val clips = audioClipDao.getClipsForTrack(trackId)
        if (clips.isEmpty()) return false
        val takesByClip = takeDao.getTakesForClips(clips.map { it.id })
            .filter { it.isActive }
            .associateBy { it.clipId }

        return clips.any { clip ->
            val take = takesByClip[clip.sourceClipId ?: clip.id] ?: takesByClip[clip.id]
            val duration = take?.let {
                (it.durationMs - it.trimStartMs - it.trimEndMs).coerceAtLeast(0L)
            } ?: 0L
            val clipStart = clip.offsetMs
            val clipEnd = clipStart + duration
            clipStart < sectionEndMs && clipEnd > sectionStartMs
        }
    }

    private data class Piece(
        val startMs: Long,
        val endMs: Long,
        val status: String
    )
}
