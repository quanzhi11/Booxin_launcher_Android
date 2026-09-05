package com.booxin.launcher.core.launch

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import com.booxin.launcher.core.LauncherPaths
import org.json.JSONObject
import java.io.File

/**
 * Cross-process lease for an active `:game` session.
 * Survives SIGKILL of `:game` so the main process can still show an unexpected-exit dialog.
 */
object GameSessionLease {
    private const val FILE_NAME = "game_session_lease.json"
    private const val USER_EXIT_FILE = "game_user_exit.flag"

    data class Lease(
        val versionId: String,
        val gamePid: Int,
        val startedAtMs: Long,
        val hotspotEntered: Boolean
    )

    @Synchronized
    fun mark(context: Context, versionId: String, hotspotEntered: Boolean = true) {
        val id = versionId.trim()
        if (id.isEmpty()) return
        userExitFile(context).delete()
        val root = JSONObject()
            .put("versionId", id)
            .put("gamePid", Process.myPid())
            .put("startedAtMs", System.currentTimeMillis())
            .put("hotspotEntered", hotspotEntered)
        file(context).apply {
            parentFile?.mkdirs()
            writeText(root.toString())
        }
    }

    @Synchronized
    fun clear(context: Context) {
        file(context).delete()
    }

    fun hasUserExitFlag(context: Context): Boolean = userExitFile(context).exists()

    /** User tapped 退出游戏 / 返回 — never treat as unexpected crash. */
    @Synchronized
    fun markUserExit(context: Context) {
        clear(context)
        GameCrashReportStore.clear(context)
        userExitFile(context).apply {
            parentFile?.mkdirs()
            writeText(System.currentTimeMillis().toString())
        }
        // Also stamp launch log so recover can see it even if flag races.
        runCatching {
            if (LauncherPaths.isInitialized) {
                File(LauncherPaths.rootDir, "logs/latest-launch.log")
                    .appendText("\n=== user_exit intentional ===\n")
            }
        }
    }

    /** True if user intentionally left within the last 5 minutes. */
    @Synchronized
    fun consumeUserExit(context: Context): Boolean {
        val f = userExitFile(context)
        val flagged = if (f.exists()) {
            val age = runCatching { System.currentTimeMillis() - f.readText().trim().toLong() }
                .getOrDefault(Long.MAX_VALUE)
            f.delete()
            age in 0..300_000L
        } else {
            false
        }
        val logged = launchLogSaysUserExit()
        if (flagged || logged) {
            GameCrashReportStore.clear(context)
            clear(context)
            return true
        }
        return false
    }

    @Synchronized
    fun load(context: Context): Lease? {
        val f = file(context)
        if (!f.exists()) return null
        return runCatching {
            val o = JSONObject(f.readText())
            val id = o.optString("versionId").trim()
            if (id.isEmpty()) return null
            Lease(
                versionId = id,
                gamePid = o.optInt("gamePid", -1),
                startedAtMs = o.optLong("startedAtMs", 0L),
                hotspotEntered = o.optBoolean("hotspotEntered", true)
            )
        }.getOrNull()
    }

    fun isGameProcessAlive(context: Context, lease: Lease): Boolean {
        if (lease.gamePid > 0) {
            if (lease.gamePid == Process.myPid()) return true
            if (File("/proc/${lease.gamePid}").exists()) return true
        }
        val gameName = "${context.packageName}:game"
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return false
        return am.runningAppProcesses.orEmpty().any { it.processName == gameName }
    }

    fun recoverUnexpectedExit(context: Context): GameCrashReport? {
        if (hasUserExitFlag(context) || launchLogSaysUserExit()) {
            clear(context)
            GameCrashReportStore.clear(context)
            userExitFile(context).delete()
            return null
        }
        val lease = load(context) ?: return null
        if (isGameProcessAlive(context, lease)) return null
        if (lease.startedAtMs > 0 &&
            System.currentTimeMillis() - lease.startedAtMs > 6 * 60 * 60_000L
        ) {
            clear(context)
            return null
        }
        val report = GameCrashAnalyzer.analyze(
            versionId = lease.versionId,
            exitCode = -1,
            gameWasRunning = lease.hotspotEntered,
            forceUnexpected = true
        )
        clear(context)
        return report
    }

    fun launchLogSaysUserExit(): Boolean {
        val text = runCatching {
            if (!LauncherPaths.isInitialized) return false
            File(LauncherPaths.rootDir, "logs/latest-launch.log").readText().takeLast(8_000)
        }.getOrNull().orEmpty()
        if (text.isBlank()) return false
        return "user_exit intentional" in text ||
            "user_stop" in text ||
            "结束游戏进程: user_stop" in text ||
            "killProcess :game (user_stop)" in text
    }

    private fun file(context: Context): File =
        File(context.applicationContext.filesDir, FILE_NAME)

    private fun userExitFile(context: Context): File =
        File(context.applicationContext.filesDir, USER_EXIT_FILE)
}
