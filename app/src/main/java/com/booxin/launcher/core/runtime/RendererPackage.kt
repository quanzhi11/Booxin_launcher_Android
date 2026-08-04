package com.booxin.launcher.core.runtime

import com.booxin.launcher.core.launch.GlRendererKind

/**
 * Downloadable renderer plugin metadata (FCL/Zalith-compatible APKs).
 * Libs are extracted for the device ABI into `runtime/renderers/<id>/`.
 */
data class RendererPackage(
    val id: String,
    val kind: GlRendererKind,
    val downloadUrl: String,
    /** Primary GL translator / OSMesa soname inside the APK. */
    val glLib: String,
    /** EGL provider — `libEGL.so` means system EGL. */
    val eglLib: String,
    val rendererToken: String,
    val libGlEs: String,
    val extraEnv: Map<String, String> = emptyMap(),
    /** When true, stage glLib as libgl4es_114.so (LWJGL GLES path). */
    val disguiseAsGl4es: Boolean = false
)

object RendererPackages {
    private const val FCL_PLUGIN_BASE =
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
            downloadUrl = "$FCL_PLUGIN_BASE/LTW-2025.7.16.apk",
            glLib = "libltw.so",
            eglLib = "libEGL.so",
            rendererToken = "opengles3_ltw",
            libGlEs = "3",
            disguiseAsGl4es = true
        ),
        RendererPackage(
            id = "zink",
            kind = GlRendererKind.VULKAN_ZINK,
            downloadUrl = "$FCL_PLUGIN_BASE/Zink.Mesa25.apk",
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
            downloadUrl = "$FCL_PLUGIN_BASE/Mesa.24.3.4.APK",
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
            downloadUrl = "$FCL_PLUGIN_BASE/Mesa.24.3.4.APK",
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
        )
    )

    fun forKind(kind: GlRendererKind): RendererPackage? = all.firstOrNull { it.kind == kind }

    fun selectableKinds(): List<GlRendererKind> = listOf(
        GlRendererKind.GL4ES,
        GlRendererKind.MOBILE_GLUES
    ) + all.map { it.kind }
}
