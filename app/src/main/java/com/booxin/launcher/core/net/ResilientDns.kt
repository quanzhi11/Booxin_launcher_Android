package com.booxin.launcher.core.net

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * System DNS first; on failure fall back to AliDNS / DNSPod DoH (IPv4 only).
 * Helps Xiaomi private-DNS / intermittent EAI_NODATA for Microsoft hosts.
 */
class ResilientDns(
    private val bootstrap: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .cache(null)
        .build()
) : Dns {
    private val fallbacks: List<Dns> by lazy {
        listOf(
            DnsOverHttps.Builder()
                .client(bootstrap)
                .url("https://dns.alidns.com/dns-query".toHttpUrl())
                .includeIPv6(false)
                .build(),
            DnsOverHttps.Builder()
                .client(bootstrap)
                .url("https://doh.pub/dns-query".toHttpUrl())
                .includeIPv6(false)
                .build()
        )
    }

    override fun lookup(hostname: String): List<InetAddress> {
        val errors = mutableListOf<Throwable>()
        try {
            return Dns.SYSTEM.lookup(hostname)
        } catch (t: Throwable) {
            errors += t
        }
        try {
            Thread.sleep(250)
        } catch (_: InterruptedException) {
        }
        for (fallback in fallbacks) {
            try {
                return fallback.lookup(hostname)
            } catch (t: Throwable) {
                errors += t
            }
        }
        throw UnknownHostException(
            "Unable to resolve host \"$hostname\" (system+DoH failed: ${
                errors.mapNotNull { it.message }.distinct().joinToString("; ")
            })"
        )
    }
}
