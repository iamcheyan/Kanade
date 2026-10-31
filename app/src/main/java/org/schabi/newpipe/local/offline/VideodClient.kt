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
    val accountId: String
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

    private fun post(path: String, body: String): String {
        val connection = URL(baseUrl + path).openConnection() as java.net.HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 10_000
        connection.readTimeout = TIMEOUT_MS
        connection.setRequestProperty("Content-Type", "application/json")
        connection.outputStream.use { it.write(body.toByteArray()) }
        val stream = if (connection.responseCode in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream
        }
        return stream.bufferedReader().use { it.readText() }
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
            accountId = stringField(obj, "account_id")
        )
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
}

/** "12:34" for a duration in seconds. */
fun formatDuration(seconds: Int): String {
    if (seconds <= 0) return ""
    val m = seconds / 60
    val s = seconds % 60
    return String.format("%d:%02d", m, s)
}
