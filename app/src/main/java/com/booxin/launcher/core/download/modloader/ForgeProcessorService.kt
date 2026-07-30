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
import com.booxin.launcher.core.launch.FclJavaRuntimeSetup
import com.booxin.launcher.core.launch.NativeJvmLauncher
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

/**
 * FCL ProcessService equivalent: run Forge processors in an isolated process.
 */
class ForgeProcessorService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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

        val command = intent.getStringArrayExtra(EXTRA_COMMAND)
        val javaMajor = intent.getIntExtra(EXTRA_JAVA_MAJOR, 21)
        val workingDir = intent.getStringExtra(EXTRA_WORKING_DIR)
            ?: LauncherPaths.rootDir.absolutePath
        val jvmArgs = intent.getStringArrayExtra(EXTRA_JVM_ARGS)?.toList().orEmpty()
        if (command == null || command.isEmpty()) {
            sendExitCode(1)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        Thread {
            val code = runProcessor(javaMajor, workingDir, jvmArgs, command)
            sendExitCode(code)
            stopSelf(startId)
            Process.killProcess(Process.myPid())
        }.start()
        return START_NOT_STICKY
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
            FclJavaRuntimeSetup.apply(this, java, tmpDir)
            if (!NativeJvmLauncher.chdir(workingDir)) {
                Log.w(TAG, "chdir failed: $workingDir")
            }
            val argv = buildList {
                add(java.javaBinary.absolutePath)
                addAll(jvmArgs)
                addAll(command)
            }
            Log.i(TAG, "launch ${argv.joinToString(" ")}")
            NativeJvmLauncher.launchToolJvm(argv.toTypedArray())
        } catch (error: Exception) {
            Log.e(TAG, "processor failed", error)
            1
        }
    }

    private fun sendExitCode(code: Int) {
        runCatching {
            DatagramSocket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", ForgeInstallSocketServer.PORT))
                val data = code.toString().toByteArray()
                socket.send(DatagramPacket(data, data.size))
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
    }
}
