package com.booxin.launcher.ui.controller

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AppCompatDialog
import androidx.core.view.isVisible
import androidx.webkit.WebViewAssetLoader
import com.booxin.launcher.R
import com.booxin.launcher.databinding.DialogBluetoothPairBinding
import com.booxin.launcher.databinding.ItemBluetoothDeviceBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Flow: pick type → scan/pair (with connect anim) → 3D calibrate → success (+ sound).
 */
class BluetoothPairDialog(
    private val activity: Activity,
    private val permissionLauncher: ActivityResultLauncher<Array<String>>,
    private val enableBtLauncher: ActivityResultLauncher<Intent>
) {
    private var dialog: AppCompatDialog? = null
    private var helper: BluetoothPairHelper? = null
    private var binding: DialogBluetoothPairBinding? = null
    private var calibrateWeb: WebView? = null
    private var selectedKind: ControllerInput.DeviceKind = ControllerInput.DeviceKind.GAMEPAD
    private var pairedName: String = ""
    private var listFingerprint = ""
    private var pendingAfterBond: BluetoothDevice? = null
    private var connectPulse: ObjectAnimator? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun show() {
        if (dialog?.isShowing == true) return
        try {
            showInternal()
        } catch (t: Throwable) {
            Toast.makeText(
                activity,
                activity.getString(R.string.bt_open_failed, t.message ?: t.javaClass.simpleName),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun showInternal() {
        val b = DialogBluetoothPairBinding.inflate(LayoutInflater.from(activity))
        binding = b
        listFingerprint = ""

        val h = BluetoothPairHelper(activity)
        helper = h
        h.listener = object : BluetoothPairHelper.Listener {
            override fun onStatus(text: String) {
                binding?.textPairStatus?.text = text
            }

            override fun onPairedChanged(list: List<BtDeviceRow>) {
                val pending = pendingAfterBond
                if (pending != null) {
                    val matched = list.any { it.address == pending.address && it.bonded }
                    if (matched || pending.bondState == BluetoothDevice.BOND_BONDED) {
                        pendingAfterBond = null
                        pairedName = list.firstOrNull { it.address == pending.address }?.name
                            ?: safeBtName(pending)
                        mainHandler.postDelayed({
                            hideConnecting()
                            goCalibrate()
                        }, 450L)
                        return
                    }
                }
                if (binding?.panelPair?.isVisible == true) {
                    listFingerprint = ""
                    renderDeviceLists(emptyList())
                }
            }

            override fun onNearbyChanged(list: List<BtDeviceRow>) {
                renderDeviceLists(list)
            }

            override fun onScanState(scanning: Boolean) {
                binding?.buttonPairScan?.setText(
                    if (scanning) R.string.bt_scanning else R.string.bt_scan
                )
                binding?.buttonPairScan?.isEnabled = !scanning
            }
        }

        b.buttonPickKeyboard.setOnClickListener { openPairPage(ControllerInput.DeviceKind.KEYBOARD) }
        b.buttonPickGamepad.setOnClickListener { openPairPage(ControllerInput.DeviceKind.GAMEPAD) }
        b.buttonPickMouse.setOnClickListener { openPairPage(ControllerInput.DeviceKind.MOUSE) }
        b.buttonPickClose.setOnClickListener { dialog?.dismiss() }
        b.buttonPairBack.setOnClickListener { showPick() }
        b.buttonPairScan.setOnClickListener { startScanFlow() }
        b.buttonPairSystem.setOnClickListener {
            activity.startActivity(h.systemBluetoothSettingsIntent())
        }
        b.buttonCalibrateDone.setOnClickListener { goSuccess() }
        b.buttonSuccessDone.setOnClickListener { dialog?.dismiss() }

        showPick()

        val dlg = AppCompatDialog(activity)
        dlg.setContentView(b.root)
        dlg.setCancelable(true)
        dlg.setOnDismissListener {
            stopConnectPulse()
            h.stop()
            destroyCalibrateWeb()
            helper = null
            binding = null
            dialog = null
        }
        dialog = dlg
        dlg.show()

        val window = dlg.window ?: return
        val dm = activity.resources.displayMetrics
        // Cap size; NestedScrollView panels adapt to remaining space on any phone.
        val w = (dm.widthPixels * 0.92f).toInt()
        val hPx = (dm.heightPixels * 0.86f).toInt()
        window.setLayout(w, hPx)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        // ContentFrameLayout requires MarginLayoutParams; never assign plain LayoutParams.
        b.root.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        h.start()
    }

    fun onPermissionsResult(granted: Boolean) {
        if (!granted) {
            Toast.makeText(activity, R.string.bt_permission_denied, Toast.LENGTH_LONG).show()
            return
        }
        helper?.refreshAll()
    }

    fun onBluetoothEnabled() {
        helper?.refreshAll()
        startScanFlow()
    }

    fun refreshConnected() = Unit

    private fun showPick() {
        pendingAfterBond = null
        hideConnecting()
        helper?.stopScan()
        helper?.filterKind = null
        val b = binding ?: return
        b.panelPick.isVisible = true
        b.panelPair.isVisible = false
        b.panelCalibrate.isVisible = false
        b.panelSuccess.isVisible = false
        destroyCalibrateWeb()
    }

    private fun openPairPage(kind: ControllerInput.DeviceKind) {
        selectedKind = kind
        listFingerprint = ""
        hideConnecting()
        val b = binding ?: return
        val h = helper ?: return
        h.filterKind = kind
        b.panelPick.isVisible = false
        b.panelPair.isVisible = true
        b.panelCalibrate.isVisible = false
        b.panelSuccess.isVisible = false
        b.textPairTitle.text = when (kind) {
            ControllerInput.DeviceKind.KEYBOARD -> activity.getString(R.string.bt_pair_keyboard)
            ControllerInput.DeviceKind.MOUSE -> activity.getString(R.string.bt_pair_mouse)
            else -> activity.getString(R.string.bt_pair_gamepad)
        }
        b.listPairDevices.removeAllViews()
        b.textPairEmpty.isVisible = true
        ensureReadyThen {
            h.refreshAll()
            renderDeviceLists(emptyList())
            startScanFlow()
        }
    }

    private fun renderDeviceLists(nearby: List<BtDeviceRow>) {
        val b = binding ?: return
        val h = helper ?: return
        if (b.panelConnecting.isVisible) return
        val bonded = h.filteredBonded()
        val byAddr = LinkedHashMap<String, BtDeviceRow>()
        for (row in bonded) byAddr[row.address] = row
        for (row in nearby) {
            if (row.address !in byAddr) byAddr[row.address] = row
        }
        val merged = byAddr.values.toList()
        val fp = merged.joinToString("|") { "${it.address}:${it.bonded}" }
        if (fp == listFingerprint) return
        listFingerprint = fp

        val scrollY = b.scrollPairList.scrollY
        b.listPairDevices.removeAllViews()
        b.textPairEmpty.isVisible = merged.isEmpty()
        val inflater = LayoutInflater.from(activity)
        for (row in merged) {
            val item = ItemBluetoothDeviceBinding.inflate(inflater, b.listPairDevices, false)
            item.textBtDeviceName.text = row.name
            item.textBtDeviceMeta.text = if (row.bonded) {
                activity.getString(R.string.bt_already_paired_meta, row.kindLabel, row.address)
            } else {
                "${row.kindLabel} · ${row.address}"
            }
            if (row.bonded) {
                item.buttonBtDisconnect.isVisible = true
                item.buttonBtDisconnect.setOnClickListener { disconnectDevice(row) }
                item.buttonBtAction.setText(R.string.bt_use_device)
                item.buttonBtAction.setOnClickListener { onPairedSelected(row) }
                item.root.setOnClickListener { onPairedSelected(row) }
            } else {
                item.buttonBtDisconnect.isVisible = false
                item.buttonBtAction.setText(R.string.bt_pair)
                item.buttonBtAction.setOnClickListener { startPair(row) }
                item.root.setOnClickListener { startPair(row) }
            }
            b.listPairDevices.addView(item.root)
        }
        b.scrollPairList.post {
            val max = (b.scrollPairList.getChildAt(0)?.height ?: 0) - b.scrollPairList.height
            b.scrollPairList.scrollTo(0, scrollY.coerceIn(0, max.coerceAtLeast(0)))
        }
    }

    private fun disconnectDevice(row: BtDeviceRow) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.bt_unpair_title)
            .setMessage(activity.getString(R.string.bt_unpair_message, row.name))
            .setPositiveButton(R.string.bt_disconnect) { _, _ ->
                val ok = helper?.unpair(row.device) == true
                Toast.makeText(
                    activity,
                    if (ok) R.string.bt_disconnect_ok else R.string.bt_disconnect_failed,
                    Toast.LENGTH_SHORT
                ).show()
                if (!ok) {
                    helper?.systemBluetoothSettingsIntent()?.let { activity.startActivity(it) }
                } else {
                    listFingerprint = ""
                    helper?.refreshPaired()
                    renderDeviceLists(emptyList())
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun startPair(row: BtDeviceRow) {
        val h = helper ?: return
        pairedName = row.name
        pendingAfterBond = row.device
        showConnecting(row.name)
        val ok = h.pair(row.device)
        if (!ok) {
            hideConnecting()
            Toast.makeText(activity, R.string.bt_pair_failed, Toast.LENGTH_SHORT).show()
            return
        }
        if (row.device.bondState == BluetoothDevice.BOND_BONDED) {
            pendingAfterBond = null
            mainHandler.postDelayed({
                hideConnecting()
                goCalibrate()
            }, 500L)
        }
    }

    private fun onPairedSelected(row: BtDeviceRow) {
        pairedName = row.name
        pendingAfterBond = null
        showConnecting(row.name)
        mainHandler.postDelayed({
            hideConnecting()
            goCalibrate()
        }, 500L)
    }

    private fun showConnecting(name: String) {
        val b = binding ?: return
        b.panelConnecting.isVisible = true
        b.panelConnecting.alpha = 0f
        b.panelConnecting.animate().alpha(1f).setDuration(220L).start()
        b.textConnectingName.text = name
        stopConnectPulse()
        connectPulse = ObjectAnimator.ofFloat(b.textConnecting, "alpha", 0.35f, 1f).apply {
            duration = 700L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun hideConnecting() {
        stopConnectPulse()
        val b = binding ?: return
        if (!b.panelConnecting.isVisible) return
        b.panelConnecting.animate().alpha(0f).setDuration(180L).withEndAction {
            b.panelConnecting.isVisible = false
            b.panelConnecting.alpha = 1f
        }.start()
    }

    private fun stopConnectPulse() {
        connectPulse?.cancel()
        connectPulse = null
        binding?.textConnecting?.alpha = 1f
    }

    private fun goCalibrate() {
        val b = binding ?: return
        helper?.stopScan()
        hideConnecting()
        b.panelPick.isVisible = false
        b.panelPair.isVisible = false
        b.panelCalibrate.isVisible = true
        b.panelSuccess.isVisible = false
        b.textCalibrateHint.text = activity.getString(
            R.string.bt_calibrate_hint_named,
            kindLabel(),
            pairedName.ifBlank { kindLabel() }
        )
        // Fixed 3D viewport + NestedScrollView: no runtime height math, fits all screens.
        b.panelCalibrate.post {
            attachCalibrateWeb(b.frameCalibrate3d, selectedKind)
        }
    }

    private fun goSuccess() {
        val b = binding ?: return
        destroyCalibrateWeb()
        b.panelPick.isVisible = false
        b.panelPair.isVisible = false
        b.panelCalibrate.isVisible = false
        b.panelSuccess.isVisible = true
        b.textSuccessMessage.text = activity.getString(
            R.string.bt_success_message,
            kindLabel(),
            pairedName.ifBlank { kindLabel() }
        )
        PeripheralSuccessSound.play()
    }

    private fun kindLabel(): String = when (selectedKind) {
        ControllerInput.DeviceKind.KEYBOARD -> activity.getString(R.string.bt_kind_keyboard)
        ControllerInput.DeviceKind.MOUSE -> activity.getString(R.string.bt_kind_mouse)
        else -> activity.getString(R.string.bt_kind_gamepad)
    }

    @SuppressLint("MissingPermission")
    private fun safeBtName(device: BluetoothDevice): String =
        runCatching { device.name }.getOrNull()?.trim().orEmpty()
            .ifBlank { device.address ?: kindLabel() }

    @SuppressLint("SetJavaScriptEnabled")
    private fun attachCalibrateWeb(frame: FrameLayout, kind: ControllerInput.DeviceKind) {
        destroyCalibrateWeb()
        val web = WebView(activity)
        calibrateWeb = web
        web.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        web.overScrollMode = WebView.OVER_SCROLL_NEVER
        // Keep drag-to-rotate from being stolen by NestedScrollView.
        web.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE ->
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.parent?.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
        frame.removeAllViews()
        frame.addView(web)

        val kindParam = when (kind) {
            ControllerInput.DeviceKind.KEYBOARD -> "keyboard"
            ControllerInput.DeviceKind.MOUSE -> "mouse"
            else -> "gamepad"
        }
        val domain = "appassets.androidplatform.net"
        val loader = WebViewAssetLoader.Builder()
            .setDomain(domain)
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(activity))
            .build()
        web.setBackgroundColor(0x00000000)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = false
            allowContentAccess = false
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url ?: return null
                return loader.shouldInterceptRequest(url)
            }
        }
        web.loadUrl("https://$domain/assets/peripheral3d/calibrate.html?kind=$kindParam")
    }

    private fun destroyCalibrateWeb() {
        val web = calibrateWeb ?: return
        calibrateWeb = null
        runCatching {
            web.stopLoading()
            web.loadUrl("about:blank")
            (web.parent as? ViewGroup)?.removeView(web)
            web.destroy()
        }
        binding?.frameCalibrate3d?.removeAllViews()
    }

    private fun ensureReadyThen(then: () -> Unit) {
        val h = helper ?: return
        val missing = h.missingPermissions()
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing)
            return
        }
        then()
    }

    private fun startScanFlow() {
        val h = helper ?: return
        val missing = h.missingPermissions()
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing)
            return
        }
        val adapter =
            (activity.getSystemService(android.content.Context.BLUETOOTH_SERVICE)
                as? android.bluetooth.BluetoothManager)?.adapter
        if (adapter == null) {
            Toast.makeText(activity, R.string.bt_status_unsupported, Toast.LENGTH_SHORT).show()
            return
        }
        if (!adapter.isEnabled) {
            enableBtLauncher.launch(h.enableBluetoothIntent())
            return
        }
        listFingerprint = ""
        if (!h.startScan()) {
            Toast.makeText(activity, R.string.bt_scan_failed, Toast.LENGTH_SHORT).show()
        }
    }
}
