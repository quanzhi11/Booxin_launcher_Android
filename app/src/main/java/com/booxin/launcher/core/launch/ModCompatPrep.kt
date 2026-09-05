package com.booxin.launcher.core.launch

import android.util.Log
import com.booxin.launcher.core.version.VersionModsManager
import java.io.File

/**
 * Best-effort tweaks so more desktop mods can run on Android:
 * - Prefer safer Flywheel backend for Create
 * - Detect heavy GL mods and report them (caller forces MobileGlues via profiler)
 *
 * Does NOT disable MTR / Create / etc. — only patches configs and returns hints.
 */
object ModCompatPrep {

    private const val TAG = "ModCompatPrep"

    data class Result(
        val notes: List<String>,
        val heavyGl: Boolean
    )

    fun prepare(gameDir: File, versionId: String): Result {
        val notes = ArrayList<String>()
        val mods = VersionModsManager.list(versionId).filter { it.enabled }
        val names = mods.map { it.displayName.lowercase() }

        val heavyTokens = listOf(
            "mtr", "transit", "nte", "jsblock", "joban",
            "create-", "create_", "flywheel",
            "iris", "oculus", "sodium", "embeddium",
            "distanthorizons", "distant-horizons",
            "immersiveportals", "imm_ptl",
            "rubidium", "indium"
        )
        val heavyHits = names.filter { n -> heavyTokens.any { t -> n.contains(t) } }
        val heavyGl = heavyHits.isNotEmpty()
        if (heavyGl) {
            notes += "检测到重渲染模组（${heavyHits.take(6).joinToString()}），将优先 MobileGlues"
        }

        // Create / Flywheel: OFF backend is most compatible on GLES translators.
        if (names.any { "create" in it || "flywheel" in it }) {
            patchFlywheelBackend(gameDir)?.let { notes += it }
        }

        // Mild FPS-friendly defaults help weak tablets survive heavy packs.
        if (heavyGl) {
            patchSodiumExtraIfPresent(gameDir)?.let { notes += it }
        }

        notes.forEach { Log.i(TAG, it) }
        return Result(notes = notes, heavyGl = heavyGl)
    }

    private fun patchFlywheelBackend(gameDir: File): String? {
        val candidates = listOf(
            File(gameDir, "config/flywheel-client.toml"),
            File(gameDir, "config/flywheel.toml"),
            File(gameDir, "config/create-flywheel.toml")
        )
        var touched = false
        for (file in candidates) {
            if (!file.isFile) continue
            val text = runCatching { file.readText() }.getOrNull() ?: continue
            val next = text
                .replace(Regex("""(?im)^\s*backend\s*=\s*".*""""), """backend = "OFF"""")
                .replace(Regex("""(?im)^\s*backend\s*=\s*'.*'"""), """backend = "OFF"""")
            if (next != text) {
                runCatching {
                    file.writeText(next)
                    touched = true
                }
            } else if (!text.contains("backend", ignoreCase = true)) {
                // Append a safe default if the key is missing.
                runCatching {
                    file.appendText("\n# Booxin Android compat\nbackend = \"OFF\"\n")
                    touched = true
                }
            }
        }
        // Seed a config if Create is present but no flywheel config yet.
        if (!touched && candidates.none { it.isFile }) {
            val seed = File(gameDir, "config/flywheel-client.toml")
            runCatching {
                seed.parentFile?.mkdirs()
                seed.writeText(
                    """
                    # Written by Booxin for Android GLES compatibility.
                    # OFF avoids Instantiation/Compute backends that often crash translators.
                    backend = "OFF"
                    """.trimIndent() + "\n"
                )
                touched = true
            }
        }
        return if (touched) "已将 Flywheel 渲染后端设为 OFF（提高 Create 等模组兼容性）" else null
    }

    private fun patchSodiumExtraIfPresent(gameDir: File): String? {
        val file = File(gameDir, "config/sodium-extra-options.json")
        if (!file.isFile) return null
        // Leave content alone if present; just note for logs.
        return null
    }
}
