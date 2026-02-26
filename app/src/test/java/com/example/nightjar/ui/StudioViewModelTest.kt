package com.example.nightjar.ui

import app.cash.turbine.test
import com.example.nightjar.audio.AudioLatencyEstimator
import com.example.nightjar.audio.OboeAudioEngine
import com.example.nightjar.data.db.entity.IdeaEntity
import com.example.nightjar.data.db.entity.TrackEntity
import com.example.nightjar.data.repository.StudioRepository
import com.example.nightjar.data.repository.IdeaRepository
import com.example.nightjar.data.storage.RecordingStorage
import com.example.nightjar.ui.studio.StudioAction
import com.example.nightjar.ui.studio.StudioEffect
import com.example.nightjar.ui.studio.StudioViewModel
import com.example.nightjar.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StudioViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val idea = IdeaEntity(
        id = 1L,
        title = "My Song"
    )

    private val tracks = listOf(
        TrackEntity(
            id = 1L,
            ideaId = 1L,
            audioFileName = "original.m4a",
            displayName = "Track 1",
            sortIndex = 0,
            durationMs = 10_000L
        )
    )

    private fun mockAudioEngine(): OboeAudioEngine {
        val engine = mockk<OboeAudioEngine>(relaxed = true)
        every { engine.isPlaying } returns MutableStateFlow(false)
        every { engine.positionMs } returns MutableStateFlow(0L)
        every { engine.totalDurationMs } returns MutableStateFlow(0L)
        return engine
    }

    private fun createViewModel(
        ideaRepo: IdeaRepository = mockk(),
        studioRepo: StudioRepository = mockk(),
        audioEngine: OboeAudioEngine = mockAudioEngine()
    ): StudioViewModel = StudioViewModel(
        ideaRepo = ideaRepo,
        studioRepo = studioRepo,
        audioEngine = audioEngine,
        recordingStorage = mockk<RecordingStorage>(relaxed = true),
        latencyEstimator = mockk<AudioLatencyEstimator>(relaxed = true)
    )

    @Test
    fun `load populates title and tracks`() = runTest(mainDispatcherRule.dispatcher.scheduler) {
        val ideaRepo = mockk<IdeaRepository>()
        val studioRepo = mockk<StudioRepository>()

        coEvery { ideaRepo.getIdeaById(1L) } returns idea
        coEvery { studioRepo.ensureProjectInitialized(1L) } returns tracks

        val vm = createViewModel(ideaRepo, studioRepo)
        vm.onAction(StudioAction.Load(1L))
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals("My Song", state.ideaTitle)
        assertEquals(tracks, state.tracks)
        assertFalse(state.isLoading)
        assertNull(state.errorMessage)
    }

    @Test
    fun `load with unknown idea shows error`() = runTest(mainDispatcherRule.dispatcher.scheduler) {
        val ideaRepo = mockk<IdeaRepository>()
        val studioRepo = mockk<StudioRepository>()

        coEvery { ideaRepo.getIdeaById(99L) } returns null

        val vm = createViewModel(ideaRepo, studioRepo)
        vm.onAction(StudioAction.Load(99L))
        advanceUntilIdle()

        val state = vm.state.value
        assertFalse(state.isLoading)
        assertEquals("Idea not found.", state.errorMessage)
    }

    @Test
    fun `load failure emits ShowError effect`() = runTest(mainDispatcherRule.dispatcher.scheduler) {
        val ideaRepo = mockk<IdeaRepository>()
        val studioRepo = mockk<StudioRepository>()

        coEvery { ideaRepo.getIdeaById(1L) } throws RuntimeException("db error")

        val vm = createViewModel(ideaRepo, studioRepo)

        vm.effects.test {
            vm.onAction(StudioAction.Load(1L))

            val effect = awaitItem()
            assertTrue(effect is StudioEffect.ShowError)
            assertEquals("db error", (effect as StudioEffect.ShowError).message)

            assertEquals("db error", vm.state.value.errorMessage)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `load is idempotent for same ideaId`() = runTest(mainDispatcherRule.dispatcher.scheduler) {
        val ideaRepo = mockk<IdeaRepository>()
        val studioRepo = mockk<StudioRepository>()

        coEvery { ideaRepo.getIdeaById(1L) } returns idea
        coEvery { studioRepo.ensureProjectInitialized(1L) } returns tracks

        val vm = createViewModel(ideaRepo, studioRepo)
        vm.onAction(StudioAction.Load(1L))
        vm.onAction(StudioAction.Load(1L))
        advanceUntilIdle()

        coVerify(exactly = 1) { ideaRepo.getIdeaById(1L) }
    }

    @Test
    fun `initial state has playback fields at defaults`() = runTest(mainDispatcherRule.dispatcher.scheduler) {
        val vm = createViewModel()
        val state = vm.state.value

        assertFalse(state.isPlaying)
        assertEquals(0L, state.globalPositionMs)
        assertEquals(0L, state.totalDurationMs)
    }

    @Test
    fun `play action delegates to audio engine`() = runTest(mainDispatcherRule.dispatcher.scheduler) {
        val engine = mockAudioEngine()
        val vm = createViewModel(audioEngine = engine)

        vm.onAction(StudioAction.Play)

        verify { engine.play() }
    }

    @Test
    fun `pause action delegates to audio engine`() = runTest(mainDispatcherRule.dispatcher.scheduler) {
        val engine = mockAudioEngine()
        val vm = createViewModel(audioEngine = engine)

        vm.onAction(StudioAction.Pause)

        verify { engine.pause() }
    }

    @Test
    fun `seek action delegates to audio engine`() = runTest(mainDispatcherRule.dispatcher.scheduler) {
        val engine = mockAudioEngine()
        val vm = createViewModel(audioEngine = engine)

        vm.onAction(StudioAction.SeekFinished(5000L))

        verify { engine.seekTo(5000L) }
    }
}
