package com.booxin.launcher.ui.community

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.booxin.launcher.R
import com.booxin.launcher.core.community.CommunityDescriptionTranslator
import com.booxin.launcher.data.model.ModrinthProject
import com.booxin.launcher.databinding.ItemCommunityProjectBinding
import java.text.NumberFormat
import java.util.Locale
import kotlinx.coroutines.CoroutineScope

class CommunityAdapter(
    private val scope: CoroutineScope,
    private val onInstall: (ModrinthProject) -> Unit
) : RecyclerView.Adapter<CommunityAdapter.ViewHolder>() {

    private val items = ArrayList<ModrinthProject>()

    fun submit(list: List<ModrinthProject>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemCommunityProjectBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding, scope, onInstall)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(
        private val binding: ItemCommunityProjectBinding,
        private val scope: CoroutineScope,
        private val onInstall: (ModrinthProject) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ModrinthProject) {
            binding.textTitle.text = item.title
            CommunityDescriptionTranslator.bind(
                binding.textDescription,
                item.description,
                scope
            )
            binding.textMeta.text = binding.root.context.getString(
                R.string.community_project_meta,
                item.author.ifBlank { "unknown" },
                NumberFormat.getNumberInstance(Locale.getDefault()).format(item.downloads)
            )
            binding.textTags.text = buildList {
                addAll(item.loaders.take(2))
                addAll(item.gameVersions.take(2))
                addAll(item.categories.take(2))
            }.distinct().joinToString(" · ").ifBlank {
                binding.root.context.getString(R.string.community_tag_placeholder)
            }
            binding.imageIcon.load(item.iconUrl) {
                crossfade(true)
                placeholder(R.drawable.ic_nav_versions)
                error(R.drawable.ic_nav_versions)
            }
            binding.root.setOnClickListener { onInstall(item) }
            binding.buttonInstall.setOnClickListener { onInstall(item) }
        }
    }
}
