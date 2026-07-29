package com.booxin.launcher.ui.multiplayer

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.booxin.launcher.databinding.ItemMultiplayerUserBinding

data class MultiplayerListItem(
    val id: String,
    val name: String,
    val meta: String,
    val avatarUrl: String? = null,
    val frameId: String? = null,
    val primaryLabel: String? = null,
    val secondaryLabel: String? = null,
    val payload: Any? = null
)

class MultiplayerUserAdapter(
    private val onPrimary: (MultiplayerListItem) -> Unit = {},
    private val onSecondary: (MultiplayerListItem) -> Unit = {},
    private val onItemClick: (MultiplayerListItem) -> Unit = {}
) : RecyclerView.Adapter<MultiplayerUserAdapter.Holder>() {

    private val items = mutableListOf<MultiplayerListItem>()

    fun submit(list: List<MultiplayerListItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemMultiplayerUserBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.binding.textName.text = item.name
        holder.binding.textMeta.text = item.meta
        holder.binding.imageAvatar.loadBooxinAvatar(item.avatarUrl)
        holder.binding.imageFrame.applyBooxinFrame(item.frameId)
        val hasActions = !item.primaryLabel.isNullOrBlank() || !item.secondaryLabel.isNullOrBlank()
        holder.binding.rowActions.isVisible = hasActions
        holder.binding.buttonPrimary.isVisible = !item.primaryLabel.isNullOrBlank()
        holder.binding.buttonPrimary.text = item.primaryLabel
        holder.binding.buttonPrimary.setOnClickListener { onPrimary(item) }
        holder.binding.buttonSecondary.isVisible = !item.secondaryLabel.isNullOrBlank()
        holder.binding.buttonSecondary.text = item.secondaryLabel
        holder.binding.buttonSecondary.setOnClickListener { onSecondary(item) }
        holder.binding.root.setOnClickListener { onItemClick(item) }
    }

    override fun getItemCount(): Int = items.size

    class Holder(val binding: ItemMultiplayerUserBinding) : RecyclerView.ViewHolder(binding.root)
}
