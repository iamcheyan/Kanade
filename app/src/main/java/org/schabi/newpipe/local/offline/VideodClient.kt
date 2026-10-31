package org.schabi.newpipe.local.offline
import java.net.URL
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One completed download, as returned by videod's library endpoint.
 *
 * The file path is deliberately not carried: videod runs as a different uid,
 * so this app cannot read the file and plays through the service's HTTP
 * stream instead.
 */
data class OfflineVideo(
    val id: String,
    val title: String,
    val thumbnail: String,
    val duration: Int,
    val accountId: String,
    val remoteMissingSince: String = ""
) {
    val isWatched: Boolean get() = remoteMissingSince.isNotEmpty()
}

/**
 * One row of a remote watch-later list, as returned by videod's watch_later
 * endpoint. Distinct from [OfflineVideo], which describes a downloaded file.
 */
data class WatchLaterItem(
    val id: String,
    val title: String,
    val url: String,
    val thumbnail: String,
    val duration: Int,
    val accountId: String
)

/**
 * One download job, as returned by videod's downloads endpoint.
 *
 * The endpoint has no title: it only knows the job, so rows built from it are
 * joined with the watch-later list for their metadata.
 */
data class DownloadJob(
    val id: Long,
    val accountId: String,
    val videoId: String,
    val state: String,
    val progress: Int,
    val error: String
) {
    companion object {
        const val QUEUED = "queued"
        const val DOWNLOADING = "downloading"
        const val PAUSED = "paused"
        const val COMPLETED = "completed"
        const val FAILED = "failed"
        const val CANCELLED = "cancelled"

        /** States that still make progress without user input. */
        val RUNNING_STATES = setOf(QUEUED, DOWNLOADING, PAUSED)
    }

    /** True while the job is worth polling for. */
    val isActive: Boolean get() = state in RUNNING_STATES
}

/** One source's share of the download directory. */
data class SourceUsage(val account: String, val files: Int, val bytes: Long)

/** One video's file size. */
data class VideoUsage(val videoId: String, val account: String, val title: String, val bytes: Long, val watched: Boolean)

/** Per-source and per-video space usage. */
data class StorageBreakdown(
    val totalBytes: Long,
    val orphanBytes: Long,
    val sources: List<SourceUsage>,
    val videos: List<VideoUsage>,
    val watchedFiles: Int,
    val watchedBytes: Long
)

/** Disk usage reported by the service. */
data class StorageStats(
    val files: Int,
    val bytes: Long,
    val orphans: Int,
    val orphanBytes: Long
)

data class SyncResponse(
    val source: String,
    val accountId: String,
    val providerSync: String,
    val syncedItems: Int,
    val watchLaterItems: Int,
    val downloadsQueued: Int,
    val errors: List<String>,
    val note: String
)

/**
 * Parameters for requesting a sync operation from videod.
 *
 * @property source Specific source to sync (e.g. Sources.YOUTUBE, Sources.BILIBILI), or null to sync all active sources.
 * @property what Sync scope: "watch_later", "subscriptions", "playlists", "all", or "channel". Defaults to "watch_later".
 * @property channelId Optional channel ID if syncing a specific channel.
 * @property autoDownload Whether newly discovered items should be automatically queued for download.
 */
data class SyncParams(
    val source: String? = null,
    val what: String = SCOPE_WATCH_LATER,
    val channelId: String? = null,
    val autoDownload: Boolean = false
) {
    companion object {
        const val SCOPE_WATCH_LATER = "watch_later"
        const val SCOPE_SUBSCRIPTIONS = "subscriptions"
        const val SCOPE_PLAYLISTS = "playlists"
        const val SCOPE_ALL = "all"
        const val SCOPE_CHANNEL = "channel"

        fun watchLater(source: String? = null, autoDownload: Boolean = false): SyncParams = SyncParams(source = source, what = SCOPE_WATCH_LATER, autoDownload = autoDownload)

        fun all(source: String? = null, autoDownload: Boolean = false): SyncParams = SyncParams(source = source, what = SCOPE_ALL, autoDownload = autoDownload)

        fun channel(channelId: String, source: String? = null, autoDownload: Boolean = false): SyncParams = SyncParams(source = source, what = SCOPE_CHANNEL, channelId = channelId, autoDownload = autoDownload)
    }
}

/**
 * Result of syncing one or multiple sources.
 */
