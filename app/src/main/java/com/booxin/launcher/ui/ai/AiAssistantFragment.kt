package com.booxin.launcher.ui.ai

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.booxin.launcher.AppContainer
import com.booxin.launcher.R
import com.booxin.launcher.core.ai.AiBackendSettings
import com.booxin.launcher.core.ai.AiMemberTier
import com.booxin.launcher.core.ai.AiModeQuality
import com.booxin.launcher.core.ai.AiModelProfiles
import com.booxin.launcher.core.ai.AiModelProvider
import com.booxin.launcher.core.ai.AiQuotaSnapshot
import com.booxin.launcher.databinding.FragmentAiAssistantBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

class AiAssistantFragment : Fragment() {

    private var _binding: FragmentAiAssistantBinding? = null
    private val binding get() = _binding!!
    private var lastSnapshot: AiQuotaSnapshot? = null
    private var syncingMode = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAiAssistantBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        refreshModelLabel()
        syncModeToggle()

        binding.buttonAiGoLogin.setOnClickListener {
            findNavController().navigate(R.id.nav_multiplayer)
        }
        binding.buttonEnterChat.setOnClickListener { openChat() }
        binding.cardEnterChat.setOnClickListener { openChat() }
        binding.buttonTeammate.setOnClickListener { showTeammateComing() }
        binding.cardTeammate.setOnClickListener { showTeammateComing() }
        binding.buttonMore.setOnClickListener {
            if (!requireBooxinLogin()) return@setOnClickListener
            showMoreMenu()
        }
        binding.textModel.setOnClickListener {
            if (!requireBooxinLogin()) return@setOnClickListener
            showModelPicker()
        }
        binding.toggleAiMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || syncingMode) return@addOnButtonCheckedListener
            if (!isBooxinLoggedIn()) {
                syncModeToggle()
                requireBooxinLogin()
                return@addOnButtonCheckedListener
            }
            val mode = when (checkedId) {
                R.id.buttonModeLow -> AiModeQuality.LOW
                R.id.buttonModeHigh -> AiModeQuality.HIGH
                else -> AiModeQuality.MEDIUM
            }
            if (mode == AiModeQuality.HIGH &&
                !AiModelProfiles.isHighModeUnlocked(currentAccessTier())
            ) {
                Toast.makeText(requireContext(), R.string.ai_high_locked, Toast.LENGTH_SHORT).show()
                syncModeToggle()
                return@addOnButtonCheckedListener
            }
            AppContainer.aiModelSettings.selectedMode = mode
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                AppContainer.multiplayerAuth.session.collect {
                    applyAccessGate()
                    refreshQuotaUi()
                }
            }
        }
    }

    private fun openChat() {
        if (!requireBooxinLogin()) return
        startActivity(Intent(requireContext(), AiChatActivity::class.java))
    }

    private fun showTeammateComing() {
        Toast.makeText(requireContext(), R.string.ai_feature_coming, Toast.LENGTH_SHORT).show()
    }

    private fun isBooxinLoggedIn(): Boolean = AppContainer.multiplayerAuth.current() != null

    private fun requireBooxinLogin(): Boolean {
        if (isBooxinLoggedIn()) return true
        applyAccessGate()
        Toast.makeText(requireContext(), R.string.ai_login_hint, Toast.LENGTH_SHORT).show()
        return false
    }

    private fun applyAccessGate() {
        val b = _binding ?: return
        val loggedIn = isBooxinLoggedIn()
        b.panelAiLoginGate.isVisible = !loggedIn
        b.panelAiContent.alpha = if (loggedIn) 1f else 0.35f
    }

    private fun currentAccessTier(): AiMemberTier {
        val snap = lastSnapshot ?: return AiMemberTier.NONE
        return AiModelProfiles.effectiveAccessTier(
            AiModelProfiles.parseTier(snap.memberTier),
            snap.isPlayPassActive
        )
    }

    private suspend fun refreshQuotaUi() {
        val b = _binding ?: return
        applyAccessGate()
        val session = AppContainer.multiplayerAuth.current()
        if (session == null) {
            b.textAiAccount.text = getString(R.string.ai_login_hint)
            b.textAiQuota.text = "—"
            b.textAiPlayPass.text = "—"
            b.textAiStatus.text = ""
            b.progressQuotaBar.progress = 0
            return
        }
        b.textAiStatus.setText(R.string.ai_quota_loading)
        val key = AiBackendSettings.buildBooxinQuotaKey(session.user.id)
        val snap = AppContainer.aiChat.refreshQuota(key)
        lastSnapshot = snap
        if (snap == null) {
            b.textAiAccount.text = getString(
                R.string.ai_account_fmt,
                session.user.username,
                "—"
            )
            b.textAiQuota.text = getString(R.string.ai_login_hint)
            b.textAiPlayPass.text = "—"
            b.textAiStatus.text = ""
            return
        }
        b.textAiAccount.text = getString(
            R.string.ai_account_fmt,
            session.user.username,
            snap.tierLabel
        )
        b.textAiQuota.text = "${snap.dialogueQuotaLine()} · ${snap.agentQuotaLine()}"
        b.textAiPlayPass.text = snap.playPassLine()
        b.textAiStatus.setText(R.string.ai_ready)
        val ratio = if (snap.weeklyLimitFen > 0) {
            ((snap.usedFen * 100) / snap.weeklyLimitFen).toInt().coerceIn(0, 100)
        } else {
            0
        }
        b.progressQuotaBar.progress = ratio
        refreshModelLabel()
        syncModeToggle()
    }

    private fun showMoreMenu() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.ai_more_title)
            .setItems(
                arrayOf(
                    getString(R.string.ai_subscribe),
                    getString(R.string.ai_play_pass)
                )
            ) { _, which ->
                when (which) {
                    0 -> showSubscribeDialog()
                    1 -> showPlayPassDialog()
                }
            }
            .show()
    }

    private fun refreshModelLabel() {
        val b = _binding ?: return
        val p = AppContainer.aiModelSettings.resolveRequestProfile()
        val selected = AppContainer.aiModelSettings.selectedProvider
        b.textModel.text = if (selected == AiModelProvider.AUTO) {
            "Auto → ${p.displayName}"
        } else {
            AppContainer.aiModelSettings.displayProfiles()
                .firstOrNull { it.provider == selected }?.displayName ?: p.displayName
        }
    }

    private fun syncModeToggle() {
        val b = _binding ?: return
        syncingMode = true
        val id = when (AppContainer.aiModelSettings.selectedMode) {
            AiModeQuality.LOW -> R.id.buttonModeLow
            AiModeQuality.HIGH -> R.id.buttonModeHigh
            AiModeQuality.MEDIUM -> R.id.buttonModeMedium
        }
        b.toggleAiMode.check(id)
        syncingMode = false
    }

    private fun showModelPicker() {
        val profiles = AppContainer.aiModelSettings.displayProfiles()
        val labels = profiles.map {
            if (it.locked) getString(R.string.ai_model_locked, it.displayName, it.lockHint ?: "")
            else it.displayName
        }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.ai_pick_model)
            .setItems(labels) { _, which ->
                val pick = profiles[which]
                if (pick.locked) {
                    Toast.makeText(
                        requireContext(),
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

    private fun quotaUserKey(): String? {
        val user = AppContainer.multiplayerAuth.current()?.user ?: return null
        return AiBackendSettings.buildBooxinQuotaKey(user.id)
    }

    private fun showSubscribeDialog() {
        val key = quotaUserKey()
        if (key == null) {
            Toast.makeText(requireContext(), R.string.ai_need_login_pay, Toast.LENGTH_LONG).show()
            return
        }
        val plans = arrayOf(
            getString(R.string.ai_plan_play_pass) to "PlayPass",
            getString(R.string.ai_plan_shelter) to "Pro",
            getString(R.string.ai_plan_hearth) to "ProMax",
            getString(R.string.ai_plan_atelier) to "Ultra"
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.ai_subscribe_title)
            .setItems(plans.map { it.first }.toTypedArray()) { _, which ->
                pickPayType(key, plans[which].second)
            }
            .show()
    }

    private fun pickPayType(userKey: String, plan: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.ai_subscribe_title)
            .setItems(
                arrayOf(
                    getString(R.string.ai_subscribe_alipay),
                    getString(R.string.ai_subscribe_wechat)
                )
            ) { _, which ->
                val payType = if (which == 0) "alipay" else "wxpay"
                viewLifecycleOwner.lifecycleScope.launch {
                    val result = AppContainer.aiBackend.createSubscription(userKey, plan, payType)
                    if (!result.success || result.payUrl.isNullOrBlank()) {
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.ai_subscribe_failed, result.message.ifBlank { "unknown" }),
                            Toast.LENGTH_LONG
                        ).show()
                        return@launch
                    }
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(result.payUrl)))
                    Toast.makeText(requireContext(), R.string.ai_subscribe_open_pay, Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun showPlayPassDialog() {
        val key = quotaUserKey()
        if (key == null) {
            Toast.makeText(requireContext(), R.string.ai_need_login_pay, Toast.LENGTH_LONG).show()
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.ai_play_pass)
            .setItems(
                arrayOf(
                    getString(R.string.ai_play_pass_claim),
                    getString(R.string.ai_play_pass_activate)
                )
            ) { _, which ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val result = if (which == 0) {
                        AppContainer.aiBackend.claimDailyPlayPass(key)
                    } else {
                        AppContainer.aiBackend.activatePlayPass(key)
                    }
                    if (result.success) {
                        Toast.makeText(requireContext(), R.string.ai_play_pass_done, Toast.LENGTH_SHORT).show()
                        if (result.snapshot != null) lastSnapshot = result.snapshot
                        refreshQuotaUi()
                    } else {
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.ai_play_pass_failed, result.message.ifBlank { "unknown" }),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            .show()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
