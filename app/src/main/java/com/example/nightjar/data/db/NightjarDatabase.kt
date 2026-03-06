package com.example.nightjar.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.nightjar.data.db.dao.DrumPatternDao
import com.example.nightjar.data.db.dao.IdeaDao
import com.example.nightjar.data.db.dao.MidiNoteDao
import com.example.nightjar.data.db.dao.TagDao
import com.example.nightjar.data.db.dao.TakeDao
import com.example.nightjar.data.db.dao.TrackDao
import com.example.nightjar.data.db.entity.DrumClipEntity
import com.example.nightjar.data.db.entity.DrumPatternEntity
import com.example.nightjar.data.db.entity.DrumStepEntity
import com.example.nightjar.data.db.entity.IdeaEntity
import com.example.nightjar.data.db.entity.MidiNoteEntity
import com.example.nightjar.data.db.entity.TagEntity
import com.example.nightjar.data.db.entity.TakeEntity
import com.example.nightjar.data.db.entity.TrackEntity

/**
 * Room database for Nightjar.
 *
 * ## Schema history
 * - **v1** — `ideas` table (core recording metadata).
 * - **v2** — Added `tags` and `idea_tags` tables for user-defined tagging.
 * - **v3** — Added `tracks` table for multi-track Studio projects.
 * - **v4** — Removed `audioFileName` from `ideas`; IdeaEntity is now a pure metadata container.
 * - **v5** — Added `takes` table for per-track multi-take support.
 * - **v6** — Added `bpm` to ideas, `trackType` + nullable `audioFileName` to tracks,
 *            `drum_patterns` and `drum_steps` tables for drum sequencer support.
 * - **v7** — Added `drum_clips` table for timeline clip placements of drum patterns.
 * - **v8** — Added `timeSignatureNumerator` and `timeSignatureDenominator` to ideas.
 * - **v9** — Added `midiProgram` and `midiChannel` to tracks, `midi_notes` table for
 *            MIDI instrument track support.
 */
@Database(
    entities = [
        IdeaEntity::class, TagEntity::class, IdeaTagCrossRef::class,
        TrackEntity::class, TakeEntity::class,
        DrumPatternEntity::class, DrumStepEntity::class,
        DrumClipEntity::class,
        MidiNoteEntity::class
    ],
    version = 9,
    exportSchema = false
)
abstract class NightjarDatabase : RoomDatabase() {

    abstract fun ideaDao(): IdeaDao
    abstract fun tagDao(): TagDao
    abstract fun trackDao(): TrackDao
    abstract fun takeDao(): TakeDao
    abstract fun drumPatternDao(): DrumPatternDao
    abstract fun midiNoteDao(): MidiNoteDao

