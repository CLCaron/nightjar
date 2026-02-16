package com.example.nightjar.ui.library

import app.cash.turbine.test
import com.example.nightjar.data.db.entity.IdeaEntity
import com.example.nightjar.data.db.entity.TagEntity
import com.example.nightjar.data.repository.IdeaRepository
import com.example.nightjar.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.confirmVerified
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

    @Test
    fun `init loads tags and newest ideas`() = runTest(mainDispatcherRule.dispatcher.scheduler) {
        val repo = mockk<IdeaRepository>()
        val tags = listOf(TagEntity(id = 1, name = "Rock", nameNormalized = "rock"))
        val ideas = listOf(IdeaEntity(id = 2, audioFileName = "idea.m4a", title = "Idea", isFavorite = false))

        coEvery { repo.getAllUsedTags() } returns tags
        coEvery { repo.getIdeasNewest() } returns ideas

        val viewModel = LibraryViewModel(repo)

        viewModel.state.test {
            val state = awaitItem()
            assertEquals(tags, state.usedTags)
            assertEquals(ideas, state.ideas)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `select tag fetches ideas for that tag`() = runTest(mainDispatcherRule.dispatcher.scheduler) {
        val repo = mockk<IdeaRepository>()
        val filteredIdeas = listOf(
            IdeaEntity(
                id = 3,
                audioFileName = "tagged.m4a",
                title = "Tagged",
                notes = "",
                isFavorite = false
            )
        )

        coEvery { repo.getAllUsedTags() } returns emptyList()
        coEvery { repo.getIdeasNewest() } returns emptyList()
        coEvery { repo.getIdeasForTag("rock") } returns filteredIdeas

        val viewModel = LibraryViewModel(repo)
        advanceUntilIdle()

        viewModel.onAction(LibraryAction.SelectTag("rock"))
        advanceUntilIdle()

        assertEquals("rock", viewModel.state.value.selectedTagNormalized)
        assertEquals(filteredIdeas, viewModel.state.value.ideas)
        assertEquals(null, viewModel.state.value.errorMessage)

        coVerify(exactly = 1) { repo.getAllUsedTags() }
        coVerify(exactly = 1) { repo.getIdeasNewest() }
        coVerify(exactly = 1) { repo.getIdeasForTag("rock") }
        confirmVerified(repo)
    }

    @Test
    fun `when used tags fails it updates error and emits ShowError effect`() =
        runTest(mainDispatcherRule.dispatcher.scheduler) {
            val repo = mockk<IdeaRepository>()
            coEvery { repo.getAllUsedTags() } throws RuntimeException("boom")
            coEvery { repo.getIdeasNewest() } returns emptyList()

            val viewModel = LibraryViewModel(repo)

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
