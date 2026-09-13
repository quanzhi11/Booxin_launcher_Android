package com.booxin.launcher.core.runtime

import android.content.Context
import com.booxin.launcher.core.launch.GlRendererKind
import java.io.File

/** Env extras for Path A BooxinGlues (MIT/BSD stack) and optional LGPL MobileGlues. */
object BooxinGluesEnv {
    fun isBooxinFamily(kind: GlRendererKind): Boolean =
        kind == GlRendererKind.BOOXIN_GLUES || kind == GlRendererKind.MOBILE_GLUES

    fun apply(
        env: MutableMap<String, String>,
        context: Context,
        kind: GlRendererKind,
        profile: ModRenderProfile?,
        resolved: BooxinGlResolved? = null,
        mcVersionId: String? = null
    ) {
        if (!isBooxinFamily(kind) &&
            kind != GlRendererKind.LTW &&
            kind != GlRendererKind.KRYPTON &&
            kind != GlRendererKind.VULKAN_ZINK &&
            kind != GlRendererKind.ANGLE
        ) {
            return
        }

        val resolvedMcId = mcVersionId
            ?: env["BOOXIN_MC_VERSION"]
            ?: env["INST_NAME"]

        val bgDir = File(context.filesDir, "BooxinGlues").also { it.mkdirs() }
        env["BOOXIN_GLUES_DIR"] = bgDir.absolutePath
        if (!resolvedMcId.isNullOrBlank()) {
            env["BOOXIN_MC_VERSION"] = resolvedMcId
        }

        if (kind == GlRendererKind.BOOXIN_GLUES || kind == GlRendererKind.ANGLE) {
            env["BOOXIN_GLUES"] = "1"
            env["LIBGL_STRING"] = GlRendererKind.BOOXIN_GLUES.displayName
            val backend = resolved?.backendEnv ?: if (kind == GlRendererKind.ANGLE) "angle" else "gl4es"
            env["BOOXIN_GLUES_BACKEND"] = backend
            when (resolved?.engine) {
                BooxinGlEngine.ZINK -> {
                    env["BOOXIN_GLUES_ENGINE"] = "libOSMesa_25.so"
                    env["BOOXIN_GLUES_BACKEND_LIB"] = "libOSMesa_25.so"
                    env.remove("MG_DIR_PATH")
                }
                BooxinGlEngine.MOBILE_GLUES_COMPAT -> {
                    env["BOOXIN_GLUES_ENGINE"] = "libmobileglues.so"
                    env["BOOXIN_GLUES_BACKEND"] = "mobileglues"
                    env["BOOXIN_GL_LICENSE"] = "LGPL-2.1"
                    env["BOOXIN_GL_MAX_COMPAT"] = "1"
                    val mgDir = File(context.filesDir, "MG").also { it.mkdirs() }
                    env["MG_DIR_PATH"] = mgDir.absolutePath
                    env["allow_higher_compat_version"] = "true"
                    env["allow_glsl_extension_directive_midshader"] = "true"
                    env["force_glsl_extensions_warn"] = "true"
                    // Same MG handshake as MOBILE_GLUES path — not claiming to be FCL.
                    val vc = com.booxin.launcher.BuildConfig.VERSION_CODE.toString()
                    env.putIfAbsent("FCL_VERSION_CODE", vc)
                    env.putIfAbsent("ZALITH_VERSION_CODE", vc)
                    MobileGluesConfig.writeProfile(
                        context,
                        runCatching {
                            com.booxin.launcher.core.launch.BooxinLaunchTune.resolve(context)
                        }.getOrNull(),
                        mcVersionId = resolvedMcId
                    )
                }
                BooxinGlEngine.CLEANROOM -> {
                    env["BOOXIN_GL_CLEANROOM"] = "1"
                    env["BOOXIN_GLUES_ENGINE"] = "libbooxingl.so"
                    env["BOOXIN_GLUES_BACKEND_LIB"] = "libgl4es_holy.so"
                    env["BOOXIN_GLUES_BACKEND"] = "gl4es"
                    env["BOOXIN_GL_LICENSE"] = "Apache-2.0+MIT"
                    env.remove("MG_DIR_PATH")
                }
                BooxinGlEngine.ANGLE_EGL -> {
                    env["BOOXIN_GL_CLEANROOM"] = "1"
                    env["BOOXIN_GLUES_ENGINE"] = "libbooxingl.so"
                    env["BOOXIN_GLUES_BACKEND_LIB"] = "libgl4es_holy.so"
                    env["BOOXIN_GLUES_BACKEND"] = "gl4es"
                    env["BOOXIN_ANGLE_EGL"] = "libEGL_angle.so"
                    env["BOOXIN_ANGLE_GLES"] = "libGLESv2_angle.so"
                    env["LIBGL_EGL"] = "libEGL_angle.so"
                    env["BOOXIN_GL_LICENSE"] = "Apache-2.0+MIT+ANGLE-BSD"
                    env.remove("MG_DIR_PATH")
                }
                else -> {
                    env["BOOXIN_GLUES_ENGINE"] = "libgl4es_holy.so"
                    env["BOOXIN_GLUES_BACKEND_LIB"] = "libgl4es_holy.so"
                    env.remove("MG_DIR_PATH")
                }
            }
        }

        if (kind == GlRendererKind.MOBILE_GLUES) {
            val mgDir = File(context.filesDir, "MG").also { it.mkdirs() }
            env["MG_DIR_PATH"] = mgDir.absolutePath
            env["allow_higher_compat_version"] = "true"
            env["allow_glsl_extension_directive_midshader"] = "true"
            env["force_glsl_extensions_warn"] = "true"
            env["BOOXIN_GL_LICENSE"] = "LGPL-2.1"
            // MobileGlues only loads config.json when it sees a "known launcher" env
            // key (FCL_VERSION_CODE / ZALITH_VERSION_CODE). Booxin is NOT FCL/Zalith —
            // we reuse those key names so MG accepts our config. Value = our versionCode.
            val vc = com.booxin.launcher.BuildConfig.VERSION_CODE.toString()
            env.putIfAbsent("FCL_VERSION_CODE", vc)
            env.putIfAbsent("ZALITH_VERSION_CODE", vc)
            // Official translator reads config.json from MG_DIR_PATH for its own perf paths.
            MobileGluesConfig.writeProfile(
                context,
                runCatching { com.booxin.launcher.core.launch.BooxinLaunchTune.resolve(context) }
                    .getOrNull(),
                mcVersionId = resolvedMcId
            )
        }

        if (kind == GlRendererKind.BOOXIN_GLUES ||
            kind == GlRendererKind.VULKAN_ZINK ||
            kind == GlRendererKind.ANGLE
        ) {
            env.putIfAbsent("BOOXIN_GL_LICENSE", "Apache-2.0+MIT")
        }

        profile?.env?.forEach { (k, v) ->
            if (kind == GlRendererKind.BOOXIN_GLUES &&
                (k.startsWith("allow_") || k.startsWith("force_glsl"))
            ) {
                return@forEach
            }
            env[k] = v
        }
        if (profile != null) {
            env["BOOXIN_GLUES_PROFILE"] = profile.profileId
            if (profile.matchedMods.isNotEmpty()) {
                env["BOOXIN_GLUES_MATCHED_MODS"] =
                    profile.matchedMods.take(12).joinToString(",")
            }
            if ("shaders" in profile.features) env["BOOXIN_GLUES_SHADERS"] = "1"
            if ("multidraw" in profile.features) env["BOOXIN_GLUES_MULTIDRAW"] = "1"
        } else if (kind == GlRendererKind.BOOXIN_GLUES) {
            env.putIfAbsent("BOOXIN_GLUES_PROFILE", "vanilla")
        }

        resolved?.let { env["BOOXIN_GLUES_RESOLVED"] = it.engine.name }
    }
}
