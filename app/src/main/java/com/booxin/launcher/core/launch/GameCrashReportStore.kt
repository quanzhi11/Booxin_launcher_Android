package com.booxin.launcher.core.launch

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Cross-process pending crash report (:game → main).
 * Uses a file under filesDir — SharedPreferences is not reliable across processes.
 */
object GameCrashReportStore {
    private const val FILE_NAME = "game_crash_report_pending.json"

    fun save(context: Context, report: GameCrashReport) {
        if (!report.shouldPrompt) return
        if (GameSessionLease.hasUserExitFlag(context)) return
        runCatching {
            file(context).apply {
                parentFile?.mkdirs()
                writeText(encode(report))
            }
        }
    }

    fun peek(context: Context): GameCrashReport? {
        val f = file(context)
        if (!f.isFile) return null
        return decode(f.readText())
    }

    fun consume(context: Context): GameCrashReport? {
        val report = peek(context) ?: return null
        clear(context)
        return report
    }

    fun clear(context: Context) {
        file(context).delete()
    }

    private fun file(context: Context): File =
        File(context.applicationContext.filesDir, FILE_NAME)

    private fun encode(report: GameCrashReport): String {
        val root = JSONObject()
        root.put("versionId", report.versionId)
        root.put("kind", report.kind.name)
        root.put("summary", report.summary)
        root.put("suggestion", report.suggestion)
        root.put("detail", report.detail)
        root.put("exitCode", report.exitCode)
        root.put("timestampMs", report.timestampMs)
        root.put("suspectMods", JSONArray().apply {
            report.suspectMods.forEach { mod ->
                put(
                    JSONObject()
                        .put("displayName", mod.displayName)
                        .put("fileName", mod.fileName)
                        .put("modId", mod.modId)
                        .put("enabled", mod.enabled)
                        .put("reason", mod.reason)
                )
            }
        })
        root.put("missingMods", JSONArray().apply {
            report.missingMods.forEach { dep ->
                put(
                    JSONObject()
                        .put("modId", dep.modId)
                        .put("displayHint", dep.displayHint)
                )
            }
        })
        return root.toString()
    }

    private fun decode(json: String): GameCrashReport? = runCatching {
        val root = JSONObject(json)
        val suspectArr = root.optJSONArray("suspectMods") ?: JSONArray()
        val missingArr = root.optJSONArray("missingMods") ?: JSONArray()
        GameCrashReport(
            versionId = root.getString("versionId"),
            kind = runCatching {
                GameCrashKind.valueOf(root.getString("kind"))
            }.getOrDefault(GameCrashKind.UNKNOWN),
            summary = root.optString("summary"),
            suggestion = root.optString("suggestion").ifBlank {
                root.optString("summary")
            },
            detail = root.optString("detail"),
            exitCode = root.optInt("exitCode", -1),
            timestampMs = root.optLong("timestampMs", System.currentTimeMillis()),
            suspectMods = buildList {
                for (i in 0 until suspectArr.length()) {
                    val item = suspectArr.optJSONObject(i) ?: continue
                    add(
                        GameCrashSuspectMod(
                            displayName = item.optString("displayName"),
                            fileName = item.optString("fileName"),
                            modId = item.optString("modId").ifBlank { null },
                            enabled = item.optBoolean("enabled", true),
                            reason = item.optString("reason").ifBlank { null }
                        )
                    )
                }
            },
            missingMods = buildList {
                for (i in 0 until missingArr.length()) {
                    val item = missingArr.optJSONObject(i) ?: continue
                    add(
                        GameCrashMissingMod(
                            modId = item.optString("modId"),
                            displayHint = item.optString("displayHint")
                        )
                    )
                }
            }
        )
    }.getOrNull()
}
