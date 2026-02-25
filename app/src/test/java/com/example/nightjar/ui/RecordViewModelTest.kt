package com.example.nightjar.ui.record

import app.cash.turbine.test
import com.example.nightjar.audio.WavRecorder
import com.example.nightjar.audio.WavRecordingResult
import com.example.nightjar.data.repository.IdeaRepository
import com.example.nightjar.data.storage.RecordingStorage
import com.example.nightjar.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.every
import io.mockk.justRun
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

    @Test
    fun `start recording updates state`() = runTest(testDispatcher.scheduler) {
        val recorder = mockk<WavRecorder>()
        val storage = mockk<RecordingStorage>()
        val repo = mockk<IdeaRepository>()
        val file = File("nightjar_20260218_120000.wav")
        every { storage.createRecordingFile(any(), any()) } returns file
        justRun { recorder.start(file) }
        coEvery { recorder.awaitFirstBuffer() } returns Unit
        justRun { recorder.markWriting() }

        val viewModel = RecordViewModel(recorder, storage, repo)
        viewModel.startRecording()

        assertTrue(viewModel.state.value.isRecording)

        advanceUntilIdle()

        assertTrue(viewModel.state.value.isRecording)
        assertEquals(null, viewModel.state.value.errorMessage)
        verify { recorder.start(file) }
    }

    @Test
    fun `start recording handles errors`() = runTest(testDispatcher.scheduler) {
        val recorder = mockk<WavRecorder>()
        val storage = mockk<RecordingStorage>()
        val repo = mockk<IdeaRepository>()
        val file = File("nightjar_20260218_120000.wav")
        every { storage.createRecordingFile(any(), any()) } returns file
        every { recorder.start(file) } throws IllegalStateException("no mic")

        val viewModel = RecordViewModel(recorder, storage, repo)
        viewModel.startRecording()
        advanceUntilIdle()

        assertFalse(viewModel.state.value.isRecording)
        assertEquals("no mic", viewModel.state.value.errorMessage)
    }

    @Test
    fun `stop and save enters post-recording state`() = runTest(testDispatcher.scheduler) {
        val recorder = mockk<WavRecorder>()
        val storage = mockk<RecordingStorage>()
        val repo = mockk<IdeaRepository>()
        val saved = File("saved.wav")
        val result = WavRecordingResult(file = saved, durationMs = 3000L)
        every { recorder.stop() } returns result
        coEvery { repo.createIdeaWithTrack(saved, 3000L) } returns 42L

        val viewModel = RecordViewModel(recorder, storage, repo)
        viewModel.onAction(RecordAction.StopAndSave)
        advanceUntilIdle()

        assertFalse(viewModel.state.value.isRecording)
        val post = viewModel.state.value.postRecording
        assertNotNull(post)
        assertEquals(42L, post!!.ideaId)
        assertEquals(saved, post.audioFile)
    }

    @Test
    fun `stop and save handles recorder errors`() = runTest(testDispatcher.scheduler) {
        val recorder = mockk<WavRecorder>()
        val storage = mockk<RecordingStorage>()
        val repo = mockk<IdeaRepository>()
        every { recorder.stop() } throws IllegalStateException("stop failed")

        val viewModel = RecordViewModel(recorder, storage, repo)

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
        val recorder = mockk<WavRecorder>()
        val storage = mockk<RecordingStorage>()
        val repo = mockk<IdeaRepository>()
        val file = File("nightjar_20260218_120000.wav")
        every { storage.createRecordingFile(any(), any()) } returns file
        justRun { recorder.start(file) }
        coEvery { recorder.awaitFirstBuffer() } returns Unit
        justRun { recorder.markWriting() }

        val bgFile = File("background.wav")
        val result = WavRecordingResult(file = bgFile, durationMs = 1000L)
        every { recorder.stop() } returns result
        coEvery { repo.createIdeaWithTrack(bgFile, 1000L) } returns 99L

        val viewModel = RecordViewModel(recorder, storage, repo)
        viewModel.startRecording()
        advanceUntilIdle()
        viewModel.onAction(RecordAction.StopForBackground)
        advanceUntilIdle()

        assertFalse(viewModel.state.value.isRecording)
        val post = viewModel.state.value.postRecording
        assertNotNull(post)
        assertEquals(99L, post!!.ideaId)
        assertEquals(bgFile, post.audioFile)
        verify { recorder.stop() }
    }

    @Test
    fun `GoToOverview emits OpenOverview and clears post-recording`() = runTest(testDispatcher.scheduler) {
        val recorder = mockk<WavRecorder>()
        val storage = mockk<RecordingStorage>()
        val repo = mockk<IdeaRepository>()
        val saved = File("saved.wav")
        val result = WavRecordingResult(file = saved, durationMs = 2000L)
        every { recorder.stop() } returns result
        coEvery { repo.createIdeaWithTrack(saved, 2000L) } returns 7L

        val viewModel = RecordViewModel(recorder, storage, repo)

        // Enter post-recording state
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
        val recorder = mockk<WavRecorder>()
        val storage = mockk<RecordingStorage>()
        val repo = mockk<IdeaRepository>()
        val saved = File("saved.wav")
        val result = WavRecordingResult(file = saved, durationMs = 2000L)
        every { recorder.stop() } returns result
        coEvery { repo.createIdeaWithTrack(saved, 2000L) } returns 7L

        val viewModel = RecordViewModel(recorder, storage, repo)

        // Enter post-recording state
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
        val recorder = mockk<WavRecorder>()
        val storage = mockk<RecordingStorage>()
        val repo = mockk<IdeaRepository>()
        val saved = File("saved.wav")
        val result = WavRecordingResult(file = saved, durationMs = 2000L)
        every { recorder.stop() } returns result
        coEvery { repo.createIdeaWithTrack(saved, 2000L) } returns 7L

        val newFile = File("new.wav")
        every { storage.createRecordingFile(any(), any()) } returns newFile
        justRun { recorder.start(newFile) }
        coEvery { recorder.awaitFirstBuffer() } returns Unit
        justRun { recorder.markWriting() }

        val viewModel = RecordViewModel(recorder, storage, repo)

        // Enter post-recording state
        viewModel.onAction(RecordAction.StopAndSave)
        advanceUntilIdle()
        assertNotNull(viewModel.state.value.postRecording)

        // Start new recording — should clear post-recording
        viewModel.onAction(RecordAction.StartRecording)
        assertTrue(viewModel.state.value.isRecording)
        assertNull(viewModel.state.value.postRecording)
    }
}
