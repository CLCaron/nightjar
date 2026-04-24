package com.example.nightjar.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.nightjar.data.db.dao.AudioClipDao
import com.example.nightjar.data.db.dao.DrumPatternDao
import com.example.nightjar.data.db.dao.IdeaDao
import com.example.nightjar.data.db.dao.MidiClipDao
import com.example.nightjar.data.db.dao.MidiNoteDao
import com.example.nightjar.data.db.dao.TagDao
import com.example.nightjar.data.db.dao.TakeDao
import com.example.nightjar.data.db.dao.TrackDao
import com.example.nightjar.data.db.entity.AudioClipEntity
import com.example.nightjar.data.db.entity.DrumClipEntity
import com.example.nightjar.data.db.entity.DrumPatternEntity
import com.example.nightjar.data.db.entity.DrumStepEntity
import com.example.nightjar.data.db.entity.IdeaEntity
import com.example.nightjar.data.db.entity.MidiClipEntity
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
 * - **v10** — Added `gridResolution` to ideas, `midi_clips` table for arrangeable MIDI
 *             clip blocks, `clipId` FK on `midi_notes`, removed UNIQUE on
 *             `drum_patterns.trackId` index (allows multiple patterns per track).
 * - **v11** — Added `scaleRoot` and `scaleType` to ideas for project-level
 *             musical scale and key signature.
 * - **v12** — Added `audio_clips` table for clip-based audio arrangement.
 *             Restructured `takes` table: `trackId` FK replaced by `clipId` FK
 *             to `audio_clips`, added `isActive`, removed `offsetMs`/`isMuted`.
 *             Each existing take becomes its own clip with one active take.
 * - **v13** — Added `sourceClipId` (nullable self-FK) to `audio_clips` and
 *             `midi_clips` for linked-clip instance→source pointers. Drums
 *             link via existing shared `patternId`, no change to `drum_clips`.
 * - **v14** — Uniform clip length model. Added nullable `lengthMs` to
 *             `midi_clips` (null = legacy, resolved to
 *             max(contentDuration, msPerMeasure) on read). Added non-null
 *             `lengthSteps` to `drum_patterns`, back-filled as
 *             `bars * stepsPerBar`. `bars` column stays for now but is no
 *             longer read at runtime (dropped in a later cleanup).
 */
@Database(
    entities = [
        IdeaEntity::class, TagEntity::class, IdeaTagCrossRef::class,
        TrackEntity::class, AudioClipEntity::class, TakeEntity::class,
        DrumPatternEntity::class, DrumStepEntity::class,
        DrumClipEntity::class,
        MidiClipEntity::class, MidiNoteEntity::class
    ],
    version = 14,
    exportSchema = false
)
abstract class NightjarDatabase : RoomDatabase() {

    abstract fun ideaDao(): IdeaDao
    abstract fun tagDao(): TagDao
    abstract fun trackDao(): TrackDao
    abstract fun takeDao(): TakeDao
    abstract fun audioClipDao(): AudioClipDao
    abstract fun drumPatternDao(): DrumPatternDao
    abstract fun midiClipDao(): MidiClipDao
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

        /**
         * v9 -> v10: MIDI clips, grid resolution, per-clip drum patterns.
         *
         * - ideas: add `gridResolution` column (default 16 = sixteenth notes)
         * - New table: `midi_clips` for arrangeable MIDI note blocks
         * - midi_notes: add `clipId` FK (recreate table, migrate notes to default clips)
         * - drum_patterns: drop + recreate trackId index without UNIQUE
         */
        private val MIGRATION_9_10 = object : androidx.room.migration.Migration(9, 10) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                // 1. Add gridResolution to ideas
                db.execSQL(
                    "ALTER TABLE ideas ADD COLUMN gridResolution INTEGER NOT NULL DEFAULT 16"
                )

                // 2. Create midi_clips table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS midi_clips (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        trackId INTEGER NOT NULL,
                        offsetMs INTEGER NOT NULL DEFAULT 0,
                        sortIndex INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY(trackId) REFERENCES tracks(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_midi_clips_trackId ON midi_clips(trackId)"
                )

                // 3. Insert a default clip (offsetMs=0) for every existing MIDI track
                db.execSQL("""
                    INSERT INTO midi_clips (trackId, offsetMs, sortIndex)
                    SELECT id, 0, 0 FROM tracks WHERE trackType = 'midi'
                """.trimIndent())

