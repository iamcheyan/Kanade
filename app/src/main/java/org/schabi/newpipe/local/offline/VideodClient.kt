package org.schabi.newpipe.local.offline

import java.net.URL

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
)

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
    }

    /** Downloaded files for one source. */
    fun library(account: String): List<OfflineVideo> {
        return parseLibrary(get("/api/library?account_id=$account"))
    }

    /** Downloaded files from every source, merged. */
    fun libraryAll(): List<OfflineVideo> = parseLibrary(get("/api/library"))

    /** The source's remote watch-later list, downloaded or not. */
    fun watchLater(account: String): List<WatchLaterItem> {
        return parseWatchLater(get("/api/watch_later?account_id=$account&limit=200"))
    }

    /**
     * Pull the remote list into the local database.
     *
     * [autoDownload] decides whether newly seen videos are queued for
     * download. Passing false keeps the list browsable without consuming disk,
     * which is what the sync toggle off is for.
     */
    fun sync(account: String, autoDownload: Boolean): String {
        val body = """{"what":"watch_later","source":"$account","auto_download":$autoDownload}"""
        return post("/api/sync", body)
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

    /** Resume position in seconds for one video, or 0 when never played. */
    fun resumePosition(account: String, videoId: String): Long {
        val json = get("/api/history?account_id=$account&limit=500")
        for (obj in objects(json)) {
            if (stringField(obj, "video_id") != videoId) continue
            return longField(obj, "position_sec")
        }
        return 0L
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

    private fun post(path: String, body: String): String {
        val connection = URL(baseUrl + path).openConnection() as java.net.HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 10_000
        connection.readTimeout = TIMEOUT_MS
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
