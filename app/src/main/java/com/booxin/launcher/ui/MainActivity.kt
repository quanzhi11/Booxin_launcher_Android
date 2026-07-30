package com.booxin.launcher.ui

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.booxin.launcher.R
import com.booxin.launcher.databinding.ActivityMainBinding
import com.booxin.launcher.ui.update.LauncherUpdateUi

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, systemBars.top, 0, 0)
            binding.bottomNav.setPadding(0, 0, 0, systemBars.bottom)
            insets
        }

        val navHost =
            supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHost.navController
        binding.bottomNav.setupWithNavController(navController)

        navController.addOnDestinationChangedListener { _, destination, _ ->
            val hideBottom = destination.id == R.id.nav_download ||
                destination.id == R.id.nav_version_manage
            binding.bottomNav.visibility = if (hideBottom) View.GONE else View.VISIBLE
        }

        if (savedInstanceState == null) {
            LauncherUpdateUi.check(
                activity = this,
                lifecycleOwner = this,
                silentWhenLatest = true
            )
        }
    }
}
