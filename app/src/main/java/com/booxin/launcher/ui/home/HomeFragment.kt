package com.booxin.launcher.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.booxin.launcher.AppContainer
import com.booxin.launcher.R
import com.booxin.launcher.core.auth.MicrosoftAuthLogger
import com.booxin.launcher.core.auth.MicrosoftAuthService
import com.booxin.launcher.data.model.AccountType
import com.booxin.launcher.data.model.LauncherAccount
import com.booxin.launcher.databinding.FragmentHomeBinding
import com.booxin.launcher.ui.auth.MicrosoftAuthErrorDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        AppContainer.repository.refreshInstalledVersions()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    AppContainer.repository.session.collect { refreshSelectedVersion() }
                }
                launch {
                    AppContainer.repository.installedVersions.collect { refreshSelectedVersion() }
                }
            }
        }

        binding.buttonSwitchVersion.setOnClickListener { showSwitchVersionDialog() }
        binding.buttonAccountManage.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_accounts)
        }

        binding.buttonLaunch.setOnClickListener {
            val version = AppContainer.repository.selectedVersion()
            if (version == null || !version.installed) {
                Toast.makeText(requireContext(), R.string.home_launch_need_install, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            viewLifecycleOwner.lifecycleScope.launch {
                val ctx = context ?: return@launch
                val account = prepareLaunchAccount() ?: return@launch
                val result = AppContainer.gameRuntime.launch(ctx, version.id, account)
                if (result.isFailure) {
                    Toast.makeText(
                        ctx,
                        getString(
                            R.string.home_launch_failed,
                            result.exceptionOrNull()?.message ?: "unknown"
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private suspend fun prepareLaunchAccount(): LauncherAccount? {
        var account = AppContainer.repository.selectedAccount()
            ?: LauncherAccount(
                id = "offline-default",
                name = "Player",
                type = AccountType.OFFLINE,
                selected = true
            )
        val joiningRoom = AppContainer.multiplayerAuth.activeLobby.value != null
        // Offline guests are supported (same as PC). Host LAN must allow offline
        // (online-mode=false / 关闭正版验证); otherwise Minecraft shows「无效会话」.
        if (joiningRoom && account.type == AccountType.OFFLINE) {
            Toast.makeText(
                requireContext(),
                R.string.multiplayer_offline_join_hint,
                Toast.LENGTH_LONG
            ).show()
        }
        if (account.type == AccountType.MICROSOFT) {
            // Refresh MSA before launch when in a Booxin room — stale tokens cause「无效会话」.
            val refreshed = MicrosoftAuthService.ensureSession(
                account,
                forceRefresh = joiningRoom
            )
            if (refreshed.isFailure) {
                val err = refreshed.exceptionOrNull()
                val summary = getString(
                    R.string.accounts_ms_refresh_failed,
                    err?.message ?: "请重新登录"
                )
                val log = MicrosoftAuthLogger.lastReport
                    ?: "（无详细日志）\n${err?.message}"
                MicrosoftAuthErrorDialog.show(requireContext(), summary, log)
                return null
            }
            account = refreshed.getOrThrow()
            AppContainer.repository.upsertMicrosoftAccount(account)
        }
        return account
    }

    private fun showSwitchVersionDialog() {
        val installed = AppContainer.repository.installedVersions.value
        if (installed.isEmpty()) {
            Toast.makeText(requireContext(), R.string.home_no_installed, Toast.LENGTH_SHORT).show()
            return
        }
        val labels = installed.map { it.id }.toTypedArray()
        val selectedId = AppContainer.repository.session.value.selectedVersionId
        val checked = installed.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.home_switch_title)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                val version = installed[which]
                AppContainer.repository.selectVersion(version.id)
                Toast.makeText(
                    requireContext(),
                    getString(R.string.home_switched, version.id),
                    Toast.LENGTH_SHORT
                ).show()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun refreshSelectedVersion() {
        val b = _binding ?: return
        val version = AppContainer.repository.selectedVersion()
        b.textSelectedVersion.text = version?.id ?: getString(R.string.home_no_version)
        b.textLaunchStatus.text = when {
            version == null || !version.installed -> getString(R.string.home_status_placeholder)
            else -> getString(R.string.home_status_ready)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
