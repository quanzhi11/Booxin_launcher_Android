package com.booxin.launcher.core.multiplayer

import com.booxin.launcher.core.diag.DiagEventLog
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Local TCP proxy so Minecraft can reach an EasyTier port-forward that may not
 * accept until the remote Open-to-LAN world is up.
 *
 * Listens on [listenHost] (use 0.0.0.0 so LAN MOTD source = Wi‑Fi IP still hits us;
 * 127.0.0.1 alone is invisible to same-device discovery on many Android ROMs).
 */
class LocalTcpRelay(
    private val listenHost: String = "0.0.0.0",
    private val targetHost: String,
    private val targetPort: Int,
    private val preferredListenPort: Int = 0
) : AutoCloseable {
    private val running = AtomicBoolean(false)
    private var server: ServerSocket? = null
    private val pipes = ConcurrentHashMap<Long, Pair<Socket, Socket>>()
    private var acceptThread: Thread? = null
    private var nextId = 0L

    var localPort: Int = 0
        private set

    fun start(): Int {
        if (running.get()) return localPort
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(InetAddress.getByName(listenHost), preferredListenPort))
        server = ss
        localPort = ss.localPort
        running.set(true)
        acceptThread = thread(name = "booxin-tcp-relay-$localPort", isDaemon = true) {
            DiagEventLog.i(TAG, "relay listen $listenHost:$localPort -> $targetHost:$targetPort")
            while (running.get()) {
                val inbound = try {
                    ss.accept()
                } catch (_: Throwable) {
                    break
                }
                val id = synchronized(this) { ++nextId }
                thread(name = "booxin-tcp-relay-pipe-$id", isDaemon = true) {
                    handleClient(id, inbound)
                }
            }
        }
        return localPort
    }

    private fun handleClient(id: Long, inbound: Socket) {
        var outbound: Socket? = null
        try {
            inbound.tcpNoDelay = true
            outbound = connectTargetWithRetry()
            outbound.tcpNoDelay = true
            pipes[id] = inbound to outbound
            val a = pipe(inbound, outbound, "in->$id")
            val b = pipe(outbound, inbound, "out->$id")
            a.join()
            b.join()
        } catch (t: Throwable) {
            DiagEventLog.w(TAG, "relay client $id failed: ${t.message}")
        } finally {
            pipes.remove(id)
            runCatching { inbound.close() }
            runCatching { outbound?.close() }
        }
    }

    private fun connectTargetWithRetry(attempts: Int = 80, delayMs: Long = 500L): Socket {
        var last: Throwable? = null
        repeat(attempts) {
            try {
                val s = Socket()
                s.connect(InetSocketAddress(targetHost, targetPort), 1_200)
                return s
            } catch (t: Throwable) {
                last = t
                Thread.sleep(delayMs)
            }
        }
        throw IOException(
            "relay target $targetHost:$targetPort not ready: ${last?.message}",
            last
        )
    }

    private fun pipe(from: Socket, to: Socket, name: String): Thread =
        thread(name = "booxin-pipe-$name", isDaemon = true) {
            try {
                from.getInputStream().copyTo(to.getOutputStream())
            } catch (_: Throwable) {
            } finally {
                runCatching { from.shutdownInput() }
                runCatching { to.shutdownOutput() }
            }
        }

    override fun close() {
        running.set(false)
        runCatching { server?.close() }
        server = null
        pipes.values.forEach { (a, b) ->
            runCatching { a.close() }
            runCatching { b.close() }
        }
        pipes.clear()
        acceptThread = null
        DiagEventLog.i(TAG, "relay stopped localPort=$localPort")
    }

    companion object {
        private const val TAG = "LocalTcpRelay"
    }
}
