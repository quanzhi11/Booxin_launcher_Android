package com.booxin.launcher

import android.app.Application
import android.content.Context
import com.booxin.launcher.core.LauncherPaths
import com.booxin.launcher.core.download.DownloadProviders
import com.booxin.launcher.core.launch.AndroidGameRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BooxinApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this
        LauncherPaths.init(this)
        DownloadProviders.init(this)
        runCatching { AndroidGameRuntime.ensure(this) }
        appScope.launch {
            runCatching { DownloadProviders.ensureProbed(this@BooxinApp) }
        }
    }

    companion object {
        private lateinit var instance: BooxinApp

        @JvmStatic
        fun getAppContext(): Context = instance.applicationContext
    }
}
