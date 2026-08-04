package org.lwjgl.glfw;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.view.Choreographer;

import dalvik.annotation.optimization.CriticalNative;

/**
 * HotSpot / JNI ABI class required by the transitional exec native.
 * <p>
 * ART and UI code must use {@link com.booxin.runtime.BooxinBridge} instead.
 * This class remains under {@code org.lwjgl.glfw} because the native RegisterNatives
 * table and LWJGL bridge patch look up that exact name.
 */
public class CallbackBridge {
    private static final String TAG = "BooxinInput";

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
    public static final Choreographer sChoreographer = Choreographer.getInstance();

    /** Logical GLFW framebuffer size (must match Surface / glfwstub props). */
    public static volatile int windowWidth;
    public static volatile int windowHeight;
    public static volatile int physicalWidth;
    public static volatile int physicalHeight;

    public static volatile float mouseX;
    public static volatile float mouseY;

    private static volatile boolean isGrabbing;
    private static volatile boolean stackQueueEnabled;
    private static volatile boolean nativesLinked;
    private static volatile GrabListener grabListener;
    private static volatile boolean linkErrorLogged;
    private static volatile boolean directMouseFallbackTried;
    private static volatile long lastReadyPumpMs;

    public interface GrabListener {
        void onGrabState(boolean grabbing);
    }

    static {
        INPUT_DEBUG_ENABLED = Boolean.parseBoolean(System.getProperty("glfwstub.debugInput", "false"));
    }

    public static void setGrabListener(GrabListener listener) {
        grabListener = listener;
        if (listener != null) {
            listener.onGrabState(isGrabbing);
        }
    }

    public static void sendData(int type, String data) {
        try {
            nativeSendData(false, type, data);
        } catch (UnsatisfiedLinkError | Exception e) {
            logLinkOnce("nativeSendData", e);
        }
    }

    /**
     * Move cursor, press, auto-release after 33ms.
     * @param button GLFW mouse button (0=LMB, 1=RMB, 2=MMB)
     * @param x game/GLFW X
     * @param y game/GLFW Y
     */
    public static void putMouseEventWithCoords(int button, float x, float y) {
        putMouseEventWithCoords(button, true, x, y);
        sChoreographer.postFrameCallbackDelayed(
            frameTimeNanos -> putMouseEventWithCoords(button, false, x, y), 33);
    }

    public static void putMouseEventWithCoords(int button, boolean isDown, float x, float y) {
        sendCursorPos(x, y);
        sendMouseButton(button, isDown);
    }

    /** GLFW window-space cursor. */
    public static void sendCursorPos(float x, float y) {
        mouseX = x;
        mouseY = y;
        try {
            nativeSendCursorPos(x, y);
            nativesLinked = true;
        } catch (UnsatisfiedLinkError | Exception e) {
            logLinkOnce("nativeSendCursorPos", e);
        }
    }

    public static void sendMouseButton(int button, boolean pressed) {
        try {
            nativeSendMouseButton(button, pressed ? 1 : 0, 0);
            nativesLinked = true;
        } catch (UnsatisfiedLinkError | Exception e) {
            logLinkOnce("nativeSendMouseButton", e);
        }
    }

    public static void sendMouseClick(int button) {
        sendMouseButton(button, true);
        sendMouseButton(button, false);
    }

    public static void sendKey(int key, boolean pressed) {
        try {
            nativeSendKey(key, 0, pressed ? 1 : 0, 0);
            nativesLinked = true;
        } catch (UnsatisfiedLinkError | Exception e) {
            logLinkOnce("nativeSendKey", e);
        }
    }

    public static void sendKeyTap(int key) {
        sendKey(key, true);
        sendKey(key, false);
    }

    public static void sendChar(char codepoint) {
        try {
            nativeSendChar(codepoint);
            nativeSendCharMods(codepoint, 0);
            nativesLinked = true;
        } catch (UnsatisfiedLinkError | Exception e) {
            logLinkOnce("nativeSendChar", e);
        }
    }

