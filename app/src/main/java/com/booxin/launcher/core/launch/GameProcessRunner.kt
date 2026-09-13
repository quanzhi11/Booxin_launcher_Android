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
            emit(
                "clientJarHint=${command.jvmArgs.firstOrNull { it.startsWith("-Dminecraft.client.jar=") }
                    ?: command.jvmArgs.firstOrNull { it.startsWith("-Dfabric.gameJarPath=") }
                    ?: "MISSING"}"
            )
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
            val fatalSeen = AtomicBoolean(false)
            val tailer = LogcatTailer { line ->
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
                if (!fatalSeen.get() && (
                        line.contains("Failed to load required shader programs", ignoreCase = true) ||
                            line.contains("Game crashed!", ignoreCase = true) ||
                            line.contains("#@!@# Game crashed", ignoreCase = true)
                        )
                ) {
                    fatalSeen.set(true)
                    emitBlocking("检测到游戏致命错误（着色器/崩溃）— 无需再空等")
                }
                emitBlocking(line)
            }
            logcatTailer = tailer
            tailer.start()

            emit("正在创建 JVM…")
            val tickSec = AtomicInteger(0)
            val heartbeat: Job = launch {
                while (isActive) {
                    delay(10_000L)
                    val sec = tickSec.addAndGet(10)
                    if (fatalSeen.get()) {
                        emitBlocking("游戏已崩溃（约 ${sec}s），请返回重试或查看日志")
                        break
                    }
                    if (!jvmCreated.get() || !gameProgress.get()) {
                        runCatching {
                            val file = GameLaunchLogBus.latestLogFile() ?: return@runCatching
                            if (!file.isFile) return@runCatching
                            val tail = file.readText().takeLast(8000)
                            if (!jvmCreated.get() && (
                                    tail.contains("JVM created", ignoreCase = true) ||
                                        tail.contains("Invoking main", ignoreCase = true) ||
                                        tail.contains("main class loaded", ignoreCase = true)
                                    )
                            ) {
                                jvmCreated.set(true)
                            }
                            if (!gameProgress.get() && (
                                    tail.contains("Invoking main", ignoreCase = true) ||
                                        tail.contains("[jvm]", ignoreCase = true)
                                    )
                            ) {
                                gameProgress.set(true)
                                jvmCreated.set(true)
                            }
                            if (!fatalSeen.get() && (
                                    tail.contains("Failed to load required shader programs", ignoreCase = true) ||
                                        tail.contains("Game crashed!", ignoreCase = true)
                                    )
                            ) {
                                fatalSeen.set(true)
                            }
                        }
                    }
                    when {
                        fatalSeen.get() ->
                            emitBlocking("游戏已崩溃（约 ${sec}s），请返回重试")
                        gameProgress.get() && sec >= 40 ->
                            emitBlocking("仍在加载…${sec}s（可玩小恐龙；一直黑屏请返回）")
                        gameProgress.get() ->
                            emitBlocking("游戏加载中…${sec}s")
                        jvmCreated.get() ->
                            emitBlocking("JVM 已创建，加载中…${sec}s")
                        sec >= 60 ->
                            emitBlocking("JVM 启动较慢…${sec}s（可强制进入或返回）")
                        else ->
                            emitBlocking("正在创建 JVM…${sec}s")
                    }
                    if (fatalSeen.get()) break
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
