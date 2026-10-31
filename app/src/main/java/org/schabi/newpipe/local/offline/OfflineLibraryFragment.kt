package org.schabi.newpipe.local.offline

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.schedulers.Schedulers
import java.io.File
import org.schabi.newpipe.BaseFragment
import org.schabi.newpipe.R
import org.schabi.newpipe.databinding.FragmentOfflineLibraryBinding
import org.schabi.newpipe.databinding.ItemOfflineVideoBinding
import org.schabi.newpipe.util.image.CoilHelper

/**
 * Shows the videos videod has already downloaded and plays them offline.
 *
 * NewPipe has no built-in local-file player: its download flow hands the file
 * to an external app. This fragment fills that gap for the fork without
 * touching the Extractor-bound service model, so the rest of NewPipe keeps
 * working unchanged.
 */
class OfflineLibraryFragment : BaseFragment() {

    private var _binding: FragmentOfflineLibraryBinding? = null
    private val binding get() = _binding!!

    private val client = VideodClient()
    private val disposables = CompositeDisposable()
    private val adapter = OfflineAdapter()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        setTitle(getString(R.string.offline_library_title))
        _binding = FragmentOfflineLibraryBinding.inflate(inflater, container, false)
        binding.offlineList.layoutManager = LinearLayoutManager(requireContext())
        binding.offlineList.adapter = adapter
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadLibrary()
    }

    override fun onDestroyView() {
        disposables.clear()
        _binding = null
        super.onDestroyView()
    }

    /**
     * Fetch the library on a background thread.
     *
     * An unreachable videod is the expected state on a fresh install, so the
     * error text names the service and the address instead of logging only.
     */
    private fun loadLibrary() {
        showStatus(
            getString(R.string.offline_library_loading),
            VideodClient.DEFAULT_BASE_URL
        )
        disposables.add(
            io.reactivex.rxjava3.core.Single
                .fromCallable { client.libraryAll() }
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    { videos ->
                        if (videos.isEmpty()) {
                            showStatus(
                                getString(R.string.offline_library_empty),
                                getString(R.string.offline_library_empty_detail)
                            )
                        } else {
                            hideStatus()
                            adapter.submit(videos)
                        }
                    },
                    { error ->
                        showStatus(
                            getString(R.string.offline_library_unreachable),
                            error.message ?: error.javaClass.simpleName
                        )
                    }
                )
        )
    }

    private fun showStatus(title: String, detail: String) {
        binding.offlineStatus.visibility = View.VISIBLE
        binding.offlineStatusTitle.text = title
        binding.offlineStatusDetail.text = detail
        binding.offlineList.visibility = View.GONE
    }

    private fun hideStatus() {
        binding.offlineStatus.visibility = View.GONE
        binding.offlineList.visibility = View.VISIBLE
    }

    /**
     * Play a downloaded file.
     *
     * The file itself is not readable by this app: videod runs as a different
     * uid and its data directory is mode 0700, so a file:// or FileProvider
     * URI fails. videod serves the same bytes over HTTP with Range support,
     * which is both readable and what ExoPlayer needs for seeking.
     */
    private fun play(video: OfflineVideo) {
        try {
            startActivity(
                OfflinePlayerActivity.intent(requireContext(), video.id, video.accountId)
            )
        } catch (e: Exception) {
            showStatus(
                getString(R.string.offline_library_no_player),
                e.message ?: e.javaClass.simpleName
            )
        }
    }

    /** Mark watched: local removal plus remote write-back when supported. */
    private fun markWatched(video: OfflineVideo) {
        disposables.add(
            io.reactivex.rxjava3.core.Single
                .fromCallable { client.markWatched(video.accountId, video.id) }
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    { loadLibrary() },
                    { error ->
                        showStatus(
                            getString(R.string.offline_library_mark_failed),
                            error.message ?: error.javaClass.simpleName
                        )
                    }
                )
        )
    }

    /** Queue a download again after its file disappeared. */
    private fun requeue(video: OfflineVideo) {
        showStatus(
            getString(R.string.offline_library_redownloading),
            video.title
        )
        disposables.add(
            io.reactivex.rxjava3.core.Single
                .fromCallable { client.download(video.accountId, video.id) }
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    { loadLibrary() },
                    { error ->
                        showStatus(
                            getString(R.string.offline_library_mark_failed),
                            error.message ?: error.javaClass.simpleName
                        )
                    }
                )
        )
    }

    private fun mimeTypeFor(name: String): String = when {
        name.endsWith(".mp4", true) -> "video/mp4"
        name.endsWith(".webm", true) -> "video/webm"
        name.endsWith(".mkv", true) -> "video/x-matroska"
        name.endsWith(".m4a", true) -> "audio/mp4"
        name.endsWith(".opus", true) -> "audio/opus"
        else -> "video/*"
    }

    private inner class OfflineAdapter : RecyclerView.Adapter<OfflineAdapter.Holder>() {
        private val items = ArrayList<OfflineVideo>()

        fun submit(videos: List<OfflineVideo>) {
            items.clear()
            items.addAll(videos)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val itemBinding = ItemOfflineVideoBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return Holder(itemBinding)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class Holder(private val itemBinding: ItemOfflineVideoBinding) :
            RecyclerView.ViewHolder(itemBinding.root) {

            fun bind(video: OfflineVideo) {
                itemBinding.offlineItemTitle.text = video.title
                itemBinding.offlineItemMeta.text = buildMeta(video)
                CoilHelper.loadThumbnail(itemBinding.offlineItemThumbnail, video.thumbnail)
                itemBinding.root.setOnClickListener { play(video) }
                itemBinding.root.setOnLongClickListener {
                    markWatched(video)
                    true
                }
            }

            /** "mm:ss · Bilibili" plus the file name, so rows stay distinct. */
            private fun buildMeta(video: OfflineVideo): String {
                val minutes = video.duration / 60
                val seconds = video.duration % 60
                val length = String.format("%d:%02d", minutes, seconds)
                val source = when (video.accountId) {
                    "bilibili" -> getString(R.string.offline_source_bilibili)
                    "youtube" -> getString(R.string.offline_source_youtube)
                    else -> video.accountId
                }
                return "$length · $source"
            }
        }
    }
}
