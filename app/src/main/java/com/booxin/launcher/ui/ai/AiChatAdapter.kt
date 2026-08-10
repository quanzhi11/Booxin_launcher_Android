package com.booxin.launcher.ui.ai

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.booxin.launcher.R
import com.booxin.launcher.core.ai.AiChatMessage
import com.booxin.launcher.databinding.ItemAiChatBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AiChatAdapter : RecyclerView.Adapter<AiChatAdapter.Holder>() {
    private val items = ArrayList<AiChatMessage>()
    private var typewriterJob: Job? = null

    fun submit(list: List<AiChatMessage>) {
        typewriterJob?.cancel()
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    /**
     * Show [base] then typewrite the last assistant reply character by character (PC-like).
     */
    fun submitWithTypewriter(
        fullHistory: List<AiChatMessage>,
        scope: CoroutineScope,
        onChar: (() -> Unit)? = null,
        onDone: (() -> Unit)? = null
    ) {
        typewriterJob?.cancel()
        if (fullHistory.isEmpty() || fullHistory.last().role != "assistant") {
            submit(fullHistory)
            onDone?.invoke()
            return
        }
        val fullText = fullHistory.last().content
        val prefix = fullHistory.dropLast(1)
        items.clear()
        items.addAll(prefix)
        items.add(AiChatMessage("assistant", ""))
        notifyDataSetChanged()

        val delayMs = when {
            fullText.length > 800 -> 4L
            fullText.length > 300 -> 10L
            else -> 18L
        }
        typewriterJob = scope.launch {
            val sb = StringBuilder()
            for (ch in fullText) {
                if (!isActive) break
                sb.append(ch)
                val idx = items.lastIndex
                if (idx >= 0) {
                    items[idx] = AiChatMessage("assistant", sb.toString())
                    notifyItemChanged(idx)
                    onChar?.invoke()
                }
                delay(delayMs)
            }
            // Ensure final full text even if cancelled mid-way is restored by caller
            val idx = items.lastIndex
            if (idx >= 0 && isActive) {
                items[idx] = AiChatMessage("assistant", fullText)
                notifyItemChanged(idx)
            }
            onDone?.invoke()
        }
    }

    fun cancelTypewriter() {
        typewriterJob?.cancel()
        typewriterJob = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemAiChatBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    override fun getItemCount(): Int = items.size

    class Holder(private val binding: ItemAiChatBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: AiChatMessage) {
            val ctx = binding.root.context
            binding.textRole.text = when (item.role) {
                "user" -> ctx.getString(R.string.ai_role_user)
                else -> ctx.getString(R.string.ai_role_assistant)
            }
            binding.textContent.text = item.content
        }
    }
}
