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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.booxin.launcher.AppContainer
import com.booxin.launcher.R
import com.booxin.launcher.core.diag.DiagEventLog
import com.booxin.launcher.core.diag.PerfSnapshot
import com.booxin.launcher.core.runtime.GameRuntimeBackends
import com.booxin.launcher.core.runtime.RuntimeEnv
import com.booxin.launcher.ui.launch.LaunchActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.io.File

/**
 * :game 前台服务。必须 foreground，否则 LMK 很容易杀掉。
 */
class GameLaunchService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runner by lazy { GameProcessRunner(this) }
    private var launchJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var muteReceiver: android.content.BroadcastReceiver? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var hardKillScheduled = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        // 切后台 / 熄屏会拆掉 Surface：保活 JVM，回前台再绑窗（不要杀进程）。
        GameSurfaceBridge.onSurfaceLostWhileRunning = {
            appendLog("Surface 已暂停（切后台/熄屏），保持游戏进程与前台服务…")
            acquireWakeLock()
        }
        GameSurfaceBridge.onSurfaceRestoredWhileRunning = {
            appendLog("Surface 已恢复，重新绑定游戏窗口…")
            scope.launch {
                rebindGameSurface(retries = 12)
            }
        }
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
                requestStop("user_stop")
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

        // FGS：尽快 startForeground
        startAsForeground(
            if (versionId.isBlank()) "缺少版本 ID" else getString(R.string.launch_fg_text)
        )

        if (versionId.isBlank()) {
            appendLog("缺少版本 ID")
            softStopSelf(startId)
            return START_NOT_STICKY
        }

        if (!LaunchSession.tryBegin()) {
            appendLog("已有启动任务（${LaunchSession.current()}）")
            return START_NOT_STICKY
        }

        acquireWakeLock()
        hardKillScheduled = false

        launchJob = scope.launch {
            var outcome: LaunchOutcome = LaunchOutcome.Failed("未知错误")
            try {
                coroutineScope {
                    outcome = runLaunch(
                        versionId = versionId,
                        username = username,
                        windowWidth = windowWidth,
                        windowHeight = windowHeight,
                        uuid = uuid,
                        accessToken = accessToken,
                        userType = userType,
                        serverAddress = serverAddress
                    )
                }
            } catch (e: CancellationException) {
                outcome = if (LaunchSession.hotspotEntered || hardKillScheduled) {
                    LaunchOutcome.HardExit("已取消")
                } else {
                    LaunchOutcome.Failed("已取消")
                }
                throw e
            } catch (t: Throwable) {
                outcome = if (LaunchSession.hotspotEntered) {
                    LaunchOutcome.HardExit(t.message ?: t.javaClass.simpleName)
                } else {
                    LaunchOutcome.Failed(t.message ?: t.javaClass.simpleName)
                }
            } finally {
                if (!hardKillScheduled) {
                    settleOutcome(outcome, startId)
                }
            }
        }

        return START_NOT_STICKY
    }

    private sealed class LaunchOutcome(val message: String) {
        class Failed(message: String) : LaunchOutcome(message)
        class HardExit(message: String) : LaunchOutcome(message)
        class Success(message: String) : LaunchOutcome(message)
    }

    private fun settleOutcome(outcome: LaunchOutcome, startId: Int) {
        when (outcome) {
            is LaunchOutcome.HardExit, is LaunchOutcome.Success -> {
                appendLog(outcome.message)
                scheduleHardKill(outcome.message)
            }
            is LaunchOutcome.Failed -> {
                appendLog("启动失败: ${outcome.message}")
                LaunchSession.fail(outcome.message)
                GameLaunchLogBus.emitFailed(applicationContext, outcome.message)
                softStopSelf(startId)
            }
        }
    }

    private fun requestStop(reason: String) {
        when (LaunchSession.requestStop()) {
            LaunchSession.StopKind.None -> {
                if (reason == "user_stop") {
                    scheduleHardKill(reason)
                } else {
                    softStopSelf(null)
                }
            }
            LaunchSession.StopKind.CancelJob -> {
                appendLog("取消启动: $reason")
                runner.stop()
                launchJob?.cancel()
                scheduleHardKill(reason)
            }
            LaunchSession.StopKind.HardKill -> {
                appendLog("结束游戏进程: $reason")
                runner.stop()
                launchJob?.cancel()
                scheduleHardKill(reason)
            }
        }
    }

    private fun scheduleHardKill(reason: String) {
        if (hardKillScheduled) return
        hardKillScheduled = true
        GameLaunchLogBus.finished(applicationContext)
        releaseWakeLock()
        mainHandler.post {
            runCatching {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            appendLog("killProcess :game ($reason)")
            Process.killProcess(Process.myPid())
        }
    }

    private fun softStopSelf(startId: Int?) {
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (startId != null) stopSelf(startId) else stopSelf()
    }

    private suspend fun CoroutineScope.runLaunch(
        versionId: String,
        username: String,
        windowWidth: Int,
        windowHeight: Int,
        uuid: String? = null,
        accessToken: String? = null,
        userType: String? = null,
        serverAddress: String? = null
    ): LaunchOutcome {
        appendLog("准备 Java 与游戏文件…")
        GameLaunchLogBus.muteUi.set(false)
        GameLaunchLogBus.beginSession(versionId)
        if (RealtimeLaunchLog.isEnabled()) {
            appendLog("实时日志已开启")
        }
        val prepare = AppContainer.gameRuntime.prepare(versionId)
        if (prepare.isFailure) {
            return LaunchOutcome.Failed("准备失败: ${prepare.exceptionOrNull()?.message}")
        }

        appendLog("校验并补全游戏资源（缺资源会闪退）…")
        val progressJob = launch {
            AppContainer.repository.installProgress.collect { progress ->
                if (progress == null) return@collect
                if (progress.phase != com.booxin.launcher.core.download.game.GameInstallPhase.ASSETS) {
                    return@collect
                }
                val detail = if (progress.total > 0) {
                    "${progress.message} (${progress.completed}/${progress.total})"
                } else {
                    progress.message
                }
                appendLog(detail)
            }
        }
        val assets = try {
            runCatching {
                AppContainer.repository.ensureGameAssets(versionId).getOrThrow()
            }
        } finally {
            progressJob.cancel()
        }
        if (assets.isFailure) {
            return LaunchOutcome.Failed(
                "资源补全失败: ${assets.exceptionOrNull()?.message}"
            )
        }
        appendLog("游戏资源就绪")

        SodiumPodiumInstaller.ensure(this@GameLaunchService, versionId)?.let { appendLog(it) }

        val java = AppContainer.javaEnvironment.ensureForMinecraft(versionId).getOrElse {
            return LaunchOutcome.Failed("Java 不可用: ${it.message}")
        }
        appendLog("Java 就绪: ${java.homeDir.absolutePath}")

        val backend = GameRuntimeBackends.current()
        backend.prepare(this@GameLaunchService)
        appendLog("运行时后端: ${backend.id}")

        LaunchSession.enterBinding()
        appendLog("等待游戏 Surface…")
        val surface = runCatching { GameSurfaceBridge.awaitValidSurface() }.getOrElse {
            return LaunchOutcome.Failed("Surface 超时: ${it.message}")
        }
        if (!surface.isValid) {
            return LaunchOutcome.Failed("Surface 无效")
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
        appendLog(PerfSnapshot.launchLine(this@GameLaunchService, width, height))
        runCatching {
            DiagEventLog.i("Perf", PerfSnapshot.launchLine(this@GameLaunchService, width, height))
        }
        runCatching {
            val gameDir = File(
                com.booxin.launcher.core.LauncherPaths.versionsDir,
                versionId
            )
            GameOptionsPatch.applyWindowOverrides(gameDir, width, height)
            appendLog("options.txt → ${width}x${height}，关垂直同步，maxFps+10")
        }.onFailure {
            appendLog("options.txt 写入失败: ${it.message}")
        }
        appendLog("构建启动命令…（窗口 ${width}x${height}，内存 ${com.booxin.launcher.core.LauncherPrefs.maxMemoryMb()} MB）")
        if (OemLaunchProfile.needsForgeShortClasspath()) {
            appendLog("vivo 系启动优化：Forge 短 classpath + legacyClassPath.file（${OemLaunchProfile.describe()}）")
        }
        if (com.booxin.launcher.core.java.MinecraftJavaRequirement.usesSdlWindowing(versionId)) {
            val sdl = File(com.booxin.launcher.core.launch.AndroidGameRuntime.nativesDir(), "libSDL3.so")
            val sdlJar = com.booxin.launcher.core.launch.AndroidGameRuntime.lwjglSdlJar()
            appendLog(
                if (sdl.isFile) "SDL3（${sdl.length()} bytes）"
                else "窗口系统: SDL3 需要但缺少 libSDL3.so"
            )
            appendLog(
                if (sdlJar.isFile) "LWJGL SDL 绑定: ${sdlJar.name}（${sdlJar.length()} bytes）"
                else "LWJGL SDL 绑定缺失: ${sdlJar.absolutePath}"
            )
        }
        if (!serverAddress.isNullOrBlank()) {
            appendLog("自动加入服务器: $serverAddress")
        }
        val command = runCatching {
            LaunchCommandBuilder(this@GameLaunchService).build(
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
            return LaunchOutcome.Failed("命令构建失败: ${it.message}")
        }

        appendLog("配置运行时环境…")
        backend.applyJvmEnvironment(this@GameLaunchService, java, command.env)
        appendLog(
            "渲染环境: ${RuntimeEnv.RENDERER}=${command.env[RuntimeEnv.RENDERER]} " +
                "LIBGL_STRING=${command.env["LIBGL_STRING"]} " +
                "${RuntimeEnv.EGL}=${command.env[RuntimeEnv.EGL]} " +
                "libname=${command.jvmArgs.firstOrNull { it.startsWith("-Dorg.lwjgl.opengl.libname=") }}"
        )
        System.getProperty("booxin.renderer.fallback")?.let {
            appendLog("渲染器回退: $it")
            System.clearProperty("booxin.renderer.fallback")
        }

        val skipArtPreload = shouldSkipArtExecPreload(versionId, command.mainClass)
        if (skipArtPreload) {
            appendLog("Forge/NeoForge：跳过 ART 侧 exec bridge 预加载")
        } else {
            appendLog("初始化 exec bridge（ART hooks）…")
            backend.ensureExecBridgeLoaded(skipArtPreload = false).onFailure { err ->
                return LaunchOutcome.Failed("exec bridge 初始化失败: ${err.message}")
            }
        }
        val inputOk = backend.enableInput()
        appendLog(
            if (inputOk) "输入桥已就绪（stack queue=ON）"
            else "输入桥提示：stack queue 暂未确认（Forge 常在 JVM 后才真正就绪）"
        )

        appendLog("绑定游戏窗口…")
        var bound = false
        run {
            repeat(40) { attempt ->
                val candidate = runCatching {
                    GameSurfaceBridge.awaitValidSurface(timeoutMs = 3_000L)
                }.getOrNull()
                if (candidate == null || !candidate.isValid) {
                    appendLog("Surface 无效，重试 ${attempt + 1}/40…")
                    kotlinx.coroutines.delay(200)
                    return@repeat
                }
                val ok = kotlinx.coroutines.withContext(Dispatchers.Main) {
                    runCatching { backend.attachSurface(candidate) }.getOrDefault(false)
                }
                if (ok) {
                    bound = true
                    if (GameSurfaceBridge.width > 0 && GameSurfaceBridge.height > 0) {
                        GameSurfaceBridge.onSurfaceSizeChanged(
                            GameSurfaceBridge.width,
                            GameSurfaceBridge.height
                        )
                    }
                    appendLog(if (attempt == 0) "游戏窗口已绑定" else "游戏窗口已绑定（重试 ${attempt + 1}）")
                    return@run
                }
                appendLog("窗口绑定未就绪，重试 ${attempt + 1}/40…")
                kotlinx.coroutines.delay(300)
            }
        }
        if (!bound) {
            return LaunchOutcome.Failed("setupBridgeWindow 失败: ANativeWindow 为空（Surface 未就绪）")
        }
        if (com.booxin.launcher.core.java.MinecraftJavaRequirement.usesSdlWindowing(versionId)) {
            runCatching {
                val act = LaunchActivity.foregroundOrNull()
                val surf = GameSurfaceBridge.currentSurface()
                if (act != null && surf != null) {
                    appendLog("初始化 SDL3（大栈 ART load）…")
                    kotlinx.coroutines.withContext(Dispatchers.Main) {
                        BooxinSdlBootstrap.maybePrepare(act, versionId, surf, width, height)
                    }
                    appendLog("SDL3 Android JNI 就绪")
                } else {
                    appendLog("跳过 SDL 绑定：activity=${act != null} surface=${surf != null}")
                }
            }.onFailure {
                appendLog("SDL3 绑定失败: ${it.javaClass.simpleName}: ${it.message}")
            }
        }
        runCatching {
            if (NativeJvmLauncher.initializeHooks()) {
                appendLog("输入 native 钩子已就绪")
            } else {
                appendLog("警告：输入钩子注册失败，触控可能无效")
            }
        }.onFailure { appendLog("initializeHooks: ${it.message}") }

        val again = backend.enableInput()
        appendLog(
            if (again) "输入桥绑定后确认: stackQueue=true"
            else "输入桥绑定后确认: stackQueue=false（Forge 在 JVM 起来前常如此，属正常）"
        )

        appendLog("探测 Java 运行时…")
        val probe = runner.probeJava(java, command.env)
        if (probe.isFailure) {
            return LaunchOutcome.Failed("Java 探测失败: ${probe.exceptionOrNull()?.message}")
        }
        appendLog(probe.getOrThrow())

        appendLog("启动 Minecraft JVM（前台 :game 进程）…")
        appendLog("提示：创建 JVM / 加载 Forge 主类可能要一两分钟，进度会持续刷新")
        updateNotification("Minecraft 正在加载…")

        if (!LaunchSession.enterRunning()) {
            return LaunchOutcome.Failed("已停止")
        }

        val logJob = launch {
            runner.logs.collect { appendLog(it) }
        }
        return try {
            val exit = runner.start(command, java, applyEnvironment = false)
            if (exit.isFailure) {
                LaunchOutcome.HardExit("启动失败: ${exit.exceptionOrNull()?.message}")
            } else {
                LaunchOutcome.Success("进程已结束，退出码 ${exit.getOrNull() ?: -1}")
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

    private suspend fun rebindGameSurface(retries: Int) {
        val backend = GameRuntimeBackends.current()
        val epoch = GameSurfaceBridge.currentRebindEpoch()
        repeat(retries) { attempt ->
            if (GameSurfaceBridge.currentRebindEpoch() != epoch) {
                appendLog("重绑已取消（Surface 再次变化）")
                return
            }
            val candidate = GameSurfaceBridge.currentSurface()
            if (candidate == null || !candidate.isValid) {
                appendLog("重绑等待 Surface… ${attempt + 1}/$retries")
                kotlinx.coroutines.delay(250)
                return@repeat
            }
            // Wait for a real size — attaching a 0x0 window breaks EGL on some OEMs.
            if (GameSurfaceBridge.width <= 1 || GameSurfaceBridge.height <= 1) {
                appendLog("重绑等待尺寸… ${attempt + 1}/$retries")
                kotlinx.coroutines.delay(200)
                return@repeat
            }
            val ok = kotlinx.coroutines.withContext(Dispatchers.Main) {
                runCatching {
                    backend.attachSurface(candidate)
                }.getOrDefault(false)
            }
            if (ok) {
                GameSurfaceBridge.onSurfaceSizeChanged(
                    GameSurfaceBridge.width,
                    GameSurfaceBridge.height
                )
                GameSurfaceBridge.markRebound()
                backend.enableInput()
                appendLog(
                    if (attempt == 0) "游戏窗口已重新绑定"
                    else "游戏窗口已重新绑定（重试 ${attempt + 1}）"
                )
                updateNotification("Minecraft 运行中")
                return
            }
            appendLog("窗口重绑未就绪，重试 ${attempt + 1}/$retries…")
            kotlinx.coroutines.delay(300)
        }
        appendLog("窗口重绑失败：Surface 或 ANativeWindow 仍不可用")
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java) ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "booxin:game").apply {
            setReferenceCounted(false)
            acquire(3 * 60 * 60 * 1000L)
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

    override fun onDestroy() {
        GameSurfaceBridge.onSurfaceLostWhileRunning = null
        GameSurfaceBridge.onSurfaceRestoredWhileRunning = null
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
            context.startService(intent)
        }
    }

    private fun shouldSkipArtExecPreload(versionId: String, mainClass: String): Boolean {
        val id = versionId.lowercase()
        val main = mainClass.lowercase()
        if ("knotclient" in main || "fabricmc.loader" in main || "quiltmc.loader" in main) {
            return false
        }
        return "neoforge" in id ||
            id.contains("-forge-") ||
            id.endsWith("-forge") ||
            "bootstraplauncher" in main ||
            "modlauncher" in main ||
            "cpw.mods" in main ||
            "neoforgesdk" in main
    }
}
