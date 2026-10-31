package org.schabi.newpipe.local.offline

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Player
import java.util.concurrent.CopyOnWriteArraySet
import org.schabi.newpipe.R

/**
 * Plays one videod stream with a foreground notification.
 *
 * This is a deliberately small service rather than a reuse of PlayerService:
 * that one is built around Extractor StreamInfo and a play queue, while an
 * offline item is already a plain HTTP URL. Keeping the two apart means the
 * existing player keeps working untouched.
 *
 * One player instance lives here for the process lifetime, so leaving the
 * detail screen keeps audio running and returning re-attaches to the same
 * playback position.
 */
class OfflinePlayerService : Service() {

    /**
     * Called on the main thread whenever the shared player changes in a way a
     * mini player would show: a new item, play/pause, or teardown.
     *
     * Declared at class level so callers can name it as
     * OfflinePlayerService.PlaybackListener; nesting it in the companion would
     * require the Companion qualifier, which reads worse at every call site.
     */
    fun interface PlaybackListener {
        fun onPlaybackChanged()
    }

    companion object {
        private const val CHANNEL_ID = "jm21_offline_playback"
        private const val NOTIFICATION_ID = 4711
        private const val PREFS_NAME = "offline_player_session"
        private const val PREF_URL = "url"
        private const val PREF_TITLE = "title"
        private const val PREF_ARTIST = "artist"
        private const val PREF_ART = "art"
        private const val PREF_ID = "video_id"
        private const val PREF_ACCOUNT = "account"
        private const val PREF_POSITION = "position"

        const val ACTION_PLAY = "org.schabi.newpipe.offline.PLAY"
        const val ACTION_TOGGLE = "org.schabi.newpipe.offline.TOGGLE"
        const val ACTION_STOP = "org.schabi.newpipe.offline.STOP"
        const val ACTION_LOAD_PAUSED = "org.schabi.newpipe.offline.LOAD_PAUSED"
        const val ACTION_RESTORE = "org.schabi.newpipe.offline.RESTORE"

        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_ARTIST = "artist"
        const val EXTRA_ART = "art"
        const val EXTRA_ID = "video_id"
        const val EXTRA_ACCOUNT = "account"

        /** The live player, or null when nothing has been started. */
        @Volatile
        var player: ExoPlayer? = null
            private set

        @Volatile
        var currentTitle: String = ""
            private set

        @Volatile
        var currentArtist: String = ""
            private set

        /** Thumbnail of the playing item, used by the mini player. */
        @Volatile
        private var currentArt: String = ""

        /** Identity of the playing item, so it can be reopened full screen. */
        @Volatile
        private var currentVideoId: String = ""

        @Volatile
        private var currentAccount: String = ""

        private val stateListeners = CopyOnWriteArraySet<PlaybackListener>()
        private val mainHandler = Handler(Looper.getMainLooper())

        /**
         * Whether there is something to show in a mini player.
         *
         * The service is sticky, so this also covers "played earlier, app was
         * left in the background": the item is still loaded and can resume.
         */
        @JvmStatic
        fun hasItem(): Boolean = player?.currentMediaItem != null && currentVideoId.isNotEmpty()

        @JvmStatic
        fun isPlaying(): Boolean = player?.isPlaying == true

        @JvmStatic
        fun artUrl(): String = currentArt

        @JvmStatic
        fun videoId(): String = currentVideoId

        @JvmStatic
        fun account(): String = currentAccount

        @JvmStatic
        fun restore(context: Context) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (prefs.getString(PREF_ID, "").isNullOrEmpty()) return
            val intent = Intent(context, OfflinePlayerService::class.java).setAction(ACTION_RESTORE)
            context.startService(intent)
        }

        /** Flip play/pause; same process, so no service round trip needed. */
        @JvmStatic
        fun toggle() {
            val exo = player ?: return
            exo.playWhenReady = !exo.playWhenReady
            notifyPlaybackChanged()
        }

        /** Tear the session down and remove the notification. */
        @JvmStatic
        fun stop(context: Context) {
            context.startService(
                Intent(context, OfflinePlayerService::class.java).setAction(ACTION_STOP)
            )
        }

        @JvmStatic
        fun addPlaybackListener(listener: PlaybackListener) {
            stateListeners.add(listener)
        }

        @JvmStatic
        fun removePlaybackListener(listener: PlaybackListener) {
            stateListeners.remove(listener)
        }

        /**
         * Posted from any thread; listeners always run on the main thread
         * because they touch views.
         */
        fun notifyPlaybackChanged() {
            mainHandler.post {
                for (listener in stateListeners) {
                    listener.onPlaybackChanged()
                }
            }
        }

