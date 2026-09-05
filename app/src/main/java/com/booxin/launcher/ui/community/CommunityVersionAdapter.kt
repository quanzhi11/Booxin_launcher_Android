package com.booxin.launcher.ui.community

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.booxin.launcher.data.model.ModrinthProjectVersion
import com.booxin.launcher.databinding.ItemCommunityVersionBinding
import com.google.android.material.card.MaterialCardView

class CommunityVersionAdapter(
    private val onInstall: (ModrinthProjectVersion) -> Unit,
    private val onSelect: (ModrinthProjectVersion) -> Unit
) : RecyclerView.Adapter<CommunityVersionAdapter.ViewHolder>() {

    private val items = ArrayList<Pair<ModrinthProjectVersion, String?>>()
    private var selectedId: String? = null

    fun submit(list: List<Pair<ModrinthProjectVersion, String?>>, selectedVersionId: String?) {
        items.clear()
        items.addAll(list)
        selectedId = selectedVersionId
        notifyDataSetChanged()
    }

    fun select(versionId: String) {
        selectedId = versionId
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemCommunityVersionBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding, onInstall, onSelect)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val (version, recommend) = items[position]
        holder.bind(version, recommend, version.id == selectedId)
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(
        private val binding: ItemCommunityVersionBinding,
        private val onInstall: (ModrinthProjectVersion) -> Unit,
        private val onSelect: (ModrinthProjectVersion) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(version: ModrinthProjectVersion, recommend: String?, selected: Boolean) {
            val number = version.versionNumber.ifBlank { version.name }
            val title = version.name.ifBlank { number }
            // Always surface the real version number; name alone is often a changelog title.
            binding.textName.text = if (
                number.isNotBlank() &&
                !title.equals(number, ignoreCase = true) &&
                !title.contains(number)
            ) {
                "$number · $title"
            } else {
                number.ifBlank { title }
            }
            binding.textMeta.text = buildList {
                add(version.versionType)
                addAll(version.loaders.take(3))
                addAll(version.gameVersions.take(4))
            }.joinToString(" · ")
            binding.textRecommend.isVisible = !recommend.isNullOrBlank()
            binding.textRecommend.text = recommend.orEmpty()
            val card = binding.root as MaterialCardView
            card.strokeWidth = if (selected) {
                (2 * binding.root.resources.displayMetrics.density).toInt()
            } else {
                (1 * binding.root.resources.displayMetrics.density).toInt()
            }
            binding.root.setOnClickListener { onSelect(version) }
            binding.buttonInstall.setOnClickListener { onInstall(version) }
        }
    }
}
