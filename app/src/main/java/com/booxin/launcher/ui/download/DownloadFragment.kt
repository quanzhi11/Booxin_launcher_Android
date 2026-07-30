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
import com.booxin.launcher.core.download.modloader.ForgeVersionClient
import com.booxin.launcher.core.download.modloader.ModLoaderKind
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

    private val adapter = VersionsAdapter(installedMode = false) { version ->
        if (installing) return@VersionsAdapter
        when (loader) {
            ModLoaderKind.FORGE -> pickForgeBuild(version)
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
                        val b = _binding ?: return@collect
                        if (progress == null) return@collect
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
        val wip = getString(R.string.download_loader_wip)
        binding.chipVanilla.isChecked = true
        binding.chipVanilla.setOnCheckedChangeListener { _, checked ->
            if (checked) loader = ModLoaderKind.VANILLA
        }
        binding.chipForge.isEnabled = true
        binding.chipForge.isCheckable = true
        binding.chipForge.text = getString(R.string.download_loader_forge)
        binding.chipForge.setOnCheckedChangeListener { _, checked ->
            if (checked) loader = ModLoaderKind.FORGE
        }
        listOf(
            binding.chipNeoForge to R.string.download_loader_neoforge,
            binding.chipFabric to R.string.download_loader_fabric,
            binding.chipQuilt to R.string.download_loader_quilt,
            binding.chipOptiFine to R.string.download_loader_optifine
        ).forEach { (chip, labelRes) ->
            styleWipChip(chip, getString(labelRes), wip)
        }
    }

    private fun styleWipChip(chip: Chip, name: String, wip: String) {
        chip.isCheckable = false
        chip.isClickable = true
        chip.alpha = 0.45f
        chip.text = "$name · $wip"
        chip.setOnClickListener {
            Toast.makeText(requireContext(), "$name $wip", Toast.LENGTH_SHORT).show()
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

    private fun startForgeInstall(
        mcVersion: GameVersion,
        build: com.booxin.launcher.core.download.modloader.ForgeBuild
    ) {
        viewLifecycleOwner.lifecycleScope.launch {
            installing = true
            val b = _binding ?: return@launch
            b.progressPanel.isVisible = true
            b.progressDownload.isIndeterminate = false
            b.progressDownload.progress = 0
            b.textProgress.text = getString(R.string.download_forge_installing, build.displayName)
            val runtime = AppContainer.gameRuntime as BooxinGameRuntime
            val result = runtime.prepareForge(mcVersion.id, build.loaderVersion, mcVersion.url)
            installing = false
            val end = _binding ?: return@launch
            val context = context ?: return@launch
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
    }

    private fun startInstall(version: GameVersion) {
        viewLifecycleOwner.lifecycleScope.launch {
            installing = true
            val b = _binding ?: return@launch
            b.progressPanel.isVisible = true
            b.textProgress.text = getString(R.string.download_installing)
            val result = AppContainer.gameRuntime.prepare(version.id)
            installing = false
            val end = _binding ?: return@launch
            val context = context ?: return@launch
            if (result.isSuccess) {
                end.progressPanel.isVisible = false
                Toast.makeText(
                    context,
                    getString(R.string.download_install_done, version.id),
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
