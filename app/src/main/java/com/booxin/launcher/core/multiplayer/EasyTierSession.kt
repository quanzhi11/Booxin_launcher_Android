package com.booxin.launcher.core.multiplayer

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

data class EasyTierPeer(
    val hostname: String,
    val ipv4: String,
    val cost: String = "",
    val ping: String = "",
    val loss: String = "",
    val natType: String = ""
) {
    val isHost: Boolean
        get() = hostname.startsWith(HOSTNAME_PREFIX)

    val ipv4Host: String
        get() = ipv4.substringBefore('/').trim()

    companion object {
        const val HOSTNAME_PREFIX = "terracotta-mc-"
    }
}

/**
 * EasyTier process + CLI control plane, aligned with Booxin PC LobbyService join path.
 */
class EasyTierSession private constructor(
    private val paths: EasyTierRuntime.Paths
) {
    private val processRef = AtomicReference<Process?>(null)
    private val outputTail = StringBuilder()
    var rpcPort: Int = 0
        private set

    suspend fun launchJoin(lobby: TerracottaLobbyInfo, machineId: String = UUID.randomUUID().toString()) =
        withContext(Dispatchers.IO) {
            stopInternal()
            rpcPort = allocatePort()
            val relays = listOf(
                "tcp://175.178.174.103:8080",
                "tcp://175.178.174.103:8081",
                "tcp://175.178.174.103:8082"
            )
            val args = mutableListOf(
                paths.core.absolutePath,
                "--no-tun",
                "--multi-thread",
                "--enable-kcp-proxy",
                "--enable-quic-proxy",
                "--encryption-algorithm", "aes-gcm",
                "--compression", "zstd",
                "--default-protocol", "tcp",
                "--network-name", lobby.networkName,
                "--network-secret", lobby.networkSecret,
                "--machine-id", machineId,
                "--rpc-portal", "127.0.0.1:$rpcPort",
                "--bind-device", "false",
                "--private-mode", "true",
                "-d",
                "--hostname", UUID.randomUUID().toString().replace("-", ""),
                "--tcp-whitelist", "0",
                "--udp-whitelist", "0",
                "-l", "tcp://0.0.0.0:0",
                "-l", "udp://0.0.0.0:0"
            )
            relays.forEach { relay ->
                args += listOf("-p", relay)
            }
            Log.i(TAG, "launchJoin rpc=$rpcPort name=${lobby.networkName} core=${paths.core}")
            val pb = ProcessBuilder(args)
                .directory(paths.dir)
                .redirectErrorStream(true)
            pb.environment()["LD_LIBRARY_PATH"] = paths.dir.absolutePath
            val process = try {
                pb.start()
            } catch (e: java.io.IOException) {
                throw java.io.IOException(
                    "无法启动 EasyTier（${paths.core.absolutePath}）：${e.message}。" +
                        "Android 10+ 只能执行 APK nativeLibraryDir 内的 lib*.so。",
                    e
                )
            }
            processRef.set(process)
            drainAsync(process)
            delay(400)
            if (!process.isAlive) {
                val tail = recentOutput()
                error(
                    "EasyTier 启动后立即退出" +
                        if (tail.isBlank()) "" else "：\n${tail.takeLast(600)}"
                )
            }
        }

    suspend fun readPeers(): List<EasyTierPeer> = withContext(Dispatchers.IO) {
        ensureAlive()
        val output = runCli(
            listOf(
                "--rpc-portal", "127.0.0.1:$rpcPort",
                "-o", "json",
                "peer"
            )
        )
        parsePeers(output)
    }

    suspend fun waitForHostPeer(
        maxAttempts: Int = 36,
        delayMs: Long = 350L
    ): EasyTierPeer = withContext(Dispatchers.IO) {
        var best: EasyTierPeer? = null
        var seen = 0
        repeat(maxAttempts) { attempt ->
            ensureAlive()
            val peers = readPeers()
            Log.i(TAG, "peer poll ${attempt + 1}/$maxAttempts count=${peers.size}")
            val host = peers.firstOrNull { it.isHost }
            if (host != null) {
                seen++
                best = prefer(best, host)
                if (isLinkReady(host) || seen >= 4) {
                    return@withContext best!!
                }
            }
            delay(delayMs)
        }
        best ?: error("未发现房主节点（terracotta-mc-*）")
    }

    suspend fun addPortForward(targetIp: String, targetPort: Int): Int = withContext(Dispatchers.IO) {
        ensureAlive()
        val localPort = allocatePort()
        val ip = targetIp.substringBefore('/').trim()
        val cmds = listOf(
            listOf(
                "--rpc-portal", "127.0.0.1:$rpcPort",
                "port-forward", "add", "tcp", "127.0.0.1:$localPort", "$ip:$targetPort"
            ),
            listOf(
                "--rpc-portal", "127.0.0.1:$rpcPort",
                "port-forward", "add", "udp", "127.0.0.1:$localPort", "$ip:$targetPort"
            )
        )
        cmds.forEach { runCli(it) }
        localPort
    }

    fun stop() {
        stopInternal()
    }

    private fun stopInternal() {
        processRef.getAndSet(null)?.let { p ->
            try {
                p.destroy()
                p.waitFor(2, TimeUnit.SECONDS)
                if (p.isAlive) p.destroyForcibly()
            } catch (_: Throwable) {
            }
        }
        rpcPort = 0
    }

    private fun ensureAlive() {
        val p = processRef.get() ?: error("EasyTier 未运行")
        if (!p.isAlive) error("EasyTier 进程已退出")
    }

    private fun runCli(args: List<String>): String {
        val cmd = listOf(paths.cli.absolutePath) + args
        val pb = ProcessBuilder(cmd)
            .directory(paths.dir)
            .redirectErrorStream(true)
        pb.environment()["LD_LIBRARY_PATH"] = paths.dir.absolutePath
        val proc = try {
            pb.start()
        } catch (e: java.io.IOException) {
            throw java.io.IOException(
                "无法启动 easytier-cli（${paths.cli.absolutePath}）：${e.message}",
                e
            )
        }
        val out = proc.inputStream.bufferedReader().use { it.readText() }
        val ok = proc.waitFor(15, TimeUnit.SECONDS)
        if (!ok) {
            proc.destroyForcibly()
            error("easytier-cli 超时: ${args.joinToString(" ")}")
        }
        if (proc.exitValue() != 0) {
            error("easytier-cli 失败(${proc.exitValue()}): ${out.take(400)}")
        }
        return out.trim()
    }

    private fun parsePeers(raw: String): List<EasyTierPeer> {
        if (raw.isBlank()) return emptyList()
        val text = raw.trim()
        return try {
            when {
                text.startsWith("[") -> {
                    val arr = JSONArray(text)
                    buildList {
                        for (i in 0 until arr.length()) {
                            parsePeerObj(arr.getJSONObject(i))?.let { add(it) }
                        }
                    }
                }
                text.startsWith("{") -> {
                    val o = JSONObject(text)
                    val arr = o.optJSONArray("peers") ?: o.optJSONArray("peer")
                    if (arr != null) {
                        buildList {
                            for (i in 0 until arr.length()) {
                                parsePeerObj(arr.getJSONObject(i))?.let { add(it) }
                            }
                        }
                    } else {
                        listOfNotNull(parsePeerObj(o))
                    }
                }
                else -> emptyList()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "peer parse fail: ${t.message} raw=${text.take(200)}")
            emptyList()
        }
    }

    private fun parsePeerObj(o: JSONObject): EasyTierPeer? {
        val hostname = o.optString("hostname").ifBlank { o.optString("host_name") }
        val ipv4 = o.optString("ipv4").ifBlank {
            o.optJSONObject("ipv4")?.optString("addr").orEmpty()
        }.ifBlank { o.optString("ip") }
        if (hostname.isBlank() || ipv4.isBlank()) return null
        return EasyTierPeer(
            hostname = hostname,
            ipv4 = ipv4,
            cost = o.optString("cost"),
            ping = o.optString("lat_ms").ifBlank { o.optString("latency_ms") },
            loss = o.optString("loss_rate"),
            natType = o.optString("nat_type")
        )
    }

    private fun prefer(a: EasyTierPeer?, b: EasyTierPeer): EasyTierPeer {
        if (a == null) return b
        val ap = a.ping.toFloatOrNull() ?: 9999f
        val bp = b.ping.toFloatOrNull() ?: 9999f
        return if (bp < ap) b else a
    }

    private fun isLinkReady(peer: EasyTierPeer): Boolean {
        val ping = peer.ping.toFloatOrNull() ?: return false
        return ping in 0f..<1000f
    }

    private fun allocatePort(): Int = ServerSocket(0).use { it.localPort }

    private fun drainAsync(process: Process) {
        Thread({
            try {
                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val text = line ?: continue
                        synchronized(outputTail) {
                            outputTail.append(text).append('\n')
                            if (outputTail.length > 8192) {
                                outputTail.delete(0, outputTail.length - 8192)
                            }
                        }
                        if (text.contains("error", true) ||
                            text.contains("warn", true) ||
                            text.contains("rpc portal", true)
                        ) {
                            Log.i(TAG, text)
                        }
                    }
                }
            } catch (_: Throwable) {
            }
        }, "easytier-drain").apply { isDaemon = true }.start()
    }

    private fun recentOutput(): String = synchronized(outputTail) { outputTail.toString() }

    companion object {
        private const val TAG = "EasyTierSession"

        fun create(context: Context): EasyTierSession =
            EasyTierSession(EasyTierRuntime.ensure(context))
    }
}

/** Back-compat for older call sites that used object EasyTierSession.stop(). */
object EasyTierSessionHolder {
    @Volatile
    var current: EasyTierSession? = null

    fun stop() {
        current?.stop()
        current = null
    }
}
