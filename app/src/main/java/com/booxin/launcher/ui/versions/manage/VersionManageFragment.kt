package com.booxin.launcher.ui.versions.manage

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.booxin.launcher.R
import com.booxin.launcher.core.LauncherPaths
import com.booxin.launcher.core.download.game.VersionJsonMerger
import com.booxin.launcher.core.version.AndroidIncompatibleMods
import com.booxin.launcher.core.version.VersionModFile
import com.booxin.launcher.core.version.VersionModsManager
import com.booxin.launcher.core.version.VersionResourcePackFile
import com.booxin.launcher.core.version.VersionResourcePacksManager
import com.booxin.launcher.core.version.VersionShaderPacksManager
import com.booxin.launcher.databinding.FragmentVersionManageBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

class VersionManageFragment : Fragment() {

    private var _binding: FragmentVersionManageBinding? = null
    private val binding get() = _binding!!
    private lateinit var versionId: String
    private lateinit var modsAdapter: VersionModsAdapter
    private lateinit var packsAdapter: VersionResourcePacksAdapter
    private lateinit var shadersAdapter: VersionResourcePacksAdapter
    private var downloadDialog: AlertDialog? = null

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
        refreshHeaderTitle()
        binding.buttonRenameDisplay.setOnClickListener { showRenameDialog() }
        binding.buttonSetDefault.setOnClickListener { toggleDefault() }

