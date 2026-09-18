package com.booxin.launcher.core.runtime

import com.booxin.launcher.core.launch.GlRendererKind
import java.io.File

/** Env keys for the game process (+ a few legacy aliases some natives still read). */
object RuntimeEnv {
    const val NATIVEDIR = "BOOXIN_NATIVEDIR"
    const val RENDERER = "BOOXIN_RENDERER"
    const val EGL = "BOOXIN_EGL"
    /** Native CreateJavaVM heartbeats append here (ColorOS often blocks logcat). */
    const val LAUNCH_LOG = "BOOXIN_LAUNCH_LOG"

    const val LEGACY_POJAV_NATIVEDIR = "POJAV_NATIVEDIR"
    /** Legacy native-dir env alias required by some third-party renderer plugins. */
    const val LEGACY_NATIVEDIR_ALT = "FCL_NATIVEDIR"
    const val LEGACY_POJAV_RENDERER = "POJAV_RENDERER"
    const val LEGACY_POJAVEXEC_EGL = "POJAVEXEC_EGL"

    fun rendererToken(kind: GlRendererKind): String {
        // Built-in package token wins (sideloaded plugin.json may omit/wrong token).
        RendererPackages.forKind(kind)?.rendererToken?.let { return it }
        com.booxin.launcher.core.plugin.PluginManager.findByKind(kind)
            ?.takeIf { it.enabled }
            ?.rendererToken
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        return when (kind) {
            GlRendererKind.GL4ES -> "opengles3"
            GlRendererKind.REL -> "opengles3_rel"
            GlRendererKind.BOOXIN_GLUES, GlRendererKind.BOOXIN_ZINK -> "booxin_glues"
            GlRendererKind.MOBILE_GLUES -> "opengles3"
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
            // Bridge BOOXIN_EGL → MG so CreateContext wrap can find the .so;
            // LIBGL_EGL stays system libEGL (set in LaunchCommandBuilder).
            GlRendererKind.MOBILE_GLUES -> "libmobileglues.so"
            GlRendererKind.REL -> "librel.so"
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
        return "3"
    }

    fun pluginExtraEnv(kind: GlRendererKind): Map<String, String> {
        val merged = linkedMapOf<String, String>()
        // Built-in defaults first; installed plugin.json may omit critical keys.
        RendererPackages.forKind(kind)?.extraEnv?.let { merged.putAll(it) }
        com.booxin.launcher.core.plugin.PluginManager.findByKind(kind)
            ?.takeIf { it.enabled }
            ?.extraEnv
            ?.let { merged.putAll(it) }
        if (kind == GlRendererKind.REL) {
            if (com.booxin.launcher.core.LauncherPrefs.fsr1Enabled()) {
                merged["REL_FSR_ENABLE"] = "1"
                merged.putIfAbsent("REL_FSR_SCALE", "0.77")
            } else {
                merged["REL_FSR_ENABLE"] = "0"
            }
        }
        if (kind == GlRendererKind.GL4ES) {
            // holy gl4es：ES3 + FBO 纹理附件，减轻 status=0 / Mojang 后黑屏
            merged["LIBGL_ES"] = "3"
            merged.putIfAbsent("LIBGL_FBOFORCETEX", "1")
            merged.putIfAbsent("LIBGL_NORMALIZE", "1")
            merged.putIfAbsent("LIBGL_NOINTOVLHACK", "1")
        }
        return merged
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
        base[LEGACY_NATIVEDIR_ALT] = stagedNatives
        // Do NOT export POJAV_RENDERER to Java System.getenv — Create/Sodium use it
        // and hardcode the brand "PojavLauncher". Patched LWJGL (booxinRendererToken)
        // reads BOOXIN_RENDERER instead; jre_launcher setenvs POJAV_RENDERER for C only
        // after freezing HotSpot ProcessEnvironment without that key.
        // POJAVEXEC_EGL: REL keeps system host; MG uses libmobileglues (FCL-compat).
        base[LEGACY_POJAVEXEC_EGL] = when (renderer) {
            GlRendererKind.REL -> "libEGL.so"
            else -> egl
        }
        return base
    }

    fun glLibraryFile(stagedNatives: File, kind: GlRendererKind): File {
        // REL: prefer staged APK librel.so; optional sideloaded plugin override.
        if (kind == GlRendererKind.REL) {
            val rel = File(stagedNatives, "librel.so")
            if (rel.isFile) return rel
            RendererInstaller.glLibrary(kind)?.let { return it }
            val disguised = File(stagedNatives, "libgl4es_114.so")
            if (disguised.isFile) return disguised
        }
        if (kind == GlRendererKind.MCRENDER) {
            val mc = File(stagedNatives, "libmcrender.so")
            if (mc.isFile) return mc
            RendererInstaller.glLibrary(kind)?.let { return it }
        }
        if (kind == GlRendererKind.BOOXIN_GLUES || kind == GlRendererKind.BOOXIN_ZINK) {
            val osmesa = File(stagedNatives, "libOSMesa_25.so")
            if (osmesa.isFile) return osmesa
        }
        // Disguised GLES wrappers must load the staged libgl4es_114.so copy
        // (same inode path LWJGL + bridge expect after applyRenderer).
        val pkg = RendererPackages.forKind(kind)
        if (pkg?.disguiseAsGl4es == true) {
            val staged = File(stagedNatives, "libgl4es_114.so")
            if (staged.isFile) return staged
        }
        RendererInstaller.glLibrary(kind)?.let { return it }
        return when (kind) {
            GlRendererKind.BOOXIN_GLUES,
            GlRendererKind.BOOXIN_ZINK,
            GlRendererKind.VULKAN_ZINK -> {
                val osmesa = File(stagedNatives, "libOSMesa_25.so")
                if (osmesa.isFile) return osmesa
                File(stagedNatives, "libOSMesa.so")
            }
            GlRendererKind.GL4ES -> File(stagedNatives, "libgl4es_114.so")
            GlRendererKind.MOBILE_GLUES -> File(stagedNatives, "libmobileglues.so")
            GlRendererKind.KRYPTON, GlRendererKind.LTW, GlRendererKind.REL ->
                File(stagedNatives, "libgl4es_114.so")
            else -> File(stagedNatives, "libgl4es_114.so")
        }
    }
}
