package com.example.nightjar.audio

import android.util.Log
import com.example.nightjar.data.repository.IdeaRepository
import com.example.nightjar.data.repository.CaptureGroup
import com.example.nightjar.data.repository.SavedCaptureBatch
import com.example.nightjar.data.db.entity.TakeEntity
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
        coEvery { repo.saveCaptureBatch(any(), any()) } returns batch
        coEvery { repo.getCaptureGroups(42L) } returns listOf(CaptureGroupEntity(
            id = 1L, ideaId = 42L, clipId = 7L, displayName = "Original Idea",
            sortIndex = 0, latchedTakeId = 1L, inStudio = true))
        coEvery { repo.getCaptureTakes(7L) } returns batch.takes
        coEvery { repo.getCaptureTake(1L) } returns batch.takes.single()
        coEvery { repo.getIdeaById(42L) } returns IdeaEntity(id = 42L, title = "Idea")
        coEvery { repo.latchCaptureTake(any(), any()) } just Runs
        every { storage.getAudioFile(any()) } answers { File(firstArg<String>()) }
        coEvery { writer.write(any(), any()) } returns listOf(CaptureAudio(file, 2500L))
        capture = CaptureSession(engine, storage, repo, font, foreground, writer, notes)
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
        coVerify(exactly = 1) { repo.saveCaptureBatch(any(), any()) }
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
        coVerify(exactly = 0) { repo.saveCaptureBatch(any(), any()) }
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
        coEvery { repo.saveCaptureBatch(any(), any()) } throws IllegalStateException("disk busy")
        begin()
        runCurrent()
        capture.stop()
        advanceUntilIdle()
        assertEquals(file, capture.state.value.file)
        assertEquals(CapturePhase.FAILED, capture.state.value.phase)
        coEvery { repo.saveCaptureBatch(any(), any()) } returns batch
        capture.start(CaptureOptions())
        advanceUntilIdle()
        verify(exactly = 1) { engine.startRecording(any()) }
        assertEquals(CapturePhase.SAVED, capture.state.value.phase)
    }

    @Test fun `save remains busy until repository transaction finishes`() = runTest(dispatcher) {
        val saved = CompletableDeferred<SavedCaptureBatch>()
        coEvery { repo.saveCaptureBatch(any(), any()) } coAnswers { saved.await() }
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
        coVerify { repo.saveCaptureBatch(null, any()) }
        begin(); runCurrent(); capture.stop(); advanceUntilIdle()
        coVerify { repo.saveCaptureBatch(CaptureGroup(42L, 7L), any()) }
        capture.clearCompleted()
        assertNull(capture.state.value.ideaId)
        begin(); runCurrent(); capture.stop(); advanceUntilIdle()
        coVerify(exactly = 2) { repo.saveCaptureBatch(null, any()) }
    }

    @Test fun `failed batch cannot be cleared and prepared files are reused for retry`() = runTest(dispatcher) {
        coEvery { repo.saveCaptureBatch(any(), any()) } throws IllegalStateException("busy")
        begin(); runCurrent(); capture.stop(); advanceUntilIdle()
        capture.clearCompleted()
        assertTrue(capture.state.value.pendingSave)
        coEvery { repo.saveCaptureBatch(any(), any()) } returns batch
        capture.start(CaptureOptions()); advanceUntilIdle()
        coVerify(exactly = 1) { writer.write(any(), any()) }
        assertFalse(capture.state.value.pendingSave)
    }

    @Test fun `take selection never writes a new batch or changes active choice`() = runTest(dispatcher) {
        begin(); runCurrent(); capture.stop(); advanceUntilIdle()
        capture.selectTake(1L)
        assertEquals(1L, capture.state.value.selectedTakeId)
        coVerify(exactly = 1) { repo.saveCaptureBatch(any(), any()) }
    }

    @Test fun `segmentation failure retains boundaries and retries without restarting recording`() = runTest(dispatcher) {
        coEvery { writer.write(any(), any()) } throws java.io.IOException("full")
        begin(); runCurrent()
        every { engine.getRecordedDurationMs() } returns 900L
        capture.start(CaptureOptions())
        capture.stop(); advanceUntilIdle()
        assertTrue(capture.state.value.pendingSave)
        coVerify(exactly = 0) { repo.saveCaptureBatch(any(), any()) }
        coEvery { writer.write(any(), any()) } returns listOf(CaptureAudio(file, 2500L))
        capture.start(CaptureOptions()); advanceUntilIdle()
        coVerify(exactly = 2) { writer.write(file, listOf(900L)) }
        verify(exactly = 1) { engine.startRecording(any()) }
        assertEquals(CapturePhase.SAVED, capture.state.value.phase)
    }

    @Test fun `record during playback waits for alignment support without opening microphone`() = runTest(dispatcher) {
        begin(); runCurrent(); capture.stop(); advanceUntilIdle()
        every { engine.addLoopingTrack(any(), any(), any(), any(), any()) } returns true
        capture.playSelected()
        runCurrent()
        assertTrue(capture.state.value.playing)
        clearMocks(engine, answers = false, recordedCalls = true)
        capture.start(CaptureOptions()); runCurrent()
        verify(exactly = 0) { engine.startRecording(any()) }
        assertTrue(capture.state.value.playing)
        assertTrue(capture.state.value.error!!.contains("alignment"))
        capture.stopAudition()
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
        coEvery { repo.saveCaptureBatchForIdea(42L, null, any()) } returns batch
        capture = CaptureSession(engine, storage, repo, font, foreground, writer, realNotes)
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
        coVerify(exactly = 1) { repo.saveCaptureBatchForIdea(42L, null, any()) }
        coVerify(exactly = 0) { repo.saveCaptureBatch(any(), any()) }
        assertTrue(words.state.value.safeToLeaveIdea)
    }

}
