package org.lwjgl.glfw;

/**
 * JNI surface for FCL libpojavexec.so RegisterNatives.
 * Merges lwjgl.jar stubs (GLFW.java) with pojavexec input natives.
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

    @SuppressWarnings("unused")
    public static String accessAndroidClipboard(int type, String copy) {
        return "";
    }

    @SuppressWarnings("unused")
    private static void onGrabStateChanged(boolean grabbing) {
        isGrabbing = grabbing;
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
