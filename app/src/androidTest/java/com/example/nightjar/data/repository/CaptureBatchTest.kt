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
            db.captureBackingDao(), db.captureGroupDao(), db.takeDao(), RecordingStorage(context), db)
    }

    @After fun close() = db.close()

    private fun audio(name: String) = CaptureAudio(File("$name.wav"), 1000)

    @Test fun v16MigrationRetainsExistingIdeaTakesAndLatches() = runTest {
        val saved = repo.saveCaptureBatch(null, listOf(audio("legacy")))
        val group = repo.getCaptureGroups(saved.group.ideaId).single()
        repo.latchCaptureTake(group, saved.takes.single().id)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "import-migration-${java.util.UUID.randomUUID()}.db"
        val path = context.getDatabasePath(name)
        path.parentFile?.mkdirs()
        val old = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(path, null)
        try {
            val schemas = db.openHelper.readableDatabase.query("SELECT name, sql FROM sqlite_master WHERE sql IS NOT NULL AND name NOT LIKE 'sqlite_%' AND name NOT LIKE '%imported_sources%' AND name != 'room_master_table'")
            schemas.use { while (it.moveToNext()) old.execSQL(it.getString(1)) }
            for (table in listOf("ideas", "tracks", "audio_clips", "takes", "capture_groups")) {
                db.openHelper.readableDatabase.query("SELECT * FROM $table").use { rows ->
                    while (rows.moveToNext()) {
                        val values = android.content.ContentValues()
                        for (column in 0 until rows.columnCount) {
                            val columnName = rows.getColumnName(column)
                            when (rows.getType(column)) {
                                android.database.Cursor.FIELD_TYPE_NULL -> values.putNull(columnName)
                                android.database.Cursor.FIELD_TYPE_INTEGER -> values.put(columnName, rows.getLong(column))
                                android.database.Cursor.FIELD_TYPE_FLOAT -> values.put(columnName, rows.getDouble(column))
                                else -> values.put(columnName, rows.getString(column))
                            }
                        }
                        old.insertOrThrow(table, null, values)
                    }
                }
            }
            old.version = 16
        } finally { old.close() }
        val migrated = Room.databaseBuilder(context, NightjarDatabase::class.java, name)
            .addMigrations(NightjarDatabase.MIGRATION_16_17).build()
        try {
            assertEquals("legacy.wav", migrated.takeDao().getTakeById(saved.takes.single().id)?.audioFileName)
            assertEquals(saved.takes.single().id, migrated.captureGroupDao().forIdea(saved.group.ideaId).single().latchedTakeId)
            assertTrue(migrated.importedSourceDao().forIdea(saved.group.ideaId).isEmpty())
        } finally { migrated.close(); context.deleteDatabase(name) }
    }

    @Test fun importedIdeaHasSelectedBackingAndSeparateEmptyVocalDestination() = runTest {
        val imported = com.example.nightjar.audio.AudioImporter.ImportedAudio(File("song.wav"), File("original.source"), 180000, "Band Song")
        val vocal = repo.createImportedIdea(imported)
        assertEquals("Band Song", repo.getIdeaById(vocal.ideaId)?.title)
        val groups = repo.getCaptureGroups(vocal.ideaId)
        assertEquals(listOf("Backing", "Vocals"), groups.map { it.displayName })
        assertEquals(vocal.clipId, groups.last().clipId)
        assertTrue(repo.getCaptureTakes(vocal.clipId).isEmpty())
        assertNull(groups.last().latchedTakeId)
        val backing = repo.getCaptureTakes(groups.first().clipId).single()
        assertEquals(backing.id, groups.first().latchedTakeId)
        assertEquals("song.wav", backing.audioFileName)
        assertEquals(1, db.trackDao().getStudioTracksForIdea(vocal.ideaId).size)
        assertEquals("original.source", db.importedSourceDao().forIdea(vocal.ideaId).single().originalFileName)
        db.takeDao().deleteTakeById(backing.id)
        assertEquals(1, db.importedSourceDao().forIdea(vocal.ideaId).size)
        repo.deleteIdeaAndAudio(vocal.ideaId)
        assertTrue(db.importedSourceDao().forIdea(vocal.ideaId).isEmpty())
    }

    @Test fun importedSourceInsertFailureRollsBackNewIdea() = runTest {
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_import BEFORE INSERT ON imported_sources BEGIN SELECT RAISE(ABORT, 'injected failure'); END")
        val before = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM ideas").use { it.moveToFirst(); it.getInt(0) }
        try {
            repo.createImportedIdea(com.example.nightjar.audio.AudioImporter.ImportedAudio(File("song.wav"), File("original.source"), 180000, "Band Song"))
            fail("Expected import rollback")
        } catch (_: android.database.sqlite.SQLiteException) { }
        val after = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM ideas").use { it.moveToFirst(); it.getInt(0) }
        assertEquals(before, after)
    }

    @Test fun backingContextStoresUnwrappedStartAndAudiblePhase() = runTest {
        val guitar = repo.saveCaptureBatch(null, listOf(audio("guitar")))
        val destination = repo.createCaptureGroup(guitar.group.ideaId)
        val backing = CaptureBackingContext(guitar.takes.single().id, 44100L,
            transportStartFrame = 2 * 44100L, correctionFrames = 4410L)
        val melody = repo.saveCaptureBatch(CaptureGroup(guitar.group.ideaId, destination.clipId),
            listOf(audio("melody")), listOf(backing))
        val saved = repo.getCaptureBackings(melody.takes.single().id).single()
        assertEquals(guitar.takes.single().id, saved.backingTakeId)
        assertEquals(2 * 44100L, saved.transportStartFrame)
        assertEquals(39690L, saved.sourcePhaseFrame)
        assertEquals("estimated", saved.syncMethod)
    }

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
