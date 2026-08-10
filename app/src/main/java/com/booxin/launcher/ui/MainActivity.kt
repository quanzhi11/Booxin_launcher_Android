package com.booxin.launcher.ui

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import com.booxin.launcher.R
import com.booxin.launcher.core.uiplugin.UiPluginFonts
import com.booxin.launcher.core.uiplugin.UiPluginTheme
import com.booxin.launcher.databinding.ActivityMainBinding
import com.booxin.launcher.ui.update.LauncherUpdateUi
import com.google.android.material.button.MaterialButtonToggleGroup

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var systemTopInset = 0
    private var systemBottomInset = 0
    private var syncingNav = false

    private val topDestinations = listOf(
        R.id.nav_home,
        R.id.nav_versions,
        R.id.nav_community,
        R.id.nav_multiplayer,
        R.id.nav_ai,
        R.id.nav_plugin_store,
        R.id.nav_settings
    )

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

        navController.addOnDestinationChangedListener { _, destination, _ ->
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
        }

        if (savedInstanceState == null) {
            LauncherUpdateUi.check(
                activity = this,
                lifecycleOwner = this,
                silentWhenLatest = true
            )
        }
    }

    override fun onResume() {
        super.onResume()
        UiPluginFonts.applyTo(this)
        UiPluginTheme.applyTo(this)
    }

    private fun setupTopNav(navController: NavController) {
        binding.topNav.addOnButtonCheckedListener(
            MaterialButtonToggleGroup.OnButtonCheckedListener { _, checkedId, isChecked ->
                if (!isChecked || syncingNav) return@OnButtonCheckedListener
                if (checkedId in topDestinations &&
                    navController.currentDestination?.id != checkedId
                ) {
                    navController.navigate(checkedId)
                }
            }
        )
        binding.topNav.check(R.id.nav_home)
    }

    private fun applyContentInsets(chromeVisible: Boolean) {
        val top = if (chromeVisible) 0 else systemTopInset
        binding.navHostFragment.setPadding(0, top, 0, systemBottomInset)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
