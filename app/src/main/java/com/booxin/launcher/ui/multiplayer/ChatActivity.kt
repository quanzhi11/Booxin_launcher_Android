package com.booxin.launcher.ui.multiplayer

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.booxin.launcher.AppContainer
import com.booxin.launcher.R
import com.booxin.launcher.core.uiplugin.UiPluginFonts
import com.booxin.launcher.core.uiplugin.UiPluginTheme
import com.booxin.launcher.core.multiplayer.ChatMessage
import com.booxin.launcher.core.multiplayer.DmInboxWatcher
import com.booxin.launcher.core.multiplayer.DmNotifier
import com.booxin.launcher.databinding.ActivityChatBinding
import com.booxin.launcher.databinding.ItemChatMessageBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatBinding
    private lateinit var peerUserId: String
    private lateinit var peerUsername: String
    private val adapter = ChatMessageAdapter()
    private var pollJob: Job? = null
    private var lastId = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)
        UiPluginFonts.installHost(this)
        UiPluginTheme.installHost(this)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        peerUserId = intent.getStringExtra(EXTRA_PEER_ID).orEmpty()
        peerUsername = intent.getStringExtra(EXTRA_PEER_NAME).orEmpty()
        if (peerUserId.isBlank()) {
            finish()
            return
        }
        DmInboxWatcher.activePeerId = peerUserId
        DmNotifier.cancel(this, peerUserId)
        binding.textPeerName.text = peerUsername.ifBlank { peerUserId }
        binding.recyclerMessages.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        binding.recyclerMessages.adapter = adapter
        binding.buttonBack.setOnClickListener { finish() }
        binding.buttonSend.setOnClickListener { send() }
        setupQuickReplies()

        lifecycleScope.launch { loadHistory() }
        pollJob = lifecycleScope.launch {
            while (isActive) {
                delay(2500)
                pollNew()
            }
        }
    }

    private suspend fun loadHistory() {
        val result = AppContainer.multiplayerAuth.getMessages(peerUserId, 0L)
        if (result.isSuccess) {
            val list = result.getOrThrow()
            adapter.submit(list)
            lastId = list.maxOfOrNull { it.id } ?: 0L
            if (lastId > 0L) {
                AppContainer.multiplayerAuth.markRead(peerUserId, lastId)
            }
            binding.recyclerMessages.scrollToPosition((adapter.itemCount - 1).coerceAtLeast(0))
        }
    }

    private suspend fun pollNew() {
        val result = AppContainer.multiplayerAuth.getMessages(peerUserId, lastId)
        val list = result.getOrNull().orEmpty()
        if (list.isEmpty()) return
        adapter.append(list)
        lastId = list.maxOf { it.id }.coerceAtLeast(lastId)
        AppContainer.multiplayerAuth.markRead(peerUserId, lastId)
        binding.recyclerMessages.scrollToPosition(adapter.itemCount - 1)
    }

    private fun send() {
        val body = binding.inputMessage.text?.toString().orEmpty().trim()
        if (body.isEmpty()) return
        sendText(body)
    }

    private fun sendText(body: String) {
        lifecycleScope.launch {
            binding.buttonSend.isEnabled = false
            val result = AppContainer.multiplayerAuth.sendMessage(peerUserId, body)
            binding.buttonSend.isEnabled = true
            if (result.isSuccess) {
                binding.inputMessage.setText("")
                val msg = result.getOrThrow()
                adapter.append(listOf(msg))
                lastId = maxOf(lastId, msg.id)
                binding.recyclerMessages.scrollToPosition(adapter.itemCount - 1)
            } else {
                Toast.makeText(
                    this@ChatActivity,
                    result.exceptionOrNull()?.message ?: "发送失败",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun setupQuickReplies() {
        val presets = listOf(
            "(^_^)", "(・∀・)", "(｡･ω･｡)", "(T_T)", "(⊙_⊙)",
            "orz", "233", "666", "👍", "❤", "🉑",
            "好的", "收到", "哈哈", "牛逼", "冲鸭", "晚安"
        )
        binding.chipQuickReplies.removeAllViews()
        presets.forEach { text ->
            val chip = com.google.android.material.chip.Chip(this).apply {
                this.text = text
                isCheckable = false
                isClickable = true
                setOnClickListener {
                    val current = binding.inputMessage.text?.toString().orEmpty()
                    if (current.isBlank()) {
                        sendText(text)
                    } else {
                        binding.inputMessage.append(text)
                    }
                }
            }
            binding.chipQuickReplies.addView(chip)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::peerUserId.isInitialized && peerUserId.isNotBlank()) {
            DmInboxWatcher.activePeerId = peerUserId
            DmNotifier.cancel(this, peerUserId)
        }
    }

    override fun onPause() {
        if (::peerUserId.isInitialized && DmInboxWatcher.activePeerId == peerUserId) {
            DmInboxWatcher.activePeerId = null
        }
        super.onPause()
    }

    override fun onDestroy() {
        pollJob?.cancel()
        if (::peerUserId.isInitialized && DmInboxWatcher.activePeerId == peerUserId) {
            DmInboxWatcher.activePeerId = null
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PEER_ID = "peer_id"
        const val EXTRA_PEER_NAME = "peer_name"
    }
}

private class ChatMessageAdapter : RecyclerView.Adapter<ChatMessageAdapter.Holder>() {
    private val items = mutableListOf<ChatMessage>()

    fun submit(list: List<ChatMessage>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun append(list: List<ChatMessage>) {
        val existing = items.map { it.id }.toHashSet()
        val fresh = list.filter { it.id !in existing }
        if (fresh.isEmpty()) return
        val start = items.size
        items.addAll(fresh)
        notifyItemRangeInserted(start, fresh.size)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemChatMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val msg = items[position]
        val text = if (msg.isRevoked) {
            holder.itemView.context.getString(R.string.multiplayer_message_revoked)
        } else {
            msg.body
        }
        holder.binding.textBody.text = text
        holder.binding.textMeta.text = msg.sentAtUtc.orEmpty()
        holder.binding.root.gravity = if (msg.isMine) Gravity.END else Gravity.START
    }

    override fun getItemCount(): Int = items.size

    class Holder(val binding: ItemChatMessageBinding) : RecyclerView.ViewHolder(binding.root)
}
