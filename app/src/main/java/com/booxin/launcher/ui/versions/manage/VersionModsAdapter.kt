package com.booxin.launcher.ui.versions.manage

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.booxin.launcher.R
import com.booxin.launcher.core.version.VersionModFile
import com.booxin.launcher.databinding.ItemVersionModBinding

class VersionModsAdapter(
    private val onToggle: (VersionModFile) -> Unit,
    private val onUninstall: (VersionModFile) -> Unit
) : RecyclerView.Adapter<VersionModsAdapter.Holder>() {

    private val items = ArrayList<VersionModFile>()

    fun submit(list: List<VersionModFile>) {
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
        fun bind(item: VersionModFile) {
            val context = binding.root.context
            binding.textModName.text = item.displayName
            binding.textModStatus.text = if (item.enabled) {
                context.getString(R.string.version_manage_mod_enabled)
            } else {
                context.getString(R.string.version_manage_mod_disabled)
            }
            binding.buttonToggle.text = if (item.enabled) {
                context.getString(R.string.version_manage_mod_disable)
            } else {
                context.getString(R.string.version_manage_mod_enable)
            }
            binding.buttonToggle.setOnClickListener { onToggle(item) }
            binding.buttonUninstall.setOnClickListener { onUninstall(item) }
        }
    }
}
