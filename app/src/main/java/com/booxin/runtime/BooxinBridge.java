package com.booxin.runtime;

import android.view.Choreographer;

import org.lwjgl.glfw.CallbackBridge;

/**
 * App-facing input bridge. UI should use this, not {@link CallbackBridge} directly.
 */
public final class BooxinBridge {
    private BooxinBridge() {}

    public interface GrabListener {
        void onGrabState(boolean grabbing);
    }

    public static final Choreographer sChoreographer = CallbackBridge.sChoreographer;

    public static int getWindowWidth() {
        return CallbackBridge.windowWidth;
    }

    public static int getWindowHeight() {
        return CallbackBridge.windowHeight;
    }

    public static void setWindowSize(int w, int h) {
        CallbackBridge.windowWidth = w;
        CallbackBridge.windowHeight = h;
        CallbackBridge.physicalWidth = w;
        CallbackBridge.physicalHeight = h;
    }

    public static void setGrabListener(GrabListener listener) {
        if (listener == null) {
            CallbackBridge.setGrabListener(null);
            return;
        }
        CallbackBridge.setGrabListener(listener::onGrabState);
    }

    public static boolean enableAndroidInput() {
        return CallbackBridge.enableAndroidInput();
    }

    public static void setInputReady(boolean ready) {
        CallbackBridge.setInputReady(ready);
    }

    public static boolean areNativesLinked() {
        return CallbackBridge.areNativesLinked();
    }

    public static boolean isGrabbing() {
        return CallbackBridge.isGrabbing();
    }

    public static void sendCursorPos(float x, float y) {
        CallbackBridge.sendCursorPos(x, y);
    }

    public static void sendMouseButton(int button, boolean pressed) {
        CallbackBridge.sendMouseButton(button, pressed);
    }

    public static void sendMouseClick(int button) {
        CallbackBridge.sendMouseClick(button);
    }

    public static void putMouseEventWithCoords(int button, float x, float y) {
        CallbackBridge.putMouseEventWithCoords(button, x, y);
    }

    public static void sendKey(int key, boolean pressed) {
        CallbackBridge.sendKey(key, pressed);
    }

    public static void sendKeyTap(int key) {
        CallbackBridge.sendKeyTap(key);
    }

    public static void sendChar(char codepoint) {
        CallbackBridge.sendChar(codepoint);
    }

    public static void sendScroll(double xoffset, double yoffset) {
        CallbackBridge.sendScroll(xoffset, yoffset);
    }

    public static void sendUpdateWindowSize(int w, int h) {
        CallbackBridge.sendUpdateWindowSize(w, h);
    }
}
