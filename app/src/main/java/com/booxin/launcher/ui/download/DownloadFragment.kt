package com.booxin.launcher.ui.download

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.booxin.launcher.AppContainer
import com.booxin.launcher.BooxinApp
import com.booxin.launcher.R
import com.booxin.launcher.core.BooxinGameRuntime
import com.booxin.launcher.core.download.game.GameInstallPhase
import com.booxin.launcher.core.download.game.GameInstallProgress
import com.booxin.launcher.core.download.modloader.FabricBuild
import com.booxin.launcher.core.download.modloader.FabricVersionClient
import com.booxin.launcher.core.download.modloader.ForgeBuild
import com.booxin.launcher.core.download.modloader.ForgeVersionClient
import com.booxin.launcher.core.download.modloader.ModLoaderKind
import com.booxin.launcher.core.download.modloader.NeoForgeBuild
import com.booxin.launcher.core.download.modloader.NeoForgeVersionClient
import com.booxin.launcher.core.download.modloader.OptiFineBuild
import com.booxin.launcher.core.download.modloader.OptiFineVersionClient
import com.booxin.launcher.core.download.modloader.QuiltBuild
import com.booxin.launcher.core.download.modloader.QuiltVersionClient
import com.booxin.launcher.core.java.JavaInstallState
import com.booxin.launcher.data.model.GameVersion
import com.booxin.launcher.data.model.VersionType
import com.booxin.launcher.databinding.FragmentDownloadBinding
import com.booxin.launcher.ui.controller.ControllerNavBinder
import com.booxin.launcher.ui.versions.VersionsAdapter
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Game install page: pick Minecraft version first, then choose install method.
 */
class DownloadFragment : Fragment() {

    private var _binding: FragmentDownloadBinding? = null
    private val binding get() = _binding!!

    private var showRelease = true
    private var showSnapshot = false
    private var showOld = false
    private var searchQuery = ""
    private var allRemote: List<GameVersion> = emptyList()
    private var installing = false
    private val forgeClient = ForgeVersionClient()
    private val neoForgeClient = NeoForgeVersionClient()
    private val fabricClient = FabricVersionClient()
    private val quiltClient = QuiltVersionClient()
    private val optiFineClient = OptiFineVersionClient()

