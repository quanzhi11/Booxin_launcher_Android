package com.booxin.launcher.ui.community

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.booxin.launcher.R
import com.booxin.launcher.core.community.CommunityDescriptionTranslator
import com.booxin.launcher.data.model.ModrinthResolvedDependency
import com.booxin.launcher.databinding.ItemCommunityDependencyBinding
import kotlinx.coroutines.CoroutineScope

class CommunityDependencyAdapter(
    private val scope: CoroutineScope,
    private val onClick: (ModrinthResolvedDependency) -> Unit
) : RecyclerView.Adapter<CommunityDependencyAdapter.ViewHolder>() {

    private val items = ArrayList<ModrinthResolvedDependency>()

    fun submit(list: List<ModrinthResolvedDependency>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemCommunityDependencyBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding, scope, onClick)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(
        private val binding: ItemCommunityDependencyBinding,
        private val scope: CoroutineScope,
        private val onClick: (ModrinthResolvedDependency) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ModrinthResolvedDependency) {
            binding.textTitle.text = item.title
            CommunityDescriptionTranslator.bind(
                binding.textDescription,
                item.description,
                scope
            )
            binding.imageIcon.load(item.iconUrl) {
                crossfade(true)
                placeholder(R.drawable.ic_nav_versions)
                error(R.drawable.ic_nav_versions)
            }
            binding.root.setOnClickListener { onClick(item) }
        }
    }
}
