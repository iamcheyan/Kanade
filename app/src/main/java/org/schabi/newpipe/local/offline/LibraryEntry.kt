package org.schabi.newpipe.local.offline

/**
 * What the offline library shows for one video.
 *
 * The list is built from two sources that know different things: the library
 * endpoint only lists finished files, the watch-later endpoint only lists
 * membership, and the downloads endpoint only reports job state. Merging them
 * is what lets a row exist before its file does.
 */
data class LibraryEntry(
    val videoId: String,
    val accountId: String,
    val title: String,
    val thumbnail: String,
    val duration: Int,
    val state: State,
    val progress: Int = 0,
    val jobId: Long = 0,
    val remoteMissingSince: String = "",
    val favorite: Boolean = false,
    val resumePosition: Long = 0,
    val error: String = ""
) {
    enum class State {
        DOWNLOADED,

        /** A job is queued and waiting for a download slot. */
        QUEUED,

        /** A job is transferring. */
        ACTIVE,

        /** A job is paused and waits for a manual resume. */
        PAUSED,

        /** The last job failed; tapping retries. */
        FAILED,

        /** In the source list but never downloaded; tapping queues it. */
        PENDING
    }

    /** A row counts as watched when the source list no longer contains it. */
    val isWatched: Boolean get() = remoteMissingSince.isNotEmpty()

    /** Favorited rows keep their place in the favorites tab. */
    val isFavorite: Boolean get() = favorite

    /** Rows with queued/transferring jobs are the only ones worth polling. */
    val isActive: Boolean get() = state == State.QUEUED || state == State.ACTIVE
}

/**
 * Merge the three endpoints into the rows the offline library renders.
 *
 * Downloaded files come first in the order the service returned them, then the
 * rest of the source list in its own order, so a refresh never reshuffles rows
 * the user is looking at. `watchLater` is expected to already be filtered to
 * the sources currently shown.
 */
fun buildLibraryEntries(
    downloaded: List<OfflineVideo>,
    watchLater: List<WatchLaterItem>,
    jobs: List<DownloadJob>,
    resumeById: Map<String, Long> = emptyMap(),
    favorites: Set<String> = emptySet()
): List<LibraryEntry> {
    // Only the newest job per video matters: retries insert a new row and leave
    // the failed one behind.
    val latestJob = HashMap<String, DownloadJob>()
    for (job in jobs) {
        if (job.videoId.isEmpty()) continue
        val key = job.accountId + "/" + job.videoId
        val previous = latestJob[key]
        if (previous == null || job.id > previous.id) latestJob[key] = job
    }

    val entries = ArrayList<LibraryEntry>(downloaded.size + watchLater.size)
    val seen = HashSet<String>()

    for (video in downloaded) {
        val key = video.accountId + "/" + video.id
        seen.add(key)
        entries.add(
            LibraryEntry(
                videoId = video.id,
                accountId = video.accountId,
                title = video.title,
                thumbnail = video.thumbnail,
                duration = video.duration,
                state = LibraryEntry.State.DOWNLOADED,
                remoteMissingSince = video.remoteMissingSince,
                favorite = key in favorites,
                resumePosition = resumeById[video.id] ?: 0L
            )
        )
    }

    for (item in watchLater) {
        val key = item.accountId + "/" + item.id
        if (!seen.add(key)) continue
        val job = latestJob[key]
        val state = when (job?.state) {
            DownloadJob.DOWNLOADING -> LibraryEntry.State.ACTIVE

            DownloadJob.PAUSED -> LibraryEntry.State.PAUSED

            DownloadJob.QUEUED -> LibraryEntry.State.QUEUED

            DownloadJob.FAILED -> LibraryEntry.State.FAILED

            // A cancelled job may be retried by tapping, so it reads as pending.
            else -> LibraryEntry.State.PENDING
        }
        entries.add(
            LibraryEntry(
                videoId = item.id,
                accountId = item.accountId,
                title = item.title,
                thumbnail = item.thumbnail,
                duration = item.duration,
                state = state,
                progress = job?.progress ?: 0,
                jobId = job?.id ?: 0L,
                favorite = key in favorites,
                resumePosition = resumeById[item.id] ?: 0L,
                error = job?.error.orEmpty()
            )
        )
    }

    return entries
}
