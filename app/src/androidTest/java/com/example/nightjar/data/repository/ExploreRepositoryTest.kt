package com.example.nightjar.data.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.nightjar.data.db.NightjarDatabase
import com.example.nightjar.data.db.entity.IdeaEntity
import com.example.nightjar.data.db.entity.TrackEntity
import com.example.nightjar.data.storage.RecordingStorage
import java.io.File
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExploreRepositoryTest {

    private lateinit var db: NightjarDatabase
    private lateinit var repository: ExploreRepository
    private lateinit var storage: RecordingStorage

    private var ideaId: Long = 0L
    private var trackId: Long = 0L
    private val createdFiles = mutableListOf<File>()

    @Before
    fun setUp() = runTest {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, NightjarDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        storage = RecordingStorage(context)
        repository = ExploreRepository(
            exploreDao = db.exploreDao(),
            trackDao = db.trackDao(),
            audioClipDao = db.audioClipDao(),
            takeDao = db.takeDao(),
            storage = storage,
            database = db
        )

        ideaId = db.ideaDao().insertIdea(IdeaEntity(title = "Explore test"))
        trackId = db.trackDao().insertTrack(
            TrackEntity(
                ideaId = ideaId,
                audioFileName = "original.wav",
                displayName = "Original",
                sortIndex = 0,
                durationMs = 10_000L
            )
        )
    }

    @After
    fun tearDown() {
        createdFiles.forEach(File::delete)
        db.close()
    }

    @Test
    fun createSectionAndSketch_persistsExpectedHierarchy() = runTest {
        val sectionId = createSection()
        val sketchId = repository.createSketchLane(sectionId, trackId)

        val sections = repository.getSections(ideaId)
        val sketches = repository.getSketchesForSection(sectionId)

        assertEquals(listOf(sectionId), sections.map { it.id })
        assertEquals(listOf(sketchId), sketches.map { it.id })
        assertEquals(trackId, sketches.single().trackId)
    }

    @Test
    fun seedFullSectionCapture_createsSelectedPlayableVersion() = runTest {
        val sketchId = createSketch()
        val audioFile = audioFile()

        val segmentId = repository.seedFullSectionCapture(
            sketchId = sketchId,
            audioFile = audioFile,
            durationMs = 1_100L,
            trimStartMs = 100L
        )

        val segment = repository.getSegmentsForSketch(sketchId).single()
        val candidate = repository.getCandidatesForSegments(listOf(segmentId)).single()
        val track = db.trackDao().getTrackById(trackId)!!
        val slot = repository.getPlaybackSlotsForIdea(ideaId, listOf(track)).single()

        assertEquals(segmentId, segment.id)
        assertEquals(candidate.id, segment.selectedCandidateId)
        assertEquals(audioFile.name, slot.audioFileName)
        assertEquals(SECTION_START_MS, slot.offsetMs)
        assertEquals(100L, slot.trimStartMs)
        assertEquals(0L, slot.trimEndMs)
    }

    @Test
    fun addCandidate_appendsAndSelectsWithoutDeletingEarlierCandidate() = runTest {
        val sketchId = createSketch()
        val firstFile = audioFile()
        val firstSegmentId = repository.seedFullSectionCapture(
            sketchId = sketchId,
            audioFile = firstFile,
            durationMs = 1_000L,
            trimStartMs = 0L
        )
        val firstCandidate = repository.getCandidatesForSegments(listOf(firstSegmentId)).single()

        val secondCandidateId = repository.addCandidateForSegment(
            segmentId = firstSegmentId,
            audioFile = audioFile(),
            durationMs = 1_000L,
            trimStartMs = 0L
        )

        val candidates = repository.getCandidatesForSegments(listOf(firstSegmentId))
        val segment = repository.getSegmentsForSketch(sketchId).single()

        assertEquals(2, candidates.size)
        assertTrue(candidates.any { it.id == firstCandidate.id })
        assertTrue(candidates.any { it.id == secondCandidateId })
        assertEquals(secondCandidateId, segment.selectedCandidateId)
    }

    @Test
    fun selectCandidate_changesPlaybackWithoutRemovingAlternates() = runTest {
        val sketchId = createSketch()
        val firstFile = audioFile()
        val segmentId = repository.seedFullSectionCapture(
            sketchId = sketchId,
            audioFile = firstFile,
            durationMs = 1_000L,
            trimStartMs = 0L
        )
        val firstCandidateId = repository.getCandidatesForSegments(listOf(segmentId)).single().id
        repository.addCandidateForSegment(
            segmentId = segmentId,
            audioFile = audioFile(),
            durationMs = 1_000L,
            trimStartMs = 0L
        )

        repository.selectCandidate(segmentId, firstCandidateId)

        val candidates = repository.getCandidatesForSegments(listOf(segmentId))
        val segment = repository.getSegmentsForSketch(sketchId).single()
        val track = db.trackDao().getTrackById(trackId)!!
        val playback = repository.getPlaybackSlotsForIdea(ideaId, listOf(track)).single()

        assertEquals(2, candidates.size)
        assertEquals(firstCandidateId, segment.selectedCandidateId)
        assertEquals(firstFile.name, playback.audioFileName)
    }

    @Test
    fun markRegion_referencesOneImmutableCaptureFromEveryReplacementSegment() = runTest {
        val sketchId = createSketch()
        val segmentId = repository.seedFullSectionCapture(
            sketchId = sketchId,
            audioFile = audioFile(),
            durationMs = 1_000L,
            trimStartMs = 0L
        )
        val originalCaptureId = repository.getCandidatesForSegments(listOf(segmentId))
            .single()
            .captureId

        val selectedRegionId = repository.markRegion(
            sketchId = sketchId,
            startMs = 1_200L,
            endMs = 1_800L,
            status = "try"
        )

        val segments = repository.getSegmentsForSketch(sketchId)
        val candidates = repository.getCandidatesForSegments(segments.map { it.id })

        assertEquals(3, segments.size)
        assertEquals(selectedRegionId, segments.single { it.status == "try" }.id)
        assertEquals(3, candidates.size)
        assertTrue(candidates.all { it.captureId == originalCaptureId })
        assertNotNull(db.exploreDao().getCapture(originalCaptureId))
    }

    @Test
    fun cleanupUnusedCaptureFiles_deletesOnlyTheOrphanedPhysicalFile() = runTest {
        val sketchId = createSketch()
        val orphanedFile = audioFile(writeContent = true)
        repository.seedFullSectionCapture(
            sketchId = sketchId,
            audioFile = orphanedFile,
            durationMs = 1_000L,
            trimStartMs = 0L
        )
        val selectedFile = audioFile(writeContent = true)
        repository.seedFullSectionCapture(
            sketchId = sketchId,
            audioFile = selectedFile,
            durationMs = 1_000L,
            trimStartMs = 0L
        )

        repository.cleanupUnusedCaptureFiles(ideaId)

        assertFalse(orphanedFile.exists())
        assertTrue(selectedFile.exists())
    }

    private suspend fun createSection(): Long = repository.createSectionFromLoop(
        ideaId = ideaId,
        displayName = "Part",
        startMs = SECTION_START_MS,
        endMs = SECTION_END_MS
    )

    private suspend fun createSketch(): Long =
        repository.createSketchLane(createSection(), trackId)

    private fun audioFile(writeContent: Boolean = false): File {
        val file = storage.getAudioFile("explore-test-${UUID.randomUUID()}.wav")
        if (writeContent) {
            file.parentFile?.mkdirs()
            file.writeBytes(byteArrayOf(1, 2, 3))
        }
        createdFiles += file
        return file
    }

    private companion object {
        const val SECTION_START_MS = 1_000L
        const val SECTION_END_MS = 2_000L
    }
}
