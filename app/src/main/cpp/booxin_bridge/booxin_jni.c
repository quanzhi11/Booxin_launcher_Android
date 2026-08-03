#include "booxin_environ.h"

#include <android/log.h>
#include <android/native_window_jni.h>
#include <jni.h>
#include <stdlib.h>
#include <string.h>

#define LOG_TAG "BooxinBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

/* Declared in other TUs */
void critical_set_stackqueue(jboolean use);
void critical_send_cursor_pos(jfloat x, jfloat y);
void critical_send_mouse_button(jint button, jint action, jint mods);
void critical_send_key(jint key, jint scancode, jint action, jint mods);
jboolean critical_send_char(jchar codepoint);
jboolean critical_send_char_mods(jchar codepoint, jint mods);
void critical_send_scroll(jdouble xoffset, jdouble yoffset);
void critical_send_screen_size(jint width, jint height);
void booxin_environ_init(void);
int pojavInit(void);
int pojavInitOpenGL(void);

static JavaVM *g_vm = NULL;

JNIEXPORT void JNICALL
Java_org_lwjgl_glfw_CallbackBridge_setupBridgeWindow(JNIEnv *env, jclass cls, jobject surface) {
    (void)cls;
    if (!pojav_environ) booxin_environ_init();
    if (pojav_environ->nativeWindow) {
        ANativeWindow_release((ANativeWindow *)pojav_environ->nativeWindow);
        pojav_environ->nativeWindow = NULL;
    }
    if (surface) {
        pojav_environ->nativeWindow = ANativeWindow_fromSurface(env, surface);
        if (pojav_environ->nativeWindow) {
            pojav_environ->savedWidth =
                ANativeWindow_getWidth((ANativeWindow *)pojav_environ->nativeWindow);
            pojav_environ->savedHeight =
                ANativeWindow_getHeight((ANativeWindow *)pojav_environ->nativeWindow);
            LOGI("setupBridgeWindow %dx%d", pojav_environ->savedWidth, pojav_environ->savedHeight);
        }
    }
}

JNIEXPORT void JNICALL
Java_net_kdt_pojavlaunch_utils_JREUtils_releaseBridgeWindow(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    if (pojav_environ && pojav_environ->nativeWindow) {
        ANativeWindow_release((ANativeWindow *)pojav_environ->nativeWindow);
        pojav_environ->nativeWindow = NULL;
    }
}