    companion object {
        @Volatile private var INSTANCE: NightjarDatabase? = null

        /** v1 → v2: Add tagging support (tags + junction table). */
        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("""
            CREATE TABLE IF NOT EXISTS tags (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                name TEXT NOT NULL,
                nameNormalized TEXT NOT NULL
            )
        """.trimIndent())

                db.execSQL("""
            CREATE UNIQUE INDEX IF NOT EXISTS index_tags_nameNormalized
            ON tags(nameNormalized)
        """.trimIndent())

                db.execSQL("""
            CREATE TABLE IF NOT EXISTS idea_tags (
                ideaId INTEGER NOT NULL,
                tagId INTEGER NOT NULL,
                PRIMARY KEY(ideaId, tagId)
            )
        """.trimIndent())
            }
        }

        /** v2 → v3: Add multi-track support (tracks table with idea FK). */
        private val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("""
            CREATE TABLE IF NOT EXISTS tracks (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                ideaId INTEGER NOT NULL,
                audioFileName TEXT NOT NULL,
                displayName TEXT NOT NULL,
                sortIndex INTEGER NOT NULL,
                offsetMs INTEGER NOT NULL DEFAULT 0,
                trimStartMs INTEGER NOT NULL DEFAULT 0,
                trimEndMs INTEGER NOT NULL DEFAULT 0,
                durationMs INTEGER NOT NULL,
                isMuted INTEGER NOT NULL DEFAULT 0,
                volume REAL NOT NULL DEFAULT 1.0,
                createdAtEpochMs INTEGER NOT NULL,
                FOREIGN KEY(ideaId) REFERENCES ideas(id) ON DELETE CASCADE
            )
        """.trimIndent())

                db.execSQL("""
            CREATE INDEX IF NOT EXISTS index_tracks_ideaId ON tracks(ideaId)
        """.trimIndent())
            }
        }

        /**
         * v3 → v4: Remove `audioFileName` from `ideas`.
         *
         * IdeaEntity becomes a pure metadata container — all audio references
         * now live exclusively in TrackEntity. Orphaned ideas (created before
         * Step 2 added atomic idea+track creation) are promoted to Track 1
         * before the column is dropped. Duration is set to 0 for migrated
         * tracks; [StudioRepository.ensureProjectInitialized] resolves it on
         * first access.
         *
         * SQLite < 3.35.0 (minSdk 24) doesn't support DROP COLUMN, so we
         * recreate the table.
         */
        private val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Promote orphaned ideas (no tracks yet) → create Track 1 rows
                db.execSQL("""
                    INSERT INTO tracks (ideaId, audioFileName, displayName, sortIndex,
                        offsetMs, trimStartMs, trimEndMs, durationMs, isMuted, volume, createdAtEpochMs)
                    SELECT id, audioFileName, 'Track 1', 0,
                        0, 0, 0, 0, 0, 1.0, createdAtEpochMs
                    FROM ideas WHERE id NOT IN (SELECT DISTINCT ideaId FROM tracks)
                """.trimIndent())

                // Recreate ideas table without audioFileName
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS ideas_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        title TEXT NOT NULL,
                        notes TEXT NOT NULL DEFAULT '',
                        isFavorite INTEGER NOT NULL DEFAULT 0,
                        createdAtEpochMs INTEGER NOT NULL
                    )
                """.trimIndent())

                db.execSQL("""
                    INSERT INTO ideas_new (id, title, notes, isFavorite, createdAtEpochMs)
                    SELECT id, title, notes, isFavorite, createdAtEpochMs FROM ideas
                """.trimIndent())

                db.execSQL("DROP TABLE ideas")
                db.execSQL("ALTER TABLE ideas_new RENAME TO ideas")
            }
        }

        /** v4 -> v5: Add takes table for per-track multi-take support. */
        private val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS takes (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        trackId INTEGER NOT NULL,
                        audioFileName TEXT NOT NULL,
                        displayName TEXT NOT NULL,
                        sortIndex INTEGER NOT NULL,
                        durationMs INTEGER NOT NULL,
                        offsetMs INTEGER NOT NULL DEFAULT 0,
                        trimStartMs INTEGER NOT NULL DEFAULT 0,
                        trimEndMs INTEGER NOT NULL DEFAULT 0,
                        isMuted INTEGER NOT NULL DEFAULT 0,
                        volume REAL NOT NULL DEFAULT 1.0,
                        createdAtEpochMs INTEGER NOT NULL,
                        FOREIGN KEY(trackId) REFERENCES tracks(id) ON DELETE CASCADE
                    )
                """.trimIndent())

                db.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_takes_trackId ON takes(trackId)
                """.trimIndent())
            }
        }

        /**
         * v5 -> v6: Drum sequencer support.
         *
         * - ideas: add `bpm` column (project-level tempo, default 120.0)
         * - tracks: add `trackType` column, make `audioFileName` nullable
         *   (drum tracks have no audio file). Table recreation required since
         *   SQLite < 3.35 can't change column nullability via ALTER TABLE.
         * - New tables: `drum_patterns` and `drum_steps`
         */
        private val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                // 1. Add bpm to ideas (simple ADD COLUMN)
                db.execSQL("ALTER TABLE ideas ADD COLUMN bpm REAL NOT NULL DEFAULT 120.0")

                // 2. Recreate tracks with trackType + nullable audioFileName
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS tracks_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        ideaId INTEGER NOT NULL,
                        trackType TEXT NOT NULL DEFAULT 'audio',
                        audioFileName TEXT,
                        displayName TEXT NOT NULL,
                        sortIndex INTEGER NOT NULL,
                        offsetMs INTEGER NOT NULL DEFAULT 0,
                        trimStartMs INTEGER NOT NULL DEFAULT 0,
                        trimEndMs INTEGER NOT NULL DEFAULT 0,
                        durationMs INTEGER NOT NULL,
                        isMuted INTEGER NOT NULL DEFAULT 0,
                        volume REAL NOT NULL DEFAULT 1.0,
                        createdAtEpochMs INTEGER NOT NULL,
                        FOREIGN KEY(ideaId) REFERENCES ideas(id) ON DELETE CASCADE
                    )
                """.trimIndent())

                db.execSQL("""
                    INSERT INTO tracks_new (id, ideaId, trackType, audioFileName, displayName,
                        sortIndex, offsetMs, trimStartMs, trimEndMs, durationMs, isMuted,
                        volume, createdAtEpochMs)
                    SELECT id, ideaId, 'audio', audioFileName, displayName,
                        sortIndex, offsetMs, trimStartMs, trimEndMs, durationMs, isMuted,
                        volume, createdAtEpochMs
                    FROM tracks
                """.trimIndent())

                db.execSQL("DROP TABLE tracks")
                db.execSQL("ALTER TABLE tracks_new RENAME TO tracks")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tracks_ideaId ON tracks(ideaId)")

                // 3. Create drum_patterns table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS drum_patterns (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        trackId INTEGER NOT NULL,
                        stepsPerBar INTEGER NOT NULL DEFAULT 16,
                        bars INTEGER NOT NULL DEFAULT 1,
                        FOREIGN KEY(trackId) REFERENCES tracks(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_drum_patterns_trackId ON drum_patterns(trackId)")

                // 4. Create drum_steps table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS drum_steps (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        patternId INTEGER NOT NULL,
                        stepIndex INTEGER NOT NULL,
                        drumNote INTEGER NOT NULL,
                        velocity REAL NOT NULL DEFAULT 0.8,
                        FOREIGN KEY(patternId) REFERENCES drum_patterns(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_drum_steps_patternId ON drum_steps(patternId)")
            }
        }

        /** v6 -> v7: Add drum_clips table for timeline clip placements. */
        private val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS drum_clips (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        patternId INTEGER NOT NULL,
                        offsetMs INTEGER NOT NULL DEFAULT 0,
                        sortIndex INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY(patternId) REFERENCES drum_patterns(id) ON DELETE CASCADE
                    )
                """.trimIndent())

                db.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_drum_clips_patternId ON drum_clips(patternId)
                """.trimIndent())

                // Auto-create a default clip at offset 0 for every existing pattern
                db.execSQL("""
                    INSERT INTO drum_clips (patternId, offsetMs, sortIndex)
                    SELECT id, 0, 0 FROM drum_patterns
                """.trimIndent())
            }
        }

        /** v7 -> v8: Add time signature columns to ideas. */
        private val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE ideas ADD COLUMN timeSignatureNumerator INTEGER NOT NULL DEFAULT 4"
                )
                db.execSQL(
                    "ALTER TABLE ideas ADD COLUMN timeSignatureDenominator INTEGER NOT NULL DEFAULT 4"
                )
            }
        }

        /**
         * v8 -> v9: MIDI instrument track support.
         *
         * - tracks: add `midiProgram` and `midiChannel` columns (default 0).
         * - New table: `midi_notes` for MIDI note events.
         */
        private val MIGRATION_8_9 = object : androidx.room.migration.Migration(8, 9) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE tracks ADD COLUMN midiProgram INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE tracks ADD COLUMN midiChannel INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS midi_notes (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        trackId INTEGER NOT NULL,
                        pitch INTEGER NOT NULL,
                        startMs INTEGER NOT NULL,
                        durationMs INTEGER NOT NULL,
                        velocity REAL NOT NULL DEFAULT 0.8,
                        FOREIGN KEY(trackId) REFERENCES tracks(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_midi_notes_trackId ON midi_notes(trackId)"
                )
            }
        }

        fun getInstance(context: Context): NightjarDatabase {
            return INSTANCE ?: synchronized(this) {
                val db = Room.databaseBuilder(
                    context.applicationContext,
                    NightjarDatabase::class.java,
                    "nightjar.db"
                ).addMigrations(
                    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4,
                    MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
                    MIGRATION_7_8, MIGRATION_8_9
                ).build()
                INSTANCE = db
                db
            }
        }


    }
}