        /** Ask for playback without needing a bound connection. */
        @JvmStatic
        fun play(
            context: Context,
            url: String,
            title: String,
            artist: String,
            art: String,
            videoId: String,
            account: String
        ) {
            val intent = Intent(context, OfflinePlayerService::class.java).apply {
                action = ACTION_PLAY
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_ARTIST, artist)
                putExtra(EXTRA_ART, art)
                putExtra(EXTRA_ID, videoId)
                putExtra(EXTRA_ACCOUNT, account)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Load media while leaving playback paused. */
        @JvmStatic
        fun loadPaused(
            context: Context,
            url: String,
            title: String,
            artist: String,
            art: String,
            videoId: String,
            account: String
        ) {
            val intent = Intent(context, OfflinePlayerService::class.java).apply {
                action = ACTION_LOAD_PAUSED
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_ARTIST, artist)
                putExtra(EXTRA_ART, art)
                putExtra(EXTRA_ID, videoId)
                putExtra(EXTRA_ACCOUNT, account)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private val binder = LocalBinder()
    private val handler = Handler(Looper.getMainLooper())
    private var mediaSession: MediaSessionCompat? = null

    inner class LocalBinder : Binder() {
        fun service(): OfflinePlayerService = this@OfflinePlayerService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createChannel()
        setUpMediaSession()
        // Build the player eagerly. The detail screen binds and immediately
        // asks for it; creating it here removes the window where the view has
        // nothing to attach to and shows a black frame.
        ensurePlayer()
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        currentVideoId = prefs.getString(PREF_ID, "").orEmpty()
        if (currentVideoId.isNotEmpty()) {
            currentTitle = prefs.getString(PREF_TITLE, "").orEmpty()
            currentArtist = prefs.getString(PREF_ARTIST, "").orEmpty()
            currentArt = prefs.getString(PREF_ART, "").orEmpty()
            currentAccount = prefs.getString(PREF_ACCOUNT, "").orEmpty()
        }
        notifyPlaybackChanged()
    }

    /** The single ExoPlayer instance, created on first use. */
    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        val exo = ExoPlayer.Builder(this).build()
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                updateNotification()
                notifyPlaybackChanged()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updateNotification()
                notifyPlaybackChanged()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                notifyPlaybackChanged()
            }
        })
        player = exo
        return exo
    }

    /**
     * A MediaSession is what lets the lock screen, Bluetooth buttons and the
     * quick-settings player control this playback. The notification alone
     * shows the item but is not reachable by media keys.
     */
    private fun setUpMediaSession() {
        val session = MediaSessionCompat(this, "jm21-offline")
        session.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() {
                player?.playWhenReady = true
                updateNotification()
                notifyPlaybackChanged()
            }

            override fun onPause() {
                player?.playWhenReady = false
                updateNotification()
                notifyPlaybackChanged()
            }

            override fun onStop() {
                player?.stop()
                stopSelf()
            }

            override fun onSeekTo(pos: Long) {
                player?.seekTo(pos)
            }
        })
        session.isActive = true
        mediaSession = session
    }

    /** Publish title and position so remote controls show something useful. */
    private fun updateSessionState() {
        val session = mediaSession ?: return
        val exo = player
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentTitle)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentArtist)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, exo?.duration ?: 0L)
                .build()
        )
        val state = if (exo?.isPlaying == true) {
            PlaybackStateCompat.STATE_PLAYING
        } else {
            PlaybackStateCompat.STATE_PAUSED
        }
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_STOP or
                        PlaybackStateCompat.ACTION_SEEK_TO
                )
                .setState(state, exo?.currentPosition ?: 0L, 1f)
                .build()
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            null, ACTION_RESTORE -> {
                val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                val url = prefs.getString(PREF_URL, "").orEmpty()
                currentVideoId = prefs.getString(PREF_ID, "").orEmpty()
                currentTitle = prefs.getString(PREF_TITLE, "").orEmpty()
                currentArtist = prefs.getString(PREF_ARTIST, "").orEmpty()
                currentArt = prefs.getString(PREF_ART, "").orEmpty()
                currentAccount = prefs.getString(PREF_ACCOUNT, "").orEmpty()
                if (url.isNotEmpty() && currentVideoId.isNotEmpty()) {
                    val exo = player ?: ensurePlayer()
                    exo.setMediaItem(
                        MediaItem.fromUri(url),
                        prefs.getLong(PREF_POSITION, 0L)
                    )
                    exo.prepare()
                    exo.playWhenReady = false
                    notifyPlaybackChanged()
                }
            }

            ACTION_PLAY -> {
                val url = intent.getStringExtra(EXTRA_URL).orEmpty()
                currentTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty()
                currentArtist = intent.getStringExtra(EXTRA_ARTIST).orEmpty()
                currentArt = intent.getStringExtra(EXTRA_ART).orEmpty()
                currentVideoId = intent.getStringExtra(EXTRA_ID).orEmpty()
                currentAccount = intent.getStringExtra(EXTRA_ACCOUNT).orEmpty()
                val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                prefs.edit()
                    .putString(PREF_URL, url)
                    .putString(PREF_TITLE, currentTitle)
                    .putString(PREF_ARTIST, currentArtist)
                    .putString(PREF_ART, currentArt)
                    .putString(PREF_ID, currentVideoId)
                    .putString(PREF_ACCOUNT, currentAccount)
                    .putLong(PREF_POSITION, 0L)
                    .apply()
                startPlayback(url)
                notifyPlaybackChanged()
            }

            ACTION_LOAD_PAUSED -> {
                val url = intent.getStringExtra(EXTRA_URL).orEmpty()
                currentTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty()
                currentArtist = intent.getStringExtra(EXTRA_ARTIST).orEmpty()
                currentArt = intent.getStringExtra(EXTRA_ART).orEmpty()
                currentVideoId = intent.getStringExtra(EXTRA_ID).orEmpty()
                currentAccount = intent.getStringExtra(EXTRA_ACCOUNT).orEmpty()
                val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                prefs.edit()
                    .putString(PREF_URL, url)
                    .putString(PREF_TITLE, currentTitle)
                    .putString(PREF_ARTIST, currentArtist)
                    .putString(PREF_ART, currentArt)
                    .putString(PREF_ID, currentVideoId)
                    .putString(PREF_ACCOUNT, currentAccount)
                    .apply()
                preparePaused(url)
                notifyPlaybackChanged()
            }

            ACTION_TOGGLE -> {
                player?.let {
                    it.playWhenReady = !it.playWhenReady
                    updateNotification()
                    notifyPlaybackChanged()
                }
            }

            ACTION_STOP -> {
                savePlaybackPosition()
                player?.stop()
                return START_STICKY
            }
        }
        // START_STICKY keeps audio alive if the system reclaims the process;
        // without it a background listen stops on the first memory pressure.
        return START_STICKY
    }

    private fun savePlaybackPosition() {
        val exo = player ?: return
        if (currentVideoId.isEmpty() || currentAccount.isEmpty()) return
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putLong(PREF_POSITION, exo.currentPosition.coerceAtLeast(0L))
            .apply()
    }
    private val positionSaver = object : Runnable {
        override fun run() {
            savePlaybackPosition()
            if (player != null) handler.postDelayed(this, 5000)
        }
    }

    private fun preparePaused(url: String) {
        if (url.isEmpty()) return
        val exo = player ?: ensurePlayer()
        exo.playWhenReady = false
        exo.setMediaItem(
            MediaItem.fromUri(url),
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getLong(PREF_POSITION, 0L)
        )
        exo.prepare()
        exo.playWhenReady = false
        promoteForeground()
        handler.removeCallbacks(positionSaver)
        handler.postDelayed(positionSaver, 5000)
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                savePlaybackPosition()
            }
        })
    }
    private fun startPlayback(url: String) {
        if (url.isEmpty()) return
        val existing = player
        if (existing != null && existing.currentMediaItem?.localConfiguration?.uri.toString() == url) {
            existing.playWhenReady = true
            promoteForeground()
            notifyPlaybackChanged()
            return
        }
        val exo = existing ?: ensurePlayer()
        exo.setMediaItem(
            MediaItem.fromUri(url),
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getLong(PREF_POSITION, 0L)
        )
        exo.prepare()
        exo.playWhenReady = true
        promoteForeground()
        handler.removeCallbacks(positionSaver)
        handler.postDelayed(positionSaver, 5000)
        notifyPlaybackChanged()
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                savePlaybackPosition()
            }
        })
    }

    private fun promoteForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.offline_playback_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** Rebuild the notification so the play/pause icon matches the state. */
    private fun updateNotification() {
        val exo = player ?: return
        if (!exo.isPlaying && exo.playbackState == Player.STATE_IDLE) return
        updateSessionState()
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val isPlaying = player?.playWhenReady == true
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, OfflinePlayerActivity::class.java)
                .putExtra(EXTRA_ID, currentVideoId)
                .putExtra(EXTRA_ACCOUNT, currentAccount)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val toggleIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, OfflinePlayerService::class.java).setAction(ACTION_TOGGLE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, OfflinePlayerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_play_arrow)
            .setContentTitle(currentTitle.ifEmpty { getString(R.string.offline_library_title) })
            .setContentText(currentArtist)
            .setContentIntent(openIntent)
            .setOngoing(isPlaying)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(
                if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow,
                getString(if (isPlaying) R.string.offline_pause else R.string.offline_play),
                toggleIntent
            )
            .addAction(R.drawable.ic_close, getString(R.string.offline_stop), stopIntent)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setShowActionsInCompactView(0, 1)
            )
            .build()
    }

    override fun onDestroy() {
        savePlaybackPosition()
        handler.removeCallbacks(positionSaver)
        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null
        player?.release()
        player = null
        notifyPlaybackChanged()
        super.onDestroy()
    }
}
