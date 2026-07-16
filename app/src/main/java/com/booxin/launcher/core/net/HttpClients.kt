package com.booxin.launcher.core.net

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object HttpClients {
    const val USER_AGENT = "BooxinLauncher/0.1 (Android; FCL-compatible)"

    val shared: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .writeTimeout(5, TimeUnit.MINUTES)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }
}
