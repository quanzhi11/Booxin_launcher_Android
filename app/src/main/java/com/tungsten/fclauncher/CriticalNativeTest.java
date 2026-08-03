package com.tungsten.fclauncher;

import dalvik.annotation.optimization.CriticalNative;

/**
 * ABI probe required by the transitional exec native ({@code libpojavexec.so}).
 * <p>
 * Package name {@code com.tungsten.fclauncher} is hard-coded in that binary's
 * RegisterNatives lookup — do not rename until {@code libbooxin_bridge} replaces it.
 * Not an FCL source dependency; Booxin keeps this stub solely for the transitional ABI.
 */
public class CriticalNativeTest {
    @CriticalNative
    public static native void testCriticalNative(int arg0, int arg1);

    public static void invokeTest() {
        testCriticalNative(0, 0);
    }
}
