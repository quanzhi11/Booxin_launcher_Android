package com.booxin.launcher.core.launch

import android.content.Context
import com.booxin.launcher.core.java.InstalledJavaRuntime
import com.booxin.launcher.core.runtime.GameRuntimeBackends
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class GameProcessRunner(
    private val context: Context
) {

    private val _logs = MutableSharedFlow<String>(extraBufferCapacity = 4096)
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

    /**
     * @param applyEnvironment false 表示调用方已配好环境，不再重复 preload。
     */
    suspend fun start(
        command: LaunchCommand,
        java: InstalledJavaRuntime,
        applyEnvironment: Boolean = true
    ): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            stop()
            running = true
            emit("==== Launch command ====")
            emit(command.summarize())
            emit("==== JVM start ====")
            emit("clientJarHint=${command.jvmArgs.firstOrNull { it.startsWith("-Dminecraft.client.jar=") } ?: command.jvmArgs.firstOrNull { it.startsWith("-Dfabric.gameJarPath=") } ?: "MISSING"}")
            emit("mainClass=${command.mainClass}")
            emit("jvmArgs=${command.jvmArgs.size} classpathJars=${command.classpath.size}")

            val backend = GameRuntimeBackends.current()
            if (applyEnvironment) {
                backend.applyJvmEnvironment(context, java, command.env)
            }
            if (!NativeJvmLauncher.chdir(command.workingDir.absolutePath)) {
                emit("警告: 无法切换工作目录到 ${command.workingDir.absolutePath}")
            }

            val jvmCreated = AtomicBoolean(false)
            val gameProgress = AtomicBoolean(false)
            val tailer = LogcatTailer { line ->
                // Do NOT treat "JNI_CreateJavaVM starting" as created — vivo can stall there.
                if (!jvmCreated.get() && (
                        line.contains("JVM created", ignoreCase = true) ||
                            line.contains("Invoking main", ignoreCase = true) ||
                            line.contains("main class loaded", ignoreCase = true) ||
                            (line.contains("[jvm]", ignoreCase = true) &&
                                !line.contains("JNI_CreateJavaVM starting", ignoreCase = true))
                        )
                ) {
                    jvmCreated.set(true)
                }
                if (!gameProgress.get() && (
                        line.contains("Setting user", ignoreCase = true) ||
                            line.contains("Loading Minecraft", ignoreCase = true) ||
                            line.contains("LWJGL", ignoreCase = true) ||
                            line.contains("OpenGL", ignoreCase = true) ||
                            line.contains("Sound engine", ignoreCase = true) ||
                            line.contains("Reloading ResourceManager", ignoreCase = true) ||
                            line.contains("KnotClient", ignoreCase = true) ||
                            line.contains("FabricLoader", ignoreCase = true)
                        )
                ) {
                    gameProgress.set(true)
                    jvmCreated.set(true)
                }
                emitBlocking(line)
            }
            logcatTailer = tailer
            tailer.start()

            emit("正在创建 JVM（Forge/整合包首次可能要 1–3 分钟，请勿以为卡死）…")
            val tickSec = AtomicInteger(0)
            val heartbeat: Job = launch {
                while (isActive) {
                    delay(10_000L)
                    val sec = tickSec.addAndGet(10)
                    // launch() blocks until the game exits — do not keep saying "创建虚拟机".
                    when {
                        gameProgress.get() ->
                            emit("游戏仍在加载/运行中…已等待 ${sec}s（主菜单出现后遮罩会自动关闭）")
                        jvmCreated.get() ->
                            emit("游戏仍在加载/运行中…已等待 ${sec}s（JVM 已创建，等待主菜单）")
                        sec >= 90 ->
                            // Avoid forever "创建虚拟机" spam that resets UI keep-alive semantics.
                            emit("游戏仍在加载/运行中…已等待 ${sec}s（若已见主菜单可点「强制进入游戏」）")
                        else ->
                            emit("JVM 仍在启动中…已等待 ${sec}s（创建虚拟机 / 加载主类）")
                    }
                }
            }

            val code = try {
                val result = backend.launch(command, java.majorVersion)
                emit("==== JVM returned: $result ====")
                result
            } catch (t: Throwable) {
                emit("==== JVM native exception: ${t.javaClass.simpleName}: ${t.message} ====")
                throw t
            } finally {
                heartbeat.cancel()
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
            emitBlocking("==== JVM start failed: ${it.message} ====")
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
