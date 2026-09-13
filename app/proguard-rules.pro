# Booxin Launcher — keep rules for release builds (R8).

# --- JNI / runtime ABI (must not be renamed or stripped) ---
-keep class com.booxin.runtime.BooxinBridge { *; }
-keep class com.booxin.runtime.HotSpotNativeLoader { *; }
-keep class org.lwjgl.glfw.CallbackBridge { *; }
-keep class org.lwjgl.glfw.BooxinInputHooks { *; }
-keep class org.lwjgl.glfw.BooxinBridgeLoader { *; }
-keep class org.lwjgl.glfw.BooxinPojavLoader { *; }
-keep class org.lwjgl.opengl.RendererInit { *; }
-keep class com.tungsten.fclauncher.CriticalNativeTest { *; }

# SDL 26.3+: jre_launcher / bridge FindClass by literal class + method names.
-keep class com.booxin.launcher.core.launch.BooxinSdlBootstrap { *; }
-keepclassmembers class com.booxin.launcher.core.launch.BooxinSdlBootstrap {
    public static boolean finishSdlAndroidInitFromArt();
    public static boolean resyncNativeSurface();
    public static boolean reattachSurface(android.content.Context, android.view.Surface);
}

-keepclasseswithmembernames class * {
    native <methods>;
}

-keepclassmembers class com.booxin.launcher.core.launch.NativeJvmLauncher {
    native <methods>;
}

# LWJGL / bridge may reflect on these
-keep class org.lwjgl.** { *; }

# Official zlib SDL Android Java — JNI_OnLoad FindClass by string name.
-keep class org.libsdl.app.** { *; }
-keepclassmembers class org.libsdl.app.** { *; }

# App entry / multi-process
-keep class com.booxin.launcher.BooxinApp { *; }
-keep class com.booxin.launcher.ui.launch.LaunchActivity { *; }
-keep class com.booxin.launcher.core.launch.GameLaunchService { *; }

# ViewBinding / AndroidX
-keep class * implements androidx.viewbinding.ViewBinding { *; }

# Kotlin
-dontwarn kotlin.**
-dontwarn kotlinx.coroutines.**

# OkHttp / Conscrypt optional
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.codehaus.mojo.animal_sniffer.**

# Community auto-translate (keep for release debugging / reflection-safe)
-keep class com.booxin.launcher.core.community.CommunityDescriptionTranslator { *; }
