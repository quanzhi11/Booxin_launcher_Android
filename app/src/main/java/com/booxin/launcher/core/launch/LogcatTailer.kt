package com.booxin.launcher.core.launch

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tails **this process only**. Never mirror the whole-device logcat — that floods
 * Binder + the LaunchActivity TextView and stalls touch (~1s lag / heat).
 */
class LogcatTailer(
    private val onLine: (String) -> Unit
) {
    private var proc: java.lang.Process? = null
    private var thread: Thread? = null
    private val running = AtomicBoolean(false)

    fun start() {
        if (!running.compareAndSet(false, true)) return
        runCatching {
            val pid = android.os.Process.myPid()
            // Own PID + our tags only. Minecraft INFO still arrives via BooxinJvm.
            val started = ProcessBuilder(
                "logcat",
                "--pid=$pid",
                "-v", "brief",
                "-T", "1",
                "BooxinJvm:I",
                "BooxinInput:I",
                "LaunchActivity:I",
                "GameLaunchService:I",
                "*:S"
            ).redirectErrorStream(true).start()
            proc = started
            thread = Thread {
                BufferedReader(InputStreamReader(started.inputStream)).use { reader ->
                    while (running.get()) {
                        val line = reader.readLine() ?: break
                        onLine(line)
                    }
                }
            }.apply {
                name = "booxin-logcat"
                isDaemon = true
                start()
            }
        }.onFailure {
            running.set(false)
            onLine("logcat 不可用: ${it.message}")
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { proc?.destroy() }
        thread?.interrupt()
        proc = null
        thread = null
    }
}
