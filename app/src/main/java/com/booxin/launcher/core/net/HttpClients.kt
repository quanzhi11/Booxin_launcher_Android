package com.booxin.launcher.core.net

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

object HttpClients {
    const val USER_AGENT = "BooxinLauncher/0.0.2 (Android)"

    private val hostnameVerifier = javax.net.ssl.HostnameVerifier { hostname, session ->
        val default = HttpsURLConnection.getDefaultHostnameVerifier()
        if (default.verify(hostname, session)) return@HostnameVerifier true
        // Legacy clients may still hit https://175.178.174.103/...; cert is for boonix.art.
        if (BooxinUrlNormalizer.isBooxinIpv4Host(hostname)) {
            return@HostnameVerifier default.verify(BooxinUrlNormalizer.DOMAIN, session) ||
                default.verify("www.${BooxinUrlNormalizer.DOMAIN}", session)
        }
        false
    }

    val shared: OkHttpClient by lazy {
        val dispatcher = okhttp3.Dispatcher().apply {
            // Assets are thousands of tiny files; raise pool so concurrency can breathe.
            maxRequests = 128
            maxRequestsPerHost = 48
        }
        OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .dns(ResilientDns())
            .hostnameVerifier(hostnameVerifier)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .writeTimeout(5, TimeUnit.MINUTES)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /** 镜像切换用短超时，避免主源卡住。 */
    val cascadeAttempt: OkHttpClient by lazy {
        shared.newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(25, TimeUnit.SECONDS)
            .callTimeout(35, TimeUnit.SECONDS)
            .build()
    }

    /** Room directory is plain HTTP; never upgrade/follow onto HTTPS-IP. */
    val cleartextRoom: OkHttpClient by lazy {
        shared.newBuilder()
            .followSslRedirects(false)
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
