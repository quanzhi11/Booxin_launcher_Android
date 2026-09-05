package com.booxin.launcher.ui.uiplugin

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.booxin.launcher.R
import com.booxin.launcher.core.net.FileDownloader
import com.booxin.launcher.core.pluginstore.PluginStoreApi
import com.booxin.launcher.core.uiplugin.UiPluginFonts
import com.booxin.launcher.core.uiplugin.UiPluginInstall
import com.booxin.launcher.core.uiplugin.UiPluginManager
import com.booxin.launcher.core.uiplugin.UiPluginPermissionStore
import com.booxin.launcher.core.uiplugin.UiPluginTheme
import com.booxin.launcher.databinding.FragmentUiPluginsBinding
import com.booxin.launcher.ui.MainActivity
import com.booxin.launcher.ui.pluginpage.PluginPageFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class UiPluginsFragment : Fragment() {

    private var _binding: FragmentUiPluginsBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: UiPluginsAdapter
    private val storeApi = PluginStoreApi()

    private val pickZip = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        importZip(uri)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentUiPluginsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = UiPluginsAdapter(
            onToggle = { toggle(it) },
            onUpdate = { updateFromStore(it) },
            onDelete = { confirmDelete(it) },
            onOpenPages = { openPages(it) }
        )
        binding.recyclerUiPlugins.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerUiPlugins.adapter = adapter
        binding.buttonRefreshUiPlugins.setOnClickListener { refresh() }
        binding.buttonImportUiPlugin.setOnClickListener {
            pickZip.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*"))
        }
        refresh()
    }

    fun refresh() {
        if (_binding == null) return
        viewLifecycleOwner.lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) { UiPluginManager.listInstalled() }
            val b = _binding ?: return@launch
            adapter.submit(list)
            b.textUiPluginsEmpty.isVisible = list.isEmpty()
            b.recyclerUiPlugins.isVisible = list.isNotEmpty()
            if (list.isNotEmpty()) {
                b.uiPluginsAppBar.setExpanded(true, false)
            }
            (activity as? MainActivity)?.refreshPluginPageNav()
        }
    }

    private fun importZip(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val tmp = File(requireContext().cacheDir, "ui-plugin-import-${System.currentTimeMillis()}.zip")
                    requireContext().contentResolver.openInputStream(uri)?.use { input ->
                        tmp.outputStream().use { output -> input.copyTo(output) }
                    } ?: error("无法读取文件")
                    require(tmp.isFile && tmp.length() > 0L) { "空文件" }
                    UiPluginManager.installFromZip(tmp).getOrThrow()
                }
            }
            result.onSuccess { installed ->
                toast(getString(R.string.ui_plugins_import_ok, installed.manifest.name))
                refresh()
                reapplyFonts()
            }.onFailure { err ->
                toast(getString(R.string.ui_plugins_import_failed, err.message ?: "unknown"))
            }
        }
    }

    private fun updateFromStore(plugin: UiPluginInstall) {
        val storeId = plugin.storePluginId
        if (storeId.isNullOrBlank()) {
            toast(getString(R.string.ui_plugin_update_not_from_store))
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            toast(getString(R.string.ui_plugin_updating, plugin.manifest.name))
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val remote = storeApi.getPlugin(storeId).getOrThrow()
                    val localVer = plugin.storeVersion ?: plugin.manifest.version
                    val remoteVer = remote.version.ifBlank { "1.0.0" }
                    val url = remote.downloadUrl.trim()
                    require(url.isNotEmpty()) { "商店未配置下载链接" }
                    val dest = File(requireContext().cacheDir, "store-update-${remote.id}.bin")
                    val downloaded = FileDownloader()
                        .downloadResolved(url, dest, accelerate = false)
                        .getOrThrow()
                    val installed = UiPluginManager.installFromZip(downloaded.file).getOrThrow()
                    UiPluginManager.setEnabled(installed.manifest.id, plugin.enabled)
                    UiPluginManager.writeStoreMeta(
                        pluginId = installed.manifest.id,
                        storePluginId = remote.id,
                        storeVersion = remoteVer
                    )
                    Triple(installed.manifest.name, localVer, remoteVer)
                }
            }
            result.onSuccess { (name, localVer, remoteVer) ->
                val msg = if (UiPluginManager.compareVersions(remoteVer, localVer) > 0) {
                    getString(R.string.ui_plugin_updated_from_store, name, localVer, remoteVer)
                } else {
                    getString(R.string.ui_plugin_reinstalled_from_store, name, remoteVer)
                }
                toast(msg)
                refresh()
                reapplyFonts()
            }.onFailure { err ->
                toast(getString(R.string.ui_plugin_update_failed, err.message ?: "unknown"))
            }
        }
    }

    private fun toggle(plugin: UiPluginInstall) {
        if (plugin.enabled) {
            // Disabling: revoke js grant optional? Keep grant for next enable.
            applyEnabled(plugin, enabled = false)
            return
        }
        if (plugin.manifest.jsCommands) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.ui_plugin_js_permission_title)
                .setMessage(getString(R.string.ui_plugin_js_permission_message, plugin.manifest.name))
                .setNegativeButton(R.string.ui_plugin_js_permission_deny) { _, _ ->
                    UiPluginPermissionStore.setJsCommandsGranted(
                        requireContext(),
                        plugin.manifest.id,
                        false
                    )
                    applyEnabled(plugin, enabled = true)
                }
                .setPositiveButton(R.string.ui_plugin_js_permission_allow) { _, _ ->
                    UiPluginPermissionStore.setJsCommandsGranted(
                        requireContext(),
                        plugin.manifest.id,
                        true
                    )
                    applyEnabled(plugin, enabled = true)
                }
                .show()
            return
        }
        applyEnabled(plugin, enabled = true)
    }

    private fun applyEnabled(plugin: UiPluginInstall, enabled: Boolean) {
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                UiPluginManager.setEnabled(plugin.manifest.id, enabled)
            }
            val msg = if (enabled) {
                getString(R.string.ui_plugin_enabled, plugin.manifest.name)
            } else {
                getString(R.string.ui_plugin_disabled, plugin.manifest.name)
            }
            toast(msg)
            refresh()
            reapplyFonts()
        }
    }

    private fun openPages(plugin: UiPluginInstall) {
        val pages = plugin.manifest.pages.filter {
            UiPluginManager.resolvePageEntry(plugin, it) != null
        }
        if (pages.isEmpty()) {
            toast(getString(R.string.ui_plugin_pages_none))
            return
        }
        if (pages.size == 1) {
            navigateToPage(plugin.manifest.id, pages[0].id)
            return
        }
        val labels = pages.map { it.title }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.ui_plugin_pages_title)
            .setItems(labels) { _, which ->
                navigateToPage(plugin.manifest.id, pages[which].id)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun navigateToPage(pluginId: String, pageId: String) {
        runCatching {
            findNavController().navigate(
                R.id.nav_plugin_page,
                PluginPageFragment.args(pluginId, pageId)
            )
        }.onFailure {
            // Hosted inside plugin store nested host — climb to activity nav.
            (activity as? MainActivity)?.openPluginPage(pluginId, pageId)
        }
    }

    private fun confirmDelete(plugin: UiPluginInstall) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.plugins_action_uninstall)
            .setMessage(getString(R.string.ui_plugin_delete_confirm, plugin.manifest.name))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.plugins_action_uninstall) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        UiPluginPermissionStore.revokeAllForPlugin(
                            requireContext(),
                            plugin.manifest.id
                        )
                        UiPluginManager.uninstall(plugin.manifest.id)
                    }
                    toast(getString(R.string.plugins_uninstalled, plugin.manifest.name))
                    refresh()
                    reapplyFonts()
                }
            }
            .show()
    }

    private fun reapplyFonts() {
        UiPluginFonts.invalidate()
        UiPluginTheme.invalidate()
        UiPluginFonts.applyTo(activity)
        UiPluginTheme.applyTo(activity)
    }

    private fun toast(msg: String) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
