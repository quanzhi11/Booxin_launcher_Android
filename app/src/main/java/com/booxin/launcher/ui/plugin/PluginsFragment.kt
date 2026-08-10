package com.booxin.launcher.ui.plugin

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
import androidx.recyclerview.widget.LinearLayoutManager
import com.booxin.launcher.R
import com.booxin.launcher.core.plugin.PluginDescriptor
import com.booxin.launcher.core.plugin.PluginManager
import com.booxin.launcher.core.plugin.PluginSource
import com.booxin.launcher.databinding.FragmentPluginsBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class PluginsFragment : Fragment() {

    private var _binding: FragmentPluginsBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: PluginsAdapter

    private val pickApk = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        importApk(uri)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPluginsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = PluginsAdapter(
            onPrimary = { downloadOrReinstall(it) },
            onToggle = { toggle(it) },
            onUninstall = { confirmUninstall(it) }
        )
        binding.recyclerPlugins.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerPlugins.adapter = adapter
        binding.buttonRefreshPlugins.setOnClickListener { refresh(force = true) }
        binding.buttonImportPlugin.setOnClickListener { pickApk.launch("application/vnd.android.package-archive") }
        refresh(force = false)
    }

    private fun refresh(force: Boolean) {
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                if (force || PluginManager.all().isEmpty()) {
                    PluginManager.refresh(requireContext())
                } else {
                    PluginManager.init(requireContext())
                }
            }
            val list = PluginManager.all()
            adapter.submit(list)
            binding.textPluginsEmpty.isVisible = list.isEmpty()
            binding.recyclerPlugins.isVisible = list.isNotEmpty()
            if (list.isNotEmpty()) {
                binding.pluginsAppBar.setExpanded(true, false)
            }
        }
    }

    private fun downloadOrReinstall(plugin: PluginDescriptor) {
        val url = plugin.downloadUrl
        if (url.isNullOrBlank()) {
            toast(getString(R.string.plugins_no_download_url))
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            toast(getString(R.string.plugins_downloading, plugin.name))
            val result = if (plugin.kind != null) {
                PluginManager.installBuiltin(requireContext(), plugin.kind!!)
            } else {
                PluginManager.installFromUrl(requireContext(), plugin.id, url, plugin.name)
            }
            if (result.isSuccess) {
                toast(getString(R.string.plugins_install_done, plugin.name))
                refresh(force = true)
            } else {
                toast(
                    getString(
                        R.string.plugins_install_failed,
                        result.exceptionOrNull()?.message ?: "unknown"
                    )
                )
            }
        }
    }

    private fun toggle(plugin: PluginDescriptor) {
        viewLifecycleOwner.lifecycleScope.launch {
            PluginManager.setEnabled(requireContext(), plugin.id, !plugin.enabled)
            refresh(force = true)
        }
    }

    private fun confirmUninstall(plugin: PluginDescriptor) {
        if (plugin.source == PluginSource.PACKAGE) {
            toast(getString(R.string.plugins_uninstall_package_hint))
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.plugins_action_uninstall)
            .setMessage(getString(R.string.plugins_uninstall_confirm, plugin.name))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.plugins_action_uninstall) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    PluginManager.uninstall(requireContext(), plugin.id)
                    toast(getString(R.string.plugins_uninstalled, plugin.name))
                    refresh(force = true)
                }
            }
            .show()
    }

    private fun importApk(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val cache = File(requireContext().cacheDir, "import-plugin.apk")
                    requireContext().contentResolver.openInputStream(uri)?.use { input ->
                        cache.outputStream().use { output -> input.copyTo(output) }
                    } ?: error("无法读取 APK")
                    PluginManager.installFromApk(requireContext(), cache).getOrThrow()
                }
            }
            if (result.isSuccess) {
                toast(getString(R.string.plugins_install_done, result.getOrNull()?.name ?: ""))
                refresh(force = true)
            } else {
                toast(
                    getString(
                        R.string.plugins_install_failed,
                        result.exceptionOrNull()?.message ?: "unknown"
                    )
                )
            }
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
