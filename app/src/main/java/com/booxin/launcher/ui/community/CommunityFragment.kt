package com.booxin.launcher.ui.community

import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import com.booxin.launcher.AppContainer
import com.booxin.launcher.R
import com.booxin.launcher.core.community.CommunityDescriptionTranslator
import com.booxin.launcher.core.community.CommunityPrefs
import com.booxin.launcher.core.community.CommunitySearchQuery
import com.booxin.launcher.core.community.ModrinthClient
import com.booxin.launcher.data.model.CommunityContentType
import com.booxin.launcher.data.model.CommunityLoader
import com.booxin.launcher.data.model.ModrinthProject
import com.booxin.launcher.data.model.ModrinthSearchPage
import com.booxin.launcher.databinding.FragmentCommunityBinding
import com.booxin.launcher.ui.controller.ControllerNavBinder
import com.booxin.launcher.ui.skinstore.SkinLibraryFragment
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.coordinatorlayout.widget.CoordinatorLayout

class CommunityFragment : Fragment() {

    private var _binding: FragmentCommunityBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: CommunityAdapter
    private var contentType: CommunityContentType = CommunityContentType.MOD
    private var selectedLoader: CommunityLoader = CommunityLoader.ANY
    private var lastPage: ModrinthSearchPage? = null
    private var searchJob: Job? = null
    private var skinMode: Boolean = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCommunityBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adapter = CommunityAdapter(viewLifecycleOwner.lifecycleScope, ::openProject)
        val span = if (resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        ) {
            2
        } else {
            1
        }
        binding.recyclerProjects.layoutManager = GridLayoutManager(requireContext(), span)
        binding.recyclerProjects.adapter = adapter
        ControllerNavBinder.bindRecycler(binding.recyclerProjects)

        setupChips()
        setupLoaderChips()
        setupSearch()
        setupSkinToolbar()
        refreshLoaderToolbar()
        binding.buttonPrevPage.setOnClickListener {
            if (skinMode) {
                skinFragment()?.prevPage()
                return@setOnClickListener
            }
            val page = lastPage ?: return@setOnClickListener
            if (!page.hasPrevious) return@setOnClickListener
            performSearch((page.offset - page.limit).coerceAtLeast(0))
        }
        binding.buttonNextPage.setOnClickListener {
            if (skinMode) {
                skinFragment()?.nextPage()
                return@setOnClickListener
            }
            val page = lastPage ?: return@setOnClickListener
            if (!page.hasNext) return@setOnClickListener
            performSearch(page.offset + page.limit)
        }

