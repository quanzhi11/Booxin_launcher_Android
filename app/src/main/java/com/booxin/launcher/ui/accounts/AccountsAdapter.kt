package com.booxin.launcher.ui.accounts

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.booxin.launcher.R
import com.booxin.launcher.core.skin.PlayerSkinResolver
import com.booxin.launcher.core.skin.OfflineSkinService
import com.booxin.launcher.data.model.AccountType
import com.booxin.launcher.data.model.LauncherAccount
import com.booxin.launcher.databinding.ItemAccountBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class AccountsAdapter(
    private val scope: CoroutineScope,
    private val onSelect: (LauncherAccount) -> Unit = {},
    private val onDelete: (LauncherAccount) -> Unit = {},
    private val onSkin: (LauncherAccount) -> Unit = {}
) : RecyclerView.Adapter<AccountsAdapter.Holder>() {

    private val items = mutableListOf<LauncherAccount>()

    fun submit(list: List<LauncherAccount>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemAccountBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position])
    }

    override fun onViewRecycled(holder: Holder) {
        holder.cancelSkinJob()
        super.onViewRecycled(holder)
    }

    override fun getItemCount(): Int = items.size

    inner class Holder(
        private val binding: ItemAccountBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        private var skinJob: Job? = null
        private var boundAccountId: String? = null

        fun cancelSkinJob() {
            skinJob?.cancel()
            skinJob = null
        }

        fun bind(item: LauncherAccount) {
            cancelSkinJob()
            boundAccountId = item.id
            binding.textAccountName.text = buildString {
                append(item.name)
                if (item.selected) append(" ✓")
            }
            binding.textAccountType.setText(
                when (item.type) {
                    AccountType.MICROSOFT -> R.string.accounts_microsoft
                    AccountType.OFFLINE -> R.string.accounts_offline
                    AccountType.THIRD_PARTY -> R.string.accounts_third_party
                }
            )
            binding.buttonAccountSkin.isVisible = item.type == AccountType.OFFLINE
            binding.buttonAccountSkin.setOnClickListener { onSkin(item) }
            bindAvatar(item)
            binding.root.setOnClickListener { onSelect(item) }
            binding.root.setOnLongClickListener {
                onDelete(item)
                true
            }
        }

        private fun bindAvatar(item: LauncherAccount) {
            binding.imageAccountSkin.setImageResource(R.drawable.ic_avatar_placeholder)
            binding.imageAccountSkin.setTag(R.id.imageAccountSkin, item.id)

            when (item.type) {
                AccountType.OFFLINE -> {
                    val path = OfflineSkinService.selectedSkinPath(item)
                    if (path != null) {
                        applyHeadPreview(binding.imageAccountSkin, path, item.id)
                    }
                }
                AccountType.MICROSOFT, AccountType.THIRD_PARTY -> {
                    val appCtx = binding.root.context.applicationContext
                    skinJob = scope.launch {
                        val file = withContext(Dispatchers.IO) {
                            PlayerSkinResolver.resolveAccountSkinPng(appCtx, item)
                        }
                        if (boundAccountId != item.id) return@launch
                        if (binding.imageAccountSkin.getTag(R.id.imageAccountSkin) != item.id) return@launch
                        applyHeadPreview(binding.imageAccountSkin, file.absolutePath, item.id)
                    }
                }
            }
        }

        private fun applyHeadPreview(view: ImageView, path: String, accountId: String) {
            val head = decodeSkinHead(path)
            if (view.getTag(R.id.imageAccountSkin) != accountId) {
                head?.recycle()
                return
            }
            if (head != null) {
                view.setImageBitmap(head)
            } else {
                view.setImageResource(R.drawable.ic_avatar_placeholder)
            }
        }
    }

    companion object {
        /** Crop classic skin head (8,8)-(16,16) and upscale with nearest-neighbor. */
        fun decodeSkinHead(path: String): Bitmap? {
            val opts = BitmapFactory.Options().apply {
                inScaled = false
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bmp = runCatching { BitmapFactory.decodeFile(path, opts) }.getOrNull() ?: return null
            return try {
                val head = if (bmp.width >= 64 && bmp.height >= 32) {
                    runCatching { Bitmap.createBitmap(bmp, 8, 8, 8, 8) }.getOrNull()
                } else {
                    null
                }
                val src = head ?: bmp
                val scaled = Bitmap.createScaledBitmap(src, 64, 64, false)
                if (head != null && head !== scaled) head.recycle()
                if (bmp !== scaled && bmp !== head) bmp.recycle()
                scaled
            } catch (_: Throwable) {
                bmp.recycle()
                null
            }
        }
    }
}
