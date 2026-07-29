package com.booxin.launcher.core.auth

import android.os.Build
import com.booxin.launcher.BuildConfig
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Collects step-by-step Microsoft auth logs for debugging on user devices.
 */
object MicrosoftAuthLogger {

    @Volatile
    var lastReport: String? = null
        private set

    fun begin(flow: String): Session {
        val session = Session(flow)
        session.log("=== Microsoft Auth: $flow ===")
        session.log("app=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) sdk=${Build.VERSION.SDK_INT}")
        session.log("device=${Build.MANUFACTURER} ${Build.MODEL}")
        return session
    }

    fun finish(session: Session, error: Throwable? = null) {
        if (error != null) {
            session.log("FAILED: ${error.message}")
            session.log(stackTrace(error))
        } else {
            session.log("SUCCESS")
        }
        lastReport = session.buildReport()
    }

    class Session(private val flow: String) {
        private val lines = mutableListOf<String>()

        fun log(message: String) {
            val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
            lines += "[$stamp] $message"
        }

        fun logHttp(step: String, method: String, url: String, status: Int, body: String) {
            log("$step $method $url")
            log("  HTTP $status")
            val sanitized = sanitize(body)
            if (sanitized.isBlank()) {
                log("  Body: (empty)")
            } else {
                sanitized.lineSequence().forEach { line ->
                    log("  Body: $line")
                }
            }
        }

        fun buildReport(): String = buildString {
            appendLine("=== Booxin Microsoft Auth Log ===")
            appendLine("flow=$flow")
            appendLine("generated=${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())}")
            appendLine()
            lines.forEach { appendLine(it) }
        }
    }

    private fun stackTrace(t: Throwable): String {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        return sw.toString().trimEnd()
    }

    /** Redact tokens and long secrets before writing to the log file. */
    fun sanitize(text: String): String {
        if (text.isBlank()) return text
        var s = text
        val patterns = listOf(
            """"access_token"\s*:\s*"[^"]+"""" to """"access_token":"<redacted>"""",
            """"refresh_token"\s*:\s*"[^"]+"""" to """"refresh_token":"<redacted>"""",
            """"device_code"\s*:\s*"[^"]+"""" to """"device_code":"<redacted>"""",
            """"Token"\s*:\s*"[^"]+"""" to """"Token":"<redacted>"""",
            """"identityToken"\s*:\s*"[^"]+"""" to """"identityToken":"<redacted>"""",
            """"RpsTicket"\s*:\s*"[^"]+"""" to """"RpsTicket":"<redacted>"""",
            """Bearer\s+[A-Za-z0-9._\-]+""" to "Bearer <redacted>",
            """d=[A-Za-z0-9._\-]+""" to "d=<redacted>"
        )
        for ((pattern, replacement) in patterns) {
            s = s.replace(Regex(pattern), replacement)
        }
        return if (s.length > 4000) s.take(4000) + "…(truncated)" else s
    }
}
