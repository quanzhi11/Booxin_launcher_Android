package com.booxin.launcher.ui.launch

import android.Manifest
import android.content.BroadcastReceiver
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.booxin.launcher.R
import com.booxin.launcher.core.launch.GameLaunchLogBus
import com.booxin.launcher.core.launch.GameLaunchService
import com.booxin.launcher.core.launch.GameSurfaceBridge
import com.booxin.launcher.databinding.ActivityLaunchBinding

/**
 * Runs in `:game` with [SurfaceView] + JVM so pojavexec can bind ANativeWindow.
 */
class LaunchActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLaunchBinding
    private val logBuffer = StringBuilder()
    private var logReceiver: BroadcastReceiver? = null
    private var pendingVersionId: String = ""
    private var pendingUsername: String = "Player"
    private var serviceStarted = false

    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        maybeStartGameService()
    }

    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            GameSurfaceBridge.onSurfaceCreated(holder.surface)
            appendLog("游戏 Surface 已创建")
            maybeStartGameService()
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            GameSurfaceBridge.onSurfaceDestroyed()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLaunchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        pendingVersionId = intent.getStringExtra(EXTRA_VERSION_ID).orEmpty()
        pendingUsername = intent.getStringExtra(EXTRA_USERNAME).orEmpty().ifBlank { "Player" }
        if (pendingVersionId.isBlank()) {
            appendLog("缺少版本 ID")
            binding.progressLaunch.visibility = View.GONE
            return
        }

        binding.textLaunchMeta.text = getString(R.string.launch_meta, pendingVersionId, pendingUsername)
        binding.buttonClose.setOnClickListener {
            GameLaunchService.stop(this)
            finish()
        }
        binding.buttonStop.setOnClickListener {
            appendLog("用户请求停止…")
            GameLaunchService.stop(this)
        }

        binding.surfaceGame.holder.addCallback(surfaceCallback)

        logReceiver = GameLaunchLogBus.register(
            context = this,
            onLine = { line ->
                runOnUiThread {
                    appendLog(line)
                    if (line.contains("Render thread") || line.contains("Backend library")) {
                        hideOverlay()
                    }
                }
            },
            onFinished = {
                runOnUiThread {
                    binding.progressLaunch.visibility = View.GONE
                }
            }
        )

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            appendLog("请求通知权限（前台服务需要）…")
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            maybeStartGameService()
        }
    }

    private fun maybeStartGameService() {
        if (serviceStarted || pendingVersionId.isBlank()) return
        if (!GameSurfaceBridge.hasSurface()) {
            appendLog("等待 Surface…")
            return
        }
        serviceStarted = true
        appendLog("启动游戏前台服务（:game 进程）…")
        GameLaunchService.start(this, pendingVersionId, pendingUsername)
    }

    private fun hideOverlay() {
        binding.panelOverlay.animate()
            .alpha(0f)
            .setDuration(300)
            .withEndAction { binding.panelOverlay.visibility = View.GONE }
            .start()
    }

    private fun appendLog(line: String) {
        if (line.isBlank()) return
        if (logBuffer.isNotEmpty()) logBuffer.append('\n')
        logBuffer.append(line)
        binding.textLog.text = logBuffer.toString()
        binding.scrollLog.post {
            binding.scrollLog.fullScroll(View.FOCUS_DOWN)
        }
    }

    override fun onDestroy() {
        binding.surfaceGame.holder.removeCallback(surfaceCallback)
        logReceiver?.let { unregisterReceiver(it) }
        logReceiver = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_VERSION_ID = "version_id"
        const val EXTRA_USERNAME = "username"
    }
}
