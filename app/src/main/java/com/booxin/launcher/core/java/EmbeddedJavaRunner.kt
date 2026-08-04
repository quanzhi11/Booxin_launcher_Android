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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Run a short-lived tool JVM (Forge install processors).
 *
 * Prefers isolated `:forge` process; falls back to in-process tool JVM if the
 * service never comes up (common MIUI / FGS edge cases) so install does not
 * sit forever on “重命名 MC jar”.
 */
object EmbeddedJavaRunner {
    private const val TAG = "EmbeddedJavaRunner"
    private const val PROCESS_START_GRACE_MS = 8_000L

    /**
     * @param command `-cp`, classpath, mainClass, then processor args
     */
    fun run(
        java: InstalledJavaRuntime,
        workingDir: File,
        command: List<String>,
        extraJvmArgs: List<String> = emptyList()
    ): Int {
        val chain = listOf(java.majorVersion, 8, 17, 11, 21).distinct()
        for (major in chain) {
            val runtime = AppContainer.javaEnvironment.findInstalled(major) ?: continue
            val code = runOnce(runtime, workingDir, command, extraJvmArgs)
            Log.i(TAG, "java=$major exit=$code")
            if (code == 0) return 0
        }
        return 1
    }

    private fun runOnce(
        java: InstalledJavaRuntime,
        workingDir: File,
        command: List<String>,
        extraJvmArgs: List<String>
    ): Int {
        killStaleForgeProcesses()
        waitForSiblingProcesses()

        val jobDir = File(LauncherPaths.rootDir, "cache/forge/jobs").also { it.mkdirs() }
        val jobId = System.currentTimeMillis().toString()
        val commandFile = File(jobDir, "cmd-$jobId.txt")
        val exitFile = File(jobDir, "exit-$jobId.txt")
        exitFile.delete()
        commandFile.writeText(
            buildString {
                appendLine(workingDir.absolutePath)
                appendLine(java.majorVersion.toString())
                (defaultJvmArgs() + extraJvmArgs).forEach { appendLine(it) }
                appendLine("--")
                command.forEach { appendLine(it) }
            }
        )

        val exitCode = AtomicInteger(1)
        val ready = CountDownLatch(1)
        val done = CountDownLatch(1)
        val socketAlive = AtomicBoolean(false)
        val receiver = Thread({
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket(ForgeInstallSocketServer.PORT, InetAddress.getByName("127.0.0.1"))
                socket.soTimeout = 2_000
                socketAlive.set(true)
                ready.countDown()
                val buffer = ByteArray(64)
                val packet = DatagramPacket(buffer, buffer.size)
                val deadline = System.nanoTime() +
                    TimeUnit.MINUTES.toNanos(ForgeInstallSocketServer.TIMEOUT_MINUTES)
                while (System.nanoTime() < deadline && !exitFile.isFile) {
                    try {
                        socket.receive(packet)
                        exitCode.set(String(packet.data, 0, packet.length).trim().toIntOrNull() ?: 1)
                        break
                    } catch (_: java.net.SocketTimeoutException) {
                        if (exitFile.isFile) break
                    }
                }
            } catch (error: Exception) {
                Log.e(TAG, "await exit code failed", error)
            } finally {
                socketAlive.set(false)
                runCatching { socket?.close() }
                if (exitFile.isFile) {
                    exitCode.set(exitFile.readText().trim().toIntOrNull() ?: exitCode.get())
                }
                done.countDown()
            }
        }, "forge-exit-udp")
        receiver.isDaemon = true
        receiver.start()
        if (!ready.await(3, TimeUnit.SECONDS) || !socketAlive.get()) {
            Log.e(TAG, "UDP listener failed to bind — using in-process fallback")
            return runInProcess(java, workingDir, command, extraJvmArgs).also {
                commandFile.delete()
                exitFile.delete()
            }
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
            Log.e(TAG, "startForegroundService failed — in-process fallback", error)
            runCatching { receiver.interrupt() }
            return runInProcess(java, workingDir, command, extraJvmArgs).also {
                commandFile.delete()
                exitFile.delete()
            }
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
            Log.e(TAG, ":forge process did not start within ${PROCESS_START_GRACE_MS}ms — in-process fallback")
            killStaleForgeProcesses()
            runCatching { receiver.interrupt() }
            return runInProcess(java, workingDir, command, extraJvmArgs).also {
                commandFile.delete()
                exitFile.delete()
            }
        }

        // Wait until UDP/exit file arrives, but bail if :forge vanished without reporting.
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
                // Give UDP/exit-file a short grace after process death.
                if (System.currentTimeMillis() - vanishedSince > 3_000L) {
                    if (exitFile.isFile) {
                        exitCode.set(exitFile.readText().trim().toIntOrNull() ?: 1)
                    } else {
                        Log.e(TAG, ":forge exited without exit code — treating as failure")
                        exitCode.set(1)
                    }
                    runCatching { receiver.interrupt() }
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
            runCatching { receiver.interrupt() }
        } else if (exitFile.isFile) {
            exitCode.set(exitFile.readText().trim().toIntOrNull() ?: exitCode.get())
        }
        commandFile.delete()
        exitFile.delete()
        return exitCode.get()
    }

    private fun runInProcess(
        java: InstalledJavaRuntime,
        workingDir: File,
        command: List<String>,
        extraJvmArgs: List<String>
    ): Int {
        return try {
            Log.w(TAG, "running processor in-process java=${java.majorVersion}")
            val tmpDir = File(LauncherPaths.rootDir, "cache/forge/tmp").also { it.mkdirs() }
            ToolJvmEnvironment.apply(BooxinApp.getAppContext(), java, tmpDir)
            if (!NativeJvmLauncher.chdir(workingDir.absolutePath)) {
                Log.w(TAG, "chdir failed: ${workingDir.absolutePath}")
            }
            val argv = buildList {
                add(java.javaBinary.absolutePath)
                addAll(defaultJvmArgs())
                addAll(extraJvmArgs)
                addAll(command)
            }
            NativeJvmLauncher.launchToolJvm(argv.toTypedArray())
        } catch (error: Exception) {
            Log.e(TAG, "in-process processor failed", error)
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
     * Clear leftover :forge quickly. Do not block ~60s on :game — that made Forge
     * install look stuck on the first processor while a zombie game process lingered.
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
