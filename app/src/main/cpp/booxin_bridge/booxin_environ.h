#ifndef BOOXIN_ENVIRON_H
#define BOOXIN_ENVIRON_H

#include <android/native_window.h>
#include <jni.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
    int type;
    int i1;
    int i2;
    int i3;
    int i4;
} BooxinGlfwInputEvent;

#define BOOXIN_EVENT_WINDOW_SIZE 8000

/* Layout must stay compatible with jre_launcher.c bridge environ struct. */
typedef struct booxin_environ {
    void *nativeWindow;          /* ANativeWindow* */
    void *mainWindowBundle;
    int config_renderer;
    bool force_vsync;
    atomic_size_t eventCounter;
    BooxinGlfwInputEvent events[BOOXIN_EVENT_WINDOW_SIZE];
    size_t outEventIndex;
    size_t outTargetIndex;
    size_t inEventIndex;
    size_t inEventCount;
    double cursorX, cursorY, cLastX, cLastY;
    jmethodID method_accessAndroidClipboard;
    jmethodID method_onGrabStateChanged;
    jmethodID method_glftSetWindowAttrib;
    jmethodID method_internalWindowSizeChanged;
    jclass bridgeClazz;
    jclass vmGlfwClass;
    jboolean isGrabbing;
    jbyte *keyDownBuffer;
    jbyte *mouseDownBuffer;
    JavaVM *runtimeJavaVMPtr;
    JNIEnv *runtimeJNIEnvPtr_JRE;
    JavaVM *dalvikJavaVMPtr;
    JNIEnv *dalvikJNIEnvPtr_ANDROID;
    long showingWindow;
    bool isInputReady, isCursorEntered, isUseStackQueueCall, shouldUpdateMouse;
    int savedWidth, savedHeight;
    void *GLFW_invoke_Char;
    void *GLFW_invoke_CharMods;
    void *GLFW_invoke_CursorEnter;
    void *GLFW_invoke_CursorPos;
    void *GLFW_invoke_FramebufferSize;
    void *GLFW_invoke_Key;
    void *GLFW_invoke_MouseButton;
    void *GLFW_invoke_Scroll;
    void *GLFW_invoke_WindowSize;
} booxin_environ_t;

/* Primary environ export; legacy pojav_environ aliases the same pointer. */
extern booxin_environ_t *booxin_environ;
extern booxin_environ_t *pojav_environ;
extern booxin_environ_t g_booxin_environ;

void booxin_environ_init(void);

void booxin_retain_native_window(ANativeWindow *win);
ANativeWindow *booxin_ensure_native_window(void);

/** SDL/MG present progress for Java overlay peel (TextureView may not update). */
void booxin_note_sdl_present(void);
unsigned long long booxin_sdl_present_count(void);

/** Pause/resume window EGLSurface across Android SurfaceView destroy/create. */
void booxin_egl_detach_window(void);
int booxin_egl_attach_window(void);

void booxin_bind_glfw_input_buffers(JNIEnv *env);

#ifdef __cplusplus
}
#endif

#endif
