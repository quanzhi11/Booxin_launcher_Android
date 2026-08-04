package com.booxin.launcher.core.net

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object HttpClients {
    const val USER_AGENT = "BooxinLauncher/0.0.2 (Android)"

    val shared: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(ResilientDns())
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .writeTimeout(5, TimeUnit.MINUTES)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /**
     * Short-lived attempts when cascading mirrors: fail fast so the next
     * candidate is tried instead of hanging on a throttled primary.
     */
    val cascadeAttempt: OkHttpClient by lazy {
        shared.newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(25, TimeUnit.SECONDS)
            .callTimeout(35, TimeUnit.SECONDS)
            .build()
    }
}
