package com.booxin.launcher.core.download.modloader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.booxin.launcher.AppContainer
import com.booxin.launcher.R
import com.booxin.launcher.core.LauncherPaths
import com.booxin.launcher.core.launch.ToolJvmEnvironment
import com.booxin.launcher.core.launch.NativeJvmLauncher
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

/**
 * Run Forge processors in an isolated process.
 *
 * Job is passed via files (not large Intent extras) to avoid binder limits and
 * 退出码写文件并 UDP 发送，避免丢包。
 */
class ForgeProcessorService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand pid=${Process.myPid()}")
        if (intent == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        ensureChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val jobFile = intent.getStringExtra(EXTRA_JOB_FILE)?.let(::File)
        val exitFile = intent.getStringExtra(EXTRA_EXIT_FILE)?.let(::File)
        val legacyCommand = intent.getStringArrayExtra(EXTRA_COMMAND)
        val legacyJava = intent.getIntExtra(EXTRA_JAVA_MAJOR, 21)
        val legacyWorkingDir = intent.getStringExtra(EXTRA_WORKING_DIR)
            ?: LauncherPaths.rootDir.absolutePath
        val legacyJvmArgs = intent.getStringArrayExtra(EXTRA_JVM_ARGS)?.toList().orEmpty()

        Thread({
            var code = 1
            try {
                code = when {
                    jobFile != null && jobFile.isFile -> runJobFile(jobFile)
                    legacyCommand != null && legacyCommand.isNotEmpty() ->
                        runProcessor(legacyJava, legacyWorkingDir, legacyJvmArgs, legacyCommand)
                    else -> {
                        Log.e(TAG, "missing job file / command")
                        1
                    }
                }
            } catch (error: Exception) {
                Log.e(TAG, "processor thread failed", error)
                code = 1
            } finally {
                writeExitFile(exitFile, code)
                sendExitCode(code)
                stopSelf(startId)
                Process.killProcess(Process.myPid())
            }
        }, "forge-processor").start()
        return START_NOT_STICKY
    }

    private fun runJobFile(jobFile: File): Int {
        val lines = jobFile.readLines()
        if (lines.size < 4) {
            Log.e(TAG, "invalid job file: ${jobFile.absolutePath}")
            return 1
        }
        val workingDir = lines[0]
        val javaMajor = lines[1].toIntOrNull() ?: 21
        val sep = lines.indexOf("--")
        if (sep < 2 || sep >= lines.lastIndex) {
            Log.e(TAG, "job file missing command separator")
            return 1
        }
        val jvmArgs = lines.subList(2, sep)
        val command = lines.subList(sep + 1, lines.size).toTypedArray()
        return runProcessor(javaMajor, workingDir, jvmArgs, command)
    }

    private fun runProcessor(
        javaMajor: Int,
        workingDir: String,
        jvmArgs: List<String>,
        command: Array<String>
    ): Int {
        return try {
            val java = AppContainer.javaEnvironment.findInstalled(javaMajor)
                ?: error("Java $javaMajor 未安装")
            val tmpDir = File(LauncherPaths.rootDir, "cache/forge/tmp").also { it.mkdirs() }
            ToolJvmEnvironment.apply(this, java, tmpDir)
            if (!NativeJvmLauncher.chdir(workingDir)) {
                Log.w(TAG, "chdir failed: $workingDir")
            }
            val argv = buildList {
                add(java.javaBinary.absolutePath)
                addAll(jvmArgs)
                addAll(command)
            }
            Log.i(TAG, "launch main=${command.firstOrNull { !it.startsWith("-") && it.contains('.') } ?: "?"} args=${argv.size}")
            NativeJvmLauncher.launchToolJvm(argv.toTypedArray())
        } catch (error: Exception) {
            Log.e(TAG, "processor failed", error)
            1
        }
    }

    private fun writeExitFile(exitFile: File?, code: Int) {
        if (exitFile == null) return
        runCatching {
            exitFile.parentFile?.mkdirs()
            exitFile.writeText(code.toString())
        }.onFailure { Log.e(TAG, "write exit file failed", it) }
    }

    private fun sendExitCode(code: Int) {
        runCatching {
            DatagramSocket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", ForgeInstallSocketServer.PORT))
                val data = code.toString().toByteArray()
                // Retry a few times in case the listener binds slightly late.
                repeat(5) { attempt ->
                    socket.send(DatagramPacket(data, data.size))
                    if (attempt < 4) Thread.sleep(100)
                }
            }
        }.onFailure { error ->
            Log.e(TAG, "send exit code failed", error)
        }
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.forge_processor_fg_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.forge_processor_fg_title))
            .setContentText(getString(R.string.forge_processor_fg_text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "ForgeProcessorService"
        private const val CHANNEL_ID = "forge_processor"
        private const val NOTIFICATION_ID = 42
        const val EXTRA_COMMAND = "command"
        const val EXTRA_JAVA_MAJOR = "java"
        const val EXTRA_WORKING_DIR = "workingDir"
        const val EXTRA_JVM_ARGS = "jvmArgs"
        const val EXTRA_JOB_FILE = "jobFile"
        const val EXTRA_EXIT_FILE = "exitFile"
    }
}
