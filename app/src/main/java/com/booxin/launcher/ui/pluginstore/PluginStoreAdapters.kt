package com.booxin.launcher.ui.pluginstore

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.booxin.launcher.R
import com.booxin.launcher.core.pluginstore.PluginApplication
import com.booxin.launcher.core.pluginstore.PluginComment
import com.booxin.launcher.core.pluginstore.PluginStorePlatforms
import com.booxin.launcher.core.pluginstore.PluginStoreTypes
import com.booxin.launcher.core.pluginstore.StoreInstallAction
import com.booxin.launcher.core.pluginstore.StoreInstallState
import com.booxin.launcher.core.pluginstore.StorePlugin
import com.booxin.launcher.databinding.ItemPluginApplicationBinding
import com.booxin.launcher.databinding.ItemPluginCommentBinding
import com.booxin.launcher.databinding.ItemStorePluginBinding

class StorePluginAdapter(
    private val onDetail: (StorePlugin) -> Unit,
    private val onDownload: (StorePlugin) -> Unit,
    private val onUpdate: (StorePlugin) -> Unit = onDownload
) : RecyclerView.Adapter<StorePluginAdapter.Holder>() {

    private val items = ArrayList<StorePlugin>()

    fun submit(list: List<StorePlugin>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemStorePluginBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    override fun getItemCount(): Int = items.size

    inner class Holder(private val binding: ItemStorePluginBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: StorePlugin) {
            val ctx = binding.root.context
            binding.textStorePluginName.text = item.name
            binding.textStorePluginMeta.text = ctx.getString(
                R.string.plugin_store_meta,
                PluginStoreTypes.label(ctx, item.type),
                item.developerUsername.ifBlank { item.developerUserId }
            )
            binding.textStorePluginPlatforms.text =
                PluginStorePlatforms.label(ctx, item.platforms)
            binding.textStorePluginDesc.text = item.description.ifBlank {
                ctx.getString(R.string.plugin_store_no_desc)
            }
            binding.textStorePluginRating.text = if (item.ratingCount > 0) {
                ctx.getString(
                    R.string.plugin_store_rating_list,
                    item.ratingAvg,
                    item.ratingCount,
                    item.commentCount
                )
            } else {
                ctx.getString(R.string.plugin_store_rating_none, item.commentCount)
            }
            val action = StoreInstallState.actionFor(item)
            when (action) {
                StoreInstallAction.DOWNLOAD -> {
                    binding.buttonStoreDownload.isEnabled = true
                    binding.buttonStoreDownload.alpha = 1f
                    binding.buttonStoreDownload.setText(R.string.plugin_store_download)
                    binding.buttonStoreDownload.setOnClickListener { onDownload(item) }
                }
                StoreInstallAction.INSTALLED -> {
                    binding.buttonStoreDownload.isEnabled = false
                    binding.buttonStoreDownload.alpha = 0.55f
                    binding.buttonStoreDownload.setText(R.string.plugin_store_downloaded)
                    binding.buttonStoreDownload.setOnClickListener(null)
                }
                StoreInstallAction.UPDATE -> {
                    binding.buttonStoreDownload.isEnabled = true
                    binding.buttonStoreDownload.alpha = 1f
                    binding.buttonStoreDownload.setText(R.string.plugin_store_update)
                    binding.buttonStoreDownload.setOnClickListener { onUpdate(item) }
                }
            }
            binding.root.setOnClickListener { onDetail(item) }
            binding.buttonStoreDetail.setOnClickListener { onDetail(item) }
        }
    }
}

class PluginCommentAdapter(
    private val onLike: (PluginComment) -> Unit,
    private val onReport: (PluginComment) -> Unit
) : RecyclerView.Adapter<PluginCommentAdapter.Holder>() {

    private val items = ArrayList<PluginComment>()

    fun submit(list: List<PluginComment>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun update(comment: PluginComment) {
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

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding =
            ItemPluginCommentBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    override fun getItemCount(): Int = items.size

    inner class Holder(private val binding: ItemPluginCommentBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: PluginComment) {
            val ctx = binding.root.context
            val whenText = item.createdAt.take(10).ifBlank { item.createdAt }
            binding.textCommentMeta.text = ctx.getString(
                R.string.plugin_store_comment_meta,
                item.username.ifBlank { item.userId },
                whenText
            )
            binding.textCommentBody.text = item.body
            binding.buttonCommentLike.text = ctx.getString(
                if (item.likedByMe) R.string.plugin_store_liked else R.string.plugin_store_like,
                item.likeCount
            )
            binding.buttonCommentLike.setOnClickListener { onLike(item) }
            binding.buttonCommentReport.setOnClickListener { onReport(item) }
        }
    }
}

class PluginApplicationAdapter : RecyclerView.Adapter<PluginApplicationAdapter.Holder>() {

    private val items = ArrayList<PluginApplication>()

    fun submit(list: List<PluginApplication>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding =
            ItemPluginApplicationBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    override fun getItemCount(): Int = items.size

    inner class Holder(private val binding: ItemPluginApplicationBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: PluginApplication) {
            val ctx = binding.root.context
            binding.textAppName.text = item.name
            val status = when (item.status) {
                "pending" -> ctx.getString(R.string.plugin_store_status_pending)
                "approved" -> ctx.getString(R.string.plugin_store_status_approved)
                "rejected" -> ctx.getString(R.string.plugin_store_status_rejected)
                else -> item.status
            }
            binding.textAppStatus.text = status
            binding.textAppPlatforms.text =
                PluginStorePlatforms.label(ctx, item.platforms)
            val reason = item.rejectReason.trim()
            binding.textAppReason.isVisible = item.status == "rejected" && reason.isNotEmpty()
            binding.textAppReason.text =
                ctx.getString(R.string.plugin_store_reject_reason, reason)
        }
    }
}
