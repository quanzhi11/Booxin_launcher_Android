package com.booxin.launcher.ui.versions

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.booxin.launcher.R
import com.booxin.launcher.data.model.GameVersion
import com.booxin.launcher.data.model.VersionType
import com.booxin.launcher.databinding.ItemVersionBinding

class VersionsAdapter(
    private val selectedIdProvider: () -> String? = { null },
    private val installedMode: Boolean = true,
    private val onMenu: ((GameVersion) -> Unit)? = null,
    private val onManage: ((GameVersion) -> Unit)? = null,
    private val onClick: (GameVersion) -> Unit
) : RecyclerView.Adapter<VersionsAdapter.Holder>() {

    private val items = mutableListOf<GameVersion>()

    fun submit(list: List<GameVersion>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun currentIds(): List<String> = items.map { it.id }

    fun moveItem(from: Int, to: Int) {
        if (from == to) return
        if (from !in items.indices || to !in items.indices) return
        val item = items.removeAt(from)
        items.add(to, item)
        notifyItemMoved(from, to)
    }

    fun itemAt(position: Int): GameVersion? = items.getOrNull(position)

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
            binding.textVersionName.text = item.displayName
            val typeLabel = when (item.type) {
                VersionType.RELEASE -> context.getString(R.string.download_type_release)
                VersionType.SNAPSHOT -> context.getString(R.string.download_type_snapshot)
                VersionType.OLD_BETA, VersionType.OLD_ALPHA ->
                    context.getString(R.string.download_type_old)
            }
            val selected = selectedIdProvider() == item.id
            val timeHint = item.releaseTime
                ?.takeIf { it.isNotBlank() }
                ?.let { " · ${it.take(10)}" }
                .orEmpty()
            val idHint = if (item.customDisplayName.isNullOrBlank()) {
                ""
            } else {
                " · ${item.id}"
            }
            binding.textVersionMeta.text = when {
                installedMode && item.isDefault && selected ->
                    "$typeLabel$timeHint$idHint · ${context.getString(R.string.versions_default)} · ${context.getString(R.string.versions_selected)}"
                installedMode && item.isDefault ->
                    "$typeLabel$timeHint$idHint · ${context.getString(R.string.versions_default)}"
                installedMode && selected ->
                    "$typeLabel$timeHint$idHint · ${context.getString(R.string.versions_selected)}"
                installedMode ->
                    "$typeLabel$timeHint$idHint · ${context.getString(R.string.versions_tap_select)}"
                item.installed ->
                    "$typeLabel$timeHint · ${context.getString(R.string.download_already_installed)}"
                else ->
                    "$typeLabel$timeHint · ${context.getString(R.string.download_tap_install)}"
            }
            binding.textVersionBadge.text = if (installedMode && item.isDefault) {
                context.getString(R.string.versions_default)
            } else {
                typeLabel
            }
            binding.root.setOnClickListener { onClick(item) }
            binding.root.setOnLongClickListener(null)
            if (installedMode) {
                binding.buttonManageVersion.visibility = View.VISIBLE
                binding.buttonManageVersion.text = context.getString(R.string.version_manage_entry)
                binding.buttonManageVersion.setOnClickListener { onManage?.invoke(item) }
                binding.buttonVersionMore.visibility = View.VISIBLE
                binding.buttonVersionMore.setOnClickListener { onMenu?.invoke(item) }
            } else {
                binding.buttonVersionMore.visibility = View.GONE
                binding.buttonManageVersion.visibility = View.VISIBLE
                binding.buttonManageVersion.text = context.getString(R.string.download_action_install)
                binding.buttonManageVersion.setOnClickListener { onClick(item) }
            }
        }
    }
}
