package com.oracle.dalvik;

/** JNI entry used by the embedded JVM launcher. */
public final class VMLauncher {
    private VMLauncher() {
    }

    public static native int launchJVM(String[] args);
}
