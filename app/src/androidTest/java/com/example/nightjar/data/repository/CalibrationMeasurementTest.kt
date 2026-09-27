package com.example.nightjar.data.repository

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.nightjar.audio.*
import com.example.nightjar.data.db.NightjarDatabase
import com.example.nightjar.data.storage.RecordingStorage
import java.io.File
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Disposable/in-memory databases only. Never opens the installed Ideas database. */
@RunWith(AndroidJUnit4::class)
class CalibrationMeasurementTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: NightjarDatabase
    private lateinit var measurements: CalibrationMeasurementRepository
    private val key = CalibrationRouteKey("phone", "earbud", 44100, 44100, 1, 2,
        1, 1, 96, 192, 9, 0, "a2dp", 1, "test-platform")
    private fun route(routeKey: CalibrationRouteKey = key) = CalibrationRouteSnapshot(routeKey,
        "Phone microphone", "Bluetooth headphones", "session", "test-session")
    private val streams = AudioRouteEvidence(AudioStreamEvidence(open = true, epoch = 4, sampleRate = 44100, channels = 1),
        AudioStreamEvidence(open = true, epoch = 7, sampleRate = 44100, channels = 2))
    private val result = AcousticLatencyDetector.Result(12127.5, 22.0, 88, 6, 1)

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, NightjarDatabase::class.java).build()
        measurements = CalibrationMeasurementRepository(db)
    }
    @After fun close() = db.close()

    @Test fun diagnosticRevisionsAreImmutableAndKeepDifferentMicrophonesSeparate() = runTest {
        val phone = measurements.save(route(), streams, result, 6.53, true, "{}", "[]")
        val headsetKey = key.copy(inputIdentity = "headset", transportProfile = "sco")
        val headset = measurements.save(route(headsetKey), streams, result, 6.53, true, "{}", "[]")
        assertNotEquals(phone.routeFingerprint, headset.routeFingerprint)
        assertEquals(phone, db.calibrationMeasurementDao().latestForRoute(key.fingerprint()))
        assertEquals(headset, db.calibrationMeasurementDao().latestForRoute(headsetKey.fingerprint()))
        assertEquals("CLOCK_VERIFICATION_PENDING", phone.validityReason)
        try {
            db.calibrationMeasurementDao().insert(phone.copy(delayOutputFrames = 0.0))
            fail("An immutable revision must not be overwritten")
        } catch (_: android.database.sqlite.SQLiteConstraintException) { }
        assertEquals(phone, db.calibrationMeasurementDao().latestForRoute(key.fingerprint()))
    }

    @Test fun failedSaveRetainsPreviousRevisionAndUnconfirmedEvidenceIsExplicit() = runTest {
        val original = measurements.save(route(), streams, result, 6.53, false, "{}", "[]")
        assertEquals("CONFIRMATION_FAILED", original.validityReason)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_calibration BEFORE INSERT ON calibration_measurements BEGIN SELECT RAISE(ABORT, 'injected failure'); END")
        try {
            measurements.save(route(), streams, result, 6.53, true, "{}", "[]")
            fail("Injected save failure must surface")
        } catch (expected: java.io.IOException) {
            assertTrue(expected.message!!.contains("still record"))
        }
        assertEquals(original, db.calibrationMeasurementDao().latestForRoute(key.fingerprint()))
    }

    @Test fun diagnosticEvidenceSurvivesDatabaseReopen() = runTest {
        val name = "calibration-reopen-${UUID.randomUUID()}.db"
        val first = Room.databaseBuilder(context, NightjarDatabase::class.java, name).build()
        val saved = try {
            CalibrationMeasurementRepository(first).save(route(), streams, result, 6.53, true,
                "{\"reference\":\"software\"}", "[{\"stage\":\"confirmation\"}]")
        } finally { first.close() }
        val reopened = Room.databaseBuilder(context, NightjarDatabase::class.java, name).build()
        try {
            assertEquals(saved, reopened.calibrationMeasurementDao().latestForRoute(key.fingerprint()))
        } finally { reopened.close(); context.deleteDatabase(name) }
    }

    @Test fun migrationFrom17PreservesEveryLegacyRowAndEditingValue() = runTest {
        val repo = IdeaRepository(db.ideaDao(), db.tagDao(), db.trackDao(), db.audioClipDao(),
            db.captureBackingDao(), db.captureGroupDao(), db.takeDao(), RecordingStorage(context), db)
        val saved = repo.saveCaptureBatch(null, listOf(CaptureAudio(File("calibration-fixture-${UUID.randomUUID()}.wav"), 1000)))
        val group = repo.getCaptureGroups(saved.group.ideaId).single()
        repo.latchCaptureTake(group, saved.takes.single().id)
        db.openHelper.writableDatabase.execSQL("UPDATE takes SET trimStartMs = 7, trimEndMs = 11, volume = 0.7")
        val before = legacyRows(db)
        val name = "calibration-migration-${UUID.randomUUID()}.db"
        val path = context.getDatabasePath(name)
        path.parentFile?.mkdirs()
        val old = SQLiteDatabase.openOrCreateDatabase(path, null)
        try {
            db.openHelper.readableDatabase.query("SELECT sql FROM sqlite_master WHERE sql IS NOT NULL AND name NOT LIKE 'sqlite_%' AND name NOT LIKE '%calibration_measurements%' AND name NOT IN ('room_master_table', 'android_metadata') ORDER BY CASE WHEN type = 'table' THEN 0 ELSE 1 END, rowid").use { schemas ->
                while (schemas.moveToNext()) old.execSQL(schemas.getString(0))
            }
            for (table in before.keys) db.openHelper.readableDatabase.query("SELECT * FROM \"$table\"").use { rows ->
                while (rows.moveToNext()) {
                    val values = ContentValues()
                    for (column in 0 until rows.columnCount) when (rows.getType(column)) {
                        Cursor.FIELD_TYPE_NULL -> values.putNull(rows.getColumnName(column))
                        Cursor.FIELD_TYPE_INTEGER -> values.put(rows.getColumnName(column), rows.getLong(column))
                        Cursor.FIELD_TYPE_FLOAT -> values.put(rows.getColumnName(column), rows.getDouble(column))
                        Cursor.FIELD_TYPE_BLOB -> values.put(rows.getColumnName(column), rows.getBlob(column))
                        else -> values.put(rows.getColumnName(column), rows.getString(column))
                    }
                    old.insertOrThrow(table, null, values)
                }
            }
            old.version = 17
        } finally { old.close() }
        val migrated = Room.databaseBuilder(context, NightjarDatabase::class.java, name)
            .addMigrations(NightjarDatabase.MIGRATION_17_18).build()
        try {
            assertNull(migrated.calibrationMeasurementDao().latestForRoute("not-present"))
            assertEquals(before, legacyRows(migrated))
            assertEquals(saved.takes.single().id, migrated.captureGroupDao().forIdea(saved.group.ideaId).single().latchedTakeId)
        } finally { migrated.close(); context.deleteDatabase(name) }
    }

    private fun legacyRows(database: NightjarDatabase): Map<String, List<String>> {
        val sql = database.openHelper.readableDatabase
        val tables = sql.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name NOT IN ('room_master_table', 'android_metadata', 'calibration_measurements')").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        return tables.associateWith { table ->
            sql.query("SELECT * FROM \"$table\"").use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add((0 until cursor.columnCount).joinToString("|") { column ->
                        val value = when (cursor.getType(column)) {
                            Cursor.FIELD_TYPE_NULL -> "null"
                            Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(column).joinToString("") { "%02x".format(it) }
                            else -> cursor.getString(column)
                        }
                        "${cursor.getType(column)}:${value.length}:$value"
                    })
                }.sorted()
            }
        }
    }
}
