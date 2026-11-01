package org.schabi.newpipe.local.offline

import java.io.File

/** App-owned persistence boundary; all reads are local and require no videod client. */
class OfflineLibraryRepository(private val dao: OfflineLibraryDAO) {
    /**
     * Move videod's finished downloads into app-owned storage.
     *
     * Each file is copied through videod's local stream, recorded in the local
     * index, and only then removed from the service, so the app ends up owning
     * the media and playback no longer needs the service. A copy that does not
     * complete leaves the service copy untouched.
     */
    fun importDownloaded(
        videos: List<OfflineVideo>,
        appMediaDir: File,
        copy: (OfflineVideo, File) -> Unit,
        removeFromService: (OfflineVideo) -> Unit = {}
    ): List<OfflineLibraryEntity> {
        val imported = ArrayList<OfflineLibraryEntity>()
        for (video in videos.distinctBy { OfflineLibraryEntity.key(it.accountId, it.id) }) {
            if (video.path.isBlank()) continue
            val extension = video.path.substringAfterLast('.', "media")
                .takeIf { it.matches(Regex("[A-Za-z0-9]{1,8}")) } ?: "media"
            val name = video.id.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val target = File(appMediaDir, "${video.accountId}/$name.$extension")
            if (!isUsable(target)) {
                try {
                    copy(video, target)
                } catch (error: Exception) {
                    target.delete()
                }
            }
            if (!isUsable(target)) continue
            val entity = OfflineLibraryEntity.fromVideo(
                video.copy(path = target.absolutePath),
                dao.get(video.accountId, video.id)
            )
            dao.upsert(listOf(entity))
            imported.add(entity)
            runCatching { removeFromService(video) }
        }
        return imported
    }

    fun localFile(account: String, videoId: String): File? = dao.get(account, videoId)
        ?.localPath?.takeIf { it.isNotBlank() }?.let(::File)
        ?.takeIf(::isUsable)

    private fun isUsable(file: File): Boolean = file.isFile && file.canRead() && file.length() > 0L

    fun load(
        account: String? = null,
        fileExists: (String) -> Boolean = { path -> File(path).let(::isUsable) }
    ): List<LibraryEntry> = dao.getAll()
        .asSequence()
        .filter { account == null || it.accountId == account }
        .map { entity -> entity.copy(missing = !fileExists(entity.localPath)).toEntry() }
        .toList()

    fun upsertDownloaded(videos: List<OfflineVideo>) {
        val mapped = videos.filter { it.path.isNotBlank() }
            .distinctBy { OfflineLibraryEntity.key(it.accountId, it.id) }
            .map { video ->
                OfflineLibraryEntity.fromVideo(video, dao.get(video.accountId, video.id))
            }
        if (mapped.isNotEmpty()) dao.upsert(mapped)
    }

    fun updateFavorite(account: String, videoId: String, favorite: Boolean) {
        dao.get(account, videoId)?.let { dao.upsert(listOf(it.copy(favorite = favorite))) }
    }

    fun updateResumePosition(account: String, videoId: String, seconds: Long) {
        dao.get(account, videoId)?.let {
            dao.upsert(listOf(it.copy(resumePosition = seconds.coerceAtLeast(0))))
        }
    }
}
