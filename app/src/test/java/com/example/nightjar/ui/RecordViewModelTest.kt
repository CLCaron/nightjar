package com.example.nightjar.ui.record

import app.cash.turbine.test
import com.example.nightjar.audio.OboeAudioEngine
import com.example.nightjar.data.repository.IdeaRepository
import com.example.nightjar.data.storage.RecordingStorage
import com.example.nightjar.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecordViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(testDispatcher)

    private fun mockAudioEngine(
        startResult: Boolean = true,
        awaitResult: Boolean = true,
        stopDurationMs: Long = 3000L
    ): OboeAudioEngine {
        val engine = mockk<OboeAudioEngine>(relaxed = true)
        every { engine.startRecording(any()) } returns startResult
        coEvery { engine.awaitFirstBuffer(any()) } returns awaitResult
        every { engine.stopRecording() } returns stopDurationMs
        every { engine.isRecordingActive() } returns false
        every { engine.getLatestPeakAmplitude() } returns 0f
        return engine
    }

    @Test
    fun `start recording updates state`() = runTest(testDispatcher.scheduler) {
        val engine = mockAudioEngine()
        val storage = mockk<RecordingStorage>()
        val repo = mockk<IdeaRepository>()
        val file = File("nightjar_20260218_120000.wav")
        every { storage.createRecordingFile(any(), any()) } returns file

        val viewModel = RecordViewModel(engine, storage, repo)
        viewModel.startRecording()

        assertTrue(viewModel.state.value.isRecording)

        advanceUntilIdle()

        assertTrue(viewModel.state.value.isRecording)
        assertEquals(null, viewModel.state.value.errorMessage)
        verify { engine.startRecording(file.absolutePath) }
    }

    @Test
    fun `start recording handles errors`() = runTest(testDispatcher.scheduler) {
        val engine = mockAudioEngine(startResult = false)
        val storage = mockk<RecordingStorage>()
        val repo = mockk<IdeaRepository>()
        val file = File("nightjar_20260218_120000.wav")
        every { storage.createRecordingFile(any(), any()) } returns file

        val viewModel = RecordViewModel(engine, storage, repo)
        viewModel.startRecording()
        advanceUntilIdle()

        assertFalse(viewModel.state.value.isRecording)
        assertEquals("Failed to start recording.", viewModel.state.value.errorMessage)
    }

    @Test
    fun `stop and save enters post-recording state`() = runTest(testDispatcher.scheduler) {
        val engine = mockAudioEngine(stopDurationMs = 3000L)
        val storage = mockk<RecordingStorage>()
        val repo = mockk<IdeaRepository>()
        val file = File("nightjar_20260218_120000.wav")
        every { storage.createRecordingFile(any(), any()) } returns file
        coEvery { repo.createIdeaWithTrack(file, 3000L) } returns 42L

        val viewModel = RecordViewModel(engine, storage, repo)

        // Start recording first so recordingFile is set
        viewModel.startRecording()
        advanceUntilIdle()

        // Now stop
        viewModel.onAction(RecordAction.StopAndSave)
        advanceUntilIdle()

        assertFalse(viewModel.state.value.isRecording)
        val post = viewModel.state.value.postRecording
        assertNotNull(post)
        assertEquals(42L, post!!.ideaId)
        assertEquals(file, post.audioFile)
    }

    @Test
    fun `stop and save handles engine errors`() = runTest(testDispatcher.scheduler) {
        val engine = mockk<OboeAudioEngine>(relaxed = true)
        every { engine.stopRecording() } throws IllegalStateException("stop failed")
        val storage = mockk<RecordingStorage>()
        val repo = mockk<IdeaRepository>()

        val viewModel = RecordViewModel(engine, storage, repo)

        viewModel.effects.test {
            viewModel.onAction(RecordAction.StopAndSave)
            advanceUntilIdle()
            val effect = awaitItem()
            assertTrue(effect is RecordEffect.ShowError)
            assertEquals("stop failed", (effect as RecordEffect.ShowError).message)
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals("stop failed", viewModel.state.value.errorMessage)
    }

    @Test
    fun `stop for background creates idea and enters post-recording`() = runTest(testDispatcher.scheduler) {
        val engine = mockAudioEngine(stopDurationMs = 1000L)
        val storage = mockk<RecordingStorage>()
        val repo = mockk<IdeaRepository>()
        val file = File("nightjar_20260218_120000.wav")
        every { storage.createRecordingFile(any(), any()) } returns file
        coEvery { repo.createIdeaWithTrack(file, 1000L) } returns 99L

        val viewModel = RecordViewModel(engine, storage, repo)
        viewModel.startRecording()
        advanceUntilIdle()
        viewModel.onAction(RecordAction.StopForBackground)
        advanceUntilIdle()

        assertFalse(viewModel.state.value.isRecording)
        val post = viewModel.state.value.postRecording
        assertNotNull(post)
        assertEquals(99L, post!!.ideaId)
        assertEquals(file, post.audioFile)
        verify { engine.stopRecording() }
    }

    @Test
    fun `GoToOverview emits OpenOverview and clears post-recording`() = runTest(testDispatcher.scheduler) {
        val engine = mockAudioEngine(stopDurationMs = 2000L)
        val storage = mockk<RecordingStorage>()
        val repo = mockk<IdeaRepository>()
        val file = File("saved.wav")
        every { storage.createRecordingFile(any(), any()) } returns file
        coEvery { repo.createIdeaWithTrack(file, 2000L) } returns 7L

        val viewModel = RecordViewModel(engine, storage, repo)

        // Start and stop to enter post-recording state
        viewModel.startRecording()
        advanceUntilIdle()
        viewModel.onAction(RecordAction.StopAndSave)
        advanceUntilIdle()
        assertNotNull(viewModel.state.value.postRecording)

        // Navigate to overview
        viewModel.effects.test {
            viewModel.onAction(RecordAction.GoToOverview)
            advanceUntilIdle()
            val effect = awaitItem()
            assertTrue(effect is RecordEffect.OpenOverview)
            assertEquals(7L, (effect as RecordEffect.OpenOverview).ideaId)
            cancelAndIgnoreRemainingEvents()
        }

        assertNull(viewModel.state.value.postRecording)
    }

    @Test
    fun `GoToStudio emits OpenStudio and clears post-recording`() = runTest(testDispatcher.scheduler) {
        val engine = mockAudioEngine(stopDurationMs = 2000L)
        val storage = mockk<RecordingStorage>()
        val repo = mockk<IdeaRepository>()
        val file = File("saved.wav")
        every { storage.createRecordingFile(any(), any()) } returns file
        coEvery { repo.createIdeaWithTrack(file, 2000L) } returns 7L

        val viewModel = RecordViewModel(engine, storage, repo)

        // Start and stop to enter post-recording state
        viewModel.startRecording()
        advanceUntilIdle()
        viewModel.onAction(RecordAction.StopAndSave)
        advanceUntilIdle()
        assertNotNull(viewModel.state.value.postRecording)

        // Navigate to studio
        viewModel.effects.test {
            viewModel.onAction(RecordAction.GoToStudio)
            advanceUntilIdle()
            val effect = awaitItem()
            assertTrue(effect is RecordEffect.OpenStudio)
            assertEquals(7L, (effect as RecordEffect.OpenStudio).ideaId)
            cancelAndIgnoreRemainingEvents()
        }

        assertNull(viewModel.state.value.postRecording)
    }

    @Test
    fun `StartRecording clears post-recording state`() = runTest(testDispatcher.scheduler) {
        val engine = mockAudioEngine(stopDurationMs = 2000L)
        val storage = mockk<RecordingStorage>()
        val repo = mockk<IdeaRepository>()
        val file = File("saved.wav")
        every { storage.createRecordingFile(any(), any()) } returns file
        coEvery { repo.createIdeaWithTrack(file, 2000L) } returns 7L

        val viewModel = RecordViewModel(engine, storage, repo)

        // Start and stop to enter post-recording state
        viewModel.startRecording()
        advanceUntilIdle()
        viewModel.onAction(RecordAction.StopAndSave)
        advanceUntilIdle()
        assertNotNull(viewModel.state.value.postRecording)

        // Start new recording — should clear post-recording
        viewModel.onAction(RecordAction.StartRecording)
        assertTrue(viewModel.state.value.isRecording)
        assertNull(viewModel.state.value.postRecording)
    }
}
