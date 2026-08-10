package com.booxin.launcher.ui.ai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.booxin.launcher.AppContainer
import com.booxin.launcher.R
import com.booxin.launcher.core.uiplugin.UiPluginFonts
import com.booxin.launcher.core.uiplugin.UiPluginTheme
import com.booxin.launcher.core.ai.AiBackendSettings
import com.booxin.launcher.core.ai.AiChatMessage
import com.booxin.launcher.core.ai.AiMemberTier
import com.booxin.launcher.core.ai.AiModeQuality
import com.booxin.launcher.core.ai.AiModelProfiles
import com.booxin.launcher.core.ai.AiModelProvider
import com.booxin.launcher.core.ai.AiQuotaSnapshot
import com.booxin.launcher.databinding.ActivityAiChatBinding
import com.booxin.launcher.ui.GlassBackground
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale

class AiChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAiChatBinding
    private val chatAdapter = AiChatAdapter()
    private var sendJob: Job? = null
    private var lastSnapshot: AiQuotaSnapshot? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var syncingMode = false

    private val requestMic = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startVoiceInput()
        else Toast.makeText(this, R.string.ai_voice_need_permission, Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (AppContainer.multiplayerAuth.current() == null) {
            Toast.makeText(this, R.string.ai_login_hint, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityAiChatBinding.inflate(layoutInflater)
        setContentView(binding.root)
        UiPluginFonts.installHost(this)
        UiPluginTheme.installHost(this)
        GlassBackground.bind(
            owner = this,
            textureView = binding.videoGlassBackground,
            imageView = binding.imageGlassBackground,
            orbsView = binding.viewGlassOrbs
        )
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        binding.recyclerChat.layoutManager = LinearLayoutManager(this)
        binding.recyclerChat.adapter = chatAdapter
        binding.recyclerChat.itemAnimator = null
        tts = TextToSpeech(applicationContext) { }

        refreshChatUi(animateAssistant = false)
        refreshToggleLabels()
        syncModeToggle()
        refreshModelLabel()
        binding.textAiStatus.setText(R.string.ai_ready)

        binding.buttonBack.setOnClickListener { finish() }
        binding.buttonAiSend.setOnClickListener { sendCurrentInput() }
        binding.buttonAiAgent.setOnClickListener {
            val s = AppContainer.aiModelSettings
            s.agentEnabled = !s.agentEnabled
            refreshToggleLabels()
        }
        binding.buttonAiWeb.setOnClickListener {
            val s = AppContainer.aiModelSettings
            s.webSearchEnabled = !s.webSearchEnabled
            refreshToggleLabels()
        }
        binding.buttonAiVoice.setOnClickListener { ensureMicAndListen() }
        binding.buttonMore.setOnClickListener { showMoreMenu() }
        binding.textModel.setOnClickListener { showModelPicker() }

        binding.chipQuickMc.setOnClickListener {
            fillAndSend(getString(R.string.ai_quick_mc) + "：Minecraft 生存开局怎么玩？")
        }
        binding.chipQuickBuild.setOnClickListener {
            fillAndSend("帮我设计一个小型中世纪小屋（建筑设计）")
        }
        binding.chipQuickCrash.setOnClickListener {
            fillAndSend("我的世界启动崩溃了，常见原因和排查步骤有哪些？（崩溃分析）")
        }
        binding.chipQuickMods.setOnClickListener {
            fillAndSend("推荐几个适合手机端的性能与优化模组（模组推荐）")
        }

        binding.toggleAiMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || syncingMode) return@addOnButtonCheckedListener
            val mode = when (checkedId) {
                R.id.buttonModeLow -> AiModeQuality.LOW
                R.id.buttonModeHigh -> AiModeQuality.HIGH
                else -> AiModeQuality.MEDIUM
            }
            if (mode == AiModeQuality.HIGH &&
                !AiModelProfiles.isHighModeUnlocked(currentAccessTier())
            ) {
                Toast.makeText(this, R.string.ai_high_locked, Toast.LENGTH_SHORT).show()
                syncModeToggle()
                return@addOnButtonCheckedListener
            }
            AppContainer.aiModelSettings.selectedMode = mode
        }

        lifecycleScope.launch { refreshQuotaSnapshot() }
    }

    private fun currentAccessTier(): AiMemberTier {
        val snap = lastSnapshot ?: return AiMemberTier.NONE
        return AiModelProfiles.effectiveAccessTier(
            AiModelProfiles.parseTier(snap.memberTier),
            snap.isPlayPassActive
        )
    }

    private fun quotaUserKey(): String? {
        val user = AppContainer.multiplayerAuth.current()?.user ?: return null
        return AiBackendSettings.buildBooxinQuotaKey(user.id)
    }

    private suspend fun refreshQuotaSnapshot() {
        val key = quotaUserKey() ?: return
        lastSnapshot = AppContainer.aiChat.refreshQuota(key)
        refreshModelLabel()
        syncModeToggle()
    }

    private fun refreshChatUi(animateAssistant: Boolean) {
        val list = AppContainer.aiChat.displayHistory()
        binding.recyclerChat.isVisible = true
        binding.textChatEmpty.isVisible = list.isEmpty()
        if (animateAssistant && list.isNotEmpty() && list.last().role == "assistant") {
            chatAdapter.submitWithTypewriter(
                fullHistory = list,
                scope = lifecycleScope,
                onChar = {
                    binding.recyclerChat.post {
                        val last = chatAdapter.itemCount - 1
                        if (last >= 0) binding.recyclerChat.scrollToPosition(last)
                    }
                }
            )
        } else {
            chatAdapter.submit(list)
            if (list.isNotEmpty()) {
                binding.recyclerChat.post {
                    val last = chatAdapter.itemCount - 1
                    if (last >= 0) binding.recyclerChat.scrollToPosition(last)
                }
            }
        }
    }

    private fun refreshToggleLabels() {
        val s = AppContainer.aiModelSettings
        binding.buttonAiAgent.alpha = if (s.agentEnabled) 1f else 0.45f
        binding.buttonAiWeb.alpha = if (s.webSearchEnabled) 1f else 0.45f
    }

    private fun showMoreMenu() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ai_more_title)
            .setItems(arrayOf(getString(R.string.ai_clear_chat))) { _, which ->
                if (which == 0) {
                    chatAdapter.cancelTypewriter()
                    AppContainer.aiChat.clearHistory()
                    refreshChatUi(animateAssistant = false)
                }
            }
            .show()
    }

    private fun refreshModelLabel() {
        val p = AppContainer.aiModelSettings.resolveRequestProfile()
        val selected = AppContainer.aiModelSettings.selectedProvider
        binding.textModel.text = if (selected == AiModelProvider.AUTO) {
            "Auto → ${p.displayName}"
        } else {
            AppContainer.aiModelSettings.displayProfiles()
                .firstOrNull { it.provider == selected }?.displayName ?: p.displayName
        }
    }

    private fun syncModeToggle() {
        syncingMode = true
        val id = when (AppContainer.aiModelSettings.selectedMode) {
            AiModeQuality.LOW -> R.id.buttonModeLow
            AiModeQuality.HIGH -> R.id.buttonModeHigh
            AiModeQuality.MEDIUM -> R.id.buttonModeMedium
        }
        binding.toggleAiMode.check(id)
        syncingMode = false
    }

    private fun fillAndSend(text: String) {
        binding.editAiInput.setText(text)
        sendCurrentInput()
    }

    private fun sendCurrentInput(speakReply: Boolean = false) {
        val text = binding.editAiInput.text?.toString().orEmpty().trim()
        if (text.isEmpty()) return
        if (sendJob?.isActive == true) return
        binding.editAiInput.setText("")
        binding.textAiStatus.setText(R.string.ai_sending)
        chatAdapter.cancelTypewriter()
        chatAdapter.submit(
            AppContainer.aiChat.displayHistory() + AiChatMessage("user", text)
        )
        binding.textChatEmpty.isVisible = false
        binding.recyclerChat.post {
            val last = chatAdapter.itemCount - 1
            if (last >= 0) binding.recyclerChat.scrollToPosition(last)
        }
        setBusy(true)
        sendJob = lifecycleScope.launch {
            val result = AppContainer.aiChat.processUserMessage(
                message = text,
                userKey = quotaUserKey(),
                onProgress = { msg ->
                    runOnUiThread { binding.textAiStatus.text = msg }
                }
            )
            setBusy(false)
            refreshQuotaSnapshot()
            refreshChatUi(animateAssistant = !result.quotaError)
            if (speakReply) speak(result.message)
            if (result.quotaError) {
                Toast.makeText(this@AiChatActivity, result.message, Toast.LENGTH_LONG).show()
            }
            binding.textAiStatus.setText(R.string.ai_ready)
        }
    }

    private fun setBusy(busy: Boolean) {
        binding.buttonAiSend.isEnabled = !busy
        binding.editAiInput.isEnabled = !busy
    }

    private fun showModelPicker() {
        val profiles = AppContainer.aiModelSettings.displayProfiles()
        val labels = profiles.map {
            if (it.locked) getString(R.string.ai_model_locked, it.displayName, it.lockHint ?: "")
            else it.displayName
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ai_pick_model)
            .setItems(labels) { _, which ->
                val pick = profiles[which]
                if (pick.locked) {
                    Toast.makeText(
                        this,
                        pick.lockHint ?: getString(R.string.ai_feature_coming),
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setItems
                }
                AppContainer.aiModelSettings.selectedProvider = pick.provider
                refreshModelLabel()
            }
            .show()
    }

    private fun ensureMicAndListen() {
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) startVoiceInput() else requestMic.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun startVoiceInput() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, R.string.ai_voice_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        speechRecognizer?.destroy()
        val recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer = recognizer
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                binding.textAiStatus.setText(R.string.ai_voice_listening)
            }
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onError(error: Int) {
                Toast.makeText(this@AiChatActivity, R.string.ai_voice_unavailable, Toast.LENGTH_SHORT).show()
            }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                if (text.isNotBlank()) {
                    binding.editAiInput.setText(text)
                    sendCurrentInput(speakReply = true)
                }
            }
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.CHINA.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        recognizer.startListening(intent)
    }

    private fun speak(text: String) {
        tts?.language = Locale.CHINA
        tts?.speak(text.take(400), TextToSpeech.QUEUE_FLUSH, null, "ai-reply")
    }

    override fun onDestroy() {
        sendJob?.cancel()
        chatAdapter.cancelTypewriter()
        speechRecognizer?.destroy()
        speechRecognizer = null
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }
}
