package com.example.nightjar.ui

import app.cash.turbine.test
import com.example.nightjar.data.db.entity.IdeaEntity
import com.example.nightjar.data.db.entity.TrackEntity
import com.example.nightjar.data.repository.StudioRepository
import com.example.nightjar.data.repository.IdeaRepository
import com.example.nightjar.player.StudioPlaybackManager
import com.example.nightjar.ui.studio.StudioAction
import com.example.nightjar.ui.studio.StudioEffect
import com.example.nightjar.ui.studio.StudioViewModel
import com.example.nightjar.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
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

// TODO: Kotlin K2 compiler internal error when MockK mocks StudioPlaybackManager
//  (has @Inject + @ApplicationContext). Fix by extracting an interface for the manager,
//  or by upgrading Kotlin past 2.0.21 where the K2 FIR bug is resolved.
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

    private fun mockPlaybackManager(): StudioPlaybackManager {
        val manager = mockk<StudioPlaybackManager>(relaxed = true)
        every { manager.isPlaying } returns MutableStateFlow(false)
        every { manager.globalPositionMs } returns MutableStateFlow(0L)
        every { manager.totalDurationMs } returns MutableStateFlow(0L)
        every { manager.setScope(any()) } just runs
        return manager
    }

    @Test
    fun `load populates title and tracks`() = runTest(mainDispatcherRule.dispatcher.scheduler) {
        val ideaRepo = mockk<IdeaRepository>()
        val studioRepo = mockk<StudioRepository>()

        coEvery { ideaRepo.getIdeaById(1L) } returns idea
        coEvery { studioRepo.ensureProjectInitialized(1L) } returns tracks

        val vm = StudioViewModel(ideaRepo, studioRepo, mockPlaybackManager())
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

        val vm = StudioViewModel(ideaRepo, studioRepo, mockPlaybackManager())
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

        val vm = StudioViewModel(ideaRepo, studioRepo, mockPlaybackManager())

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

        val vm = StudioViewModel(ideaRepo, studioRepo, mockPlaybackManager())
        vm.onAction(StudioAction.Load(1L))
        vm.onAction(StudioAction.Load(1L))
        advanceUntilIdle()

        coVerify(exactly = 1) { ideaRepo.getIdeaById(1L) }
    }

    @Test
    fun `initial state has playback fields at defaults`() = runTest(mainDispatcherRule.dispatcher.scheduler) {
        val vm = StudioViewModel(mockk(), mockk(), mockPlaybackManager())
        val state = vm.state.value

        assertFalse(state.isPlaying)
        assertEquals(0L, state.globalPositionMs)
        assertEquals(0L, state.totalDurationMs)
    }

    @Test
    fun `play action delegates to playback manager`() = runTest(mainDispatcherRule.dispatcher.scheduler) {
        val manager = mockPlaybackManager()
        val vm = StudioViewModel(mockk(), mockk(), manager)

        vm.onAction(StudioAction.Play)

        verify { manager.play() }
    }

    @Test
    fun `pause action delegates to playback manager`() = runTest(mainDispatcherRule.dispatcher.scheduler) {
        val manager = mockPlaybackManager()
        val vm = StudioViewModel(mockk(), mockk(), manager)

        vm.onAction(StudioAction.Pause)

        verify { manager.pause() }
    }

    @Test
    fun `seek action delegates to playback manager`() = runTest(mainDispatcherRule.dispatcher.scheduler) {
        val manager = mockPlaybackManager()
        val vm = StudioViewModel(mockk(), mockk(), manager)

        vm.onAction(StudioAction.SeekFinished(5000L))

        verify { manager.seekTo(5000L) }
    }
}
