package com.example.nightjar.ui.library

import app.cash.turbine.test
import com.example.nightjar.audio.OboeAudioEngine
import com.example.nightjar.data.db.entity.IdeaEntity
import com.example.nightjar.data.db.entity.TagEntity
import com.example.nightjar.data.repository.IdeaRepository
import com.example.nightjar.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    // TODO: OboeAudioEngine has @Inject constructor + @ApplicationContext --
    //  MockK K2 bug may prevent mocking.
    private val audioEngine = mockk<OboeAudioEngine>(relaxed = true)

    @Test
    fun `init loads tags and observes newest ideas`() = runTest(mainDispatcherRule.dispatcher.scheduler) {
        val repo = mockk<IdeaRepository>()
        val tags = listOf(TagEntity(id = 1, name = "Rock", nameNormalized = "rock"))
        val ideas = listOf(IdeaEntity(id = 2, title = "Idea", isFavorite = false))

        coEvery { repo.getAllUsedTags() } returns tags
        coEvery { repo.getIdeaDurations() } returns emptyMap()
        every { repo.observeIdeasNewest() } returns flowOf(ideas)

        val viewModel = LibraryViewModel(repo, audioEngine)
        advanceUntilIdle()

        viewModel.state.test {
            val state = awaitItem()
            assertEquals(tags, state.usedTags)
            assertEquals(ideas, state.ideas)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `select tag observes ideas for that tag`() = runTest(mainDispatcherRule.dispatcher.scheduler) {
        val repo = mockk<IdeaRepository>()
        val filteredIdeas = listOf(
            IdeaEntity(
                id = 3,
                title = "Tagged",
                notes = "",
                isFavorite = false
            )
        )

        coEvery { repo.getAllUsedTags() } returns emptyList()
        coEvery { repo.getIdeaDurations() } returns emptyMap()
        every { repo.observeIdeasNewest() } returns flowOf(emptyList())
        every { repo.observeIdeasForTag("rock") } returns flowOf(filteredIdeas)

        val viewModel = LibraryViewModel(repo, audioEngine)
        advanceUntilIdle()

        viewModel.onAction(LibraryAction.SelectTag("rock"))
        advanceUntilIdle()

        assertEquals("rock", viewModel.state.value.selectedTagNormalized)
        assertEquals(filteredIdeas, viewModel.state.value.ideas)
        assertEquals(null, viewModel.state.value.errorMessage)
    }

    @Test
    fun `when used tags fails it updates error and emits ShowError effect`() =
        runTest(mainDispatcherRule.dispatcher.scheduler) {
            val repo = mockk<IdeaRepository>()
            coEvery { repo.getAllUsedTags() } throws RuntimeException("boom")
            coEvery { repo.getIdeaDurations() } returns emptyMap()
            every { repo.observeIdeasNewest() } returns flowOf(emptyList())

            val viewModel = LibraryViewModel(repo, audioEngine)

            viewModel.effects.test {
                viewModel.onAction(LibraryAction.Load)

                val effect = awaitItem()
                assertTrue(effect is LibraryEffect.ShowError)
                assertEquals("boom", (effect as LibraryEffect.ShowError).message)

                assertEquals("boom", viewModel.state.value.errorMessage)
                cancelAndIgnoreRemainingEvents()
            }
        }
}
