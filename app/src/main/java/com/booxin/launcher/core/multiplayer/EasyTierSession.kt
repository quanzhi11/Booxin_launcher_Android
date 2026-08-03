package com.booxin.launcher.core.multiplayer

import android.content.Context
import com.booxin.launcher.BuildConfig
import com.booxin.launcher.core.diag.DiagEventLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
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
            // Relays from local.properties → booxin.easytierRelays (comma-separated).
            val relays = BuildConfig.BOOXIN_EASYTIER_RELAYS
                .split(',', ';')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
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
                // Bind loopback explicitly so CLI can always reach the portal.
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
            DiagEventLog.i(TAG, "launchJoin rpc=$rpcPort name=${lobby.networkName} core=${paths.core}")
            val pb = ProcessBuilder(args)
                .directory(paths.dir)
                .redirectErrorStream(true)
            pb.environment()["LD_LIBRARY_PATH"] = paths.dir.absolutePath
            val process = try {
                pb.start()
            } catch (e: java.io.IOException) {
                DiagEventLog.e(TAG, "start failed", e)
                throw java.io.IOException(
                    "无法启动 EasyTier（${paths.core.absolutePath}）：${e.message}。" +
                        "Android 10+ 只能执行 APK nativeLibraryDir 内的 lib*.so。",
                    e
                )
            }
            processRef.set(process)
            drainAsync(process)
            if (!process.isAlive) {
                val tail = recentOutput()
                DiagEventLog.e(TAG, "exited immediately: ${tail.takeLast(300)}")
                error(
                    "EasyTier 启动后立即退出" +
                        if (tail.isBlank()) "" else "：\n${tail.takeLast(600)}"
                )
            }
            // TCP open is not enough — WebClientService must be registered or CLI
            // returns "failed to get manage client" (common on slow OEM devices).
            waitForRpcReady()
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
            DiagEventLog.i(TAG, "peer poll ${attempt + 1}/$maxAttempts count=${peers.size}")
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
        DiagEventLog.e(TAG, "host peer not found after $maxAttempts polls")
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
        DiagEventLog.i(TAG, "port-forward local=$localPort -> $ip:$targetPort")
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
        if (!p.isAlive) {
            val tail = recentOutput().takeLast(400)
            DiagEventLog.e(TAG, "process dead: $tail")
            error(
                "EasyTier 进程已退出" +
                    if (tail.isBlank()) "" else "：\n$tail"
            )
        }
    }

    /**
     * Wait until CLI can talk to core. TCP accept alone is insufficient — the
     * WebClient/PeerManager services register slightly later, which is what
     * produces "failed to get manage client" on slow phones.
     */
    private fun waitForRpcReady(timeoutMs: Long = 20_000L) {
        ensureAlive()
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastError: String? = null
        var tcpOk = false
        while (System.currentTimeMillis() < deadline) {
            ensureAlive()
            if (!tcpOk) {
                try {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress("127.0.0.1", rpcPort), 400)
                    }
                    tcpOk = true
                    DiagEventLog.i(TAG, "RPC TCP open on 127.0.0.1:$rpcPort")
                } catch (t: Throwable) {
                    lastError = t.message
                    Thread.sleep(200)
                    continue
                }
            }
            try {
                // Probe the same path join uses; empty peer list is still success.
                runCliOnce(
                    listOf(
                        "--rpc-portal", "127.0.0.1:$rpcPort",
                        "-o", "json",
                        "peer"
                    )
                )
                DiagEventLog.i(TAG, "RPC CLI ready on 127.0.0.1:$rpcPort")
                return
            } catch (t: Throwable) {
                lastError = t.message
                Thread.sleep(300)
            }
        }
        val tail = recentOutput().takeLast(500)
        DiagEventLog.e(TAG, "RPC not ready: $lastError")
        error(
            "EasyTier RPC 未就绪（127.0.0.1:$rpcPort）：${lastError ?: "timeout"}" +
                if (tail.isBlank()) "" else "\n$tail"
        )
    }

    private fun runCli(args: List<String>, retries: Int = 8): String {
        var lastError: Throwable? = null
        repeat(retries) { attempt ->
            try {
                return runCliOnce(args)
            } catch (t: Throwable) {
                lastError = t
                val msg = t.message.orEmpty()
                val retryable = msg.contains("manage client", ignoreCase = true) ||
                    msg.contains("peer manager", ignoreCase = true) ||
                    msg.contains("no running instances", ignoreCase = true) ||
                    msg.contains("Connection refused", ignoreCase = true) ||
                    msg.contains("failed to connect", ignoreCase = true) ||
                    msg.contains("io error", ignoreCase = true) ||
                    Regex("""失败\(\d+\)""").containsMatchIn(msg)
                if (!retryable || attempt == retries - 1) {
                    val tail = recentOutput().takeLast(400)
                    DiagEventLog.e(TAG, "cli failed: ${msg.take(200)}")
                    if (tail.isNotBlank() && msg.indexOf(tail.take(40)) < 0) {
                        error("${t.message}\n[easytier-core]\n$tail")
                    }
                    throw t
                }
                DiagEventLog.w(TAG, "cli retry ${attempt + 1}/$retries: ${msg.take(160)}")
                Thread.sleep(300L * (attempt + 1))
                ensureAlive()
            }
        }
        throw lastError ?: IllegalStateException("easytier-cli 失败")
    }

    private fun runCliOnce(args: List<String>): String {
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
            DiagEventLog.w(TAG, "peer parse fail: ${t.message} raw=${text.take(200)}")
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
                            DiagEventLog.i(TAG, text.take(300))
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
