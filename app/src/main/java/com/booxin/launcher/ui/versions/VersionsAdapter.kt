package com.booxin.launcher.ui.versions

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.booxin.launcher.R
import com.booxin.launcher.data.model.GameVersion
import com.booxin.launcher.data.model.VersionType
import com.booxin.launcher.databinding.ItemVersionBinding

class VersionsAdapter(
    private val selectedIdProvider: () -> String? = { null },
    private val installedMode: Boolean = true,
    private val onDelete: ((GameVersion) -> Unit)? = null,
    private val onClick: (GameVersion) -> Unit
) : RecyclerView.Adapter<VersionsAdapter.Holder>() {

    private val items = mutableListOf<GameVersion>()

    fun submit(list: List<GameVersion>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemVersionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class Holder(
        private val binding: ItemVersionBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: GameVersion) {
            val context = binding.root.context
            binding.textVersionName.text = item.id
            val typeLabel = when (item.type) {
                VersionType.RELEASE -> context.getString(R.string.download_filter_release)
                VersionType.SNAPSHOT -> context.getString(R.string.download_filter_snapshot)
                VersionType.OLD_BETA, VersionType.OLD_ALPHA ->
                    context.getString(R.string.download_filter_old)
            }
            val selected = selectedIdProvider() == item.id
            binding.textVersionMeta.text = when {
                installedMode && selected ->
                    "$typeLabel · ${context.getString(R.string.versions_selected)}"
                installedMode ->
                    "$typeLabel · ${context.getString(R.string.versions_tap_select)}"
                item.installed ->
                    "$typeLabel · ${context.getString(R.string.download_already_installed)}"
                else ->
                    "$typeLabel · ${context.getString(R.string.download_tap_install)}"
            }
            binding.textVersionBadge.text = item.type.name.lowercase()
            binding.root.setOnClickListener { onClick(item) }
            binding.root.setOnLongClickListener {
                if (installedMode && onDelete != null) {
                    onDelete.invoke(item)
                    true
                } else {
                    false
                }
            }
        }
    }
}
