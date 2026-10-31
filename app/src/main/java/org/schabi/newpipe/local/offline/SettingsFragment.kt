package org.schabi.newpipe.local.offline

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.schedulers.Schedulers
import org.schabi.newpipe.BaseFragment
import org.schabi.newpipe.R
import org.schabi.newpipe.databinding.FragmentSettingsOfflineBinding

/**
 * Settings for the download service.
 *
 * Everything here writes to videod's settings table. The on-device scheduler
 * reads that table before each sync, so toggling a switch in this screen is
 * what actually decides whether a WiFi connection starts downloading — the
 * app itself never runs the download loop.
 */
class SettingsFragment : BaseFragment() {

    private var _binding: FragmentSettingsOfflineBinding? = null
    private val binding get() = _binding!!

    private val client = VideodClient()
    private val disposables = CompositeDisposable()

    private companion object {
        /** First entry is "follow the system", the rest are explicit. */
        val LANGUAGE_TAGS = listOf("", "en", "zh-CN", "ja")

        /** Display names; these are not translated on purpose so the user can
         *  always recognise their own language in the list. */
        val LANGUAGE_LABELS = listOf("System", "English", "中文", "日本語")
    }

    private var syncSwitch: SwitchCompat? = null
    private var autoDownloadSwitch: SwitchCompat? = null
    private var youtubeSwitch: SwitchCompat? = null
    private var bilibiliSwitch: SwitchCompat? = null
    private var storageText: TextView? = null
    private var syncDetails: TextView? = null
    private var syncProgress: ProgressBar? = null
    private var syncButton: TextView? = null
    private var syncRunning = false
    private var statusText: TextView? = null
    private var currentSyncStage: String = ""
    private var currentChoice: TextView? = null
    private var clearWatchedButton: TextView? = null
    private var lastWatchedCount: Int = 0
    private var lastWatchedBytes: Long = 0L

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        setTitle(getString(R.string.settings))
        _binding = FragmentSettingsOfflineBinding.inflate(inflater, container, false)
        buildRows()
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ViewCompat.setOnApplyWindowInsetsListener(view) { scrollView, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val density = resources.displayMetrics.density
            val top = bars.top + (56 * density).toInt()
            val bottom = bars.bottom + (8 * density).toInt()
            scrollView.setPadding(
                scrollView.paddingLeft,
                top,
                scrollView.paddingRight,
                bottom
            )
            insets
        }
        reload()
    }

    override fun onDestroyView() {
        disposables.clear()
        syncRunning = false
        syncButton = null
        syncProgress = null
        syncDetails = null
        statusText = null
        storageText = null
        clearWatchedButton = null
        _binding = null
        super.onDestroyView()
    }
    override fun onResume() {
        super.onResume()
        (activity as? androidx.appcompat.app.AppCompatActivity)?.let { host ->
            host.findViewById<View>(R.id.toolbar_layout)?.visibility = View.VISIBLE
            host.supportActionBar?.setTitle(R.string.settings)
            host.supportActionBar?.setDisplayHomeAsUpEnabled(false)
            val toolbar = host.findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
            toolbar.title = getString(R.string.settings)
            toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
            toolbar.setNavigationOnClickListener { host.onBackPressedDispatcher.onBackPressed() }
        }
    }

    // ------------------------------------------------------------------- UI

    /** Rows are built in code so the screen stays one small file. */
    private fun buildRows() {
        val column = binding.settingsColumn

        addSection(column, getString(R.string.settings_section_sync))
        syncSwitch = addSwitch(
            column,
            getString(R.string.settings_sync_on_wifi),
            getString(R.string.settings_sync_on_wifi_summary)
        ) { save(mapOf("source_sync" to if (it) "on" else "off")) }

        autoDownloadSwitch = addSwitch(
            column,
            getString(R.string.settings_auto_download),
            getString(R.string.settings_auto_download_summary)
        ) { save(mapOf("auto_download" to if (it) "on" else "off")) }

        syncButton = addButton(column, getString(R.string.settings_sync_now)) { syncNow() }
        syncProgress = ProgressBar(
            requireContext(),
            null,
            android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = 4
            progress = 0
            visibility = View.GONE
        }.also(column::addView)
        syncDetails = TextView(requireContext()).apply {
            textSize = 13f
            setTextColor(0xFFBBBBBB.toInt())
            visibility = View.GONE
            setTextIsSelectable(true)
        }.also(column::addView)

        addSection(column, getString(R.string.settings_section_sources))
        youtubeSwitch = addSwitch(
            column,
            getString(R.string.drawer_youtube),
            getString(R.string.settings_source_summary)
        ) { save(mapOf("source_youtube" to if (it) "on" else "off")) }
        bilibiliSwitch = addSwitch(
            column,
            getString(R.string.drawer_bilibili),
            getString(R.string.settings_source_summary)
        ) { save(mapOf("source_bilibili" to if (it) "on" else "off")) }

        addSection(column, getString(R.string.settings_section_language))
        addChoice(
            column,
            getString(R.string.settings_language),
            LANGUAGE_TAGS.indexOf(currentLanguageTag()),
            getString(R.string.settings_language_summary)
        ) { index -> setLanguage(LANGUAGE_TAGS[index]) }

        addSection(column, getString(R.string.settings_section_storage))
        storageText = addValue(column, getString(R.string.settings_storage_usage), "…")
        addButton(
            column,
            getString(R.string.settings_storage_analysis)
        ) { openAnalysis() }
        clearWatchedButton = addButton(column, getString(R.string.settings_clear_watched)) {
            if (lastWatchedCount <= 0) {
                android.widget.Toast.makeText(
                    requireContext(),
                    R.string.settings_clear_watched_none,
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                return@addButton
            }
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle(R.string.settings_clear_watched)
                .setMessage(
                    getString(
                        R.string.settings_clear_watched_confirm,
                        lastWatchedCount,
                        formatBytes(lastWatchedBytes)
                    )
                )
                .setPositiveButton(R.string.delete) { _, _ -> clear("watched") }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        addButton(column, getString(R.string.settings_clear_all)) { clear("all") }

        addSection(column, "")
        statusText = addValue(column, getString(R.string.settings_status), "")
    }

    private fun addSection(parent: LinearLayout, title: String) {
        if (title.isNotEmpty()) {
            val header = TextView(requireContext()).apply {
                text = title
                textSize = 13f
                setTextColor(0xFF888888.toInt())
                setPadding(0, 40, 0, 12)
            }
            parent.addView(header)
        }
    }

    private fun addSwitch(
        parent: LinearLayout,
        title: String,
        summary: String,
        onChange: (Boolean) -> Unit
    ): SwitchCompat {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 20, 0, 20)
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val textColumn = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(0, 0, 16, 0)
        }
        textColumn.addView(
            TextView(requireContext()).apply {
                text = title
                textSize = 15f
                setTextColor(0xFFFFFFFF.toInt())
            }
        )
        textColumn.addView(
            TextView(requireContext()).apply {
                text = summary
                textSize = 12f
                setTextColor(0xFF999999.toInt())
            }
        )
        val toggle = SwitchCompat(requireContext())
        row.addView(textColumn)
        row.addView(toggle)
        parent.addView(row)

        toggle.setOnCheckedChangeListener { _, checked -> onChange(checked) }
        return toggle
    }

    /** A row that cycles through the available choices when tapped. */
    private fun addChoice(
        parent: LinearLayout,
        title: String,
        initialIndex: Int,
        summary: String,
        onPick: (Int) -> Unit
    ) {
        var index = initialIndex.coerceIn(0, LANGUAGE_LABELS.size - 1)
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 20, 0, 20)
            isClickable = true
            setOnClickListener {
                index = (index + 1) % LANGUAGE_LABELS.size
                currentChoice?.text = LANGUAGE_LABELS[index]
                onPick(index)
            }
        }
        row.addView(
            TextView(requireContext()).apply {
                text = title
                textSize = 15f
                setTextColor(0xFFFFFFFF.toInt())
            }
        )
        row.addView(
            TextView(requireContext()).apply {
                text = summary
                textSize = 12f
                setTextColor(0xFF999999.toInt())
            }
        )
        val value = TextView(requireContext()).apply {
            text = LANGUAGE_LABELS[index]
            textSize = 15f
            setTextColor(0xFF38BDF8.toInt())
            setPadding(0, 8, 0, 0)
        }
        currentChoice = value
        row.addView(value)
        parent.addView(row)
    }

    /** The tag currently applied to the app, or the system default. */
    private fun currentLanguageTag(): String {
        val tags = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales()
        return if (tags.isEmpty) LANGUAGE_TAGS[0] else tags.toLanguageTags()
    }

    /**
     * Apply a language immediately and persist it.
     *
     * setApplicationLocales recreates activities on Android 13+, which is what
     * makes the switch visible without a manual restart.
     */
    private fun setLanguage(tag: String) {
        androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
            androidx.core.os.LocaleListCompat.forLanguageTags(tag)
        )
        requireContext().getSharedPreferences("jm21_sources", 0).edit()
            .putString("language", tag).apply()
    }

    private fun addButton(parent: LinearLayout, label: String, onClick: () -> Unit): TextView {
        val button = TextView(requireContext()).apply {
            text = label
            textSize = 15f
            setTextColor(0xFF38BDF8.toInt())
            setPadding(0, 24, 0, 24)
            isClickable = true
            setOnClickListener { onClick() }
        }
        parent.addView(button)
        return button
    }

    private fun addValue(parent: LinearLayout, label: String, value: String): TextView {
        // Stacked, not side by side: a value such as "233 files, 10.1 GB
        // (47 orphans, 940 MB)" is wider than half the screen in most
        // languages, and sharing a row squeezes the label to one character
        // per line.
        val column = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 16, 0, 16)
        }
        column.addView(
            TextView(requireContext()).apply {
                text = label
                textSize = 15f
                setTextColor(0xFFFFFFFF.toInt())
            }
        )
        val valueView = TextView(requireContext()).apply {
            text = value
            textSize = 13f
            setTextColor(0xFFAAAAAA.toInt())
            setPadding(0, 4, 0, 0)
        }
        column.addView(valueView)
        parent.addView(column)
        return valueView
    }

    // ------------------------------------------------------------- behavior

    private fun reload() {
        disposables.add(
            Single.fromCallable {
                val settings = client.settings()
                val storage = client.storage()
                val breakdown = client.breakdown()
                Triple(settings, storage, breakdown)
            }
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    { (settings, storage, breakdown) ->
                        applySettings(settings)
                        showStorage(storage)
                        updateWatchedStorage(breakdown)
                        statusText?.text = getString(R.string.settings_connected)
                    },
                    { error ->
                        statusText?.text = error.message ?: error.javaClass.simpleName
                    }
                )
        )
    }

    private fun updateWatchedStorage(breakdown: StorageBreakdown) {
        lastWatchedCount = breakdown.watchedFiles
        lastWatchedBytes = breakdown.watchedBytes
        if (lastWatchedCount > 0) {
            clearWatchedButton?.text = getString(
                R.string.settings_clear_watched_count,
                lastWatchedCount,
                formatBytes(lastWatchedBytes)
            )
        } else {
            clearWatchedButton?.text = getString(R.string.settings_clear_watched)
        }
    }

    /** Set switches without firing their listeners back into the service. */
    private fun applySettings(settings: Map<String, String>) {
        setSilently(syncSwitch, settings["source_sync"] != "off")
        setSilently(autoDownloadSwitch, settings["auto_download"] != "off")
        setSilently(youtubeSwitch, settings["source_youtube"] != "off")
        setSilently(bilibiliSwitch, settings["source_bilibili"] != "off")
    }

    private fun setSilently(switch: SwitchCompat?, checked: Boolean) {
        switch ?: return
        switch.setOnCheckedChangeListener(null)
        switch.isChecked = checked
        // Restore the listener the row was created with.
        switch.setOnCheckedChangeListener { _, value ->
            when (switch) {
                syncSwitch -> save(mapOf("source_sync" to if (value) "on" else "off"))
                autoDownloadSwitch -> save(mapOf("auto_download" to if (value) "on" else "off"))
                youtubeSwitch -> save(mapOf("source_youtube" to if (value) "on" else "off"))
                bilibiliSwitch -> save(mapOf("source_bilibili" to if (value) "on" else "off"))
            }
        }
    }

    private fun save(values: Map<String, String>) {
        disposables.add(
            Single.fromCallable { client.saveSettings(values) }
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    { statusText?.text = getString(R.string.settings_saved) },
                    { error ->
                        statusText?.text = error.message ?: error.javaClass.simpleName
                    }
                )
        )
    }

    private fun syncNow() {
        if (syncRunning) return
        syncRunning = true
        currentSyncStage = getString(R.string.settings_sync_connecting)
        syncButton?.isEnabled = false
        syncButton?.alpha = 0.5f
        syncProgress?.apply {
            isIndeterminate = true
            progress = 0
            visibility = View.VISIBLE
        }
        syncDetails?.apply {
            visibility = View.VISIBLE
            text = getString(R.string.settings_sync_connecting)
        }
        statusText?.text = getString(R.string.settings_syncing)

        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        disposables.add(
            Single.fromCallable {
                mainHandler.post {
                    currentSyncStage = getString(R.string.settings_sync_youtube_running)
                    syncDetails?.text = currentSyncStage
                }
                val startedAt = System.nanoTime()
                val syncSettings = client.settings()
                val autoDownload = syncSettings["auto_download"] != "off"
                val youtubeEnabled = syncSettings["source_youtube"] != "off"
                val bilibiliEnabled = syncSettings["source_bilibili"] != "off"
                val sourceCount = (if (youtubeEnabled) 1 else 0) + (if (bilibiliEnabled) 1 else 0)
                val youtubeStarted = System.nanoTime()
                val youtubeResult = if (youtubeEnabled) {
                    client.sync(Sources.YOUTUBE, autoDownload)
                } else {
                    null
                }
                val youtubeDuration = if (youtubeEnabled) elapsedSeconds(youtubeStarted) else 0L
                mainHandler.post {
                    if (youtubeResult != null) {
                        updateSyncDetail(
                            R.string.settings_sync_youtube_done,
                            youtubeDuration,
                            formatSyncResponse(Sources.YOUTUBE, youtubeResult)
                        )
                    } else {
                        syncDetails?.append("\n")
                        syncDetails?.append(getString(R.string.settings_sync_source_skipped, Sources.label(Sources.YOUTUBE)))
                    }
                    syncProgress?.apply {
                        max = sourceCount + 2
                        isIndeterminate = false
                        progress = 1
                    }
                    if (bilibiliEnabled) {
                        currentSyncStage = getString(R.string.settings_sync_bilibili_running)
                        syncDetails?.append("\n")
                        syncDetails?.append(currentSyncStage)
                    }
                }

                val bilibiliStarted = System.nanoTime()
                val bilibiliResult = if (bilibiliEnabled) {
                    client.sync(Sources.BILIBILI, autoDownload)
                } else {
                    null
                }
                val bilibiliDuration = if (bilibiliEnabled) elapsedSeconds(bilibiliStarted) else 0L
                mainHandler.post {
                    if (bilibiliResult != null) {
                        updateSyncDetail(
                            R.string.settings_sync_bilibili_done,
                            bilibiliDuration,
                            formatSyncResponse(Sources.BILIBILI, bilibiliResult)
                        )
                    } else {
                        syncDetails?.append("\n")
                        syncDetails?.append(
                            getString(
                                R.string.settings_sync_source_skipped,
                                Sources.label(Sources.BILIBILI)
                            )
                        )
                    }
                    syncProgress?.apply {
                        max = sourceCount + 2
                        isIndeterminate = false
                        progress = sourceCount
                    }
                    currentSyncStage = getString(R.string.settings_sync_storage_running)
                }

                mainHandler.post {
                    syncProgress?.apply {
                        isIndeterminate = true
                        progress = sourceCount
                    }
                }
                val storage = client.storage()
                val breakdown = client.breakdown()
                val result = SyncResult(
                    storage = storage,
                    breakdown = breakdown,
                    youtubeDuration = youtubeDuration,
                    bilibiliDuration = bilibiliDuration,
                    totalDuration = elapsedSeconds(startedAt)
                )
                mainHandler.post {
                    syncProgress?.apply {
                        isIndeterminate = false
                        progress = 3
                    }
                    syncDetails?.append("\n")
                    syncDetails?.append(getString(R.string.settings_sync_storage_done))
                }
                result
            }
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    { result ->
                        syncProgress?.apply {
                            max = 4
                            isIndeterminate = false
                            progress = 4
                        }
                        syncDetails?.append("\n")
                        syncDetails?.append(
                            getString(
                                R.string.settings_sync_summary,
                                result.youtubeDuration,
                                result.bilibiliDuration,
                                result.totalDuration,
                                result.storage.files,
                                formatBytes(result.storage.bytes),
                                result.storage.orphans,
                                formatBytes(result.storage.orphanBytes)
                            )
                        )
                        showStorage(result.storage)
                        updateWatchedStorage(result.breakdown)
                        currentSyncStage = getString(R.string.settings_sync_complete)
                        statusText?.text = currentSyncStage
                        finishSync()
                    },
                    { error ->
                        syncDetails?.append("\n")
                        syncDetails?.append(
                            getString(
                                R.string.settings_sync_failed_detail,
                                currentSyncStage,
                                error.message ?: error.javaClass.simpleName
                            )
                        )
                        statusText?.text = getString(R.string.settings_sync_failed)
                        finishSync()
                    }
                )
        )
    }

    private fun updateSyncDetail(labelRes: Int, seconds: Long, response: String) {
        syncDetails?.append("\n")
        syncDetails?.append(getString(labelRes, seconds, response.take(240).ifBlank { "—" }))
    }
    private fun formatSyncResponse(source: String, response: SyncResponse): String {
        if (response.errors.isNotEmpty()) {
            throw IllegalStateException(response.errors.joinToString("; "))
        }
        if (response.providerSync != "done") {
            throw IllegalStateException(response.note.ifBlank { "provider_sync=${response.providerSync}" })
        }
        val watchLaterCount = if (response.watchLaterItems >= 0) {
            response.watchLaterItems
        } else {
            response.syncedItems
        }
        if (source == Sources.YOUTUBE && watchLaterCount == 0) {
            throw IllegalStateException(
                getString(R.string.settings_sync_no_items)
            )
        }
        if (source == Sources.YOUTUBE && response.accountId != Sources.YOUTUBE) {
            throw IllegalStateException(
                getString(R.string.settings_sync_wrong_account, response.accountId)
            )
        }
        return getString(
            R.string.settings_sync_source_result,
            Sources.label(source), response.accountId, response.providerSync,
            response.syncedItems, watchLaterCount, response.downloadsQueued,
            response.errors.joinToString().ifBlank { "—" },
            response.note.ifBlank { "—" }
        )
    }

    private fun finishSync() {
        syncRunning = false
        syncButton?.isEnabled = true
        syncButton?.alpha = 1f
    }

    private fun elapsedSeconds(startedAtNanos: Long): Long = ((System.nanoTime() - startedAtNanos) / 1_000_000_000L).coerceAtLeast(1)

    private data class SyncResult(
        val storage: StorageStats,
        val breakdown: StorageBreakdown,
        val youtubeDuration: Long,
        val bilibiliDuration: Long,
        val totalDuration: Long
    )

    /** Open the breakdown screen, which reads its own data. */
    private fun openAnalysis() {
        parentFragmentManager.beginTransaction()
            .replace(R.id.fragment_holder, StorageAnalysisFragment())
            .addToBackStack(null)
            .commit()
    }

    private fun clear(scope: String) {
        disposables.add(
            Single.fromCallable {
                client.clearStorage(scope)
                val storage = client.storage()
                val breakdown = client.breakdown()
                storage to breakdown
            }
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    { (storage, breakdown) ->
                        showStorage(storage)
                        updateWatchedStorage(breakdown)
                        statusText?.text = getString(R.string.settings_cleared)
                    },
                    { error ->
                        statusText?.text = error.message ?: error.javaClass.simpleName
                    }
                )
        )
    }

    /** "12 files · 1.4 GB (3 orphans)" so the numbers are readable at a glance. */
    private fun showStorage(storage: StorageStats) {
        storageText?.text = getString(
            R.string.settings_storage_format,
            storage.files,
            formatBytes(storage.bytes),
            storage.orphans,
            formatBytes(storage.orphanBytes)
        )
    }
}

/** Human-readable byte count. */
fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024
    var unit = 0
    while (value >= 1024 && unit < units.size - 1) {
        value /= 1024
        unit++
    }
    return String.format("%.1f %s", value, units[unit])
}
