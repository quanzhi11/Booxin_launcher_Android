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
import coil.load
import com.booxin.launcher.AppContainer
import com.booxin.launcher.R
import com.booxin.launcher.core.auth.MicrosoftAuthLogger
import com.booxin.launcher.core.auth.MicrosoftAuthService
import com.booxin.launcher.core.uiplugin.UiPluginManager
import com.booxin.launcher.core.uiplugin.UiPluginTheme
import com.booxin.launcher.data.model.AccountType
import com.booxin.launcher.data.model.LauncherAccount
import com.booxin.launcher.databinding.FragmentHomeBinding
import com.booxin.launcher.ui.auth.MicrosoftAuthErrorDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Calendar
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
        applyHomeGreeting()
        applyCustomLauncherIcon()
        applyHomeThemeLabels()
        AppContainer.repository.refreshInstalledVersions()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    AppContainer.repository.session.collect {
                        refreshSelectedVersion()
                        refreshCurrentAccount()
                    }
                }
                launch {
                    AppContainer.repository.installedVersions.collect { refreshSelectedVersion() }
                }
                launch {
                    AppContainer.repository.accounts.collect { refreshCurrentAccount() }
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
                    val err = result.exceptionOrNull()
                    if (err is kotlinx.coroutines.CancellationException) return@launch
                    Toast.makeText(
                        ctx,
                        getString(
                            R.string.home_launch_failed,
                            err?.message ?: "unknown"
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        applyHomeGreeting()
        applyCustomLauncherIcon()
        applyHomeThemeLabels()
    }

    /** UI plugin: theme welcome text and/or homeGreetingByTime */
    private fun applyHomeGreeting() {
        val b = _binding ?: return
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val useTime = UiPluginManager.isFeatureEnabled("homeGreetingByTime")
        b.textWelcome.text = UiPluginTheme.welcomeText(
            hourOfDay = hour,
            defaultWelcome = getString(R.string.home_welcome),
            defaultMorning = getString(R.string.home_welcome_morning),
            defaultNoon = getString(R.string.home_welcome_noon),
            defaultEvening = getString(R.string.home_welcome_evening),
            useTimeGreeting = useTime
        )
        UiPluginTheme.applyDeep(b.root)
    }

    /** UI plugin feature: customLauncherIcon → replace home brand mark */
    private fun applyCustomLauncherIcon() {
        val b = _binding ?: return
        val icon = UiPluginManager.resolveCustomLauncherIconFile()
        if (icon == null) {
            b.imageBrandIcon.setImageResource(R.mipmap.ic_launcher_round)
            return
        }
        b.imageBrandIcon.load(icon) {
            placeholder(R.mipmap.ic_launcher_round)
            error(R.mipmap.ic_launcher_round)
            crossfade(true)
        }
    }

    /** customTheme home button / status label overrides */
    private fun applyHomeThemeLabels() {
        val b = _binding ?: return
        val theme = UiPluginTheme.current()?.spec
        if (theme == null) {
            b.buttonLaunch.setText(R.string.home_launch)
            b.buttonSwitchVersion.setText(R.string.home_switch_version)
            b.buttonAccountManage.setText(R.string.home_account_manage)
            b.textLaunchStatus.setText(R.string.home_status_placeholder)
            b.textSelectedVersionLabel.setText(R.string.home_selected_version)
            return
        }
        b.buttonLaunch.text = theme.homeLaunchText.ifBlank { getString(R.string.home_launch) }
        b.buttonSwitchVersion.text =
            theme.homeSwitchVersionText.ifBlank { getString(R.string.home_switch_version) }
        b.buttonAccountManage.text =
            theme.homeAccountText.ifBlank { getString(R.string.home_account_manage) }
        b.textLaunchStatus.text =
            theme.homeStatusText.ifBlank { getString(R.string.home_status_placeholder) }
        b.textSelectedVersionLabel.text =
            theme.homeSelectedVersionLabel.ifBlank { getString(R.string.home_selected_version) }
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
        // 离线进房可以；主机需关正版验证，否则会「无效会话」。
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

    private fun refreshCurrentAccount() {
        val b = _binding ?: return
        val account = AppContainer.repository.selectedAccount()
        b.textCurrentAccount.text = if (account == null) {
            getString(R.string.home_no_account)
        } else {
            getString(R.string.home_current_account, account.name)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
