package com.booxin.launcher.ui.community

import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import com.booxin.launcher.AppContainer
import com.booxin.launcher.R
import com.booxin.launcher.data.model.CommunityContentType
import com.booxin.launcher.data.model.InstallTargetRecommendation
import com.booxin.launcher.data.model.ModrinthProject
import com.booxin.launcher.data.model.ModrinthProjectVersion
import com.booxin.launcher.databinding.FragmentCommunityProjectDetailBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.NumberFormat
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CommunityProjectDetailFragment : Fragment() {

    private var _binding: FragmentCommunityProjectDetailBinding? = null
    private val binding get() = _binding!!

    private lateinit var contentType: CommunityContentType
    private var projectId: String = ""
    private var project: ModrinthProject? = null
    private var versions: List<ModrinthProjectVersion> = emptyList()
    private var selectedVersion: ModrinthProjectVersion? = null
    private var descriptionExpanded = false
    private var fullDescription: String = ""
    private var loadJob: Job? = null
    private var depsJob: Job? = null

    private val versionAdapter = CommunityVersionAdapter(
        onInstall = ::onInstallVersion,
        onSelect = ::onSelectVersion
    )
    private val dependencyAdapter = CommunityDependencyAdapter { dep ->
        if (!isAdded || _binding == null) return@CommunityDependencyAdapter
        findNavController().navigate(
            R.id.action_community_project_detail_self,
            bundleOf(
                ARG_PROJECT_ID to dep.projectId,
                ARG_CONTENT_TYPE to CommunityContentType.MOD.name
            )
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectId = requireArguments().getString(ARG_PROJECT_ID).orEmpty()
        contentType = runCatching {
            CommunityContentType.valueOf(
                requireArguments().getString(ARG_CONTENT_TYPE) ?: CommunityContentType.MOD.name
            )
        }.getOrDefault(CommunityContentType.MOD)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCommunityProjectDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.buttonBack.setOnClickListener { findNavController().navigateUp() }
        binding.recyclerVersions.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerVersions.adapter = versionAdapter
        binding.recyclerDependencies.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerDependencies.adapter = dependencyAdapter
        binding.buttonExpandDescription.setOnClickListener { toggleDescription() }
        binding.textDescription.movementMethod = LinkMovementMethod.getInstance()
        loadDetail()
    }

    private fun loadDetail() {
        if (projectId.isBlank()) {
            if (isAdded) {
                Toast.makeText(requireContext(), R.string.community_versions_failed, Toast.LENGTH_SHORT).show()
                findNavController().navigateUp()
            }
            return
        }
        loadJob?.cancel()
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            showLoading(getString(R.string.community_loading_detail))
            val repo = AppContainer.communityRepository
            // Fetch project + versions in parallel; paint project as soon as it arrives.
            val projectDeferred = async { repo.getProject(projectId) }
            val versionsDeferred = async { repo.getProjectVersions(projectId) }

            val loadedProject = projectDeferred.await().getOrElse { err ->
                if (_binding == null) return@launch
                hideLoading()
                if (isAdded) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.community_detail_failed, err.message ?: "unknown"),
                        Toast.LENGTH_LONG
                    ).show()
                    findNavController().navigateUp()
                }
                return@launch
            }
            if (_binding == null) return@launch
            project = loadedProject
            bindProject(loadedProject)
            // Keep a light versions placeholder while the (often larger) versions call finishes.
            binding.textVersionsEmpty.isVisible = true
            binding.textVersionsEmpty.text = getString(R.string.community_loading_versions)
            versionAdapter.submit(emptyList(), null)
            hideLoading()

            val loadedVersions = versionsDeferred.await().getOrElse { err ->
                if (_binding == null) return@launch
                binding.textVersionsEmpty.text = getString(
                    R.string.community_versions_failed,
                    err.message ?: "unknown"
                )
                if (isAdded) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.community_versions_failed, err.message ?: "unknown"),
                        Toast.LENGTH_LONG
                    ).show()
                }
                emptyList()
            }
            if (_binding == null) return@launch
            binding.textVersionsEmpty.text = getString(R.string.community_versions_empty)
            val prepared = withContext(Dispatchers.Default) {
                prepareVersionRows(loadedVersions)
            }
            if (_binding == null) return@launch
            versions = prepared.map { it.first }
            selectedVersion = versions.firstOrNull()
            bindVersionRows(
                prepared.map { (version, targetId) ->
                    version to targetId?.let { getString(R.string.community_recommended_short, it) }
                }
            )
            refreshDependencies()
        }
    }

    private fun bindProject(item: ModrinthProject) {
        val b = _binding ?: return
        b.textTitle.text = item.title
        b.textMeta.text = getString(
            R.string.community_project_meta,
            item.author.ifBlank { "unknown" },
            NumberFormat.getNumberInstance(Locale.getDefault()).format(item.downloads)
        )
        b.textTags.text = buildList {
            addAll(item.loaders.take(3))
            addAll(item.gameVersions.take(3))
            addAll(item.categories.take(3))
        }.distinct().joinToString(" · ")
        b.imageIcon.load(item.iconUrl) {
            crossfade(true)
            placeholder(R.drawable.ic_nav_versions)
            error(R.drawable.ic_nav_versions)
        }
        fullDescription = item.body?.trim().orEmpty().ifBlank { item.description.trim() }
            .ifBlank { getString(R.string.community_no_description) }
            .let { stripSimpleMarkdown(it) }
        descriptionExpanded = false
        applyDescriptionState()
    }

    private fun applyDescriptionState() {
        val b = _binding ?: return
        b.textDescription.text = fullDescription
        b.textDescription.maxLines = if (descriptionExpanded) Int.MAX_VALUE else 3
        val needsExpand = fullDescription.lines().size > 3 || fullDescription.length > 120
        b.buttonExpandDescription.isVisible = needsExpand
        b.buttonExpandDescription.setText(
            if (descriptionExpanded) R.string.community_collapse else R.string.community_expand
        )
    }

    private fun toggleDescription() {
        descriptionExpanded = !descriptionExpanded
        applyDescriptionState()
    }

    /** Returns version → recommended install target id (not display text). */
    private fun prepareVersionRows(
        list: List<ModrinthProjectVersion>
    ): List<Pair<ModrinthProjectVersion, String?>> {
        val selected = AppContainer.repository.session.value.selectedVersionId
        val labels = AppContainer.communityRepository.recommendLabels(contentType, list)
        val sorted = list.sortedWith(
            compareByDescending<ModrinthProjectVersion> {
                selected != null && labels[it.id] == selected
            }.thenByDescending { it.datePublished.orEmpty() }
        )
        // 限制列表长度。
        return sorted.take(MAX_VERSIONS_SHOWN).map { version ->
            version to labels[version.id]
        }
    }

    private fun bindVersionRows(rows: List<Pair<ModrinthProjectVersion, String?>>) {
        val b = _binding ?: return
        b.textVersionsEmpty.isVisible = rows.isEmpty()
        versionAdapter.submit(rows, selectedVersion?.id)
    }

    private fun onSelectVersion(version: ModrinthProjectVersion) {
        selectedVersion = version
        versionAdapter.select(version.id)
        refreshDependencies()
    }

    private fun refreshDependencies() {
        val b = _binding ?: return
        val version = selectedVersion
        if (version == null) {
            dependencyAdapter.submit(emptyList())
            b.textDependenciesEmpty.isVisible = true
            b.textDependenciesHint.text = getString(R.string.community_dependencies_hint)
            return
        }
        b.textDependenciesHint.text = getString(
            R.string.community_dependencies_for_version,
            version.name.ifBlank { version.versionNumber }
        )
        depsJob?.cancel()
        depsJob = viewLifecycleOwner.lifecycleScope.launch {
            val deps = AppContainer.communityRepository.resolveRequiredDependencies(version)
                .getOrElse { emptyList() }
            val ui = _binding ?: return@launch
            dependencyAdapter.submit(deps)
            ui.textDependenciesEmpty.isVisible = deps.isEmpty()
        }
    }

    private fun onInstallVersion(version: ModrinthProjectVersion) {
        selectedVersion = version
        versionAdapter.select(version.id)
        refreshDependencies()
        when (contentType) {
            CommunityContentType.MOD, CommunityContentType.RESOURCE_PACK -> showTargetPicker(version)
            CommunityContentType.MODPACK -> installModpack(version)
        }
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

    private fun stripSimpleMarkdown(text: String): String {
        return text
            .replace(Regex("!\\[[^\\]]*\\]\\([^)]*\\)"), "")
            .replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")
            .replace(Regex("^#{1,6}\\s*", RegexOption.MULTILINE), "")
            .replace(Regex("`{1,3}"), "")
            .replace(Regex("\\*{1,2}|_{1,2}"), "")
            .trim()
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
        loadJob?.cancel()
        depsJob?.cancel()
        loadJob = null
        depsJob = null
        _binding = null
        super.onDestroyView()
    }

    companion object {
        const val ARG_PROJECT_ID = "projectId"
        const val ARG_CONTENT_TYPE = "contentType"
        private const val MAX_VERSIONS_SHOWN = 40
    }
}
