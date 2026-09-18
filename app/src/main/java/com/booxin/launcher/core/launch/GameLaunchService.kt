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
import com.booxin.launcher.core.LauncherPaths
import com.booxin.launcher.core.diag.DiagEventLog
import com.booxin.launcher.core.diag.PerfSnapshot
import com.booxin.launcher.core.download.game.VersionJsonMerger
import com.booxin.launcher.core.runtime.GameRuntimeBackends
import com.booxin.launcher.core.runtime.RuntimeEnv
import com.booxin.launcher.core.version.AndroidIncompatibleMods
import com.booxin.launcher.core.version.VersionModsManager
import com.booxin.launcher.ui.launch.LaunchActivity
import com.booxin.runtime.BooxinBridge
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
    @Volatile private var userRequestedStop = false

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
        val offlineSkinPath = intent?.getStringExtra(EXTRA_OFFLINE_SKIN_PATH)?.takeIf { it.isNotBlank() }
        val authServerUrl = intent?.getStringExtra(EXTRA_AUTH_SERVER_URL)?.takeIf { it.isNotBlank() }
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
        userRequestedStop = false
        OemLaunchProfile.applyGameProcessBoost(this)
        if (OemLaunchProfile.isOplusFamily()) {
            appendLog("ColorOS 性能保活：提高调度优先级 / GameState / 前台通知")
        }

        launchJob = scope.launch {
            var outcome: LaunchOutcome = LaunchOutcome.Failed("未知错误")
            var relHealRetryUsed = false
            var lastExitCode = -1
            try {
                coroutineScope {
                    while (true) {
                        outcome = runLaunch(
                            versionId = versionId,
                            username = username,
                            windowWidth = windowWidth,
                            windowHeight = windowHeight,
                            uuid = uuid,
                            accessToken = accessToken,
                            userType = userType,
                            serverAddress = serverAddress,
                            offlineSkinPath = offlineSkinPath,
                            authServerUrl = authServerUrl
                        )
                        if (LaunchSession.current() == LaunchPhase.Stopping) break

                        // Only after REL already ran with VRAM mitigations and still died.
                        if (!relHealRetryUsed) {
                            val healed = com.booxin.launcher.core.runtime.RendererCrashHeal
                                .healRelWorldJoinCrash(this@GameLaunchService, versionId)
                            if (healed != null) {
                                relHealRetryUsed = true
                                appendLog(healed)
                                appendLog("使用 MobileGlues 自动重试启动…")
                                LaunchSession.fail("retry-after-rel-crash")
                                if (!LaunchSession.tryBegin()) {
                                    appendLog("无法重试启动（会话忙）")
                                    break
                                }
                                continue
                            }
                        }

                        lastExitCode = when (outcome) {
                            is LaunchOutcome.Success -> outcome.exitCode
                            else -> -1
                        }
                        break
                    }
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
                if (!hardKillScheduled && !userRequestedStop) {
                    maybeStoreCrashReport(versionId, lastExitCode)
                    settleOutcome(outcome, startId)
                } else if (!hardKillScheduled) {
                    // User exit: still tear down without crash dialog.
                    GameSessionLease.markUserExit(applicationContext)
                    settleOutcome(outcome, startId)
                }
            }
        }

        return START_NOT_STICKY
    }

    private sealed class LaunchOutcome(val message: String) {
        class Failed(message: String) : LaunchOutcome(message)
        class HardExit(message: String) : LaunchOutcome(message)
        class Success(message: String, val exitCode: Int = 0) : LaunchOutcome(message)
    }

    private fun settleOutcome(outcome: LaunchOutcome, startId: Int) {
        com.booxin.launcher.core.skin.OfflineSkinLaunch.shutdown()
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
        val intentional = reason == "user_stop" ||
            reason.contains("取消") ||
            reason.contains("用户")
        if (intentional) {
            userRequestedStop = true
            GameSessionLease.markUserExit(applicationContext)
        }
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
                // Set kill flag BEFORE cancel so coroutine finally skips crash report.
                if (intentional) hardKillScheduled = true
                runner.stop()
                launchJob?.cancel()
                scheduleHardKill(reason)
            }
            LaunchSession.StopKind.HardKill -> {
                appendLog("结束游戏进程: $reason")
                if (intentional) hardKillScheduled = true
                runner.stop()
                launchJob?.cancel()
                scheduleHardKill(reason)
            }
        }
    }

    private fun scheduleHardKill(reason: String) {
        val intentional = reason == "user_stop" ||
            reason.contains("取消") ||
            reason.contains("用户")
        if (intentional) {
            userRequestedStop = true
            GameSessionLease.markUserExit(applicationContext)
        }
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
        serverAddress: String? = null,
        offlineSkinPath: String? = null,
        authServerUrl: String? = null
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

        SodiumCompatPrep.prepare(versionId)?.let { appendLog(it) }

        appendLog("正在扫描模组兼容性…")
        val modScan = AndroidIncompatibleMods.scanAndDisable(versionId)
        if (modScan.changed) {
            appendLog("发现不兼容模组（仅禁用 ARM 无法加载的），已处理:")
            modScan.disabled.forEach { appendLog("  · $it") }
        } else {
            appendLog("未发现必须禁用的模组（x86 原生库等）")
        }
        val enabledMods = VersionModsManager.list(versionId).count { it.enabled }
        if (VersionJsonMerger.isModLoaderVersion(versionId)) {
            appendLog("模组列表就绪：启用 $enabledMods 个")
        }

        val gameDirForCompat = File(
            com.booxin.launcher.core.LauncherPaths.versionsDir,
            versionId
        )
        val compat = ModCompatPrep.prepare(gameDirForCompat, versionId)
        compat.notes.forEach { appendLog(it) }
        // Stash for MobileGlues config a few steps below.
        val needsMgCompute = compat.needsComputeShader

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
        var width = when {
            GameSurfaceBridge.width > 1 -> GameSurfaceBridge.width
            windowWidth > 0 -> windowWidth
            else -> resources.displayMetrics.widthPixels
        }
        var height = when {
            GameSurfaceBridge.height > 1 -> GameSurfaceBridge.height
            windowHeight > 0 -> windowHeight
            else -> resources.displayMetrics.heightPixels
        }
        val physicalWidth = width
        val physicalHeight = height
        GameSurfaceBridge.noteViewSize(physicalWidth, physicalHeight)
        val mcVersionId = runCatching {
            VersionJsonMerger.resolveMinecraftVersionId(versionId)
        }.getOrDefault(versionId)
        val rendererKind = com.booxin.launcher.core.runtime.RendererBackend.kindForLaunch(
            instanceVersionId = versionId,
            mcVersionId = mcVersionId
        )
        val launchTune = runCatching {
            BooxinLaunchTune.resolve(this@GameLaunchService)
        }.getOrElse {
            appendLog("启动调优解析失败，使用当前设置: ${it.message}")
            null
        }
        if (launchTune != null) {
            appendLog("Booxin 启动调优：${launchTune.summaryZh}")
            // holy GL4ES + 降分辨率：Mojang 后常黑屏。MobileGlues 保留调优缩放（否则
            // 全分辨率 + Auto guiScale 会把界面放得过大）。
            val resScale =
                if (rendererKind == com.booxin.launcher.core.launch.GlRendererKind.GL4ES &&
                    launchTune.resolutionScale < 0.99f
                ) {
                    appendLog(
                        "GL4ES：禁用分辨率缩放 ${"%.0f".format(launchTune.resolutionScale * 100)}%→100%（避免黑屏）"
                    )
                    1f
                } else {
                    launchTune.resolutionScale
                }
            BooxinLaunchTune.scaledWindow(width, height, resScale)
                ?.let { (w, h) ->
                    appendLog(
                        "渲染分辨率 ${width}x${height} → ${w}x${h} " +
                            "（${(resScale * 100).toInt()}%，降低填色压力）"
                    )
                    width = w
                    height = h
                }
        }
        val requestedMem = launchTune?.maxMemoryMb
            ?: com.booxin.launcher.core.LauncherPrefs.maxMemoryMb()
        val relGuard = com.booxin.launcher.core.runtime.RendererCrashHeal.buildVramGuard(
            width = width,
            height = height,
            requestedMemoryMb = requestedMem,
            kind = rendererKind
        )
        if (relGuard != null) {
            width = relGuard.width
            height = relGuard.height
            appendLog("REL 显存防护：${relGuard.notes.joinToString("；")}")
            com.booxin.launcher.core.runtime.RendererCrashHeal.markMitigationsApplied(versionId)
        }
        // MobileGlues official backend: write its own config.json (GLSL cache / no compute path).
        if (rendererKind == com.booxin.launcher.core.launch.GlRendererKind.MOBILE_GLUES) {
            com.booxin.launcher.core.runtime.MobileGluesConfig.writeProfile(
                this@GameLaunchService,
                launchTune,
                mcVersionId = mcVersionId ?: versionId,
                enableComputeShader = needsMgCompute
            )
            appendLog(
                if (needsMgCompute) {
                    "已写入 MobileGlues 性能配置（含 compute 扩展）"
                } else {
                    "已写入 MobileGlues 性能配置（官方后端自读取）"
                }
            )
        }
        if (rendererKind == com.booxin.launcher.core.launch.GlRendererKind.MCRENDER) {
            com.booxin.launcher.core.runtime.McRenderConfig.ensureForLaunch(
                this@GameLaunchService,
                rendererKind
            )
            appendLog("已写入 mcrender.conf（compat=0，修正偏色）")
        }
        // Align SurfaceTexture buffer with GLFW size *before* EGL create. REL has no
        // present blit when FSR is off — a full-physical EGL + shrunk viewport leaves
        // the unused FB uncleared (solid red) and the game stuck in the bottom-left.
        val needBufferAlign = width != physicalWidth || height != physicalHeight
        kotlinx.coroutines.withContext(Dispatchers.Main) {
            if (needBufferAlign) {
                GameSurfaceBridge.applyRenderSize(width, height)
                appendLog(
                    "渲染缓冲对齐 Surface ${width}x${height}（物理 ${physicalWidth}x${physicalHeight}）"
                )
            } else {
                GameSurfaceBridge.onSurfaceSizeChanged(width, height)
            }
        }
        appendLog(PerfSnapshot.launchLine(this@GameLaunchService, width, height))
        runCatching {
            DiagEventLog.i("Perf", PerfSnapshot.launchLine(this@GameLaunchService, width, height))
        }
        runCatching {
            val gameDir = File(
                com.booxin.launcher.core.LauncherPaths.versionsDir,
                versionId
            )
            val preferVulkan =
                rendererKind == com.booxin.launcher.core.launch.GlRendererKind.BOOXIN_GLUES ||
                    rendererKind == com.booxin.launcher.core.launch.GlRendererKind.BOOXIN_ZINK
            GameOptionsPatch.applyWindowOverrides(
                gameDir,
                width,
                height,
                launchTune,
                relMipmapCap = relGuard?.mipmapLevels,
                preferVulkanApi = preferVulkan
            )
            if (preferVulkan) {
                appendLog("BooxinGlues：preferredGraphicsBackend=vulkan（并清除上次崩溃强制的 OpenGL）")
            }
            if (versionId.contains("optifine", ignoreCase = true)) {
                if (GameOptionsPatch.disableOptiFineShadersOnce(gameDir)) {
                    appendLog("OptiFine：已关闭从其他启动器带入的光影（避免卡死），可在游戏内重新开启")
                }
            }
            val rd = launchTune?.renderDistance
                ?: com.booxin.launcher.core.LauncherPrefs.renderDistance()
            val vsync = launchTune?.enableVsync
                ?: com.booxin.launcher.core.LauncherPrefs.enableVsync()
            appendLog("options.txt → ${width}x${height}，rd=$rd，vsync=$vsync，lang=zh_cn")
        }.onFailure {
            appendLog("options.txt 写入失败: ${it.message}")
        }
        val effectiveMemoryMb = relGuard?.maxMemoryMb ?: requestedMem
        appendLog("构建启动命令…（窗口 ${width}x${height}，内存 ${effectiveMemoryMb} MB）")
        appendLog("设备配置文件: ${OemLaunchProfile.describe()}")
        if (OemLaunchProfile.needsClasspathMitigation()) {
            appendLog(
                "OEM CreateJavaVM 优化：短 classpath / classpath.jar（${OemLaunchProfile.describe()}）"
            )
        }
        val userRenderer = com.booxin.launcher.core.LauncherPrefs.rendererKind()
        if (userRenderer != null &&
            OemLaunchProfile.shouldForceMobileGlues(userRenderer) &&
            rendererKind == com.booxin.launcher.core.launch.GlRendererKind.MOBILE_GLUES
        ) {
            appendLog(
                "OEM 渲染：${userRenderer.displayName} 在此机型易黑屏/无法启动，已改用 MobileGlues"
            )
        } else if (userRenderer != null && userRenderer != rendererKind) {
            appendLog("渲染器：26.3+ 使用 ${rendererKind.displayName}")
        } else if (userRenderer != null) {
            appendLog("渲染器：用户指定 ${userRenderer.displayName}（不自动更换）")
        } else if (
            OemLaunchProfile.shouldUpgradeGl4esToMobileGlues() &&
            rendererKind == com.booxin.launcher.core.launch.GlRendererKind.MOBILE_GLUES
        ) {
            appendLog("OEM 渲染：自动模式使用 MobileGlues（避免 GL4ES 黑屏/无帧）")
        }
        val idLower = versionId.lowercase()
        if ("fabric" in idLower || "quilt" in idLower) {
            appendLog("Fabric/Quilt：使用完整 classpath（避免 loader 被打进错误 ClassLoader）")
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
            // Do not pin as "Booxin 联机隧道" — join via LAN / --server / official list only.
            appendLog("自动加入服务器: $serverAddress")
        }
        val offlineSkin = runCatching {
            if (
                (accessToken.isNullOrBlank() || accessToken == "0" ||
                    userType.equals("legacy", ignoreCase = true)) &&
                com.booxin.launcher.core.skin.OfflineSkinLaunch.resolveSkinFile(
                    username,
                    offlineSkinPath
                ) != null
            ) {
                if (!com.booxin.launcher.core.skin.AuthlibInjectorInstaller.isReady()) {
                    appendLog("正在下载 authlib-injector（原版离线皮肤，非模组）…")
                }
            }
            com.booxin.launcher.core.skin.OfflineSkinLaunch.prepare(
                username = username,
                uuidNoDash = uuid,
                accessToken = accessToken,
                userType = userType,
                skinPathHint = offlineSkinPath
            )
        }.onFailure {
            appendLog("离线皮肤准备失败: ${it.message}")
        }.getOrNull()
        if (offlineSkin != null) {
            appendLog("原版离线皮肤已启用（authlib-injector @ ${offlineSkin.apiRoot}）")
        } else if (
            (accessToken.isNullOrBlank() || accessToken == "0") &&
            com.booxin.launcher.core.skin.OfflineSkinLaunch.resolveSkinFile(
                username,
                offlineSkinPath
            ) != null
        ) {
            appendLog("离线皮肤未能启用（将使用默认皮肤）")
        }
        val thirdPartyAgent = if (offlineSkin == null && !authServerUrl.isNullOrBlank()) {
            runCatching {
                if (!com.booxin.launcher.core.skin.AuthlibInjectorInstaller.isReady()) {
                    appendLog("正在下载 authlib-injector（第三方登录）…")
                }
                com.booxin.launcher.core.auth.ThirdPartyAuthService.prepareLaunchAgent(authServerUrl)
            }.onFailure {
                appendLog("第三方 authlib-injector 准备失败: ${it.message}")
            }.getOrNull()
        } else {
            null
        }
        if (thirdPartyAgent != null) {
            appendLog("第三方登录已启用（authlib-injector @ ${thirdPartyAgent.apiRoot}）")
        } else if (offlineSkin == null && !authServerUrl.isNullOrBlank()) {
            appendLog("第三方 authlib-injector 未能启用")
        }
        val command = runCatching {
            LaunchCommandBuilder(this@GameLaunchService).build(
                versionId = versionId,
                username = username,
                java = java,
                maxMemoryMb = effectiveMemoryMb,
                windowWidth = width,
                windowHeight = height,
                uuid = uuid,
                accessToken = accessToken,
                userType = userType,
                serverAddress = serverAddress,
                javaAgentArg = offlineSkin?.javaAgentArg ?: thirdPartyAgent?.javaAgentArg,
                offlineSkinAccessToken = offlineSkin?.accessToken,
                offlineSkinUserType = offlineSkin?.userType,
                offlineSkinExtraJvmArgs = offlineSkin?.extraJvmArgs
                    ?: thirdPartyAgent?.extraJvmArgs.orEmpty(),
                forceVsync = launchTune?.enableVsync
            )
        }.getOrElse {
            com.booxin.launcher.core.skin.OfflineSkinLaunch.shutdown()
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
            // Cache ART ClassLoader early so HotSpot can finish SDL JNI even if Surface
            // attach is delayed.
            runCatching {
                NativeJvmLauncher.cacheArtClassLoader(BooxinSdlBootstrap::class.java.classLoader)
            }
            runCatching {
                val act = LaunchActivity.foregroundOrNull()
                val surf = GameSurfaceBridge.currentSurface()
                if (act != null && surf != null) {
                    appendLog("初始化 SDL3（仅附着 Surface，库延后加载）…")
                    kotlinx.coroutines.withContext(Dispatchers.Main) {
                        BooxinSdlBootstrap.maybePrepare(act, versionId, surf, width, height)
                    }
                    appendLog("SDL3 Surface 已附着（JVM 创建后再完成 JNI）")
                    // Avoid SIGSEGV=IGN during CreateJavaVM while SDL is not yet loaded.
                    runCatching {
                        android.system.Os.setenv("BOOXIN_KEEP_SIGSEGV", "1", true)
                    }
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

        // LAN MOTD must run in :game — main-process multicast often never reaches MC here.
        com.booxin.launcher.core.multiplayer.GameProcessLanMotd.startIfRoomActive(applicationContext)

        if (!LaunchSession.enterRunning()) {
            return LaunchOutcome.Failed("已停止")
        }
        GameSessionLease.mark(applicationContext, versionId, hotspotEntered = true)

        val logJob = launch {
            runner.logs.collect { appendLog(it) }
        }
        return try {
            val exit = runner.start(command, java, applyEnvironment = false)
            if (exit.isFailure) {
                LaunchOutcome.HardExit("启动失败: ${exit.exceptionOrNull()?.message}")
            } else {
                val code = exit.getOrNull() ?: -1
            LaunchOutcome.Success("进程已结束，退出码 $code", code)
            }
        } finally {
            logJob.cancel()
            com.booxin.launcher.core.multiplayer.GameProcessLanMotd.stop()
        }
    }

    private fun maybeStoreCrashReport(versionId: String, exitCode: Int) {
        if (userRequestedStop) {
            GameSessionLease.markUserExit(applicationContext)
            return
        }
        try {
            val report = GameCrashAnalyzer.analyze(
                versionId = versionId,
                exitCode = exitCode,
                gameWasRunning = LaunchSession.hotspotEntered
            ) ?: return
            GameCrashReportStore.save(applicationContext, report)
            appendLog("已记录崩溃报告：${report.summary}")
        } finally {
            GameSessionLease.clear(applicationContext)
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
        val priority = if (OemLaunchProfile.isOplusFamily()) {
            NotificationCompat.PRIORITY_DEFAULT
        } else {
            NotificationCompat.PRIORITY_LOW
        }
        return NotificationCompat.Builder(this, foregroundChannelId())
            .setContentTitle(getString(R.string.launch_fg_title))
            .setContentText(content)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, getString(R.string.launch_stop), stopPi)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(priority)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        // ColorOS often demotes LOW-importance FGS; dedicated DEFAULT channel for oplus.
        val id = foregroundChannelId()
        val importance = if (OemLaunchProfile.isOplusFamily()) {
            NotificationManager.IMPORTANCE_DEFAULT
        } else {
            NotificationManager.IMPORTANCE_LOW
        }
        val channel = NotificationChannel(
            id,
            getString(R.string.launch_fg_channel),
            importance
        )
        nm.createNotificationChannel(channel)
    }

    private fun foregroundChannelId(): String =
        if (OemLaunchProfile.isOplusFamily()) "booxin_game_oplus" else CHANNEL_ID

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
                // Nudge MC to rebuild the framebuffer after resume rebind.
                val w = GameSurfaceBridge.width
                val h = GameSurfaceBridge.height
                if (w > 2 && h > 2) {
                    runCatching {
                        BooxinBridge.sendUpdateWindowSize(w, h)
                    }
                }
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
        com.booxin.launcher.core.multiplayer.GameProcessLanMotd.stop()
        com.booxin.launcher.core.skin.OfflineSkinLaunch.shutdown()
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
        const val EXTRA_OFFLINE_SKIN_PATH = "offline_skin_path"
        const val EXTRA_AUTH_SERVER_URL = "auth_server_url"
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
            serverAddress: String? = null,
            offlineSkinPath: String? = null,
            authServerUrl: String? = null
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
                offlineSkinPath?.takeIf { it.isNotBlank() }?.let { putExtra(EXTRA_OFFLINE_SKIN_PATH, it) }
                authServerUrl?.takeIf { it.isNotBlank() }?.let { putExtra(EXTRA_AUTH_SERVER_URL, it) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            // Clear before async STOP — otherwise killProcess can race and MainActivity
            // treats a normal exit as unexpected death.
            GameSessionLease.markUserExit(context)
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
