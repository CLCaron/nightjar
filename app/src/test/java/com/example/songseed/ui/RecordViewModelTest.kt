package com.example.songseed.ui.record

import app.cash.turbine.test
import com.example.songseed.audio.AudioRecorder
import com.example.songseed.data.repository.IdeaRepository
import com.example.songseed.util.MainDispatcherRule
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
        val recorder = mockk<AudioRecorder>()
        val repo = mockk<IdeaRepository>()
        every { recorder.start() } returns File("recording.m4a")

        val viewModel = RecordViewModel(recorder, repo)
        viewModel.startRecording()

        assertTrue(viewModel.state.value.isRecording)
        assertEquals(null, viewModel.state.value.errorMessage)
        verify { recorder.start() }
    }

    @Test
    fun `start recording handles errors`() = runTest(testDispatcher.scheduler) {
        val recorder = mockk<AudioRecorder>()
        val repo = mockk<IdeaRepository>()
        every { recorder.start() } throws IllegalStateException("no mic")

        val viewModel = RecordViewModel(recorder, repo)
        viewModel.startRecording()

        assertFalse(viewModel.state.value.isRecording)
        assertEquals("no mic", viewModel.state.value.errorMessage)
    }

    @Test
    fun `stop and save emits open workspace`() = runTest(testDispatcher.scheduler) {
        val recorder = mockk<AudioRecorder>()
        val repo = mockk<IdeaRepository>()
        val saved = File("saved.m4a")
        every { recorder.stop() } returns saved
        coEvery { repo.createIdeaForRecordingFile(saved) } returns 42L

        val viewModel = RecordViewModel(recorder, repo)

        viewModel.effects.test {
            viewModel.onAction(RecordAction.StopAndSave)
            advanceUntilIdle()
            val effect = awaitItem()
            assertTrue(effect is RecordEffect.OpenWorkspace)
            assertEquals(42L, (effect as RecordEffect.OpenWorkspace).ideaId)
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals("saved.m4a", viewModel.state.value.lastSavedFileName)
    }

    @Test
    fun `stop and save handles recorder errors`() = runTest(testDispatcher.scheduler) {
        val recorder = mockk<AudioRecorder>()
        val repo = mockk<IdeaRepository>()
        every { recorder.stop() } throws IllegalStateException("stop failed")

        val viewModel = RecordViewModel(recorder, repo)

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
        val recorder = mockk<AudioRecorder>()
        val repo = mockk<IdeaRepository>()
        every { recorder.start() } returns File("recording.m4a")
        every { recorder.stop() } returns File("background.m4a")

        val viewModel = RecordViewModel(recorder, repo)
        viewModel.startRecording()
        viewModel.onAction(RecordAction.StopForBackground)

        assertFalse(viewModel.state.value.isRecording)
        assertEquals("background.m4a", viewModel.state.value.lastSavedFileName)
        verify { recorder.stop() }
    }
}