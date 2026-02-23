package com.example.nightjar.data.db.dao

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.turbine.test
import com.example.nightjar.data.db.NightjarDatabase
import com.example.nightjar.data.db.entity.IdeaEntity
import com.example.nightjar.data.db.entity.TrackEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrackDaoTest {

    private lateinit var db: NightjarDatabase
    private lateinit var trackDao: TrackDao
    private lateinit var ideaDao: IdeaDao

    private var ideaId: Long = 0L

    @Before
    fun setUp() = runTest {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, NightjarDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        trackDao = db.trackDao()
        ideaDao = db.ideaDao()

        ideaId = ideaDao.insertIdea(
            IdeaEntity(title = "Test Idea")
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    /* ---------- insert & basic reads ---------- */

    @Test
    fun insertTrack_and_getById_returnsTrack() = runTest {
        val id = trackDao.insertTrack(track(sortIndex = 0))

        val result = trackDao.getTrackById(id)

        assertEquals("track_0.m4a", result?.audioFileName)
        assertEquals(ideaId, result?.ideaId)
    }

    @Test
    fun getTrackById_nonExistent_returnsNull() = runTest {
        assertNull(trackDao.getTrackById(999))
    }

    @Test
    fun getTracksForIdea_returnsSortedBySortIndex() = runTest {
        trackDao.insertTrack(track(sortIndex = 2, audioFileName = "c.m4a"))
        trackDao.insertTrack(track(sortIndex = 0, audioFileName = "a.m4a"))
        trackDao.insertTrack(track(sortIndex = 1, audioFileName = "b.m4a"))

        val tracks = trackDao.getTracksForIdea(ideaId)

        assertEquals(listOf("a.m4a", "b.m4a", "c.m4a"), tracks.map { it.audioFileName })
    }

    @Test
    fun getTrackCount_returnsCorrectCount() = runTest {
        assertEquals(0, trackDao.getTrackCount(ideaId))

        trackDao.insertTrack(track(sortIndex = 0))
        trackDao.insertTrack(track(sortIndex = 1))

        assertEquals(2, trackDao.getTrackCount(ideaId))
    }

    /* ---------- updates ---------- */

    @Test
    fun updateDisplayName_changesName() = runTest {
        val id = trackDao.insertTrack(track(sortIndex = 0))

        trackDao.updateDisplayName(id, "Vocals")

        assertEquals("Vocals", trackDao.getTrackById(id)?.displayName)
    }

    @Test
    fun updateOffset_changesOffset() = runTest {
        val id = trackDao.insertTrack(track(sortIndex = 0))

        trackDao.updateOffset(id, 5000L)

        assertEquals(5000L, trackDao.getTrackById(id)?.offsetMs)
    }

    @Test
    fun updateTrim_changesStartAndEnd() = runTest {
        val id = trackDao.insertTrack(track(sortIndex = 0))

        trackDao.updateTrim(id, startMs = 1000L, endMs = 2000L)

        val result = trackDao.getTrackById(id)!!
        assertEquals(1000L, result.trimStartMs)
        assertEquals(2000L, result.trimEndMs)
    }

    @Test
    fun updateSortIndex_changesIndex() = runTest {
        val id = trackDao.insertTrack(track(sortIndex = 0))

        trackDao.updateSortIndex(id, 5)

        assertEquals(5, trackDao.getTrackById(id)?.sortIndex)
    }

    @Test
    fun updateMuted_togglesMute() = runTest {
        val id = trackDao.insertTrack(track(sortIndex = 0))

        trackDao.updateMuted(id, true)
        assertEquals(true, trackDao.getTrackById(id)?.isMuted)

        trackDao.updateMuted(id, false)
        assertEquals(false, trackDao.getTrackById(id)?.isMuted)
    }

    @Test
    fun updateVolume_changesVolume() = runTest {
        val id = trackDao.insertTrack(track(sortIndex = 0))

        trackDao.updateVolume(id, 0.5f)

        assertEquals(0.5f, trackDao.getTrackById(id)?.volume)
    }

    /* ---------- delete ---------- */

    @Test
    fun deleteTrackById_removesTrack() = runTest {
        val id = trackDao.insertTrack(track(sortIndex = 0))

        trackDao.deleteTrackById(id)

        assertNull(trackDao.getTrackById(id))
        assertEquals(0, trackDao.getTrackCount(ideaId))
    }

    /* ---------- cascade delete ---------- */

    @Test
    fun deletingIdea_cascadeDeletesTracks() = runTest {
        trackDao.insertTrack(track(sortIndex = 0))
        trackDao.insertTrack(track(sortIndex = 1))
        assertEquals(2, trackDao.getTrackCount(ideaId))

        ideaDao.deleteIdeaById(ideaId)

        assertEquals(0, trackDao.getTrackCount(ideaId))
    }

    /* ---------- Flow / observe ---------- */

    @Test
    fun observeTracksForIdea_emitsUpdates() = runTest {
        trackDao.observeTracksForIdea(ideaId).test {
            assertEquals(emptyList<TrackEntity>(), awaitItem())

            val id = trackDao.insertTrack(track(sortIndex = 0, audioFileName = "first.m4a"))
            assertEquals(1, awaitItem().size)

            trackDao.insertTrack(track(sortIndex = 1, audioFileName = "second.m4a"))
            assertEquals(2, awaitItem().size)

            trackDao.deleteTrackById(id)
            assertEquals(1, awaitItem().size)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun observeTracksForIdea_doesNotEmitTracksFromOtherIdeas() = runTest {
        val otherId = ideaDao.insertIdea(
            IdeaEntity(title = "Other Idea")
        )

        trackDao.observeTracksForIdea(ideaId).test {
            assertEquals(emptyList<TrackEntity>(), awaitItem())

            // Insert track for the other idea — should not trigger emission
            trackDao.insertTrack(track(ideaId = otherId, sortIndex = 0))

            // Insert track for our idea — should trigger
            trackDao.insertTrack(track(ideaId = ideaId, sortIndex = 0))
            val items = awaitItem()
            assertEquals(1, items.size)
            assertEquals(ideaId, items[0].ideaId)

            cancelAndIgnoreRemainingEvents()
        }
    }

    /* ---------- helper ---------- */

    private fun track(
        ideaId: Long = this.ideaId,
        sortIndex: Int = 0,
        audioFileName: String = "track_$sortIndex.m4a",
        displayName: String = "Track ${sortIndex + 1}",
        durationMs: Long = 10_000L
    ) = TrackEntity(
        ideaId = ideaId,
        audioFileName = audioFileName,
        displayName = displayName,
        sortIndex = sortIndex,
        durationMs = durationMs
    )
}
