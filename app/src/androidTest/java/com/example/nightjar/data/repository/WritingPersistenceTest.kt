package com.example.nightjar.data.repository

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.nightjar.audio.CaptureAudio
import com.example.nightjar.data.db.NightjarDatabase
import com.example.nightjar.data.storage.NotesDraft
import com.example.nightjar.data.storage.NotesDraftStorage
import com.example.nightjar.data.storage.RecordingStorage
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WritingPersistenceTest {
    private lateinit var db: NightjarDatabase
    private lateinit var repo: IdeaRepository
    private lateinit var folder: File
    private lateinit var storage: NotesDraftStorage

    @Before fun setup() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        folder = File(app.cacheDir, "writing-test-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(app) { override fun getFilesDir(): File = folder }
        db = Room.inMemoryDatabaseBuilder(context, NightjarDatabase::class.java).build()
        repo = IdeaRepository(db.ideaDao(), db.tagDao(), db.trackDao(), db.audioClipDao(),
            db.takeDao(), RecordingStorage(context), db)
        storage = NotesDraftStorage(context)
    }

    @After fun close() { db.close(); folder.deleteRecursively() }

    @Test fun textFirstAudioUsesExistingIdeaAndPreservesWords() = runTest {
        val id = repo.createEmptyIdea()
        repo.saveNotesRevision(id, "", "a melody in words")
        val batch = repo.saveCaptureBatchForIdea(id, null, listOf(CaptureAudio(File("audio.wav"), 2000)))
        assertEquals(id, batch.group.ideaId)
        assertEquals("a melody in words", repo.getIdeaById(id)?.notes)
        assertEquals(1, db.trackDao().getTracksForIdea(id).size)
    }

    @Test fun staleRevisionAndRecoveryCannotOverwriteNewerText() = runTest {
        val id = repo.createEmptyIdea()
        repo.saveNotesRevision(id, "", "newer")
        try { repo.saveNotesRevision(id, "", "stale"); fail("Expected conflict") }
        catch (_: IllegalStateException) { }
        assertEquals("newer", repo.getIdeaById(id)?.notes)
        // Replaying a committed recovery record is idempotent.
        repo.saveNotesRevision(id, "", "newer")
        assertEquals("newer", repo.getIdeaById(id)?.notes)
    }

    @Test fun recoveryJournalRoundTripsLongUnicodeAndCanBeCleared() = runTest {
        val draft = NotesDraft("old", "a line 🎵\n".repeat(12000))
        storage.write(1L, draft)
        assertEquals(draft, storage.read(1L))
        storage.clear(1L)
        assertNull(storage.read(1L))
    }

    @Test fun unreadableJournalIsNotSilentlyDiscarded() = runTest {
        val directory = File(folder, "notes-recovery").apply { mkdirs() }
        val file = File(directory, "1.draft").apply { writeBytes(byteArrayOf(1, 2)) }
        try { storage.read(1L); fail("Expected recovery error") }
        catch (_: java.io.EOFException) { }
        assertTrue(file.exists())
    }
}
