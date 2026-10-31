package org.schabi.newpipe.local.offline

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.schedulers.Schedulers
import org.schabi.newpipe.R
import org.schabi.newpipe.databinding.FragmentSourcePageBinding
import org.schabi.newpipe.databinding.FragmentSourcesBinding
import org.schabi.newpipe.databinding.ItemSourceEntryBinding
import org.schabi.newpipe.util.image.CoilHelper

/**
 * Hosts one page per source. Each page is its own fragment so the pager can
 * keep page state across swipes, which a hand-rolled adapter cannot do without
 * re-owning the view hierarchy.
 */
class SourcesFragment : Fragment() {

    private var _binding: FragmentSourcesBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSourcesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (activity as? androidx.appcompat.app.AppCompatActivity)
            ?.supportActionBar?.title = getString(R.string.sources_title)
        binding.sourcesPager.adapter = SourceAdapter(this)
        TabLayoutMediator(binding.sourcesTabs, binding.sourcesPager) { tab, position ->
            tab.text = Sources.label(SourceAdapter.SOURCES[position])
        }.attach()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private class SourceAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {
        override fun getItemCount(): Int = SOURCES.size
        override fun createFragment(position: Int): Fragment = SourcePageFragment.newInstance(SOURCES[position])

        companion object {
            val SOURCES = listOf(Sources.YOUTUBE, Sources.BILIBILI)
        }
    }
}

/**
 * One source's watch-later list.
 *
 */
class SourcePageFragment : Fragment() {

    private var _binding: FragmentSourcePageBinding? = null
    private val binding get() = _binding!!

    private val client = VideodClient()
    private val disposables = CompositeDisposable()
    private val adapter = EntryAdapter(this)

    private val account: String get() = requireArguments().getString(ARG_ACCOUNT)!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSourcePageBinding.inflate(inflater, container, false)
        binding.sourceList.layoutManager = LinearLayoutManager(requireContext())
        binding.sourceList.adapter = adapter
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        reload()
    }

    override fun onDestroyView() {
        disposables.clear()
        _binding = null
        super.onDestroyView()
    }

    /** Read one source's already-synced Watch Later list. */
    fun reload() {
        showStatus(getString(R.string.sources_loading), "")
        disposables.clear()
        disposables.add(
            Single.fromCallable {
                val watchLater = client.watchLater(account)
                val downloaded = client.library(account).map { it.id }.toSet()
                watchLater to downloaded
            }
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    { (watchLater, downloaded) ->
                        if (watchLater.isEmpty()) {
                            showStatus(
                                getString(R.string.sources_empty),
                                getString(R.string.sources_empty_detail)
                            )
                        } else {
                            hideStatus()
                            adapter.submit(watchLater.map { Entry(it, it.id in downloaded) })
                        }
                    },
                    { error ->
                        showStatus(
                            getString(R.string.sources_unreachable),
                            error.message ?: error.javaClass.simpleName
                        )
                    }
                )
        )
    }

    /** Play an already-downloaded item through the same HTTP stream. */
    fun play(item: WatchLaterItem) {
        try {
            startActivity(
                OfflinePlayerActivity.intent(requireContext(), item.id, item.accountId)
            )
        } catch (e: Exception) {
            showStatus(getString(R.string.offline_library_no_player), e.message ?: "")
        }
    }

    private fun download(item: WatchLaterItem) {
        disposables.add(
            Single.fromCallable { client.download(account, item.id) }
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({ reload() }, { error -> showStatus(getString(R.string.sources_download_failed), error.message ?: "") })
        )
    }

    private fun showStatus(title: String, detail: String) {
        _binding ?: return
        binding.sourceStatus.visibility = View.VISIBLE
        binding.sourceStatusTitle.text = title
        binding.sourceStatusDetail.text = detail
        binding.sourceList.visibility = View.GONE
    }

    private fun hideStatus() {
        binding.sourceStatus.visibility = View.GONE
        binding.sourceList.visibility = View.VISIBLE
    }

    data class Entry(val item: WatchLaterItem, val downloaded: Boolean)

    private class EntryAdapter(private val host: SourcePageFragment) :
        RecyclerView.Adapter<EntryAdapter.Holder>() {
        private val items = ArrayList<Entry>()

        fun submit(entries: List<Entry>) {
            items.clear()
            items.addAll(entries)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val itemBinding = ItemSourceEntryBinding.inflate(
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

        inner class Holder(private val b: ItemSourceEntryBinding) :
            RecyclerView.ViewHolder(b.root) {
            fun bind(entry: Entry) {
                b.sourceItemTitle.text = entry.item.title
                b.sourceItemDuration.text = formatDuration(entry.item.duration)
                b.sourceItemDuration.visibility =
                    if (entry.item.duration > 0) View.VISIBLE else View.GONE
                CoilHelper.loadThumbnail(b.sourceItemThumbnail, entry.item.thumbnail)
                b.sourceItemMeta.text = if (entry.downloaded) {
                    host.getString(R.string.sources_downloaded)
                } else {
                    host.getString(R.string.sources_tap_to_download)
                }
                b.root.setOnClickListener {
                    if (entry.downloaded) {
                        host.play(entry.item)
                    } else {
                        host.download(entry.item)
                    }
                }
            }
        }
    }

    companion object {
        private const val ARG_ACCOUNT = "account"

        fun newInstance(account: String) = SourcePageFragment().apply {
            arguments = Bundle().apply { putString(ARG_ACCOUNT, account) }
        }
    }
}
