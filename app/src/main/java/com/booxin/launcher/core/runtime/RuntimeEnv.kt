package com.booxin.launcher.core.runtime

import com.booxin.launcher.core.launch.GlRendererKind
import java.io.File

/** Env keys for the game process (+ a few legacy aliases some natives still read). */
object RuntimeEnv {
    const val NATIVEDIR = "BOOXIN_NATIVEDIR"
    const val RENDERER = "BOOXIN_RENDERER"
    const val EGL = "BOOXIN_EGL"

    const val LEGACY_POJAV_NATIVEDIR = "POJAV_NATIVEDIR"
    const val LEGACY_FCL_NATIVEDIR = "FCL_NATIVEDIR"
    const val LEGACY_POJAV_RENDERER = "POJAV_RENDERER"
    const val LEGACY_POJAVEXEC_EGL = "POJAVEXEC_EGL"

    fun rendererToken(kind: GlRendererKind): String = when (kind) {
        GlRendererKind.GL4ES -> "opengles2"
        GlRendererKind.MOBILE_GLUES -> "opengles3"
    }

    fun eglLib(kind: GlRendererKind): String = when (kind) {
        GlRendererKind.GL4ES -> "libEGL.so"
        GlRendererKind.MOBILE_GLUES -> "libmobileglues.so"
    }

    fun libGlString(kind: GlRendererKind): String = when (kind) {
        GlRendererKind.GL4ES -> "GL4ES"
        GlRendererKind.MOBILE_GLUES -> "MobileGlues"
    }

    fun libGlEs(kind: GlRendererKind): String = when (kind) {
        GlRendererKind.GL4ES -> "2"
        GlRendererKind.MOBILE_GLUES -> "3"
    }

    fun withNativeAliases(
        base: MutableMap<String, String>,
        stagedNatives: String,
        renderer: GlRendererKind,
        eglOverride: String? = null
    ): Map<String, String> {
        val token = rendererToken(renderer)
        val egl = eglOverride ?: eglLib(renderer)
        base[NATIVEDIR] = stagedNatives
        base[RENDERER] = token
        base[EGL] = egl
        base[LEGACY_POJAV_NATIVEDIR] = stagedNatives
        base[LEGACY_FCL_NATIVEDIR] = stagedNatives
        base[LEGACY_POJAV_RENDERER] = token
        base[LEGACY_POJAVEXEC_EGL] = egl
        return base
    }

    fun glLibraryFile(stagedNatives: File, kind: GlRendererKind): File = when (kind) {
        GlRendererKind.GL4ES -> File(stagedNatives, "libgl4es_114.so")
        GlRendererKind.MOBILE_GLUES -> File(stagedNatives, "libmobileglues.so")
    }
}
