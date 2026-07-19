package com.booxin.launcher.core.launch

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean

class LogcatTailer(
    private val onLine: (String) -> Unit
) {
    private var process: Process? = null
    private var thread: Thread? = null
    private val running = AtomicBoolean(false)

    fun start() {
        if (!running.compareAndSet(false, true)) return
        runCatching {
            ProcessBuilder("logcat", "-c").redirectErrorStream(true).start()?.waitFor()
            val proc = ProcessBuilder("logcat", "-v", "brief", "-T", "1")
                .redirectErrorStream(true)
                .start()
            process = proc
            thread = Thread {
                BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
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
        runCatching { process?.destroy() }
        thread?.interrupt()
        process = null
        thread = null
    }
}
