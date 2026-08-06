package com.booxin.launcher.ui.versions.manage

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.booxin.launcher.R
import com.booxin.launcher.core.version.VersionResourcePackFile
import com.booxin.launcher.databinding.ItemVersionModBinding

class VersionResourcePacksAdapter(
    private val onToggle: (VersionResourcePackFile) -> Unit,
    private val onUninstall: (VersionResourcePackFile) -> Unit
) : RecyclerView.Adapter<VersionResourcePacksAdapter.Holder>() {

    private val items = ArrayList<VersionResourcePackFile>()

    fun submit(list: List<VersionResourcePackFile>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemVersionModBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class Holder(
        private val binding: ItemVersionModBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: VersionResourcePackFile) {
            val context = binding.root.context
            binding.textModName.text = item.displayName
            binding.textModStatus.text = if (item.enabled) {
                context.getString(R.string.version_manage_pack_enabled)
            } else {
                context.getString(R.string.version_manage_pack_disabled)
            }
            binding.buttonToggle.text = if (item.enabled) {
                context.getString(R.string.version_manage_pack_disable)
            } else {
                context.getString(R.string.version_manage_pack_enable)
            }
            binding.buttonToggle.setOnClickListener { onToggle(item) }
            binding.buttonUninstall.text = context.getString(R.string.version_manage_pack_uninstall)
            binding.buttonUninstall.setOnClickListener { onUninstall(item) }
        }
    }
}