        setupTabs()
        setupModsList()
        setupPacksList()
        setupShadersList()
        binding.buttonDownloadUrl.setOnClickListener { showModUrlDownloadDialog() }
        binding.buttonDownloadPackUrl.setOnClickListener { showPackUrlDownloadDialog() }
        binding.buttonDownloadShaderUrl.setOnClickListener { showShaderUrlDownloadDialog() }
        bindDetails()
        refreshMods()
        refreshPacks()
        refreshShaders()
    }

    private fun refreshHeaderTitle() {
        val version = com.booxin.launcher.AppContainer.repository.installedVersions.value
            .firstOrNull { it.id == versionId }
        binding.textVersionTitle.text = version?.displayName ?: versionId
        binding.textDisplayName.text = version?.displayName ?: versionId
        binding.buttonSetDefault.setText(
            if (version?.isDefault == true) R.string.versions_clear_default
            else R.string.versions_set_default
        )
    }

    private fun showRenameDialog() {
        val version = com.booxin.launcher.AppContainer.repository.installedVersions.value
            .firstOrNull { it.id == versionId }
        val density = resources.displayMetrics.density
        val pad = (20 * density).toInt()
        val inputLayout = TextInputLayout(requireContext()).apply {
            hint = getString(R.string.versions_rename_hint)
            setPadding(pad, pad / 2, pad, 0)
        }
        val edit = TextInputEditText(inputLayout.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            maxLines = 1
            setText(version?.customDisplayName ?: versionId)
            setSelectAllOnFocus(true)
        }
        inputLayout.addView(edit)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.versions_rename_title)
            .setMessage("ID: $versionId")
            .setView(inputLayout)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                com.booxin.launcher.AppContainer.repository.setVersionDisplayName(
                    versionId,
                    edit.text?.toString()
                )
                refreshHeaderTitle()
                Toast.makeText(requireContext(), R.string.versions_rename_done, Toast.LENGTH_SHORT)
                    .show()
            }
            .show()
        edit.requestFocus()
    }

    private fun toggleDefault() {
        val version = com.booxin.launcher.AppContainer.repository.installedVersions.value
            .firstOrNull { it.id == versionId }
        if (version?.isDefault == true) {
            com.booxin.launcher.AppContainer.repository.setDefaultVersion(null)
        } else {
            com.booxin.launcher.AppContainer.repository.setDefaultVersion(versionId)
            Toast.makeText(
                requireContext(),
                getString(R.string.versions_set_default_done, version?.displayName ?: versionId),
                Toast.LENGTH_SHORT
            ).show()
        }
        refreshHeaderTitle()
    }

    override fun onResume() {
        super.onResume()
        refreshMods()
        refreshPacks()
        refreshShaders()
    }

    private fun setupTabs() {
        binding.tabManage.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab) {
                binding.panelDetails.isVisible = tab.position == 0
                binding.panelMods.isVisible = tab.position == 1
                binding.panelResourcePacks.isVisible = tab.position == 2
                binding.panelShaders.isVisible = tab.position == 3
            }

            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab) = Unit
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab) = Unit
        })
    }

    private fun setupModsList() {
        modsAdapter = VersionModsAdapter(
            onToggle = { toggleMod(it) },
            onUninstall = { confirmUninstallMod(it) }
        )
        binding.recyclerMods.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerMods.adapter = modsAdapter
    }

    private fun setupPacksList() {
        packsAdapter = VersionResourcePacksAdapter(
            onToggle = { togglePack(it) },
            onUninstall = { confirmUninstallPack(it) }
        )
        binding.recyclerResourcePacks.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerResourcePacks.adapter = packsAdapter
    }

    private fun setupShadersList() {
        shadersAdapter = VersionResourcePacksAdapter(
            onToggle = { toggleShader(it) },
            onUninstall = { confirmUninstallShader(it) }
        )
        binding.recyclerShaders.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerShaders.adapter = shadersAdapter
    }

    private fun bindDetails() {
        val json = VersionJsonMerger.readVersionJson(versionId)
        val isModded = VersionJsonMerger.isModLoaderVersion(versionId)
        val base = VersionJsonMerger.resolveMinecraftVersionId(versionId)
        val versionRoot = File(LauncherPaths.versionsDir, versionId)
        val modsDir = File(versionRoot, "mods")
        val packsDir = VersionResourcePacksManager.resourcePacksDir(versionId)
        val shadersDir = VersionShaderPacksManager.shaderPacksDir(versionId)

        binding.textBaseVersion.text = base
        binding.textVersionType.text = if (isModded) {
            getString(R.string.version_manage_loader_modded)
        } else {
            getString(R.string.version_manage_loader_vanilla)
        }
        binding.textVersionPath.text = versionRoot.absolutePath
        binding.textModsPath.text = modsDir.absolutePath
        binding.textResourcePacksPath.text = packsDir.absolutePath
        binding.textShadersPath.text = shadersDir.absolutePath
        binding.textJsonType.text = resolveJsonType(json)

        binding.textVanillaHint.isVisible = !isModded
        binding.recyclerMods.isVisible = isModded
        binding.textModsEmpty.isVisible = isModded && VersionModsManager.list(versionId).isEmpty()
    }

    private fun refreshMods() {
        val isModded = VersionJsonMerger.isModLoaderVersion(versionId)
        binding.buttonDownloadUrl.isEnabled = isModded
        binding.buttonDownloadUrl.alpha = if (isModded) 1f else 0.45f
        if (!isModded) {
            modsAdapter.submit(emptyList())
            binding.recyclerMods.isVisible = false
            binding.textModsEmpty.isVisible = false
            binding.textVanillaHint.isVisible = true
            return
        }
        val scan = AndroidIncompatibleMods.scanAndDisable(versionId)
        if (scan.changed) {
            Toast.makeText(
                requireContext(),
                getString(
                    R.string.version_manage_mod_android_auto_disabled,
                    scan.disabled.size
                ),
                Toast.LENGTH_LONG
            ).show()
        }
        val list = VersionModsManager.list(versionId)
        modsAdapter.submit(list)
        binding.recyclerMods.isVisible = true
        binding.textVanillaHint.isVisible = false
        binding.textModsEmpty.isVisible = list.isEmpty()
    }

    private fun refreshPacks() {
        val list = VersionResourcePacksManager.list(versionId)
        packsAdapter.submit(list)
        binding.textPacksEmpty.isVisible = list.isEmpty()
    }

    private fun refreshShaders() {
        val list = VersionShaderPacksManager.list(versionId)
        shadersAdapter.submit(list)
        binding.textShadersEmpty.isVisible = list.isEmpty()
    }

    private fun showModUrlDownloadDialog() {
        if (!VersionJsonMerger.isModLoaderVersion(versionId)) {
            Toast.makeText(requireContext(), R.string.version_manage_vanilla_no_mods, Toast.LENGTH_SHORT).show()
            return
        }
        showUrlInputDialog(
            titleRes = R.string.version_manage_download_url_title,
            hintRes = R.string.version_manage_download_url_hint
        ) { url -> downloadModFromUrl(url) }
    }

    private fun showPackUrlDownloadDialog() {
        showUrlInputDialog(
            titleRes = R.string.version_manage_download_pack_url_title,
            hintRes = R.string.version_manage_download_pack_url_hint
        ) { url -> downloadPackFromUrl(url) }
    }

    private fun showShaderUrlDownloadDialog() {
        showUrlInputDialog(
            titleRes = R.string.version_manage_download_shader_url_title,
            hintRes = R.string.version_manage_download_shader_url_hint
        ) { url -> downloadShaderFromUrl(url) }
    }

    private fun showUrlInputDialog(titleRes: Int, hintRes: Int, onConfirm: (String) -> Unit) {
        val density = resources.displayMetrics.density
        val pad = (20 * density).toInt()
        val inputLayout = TextInputLayout(requireContext()).apply {
            hint = getString(hintRes)
            setPadding(pad, pad / 2, pad, 0)
        }
        val edit = TextInputEditText(inputLayout.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            maxLines = 3
            setTextIsSelectable(true)
        }
        inputLayout.addView(edit)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(titleRes)
            .setView(inputLayout)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.version_manage_download_url_start) { _, _ ->
                onConfirm(edit.text?.toString().orEmpty().trim())
            }
            .show()
        edit.requestFocus()
    }

    private fun downloadModFromUrl(url: String) {
        if (!isHttpUrl(url)) {
            Toast.makeText(requireContext(), R.string.version_manage_download_url_invalid, Toast.LENGTH_SHORT).show()
            return
        }
        showDownloadProgress(R.string.version_manage_download_url_progress)
        viewLifecycleOwner.lifecycleScope.launch {
            val result = VersionModsManager.downloadFromUrl(versionId, url)
            dismissDownloadProgress()
            if (_binding == null) return@launch
            result.fold(
                onSuccess = { file ->
                    refreshMods()
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.version_manage_download_url_done, file.name),
                        Toast.LENGTH_SHORT
                    ).show()
                },
                onFailure = { err ->
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.version_manage_download_url_failed, err.message ?: "unknown"),
                        Toast.LENGTH_LONG
                    ).show()
                }
            )
        }
    }

    private fun downloadPackFromUrl(url: String) {
        if (!isHttpUrl(url)) {
            Toast.makeText(requireContext(), R.string.version_manage_download_url_invalid, Toast.LENGTH_SHORT).show()
            return
        }
        showDownloadProgress(R.string.version_manage_download_pack_url_progress)
        viewLifecycleOwner.lifecycleScope.launch {
            val result = VersionResourcePacksManager.downloadFromUrl(versionId, url)
            dismissDownloadProgress()
            if (_binding == null) return@launch
            result.fold(
                onSuccess = { file ->
                    refreshPacks()
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.version_manage_download_url_done, file.name),
                        Toast.LENGTH_SHORT
                    ).show()
                },
                onFailure = { err ->
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.version_manage_download_url_failed, err.message ?: "unknown"),
                        Toast.LENGTH_LONG
                    ).show()
                }
            )
        }
    }

    private fun downloadShaderFromUrl(url: String) {
        if (!isHttpUrl(url)) {
            Toast.makeText(requireContext(), R.string.version_manage_download_url_invalid, Toast.LENGTH_SHORT).show()
            return
        }
        showDownloadProgress(R.string.version_manage_download_shader_url_progress)
        viewLifecycleOwner.lifecycleScope.launch {
            val result = VersionShaderPacksManager.downloadFromUrl(versionId, url)
            dismissDownloadProgress()
            if (_binding == null) return@launch
            result.fold(
                onSuccess = { file ->
                    refreshShaders()
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.version_manage_download_url_done, file.name),
                        Toast.LENGTH_SHORT
                    ).show()
                },
                onFailure = { err ->
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.version_manage_download_url_failed, err.message ?: "unknown"),
                        Toast.LENGTH_LONG
                    ).show()
                }
            )
        }
    }

    private fun showDownloadProgress(messageRes: Int) {
        downloadDialog?.dismiss()
        downloadDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.version_manage_download_url)
            .setMessage(messageRes)
            .setCancelable(false)
            .create()
            .also { it.show() }
    }

    private fun dismissDownloadProgress() {
        downloadDialog?.dismiss()
        downloadDialog = null
    }

    private fun isHttpUrl(url: String): Boolean =
        url.isNotBlank() &&
            (url.startsWith("http://", ignoreCase = true) ||
                url.startsWith("https://", ignoreCase = true))

    private fun toggleMod(mod: VersionModFile) {
        if (!mod.enabled) {
            val blocked = AndroidIncompatibleMods.match(mod)
            if (blocked != null) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.version_manage_mod_android_blocked, blocked.reason),
                    Toast.LENGTH_LONG
                ).show()
                return
            }
        }
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

    private fun confirmUninstallMod(mod: VersionModFile) {
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

    private fun togglePack(pack: VersionResourcePackFile) {
        val result = VersionResourcePacksManager.toggle(pack)
        if (result.isSuccess) {
            refreshPacks()
            Toast.makeText(requireContext(), R.string.version_manage_pack_toggled, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(
                requireContext(),
                getString(R.string.version_manage_action_failed, result.exceptionOrNull()?.message ?: "unknown"),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun confirmUninstallPack(pack: VersionResourcePackFile) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.version_manage_pack_uninstall)
            .setMessage(getString(R.string.version_manage_pack_uninstall_confirm, pack.displayName))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.version_manage_pack_uninstall) { _, _ ->
                val result = VersionResourcePacksManager.uninstall(pack)
                if (result.isSuccess) {
                    refreshPacks()
                    Toast.makeText(requireContext(), R.string.version_manage_pack_uninstalled, Toast.LENGTH_SHORT).show()
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

    private fun toggleShader(pack: VersionResourcePackFile) {
        val result = VersionShaderPacksManager.toggle(pack)
        if (result.isSuccess) {
            refreshShaders()
            Toast.makeText(requireContext(), R.string.version_manage_pack_toggled, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(
                requireContext(),
                getString(R.string.version_manage_action_failed, result.exceptionOrNull()?.message ?: "unknown"),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun confirmUninstallShader(pack: VersionResourcePackFile) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.version_manage_pack_uninstall)
            .setMessage(getString(R.string.version_manage_pack_uninstall_confirm, pack.displayName))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.version_manage_pack_uninstall) { _, _ ->
                val result = VersionShaderPacksManager.uninstall(pack)
                if (result.isSuccess) {
                    refreshShaders()
                    Toast.makeText(requireContext(), R.string.version_manage_pack_uninstalled, Toast.LENGTH_SHORT).show()
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
            "release" -> getString(R.string.download_type_release)
            "snapshot" -> getString(R.string.download_type_snapshot)
            "old_beta", "old_alpha" -> getString(R.string.download_type_old)
            else -> type.ifBlank { getString(R.string.version_manage_unknown) }
        }
    }

    override fun onDestroyView() {
        downloadDialog?.dismiss()
        downloadDialog = null
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val ARG_VERSION_ID = "versionId"
    }
}
