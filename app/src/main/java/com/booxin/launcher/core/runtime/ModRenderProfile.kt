package com.booxin.launcher.core.runtime

import com.booxin.launcher.core.launch.GlRendererKind
import com.booxin.launcher.core.version.VersionModsManager
import org.json.JSONObject

/**
 * Mod / loader image used to pick BooxinGlues profiles and renderer fallbacks.
 * Profiles are seeded from assets `booxin_glues/mod_profiles.json` with hardcoded defaults.
 */
data class ModRenderProfile(
    val profileId: String,
    val preferred: GlRendererKind,
    val fallback: List<GlRendererKind>,
    val env: Map<String, String>,
    val matchedMods: List<String>,
    val features: Set<String>
) {
    val isHeavyGl: Boolean
        get() = features.any {
            it in setOf("shaders", "multidraw", "custom_render", "compute_emulate", "dsa")
        }
}

object ModRenderProfiler {
    private data class Rule(
        val id: String,
        val matchAny: List<String>,
        val matchLoader: List<String>,
        val preferred: GlRendererKind,
        val fallback: List<GlRendererKind>,
        val env: Map<String, String>,
        val features: Set<String>
    )

    private val defaultRules: List<Rule> = listOf(
        Rule(
            id = "iris",
            matchAny = listOf("iris-", "iris_", "oculus-", "irisflywheel"),
            matchLoader = emptyList(),
            preferred = GlRendererKind.BOOXIN_GLUES,
            fallback = listOf(
                GlRendererKind.VULKAN_ZINK,
                GlRendererKind.ANGLE,
                GlRendererKind.GL4ES,
                GlRendererKind.MOBILE_GLUES
            ),
            env = mapOf("BOOXIN_GLUES_PROFILE" to "iris"),
            features = setOf("shaders", "multidraw", "dsa")
        ),
        Rule(
            id = "sodium",
            matchAny = listOf("sodium-", "sodium_", "embeddium-", "rubidium-", "indium-"),
            matchLoader = emptyList(),
            preferred = GlRendererKind.BOOXIN_GLUES,
            fallback = listOf(
                GlRendererKind.VULKAN_ZINK,
                GlRendererKind.LTW,
                GlRendererKind.ANGLE,
                GlRendererKind.MOBILE_GLUES
            ),
            env = mapOf("BOOXIN_GLUES_PROFILE" to "sodium"),
            features = setOf("multidraw", "buffer_storage", "dsa")
        ),
        Rule(
            id = "distanthorizons",
            matchAny = listOf("distanthorizons", "distant-horizons", "distant_horizons"),
            matchLoader = emptyList(),
            preferred = GlRendererKind.BOOXIN_GLUES,
            fallback = listOf(
                GlRendererKind.VULKAN_ZINK,
                GlRendererKind.ANGLE,
                GlRendererKind.MOBILE_GLUES
            ),
            env = mapOf(
                "BOOXIN_GLUES_PROFILE" to "heavy_custom",
                "BOOXIN_GLUES_DSA_EMULATE" to "1"
            ),
            features = setOf("custom_render", "multidraw", "dsa")
        ),
        Rule(
            id = "immersiveportals",
            matchAny = listOf("immersiveportals", "immersive_portals", "imm_ptl"),
            matchLoader = emptyList(),
            preferred = GlRendererKind.BOOXIN_GLUES,
            fallback = listOf(
                GlRendererKind.VULKAN_ZINK,
                GlRendererKind.ANGLE,
                GlRendererKind.MOBILE_GLUES
            ),
            env = mapOf(
                "BOOXIN_GLUES_PROFILE" to "heavy_custom",
                "BOOXIN_GLUES_DSA_EMULATE" to "1"
            ),
            features = setOf("custom_render", "dsa")
        ),
        Rule(
            id = "create",
            matchAny = listOf("create-", "create_"),
            matchLoader = emptyList(),
            preferred = GlRendererKind.BOOXIN_GLUES,
            fallback = listOf(
                GlRendererKind.VULKAN_ZINK,
                GlRendererKind.ANGLE,
                GlRendererKind.GL4ES,
                GlRendererKind.MOBILE_GLUES
            ),
            env = mapOf(
                "BOOXIN_GLUES_PROFILE" to "create",
                "BOOXIN_GLUES_DSA_EMULATE" to "1",
                "BOOXIN_GLUES_COMPUTE_EMULATE" to "1"
            ),
            features = setOf("custom_render", "compute_emulate", "dsa")
        ),
        Rule(
            id = "journeymap",
            matchAny = listOf("journeymap", "xaero", "voxelmap"),
            matchLoader = emptyList(),
            preferred = GlRendererKind.BOOXIN_GLUES,
            fallback = listOf(GlRendererKind.GL4ES, GlRendererKind.MOBILE_GLUES),
            env = mapOf("BOOXIN_GLUES_PROFILE" to "heavy_custom"),
            features = setOf("custom_render")
        ),
        Rule(
            id = "neoforge",
            matchAny = emptyList(),
            matchLoader = listOf("neoforge"),
            preferred = GlRendererKind.BOOXIN_GLUES,
            fallback = listOf(GlRendererKind.GL4ES, GlRendererKind.MOBILE_GLUES),
            env = mapOf("BOOXIN_GLUES_PROFILE" to "neoforge"),
            features = emptySet()
        )
    )

