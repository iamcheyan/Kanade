package org.schabi.newpipe.local.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineLibraryLocalFirstTest {

    private class FakeDao(initial: List<OfflineLibraryEntity> = emptyList()) : OfflineLibraryDAO {
        val rows = initial.toMutableList()

        override fun getAll(): List<OfflineLibraryEntity> = rows.toList()

        override fun get(accountId: String, videoId: String): OfflineLibraryEntity? = rows.firstOrNull { it.accountId == accountId && it.videoId == videoId }

        override fun upsert(entries: List<OfflineLibraryEntity>) {
            // Match Room's REPLACE semantics on the primary key and unique pair.
            for (entry in entries) {
                rows.removeAll {
                    it.libraryKey == entry.libraryKey ||
                        (it.accountId == entry.accountId && it.videoId == entry.videoId)
                }
                rows.add(entry)
            }
        }

        override fun delete(accountId: String, videoId: String) {
            rows.removeAll { it.accountId == accountId && it.videoId == videoId }
        }

        override fun deleteWatched() {
            rows.removeAll { it.remoteMissingSince.isNotEmpty() }
        }

        override fun deleteAll() {
            rows.clear()
        }
    }

    private fun entity(
        id: String,
        account: String = "youtube",
        path: String = "/media/$id.mp4",
        missing: Boolean = false,
        watched: String = "",
        favorite: Boolean = false,
        resume: Long = 0
    ) = OfflineLibraryEntity(
        libraryKey = OfflineLibraryEntity.key(account, id),
        videoId = id,
        accountId = account,
        title = "title $id",
        thumbnail = "",
        duration = 60,
        localPath = path,
        container = "video",
        encoding = "720p",
        downloadedAt = "2026-01-02",
        remoteMissingSince = watched,
        favorite = favorite,
        resumePosition = resume,
        missing = missing
    )

    private fun video(id: String, account: String = "youtube", path: String = "/media/$id.mp4") = OfflineVideo(
        id = id,
        title = "title $id",
        thumbnail = "",
        duration = 60,
        accountId = account,
        path = path
    )

    @Test
    fun `import moves a service download into app storage and records it`() {
        val dao = FakeDao()
        val repository = OfflineLibraryRepository(dao)
        val media = kotlin.io.path.createTempDirectory("media").toFile()
        val removed = ArrayList<String>()

        val imported = repository.importDownloaded(
            listOf(video("copied", path = "/service/copied.webm")),
            media,
            copy = { _, target ->
                target.parentFile?.mkdirs()
                target.writeBytes(ByteArray(16) { 1 })
            },
            removeFromService = { removed.add(it.id) }
        )

        assertEquals(1, imported.size)
        val row = dao.rows.single()
        assertTrue(row.localPath.startsWith(media.absolutePath))
        assertTrue(java.io.File(row.localPath).isFile)
        assertEquals(listOf("copied"), removed)
        assertEquals(row.localPath, repository.localFile("youtube", "copied")?.absolutePath)
    }

    @Test
    fun `failed import keeps the service copy and records nothing`() {
        val dao = FakeDao()
        val repository = OfflineLibraryRepository(dao)
        val media = kotlin.io.path.createTempDirectory("media").toFile()
        val removed = ArrayList<String>()

        val imported = repository.importDownloaded(
            listOf(video("broken", path = "/service/broken.mp4")),
            media,
            copy = { _, target ->
                target.writeBytes(ByteArray(4))
                throw java.io.IOException("stream failed")
            },
            removeFromService = { removed.add(it.id) }
        )

        assertTrue(imported.isEmpty())
        assertTrue(dao.rows.isEmpty())
        assertTrue(removed.isEmpty())
    }

    @Test
    fun `local rows render and play without a service`() {
        val repository = OfflineLibraryRepository(FakeDao(listOf(entity("id"))))

        val rows = repository.load(fileExists = { true })

        assertEquals(1, rows.size)
        assertTrue(rows.single().isPlayable)
        assertEquals("/media/id.mp4", rows.single().localPath)
    }

    @Test
    fun `missing local file becomes unplayable and is never deleted`() {
        val dao = FakeDao(listOf(entity("gone", watched = "2026-01-01", favorite = true)))
        val repository = OfflineLibraryRepository(dao)

        val missing = repository.load(fileExists = { false })

        assertFalse(missing.single().isPlayable)
        assertEquals(1, dao.rows.size)
        assertTrue(dao.rows.single().favorite)
    }

    @Test
    fun `videod upsert is idempotent and keeps accounts separate`() {
        val dao = FakeDao()
        val repository = OfflineLibraryRepository(dao)

        repository.upsertDownloaded(listOf(video("same"), video("same"), video("same")))
        repository.upsertDownloaded(listOf(video("same", account = "bilibili")))
        repository.upsertDownloaded(listOf(video("same"), video("same", account = "bilibili")))

        assertEquals(2, dao.rows.size)
        assertEquals(
            setOf("youtube/same", "bilibili/same"),
            dao.rows.map { it.libraryKey }.toSet()
        )
    }

    @Test
    fun `upsert preserves local favorite progress and watched marker`() {
        val dao = FakeDao(
            listOf(
                entity(
                    "same",
                    path = "/old.mp4",
                    watched = "2026-01-01",
                    favorite = true,
                    resume = 39
                )
            )
        )
        val repository = OfflineLibraryRepository(dao)

        repository.upsertDownloaded(
            listOf(video("same", path = "/new.mp4").copy(title = "new title"))
        )

        val row = dao.rows.single()
        assertEquals("/new.mp4", row.localPath)
        assertEquals("new title", row.title)
        assertEquals("2026-01-01", row.remoteMissingSince)
        assertTrue(row.favorite)
        assertEquals(39L, row.resumePosition)
    }

    @Test
    fun `favorite and resume updates write through local storage`() {
        val dao = FakeDao(listOf(entity("id")))
        val repository = OfflineLibraryRepository(dao)

        repository.updateFavorite("youtube", "id", true)
        repository.updateResumePosition("youtube", "id", 123)

        assertTrue(dao.rows.single().favorite)
        assertEquals(123L, dao.rows.single().resumePosition)
    }

    @Test
    fun `clearing watched or all removes the matching local index rows`() {
        val dao = FakeDao(
            listOf(
                entity("watched", watched = "2026-01-01"),
                entity("saved")
            )
        )

        dao.deleteWatched()
        assertEquals(listOf("saved"), dao.rows.map { it.videoId })

        dao.deleteAll()
        assertTrue(dao.rows.isEmpty())
    }
}
