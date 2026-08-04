package com.booxin.launcher.ui.download

import android.os.Bundle
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
import com.booxin.launcher.ui.versions.VersionsAdapter
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

class DownloadFragment : Fragment() {

    private var _binding: FragmentDownloadBinding? = null
    private val binding get() = _binding!!

    private var filter: VersionType = VersionType.RELEASE
    private var loader: ModLoaderKind = ModLoaderKind.VANILLA
    private var allRemote: List<GameVersion> = emptyList()
    private var installing = false
    private val forgeClient = ForgeVersionClient()
    private val neoForgeClient = NeoForgeVersionClient()
    private val fabricClient = FabricVersionClient()
    private val quiltClient = QuiltVersionClient()
    private val optiFineClient = OptiFineVersionClient()

    private val adapter = VersionsAdapter(installedMode = false) { version ->
        if (installing) return@VersionsAdapter
        when (loader) {
            ModLoaderKind.FORGE -> pickForgeBuild(version)
            ModLoaderKind.NEOFORGE -> pickNeoForgeBuild(version)
            ModLoaderKind.FABRIC -> pickFabricBuild(version)
            ModLoaderKind.QUILT -> pickQuiltBuild(version)
            ModLoaderKind.OPTIFINE -> pickOptiFineBuild(version)
            ModLoaderKind.VANILLA -> startInstall(version)
        }
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

        binding.buttonBack.setOnClickListener {
            findNavController().navigateUp()
        }
        binding.buttonRefresh.setOnClickListener { refreshRemote() }

        setupLoaderChips()
        setupFilterChips()

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

    private fun setupLoaderChips() {
        binding.chipVanilla.isChecked = true
        binding.chipVanilla.setOnCheckedChangeListener { _, checked ->
            if (checked) loader = ModLoaderKind.VANILLA
        }
        enableLoaderChip(binding.chipForge, R.string.download_loader_forge) {
            loader = ModLoaderKind.FORGE
        }
        enableLoaderChip(binding.chipNeoForge, R.string.download_loader_neoforge) {
            loader = ModLoaderKind.NEOFORGE
        }
        enableLoaderChip(binding.chipFabric, R.string.download_loader_fabric) {
            loader = ModLoaderKind.FABRIC
        }
        enableLoaderChip(binding.chipQuilt, R.string.download_loader_quilt) {
            loader = ModLoaderKind.QUILT
        }
        enableLoaderChip(binding.chipOptiFine, R.string.download_loader_optifine) {
            loader = ModLoaderKind.OPTIFINE
        }
    }

    private fun enableLoaderChip(chip: Chip, labelRes: Int, onSelected: () -> Unit) {
        chip.isEnabled = true
        chip.isCheckable = true
        chip.alpha = 1f
        chip.text = getString(labelRes)
        chip.setOnClickListener(null)
        chip.setOnCheckedChangeListener { _, checked ->
            if (checked) onSelected()
        }
    }

    private fun setupFilterChips() {
        binding.chipRelease.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                filter = VersionType.RELEASE
                applyFilter()
            }
        }
        binding.chipSnapshot.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                filter = VersionType.SNAPSHOT
                applyFilter()
            }
        }
        binding.chipOld.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                filter = VersionType.OLD_BETA
                applyFilter()
            }
        }
    }

    private fun applyFilter() {
        val filtered = when (filter) {
            VersionType.RELEASE -> allRemote.filter { it.type == VersionType.RELEASE }
            VersionType.SNAPSHOT -> allRemote.filter { it.type == VersionType.SNAPSHOT }
            VersionType.OLD_BETA, VersionType.OLD_ALPHA ->
                allRemote.filter {
                    it.type == VersionType.OLD_BETA || it.type == VersionType.OLD_ALPHA
                }
        }
        adapter.submit(filtered)
        binding.textEmpty.isVisible = filtered.isEmpty()
        binding.filterAppBar.setExpanded(true, false)
        binding.recyclerDownload.scrollToPosition(0)
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
        viewLifecycleOwner.lifecycleScope.launch {
            installing = true
            val b = _binding ?: return@launch
            b.progressPanel.isVisible = true
            b.progressDownload.isIndeterminate = false
            b.progressDownload.progress = 0
            b.textProgress.text = getString(R.string.download_forge_installing, build.displayName)
            val runtime = AppContainer.gameRuntime as BooxinGameRuntime
            val result = runtime.prepareForge(mcVersion.id, build.loaderVersion, mcVersion.url)
            finishLoaderInstall(result)
        }
    }

    private fun startNeoForgeInstall(mcVersion: GameVersion, build: NeoForgeBuild) {
        viewLifecycleOwner.lifecycleScope.launch {
            installing = true
            val b = _binding ?: return@launch
            b.progressPanel.isVisible = true
            b.progressDownload.isIndeterminate = false
            b.progressDownload.progress = 0
            b.textProgress.text = getString(R.string.download_neoforge_installing, build.displayName)
            val runtime = AppContainer.gameRuntime as BooxinGameRuntime
            val result = runtime.prepareNeoForge(mcVersion.id, build.loaderVersion, mcVersion.url)
            finishLoaderInstall(result)
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
        viewLifecycleOwner.lifecycleScope.launch {
            installing = true
            val b = _binding ?: return@launch
            b.progressPanel.isVisible = true
            b.progressDownload.isIndeterminate = false
            b.progressDownload.progress = 0
            b.textProgress.text = getString(R.string.download_fabric_installing, build.displayName)
            val runtime = AppContainer.gameRuntime as BooxinGameRuntime
            val result = runtime.prepareFabric(mcVersion.id, build.loaderVersion, mcVersion.url)
            finishLoaderInstall(result)
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
        viewLifecycleOwner.lifecycleScope.launch {
            installing = true
            val b = _binding ?: return@launch
            b.progressPanel.isVisible = true
            b.progressDownload.isIndeterminate = false
            b.progressDownload.progress = 0
            b.textProgress.text = getString(R.string.download_quilt_installing, build.displayName)
            val runtime = AppContainer.gameRuntime as BooxinGameRuntime
            val result = runtime.prepareQuilt(mcVersion.id, build.loaderVersion, mcVersion.url)
            finishLoaderInstall(result)
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
        viewLifecycleOwner.lifecycleScope.launch {
            installing = true
            val b = _binding ?: return@launch
            b.progressPanel.isVisible = true
            b.progressDownload.isIndeterminate = false
            b.progressDownload.progress = 0
            b.textProgress.text = getString(R.string.download_optifine_installing, build.displayName)
            val runtime = AppContainer.gameRuntime as BooxinGameRuntime
            val result = runtime.prepareOptiFine(
                mcVersion.id,
                build.type,
                build.patch,
                mcVersion.url
            )
            finishLoaderInstall(result)
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

    private fun finishLoaderInstall(result: Result<String>) {
        installing = false
        val end = _binding ?: return
        val context = context ?: return
        if (result.isSuccess) {
            end.progressPanel.isVisible = false
            Toast.makeText(
                context,
                getString(R.string.download_install_done, result.getOrThrow()),
                Toast.LENGTH_SHORT
            ).show()
            adapter.notifyDataSetChanged()
        } else {
            val message = result.exceptionOrNull()?.message ?: "unknown"
            end.textProgress.text = message
            Toast.makeText(
                context,
                getString(R.string.download_install_failed, message),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun startInstall(version: GameVersion) {
        viewLifecycleOwner.lifecycleScope.launch {
            installing = true
            try {
                val b = _binding ?: return@launch
                b.progressPanel.isVisible = true
                b.textProgress.text = getString(R.string.download_installing)

                // Download page must run the real install pipeline — prepare()/ensureVersionReady
                // short-circuits when files already look present and shows a fake "done".
                val alreadyInstalled = AppContainer.repository.installedVersions.value
                    .any { it.id == version.id } || version.installed
                AppContainer.javaEnvironment.ensureForMinecraft(version.id).getOrElse {
                    val end = _binding ?: return@launch
                    val context = context ?: return@launch
                    val message = it.message ?: "unknown"
                    end.textProgress.text = message
                    Toast.makeText(
                        context,
                        getString(R.string.download_install_failed, message),
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }
                val result = AppContainer.repository.installVersion(version.id)
                val end = _binding ?: return@launch
                val context = context ?: return@launch
                if (result.isSuccess) {
                    end.progressPanel.isVisible = false
                    Toast.makeText(
                        context,
                        if (alreadyInstalled) {
                            getString(R.string.download_reinstall_done, version.id)
                        } else {
                            getString(R.string.download_install_done, version.id)
                        },
                        Toast.LENGTH_SHORT
                    ).show()
                    adapter.notifyDataSetChanged()
                } else {
                    val message = result.exceptionOrNull()?.message ?: "unknown"
                    end.textProgress.text = message
                    Toast.makeText(
                        context,
                        getString(R.string.download_install_failed, message),
                        Toast.LENGTH_LONG
                    ).show()
                }
            } finally {
                installing = false
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
