package com.tungsten.fclauncher;

import dalvik.annotation.optimization.CriticalNative;

/**
 * Required by libpojavexec RegisterNatives probe (FCL CriticalNativeTest).
 * Enables the faster CriticalNative input path when available.
 */
public class CriticalNativeTest {
    @CriticalNative
    public static native void testCriticalNative(int arg0, int arg1);

    public static void invokeTest() {
        testCriticalNative(0, 0);
    }
}
