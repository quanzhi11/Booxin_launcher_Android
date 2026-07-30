package com.booxin.launcher.core.java

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat
import com.booxin.launcher.AppContainer
import com.booxin.launcher.BooxinApp
import com.booxin.launcher.core.download.modloader.ForgeInstallSocketServer
import com.booxin.launcher.core.download.modloader.ForgeProcessorService
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * FCL ForgeNewInstallTask.runJVMProcess equivalent.
 */
object EmbeddedJavaRunner {
    private const val TAG = "EmbeddedJavaRunner"

    /**
     * @param command FCL format: `-cp`, classpath, mainClass, processor args…
     */
    fun run(
        java: InstalledJavaRuntime,
        workingDir: File,
        command: List<String>,
        extraJvmArgs: List<String> = emptyList()
    ): Int {
        val chain = listOf(java.majorVersion, 8, 17, 11, 21).distinct()
        for (major in chain) {
            val runtime = AppContainer.javaEnvironment.findInstalled(major)
            if (runtime == null) continue
            val code = runOnce(runtime, workingDir, command, extraJvmArgs)
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
        waitForOtherProcesses()
        val exitCode = AtomicInteger(1)
        val latch = CountDownLatch(1)
        val receiver = Thread {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket(ForgeInstallSocketServer.PORT, InetAddress.getByName("127.0.0.1"))
                val buffer = ByteArray(64)
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                exitCode.set(String(packet.data, 0, packet.length).trim().toIntOrNull() ?: 1)
            } catch (error: Exception) {
                Log.e(TAG, "await exit code failed", error)
            } finally {
                runCatching { socket?.close() }
                latch.countDown()
            }
        }
        receiver.isDaemon = true
        receiver.start()

        val context = BooxinApp.getAppContext()
        val intent = android.content.Intent(context, ForgeProcessorService::class.java).apply {
            putExtra(ForgeProcessorService.EXTRA_COMMAND, command.toTypedArray())
            putExtra(ForgeProcessorService.EXTRA_JAVA_MAJOR, java.majorVersion)
            putExtra(ForgeProcessorService.EXTRA_WORKING_DIR, workingDir.absolutePath)
            putExtra(ForgeProcessorService.EXTRA_JVM_ARGS, extraJvmArgs.toTypedArray())
        }
        Log.i(TAG, "start ForgeProcessorService java=${java.majorVersion}")
        ContextCompat.startForegroundService(context, intent)

        if (!latch.await(ForgeInstallSocketServer.TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
            Log.e(TAG, "processor timed out")
            receiver.interrupt()
        }
        return exitCode.get()
    }

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

    private fun waitForOtherProcesses() {
        val context = BooxinApp.getAppContext()
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        repeat(120) {
            val count = am.runningAppProcesses
                ?.count { it.processName.startsWith(context.packageName) }
                ?: 1
            if (count <= 1) return
            Thread.sleep(500)
        }
    }
}
