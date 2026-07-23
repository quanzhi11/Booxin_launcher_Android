package com.booxin.launcher.core.launch

import android.content.Context
import com.booxin.launcher.core.java.InstalledJavaRuntime
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext

class GameProcessRunner(
    private val context: Context
) {

    private val _logs = MutableSharedFlow<String>(extraBufferCapacity = 512)
    val logs: SharedFlow<String> = _logs.asSharedFlow()

    @Volatile
    private var logcatTailer: LogcatTailer? = null

    @Volatile
    private var running = false

    val isRunning: Boolean
        get() = running

    suspend fun probeJava(java: InstalledJavaRuntime, env: Map<String, String> = emptyMap()): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (!NativeJvmLauncher.probeJvm()) {
                    error("无法加载 libjvm.so 或 JNI_CreateJavaVM")
                }
                "Java ${java.majorVersion} 运行时库就绪"
            }
        }

    suspend fun start(command: LaunchCommand, java: InstalledJavaRuntime): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            stop()
            running = true
            emit("==== Launch command ====")
            emit(command.summarize())
            emit("==== JVM start ====")

            JvmEnvironment.apply(context, java, command.env)
            if (!NativeJvmLauncher.chdir(command.workingDir.absolutePath)) {
                emit("警告: 无法切换工作目录到 ${command.workingDir.absolutePath}")
            }

            val argv = command.asProcessCommand().toMutableList()
            argv[0] = "java"

            val tailer = LogcatTailer { line -> emitBlocking(line) }
            logcatTailer = tailer
            tailer.start()

            val code = try {
                NativeJvmLauncher.launchJvm(argv.toTypedArray(), java.majorVersion)
            } finally {
                tailer.stop()
                logcatTailer = null
                running = false
            }

            emit("==== JVM exit: $code ====")
            code
        }.onFailure {
            running = false
            logcatTailer?.stop()
            logcatTailer = null
        }
    }

    fun stop() {
        logcatTailer?.stop()
        logcatTailer = null
        running = false
    }

    private fun emitBlocking(line: String) {
        _logs.tryEmit(line)
    }

    private suspend fun emit(line: String) {
        _logs.emit(line)
    }
}
