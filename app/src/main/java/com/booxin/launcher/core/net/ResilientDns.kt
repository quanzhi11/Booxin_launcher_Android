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
 *
 * Booxin API hosts are pinned to the known IPv4 so OkHttp can keep using the
 * domain in the URL (TLS cert SAN matches) without falling back to
 * `https://IP/...` which fails hostname verification.
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
        pinned(hostname)?.let { return it }

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

    private fun pinned(hostname: String): List<InetAddress>? {
        val ip = PINNED[hostname.trim().lowercase()] ?: return null
        return listOf(InetAddress.getByName(ip))
    }

    companion object {
        /** Official API / site IPv4 behind nginx TLS for boonix.art. */
        const val BOONIX_IPV4 = "175.178.174.103"

        private val PINNED = mapOf(
            "boonix.art" to BOONIX_IPV4,
            "www.boonix.art" to BOONIX_IPV4
        )
    }
}
