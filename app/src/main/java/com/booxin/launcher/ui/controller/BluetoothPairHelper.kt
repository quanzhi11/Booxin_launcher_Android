package com.booxin.launcher.ui.controller

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.view.InputDevice
import androidx.core.content.ContextCompat

data class BtDeviceRow(
    val address: String,
    val name: String,
    val kindLabel: String,
    val bonded: Boolean,
    val device: BluetoothDevice
)

/**
 * Classic Bluetooth scan / bond for keyboards and gamepads.
 */
class BluetoothPairHelper(private val context: Context) {

    interface Listener {
        fun onStatus(text: String)
        fun onPairedChanged(list: List<BtDeviceRow>)
        fun onNearbyChanged(list: List<BtDeviceRow>)
        fun onScanState(scanning: Boolean)
    }

    var listener: Listener? = null

    /** Filter scanned devices: KEYBOARD / MOUSE / GAMEPAD / null = all. */
    var filterKind: ControllerInput.DeviceKind? = null

    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            ?: @Suppress("DEPRECATION") BluetoothAdapter.getDefaultAdapter()

    private val nearby = LinkedHashMap<String, BtDeviceRow>()
    private var scanning = false
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
                    scanning = true
                    listener?.onScanState(true)
                    listener?.onStatus(context.getString(com.booxin.launcher.R.string.bt_status_scanning))
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    scanning = false
                    listener?.onScanState(false)
                    listener?.onStatus(
                        if (adapter?.isEnabled == true) {
                            context.getString(com.booxin.launcher.R.string.bt_status_on)
                        } else {
                            context.getString(com.booxin.launcher.R.string.bt_status_off)
                        }
                    )
                }
                BluetoothDevice.ACTION_FOUND -> {
                    val device =
                        if (Build.VERSION.SDK_INT >= 33) {
                            intent.getParcelableExtra(
                                BluetoothDevice.EXTRA_DEVICE,
                                BluetoothDevice::class.java
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        } ?: return
                    if (!isInteresting(device)) return
                    if (!matchesFilter(device)) return
                    val row = toRow(device, bonded = device.bondState == BluetoothDevice.BOND_BONDED)
                    nearby[row.address] = row
                    listener?.onNearbyChanged(nearby.values.toList())
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    refreshPaired()
                    val device =
                        if (Build.VERSION.SDK_INT >= 33) {
                            intent.getParcelableExtra(
                                BluetoothDevice.EXTRA_DEVICE,
                                BluetoothDevice::class.java
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        }
                    if (device != null && device.bondState == BluetoothDevice.BOND_BONDED) {
                        nearby.remove(device.address)
                        listener?.onNearbyChanged(nearby.values.toList())
                        listener?.onStatus(
                            context.getString(
                                com.booxin.launcher.R.string.bt_status_paired,
                                safeName(device)
                            )
                        )
                    }
                }
                BluetoothAdapter.ACTION_STATE_CHANGED -> refreshAll()
            }
        }
    }

    fun missingPermissions(): Array<String> {
        val need = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!granted(Manifest.permission.BLUETOOTH_CONNECT)) {
                need.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (!granted(Manifest.permission.BLUETOOTH_SCAN)) {
                need.add(Manifest.permission.BLUETOOTH_SCAN)
            }
        } else {
            if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
                need.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
        return need.toTypedArray()
    }

    fun hasPermissions(): Boolean = missingPermissions().isEmpty()

    fun start() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        registered = true
        refreshAll()
    }

    fun stop() {
        stopScan()
        if (registered) {
            runCatching { context.unregisterReceiver(receiver) }
            registered = false
        }
    }

    fun refreshAll() {
        val a = adapter
        when {
            a == null -> listener?.onStatus(context.getString(com.booxin.launcher.R.string.bt_status_unsupported))
            !a.isEnabled -> listener?.onStatus(context.getString(com.booxin.launcher.R.string.bt_status_off))
            scanning -> listener?.onStatus(context.getString(com.booxin.launcher.R.string.bt_status_scanning))
            else -> listener?.onStatus(context.getString(com.booxin.launcher.R.string.bt_status_on))
        }
        refreshPaired()
        listener?.onNearbyChanged(nearby.values.toList())
        listener?.onScanState(scanning)
    }

    @SuppressLint("MissingPermission")
    fun filteredBonded(): List<BtDeviceRow> {
        if (!hasPermissions()) return emptyList()
        return adapter?.bondedDevices.orEmpty()
            .filter { isInteresting(it) && matchesFilter(it) }
            .map { toRow(it, bonded = true) }
            .sortedBy { it.name.lowercase() }
    }

    @SuppressLint("MissingPermission")
    fun refreshPaired() {
        if (!hasPermissions()) {
            listener?.onPairedChanged(emptyList())
            return
        }
        listener?.onPairedChanged(filteredBonded())
    }

    @SuppressLint("MissingPermission")
    fun startScan(): Boolean {
        val a = adapter ?: return false
        if (!hasPermissions()) return false
        if (!a.isEnabled) return false
        nearby.clear()
        listener?.onNearbyChanged(emptyList())
        runCatching { a.cancelDiscovery() }
        return runCatching { a.startDiscovery() }.getOrDefault(false)
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        val a = adapter ?: return
        if (!hasPermissions()) return
        runCatching { a.cancelDiscovery() }
        scanning = false
        listener?.onScanState(false)
    }

    @SuppressLint("MissingPermission")
    fun pair(device: BluetoothDevice): Boolean {
        if (!hasPermissions()) return false
        stopScan()
        return when (device.bondState) {
            BluetoothDevice.BOND_BONDED -> true
            BluetoothDevice.BOND_BONDING -> true
            else -> runCatching { device.createBond() }.getOrDefault(false)
        }
    }

    @SuppressLint("MissingPermission")
    fun unpair(device: BluetoothDevice): Boolean {
        if (!hasPermissions()) return false
        return runCatching {
            val method = device.javaClass.getMethod("removeBond")
            method.invoke(device) as Boolean
        }.getOrDefault(false)
    }

    fun enableBluetoothIntent(): Intent =
        Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)

    fun systemBluetoothSettingsIntent(): Intent =
        Intent(Settings.ACTION_BLUETOOTH_SETTINGS)

    fun connectedInputSummary(): String {
        val devices = physicalInputDevices()
        if (devices.isEmpty()) {
            return context.getString(com.booxin.launcher.R.string.bt_connected_none)
        }
        return devices.joinToString("\n") { d ->
            val kind = when (ControllerInput.deviceKindLabel(d)) {
                ControllerInput.DeviceKind.KEYBOARD ->
                    context.getString(com.booxin.launcher.R.string.bt_kind_keyboard)
                ControllerInput.DeviceKind.GAMEPAD ->
                    context.getString(com.booxin.launcher.R.string.bt_kind_gamepad)
                ControllerInput.DeviceKind.MOUSE ->
                    context.getString(com.booxin.launcher.R.string.bt_kind_mouse)
                ControllerInput.DeviceKind.COMBO ->
                    context.getString(com.booxin.launcher.R.string.bt_kind_combo)
                else -> context.getString(com.booxin.launcher.R.string.bt_kind_other)
            }
            "• $kind · ${d.name}"
        }
    }

    data class ConnectedSlots(
        val keyboard: String?,
        val mouse: String?,
        val gamepad: String?
    )

    fun connectedSlots(): ConnectedSlots {
        var keyboard: String? = null
        var mouse: String? = null
        var gamepad: String? = null
        for (d in physicalInputDevices()) {
            when (ControllerInput.deviceKindLabel(d)) {
                ControllerInput.DeviceKind.KEYBOARD,
                ControllerInput.DeviceKind.COMBO ->
                    if (keyboard == null) keyboard = d.name
                ControllerInput.DeviceKind.MOUSE ->
                    if (mouse == null) mouse = d.name
                ControllerInput.DeviceKind.GAMEPAD ->
                    if (gamepad == null) gamepad = d.name
                else -> Unit
            }
        }
        return ConnectedSlots(keyboard, mouse, gamepad)
    }

    private fun physicalInputDevices(): List<InputDevice> {
        val devices = ArrayList<InputDevice>()
        for (id in InputDevice.getDeviceIds()) {
            val device = InputDevice.getDevice(id) ?: continue
            val kind = ControllerInput.deviceKindLabel(device)
            if (kind != ControllerInput.DeviceKind.OTHER) devices.add(device)
        }
        return devices
    }

    @SuppressLint("MissingPermission")
    private fun matchesFilter(device: BluetoothDevice): Boolean {
        val want = filterKind ?: return true
        return classifyBt(device) == want
    }

    @SuppressLint("MissingPermission")
    private fun classifyBt(device: BluetoothDevice): ControllerInput.DeviceKind {
        val cls = device.bluetoothClass
        if (cls != null) {
            val major = cls.majorDeviceClass
            val deviceClass = cls.deviceClass
            if (major == BluetoothClass.Device.Major.PERIPHERAL) {
                when (deviceClass) {
                    BluetoothClass.Device.PERIPHERAL_KEYBOARD,
                    BluetoothClass.Device.PERIPHERAL_KEYBOARD_POINTING ->
                        return ControllerInput.DeviceKind.KEYBOARD
                    BluetoothClass.Device.PERIPHERAL_POINTING ->
                        return ControllerInput.DeviceKind.MOUSE
                    else -> Unit
                }
            }
            if (major == BluetoothClass.Device.Major.AUDIO_VIDEO) {
                return ControllerInput.DeviceKind.GAMEPAD
            }
        }
        val name = runCatching { device.name }.getOrNull()?.lowercase().orEmpty()
        return when {
            name.contains("mouse") || name.contains("mice") || name.contains("鼠标") ->
                ControllerInput.DeviceKind.MOUSE
            name.contains("keyboard") || name.contains("键盘") || name.contains("kb") ->
                ControllerInput.DeviceKind.KEYBOARD
            name.contains("gamepad") || name.contains("controller") || name.contains("手柄") ||
                name.contains("xbox") || name.contains("dualsense") || name.contains("dualshock") ||
                name.contains("joystick") ->
                ControllerInput.DeviceKind.GAMEPAD
            else -> ControllerInput.DeviceKind.GAMEPAD
        }
    }

    @SuppressLint("MissingPermission")
    private fun toRow(device: BluetoothDevice, bonded: Boolean): BtDeviceRow {
        val name = safeName(device)
        return BtDeviceRow(
            address = device.address ?: name,
            name = name,
            kindLabel = kindLabel(device),
            bonded = bonded,
            device = device
        )
    }

    @SuppressLint("MissingPermission")
    private fun safeName(device: BluetoothDevice): String {
        val n = runCatching { device.name }.getOrNull()?.trim().orEmpty()
        return n.ifBlank { device.address ?: "?" }
    }

    @SuppressLint("MissingPermission")
    private fun kindLabel(device: BluetoothDevice): String {
        val cls = device.bluetoothClass ?: return context.getString(com.booxin.launcher.R.string.bt_kind_other)
        val major = cls.majorDeviceClass
        val deviceClass = cls.deviceClass
        return when {
            major == BluetoothClass.Device.Major.PERIPHERAL &&
                (deviceClass == BluetoothClass.Device.PERIPHERAL_KEYBOARD ||
                    deviceClass == BluetoothClass.Device.PERIPHERAL_KEYBOARD_POINTING) ->
                context.getString(com.booxin.launcher.R.string.bt_kind_keyboard)
            major == BluetoothClass.Device.Major.PERIPHERAL &&
                deviceClass == BluetoothClass.Device.PERIPHERAL_POINTING ->
                context.getString(com.booxin.launcher.R.string.bt_kind_mouse)
            major == BluetoothClass.Device.Major.PERIPHERAL ->
                context.getString(com.booxin.launcher.R.string.bt_kind_peripheral)
            major == BluetoothClass.Device.Major.AUDIO_VIDEO ->
                context.getString(com.booxin.launcher.R.string.bt_kind_gamepad)
            else -> context.getString(com.booxin.launcher.R.string.bt_kind_other)
        }
    }

    @SuppressLint("MissingPermission")
    private fun isInteresting(device: BluetoothDevice): Boolean {
        val cls = device.bluetoothClass
        if (cls != null) {
            val major = cls.majorDeviceClass
            if (major == BluetoothClass.Device.Major.PERIPHERAL) return true
            // Many controllers report as audio/video or uncategorized.
            if (major == BluetoothClass.Device.Major.AUDIO_VIDEO) return true
            if (major == BluetoothClass.Device.Major.MISC) return true
            if (major == BluetoothClass.Device.Major.UNCATEGORIZED) return true
        }
        val name = runCatching { device.name }.getOrNull()?.lowercase().orEmpty()
        if (name.isBlank()) return true // still show unnamed during scan; user can decide
        val keywords = listOf(
            "keyboard", "kb", "key", "mouse", "mice", "gamepad", "controller", "joystick", "joy",
            "xbox", "dualshock", "dualsense", "playstation", "switch", "pro controller",
            "键盘", "鼠标", "手柄", "摇杆"
        )
        return keywords.any { name.contains(it) } || cls == null
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
