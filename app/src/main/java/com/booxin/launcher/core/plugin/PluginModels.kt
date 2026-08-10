package com.booxin.launcher.core.plugin

import com.booxin.launcher.core.launch.GlRendererKind
import java.io.File

enum class PluginType {
    RENDERER,
    DRIVER
}

enum class PluginSource {
    /** Shipped catalog entry (may need download). */
    BUILTIN,
    /** Extracted under booxin-runtime/plugins or renderers. */
    LOCAL,
    /** Discovered via PackageManager (FCL-style installed APK). */
    PACKAGE
}

/**
 * Unified renderer/driver plugin descriptor (FCL-inspired).
 */
data class PluginDescriptor(
    val id: String,
    val name: String,
    val version: String,
    val type: PluginType,
    val source: PluginSource,
    val enabled: Boolean,
    /** Directory containing extracted .so (null if PACKAGE and libs live in app nativeLibraryDir). */
    val nativeDir: File?,
    val glLib: String,
    val eglLib: String,
    val rendererToken: String,
    val libGlEs: String,
    val extraEnv: Map<String, String> = emptyMap(),
    val downloadUrl: String? = null,
    val packageName: String? = null,
    /** Maps to [GlRendererKind] when known. */
    val kindName: String? = null,
    val installed: Boolean = false,
    val disguiseAsGl4es: Boolean = false
) {
    val kind: GlRendererKind?
        get() = kindName?.let { runCatching { GlRendererKind.valueOf(it) }.getOrNull() }
}
