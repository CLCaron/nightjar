package com.example.nightjar.data.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.nightjar.data.db.entity.IdeaEntity
import com.example.nightjar.data.db.entity.MidiClipEntity
import com.example.nightjar.data.db.entity.MidiNoteEntity
import com.example.nightjar.data.db.entity.TrackEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LegacyGrooveMidiMigrationTest {

    private lateinit var db: NightjarDatabase

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, NightjarDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun repairLegacyGrooveMidiClipSchema_preservesClipsLinksAndNotes() = runTest {
        val ideaId = db.ideaDao().insertIdea(IdeaEntity(title = "Migration test"))
        val trackId = db.trackDao().insertTrack(
            TrackEntity(
                ideaId = ideaId,
                trackType = "midi",
                displayName = "MIDI",
                sortIndex = 0,
                durationMs = 4_000L
            )
        )
        val sourceClipId = db.midiClipDao().insertClip(
            MidiClipEntity(
                trackId = trackId,
                offsetMs = 250L,
                sortIndex = 0,
                lengthMs = 2_000L
            )
        )
        val linkedClipId = db.midiClipDao().insertClip(
            MidiClipEntity(
                trackId = trackId,
                offsetMs = 2_250L,
                sortIndex = 1,
                sourceClipId = sourceClipId,
                lengthMs = 2_000L
            )
        )
        val noteId = db.midiNoteDao().insertNote(
            MidiNoteEntity(
                trackId = trackId,
                clipId = sourceClipId,
                pitch = 64,
                startMs = 125L,
                durationMs = 750L,
                velocity = 0.65f
            )
        )

        val sqliteDb = db.openHelper.writableDatabase
        sqliteDb.execSQL(
            "ALTER TABLE midi_clips ADD COLUMN origin TEXT NOT NULL DEFAULT 'arrange'"
        )
        sqliteDb.execSQL("CREATE INDEX index_midi_clips_origin ON midi_clips(origin)")

        sqliteDb.beginTransaction()
        try {
            NightjarDatabase.repairLegacyGrooveMidiClipSchema(sqliteDb)
            sqliteDb.setTransactionSuccessful()
        } finally {
            sqliteDb.endTransaction()
        }

        val columns = buildList {
            sqliteDb.query("PRAGMA table_info(midi_clips)").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) add(cursor.getString(nameIndex))
            }
        }
        val clipForeignKeyTables = buildList {
            sqliteDb.query("PRAGMA foreign_key_list(midi_clips)").use { cursor ->
                val tableIndex = cursor.getColumnIndex("table")
                while (cursor.moveToNext()) add(cursor.getString(tableIndex))
            }
        }
        val noteForeignKeyTables = buildList {
            sqliteDb.query("PRAGMA foreign_key_list(midi_notes)").use { cursor ->
                val tableIndex = cursor.getColumnIndex("table")
                while (cursor.moveToNext()) add(cursor.getString(tableIndex))
            }
        }

        assertEquals(
            setOf("id", "trackId", "offsetMs", "sortIndex", "sourceClipId", "lengthMs"),
            columns.toSet()
        )
        assertFalse("origin" in columns)
        assertTrue("tracks" in clipForeignKeyTables)
        assertTrue("midi_clips" in clipForeignKeyTables)
        assertTrue("tracks" in noteForeignKeyTables)
        assertTrue("midi_clips" in noteForeignKeyTables)

        val sourceClip = db.midiClipDao().getClipById(sourceClipId)!!
        val linkedClip = db.midiClipDao().getClipById(linkedClipId)!!
        val note = db.midiNoteDao().getNoteById(noteId)!!

        assertEquals(250L, sourceClip.offsetMs)
        assertEquals(2_000L, sourceClip.lengthMs)
        assertEquals(sourceClipId, linkedClip.sourceClipId)
        assertEquals(2_250L, linkedClip.offsetMs)
        assertEquals(sourceClipId, note.clipId)
        assertEquals(64, note.pitch)
        assertEquals(0.65f, note.velocity)

        sqliteDb.query("PRAGMA foreign_key_check").use { cursor ->
            assertEquals(0, cursor.count)
        }

        val newClipId = db.midiClipDao().insertClip(
            MidiClipEntity(
                trackId = trackId,
                offsetMs = 4_250L,
                sortIndex = 2,
                lengthMs = 1_000L
            )
        )
        assertTrue(newClipId > linkedClipId)
    }

    @Test
    fun migration15To16_opensLegacyGrooveForkWithoutDataLoss() = runTest {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "legacy-groove-${System.nanoTime()}.db"
        var legacyDb: NightjarDatabase? = null
        var migratedDb: NightjarDatabase? = null

        try {
            legacyDb = Room.databaseBuilder(context, NightjarDatabase::class.java, databaseName)
                .allowMainThreadQueries()
                .build()
            val ideaId = legacyDb.ideaDao().insertIdea(IdeaEntity(title = "Legacy Groove"))
            val trackId = legacyDb.trackDao().insertTrack(
                TrackEntity(
                    ideaId = ideaId,
                    trackType = "midi",
                    displayName = "Legacy MIDI",
                    sortIndex = 0,
                    durationMs = 2_000L
                )
            )
            val clipId = legacyDb.midiClipDao().insertClip(
                MidiClipEntity(
                    trackId = trackId,
                    offsetMs = 375L,
                    sortIndex = 0,
                    lengthMs = 1_500L
                )
            )
            val noteId = legacyDb.midiNoteDao().insertNote(
                MidiNoteEntity(
                    trackId = trackId,
                    clipId = clipId,
                    pitch = 67,
                    startMs = 100L,
                    durationMs = 500L,
                    velocity = 0.7f
                )
            )
            val sqliteDb = legacyDb.openHelper.writableDatabase
            sqliteDb.execSQL(
                "ALTER TABLE midi_clips ADD COLUMN origin TEXT NOT NULL DEFAULT 'arrange'"
            )
            sqliteDb.execSQL("CREATE INDEX index_midi_clips_origin ON midi_clips(origin)")
            // The Groove v15 fork predates every Explore table. Remove the
            // current empty tables so this exercises their defensive creation
            // and the MIDI repair in the same real Room upgrade.
            sqliteDb.execSQL("DROP TABLE explore_candidates")
            sqliteDb.execSQL("DROP TABLE explore_segments")
            sqliteDb.execSQL("DROP TABLE explore_captures")
            sqliteDb.execSQL("DROP TABLE explore_sketches")
            sqliteDb.execSQL("DROP TABLE idea_sections")
            sqliteDb.execSQL("PRAGMA user_version = 15")
            legacyDb.close()
            legacyDb = null

            migratedDb = Room.databaseBuilder(context, NightjarDatabase::class.java, databaseName)
                .addMigrations(NightjarDatabase.MIGRATION_15_16)
                .allowMainThreadQueries()
                .build()

            val migratedClip = migratedDb.midiClipDao().getClipById(clipId)!!
            val migratedNote = migratedDb.midiNoteDao().getNoteById(noteId)!!

            assertEquals(375L, migratedClip.offsetMs)
            assertEquals(1_500L, migratedClip.lengthMs)
            assertEquals(67, migratedNote.pitch)
            assertEquals(500L, migratedNote.durationMs)
            assertEquals(0.7f, migratedNote.velocity)

            migratedDb.openHelper.writableDatabase
                .query("PRAGMA table_info(midi_clips)")
                .use { cursor ->
                    val nameIndex = cursor.getColumnIndex("name")
                    val columnNames = buildList {
                        while (cursor.moveToNext()) add(cursor.getString(nameIndex))
                    }
                    assertFalse("origin" in columnNames)
                }
        } finally {
            legacyDb?.close()
            migratedDb?.close()
            context.deleteDatabase(databaseName)
        }
    }
}
