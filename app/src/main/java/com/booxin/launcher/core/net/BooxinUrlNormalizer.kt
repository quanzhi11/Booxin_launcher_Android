package com.booxin.launcher.core.net

/**
 * Server TLS cert is issued for [DOMAIN], not the raw IPv4.
 * Rewrite absolute IP URLs so OkHttp / Coil verify the correct hostname.
 */
object BooxinUrlNormalizer {
    const val DOMAIN = "boonix.art"
    const val IPV4 = ResilientDns.BOONIX_IPV4

    fun rewrite(url: String?): String? {
        val raw = url?.trim().orEmpty()
        if (raw.isEmpty()) return url
        return raw
            .replace("https://$IPV4", "https://$DOMAIN", ignoreCase = true)
            .replace("http://$IPV4/", "https://$DOMAIN/", ignoreCase = true)
            .replace("http://$IPV4?", "https://$DOMAIN?", ignoreCase = true)
    }

    fun isBooxinIpv4Host(hostname: String): Boolean =
        hostname.equals(IPV4, ignoreCase = true)
}
