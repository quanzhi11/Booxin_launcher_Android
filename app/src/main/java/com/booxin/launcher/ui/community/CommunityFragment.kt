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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.booxin.launcher.AppContainer
import com.booxin.launcher.R
import com.booxin.launcher.core.community.ModrinthClient
import com.booxin.launcher.data.model.CommunityContentType
import com.booxin.launcher.data.model.CommunityLoader
import com.booxin.launcher.data.model.ModrinthProject
import com.booxin.launcher.data.model.ModrinthSearchPage
import com.booxin.launcher.databinding.FragmentCommunityBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class CommunityFragment : Fragment() {

    private var _binding: FragmentCommunityBinding? = null
    private val binding get() = _binding!!

    private val adapter = CommunityAdapter(::openProject)
    private var contentType: CommunityContentType = CommunityContentType.MOD
    private var loader: CommunityLoader = CommunityLoader.ANY
    private var lastPage: ModrinthSearchPage? = null
    private var searchJob: Job? = null

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
        binding.recyclerProjects.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerProjects.adapter = adapter

        setupChips()
        setupSearch()
        binding.buttonMultiplayer.setOnClickListener {
            findNavController().navigate(R.id.nav_multiplayer)
        }
        binding.buttonPrevPage.setOnClickListener {
            val page = lastPage ?: return@setOnClickListener
            if (!page.hasPrevious) return@setOnClickListener
            performSearch((page.offset - page.limit).coerceAtLeast(0))
        }
        binding.buttonNextPage.setOnClickListener {
            val page = lastPage ?: return@setOnClickListener
            if (!page.hasNext) return@setOnClickListener
            performSearch(page.offset + page.limit)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                AppContainer.repository.session.collect { session ->
                    val b = _binding ?: return@collect
                    b.textSelectedVersion.text = getString(
                        R.string.community_selected_version,
                        session.selectedVersionId ?: getString(R.string.community_no_selected_version)
                    )
                }
            }
        }

        performSearch(0)
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

    private fun setupChips() {
        binding.chipMods.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                contentType = CommunityContentType.MOD
                refreshLoaderVisibility()
                performSearch(0)
            }
        }
        binding.chipResourcePacks.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                contentType = CommunityContentType.RESOURCE_PACK
                refreshLoaderVisibility()
                performSearch(0)
            }
        }
        binding.chipModpacks.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                contentType = CommunityContentType.MODPACK
                refreshLoaderVisibility()
                performSearch(0)
            }
        }
        listOf(
            binding.chipLoaderAny to CommunityLoader.ANY,
            binding.chipLoaderForge to CommunityLoader.FORGE,
            binding.chipLoaderNeoForge to CommunityLoader.NEOFORGE,
            binding.chipLoaderFabric to CommunityLoader.FABRIC,
            binding.chipLoaderQuilt to CommunityLoader.QUILT
        ).forEach { (chip, value) ->
            chip.setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    loader = value
                    if (contentType == CommunityContentType.MOD) {
                        performSearch(0)
                    }
                }
            }
        }
        refreshLoaderVisibility()
    }

    private fun refreshLoaderVisibility() {
        val b = _binding ?: return
        val show = contentType == CommunityContentType.MOD
        b.scrollLoaders.isVisible = show
        b.textHint.text = when (contentType) {
            CommunityContentType.MOD ->
                getString(R.string.community_hint_mods)
            CommunityContentType.RESOURCE_PACK ->
                getString(R.string.community_hint_resourcepacks)
            CommunityContentType.MODPACK ->
                getString(R.string.community_hint_modpacks)
        }
    }

    private fun performSearch(offset: Int) {
        val b = _binding ?: return
        val query = b.editSearch.text?.toString().orEmpty().trim()
        val type = contentType
        val selectedLoader = loader
        searchJob?.cancel()
        searchJob = viewLifecycleOwner.lifecycleScope.launch {
            showLoading(getString(R.string.community_loading))
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
            adapter.submit(page.projects)
            ui.textEmpty.isVisible = page.projects.isEmpty()
            updatePagination(page)
            if (page.projects.isNotEmpty()) {
                ui.recyclerProjects.scrollToPosition(0)
            }
        }
    }

    private fun updatePagination(page: ModrinthSearchPage?) {
        val b = _binding ?: return
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
        if (!isAdded || _binding == null) return
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
