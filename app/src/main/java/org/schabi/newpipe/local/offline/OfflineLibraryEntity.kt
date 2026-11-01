package org.schabi.newpipe.local.offline

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/** Durable app-owned snapshot of one locally downloaded video. */
@Entity(
    tableName = "offline_library",
    indices = [Index(value = ["account_id", "video_id"], unique = true)]
)
data class OfflineLibraryEntity(
    @PrimaryKey
    @ColumnInfo(name = "library_key") val libraryKey: String,
    @ColumnInfo(name = "video_id") val videoId: String,
    @ColumnInfo(name = "account_id") val accountId: String,
    val title: String,
    val thumbnail: String,
    val duration: Int,
    @ColumnInfo(name = "local_path") val localPath: String,
    val container: String,
    val encoding: String,
    @ColumnInfo(name = "downloaded_at") val downloadedAt: String,
    @ColumnInfo(name = "remote_missing_since") val remoteMissingSince: String,
    val favorite: Boolean,
    @ColumnInfo(name = "resume_position") val resumePosition: Long,
    val missing: Boolean = false,
    @ColumnInfo(name = "channel_id") val channelId: String = "",
    val url: String = ""
) {
    fun toEntry(): LibraryEntry = LibraryEntry(
        videoId = videoId,
        accountId = accountId,
        title = title,
        thumbnail = thumbnail,
        duration = duration,
        state = LibraryEntry.State.DOWNLOADED,
        remoteMissingSince = remoteMissingSince,
        favorite = favorite,
        resumePosition = resumePosition,
        localPath = localPath,
        container = container,
        encoding = encoding,
        downloadedAt = downloadedAt,
        missing = missing,
        channelId = channelId,
        url = url
    )

    companion object {
        fun key(accountId: String, videoId: String) = "$accountId/$videoId"

        fun fromVideo(video: OfflineVideo, previous: OfflineLibraryEntity? = null) = OfflineLibraryEntity(
            libraryKey = key(video.accountId, video.id),
            videoId = video.id,
            accountId = video.accountId,
            title = video.title,
            thumbnail = video.thumbnail,
            duration = video.duration,
            localPath = video.path,
            container = video.container,
            encoding = video.encoding,
            downloadedAt = video.downloadedAt,
            remoteMissingSince = video.remoteMissingSince.ifEmpty {
                previous?.remoteMissingSince.orEmpty()
            },
            favorite = previous?.favorite ?: false,
            resumePosition = previous?.resumePosition ?: 0L,
            missing = false,
            channelId = video.channelId,
            url = video.url
        )
    }
}
