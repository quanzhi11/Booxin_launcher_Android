package com.booxin.launcher.core.java

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat
import com.booxin.launcher.AppContainer
import com.booxin.launcher.BooxinApp
import com.booxin.launcher.core.LauncherPaths
import com.booxin.launcher.core.download.modloader.ForgeInstallSocketServer
import com.booxin.launcher.core.download.modloader.ForgeProcessorService
import com.booxin.launcher.core.launch.ToolJvmEnvironment
import com.booxin.launcher.core.launch.NativeJvmLauncher
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.StandardSocketOptions
import java.nio.channels.DatagramChannel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Forge 安装等短生命周期工具 JVM（优先 :forge 进程）。 */
object EmbeddedJavaRunner {
    private const val TAG = "EmbeddedJavaRunner"
    private const val PROCESS_START_GRACE_MS = 8_000L
    /** After :forge disappears, wait this long for exit-file / UDP before failing. */
    private const val VANISH_GRACE_MS = 15_000L
    private const val UDP_BIND_RETRIES = 8

    /**
     * Once HotSpot has been created in the UI process, further in-process
     * CreateJavaVM calls return JNI_EEXIST (-5). Prefer :forge only after that.
     */
    private val hotSpotInUiProcess = AtomicBoolean(false)

    /**
     * @param command `-cp`, classpath, mainClass, then processor args
     * @param logFile optional path for BOOXIN_LAUNCH_LOG (processor stdout/stderr)
     */
    fun run(
        java: InstalledJavaRuntime,
        workingDir: File,
        command: List<String>,
        extraJvmArgs: List<String> = emptyList(),
        logFile: File? = null
    ): Int {
        val chain = listOf(java.majorVersion, 8, 17, 11, 21).distinct()
        for (major in chain) {
            val runtime = AppContainer.javaEnvironment.findInstalled(major) ?: continue
            val code = runOnce(runtime, workingDir, command, extraJvmArgs, logFile)
            Log.i(TAG, "java=$major exit=$code")
            if (code == 0) return 0
        }
        return 1
    }

