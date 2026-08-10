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
    private val onDelete: (UiPluginInstall) -> Unit
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
            }
            val desc = m.description.trim()
            binding.textUiPluginDesc.isVisible = desc.isNotEmpty()
            binding.textUiPluginDesc.text = desc

            binding.buttonUiPluginToggle.text = if (item.enabled) {
                ctx.getString(R.string.plugins_action_disable)
            } else {
                ctx.getString(R.string.plugins_action_enable)
            }
            binding.buttonUiPluginToggle.setOnClickListener { onToggle(item) }
            binding.buttonUiPluginDelete.setOnClickListener { onDelete(item) }
        }
    }
}
