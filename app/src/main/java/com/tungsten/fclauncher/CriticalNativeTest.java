package com.tungsten.fclauncher;

import dalvik.annotation.optimization.CriticalNative;

/** Tiny native probe; keep this package name — some loaders still look it up. */
public class CriticalNativeTest {
    @CriticalNative
    public static native void testCriticalNative(int arg0, int arg1);

    public static void invokeTest() {
        testCriticalNative(0, 0);
    }
}
