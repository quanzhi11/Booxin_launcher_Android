package com.booxin.launcher.ui.versions.manage

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.booxin.launcher.R
import com.booxin.launcher.core.LauncherPaths
import com.booxin.launcher.core.download.game.VersionJsonMerger
import com.booxin.launcher.core.version.VersionModFile
import com.booxin.launcher.core.version.VersionModsManager
import com.booxin.launcher.databinding.FragmentVersionManageBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONObject
import java.io.File

class VersionManageFragment : Fragment() {

    private var _binding: FragmentVersionManageBinding? = null
    private val binding get() = _binding!!
    private lateinit var versionId: String
    private lateinit var adapter: VersionModsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        versionId = requireArguments().getString(ARG_VERSION_ID).orEmpty()
        require(versionId.isNotBlank()) { "missing versionId" }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentVersionManageBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.buttonBack.setOnClickListener { findNavController().navigateUp() }
        binding.textVersionTitle.text = versionId

        setupTabs()
        setupModsList()
        bindDetails()
        refreshMods()
    }

    override fun onResume() {
        super.onResume()
        refreshMods()
    }

    private fun setupTabs() {
        binding.tabManage.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab) {
                val detail = tab.position == 0
                binding.panelDetails.isVisible = detail
                binding.panelMods.isVisible = !detail
            }

            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab) = Unit
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab) = Unit
        })
    }

    private fun setupModsList() {
        adapter = VersionModsAdapter(
            onToggle = { toggleMod(it) },
            onUninstall = { confirmUninstall(it) }
        )
        binding.recyclerMods.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerMods.adapter = adapter
    }

    private fun bindDetails() {
        val json = VersionJsonMerger.readVersionJson(versionId)
        val isModded = VersionJsonMerger.isModLoaderVersion(versionId)
        val base = VersionJsonMerger.resolveMinecraftVersionId(versionId)
        val versionRoot = File(LauncherPaths.versionsDir, versionId)
        val modsDir = File(versionRoot, "mods")

        binding.textBaseVersion.text = base
        binding.textVersionType.text = if (isModded) {
            getString(R.string.version_manage_loader_modded)
        } else {
            getString(R.string.version_manage_loader_vanilla)
        }
        binding.textVersionPath.text = versionRoot.absolutePath
        binding.textModsPath.text = modsDir.absolutePath
        binding.textJsonType.text = resolveJsonType(json)

        binding.textVanillaHint.isVisible = !isModded
        binding.recyclerMods.isVisible = isModded
        binding.textModsEmpty.isVisible = isModded && VersionModsManager.list(versionId).isEmpty()
    }

    private fun refreshMods() {
        val isModded = VersionJsonMerger.isModLoaderVersion(versionId)
        if (!isModded) {
            adapter.submit(emptyList())
            binding.recyclerMods.isVisible = false
            binding.textModsEmpty.isVisible = false
            binding.textVanillaHint.isVisible = true
            return
        }
        val list = VersionModsManager.list(versionId)
        adapter.submit(list)
        binding.recyclerMods.isVisible = true
        binding.textVanillaHint.isVisible = false
        binding.textModsEmpty.isVisible = list.isEmpty()
    }

    private fun toggleMod(mod: VersionModFile) {
        val result = VersionModsManager.toggle(mod)
        if (result.isSuccess) {
            refreshMods()
            Toast.makeText(requireContext(), R.string.version_manage_mod_toggled, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(
                requireContext(),
                getString(R.string.version_manage_action_failed, result.exceptionOrNull()?.message ?: "unknown"),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun confirmUninstall(mod: VersionModFile) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.version_manage_mod_uninstall)
            .setMessage(getString(R.string.version_manage_mod_uninstall_confirm, mod.displayName))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.version_manage_mod_uninstall) { _, _ ->
                val result = VersionModsManager.uninstall(mod)
                if (result.isSuccess) {
                    refreshMods()
                    Toast.makeText(requireContext(), R.string.version_manage_mod_uninstalled, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(
                        requireContext(),
                        getString(
                            R.string.version_manage_action_failed,
                            result.exceptionOrNull()?.message ?: "unknown"
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            .show()
    }

    private fun resolveJsonType(json: JSONObject?): String {
        val type = json?.optString("type").orEmpty()
        return when (type.lowercase()) {
            "release" -> getString(R.string.download_filter_release)
            "snapshot" -> getString(R.string.download_filter_snapshot)
            "old_beta", "old_alpha" -> getString(R.string.download_filter_old)
            else -> type.ifBlank { getString(R.string.version_manage_unknown) }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val ARG_VERSION_ID = "versionId"
    }
}
