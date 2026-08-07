package com.booxin.launcher.ui.launch

import android.Manifest
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import org.lwjgl.glfw.CallbackBridge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.booxin.launcher.R
import com.booxin.launcher.core.download.game.VersionJsonMerger
import com.booxin.launcher.core.launch.GameLaunchLogBus
import com.booxin.launcher.core.launch.GameLaunchService
import com.booxin.launcher.core.launch.GameSurfaceBridge
import com.booxin.launcher.core.launch.LaunchPhase
import com.booxin.launcher.core.launch.LaunchSession
import com.booxin.launcher.core.runtime.GameRuntimeBackends
import com.booxin.launcher.databinding.ActivityLaunchBinding
import com.booxin.launcher.ui.launch.input.ControlLayoutController
import com.booxin.launcher.ui.launch.input.GameGyroscope
import com.booxin.launcher.ui.launch.input.GameInput
import com.booxin.launcher.ui.launch.input.GestureMode
import com.booxin.launcher.ui.launch.input.LaunchControlPrefs
import com.booxin.launcher.ui.launch.input.MouseMoveMode
import com.booxin.runtime.BooxinBridge
import java.util.Locale
import kotlin.math.abs

/** :game 进程：Surface + 嵌入 JVM。 */
class LaunchActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLaunchBinding
    private lateinit var controlLayout: ControlLayoutController
    private lateinit var gyroscope: GameGyroscope
    private val multiplayerPanel by lazy {
        InGameMultiplayerPanel(this) { pendingUsername }
    }
    private val logBuffer = StringBuilder()
    private var logReceiver: BroadcastReceiver? = null
    private var pendingVersionId: String = ""
    private var pendingUsername: String = "Player"
    private var pendingUuid: String? = null
    private var pendingAccessToken: String? = null
    private var pendingUserType: String? = null
    private var pendingServerAddress: String? = null
    private var canRetryLaunch = false
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var controlsVisible = true
    private var inputReady = false
    private var overlayHidden = false
    private var loadingPercent = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private var overlayHideTimeout: Runnable? = null
    private var inputArmRetries = 0
    private val inputArmRunnable = object : Runnable {
        override fun run() {
            BooxinBridge.enableAndroidInput()
            if (inputReady) return
            // Don't wait forever for callbacks; queue touch once UI is up.
            if (inputArmRetries >= 3) {
                enableGameInputForced()
                return
            }
            val dump = getInputBridgeDump()
            if (isInputBridgeReady(dump) || isInputBridgeMinimallyReady(dump)) {
                enableGameInputForced()
                return
            }
            inputArmRetries++
            mainHandler.postDelayed(this, 2_000L)
        }
    }

    /**
     * Vanilla: hide once the client is clearly up.
     * Forge/Fabric: LWJGL/OpenGL/"Setting user" appear long before mods finish —
     * hiding then leaves a black Surface and looks "broken".
     */
    private val overlayReadyPatternsVanilla = listOf(
        "Setting user",
        "Reloading ResourceManager",
        "Reload of ResourceManager",
        "Narrator library successfully loaded",
        "Sound engine started",
        "Backend library GL",
        "Loading Minecraft",
        "OpenGL debug",
    )

    private val overlayReadyPatternsModLoader = listOf(
        "Narrator library successfully loaded",
        "Sound engine started",
        // Realms / late client confirms title screen is up (Narrator often missing on Android).
        "OpenAL initialized",
        "SoundEngine started",
        "mods loaded",
    )

    /** Logs that mean the GL surface is painting — arm a short soft-hide, don't peel instantly. */
    private val overlaySoftHideTriggers = listOf(
        "Setting user",
        "LWJGL Version",
        "Backend library GL",
        "OpenGL",
        "Loading Minecraft",
        "Reloading ResourceManager",
        "Reload of ResourceManager",
    )

    private var gameProgressSeen = false
    private var modLoadingSeen = false
    private var softHideScheduled = false
    private var overlaySoftHide: Runnable? = null

    /** TextureView producer frames — proves GL actually swapped into the surface. */
    private var textureFrameCount = 0L
    private var forceEnterWatchingFrames = false
    private var forceEnterBaselineFrames = 0L
    private var forceRebindAttempts = 0
    private var forceFrameNotified = false
    private val forceRenderWatchdog = object : Runnable {
        override fun run() {
            if (!forceEnterWatchingFrames || isFinishing) return
            if (textureFrameCount > forceEnterBaselineFrames) {
                onForceEnterFramesDetected()
                return
            }
            forceRebindAttempts++
            if (forceRebindAttempts <= 3) {
                Toast.makeText(this@LaunchActivity, R.string.launch_force_rebind, Toast.LENGTH_SHORT)
                    .show()
                Log.i(TAG, "forceRenderWatchdog rebind attempt=$forceRebindAttempts frames=$textureFrameCount")
                performForceRenderRebind("watchdog-$forceRebindAttempts")
                mainHandler.postDelayed(this, 2_000L)
            } else {
                forceEnterWatchingFrames = false
                Toast.makeText(this@LaunchActivity, R.string.launch_force_no_frames, Toast.LENGTH_LONG)
                    .show()
                Log.w(TAG, "forceRenderWatchdog: still no frames after $forceRebindAttempts rebinds")
            }
        }
    }

    private val isModLoaderLaunch: Boolean by lazy {
        val id = pendingVersionId.lowercase(Locale.US)
        "forge" in id || "neoforge" in id || "fabric" in id || "quilt" in id ||
            VersionJsonMerger.isModLoaderVersion(pendingVersionId)
    }

    private val loadingStages = listOf(
        "等待 Surface" to 5,
        "启动游戏前台服务" to 8,
        "准备 Java" to 12,
        "Java 就绪" to 18,
        "构建启动命令" to 22,
        "配置运行时" to 28,
        "初始化 exec bridge" to 35,
        "输入桥已就绪" to 40,
        "GLFW 窗口已绑定" to 48,
        "探测 Java" to 52,
        "启动 Minecraft JVM" to 58,
        "正在创建 JVM" to 60,
        "libjvm.so loaded" to 62,
        "JNI_CreateJavaVM starting" to 64,
        "JVM 仍在启动" to 65,
        "JVM created" to 68,
        "loading main class" to 70,
        "main class loaded" to 72,
        "Invoking main" to 74,
        "LWJGL" to 76,
        "OpenGL" to 78,
        "Found mod file" to 68,
        "Loading mods" to 70,
        "Building Mod List" to 72,
        "Mod list built" to 74,
        "Loading mod " to 76,
        "Forge Version" to 78,
        "Mod loading" to 80,
        "Setting user" to 82,
        "Loading Minecraft" to 84,
        "Reloading ResourceManager" to 90,
        "Sound engine" to 94,
        "Narrator" to 96,
        "Backend library" to 97,
    )

    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        maybeStartGameService()
    }

    /** Held Surface for the current TextureView SurfaceTexture. */
    private var textureSurface: Surface? = null

    /**
     * TextureView: when available while the game is running, rebind the bridge window.
     * Do not kill the JVM when the texture is destroyed.
     */
    private val textureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            bindTextureSurface(surface, width, height, "available")
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
            surfaceWidth = width
            surfaceHeight = height
            if (width > 0 && height > 0) {
                surface.setDefaultBufferSize(width, height)
            }
            GameSurfaceBridge.onSurfaceSizeChanged(width, height)
            appendLog("Texture 尺寸: ${width}x${height}")
            // Push window size; if still paused, rebind with new dims.
            if (LaunchSession.hotspotEntered && GameSurfaceBridge.surfacePaused) {
                bindTextureSurface(surface, width, height, "sizeChanged-rebind")
            } else {
                maybeStartGameService()
            }
        }

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            // Keep JVM alive across texture destroy (background / screen off).
            appendLog("Texture Surface 已销毁（保持游戏进程）")
            GameSurfaceBridge.onSurfaceDestroyed()
            runCatching { textureSurface?.release() }
            textureSurface = null
            return true
        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
            textureFrameCount++
            if (forceEnterWatchingFrames &&
                !forceFrameNotified &&
                textureFrameCount > forceEnterBaselineFrames
            ) {
                onForceEnterFramesDetected()
            }
            // Game is painting under the glass — peel without waiting for Minecraft log tags
            // (default logcat is Booxin-only and never sees "Sound engine started").
            if (!overlayHidden) {
                onGameSurfaceFrame()
            }
        }
    }

    /** First frames after JVM is up → schedule a short auto-hide (title screen is visible). */
    private var frameHideArmed = false
    private var frameHideRunnable: Runnable? = null
    private var firstPaintFrameAtMs = 0L

    private fun onGameSurfaceFrame() {
        if (overlayHidden || isFinishing) return
        // Ignore early clear/black frames before the process has really started.
        if (loadingPercent < 55 && !LaunchSession.hotspotEntered) return
        if (firstPaintFrameAtMs == 0L) {
            firstPaintFrameAtMs = SystemClock.uptimeMillis()
            appendLog("检测到游戏画面开始绘制（Texture 出帧）")
            updateLoadingUi(
                loadingPercent.coerceAtLeast(90),
                "检测到游戏画面，即将关闭遮罩…"
            )
        }
        // Enough continuous paints → treat as title/menu ready.
        if (textureFrameCount < 6L) return
        if (frameHideArmed) return
        frameHideArmed = true
        frameHideRunnable?.let { mainHandler.removeCallbacks(it) }
        val delayMs = when {
            loadingPercent >= 90 || softHideScheduled || gameProgressSeen -> 600L
            isModLoaderLaunch -> 1_800L
            else -> 900L
        }
        frameHideRunnable = Runnable {
            if (overlayHidden || isFinishing) return@Runnable
            gameProgressSeen = true
            appendLog("画面已持续绘制，自动关闭加载遮罩")
            updateLoadingUi(100, "进入游戏")
            hideOverlayIfNeeded(force = true, allowWithoutBridge = true)
        }
        mainHandler.postDelayed(frameHideRunnable!!, delayMs)
    }

    private fun bindTextureSurface(
        surfaceTexture: SurfaceTexture,
        width: Int,
        height: Int,
        reason: String
    ) {
        if (width > 0 && height > 0) {
            surfaceTexture.setDefaultBufferSize(width, height)
        }
        surfaceWidth = width
        surfaceHeight = height
        runCatching { textureSurface?.release() }
        val surf = Surface(surfaceTexture)
        textureSurface = surf
        GameSurfaceBridge.onSurfaceCreated(surf)
        if (width > 0 && height > 0) {
            GameSurfaceBridge.onSurfaceSizeChanged(width, height)
            // Nudge framebuffer size so MC rebuilds after resume / rebind.
            if (LaunchSession.hotspotEntered || reason == "onResume" || reason.contains("rebind")) {
                runCatching {
                    BooxinBridge.sendUpdateWindowSize(width, height)
                    if (width > 2 && height > 2) {
                        mainHandler.postDelayed({
                            BooxinBridge.sendUpdateWindowSize(width - 1, height)
                            BooxinBridge.sendUpdateWindowSize(width, height)
                        }, 50L)
                    }
                }
            }
        }
        appendLog("Texture Surface 就绪（$reason） ${width}x${height}")
        maybeStartGameService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // 锁屏/ADB 启动也要保住 Surface。
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        runCatching {
            getSystemService(KeyguardManager::class.java)
                ?.requestDismissKeyguard(this, null)
        }
        binding = ActivityLaunchBinding.inflate(layoutInflater)
        setContentView(binding.root)
        foreground = this
        hideSystemBars()

        pendingVersionId = intent.getStringExtra(EXTRA_VERSION_ID).orEmpty()
        pendingUsername = intent.getStringExtra(EXTRA_USERNAME).orEmpty().ifBlank { "Player" }
        pendingUuid = intent.getStringExtra(EXTRA_UUID)
        pendingAccessToken = intent.getStringExtra(EXTRA_ACCESS_TOKEN)
        pendingUserType = intent.getStringExtra(EXTRA_USER_TYPE)
        pendingServerAddress = intent.getStringExtra(EXTRA_SERVER_ADDRESS)
            ?.takeIf { it.isNotBlank() }
        if (pendingVersionId.isBlank()) {
            appendLog("缺少版本 ID")
            updateLoadingUi(0, "缺少版本 ID")
            return
        }

        binding.textLaunchMeta.text = getString(R.string.launch_meta, pendingVersionId, pendingUsername)
        if (!pendingServerAddress.isNullOrBlank()) {
            appendLog("联机直连: $pendingServerAddress")
        }
        updateLoadingUi(0, getString(R.string.launch_loading_status_init))
        binding.buttonClose.setOnClickListener { returnToLauncher() }
        binding.buttonStop.setOnClickListener {
            appendLog("用户请求停止…")
            returnToLauncher()
        }
        binding.buttonForceEnter.setOnClickListener { forceEnterGameScreen() }

        // Glass layer: see-through, swallow touches so GLFW never sees them mid-load
        // (a tap used to dismiss the overlay early → black Surface).
        binding.panelOverlay.setOnTouchListener { v, event ->
            if (event.actionMasked == MotionEvent.ACTION_UP && canRetryLaunch) {
                v.performClick()
            }
            true
        }
        binding.panelOverlay.setOnClickListener {
            if (canRetryLaunch) {
                canRetryLaunch = false
                appendLog("重试启动…")
                updateLoadingUi(0, getString(R.string.launch_loading_status_init))
                maybeStartGameService(forceRetry = true)
                return@setOnClickListener
            }
            // Prefer real readiness. Soft-hide alone must not force peel without bridge
            // (vivo often blacks if glass goes away before GLFW paints).
            when {
                gameProgressSeen ->
                    hideOverlayIfNeeded(force = true, allowWithoutBridge = true)
                softHideScheduled && loadingPercent >= 90 -> {
                    hideOverlayIfNeeded(force = true, allowWithoutBridge = false)
                    if (!overlayHidden) {
                        appendLog("已看到画面信号，但触控桥未就绪，暂留遮罩防黑屏（可再点一次）")
                        // Second tap / later: allow peel.
                        gameProgressSeen = true
                    }
                }
                else -> appendLog(
                    if (isModLoaderLaunch) "模组仍在加载，请稍候（可透过玻璃看画面）"
                    else "仍在加载（${loadingPercent}%）"
                )
            }
        }

        setupControls()
        GameInput.bindSoftKeyboard(binding.touchCharInput)
        // TouchPad 尺寸初始化。
        GameInput.initScreenSize(
            resources.displayMetrics.widthPixels,
            resources.displayMetrics.heightPixels
        )
        binding.surfaceGame.isOpaque = true
        binding.surfaceGame.surfaceTextureListener = textureListener
        // Already available (e.g. after config change) — bind immediately.
        if (binding.surfaceGame.isAvailable) {
            val st = binding.surfaceGame.surfaceTexture
            if (st != null) {
                bindTextureSurface(
                    st,
                    binding.surfaceGame.width.coerceAtLeast(1),
                    binding.surfaceGame.height.coerceAtLeast(1),
                    "already-available"
                )
            }
        }

        logReceiver = GameLaunchLogBus.register(
            context = this,
            onLine = { line ->
                runOnUiThread {
                    appendLog(line)
                    onLaunchLogLine(line)
                }
            },
            onFinished = {
                runOnUiThread { returnToLauncher() }
            },
            onFailed = { reason ->
                runOnUiThread {
                    canRetryLaunch = true
                    if (::binding.isInitialized) {
                        binding.panelOverlay.visibility = View.VISIBLE
                        binding.panelOverlay.alpha = 1f
                        binding.panelOverlay.isClickable = true
                        binding.panelOverlay.isFocusable = true
                    }
                    overlayHidden = false
                    updateLoadingUi(
                        loadingPercent.coerceAtMost(40),
                        "失败: ${reason.take(80)}（点击重试）"
                    )
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

    override fun onResume() {
        super.onResume()
        maybeStartGameService()
        // Mark window focused/visible again.
        runCatching {
            CallbackBridge.nativeSetWindowAttrib(GLFW_FOCUSED, 1)
            CallbackBridge.nativeSetWindowAttrib(GLFW_HOVERED, 1)
            CallbackBridge.nativeSetWindowAttrib(GLFW_VISIBLE, 1)
        }
        // On resume: re-push size; if texture already up while paused, rebind like
        // onSurfaceTextureAvailable while running.
        binding.surfaceGame.post {
            if (isFinishing) return@post
            val st = binding.surfaceGame.surfaceTexture
            if (st != null && binding.surfaceGame.isAvailable &&
                (LaunchSession.hotspotEntered || GameSurfaceBridge.surfacePaused)
            ) {
                bindTextureSurface(
                    st,
                    binding.surfaceGame.width.coerceAtLeast(1),
                    binding.surfaceGame.height.coerceAtLeast(1),
                    "onResume"
                )
                runCatching { GameRuntimeBackends.current().enableInput() }
                scheduleInputArmRetries()
            }
        }
        if (::gyroscope.isInitialized && LaunchControlPrefs.isGyroEnabled(this)) {
            gyroscope.enable()
        }
    }

    override fun onPause() {
        if (::gyroscope.isInitialized) gyroscope.disable()
        // Only flip GLFW focus attrs — do not kill JVM.
        runCatching {
            CallbackBridge.nativeSetWindowAttrib(GLFW_FOCUSED, 0)
            CallbackBridge.nativeSetWindowAttrib(GLFW_HOVERED, 0)
        }
        if (LaunchSession.hotspotEntered || LaunchSession.current() == LaunchPhase.Running) {
            appendLog("进入后台：保持游戏进程（TextureView）")
        }
        super.onPause()
    }

    override fun onStart() {
        super.onStart()
        runCatching { CallbackBridge.nativeSetWindowAttrib(GLFW_VISIBLE, 1) }
    }

    override fun onStop() {
        runCatching { CallbackBridge.nativeSetWindowAttrib(GLFW_VISIBLE, 0) }
        super.onStop()
    }

    private fun setupControls() {
        // Route blank-area fingers to the look pad while buttons/joystick keep their own pointers.
        binding.panelCustomButtons.fallbackTarget = binding.touchPad

        controlLayout = ControlLayoutController(
            context = this,
            host = binding.panelCustomButtons,
            joystick = binding.joystickMove,
            floatingBall = binding.btnFloatingBall,
            gestureQuick = binding.btnGestureQuick,
            editBar = binding.panelEditBar,
            onEditModeChanged = { editing ->
                binding.touchPad.isEnabled = !editing
                refreshMoveVisibility()
            }
        )

        // Screen touch = mouse: GUI click-to-point; in-world BUILD gestures.
        binding.touchPad.mouseMoveMode = MouseMoveMode.CLICK
        binding.touchPad.gestureMode = GestureMode.BUILD
        binding.touchPad.lookSensitivity = 1.2f
        binding.touchPad.guiSensitivity = 1.0f
        // left/top gravity so translationX/Y map from (0,0)
        binding.cursorView.layoutParams = (binding.cursorView.layoutParams as FrameLayout.LayoutParams).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = 0
            topMargin = 0
            width = (24 * resources.displayMetrics.density).toInt().coerceAtLeast(24)
            height = (24 * resources.displayMetrics.density).toInt().coerceAtLeast(24)
        }
        binding.cursorView.visibility = View.VISIBLE
        binding.touchPad.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            GameInput.bindCursor(binding.cursorView, v.width, v.height)
            if (!BooxinBridge.isGrabbing() && GameInput.pointerX == 0 && GameInput.pointerY == 0) {
                binding.touchPad.syncCursorToCenter()
            }
        }
        // Physical mouse HOVER / BUTTON arrive via generic motion on TouchPad.
        binding.touchPad.setOnGenericMotionListener { _, event ->
            inputReady && GameInput.handleGenericMotion(event)
        }

        binding.btnEditAdd.setOnClickListener { controlLayout.addButton() }
        binding.btnEditDelete.setOnClickListener { controlLayout.deleteSelected() }
        binding.btnEditShrink.setOnClickListener { controlLayout.resizeSelected(-8) }
        binding.btnEditGrow.setOnClickListener { controlLayout.resizeSelected(8) }
        binding.btnEditReset.setOnClickListener { controlLayout.resetToDefault() }
        binding.btnEditDone.setOnClickListener { controlLayout.exitEditMode(save = true) }

        refreshGestureQuickButton()
        setupGestureQuickButton()
        setupFloatingBall()

        gyroscope = GameGyroscope(this, LaunchControlPrefs.gyroSensitivity(this))
        if (LaunchControlPrefs.isGyroEnabled(this)) {
            gyroscope.enable()
        }

        // 仅 grab=true 时藏遮罩。
        BooxinBridge.setGrabListener { grabbing ->
            Log.i(TAG, "grabListener grabbing=$grabbing overlayHidden=$overlayHidden")
            if (!overlayHidden) {
                if (grabbing) {
                    // 已 grab，可进游戏。
                    hideOverlayIfNeeded(force = true, allowWithoutBridge = true)
                }
                return@setGrabListener
            }
            refreshMoveVisibility()
            // 释放卡住的鼠标键。
            binding.touchPad.resetTouchState()
            GameInput.releaseAllMouseButtons()
            GameInput.refreshCursorVisibility()
            if (grabbing) {
                binding.touchPad.syncCursorToCenter()
            } else {
                // Keep view-space pointer; re-push so game cursor matches overlay.
                GameInput.setPointer(GameInput.pointerX, GameInput.pointerY)
            }
            binding.joystickMove.releaseKeys()
            // Re-arm stack-queue after GLFW grab transitions.
            BooxinBridge.enableAndroidInput()
        }
    }

    private fun onLaunchLogLine(line: String) {
        if (overlayHidden) return
        val crash = line.contains("ExceptionInInitializerError", ignoreCase = true) ||
            line.contains("InaccessibleObjectException", ignoreCase = true) ||
            line.contains("FindException", ignoreCase = true) ||
            line.contains("ResolutionException", ignoreCase = true) ||
            (line.contains("main():", ignoreCase = true) &&
                (line.contains("Exception", ignoreCase = true) || line.contains("Error", ignoreCase = true)))
        if (crash) {
            updateLoadingUi(100, "启动失败（已崩溃，不是卡在加载）")
            binding.textLoadingStatus.text = shortenStatus(line)
            appendLog("检测到 JVM 崩溃，请查看上方日志")
            return
        }
        val readyPatterns =
            if (isModLoaderLaunch) overlayReadyPatternsModLoader else overlayReadyPatternsVanilla
        val ready = readyPatterns.any { pattern ->
            line.contains(pattern, ignoreCase = true)
        }
        if (ready) {
            gameProgressSeen = true
            updateLoadingUi(100, line.trim().take(80))
            hideOverlayIfNeeded(force = true, allowWithoutBridge = true)
            return
        }
        if (isModLoaderLaunch &&
            overlaySoftHideTriggers.any { line.contains(it, ignoreCase = true) }
        ) {
            if (line.contains("Reloading ResourceManager", ignoreCase = true) ||
                line.contains("Reload of ResourceManager", ignoreCase = true)
            ) {
                updateLoadingUi(90.coerceAtLeast(loadingPercent), "模组已就绪，正在加载资源…")
            }
            // Longer delay: early OpenGL on some OEMs (vivo) is before the menu paints.
            val titleLike = line.contains("Setting user", ignoreCase = true) ||
                line.contains("Loading Minecraft", ignoreCase = true)
            scheduleSoftHideAfterRender(
                when {
                    titleLike && modLoadingSeen -> 18_000L
                    titleLike -> 25_000L
                    modLoadingSeen -> 28_000L
                    else -> 40_000L
                }
            )
        }
    }

    private fun updateLoadingFromLog(line: String) {
        // Heartbeat must not reset the overlay fallback timer — that used to keep the
        // glass forever while Minecraft was already on the title screen.
        val isHeartbeat = line.contains("JVM 仍在启动") ||
            line.contains("正在创建 JVM") ||
            line.contains("游戏仍在加载/运行中")
        if (isHeartbeat) {
            if (loadingPercent >= 68) {
                if (line.contains("游戏仍在加载")) {
                    binding.textLoadingStatus.text = shortenStatus(line)
                }
                return
            }
            // Cap early heartbeat progress; do not bump keep-alive.
            updateLoadingUi(maxOf(loadingPercent, 65), shortenStatus(line))
            return
        }
        val modStatus = modLoaderStatus(line)
        if (modStatus != null) {
            modLoadingSeen = true
            binding.textLaunchTitle.text = getString(R.string.launch_loading_title_mods)
            // Never coerceIn(loadingPercent, 89) when percent already > 89 (soft-hide) —
            // that throws IllegalArgumentException and kills :game on Xiaomi etc.
            val pct = maxOf(loadingPercent, modStatus.second.coerceAtMost(89))
            updateLoadingUi(pct, modStatus.first)
            bumpOverlayKeepAlive()
            // Mods still arriving — push soft-hide out so we don't flash black.
            // But once TextureView is painting, prefer frame-based peel (don't delay forever).
            if (softHideScheduled && textureFrameCount < 3L) {
                scheduleSoftHideAfterRender(15_000L)
            }
            return
        }
        // 优先解析日志里的百分比（忽略心跳文案里偶发的数字）。
        val pctMatch = PERCENT_IN_LOG.find(line)
        if (pctMatch != null) {
            val pct = pctMatch.groupValues[1].toIntOrNull()?.coerceIn(0, 99)
            if (pct != null && pct >= loadingPercent) {
                updateLoadingUi(pct, shortenStatus(line))
                bumpOverlayKeepAlive()
                return
            }
        }
        var best = loadingPercent
        var status: String? = null
        for ((needle, pct) in loadingStages) {
            if (line.contains(needle, ignoreCase = true) && pct >= best) {
                best = pct
                status = shortenStatus(line)
            }
        }
        if (best > loadingPercent || status != null && best == loadingPercent) {
            updateLoadingUi(best, status ?: shortenStatus(line))
            bumpOverlayKeepAlive()
            if (best >= 84) {
                // Loading Minecraft / resources — safer than bare OpenGL@76.
                scheduleSoftHideAfterRender(if (isModLoaderLaunch) 22_000L else 10_000L)
            } else if (best >= 76 && !isModLoaderLaunch) {
                scheduleSoftHideAfterRender(10_000L)
            }
        } else if (line.isNotBlank() && loadingPercent in 1..98) {
            // Keep status fresh without jumping percent backward.
            binding.textLoadingStatus.text = shortenStatus(line)
            if (isModLoaderLaunch) bumpOverlayKeepAlive()
        }
    }

    /** Friendly Chinese status + progress ceiling while Forge/Fabric load mods. */
    private fun modLoaderStatus(line: String): Pair<String, Int>? {
        if (!isModLoaderLaunch) return null
        val lower = line.lowercase(Locale.US)
        return when {
            "found mod file" in lower -> {
                val name = line.substringAfterLast('/').substringAfterLast('\\')
                    .substringBefore(".jar").ifBlank { null }
                ("发现模组" + (name?.let { "：$it" } ?: "…")) to 68
            }
            "building mod list" in lower -> "正在构建模组列表…" to 72
            "mod list built" in lower -> "模组列表已就绪…" to 74
            "loading mods" in lower -> "正在加载模组…" to 70
            "loading mod " in lower -> {
                val mod = Regex("""Loading mod\s+([^\s,]+)""", RegexOption.IGNORE_CASE)
                    .find(line)?.groupValues?.getOrNull(1)
                ("正在加载模组" + (mod?.let { "：$it" } ?: "…")) to 78
            }
            "forge version information" in lower || "neoforge" in lower && "version" in lower ->
                "Forge 初始化中…" to 80
            "mod loading completed" in lower || "modloading complete" in lower ->
                "模组加载完成，准备进入游戏…" to 88
            "applying holder lookups" in lower || "injecting existing registry" in lower ->
                "正在注入模组注册表…" to 84
            "mixin" in lower && ("apply" in lower || "load" in lower) ->
                "正在应用 Mixin…" to 76
            else -> null
        }
    }

    private fun updateLoadingUi(percent: Int, status: String) {
        loadingPercent = percent.coerceIn(0, 100)
        binding.progressLaunch.progress = loadingPercent
        binding.textLoadingPercent.text = getString(R.string.launch_loading_percent_fmt, loadingPercent)
        if (status.isNotBlank()) {
            binding.textLoadingStatus.text = status
        }
    }

    private fun shortenStatus(line: String): String {
        val trimmed = line.trim().removePrefix("[Booxin]").trim()
        return if (trimmed.length <= 96) trimmed else trimmed.take(93) + "…"
    }

    /**
     * vivo 等：遮罩揭了仍可能全黑 —— 游戏可能从未向 TextureView 出帧。
     * 强制进入 = 揭遮罩 + resume 级 setupBridgeWindow 重绑 + 首帧 watchdog。
     */
    private fun forceEnterGameScreen() {
        appendLog("用户强制进入游戏画面（揭遮罩并重绑渲染窗口）")
        Log.i(TAG, "forceEnterGameScreen percent=$loadingPercent frames=$textureFrameCount")
        gameProgressSeen = true
        updateLoadingUi(100, "强制进入游戏")
        hideOverlayIfNeeded(force = true, allowWithoutBridge = true)

        forceEnterBaselineFrames = textureFrameCount
        forceRebindAttempts = 0
        forceFrameNotified = false
        forceEnterWatchingFrames = true
        mainHandler.removeCallbacks(forceRenderWatchdog)

        performForceRenderRebind("forceEnter")
        // 强制显示触控层，不等待桥 dump 完全就绪。
        mainHandler.postDelayed({ enableGameInputForced() }, 350L)
        // 2s 内无新帧 → 再重绑；最多 3 次。
        mainHandler.postDelayed(forceRenderWatchdog, 2_000L)
    }

    /** Same path as onResume: rebind Surface → setupBridgeWindow → EGL stale → size nudge. */
    private fun performForceRenderRebind(reason: String) {
        val st = binding.surfaceGame.surfaceTexture
        val w = binding.surfaceGame.width.coerceAtLeast(surfaceWidth).coerceAtLeast(1)
        val h = binding.surfaceGame.height.coerceAtLeast(surfaceHeight).coerceAtLeast(1)
        if (st != null && binding.surfaceGame.isAvailable) {
            bindTextureSurface(st, w, h, "force-rebind-$reason")
        }
        val rebound = runCatching { GameSurfaceBridge.rebindIfPossible() }.getOrDefault(false)
        runCatching { GameRuntimeBackends.current().enableInput() }
        runCatching {
            CallbackBridge.nativeSetWindowAttrib(GLFW_FOCUSED, 1)
            CallbackBridge.nativeSetWindowAttrib(GLFW_HOVERED, 1)
            CallbackBridge.nativeSetWindowAttrib(GLFW_VISIBLE, 1)
        }
        if (w > 2 && h > 2) {
            runCatching {
                BooxinBridge.sendUpdateWindowSize(w, h)
                mainHandler.postDelayed({
                    BooxinBridge.sendUpdateWindowSize(w - 1, h)
                    BooxinBridge.sendUpdateWindowSize(w, h)
                }, 80L)
            }
        }
        Log.i(TAG, "performForceRenderRebind reason=$reason rebound=$rebound ${w}x${h} frames=$textureFrameCount")
        if (!overlayHidden) {
            appendLog("强制重绑渲染窗口（$reason） rebound=$rebound")
        }
    }

    private fun onForceEnterFramesDetected() {
        if (forceFrameNotified) return
        forceFrameNotified = true
        forceEnterWatchingFrames = false
        mainHandler.removeCallbacks(forceRenderWatchdog)
        Toast.makeText(this, R.string.launch_force_frames_ok, Toast.LENGTH_SHORT).show()
        Log.i(TAG, "forceEnter: frames detected count=$textureFrameCount")
    }

    private fun hideOverlayIfNeeded(force: Boolean = false, allowWithoutBridge: Boolean = false) {
        if (overlayHidden) return
        if (!force) return
        val dump = getInputBridgeDump()
        val bridgeOk = isInputBridgeReady(dump) ||
            isInputBridgeMinimallyReady(dump) ||
            BooxinBridge.isGrabbing() ||
            BooxinBridge.areNativesLinked()
        // Forge often keeps stackQ=0; still show the game once progress is clear.
        if (!bridgeOk && !allowWithoutBridge) {
            appendLog("输入桥尚未完全就绪，继续等待游戏窗口/回调…")
            Log.i(TAG, "skip hideOverlay; bridge not ready: $dump")
            scheduleInputArmRetries()
            return
        }
        if (!bridgeOk) {
            appendLog("输入桥未完全确认，仍进入游戏画面并继续重试触控…")
            Log.i(TAG, "hideOverlay with soft bridge: $dump")
            scheduleInputArmRetries()
        }
        overlayHidden = true
        overlayHideTimeout?.let { mainHandler.removeCallbacks(it) }
        overlayHideTimeout = null
        overlaySoftHide?.let { mainHandler.removeCallbacks(it) }
        overlaySoftHide = null
        softHideScheduled = false
        frameHideRunnable?.let { mainHandler.removeCallbacks(it) }
        frameHideRunnable = null
        frameHideArmed = false
        // Stop mirroring Minecraft logs onto the UI thread (Binder + TextView).
        GameLaunchLogBus.muteUiLogs(this)
        Log.i(TAG, "hideOverlay percent=$loadingPercent frames=$textureFrameCount")
        updateLoadingUi(100, "进入游戏")
        hideOverlay()
    }

    private fun scheduleOverlayFallbackHide() {
        overlayHideTimeout?.let { mainHandler.removeCallbacks(it) }
        // Soft upper bound: frame-based hide usually peels earlier. Heartbeats no longer
        // reset this timer. Modpacks still get more time than vanilla.
        val delayMs = if (isModLoaderLaunch) 120_000L else 45_000L
        overlayHideTimeout = Runnable {
            appendLog(
                if (isModLoaderLaunch) "模组加载较久，先进入游戏画面（仍可能在后台加载）"
                else "加载超时，自动进入游戏画面"
            )
            updateLoadingUi(99, "进入游戏")
            hideOverlayIfNeeded(force = true, allowWithoutBridge = true)
        }
        mainHandler.postDelayed(overlayHideTimeout!!, delayMs)
    }

    /** While mods keep logging, keep the overlay alive so users don't stare at black. */
    private fun bumpOverlayKeepAlive() {
        if (overlayHidden || !isModLoaderLaunch) return
        if (softHideScheduled) {
            // Soft-hide armed but mods still logging — defer, don't fall back to 10min forever.
            return
        }
        scheduleOverlayFallbackHide()
    }

    /**
     * Title/GL signals often arrive before the Surface really paints on some OEMs.
     * First try a bridge-gated peel; only force after an extra grace period.
     */
    private fun scheduleSoftHideAfterRender(delayMs: Long) {
        if (overlayHidden) return
        softHideScheduled = true
        overlaySoftHide?.let { mainHandler.removeCallbacks(it) }
        overlaySoftHide = Runnable {
            if (overlayHidden) return@Runnable
            appendLog("检测到游戏画面信号，尝试关闭遮罩…")
            updateLoadingUi(100, "进入游戏")
            hideOverlayIfNeeded(force = true, allowWithoutBridge = false)
            if (overlayHidden) {
                gameProgressSeen = true
                return@Runnable
            }
            appendLog("触控桥未就绪，再等一会儿再关遮罩（避免黑屏）")
            val graceMs = if (textureFrameCount >= 3L) 2_500L else 12_000L
            overlaySoftHide = Runnable {
                if (overlayHidden) return@Runnable
                gameProgressSeen = true
                appendLog("延迟关闭加载遮罩")
                hideOverlayIfNeeded(force = true, allowWithoutBridge = true)
            }
            mainHandler.postDelayed(overlaySoftHide!!, graceMs)
        }
        mainHandler.postDelayed(overlaySoftHide!!, delayMs)
        updateLoadingUi(
            loadingPercent.coerceAtLeast(90),
            "游戏画面已出现，即将关闭遮罩…"
        )
    }

    private fun refreshMoveVisibility() {
        if (!inputReady || binding.panelControls.visibility != View.VISIBLE) {
            // GONE so it does not steal blank-area touches while hidden.
            binding.joystickMove.visibility = View.GONE
            return
        }
        binding.joystickMove.visibility = View.VISIBLE
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupFloatingBall() {
        bindDraggableHudButton(
            view = binding.btnFloatingBall,
            onTap = { showFloatingMenu() },
            onDragEnd = { controlLayout.updateFloatingBallFromView() }
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestureQuickButton() {
        bindDraggableHudButton(
            view = binding.btnGestureQuick,
            onTap = { toggleGestureMode() },
            onDragEnd = { controlLayout.updateGestureQuickFromView() }
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun bindDraggableHudButton(
        view: View,
        onTap: () -> Unit,
        onDragEnd: () -> Unit
    ) {
        var downX = 0f
        var downY = 0f
        var startL = 0
        var startT = 0
        var moved = false

        view.setOnTouchListener { v, event ->
            val parent = v.parent as? FrameLayout ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    val lp = v.layoutParams as FrameLayout.LayoutParams
                    if (lp.gravity != (Gravity.TOP or Gravity.START)) {
                        lp.gravity = Gravity.TOP or Gravity.START
                        lp.leftMargin = v.left
                        lp.topMargin = v.top
                        v.layoutParams = lp
                    }
                    startL = lp.leftMargin
                    startT = lp.topMargin
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (abs(dx) > 8 || abs(dy) > 8) moved = true
                    if (moved) {
                        val lp = v.layoutParams as FrameLayout.LayoutParams
                        lp.leftMargin = (startL + dx).coerceIn(0, parent.width - v.width)
                        lp.topMargin = (startT + dy).coerceIn(0, parent.height - v.height)
                        v.layoutParams = lp
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) onDragEnd() else onTap()
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    private fun refreshGestureQuickButton() {
        binding.btnGestureQuick.text = when (binding.touchPad.gestureMode) {
            GestureMode.BUILD -> getString(R.string.control_gesture_quick_build)
            GestureMode.FIGHT -> getString(R.string.control_gesture_quick_fight)
        }
    }

    private fun setGestureMode(mode: GestureMode, toast: Boolean = true) {
        binding.touchPad.gestureMode = mode
        refreshGestureQuickButton()
        if (!toast) return
        val msg = when (mode) {
            GestureMode.BUILD -> R.string.control_toast_gesture_build
            GestureMode.FIGHT -> R.string.control_toast_gesture_fight
        }
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun toggleGestureMode() {
        val next = when (binding.touchPad.gestureMode) {
            GestureMode.BUILD -> GestureMode.FIGHT
            GestureMode.FIGHT -> GestureMode.BUILD
        }
        setGestureMode(next)
    }

    private fun showGyroSettingsDialog() {
        if (!::gyroscope.isInitialized) return
        if (!gyroscope.available) {
            Toast.makeText(this, R.string.control_toast_gyro_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        val view = layoutInflater.inflate(R.layout.dialog_gyro_settings, null)
        val switchGyro = view.findViewById<android.widget.Switch>(R.id.switchGyro)
        val seek = view.findViewById<android.widget.SeekBar>(R.id.seekGyroSensitivity)
        val label = view.findViewById<android.widget.TextView>(R.id.textGyroSensitivity)

        fun refreshSensLabel(progress: Int) {
            label.text = getString(R.string.control_gyro_sensitivity_fmt, progress)
        }

        switchGyro.isChecked = LaunchControlPrefs.isGyroEnabled(this)
        seek.max = LaunchControlPrefs.GYRO_SENS_MAX
        if (Build.VERSION.SDK_INT >= 26) {
            seek.min = LaunchControlPrefs.GYRO_SENS_MIN
        }
        val initial = LaunchControlPrefs.gyroSensitivityProgress(this)
        seek.progress = initial
        refreshSensLabel(initial)

        switchGyro.setOnCheckedChangeListener { _, checked ->
            LaunchControlPrefs.setGyroEnabled(this, checked)
            gyroscope.setEnabled(checked)
        }
        seek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                val p = progress.coerceAtLeast(LaunchControlPrefs.GYRO_SENS_MIN)
                refreshSensLabel(p)
                if (fromUser) {
                    LaunchControlPrefs.setGyroSensitivityProgress(this@LaunchActivity, p)
                    gyroscope.sensitivity = LaunchControlPrefs.progressToSensitivity(p)
                }
            }

            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) = Unit
        })

        AlertDialog.Builder(this)
            .setTitle(R.string.control_gyro_title)
            .setView(view)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showFloatingMenu() {
        if (controlLayout.editMode) {
            controlLayout.exitEditMode(save = true)
            return
        }
        val hideLabel = if (controlsVisible) {
            getString(R.string.control_menu_hide)
        } else {
            getString(R.string.control_menu_show)
        }
        val items = arrayOf(
            getString(R.string.control_menu_gyro),
            getString(R.string.control_menu_multiplayer),
            getString(R.string.control_menu_edit),
            hideLabel,
            getString(R.string.control_menu_exit)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.control_menu_title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showGyroSettingsDialog()
                    1 -> multiplayerPanel.show()
                    2 -> {
                        setControlsVisible(true)
                        controlLayout.enterEditMode()
                    }
                    3 -> setControlsVisible(!controlsVisible)
                    4 -> returnToLauncher()
                }
            }
            .show()
    }

    private fun returnToLauncher() {
        if (isFinishing || isDestroyed) return
        canRetryLaunch = false
        runCatching { multiplayerPanel.dispose() }
        runCatching { GameLaunchService.stop(this) }
        val intent = Intent(this, com.booxin.launcher.ui.MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_NEW_TASK
            )
        }
        startActivity(intent)
        finish()
        mainHandler.postDelayed({
            android.os.Process.killProcess(android.os.Process.myPid())
        }, 350L)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        returnToLauncher()
    }

    private fun setControlsVisible(visible: Boolean) {
        controlsVisible = visible
        if (!inputReady) return
        if (controlLayout.editMode && !visible) {
            controlLayout.exitEditMode(save = true)
        }
        binding.panelControls.visibility = View.VISIBLE
        // Hide only action buttons; move stick + touch→mouse + gesture toggle stay.
        controlLayout.setButtonsVisible(visible)
        binding.touchPad.visibility = View.VISIBLE
        binding.btnGestureQuick.visibility = View.VISIBLE
        refreshMoveVisibility()
        if (!visible) {
            controlLayout.releaseAllHolds()
        }
    }

    private fun enableGameInput() {
        if (inputReady) {
            BooxinBridge.enableAndroidInput()
            return
        }
        val dump = getInputBridgeDump()
        val ready = isInputBridgeReady(dump) ||
            isInputBridgeMinimallyReady(dump) ||
            BooxinBridge.isGrabbing()
        if (!ready) {
            BooxinBridge.enableAndroidInput()
            appendLog("等待输入桥完全就绪（window/callback）…")
            Log.i(TAG, "delay enableGameInput; bridge not ready: $dump")
            scheduleInputArmRetries()
            return
        }
        enableGameInputForced()
    }

    private fun enableGameInputForced() {
        if (inputReady) {
            BooxinBridge.enableAndroidInput()
            return
        }
        inputReady = true
        val ok = BooxinBridge.enableAndroidInput()
        binding.panelControls.visibility = View.VISIBLE
        binding.touchPad.visibility = View.VISIBLE
        binding.touchPad.isEnabled = true
        GameInput.bindCursor(binding.cursorView, binding.touchPad.width, binding.touchPad.height)
        GameInput.refreshCursorVisibility()
        binding.cursorView.visibility = View.VISIBLE
        binding.cursorView.bringToFront()
        binding.touchPad.post {
            binding.touchPad.syncCursorToCenter()
            GameInput.refreshCursorVisibility()
            binding.cursorView.visibility =
                if (BooxinBridge.isGrabbing()) View.GONE else View.VISIBLE
        }
        setControlsVisible(true)
        refreshMoveVisibility()
        Log.i(TAG, "enableGameInput ok=$ok w=${BooxinBridge.getWindowWidth()} h=${BooxinBridge.getWindowHeight()}")
        appendLog(
            if (ok) "触控已启用：屏幕触摸 = 鼠标"
            else "触控 UI 已显示，等待输入桥…"
        )
        logInputBridgeStatus("enableGameInput")
        scheduleInputArmRetries()
    }

    private fun getInputBridgeDump(): String = runCatching {
        com.booxin.launcher.core.launch.NativeJvmLauncher.dumpInputBridge()
    }.getOrElse { "error:${it.message}" }

    private fun isInputBridgeReady(dump: String): Boolean {
        return dump.contains("ready=1") &&
            !dump.contains("showing=0") &&
            !dump.contains("mouseCb=0x0") && !dump.contains("mouseCb=0 ") &&
            !dump.contains("cursorCb=0x0") && !dump.contains("cursorCb=0 ")
    }

    private fun isInputBridgeMinimallyReady(dump: String): Boolean {
        // Forge often reports stackQ=0 even when touch works after enableAndroidInput().
        return dump.contains("ready=1") &&
            !dump.contains("mouseBuf=0x0") && !dump.contains("mouseBuf=0 ")
    }

    private fun logInputBridgeStatus(where: String) {
        val dump = getInputBridgeDump()
        appendLog("输入桥[$where]: $dump")
        Log.i(TAG, "inputBridge[$where] $dump")
        if (dump.contains("mouseCb=0x0") || dump.contains("mouseCb=0 ") ||
            dump.contains("mouseBuf=0x0") || dump.contains("mouseBuf=0 ") ||
            dump.contains("ready=0") || dump.contains("showing=0")
        ) {
            appendLog("警告：输入桥未就绪，点击会被忽略（mouseCb/mouseBuf/ready/showing）")
        }
    }

    private fun scheduleInputArmRetries() {
        mainHandler.removeCallbacks(inputArmRunnable)
        inputArmRetries = 0
        mainHandler.postDelayed(inputArmRunnable, 500L)
        mainHandler.postDelayed({ BooxinBridge.enableAndroidInput() }, 1_500L)
        mainHandler.postDelayed({ BooxinBridge.enableAndroidInput() }, 4_000L)
        mainHandler.postDelayed({ BooxinBridge.enableAndroidInput() }, 8_000L)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        // Physical mouse HOVER_MOVE / BUTTON_PRESS arrive here, not onTouch.
        if (inputReady && GameInput.handleGenericMotion(event)) return true
        return super.dispatchGenericMotionEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (inputReady && GameInput.handleKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }

    private fun hideSystemBars() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun maybeStartGameService(forceRetry: Boolean = false) {
        if (pendingVersionId.isBlank()) return
        if (lifecycle.currentState < androidx.lifecycle.Lifecycle.State.RESUMED) {
            appendLog("等待回到前台…")
            return
        }
        if (!GameSurfaceBridge.hasSurface()) {
            appendLog("等待 Surface…")
            return
        }
        if (surfaceWidth <= 0 || surfaceHeight <= 0) {
            appendLog("等待 Surface 尺寸…")
            return
        }
        when (LaunchSession.current()) {
            LaunchPhase.Starting, LaunchPhase.Binding, LaunchPhase.Running, LaunchPhase.Stopping -> {
                return
            }
            LaunchPhase.Failed -> {
                if (!forceRetry) return
            }
            LaunchPhase.Idle -> Unit
        }
        canRetryLaunch = false
        appendLog("启动游戏前台服务（:game 进程，${surfaceWidth}x${surfaceHeight}，${LaunchSession.current()}）…")
        scheduleOverlayFallbackHide()
        GameLaunchService.start(
            this,
            pendingVersionId,
            pendingUsername,
            surfaceWidth,
            surfaceHeight,
            uuid = pendingUuid,
            accessToken = pendingAccessToken,
            userType = pendingUserType,
            serverAddress = pendingServerAddress
        )
    }

    private fun hideOverlay() {
        if (binding.panelOverlay.visibility != View.VISIBLE) {
            enableGameInput()
            return
        }
        // Stop intercepting touches immediately; the fade-out is visual only.
        binding.panelOverlay.isClickable = false
        binding.panelOverlay.isFocusable = false
        binding.panelOverlay.animate()
            .alpha(0f)
            .setDuration(300)
            .withEndAction {
                binding.panelOverlay.visibility = View.GONE
                binding.panelOverlay.isClickable = false
                binding.panelOverlay.isFocusable = false
                Log.i(TAG, "panelOverlay hidden; touchPad should receive input")
                enableGameInput()
            }
            .start()
    }

    private fun appendLog(line: String) {
        if (line.isBlank()) return
        // 进游戏后别再改 TextView，会影响触控。
        if (overlayHidden) return
        if (logBuffer.length > 48_000) {
            logBuffer.delete(0, logBuffer.length - 24_000)
        }
        if (logBuffer.isNotEmpty()) logBuffer.append('\n')
        logBuffer.append(line)
        binding.textLog.text = logBuffer.toString()
        binding.scrollLog.post {
            binding.scrollLog.fullScroll(View.FOCUS_DOWN)
        }
        updateLoadingFromLog(line)
    }

    override fun onDestroy() {
        if (foreground === this) foreground = null
        overlayHideTimeout?.let { mainHandler.removeCallbacks(it) }
        mainHandler.removeCallbacks(inputArmRunnable)
        mainHandler.removeCallbacks(forceRenderWatchdog)
        frameHideRunnable?.let { mainHandler.removeCallbacks(it) }
        frameHideRunnable = null
        forceEnterWatchingFrames = false
        frameHideArmed = false
        BooxinBridge.setGrabListener(null)
        if (::gyroscope.isInitialized) gyroscope.disable()
        if (::controlLayout.isInitialized) {
            controlLayout.releaseAllHolds()
        }
        multiplayerPanel.dispose()
        binding.joystickMove.releaseKeys()
        binding.surfaceGame.surfaceTextureListener = null
        runCatching { textureSurface?.release() }
        textureSurface = null
        logReceiver?.let { unregisterReceiver(it) }
        logReceiver = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_VERSION_ID = "version_id"
        const val EXTRA_USERNAME = "username"
        const val EXTRA_UUID = "uuid"
        const val EXTRA_ACCESS_TOKEN = "access_token"
        const val EXTRA_USER_TYPE = "user_type"
        /** EasyTier local forward, e.g. 127.0.0.1:37859 */
        const val EXTRA_SERVER_ADDRESS = "server_address"
        private const val TAG = "LaunchActivity"
        // GLFW window attribs (standard GLFW numeric values).
        private const val GLFW_FOCUSED = 0x00020001
        private const val GLFW_VISIBLE = 0x00020004
        private const val GLFW_HOVERED = 0x0002000B

        @Volatile
        private var foreground: LaunchActivity? = null

        fun foregroundOrNull(): LaunchActivity? = foreground

        private val PERCENT_IN_LOG = Regex("""(?<![\d.])(\d{1,3})\s*%""")
    }
}