data class SyncResult(
    val responses: List<SyncResponse>,
    val errors: List<String> = emptyList()
) {
    val isSuccess: Boolean get() = errors.isEmpty()
    val hasItems: Boolean get() = responses.any { it.syncedItems > 0 }
    val totalSynced: Int get() = responses.sumOf { if (it.syncedItems > 0) it.syncedItems else 0 }
    val totalDownloadsQueued: Int get() = responses.sumOf { it.downloadsQueued }

    fun summaryMessage(): String {
        return if (errors.isEmpty()) {
            "Synced $totalSynced items"
        } else {
            errors.joinToString("; ")
        }
    }
}

/** Where a library row came from; each source has its own account. */
object Sources {
    const val YOUTUBE = "youtube"
    const val BILIBILI = "bilibili"

    fun label(account: String): String = when (account) {
        YOUTUBE -> "YouTube"
        BILIBILI -> "Bilibili"
        else -> account
    }
}

/**
 * Client for the videod service running on this device.
 *
 * The service is the single writer of the download queue: the app never talks
 * to YouTube or Bilibili directly, so cookie handling and yt-dlp stay in one
 * process.
 */
class VideodClient(private val baseUrl: String = DEFAULT_BASE_URL) {

    companion object {
        /** videod listens here by default (POCKET_YOUTUBE_ADDR). */
        const val DEFAULT_BASE_URL = "http://127.0.0.1:8732"

        private const val TIMEOUT_MS = 30_000

        /**
         * Provider sync runs yt-dlp on the device and the service allows it five
         * minutes; a shorter read timeout would abort the request on the client
         * side while the service keeps working, and its context cancellation
         * then throws the fetched list away.
         */
        private const val SYNC_TIMEOUT_MS = 330_000
        private const val PAGE_SIZE = 100
        private const val HISTORY_LOOKBACK_LIMIT = 500

        /**
         * Job states that decide how a not-yet-downloaded row reads. A failed
         * job keeps its row retryable; cancelled jobs are queried too so a row
         * does not linger on a state the service already dropped.
         */
        private val JOB_STATES = listOf(
            DownloadJob.QUEUED,
            DownloadJob.DOWNLOADING,
            DownloadJob.PAUSED,
            DownloadJob.FAILED,
            DownloadJob.CANCELLED
        )
        private val ACTIVE_JOB_STATES = listOf(
            DownloadJob.QUEUED,
            DownloadJob.DOWNLOADING
        )
    }

    /** Downloaded files for one source. */
    fun library(account: String): List<OfflineVideo> {
        return parseLibrary(get("/api/library?account_id=$account"))
    }

    /** Downloaded files from every source, merged. */
    fun libraryAll(): List<OfflineVideo> = parseLibrary(get("/api/library"))

    /**
     * The user setting shared with the service's post-sync auto-queue policy.
     * Missing/unknown values retain the backend default (on).
     */
    fun autoDownloadEnabled(): Boolean = settings()["auto_download"]?.let {
        it != "off" && it != "false" && it != "0"
    } ?: true

    /**
     * Download jobs for one source that still describe a row.
     *
     * The listing is paged by creation order, and a long-lived device holds far
     * more finished jobs than one page, so asking for everything would return
     * the oldest page and miss the job a user just started. Each state that can
     * affect a row is queried on its own instead; `completed` is not, because a
     * finished download shows up in the library endpoint.
     */
    fun downloads(account: String): List<DownloadJob> = downloadJobs(account, JOB_STATES)

    /** Active states for lightweight polling; finished failures are loaded once. */
    fun activeDownloads(account: String): List<DownloadJob> = downloadJobs(account, ACTIVE_JOB_STATES)

    private fun downloadJobs(account: String, states: List<String>): List<DownloadJob> {
        val jobs = ArrayList<DownloadJob>()
        for (state in states) {
            var offset = 0
            while (true) {
                val page = try {
                    parseDownloads(
                        get("/api/downloads?account_id=$account&state=$state&limit=$PAGE_SIZE&offset=$offset")
                    )
                } catch (e: IllegalStateException) {
                    // Ignore older videod builds that do not support a state.
                    break
                }
                jobs.addAll(page)
                if (page.size < PAGE_SIZE) break
                offset += PAGE_SIZE
            }
        }
        return jobs
    }

