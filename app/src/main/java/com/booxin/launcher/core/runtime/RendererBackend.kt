package com.booxin.launcher.core.runtime

import com.booxin.launcher.core.launch.GlRendererKind
import com.booxin.launcher.core.launch.GlRendererProfile

/**
 * Booxin-facing renderer selection. Keeps GLES translator choice out of UI/business code.
 * See docs/RENDERER_LICENSE.md for third-party license notes.
 */
object RendererBackend {
    fun kindForVersion(versionId: String): GlRendererKind = GlRendererProfile.forVersion(versionId)

    fun displayName(kind: GlRendererKind): String = RuntimeEnv.libGlString(kind)

    fun booxinRendererToken(kind: GlRendererKind): String = RuntimeEnv.rendererToken(kind)
}
