package com.booxin.launcher.ui.community

import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.booxin.launcher.AppContainer
import com.booxin.launcher.R
import com.booxin.launcher.data.model.CommunityContentType
import com.booxin.launcher.data.model.CommunityLoader
import com.booxin.launcher.data.model.InstallTargetRecommendation
import com.booxin.launcher.data.model.ModrinthProject
import com.booxin.launcher.data.model.ModrinthProjectVersion
import com.booxin.launcher.databinding.FragmentCommunityBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

class CommunityFragment : Fragment() {

    private var _binding: FragmentCommunityBinding? = null
    private val binding get() = _binding!!

    private val adapter = CommunityAdapter(::openProject)
    private var contentType: CommunityContentType = CommunityContentType.MOD
    private var loader: CommunityLoader = CommunityLoader.ANY

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

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                AppContainer.repository.session.collect {
                    binding.textSelectedVersion.text = getString(
                        R.string.community_selected_version,
                        it.selectedVersionId ?: getString(R.string.community_no_selected_version)
                    )
                }
            }
        }

        performSearch()
    }

    private fun setupSearch() {
        binding.buttonSearch.setOnClickListener { performSearch() }
        binding.editSearch.setOnEditorActionListener { _, actionId, event ->
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_SEARCH || enter) {
                performSearch()
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
                performSearch()
            }
        }
        binding.chipResourcePacks.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                contentType = CommunityContentType.RESOURCE_PACK
                refreshLoaderVisibility()
                performSearch()
            }
        }
        binding.chipModpacks.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                contentType = CommunityContentType.MODPACK
                refreshLoaderVisibility()
                performSearch()
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
                        performSearch()
                    }
                }
            }
        }
        refreshLoaderVisibility()
    }

    private fun refreshLoaderVisibility() {
        val show = contentType == CommunityContentType.MOD
        binding.scrollLoaders.isVisible = show
        binding.textHint.text = when (contentType) {
            CommunityContentType.MOD ->
                getString(R.string.community_hint_mods)
            CommunityContentType.RESOURCE_PACK ->
                getString(R.string.community_hint_resourcepacks)
            CommunityContentType.MODPACK ->
                getString(R.string.community_hint_modpacks)
        }
    }

    private fun performSearch() {
        viewLifecycleOwner.lifecycleScope.launch {
            showLoading(getString(R.string.community_loading))
            val query = binding.editSearch.text?.toString().orEmpty().trim()
            val result = AppContainer.communityRepository.searchProjects(query, contentType, loader)
            hideLoading()
            val items = result.getOrElse { err ->
                adapter.submit(emptyList())
                binding.textEmpty.isVisible = true
                Toast.makeText(
                    requireContext(),
                    getString(R.string.community_search_failed, err.message ?: "unknown"),
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            adapter.submit(items)
            binding.textEmpty.isVisible = items.isEmpty()
        }
    }

    private fun openProject(project: ModrinthProject) {
        viewLifecycleOwner.lifecycleScope.launch {
            showLoading(getString(R.string.community_loading_versions))
            val versions = AppContainer.communityRepository.getProjectVersions(project.id).getOrElse { err ->
                hideLoading()
                Toast.makeText(
                    requireContext(),
                    getString(R.string.community_versions_failed, err.message ?: "unknown"),
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            hideLoading()
            if (versions.isEmpty()) {
                Toast.makeText(requireContext(), R.string.community_versions_empty, Toast.LENGTH_SHORT).show()
                return@launch
            }
            showVersionPicker(project, versions)
        }
    }

    private fun showVersionPicker(
        project: ModrinthProject,
        versions: List<ModrinthProjectVersion>
    ) {
        val selected = AppContainer.repository.session.value.selectedVersionId
        val sorted = versions.sortedWith(
            compareByDescending<ModrinthProjectVersion> {
                selected != null && AppContainer.communityRepository.recommendTargets(contentType, it)
                    .any { target -> target.versionId == selected }
            }.thenByDescending { it.datePublished.orEmpty() }
        )
        val labels = sorted.map { version ->
            val recommend = AppContainer.communityRepository.recommendTargets(contentType, version)
                .firstOrNull { it.recommended }
                ?.let { " · ${getString(R.string.community_recommended_short, it.versionId)}" }
                .orEmpty()
            buildString {
                append(version.name.ifBlank { version.versionNumber })
                if (version.gameVersions.isNotEmpty()) {
                    append(" · ")
                    append(version.gameVersions.take(2).joinToString("/"))
                }
                if (version.loaders.isNotEmpty()) {
                    append(" · ")
                    append(version.loaders.take(2).joinToString("/"))
                }
                append(recommend)
            }
        }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(project.title)
            .setItems(labels) { _, which ->
                val version = sorted[which]
                when (contentType) {
                    CommunityContentType.MOD, CommunityContentType.RESOURCE_PACK ->
                        showTargetPicker(version)
                    CommunityContentType.MODPACK ->
                        installModpack(version)
                }
            }
            .show()
    }

    private fun showTargetPicker(version: ModrinthProjectVersion) {
        val targets = AppContainer.communityRepository.recommendTargets(contentType, version)
        if (targets.isEmpty()) {
            Toast.makeText(requireContext(), R.string.community_no_compatible_target, Toast.LENGTH_LONG).show()
            return
        }
        val labels = targets.map { target ->
            val prefix = if (target.recommended) {
                getString(R.string.community_recommended_prefix)
            } else {
                getString(R.string.community_target_prefix)
            }
            "$prefix ${target.versionId}\n${target.reason}"
        }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.community_pick_target)
            .setItems(labels) { _, which ->
                installIntoTarget(targets[which], version)
            }
            .show()
    }

    private fun installIntoTarget(
        target: InstallTargetRecommendation,
        version: ModrinthProjectVersion
    ) {
        viewLifecycleOwner.lifecycleScope.launch {
            showLoading(getString(R.string.community_installing_target, target.versionId))
            val result = AppContainer.communityRepository.installVersionFile(
                contentType = contentType,
                targetVersionId = target.versionId,
                version = version
            )
            hideLoading()
            result.fold(
                onSuccess = {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.community_install_done, it.name, target.versionId),
                        Toast.LENGTH_SHORT
                    ).show()
                },
                onFailure = { err ->
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.community_install_failed, err.message ?: "unknown"),
                        Toast.LENGTH_LONG
                    ).show()
                }
            )
        }
    }

    private fun installModpack(version: ModrinthProjectVersion) {
        val compatibleTargets = AppContainer.communityRepository.recommendTargets(
            CommunityContentType.MODPACK,
            version
        )
        val options = compatibleTargets.map { it.versionId } + getString(R.string.community_auto_create_target)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.community_pick_target)
            .setItems(options.toTypedArray()) { _, which ->
                val target = compatibleTargets.getOrNull(which)?.versionId
                doInstallModpack(target, version)
            }
            .show()
    }

    private fun doInstallModpack(targetVersionId: String?, version: ModrinthProjectVersion) {
        viewLifecycleOwner.lifecycleScope.launch {
            showLoading(getString(R.string.community_installing_modpack))
            val result = AppContainer.communityRepository.installModpack(targetVersionId, version)
            hideLoading()
            result.fold(
                onSuccess = { target ->
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.community_modpack_done, target),
                        Toast.LENGTH_LONG
                    ).show()
                },
                onFailure = { err ->
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.community_install_failed, err.message ?: "unknown"),
                        Toast.LENGTH_LONG
                    ).show()
                }
            )
        }
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
        super.onDestroyView()
        _binding = null
    }
}
