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

    fun rendererToken(kind: GlRendererKind): String {
        RendererPackages.forKind(kind)?.let { return it.rendererToken }
        return when (kind) {
            GlRendererKind.GL4ES -> "opengles2"
            GlRendererKind.MOBILE_GLUES -> "opengles3"
            else -> "opengles3"
        }
    }

    fun eglLib(kind: GlRendererKind): String {
        RendererPackages.forKind(kind)?.let { pkg ->
            if (pkg.eglLib == "libEGL.so") return "libEGL.so"
            val dir = RendererInstaller.pluginNativeDir(kind)
            if (dir != null) {
                val file = File(dir, pkg.eglLib)
                if (file.isFile) return file.absolutePath
            }
            return pkg.eglLib
        }
        return when (kind) {
            GlRendererKind.MOBILE_GLUES -> "libmobileglues.so"
            else -> "libEGL.so"
        }
    }

    fun libGlString(kind: GlRendererKind): String = kind.displayName

    fun libGlEs(kind: GlRendererKind): String {
        RendererPackages.forKind(kind)?.let { return it.libGlEs }
        return if (kind == GlRendererKind.GL4ES) "2" else "3"
    }

    fun pluginExtraEnv(kind: GlRendererKind): Map<String, String> =
        RendererPackages.forKind(kind)?.extraEnv.orEmpty()

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

    fun glLibraryFile(stagedNatives: File, kind: GlRendererKind): File {
        RendererInstaller.glLibrary(kind)?.let { return it }
        return when (kind) {
            GlRendererKind.GL4ES -> File(stagedNatives, "libgl4es_114.so")
            GlRendererKind.MOBILE_GLUES -> File(stagedNatives, "libmobileglues.so")
            GlRendererKind.KRYPTON, GlRendererKind.LTW -> File(stagedNatives, "libgl4es_114.so")
            else -> File(stagedNatives, "libgl4es_114.so")
        }
    }
}
