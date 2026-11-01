//
package org.schabi.newpipe.local.offline

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
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
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.disposables.Disposable
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
    private val refreshResultHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var isPageResumed = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var libraryWork: Disposable? = null
    private var loadGeneration = 0
    private val unseenPolls = HashMap<String, Int>()

    /** Job ids already retried by a refresh, so one bad URL is not requeued forever. */
    private val retriedJobs = HashSet<Long>()

    private val connectivityManager get() = requireContext()
        .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var currentAccountFilter: String? = null
    private var showWatchedOnly = false
    private var showFavoritesOnly = false
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

        /** Local job progress while this screen is open. */
        private const val POLL_INTERVAL_MS = 2_000L

        private const val PAYLOAD_PROGRESS = "progress"

        private const val PREFS_SCOPE = "offline_library_scope"
        private const val PREF_FAVORITES_ONLY = "favorites_only"

        /** How long the refresh summary stays before collapsing. */
        private const val REFRESH_RESULT_MS = 4_000L

        fun newInstance(account: String?): OfflineLibraryFragment = OfflineLibraryFragment().apply {
            arguments = Bundle().apply {
                if (account != null) putString(ARG_ACCOUNT, account)
            }
        }
    }

    override fun onPause() {
        isPageResumed = false
        pollTask?.let { pollingHandler.removeCallbacks(it) }
        pollTask = null
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        accumulatedScrollY = 0
        isMiniPlayerHidden = false
        activity?.findViewById<View>(R.id.toolbar_layout)?.visibility = View.GONE
        (activity as? MainActivity)?.setHomeMiniPlayerScrollHidden(false)
        binding.appBarLayout.setExpanded(true, false)
        isPageResumed = true
        updateRefreshAvailability()
        schedulePolling()
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
        restoreFavoritesScope()
        setupHeaderActions()
        setupTabs()
        setupSearch()
        updateScopeButton()

        binding.offlineList.layoutManager = LinearLayoutManager(requireContext())
        binding.offlineList.adapter = adapter
        binding.offlineList.addOnScrollListener(homeChromeScrollListener)

        binding.offlineSwipeRefresh.setProgressBackgroundColorSchemeColor(0xFF1E222D.toInt())
        binding.offlineSwipeRefresh.setColorSchemeColors(0xFFF1F3F7.toInt(), 0xFF4A80FF.toInt())
        binding.offlineSwipeRefresh.setOnRefreshListener {
            if (!canSyncNow()) {
                binding.offlineSwipeRefresh.isRefreshing = false
                showWifiRequired()
                return@setOnRefreshListener
            }
            syncAndReload(SyncParams.watchLater(source = currentAccountFilter))
        }
        updateRefreshAvailability()

        binding.appBarLayout.addOnOffsetChangedListener { _, verticalOffset ->
            binding.offlineSwipeRefresh.isEnabled = verticalOffset == 0 && canSyncNow()
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                onNetworkChanged()
            }

            override fun onLost(network: Network) {
                onNetworkChanged()
            }
        }
        networkCallback = callback
        connectivityManager.registerDefaultNetworkCallback(callback)

        loadLibrary()
    }

    override fun onDestroyView() {
        isMiniPlayerHidden = false
        accumulatedScrollY = 0
        (activity as? MainActivity)?.setHomeMiniPlayerScrollHidden(false)
        binding.offlineList.removeOnScrollListener(homeChromeScrollListener)
        pollTask?.let { pollingHandler.removeCallbacks(it) }
        pollTask = null
        networkCallback?.let {
            runCatching { connectivityManager.unregisterNetworkCallback(it) }
        }
        networkCallback = null
        refreshResultHandler.removeCallbacks(collapseRefreshResult)
        libraryWork?.dispose()
        libraryWork = null
        disposables.clear()
        _binding = null
        super.onDestroyView()
    }

    private fun beginLoad(): Int {
        loadGeneration += 1
        libraryWork?.dispose()
        libraryWork = null
        watchedSyncRunning = false
        _binding?.offlineSwipeRefresh?.isRefreshing = false
        return loadGeneration
    }

    private fun isCurrent(generation: Int): Boolean {
        return generation == loadGeneration && isAdded && _binding != null
    }

    private fun trackLibraryWork(generation: Int, work: Disposable) {
        if (!isCurrent(generation)) {
            work.dispose()
            return
        }
        libraryWork = work
        disposables.add(work)
    }

    /**
     * Pull-to-refresh syncs the source list and queues downloads, so it only
     * runs on Wi-Fi. On mobile data the gesture is disabled rather than quietly
     * spending the user's allowance.
     */
    private fun isOnWifi(): Boolean {
        val capabilities = connectivityManager.getNetworkCapabilities(
            connectivityManager.activeNetwork
        ) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private fun canSyncNow(): Boolean = isOnWifi()

    private fun updateRefreshAvailability() {
        if (_binding == null) return
        binding.offlineSwipeRefresh.isEnabled = canSyncNow()
        if (!canSyncNow()) binding.offlineSwipeRefresh.isRefreshing = false
    }

    private fun onNetworkChanged() {
        if (_binding == null || !isAdded) return
        activity?.runOnUiThread {
            if (_binding == null) return@runOnUiThread
            updateRefreshAvailability()
        }
    }

    private fun showWifiRequired() {
        android.widget.Toast.makeText(
            requireContext(),
            getString(R.string.offline_wifi_required),
            android.widget.Toast.LENGTH_LONG
        ).show()
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
            // The list sits inside a weighted column that already ends above the
            // mini player, so only the navigation bar needs clearing here.
            val baseBottom = (12 * resources.displayMetrics.density).toInt()
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

        binding.btnScope.setOnClickListener {
            showFavoritesOnly = !showFavoritesOnly
            persistFavoritesScope()
            updateScopeButton()
            applyFilterAndSort()
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
        applyFilterAndSort()
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
        updateClearWatchedButton()
    }

    /**
     * The "delete all watched" action only makes sense on the watched tab and
     * only while at least one watched row exists; otherwise it is a dead button.
     */
    private fun updateClearWatchedButton() {
        val hasWatched = entries.any { it.isWatched }
        binding.clearWatchedButton.visibility =
            if (showWatchedOnly && hasWatched) View.VISIBLE else View.GONE
    }

    /**
     * Mirror the all/favorites scope on the header button so the current mode
     * is readable without opening anything.
     */
    private fun updateScopeButton() {
        binding.btnScope.setImageResource(
            if (showFavoritesOnly) R.drawable.ic_favorite else R.drawable.ic_stars
        )
        binding.btnScope.contentDescription = getString(
            if (showFavoritesOnly) R.string.offline_scope_favorites else R.string.offline_scope_all
        )
        binding.btnScope.setColorFilter(
            if (showFavoritesOnly) 0xFF38BDF8.toInt() else 0xFFD0D3DC.toInt()
        )
    }

    /** The scope survives restarts so a user who only wants favorites keeps it. */
    private fun persistFavoritesScope() {
        requireContext().getSharedPreferences(PREFS_SCOPE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_FAVORITES_ONLY, showFavoritesOnly)
            .apply()
    }

    private fun restoreFavoritesScope() {
        showFavoritesOnly = requireContext()
            .getSharedPreferences(PREFS_SCOPE, Context.MODE_PRIVATE)
            .getBoolean(PREF_FAVORITES_ONLY, false)
    }

    /**
     * Pull-to-refresh. Each enabled source is synced on its own; its rows are
     * shown as soon as that source returns, then pending rows are queued.
     */
    fun syncAndReload(
        params: SyncParams = SyncParams.watchLater(source = currentAccountFilter)
    ) {
        if (watchedSyncRunning) return
        if (!canSyncNow()) {
            binding.offlineSwipeRefresh.isRefreshing = false
            showWifiRequired()
            return
        }
        val generation = beginLoad()
        watchedSyncRunning = true
        binding.offlineSwipeRefresh.isRefreshing = true
        val requested = params.source
        val syncedSources = ArrayList<String>()
        val syncErrors = ArrayList<String>()
        val work = Single.fromCallable { sourcesFor(requested) }
            .subscribeOn(Schedulers.io())
            .flatMapObservable { sources ->
                Observable.fromIterable(sources).concatMap { source ->
                    Single.fromCallable {
                        val result = client.sync(
                            SyncParams.watchLater(source = source, autoDownload = false)
                        )
                        SourceLoad(source, loadEntries(source, includeHistory = false), result.errors)
                    }
                        .subscribeOn(Schedulers.io())
                        .onErrorReturn { error ->
                            SourceLoad(
                                source,
                                null,
                                listOf(error.message ?: error.javaClass.simpleName)
                            )
                        }
                        .toObservable()
                }
            }
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                { loaded ->
                    if (!isCurrent(generation)) return@subscribe
                    if (loaded.entries != null) {
                        syncedSources.add(loaded.source)
                        applyEntries(mergeEntries(entries, loaded.entries))
                    }
                    syncErrors.addAll(loaded.errors)
                },
                { error ->
                    if (!isCurrent(generation)) return@subscribe
                    finishRefresh()
                    if (entries.isEmpty()) {
                        showStatus(
                            getString(R.string.offline_sync_failed),
                            error.message ?: error.javaClass.simpleName
                        )
                    } else {
                        toast(error.message ?: error.javaClass.simpleName)
                    }
                },
                {
                    if (!isCurrent(generation)) return@subscribe
                    if (syncErrors.isNotEmpty()) {
                        toast(syncErrors.joinToString("\n"))
                    }
                    val accounts = if (requested != null) listOf(requested) else syncedSources
                    queuePendingEntries(accounts, generation)
                }
            )
        trackLibraryWork(generation, work)
    }

    private fun sourcesFor(requested: String?): List<String> {
        val enabled = client.configuredSources()
        return if (requested == null) enabled else enabled.filter { it == requested }
    }

    private fun finishRefresh() {
        watchedSyncRunning = false
        _binding?.offlineSwipeRefresh?.isRefreshing = false
    }

    private fun toast(message: String) {
        if (!isAdded) return
        android.widget.Toast.makeText(requireContext(), message, android.widget.Toast.LENGTH_LONG).show()
    }

    /** Queue rows that are on the list but not downloaded yet, without reordering them. */
    private fun queuePendingEntries(accounts: List<String>, generation: Int) {
        if (!isCurrent(generation) || _binding == null) return
        // Failed rows are retried too: without that, one transient yt-dlp error
        // parks a video forever, because it never returns to PENDING by itself.
        // PAUSED is left alone; that state is the user's decision.
        val pendingByAccount = accounts.distinct().associateWith { account ->
            entries.filter {
                it.accountId == account && when (it.state) {
                    LibraryEntry.State.PENDING -> true

                    // Retry a failure once; a job that failed again is left for
                    // the user to tap, so one dead URL cannot loop forever.
                    LibraryEntry.State.FAILED -> it.jobId !in retriedJobs

                    else -> false
                }
            }
        }
        val queuedIds = pendingByAccount.values.flatten().map { it.videoId }.toSet()
        android.util.Log.d(
            "KanadeQueue",
            "accounts=$accounts entries=${entries.size} pending=${pendingByAccount.mapValues { it.value.size }}"
        )
        if (queuedIds.isEmpty()) {
            finishRefresh()
            schedulePolling()
            // Everything the sources returned is already on disk or queued.
            showRefreshResult(getString(R.string.offline_refresh_up_to_date))
            return
        }
        announceNewVideos(queuedIds.size)

        for (entryIndex in entries.indices) {
            val entry = entries[entryIndex]
            if (entry.videoId in queuedIds && entry.accountId in accounts) {
                if (entry.state == LibraryEntry.State.FAILED && entry.jobId > 0L) {
                    retriedJobs.add(entry.jobId)
                }
                entries[entryIndex] = entry.copy(state = LibraryEntry.State.QUEUED, error = "")
            }
        }
        syncDisplayedFromEntries()
        schedulePolling()

        val work = Single.fromCallable {
            for ((account, ids) in pendingByAccount) {
                if (ids.isNotEmpty()) client.queueDownloads(account, ids.map { it.videoId })
            }
            true
        }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                {
                    if (!isCurrent(generation)) return@subscribe
                    finishRefresh()
                    schedulePolling()
                },
                { error ->
                    if (!isCurrent(generation)) return@subscribe
                    finishRefresh()
                    toast(error.message ?: error.javaClass.simpleName)
                }
            )
        trackLibraryWork(generation, work)
    }

    /**
     * Show what a refresh found above the list, then collapse it.
     *
     * The strip is used instead of a toast so the outcome survives long enough
     * to read while new rows animate in underneath.
     */
    private fun announceNewVideos(count: Int) {
        showRefreshResult(
            if (count == 1) {
                getString(R.string.offline_refresh_found_one)
            } else {
                getString(R.string.offline_refresh_found, count)
            }
        )
    }

    private fun showRefreshResult(message: String) {
        if (_binding == null) return
        val bar = binding.refreshResult
        binding.refreshResultText.text = message
        refreshResultHandler.removeCallbacks(collapseRefreshResult)
        if (bar.visibility != View.VISIBLE) {
            bar.visibility = View.VISIBLE
            bar.alpha = 0f
            bar.translationY = -bar.resources.displayMetrics.density * 8f
            bar.animate().alpha(1f).translationY(0f).setDuration(180).start()
        }
        refreshResultHandler.postDelayed(collapseRefreshResult, REFRESH_RESULT_MS)
    }

    private val collapseRefreshResult = Runnable {
        if (_binding == null) return@Runnable
        val bar = binding.refreshResult
        bar.animate()
            .alpha(0f)
            .translationY(-bar.resources.displayMetrics.density * 8f)
            .setDuration(160)
            .withEndAction {
                if (_binding != null) binding.refreshResult.visibility = View.GONE
            }
            .start()
    }

    private data class SourceLoad(
        val source: String,
        val entries: List<LibraryEntry>?,
        val errors: List<String>
    )

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
    private fun loadEntries(source: String?, includeHistory: Boolean = true): List<LibraryEntry> {
        val accounts = if (source != null) listOf(source) else SOURCE_ORDER
        val downloaded = client.libraryAll()

        val watchLater = ArrayList<WatchLaterItem>()
        val jobs = ArrayList<DownloadJob>()
        val favorites = HashSet<String>()
        for (account in accounts) {
            try {
                watchLater.addAll(client.watchLater(account))
            } catch (e: Exception) {
                // No source list for this account; downloaded rows still show.
            }
            try {
                for (id in client.favorites(account)) favorites.add(account + "/" + id)
            } catch (e: Exception) {
                // Favorites are optional; the library still loads without them.
            }
            try {
                jobs.addAll(client.downloads(account))
            } catch (e: Exception) {
                // Without job state, not-yet-downloaded rows read as pending.
            }
        }

        val visibleDownloads = downloaded.filter { source == null || it.accountId == source }
        val resumeById = HashMap<String, Long>()
        if (includeHistory) {
            for (account in accounts) {
                val accountVideoIds = visibleDownloads
                    .filter { it.accountId == account }
                    .mapTo(HashSet()) { it.id }
                try {
                    resumeById.putAll(client.resumePositions(account, accountVideoIds))
                } catch (e: Exception) {
                    // Resume positions are optional; the library itself still loads.
                }
            }
            progressById.clear()
            progressById.putAll(resumeById)
        }

        return buildLibraryEntries(visibleDownloads, watchLater, jobs, resumeById, favorites)
    }

    /** Replace the rows for the accounts in [fresh], keeping every other row. */
    private fun mergeEntries(
        existing: List<LibraryEntry>,
        fresh: List<LibraryEntry>
    ): List<LibraryEntry> {
        val previous = existing.associateBy { it.accountId + "/" + it.videoId }
        val replaced = fresh.mapTo(HashSet()) { it.accountId }
        val merged = existing.filter { it.accountId !in replaced }.toMutableList()
        merged.addAll(
            fresh.map { row ->
                val old = previous[row.accountId + "/" + row.videoId]
                if (old != null && row.resumePosition == 0L && old.resumePosition > 0L) {
                    row.copy(resumePosition = old.resumePosition)
                } else {
                    row
                }
            }
        )
        return merged
    }

    private fun applyEntries(next: List<LibraryEntry>) {
        entries.clear()
        entries.addAll(next)
        applyFilterAndSort()
        schedulePolling()
    }

    private fun loadLibrary() {
        if (entries.isEmpty() && _binding != null) {
            showStatus(
                getString(R.string.offline_library_loading),
                VideodClient.DEFAULT_BASE_URL
            )
        }
        val source = currentAccountFilter
        val generation = beginLoad()
        val work = Single.fromCallable { loadEntries(source) }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                { loaded ->
                    if (!isCurrent(generation)) return@subscribe
                    applyEntries(if (source == null) loaded else mergeEntries(entries, loaded))
                },
                { error ->
                    if (!isCurrent(generation)) return@subscribe
                    showStatus(
                        getString(R.string.offline_library_unreachable),
                        error.message ?: error.javaClass.simpleName
                    )
                }
            )
        trackLibraryWork(generation, work)
    }

    private fun applyFilterAndSort() {
        var filtered = entries.filter { entry ->
            val matchAccount = currentAccountFilter == null ||
                entry.accountId == currentAccountFilter
            val matchWatched = !showWatchedOnly || entry.isWatched
            val matchFavorite = !showFavoritesOnly || entry.isFavorite
            val matchQuery = currentSearchQuery.isEmpty() ||
                entry.title.contains(currentSearchQuery, ignoreCase = true)
            matchAccount && matchWatched && matchFavorite && matchQuery
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
            if (showFavoritesOnly) {
                showStatus(getString(R.string.offline_favorites_empty), "")
            } else if (showWatchedOnly) {
                showStatus(getString(R.string.offline_watched_empty), "")
            } else {
                showStatus(getString(R.string.sources_empty), "")
            }
        } else {
            hideStatus()
            adapter.submit(displayedEntries)
        }
        updateClearWatchedButton()
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
        if (!isPageResumed || _binding == null || entries.none { it.isActive }) return
        val task = Runnable { pollJobStates() }
        pollTask = task
        pollingHandler.postDelayed(task, POLL_INTERVAL_MS)
    }

    private fun pollJobStates() {
        if (_binding == null || !isAdded) return
        val accounts = entries.filter { it.isActive }.mapTo(LinkedHashSet()) { it.accountId }
        if (accounts.isEmpty()) return
        disposables.add(
            Single.fromCallable {
                accounts.associateWith { account -> client.activeDownloads(account) }
            }
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    { jobsByAccount ->
                        if (_binding == null) return@subscribe
                        val settled = updateJobStates(jobsByAccount.values.flatten())
                        if (settled) {
                            promoteFinished(accounts)
                        } else {
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
     * A row left the active job list. Turn it into a library row where the
     * file exists, otherwise show the failed or paused job, in the same position.
     */
    private fun promoteFinished(accounts: Set<String>) {
        disposables.add(
            Single.fromCallable {
                accounts.associateWith { account ->
                    client.library(account) to client.downloads(account)
                }
            }
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    { snapshot ->
                        if (_binding == null) return@subscribe
                        for (index in entries.indices) {
                            val entry = entries[index]
                            if (!entry.isActive) continue
                            val pair = snapshot[entry.accountId] ?: continue
                            val key = entry.accountId + "/" + entry.videoId
                            val video = pair.first.firstOrNull { it.id == entry.videoId }
                            if (video != null) {
                                entries[index] = entry.copy(
                                    state = LibraryEntry.State.DOWNLOADED,
                                    progress = 100,
                                    remoteMissingSince = video.remoteMissingSince,
                                    error = ""
                                )
                                unseenPolls.remove(key)
                                continue
                            }
                            val job = pair.second
                                .filter { it.videoId == entry.videoId }
                                .maxByOrNull { it.id }
                            if (job == null) {
                                if ((unseenPolls[key] ?: 0) >= 2 && entry.jobId == 0L) {
                                    entries[index] = entry.copy(
                                        state = LibraryEntry.State.PENDING,
                                        progress = 0
                                    )
                                }
                                continue
                            }
                            if (job.isActive) {
                                entries[index] = entry.copy(
                                    state = if (job.state == DownloadJob.DOWNLOADING) {
                                        LibraryEntry.State.ACTIVE
                                    } else {
                                        LibraryEntry.State.QUEUED
                                    },
                                    progress = job.progress,
                                    jobId = job.id,
                                    error = job.error
                                )
                                continue
                            }
                            val state = when (job.state) {
                                DownloadJob.PAUSED -> LibraryEntry.State.PAUSED
                                DownloadJob.FAILED -> LibraryEntry.State.FAILED
                                else -> LibraryEntry.State.PENDING
                            }
                            entries[index] = entry.copy(
                                state = state,
                                progress = job.progress,
                                jobId = job.id,
                                error = job.error
                            )
                            unseenPolls.remove(key)
                        }
                        syncDisplayedFromEntries()
                        schedulePolling()
                    },
                    { schedulePolling() }
                )
        )
    }

    /** Copy updated rows into the visible list without changing their order. */
    private fun syncDisplayedFromEntries() {
        for (index in displayedEntries.indices) {
            val shown = displayedEntries[index]
            val fresh = entries.firstOrNull {
                it.videoId == shown.videoId && it.accountId == shown.accountId
            } ?: continue
            if (fresh != shown) {
                displayedEntries[index] = fresh
                adapter.bindProgressAt(index, fresh)
            }
        }
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
            val key = entry.accountId + "/" + entry.videoId
            val job = latest[key]
            if (job == null) {
                val misses = (unseenPolls[key] ?: 0) + 1
                unseenPolls[key] = misses
                if (entry.jobId > 0L || misses >= 2) settled = true
                continue
            }
            unseenPolls.remove(key)
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
        syncDisplayedFromEntries()
        return settled
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

    /**
     * Remove a download (and its job history) so the video can be fetched again.
     *
     * Used both to clear a half-broken file and to reset a video during
     * testing: after this the row is back to PENDING, and the next refresh
     * queues it exactly like a newly discovered video.
     */
    private fun deleteDownload(entry: LibraryEntry) {
        disposables.add(
            io.reactivex.rxjava3.core.Single.fromCallable {
                client.deleteLibrary(entry.accountId, entry.videoId)
            }
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    {
                        // Forget the retry bookkeeping for this video, otherwise
                        // the "already retried once" guard would skip it.
                        val index = entries.indexOfFirst {
                            it.videoId == entry.videoId && it.accountId == entry.accountId
                        }
                        if (index >= 0) {
                            val old = entries[index]
                            if (old.jobId > 0L) retriedJobs.remove(old.jobId)
                            entries[index] = old.copy(
                                state = LibraryEntry.State.PENDING,
                                progress = 0,
                                jobId = 0L,
                                error = ""
                            )
                            syncDisplayedFromEntries()
                            applyFilterAndSort()
                        }
                        loadLibrary()
                    },
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

    /**
     * Favorites live in videod so they survive a reinstall, and the row is
     * updated in place: a full reload would scroll the list back to the top.
     */
    private fun toggleFavorite(entry: LibraryEntry) {
        val next = !entry.isFavorite
        val index = entries.indexOfFirst {
            it.videoId == entry.videoId && it.accountId == entry.accountId
        }
        if (index >= 0) {
            entries[index] = entries[index].copy(favorite = next)
            syncDisplayedFromEntries()
            if (showFavoritesOnly) applyFilterAndSort()
        }
        disposables.add(
            io.reactivex.rxjava3.core.Single.fromCallable {
                if (next) {
                    client.addFavorite(entry.accountId, entry.videoId)
                } else {
                    client.removeFavorite(entry.accountId, entry.videoId)
                }
            }
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    {},
                    { error ->
                        // Put the row back so the UI does not claim a state the
                        // service rejected.
                        if (index >= 0 && index < entries.size) {
                            entries[index] = entries[index].copy(favorite = !next)
                            syncDisplayedFromEntries()
                            applyFilterAndSort()
                        }
                        toast(error.message ?: error.javaClass.simpleName)
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
            val old = items.toList()
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize(): Int = old.size
                override fun getNewListSize(): Int = next.size
                override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean {
                    return old[oldPos].videoId == next[newPos].videoId &&
                        old[oldPos].accountId == next[newPos].accountId
                }
                override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean {
                    return old[oldPos] == next[newPos]
                }
            })
            items.clear()
            items.addAll(next)
            diff.dispatchUpdatesTo(this)
        }

        fun bindProgressAt(position: Int, entry: LibraryEntry) {
            if (position !in items.indices) return
            items[position] = entry
            notifyItemChanged(position, PAYLOAD_PROGRESS)
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

        override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
            if (payloads.isNotEmpty()) {
                holder.bindProgress(items[position])
                return
            }
            super.onBindViewHolder(holder, position, payloads)
        }

        override fun getItemCount(): Int = items.size

        inner class Holder(private val itemBinding: ItemOfflineVideoBinding) :
            RecyclerView.ViewHolder(itemBinding.root) {

            fun bind(entry: LibraryEntry) {
                itemBinding.offlineItemTitle.text = entry.title
                CoilHelper.loadThumbnail(itemBinding.offlineItemThumbnail, entry.thumbnail)
                bindProgress(entry)
            }

            fun bindProgress(entry: LibraryEntry) {
                itemBinding.offlineItemMeta.text = buildMeta(entry)
                itemBinding.root.setOnClickListener { onRowClick(entry) }

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
                    itemBinding.offlineItemProgress.isIndeterminate = false
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

                popup.menu.add(
                    0,
                    7,
                    if (entry.state == LibraryEntry.State.DOWNLOADED) 3 else 1,
                    getString(
                        if (entry.isFavorite) {
                            R.string.offline_favorite_remove
                        } else {
                            R.string.offline_favorite_add
                        }
                    )
                )

                // Deleting is how a half-broken download is cleared, and how a
                // test run resets a video so the next refresh queues it again.
                if (entry.state != LibraryEntry.State.QUEUED &&
                    entry.state != LibraryEntry.State.ACTIVE
                ) {
                    popup.menu.add(0, 4, 4, getString(R.string.offline_delete_download))
                }

                popup.setOnMenuItemClickListener { menuItem ->
                    when (menuItem.itemId) {
                        1 -> play(entry, false)
                        2 -> play(entry, true)
                        3 -> markWatched(entry)
                        4 -> deleteDownload(entry)
                        5 -> startDownload(entry)
                        6 -> resumeDownload(entry)
                        7 -> toggleFavorite(entry)
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
