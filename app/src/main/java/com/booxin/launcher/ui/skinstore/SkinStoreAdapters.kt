package com.booxin.launcher.ui.skinstore

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.booxin.launcher.R
import com.booxin.launcher.core.skinstore.SkinComment
import com.booxin.launcher.core.skinstore.SkinFrontPreview
import com.booxin.launcher.core.skinstore.StoreSkin
import com.booxin.launcher.databinding.ItemPluginCommentBinding
import com.booxin.launcher.databinding.ItemStoreSkinBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import com.booxin.launcher.core.net.HttpClients

class SkinStoreAdapter(
    private val scope: CoroutineScope,
    private val onOpen: (StoreSkin) -> Unit
) : RecyclerView.Adapter<SkinStoreAdapter.VH>() {

    private val items = mutableListOf<StoreSkin>()
    private val jobs = mutableMapOf<String, Job>()

    fun submit(list: List<StoreSkin>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemStoreSkinBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    override fun onViewRecycled(holder: VH) {
        super.onViewRecycled(holder)
        holder.clear()
    }

    inner class VH(private val binding: ItemStoreSkinBinding) : RecyclerView.ViewHolder(binding.root) {
        private var boundId: String? = null

        fun bind(item: StoreSkin) {
            boundId = item.id
            binding.textStoreSkinName.text = item.name
            val reprint = item.isReprint()
            binding.textStoreSkinReprintBadge.isVisible = reprint
            val badge = item.originBadgeText()
            binding.textStoreSkinOrigin.isVisible = !badge.isNullOrBlank()
            binding.textStoreSkinOrigin.text = badge
            binding.textStoreSkinMeta.text = binding.root.context.getString(
                R.string.skin_store_meta,
                item.authorUsername.ifBlank { "-" },
                item.model,
                item.version
            )
            binding.textStoreSkinRating.text = if (item.ratingCount > 0) {
                binding.root.context.getString(
                    R.string.skin_store_rating_list,
                    item.ratingAvg,
                    item.ratingCount,
                    item.commentCount
                )
            } else {
                binding.root.context.getString(R.string.skin_store_rating_none, item.commentCount)
            }
            binding.imageStoreSkin.setImageDrawable(null)
            jobs[item.id]?.cancel()
            jobs[item.id] = scope.launch {
                val bmp = withContext(Dispatchers.IO) {
                    runCatching {
                        HttpClients.shared.newCall(
                            Request.Builder().url(item.textureUrl).get().build()
                        ).execute().use { resp ->
                            if (!resp.isSuccessful) return@use null
                            val bytes = resp.body?.bytes() ?: return@use null
                            val texture = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                ?: return@use null
                            SkinFrontPreview.render(
                                texture,
                                slim = SkinFrontPreview.isSlimModel(item.model)
                            ).also {
                                if (it !== texture) texture.recycle()
                            }
                        }
                    }.getOrNull()
                }
                if (boundId == item.id && bmp != null) {
                    binding.imageStoreSkin.setImageBitmap(bmp)
                }
            }
            binding.root.setOnClickListener { onOpen(item) }
        }

        fun clear() {
            boundId = null
        }
    }
}

class SkinCommentAdapter(
    private val onLike: (SkinComment) -> Unit,
    private val onReport: (SkinComment) -> Unit
) : RecyclerView.Adapter<SkinCommentAdapter.VH>() {

    private val items = mutableListOf<SkinComment>()

    fun submit(list: List<SkinComment>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun update(comment: SkinComment) {
        val idx = items.indexOfFirst { it.id == comment.id }
        if (idx < 0) return
        items[idx] = comment
        notifyItemChanged(idx)
    }

    fun remove(commentId: String) {
        val idx = items.indexOfFirst { it.id == commentId }
        if (idx < 0) return
        items.removeAt(idx)
        notifyItemRemoved(idx)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemPluginCommentBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    inner class VH(private val binding: ItemPluginCommentBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(comment: SkinComment) {
            binding.textCommentMeta.text = binding.root.context.getString(
                R.string.skin_store_comment_meta,
                comment.username.ifBlank { "-" },
                comment.createdAt.take(19).replace('T', ' ')
            )
            binding.textCommentBody.text = comment.body
            binding.buttonCommentLike.text = binding.root.context.getString(
                if (comment.likedByMe) R.string.plugin_store_liked else R.string.plugin_store_like,
                comment.likeCount
            )
            binding.buttonCommentLike.setOnClickListener { onLike(comment) }
            binding.buttonCommentReport.setOnClickListener { onReport(comment) }
        }
    }
}
