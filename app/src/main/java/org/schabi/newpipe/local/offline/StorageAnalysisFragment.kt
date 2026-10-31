package org.schabi.newpipe.local.offline

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.schedulers.Schedulers
import org.schabi.newpipe.BaseFragment
import org.schabi.newpipe.R
import org.schabi.newpipe.databinding.FragmentStorageAnalysisBinding

/**
 * Space usage, one section per source.
 *
 * Each source gets its own donut listing its ten largest videos, with the
 * remainder folded into one slice. Splitting by source answers the question a
 * user actually asks ("how much is YouTube, how much is Bilibili") without
 * mixing colours from unrelated libraries in one chart.
 */
class StorageAnalysisFragment : BaseFragment() {

    private var _binding: FragmentStorageAnalysisBinding? = null
    private val binding get() = _binding!!

    private val client = VideodClient()
    private val disposables = CompositeDisposable()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        setTitle(getString(R.string.storage_analysis_title))
        _binding = FragmentStorageAnalysisBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        load()
    }

    override fun onDestroyView() {
        disposables.clear()
        _binding = null
        super.onDestroyView()
    }

    private fun load() {
        binding.analysisStatus.text = getString(R.string.sources_loading)
        binding.analysisStatus.visibility = View.VISIBLE
        disposables.add(
            Single.fromCallable { client.breakdown() }
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    { data ->
                        binding.analysisStatus.visibility = View.GONE
                        binding.analysisSections.removeAllViews()
                        addWatchedSection(data)
                        for (usage in data.sources) {
                            addSourceSection(usage, data.videos.filterNot { it.watched })
                        }
                        addOrphanSection(data)
                    },
                    { error ->
                        binding.analysisStatus.text =
                            error.message ?: error.javaClass.simpleName
                    }
                )
        )
    }

    /** A bulk-cleanable list of files missing from their upstream list. */
    private fun addWatchedSection(data: StorageBreakdown) {
        if (data.watchedFiles == 0) return
        val context = requireContext()
        val section = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 20, 0, 12)
        }
        section.addView(
            TextView(context).apply {
                text = getString(
                    R.string.storage_analysis_watched_summary,
                    data.watchedFiles,
                    formatBytes(data.watchedBytes)
                )
                textSize = 16f
                setTextColor(0xFFFFFFFF.toInt())
            }
        )
        data.videos.filter { it.watched }.forEach { video ->
            addLegendRow(
                section,
                "${Sources.label(video.account)} · ${video.title}",
                video.bytes,
                data.watchedBytes.coerceAtLeast(1),
                0xFF88CC44.toInt(),
                video
            )
        }
        val clear = TextView(context).apply {
            text = getString(R.string.storage_analysis_clear_watched)
            textSize = 14f
            setTextColor(0xFFFF7777.toInt())
            setPadding(0, 16, 0, 12)
            isClickable = true
            setOnClickListener { confirmClearWatched() }
        }
        section.addView(clear)
        binding.analysisSections.addView(section, 0)
    }

    private fun confirmClearWatched() {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.storage_analysis_clear_watched)
            .setMessage(R.string.storage_analysis_clear_watched_confirm)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.offline_remove_from_library) { _, _ ->
                disposables.add(
                    io.reactivex.rxjava3.core.Single.fromCallable {
                        client.clearStorage("watched")
                        client.breakdown()
                    }
                        .subscribeOn(Schedulers.io())
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe(
                            { refreshed ->
                                binding.analysisSections.removeAllViews()
                                addWatchedSection(refreshed)
                                refreshed.sources.forEach { addSourceSection(it, refreshed.videos.filterNot { v -> v.watched }) }
                                addOrphanSection(refreshed)
                            },
                            { error ->
                                binding.analysisStatus.visibility = View.VISIBLE
                                binding.analysisStatus.text = error.message ?: error.javaClass.simpleName
                            }
                        )
                )
            }
            .show()
    }

    /** One donut plus a legend for a single source. */
    private fun addSourceSection(usage: SourceUsage, allVideos: List<VideoUsage>) {
        val context = requireContext()
        val section = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 20, 0, 8)
        }

        section.addView(
            TextView(context).apply {
                text = Sources.label(usage.account)
                textSize = 17f
                setTextColor(0xFFFFFFFF.toInt())
            }
        )
        section.addView(
            TextView(context).apply {
                text = getString(
                    R.string.storage_analysis_source_summary,
                    usage.files,
                    formatBytes(usage.bytes)
                )
                textSize = 12f
                setTextColor(0xFF999999.toInt())
                setPadding(0, 4, 0, 8)
            }
        )

        val chart = DonutChartView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (200 * resources.displayMetrics.density).toInt()
            )
        }
        section.addView(chart)

        // Ten biggest; everything else becomes "other" so the ring stays
        // readable instead of showing a hundred slivers.
        val mine = allVideos.filter { it.account == usage.account }.sortedByDescending { it.bytes }
        val top = mine.take(10)
        val restBytes = mine.drop(10).sumOf { it.bytes }

        val palette = intArrayOf(
            0xFFFF4444.toInt(), 0xFFFF8844.toInt(), 0xFFFFCC44.toInt(),
            0xFF88CC44.toInt(), 0xFF44CC88.toInt(), 0xFF44CCCC.toInt(),
            0xFF4488FF.toInt(), 0xFF6644FF.toInt(), 0xFFAA44FF.toInt(),
            0xFFEE44AA.toInt()
        )
        val slices = top.mapIndexed { index, video ->
            DonutChartView.Slice(
                label = video.title,
                bytes = video.bytes,
                color = palette[index % palette.size]
            )
        }.toMutableList()
        if (restBytes > 0) {
            slices.add(
                DonutChartView.Slice(
                    label = getString(R.string.storage_analysis_other),
                    bytes = restBytes,
                    color = 0xFF555555.toInt()
                )
            )
        }
        chart.submit(slices)

        val legend = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val total = usage.bytes.coerceAtLeast(1)
        top.forEachIndexed { index, video ->
            addLegendRow(
                legend,
                video.title,
                video.bytes,
                total,
                palette[index % palette.size]
            )
        }
        if (restBytes > 0) {
            addLegendRow(
                legend,
                getString(R.string.storage_analysis_other),
                restBytes,
                total,
                0xFF555555.toInt()
            )
        }
        section.addView(legend)
        binding.analysisSections.addView(section)
    }

    /** Files no download row claims, shown separately so totals still add up. */
    private fun addOrphanSection(data: StorageBreakdown) {
        if (data.orphanBytes <= 0) return
        val context = requireContext()
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 24, 0, 24)
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        row.addView(
            View(context).apply {
                setBackgroundColor(0xFF666666.toInt())
                layoutParams = LinearLayout.LayoutParams(24, 24).apply { rightMargin = 20 }
            }
        )
        row.addView(
            TextView(context).apply {
                text = getString(R.string.storage_analysis_orphans)
                textSize = 14f
                setTextColor(0xFFFFFFFF.toInt())
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
        )
        row.addView(
            TextView(context).apply {
                text = formatBytes(data.orphanBytes)
                textSize = 13f
                setTextColor(0xFFAAAAAA.toInt())
            }
        )
        binding.analysisSections.addView(row)
    }

    /** Jump to the player for this file. */
    private fun openVideo(video: VideoUsage) {
        startActivity(OfflinePlayerActivity.intent(requireContext(), video.videoId, video.account))
    }

    private fun addLegendRow(
        parent: LinearLayout,
        label: String,
        bytes: Long,
        total: Long,
        color: Int,
        video: VideoUsage? = null
    ) {
        val context = requireContext()
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 10, 0, 10)
            gravity = android.view.Gravity.CENTER_VERTICAL
            if (video != null) {
                isClickable = true
                setBackgroundResource(android.R.drawable.list_selector_background)
                setOnClickListener { openVideo(video) }
            }
        }
        row.addView(
            View(context).apply {
                setBackgroundColor(color)
                layoutParams = LinearLayout.LayoutParams(20, 20).apply { rightMargin = 16 }
            }
        )
        row.addView(
            TextView(context).apply {
                text = label
                textSize = 13f
                setTextColor(0xFFFFFFFF.toInt())
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
        )
        val percent = bytes * 100 / total
        row.addView(
            TextView(context).apply {
                text = "$percent%  ${formatBytes(bytes)}"
                textSize = 12f
                setTextColor(0xFFAAAAAA.toInt())
                setPadding(12, 0, 0, 0)
            }
        )
        parent.addView(row)
    }
}
