package com.booxin.launcher.core.diag

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import com.booxin.launcher.AppContainer
import com.booxin.launcher.BuildConfig
import com.booxin.launcher.core.LauncherPaths
import com.booxin.launcher.core.download.DownloadProviders
import com.booxin.launcher.core.launch.GameLaunchLogBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Builds a shareable diagnostic text for devices we cannot adb into.
 */
object DiagnosticLogExporter {

    suspend fun exportAndShare(context: Context): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.cacheDir, "diagnostics").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val file = File(dir, "booxin-diag-$stamp.txt")
            file.writeText(buildReport(context))
            share(context, file)
            file
        }
    }

    private fun buildReport(context: Context): String {
        val sb = StringBuilder()
        sb.appendLine("=== Booxin Launcher Diagnostic ===")
        sb.appendLine("time=${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())}")
        sb.appendLine("app=${BuildConfig.APPLICATION_ID} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        sb.appendLine("buildType=${BuildConfig.BUILD_TYPE}")
        sb.appendLine("sdk=${Build.VERSION.SDK_INT} release=${Build.VERSION.RELEASE}")
        sb.appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("abi=${Build.SUPPORTED_ABIS.joinToString()}")
        sb.appendLine("gameDir=${LauncherPaths.rootDir.absolutePath}")
        sb.appendLine("downloadSource=${DownloadProviders.source}")
        sb.appendLine("java=${AppContainer.javaEnvironment.statusText()}")
        sb.appendLine(
            "installedVersions=${
                AppContainer.repository.installedVersions.value.joinToString { it.id }.ifBlank { "(none)" }
            }"
        )
        sb.appendLine(
            "selectedVersion=${AppContainer.repository.session.value.selectedVersionId ?: "(none)"}"
        )
        val mp = AppContainer.multiplayerAuth.current()
        sb.appendLine(
            "multiplayer=${mp?.user?.username ?: "(not logged in)"} root=${mp?.apiRoot ?: "-"}"
        )
        sb.appendLine("joinStatus=${AppContainer.multiplayerAuth.joinStatus.value ?: "(none)"}")
        sb.appendLine(
            "directConnect=${AppContainer.multiplayerAuth.directConnectAddress.value ?: "(none)"}"
        )
        sb.appendLine()
        sb.appendLine("=== app event log (release-safe) ===")
        sb.appendLine(DiagEventLog.readPersisted())
        sb.appendLine()
        sb.appendLine("=== latest launch log (shared with :game) ===")
        sb.appendLine(GameLaunchLogBus.readPersistedLog())
        sb.appendLine()
        sb.appendLine("=== HotSpot hs_err (native crash) ===")
        sb.appendLine(collectHsErrLogs())
        sb.appendLine()
        sb.appendLine("=== logcat (pid=${android.os.Process.myPid()}, last ~400 lines) ===")
        sb.appendLine(captureLogcat(pidOnly = true, lines = 400))
        sb.appendLine()
        sb.appendLine("=== logcat (package, last ~400 lines) ===")
        sb.appendLine(captureLogcat(pidOnly = false, lines = 400))
        return sb.toString()
    }

    private fun collectHsErrLogs(maxChars: Int = 80_000): String {
        if (!LauncherPaths.isInitialized) return "(paths unavailable)"
        val files = mutableListOf<File>()
        runCatching {
            LauncherPaths.versionsDir.listFiles()?.forEach { verDir ->
                if (!verDir.isDirectory) return@forEach
                verDir.listFiles()
                    ?.filter { it.isFile && it.name.startsWith("hs_err_pid") && it.name.endsWith(".log") }
                    ?.let { files += it }
            }
            File(LauncherPaths.rootDir, "logs").listFiles()
                ?.filter { it.isFile && it.name.startsWith("hs_err_pid") }
                ?.let { files += it }
        }
        if (files.isEmpty()) return "(no hs_err_pid*.log found)"
        files.sortByDescending { it.lastModified() }
        return buildString {
            for (f in files.take(3)) {
                appendLine("--- ${f.absolutePath} (${f.length()} bytes) ---")
                val text = runCatching { f.readText() }.getOrElse { "read failed: ${it.message}" }
                appendLine(if (text.length <= maxChars / 3) text else text.take(maxChars / 3))
                appendLine()
            }
        }.take(maxChars)
    }

    private fun captureLogcat(pidOnly: Boolean, lines: Int): String {
        return try {
            val cmd = mutableListOf("logcat", "-d", "-t", lines.toString())
            if (pidOnly) {
                cmd += "--pid=${android.os.Process.myPid()}"
            } else {
                // Best-effort: include HotSpot / launcher tags from any process we can read.
                cmd += listOf(
                    "BooxinDiag:I",
                    "EasyTierSession:I",
                    "EasyTierRuntime:I",
                    "RoomJoin:I",
                    "BooxinLaunch:I",
                    "GameLaunchService:I",
                    "LaunchActivity:I",
                    "AndroidRuntime:E",
                    "art:W",
                    "*:S"
                )
            }
            val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val text = proc.inputStream.bufferedReader().use { it.readText() }
            val code = proc.waitFor()
            when {
                text.isNotBlank() -> text
                code != 0 -> "(empty logcat dump; exit=$code — release builds often cannot read logcat; see app event log above)"
                else -> "(empty logcat dump — release builds often cannot read logcat; see app event log above)"
            }
        } catch (t: Throwable) {
            "logcat failed: ${t.message}"
        }
    }

    private fun share(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Booxin diagnostic ${file.name}")
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, "Booxin 诊断日志，请发给开发者。")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(
            Intent.createChooser(intent, "分享诊断日志").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
