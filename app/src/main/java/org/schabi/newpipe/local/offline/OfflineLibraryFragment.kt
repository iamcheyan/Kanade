//
package org.schabi.newpipe.local.offline

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.schedulers.Schedulers
import org.schabi.newpipe.BaseFragment
import org.schabi.newpipe.MainActivity
import org.schabi.newpipe.R
import org.schabi.newpipe.databinding.FragmentOfflineLibraryBinding
import org.schabi.newpipe.databinding.ItemOfflineVideoBinding
import org.schabi.newpipe.util.NavigationHelper
import org.schabi.newpipe.util.image.CoilHelper

/**
 * Modern Gramophone-inspired offline library fragment.
 * Features a large stylish header "Kanade", category tabs (All / Bilibili / YouTube),
 * fast search & sort, rounded cover cards, and full edge-to-edge support.
 */
class OfflineLibraryFragment : BaseFragment() {

    private var _binding: FragmentOfflineLibraryBinding? = null
    private val binding get() = _binding!!

    private var accumulatedScrollY = 0
    private var isMiniPlayerHidden = false
    private val homeChromeScrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            if (dy > 0) {
                if (accumulatedScrollY < 0) accumulatedScrollY = 0
                accumulatedScrollY += dy
                val threshold = (20 * resources.displayMetrics.density).toInt()
                if (accumulatedScrollY > threshold && !isMiniPlayerHidden) {
                    isMiniPlayerHidden = true
                    (activity as? MainActivity)?.setHomeMiniPlayerScrollHidden(true)
                }
            } else if (dy < 0) {
                if (accumulatedScrollY > 0) accumulatedScrollY = 0
                accumulatedScrollY += dy
                val threshold = (12 * resources.displayMetrics.density).toInt()
                if (accumulatedScrollY < -threshold && isMiniPlayerHidden) {
                    isMiniPlayerHidden = false
                    (activity as? MainActivity)?.setHomeMiniPlayerScrollHidden(false)
                }
            }
        }

        override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
            if (!recyclerView.canScrollVertically(-1) && isMiniPlayerHidden) {
                isMiniPlayerHidden = false
                (activity as? MainActivity)?.setHomeMiniPlayerScrollHidden(false)
            }
        }
    }

    private val client = VideodClient()
    private val disposables = CompositeDisposable()
    private val adapter = OfflineAdapter()

    private val entries = ArrayList<LibraryEntry>()
    private val displayedEntries = ArrayList<LibraryEntry>()
    private val progressById = HashMap<String, Long>()
    private val pollingHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pollTask: Runnable? = null

    private var currentAccountFilter: String? = null
    private var showWatchedOnly = false
    private var watchedSyncRunning = false
    private var currentSearchQuery: String = ""
    private var currentSortMode: Int = SORT_DEFAULT

    companion object {
        private const val ARG_ACCOUNT = "account"
        private const val SORT_DEFAULT = 0
        private const val SORT_UNWATCHED_FIRST = 1
        private const val SORT_WATCHED_FIRST = 2
        private const val SORT_DURATION_DESC = 3
        private const val SORT_DURATION_ASC = 4
        private const val SORT_TITLE = 5
        private const val SORT_RESUME = 6

        /** Sources read when no tab narrows the list. */
        private val SOURCE_ORDER = listOf(Sources.YOUTUBE, Sources.BILIBILI)

        /**
         * Two seconds is fast enough that a queued row visibly moves, and slow
         * enough that a long download does not spin the loopback service.
         */
        private const val POLL_INTERVAL_MS = 2_000L

        fun newInstance(account: String?): OfflineLibraryFragment = OfflineLibraryFragment().apply {
            arguments = Bundle().apply {
                if (account != null) putString(ARG_ACCOUNT, account)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentOfflineLibraryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Both sources are selected by default.

        setupInsets()
        setupHeaderActions()
        setupTabs()
        setupSearch()

        binding.offlineList.layoutManager = LinearLayoutManager(requireContext())
        binding.offlineList.adapter = adapter
        binding.offlineList.addOnScrollListener(homeChromeScrollListener)

        binding.offlineSwipeRefresh.setProgressBackgroundColorSchemeColor(0xFF1E222D.toInt())
        binding.offlineSwipeRefresh.setColorSchemeColors(0xFFF1F3F7.toInt(), 0xFF4A80FF.toInt())
        binding.offlineSwipeRefresh.setOnRefreshListener {
            syncAndReload(SyncParams.watchLater(source = currentAccountFilter))
        }

        binding.appBarLayout.addOnOffsetChangedListener { _, verticalOffset ->
            binding.offlineSwipeRefresh.isEnabled = (verticalOffset == 0)
        }

        loadLibrary()
    }

    override fun onDestroyView() {
        isMiniPlayerHidden = false
        accumulatedScrollY = 0
        (activity as? MainActivity)?.setHomeMiniPlayerScrollHidden(false)
        binding.offlineList.removeOnScrollListener(homeChromeScrollListener)
        pollTask?.let { pollingHandler.removeCallbacks(it) }
        pollTask = null
        disposables.clear()
        _binding = null
        super.onDestroyView()
    }

    override fun onResume() {
        super.onResume()
        accumulatedScrollY = 0
        isMiniPlayerHidden = false
        activity?.findViewById<View>(R.id.toolbar_layout)?.visibility = View.GONE
        (activity as? MainActivity)?.setHomeMiniPlayerScrollHidden(false)
        binding.appBarLayout.setExpanded(true, false)
    }

    private fun setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.headerContainer) { view, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val baseTop = (6 * resources.displayMetrics.density).toInt()
            view.setPadding(view.paddingLeft, top + baseTop, view.paddingRight, view.paddingBottom)
            insets
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.offlineList) { list, insets ->
            val bottomInset = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val baseBottom = (96 * resources.displayMetrics.density).toInt()
            list.setPadding(
                list.paddingLeft,
                list.paddingTop,
                list.paddingRight,
                baseBottom + bottomInset
            )
            WindowInsetsCompat.CONSUMED
        }
    }

    private fun setupHeaderActions() {
        binding.btnSettings.setOnClickListener {
            NavigationHelper.openOfflineSettings(parentFragmentManager)
        }

        binding.btnSort.setOnClickListener {
            showSortMenu()
        }
    }

    private fun setupTabs() {
        binding.tabAll.setOnClickListener { selectTab(null) }
        binding.tabBilibili.setOnClickListener { selectTab(Sources.BILIBILI) }
        binding.tabYoutube.setOnClickListener { selectTab(Sources.YOUTUBE) }
        binding.tabWatched.setOnClickListener { selectWatchedTab() }
        binding.clearWatchedButton.setOnClickListener { confirmClearWatched() }
        updateTabsUI()
    }

    private fun selectTab(account: String?) {
        if (currentAccountFilter == account && !showWatchedOnly) return
        currentAccountFilter = account
        showWatchedOnly = false
        updateTabsUI()
        applyFilterAndSort()
    }

    private fun selectWatchedTab() {
        currentAccountFilter = null
        showWatchedOnly = true
        updateTabsUI()
        if (!watchedSyncRunning) {
            syncAndReload(SyncParams.watchLater(source = null))
        }
    }

    private fun updateTabsUI() {
        val selectedTab = currentAccountFilter

        fun setPill(tv: TextView, isSelected: Boolean) {
            tv.setBackgroundResource(
                if (isSelected) R.drawable.bg_tab_pill_selected else R.drawable.bg_tab_pill_unselected
            )
            tv.setTextColor(if (isSelected) 0xFF0D0F14.toInt() else 0xFF9DA3B4.toInt())
            tv.typeface = if (isSelected) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
        }

        setPill(binding.tabAll, selectedTab == null && !showWatchedOnly)
        setPill(binding.tabBilibili, selectedTab == Sources.BILIBILI && !showWatchedOnly)
        setPill(binding.tabYoutube, selectedTab == Sources.YOUTUBE && !showWatchedOnly)
        setPill(binding.tabWatched, showWatchedOnly)
        binding.clearWatchedButton.visibility = if (showWatchedOnly) View.VISIBLE else View.GONE
    }

    /**
     * Unified sync operation that can accept any [SyncParams].
     * Resilient to transient errors: updates UI with whatever is available,
     * keeps pull-to-refresh smooth without hiding content.
     */
    fun syncAndReload(
        params: SyncParams = SyncParams.watchLater(source = currentAccountFilter)
    ) {
        if (watchedSyncRunning) return
        watchedSyncRunning = true
        binding.offlineSwipeRefresh.isRefreshing = true

        disposables.add(
            io.reactivex.rxjava3.core.Single.fromCallable {
                val shouldAutoDownload = client.autoDownloadEnabled()
                val syncResult = client.sync(params.copy(autoDownload = false))
                val loaded = loadEntries(params.source)
                Triple(syncResult, loaded, Pair(params.source, shouldAutoDownload))
            }
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    { (syncResult, loaded, options) ->
                        val (source, shouldAutoDownload) = options
                        // Show newly discovered videos before queueing their files.
                        applyEntries(if (source == null) loaded else mergeEntries(entries, loaded))

                        if (syncResult.errors.isNotEmpty()) {
                            android.widget.Toast.makeText(
                                requireContext(),
                                syncResult.errors.joinToString("\n"),
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }

                        if (shouldAutoDownload) {
                            // Lay out discovered rows before changing them to queued.
                            binding.offlineList.post {
                                if (_binding != null) queuePendingEntries(source)
                            }
                        } else {
                            watchedSyncRunning = false
                            binding.offlineSwipeRefresh.isRefreshing = false
                        }
                    },
                    { error ->
                        watchedSyncRunning = false
                        binding.offlineSwipeRefresh.isRefreshing = false
                        if (entries.isEmpty()) {
                            showStatus(
                                getString(R.string.offline_sync_failed),
                                error.message ?: error.javaClass.simpleName
                            )
                        } else {
                            applyFilterAndSort()
                            android.widget.Toast.makeText(
                                requireContext(),
                                error.message ?: error.javaClass.simpleName,
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                )
        )
    }

    /** Queue newly discovered entries only after their rows have been displayed. */
    private fun queuePendingEntries(source: String?) {
        val accounts = if (source == null) SOURCE_ORDER else listOf(source)
        val pendingByAccount = accounts.associateWith { account ->
            entries.filter { it.accountId == account && it.state == LibraryEntry.State.PENDING }
        }
        val queuedIDs = pendingByAccount.values.flatten().map { it.videoId }.toSet()
        if (queuedIDs.isEmpty()) {
            watchedSyncRunning = false
            binding.offlineSwipeRefresh.isRefreshing = false
            return
        }

        for (entryIndex in entries.indices) {
            val entry = entries[entryIndex]
            if (entry.videoId in queuedIDs && entry.accountId in accounts) {
                entries[entryIndex] = entry.copy(state = LibraryEntry.State.QUEUED, error = "")
            }
        }
        applyFilterAndSort()
        schedulePolling()

        disposables.add(
            io.reactivex.rxjava3.core.Single.fromCallable {
                for ((account, ids) in pendingByAccount) {
                    client.queueDownloads(account, ids.map { it.videoId })
                }
                loadEntries(source)
            }
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    { loaded ->
                        watchedSyncRunning = false
                        binding.offlineSwipeRefresh.isRefreshing = false
                        applyEntries(if (source == null) loaded else mergeEntries(entries, loaded))
                    },
                    { error ->
                        watchedSyncRunning = false
                        binding.offlineSwipeRefresh.isRefreshing = false
                        android.widget.Toast.makeText(
                            requireContext(),
                            error.message ?: error.javaClass.simpleName,
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                        loadLibrary()
                    }
                )
        )
    }

    private fun confirmClearWatched() {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.offline_clear_watched)
            .setMessage(R.string.offline_clear_watched_confirm)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ -> clearWatchedDownloads() }
            .show()
    }

    private fun clearWatchedDownloads() {
        binding.clearWatchedButton.isEnabled = false
        disposables.add(
            io.reactivex.rxjava3.core.Single.fromCallable {
                client.clearStorage("watched")
            }
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    {
                        binding.clearWatchedButton.isEnabled = true
                        loadLibrary()
                    },
                    { error ->
                        binding.clearWatchedButton.isEnabled = true
                        android.widget.Toast.makeText(
                            requireContext(),
                            error.message ?: error.javaClass.simpleName,
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                )
        )
    }

    private fun setupSearch() {
        binding.btnSearch.setOnClickListener {
            if (binding.searchBarContainer.visibility == View.VISIBLE) {
                binding.searchBarContainer.visibility = View.GONE
                binding.searchEditText.setText("")
                hideKeyboard(binding.searchEditText)
            } else {
                binding.appBarLayout.setExpanded(true, true)
                binding.searchBarContainer.visibility = View.VISIBLE
                binding.searchEditText.requestFocus()
                showKeyboard(binding.searchEditText)
            }
        }

        binding.btnClearSearch.setOnClickListener {
            binding.searchEditText.setText("")
            binding.searchBarContainer.visibility = View.GONE
            hideKeyboard(binding.searchEditText)
        }

        binding.searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                currentSearchQuery = s?.toString()?.trim() ?: ""
                applyFilterAndSort()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun showKeyboard(view: View) {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard(view: View) {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun showSortMenu() {
        val popup = PopupMenu(requireContext(), binding.btnSort)
        popup.menu.add(0, SORT_DEFAULT, 0, getString(R.string.sort_default))
        popup.menu.add(0, SORT_UNWATCHED_FIRST, 1, getString(R.string.sort_unwatched_first))
        popup.menu.add(0, SORT_WATCHED_FIRST, 2, getString(R.string.sort_watched_first))
        popup.menu.add(0, SORT_DURATION_DESC, 3, getString(R.string.sort_duration_desc))
        popup.menu.add(0, SORT_DURATION_ASC, 4, getString(R.string.sort_duration_asc))
        popup.menu.add(0, SORT_TITLE, 5, getString(R.string.sort_title))
        popup.menu.add(0, SORT_RESUME, 6, getString(R.string.sort_resume))

        popup.setOnMenuItemClickListener { item ->
            currentSortMode = item.itemId
            applyFilterAndSort()
            true
        }
        popup.show()
    }

    /**
     * Read the three endpoints that together describe the offline library.
     *
     * Runs on the IO scheduler; the result goes to [applyEntries] on the main
     * thread. A failing endpoint degrades the list instead of emptying it: the
     * library, the source list and job state each cover different rows, so one
     * being unreachable must not hide the others.
     */
    private fun loadEntries(source: String?): List<LibraryEntry> {
        val accounts = if (source != null) listOf(source) else SOURCE_ORDER
        val downloaded = client.libraryAll()

        val watchLater = ArrayList<WatchLaterItem>()
        val jobs = ArrayList<DownloadJob>()
        for (account in accounts) {
            try {
                watchLater.addAll(client.watchLater(account))
            } catch (e: Exception) {
                // No source list for this account; downloaded rows still show.
            }
            try {
                jobs.addAll(client.downloads(account))
            } catch (e: Exception) {
                // Without job state, not-yet-downloaded rows read as pending.
            }
        }

        val visibleDownloads = downloaded.filter { source == null || it.accountId == source }
        val resumeById = HashMap<String, Long>()
        for (video in visibleDownloads) {
            val position = client.resumePosition(video.accountId, video.id)
            if (position > 0) resumeById[video.id] = position
        }
        progressById.clear()
        progressById.putAll(resumeById)

        return buildLibraryEntries(visibleDownloads, watchLater, jobs, resumeById)
    }

    /** Replace the rows for the accounts in [fresh], keeping every other row. */
    private fun mergeEntries(
        existing: List<LibraryEntry>,
        fresh: List<LibraryEntry>
    ): List<LibraryEntry> {
        val replaced = fresh.mapTo(HashSet()) { it.accountId }
        val merged = existing.filter { it.accountId !in replaced }.toMutableList()
        merged.addAll(fresh)
        return merged
    }

    private fun applyEntries(next: List<LibraryEntry>) {
        entries.clear()
        entries.addAll(next)
        applyFilterAndSort()
        schedulePolling()
    }

    private fun loadLibrary() {
        if (entries.isEmpty()) {
            showStatus(
                getString(R.string.offline_library_loading),
                VideodClient.DEFAULT_BASE_URL
            )
        }
        val source = currentAccountFilter
        disposables.add(
            io.reactivex.rxjava3.core.Single.fromCallable { loadEntries(source) }
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    { loaded ->
                        applyEntries(if (source == null) loaded else mergeEntries(entries, loaded))
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

    private fun applyFilterAndSort() {
        var filtered = entries.filter { entry ->
            val matchAccount = currentAccountFilter == null ||
                entry.accountId == currentAccountFilter
            val matchWatched = !showWatchedOnly || entry.isWatched
            val matchQuery = currentSearchQuery.isEmpty() ||
                entry.title.contains(currentSearchQuery, ignoreCase = true)
            matchAccount && matchWatched && matchQuery
        }

        filtered = when (currentSortMode) {
            SORT_UNWATCHED_FIRST -> filtered.sortedWith(
                compareBy<LibraryEntry> { it.isWatched }
                    .thenBy { it.state == LibraryEntry.State.DOWNLOADED }
            )

            SORT_WATCHED_FIRST -> filtered.sortedWith(
                compareByDescending<LibraryEntry> { it.isWatched }
                    .thenBy { it.state != LibraryEntry.State.DOWNLOADED }
            )

            SORT_DURATION_DESC -> filtered.sortedWith(
                compareBy<LibraryEntry> { it.state == LibraryEntry.State.DOWNLOADED }
                    .thenByDescending { it.duration }
            )

            SORT_DURATION_ASC -> filtered.sortedWith(
                compareBy<LibraryEntry> { it.state == LibraryEntry.State.DOWNLOADED }
                    .thenBy { it.duration }
            )

            SORT_TITLE -> filtered.sortedWith(
                compareBy<LibraryEntry> { it.state == LibraryEntry.State.DOWNLOADED }
                    .thenBy { it.title.lowercase() }
            )

            SORT_RESUME -> filtered.sortedWith(
                compareBy<LibraryEntry> { it.state == LibraryEntry.State.DOWNLOADED }
                    .thenByDescending { it.resumePosition }
            )

            else -> filtered.sortedBy { it.state == LibraryEntry.State.DOWNLOADED }
        }

        displayedEntries.clear()
        displayedEntries.addAll(filtered)

        if (entries.isEmpty()) {
            showStatus(
                getString(R.string.offline_library_empty),
                getString(R.string.offline_library_empty_detail)
            )
        } else if (displayedEntries.isEmpty()) {
            if (showWatchedOnly) {
                showStatus(getString(R.string.offline_watched_empty), "")
            } else {
                showStatus(getString(R.string.sources_empty), "")
            }
        } else {
            hideStatus()
            adapter.submit(displayedEntries)
        }
    }

    private fun showStatus(title: String, detail: String) {
        binding.offlineStatus.visibility = View.VISIBLE
        binding.offlineStatusTitle.text = title
        binding.offlineStatusDetail.text = detail
        adapter.submit(emptyList())
    }

    private fun hideStatus() {
        binding.offlineStatus.visibility = View.GONE
    }

    /**
     * Poll job state while a row is transferring.
     *
     * The service pushes nothing, so asking is the only way to show progress.
     * Polling stops as soon as no row is active, which is the normal state once
     * the queue drains.
     */
    private fun schedulePolling() {
        pollTask?.let { pollingHandler.removeCallbacks(it) }
        pollTask = null
        if (_binding == null || entries.none { it.isActive }) return
        val task = Runnable { pollJobStates() }
        pollTask = task
        pollingHandler.postDelayed(task, POLL_INTERVAL_MS)
    }

    private fun pollJobStates() {
        if (_binding == null || !isAdded) return
        val accounts = entries.filter { it.isActive }.mapTo(LinkedHashSet()) { it.accountId }
        if (accounts.isEmpty()) return
        disposables.add(
            io.reactivex.rxjava3.core.Single.fromCallable {
                accounts.associateWith { account -> client.activeDownloads(account) }
            }
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    { jobsByAccount ->
                        if (_binding == null) return@subscribe
                        val settled = updateJobStates(jobsByAccount.values.flatten())
                        if (settled) {
                            // A finished job becomes a library row, and the
                            // library endpoint is the source of truth for that.
                            loadLibrary()
                        } else {
                            adapter.submit(displayedEntries)
                            schedulePolling()
                        }
                    },
                    {
                        // Poll transport errors are temporary; keep active rows
                        // and retry instead of treating a network blip as done.
                        schedulePolling()
                    }
                )
        )
    }

    /**
     * Fold the newest job per video into the visible rows.
     *
     * Returns true when a row left the active states, because the caller then
     * has to rebuild the rows from the library endpoint.
     */
    private fun updateJobStates(jobs: List<DownloadJob>): Boolean {
        val latest = HashMap<String, DownloadJob>()
        for (job in jobs) {
            if (job.videoId.isEmpty()) continue
            val key = job.accountId + "/" + job.videoId
            val previous = latest[key]
            if (previous == null || job.id > previous.id) latest[key] = job
        }

        var settled = false
        for (index in entries.indices) {
            val entry = entries[index]
            if (!entry.isActive) continue
            val job = latest[entry.accountId + "/" + entry.videoId] ?: continue
            val state = when (job.state) {
                DownloadJob.QUEUED -> LibraryEntry.State.QUEUED

                DownloadJob.DOWNLOADING -> LibraryEntry.State.ACTIVE

                DownloadJob.PAUSED -> LibraryEntry.State.PAUSED

                DownloadJob.FAILED -> LibraryEntry.State.FAILED

                else -> {
                    settled = true
                    null
                }
            } ?: continue
            entries[index] = entry.copy(
                state = state,
                progress = job.progress,
                jobId = job.id,
                error = job.error
            )
        }
        if (settled) return true
        for (index in displayedEntries.indices) {
            val entry = displayedEntries[index]
            displayedEntries[index] = entries.firstOrNull {
                it.videoId == entry.videoId && it.accountId == entry.accountId
            } ?: entry
        }
        return false
    }

    /** Resume a paused job through videod. */
    private fun resumeDownload(entry: LibraryEntry) {
        disposables.add(
            io.reactivex.rxjava3.core.Single.fromCallable {
                client.resumeDownload(entry.accountId, entry.jobId)
            }
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    { loadLibrary() },
                    { error ->
                        android.widget.Toast.makeText(
                            requireContext(),
                            error.message ?: error.javaClass.simpleName,
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                )
        )
    }

    /** Queue a row whose file is missing; polling then tracks it. */
    private fun startDownload(entry: LibraryEntry) {
        if (entry.isActive) return
        val index = entries.indexOfFirst {
            it.videoId == entry.videoId && it.accountId == entry.accountId
        }
        if (index >= 0) {
            entries[index] = entry.copy(state = LibraryEntry.State.QUEUED, error = "")
            applyFilterAndSort()
            schedulePolling()
        }
        disposables.add(
            io.reactivex.rxjava3.core.Single.fromCallable {
                client.queueDownloads(entry.accountId, listOf(entry.videoId))
            }
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    { loadLibrary() },
                    { error ->
                        android.widget.Toast.makeText(
                            requireContext(),
                            error.message ?: error.javaClass.simpleName,
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                        loadLibrary()
                    }
                )
        )
    }

    private fun play(entry: LibraryEntry, fromStart: Boolean = false) {
        try {
            val intent = OfflinePlayerActivity.intent(
                requireContext(),
                entry.videoId,
                entry.accountId
            )
            if (fromStart) {
                intent.putExtra("from_start", true)
            }
            startActivity(intent)
        } catch (e: Exception) {
            showStatus(
                getString(R.string.offline_library_no_player),
                e.message ?: e.javaClass.simpleName
            )
        }
    }

    private fun deleteDownload(entry: LibraryEntry) {
        disposables.add(
            io.reactivex.rxjava3.core.Single.fromCallable {
                client.deleteLibrary(entry.accountId, entry.videoId)
            }
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    { loadLibrary() },
                    { error ->
                        showStatus(
                            getString(R.string.offline_library_delete_failed),
                            error.message ?: error.javaClass.simpleName
                        )
                    }
                )
        )
    }

    private fun markWatched(entry: LibraryEntry) {
        disposables.add(
            io.reactivex.rxjava3.core.Single
                .fromCallable { client.markWatched(entry.accountId, entry.videoId) }
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

    private fun sourceLabel(account: String): String = when (account) {
        Sources.BILIBILI -> getString(R.string.offline_source_bilibili)
        Sources.YOUTUBE -> getString(R.string.offline_source_youtube)
        else -> account
    }

    private inner class OfflineAdapter : RecyclerView.Adapter<OfflineAdapter.Holder>() {
        private val items = ArrayList<LibraryEntry>()

        fun submit(next: List<LibraryEntry>) {
            items.clear()
            items.addAll(next)
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

            fun bind(entry: LibraryEntry) {
                itemBinding.offlineItemTitle.text = entry.title
                itemBinding.offlineItemMeta.text = buildMeta(entry)
                CoilHelper.loadThumbnail(itemBinding.offlineItemThumbnail, entry.thumbnail)

                val watched = entry.isWatched
                itemBinding.offlineItemWatchedBadge.visibility = if (watched) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
                itemBinding.offlineItemWatchedDim.visibility = if (watched) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
                itemBinding.offlineItemTitle.alpha = if (watched) 0.7f else 1.0f
                itemBinding.offlineItemThumbnail.alpha = if (watched) 0.75f else 1.0f

                if (entry.state == LibraryEntry.State.QUEUED) {
                    // Nothing has transferred yet, so show motion without a value.
                    itemBinding.offlineItemProgress.visibility = View.VISIBLE
                    itemBinding.offlineItemProgress.isIndeterminate = true
                } else if (entry.state == LibraryEntry.State.ACTIVE) {
                    itemBinding.offlineItemProgress.visibility = View.VISIBLE
                    itemBinding.offlineItemProgress.isIndeterminate = false
                    itemBinding.offlineItemProgress.progress = entry.progress
                } else if (entry.state == LibraryEntry.State.FAILED ||
                    entry.state == LibraryEntry.State.PAUSED
                ) {
                    itemBinding.offlineItemProgress.visibility = View.GONE
                } else {
                    itemBinding.offlineItemProgress.visibility = View.GONE
                }
                itemBinding.offlineItemMenu.setOnClickListener {
                    showItemPopupMenu(itemBinding.offlineItemMenu, entry)
                }

                itemBinding.root.setOnLongClickListener {
                    showItemPopupMenu(itemBinding.offlineItemMenu, entry)
                    true
                }
            }

            private fun onRowClick(entry: LibraryEntry) {
                when (entry.state) {
                    LibraryEntry.State.DOWNLOADED -> play(entry)

                    LibraryEntry.State.PENDING, LibraryEntry.State.FAILED -> startDownload(entry)

                    LibraryEntry.State.PAUSED -> resumeDownload(entry)

                    // A running job ignores taps; the row shows its own progress.
                    else -> Unit
                }
            }

            private fun showItemPopupMenu(anchor: View, entry: LibraryEntry) {
                val popup = PopupMenu(requireContext(), anchor)
                if (entry.state == LibraryEntry.State.DOWNLOADED) {
                    popup.menu.add(0, 1, 0, getString(R.string.offline_play))
                    popup.menu.add(0, 2, 1, getString(R.string.offline_play_from_start))
                    if (!entry.isWatched) {
                        popup.menu.add(0, 3, 2, getString(R.string.offline_mark_watched))
                    }
                    popup.menu.add(0, 4, 3, getString(R.string.offline_remove_from_library))
                } else {
                    popup.menu.add(
                        0,
                        if (entry.state == LibraryEntry.State.PAUSED) 6 else 5,
                        0,
                        getString(
                            if (entry.state == LibraryEntry.State.PAUSED) {
                                R.string.offline_download_resume
                            } else {
                                R.string.offline_download_now
                            }
                        )
                    )
                }

                popup.setOnMenuItemClickListener { menuItem ->
                    when (menuItem.itemId) {
                        1 -> play(entry, false)
                        2 -> play(entry, true)
                        3 -> markWatched(entry)
                        4 -> deleteDownload(entry)
                        5 -> startDownload(entry)
                        6 -> resumeDownload(entry)
                    }
                    true
                }
                popup.show()
            }

            private fun buildMeta(entry: LibraryEntry): String {
                val length = formatDuration(entry.duration)
                val source = sourceLabel(entry.accountId)
                val head = if (length.isEmpty()) source else "$length · $source"
                return when (entry.state) {
                    LibraryEntry.State.DOWNLOADED -> {
                        val watchedTag = if (entry.isWatched) {
                            " · " + getString(R.string.offline_watched_badge)
                        } else {
                            ""
                        }
                        if (entry.resumePosition > 0) {
                            "$head · " + getString(
                                R.string.offline_resume_at,
                                formatSeconds(entry.resumePosition)
                            ) + watchedTag
                        } else {
                            "$head$watchedTag"
                        }
                    }

                    LibraryEntry.State.QUEUED ->
                        "$head · " + getString(R.string.offline_download_queued)

                    LibraryEntry.State.ACTIVE -> getString(
                        R.string.offline_downloading,
                        entry.progress
                    ) + " · $source"

                    LibraryEntry.State.PAUSED -> "$head · Paused"

                    LibraryEntry.State.FAILED ->
                        "$head · " + getString(R.string.offline_download_failed)

                    LibraryEntry.State.PENDING ->
                        "$head · " + getString(R.string.sources_tap_to_download)
                }
            }
        }
    }
}
