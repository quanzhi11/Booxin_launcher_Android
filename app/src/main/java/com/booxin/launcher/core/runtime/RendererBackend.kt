package com.booxin.launcher.core.runtime

import com.booxin.launcher.core.launch.GlRendererKind
import com.booxin.launcher.core.launch.GlRendererProfile

/** Picks GLES translator for a version id. */
object RendererBackend {
    fun kindForVersion(versionId: String): GlRendererKind = GlRendererProfile.forVersion(versionId)

    fun displayName(kind: GlRendererKind): String = RuntimeEnv.libGlString(kind)

    fun booxinRendererToken(kind: GlRendererKind): String = RuntimeEnv.rendererToken(kind)
}
