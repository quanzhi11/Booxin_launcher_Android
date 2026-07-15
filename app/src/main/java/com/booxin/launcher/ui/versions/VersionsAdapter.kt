package com.booxin.launcher.ui.versions

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.booxin.launcher.data.model.GameVersion
import com.booxin.launcher.data.model.VersionType
import com.booxin.launcher.databinding.ItemVersionBinding

class VersionsAdapter(
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
            binding.textVersionName.text = item.id
            val status = if (item.installed) "已安装" else "未安装"
            val typeLabel = when (item.type) {
                VersionType.RELEASE -> "正式版"
                VersionType.SNAPSHOT -> "快照"
                VersionType.OLD_BETA -> "旧 Beta"
                VersionType.OLD_ALPHA -> "旧 Alpha"
            }
            binding.textVersionMeta.text = "$typeLabel · $status"
            binding.textVersionBadge.text = item.type.name.lowercase()
            binding.root.setOnClickListener { onClick(item) }
        }
    }
}
