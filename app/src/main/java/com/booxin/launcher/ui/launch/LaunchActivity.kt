package com.booxin.launcher.ui.launch

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.booxin.launcher.R
import com.booxin.launcher.core.launch.GameLaunchLogBus
import com.booxin.launcher.core.launch.GameLaunchService
import com.booxin.launcher.core.launch.GameSurfaceBridge
import com.booxin.launcher.databinding.ActivityLaunchBinding
import com.booxin.launcher.ui.launch.input.ControlLayoutController
import com.booxin.launcher.ui.launch.input.GameInput
import com.booxin.launcher.ui.launch.input.GestureMode
import com.booxin.launcher.ui.launch.input.MouseMoveMode
import com.booxin.runtime.BooxinBridge
import kotlin.math.abs

/**
 * Runs in `:game` with [SurfaceView] + JVM so the exec bridge can bind ANativeWindow.
 * Touch / virtual controls inject GLFW events via [BooxinBridge].
 */
class LaunchActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLaunchBinding
    private lateinit var controlLayout: ControlLayoutController
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
    private var serviceStarted = false
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

    // Hide loading only on these — early GLFW/GL lines flash a black screen.
    private val overlayReadyPatterns = listOf(
        "Setting user",
        "Reloading ResourceManager",
        "Reload of ResourceManager",
        "Narrator library successfully loaded",
        "Sound engine started",
        "Backend library GL",
        "Loading Minecraft",
        "OpenGL debug",
    )

    /** True once Minecraft log shows we're past early black Surface. */
    private var gameProgressSeen = false

    /** Coarse stage → percent mapping from our launcher + Minecraft logs. */
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
        "LWJGL" to 65,
        "OpenGL" to 70,
        "Setting user" to 78,
        "Loading Minecraft" to 82,
        "Reloading ResourceManager" to 88,
        "Sound engine" to 92,
        "Narrator" to 95,
        "Backend library" to 96,
    )

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

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            surfaceWidth = width
            surfaceHeight = height
            GameSurfaceBridge.onSurfaceSizeChanged(width, height)
            appendLog("Surface 尺寸: ${width}x${height}")
            maybeStartGameService()
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            GameSurfaceBridge.onSurfaceDestroyed()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityLaunchBinding.inflate(layoutInflater)
        setContentView(binding.root)
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

        // Manual dismiss once game has progressed far enough (Surface isn't pure black).
        binding.panelOverlay.setOnClickListener {
            if (loadingPercent >= 60 || gameProgressSeen || BooxinBridge.areNativesLinked()) {
                hideOverlayIfNeeded(force = true, allowWithoutBridge = true)
            } else {
                appendLog("仍在加载（${loadingPercent}%），请稍候再点进入")
            }
        }

        setupControls()
        GameInput.bindSoftKeyboard(binding.touchCharInput)
        // TouchPad size is fixed once constructed — never leave 0.
        GameInput.initScreenSize(
            resources.displayMetrics.widthPixels,
            resources.displayMetrics.heightPixels
        )
        binding.surfaceGame.holder.addCallback(surfaceCallback)

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

    private fun setupControls() {
        // Route blank-area fingers to the look pad while buttons/joystick keep their own pointers.
        binding.panelCustomButtons.fallbackTarget = binding.touchPad

        controlLayout = ControlLayoutController(
            context = this,
            host = binding.panelCustomButtons,
            joystick = binding.joystickMove,
            floatingBall = binding.btnFloatingBall,
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

        setupFloatingBall()

        // Grab sync fires immediately on register (often grabbing=false) — must not
        // hide the loading cover there. Only a later grab=true means the game is up.
        BooxinBridge.setGrabListener { grabbing ->
            Log.i(TAG, "grabListener grabbing=$grabbing overlayHidden=$overlayHidden")
            if (!overlayHidden) {
                if (grabbing) {
                    // Cursor grab = in-world / menu ready — enter without requiring a tap.
                    hideOverlayIfNeeded(force = true, allowWithoutBridge = true)
                }
                return@setGrabListener
            }
            refreshMoveVisibility()
            // Prevent stuck LMB/RMB from freezing the game until chat opens.
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
        val ready = overlayReadyPatterns.any { pattern ->
            line.contains(pattern, ignoreCase = true)
        }
        if (ready) {
            gameProgressSeen = true
            updateLoadingUi(100, line.trim().take(80))
            hideOverlayIfNeeded(force = true, allowWithoutBridge = true)
        }
    }

    private fun updateLoadingFromLog(line: String) {
        // Prefer explicit "xx%" in Minecraft / installer output.
        val pctMatch = PERCENT_IN_LOG.find(line)
        if (pctMatch != null) {
            val pct = pctMatch.groupValues[1].toIntOrNull()?.coerceIn(0, 99)
            if (pct != null && pct >= loadingPercent) {
                updateLoadingUi(pct, shortenStatus(line))
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
        } else if (line.isNotBlank() && loadingPercent in 1..98) {
            // Keep status fresh without jumping percent backward.
            binding.textLoadingStatus.text = shortenStatus(line)
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
        // Stop mirroring Minecraft logs onto the UI thread (Binder + TextView).
        GameLaunchLogBus.muteUiLogs(this)
        Log.i(TAG, "hideOverlay percent=$loadingPercent")
        updateLoadingUi(100, "进入游戏")
        hideOverlay()
    }

    private fun scheduleOverlayFallbackHide() {
        overlayHideTimeout?.let { mainHandler.removeCallbacks(it) }
        // Safety net — prefer ready logs / first grab; do not leave users on a black cover.
        overlayHideTimeout = Runnable {
            appendLog("加载超时，自动进入游戏画面")
            updateLoadingUi(99, "进入游戏")
            hideOverlayIfNeeded(force = true, allowWithoutBridge = true)
        }
        mainHandler.postDelayed(overlayHideTimeout!!, 35_000L)
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
        val ball = binding.btnFloatingBall
        var downX = 0f
        var downY = 0f
        var startL = 0
        var startT = 0
        var moved = false

        ball.setOnTouchListener { v, event ->
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
                    if (moved) {
                        controlLayout.updateFloatingBallFromView()
                    } else {
                        showFloatingMenu()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
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
        val buildLabel = if (binding.touchPad.gestureMode == GestureMode.BUILD) {
            getString(R.string.control_menu_gesture_build_current)
        } else {
            getString(R.string.control_menu_gesture_build)
        }
        val fightLabel = if (binding.touchPad.gestureMode == GestureMode.FIGHT) {
            getString(R.string.control_menu_gesture_fight_current)
        } else {
            getString(R.string.control_menu_gesture_fight)
        }
        val items = arrayOf(
            buildLabel,
            fightLabel,
            getString(R.string.control_menu_multiplayer),
            getString(R.string.control_menu_edit),
            hideLabel,
            getString(R.string.control_menu_exit)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.control_menu_title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> binding.touchPad.gestureMode = GestureMode.BUILD
                    1 -> binding.touchPad.gestureMode = GestureMode.FIGHT
                    2 -> multiplayerPanel.show()
                    3 -> {
                        setControlsVisible(true)
                        controlLayout.enterEditMode()
                    }
                    4 -> setControlsVisible(!controlsVisible)
                    5 -> returnToLauncher()
                }
            }
            .show()
    }

    /** Stop game, bring MainActivity to front, kill :game process (HotSpot cannot be stopped cleanly). */
    private fun returnToLauncher() {
        if (isFinishing || isDestroyed) return
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
        // Embedded JVM keeps the process alive after Activity finish — force exit.
        mainHandler.postDelayed({
            android.os.Process.killProcess(android.os.Process.myPid())
        }, 200L)
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
        // Hide only action buttons; move stick + touch→mouse stay.
        controlLayout.setButtonsVisible(visible)
        binding.touchPad.visibility = View.VISIBLE
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

    private fun maybeStartGameService() {
        if (serviceStarted || pendingVersionId.isBlank()) return
        if (!GameSurfaceBridge.hasSurface()) {
            appendLog("等待 Surface…")
            return
        }
        if (surfaceWidth <= 0 || surfaceHeight <= 0) {
            appendLog("等待 Surface 尺寸…")
            return
        }
        serviceStarted = true
        appendLog("启动游戏前台服务（:game 进程，${surfaceWidth}x${surfaceHeight}）…")
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
        // After play starts: never touch TextView — that was starving touch input.
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
        overlayHideTimeout?.let { mainHandler.removeCallbacks(it) }
        mainHandler.removeCallbacks(inputArmRunnable)
        BooxinBridge.setGrabListener(null)
        if (::controlLayout.isInitialized) {
            controlLayout.releaseAllHolds()
        }
        multiplayerPanel.dispose()
        binding.joystickMove.releaseKeys()
        binding.surfaceGame.holder.removeCallback(surfaceCallback)
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

        private val PERCENT_IN_LOG = Regex("""(?<![\d.])(\d{1,3})\s*%""")
    }
}
