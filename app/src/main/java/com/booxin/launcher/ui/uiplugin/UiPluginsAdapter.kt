package com.booxin.launcher.ui.uiplugin

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.booxin.launcher.R
import com.booxin.launcher.core.uiplugin.UiPluginInstall
import com.booxin.launcher.databinding.ItemUiPluginBinding

class UiPluginsAdapter(
    private val onToggle: (UiPluginInstall) -> Unit,
    private val onUpdate: (UiPluginInstall) -> Unit,
    private val onDelete: (UiPluginInstall) -> Unit,
    private val onOpenPages: (UiPluginInstall) -> Unit
) : RecyclerView.Adapter<UiPluginsAdapter.Holder>() {

    private val items = ArrayList<UiPluginInstall>()

    fun submit(list: List<UiPluginInstall>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemUiPluginBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    override fun getItemCount(): Int = items.size

    inner class Holder(
        private val binding: ItemUiPluginBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: UiPluginInstall) {
            val ctx = binding.root.context
            val m = item.manifest
            binding.textUiPluginName.text = m.name
            val status = if (item.enabled) {
                ctx.getString(R.string.plugins_status_enabled)
            } else {
                ctx.getString(R.string.plugins_status_disabled)
            }
            val typeLabel = m.type.ifBlank { "ui" }
            binding.textUiPluginDetail.text = buildString {
                append(typeLabel)
                append(" · ")
                append(status)
                append(" · v")
                append(m.version)
                if (m.jsCommands) append(" · JS")
                if (m.pages.isNotEmpty()) {
                    append(" · ")
                    append(m.pages.size)
                    append("页")
                }
                val storeVer = item.storeVersion
                if (!storeVer.isNullOrBlank() && storeVer != m.version) {
                    append(" · 商店 v")
                    append(storeVer)
                } else if (!item.storePluginId.isNullOrBlank()) {
                    append(" · 商店")
                }
            }
            val desc = m.description.trim()
            binding.textUiPluginDesc.isVisible = desc.isNotEmpty()
            binding.textUiPluginDesc.text = desc

            binding.buttonUiPluginToggle.text = if (item.enabled) {
                ctx.getString(R.string.plugins_action_disable)
            } else {
                ctx.getString(R.string.plugins_action_enable)
            }
            val hasPages = item.enabled && m.pages.isNotEmpty()
            binding.buttonUiPluginPages.isVisible = hasPages
            binding.buttonUiPluginUpdate.isEnabled = !item.storePluginId.isNullOrBlank()
            binding.buttonUiPluginUpdate.alpha =
                if (item.storePluginId.isNullOrBlank()) 0.4f else 1f
            binding.buttonUiPluginToggle.setOnClickListener { onToggle(item) }
            binding.buttonUiPluginUpdate.setOnClickListener { onUpdate(item) }
            binding.buttonUiPluginDelete.setOnClickListener { onDelete(item) }
            binding.buttonUiPluginPages.setOnClickListener { onOpenPages(item) }
        }
    }
}
