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
import com.booxin.launcher.core.multiplayer.LobbyChatMessage
import com.booxin.launcher.core.uiplugin.UiPluginFonts
import com.booxin.launcher.core.uiplugin.UiPluginTheme
import com.booxin.launcher.databinding.ActivityChatBinding
import com.booxin.launcher.databinding.ItemChatMessageBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class LobbyChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatBinding
    private val adapter = LobbyChatMessageAdapter()
    private var pollJob: Job? = null
    private var lastId = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (AppContainer.multiplayerAuth.current() == null) {
            Toast.makeText(this, R.string.multiplayer_lobby_chat_need_login, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

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

        binding.textPeerName.setText(R.string.multiplayer_lobby_chat_title)
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
        val result = AppContainer.multiplayerAuth.getLobbyMessages(0L)
        if (result.isSuccess) {
            val list = result.getOrThrow()
            adapter.submit(list)
            lastId = list.maxOfOrNull { it.id } ?: 0L
            binding.recyclerMessages.scrollToPosition((adapter.itemCount - 1).coerceAtLeast(0))
        } else {
            Toast.makeText(
                this,
                result.exceptionOrNull()?.message ?: "加载失败",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private suspend fun pollNew() {
        if (lastId <= 0L) {
            loadHistory()
            return
        }
        val result = AppContainer.multiplayerAuth.getLobbyMessages(lastId)
        val list = result.getOrNull().orEmpty()
        if (list.isEmpty()) return
        adapter.append(list)
        lastId = list.maxOf { it.id }.coerceAtLeast(lastId)
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
            val result = AppContainer.multiplayerAuth.sendLobbyMessage(body)
            binding.buttonSend.isEnabled = true
            if (result.isSuccess) {
                binding.inputMessage.setText("")
                val msg = result.getOrThrow()
                adapter.append(listOf(msg))
                lastId = maxOf(lastId, msg.id)
                binding.recyclerMessages.scrollToPosition(adapter.itemCount - 1)
            } else {
                Toast.makeText(
                    this@LobbyChatActivity,
                    result.exceptionOrNull()?.message ?: "发送失败",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun setupQuickReplies() {
        val presets = listOf(
            "(^_^)", "(・∀・)", "(｡･ω･｡)", "(T_T)",
            "orz", "233", "666", "👍",
            "大家好", "有人组队吗", "求带", "晚安"
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

    override fun onDestroy() {
        pollJob?.cancel()
        super.onDestroy()
    }
}

private class LobbyChatMessageAdapter : RecyclerView.Adapter<LobbyChatMessageAdapter.Holder>() {
    private val items = mutableListOf<LobbyChatMessage>()

    fun submit(list: List<LobbyChatMessage>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun append(list: List<LobbyChatMessage>) {
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
        val ctx = holder.itemView.context
        holder.binding.textBody.text = msg.body
        val name = if (msg.isMine) {
            ctx.getString(R.string.multiplayer_lobby_chat_me)
        } else {
            msg.senderUsername
        }
        holder.binding.textMeta.text = ctx.getString(
            R.string.multiplayer_lobby_chat_meta,
            name,
            msg.sentAtUtc.orEmpty()
        )
        holder.binding.root.gravity = if (msg.isMine) Gravity.END else Gravity.START
    }

    override fun getItemCount(): Int = items.size

    class Holder(val binding: ItemChatMessageBinding) : RecyclerView.ViewHolder(binding.root)
}
