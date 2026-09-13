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
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.nio.charset.StandardCharsets

/**
 * Guest-side Minecraft LAN MOTD rebroadcast.
 *
 * Protocol payload: `[MOTD]{description}[/MOTD][AD]{localPort}[/AD]` on UDP 4445.
 *
 * Android same-device note: multicast loopback is flaky (worse under Forge load), so we
 * **also unicast to 127.0.0.1:4445**. Minecraft's LanServerDetector binds that port and
 * will treat the source as 127.0.0.1 — which matches our LocalTcpRelay.
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
    @Volatile private var multicastSocket: MulticastSocket? = null
    @Volatile private var unicastSocket: DatagramSocket? = null

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
            val loopback = runCatching { InetAddress.getByName("127.0.0.1") }.getOrNull()
            if (ipv4 == null && loopback == null) {
                DiagEventLog.w(TAG, "LAN broadcast aborted: no send address")
                return@launch
            }

            val mcast = if (ipv4 != null) {
                runCatching {
                    MulticastSocket().also {
                        it.reuseAddress = true
                        runCatching { it.loopbackMode = false }
                        runCatching { it.timeToLive = 2 }
                        runCatching { it.joinGroup(ipv4) }
                        multicastSocket = it
                    }
                }.onFailure {
                    DiagEventLog.w(TAG, "multicast socket open failed: ${it.message}")
                }.getOrNull()
            } else null

            val ucast = runCatching {
                DatagramSocket().also {
                    it.reuseAddress = true
                    unicastSocket = it
                }
            }.onFailure {
                DiagEventLog.w(TAG, "unicast socket open failed: ${it.message}")
            }.getOrNull()

            if (mcast == null && ucast == null) {
                DiagEventLog.w(TAG, "LAN broadcast aborted: no sockets")
                return@launch
            }

            var ticks = 0
            while (isActive) {
                try {
                    // Prefer loopback unicast — reliable on Android / Forge.
                    if (ucast != null && loopback != null) {
                        ucast.send(
                            DatagramPacket(
                                payload,
                                payload.size,
                                InetSocketAddress(loopback, broadcastPort)
                            )
                        )
                    }
                    if (mcast != null && ipv4 != null) {
                        runCatching {
                            mcast.send(
                                DatagramPacket(
                                    payload,
                                    payload.size,
                                    InetSocketAddress(ipv4, broadcastPort)
                                )
                            )
                        }
                    }
                    if (ticks == 0 || ticks % 10 == 0) {
                        DiagEventLog.i(
                            TAG,
                            "LAN MOTD tick#$ticks port=$localPort unicast=${ucast != null} mcast=${mcast != null}"
                        )
                    }
                    ticks++
                    delay(INTERVAL_MS)
                } catch (t: Throwable) {
                    if (!isActive) break
                    DiagEventLog.w(TAG, "LAN broadcast send failed: ${t.message}")
                    delay(RETRY_MS)
                }
            }
            runCatching { mcast?.close() }
            runCatching { ucast?.close() }
            if (multicastSocket === mcast) multicastSocket = null
            if (unicastSocket === ucast) unicastSocket = null
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        runCatching { multicastSocket?.close() }
        runCatching { unicastSocket?.close() }
        multicastSocket = null
        unicastSocket = null
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
        private const val INTERVAL_MS = 800L
        private const val RETRY_MS = 2_000L

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
