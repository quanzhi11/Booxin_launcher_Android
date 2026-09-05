package com.booxin.launcher.core.launch

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 暂存待展示的崩溃报告（:game → 主界面）。 */
object GameCrashReportStore {
    private const val PREFS = "game_crash_report"
    private const val KEY_PENDING = "pending_json"

    fun save(context: Context, report: GameCrashReport) {
        if (!report.shouldPrompt) return
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PENDING, encode(report))
            .apply()
    }

    fun peek(context: Context): GameCrashReport? {
        val json = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PENDING, null)
            ?: return null
        return decode(json)
    }

    fun consume(context: Context): GameCrashReport? {
        val report = peek(context) ?: return null
        clear(context)
        return report
    }

    fun clear(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_PENDING)
            .apply()
    }

    private fun encode(report: GameCrashReport): String {
        val root = JSONObject()
        root.put("versionId", report.versionId)
        root.put("kind", report.kind.name)
        root.put("summary", report.summary)
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
