package org.schabi.newpipe.local.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryEntryTest {

    private fun downloaded(id: String, account: String = "youtube", missing: String = "") = OfflineVideo(
        id = id,
        title = "title $id",
        thumbnail = "",
        duration = 60,
        accountId = account,
        remoteMissingSince = missing
    )

    private fun watchLater(id: String, account: String = "youtube") = WatchLaterItem(
        id = id,
        title = "title $id",
        url = "https://example/$id",
        thumbnail = "",
        duration = 60,
        accountId = account
    )

    private fun job(
        id: Long,
        videoId: String,
        state: String,
        account: String = "youtube",
        progress: Int = 0,
        error: String = ""
    ) = DownloadJob(
        id = id,
        accountId = account,
        videoId = videoId,
        state = state,
        progress = progress,
        error = error
    )

    @Test
    fun `a downloaded file is not duplicated by its source entry`() {
        val entries = buildLibraryEntries(
            downloaded = listOf(downloaded("a")),
            watchLater = listOf(watchLater("a"), watchLater("b")),
            jobs = emptyList()
        )

        assertEquals(listOf("a", "b"), entries.map { it.videoId })
        assertEquals(LibraryEntry.State.DOWNLOADED, entries[0].state)
        assertEquals(LibraryEntry.State.PENDING, entries[1].state)
    }

    @Test
    fun `job state decides how an undownloaded row reads`() {
        val entries = buildLibraryEntries(
            downloaded = emptyList(),
            watchLater = listOf(watchLater("q"), watchLater("d"), watchLater("p"), watchLater("f")),
            jobs = listOf(
                job(1, "q", DownloadJob.QUEUED),
                job(2, "d", DownloadJob.DOWNLOADING, progress = 42),
                job(3, "p", DownloadJob.PAUSED, progress = 7),
                job(4, "f", DownloadJob.FAILED, error = "boom")
            )
        )

        val byId = entries.associateBy { it.videoId }
        assertEquals(LibraryEntry.State.QUEUED, byId.getValue("q").state)
        assertEquals(LibraryEntry.State.ACTIVE, byId.getValue("d").state)
        assertEquals(42, byId.getValue("d").progress)
        assertEquals(LibraryEntry.State.PAUSED, byId.getValue("p").state)
        assertEquals(LibraryEntry.State.FAILED, byId.getValue("f").state)
        assertEquals("boom", byId.getValue("f").error)
        assertTrue(byId.getValue("d").isActive)
        assertFalse(byId.getValue("f").isActive)
    }

    @Test
    fun `a retried video keeps only its newest job`() {
        val entries = buildLibraryEntries(
            downloaded = emptyList(),
            watchLater = listOf(watchLater("v")),
            jobs = listOf(
                job(10, "v", DownloadJob.FAILED, error = "old"),
                job(11, "v", DownloadJob.DOWNLOADING, progress = 5)
            )
        )

        assertEquals(1, entries.size)
        assertEquals(LibraryEntry.State.ACTIVE, entries[0].state)
        assertEquals(5, entries[0].progress)
        assertEquals("", entries[0].error)
    }

    @Test
    fun `sources with the same video id stay separate`() {
        val entries = buildLibraryEntries(
            downloaded = listOf(downloaded("x", "youtube")),
            watchLater = listOf(watchLater("x", "bilibili")),
            jobs = listOf(job(1, "x", DownloadJob.QUEUED, account = "bilibili"))
        )

        assertEquals(2, entries.size)
        assertEquals(LibraryEntry.State.DOWNLOADED, entries[0].state)
        assertEquals(LibraryEntry.State.QUEUED, entries[1].state)
    }

    @Test
    fun `a cancelled job returns to pending so a tap can retry it`() {
        val entries = buildLibraryEntries(
            downloaded = emptyList(),
            watchLater = listOf(watchLater("c")),
            jobs = listOf(job(1, "c", DownloadJob.CANCELLED))
        )

        assertEquals(LibraryEntry.State.PENDING, entries[0].state)
    }

    @Test
    fun `watched flag and resume position come from their own inputs`() {
        val entries = buildLibraryEntries(
            downloaded = listOf(downloaded("w", missing = "2026-10-01T00:00:00Z")),
            watchLater = emptyList(),
            jobs = emptyList(),
            resumeById = mapOf("w" to 120L)
        )

        assertTrue(entries[0].isWatched)
        assertEquals(120L, entries[0].resumePosition)
    }
}
