package com.booxin.launcher.core.multiplayer

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.File
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

data class FoundLanWorld(
    val name: String,
    val port: Int,
    val onlinePlayers: Int = -1,
    val maxPlayers: Int = -1
)

/**
 * Discover local Minecraft "Open to LAN" ports (PC LocalMinecraftPortScanner parity).
 *
 * Default path avoids multicast (safer in `:game`); optional LAN MOTD listen is best-effort.
 */
object LocalMinecraftPortScanner {
    private const val TAG = "LanPortScan"

    private val adPattern = Pattern.compile(
        "\\[AD\\](\\d{1,5})\\[/AD\\]",
        Pattern.CASE_INSENSITIVE
    )
    private val motdPattern = Pattern.compile(
        "\\[MOTD\\](.*?)\\[/MOTD\\]",
        Pattern.CASE_INSENSITIVE or Pattern.DOTALL
    )

    suspend fun scan(
        context: Context? = null,
        excludePorts: Set<Int> = emptySet(),
        multicastListenMs: Long = 0L
    ): List<FoundLanWorld> = withContext(Dispatchers.IO) {
        runCatching {
            scanInternal(context, excludePorts, multicastListenMs)
        }.getOrElse { err ->
            Log.w(TAG, "scan failed: ${err.message}")
            emptyList()
        }
    }

    private suspend fun scanInternal(
        context: Context?,
        excludePorts: Set<Int>,
        multicastListenMs: Long
    ): List<FoundLanWorld> {
        val discovered = ConcurrentHashMap<Int, FoundLanWorld>()

        if (context != null && multicastListenMs > 0L) {
            runCatching {
                listenMulticast(context, multicastListenMs)
            }.getOrDefault(emptyList()).forEach { world ->
                if (world.port !in excludePorts) {
                    discovered.putIfAbsent(world.port, world)
                }
            }
        }

        val listeners = getListeningPorts()
            .filter { it in 1024..65535 && it !in excludePorts }
            .toSet()

        val candidates = LinkedHashSet<Int>().apply {
            addAll(listeners)
            // Always include common LAN defaults so Open-to-LAN is findable
            // even when /proc/net/tcp is empty or filtered.
            add(25565)
            for (p in 25566..25580) add(p)
        }

        coroutineScope {
            candidates.map { port ->
                async {
                    if (port in excludePorts) return@async
                    val status = runCatching {
                        MinecraftStatusProbe.probe("127.0.0.1", port)
                    }.getOrNull()
                    if (status != null && status.protocol > 0) {
                        val name = status.versionName.ifBlank { "Minecraft 世界 ($port)" }
                        discovered[port] = FoundLanWorld(
                            name = name,
                            port = port,
                            onlinePlayers = status.onlinePlayers,
                            maxPlayers = status.maxPlayers
                        )
                        return@async
                    }
                    if (port in listeners && canReach(port) && !discovered.containsKey(port)) {
                        discovered.putIfAbsent(
                            port,
                            FoundLanWorld(name = "本地端口 $port", port = port)
                        )
                    }
                }
            }.awaitAll()
        }

        return discovered.values.sortedBy { it.port }
    }

    /** Best single port for auto-fill: prefer status-confirmed worlds. */
    suspend fun scanBestPort(context: Context? = null, excludePorts: Set<Int> = emptySet()): Int? {
        val worlds = scan(context, excludePorts)
        if (worlds.isEmpty()) return null
        val confirmed = worlds.filter {
            it.onlinePlayers >= 0 || it.name.contains("Minecraft", ignoreCase = true)
        }
        return (confirmed.ifEmpty { worlds }).firstOrNull()?.port
    }

    private suspend fun listenMulticast(context: Context, durationMs: Long): List<FoundLanWorld> {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = wifi?.createMulticastLock("booxin-lan-scan")?.apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }
        return try {
            withTimeoutOrNull(durationMs) {
                withContext(Dispatchers.IO) {
                    val found = ConcurrentHashMap<Int, FoundLanWorld>()
                    val group = InetAddress.getByName("224.0.2.60")
                    MulticastSocket(null).use { socket ->
                        socket.reuseAddress = true
                        socket.bind(InetSocketAddress(LanBroadcast.DEFAULT_BROADCAST_PORT))
                        runCatching { socket.joinGroup(group) }
                        socket.soTimeout = 300
                        val buf = ByteArray(1024)
                        val deadline = System.currentTimeMillis() + durationMs
                        while (System.currentTimeMillis() < deadline) {
                            try {
                                val packet = DatagramPacket(buf, buf.size)
                                socket.receive(packet)
                                val text = String(
                                    packet.data,
                                    packet.offset,
                                    packet.length,
                                    StandardCharsets.UTF_8
                                )
                                parseLanPayload(text)?.let { found[it.port] = it }
                            } catch (_: Throwable) {
                            }
                        }
                        runCatching { socket.leaveGroup(group) }
                    }
                    found.values.toList()
                }
            }.orEmpty()
        } finally {
            runCatching { if (lock?.isHeld == true) lock.release() }
        }
    }

    private fun parseLanPayload(text: String): FoundLanWorld? {
        val ad = adPattern.matcher(text)
        if (!ad.find()) return null
        val port = ad.group(1)?.toIntOrNull() ?: return null
        if (port !in 1..65535) return null
        val motd = motdPattern.matcher(text).takeIf { it.find() }?.group(1)?.trim().orEmpty()
        return FoundLanWorld(
            name = motd.ifBlank { "局域网世界 ($port)" },
            port = port
        )
    }

    private fun getListeningPorts(): Set<Int> {
        val ports = LinkedHashSet<Int>()
        parseProcNetTcp(File("/proc/net/tcp"), ports)
        parseProcNetTcp(File("/proc/net/tcp6"), ports)
        return ports
    }

    private fun parseProcNetTcp(file: File, out: MutableSet<Int>) {
        if (!file.exists()) return
        runCatching {
            BufferedReader(file.reader()).use { reader ->
                reader.readLine()
                var line = reader.readLine()
                while (line != null) {
                    val parts = line.trim().split(Regex("\\s+"))
                    if (parts.size >= 4 && parts[3].equals("0A", ignoreCase = true)) {
                        val local = parts[1]
                        val colon = local.lastIndexOf(':')
                        if (colon >= 0) {
                            val portHex = local.substring(colon + 1)
                            portHex.toIntOrNull(16)?.takeIf { it in 1..65535 }?.let { out.add(it) }
                        }
                    }
                    line = reader.readLine()
                }
            }
        }
    }

    private fun canReach(port: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), 400)
                true
            }
        } catch (_: Throwable) {
            false
        }
    }
}
