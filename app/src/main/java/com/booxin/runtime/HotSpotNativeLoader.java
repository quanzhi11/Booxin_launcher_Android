package com.booxin.runtime;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * HotSpot helpers that must not live under {@code org.lwjgl.*}.
 * Early FindClass on org.lwjgl pulls fat-jar LWJGL onto AppClassLoader and breaks Forge.
 */
public final class HotSpotNativeLoader {
    private HotSpotNativeLoader() {}

    public static void loadAbsolute(String absolutePath) {
        System.load(absolutePath);
    }

    /** Log GLFW.Functions pump pointers (safe after LWJGL is on the module layer). */
    public static String pumpDiag() {
        try {
            Class<?> fn = Class.forName("org.lwjgl.glfw.GLFW$Functions");
            long pump = fn.getField("PumpEvents").getLong(null);
            long start = fn.getField("StartPumping").getLong(null);
            long stop = fn.getField("StopPumping").getLong(null);
            return "PumpEvents=0x" + Long.toHexString(pump)
                + " StartPumping=0x" + Long.toHexString(start)
                + " StopPumping=0x" + Long.toHexString(stop);
        } catch (Throwable t) {
            return "pumpDiag-error: " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    /** Overwrite final GLFW.Functions pump pointers via Unsafe (JDK 17+). */
    public static boolean forcePumpFunctionPointers(long start, long pump, long stop) {
        try {
            Class<?> fn = Class.forName("org.lwjgl.glfw.GLFW$Functions");
            Object unsafe = getUnsafe();
            if (unsafe == null) return false;
            Method staticFieldBase = unsafe.getClass().getMethod("staticFieldBase", Field.class);
            Method staticFieldOffset = unsafe.getClass().getMethod("staticFieldOffset", Field.class);
            Method putLong = unsafe.getClass().getMethod("putLong", Object.class, long.class, long.class);
            Field fStart = fn.getField("StartPumping");
            Field fPump = fn.getField("PumpEvents");
            Field fStop = fn.getField("StopPumping");
            Object base = staticFieldBase.invoke(unsafe, fPump);
            putLong.invoke(unsafe, base, (Long) staticFieldOffset.invoke(unsafe, fStart), start);
            putLong.invoke(unsafe, base, (Long) staticFieldOffset.invoke(unsafe, fPump), pump);
            putLong.invoke(unsafe, base, (Long) staticFieldOffset.invoke(unsafe, fStop), stop);
            return fPump.getLong(null) == pump;
        } catch (Throwable t) {
            System.err.println("[BooxinInput] forcePumpFunctionPointers: " + t);
            return false;
        }
    }

    private static Object getUnsafe() {
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            return theUnsafe.get(null);
        } catch (Throwable ignored) {
        }
        try {
            Class<?> unsafeClass = Class.forName("jdk.internal.misc.Unsafe");
            Method getUnsafe = unsafeClass.getDeclaredMethod("getUnsafe");
            getUnsafe.setAccessible(true);
            return getUnsafe.invoke(null);
        } catch (Throwable ignored) {
        }
        return null;
    }
}
