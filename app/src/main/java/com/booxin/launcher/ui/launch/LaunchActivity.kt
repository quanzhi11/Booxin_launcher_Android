package com.booxin.launcher.ui.launch

import android.content.BroadcastReceiver
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.booxin.launcher.R
import com.booxin.launcher.core.launch.GameLaunchLogBus
import com.booxin.launcher.core.launch.GameLaunchService
import com.booxin.launcher.databinding.ActivityLaunchBinding

/**
 * UI lives in the main process. JVM runs in [GameLaunchService] (:game).
 */
class LaunchActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLaunchBinding
    private val logBuffer = StringBuilder()
    private var logReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLaunchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val versionId = intent.getStringExtra(EXTRA_VERSION_ID).orEmpty()
        val username = intent.getStringExtra(EXTRA_USERNAME).orEmpty().ifBlank { "Player" }
        if (versionId.isBlank()) {
            appendLog("缺少版本 ID")
            binding.progressLaunch.visibility = View.GONE
            return
        }

        binding.textLaunchMeta.text = getString(R.string.launch_meta, versionId, username)
        binding.buttonClose.setOnClickListener {
            GameLaunchService.stop(this)
            finish()
        }
        binding.buttonStop.setOnClickListener {
            appendLog("用户请求停止…")
            GameLaunchService.stop(this)
        }

        logReceiver = GameLaunchLogBus.register(
            context = this,
            onLine = { line -> runOnUiThread { appendLog(line) } },
            onFinished = {
                runOnUiThread {
                    binding.progressLaunch.visibility = View.GONE
                }
            }
        )

        appendLog("启动游戏服务（独立 :game 进程）…")
        GameLaunchService.start(this, versionId, username)
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
        logReceiver?.let { unregisterReceiver(it) }
        logReceiver = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_VERSION_ID = "version_id"
        const val EXTRA_USERNAME = "username"
    }
}
