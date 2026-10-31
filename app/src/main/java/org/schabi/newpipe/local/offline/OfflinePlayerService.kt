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
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Player
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

    companion object {
        private const val CHANNEL_ID = "jm21_offline_playback"
        private const val NOTIFICATION_ID = 4711

        const val ACTION_PLAY = "org.schabi.newpipe.offline.PLAY"
        const val ACTION_TOGGLE = "org.schabi.newpipe.offline.TOGGLE"
        const val ACTION_STOP = "org.schabi.newpipe.offline.STOP"

        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_ARTIST = "artist"
        const val EXTRA_ART = "art"

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

        /** Ask for playback without needing a bound connection. */
        fun play(context: Context, url: String, title: String, artist: String, art: String) {
            val intent = Intent(context, OfflinePlayerService::class.java).apply {
                action = ACTION_PLAY
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_ARTIST, artist)
                putExtra(EXTRA_ART, art)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private val binder = LocalBinder()
    private var artUrl: String = ""
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
    }

    /** The single ExoPlayer instance, created on first use. */
    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        val exo = ExoPlayer.Builder(this).build()
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                updateNotification()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updateNotification()
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
            }

            override fun onPause() {
                player?.playWhenReady = false
                updateNotification()
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
            ACTION_PLAY -> {
                val url = intent.getStringExtra(EXTRA_URL).orEmpty()
                currentTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty()
                currentArtist = intent.getStringExtra(EXTRA_ARTIST).orEmpty()
                artUrl = intent.getStringExtra(EXTRA_ART).orEmpty()
                startPlayback(url)
            }

            ACTION_TOGGLE -> {
                player?.let {
                    it.playWhenReady = !it.playWhenReady
                    updateNotification()
                }
            }

            ACTION_STOP -> {
                player?.stop()
                stopSelf()
                return START_NOT_STICKY
            }
        }
        // START_STICKY keeps audio alive if the system reclaims the process;
        // without it a background listen stops on the first memory pressure.
        return START_STICKY
    }

    private fun startPlayback(url: String) {
        if (url.isEmpty()) return
        val existing = player
        if (existing != null && existing.currentMediaItem?.localConfiguration?.uri.toString() == url) {
            existing.playWhenReady = true
            promoteForeground()
            return
        }
        val exo = existing ?: ensurePlayer()
        exo.setMediaItem(MediaItem.fromUri(url))
        exo.prepare()
        exo.playWhenReady = true
        promoteForeground()
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
        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null
        player?.release()
        player = null
        super.onDestroy()
    }
}
