package com.booxin.launcher.core.launch

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tails **this process only**.
 *
 * Default: a few Booxin tags (avoids Binder flood / touch lag).
 * When [RealtimeLaunchLog] is enabled: full pid logcat with threadtime for diagnostics.
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
            val verbose = RealtimeLaunchLog.isEnabled()
            val cmd = if (verbose) {
                // 仅在开启实时日志时拉全量。
                listOf(
                    "logcat",
                    "--pid=$pid",
                    "-v", "threadtime",
                    "-T", "1"
                )
            } else {
                listOf(
                    "logcat",
                    "--pid=$pid",
                    "-v", "brief",
                    "-T", "1",
                    "BooxinJvm:I",
                    "BooxinInput:I",
                    "BooxinBridge:I",
                    "BooxinEGL:I",
                    "BooxinSdlGl:I",
                    "MobileGluesConfig:I",
                    "LaunchActivity:I",
                    "GameLaunchService:I",
                    "AndroidRuntime:E",
                    "*:S"
                )
            }
            onLine(
                if (verbose) "logcat: realtime FULL pid=$pid"
                else "logcat: filtered tags pid=$pid"
            )
            val started = ProcessBuilder(cmd).redirectErrorStream(true).start()
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
