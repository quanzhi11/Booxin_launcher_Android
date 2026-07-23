package org.lwjgl.glfw;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

/**
 * Android-side GLFW bridge for libpojavexec.so. Must live in the app dex so
 * {@link com.booxin.launcher.core.launch.GameSurfaceBridge} can call setupBridgeWindow
 * before the JVM starts. The JVM loads this same class from the APK on java.class.path.
 */
public class CallbackBridge {
    public static final int CLIPBOARD_COPY = 2000;
    public static final int CLIPBOARD_PASTE = 2001;
    public static final int CLIPBOARD_OPEN = 2002;

    public static final int EVENT_TYPE_CHAR = 1000;
    public static final int EVENT_TYPE_CHAR_MODS = 1001;
    public static final int EVENT_TYPE_CURSOR_ENTER = 1002;
    public static final int EVENT_TYPE_CURSOR_POS = 1003;
    public static final int EVENT_TYPE_FRAMEBUFFER_SIZE = 1004;
    public static final int EVENT_TYPE_KEY = 1005;
    public static final int EVENT_TYPE_MOUSE_BUTTON = 1006;
    public static final int EVENT_TYPE_SCROLL = 1007;
    public static final int EVENT_TYPE_WINDOW_SIZE = 1008;

    public static final int ANDROID_TYPE_GRAB_STATE = 0;

    public static final boolean INPUT_DEBUG_ENABLED;

    private static volatile boolean isGrabbing;

    static {
        INPUT_DEBUG_ENABLED = Boolean.parseBoolean(System.getProperty("glfwstub.debugInput", "false"));
    }

    public static void sendData(int type, String data) {
        nativeSendData(false, type, data);
    }

    /** Called from libpojavexec during JNI_OnLoad. */
    @SuppressWarnings("unused")
    public static String accessAndroidClipboard(int type, String copy) {
        // Context is obtained via the app's registered singleton, not ActivityThread.
        Context context = com.booxin.launcher.BooxinApp.getAppContext();
        if (context == null) {
            return "";
        }
        ClipboardManager clipboard =
            (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            return "";
        }
        switch (type) {
            case CLIPBOARD_COPY:
                clipboard.setPrimaryClip(ClipData.newPlainText("Booxin Clipboard", copy));
                return null;
            case CLIPBOARD_PASTE:
                if (clipboard.hasPrimaryClip()
                    && clipboard.getPrimaryClipDescription() != null
                    && clipboard.getPrimaryClipDescription().hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN)
                    && clipboard.getPrimaryClip() != null
                    && clipboard.getPrimaryClip().getItemCount() > 0
                    && clipboard.getPrimaryClip().getItemAt(0).getText() != null) {
                    return clipboard.getPrimaryClip().getItemAt(0).getText().toString();
                }
                return "";
            case CLIPBOARD_OPEN:
                if (copy != null && !copy.isEmpty()) {
                    try {
                        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(copy));
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        context.startActivity(intent);
                    } catch (RuntimeException ignored) {
                        // Ignore malformed URLs or missing handlers.
                    }
                }
                return null;
            default:
                return "";
        }
    }

    /** Called from libpojavexec when GLFW grab state changes. */
    @SuppressWarnings("unused")
    private static void onGrabStateChanged(boolean grabbing) {
        isGrabbing = grabbing;
    }

    public static boolean isGrabbing() {
        return isGrabbing;
    }

    public static native void nativeSendData(boolean isAndroid, int type, String data);
    public static native boolean nativeSetInputReady(boolean ready);
    public static native String nativeClipboard(int action, byte[] copy);
    public static native void nativeSetGrabbing(boolean grab);

    public static native void nativeSetUseInputStackQueue(boolean useInputStackQueue);
    public static native boolean nativeSendChar(char codepoint);
    public static native boolean nativeSendCharMods(char codepoint, int mods);
    public static native void nativeSendKey(int key, int scancode, int action, int mods);
    public static native void nativeSendCursorPos(float x, float y);
    public static native void nativeSendMouseButton(int button, int action, int mods);
    public static native void nativeSendScroll(double xoffset, double yoffset);
    public static native void nativeSendScreenSize(int width, int height);
    public static native void nativeSetWindowAttrib(int attrib, int value);
    public static native void setupBridgeWindow(Object surface);
    public static native int getFps();
}
