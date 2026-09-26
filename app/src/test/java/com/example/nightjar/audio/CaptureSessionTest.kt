package com.example.nightjar.audio

import android.util.Log
import com.example.nightjar.data.repository.IdeaRepository
import com.example.nightjar.data.repository.CaptureGroup
import com.example.nightjar.data.repository.SavedCaptureBatch
import com.example.nightjar.data.repository.CaptureBackingContext
import com.example.nightjar.data.db.entity.TakeEntity
import com.example.nightjar.data.db.entity.CaptureBackingEntity
import com.example.nightjar.data.db.entity.CaptureGroupEntity
import com.example.nightjar.data.db.entity.IdeaEntity
import com.example.nightjar.data.storage.RecordingStorage
import com.example.nightjar.util.MainDispatcherRule
import io.mockk.*
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CaptureSessionTest {
    private val dispatcher = StandardTestDispatcher()
    @get:Rule val main = MainDispatcherRule(dispatcher)
    private val engine = mockk<OboeAudioEngine>(relaxed = true)
    private val storage = mockk<RecordingStorage>()
    private val repo = mockk<IdeaRepository>()
    private val font = mockk<SoundFontManager>()
    private val foreground = mockk<CaptureForeground>(relaxed = true)
    private val file = File("retained.wav")
    private val writer = mockk<CaptureTakeWriter>()
    private val notes = mockk<com.example.nightjar.data.repository.NotesSession>(relaxed = true)
    private val latency = mockk<AudioLatencyEstimator>(relaxed = true)
    private val batch = SavedCaptureBatch(CaptureGroup(42L, 7L), listOf(TakeEntity(id = 1L,
        clipId = 7L, audioFileName = file.name, displayName = "Take 1", sortIndex = 0, durationMs = 2500L)))
    private lateinit var capture: CaptureSession
    private var active = false

    @Before fun setup() {
        mockkStatic(Log::class)
        every { Log.e(any(), any(), any()) } returns 0
        every { engine.isRecordingActive() } answers { active }
        every { engine.startRecording(any()) } answers { active = true; true }
        every { engine.stopRecording() } answers { active = false; 2500L }
        every { storage.createRecordingFile(any(), any()) } returns file
        coEvery { engine.awaitFirstBuffer(any()) } returns true
        coEvery { font.getSoundFontPath() } returns null
        coEvery { repo.saveCaptureBatch(any(), any(), any()) } returns batch
        coEvery { repo.getCaptureGroups(42L) } returns listOf(CaptureGroupEntity(
            id = 1L, ideaId = 42L, clipId = 7L, displayName = "Original Idea",
            sortIndex = 0, latchedTakeId = 1L, inStudio = true))
        coEvery { repo.getCaptureTakes(7L) } returns batch.takes
        coEvery { repo.getCaptureTake(1L) } returns batch.takes.single()
        coEvery { repo.getIdeaById(42L) } returns IdeaEntity(id = 42L, title = "Idea")
        coEvery { repo.latchCaptureTake(any(), any()) } just Runs
        every { storage.getAudioFile(any()) } answers { File(firstArg<String>()) }
        coEvery { writer.write(any(), any()) } returns listOf(CaptureAudio(file, 2500L))
        capture = CaptureSession(engine, storage, repo, font, foreground, writer, notes, latency)
    }

    @After fun cleanup() { unmockkAll() }

    private fun begin() {
        capture.start(CaptureOptions())
        capture.foregroundReady(capture.state.value.token)
    }

    @Test fun `input waits for foreground service and duplicate starts are ignored`() = runTest(dispatcher) {
        capture.start(CaptureOptions())
        val token = capture.state.value.token
        capture.start(CaptureOptions())
        runCurrent()
        verify(exactly = 1) { foreground.start(token) }
        verify(exactly = 0) { engine.startRecording(any()) }
        capture.foregroundReady(token)
        runCurrent()
        assertEquals(CapturePhase.RECORDING, capture.state.value.phase)
        capture.stop()
        advanceUntilIdle()
    }

    private fun prepareRelatedPair() {
        val melody = TakeEntity(id = 2L, clipId = 8L, audioFileName = "melody.wav",
            displayName = "Take 1", sortIndex = 0, durationMs = 5000L)
        coEvery { repo.getCaptureGroups(42L) } returns listOf(
            CaptureGroupEntity(id = 1L, ideaId = 42L, clipId = 7L,
                displayName = "Guitar", sortIndex = 0, latchedTakeId = 1L),
            CaptureGroupEntity(id = 2L, ideaId = 42L, clipId = 8L,
                displayName = "Melody", sortIndex = 1, latchedTakeId = 2L))
        coEvery { repo.getCaptureTake(2L) } returns melody
        coEvery { repo.getCaptureBackings(1L) } returns emptyList()
        coEvery { repo.getCaptureBackings(2L) } returns listOf(CaptureBackingEntity(
            2L, 1L, 110250L, 100000L, 4410L, 44100L, "estimated"))
        every { engine.addCaptureLoop(any(), any(), any(), any(), any()) } returns true
        capture.openIdea(42L)
    }

    @Test fun `selected related pair uses performance cycle and remembered backing phase`() = runTest(dispatcher) {
        prepareRelatedPair(); runCurrent()
        capture.playSelected(); runCurrent()
        assertTrue(capture.state.value.playing)
        verify { engine.addCaptureLoop(-1, any(), 5000L, 0L, 220500L) }
        verify { engine.addCaptureLoop(-2, any(), 2500L, 44100L, 220500L) }
        capture.start(CaptureOptions())
        verify(exactly = 0) { foreground.start(any()) }
        assertTrue(capture.state.value.playing)
        assertTrue(capture.state.value.error!!.contains("one backing take"))
        capture.stopAudition(); advanceUntilIdle()
    }

    @Test fun `unrelated selection remains latched without starting audio`() = runTest(dispatcher) {
        prepareRelatedPair(); runCurrent()
        coEvery { repo.getCaptureBackings(2L) } returns emptyList()
        capture.playSelected(); advanceUntilIdle()
        assertFalse(capture.state.value.playing)
        assertEquals(listOf(1L, 2L), capture.state.value.groups.map { it.latchedTakeId })
        verify(exactly = 0) { engine.play() }
        verify(exactly = 0) { engine.addCaptureLoop(any(), any(), any(), any(), any()) }
    }

    @Test fun `failure loading second source cleans up without playing the first alone`() = runTest(dispatcher) {
        prepareRelatedPair(); runCurrent()
        every { engine.addCaptureLoop(-2, any(), any(), any(), any()) } returns false
        capture.playSelected(); advanceUntilIdle()
        assertFalse(capture.state.value.playing)
        verify(exactly = 0) { engine.play() }
        verify(atLeast = 2) { engine.removeAllTracks() }
        assertEquals(listOf(1L, 2L), capture.state.value.groups.map { it.latchedTakeId })
    }

    @Test fun `reopening an Idea cannot record into the previous group while loading`() = runTest(dispatcher) {
        val groups = CompletableDeferred<List<CaptureGroupEntity>>()
        coEvery { repo.getCaptureGroups(42L) } coAnswers { groups.await() }
        capture.openIdea(42L)
        assertTrue(capture.state.value.loadingIdea)
        capture.start(CaptureOptions())
        verify(exactly = 0) { foreground.start(any()) }
        groups.complete(listOf(CaptureGroupEntity(id = 1L, ideaId = 42L,
            clipId = 7L, displayName = "Original Idea", sortIndex = 0)))
        advanceUntilIdle()
        assertFalse(capture.state.value.loadingIdea)
        assertEquals(7L, capture.state.value.groups.single().clipId)
    }

    @Test fun `stop before service readiness cannot reopen microphone`() = runTest(dispatcher) {
        capture.start(CaptureOptions())
        val token = capture.state.value.token
        capture.stop()
        capture.foregroundReady(token)
        advanceUntilIdle()
        verify(exactly = 0) { engine.startRecording(any()) }
        assertEquals(CapturePhase.IDLE, capture.state.value.phase)
    }

    @Test fun `stop from notification and screen concurrently saves only once`() = runTest(dispatcher) {
        begin()
        runCurrent()
        capture.stop()
        capture.stop()
        advanceUntilIdle()
        verify(exactly = 1) { engine.stopRecording() }
        coVerify(exactly = 1) { repo.saveCaptureBatch(any(), any(), any()) }
        assertEquals(42L, capture.state.value.ideaId)
        assertEquals(CapturePhase.SAVED, capture.state.value.phase)
    }

    @Test fun `stop during count in never opens write gate afterward`() = runTest(dispatcher) {
        capture.start(CaptureOptions(metronome = true, countInBars = 2))
        capture.foregroundReady(capture.state.value.token)
        runCurrent()
        assertTrue(capture.state.value.countingIn)
        every { engine.stopRecording() } answers { active = false; 0L }
        capture.stop()
        advanceUntilIdle()
        verify(exactly = 0) { engine.openWriteGate() }
        coVerify(exactly = 0) { repo.saveCaptureBatch(any(), any(), any()) }
        assertFalse(capture.state.value.busy)
    }

    @Test fun `first buffer timeout closes input and reports failure`() = runTest(dispatcher) {
        coEvery { engine.awaitFirstBuffer(any()) } returns false
        every { engine.stopRecording() } answers { active = false; 0L }
        begin()
        advanceUntilIdle()
        verify(exactly = 1) { engine.stopRecording() }
        verify(exactly = 0) { engine.openWriteGate() }
        assertEquals(CapturePhase.FAILED, capture.state.value.phase)
        assertNotNull(capture.state.value.error)
    }

    @Test fun `failed indexing retains file and retries without new capture`() = runTest(dispatcher) {
        coEvery { repo.saveCaptureBatch(any(), any(), any()) } throws IllegalStateException("disk busy")
        begin()
        runCurrent()
        capture.stop()
        advanceUntilIdle()
        assertEquals(file, capture.state.value.file)
        assertEquals(CapturePhase.FAILED, capture.state.value.phase)
        coEvery { repo.saveCaptureBatch(any(), any(), any()) } returns batch
        capture.start(CaptureOptions())
        advanceUntilIdle()
        verify(exactly = 1) { engine.startRecording(any()) }
        assertEquals(CapturePhase.SAVED, capture.state.value.phase)
    }

    @Test fun `save remains busy until repository transaction finishes`() = runTest(dispatcher) {
        val saved = CompletableDeferred<SavedCaptureBatch>()
        coEvery { repo.saveCaptureBatch(any(), any(), any()) } coAnswers { saved.await() }
        begin()
        runCurrent()
        capture.stop()
        runCurrent()
        capture.start(CaptureOptions())
        assertEquals(CapturePhase.SAVING, capture.state.value.phase)
        verify(exactly = 1) { engine.startRecording(any()) }
        saved.complete(batch)
        advanceUntilIdle()
        assertEquals(CapturePhase.SAVED, capture.state.value.phase)
    }

    @Test fun `long recording bounds visual history without stopping audio`() = runTest(dispatcher) {
        begin()
        runCurrent()
        advanceTimeBy(120_000)
        runCurrent()
        assertTrue(capture.state.value.recording)
        assertEquals(1200, capture.state.value.amplitudes.size)
        verify(exactly = 0) { engine.stopRecording() }
        capture.stop()
        advanceUntilIdle()
    }

    @Test fun `input interruption finalizes and preserves an honest stopped state`() = runTest(dispatcher) {
        begin()
        runCurrent()
        active = false
        advanceTimeBy(50)
        advanceUntilIdle()
        assertEquals(CapturePhase.SAVED, capture.state.value.phase)
        assertNotNull(capture.state.value.error)
        assertEquals(42L, capture.state.value.ideaId)
        verify(exactly = 1) { engine.stopRecording() }
    }

    @Test fun `stale service callback cannot affect the next recording`() = runTest(dispatcher) {
        begin()
        val oldToken = capture.state.value.token
        runCurrent()
        capture.stop()
        advanceUntilIdle()
        capture.start(CaptureOptions())
        val token = capture.state.value.token
        capture.foregroundReady(oldToken)
        capture.foregroundFailed(oldToken, "old service")
        assertEquals(CapturePhase.STARTING, capture.state.value.phase)
        capture.foregroundReady(token)
        runCurrent()
        assertTrue(capture.state.value.recording)
        capture.stop()
        advanceUntilIdle()
        verify(exactly = 2) { engine.startRecording(any()) }
    }
    @Test fun `repeated record marks boundaries without restarting input and appends after stop`() = runTest(dispatcher) {
        begin(); runCurrent()
        every { engine.getRecordedDurationMs() } returnsMany listOf(700L, 700L, 1600L)
        repeat(3) { capture.start(CaptureOptions()) }
        assertEquals(4, capture.state.value.takeNumber)
        assertEquals(3, capture.state.value.pendingTakeWaveforms.size)
        verify(exactly = 1) { engine.startRecording(any()) }
        verify(exactly = 0) { engine.stopRecording() }
        capture.clearCompleted()
        assertTrue(capture.state.value.recording)
        capture.stop(); advanceUntilIdle()
        assertTrue(capture.state.value.pendingTakeWaveforms.isEmpty())
        coVerify { writer.write(file, listOf(700L, 700L, 1600L)) }
        coVerify { repo.saveCaptureBatch(null, any(), any()) }
        begin(); runCurrent(); capture.stop(); advanceUntilIdle()
        coVerify { repo.saveCaptureBatch(CaptureGroup(42L, 7L), any(), any()) }
        capture.clearCompleted()
        assertNull(capture.state.value.ideaId)
        begin(); runCurrent(); capture.stop(); advanceUntilIdle()
        coVerify(exactly = 2) { repo.saveCaptureBatch(null, any(), any()) }
    }

    @Test fun `failed batch cannot be cleared and prepared files are reused for retry`() = runTest(dispatcher) {
        coEvery { repo.saveCaptureBatch(any(), any(), any()) } throws IllegalStateException("busy")
        begin(); runCurrent(); capture.stop(); advanceUntilIdle()
        capture.clearCompleted()
        assertTrue(capture.state.value.pendingSave)
        coEvery { repo.saveCaptureBatch(any(), any(), any()) } returns batch
        capture.start(CaptureOptions()); advanceUntilIdle()
        coVerify(exactly = 1) { writer.write(any(), any()) }
        assertFalse(capture.state.value.pendingSave)
    }

    @Test fun `take selection never writes a new batch or changes active choice`() = runTest(dispatcher) {
        begin(); runCurrent(); capture.stop(); advanceUntilIdle()
        capture.selectTake(1L)
        assertEquals(1L, capture.state.value.selectedTakeId)
        coVerify(exactly = 1) { repo.saveCaptureBatch(any(), any(), any()) }
    }

    @Test fun `segmentation failure retains boundaries and retries without restarting recording`() = runTest(dispatcher) {
        coEvery { writer.write(any(), any()) } throws java.io.IOException("full")
        begin(); runCurrent()
        every { engine.getRecordedDurationMs() } returns 900L
        capture.start(CaptureOptions())
        capture.stop(); advanceUntilIdle()
        assertTrue(capture.state.value.pendingSave)
        coVerify(exactly = 0) { repo.saveCaptureBatch(any(), any(), any()) }
        coEvery { writer.write(any(), any()) } returns listOf(CaptureAudio(file, 2500L))
        capture.start(CaptureOptions()); advanceUntilIdle()
        coVerify(exactly = 2) { writer.write(file, listOf(900L)) }
        verify(exactly = 1) { engine.startRecording(any()) }
        assertEquals(CapturePhase.SAVED, capture.state.value.phase)
    }

    @Test fun `record during playback keeps loop running until stop`() = runTest(dispatcher) {
        begin(); runCurrent(); capture.stop(); advanceUntilIdle()
        every { engine.addLoopingTrack(any(), any(), any(), any(), any()) } returns true
        capture.playSelected()
        runCurrent()
        assertTrue(capture.state.value.playing)
        clearMocks(engine, answers = false, recordedCalls = true)
        capture.start(CaptureOptions()); runCurrent()
        verify(exactly = 1) { foreground.start(capture.state.value.token) }
        assertTrue(capture.state.value.playing)
        verify(exactly = 0) { engine.pause() }
        capture.foregroundReady(capture.state.value.token)
        runCurrent()
        verify(exactly = 1) { engine.startRecording(any()) }
        verify(exactly = 0) { engine.pause() }
        capture.stop()
        advanceUntilIdle()
        verify(exactly = 1) { engine.stopRecording() }
        verify(atLeast = 1) { engine.pause() }
        assertFalse(capture.state.value.playing)
    }

    @Test fun `manual takes retain the same backing and their own unwrapped start`() = runTest(dispatcher) {
        begin(); runCurrent(); capture.stop(); advanceUntilIdle()
        every { engine.addLoopingTrack(any(), any(), any(), any(), any()) } returns true
        every { engine.getCaptureStartPlaybackFrame() } returns 88200L
        every { engine.getCapturedFrames() } returns 44100L
        every { latency.computeCompensationMs(0L, true) } returns 100L
        val parts = listOf(CaptureAudio(File("first.wav"), 1000L), CaptureAudio(File("second.wav"), 1500L))
        coEvery { writer.write(any(), any()) } returns parts
        var contexts: List<CaptureBackingContext?> = emptyList()
        coEvery { repo.saveCaptureBatch(any(), any(), any()) } coAnswers {
            contexts = thirdArg()
            batch
        }
        capture.playSelected(); runCurrent()
        capture.start(CaptureOptions())
        capture.foregroundReady(capture.state.value.token)
        runCurrent()
        capture.start(CaptureOptions())
        capture.stop()
        advanceUntilIdle()
        assertEquals(2, contexts.size)
        assertEquals(listOf(88200L, 132300L), contexts.map { it?.transportStartFrame })
        assertEquals(listOf(4410L, 4410L), contexts.map { it?.correctionFrames })
        assertEquals(listOf(1L, 1L), contexts.map { it?.backingTakeId })
    }

    @Test fun `lost backing stops and saves the microphone take`() = runTest(dispatcher) {
        begin(); runCurrent(); capture.stop(); advanceUntilIdle()
        every { engine.addLoopingTrack(any(), any(), any(), any(), any()) } returns true
        every { engine.isPlaying.value } returns false
        capture.playSelected(); runCurrent()
        capture.start(CaptureOptions())
        capture.foregroundReady(capture.state.value.token)
        runCurrent()
        advanceTimeBy(60)
        runCurrent()
        assertEquals(CapturePhase.SAVED, capture.state.value.phase)
        assertFalse(capture.state.value.playing)
        assertTrue(capture.state.value.error!!.contains("Backing playback stopped"))
        verify(exactly = 2) { engine.stopRecording() }
    }

    @Test fun `leaving before a take loads cannot start playback later`() = runTest(dispatcher) {
        begin(); runCurrent(); capture.stop(); advanceUntilIdle()
        val take = CompletableDeferred<TakeEntity>()
        coEvery { repo.getCaptureTake(1L) } coAnswers { take.await() }
        capture.playSelected()
        runCurrent()
        capture.stopAudition()
        take.complete(batch.takes.single())
        advanceUntilIdle()
        verify(exactly = 0) { engine.play() }
        assertFalse(capture.state.value.playing)
    }

    @Test fun `text creation and audio finalization share one Idea even when creation is delayed`() = runTest(dispatcher) {
        val draftStorage = mockk<com.example.nightjar.data.storage.NotesDraftStorage>(relaxed = true)
        val realNotes = com.example.nightjar.data.repository.NotesSession(repo, draftStorage)
        val created = CompletableDeferred<Long>()
        coEvery { repo.createEmptyIdea() } coAnswers { created.await() }
        coEvery { repo.saveNotesRevision(any(), any(), any()) } returns Unit
        coEvery { repo.saveCaptureBatchForIdea(42L, null, any(), any()) } returns batch
        capture = CaptureSession(engine, storage, repo, font, foreground, writer, realNotes, latency)
        val words = capture.writingDocument()
        words.edit("before the melody")
        begin(); runCurrent()
        assertTrue(capture.state.value.recording)
        assertFalse(capture.canStartNewIdea())
        capture.stop(); runCurrent()
        assertEquals(CapturePhase.SAVING, capture.state.value.phase)
        created.complete(42L); advanceUntilIdle()
        assertEquals(42L, capture.state.value.ideaId)
        assertEquals(42L, words.state.value.ideaId)
        coVerify(exactly = 1) { repo.createEmptyIdea() }
        coVerify(exactly = 1) { repo.saveCaptureBatchForIdea(42L, null, any(), any()) }
        coVerify(exactly = 0) { repo.saveCaptureBatch(any(), any(), any()) }
        assertTrue(words.state.value.safeToLeaveIdea)
    }

}
