package com.booxin.launcher.ui.community

import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import coil.load
import com.booxin.launcher.AppContainer
import com.booxin.launcher.BooxinApp
import com.booxin.launcher.R
import com.booxin.launcher.core.community.CommunityDescriptionTranslator
import com.booxin.launcher.core.community.CommunityInstallHub
import com.booxin.launcher.core.download.game.VersionJsonMerger
import com.booxin.launcher.data.model.CommunityContentType
import com.booxin.launcher.data.model.CommunityLoader
import com.booxin.launcher.data.model.GameVersion
import com.booxin.launcher.data.model.InstallTargetRecommendation
import com.booxin.launcher.data.model.ModpackInstallProgress
import com.booxin.launcher.data.model.ModrinthProject
import com.booxin.launcher.data.model.ModrinthProjectVersion
import com.booxin.launcher.data.model.ModrinthResolvedDependency
import com.booxin.launcher.databinding.FragmentCommunityProjectDetailBinding
import com.booxin.launcher.databinding.ItemCommunityDependencyBinding
import com.google.android.material.color.MaterialColors
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
    private var translateJob: Job? = null

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
        binding.buttonDownload.setOnClickListener { onDownloadClicked() }
        binding.buttonExpandDescription.setOnClickListener { toggleDescription() }
        binding.textDescription.movementMethod = LinkMovementMethod.getInstance()
        binding.buttonDownload.setText(downloadButtonLabel())
        updateDependenciesSectionVisibility()
        loadDetail()
    }

    private fun downloadButtonLabel(): Int = when (contentType) {
        CommunityContentType.MOD -> R.string.community_download_mod
        CommunityContentType.SHADER -> R.string.community_download_shader
        CommunityContentType.RESOURCE_PACK -> R.string.community_download_resourcepack
        CommunityContentType.MODPACK -> R.string.community_download_modpack
    }

    private fun updateDependenciesSectionVisibility() {
        val b = _binding ?: return
        val show = contentType == CommunityContentType.MOD
        b.textDependenciesSection.isVisible = show
        b.textDependenciesHint.isVisible = show
        b.layoutDependencies.isVisible = show
        b.textDependenciesEmpty.isVisible = show
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
            binding.buttonDownload.isEnabled = false
            binding.textVersionsEmpty.isVisible = true
            binding.textVersionsEmpty.text = getString(R.string.community_loading_versions)
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
            val prepared = withContext(Dispatchers.Default) {
                prepareVersionRows(loadedVersions)
            }
            if (_binding == null) return@launch
            versions = prepared.map { it.first }
            selectedVersion = pickDefaultVersion(prepared)
            binding.buttonDownload.isEnabled = versions.isNotEmpty()
            binding.textVersionsEmpty.isVisible = versions.isEmpty()
            binding.textVersionsEmpty.text = getString(R.string.community_versions_empty)
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
        val source = item.body?.trim().orEmpty().ifBlank { item.description.trim() }
            .ifBlank { getString(R.string.community_no_description) }
            .let { stripSimpleMarkdown(it) }
        fullDescription = source
        descriptionExpanded = false
        applyDescriptionState()
        val status = b.textTranslateStatus
        if (!CommunityDescriptionTranslator.needsTranslate(source)) {
            status.isVisible = false
            return
        }
        status.isVisible = true
        status.setText(R.string.community_translating)
        translateJob?.cancel()
        translateJob = viewLifecycleOwner.lifecycleScope.launch {
            val zh = CommunityDescriptionTranslator.translate(source)
            if (_binding == null || project?.id != item.id) return@launch
            fullDescription = zh
            applyDescriptionState()
            val ui = _binding ?: return@launch
            if (zh != source) {
                ui.textTranslateStatus.isVisible = true
                ui.textTranslateStatus.setText(R.string.community_translated_badge)
            } else {
                ui.textTranslateStatus.isVisible = false
            }
        }
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
        val installedMc = AppContainer.repository.installedVersions.value
            .map {
                com.booxin.launcher.core.download.game.VersionJsonMerger.resolveMinecraftVersionId(it.id)
            }
            .filter { it.isNotBlank() }
            .toSet()
        val sorted = list.sortedWith(
            compareByDescending<ModrinthProjectVersion> {
                selected != null && labels[it.id] == selected
            }.thenBy { versionStabilityRank(it.versionType) }
                .thenByDescending { !labels[it.id].isNullOrBlank() }
                .thenByDescending { version ->
                    version.gameVersions.any { it in installedMc }
                }
                .thenByDescending { it.datePublished.orEmpty() }
        )
        return sorted.take(MAX_VERSIONS_SHOWN).map { version ->
            version to labels[version.id]
        }
    }

    /** Prefer release over beta/alpha so “default” is not always the newest pre-release. */
    private fun versionStabilityRank(type: String): Int = when (type.lowercase(Locale.US)) {
        "release" -> 0
        "beta" -> 1
        "alpha" -> 2
        else -> 3
    }

    private fun pickDefaultVersion(
        rows: List<Pair<ModrinthProjectVersion, String?>>
    ): ModrinthProjectVersion? {
        if (rows.isEmpty()) return null
        val selectedId = AppContainer.repository.session.value.selectedVersionId
        val selectedLoader = selectedId?.let { CommunityLoader.fromVersionId(it) }
        val selectedMc = selectedId?.let {
            runCatching { VersionJsonMerger.resolveMinecraftVersionId(it) }.getOrNull()
        }
        return rows.maxByOrNull { (version, targetId) ->
            var score = 0
            if (targetId != null && selectedId != null && targetId == selectedId) score += 100
            if (version.versionType.equals("release", ignoreCase = true)) score += 20
            if (targetId != null) score += 10
            if (versionMatchesLoader(version, selectedLoader)) score += 50
            if (selectedMc != null && selectedMc in version.gameVersions) score += 30
            score
        }?.first ?: rows.first().first
    }

    private fun versionMatchesLoader(
        version: ModrinthProjectVersion,
        loader: CommunityLoader?
    ): Boolean {
        if (loader == null || loader == CommunityLoader.ANY) return true
        val normalized = version.loaders.map { it.lowercase() }
        return when (loader) {
            CommunityLoader.FORGE -> "forge" in normalized
            CommunityLoader.NEOFORGE -> "neoforge" in normalized
            CommunityLoader.FABRIC -> "fabric" in normalized
            CommunityLoader.QUILT -> "quilt" in normalized
            CommunityLoader.ANY -> true
        }
    }

    private fun onDownloadClicked() {
        val preferred = selectedVersion
        if (versions.isEmpty()) {
            Toast.makeText(requireContext(), R.string.community_versions_empty, Toast.LENGTH_SHORT).show()
            return
        }
        when (contentType) {
            CommunityContentType.MOD,
            CommunityContentType.SHADER,
            CommunityContentType.RESOURCE_PACK -> showLocalTargetThenRemote(preferredRemote = preferred)
            CommunityContentType.MODPACK -> pickModpackVersionThenInstall(preferred)
        }
    }

    /** Pick a remote modpack file version, then always create a new local instance. */
    private fun pickModpackVersionThenInstall(preferredRemote: ModrinthProjectVersion?) {
        if (versions.isEmpty()) {
            Toast.makeText(requireContext(), R.string.community_versions_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val ordered = buildList {
            val pref = preferredRemote?.takeIf { p -> versions.any { it.id == p.id } }
            if (pref != null) add(pref)
            versions.filter { pref == null || it.id != pref.id }.forEach { add(it) }
        }
        val labels = ordered.map { version ->
            val number = version.versionNumber.ifBlank { version.name }
            val meta = buildList {
                add(version.versionType)
                addAll(version.loaders.take(2))
                addAll(version.gameVersions.take(3))
            }.joinToString(" · ")
            "$number\n$meta"
        }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.community_pick_modpack_version)
            .setItems(labels) { _, which ->
                val version = ordered[which]
                selectedVersion = version
                val defaultName = buildString {
                    append(
                        project?.title?.trim().orEmpty()
                            .ifBlank { version.name.ifBlank { version.versionNumber } }
                            .ifBlank { "整合包" }
                    )
                    val ver = version.versionNumber.trim()
                    if (ver.isNotEmpty() && !contains(ver)) {
                        append(' ')
                        append(ver)
                    }
                }
                doInstallModpack(version, defaultName)
            }
            .show()
    }

    private fun refreshDependencies() {
        val b = _binding ?: return
        if (contentType != CommunityContentType.MOD) {
            b.layoutDependencies.removeAllViews()
            return
        }
        val version = selectedVersion
        if (version == null) {
            bindDependencies(emptyList())
            b.textDependenciesHint.text = getString(R.string.community_dependencies_hint)
            return
        }
        b.textDependenciesHint.text = getString(
            R.string.community_dependencies_for_version,
            version.name.ifBlank { version.versionNumber }
        )
        depsJob?.cancel()
        depsJob = viewLifecycleOwner.lifecycleScope.launch {
            val result = AppContainer.communityRepository.resolveRequiredDependencies(version)
            if (_binding == null) return@launch
            result.fold(
                onSuccess = { deps -> bindDependencies(deps) },
                onFailure = { err ->
                    bindDependencies(emptyList())
                    if (isAdded) {
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.community_dependencies_failed, err.message ?: "unknown"),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            )
        }
    }

    private fun bindDependencies(deps: List<ModrinthResolvedDependency>) {
        val b = _binding ?: return
        b.layoutDependencies.removeAllViews()
        b.textDependenciesEmpty.isVisible = deps.isEmpty()
        if (deps.isEmpty()) return
        val inflater = LayoutInflater.from(requireContext())
        deps.forEach { dep ->
            val item = ItemCommunityDependencyBinding.inflate(inflater, b.layoutDependencies, false)
            item.textTitle.text = dep.title
            CommunityDescriptionTranslator.bind(
                item.textDescription,
                dep.description,
                viewLifecycleOwner.lifecycleScope
            )
            item.imageIcon.load(dep.iconUrl) {
                crossfade(true)
                placeholder(R.drawable.ic_nav_versions)
                error(R.drawable.ic_nav_versions)
            }
            item.root.setOnClickListener {
                if (!isAdded || _binding == null) return@setOnClickListener
                findNavController().navigate(
                    R.id.action_community_project_detail_self,
                    bundleOf(
                        ARG_PROJECT_ID to dep.projectId,
                        ARG_CONTENT_TYPE to CommunityContentType.MOD.name
                    )
                )
            }
            b.layoutDependencies.addView(item.root)
        }
    }

    private data class LocalTargetRow(
        val version: GameVersion,
        val vanilla: Boolean,
        val enabled: Boolean,
        val label: String
    )

    /** Step 1: pick local install target → Step 2: pick compatible remote file version. */
    private fun showLocalTargetThenRemote(preferredRemote: ModrinthProjectVersion?) {
        val locals = AppContainer.repository.installedVersions.value
        if (locals.isEmpty()) {
            Toast.makeText(requireContext(), R.string.community_no_local_target, Toast.LENGTH_LONG).show()
            return
        }
        val selectedId = AppContainer.repository.session.value.selectedVersionId
        val modsNeedLoader = contentType == CommunityContentType.MOD
        val rows = locals.map { item ->
            val vanilla = CommunityLoader.fromVersionId(item.id) == CommunityLoader.ANY
            val compatibleFiles = AppContainer.communityRepository.filterCompatibleVersions(
                item.id,
                contentType,
                versions
            )
            val enabled = when {
                modsNeedLoader && vanilla -> false
                compatibleFiles.isEmpty() -> false
                else -> true
            }
            val prefix = when {
                vanilla -> getString(R.string.community_target_vanilla_prefix)
                item.id == selectedId -> getString(R.string.community_recommended_prefix)
                else -> getString(R.string.community_target_prefix)
            }
            val suffix = when {
                vanilla && modsNeedLoader -> "\n${getString(R.string.community_vanilla_cannot_mod)}"
                !enabled && !vanilla -> "\n${getString(R.string.community_target_loader_mismatch)}"
                else -> ""
            }
            LocalTargetRow(
                version = item,
                vanilla = vanilla,
                enabled = enabled,
                label = "$prefix ${item.id}$suffix"
            )
        }.sortedWith(
            // Compatible loader instances first; vanilla / mismatched last (gray).
            compareBy<LocalTargetRow> { !it.enabled }
                .thenBy { it.vanilla }
                .thenByDescending { it.enabled && it.version.id == selectedId }
                .thenBy { it.version.id }
        )
        if (modsNeedLoader && rows.none { it.enabled }) {
            Toast.makeText(requireContext(), R.string.community_no_modloader_target, Toast.LENGTH_LONG).show()
            return
        }
        if (rows.none { it.enabled }) {
            Toast.makeText(requireContext(), R.string.community_no_compatible_target, Toast.LENGTH_LONG).show()
            return
        }
        val ctx = requireContext()
        val enabledColor = MaterialColors.getColor(
            ctx,
            com.google.android.material.R.attr.colorOnSurface,
            0xFF1C1B1F.toInt()
        )
        val disabledColor = MaterialColors.getColor(
            ctx,
            com.google.android.material.R.attr.colorOnSurfaceVariant,
            0xFF79747E.toInt()
        )
        val adapter = object : ArrayAdapter<String>(
            ctx,
            android.R.layout.simple_list_item_1,
            rows.map { it.label }
        ) {
            override fun isEnabled(position: Int): Boolean = rows[position].enabled
            override fun areAllItemsEnabled(): Boolean = rows.all { it.enabled }
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                val text = view.findViewById<TextView>(android.R.id.text1)
                val row = rows[position]
                text.setTextColor(if (row.vanilla || !row.enabled) disabledColor else enabledColor)
                text.alpha = when {
                    !row.enabled -> 0.55f
                    row.vanilla -> 0.72f
                    else -> 1f
                }
                return view
            }
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.community_pick_target)
            .setAdapter(adapter) { _, which ->
                val row = rows.getOrNull(which) ?: return@setAdapter
                if (!row.enabled) {
                    Toast.makeText(
                        ctx,
                        if (row.vanilla) R.string.community_vanilla_cannot_mod
                        else R.string.community_target_loader_mismatch,
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setAdapter
                }
                showRemoteVersionPicker(row.version.id, preferredRemote)
            }
            .show()
    }

    private fun showRemoteVersionPicker(
        targetVersionId: String,
        preferredRemote: ModrinthProjectVersion?
    ) {
        val compatible = AppContainer.communityRepository.filterCompatibleVersions(
            targetVersionId,
            contentType,
            versions
        )
        if (compatible.isEmpty()) {
            Toast.makeText(
                requireContext(),
                getString(R.string.community_no_compatible_remote, targetVersionId),
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val ordered = buildList {
            val pref = preferredRemote?.takeIf { p -> compatible.any { it.id == p.id } }
            if (pref != null) add(pref)
            compatible.filter { pref == null || it.id != pref.id }.forEach { add(it) }
        }
        val labels = ordered.map { version ->
            val number = version.versionNumber.ifBlank { version.name }
            val meta = buildList {
                add(version.versionType)
                addAll(version.loaders.take(2))
                addAll(version.gameVersions.take(3))
            }.joinToString(" · ")
            "$number\n$meta"
        }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.community_pick_remote_version)
            .setItems(labels) { _, which ->
                val version = ordered[which]
                selectedVersion = version
                refreshDependencies()
                installIntoTarget(
                    InstallTargetRecommendation(
                        versionId = targetVersionId,
                        reason = "",
                        recommended = true
                    ),
                    version
                )
            }
            .show()
    }

    private fun installIntoTarget(
        target: InstallTargetRecommendation,
        version: ModrinthProjectVersion
    ) {
        val title = project?.title?.ifBlank { version.name } ?: version.name
        val jobId = CommunityInstallHub.begin(title)
        // Survive leaving this page (same pattern as DownloadFragment.runInstallJob).
        AppContainer.appScope.launch {
            withContext(Dispatchers.Main) {
                _binding?.let {
                    showLoading(getString(R.string.community_installing_target, target.versionId))
                }
            }
            var lastUiAt = 0L
            val result = AppContainer.communityRepository.installVersionFile(
                contentType = contentType,
                targetVersionId = target.versionId,
                version = version,
                onProgress = { p ->
                    CommunityInstallHub.update(jobId, p)
                    val now = System.currentTimeMillis()
                    if (p.bytesDownloaded >= 0L && now - lastUiAt < 200L) return@installVersionFile
                    lastUiAt = now
                    val line = formatInstallProgress(p)
                    AppContainer.appScope.launch(Dispatchers.Main.immediate) {
                        if (_binding == null) return@launch
                        showLoading(line)
                    }
                }
            )
            withContext(Dispatchers.Main) {
                _binding?.let { hideLoading() }
                val appCtx = BooxinApp.getAppContext()
                result.fold(
                    onSuccess = {
                        CommunityInstallHub.succeed(jobId, it.name)
                        Toast.makeText(
                            appCtx,
                            appCtx.getString(
                                R.string.community_install_done,
                                it.name,
                                target.versionId
                            ),
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    onFailure = { err ->
                        CommunityInstallHub.fail(jobId, err.message ?: "unknown")
                        Toast.makeText(
                            appCtx,
                            appCtx.getString(
                                R.string.community_install_failed,
                                err.message ?: "unknown"
                            ),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                )
            }
        }
    }

    private fun doInstallModpack(version: ModrinthProjectVersion, instanceDisplayName: String) {
        val jobId = CommunityInstallHub.begin(instanceDisplayName)
        AppContainer.appScope.launch {
            withContext(Dispatchers.Main) {
                _binding?.let {
                    showLoading(getString(R.string.community_installing_modpack))
                }
            }
            var lastUiAt = 0L
            val result = AppContainer.communityRepository.installModpack(
                requestedTargetVersionId = null,
                version = version,
                instanceDisplayName = instanceDisplayName,
                forceNewInstance = true,
                onProgress = { p ->
                    CommunityInstallHub.update(jobId, p)
                    val now = System.currentTimeMillis()
                    if (p.bytesDownloaded >= 0L && now - lastUiAt < 200L) return@installModpack
                    lastUiAt = now
                    val line = formatInstallProgress(p)
                    AppContainer.appScope.launch(Dispatchers.Main.immediate) {
                        if (_binding == null) return@launch
                        showLoading(line)
                    }
                }
            )
            withContext(Dispatchers.Main) {
                _binding?.let { hideLoading() }
                val appCtx = BooxinApp.getAppContext()
                result.fold(
                    onSuccess = { target ->
                        CommunityInstallHub.succeed(jobId, target)
                        Toast.makeText(
                            appCtx,
                            appCtx.getString(R.string.community_modpack_done, target),
                            Toast.LENGTH_LONG
                        ).show()
                    },
                    onFailure = { err ->
                        CommunityInstallHub.fail(jobId, err.message ?: "unknown")
                        Toast.makeText(
                            appCtx,
                            appCtx.getString(
                                R.string.community_install_failed,
                                err.message ?: "unknown"
                            ),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                )
            }
        }
    }

    private fun formatInstallProgress(p: ModpackInstallProgress): String {
        return buildString {
            append(p.stage)
            if (p.total > 0) append(" (${p.current}/${p.total})")
            if (p.bytesTotal > 0L) {
                append(" · ")
                append("%.1f".format(p.bytesDownloaded.coerceAtLeast(0L) / 1048576.0))
                append('/')
                append("%.1f".format(p.bytesTotal / 1048576.0))
                append(" MB")
            } else if (p.bytesDownloaded > 0L) {
                append(" · ")
                append("%.1f".format(p.bytesDownloaded / 1048576.0))
                append(" MB")
            }
            if (p.detail.isNotBlank()) {
                append('\n')
                append(p.detail)
            }
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
        translateJob?.cancel()
        loadJob = null
        depsJob = null
        translateJob = null
        _binding = null
        super.onDestroyView()
    }

    companion object {
        const val ARG_PROJECT_ID = "projectId"
        const val ARG_CONTENT_TYPE = "contentType"
        private const val MAX_VERSIONS_SHOWN = 200
    }
}