    private val adapter = VersionsAdapter(installedMode = false) { version ->
        if (installing) return@VersionsAdapter
        showInstallMethodDialog(version)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDownloadBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.recyclerDownload.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerDownload.adapter = adapter
        ControllerNavBinder.bindRecycler(binding.recyclerDownload)

        binding.buttonBack.setOnClickListener {
            findNavController().navigateUp()
        }
        binding.buttonRefresh.setOnClickListener { refreshRemote() }

        setupFilters()
        setupSearch()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    AppContainer.repository.remoteVersions.collect { list ->
                        allRemote = list
                        applyFilter()
                    }
                }
                launch {
                    AppContainer.repository.installProgress.collect { progress ->
                        val b = _binding ?: return@collect
                        if (progress == null) {
                            if (!installing) b.progressPanel.isVisible = false
                            return@collect
                        }
                        b.progressPanel.isVisible =
                            progress.phase != GameInstallPhase.DONE &&
                                progress.phase != GameInstallPhase.FAILED &&
                                progress.phase != GameInstallPhase.IDLE
                        b.textProgress.text = formatProgressText(progress)
                        val fraction = progress.fraction
                        if (fraction >= 0f) {
                            b.progressDownload.isIndeterminate = false
                            b.progressDownload.progress = (fraction * 100).toInt()
                        } else {
                            b.progressDownload.isIndeterminate = true
                        }
                    }
                }
                launch {
                    AppContainer.repository.forgeInstallProgress.collect { progress ->
                        applyLoaderProgress(progress)
                    }
                }
                launch {
                    AppContainer.repository.fabricInstallProgress.collect { progress ->
                        applyLoaderProgress(progress)
                    }
                }
                launch {
                    AppContainer.repository.quiltInstallProgress.collect { progress ->
                        applyLoaderProgress(progress)
                    }
                }
                launch {
                    AppContainer.repository.optiFineInstallProgress.collect { progress ->
                        applyLoaderProgress(progress)
                    }
                }
                launch {
                    AppContainer.javaEnvironment.progress.collect { progress ->
                        val b = _binding ?: return@collect
                        if (!installing || progress == null) return@collect
                        if (progress.state == JavaInstallState.INSTALLED ||
                            progress.state == JavaInstallState.FAILED
                        ) {
                            return@collect
                        }
                        b.progressPanel.isVisible = true
                        b.textProgress.text = progress.message.ifBlank {
                            getString(R.string.download_installing)
                        }
                        val fraction = progress.progressFraction
                        if (fraction >= 0f) {
                            b.progressDownload.isIndeterminate = false
                            b.progressDownload.progress = (fraction * 100).toInt()
                        } else {
                            b.progressDownload.isIndeterminate = true
                        }
                    }
                }
            }
        }

        if (AppContainer.repository.remoteVersions.value.isEmpty()) {
            refreshRemote()
        } else {
            allRemote = AppContainer.repository.remoteVersions.value
            applyFilter()
        }
    }

    private fun setupFilters() {
        binding.checkRelease.setOnCheckedChangeListener { _, checked ->
            showRelease = checked
            ensureOneFilterSelected()
            applyFilter()
        }
        binding.checkSnapshot.setOnCheckedChangeListener { _, checked ->
            showSnapshot = checked
            ensureOneFilterSelected()
            applyFilter()
        }
        binding.checkOld.setOnCheckedChangeListener { _, checked ->
            showOld = checked
            ensureOneFilterSelected()
            applyFilter()
        }
    }

    private fun ensureOneFilterSelected() {
        if (showRelease || showSnapshot || showOld) return
        // Keep at least one filter selected.
        showRelease = true
        binding.checkRelease.isChecked = true
    }

    private fun setupSearch() {
        binding.inputSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString().orEmpty().trim()
                applyFilter()
            }
        })
    }

    private fun applyFilter() {
        val q = searchQuery.lowercase(Locale.US)
        val filtered = allRemote.filter { version ->
            val typeOk = when (version.type) {
                VersionType.RELEASE -> showRelease
                VersionType.SNAPSHOT -> showSnapshot
                VersionType.OLD_BETA, VersionType.OLD_ALPHA -> showOld
            }
            if (!typeOk) return@filter false
            if (q.isEmpty()) return@filter true
            version.id.lowercase(Locale.US).contains(q)
        }
        adapter.submit(filtered)
        binding.textEmpty.isVisible = filtered.isEmpty()
        binding.filterAppBar.setExpanded(true, false)
        binding.recyclerDownload.scrollToPosition(0)
    }

    private fun showInstallMethodDialog(version: GameVersion) {
        // Custom view: OEM Material setItems() lists often render with invisible text.
        val content = layoutInflater.inflate(R.layout.dialog_install_method, null, false)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.download_install_method_title, version.id))
            .setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        fun pick(kind: ModLoaderKind) {
            dialog.dismiss()
            when (kind) {
                ModLoaderKind.VANILLA -> startInstall(version)
                ModLoaderKind.FORGE -> pickForgeBuild(version)
                ModLoaderKind.NEOFORGE -> pickNeoForgeBuild(version)
                ModLoaderKind.FABRIC -> pickFabricBuild(version)
                ModLoaderKind.QUILT -> pickQuiltBuild(version)
                ModLoaderKind.OPTIFINE -> pickOptiFineBuild(version)
            }
        }

        content.findViewById<View>(R.id.buttonMethodVanilla)
            .setOnClickListener { pick(ModLoaderKind.VANILLA) }
        content.findViewById<View>(R.id.buttonMethodForge)
            .setOnClickListener { pick(ModLoaderKind.FORGE) }
        content.findViewById<View>(R.id.buttonMethodNeoForge)
            .setOnClickListener { pick(ModLoaderKind.NEOFORGE) }
        content.findViewById<View>(R.id.buttonMethodFabric)
            .setOnClickListener { pick(ModLoaderKind.FABRIC) }
        content.findViewById<View>(R.id.buttonMethodQuilt)
            .setOnClickListener { pick(ModLoaderKind.QUILT) }
        content.findViewById<View>(R.id.buttonMethodOptiFine)
            .setOnClickListener { pick(ModLoaderKind.OPTIFINE) }

        dialog.show()
    }

    private fun refreshRemote() {
        viewLifecycleOwner.lifecycleScope.launch {
            val b = _binding ?: return@launch
            b.buttonRefresh.isEnabled = false
            b.buttonRefresh.text = getString(R.string.download_refreshing)
            val result = AppContainer.repository.refreshVersions()
            val end = _binding ?: return@launch
            end.buttonRefresh.isEnabled = true
            end.buttonRefresh.text = getString(R.string.download_refresh)
            if (result.isFailure) {
                val context = context ?: return@launch
                Toast.makeText(
                    context,
                    getString(
                        R.string.download_refresh_failed,
                        result.exceptionOrNull()?.message ?: "unknown"
                    ),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun pickForgeBuild(version: GameVersion) {
        viewLifecycleOwner.lifecycleScope.launch {
            val result = forgeClient.listBuilds(version.id)
            val builds = result.getOrNull().orEmpty()
            if (builds.isEmpty()) {
                Toast.makeText(
                    requireContext(),
                    getString(
                        R.string.download_forge_empty,
                        result.exceptionOrNull()?.message ?: version.id
                    ),
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            val labels = builds.map { build ->
                build.displayName + if (build.recommended) " ★" else ""
            }.toTypedArray()
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.download_forge_pick, version.id))
                .setItems(labels) { _, which ->
                    startForgeInstall(version, builds[which])
                }
                .show()
        }
    }

    private fun pickNeoForgeBuild(version: GameVersion) {
        viewLifecycleOwner.lifecycleScope.launch {
            val result = neoForgeClient.listBuilds(version.id)
            val builds = result.getOrNull().orEmpty()
            if (builds.isEmpty()) {
                Toast.makeText(
                    requireContext(),
                    getString(
                        R.string.download_neoforge_empty,
                        result.exceptionOrNull()?.message ?: version.id
                    ),
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            val labels = builds.map { build ->
                build.displayName + if (build.recommended) " ★" else ""
            }.toTypedArray()
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.download_neoforge_pick, version.id))
                .setItems(labels) { _, which ->
                    startNeoForgeInstall(version, builds[which])
                }
                .show()
        }
    }

    private fun startForgeInstall(mcVersion: GameVersion, build: ForgeBuild) {
        promptInstanceName(build.displayName) { displayName ->
            runInstallJob(
                preparing = {
                    textProgress.text =
                        getString(R.string.download_forge_installing, build.displayName)
                }
            ) {
                val runtime = AppContainer.gameRuntime as BooxinGameRuntime
                val result = runtime.prepareForge(mcVersion.id, build.loaderVersion, mcVersion.url)
                withContext(Dispatchers.Main) { finishLoaderInstall(result, displayName) }
            }
        }
    }

    private fun startNeoForgeInstall(mcVersion: GameVersion, build: NeoForgeBuild) {
        promptInstanceName(build.displayName) { displayName ->
            runInstallJob(
                preparing = {
                    textProgress.text =
                        getString(R.string.download_neoforge_installing, build.displayName)
                }
            ) {
                val runtime = AppContainer.gameRuntime as BooxinGameRuntime
                val result =
                    runtime.prepareNeoForge(mcVersion.id, build.loaderVersion, mcVersion.url)
                withContext(Dispatchers.Main) { finishLoaderInstall(result, displayName) }
            }
        }
    }

    private fun pickFabricBuild(version: GameVersion) {
        viewLifecycleOwner.lifecycleScope.launch {
            val result = fabricClient.listBuilds(version.id)
            val builds = result.getOrNull().orEmpty()
            if (builds.isEmpty()) {
                Toast.makeText(
                    requireContext(),
                    getString(
                        R.string.download_fabric_empty,
                        result.exceptionOrNull()?.message ?: version.id
                    ),
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            val labels = builds.map { build ->
                build.displayName + if (build.recommended) " ★" else ""
            }.toTypedArray()
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.download_fabric_pick, version.id))
                .setItems(labels) { _, which ->
                    startFabricInstall(version, builds[which])
                }
                .show()
        }
    }

    private fun startFabricInstall(mcVersion: GameVersion, build: FabricBuild) {
        promptInstanceName(build.displayName) { displayName ->
            runInstallJob(
                preparing = {
                    textProgress.text =
                        getString(R.string.download_fabric_installing, build.displayName)
                }
            ) {
                val runtime = AppContainer.gameRuntime as BooxinGameRuntime
                val result = runtime.prepareFabric(mcVersion.id, build.loaderVersion, mcVersion.url)
                withContext(Dispatchers.Main) { finishLoaderInstall(result, displayName) }
            }
        }
    }

    private fun pickQuiltBuild(version: GameVersion) {
        viewLifecycleOwner.lifecycleScope.launch {
            val result = quiltClient.listBuilds(version.id)
            val builds = result.getOrNull().orEmpty()
            if (builds.isEmpty()) {
                Toast.makeText(
                    requireContext(),
                    getString(
                        R.string.download_quilt_empty,
                        result.exceptionOrNull()?.message ?: version.id
                    ),
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            val labels = builds.map { build ->
                build.displayName + if (build.recommended) " ★" else ""
            }.toTypedArray()
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.download_quilt_pick, version.id))
                .setItems(labels) { _, which ->
                    startQuiltInstall(version, builds[which])
                }
                .show()
        }
    }

    private fun startQuiltInstall(mcVersion: GameVersion, build: QuiltBuild) {
        promptInstanceName(build.displayName) { displayName ->
            runInstallJob(
                preparing = {
                    textProgress.text =
                        getString(R.string.download_quilt_installing, build.displayName)
                }
            ) {
                val runtime = AppContainer.gameRuntime as BooxinGameRuntime
                val result = runtime.prepareQuilt(mcVersion.id, build.loaderVersion, mcVersion.url)
                withContext(Dispatchers.Main) { finishLoaderInstall(result, displayName) }
            }
        }
    }

    private fun pickOptiFineBuild(version: GameVersion) {
        viewLifecycleOwner.lifecycleScope.launch {
            val result = optiFineClient.listBuilds(version.id)
            val builds = result.getOrNull().orEmpty()
            if (builds.isEmpty()) {
                Toast.makeText(
                    requireContext(),
                    getString(
                        R.string.download_optifine_empty,
                        result.exceptionOrNull()?.message ?: version.id
                    ),
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            val labels = builds.map { build ->
                build.displayName + if (build.recommended) " ★" else ""
            }.toTypedArray()
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.download_optifine_pick, version.id))
                .setItems(labels) { _, which ->
                    startOptiFineInstall(version, builds[which])
                }
                .show()
        }
    }

    private fun startOptiFineInstall(mcVersion: GameVersion, build: OptiFineBuild) {
        promptInstanceName(build.displayName) { displayName ->
            runInstallJob(
                preparing = {
                    textProgress.text =
                        getString(R.string.download_optifine_installing, build.displayName)
                }
            ) {
                val runtime = AppContainer.gameRuntime as BooxinGameRuntime
                val result = runtime.prepareOptiFine(
                    mcVersion.id,
                    build.type,
                    build.patch,
                    mcVersion.url
                )
                withContext(Dispatchers.Main) { finishLoaderInstall(result, displayName) }
            }
        }
    }

    private fun applyLoaderProgress(progress: GameInstallProgress?) {
        val b = _binding ?: return
        if (progress == null) return
        b.progressPanel.isVisible =
            progress.phase != GameInstallPhase.DONE &&
                progress.phase != GameInstallPhase.FAILED &&
                progress.phase != GameInstallPhase.IDLE
        b.textProgress.text = formatProgressText(progress)
        val fraction = progress.fraction
        if (fraction >= 0f) {
            b.progressDownload.isIndeterminate = false
            b.progressDownload.progress = (fraction * 100).toInt()
        } else {
            b.progressDownload.isIndeterminate = true
        }
    }

    private fun finishLoaderInstall(result: Result<String>, displayName: String? = null) {
        installing = false
        val end = _binding
        val appCtx = BooxinApp.getAppContext()
        if (result.isSuccess) {
            val versionId = result.getOrThrow()
            if (!displayName.isNullOrBlank()) {
                AppContainer.repository.setVersionDisplayName(versionId, displayName)
            }
            end?.progressPanel?.isVisible = false
            Toast.makeText(
                appCtx,
                appCtx.getString(R.string.download_install_done, versionId),
                Toast.LENGTH_SHORT
            ).show()
            end?.let { adapter.notifyDataSetChanged() }
        } else {
            val message = result.exceptionOrNull()?.message ?: "unknown"
            end?.textProgress?.text = message
            Toast.makeText(
                appCtx,
                appCtx.getString(R.string.download_install_failed, message),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /** Optional display name before install; skip keeps the default version id label. */
    private fun promptInstanceName(defaultHint: String, onProceed: (displayName: String?) -> Unit) {
        val input = TextInputEditText(requireContext()).apply {
            hint = getString(R.string.download_instance_name_hint)
            setText(defaultHint)
            setSelection(text?.length ?: 0)
        }
        val layout = TextInputLayout(requireContext()).apply {
            hint = getString(R.string.download_instance_name_title)
            setPadding(48, 24, 48, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.download_instance_name_title)
            .setMessage(R.string.download_instance_name_message)
            .setView(layout)
            .setPositiveButton(R.string.download_instance_name_ok) { _, _ ->
                onProceed(input.text?.toString()?.trim()?.takeIf { it.isNotBlank() })
            }
            .setNeutralButton(R.string.download_instance_name_skip) { _, _ ->
                onProceed(null)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Install jobs run on [AppContainer.appScope] so leaving this page does not cancel downloads.
     */
    private fun runInstallJob(
        preparing: FragmentDownloadBinding.() -> Unit,
        block: suspend () -> Unit
    ) {
        if (installing) return
        installing = true
        _binding?.let { b ->
            b.progressPanel.isVisible = true
            b.progressDownload.isIndeterminate = false
            b.progressDownload.progress = 0
            b.preparing()
        }
        AppContainer.appScope.launch {
            try {
                block()
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    installing = false
                    val message = t.message ?: "unknown"
                    _binding?.textProgress?.text = message
                    Toast.makeText(
                        BooxinApp.getAppContext(),
                        BooxinApp.getAppContext()
                            .getString(R.string.download_install_failed, message),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun startInstall(version: GameVersion) {
        promptInstanceName(version.id) { displayName ->
            runInstallJob(
                preparing = { textProgress.text = getString(R.string.download_installing) }
            ) {
                val alreadyInstalled = AppContainer.repository.installedVersions.value
                    .any { it.id == version.id } || version.installed
                AppContainer.javaEnvironment.ensureForMinecraft(version.id).getOrElse {
                    withContext(Dispatchers.Main) {
                        installing = false
                        val message = it.message ?: "unknown"
                        _binding?.textProgress?.text = message
                        Toast.makeText(
                            BooxinApp.getAppContext(),
                            BooxinApp.getAppContext()
                                .getString(R.string.download_install_failed, message),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    return@runInstallJob
                }
                val result = AppContainer.repository.installVersion(version.id)
                withContext(Dispatchers.Main) {
                    installing = false
                    val end = _binding
                    val appCtx = BooxinApp.getAppContext()
                    if (result.isSuccess) {
                        if (!displayName.isNullOrBlank()) {
                            AppContainer.repository.setVersionDisplayName(version.id, displayName)
                        }
                        end?.progressPanel?.isVisible = false
                        Toast.makeText(
                            appCtx,
                            if (alreadyInstalled) {
                                appCtx.getString(R.string.download_reinstall_done, version.id)
                            } else {
                                appCtx.getString(R.string.download_install_done, version.id)
                            },
                            Toast.LENGTH_SHORT
                        ).show()
                        end?.let { adapter.notifyDataSetChanged() }
                    } else {
                        val message = result.exceptionOrNull()?.message ?: "unknown"
                        end?.textProgress?.text = message
                        Toast.makeText(
                            appCtx,
                            appCtx.getString(R.string.download_install_failed, message),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    private fun formatProgressText(progress: GameInstallProgress): String {
        val fraction = progress.fraction
        if (fraction < 0f) return progress.message
        val percent = (fraction * 100).toInt().coerceIn(0, 100)
        return "${progress.message}（$percent%）"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
