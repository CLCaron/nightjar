package com.example.nightjar.data.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.nightjar.audio.CaptureAudio
import com.example.nightjar.data.db.NightjarDatabase
import com.example.nightjar.data.storage.RecordingStorage
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** In-memory database only. Never opens the installed app's ideas database. */
@RunWith(AndroidJUnit4::class)
class CaptureBatchTest {
    private lateinit var db: NightjarDatabase
    private lateinit var repo: IdeaRepository

    @Before fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, NightjarDatabase::class.java).build()
        repo = IdeaRepository(db.ideaDao(), db.tagDao(), db.trackDao(), db.audioClipDao(),
            db.captureGroupDao(), db.takeDao(), RecordingStorage(context), db)
    }

    @After fun close() = db.close()

    private fun audio(name: String) = CaptureAudio(File("$name.wav"), 1000)

    @Test fun appendingUsesOneGroupAndPreservesChosenTake() = runTest {
        val first = repo.saveCaptureBatch(null, listOf(audio("one"), audio("two")))
        db.takeDao().setActiveTake(first.group.clipId, first.takes[1].id)
        val second = repo.saveCaptureBatch(first.group, listOf(audio("three")))
        assertEquals(first.group, second.group)
        val tracks = db.trackDao().getTracksForIdea(first.group.ideaId)
        assertEquals(1, tracks.size)
        assertEquals(1, db.audioClipDao().getClipsForTrack(tracks.single().id).size)
        val takes = db.takeDao().getTakesForClip(first.group.clipId)
        assertEquals(listOf("Take 1", "Take 2", "Take 3"), takes.map { it.displayName })
        assertEquals(first.takes[1].id, takes.single { it.isActive }.id)
        assertEquals(3, takes.map { it.audioFileName }.distinct().size)
    }

    @Test fun invalidOwnershipCannotAppend() = runTest {
        val first = repo.saveCaptureBatch(null, listOf(audio("one")))
        try {
            repo.saveCaptureBatch(first.group.copy(ideaId = first.group.ideaId + 100), listOf(audio("bad")))
            fail("Expected ownership validation")
        } catch (_: IllegalArgumentException) { }
        assertEquals(1, db.takeDao().getTakesForClip(first.group.clipId).size)
    }

    @Test fun secondInsertFailureRollsBackEntireBatch() = runTest {
        val first = repo.saveCaptureBatch(null, listOf(audio("one")))
        db.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER reject_test_take BEFORE INSERT ON takes
            WHEN NEW.audioFileName = 'reject.wav'
            BEGIN SELECT RAISE(ABORT, 'injected failure'); END
        """.trimIndent())
        var failed = false
        try {
            repo.saveCaptureBatch(first.group, listOf(audio("two"), audio("reject")))
        } catch (_: android.database.sqlite.SQLiteException) { failed = true }
        assertTrue(failed)
        assertEquals(listOf("one.wav"), db.takeDao().getTakesForClip(first.group.clipId).map { it.audioFileName })
    }

    @Test fun newGroupKeepsLatchSeparateFromStudioUntilAdded() = runTest {
        val original = repo.saveCaptureBatch(null, listOf(audio("guitar")))
        val melody = repo.createCaptureGroup(original.group.ideaId)
        val saved = repo.saveCaptureBatch(CaptureGroup(melody.ideaId, melody.clipId), listOf(audio("melody")))
        repo.latchCaptureTake(melody, saved.takes.single().id)
        assertEquals(2, repo.getCaptureGroups(original.group.ideaId).size)
        assertEquals(saved.takes.single().id, repo.getCaptureGroups(original.group.ideaId).last().latchedTakeId)
        assertEquals(1, db.trackDao().getStudioTracksForIdea(original.group.ideaId).size)
        repo.addCaptureGroupToStudio(melody)
        assertEquals(2, db.trackDao().getStudioTracksForIdea(original.group.ideaId).size)
    }
}
