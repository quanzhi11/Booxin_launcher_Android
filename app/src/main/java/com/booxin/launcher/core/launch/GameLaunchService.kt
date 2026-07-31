package com.booxin.launcher.core.launch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.booxin.launcher.AppContainer
import com.booxin.launcher.R
import com.booxin.launcher.ui.launch.LaunchActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.lwjgl.glfw.CallbackBridge
import java.io.File

/**
 * Headless foreground service in `:game` process.
 * Must stay foreground — otherwise LMK kills it (oom_score_adj ~800).
 */
class GameLaunchService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runner by lazy { GameProcessRunner(this) }
    private var launchJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var muteReceiver: android.content.BroadcastReceiver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        muteReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == GameLaunchLogBus.ACTION_MUTE_UI) {
                    GameLaunchLogBus.muteUi.set(true)
                }
            }
        }
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            muteReceiver,
            android.content.IntentFilter(GameLaunchLogBus.ACTION_MUTE_UI),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

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
        val uuid = intent?.getStringExtra(EXTRA_UUID)
        val accessToken = intent?.getStringExtra(EXTRA_ACCESS_TOKEN)
        val userType = intent?.getStringExtra(EXTRA_USER_TYPE)
        val serverAddress = intent?.getStringExtra(EXTRA_SERVER_ADDRESS)?.takeIf { it.isNotBlank() }
        val windowWidth = intent?.getIntExtra(EXTRA_WINDOW_WIDTH, 0) ?: 0
        val windowHeight = intent?.getIntExtra(EXTRA_WINDOW_HEIGHT, 0) ?: 0
        if (versionId.isBlank()) {
            // Still enter FGS briefly so startForegroundService contract is met.
            startAsForeground("缺少版本 ID")
            appendLog("缺少版本 ID")
            finishAndStop(startId)
            return START_NOT_STICKY
        }

        startAsForeground(getString(R.string.launch_fg_text))
        acquireWakeLock()

        if (launchJob?.isActive == true) {
            appendLog("已有启动任务在运行")
            return START_NOT_STICKY
        }

        launchJob = scope.launch {
            try {
                runLaunch(
                    versionId = versionId,
                    username = username,
                    windowWidth = windowWidth,
                    windowHeight = windowHeight,
                    uuid = uuid,
                    accessToken = accessToken,
                    userType = userType,
                    serverAddress = serverAddress
                )
            } finally {
                finishAndStop(startId)
            }
        }

        return START_NOT_STICKY
    }

    private suspend fun runLaunch(
        versionId: String,
        username: String,
        windowWidth: Int,
        windowHeight: Int,
        uuid: String? = null,
        accessToken: String? = null,
        userType: String? = null,
        serverAddress: String? = null
    ) {
        appendLog("准备 Java 与游戏文件…")
        GameLaunchLogBus.muteUi.set(false)
        GameLaunchLogBus.beginSession(versionId)
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

        AndroidGameRuntime.ensure(this)

        // Prefer real Surface size (FCL writes options after TextureView is ready).
        appendLog("等待游戏 Surface…")
        val surface = runCatching { GameSurfaceBridge.awaitSurface() }.getOrElse {
            appendLog("Surface 超时: ${it.message}")
            return
        }
        val width = when {
            GameSurfaceBridge.width > 1 -> GameSurfaceBridge.width
            windowWidth > 0 -> windowWidth
            else -> resources.displayMetrics.widthPixels
        }
        val height = when {
            GameSurfaceBridge.height > 1 -> GameSurfaceBridge.height
            windowHeight > 0 -> windowHeight
            else -> resources.displayMetrics.heightPixels
        }
        GameSurfaceBridge.onSurfaceSizeChanged(width, height)
        // FCL JVMActivity: options.txt fullscreen=false + overrideWidth/Height before JVM.
        runCatching {
            val gameDir = File(
                com.booxin.launcher.core.LauncherPaths.versionsDir,
                versionId
            )
            GameOptionsPatch.applyWindowOverrides(gameDir, width, height)
            appendLog("options.txt → ${width}x${height} fullscreen=false")
        }.onFailure {
            appendLog("options.txt 写入失败: ${it.message}")
        }
        appendLog("构建启动命令…（窗口 ${width}x${height}，内存 ${com.booxin.launcher.core.LauncherPrefs.maxMemoryMb()} MB）")
        if (!serverAddress.isNullOrBlank()) {
            appendLog("自动加入服务器: $serverAddress")
        }
        val command = runCatching {
            LaunchCommandBuilder(this).build(
                versionId = versionId,
                username = username,
                java = java,
                maxMemoryMb = com.booxin.launcher.core.LauncherPrefs.maxMemoryMb(),
                windowWidth = width,
                windowHeight = height,
                uuid = uuid,
                accessToken = accessToken,
                userType = userType,
                serverAddress = serverAddress
            )
        }.getOrElse {
            appendLog("命令构建失败: ${it.message}")
            return
        }

        appendLog("配置 Pojav 环境…")
        JvmEnvironment.apply(this, java, command.env)
        appendLog(
            "渲染环境: POJAV_RENDERER=${command.env["POJAV_RENDERER"]} " +
                "LIBGL_STRING=${command.env["LIBGL_STRING"]} " +
                "POJAVEXEC_EGL=${command.env["POJAVEXEC_EGL"]} " +
                "libname=${command.jvmArgs.firstOrNull { it.startsWith("-Dorg.lwjgl.opengl.libname=") }}"
            )

        val isForgeOrLoader =
            com.booxin.launcher.core.download.game.VersionJsonMerger.isModLoaderVersion(versionId)
        if (isForgeOrLoader) {
            appendLog("Forge/模组版本：跳过 ART 侧 pojavexec 预加载")
        } else {
            appendLog("初始化 pojavexec（ART hooks）…")
            runCatching { PojavExecLoader.ensureLoaded() }.onFailure { err ->
                appendLog("pojavexec 初始化失败: ${err.message}")
                return
            }
        }
        // FCL: nativeSetUseInputStackQueue before JVM so ART touch reaches GLFW safely.
        val inputOk = CallbackBridge.enableAndroidInput()
        appendLog(
            if (inputOk) "输入桥已就绪（stack queue=ON）"
            else "输入桥警告：stack queue 未确认，触控可能卡死"
        )

        appendLog("绑定 GLFW 窗口…")
        runCatching { GameSurfaceBridge.attachToGlfw(surface) }.onFailure { err ->
            appendLog("setupBridgeWindow 失败: ${err.javaClass.simpleName}: ${err.message}")
            err.cause?.let { appendLog("  cause: ${it.message}") }
            return
        }
        appendLog("GLFW 窗口已绑定")

        // Re-arm after bridge (FCL also sets ready on first glfwPollEvents).
        val again = CallbackBridge.enableAndroidInput()
        appendLog("输入桥绑定后确认: stackQueue=$again")

        appendLog("探测 Java 运行时…")
        val probe = runner.probeJava(java, command.env)
        if (probe.isFailure) {
            appendLog("Java 探测失败: ${probe.exceptionOrNull()?.message}")
            return
        }
        appendLog(probe.getOrThrow())

        appendLog("启动 Minecraft JVM（前台 :game 进程）…")
        updateNotification("Minecraft 正在加载…")

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

    private fun startAsForeground(content: String) {
        val notification = buildNotification(content)
        if (Build.VERSION.SDK_INT >= 34) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(content: String) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIFICATION_ID, buildNotification(content))
    }

    private fun buildNotification(content: String): Notification {
        val open = Intent(this, LaunchActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(
            this,
            0,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = Intent(this, GameLaunchService::class.java).apply { action = ACTION_STOP }
        val stopPi = PendingIntent.getService(
            this,
            1,
            stop,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.launch_fg_title))
            .setContentText(content)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, getString(R.string.launch_stop), stopPi)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.launch_fg_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        nm.createNotificationChannel(channel)
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java) ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "booxin:game").apply {
            setReferenceCounted(false)
            acquire(3 * 60 * 60 * 1000L) // 3 hours max
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        wakeLock = null
    }

    private fun appendLog(line: String) {
        GameLaunchLogBus.emit(applicationContext, line)
    }

    private fun finishAndStop(startId: Int) {
        GameLaunchLogBus.finished(applicationContext)
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    override fun onDestroy() {
        muteReceiver?.let { runCatching { unregisterReceiver(it) } }
        muteReceiver = null
        runner.stop()
        launchJob?.cancel()
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_VERSION_ID = "version_id"
        const val EXTRA_USERNAME = "username"
        const val EXTRA_WINDOW_WIDTH = "window_width"
        const val EXTRA_WINDOW_HEIGHT = "window_height"
        const val EXTRA_UUID = "uuid"
        const val EXTRA_ACCESS_TOKEN = "access_token"
        const val EXTRA_USER_TYPE = "user_type"
        const val EXTRA_SERVER_ADDRESS = "server_address"
        const val ACTION_STOP = "com.booxin.launcher.STOP_GAME"
        private const val CHANNEL_ID = "booxin_game"
        private const val NOTIFICATION_ID = 2107

        fun start(
            context: Context,
            versionId: String,
            username: String,
            windowWidth: Int,
            windowHeight: Int,
            uuid: String? = null,
            accessToken: String? = null,
            userType: String? = null,
            serverAddress: String? = null
        ) {
            val intent = Intent(context, GameLaunchService::class.java).apply {
                putExtra(EXTRA_VERSION_ID, versionId)
                putExtra(EXTRA_USERNAME, username)
                putExtra(EXTRA_WINDOW_WIDTH, windowWidth.coerceAtLeast(64))
                putExtra(EXTRA_WINDOW_HEIGHT, windowHeight.coerceAtLeast(64))
                uuid?.let { putExtra(EXTRA_UUID, it) }
                accessToken?.let { putExtra(EXTRA_ACCESS_TOKEN, it) }
                userType?.let { putExtra(EXTRA_USER_TYPE, it) }
                serverAddress?.takeIf { it.isNotBlank() }?.let { putExtra(EXTRA_SERVER_ADDRESS, it) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, GameLaunchService::class.java).apply {
                action = ACTION_STOP
            }
            // stop action also goes through onStartCommand; use startService
            // (FGS already running) so we don't re-trigger FGS start contract.
            context.startService(intent)
        }
    }
}
