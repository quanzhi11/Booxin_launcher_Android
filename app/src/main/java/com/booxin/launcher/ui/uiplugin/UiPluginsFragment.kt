package com.booxin.launcher.ui.uiplugin

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.booxin.launcher.R
import com.booxin.launcher.core.uiplugin.UiPluginFonts
import com.booxin.launcher.core.uiplugin.UiPluginInstall
import com.booxin.launcher.core.uiplugin.UiPluginManager
import com.booxin.launcher.core.uiplugin.UiPluginTheme
import com.booxin.launcher.databinding.FragmentUiPluginsBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class UiPluginsFragment : Fragment() {

    private var _binding: FragmentUiPluginsBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: UiPluginsAdapter

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
            onDelete = { confirmDelete(it) }
        )
        binding.recyclerUiPlugins.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerUiPlugins.adapter = adapter
        binding.buttonRefreshUiPlugins.setOnClickListener { refresh() }
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
        }
    }

    private fun toggle(plugin: UiPluginInstall) {
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                UiPluginManager.setEnabled(plugin.manifest.id, !plugin.enabled)
            }
            val msg = if (plugin.enabled) {
                getString(R.string.ui_plugin_disabled, plugin.manifest.name)
            } else {
                getString(R.string.ui_plugin_enabled, plugin.manifest.name)
            }
            toast(msg)
            refresh()
            reapplyFonts()
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
