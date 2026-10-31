package org.schabi.newpipe.local.offline

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.graphics.Insets
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
    private val homeChromeScrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            if (dy != 0) setHomeChromeHidden(dy > 0)
        }
    }

    private fun setHomeChromeHidden(hidden: Boolean) {
        val header = _binding?.headerContainer ?: return
        header.animate().cancel()
        if (!hidden) header.visibility = View.VISIBLE
        header.animate()
            .translationY(if (hidden) -header.height.toFloat() else 0f)
            .alpha(if (hidden) 0f else 1f)
            .setDuration(220)
            .withEndAction { if (hidden) header.visibility = View.GONE }
            .start()
        (activity as? MainActivity)?.setHomeMiniPlayerScrollHidden(hidden)
    }

    private val client = VideodClient()
    private val disposables = CompositeDisposable()
    private val adapter = OfflineAdapter()

    private val allVideos = ArrayList<OfflineVideo>()
    private val displayedVideos = ArrayList<OfflineVideo>()
    private val progressById = HashMap<String, Long>()

    private val selectedAccounts = linkedSetOf(Sources.BILIBILI, Sources.YOUTUBE)
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

        loadLibrary()
    }

    override fun onDestroyView() {
        (activity as? MainActivity)?.setHomeMiniPlayerScrollHidden(false)
        binding.offlineList.removeOnScrollListener(homeChromeScrollListener)
        binding.headerContainer.animate().cancel()
        binding.headerContainer.visibility = View.VISIBLE
        binding.headerContainer.translationY = 0f
        binding.headerContainer.alpha = 1f
        disposables.clear()
        _binding = null
        super.onDestroyView()
    }

    override fun onResume() {
        super.onResume()
        activity?.findViewById<View>(R.id.toolbar_layout)?.visibility = View.GONE
        setHomeChromeHidden(false)
    }


    private fun setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.headerContainer) { view, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val baseTop = (6 * resources.displayMetrics.density).toInt()
            view.setPadding(view.paddingLeft, top + baseTop, view.paddingRight, view.paddingBottom)
            insets
        }
        binding.offlineList.setPadding(
            binding.offlineList.paddingLeft,
            (4 * resources.displayMetrics.density).toInt(),
            binding.offlineList.paddingRight,
            binding.offlineList.paddingBottom
        )
        binding.headerContainer.addOnLayoutChangeListener { header, _, _, _, _, _, _, _, _ ->
            val top = header.height + (4 * resources.displayMetrics.density).toInt()
            binding.offlineList.setPadding(
                binding.offlineList.paddingLeft,
                top,
                binding.offlineList.paddingRight,
                binding.offlineList.paddingBottom
            )
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
        binding.sourceFilter.setOnClickListener { anchor ->
            val popup = PopupMenu(requireContext(), anchor)
            popup.menu.add(0, 1, 0, getString(R.string.drawer_bilibili)).apply {
                isCheckable = true
                isChecked = Sources.BILIBILI in selectedAccounts
            }
            popup.menu.add(0, 2, 1, getString(R.string.drawer_youtube)).apply {
                isCheckable = true
                isChecked = Sources.YOUTUBE in selectedAccounts
            }
            popup.setOnMenuItemClickListener { item ->
                val account = if (item.itemId == 1) Sources.BILIBILI else Sources.YOUTUBE
                if (account in selectedAccounts) {
                    if (selectedAccounts.size > 1) selectedAccounts.remove(account)
                } else {
                    selectedAccounts.add(account)
                }
                updateTabsUI()
                applyFilterAndSort()
                true
            }
            popup.show()
        }
    }

    private fun updateTabsUI() {
        binding.sourceFilter.contentDescription = getString(R.string.app_name)
    }

    private fun setupSearch() {
        binding.btnSearch.setOnClickListener {
            if (binding.searchBarContainer.visibility == View.VISIBLE) {
                binding.searchBarContainer.visibility = View.GONE
                binding.searchEditText.setText("")
                hideKeyboard(binding.searchEditText)
            } else {
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

    private fun loadLibrary() {
        showStatus(
            getString(R.string.offline_library_loading),
            VideodClient.DEFAULT_BASE_URL
        )
        disposables.add(
            io.reactivex.rxjava3.core.Single
                .fromCallable {
                    val all = client.libraryAll()
                    val positions = HashMap<String, Long>()
                    for (item in all) {
                        val p = client.resumePosition(item.accountId, item.id)
                        if (p > 0) positions[item.id] = p
                    }
                    all to positions
                }
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    { (videos, positions) ->
                        allVideos.clear()
                        allVideos.addAll(videos)
                        progressById.clear()
                        progressById.putAll(positions)
                        applyFilterAndSort()
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
        var filtered = allVideos.filter { video ->
            val matchAccount = video.accountId in selectedAccounts
            val matchQuery = currentSearchQuery.isEmpty() ||
                video.title.contains(currentSearchQuery, ignoreCase = true)
            matchAccount && matchQuery
        }

        filtered = when (currentSortMode) {
            SORT_UNWATCHED_FIRST -> {
                filtered.sortedWith(
                    compareBy<OfflineVideo> { it.isWatched }
                        .thenBy { allVideos.indexOf(it) }
                )
            }

            SORT_WATCHED_FIRST -> {
                filtered.sortedWith(
                    compareByDescending<OfflineVideo> { it.isWatched }
                        .thenBy { allVideos.indexOf(it) }
                )
            }

            SORT_DURATION_DESC -> {
                filtered.sortedByDescending { it.duration }
            }

            SORT_DURATION_ASC -> {
                filtered.sortedBy { it.duration }
            }

            SORT_TITLE -> {
                filtered.sortedBy { it.title.lowercase() }
            }

            SORT_RESUME -> {
                filtered.sortedByDescending { progressById[it.id] ?: 0L }
            }

            else -> {
                filtered
            }
        }

        displayedVideos.clear()
        displayedVideos.addAll(filtered)

        if (allVideos.isEmpty()) {
            showStatus(
                getString(R.string.offline_library_empty),
                getString(R.string.offline_library_empty_detail)
            )
        } else if (displayedVideos.isEmpty()) {
            showStatus(
                getString(R.string.sources_empty),
                ""
            )
        } else {
            hideStatus()
            adapter.submit(displayedVideos)
        }
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

    private fun play(video: OfflineVideo, fromStart: Boolean = false) {
        try {
            val intent = OfflinePlayerActivity.intent(requireContext(), video.id, video.accountId)
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

    private fun deleteDownload(video: OfflineVideo) {
        disposables.add(
            io.reactivex.rxjava3.core.Single.fromCallable {
                client.deleteLibrary(video.accountId, video.id)
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

                val watched = video.isWatched
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

                itemBinding.root.setOnClickListener { play(video) }

                itemBinding.offlineItemMenu.setOnClickListener {
                    showItemPopupMenu(itemBinding.offlineItemMenu, video)
                }

                itemBinding.root.setOnLongClickListener {
                    showItemPopupMenu(itemBinding.offlineItemMenu, video)
                    true
                }
            }

            private fun showItemPopupMenu(anchor: View, video: OfflineVideo) {
                val popup = PopupMenu(requireContext(), anchor)
                popup.menu.add(0, 1, 0, getString(R.string.offline_play))
                popup.menu.add(0, 2, 1, getString(R.string.offline_play_from_start))
                if (!video.isWatched) {
                    popup.menu.add(0, 3, 2, getString(R.string.offline_mark_watched))
                }
                popup.menu.add(0, 4, 3, getString(R.string.offline_remove_from_library))

                popup.setOnMenuItemClickListener { menuItem ->
                    when (menuItem.itemId) {
                        1 -> play(video, false)
                        2 -> play(video, true)
                        3 -> markWatched(video)
                        4 -> deleteDownload(video)
                    }
                    true
                }
                popup.show()
            }

            private fun buildMeta(video: OfflineVideo): String {
                val minutes = video.duration / 60
                val seconds = video.duration % 60
                val length = String.format("%d:%02d", minutes, seconds)
                val source = when (video.accountId) {
                    "bilibili" -> getString(R.string.offline_source_bilibili)
                    "youtube" -> getString(R.string.offline_source_youtube)
                    else -> video.accountId
                }
                val watchedTag = if (video.isWatched) {
                    " · " + getString(R.string.offline_watched_badge)
                } else {
                    ""
                }
                val resume = progressById[video.id] ?: 0L
                return if (resume > 0) {
                    "$length · $source · " + getString(
                        R.string.offline_resume_at,
                        formatSeconds(resume)
                    ) + watchedTag
                } else {
                    "$length · $source$watchedTag"
                }
            }
        }
    }
}
