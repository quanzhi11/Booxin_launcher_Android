package org.lwjgl.opengl;

import org.lwjgl.system.FunctionProvider;
import org.lwjgl.system.SharedLibrary;

/**
 * FCL/Pojav RendererInit: call gl4es internals only when LWJGL actually loads
 * {@code libgl4es_114.so}. MobileGlues is loaded via {@code libmobileglues.so},
 * so it must not hit {@link #nativeInitGl4esInternals}.
 */
public class RendererInit {
    public static void onCreateCapabilities(FunctionProvider provider) {
        String name = null;
        if (provider instanceof SharedLibrary) {
            name = ((SharedLibrary) provider).getName();
        }
        if (!isValidString(name)) {
            name = System.getProperty("org.lwjgl.opengl.libname");
        }
        if (!isValidString(name)) {
            System.out.println(
                "PojavRendererInit: Failed to find Pojav renderer name! " +
                    "Renderer-specific initialization may not work properly"
            );
            return;
        }
        if (name.endsWith("libgl4es_114.so")) {
            nativeInitGl4esInternals(provider);
        }
    }

    private static boolean isValidString(String value) {
        return value != null && !value.isEmpty();
    }

    public static native void nativeInitGl4esInternals(FunctionProvider provider);
}
