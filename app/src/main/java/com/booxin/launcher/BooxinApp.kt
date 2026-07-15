package com.booxin.launcher

import android.app.Application
import com.booxin.launcher.core.LauncherPaths

class BooxinApp : Application() {
    override fun onCreate() {
        super.onCreate()
        LauncherPaths.init(this)
    }
}