    private fun runOnce(
        java: InstalledJavaRuntime,
        workingDir: File,
        command: List<String>,
        extraJvmArgs: List<String>,
        logFile: File?
    ): Int {
        killStaleForgeProcesses()
        waitForSiblingProcesses()

        val jobDir = File(LauncherPaths.rootDir, "cache/forge/jobs").also { it.mkdirs() }
        val jobId = System.currentTimeMillis().toString()
        val commandFile = File(jobDir, "cmd-$jobId.txt")
        val exitFile = File(jobDir, "exit-$jobId.txt")
        exitFile.delete()
        val resolvedLog = logFile ?: File(jobDir, "log-$jobId.txt")
        runCatching {
            resolvedLog.parentFile?.mkdirs()
            if (!resolvedLog.exists()) resolvedLog.writeText("")
        }
        commandFile.writeText(
            buildString {
                appendLine(workingDir.absolutePath)
                appendLine(java.majorVersion.toString())
                appendLine(resolvedLog.absolutePath)
                (defaultJvmArgs() + extraJvmArgs).forEach { appendLine(it) }
                appendLine("--")
                command.forEach { appendLine(it) }
            }
        )

        val exitCode = AtomicInteger(1)
        val ready = CountDownLatch(1)
        val done = CountDownLatch(1)
        val socketAlive = AtomicBoolean(false)
        val socketRef = arrayOfNulls<DatagramSocket>(1)
        val receiver = Thread({
            var socket: DatagramSocket? = null
            try {
                socket = openReuseUdp(ForgeInstallSocketServer.PORT)
                socketRef[0] = socket
                socket.soTimeout = 2_000
                socketAlive.set(true)
                ready.countDown()
                val buffer = ByteArray(64)
                val packet = DatagramPacket(buffer, buffer.size)
                val deadline = System.nanoTime() +
                    TimeUnit.MINUTES.toNanos(ForgeInstallSocketServer.TIMEOUT_MINUTES)
                while (System.nanoTime() < deadline && !exitFile.isFile && !Thread.interrupted()) {
                    try {
                        socket.receive(packet)
                        exitCode.set(String(packet.data, 0, packet.length).trim().toIntOrNull() ?: 1)
                        break
                    } catch (_: java.net.SocketTimeoutException) {
                        if (exitFile.isFile) break
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            } catch (error: Exception) {
                Log.e(TAG, "await exit code failed", error)
            } finally {
                socketAlive.set(false)
                runCatching { socket?.close() }
                socketRef[0] = null
                if (exitFile.isFile) {
                    exitCode.set(exitFile.readText().trim().toIntOrNull() ?: exitCode.get())
                }
                done.countDown()
            }
        }, "forge-exit-udp")
        receiver.isDaemon = true
        receiver.start()

        fun releaseUdp() {
            runCatching { socketRef[0]?.close() }
            runCatching { receiver.interrupt() }
            // Wait until the port is actually freed before the next processor binds.
            done.await(2, TimeUnit.SECONDS)
            runCatching { receiver.join(1_500) }
        }

        if (!ready.await(3, TimeUnit.SECONDS) || !socketAlive.get()) {
            Log.e(TAG, "UDP listener failed to bind")
            releaseUdp()
            val fallback = tryInProcessFallback(java, workingDir, command, extraJvmArgs, resolvedLog)
            commandFile.delete()
            exitFile.delete()
            return fallback
        }

        val context = BooxinApp.getAppContext()
        val intent = android.content.Intent(context, ForgeProcessorService::class.java).apply {
            putExtra(ForgeProcessorService.EXTRA_JOB_FILE, commandFile.absolutePath)
            putExtra(ForgeProcessorService.EXTRA_EXIT_FILE, exitFile.absolutePath)
        }
        Log.i(TAG, "start ForgeProcessorService java=${java.majorVersion} job=$jobId")
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (error: Exception) {
            Log.e(TAG, "startForegroundService failed", error)
            releaseUdp()
            val fallback = tryInProcessFallback(java, workingDir, command, extraJvmArgs, resolvedLog)
            commandFile.delete()
            exitFile.delete()
            return fallback
        }

        val forgeName = "${context.packageName}:forge"
        val startedAt = System.currentTimeMillis()
        var sawForge = false
        while (System.currentTimeMillis() - startedAt < PROCESS_START_GRACE_MS) {
            if (exitFile.isFile || done.count == 0L) break
            if (isProcessRunning(forgeName)) {
                sawForge = true
                break
            }
            Thread.sleep(200)
        }
        if (!sawForge && !exitFile.isFile && done.count != 0L) {
            Log.e(TAG, ":forge process did not start within ${PROCESS_START_GRACE_MS}ms")
            killStaleForgeProcesses()
            releaseUdp()
            val fallback = tryInProcessFallback(java, workingDir, command, extraJvmArgs, resolvedLog)
            commandFile.delete()
            exitFile.delete()
            return fallback
        }

        // Wait for UDP / exit-file. Do NOT treat a short process lifetime as failure —
        // ConsoleTool / fart often finish in <2s; ActivityManager can also flap.
        val deadline = System.nanoTime() +
            TimeUnit.MINUTES.toNanos(ForgeInstallSocketServer.TIMEOUT_MINUTES)
        var vanishedSince = 0L
        while (done.count != 0L && System.nanoTime() < deadline) {
            if (exitFile.isFile) {
                exitCode.set(exitFile.readText().trim().toIntOrNull() ?: exitCode.get())
                break
            }
            val running = isProcessRunning(forgeName)
            if (!running && sawForge) {
                if (vanishedSince == 0L) vanishedSince = System.currentTimeMillis()
                if (System.currentTimeMillis() - vanishedSince > VANISH_GRACE_MS) {
                    if (exitFile.isFile) {
                        exitCode.set(exitFile.readText().trim().toIntOrNull() ?: 1)
                    } else {
                        Log.e(TAG, ":forge exited without exit code after ${VANISH_GRACE_MS}ms")
                        exitCode.set(1)
                    }
                    break
                }
            } else {
                vanishedSince = 0L
            }
            done.await(500, TimeUnit.MILLISECONDS)
        }
        if (done.count != 0L && !exitFile.isFile && System.nanoTime() >= deadline) {
            Log.e(TAG, "processor timed out")
            killStaleForgeProcesses()
            exitCode.set(1)
        } else if (exitFile.isFile) {
            exitCode.set(exitFile.readText().trim().toIntOrNull() ?: exitCode.get())
        }
        releaseUdp()
        val code = exitCode.get()
        // Keep exit file briefly for debugging if failed; always drop cmd.
        commandFile.delete()
        if (code == 0) exitFile.delete()
        return code
    }

    private fun tryInProcessFallback(
        java: InstalledJavaRuntime,
        workingDir: File,
        command: List<String>,
        extraJvmArgs: List<String>,
        logFile: File?
    ): Int {
        if (hotSpotInUiProcess.get()) {
            Log.e(TAG, "skip in-process fallback — HotSpot already in UI process (JNI_EEXIST)")
            // Last resort: retry :forge once more after freeing UDP / killing stale.
            killStaleForgeProcesses()
            Thread.sleep(400)
            return retryForgeOnly(java, workingDir, command, extraJvmArgs, logFile)
        }
        return runInProcess(java, workingDir, command, extraJvmArgs, logFile)
    }

    /**
     * One more :forge attempt without falling back to in-process again.
     * Used when UI-process HotSpot already exists.
     */
    private fun retryForgeOnly(
        java: InstalledJavaRuntime,
        workingDir: File,
        command: List<String>,
        extraJvmArgs: List<String>,
        logFile: File?
    ): Int {
        val jobDir = File(LauncherPaths.rootDir, "cache/forge/jobs").also { it.mkdirs() }
        val jobId = "retry-${System.currentTimeMillis()}"
        val commandFile = File(jobDir, "cmd-$jobId.txt")
        val exitFile = File(jobDir, "exit-$jobId.txt")
        exitFile.delete()
        val resolvedLog = logFile ?: File(jobDir, "log-$jobId.txt")
        commandFile.writeText(
            buildString {
                appendLine(workingDir.absolutePath)
                appendLine(java.majorVersion.toString())
                appendLine(resolvedLog.absolutePath)
                (defaultJvmArgs() + extraJvmArgs).forEach { appendLine(it) }
                appendLine("--")
                command.forEach { appendLine(it) }
            }
        )
        val exitCode = AtomicInteger(1)
        val ready = CountDownLatch(1)
        val done = CountDownLatch(1)
        val socketAlive = AtomicBoolean(false)
        val socketRef = arrayOfNulls<DatagramSocket>(1)
        val receiver = Thread({
            var socket: DatagramSocket? = null
            try {
                socket = openReuseUdp(ForgeInstallSocketServer.PORT)
                socketRef[0] = socket
                socket.soTimeout = 2_000
                socketAlive.set(true)
                ready.countDown()
                val buffer = ByteArray(64)
                val packet = DatagramPacket(buffer, buffer.size)
                val deadline = System.nanoTime() +
                    TimeUnit.MINUTES.toNanos(ForgeInstallSocketServer.TIMEOUT_MINUTES)
                while (System.nanoTime() < deadline && !exitFile.isFile && !Thread.interrupted()) {
                    try {
                        socket.receive(packet)
                        exitCode.set(String(packet.data, 0, packet.length).trim().toIntOrNull() ?: 1)
                        break
                    } catch (_: java.net.SocketTimeoutException) {
                        if (exitFile.isFile) break
                    }
                }
            } catch (error: Exception) {
                Log.e(TAG, "retry UDP failed", error)
            } finally {
                socketAlive.set(false)
                runCatching { socket?.close() }
                if (exitFile.isFile) {
                    exitCode.set(exitFile.readText().trim().toIntOrNull() ?: exitCode.get())
                }
                done.countDown()
            }
        }, "forge-exit-udp-retry")
        receiver.isDaemon = true
        receiver.start()
        if (!ready.await(3, TimeUnit.SECONDS) || !socketAlive.get()) {
            runCatching { socketRef[0]?.close() }
            runCatching { receiver.interrupt() }
            done.await(2, TimeUnit.SECONDS)
            commandFile.delete()
            return 1
        }
        val context = BooxinApp.getAppContext()
        val intent = android.content.Intent(context, ForgeProcessorService::class.java).apply {
            putExtra(ForgeProcessorService.EXTRA_JOB_FILE, commandFile.absolutePath)
            putExtra(ForgeProcessorService.EXTRA_EXIT_FILE, exitFile.absolutePath)
        }
        Log.i(TAG, "retry ForgeProcessorService java=${java.majorVersion} job=$jobId")
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (error: Exception) {
            Log.e(TAG, "retry startForegroundService failed", error)
            runCatching { socketRef[0]?.close() }
            runCatching { receiver.interrupt() }
            done.await(2, TimeUnit.SECONDS)
            commandFile.delete()
            return 1
        }
        if (!done.await(ForgeInstallSocketServer.TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
            Log.e(TAG, "retry processor timed out")
            killStaleForgeProcesses()
            exitCode.set(1)
        } else if (exitFile.isFile) {
            exitCode.set(exitFile.readText().trim().toIntOrNull() ?: exitCode.get())
        }
        runCatching { socketRef[0]?.close() }
        runCatching { receiver.interrupt() }
        done.await(2, TimeUnit.SECONDS)
        commandFile.delete()
        if (exitCode.get() == 0) exitFile.delete()
        return exitCode.get()
    }

    private fun openReuseUdp(port: Int): DatagramSocket {
        var last: Exception? = null
        repeat(UDP_BIND_RETRIES) { attempt ->
            try {
                // NIO channel so SO_REUSEADDR works reliably on Android before bind.
                val channel = DatagramChannel.open()
                channel.setOption(StandardSocketOptions.SO_REUSEADDR, true)
                channel.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
                val socket = channel.socket()
                socket.soTimeout = 2_000
                if (attempt > 0) Log.i(TAG, "UDP bind ok on retry $attempt")
                return socket
            } catch (error: Exception) {
                last = error
                Log.w(TAG, "UDP bind attempt ${attempt + 1}/$UDP_BIND_RETRIES failed: ${error.message}")
                Thread.sleep(150L * (attempt + 1))
            }
        }
        throw last ?: IllegalStateException("UDP bind failed")
    }

    private fun runInProcess(
        java: InstalledJavaRuntime,
        workingDir: File,
        command: List<String>,
        extraJvmArgs: List<String>,
        logFile: File?
    ): Int {
        return try {
            Log.w(TAG, "running processor in-process java=${java.majorVersion}")
            val tmpDir = File(LauncherPaths.rootDir, "cache/forge/tmp").also { it.mkdirs() }
            ToolJvmEnvironment.apply(BooxinApp.getAppContext(), java, tmpDir, logFile)
            if (!NativeJvmLauncher.chdir(workingDir.absolutePath)) {
                Log.w(TAG, "chdir failed: ${workingDir.absolutePath}")
            }
            val argv = buildList {
                add(java.javaBinary.absolutePath)
                addAll(defaultJvmArgs())
                addAll(extraJvmArgs)
                addAll(command)
            }
            val code = NativeJvmLauncher.launchToolJvm(argv.toTypedArray())
            // Any CreateJavaVM attempt (even -5) means we must not try again in-UI.
            hotSpotInUiProcess.set(true)
            code
        } catch (error: Exception) {
            Log.e(TAG, "in-process processor failed", error)
            hotSpotInUiProcess.set(true)
            1
        }
    }

    private fun defaultJvmArgs(): List<String> = listOf(
        "-Xms256M",
        "-Xmx2048M",
        "-Djava.awt.headless=true",
        "-Dfile.encoding=UTF-8"
    )

    private fun killStaleForgeProcesses() {
        val context = BooxinApp.getAppContext()
        val forgeProcess = "${context.packageName}:forge"
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.runningAppProcesses?.forEach { info ->
            if (info.processName == forgeProcess) {
                Log.w(TAG, "kill stale forge process pid=${info.pid}")
                Process.killProcess(info.pid)
            }
        }
        Thread.sleep(300)
    }

    /**
     * 清残留 :forge，不要卡在 :game 上。
     */
    private fun waitForSiblingProcesses() {
        killStaleForgeProcesses()
        val context = BooxinApp.getAppContext()
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val gameName = "${context.packageName}:game"
        val forgeName = "${context.packageName}:forge"
        // Short grace only: processors run in :forge and do not need :game gone.
        repeat(10) {
            val procs = am.runningAppProcesses.orEmpty()
            val forgeBusy = procs.any { it.processName == forgeName }
            if (!forgeBusy) {
                val gameBusy = procs.any { it.processName == gameName }
                if (gameBusy) {
                    Log.w(TAG, ":game still running — continuing Forge processor anyway")
                }
                return
            }
            Thread.sleep(200)
        }
        Log.w(TAG, ":forge still present after grace — force kill and continue")
        killStaleForgeProcesses()
    }

    private fun isProcessRunning(processName: String): Boolean {
        val context = BooxinApp.getAppContext()
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.runningAppProcesses.orEmpty().any { it.processName == processName }
    }
}
