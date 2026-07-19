package com.booxin.launcher.core.launch

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.booxin.launcher.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Headless service in `:game` process — runs the JVM without UI/HWUI threads.
 * Logs are broadcast to [LaunchActivity] in the main process via [GameLaunchLogBus].
 */
class GameLaunchService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runner by lazy { GameProcessRunner(this) }
    private var launchJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                appendLog("收到停止请求")
                runner.stop()
                launchJob?.cancel()
                finishAndStop(startId)
                return START_NOT_STICKY
            }
        }

        val versionId = intent?.getStringExtra(EXTRA_VERSION_ID).orEmpty()
        val username = intent?.getStringExtra(EXTRA_USERNAME).orEmpty().ifBlank { "Player" }
        if (versionId.isBlank()) {
            appendLog("缺少版本 ID")
            finishAndStop(startId)
            return START_NOT_STICKY
        }

        if (launchJob?.isActive == true) {
            appendLog("已有启动任务在运行")
            return START_NOT_STICKY
        }

        launchJob = scope.launch {
            try {
                runLaunch(versionId, username)
            } finally {
                finishAndStop(startId)
            }
        }

        return START_NOT_STICKY
    }

    private suspend fun runLaunch(versionId: String, username: String) {
        appendLog("准备 Java 与游戏文件…")
        val prepare = AppContainer.gameRuntime.prepare(versionId)
        if (prepare.isFailure) {
            appendLog("准备失败: ${prepare.exceptionOrNull()?.message}")
            return
        }

        val java = AppContainer.javaEnvironment.ensureForMinecraft(versionId).getOrElse {
            appendLog("Java 不可用: ${it.message}")
            return
        }
        appendLog("Java 就绪: ${java.homeDir.absolutePath}")

        appendLog("探测 Java 运行时（JLI）…")
        val probe = runner.probeJava(java)
        if (probe.isFailure) {
            appendLog("Java 探测失败: ${probe.exceptionOrNull()?.message}")
            return
        }
        appendLog(probe.getOrThrow())

        appendLog("构建启动命令…")
        val command = runCatching {
            LaunchCommandBuilder(this).build(
                versionId = versionId,
                username = username,
                java = java
            )
        }.getOrElse {
            appendLog("命令构建失败: ${it.message}")
            return
        }

        appendLog("启动 Minecraft JVM（独立 :game 进程）…")
        val logJob = scope.launch {
            runner.logs.collect { appendLog(it) }
        }

        try {
            val exit = runner.start(command, java)
            if (exit.isFailure) {
                appendLog("启动失败: ${exit.exceptionOrNull()?.message}")
            } else {
                appendLog("进程已结束，退出码 ${exit.getOrNull() ?: -1}")
            }
        } finally {
            logJob.cancel()
        }
    }

    private fun appendLog(line: String) {
        GameLaunchLogBus.emit(applicationContext, line)
    }

    private fun finishAndStop(startId: Int) {
        GameLaunchLogBus.finished(applicationContext)
        stopSelf(startId)
    }

    override fun onDestroy() {
        runner.stop()
        launchJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_VERSION_ID = "version_id"
        const val EXTRA_USERNAME = "username"
        const val ACTION_STOP = "com.booxin.launcher.STOP_GAME"

        fun start(context: Context, versionId: String, username: String) {
            val intent = Intent(context, GameLaunchService::class.java).apply {
                putExtra(EXTRA_VERSION_ID, versionId)
                putExtra(EXTRA_USERNAME, username)
            }
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, GameLaunchService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
