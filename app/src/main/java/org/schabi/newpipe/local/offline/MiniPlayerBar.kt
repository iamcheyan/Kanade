package org.schabi.newpipe.local.offline

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import org.schabi.newpipe.R
import org.schabi.newpipe.databinding.ViewMiniPlayerBinding
import org.schabi.newpipe.util.image.CoilHelper

/**
 * The collapsed player pinned above the navigation bar.
 *
 * It reflects [OfflinePlayerService]'s single ExoPlayer rather than owning
 * playback: the full screen player and this bar are two views of the same
 * session, so leaving the player shrinks it here and tapping it expands it
 * again at the same position.
 *
 * [attach] and [detach] mirror the host activity's start/stop, which keeps the
 * progress ticker from running while the player screen is on top.
 */
class MiniPlayerBar(
    private val activity: AppCompatActivity,
    private val binding: ViewMiniPlayerBinding,
    /** The content that must not sit underneath the bar. */
    private val content: View,
    private val onVisibilityChanged: (Boolean) -> Unit
) {

    private val handler = Handler(Looper.getMainLooper())
    private val listener = OfflinePlayerService.PlaybackListener { refresh() }

    private val ticker = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 500)
        }
    }

    init {
        binding.miniPlayerRow.setOnClickListener { expand() }
        binding.miniPlayerPlay.setOnClickListener {
            OfflinePlayerService.toggle()
        }
        binding.miniPlayerProgress.onSeek = seek@{ fraction ->
            val exo = OfflinePlayerService.player ?: return@seek
            val duration = exo.duration
            if (duration > 0) {
                exo.seekTo((duration * fraction).toLong())
            }
        }
    }

    fun attach() {
        OfflinePlayerService.addPlaybackListener(listener)
        refresh()
    }

    fun detach() {
        OfflinePlayerService.removePlaybackListener(listener)
        handler.removeCallbacks(ticker)
    }

    /** Show or hide the bar and keep its text and icon in sync. */
    @SuppressLint("SetTextI18n")
    fun refresh() {
        val visible = OfflinePlayerService.hasItem()
        binding.root.visibility = View.VISIBLE
        if (!visible) {
            binding.miniPlayerTitle.text = activity.getString(R.string.offline_library_title)
            binding.miniPlayerMeta.text = activity.getString(R.string.offline_play)
            binding.miniPlayerPlay.isEnabled = false
            binding.miniPlayerPlay.setImageResource(R.drawable.ic_play_arrow)
            binding.miniPlayerPlay.contentDescription = activity.getString(R.string.offline_play)
            binding.miniPlayerProgress.fraction = 0f
            binding.miniPlayerProgress.bufferedFraction = 0f
            handler.removeCallbacks(ticker)
            onVisibilityChanged(true)
            return
        }
        binding.miniPlayerPlay.isEnabled = true
        binding.miniPlayerTitle.text = OfflinePlayerService.currentTitle
        binding.root.setOnClickListener {
            val videoId = OfflinePlayerService.videoId()
            val account = OfflinePlayerService.account()
            if (videoId.isNotEmpty() && account.isNotEmpty()) {
                expand()
            }
        }
        binding.miniPlayerMeta.text = OfflinePlayerService.currentArtist
        val art = OfflinePlayerService.artUrl()
        if (art.isNotEmpty()) {
            CoilHelper.loadThumbnail(binding.miniPlayerThumb, art)
        }
        updatePlayIcon()
        updateProgress()
        if (binding.root.visibility != View.VISIBLE) {
            show()
        }
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    private fun updatePlayIcon() {
        val playing = OfflinePlayerService.isPlaying()
        binding.miniPlayerPlay.setImageResource(
            if (playing) R.drawable.ic_pause else R.drawable.ic_play_arrow
        )
        binding.miniPlayerPlay.contentDescription = activity.getString(
            if (playing) R.string.offline_pause else R.string.offline_play
        )
    }

    private fun updateProgress() {
        val exo = OfflinePlayerService.player ?: return
        val duration = exo.duration
        if (duration <= 0) return
        // Do not fight the user's thumb while they are dragging.
        if (!binding.miniPlayerProgress.isDragging) {
            binding.miniPlayerProgress.fraction = exo.currentPosition.toFloat() / duration
        }
        binding.miniPlayerProgress.bufferedFraction = exo.bufferedPercentage / 100f
        updatePlayIcon()
    }

    /** Reopen the full screen player on the item that is playing. */
    private fun expand() {
        val videoId = OfflinePlayerService.videoId()
        val account = OfflinePlayerService.account()
        if (videoId.isEmpty() || account.isEmpty()) return
        activity.startActivity(OfflinePlayerActivity.intent(activity, videoId, account))
    }

    private fun show() {
        onVisibilityChanged(true)
        binding.root.visibility = View.VISIBLE
        binding.root.translationY = binding.root.height.toFloat()
        binding.root.animate().translationY(0f).setDuration(180).start()
        // The list must end where the bar starts, otherwise its last row sits
        // under an opaque strip. Height is 0 until the first layout pass.
        binding.root.post { setContentInset(binding.root.height) }
    }

    private fun hide() {
        if (binding.root.visibility != View.VISIBLE) return
        binding.root.visibility = View.GONE
        onVisibilityChanged(false)
        setContentInset(0)
    }

    private fun setContentInset(bottom: Int) {
        // Leave content full-bleed behind the floating player so the background
        // and navigation bar stay seamlessly transparent.
    }
}