    /**
     * Queue several videos in one request.
     *
     * The service rejects a batch that queued nothing, which happens when every
     * id already has a job; that is not an error worth surfacing.
     */
    fun queueDownloads(account: String, videoIds: List<String>): Int {
        if (videoIds.isEmpty()) return 0
        val batch = videoIds.joinToString(",", "[", "]") { "\"" + it.replace("\"", "\\\"") + "\"" }
        val body = "{\"video_ids\":$batch,\"mode\":\"video\"}"
        return try {
            val json = Json.parseToJsonElement(post("/api/downloads?account_id=$account", body)).jsonObject
            json["queued"]?.jsonPrimitive?.intOrNull ?: 0
        } catch (e: IllegalStateException) {
            // HTTP 400 batch_rejected: nothing left to queue.
            if (e.message?.contains("batch_rejected") == true) 0 else throw e
        }
    }

    /** Resume a paused service job. */
    fun resumeDownload(account: String, id: Long): String {
        return post("/api/downloads/$id?account_id=$account", "{\"action\":\"resume\"}")
    }

    /** The source's remote watch-later list, downloaded or not. */
    fun watchLater(account: String): List<WatchLaterItem> {
        val items = ArrayList<WatchLaterItem>()
        var offset = 0
        while (true) {
            val page = parseWatchLater(
                get("/api/watch_later?account_id=$account&limit=$PAGE_SIZE&offset=$offset")
            )
            items.addAll(page)
            if (page.size < PAGE_SIZE) break
            offset += PAGE_SIZE
        }
        return items
    }

    /**
     * Pull remote lists or channel data into the local database according to [params].
     * When [SyncParams.source] is null, syncs all supported sources and aggregates results.
     */
    fun sync(params: SyncParams): SyncResult {
        val sources = if (params.source != null) {
            listOf(params.source)
        } else {
            listOf(Sources.YOUTUBE, Sources.BILIBILI)
        }

        val responses = mutableListOf<SyncResponse>()
        val errors = mutableListOf<String>()

        for (source in sources) {
            try {
                // Note: Bilibili only supports watch_later. If all/subscriptions/playlists is requested, adjust scope for bilibili.
                val effectiveWhat = if (source == Sources.BILIBILI &&
                    (
                        params.what == SyncParams.SCOPE_ALL ||
                            params.what == SyncParams.SCOPE_SUBSCRIPTIONS ||
                            params.what == SyncParams.SCOPE_PLAYLISTS
                        )
                ) {
                    SyncParams.SCOPE_WATCH_LATER
                } else {
                    params.what
                }

                val resp = executeSync(
                    source = source,
                    what = effectiveWhat,
                    autoDownload = params.autoDownload,
                    channelId = params.channelId
                )
                responses.add(resp)
                if (resp.errors.isNotEmpty()) {
                    errors.addAll(resp.errors.map { "${Sources.label(source)}: $it" })
                }
            } catch (e: Exception) {
                errors.add("${Sources.label(source)}: ${e.message ?: e.javaClass.simpleName}")
            }
        }

        return SyncResult(responses, errors)
    }

    /**
     * Legacy single-source sync compatibility.
     */
    fun sync(account: String, autoDownload: Boolean): SyncResponse {
        return executeSync(account, SyncParams.SCOPE_WATCH_LATER, autoDownload, null)
    }

