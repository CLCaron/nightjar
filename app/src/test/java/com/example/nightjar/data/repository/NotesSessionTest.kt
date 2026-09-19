package com.example.nightjar.data.repository

import android.util.Log
import com.example.nightjar.data.db.entity.IdeaEntity
import com.example.nightjar.data.storage.NotesDraft
import com.example.nightjar.data.storage.NotesDraftStorage
import com.example.nightjar.util.MainDispatcherRule
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotesSessionTest {
    private val dispatcher = StandardTestDispatcher()
    @get:Rule val main = MainDispatcherRule(dispatcher)
    private val repo = mockk<IdeaRepository>()
    private val storage = mockk<NotesDraftStorage>()
    private lateinit var notes: NotesSession

    @Before fun setup() {
        mockkStatic(Log::class)
        every { Log.e(any(), any(), any()) } returns 0
        coEvery { repo.getIdeaById(1L) } returns IdeaEntity(id = 1L, title = "Test", notes = "original")
        coEvery { repo.saveNotesRevision(any(), any(), any()) } returns Unit
        coEvery { storage.read(any()) } returns null
        coEvery { storage.write(any(), any()) } returns Unit
        coEvery { storage.clear(any()) } returns Unit
        notes = NotesSession(repo, storage)
    }

    @After fun cleanup() = unmockkAll()

    @Test fun `late acknowledgement never replaces a newer edit`() = runTest(dispatcher) {
        val waiting = CompletableDeferred<Unit>()
        coEvery { repo.saveNotesRevision(1L, "original", "first") } coAnswers { waiting.await() }
        val doc = notes.open(1L); runCurrent()
        doc.edit("first"); runCurrent()
        doc.edit("second")
        assertEquals("second", doc.state.value.text)
        assertTrue(doc.state.value.pending)
        waiting.complete(Unit); advanceUntilIdle()
        assertEquals("second", doc.state.value.text)
        assertTrue(doc.state.value.safeToLeaveIdea)
        coVerifyOrder {
            storage.write(1L, NotesDraft("original", "first"))
            repo.saveNotesRevision(1L, "original", "first")
            storage.write(1L, NotesDraft("first", "second"))
            repo.saveNotesRevision(1L, "first", "second")
        }
    }

    @Test fun `failed write retains draft and retry persists without typing again`() = runTest(dispatcher) {
        val doc = notes.open(1L); runCurrent()
        coEvery { repo.saveNotesRevision(any(), any(), any()) } throws IllegalStateException("busy")
        doc.edit("keep me"); advanceUntilIdle()
        assertEquals("keep me", doc.state.value.text)
        assertFalse(doc.state.value.safeToLeaveIdea)
        coVerify(exactly = 0) { storage.clear(any()) }
        coEvery { repo.saveNotesRevision(any(), any(), any()) } returns Unit
        assertTrue(doc.flush())
        assertNull(doc.state.value.error)
    }

    @Test fun `opening the same Idea shares pending edits with Overview`() = runTest(dispatcher) {
        val first = notes.open(1L); runCurrent()
        first.edit("from capture")
        val overview = notes.open(1L)
        assertSame(first, overview)
        assertEquals("from capture", overview.state.value.text)
        overview.edit("from overview"); advanceUntilIdle()
        assertEquals("from overview", first.state.value.text)
    }

    @Test fun `process recovery replays journal before acknowledging saved`() = runTest(dispatcher) {
        coEvery { storage.read(1L) } returns NotesDraft("original", "recovered")
        val doc = notes.open(1L); advanceUntilIdle()
        assertEquals("recovered", doc.state.value.text)
        assertTrue(doc.state.value.safeToLeaveIdea)
        coVerify { repo.saveNotesRevision(1L, "original", "recovered") }
    }

    @Test fun `empty writing creates no Idea and first edit creates only one`() = runTest(dispatcher) {
        var created = 0
        val doc = notes.create { created++; 1L }
        advanceUntilIdle(); assertEquals(0, created)
        doc.edit("one"); doc.edit("two"); advanceUntilIdle()
        assertEquals(1, created)
        assertEquals(1L, doc.state.value.ideaId)
        assertSame(doc, notes.open(1L))
        coVerify { repo.saveNotesRevision(1L, "", "two") }
    }

    @Test fun `audio can bind an empty editor before text is entered`() = runTest(dispatcher) {
        val doc = notes.create { error("Must use the audio Idea") }
        doc.attachIdea(1L)
        assertSame(doc, notes.open(1L))
        doc.edit("after sound"); advanceUntilIdle()
        coVerify { repo.saveNotesRevision(1L, "", "after sound") }
    }

    @Test fun `failed journal write never acknowledges or indexes unprotected text`() = runTest(dispatcher) {
        val doc = notes.open(1L); runCurrent()
        coEvery { storage.write(any(), any()) } throws java.io.IOException("full")
        doc.edit("retained in memory"); advanceUntilIdle()
        assertTrue(doc.state.value.pending)
        assertNotNull(doc.state.value.error)
        coVerify(exactly = 0) { repo.saveNotesRevision(any(), any(), any()) }
    }
}
