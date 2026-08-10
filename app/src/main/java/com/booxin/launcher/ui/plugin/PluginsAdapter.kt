package com.booxin.launcher.ui.plugin

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.booxin.launcher.R
import com.booxin.launcher.core.plugin.PluginDescriptor
import com.booxin.launcher.core.plugin.PluginSource
import com.booxin.launcher.core.plugin.PluginType
import com.booxin.launcher.databinding.ItemPluginBinding

class PluginsAdapter(
    private val onPrimary: (PluginDescriptor) -> Unit,
    private val onToggle: (PluginDescriptor) -> Unit,
    private val onUninstall: (PluginDescriptor) -> Unit
) : RecyclerView.Adapter<PluginsAdapter.Holder>() {

    private val items = ArrayList<PluginDescriptor>()

    fun submit(list: List<PluginDescriptor>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemPluginBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    override fun getItemCount(): Int = items.size

    inner class Holder(private val binding: ItemPluginBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: PluginDescriptor) {
            val ctx = binding.root.context
            binding.textPluginName.text = item.name
            val typeLabel = when (item.type) {
                PluginType.RENDERER -> ctx.getString(R.string.plugins_type_renderer)
                PluginType.DRIVER -> ctx.getString(R.string.plugins_type_driver)
            }
            val sourceLabel = when (item.source) {
                PluginSource.BUILTIN -> ctx.getString(R.string.plugins_source_builtin)
                PluginSource.LOCAL -> ctx.getString(R.string.plugins_source_local)
                PluginSource.PACKAGE -> ctx.getString(R.string.plugins_source_package)
            }
            val status = when {
                !item.installed -> ctx.getString(R.string.plugins_status_missing)
                item.enabled -> ctx.getString(R.string.plugins_status_enabled)
                else -> ctx.getString(R.string.plugins_status_disabled)
            }
            binding.textPluginDetail.text = buildString {
                append(typeLabel)
                append(" · ")
                append(status)
                append(" · ")
                append(sourceLabel)
                append(" · v")
                append(item.version)
                item.packageName?.let {
                    append('\n')
                    append(it)
                }
            }

            val canDownload = !item.installed && !item.downloadUrl.isNullOrBlank()
            val canUninstall = item.installed && item.source != PluginSource.PACKAGE
            binding.buttonPluginAction.isVisible = canDownload || item.installed
            binding.buttonPluginAction.text = when {
                canDownload -> ctx.getString(R.string.plugins_action_download)
                else -> ctx.getString(R.string.plugins_action_reinstall)
            }
            binding.buttonPluginAction.isEnabled = canDownload ||
                (item.installed && !item.downloadUrl.isNullOrBlank())
            binding.buttonPluginAction.alpha = if (binding.buttonPluginAction.isEnabled) 1f else 0.4f
            binding.buttonPluginAction.setOnClickListener { onPrimary(item) }

            binding.buttonPluginToggle.isVisible = item.installed
            binding.buttonPluginToggle.text = if (item.enabled) {
                ctx.getString(R.string.plugins_action_disable)
            } else {
                ctx.getString(R.string.plugins_action_enable)
            }
            binding.buttonPluginToggle.setOnClickListener { onToggle(item) }

            binding.buttonPluginUninstall.isVisible = canUninstall
            binding.buttonPluginUninstall.setOnClickListener { onUninstall(item) }
        }
    }
}
