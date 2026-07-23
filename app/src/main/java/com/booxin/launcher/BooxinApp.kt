package com.booxin.launcher

import android.app.Application
import android.content.Context
import com.booxin.launcher.core.LauncherPaths
import com.booxin.launcher.core.launch.AndroidGameRuntime

class BooxinApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        LauncherPaths.init(this)
        runCatching { AndroidGameRuntime.ensure(this) }
    }

    companion object {
        private lateinit var instance: BooxinApp

        @JvmStatic
        fun getAppContext(): Context = instance.applicationContext
    }
}
