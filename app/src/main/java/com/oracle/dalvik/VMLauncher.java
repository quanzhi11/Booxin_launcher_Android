package com.oracle.dalvik;

/** JNI entry in libpojavexec.so — FCL/Pojav JVM launcher. */
public final class VMLauncher {
    private VMLauncher() {
    }

    public static native int launchJVM(String[] args);
}
