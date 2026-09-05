package com.booxin.launcher.ui.launch

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.booxin.launcher.R
import com.booxin.launcher.data.model.ModrinthProject
import com.booxin.launcher.databinding.ItemCommunityProjectIngameBinding
import java.text.NumberFormat
import java.util.Locale

/** 游戏内社区两列卡片（不加载网络图标，避免 :game 进程 OOM）。 */
class InGameCommunityAdapter(
    private val onInstall: (ModrinthProject) -> Unit
) : RecyclerView.Adapter<InGameCommunityAdapter.ViewHolder>() {

    private val items = ArrayList<ModrinthProject>()

    fun submit(list: List<ModrinthProject>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemCommunityProjectIngameBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding, onInstall)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(
        private val binding: ItemCommunityProjectIngameBinding,
        private val onInstall: (ModrinthProject) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ModrinthProject) {
            binding.textTitle.text = item.title
            binding.textMeta.text = binding.root.context.getString(
                R.string.community_project_meta,
                item.author.ifBlank { "unknown" },
                NumberFormat.getNumberInstance(Locale.getDefault()).format(item.downloads)
            )
            binding.imageIcon.setImageResource(R.drawable.ic_nav_versions)
            binding.root.setOnClickListener { onInstall(item) }
            binding.buttonInstall.setOnClickListener { onInstall(item) }
        }
    }
}