    private fun executeSync(
        source: String,
        what: String,
        autoDownload: Boolean,
        channelId: String?
    ): SyncResponse {
        val sb = StringBuilder()
        sb.append("{\"what\":\"").append(what).append("\"")
        sb.append(",\"source\":\"").append(source).append("\"")
        sb.append(",\"auto_download\":").append(autoDownload)
        if (!channelId.isNullOrBlank()) {
            sb.append(",\"channel_id\":\"").append(channelId).append("\"")
        }
        sb.append("}")

        val json = Json.parseToJsonElement(
            post("/api/sync", sb.toString(), SYNC_TIMEOUT_MS)
        ).jsonObject
        val errors = json["errors"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
        return SyncResponse(
            source = json["source"]?.jsonPrimitive?.content ?: source,
            accountId = json["account_id"]?.jsonPrimitive?.content.orEmpty(),
            providerSync = json["provider_sync"]?.jsonPrimitive?.content.orEmpty(),
            syncedItems = json["synced_items"]?.jsonPrimitive?.intOrNull ?: -1,
            watchLaterItems = json["watch_later"]?.jsonPrimitive?.intOrNull ?: -1,
            downloadsQueued = json["downloads_queued"]?.jsonPrimitive?.intOrNull ?: 0,
            errors = errors,
            note = json["note"]?.jsonPrimitive?.content.orEmpty()
        )
    }

    /** Queue one video for download. */
    fun download(account: String, videoId: String): String {
        val body = """{"video_id":"$videoId","mode":"video","quality":"720p"}"""
        return post("/api/downloads?account_id=$account", body)
    }

    /** Settings map, defaults included by the service. */
    fun settings(): Map<String, String> = parseStringMap(get("/api/settings"))

    /** Persist a partial settings map; unknown keys are ignored by the service. */
    fun saveSettings(values: Map<String, String>): String {
        val body = values.entries.joinToString(",", "{", "}") { (k, v) ->
            "\"" + k + "\":\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        }
        return put("/api/settings", body)
    }

    /** Disk usage of the download directory. */
    fun storage(): StorageStats = parseStorage(get("/api/storage"))

    /** Space usage broken down by source and by video. */
    fun breakdown(): StorageBreakdown = parseBreakdown(get("/api/storage/breakdown"))

    /** Remove one completed download and its file, without changing watch later. */
    fun deleteLibrary(account: String, videoId: String) {
        val connection = URL("$DEFAULT_BASE_URL/api/library/$videoId?account_id=$account")
            .openConnection() as java.net.HttpURLConnection
        connection.requestMethod = "DELETE"
        connection.connectTimeout = 10_000
        connection.readTimeout = TIMEOUT_MS
        try {
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("Delete failed: HTTP ${connection.responseCode}")
            }
        } finally {
            connection.disconnect()
        }
    }

    /** Remove files. scope is "orphans", "watched" or "all". */
    fun clearStorage(scope: String): String = post("/api/storage/clear?scope=" + scope, "{}")

    /**
     * Resume positions for the requested videos, fetched in pages. Most recent
     * history is returned first, so scanning stops as soon as every requested
     * id is found (or the service has no more rows).
     */
    fun resumePositions(account: String, videoIds: Set<String>): Map<String, Long> {
        if (videoIds.isEmpty()) return emptyMap()
        val positions = HashMap<String, Long>(videoIds.size)
        var offset = 0
        while (positions.size < videoIds.size && offset < HISTORY_LOOKBACK_LIMIT) {
            val requestLimit = minOf(PAGE_SIZE, HISTORY_LOOKBACK_LIMIT - offset)
            val page = parseHistoryPositions(
                get("/api/history?account_id=$account&limit=$requestLimit&offset=$offset")
            )
            if (page.isEmpty()) break
            for (id in videoIds) {
                if (id in page) positions[id] = page.getValue(id)
            }
            if (page.size < requestLimit) break
            offset += PAGE_SIZE
        }
        return positions
    }

    /** Resume position in seconds for one video. */
    fun resumePosition(account: String, videoId: String): Long {
        return resumePositions(account, setOf(videoId))[videoId] ?: 0L
    }

    /** Remember how far the user got in a video. */
    fun saveProgress(account: String, videoId: String, positionSec: Long, durationSec: Long) {
        val body = "{\"video_id\":\"$videoId\",\"position_sec\":$positionSec," +
            "\"duration_sec\":$durationSec}"
        post("/api/history?account_id=$account", body)
    }

    /** Mark watched: local removal plus remote write-back when supported. */
    fun markWatched(account: String, videoId: String): String {
        return post("/api/watch_later/$videoId/watched?account_id=$account", "{}")
    }

    // ---------------------------------------------------------------- HTTP

    private fun get(path: String): String {
        val connection = URL(baseUrl + path).openConnection() as java.net.HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = TIMEOUT_MS
        return try {
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun put(path: String, body: String): String {
        val connection = URL(baseUrl + path).openConnection() as java.net.HttpURLConnection
        connection.requestMethod = "PUT"
        connection.doOutput = true
        connection.connectTimeout = 10_000
        connection.readTimeout = TIMEOUT_MS
        connection.setRequestProperty("Content-Type", "application/json")
        connection.outputStream.use { it.write(body.toByteArray()) }
        return connection.inputStream.bufferedReader().use { it.readText() }
    }

    private fun post(path: String, body: String): String = post(path, body, TIMEOUT_MS)

    private fun post(path: String, body: String, readTimeoutMs: Int): String {
        val connection = URL(baseUrl + path).openConnection() as java.net.HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 10_000
        connection.readTimeout = readTimeoutMs
        connection.setRequestProperty("Content-Type", "application/json")
        connection.outputStream.use { it.write(body.toByteArray()) }
        val responseCode = connection.responseCode
        val stream = if (responseCode in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream ?: throw IllegalStateException("HTTP $responseCode")
        }
        val response = stream.bufferedReader().use { it.readText() }
        if (responseCode !in 200..299) {
            throw IllegalStateException("HTTP $responseCode: $response")
        }
        return response
    }

    // --------------------------------------------------------------- parsing

    /** Split a JSON array into its object elements without a JSON library. */
    private fun objects(json: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (true) {
            val start = json.indexOf('{', i)
            if (start < 0) break
            val end = objectEnd(json, start)
            if (end < 0) break
            out.add(json.substring(start, end + 1))
            i = end + 1
        }
        return out
    }

    internal fun parseLibrary(json: String): List<OfflineVideo> = objects(json).map { obj ->
        OfflineVideo(
            id = stringField(obj, "id"),
            title = stringField(obj, "title").ifEmpty { stringField(obj, "id") },
            thumbnail = stringField(obj, "thumbnail"),
            duration = intField(obj, "duration"),
            accountId = stringField(obj, "account_id"),
            remoteMissingSince = stringField(obj, "remote_missing_since")
        )
    }

    /** Flat JSON object of string values. */
    internal fun parseStringMap(json: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val pattern = "\"([^\"]+)\"\\s*:\\s*\"([^\"]*)\"".toRegex()
        for (m in pattern.findAll(json)) {
            out[m.groupValues[1]] = m.groupValues[2]
        }
        return out
    }

    /** Storage object; missing numeric fields read as zero. */
    internal fun parseStorage(json: String): StorageStats = StorageStats(
        files = intField(json, "files"),
        bytes = longField(json, "bytes"),
        orphans = intField(json, "orphans"),
        orphanBytes = longField(json, "orphan_bytes")
    )

    internal fun parseBreakdown(json: String): StorageBreakdown {
        // The response has two arrays; objects() already walks any array, so
        // slice each section out by its key and parse it independently.
        val sources = sliceArray(json, "sources")
        val videos = sliceArray(json, "videos")
        return StorageBreakdown(
            totalBytes = longField(json, "total_bytes"),
            orphanBytes = longField(json, "orphan_bytes"),
            watchedFiles = intField(json, "watched", "files"),
            watchedBytes = longField(json, "watched", "bytes"),
            sources = sources.mapNotNull { obj ->
                val account = stringField(obj, "account")
                if (account.isEmpty()) {
                    null
                } else {
                    SourceUsage(
                        account = account,
                        files = intField(obj, "files"),
                        bytes = longField(obj, "bytes")
                    )
                }
            },
            videos = videos.mapNotNull { obj ->
                val id = stringField(obj, "video_id")
                if (id.isEmpty()) {
                    null
                } else {
                    VideoUsage(
                        videoId = id,
                        account = stringField(obj, "account"),
                        title = stringField(obj, "title").ifEmpty { id },
                        bytes = longField(obj, "bytes"),
                        watched = booleanField(obj, "watched")
                    )
                }
            }
        )
    }

    /**
     * The object elements of the array stored under key.
     *
     * Walks from the opening bracket and tracks nesting, so nested objects and
     * strings containing brackets do not end the slice early.
     */
    private fun sliceArray(json: String, key: String): List<String> {
        val marker = "\"$key\":["
        val markerAt = json.indexOf(marker)
        if (markerAt < 0) return emptyList()
        val start = markerAt + marker.length - 1 // points at '['
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until json.length) {
            val c = json[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true

                '[' -> depth++

                ']' -> {
                    depth--
                    if (depth == 0) {
                        return objects(json.substring(start, i + 1))
                    }
                }
            }
        }
        return emptyList()
    }

    internal fun parseWatchLater(json: String): List<WatchLaterItem> = objects(json).map { obj ->
        WatchLaterItem(
            id = stringField(obj, "id"),
            title = stringField(obj, "title").ifEmpty { stringField(obj, "id") },
            url = stringField(obj, "url"),
            thumbnail = stringField(obj, "thumbnail"),
            duration = intField(obj, "duration"),
            accountId = stringField(obj, "account_id")
        )
    }

    /** Map history page entries by video ID to their last saved position. */
    internal fun parseHistoryPositions(json: String): Map<String, Long> {
        val positions = HashMap<String, Long>()
        for (obj in objects(json)) {
            val id = stringField(obj, "video_id").ifEmpty { stringField(obj, "id") }
            if (id.isNotEmpty()) positions[id] = longField(obj, "position_sec")
        }
        return positions
    }

    /** Download jobs; progress is a whole percentage, as the service stores it. */
    internal fun parseDownloads(json: String): List<DownloadJob> = objects(json).map { obj ->
        DownloadJob(
            id = longField(obj, "id"),
            accountId = stringField(obj, "account_id"),
            videoId = stringField(obj, "video_id"),
            state = stringField(obj, "state"),
            progress = intField(obj, "progress"),
            error = stringField(obj, "error")
        )
    }

    private fun objectEnd(json: String, start: Int): Int {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until json.length) {
            val c = json[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true

                '{' -> depth++

                '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return -1
    }

    private fun stringField(obj: String, name: String): String {
        val marker = "\"$name\":"
        val at = obj.indexOf(marker)
        if (at < 0) return ""
        var i = at + marker.length
        while (i < obj.length && obj[i].isWhitespace()) i++
        if (i >= obj.length || obj[i] != '"') return ""
        i++
        val sb = StringBuilder()
        var escaped = false
        while (i < obj.length) {
            val c = obj[i]
            if (escaped) {
                sb.append(
                    when (c) {
                        'n' -> '\n'
                        't' -> '\t'
                        'r' -> '\r'
                        '"' -> '"'
                        '\\' -> '\\'
                        '/' -> '/'
                        else -> c
                    }
                )
                escaped = false
            } else if (c == '\\') {
                escaped = true
            } else if (c == '"') {
                break
            } else {
                sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    private fun longField(obj: String, name: String): Long {
        val marker = "\"$name\":"
        val at = obj.indexOf(marker)
        if (at < 0) return 0L
        var i = at + marker.length
        while (i < obj.length && obj[i].isWhitespace()) i++
        val start = i
        while (i < obj.length && (obj[i].isDigit() || obj[i] == '-')) i++
        return obj.substring(start, i).toLongOrNull() ?: 0L
    }

    private fun intField(obj: String, name: String): Int {
        val marker = "\"$name\":"
        val at = obj.indexOf(marker)
        if (at < 0) return 0
        var i = at + marker.length
        while (i < obj.length && obj[i].isWhitespace()) i++
        val start = i
        while (i < obj.length && (obj[i].isDigit() || obj[i] == '-')) i++
        return obj.substring(start, i).toIntOrNull() ?: 0
    }

    private fun intField(json: String, objectName: String, fieldName: String): Int {
        return sliceObject(json, objectName)?.let { intField(it, fieldName) } ?: 0
    }

    private fun longField(json: String, objectName: String, fieldName: String): Long {
        return sliceObject(json, objectName)?.let { longField(it, fieldName) } ?: 0L
    }

    private fun booleanField(obj: String, name: String): Boolean {
        val marker = "\"$name\":"
        val at = obj.indexOf(marker)
        if (at < 0) return false
        return obj.substring(at + marker.length).trimStart().startsWith("true")
    }

    private fun sliceObject(json: String, key: String): String? {
        val marker = "\"$key\":"
        val markerAt = json.indexOf(marker)
        if (markerAt < 0) return null
        val start = json.indexOf('{', markerAt + marker.length)
        if (start < 0) return null
        val end = objectEnd(json, start)
        return if (end >= 0) json.substring(start, end + 1) else null
    }
}

/** "12:34" for a duration in seconds. */
fun formatDuration(seconds: Int): String {
    if (seconds <= 0) return ""
    val m = seconds / 60
    val s = seconds % 60
    return String.format("%d:%02d", m, s)
}

/** "12:34" for a position in whole seconds. */
fun formatSeconds(totalSeconds: Long): String {
    val s = totalSeconds.coerceAtLeast(0)
    return String.format("%d:%02d", s / 60, s % 60)
}
