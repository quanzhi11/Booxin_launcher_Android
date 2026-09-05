package com.booxin.launcher.core.multiplayer

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.util.concurrent.atomic.AtomicInteger

/** Tracks whether any activity is started (app in foreground). */
object AppForeground {
    private val startedCount = AtomicInteger(0)

    @Volatile
    var isForeground: Boolean = false
        private set

    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                if (startedCount.incrementAndGet() == 1) isForeground = true
            }

            override fun onActivityStopped(activity: Activity) {
                if (startedCount.decrementAndGet() <= 0) {
                    startedCount.set(0)
                    isForeground = false
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}