JNIEXPORT jboolean JNICALL
Java_org_lwjgl_glfw_CallbackBridge_nativeSetInputReady(JNIEnv *env, jclass cls, jboolean ready) {
    (void)env; (void)cls;
    if (pojav_environ) pojav_environ->isInputReady = ready == JNI_TRUE;
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
JavaCritical_org_lwjgl_glfw_CallbackBridge_nativeSetInputReady(jboolean ready) {
    if (pojav_environ) pojav_environ->isInputReady = ready == JNI_TRUE;
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_org_lwjgl_glfw_CallbackBridge_nativeSetGrabbing(JNIEnv *env, jclass cls, jboolean grab) {
    (void)cls;
    if (!pojav_environ) return;
    pojav_environ->isGrabbing = grab;
    if (pojav_environ->bridgeClazz && pojav_environ->method_onGrabStateChanged && env) {
        (*env)->CallStaticVoidMethod(
            env, pojav_environ->bridgeClazz, pojav_environ->method_onGrabStateChanged, grab);
    }
}

JNIEXPORT void JNICALL
Java_org_lwjgl_glfw_CallbackBridge_nativeSetWindowAttrib(JNIEnv *env, jclass cls, jint attrib, jint value) {
    (void)env; (void)cls; (void)attrib; (void)value;
}

JNIEXPORT jint JNICALL
Java_org_lwjgl_glfw_CallbackBridge_getFps(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    return 0;
}

JNIEXPORT void JNICALL
Java_org_lwjgl_glfw_CallbackBridge_nativeSendData(JNIEnv *env, jclass cls, jboolean isAndroid, jint type, jstring data) {
    (void)env; (void)cls; (void)isAndroid; (void)type; (void)data;
}

JNIEXPORT jstring JNICALL
Java_org_lwjgl_glfw_CallbackBridge_nativeClipboard(JNIEnv *env, jclass cls, jint action, jbyteArray copy) {
    (void)cls; (void)action; (void)copy;
    return (*env)->NewStringUTF(env, "");
}

/* @CriticalNative entry points (JavaCritical_* + RegisterNatives). */
JNIEXPORT void JNICALL
Java_org_lwjgl_glfw_CallbackBridge_nativeSetUseInputStackQueue(JNIEnv *env, jclass cls, jboolean use) {
    (void)env; (void)cls;
    critical_set_stackqueue(use);
}

JNIEXPORT void JNICALL
JavaCritical_org_lwjgl_glfw_CallbackBridge_nativeSetUseInputStackQueue(jboolean use) {
    critical_set_stackqueue(use);
}

JNIEXPORT jboolean JNICALL
Java_org_lwjgl_glfw_CallbackBridge_nativeSendChar(JNIEnv *env, jclass cls, jchar codepoint) {
    (void)env; (void)cls;
    return critical_send_char(codepoint);
}
JNIEXPORT jboolean JNICALL
JavaCritical_org_lwjgl_glfw_CallbackBridge_nativeSendChar(jchar codepoint) {
    return critical_send_char(codepoint);
}

JNIEXPORT jboolean JNICALL
Java_org_lwjgl_glfw_CallbackBridge_nativeSendCharMods(JNIEnv *env, jclass cls, jchar codepoint, jint mods) {
    (void)env; (void)cls;
    return critical_send_char_mods(codepoint, mods);
}
JNIEXPORT jboolean JNICALL
JavaCritical_org_lwjgl_glfw_CallbackBridge_nativeSendCharMods(jchar codepoint, jint mods) {
    return critical_send_char_mods(codepoint, mods);
}

JNIEXPORT void JNICALL
Java_org_lwjgl_glfw_CallbackBridge_nativeSendKey(JNIEnv *env, jclass cls, jint key, jint scancode, jint action, jint mods) {
    (void)env; (void)cls;
    critical_send_key(key, scancode, action, mods);
}
JNIEXPORT void JNICALL
JavaCritical_org_lwjgl_glfw_CallbackBridge_nativeSendKey(jint key, jint scancode, jint action, jint mods) {
    critical_send_key(key, scancode, action, mods);
}

JNIEXPORT void JNICALL
Java_org_lwjgl_glfw_CallbackBridge_nativeSendCursorPos(JNIEnv *env, jclass cls, jfloat x, jfloat y) {
    (void)env; (void)cls;
    critical_send_cursor_pos(x, y);
}
JNIEXPORT void JNICALL
JavaCritical_org_lwjgl_glfw_CallbackBridge_nativeSendCursorPos(jfloat x, jfloat y) {
    critical_send_cursor_pos(x, y);
}

JNIEXPORT void JNICALL
Java_org_lwjgl_glfw_CallbackBridge_nativeSendMouseButton(JNIEnv *env, jclass cls, jint button, jint action, jint mods) {
    (void)env; (void)cls;
    critical_send_mouse_button(button, action, mods);
}
JNIEXPORT void JNICALL
JavaCritical_org_lwjgl_glfw_CallbackBridge_nativeSendMouseButton(jint button, jint action, jint mods) {
    critical_send_mouse_button(button, action, mods);
}

JNIEXPORT void JNICALL
Java_org_lwjgl_glfw_CallbackBridge_nativeSendScroll(JNIEnv *env, jclass cls, jdouble xoffset, jdouble yoffset) {
    (void)env; (void)cls;
    critical_send_scroll(xoffset, yoffset);
}
JNIEXPORT void JNICALL
JavaCritical_org_lwjgl_glfw_CallbackBridge_nativeSendScroll(jdouble xoffset, jdouble yoffset) {
    critical_send_scroll(xoffset, yoffset);
}

JNIEXPORT void JNICALL
Java_org_lwjgl_glfw_CallbackBridge_nativeSendScreenSize(JNIEnv *env, jclass cls, jint width, jint height) {
    (void)env; (void)cls;
    critical_send_screen_size(width, height);
}
JNIEXPORT void JNICALL
JavaCritical_org_lwjgl_glfw_CallbackBridge_nativeSendScreenSize(jint width, jint height) {
    critical_send_screen_size(width, height);
}

#define SET_CB(field, value) do { if (pojav_environ) pojav_environ->field = (void *)(intptr_t)(value); } while (0)

JNIEXPORT jlong JNICALL
Java_org_lwjgl_glfw_GLFW_nglfwSetCharCallback(JNIEnv *env, jclass cls, jlong window, jlong cb) {
    (void)env; (void)cls; (void)window;
    jlong old = pojav_environ ? (jlong)(intptr_t)pojav_environ->GLFW_invoke_Char : 0;
    SET_CB(GLFW_invoke_Char, cb);
    return old;
}
JNIEXPORT jlong JNICALL
Java_org_lwjgl_glfw_GLFW_nglfwSetCharModsCallback(JNIEnv *env, jclass cls, jlong window, jlong cb) {
    (void)env; (void)cls; (void)window;
    jlong old = pojav_environ ? (jlong)(intptr_t)pojav_environ->GLFW_invoke_CharMods : 0;
    SET_CB(GLFW_invoke_CharMods, cb);
    return old;
}
JNIEXPORT jlong JNICALL
Java_org_lwjgl_glfw_GLFW_nglfwSetCursorEnterCallback(JNIEnv *env, jclass cls, jlong window, jlong cb) {
    (void)env; (void)cls; (void)window;
    jlong old = pojav_environ ? (jlong)(intptr_t)pojav_environ->GLFW_invoke_CursorEnter : 0;
    SET_CB(GLFW_invoke_CursorEnter, cb);
    return old;
}
JNIEXPORT jlong JNICALL
Java_org_lwjgl_glfw_GLFW_nglfwSetCursorPosCallback(JNIEnv *env, jclass cls, jlong window, jlong cb) {
    (void)env; (void)cls; (void)window;
    jlong old = pojav_environ ? (jlong)(intptr_t)pojav_environ->GLFW_invoke_CursorPos : 0;
    SET_CB(GLFW_invoke_CursorPos, cb);
    return old;
}
JNIEXPORT jlong JNICALL
Java_org_lwjgl_glfw_GLFW_nglfwSetFramebufferSizeCallback(JNIEnv *env, jclass cls, jlong window, jlong cb) {
    (void)env; (void)cls; (void)window;
    jlong old = pojav_environ ? (jlong)(intptr_t)pojav_environ->GLFW_invoke_FramebufferSize : 0;
    SET_CB(GLFW_invoke_FramebufferSize, cb);
    return old;
}
JNIEXPORT jlong JNICALL
Java_org_lwjgl_glfw_GLFW_nglfwSetKeyCallback(JNIEnv *env, jclass cls, jlong window, jlong cb) {
    (void)env; (void)cls; (void)window;
    jlong old = pojav_environ ? (jlong)(intptr_t)pojav_environ->GLFW_invoke_Key : 0;
    SET_CB(GLFW_invoke_Key, cb);
    return old;
}
JNIEXPORT jlong JNICALL
Java_org_lwjgl_glfw_GLFW_nglfwSetMouseButtonCallback(JNIEnv *env, jclass cls, jlong window, jlong cb) {
    (void)env; (void)cls; (void)window;
    jlong old = pojav_environ ? (jlong)(intptr_t)pojav_environ->GLFW_invoke_MouseButton : 0;
    SET_CB(GLFW_invoke_MouseButton, cb);
    return old;
}
JNIEXPORT jlong JNICALL
Java_org_lwjgl_glfw_GLFW_nglfwSetScrollCallback(JNIEnv *env, jclass cls, jlong window, jlong cb) {
    (void)env; (void)cls; (void)window;
    jlong old = pojav_environ ? (jlong)(intptr_t)pojav_environ->GLFW_invoke_Scroll : 0;
    SET_CB(GLFW_invoke_Scroll, cb);
    return old;
}
JNIEXPORT jlong JNICALL
Java_org_lwjgl_glfw_GLFW_nglfwSetWindowSizeCallback(JNIEnv *env, jclass cls, jlong window, jlong cb) {
    (void)env; (void)cls; (void)window;
    jlong old = pojav_environ ? (jlong)(intptr_t)pojav_environ->GLFW_invoke_WindowSize : 0;
    SET_CB(GLFW_invoke_WindowSize, cb);
    return old;
}

JNIEXPORT void JNICALL
Java_org_lwjgl_glfw_GLFW_nglfwSetShowingWindow(JNIEnv *env, jclass cls, jlong window) {
    (void)env; (void)cls;
    if (pojav_environ) pojav_environ->showingWindow = (long)window;
}

JNIEXPORT void JNICALL
Java_org_lwjgl_glfw_GLFW_nglfwGetCursorPos(JNIEnv *env, jclass cls, jlong window, jlong xpos, jlong ypos) {
    (void)env; (void)cls; (void)window;
    if (!pojav_environ) return;
    if (xpos) *((double *)(intptr_t)xpos) = pojav_environ->cursorX;
    if (ypos) *((double *)(intptr_t)ypos) = pojav_environ->cursorY;
}

JNIEXPORT void JNICALL
Java_org_lwjgl_glfw_GLFW_nglfwGetCursorPosA(JNIEnv *env, jclass cls, jlong window, jdoubleArray xpos, jdoubleArray ypos) {
    (void)window;
    if (!pojav_environ) return;
    if (xpos) {
        jdouble v = pojav_environ->cursorX;
        (*env)->SetDoubleArrayRegion(env, xpos, 0, 1, &v);
    }
    if (ypos) {
        jdouble v = pojav_environ->cursorY;
        (*env)->SetDoubleArrayRegion(env, ypos, 0, 1, &v);
    }
}

JNIEXPORT void JNICALL
JavaCritical_org_lwjgl_glfw_GLFW_nglfwGetCursorPosA(jlong window, jdouble *xpos, jdouble *ypos) {
    (void)window;
    if (!pojav_environ) return;
    if (xpos) *xpos = pojav_environ->cursorX;
    if (ypos) *ypos = pojav_environ->cursorY;
}

JNIEXPORT void JNICALL
Java_org_lwjgl_glfw_GLFW_glfwSetCursorPos(JNIEnv *env, jclass cls, jlong window, jdouble x, jdouble y) {
    (void)env; (void)cls; (void)window;
    critical_send_cursor_pos((jfloat)x, (jfloat)y);
}

JNIEXPORT void JNICALL
JavaCritical_org_lwjgl_glfw_GLFW_glfwSetCursorPos(jlong window, jdouble x, jdouble y) {
    (void)window;
    critical_send_cursor_pos((jfloat)x, (jfloat)y);
}

JNIEXPORT void JNICALL
Java_org_lwjgl_opengl_RendererInit_nativeInitGl4esInternals(JNIEnv *env, jclass cls, jobject provider) {
    (void)env; (void)cls; (void)provider;
    LOGI("RendererInit: Booxin bridge (no gl4es internals hook)");
}

JNIEXPORT jlong JNICALL
Java_org_lwjgl_vulkan_VK_getVulkanDriverHandle(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    return 0;
}

JNIEXPORT jlong JNICALL
Java_org_lwjgl_vulkan_VK_getFpsAddress(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    return 0;
}

/* Old ABI probe; package name is hardcoded in some load paths. */
JNIEXPORT void JNICALL
Java_com_tungsten_fclauncher_CriticalNativeTest_testCriticalNative(JNIEnv *env, jclass cls, jint a, jint b) {
    (void)env; (void)cls; (void)a; (void)b;
}

static void cache_bridge_methods(JNIEnv *env) {
    if (!pojav_environ) return;
    jclass cls = (*env)->FindClass(env, "org/lwjgl/glfw/CallbackBridge");
    if (!cls || (*env)->ExceptionCheck(env)) {
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        return;
    }
    pojav_environ->bridgeClazz = (*env)->NewGlobalRef(env, cls);
    pojav_environ->method_onGrabStateChanged =
        (*env)->GetStaticMethodID(env, cls, "onGrabStateChanged", "(Z)V");
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        pojav_environ->method_onGrabStateChanged = NULL;
    }
    pojav_environ->method_accessAndroidClipboard =
        (*env)->GetStaticMethodID(env, cls, "accessAndroidClipboard", "(ILjava/lang/String;)Ljava/lang/String;");
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        pojav_environ->method_accessAndroidClipboard = NULL;
    }
    (*env)->DeleteLocalRef(env, cls);
}