    fun probe(versionId: String): ModRenderProfile {
        val mods = VersionModsManager.list(versionId)
            .filter { it.enabled }
            .map { it.displayName.lowercase() }
        val loaderHints = listOf(
            versionId.lowercase(),
            versionId.substringBefore('-').lowercase()
        )

        val hits = mutableListOf<Pair<Rule, List<String>>>()
        for (rule in defaultRules) {
            val modHits = mods.filter { name ->
                rule.matchAny.any { token -> name.contains(token) }
            }
            val loaderHit = rule.matchLoader.any { token ->
                loaderHints.any { it.contains(token) }
            }
            if (modHits.isNotEmpty() || loaderHit) {
                hits += rule to modHits
            }
        }

        if (hits.isEmpty()) {
            return ModRenderProfile(
                profileId = "vanilla",
                preferred = GlRendererKind.BOOXIN_GLUES,
                fallback = listOf(
                    GlRendererKind.VULKAN_ZINK,
                    GlRendererKind.GL4ES,
                    GlRendererKind.MOBILE_GLUES
                ),
                env = mapOf("BOOXIN_GLUES_PROFILE" to "vanilla"),
                matchedMods = emptyList(),
                features = emptySet()
            )
        }

        // Prefer the most feature-rich hit (shaders > custom > sodium > loader-only).
        val ranked = hits.sortedWith(
            compareByDescending<Pair<Rule, List<String>>> { (rule, _) ->
                when {
                    "shaders" in rule.features -> 5
                    rule.id == "distanthorizons" || rule.id == "immersiveportals" -> 4
                    "custom_render" in rule.features -> 3
                    "multidraw" in rule.features -> 2
                    rule.matchLoader.isNotEmpty() -> 1
                    else -> 0
                }
            }.thenByDescending { it.second.size }
        )
        val (best, matched) = ranked.first()
        val mergedEnv = linkedMapOf<String, String>()
        val mergedFeatures = linkedSetOf<String>()
        val allMatched = linkedSetOf<String>()
        for ((rule, modHits) in ranked) {
            mergedEnv.putAll(rule.env)
            mergedFeatures += rule.features
            allMatched += modHits
        }
        // Keep winner profile id / preferred renderer.
        mergedEnv["BOOXIN_GLUES_PROFILE"] = best.env["BOOXIN_GLUES_PROFILE"] ?: best.id

        return ModRenderProfile(
            profileId = best.id,
            preferred = best.preferred,
            fallback = best.fallback,
            env = mergedEnv,
            matchedMods = allMatched.toList(),
            features = mergedFeatures
        )
    }

    /** Optional: merge JSON asset overlays without failing launch. */
    fun mergeAssetOverlay(jsonText: String): List<String> {
        return runCatching {
            val root = JSONObject(jsonText)
            val arr = root.optJSONArray("profiles") ?: return emptyList()
            val ids = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                ids += arr.optJSONObject(i)?.optString("id").orEmpty()
            }
            ids.filter { it.isNotBlank() }
        }.getOrDefault(emptyList())
    }
}
