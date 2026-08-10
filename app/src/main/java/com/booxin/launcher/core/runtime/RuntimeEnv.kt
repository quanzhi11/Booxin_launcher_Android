package com.booxin.launcher.core.runtime

import com.booxin.launcher.core.launch.GlRendererKind
import java.io.File

/** Env keys for the game process (+ a few legacy aliases some natives still read). */
object RuntimeEnv {
    const val NATIVEDIR = "BOOXIN_NATIVEDIR"
    const val RENDERER = "BOOXIN_RENDERER"
    const val EGL = "BOOXIN_EGL"

    const val LEGACY_POJAV_NATIVEDIR = "POJAV_NATIVEDIR"
    /** Legacy native-dir env alias required by some renderer plugins. */
    const val LEGACY_NATIVEDIR_ALT = "FCL_NATIVEDIR"
    const val LEGACY_POJAV_RENDERER = "POJAV_RENDERER"
    const val LEGACY_POJAVEXEC_EGL = "POJAVEXEC_EGL"

    fun rendererToken(kind: GlRendererKind): String {
        com.booxin.launcher.core.plugin.PluginManager.findByKind(kind)
            ?.takeIf { it.enabled }
            ?.let { return it.rendererToken }
        RendererPackages.forKind(kind)?.let { return it.rendererToken }
        return when (kind) {
            GlRendererKind.GL4ES -> "opengles2"
            GlRendererKind.MOBILE_GLUES,
            GlRendererKind.BOOXIN_GLUES -> "opengles3"
            else -> "opengles3"
        }
    }

    fun eglLib(kind: GlRendererKind): String {
        com.booxin.launcher.core.plugin.PluginManager.findByKind(kind)
            ?.takeIf { it.enabled }
            ?.let { p ->
                if (p.eglLib == "libEGL.so") return "libEGL.so"
                val dir = p.nativeDir
                if (dir != null) {
                    val file = File(dir, p.eglLib)
                    if (file.isFile) return file.absolutePath
                }
                return p.eglLib
            }
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
            GlRendererKind.ANGLE -> {
                val dir = RendererInstaller.pluginNativeDir(kind)
                val file = dir?.let { File(it, "libEGL_angle.so") }
                if (file?.isFile == true) file.absolutePath else "libEGL_angle.so"
            }
            GlRendererKind.BOOXIN_GLUES -> "libEGL.so"
            else -> "libEGL.so"
        }
    }

    fun libGlString(kind: GlRendererKind): String =
        com.booxin.launcher.core.plugin.PluginManager.findByKind(kind)
            ?.takeIf { it.enabled }
            ?.name
            ?: kind.displayName

    fun libGlEs(kind: GlRendererKind): String {
        com.booxin.launcher.core.plugin.PluginManager.findByKind(kind)
            ?.takeIf { it.enabled }
            ?.let { return it.libGlEs }
        RendererPackages.forKind(kind)?.let { return it.libGlEs }
        return if (kind == GlRendererKind.GL4ES) "2" else "3"
    }

    fun pluginExtraEnv(kind: GlRendererKind): Map<String, String> =
        com.booxin.launcher.core.plugin.PluginManager.findByKind(kind)
            ?.takeIf { it.enabled }
            ?.extraEnv
            ?: RendererPackages.forKind(kind)?.extraEnv.orEmpty()

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
        base[LEGACY_NATIVEDIR_ALT] = stagedNatives
        base[LEGACY_POJAV_RENDERER] = token
        base[LEGACY_POJAVEXEC_EGL] = egl
        return base
    }

    fun glLibraryFile(stagedNatives: File, kind: GlRendererKind): File {
        RendererInstaller.glLibrary(kind)?.let { return it }
        return when (kind) {
            GlRendererKind.GL4ES,
            GlRendererKind.BOOXIN_GLUES -> File(stagedNatives, "libgl4es_114.so")
            GlRendererKind.MOBILE_GLUES -> File(stagedNatives, "libmobileglues.so")
            GlRendererKind.KRYPTON, GlRendererKind.LTW -> File(stagedNatives, "libgl4es_114.so")
            else -> File(stagedNatives, "libgl4es_114.so")
        }
    }
}
