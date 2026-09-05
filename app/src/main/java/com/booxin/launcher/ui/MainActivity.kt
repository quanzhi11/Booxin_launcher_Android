package com.booxin.launcher.ui

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavOptions
import androidx.navigation.fragment.NavHostFragment
import com.booxin.launcher.AppContainer
import com.booxin.launcher.R
import com.booxin.launcher.core.download.game.InstallProgressHub
import com.booxin.launcher.core.multiplayer.RoomInvite
import com.booxin.launcher.core.multiplayer.RoomInviteCoordinator
import com.booxin.launcher.core.multiplayer.RoomInviteNotifier
import com.booxin.launcher.core.multiplayer.HostedRoomCleanup
import com.booxin.launcher.core.launch.GameCrashReportStore
import com.booxin.launcher.core.launch.GameSessionLease
import com.booxin.launcher.ui.crash.GameCrashDialog
import com.booxin.launcher.core.uiplugin.UiPluginFonts
import com.booxin.launcher.core.uiplugin.UiPluginManager
import com.booxin.launcher.core.uiplugin.UiPluginTheme
import com.booxin.launcher.databinding.ActivityMainBinding
import com.booxin.launcher.ui.agreement.UserAgreementUi
import com.booxin.launcher.ui.update.LauncherUpdateUi
import android.widget.LinearLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.booxin.launcher.ui.pluginpage.PluginPageFragment
import kotlinx.coroutines.launch
import androidx.core.view.isVisible
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.collectLatest

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var systemTopInset = 0
    private var systemBottomInset = 0
    private var syncingNav = false
    private var showingInviteDialog = false
    private var showingCrashDialog = false
    private var suppressCrashDialogOnce = false
    /** Latest install snapshot; used to hide global bar on pages that already show progress. */
    private var lastInstallSnapshot: InstallProgressHub.Snapshot? = null
    private var currentDestId: Int = 0

    private val topDestinations = listOf(
        R.id.nav_home,
        R.id.nav_versions,
        R.id.nav_community,
        R.id.nav_multiplayer,
        R.id.nav_ai,
        R.id.nav_plugin_store,
        R.id.nav_settings
    )

    companion object {
        /** Set by LaunchActivity when user taps 退出游戏 / 返回. */
        const val EXTRA_USER_EXITED_GAME = "booxin_user_exited_game"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        UiPluginFonts.installHost(this)
        UiPluginTheme.installHost(this)
        GlassBackground.bind(
            owner = this,
            textureView = binding.videoGlassBackground,
            imageView = binding.imageGlassBackground,
            orbsView = binding.viewGlassOrbs
        )

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            systemTopInset = systemBars.top
            systemBottomInset = systemBars.bottom
            binding.topChrome.setPadding(
                systemBars.left + dp(10),
                systemBars.top + dp(4),
                systemBars.right + dp(10),
                dp(4)
            )
            applyContentInsets(binding.topChrome.visibility == View.VISIBLE)
            insets
        }

        val navHost =
            supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHost.navController
        setupTopNav(navController)
        refreshPluginPageNav()

        navController.addOnDestinationChangedListener { _, destination, _ ->
            currentDestId = destination.id
            val hideChrome = destination.id == R.id.nav_download ||
                destination.id == R.id.nav_version_manage ||
                destination.id == R.id.nav_community_project_detail ||
                destination.id == R.id.nav_accounts ||
                destination.id == R.id.nav_plugins
            binding.topChrome.visibility = if (hideChrome) View.GONE else View.VISIBLE
            applyContentInsets(!hideChrome)
            WindowInsetsControllerCompat(window, window.decorView)
                .isAppearanceLightStatusBars = false
            if (!hideChrome && destination.id in topDestinations) {
                syncingNav = true
                binding.topNav.check(destination.id)
                syncingNav = false
            }
            // Download / community detail already have their own progress panel.
            renderGlobalInstallBar(lastInstallSnapshot)
        }

        UserAgreementUi.showIfNeeded(this) {
            if (savedInstanceState == null) {
                LauncherUpdateUi.check(
                    activity = this,
                    lifecycleOwner = this,
                    silentWhenLatest = true
                )
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    RoomInviteCoordinator.dialogInvites.collect { invite ->
                        showRoomInviteDialog(invite)
                    }
                }
                launch {
                    InstallProgressHub.snapshots().collect { snapshot ->
                        renderGlobalInstallBar(snapshot)
                    }
                }
            }
        }

        handleInviteIntent(intent)
    }

    private fun renderGlobalInstallBar(snapshot: InstallProgressHub.Snapshot?) {
        if (!::binding.isInitialized) return
        lastInstallSnapshot = snapshot
        val bar = binding.globalInstallBar
        val onDownloadPage = currentNavDestinationId() == R.id.nav_download
        if (snapshot == null || !snapshot.active || onDownloadPage) {
            bar.visibility = View.GONE
            return
        }
        bar.visibility = View.VISIBLE
        binding.globalInstallBar.setPadding(
            binding.globalInstallBar.paddingLeft,
            binding.globalInstallBar.paddingTop,
            binding.globalInstallBar.paddingRight,
            systemBottomInset + dp(8)
        )
        binding.textGlobalInstallTitle.text =
            getString(R.string.download_global_title, snapshot.title)
        binding.textGlobalInstallMessage.text = snapshot.message
        if (snapshot.fraction >= 0f) {
            binding.progressGlobalInstall.isIndeterminate = false
            binding.progressGlobalInstall.progress =
                (snapshot.fraction * 100).toInt().coerceIn(0, 100)
        } else {
            binding.progressGlobalInstall.isIndeterminate = true
        }
        bar.setOnClickListener {
            val navHost =
                supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as? NavHostFragment
                    ?: return@setOnClickListener
            val nav = navHost.navController
            if (nav.currentDestination?.id != R.id.nav_download) {
                runCatching { nav.navigate(R.id.nav_download) }
            }
        }
    }

    private fun currentNavDestinationId(): Int? {
        val navHost =
            supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as? NavHostFragment
                ?: return null
        return navHost.navController.currentDestination?.id
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_USER_EXITED_GAME, false)) {
            suppressCrashDialogOnce = true
            GameSessionLease.markUserExit(this)
            intent.removeExtra(EXTRA_USER_EXITED_GAME)
        }
        handleInviteIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        UiPluginFonts.applyTo(this)
        UiPluginTheme.applyTo(this)
        refreshPluginPageNav()
        // After :game crash, MainActivity comes back — unpublish zombie public rooms.
        HostedRoomCleanup.sweepAsync(this)
        if (intent?.getBooleanExtra(EXTRA_USER_EXITED_GAME, false) == true) {
            suppressCrashDialogOnce = true
            GameSessionLease.markUserExit(this)
            intent.removeExtra(EXTRA_USER_EXITED_GAME)
        }
        maybeShowCrashDialog()
    }

    private fun maybeShowCrashDialog() {
        if (showingCrashDialog || showingInviteDialog) return
        if (suppressCrashDialogOnce) {
            suppressCrashDialogOnce = false
            GameCrashReportStore.clear(this)
            GameSessionLease.clear(this)
            return
        }
        // User tapped 退出游戏 — never show.
        if (GameSessionLease.consumeUserExit(this) || GameSessionLease.launchLogSaysUserExit()) {
            GameCrashReportStore.clear(this)
            GameSessionLease.clear(this)
            return
        }
        val report = GameCrashReportStore.consume(this)
            ?: GameSessionLease.recoverUnexpectedExit(this)
            ?: return
        showingCrashDialog = true
        GameCrashDialog.showIfNeeded(this, report)
        showingCrashDialog = false
    }

    fun openPluginPage(pluginId: String, pageId: String) {
        val navHost =
            supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as? NavHostFragment
                ?: return
        navHost.navController.navigate(
            R.id.nav_plugin_page,
            PluginPageFragment.args(pluginId, pageId)
        )
    }

    /** Used by fullscreen plugin pages (e.g. forum shell). */
    fun setTopChromeVisible(visible: Boolean) {
        if (!::binding.isInitialized) return
        binding.topChrome.visibility = if (visible) View.VISIBLE else View.GONE
        applyContentInsets(visible)
    }

    fun refreshPluginPageNav() {
        if (!::binding.isInitialized) return
        val host = binding.pluginPageNav
        host.removeAllViews()
        val pages = UiPluginManager.listActivePages()
        if (pages.isEmpty()) {
            host.visibility = View.GONE
            return
        }
        host.visibility = View.VISIBLE
        val density = resources.displayMetrics.density
        pages.take(8).forEach { (install, page) ->
            val btn = MaterialButton(
                this,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = page.title
                textSize = 11f
                isAllCaps = false
                minimumHeight = (32 * density).toInt()
                minWidth = 0
                setPadding((10 * density).toInt(), 0, (10 * density).toInt(), 0)
                setOnClickListener {
                    openPluginPage(install.manifest.id, page.id)
                }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = (6 * density).toInt()
            }
            host.addView(btn, lp)
        }
    }

    private fun handleInviteIntent(intent: Intent?) {
        intent ?: return
        val deepCode = extractRoomCode(intent.data)
        val action = intent.action.orEmpty()
        val code = intent.getStringExtra(RoomInviteCoordinator.EXTRA_ROOM_CODE)
            ?.trim()
            .orEmpty()
            .ifBlank { deepCode.orEmpty() }
        val inviteId = intent.getStringExtra(RoomInviteCoordinator.EXTRA_INVITE_ID)
            ?.trim()
            .orEmpty()
            .ifBlank { null }
        if (code.isBlank()) return

        // Clear sticky extras so rotation / recreate won't re-join.
        intent.removeExtra(RoomInviteCoordinator.EXTRA_ROOM_CODE)
        intent.removeExtra(RoomInviteCoordinator.EXTRA_INVITE_ID)
        intent.action = Intent.ACTION_MAIN

        if (action == RoomInviteCoordinator.ACTION_ACCEPT ||
            action == Intent.ACTION_VIEW ||
            !inviteId.isNullOrBlank() ||
            deepCode != null
        ) {
            RoomInviteCoordinator.requestJoin(code, inviteId)
            if (!inviteId.isNullOrBlank()) {
                RoomInviteNotifier.cancel(this, inviteId)
            }
            navigateToMultiplayer()
        }
    }

    private fun extractRoomCode(uri: Uri?): String? {
        uri ?: return null
        val fromQuery = sequenceOf("code", "room", "roomCode", "c")
            .mapNotNull { key -> uri.getQueryParameter(key)?.trim()?.takeIf { it.isNotEmpty() } }
            .firstOrNull()
        if (!fromQuery.isNullOrBlank()) return fromQuery
        // booxin://join/<code> or https://boonix.art/join/<code>
        val path = uri.pathSegments.orEmpty()
        if (path.size >= 2 && path[0].equals("join", ignoreCase = true)) {
            return path[1].trim().takeIf { it.isNotEmpty() }
        }
        if (uri.host.equals("join", ignoreCase = true) && path.isNotEmpty()) {
            return path[0].trim().takeIf { it.isNotEmpty() }
        }
        return null
    }

    private fun showRoomInviteDialog(invite: RoomInvite) {
        if (showingInviteDialog || isFinishing) return
        showingInviteDialog = true
        RoomInviteNotifier.cancel(this, invite.inviteId)
        val sender = invite.fromUsername.ifBlank { getString(R.string.multiplayer_room_invite_unknown) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.multiplayer_room_invite_dialog_title)
            .setMessage(
                getString(
                    R.string.multiplayer_room_invite_dialog_message,
                    sender,
                    invite.roomCode
                )
            )
            .setPositiveButton(R.string.multiplayer_room_invite_join) { _, _ ->
                if (AppContainer.multiplayerAuth.activeLobby.value != null) {
                    Toast.makeText(this, R.string.multiplayer_room_invite_busy, Toast.LENGTH_LONG)
                        .show()
                    lifecycleScope.launch {
                        AppContainer.multiplayerAuth.dismissInvite(invite.inviteId)
                    }
                } else {
                    RoomInviteCoordinator.requestJoin(invite.roomCode, invite.inviteId)
                    navigateToMultiplayer()
                }
            }
            .setNegativeButton(R.string.multiplayer_dismiss_invite) { _, _ ->
                lifecycleScope.launch {
                    AppContainer.multiplayerAuth.dismissInvite(invite.inviteId)
                }
            }
            .setOnDismissListener { showingInviteDialog = false }
            .show()
    }

    private fun navigateToMultiplayer() {
        val navHost =
            supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as? NavHostFragment
                ?: return
        val navController = navHost.navController
        if (navController.currentDestination?.id != R.id.nav_multiplayer) {
            navigateTopDestination(navController, R.id.nav_multiplayer)
        }
        syncingNav = true
        binding.topNav.check(R.id.nav_multiplayer)
        syncingNav = false
    }

    private fun setupTopNav(navController: NavController) {
        binding.topNav.addOnButtonCheckedListener(
            MaterialButtonToggleGroup.OnButtonCheckedListener { _, checkedId, isChecked ->
                if (!isChecked || syncingNav) return@OnButtonCheckedListener
                if (checkedId in topDestinations &&
                    navController.currentDestination?.id != checkedId
                ) {
                    navigateTopDestination(navController, checkedId)
                }
            }
        )
        binding.topNav.check(R.id.nav_home)
    }

    private fun navigateTopDestination(navController: NavController, destinationId: Int) {
        if (!UiPluginManager.isFeatureEnabled("pageSlideTransitions")) {
            navController.navigate(destinationId)
            return
        }
        val from = topDestinations.indexOf(navController.currentDestination?.id ?: -1)
        val to = topDestinations.indexOf(destinationId)
        val forward = to >= from
        val options = NavOptions.Builder()
            .setLaunchSingleTop(true)
            .setEnterAnim(if (forward) R.anim.slide_in_right else R.anim.slide_in_left)
            .setExitAnim(if (forward) R.anim.slide_out_left else R.anim.slide_out_right)
            .setPopEnterAnim(if (forward) R.anim.slide_in_left else R.anim.slide_in_right)
            .setPopExitAnim(if (forward) R.anim.slide_out_right else R.anim.slide_out_left)
            .build()
        navController.navigate(destinationId, null, options)
    }

    private fun applyContentInsets(chromeVisible: Boolean) {
        val top = if (chromeVisible) 0 else systemTopInset
        binding.navHostFragment.setPadding(0, top, 0, systemBottomInset)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
