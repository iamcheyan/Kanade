package org.schabi.newpipe.local.offline

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout
import com.google.android.exoplayer2.ui.PlayerView
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.schedulers.Schedulers
import org.schabi.newpipe.R
import org.schabi.newpipe.util.image.CoilHelper

/**
 * Detail screen for one offline video.
 *
 * Layout follows a video page: the player fills the width at the top, the
 * title and uploader sit under it, and the rest of the downloaded library
 * fills the remaining space so the user can jump to something else. Rotation
 * to landscape turns the whole screen into the player.
 *
 * Playback itself lives in [OfflinePlayerService] so audio keeps running when
 * this screen is dismissed, matching the queue behaviour of a music player.
 */
class OfflinePlayerActivity : AppCompatActivity() {

    private val client = VideodClient()
    private val disposables = CompositeDisposable()

    private var playerView: PlayerView? = null
    private var titleView: TextView? = null
    private var metaView: TextView? = null
    private var artView: ImageView? = null
    private var root: FrameLayout? = null
    private var content: LinearLayout? = null

    private var bound = false
    private var service: OfflinePlayerService? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var controlsOverlay: android.view.View? = null
    private var progressTicker: Runnable? = null
    private var currentVideoId: String = ""
    private var currentAccount: String = ""
    private var resumePositionSec: Long = 0
    private var attachAttempts = 0
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as? OfflinePlayerService.LocalBinder)?.service()
            bound = true
            scheduleAttach()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val videoId = intent.getStringExtra(EXTRA_ID).orEmpty()
        val account = intent.getStringExtra(EXTRA_ACCOUNT).orEmpty()
        if (videoId.isEmpty() || account.isEmpty()) {
            finish()
            return
        }

        val streamUrl = VideodClient.DEFAULT_BASE_URL + "/api/media/" + videoId +
            "?account_id=" + account

        buildUi()
        if (isLandscape()) {
            content?.visibility = View.GONE
            root?.findViewById<RecyclerView>(android.R.id.list)?.visibility = View.GONE
            (root?.getChildAt(0) as? LinearLayout)?.getChildAt(0)?.let { pb ->
                (pb.layoutParams as? LinearLayout.LayoutParams)?.let {
                    it.height = LinearLayout.LayoutParams.MATCH_PARENT
                    it.weight = 1f
                    pb.layoutParams = it
                }
            }
            hideSystemBars(true)
        }
        loadDetails(client, account, videoId, streamUrl)
        bindService(
            Intent(this, OfflinePlayerService::class.java),
            connection,
            Context.BIND_AUTO_CREATE
        )
    }

    // ------------------------------------------------------------------- UI

    private fun buildUi() {
        val container = FrameLayout(this)
        root = container
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // Player at the top. In landscape it takes the whole screen so a
        // rotated device behaves like a normal video player.
        val playerBox = FrameLayout(this)
        val pv = PlayerView(this).apply {
            // The default controller draws a full-screen scrim with skip and
            // next buttons; this screen only wants a centre pull and a thin
            // progress line, built below.
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        playerView = pv
        playerBox.addView(pv)

        val controls = layoutInflater.inflate(
            R.layout.exo_offline_controls,
            playerBox,
            false
        )
        playerBox.addView(controls)
        bindControls(controls, playerBox)
        val playerParams = if (isLandscape()) {
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        } else {
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.widthPixels * 9 / 16)
            )
        }
        column.addView(playerBox, playerParams)

        // Only the player stays put; the title block scrolls away with the
        // list so a long scroll leaves nothing but the video on screen.
        val rest = RecyclerView(this).apply {
            id = android.R.id.list
            layoutManager = LinearLayoutManager(this@OfflinePlayerActivity)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        rest.adapter = HeaderAdapter()
        column.addView(rest)

        container.addView(column)

        setContentView(container)
    }

    /** The title block, rendered as row 0 of the list. */
    private fun buildInfoRow(): View {
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 8)
        }
        content = info

        val titleText = TextView(this).apply {
            textSize = 17f
            setTextColor(0xFFFFFFFF.toInt())
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        titleView = titleText
        info.addView(titleText)

        val metaText = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFFAAAAAA.toInt())
            setPadding(0, 6, 0, 0)
        }
        metaView = metaText
        info.addView(metaText)

        return info
    }

    /**
     * Back out of fullscreen first.
     *
     * With this activity holding the orientation in configChanges, the system
     * does not treat a rotation as a separate back target, so without an
     * explicit handler the back key would leave the detail page entirely from
     * a fullscreen video. The expected behaviour is to return to the portrait
     * page, where the title and the list live.
     */
    override fun onBackPressed() {
        if (isLandscape()) {
            requestedOrientation =
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    private fun isLandscape(): Boolean = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    /**
     * Re-apply the layout when the device rotates.
     *
     * The manifest declares orientation in configChanges, so the activity is
     * not recreated on rotation. Without this the screen would rotate but the
     * player would keep its portrait height and the info column would stay
     * visible, which is exactly what "fullscreen does nothing" looked like.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val landscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        content?.visibility = if (landscape) View.GONE else View.VISIBLE
        root?.findViewById<RecyclerView>(android.R.id.list)?.visibility =
            if (landscape) View.GONE else View.VISIBLE

        val column = root?.getChildAt(0) as? LinearLayout
        val playerBox = column?.getChildAt(0) as? FrameLayout
        val params = playerBox?.layoutParams as? LinearLayout.LayoutParams
        if (params != null) {
            if (landscape) {
                params.height = LinearLayout.LayoutParams.MATCH_PARENT
                params.weight = 1f
            } else {
                params.height = resources.displayMetrics.widthPixels * 9 / 16
                params.weight = 0f
            }
            playerBox.layoutParams = params
        }
        // Landscape hides the system bars, so the control row needs its own
        // inset or the fullscreen button ends up under the gesture area and
        // becomes nearly untappable.
        controlsOverlay?.findViewById<android.view.View>(R.id.exo_bottom_bar)?.let { bar ->
            val padH = (if (landscape) 32 else 16) * resources.displayMetrics.density
            val padV = (if (landscape) 12 else 6) * resources.displayMetrics.density
            bar.setPadding(padH.toInt(), padV.toInt(), padH.toInt(), padV.toInt())
        }
        hideSystemBars(landscape)
        // The ticker updates the overlay every 250 ms; after a rotation the
        // overlay object is the same but its measured size changed, and the
        // icon can be left on a stale frame. Restarting it makes the play /
        // pause state reflect the player again on the very next tick.
        progressTicker?.let {
            handler.removeCallbacks(it)
            handler.post(it)
        }
    }

    /** Immersive mode while fullscreen, normal bars otherwise. */
    private fun hideSystemBars(hide: Boolean) {
        val controller =
            androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        if (hide) {
            controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun toggleOrientation() {
        val target = if (isLandscape()) {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        requestedOrientation = target
    }

    /**
     * Connect the custom overlay to the shared player.
     *
     * Controls stay visible while paused and fade out a few seconds after
     * playback starts; tapping the video brings them back, which is the
     * interaction the reference player uses.
     */
    private fun bindControls(overlay: android.view.View, playerBox: FrameLayout) {
        val centerPlay = overlay.findViewById<android.widget.ImageButton>(R.id.exo_center_play)
        val positionView = overlay.findViewById<android.widget.TextView>(R.id.exo_position)
        val durationView = overlay.findViewById<android.widget.TextView>(R.id.exo_duration)
        val seekBar = overlay.findViewById<ThinSeekBar>(R.id.exo_progress)
        val fullscreenBtn =
            overlay.findViewById<android.widget.ImageButton>(R.id.exo_fullscreen_btn)
        controlsOverlay = overlay

        centerPlay.setOnClickListener {
            val exo = OfflinePlayerService.player ?: return@setOnClickListener
            exo.playWhenReady = !exo.playWhenReady
            if (exo.playWhenReady) scheduleHideControls() else showControls()
        }
        fullscreenBtn.setOnClickListener { toggleOrientation() }

        playerBox.setOnClickListener {
            toggleControls()
        }

        seekBar.onSeek = seek@{ value ->
            val exo = OfflinePlayerService.player ?: return@seek
            val dur = exo.duration.coerceAtLeast(0L)
            if (dur > 0) {
                exo.seekTo((dur * value).toLong())
                positionView.text = formatClock(((dur * value) / 1000).toInt())
            }
        }

        progressTicker = object : Runnable {
            override fun run() {
                val exo = OfflinePlayerService.player
                if (exo != null) {
                    val dur = exo.duration
                    if (dur > 0) {
                        if (!seekBar.isPressed) {
                            seekBar.fraction = (exo.currentPosition.toFloat() / dur)
                        }
                        positionView.text = formatClock((exo.currentPosition / 1000).toInt())
                        durationView.text = formatClock((dur / 1000).toInt())
                    }
                    seekBar.bufferedFraction =
                        if (dur > 0) exo.bufferedPercentage / 100f else 0f
                    centerPlay.setImageResource(
                        if (exo.playWhenReady) R.drawable.ic_pause else R.drawable.ic_play_arrow
                    )
                    centerPlay.contentDescription = getString(
                        if (exo.playWhenReady) R.string.offline_pause else R.string.offline_play
                    )
                }
                handler.postDelayed(this, 250)
            }
        }
        // Start the ticker once the player exists: it is the only thing that
        // moves the progress bar and the play/pause icon.
        scheduleAttach()
    }

    /**
     * Seek to the remembered position once the player has the item ready.
     *
     * seekTo before prepare would be dropped, so this retries briefly instead
     * of depending on a single lucky timing.
     */
    private fun applyResumePosition() {
        if (resumePositionSec <= 0) return
        var attempts = 0
        val run = object : Runnable {
            override fun run() {
                val exo = OfflinePlayerService.player ?: return
                val ready = exo.playbackState == com.google.android.exoplayer2.Player.STATE_READY
                if (ready && exo.duration > 0) {
                    // Ignore a saved position at the very end: starting there
                    // would look like the video finished.
                    val target = resumePositionSec * 1000
                    if (target < exo.duration - 5000) {
                        exo.seekTo(target)
                    }
                    return
                }
                if (++attempts < 100) handler.postDelayed(this, 100)
            }
        }
        handler.post(run)
    }

    /** Save every 10 s while playing, so a crash loses at most that much. */
    private fun scheduleProgressSaves() {
        val save = object : Runnable {
            override fun run() {
                persistProgress()
                handler.postDelayed(this, 10_000)
            }
        }
        handler.postDelayed(save, 10_000)
    }

    /** Persist the position so the next open resumes here. */
    private fun persistProgress() {
        val exo = OfflinePlayerService.player ?: return
        if (currentVideoId.isEmpty() || currentAccount.isEmpty()) return
        val position = exo.currentPosition / 1000
        val duration = exo.duration / 1000
        if (position <= 0) return
        val id = currentVideoId
        val account = currentAccount
        disposables.add(
            io.reactivex.rxjava3.core.Completable
                .fromAction { client.saveProgress(account, id, position, duration) }
                .subscribeOn(Schedulers.io())
                .subscribe({}, {})
        )
    }

    /** Show or hide the overlay in response to a tap on the video. */
    private fun toggleControls() {
        val overlay = controlsOverlay ?: return
        if (overlay.alpha > 0.5f) {
            hideControls()
        } else {
            overlay.alpha = 1f
            overlay.visibility = android.view.View.VISIBLE
            scheduleHideControls()
        }
    }

    private fun showControls() {
        controlsOverlay?.let {
            it.alpha = 1f
            it.visibility = android.view.View.VISIBLE
        }
        handler.removeCallbacks(hideControlsRunnable)
    }

    private fun hideControls() {
        val overlay = controlsOverlay ?: return
        if (OfflinePlayerService.player?.isPlaying != true) return
        overlay.animate().alpha(0f).setDuration(200).withEndAction {
            overlay.visibility = android.view.View.INVISIBLE
        }.start()
    }

    private fun scheduleHideControls() {
        handler.removeCallbacks(hideControlsRunnable)
        handler.postDelayed(hideControlsRunnable, 3000)
    }

    private val hideControlsRunnable = Runnable { hideControls() }

    /** "m:ss" for a duration in whole seconds. */
    private fun formatClock(totalSeconds: Int): String {
        val s = totalSeconds.coerceAtLeast(0)
        return String.format("%d:%02d", s / 60, s % 60)
    }

    // ------------------------------------------------------------- playback

    /**
     * Attach the shared player to this screen, retrying until it exists.
     *
     * The player is created by the service after startForegroundService, which
     * runs on its own thread and may finish after this activity binds. Without
     * the retry the view silently keeps a null player: audio plays (the player
     * runs on its own) while the surface stays black.
     */
    private fun scheduleAttach() {
        attachAttempts = 0
        handler.post(attachRunnable)
    }

    private val attachRunnable = object : Runnable {
        override fun run() {
            val exo = OfflinePlayerService.player
            if (exo != null) {
                if (playerView?.player !== exo) {
                    playerView?.player = exo
                }
                // Ticker drives the progress bar and the centre icon; without
                // it the UI keeps whatever state it had when the player was
                // still being created.
                progressTicker?.let { handler.removeCallbacks(it) }
                progressTicker?.let { handler.post(it) }
                scheduleProgressSaves()
                return
            }
            if (++attachAttempts < 60) {
                handler.postDelayed(this, 100)
            }
        }
    }

    /**
     * Fetch this video's metadata and the rest of the library.
     *
     * The list under the player is the other downloaded items rather than
     * remote recommendations: this is an offline player, so everything shown
     * is already on disk and playable without a network.
     */
    private fun loadDetails(
        client: VideodClient,
        account: String,
        videoId: String,
        streamUrl: String
    ) {
        currentVideoId = videoId
        currentAccount = account
        disposables.add(
            Single.fromCallable {
                val library = client.libraryAll()
                val current = library.firstOrNull { it.id == videoId }
                // Fetch the saved position before starting playback so the very
                // first frame already lands where the user stopped.
                val resume = client.resumePosition(account, videoId)
                Triple(current, library.filter { it.id != videoId }, resume)
            }
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    { (current, others, resume) ->
                        resumePositionSec = resume
                        titleView?.text = current?.title ?: ""
                        metaView?.text = buildMeta(current)
                        current?.thumbnail?.let {
                            if (it.isNotEmpty()) {
                                CoilHelper.loadThumbnail(
                                    ImageView(this),
                                    it
                                )
                            }
                        }
                        if (current != null) {
                            OfflinePlayerService.play(
                                this,
                                streamUrl,
                                current.title,
                                Sources.label(account),
                                current.thumbnail,
                                current.id,
                                account
                            )
                            applyResumePosition()
                        }
                        showSuggestions(others)
                    },
                    { error ->
                        titleView?.text = error.message ?: error.javaClass.simpleName
                        showSuggestions(emptyList())
                    }
                )
        )
    }

    private fun buildMeta(video: OfflineVideo?): String {
        if (video == null) return ""
        val parts = ArrayList<String>()
        formatDuration(video.duration).takeIf { it.isNotEmpty() }?.let { parts.add(it) }
        parts.add(Sources.label(video.accountId))
        parts.add(getString(R.string.offline_downloaded_tag))
        return parts.joinToString(" · ")
    }

    /** Fill the list under the player with the other downloaded items. */
    private fun showSuggestions(items: List<OfflineVideo>) {
        val list = root?.findViewById<RecyclerView>(android.R.id.list) ?: return
        (list.adapter as? HeaderAdapter)?.submit(items)
    }

    /**
     * The title block followed by one row per other download.
     *
     * The title is an ordinary row rather than a view above the list: that is
     * what makes it scroll away with the recommendations instead of staying
     * pinned while the list moves underneath it.
     */
    private inner class HeaderAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val items = ArrayList<OfflineVideo>()

        fun submit(next: List<OfflineVideo>) {
            items.clear()
            items.addAll(next)
            notifyDataSetChanged()
        }

        override fun getItemViewType(position: Int): Int = if (position == 0) TYPE_HEADER else TYPE_ROW

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            if (viewType == TYPE_HEADER) {
                return object : RecyclerView.ViewHolder(buildInfoRow()) {}
            }
            val row = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(24, 12, 24, 12)
                isClickable = true
            }
            val thumb = ImageView(parent.context).apply {
                layoutParams = LinearLayout.LayoutParams(192, 108)
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            val textColumn = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(20, 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val title = TextView(parent.context).apply {
                textSize = 14f
                setTextColor(0xFFFFFFFF.toInt())
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            val meta = TextView(parent.context).apply {
                textSize = 12f
                setTextColor(0xFFAAAAAA.toInt())
            }
            textColumn.addView(title)
            textColumn.addView(meta)
            row.addView(thumb)
            row.addView(textColumn)
            return RowHolder(row, thumb, title, meta)
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder is RowHolder) {
                holder.bind(items[position - 1])
            }
        }

        override fun getItemCount(): Int = items.size + 1

        inner class RowHolder(
            private val row: LinearLayout,
            private val thumb: ImageView,
            private val title: TextView,
            private val meta: TextView
        ) : RecyclerView.ViewHolder(row) {
            fun bind(video: OfflineVideo) {
                title.text = video.title
                meta.text = listOf(formatDuration(video.duration), Sources.label(video.accountId))
                    .filter { it.isNotEmpty() }.joinToString(" · ")
                if (video.thumbnail.isNotEmpty()) {
                    CoilHelper.loadThumbnail(thumb, video.thumbnail)
                }
                row.setOnClickListener {
                    startActivity(intent(this@OfflinePlayerActivity, video.id, video.accountId))
                    finish()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (bound) scheduleAttach()
    }

    override fun onStop() {
        super.onStop()
        persistProgress()
    }

    override fun onDestroy() {
        handler.removeCallbacks(attachRunnable)
        progressTicker?.let { handler.removeCallbacks(it) }
        handler.removeCallbacks(hideControlsRunnable)
        disposables.clear()
        playerView?.player = null
        if (bound) {
            unbindService(connection)
            bound = false
        }
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_ID = "video_id"
        private const val EXTRA_ACCOUNT = "account"
        private const val TYPE_HEADER = 0
        private const val TYPE_ROW = 1
        fun intent(context: Context, videoId: String, account: String): Intent = Intent(context, OfflinePlayerActivity::class.java).apply {
            putExtra(EXTRA_ID, videoId)
            putExtra(EXTRA_ACCOUNT, account)
        }
    }
}