void booxin_bind_glfw_input_buffers(JNIEnv *env) {
    if (!env || !pojav_environ) return;
    jclass glfwCls = (*env)->FindClass(env, "org/lwjgl/glfw/GLFW");
    if (!glfwCls || (*env)->ExceptionCheck(env)) {
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        LOGW("bind buffers: GLFW class missing");
        return;
    }
    jfieldID keyFid =
        (*env)->GetStaticFieldID(env, glfwCls, "keyDownBuffer", "Ljava/nio/ByteBuffer;");
    jfieldID mouseFid =
        (*env)->GetStaticFieldID(env, glfwCls, "mouseDownBuffer", "Ljava/nio/ByteBuffer;");
    if (!keyFid || !mouseFid || (*env)->ExceptionCheck(env)) {
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        LOGW("bind buffers: field ids missing");
        (*env)->DeleteLocalRef(env, glfwCls);
        return;
    }
    jobject keyBuf = (*env)->GetStaticObjectField(env, glfwCls, keyFid);
    jobject mouseBuf = (*env)->GetStaticObjectField(env, glfwCls, mouseFid);
    if (keyBuf) {
        void *addr = (*env)->GetDirectBufferAddress(env, keyBuf);
        jlong cap = (*env)->GetDirectBufferCapacity(env, keyBuf);
        if (addr && cap >= 317) {
            pojav_environ->keyDownBuffer = (jbyte *)addr;
            LOGI("bound keyDownBuffer=%p cap=%lld", addr, (long long)cap);
        }
        (*env)->DeleteLocalRef(env, keyBuf);
    }
    if (mouseBuf) {
        void *addr = (*env)->GetDirectBufferAddress(env, mouseBuf);
        jlong cap = (*env)->GetDirectBufferCapacity(env, mouseBuf);
        if (addr && cap >= 8) {
            pojav_environ->mouseDownBuffer = (jbyte *)addr;
            LOGI("bound mouseDownBuffer=%p cap=%lld", addr, (long long)cap);
        }
        (*env)->DeleteLocalRef(env, mouseBuf);
    }
    (*env)->DeleteLocalRef(env, glfwCls);
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    g_vm = vm;
    booxin_environ_init();
    JNIEnv *env = NULL;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        return JNI_VERSION_1_6;
    }
    /* ART first, then HotSpot. */
    if (!pojav_environ->dalvikJavaVMPtr) {
        pojav_environ->dalvikJavaVMPtr = vm;
        pojav_environ->dalvikJNIEnvPtr_ANDROID = env;
        LOGI("JNI_OnLoad ART/dalvik");
    } else if (!pojav_environ->runtimeJavaVMPtr || pojav_environ->runtimeJavaVMPtr != vm) {
        pojav_environ->runtimeJavaVMPtr = vm;
        pojav_environ->runtimeJNIEnvPtr_JRE = env;
        LOGI("JNI_OnLoad HotSpot");
    }
    cache_bridge_methods(env);
    /* Share GLFW's key/mouse ByteBuffers when the class is already up. */
    booxin_bind_glfw_input_buffers(env);
    critical_set_stackqueue(JNI_TRUE);
    pojav_environ->isInputReady = true;
    return JNI_VERSION_1_6;
}

/* Unused stubs some callers still dlsym. */
void installEMUIIteratorMititgation(JNIEnv *env) { (void)env; }
void installLwjglDlopenHook(JNIEnv *env) { (void)env; }
void hookExec(JNIEnv *env) { (void)env; }
void env_init() { booxin_environ_init(); }
