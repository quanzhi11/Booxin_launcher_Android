package com.booxin.launcher.ui.multiplayer

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.booxin.launcher.core.multiplayer.PublicRoom
import com.booxin.launcher.databinding.ItemPublicRoomCardBinding

class PublicRoomCardAdapter(
    private val onRoomClick: (PublicRoom) -> Unit
) : RecyclerView.Adapter<PublicRoomCardAdapter.Holder>() {

    private val rooms = mutableListOf<PublicRoom>()

    fun submit(list: List<PublicRoom>) {
        rooms.clear()
        rooms.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemPublicRoomCardBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val room = rooms[position]
        holder.binding.textHost.text = room.hostName.ifBlank { "房间" }
        holder.binding.textMotd.text = room.motd.ifBlank {
            room.remark.orEmpty().ifBlank { "公开房间" }
        }
        holder.binding.textMeta.text = buildString {
            append(room.version?.ifBlank { "?" } ?: "?")
            append(" · ")
            append("${room.currentPlayers}/${room.maxPlayers}")
        }
        holder.binding.root.setOnClickListener { onRoomClick(room) }
    }

    override fun getItemCount(): Int = rooms.size

    class Holder(val binding: ItemPublicRoomCardBinding) : RecyclerView.ViewHolder(binding.root)
}
