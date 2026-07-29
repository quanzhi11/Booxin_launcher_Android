package com.booxin.launcher.ui.accounts

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.booxin.launcher.R
import com.booxin.launcher.data.model.AccountType
import com.booxin.launcher.data.model.LauncherAccount
import com.booxin.launcher.databinding.ItemAccountBinding

class AccountsAdapter(
    private val onSelect: (LauncherAccount) -> Unit = {},
    private val onDelete: (LauncherAccount) -> Unit = {}
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
            binding.root.setOnClickListener { onSelect(item) }
            binding.root.setOnLongClickListener {
                onDelete(item)
                true
            }
        }
    }
}
