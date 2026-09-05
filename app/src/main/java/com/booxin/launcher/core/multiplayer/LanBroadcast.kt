package com.booxin.launcher.core.multiplayer

import android.content.Context
import android.net.wifi.WifiManager
import com.booxin.launcher.core.diag.DiagEventLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.nio.charset.StandardCharsets

/**
 * Guest-side Minecraft LAN MOTD rebroadcast, aligned with PC BroadcastLocal.
 *
 * Protocol:
 * - IPv4 multicast `224.0.2.60:4445`
 * - IPv6 multicast `ff75:230::60:4445` (best-effort)
 * - UTF-8 payload `[MOTD]{description}[/MOTD][AD]{localPort}[/AD]`
 * - ~1.5s interval; [localPort] is the locally forwarded Minecraft TCP port
 *
 * Android notes:
 * - Needs [android.permission.CHANGE_WIFI_MULTICAST_MODE] so Wi-Fi chipsets deliver
 *   multicast to the local Minecraft client (same-device discovery).
 * - Holds [WifiManager.MulticastLock] while broadcasting.
 * - Failures are logged only; callers must treat start as best-effort.
 */
class LanBroadcast(
    context: Context,
    private val description: String,
    private val localPort: Int,
    private val broadcastPort: Int = DEFAULT_BROADCAST_PORT
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    @Volatile private var socket: MulticastSocket? = null

    val isRunning: Boolean
        get() = job?.isActive == true

    fun start() {
        if (job?.isActive == true) return
        acquireMulticastLock()
        val payload = "[MOTD]$description[/MOTD][AD]$localPort[/AD]"
            .toByteArray(StandardCharsets.UTF_8)
        job = scope.launch {
            DiagEventLog.i(
                TAG,
                "LAN broadcast start desc=\"$description\" localPort=$localPort"
            )
            val ipv4 = runCatching { InetAddress.getByName(MULTICAST_V4) }.getOrNull()
            val ipv6 = runCatching { InetAddress.getByName(MULTICAST_V6) }.getOrNull()
            if (ipv4 == null && ipv6 == null) {
                DiagEventLog.w(TAG, "LAN broadcast aborted: no multicast address resolved")
                return@launch
            }

            val sock = try {
                MulticastSocket().also {
                    it.reuseAddress = true
                    // TTL 2 matches PC BroadcastLocal MulticastTimeToLive.
                    runCatching { it.timeToLive = 2 }
                    socket = it
                }
            } catch (t: Throwable) {
                DiagEventLog.w(TAG, "LAN broadcast socket open failed: ${t.message}")
                return@launch
            }

            while (isActive) {
                try {
                    if (ipv4 != null) {
                        sock.send(
                            DatagramPacket(
                                payload,
                                payload.size,
                                InetSocketAddress(ipv4, broadcastPort)
                            )
                        )
                    }
                    if (ipv6 != null) {
                        runCatching {
                            sock.send(
                                DatagramPacket(
                                    payload,
                                    payload.size,
                                    InetSocketAddress(ipv6, broadcastPort)
                                )
                            )
                        }
                    }
                    delay(INTERVAL_MS)
                } catch (t: Throwable) {
                    if (!isActive) break
                    DiagEventLog.w(TAG, "LAN broadcast send failed: ${t.message}")
                    delay(RETRY_MS)
                }
            }
            runCatching { sock.close() }
            if (socket === sock) socket = null
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        runCatching { socket?.close() }
        socket = null
        releaseMulticastLock()
        DiagEventLog.i(TAG, "LAN broadcast stopped localPort=$localPort")
    }

    override fun close() {
        stop()
        scope.cancel()
    }

    private fun acquireMulticastLock() {
        if (multicastLock != null) return
        try {
            val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return
            val lock = wifi.createMulticastLock(TAG).also { it.setReferenceCounted(false) }
            lock.acquire()
            multicastLock = lock
            DiagEventLog.i(TAG, "MulticastLock acquired")
        } catch (t: Throwable) {
            DiagEventLog.w(TAG, "MulticastLock acquire failed: ${t.message}")
        }
    }

    private fun releaseMulticastLock() {
        val lock = multicastLock ?: return
        multicastLock = null
        runCatching {
            if (lock.isHeld) lock.release()
            DiagEventLog.i(TAG, "MulticastLock released")
        }.onFailure { t ->
            DiagEventLog.w(TAG, "MulticastLock release failed: ${t.message}")
        }
    }

    companion object {
        private const val TAG = "LanBroadcast"
        const val DEFAULT_BROADCAST_PORT = 4445
        private const val MULTICAST_V4 = "224.0.2.60"
        private const val MULTICAST_V6 = "ff75:230::60"
        private const val INTERVAL_MS = 1_500L
        private const val RETRY_MS = 5_000L

        /** Same wording as PC LobbyService.BuildLocalBroadcastDescription. */
        fun buildDescription(members: List<RoomMember>): String {
            val hostName = members.firstOrNull { it.isHost }?.name?.trim()
            return if (hostName.isNullOrBlank()) {
                "Booxin 联机大厅"
            } else {
                "${hostName} 的联机房间"
            }
        }
    }
}
