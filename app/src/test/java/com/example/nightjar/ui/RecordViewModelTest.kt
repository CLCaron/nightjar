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
    fun `stop and save emits open overview`() = runTest(testDispatcher.scheduler) {
        val recorder = mockk<WavRecorder>()
        val storage = mockk<RecordingStorage>()
        val repo = mockk<IdeaRepository>()
        val saved = File("saved.wav")
        val result = WavRecordingResult(file = saved, durationMs = 3000L)
        every { recorder.stop() } returns result
        coEvery { repo.createIdeaWithTrack(saved, 3000L) } returns 42L

        val viewModel = RecordViewModel(recorder, storage, repo)

        viewModel.effects.test {
            viewModel.onAction(RecordAction.StopAndSave)
            advanceUntilIdle()
            val effect = awaitItem()
            assertTrue(effect is RecordEffect.OpenOverview)
            assertEquals(42L, (effect as RecordEffect.OpenOverview).ideaId)
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals("saved.wav", viewModel.state.value.lastSavedFileName)
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
    fun `stop for background persists file name`() = runTest(testDispatcher.scheduler) {
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

        val viewModel = RecordViewModel(recorder, storage, repo)
        viewModel.startRecording()
        advanceUntilIdle()
        viewModel.onAction(RecordAction.StopForBackground)

        assertFalse(viewModel.state.value.isRecording)
        assertEquals("background.wav", viewModel.state.value.lastSavedFileName)
        verify { recorder.stop() }
    }
}
