package com.booxin.launcher.core.runtime

import com.booxin.launcher.core.launch.GlRendererKind

/**
 * Downloadable renderer plugin metadata (third-party renderer APKs).
 * Libs are extracted for the device ABI into `runtime/renderers/<id>/`.
 */
data class RendererPackage(
    val id: String,
    val kind: GlRendererKind,
    val downloadUrl: String,
    /** Primary GL translator / OSMesa soname inside the APK. */
    val glLib: String,
    /** EGL 提供者；libEGL.so 表示系统 EGL。 */
    val eglLib: String,
    val rendererToken: String,
    val libGlEs: String,
    val extraEnv: Map<String, String> = emptyMap(),
    /** When true, stage glLib as libgl4es_114.so (LWJGL GLES path). */
    val disguiseAsGl4es: Boolean = false
)

object RendererPackages {
    private const val RENDERER_PLUGIN_BASE =
        "https://github.com/ShirosakiMio/FCLRendererPlugin/releases/download/Renderer"

    val all: List<RendererPackage> = listOf(
        RendererPackage(
            id = "krypton",
            kind = GlRendererKind.KRYPTON,
            downloadUrl =
                "https://github.com/BZLZHH/NGG-FCLRendererPlugin/releases/download/R0.4.5/Krypton_Wrapper_0.4.5.apk",
            glLib = "libng_gl4es.so",
            eglLib = "libEGL.so",
            rendererToken = "opengles3",
            libGlEs = "3",
            disguiseAsGl4es = true,
            extraEnv = mapOf(
                "LIBGL_MIPMAP" to "3",
                "LIBGL_NORMALIZE" to "1"
            )
        ),
        RendererPackage(
            id = "ltw",
            kind = GlRendererKind.LTW,
            downloadUrl = "$RENDERER_PLUGIN_BASE/LTW-2025.7.16.apk",
            glLib = "libltw.so",
            eglLib = "libEGL.so",
            rendererToken = "opengles3_ltw",
            libGlEs = "3",
            disguiseAsGl4es = true
        ),
        RendererPackage(
            id = "rel",
            kind = GlRendererKind.REL,
            // Bundled in APK jniLibs; URL kept for optional QA sideload refresh only.
            downloadUrl =
                "https://github.com/Layer-MC-Team/Layer-MC-Team-REL/releases/download/v1.0.0/RELv1.0.0.apk",
            glLib = "librel.so",
            eglLib = "librel.so",
            rendererToken = "opengles3_rel",
            libGlEs = "3",
            disguiseAsGl4es = true,
            extraEnv = mapOf(
                "MESA_NO_ERROR" to "1",
                // Prefer single-thread GL on Adreno: less texture upload races at world join.
                "MESA_GLTHREAD" to "false",
                "REL_ENABLE_TIMER_QUERY" to "0",
                "REL_DEBUG" to "0",
                // Prefer glBufferData path over EXT_buffer_storage (lower peak VRAM on some Adreno).
                "REL_HIDE_BUFFER_STORAGE" to "1",
                // Default FSR scale remaps FBO0 and clips phone GUI — keep off; we scale window instead.
                "REL_FSR_ENABLE" to "0"
            )
        ),
        RendererPackage(
            id = "mcrender",
            kind = GlRendererKind.MCRENDER,
            // Bundled arm64 libmcrender.so; asset APK kept for import / sideload refresh.
            downloadUrl = "asset://app_runtime/renderers/mcrender.apk",
            glLib = "libmcrender.so",
            eglLib = "libEGL.so",
            // Must load as libmcrender.so (not disguised gl4es): MCrender self-promote
            // via dladdr fails when the soname is rewritten, leaving glGetString null.
            rendererToken = "opengles3",
            libGlEs = "3",
            disguiseAsGl4es = false,
            extraEnv = mapOf(
                "LIBGL_MIPMAP" to "3",
                "LIBGL_NORMALIZE" to "1",
                "LIBGL_NOERROR" to "1"
            )
        ),
        /**
         * BooxinGlues：内置 libOSMesa_25.so（Mesa Zink，MIT），不走下载。
         */
        RendererPackage(
            id = "booxin-glues",
            kind = GlRendererKind.BOOXIN_GLUES,
            downloadUrl = "$RENDERER_PLUGIN_BASE/Zink.Mesa25.apk",
            glLib = "libOSMesa_25.so",
            eglLib = "libOSMesa_25.so",
            rendererToken = "booxin_glues",
            libGlEs = "3",
            extraEnv = mapOf(
                "MESA_LOADER_DRIVER_OVERRIDE" to "zink",
                "GALLIUM_DRIVER" to "zink",
                "MESA_GL_VERSION_OVERRIDE" to "4.6",
                "MESA_GLSL_VERSION_OVERRIDE" to "460",
                "LIB_MESA" to "libOSMesa_25.so",
                "BOOXIN_GLUES" to "1",
                "BOOXIN_GLUES_PEARL" to "1",
                "BOOXIN_VULKAN_LIB" to "libvulkan.so",
                "MESA_NO_ERROR" to "1"
            )
        ),
        RendererPackage(
            id = "zink",
            kind = GlRendererKind.VULKAN_ZINK,
            downloadUrl = "$RENDERER_PLUGIN_BASE/Zink.Mesa25.apk",
            glLib = "libOSMesa_25.so",
            eglLib = "libOSMesa_25.so",
            rendererToken = "vulkan_zink",
            libGlEs = "3",
            extraEnv = mapOf(
                "MESA_LOADER_DRIVER_OVERRIDE" to "zink",
                "GALLIUM_DRIVER" to "zink",
                "MESA_GL_VERSION_OVERRIDE" to "4.6",
                "MESA_GLSL_VERSION_OVERRIDE" to "460",
                "LIB_MESA" to "libOSMesa_25.so"
            )
        ),
        RendererPackage(
            id = "virgl",
            kind = GlRendererKind.VIRGL,
            downloadUrl = "$RENDERER_PLUGIN_BASE/Mesa.24.3.4.APK",
            glLib = "libOSMesa.so",
            eglLib = "libOSMesa.so",
            rendererToken = "gallium_virgl",
            libGlEs = "3",
            extraEnv = mapOf(
                "MESA_LOADER_DRIVER_OVERRIDE" to "virpipe",
                "GALLIUM_DRIVER" to "virpipe",
                "MESA_GL_VERSION_OVERRIDE" to "4.3",
                "MESA_GLSL_VERSION_OVERRIDE" to "430",
                "LIB_MESA" to "libOSMesa.so"
            )
        ),
        RendererPackage(
            id = "freedreno",
            kind = GlRendererKind.FREEDRENO,
            downloadUrl = "$RENDERER_PLUGIN_BASE/Mesa.24.3.4.APK",
            glLib = "libOSMesa.so",
            eglLib = "libOSMesa.so",
            rendererToken = "gallium_freedreno",
            libGlEs = "3",
            extraEnv = mapOf(
                "MESA_LOADER_DRIVER_OVERRIDE" to "freedreno",
                "GALLIUM_DRIVER" to "freedreno",
                "MESA_GL_VERSION_OVERRIDE" to "3.3",
                "MESA_GLSL_VERSION_OVERRIDE" to "330",
                "LIB_MESA" to "libOSMesa.so"
            )
        ),
        RendererPackage(
            id = "angle",
            kind = GlRendererKind.ANGLE,
            downloadUrl = "$RENDERER_PLUGIN_BASE/ANGLE.Renderer.apk",
            glLib = "libGLESv2_angle.so",
            eglLib = "libEGL_angle.so",
            rendererToken = "opengles3",
            libGlEs = "3",
            disguiseAsGl4es = false,
            extraEnv = mapOf(
                "LIBGL_EGL" to "libEGL_angle.so",
                "BOOXIN_ANGLE_EGL" to "libEGL_angle.so",
                "BOOXIN_ANGLE_GLES" to "libGLESv2_angle.so"
            )
        )
    )

    fun forKind(kind: GlRendererKind): RendererPackage? = all.firstOrNull { it.kind == kind }

    /**
     * Built-ins first, then **BooxinGlues** (26.3+ product), then other plugins.
     * REL metadata stays in [all] for env/token but is not a downloadable plugin row.
     */
    fun selectableKinds(): List<GlRendererKind> =
        listOf(
            GlRendererKind.MOBILE_GLUES,
            GlRendererKind.MCRENDER,
            GlRendererKind.REL,
            GlRendererKind.GL4ES,
            GlRendererKind.BOOXIN_GLUES
        ) + all.map { it.kind }.filter {
            it.requiresPlugin &&
                it != GlRendererKind.BOOXIN_GLUES &&
                it != GlRendererKind.BOOXIN_ZINK
        }
}
