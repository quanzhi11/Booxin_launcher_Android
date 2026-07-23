package org.lwjgl.opengl;

import org.lwjgl.system.FunctionProvider;

/**
 * Replaces FCL lwjgl.jar RendererInit. The stock version calls
 * nativeInitGl4esInternals whenever the GL lib name ends with libgl4es_114.so.
 * We may ship MobileGlues under that filename so pojavexec's opengles3 path
 * works — gl4es-only init must not run.
 */
public class RendererInit {
    public static void onCreateCapabilities(FunctionProvider provider) {
        // no-op: MobileGlues / non-gl4es renderers need no gl4es internals.
    }
}
