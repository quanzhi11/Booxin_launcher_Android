package com.booxin.launcher

import android.app.Application
import android.content.Context
import com.booxin.launcher.core.LauncherPaths
import com.booxin.launcher.core.LauncherPrefs
import com.booxin.launcher.core.download.DownloadProviders
import com.booxin.launcher.core.launch.AndroidGameRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

class BooxinApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this
        LauncherPaths.init(this)
        LauncherPrefs.init(this)
        DownloadProviders.init(this)
        runCatching { AndroidGameRuntime.ensure(this) }
        appScope.launch {
            runCatching { DownloadProviders.ensureProbed(this@BooxinApp) }
            runCatching { stageHsErrForAdb(this@BooxinApp) }
        }
    }

    /** Copy HotSpot crash logs to external files so `adb pull` works on release builds. */
    private fun stageHsErrForAdb(context: Context) {
        val outDir = File(context.getExternalFilesDir(null), "crash").also { it.mkdirs() }
        val sources = mutableListOf<File>()
        LauncherPaths.versionsDir.listFiles()?.forEach { ver ->
            if (!ver.isDirectory) return@forEach
            ver.listFiles()
                ?.filter { it.isFile && it.name.startsWith("hs_err_pid") }
                ?.let { sources += it }
        }
        sources.sortedByDescending { it.lastModified() }.take(3).forEach { src ->
            val dest = File(outDir, "${src.parentFile?.name}-${src.name}")
            src.copyTo(dest, overwrite = true)
        }
    }

    companion object {
        private lateinit var instance: BooxinApp

        @JvmStatic
        fun getAppContext(): Context = instance.applicationContext
    }
}
