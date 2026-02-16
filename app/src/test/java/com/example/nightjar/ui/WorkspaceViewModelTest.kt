package com.example.nightjar.ui.workspace

import app.cash.turbine.test
import com.example.nightjar.data.db.entity.IdeaEntity
import com.example.nightjar.data.db.entity.TagEntity
import com.example.nightjar.data.repository.IdeaRepository
import com.example.nightjar.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(testDispatcher)

    @Test
    fun `load idea populates state`() = runTest(testDispatcher.scheduler) {
        val repo = mockk<IdeaRepository>()
        val idea = IdeaEntity(
            id = 9,
            audioFileName = "idea.m4a",
            title = "Idea Title",
            notes = "Notes"
        )
        val tags = listOf(TagEntity(id = 1, name = "Rock", nameNormalized = "rock"))
        coEvery { repo.getIdeaById(9) } returns idea
        coEvery { repo.getTagsForIdea(9) } returns tags

        val viewModel = WorkspaceViewModel(repo)
        viewModel.onAction(WorkspaceAction.Load(9))
        advanceUntilIdle()

        assertEquals(idea, viewModel.state.value.idea)
        assertEquals("Idea Title", viewModel.state.value.titleDraft)
        assertEquals("Notes", viewModel.state.value.notesDraft)
        assertEquals(tags, viewModel.state.value.tags)
    }

    @Test
    fun `title change debounces and persists`() = runTest(testDispatcher.scheduler) {
        val repo = mockk<IdeaRepository>()
        val idea = IdeaEntity(
            id = 10,
            audioFileName = "idea.m4a",
            title = "Original",
            notes = ""
        )
        coEvery { repo.getIdeaById(10) } returns idea
        coEvery { repo.getTagsForIdea(10) } returns emptyList()
        coEvery { repo.updateTitle(10, "New Title") } returns Unit

        val viewModel = WorkspaceViewModel(repo)
        viewModel.onAction(WorkspaceAction.Load(10))
        advanceUntilIdle()

        viewModel.onAction(WorkspaceAction.TitleChanged("  New Title  "))
        advanceTimeBy(600)
        advanceUntilIdle()

        coVerify { repo.updateTitle(10, "New Title") }
        assertEquals("New Title", viewModel.state.value.idea?.title)
    }

    @Test
    fun `toggle favorite updates repository and state`() = runTest(testDispatcher.scheduler) {
        val repo = mockk<IdeaRepository>()
        val idea = IdeaEntity(
            id = 11,
            audioFileName = "idea.m4a",
            title = "Idea",
            notes = "",
            isFavorite = false
        )
        coEvery { repo.getIdeaById(11) } returns idea
        coEvery { repo.getTagsForIdea(11) } returns emptyList()
        coEvery { repo.updateFavorite(11, true) } returns Unit

        val viewModel = WorkspaceViewModel(repo)
        viewModel.onAction(WorkspaceAction.Load(11))
        advanceUntilIdle()

        viewModel.onAction(WorkspaceAction.ToggleFavorite)
        advanceUntilIdle()

        coVerify { repo.updateFavorite(11, true) }
        assertTrue(viewModel.state.value.idea?.isFavorite == true)
    }

    @Test
    fun `add tags from input adds each tag`() = runTest(testDispatcher.scheduler) {
        val repo = mockk<IdeaRepository>()
        val idea = IdeaEntity(
            id = 12,
            audioFileName = "idea.m4a",
            title = "Idea",
            notes = ""
        )
        coEvery { repo.getIdeaById(12) } returns idea
        coEvery { repo.getTagsForIdea(12) } returns emptyList()
        coEvery { repo.addTagToIdea(12, any()) } returns Unit

        val viewModel = WorkspaceViewModel(repo)
        viewModel.onAction(WorkspaceAction.Load(12))
        advanceUntilIdle()

        viewModel.onAction(WorkspaceAction.AddTagsFromInput("Rock, Jazz"))
        advanceUntilIdle()

        coVerify { repo.addTagToIdea(12, "Rock") }
        coVerify { repo.addTagToIdea(12, "Jazz") }
        coVerify { repo.getTagsForIdea(12) }
    }

    @Test
    fun `delete idea emits navigate back`() = runTest(testDispatcher.scheduler) {
        val repo = mockk<IdeaRepository>()
        val idea = IdeaEntity(
            id = 13,
            audioFileName = "idea.m4a",
            title = "Idea",
            notes = ""
        )
        coEvery { repo.getIdeaById(13) } returns idea
        coEvery { repo.getTagsForIdea(13) } returns emptyList()
        coEvery { repo.deleteIdeaAndAudio(13) } returns Unit

        val viewModel = WorkspaceViewModel(repo)
        viewModel.onAction(WorkspaceAction.Load(13))
        advanceUntilIdle()

        viewModel.effects.test {
            viewModel.onAction(WorkspaceAction.DeleteIdea)
            advanceUntilIdle()
            val effect = awaitItem()
            assertTrue(effect is WorkspaceEffect.NavigateBack)
            cancelAndIgnoreRemainingEvents()
        }
    }
}