                // 4. Recreate midi_notes with clipId column.
                //    Existing notes get clipId from the default clip created above.
                //    Since default clips have offsetMs=0, note startMs values are
                //    already correct as clip-relative positions.
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS midi_notes_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        trackId INTEGER NOT NULL,
                        clipId INTEGER NOT NULL,
                        pitch INTEGER NOT NULL,
                        startMs INTEGER NOT NULL,
                        durationMs INTEGER NOT NULL,
                        velocity REAL NOT NULL DEFAULT 0.8,
                        FOREIGN KEY(trackId) REFERENCES tracks(id) ON DELETE CASCADE,
                        FOREIGN KEY(clipId) REFERENCES midi_clips(id) ON DELETE CASCADE
                    )
                """.trimIndent())

                db.execSQL("""
                    INSERT INTO midi_notes_new (id, trackId, clipId, pitch, startMs, durationMs, velocity)
                    SELECT mn.id, mn.trackId, mc.id, mn.pitch, mn.startMs, mn.durationMs, mn.velocity
                    FROM midi_notes mn
                    INNER JOIN midi_clips mc ON mc.trackId = mn.trackId
                """.trimIndent())

                db.execSQL("DROP TABLE midi_notes")
                db.execSQL("ALTER TABLE midi_notes_new RENAME TO midi_notes")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_midi_notes_trackId ON midi_notes(trackId)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_midi_notes_clipId ON midi_notes(clipId)"
                )

                // 5. Drop + recreate drum_patterns trackId index without UNIQUE
                db.execSQL("DROP INDEX IF EXISTS index_drum_patterns_trackId")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_drum_patterns_trackId ON drum_patterns(trackId)"
                )
            }
        }

        private val MIGRATION_10_11 = object : androidx.room.migration.Migration(10, 11) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE ideas ADD COLUMN scaleRoot INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE ideas ADD COLUMN scaleType TEXT NOT NULL DEFAULT 'MAJOR'"
                )
            }
        }

        /**
         * v11 -> v12: Audio clips -- restructure takes into clip-based arrangement.
         *
         * - New table: `audio_clips` for timeline clip placements of audio
         * - `takes` table: replace `trackId` FK with `clipId` FK to `audio_clips`,
         *   add `isActive`, remove `offsetMs` and `isMuted`
         *
         * Migration strategy: each existing take becomes its own clip with one
         * active take, preserving all positions and audio behavior identically.
         * Tracks with no takes but with `audioFileName` get a clip + take from
         * the track's audio.
         */
        private val MIGRATION_11_12 = object : androidx.room.migration.Migration(11, 12) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                // 1. Create audio_clips table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS audio_clips (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        trackId INTEGER NOT NULL,
                        offsetMs INTEGER NOT NULL DEFAULT 0,
                        displayName TEXT NOT NULL DEFAULT '',
                        sortIndex INTEGER NOT NULL DEFAULT 0,
                        isMuted INTEGER NOT NULL DEFAULT 0,
                        createdAtEpochMs INTEGER NOT NULL,
                        FOREIGN KEY(trackId) REFERENCES tracks(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_audio_clips_trackId ON audio_clips(trackId)"
                )

                // 2. For each existing take on an audio track: create a clip
                //    with offsetMs = take.offsetMs, isMuted = take.isMuted
                db.execSQL("""
                    INSERT INTO audio_clips (trackId, offsetMs, displayName, sortIndex, isMuted, createdAtEpochMs)
                    SELECT t.trackId, t.offsetMs,
                           'Clip ' || (t.sortIndex + 1),
                           t.sortIndex,
                           t.isMuted,
                           t.createdAtEpochMs
                    FROM takes t
                    INNER JOIN tracks tr ON tr.id = t.trackId
                    WHERE tr.trackType = 'audio'
                """.trimIndent())

                // 3. For audio tracks with NO takes but with audioFileName:
                //    create a clip at the track's offsetMs
                db.execSQL("""
                    INSERT INTO audio_clips (trackId, offsetMs, displayName, sortIndex, isMuted, createdAtEpochMs)
                    SELECT tr.id, tr.offsetMs, 'Clip 1', 0, 0, tr.createdAtEpochMs
                    FROM tracks tr
                    WHERE tr.trackType = 'audio'
                      AND tr.audioFileName IS NOT NULL
                      AND tr.audioFileName != ''
                      AND tr.id NOT IN (SELECT DISTINCT trackId FROM takes)
                """.trimIndent())

                // 4. Create takes_new table with new schema
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS takes_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        clipId INTEGER NOT NULL,
                        audioFileName TEXT NOT NULL,
                        displayName TEXT NOT NULL,
                        sortIndex INTEGER NOT NULL,
                        durationMs INTEGER NOT NULL,
                        trimStartMs INTEGER NOT NULL DEFAULT 0,
                        trimEndMs INTEGER NOT NULL DEFAULT 0,
                        isActive INTEGER NOT NULL DEFAULT 1,
                        volume REAL NOT NULL DEFAULT 1.0,
                        createdAtEpochMs INTEGER NOT NULL,
                        FOREIGN KEY(clipId) REFERENCES audio_clips(id) ON DELETE CASCADE
                    )
                """.trimIndent())

                // 5. Migrate existing takes into takes_new by joining to their
                //    corresponding clip via trackId + sortIndex match
                db.execSQL("""
                    INSERT INTO takes_new (id, clipId, audioFileName, displayName, sortIndex,
                        durationMs, trimStartMs, trimEndMs, isActive, volume, createdAtEpochMs)
                    SELECT t.id, ac.id, t.audioFileName, t.displayName, 0,
                        t.durationMs, t.trimStartMs, t.trimEndMs, 1, t.volume, t.createdAtEpochMs
                    FROM takes t
                    INNER JOIN tracks tr ON tr.id = t.trackId
                    INNER JOIN audio_clips ac ON ac.trackId = t.trackId AND ac.sortIndex = t.sortIndex
                    WHERE tr.trackType = 'audio'
                """.trimIndent())

                // 6. Create takes for tracks that had no takes (from step 3)
                //    using track.audioFileName
                db.execSQL("""
                    INSERT INTO takes_new (clipId, audioFileName, displayName, sortIndex,
                        durationMs, trimStartMs, trimEndMs, isActive, volume, createdAtEpochMs)
                    SELECT ac.id, tr.audioFileName, 'Take 1', 0,
                        tr.durationMs, tr.trimStartMs, tr.trimEndMs, 1, tr.volume, tr.createdAtEpochMs
                    FROM audio_clips ac
                    INNER JOIN tracks tr ON tr.id = ac.trackId
                    WHERE ac.id NOT IN (SELECT DISTINCT clipId FROM takes_new)
                      AND tr.audioFileName IS NOT NULL
                      AND tr.audioFileName != ''
                """.trimIndent())

                // 7. Drop old takes, rename takes_new
                db.execSQL("DROP TABLE takes")
                db.execSQL("ALTER TABLE takes_new RENAME TO takes")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_takes_clipId ON takes(clipId)"
                )
            }
        }

        /**
         * v12 -> v13: Linked clips support.
         *
         * Add nullable self-referencing `sourceClipId` column to `audio_clips`
         * and `midi_clips`. NULL = clip is a source (owns its takes/notes).
         * Non-NULL = clip is an instance of that source id. FK uses NO ACTION
         * on delete; promotion is handled explicitly in repository transactions.
         *
         * Drum clips already share content via `patternId`, so no change there.
         *
         * All existing clips become sources (sourceClipId defaults to NULL).
         */
        private val MIGRATION_12_13 = object : androidx.room.migration.Migration(12, 13) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE audio_clips ADD COLUMN sourceClipId INTEGER " +
                    "REFERENCES audio_clips(id) ON DELETE NO ACTION"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_audio_clips_sourceClipId " +
                    "ON audio_clips(sourceClipId)"
                )
                db.execSQL(
                    "ALTER TABLE midi_clips ADD COLUMN sourceClipId INTEGER " +
                    "REFERENCES midi_clips(id) ON DELETE NO ACTION"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_midi_clips_sourceClipId " +
                    "ON midi_clips(sourceClipId)"
                )
            }
        }

        /**
         * v13 -> v14: Uniform clip length model.
         *
         * MIDI: add nullable `lengthMs`. NULL means "legacy clip" — readers
         * fall back to `max(maxNoteEnd, msPerMeasure)`. The first explicit
         * length mutation (split, trim, resize) writes a non-null value.
         *
         * Drum: add non-null `lengthSteps` to `drum_patterns`, back-filled
         * as `bars * stepsPerBar` so existing patterns render at identical
         * width. `bars` stays in schema (deprecated) until a later cleanup.
         */
        private val MIGRATION_13_14 = object : androidx.room.migration.Migration(13, 14) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE midi_clips ADD COLUMN lengthMs INTEGER")
                db.execSQL(
                    "ALTER TABLE drum_patterns ADD COLUMN lengthSteps INTEGER NOT NULL DEFAULT 16"
                )
                db.execSQL(
                    "UPDATE drum_patterns SET lengthSteps = bars * stepsPerBar"
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
                    MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10,
                    MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13,
                    MIGRATION_13_14
                ).build()
                INSTANCE = db
                db
            }
        }


    }
}