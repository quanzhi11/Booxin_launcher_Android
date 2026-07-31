package com.booxin.launcher.ui.accounts

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.booxin.launcher.R
import com.booxin.launcher.data.model.AccountType
import com.booxin.launcher.data.model.LauncherAccount
import com.booxin.launcher.databinding.ItemAccountBinding
import java.io.File

class AccountsAdapter(
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

    override fun getItemCount(): Int = items.size

    inner class Holder(
        private val binding: ItemAccountBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: LauncherAccount) {
            binding.textAccountName.text = buildString {
                append(item.name)
                if (item.selected) append(" ✓")
            }
            binding.textAccountType.setText(
                when (item.type) {
                    AccountType.MICROSOFT -> R.string.accounts_microsoft
                    AccountType.OFFLINE -> R.string.accounts_offline
                }
            )
            binding.buttonAccountSkin.isVisible = item.type == AccountType.OFFLINE
            binding.buttonAccountSkin.setOnClickListener { onSkin(item) }
            bindSkinPreview(item)
            binding.root.setOnClickListener { onSelect(item) }
            binding.root.setOnLongClickListener {
                onDelete(item)
                true
            }
        }

        private fun bindSkinPreview(item: LauncherAccount) {
            val path = item.skinPath
            if (path.isNullOrBlank() || !File(path).isFile) {
                binding.imageAccountSkin.setImageResource(R.drawable.ic_avatar_placeholder)
                return
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
            val bmp = runCatching { BitmapFactory.decodeFile(path, opts) }.getOrNull()
            if (bmp != null) {
                // Classic skin: head is at (8,8)-(16,16) on 64x64; show head crop if possible.
                val head = if (bmp.width >= 64 && bmp.height >= 32) {
                    runCatching {
                        android.graphics.Bitmap.createBitmap(bmp, 8, 8, 8, 8)
                    }.getOrNull()
                } else null
                binding.imageAccountSkin.setImageBitmap(head ?: bmp)
                if (head != null && head !== bmp) bmp.recycle()
            } else {
                binding.imageAccountSkin.setImageResource(R.drawable.ic_avatar_placeholder)
            }
        }
    }
}