        if (skinMode) {
            showSkinMode(true)
        } else {
            performSearch(0)
        }
        maybeShowFirstOpenGuide()
    }

    private fun maybeShowFirstOpenGuide() {
        val ctx = context ?: return
        if (CommunityPrefs.hasShownFirstOpenGuide(ctx)) return
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.community_guide_title)
            .setMessage(R.string.community_guide_message)
            .setPositiveButton(R.string.community_guide_ok) { _, _ ->
                CommunityPrefs.markFirstOpenGuideShown(ctx)
            }
            .setOnCancelListener {
                CommunityPrefs.markFirstOpenGuideShown(ctx)
            }
            .show()
    }

    private fun setupSearch() {
        binding.buttonSearch.setOnClickListener { performSearch(0) }
        binding.editSearch.setOnEditorActionListener { _, actionId, event ->
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_SEARCH || enter) {
                performSearch(0)
                true
            } else {
                false
            }
        }
    }

    private fun setupSkinToolbar() {
        binding.buttonSkinRefresh.setOnClickListener {
            skinFragment()?.refresh()
                ?: run {
                    ensureSkinFragment()
                }
        }
        binding.buttonSkinUpload.setOnClickListener {
            skinFragment()?.openUpload()
        }
        binding.chipSkinFilterAll.setOnCheckedChangeListener { _, checked ->
            if (checked) skinFragment()?.setModelFilter(null)
        }
        binding.chipSkinFilterClassic.setOnCheckedChangeListener { _, checked ->
            if (checked) skinFragment()?.setModelFilter("classic")
        }
        binding.chipSkinFilterSlim.setOnCheckedChangeListener { _, checked ->
            if (checked) skinFragment()?.setModelFilter("slim")
        }
    }

    private fun setupLoaderChips() {
        fun select(loader: CommunityLoader) {
            if (selectedLoader == loader) return
            selectedLoader = loader
            if (!skinMode) performSearch(0)
        }
        binding.chipLoaderAny.setOnCheckedChangeListener { _, checked ->
            if (checked) select(CommunityLoader.ANY)
        }
        binding.chipLoaderForge.setOnCheckedChangeListener { _, checked ->
            if (checked) select(CommunityLoader.FORGE)
        }
        binding.chipLoaderNeoForge.setOnCheckedChangeListener { _, checked ->
            if (checked) select(CommunityLoader.NEOFORGE)
        }
        binding.chipLoaderFabric.setOnCheckedChangeListener { _, checked ->
            if (checked) select(CommunityLoader.FABRIC)
        }
        binding.chipLoaderQuilt.setOnCheckedChangeListener { _, checked ->
            if (checked) select(CommunityLoader.QUILT)
        }
    }

    private fun refreshLoaderToolbar() {
        val b = _binding ?: return
        val show = !skinMode &&
            (contentType == CommunityContentType.MOD || contentType == CommunityContentType.MODPACK)
        b.loaderToolbar.isVisible = show
    }

    private fun setupChips() {
        binding.chipMods.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                contentType = CommunityContentType.MOD
                showSkinMode(false)
                refreshHints()
                refreshLoaderToolbar()
                performSearch(0)
            }
        }
        binding.chipShaders.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                contentType = CommunityContentType.SHADER
                showSkinMode(false)
                refreshHints()
                refreshLoaderToolbar()
                performSearch(0)
            }
        }
        binding.chipResourcePacks.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                contentType = CommunityContentType.RESOURCE_PACK
                showSkinMode(false)
                refreshHints()
                refreshLoaderToolbar()
                performSearch(0)
            }
        }
        binding.chipModpacks.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                contentType = CommunityContentType.MODPACK
                showSkinMode(false)
                refreshHints()
                refreshLoaderToolbar()
                performSearch(0)
            }
        }
        binding.chipSkins.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                showSkinMode(true)
                refreshHints()
                refreshLoaderToolbar()
            }
        }
        refreshHints()
    }

    private fun showSkinMode(enabled: Boolean) {
        skinMode = enabled
        val b = _binding ?: return
        b.skinHost.isVisible = enabled
        b.recyclerProjects.isVisible = !enabled
        b.textEmpty.isVisible = false
        b.paginationBar.isVisible = false
        b.filterAppBar.isVisible = true
        b.skinToolbar.isVisible = enabled
        b.loaderToolbar.isVisible = !enabled &&
            (contentType == CommunityContentType.MOD || contentType == CommunityContentType.MODPACK)
        if (enabled) {
            ensureSkinFragment()
            b.textHint.text = getString(R.string.community_hint_skins)
            b.filterAppBar.setExpanded(true, false)
            searchJob?.cancel()
            hideLoading()
            childFragmentManager.executePendingTransactions()
            val model = when {
                b.chipSkinFilterClassic.isChecked -> "classic"
                b.chipSkinFilterSlim.isChecked -> "slim"
                else -> null
            }
            skinFragment()?.applyCatalogFilters(
                query = b.editSearch.text?.toString().orEmpty(),
                model = model
            )
        } else {
            childFragmentManager.findFragmentByTag(SkinLibraryFragment.TAG)?.let {
                childFragmentManager.beginTransaction().remove(it).commitAllowingStateLoss()
            }
        }
    }

    /** 皮肤详情 / 上传页时隐藏社区搜索与分区芯片，并去掉 AppBar 顶部留白。 */
    fun setSkinSubPageActive(active: Boolean) {
        val b = _binding ?: return
        if (!skinMode) return
        b.filterAppBar.isVisible = !active
        if (!active) {
            b.filterAppBar.setExpanded(true, false)
        }
        val lp = b.communityContentHost.layoutParams as? CoordinatorLayout.LayoutParams ?: return
        lp.behavior = if (active) {
            null
        } else {
            AppBarLayout.ScrollingViewBehavior()
        }
        b.communityContentHost.layoutParams = lp
        b.communityContentHost.translationY = 0f
        b.communityContentHost.requestLayout()
        if (active) {
            b.paginationBar.isVisible = false
        }
    }

    fun onSkinPageChanged(
        pageNumber: Int,
        totalPages: Int,
        totalHits: Int,
        hasPrevious: Boolean,
        hasNext: Boolean
    ) {
        val b = _binding ?: return
        if (!skinMode) return
        if (totalHits <= 0 || totalPages <= 0) {
            b.paginationBar.isVisible = false
            return
        }
        b.paginationBar.isVisible = true
        b.buttonPrevPage.isEnabled = hasPrevious
        b.buttonNextPage.isEnabled = hasNext
        b.textPageInfo.text = getString(
            R.string.community_page_info,
            pageNumber,
            totalPages,
            totalHits
        )
    }

    private fun ensureSkinFragment() {
        val existing = childFragmentManager.findFragmentByTag(SkinLibraryFragment.TAG)
        if (existing != null) return
        childFragmentManager.beginTransaction()
            .replace(R.id.skinHost, SkinLibraryFragment(), SkinLibraryFragment.TAG)
            .commitNowAllowingStateLoss()
    }

    private fun skinFragment(): SkinLibraryFragment? =
        childFragmentManager.findFragmentByTag(SkinLibraryFragment.TAG) as? SkinLibraryFragment

    private fun refreshHints() {
        val b = _binding ?: return
        if (skinMode) {
            b.skinToolbar.isVisible = true
            b.textHint.text = getString(R.string.community_hint_skins)
            return
        }
        b.skinToolbar.isVisible = false
        b.textHint.text = when (contentType) {
            CommunityContentType.MOD ->
                getString(R.string.community_hint_mods) + "\n" +
                    getString(R.string.community_hint_translate)
            CommunityContentType.SHADER ->
                getString(R.string.community_hint_shaders)
            CommunityContentType.RESOURCE_PACK ->
                getString(R.string.community_hint_resourcepacks)
            CommunityContentType.MODPACK ->
                getString(R.string.community_hint_modpacks)
        }
    }

    private fun performSearch(offset: Int) {
        val b = _binding ?: return
        val query = b.editSearch.text?.toString().orEmpty().trim()
        if (skinMode) {
            skinFragment()?.search(query)
            b.filterAppBar.setExpanded(true, false)
            return
        }
        val type = contentType
        searchJob?.cancel()
        searchJob = viewLifecycleOwner.lifecycleScope.launch {
            val loadingMsg = if (CommunitySearchQuery.containsChinese(query)) {
                getString(R.string.community_loading_zh)
            } else {
                getString(R.string.community_loading)
            }
            showLoading(loadingMsg)
            val result = AppContainer.communityRepository.searchProjects(
                query = query,
                contentType = type,
                loader = selectedLoader,
                offset = offset,
                limit = ModrinthClient.PAGE_SIZE
            )
            val ui = _binding ?: return@launch
            hideLoading()
            val page = result.getOrElse { err ->
                lastPage = null
                adapter.submit(emptyList())
                ui.textEmpty.isVisible = true
                updatePagination(null)
                if (isAdded) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.community_search_failed, err.message ?: "unknown"),
                        Toast.LENGTH_LONG
                    ).show()
                }
                return@launch
            }
            lastPage = page
            CommunityDescriptionTranslator.prefetch(page.projects.map { it.description })
            adapter.submit(page.projects)
            ui.textEmpty.isVisible = page.projects.isEmpty()
            updatePagination(page)
            if (page.projects.isNotEmpty()) {
                ui.filterAppBar.setExpanded(true, false)
                ui.recyclerProjects.scrollToPosition(0)
            }
        }
    }

    private fun updatePagination(page: ModrinthSearchPage?) {
        val b = _binding ?: return
        if (skinMode) return
        if (page == null || page.projects.isEmpty()) {
            b.paginationBar.isVisible = false
            return
        }
        b.paginationBar.isVisible = true
        b.buttonPrevPage.isEnabled = page.hasPrevious
        b.buttonNextPage.isEnabled = page.hasNext
        b.textPageInfo.text = getString(
            R.string.community_page_info,
            page.pageNumber,
            page.totalPages,
            page.totalHits
        )
    }

    private fun openProject(project: ModrinthProject) {
        if (!isAdded || _binding == null || skinMode) return
        findNavController().navigate(
            R.id.action_community_to_project_detail,
            bundleOf(
                CommunityProjectDetailFragment.ARG_PROJECT_ID to project.id,
                CommunityProjectDetailFragment.ARG_CONTENT_TYPE to contentType.name
            )
        )
    }

    private fun showLoading(message: String) {
        val b = _binding ?: return
        b.progressPanel.isVisible = true
        b.textProgress.text = message
    }

    private fun hideLoading() {
        val b = _binding ?: return
        b.progressPanel.isVisible = false
    }

    override fun onDestroyView() {
        searchJob?.cancel()
        searchJob = null
        _binding = null
        super.onDestroyView()
    }
}