    public static void sendScroll(double xoffset, double yoffset) {
        try {
            nativeSendScroll(xoffset, yoffset);
            nativesLinked = true;
        } catch (UnsatisfiedLinkError | Exception e) {
            logLinkOnce("nativeSendScroll", e);
        }
    }

    public static void sendUpdateWindowSize(int w, int h) {
        try {
            nativeSendScreenSize(w, h);
            nativesLinked = true;
        } catch (UnsatisfiedLinkError | Exception e) {
            logLinkOnce("nativeSendScreenSize", e);
        }
    }

    public static void setInputReady(boolean ready) {
        try {
            nativeSetInputReady(ready);
        } catch (UnsatisfiedLinkError | Exception ignored) {
            // Bridge may not be loaded yet.
        }
    }

    /** Turn on stack-queue input (needed so glfwGetCursorPos sees moves). */
    public static boolean enableAndroidInput() {
        try {
            nativeSetUseInputStackQueue(true);
            boolean ready = nativeSetInputReady(true);
            boolean first = !stackQueueEnabled;
            stackQueueEnabled = true;
            nativesLinked = true;
            if (first) {
                Log.i(TAG, "enableAndroidInput stackQueue=true ready=" + ready);
            }
            return true;
        } catch (UnsatisfiedLinkError | Exception e) {
            stackQueueEnabled = false;
            logLinkOnce("enableAndroidInput", e);
            return false;
        }
    }

    public static boolean isStackQueueEnabled() {
        return stackQueueEnabled;
    }

    public static boolean areNativesLinked() {
        return nativesLinked;
    }

    private static void logLinkOnce(String where, Throwable e) {
        if (linkErrorLogged) return;
        // Don't stick on early "library not loaded" — wait for a real failure.
        if (!(e instanceof UnsatisfiedLinkError) || nativesLinked) {
            linkErrorLogged = true;
        }
        Log.e(TAG, where + " failed (bridge not ready?): " + e.getMessage());
    }

    private static void maybeSwitchToDirectMousePath() {
        // Off: without stack-queue, cursor pos stays at 0,0.
    }

    private static void maybePumpReadyBridge(boolean fromButton) {
        // Pump runs on the game render thread.
    }

    @SuppressWarnings("unused")
    public static String accessAndroidClipboard(int type, String copy) {
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

    /** Native → UI grab change (slight delay so UI doesn't thrash). */
    @SuppressWarnings("unused")
    public static void onGrabStateChanged(final boolean grabbing) {
        isGrabbing = grabbing;
        Log.i(TAG, "onGrabStateChanged grabbing=" + grabbing);
        final GrabListener listener = grabListener;
        if (listener == null) return;
        sChoreographer.postFrameCallbackDelayed(frameTimeNanos -> {
            if (isGrabbing != grabbing) return;
            listener.onGrabState(grabbing);
        }, 16);
    }

    public static boolean isGrabbing() {
        return isGrabbing;
    }

    public static native void nativeSendData(boolean isAndroid, int type, String data);
    public static native boolean nativeSetInputReady(boolean ready);
    public static native String nativeClipboard(int action, byte[] copy);
    public static native void nativeSetGrabbing(boolean grab);

    // @CriticalNative: wrong ABI = broken touch. Keep private.
    @CriticalNative
    private static native void nativeSetUseInputStackQueue(boolean useInputStackQueue);

    @CriticalNative
    private static native boolean nativeSendChar(char codepoint);

    @CriticalNative
    private static native boolean nativeSendCharMods(char codepoint, int mods);

    @CriticalNative
    private static native void nativeSendKey(int key, int scancode, int action, int mods);

    @CriticalNative
    private static native void nativeSendCursorPos(float x, float y);

    @CriticalNative
    private static native void nativeSendMouseButton(int button, int action, int mods);

    @CriticalNative
    private static native void nativeSendScroll(double xoffset, double yoffset);

    @CriticalNative
    private static native void nativeSendScreenSize(int width, int height);

    public static native void nativeSetWindowAttrib(int attrib, int value);
    public static native void setupBridgeWindow(Object surface);
    public static native int getFps();
}
