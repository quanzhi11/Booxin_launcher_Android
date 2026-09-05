#include "booxin_environ.h"

#include <android/log.h>
#include <jni.h>
#include <pthread.h>
#include <string.h>

#define LOG_TAG "BooxinBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

enum {
    EVENT_TYPE_CHAR = 1000,
    EVENT_TYPE_CHAR_MODS = 1001,
    EVENT_TYPE_CURSOR_POS = 1003,
    EVENT_TYPE_KEY = 1005,
    EVENT_TYPE_MOUSE_BUTTON = 1006,
    EVENT_TYPE_SCROLL = 1007,
    EVENT_TYPE_WINDOW_SIZE = 1008
};

static pthread_mutex_t g_queue_mu = PTHREAD_MUTEX_INITIALIZER;

static void push_event(int type, int i1, int i2, int i3, int i4) {
    booxin_environ_t *e = booxin_environ;
    if (!e || !e->isUseStackQueueCall) return;
    pthread_mutex_lock(&g_queue_mu);
    /* Drop oldest when full — never overwrite unread slots (lost/spurious clicks). */
    if (e->inEventIndex - e->outEventIndex >= BOOXIN_EVENT_WINDOW_SIZE) {
        e->outEventIndex = e->inEventIndex - BOOXIN_EVENT_WINDOW_SIZE + 1;
        if (e->inEventCount > 0) e->inEventCount--;
    }
    size_t idx = e->inEventIndex % BOOXIN_EVENT_WINDOW_SIZE;
    e->events[idx].type = type;
    e->events[idx].i1 = i1;
    e->events[idx].i2 = i2;
    e->events[idx].i3 = i3;
    e->events[idx].i4 = i4;
    e->inEventIndex++;
    if (e->inEventCount < BOOXIN_EVENT_WINDOW_SIZE) e->inEventCount++;
    atomic_fetch_add(&e->eventCounter, 1);
    pthread_mutex_unlock(&g_queue_mu);
}

void critical_set_stackqueue(jboolean use) {
    if (booxin_environ) booxin_environ->isUseStackQueueCall = use == JNI_TRUE;
}

void noncritical_set_stackqueue(jboolean use) { critical_set_stackqueue(use); }

void critical_send_cursor_pos(jfloat x, jfloat y) {
    if (!booxin_environ) return;
    /* Cursor goes through pump; don't queue every move. */
    booxin_environ->cursorX = x;
    booxin_environ->cursorY = y;
    booxin_environ->shouldUpdateMouse = true;
}

void noncritical_send_cursor_pos(JNIEnv *env, jclass cls, jfloat x, jfloat y) {
    (void)env; (void)cls;
    critical_send_cursor_pos(x, y);
}

void critical_send_mouse_button(jint button, jint action, jint mods) {
    if (booxin_environ && booxin_environ->mouseDownBuffer && button >= 0 && button < 8) {
        booxin_environ->mouseDownBuffer[button] = (jbyte)(action == 1 ? 1 : 0);
    }
    push_event(EVENT_TYPE_MOUSE_BUTTON, button, action, mods, 0);
}

void noncritical_send_mouse_button(JNIEnv *env, jclass cls, jint button, jint action, jint mods) {
    (void)env; (void)cls;
    critical_send_mouse_button(button, action, mods);
}

void critical_send_key(jint key, jint scancode, jint action, jint mods) {
    if (booxin_environ && booxin_environ->keyDownBuffer && key >= 0 && key < 317) {
        booxin_environ->keyDownBuffer[key] = (jbyte)(action == 1 || action == 2 ? 1 : 0);
    }
    push_event(EVENT_TYPE_KEY, key, scancode, action, mods);
}

void noncritical_send_key(JNIEnv *env, jclass cls, jint key, jint scancode, jint action, jint mods) {
    (void)env; (void)cls;
    critical_send_key(key, scancode, action, mods);
}

jboolean critical_send_char(jchar codepoint) {
    push_event(EVENT_TYPE_CHAR, (int)codepoint, 0, 0, 0);
    return JNI_TRUE;
}

jboolean noncritical_send_char(JNIEnv *env, jclass cls, jchar codepoint) {
    (void)env; (void)cls;
    return critical_send_char(codepoint);
}

jboolean critical_send_char_mods(jchar codepoint, jint mods) {
    push_event(EVENT_TYPE_CHAR_MODS, (int)codepoint, mods, 0, 0);
    return JNI_TRUE;
}

jboolean noncritical_send_char_mods(JNIEnv *env, jclass cls, jchar codepoint, jint mods) {
    (void)env; (void)cls;
    return critical_send_char_mods(codepoint, mods);
}

void critical_send_scroll(jdouble xoffset, jdouble yoffset) {
    push_event(EVENT_TYPE_SCROLL, (int)(xoffset * 1000), (int)(yoffset * 1000), 0, 0);
}

void noncritical_send_scroll(JNIEnv *env, jclass cls, jdouble xoffset, jdouble yoffset) {
    (void)env; (void)cls;
    critical_send_scroll(xoffset, yoffset);
}

void critical_send_screen_size(jint width, jint height) {
    if (booxin_environ) {
        booxin_environ->savedWidth = width;
        booxin_environ->savedHeight = height;
    }
    push_event(EVENT_TYPE_WINDOW_SIZE, width, height, width, height);
}

void noncritical_send_screen_size(JNIEnv *env, jclass cls, jint width, jint height) {
    (void)env; (void)cls;
    critical_send_screen_size(width, height);
}

typedef void (*cursor_pos_fn)(void *window, double x, double y);
typedef void (*mouse_btn_fn)(void *window, int button, int action, int mods);
typedef void (*key_fn)(void *window, int key, int scancode, int action, int mods);
typedef void (*scroll_fn)(void *window, double x, double y);
typedef void (*char_fn)(void *window, unsigned int codepoint);
typedef void (*char_mods_fn)(void *window, unsigned int codepoint, int mods);
typedef void (*size_fn)(void *window, int w, int h);

void booxinStartPumping(void) {
    booxin_environ_t *e = booxin_environ;
    if (!e) return;
    if (e->cursorX != e->cLastX || e->cursorY != e->cLastY) {
        e->shouldUpdateMouse = true;
    }
}

void booxinStopPumping(void) {
}

void booxinPumpEvents(void *window) {
    booxin_environ_t *e = booxin_environ;
    if (!e) return;
    void *win = window ? window : (void *)(intptr_t)e->showingWindow;
    /* Callbacks expect the GLFW/createContext handle, not ANativeWindow. */

    if (e->shouldUpdateMouse) {
        if (e->GLFW_invoke_CursorPos && win) {
            ((cursor_pos_fn)e->GLFW_invoke_CursorPos)(win, e->cursorX, e->cursorY);
        }
        e->cLastX = e->cursorX;
        e->cLastY = e->cursorY;
        e->shouldUpdateMouse = false;
    }

    pthread_mutex_lock(&g_queue_mu);
    while (e->outEventIndex != e->inEventIndex) {
        size_t idx = e->outEventIndex % BOOXIN_EVENT_WINDOW_SIZE;
        BooxinGlfwInputEvent ev = e->events[idx];
        e->outEventIndex++;
        pthread_mutex_unlock(&g_queue_mu);

        switch (ev.type) {
            case EVENT_TYPE_CURSOR_POS:
                if (e->GLFW_invoke_CursorPos && win)
                    ((cursor_pos_fn)e->GLFW_invoke_CursorPos)(win, (double)ev.i1, (double)ev.i2);
                break;
            case EVENT_TYPE_MOUSE_BUTTON:
                if (e->GLFW_invoke_MouseButton && win)
                    ((mouse_btn_fn)e->GLFW_invoke_MouseButton)(win, ev.i1, ev.i2, ev.i3);
                break;
            case EVENT_TYPE_KEY:
                if (e->GLFW_invoke_Key && win)
                    ((key_fn)e->GLFW_invoke_Key)(win, ev.i1, ev.i2, ev.i3, ev.i4);
                break;
            case EVENT_TYPE_CHAR:
                if (e->GLFW_invoke_Char && win)
                    ((char_fn)e->GLFW_invoke_Char)(win, (unsigned int)ev.i1);
                break;
            case EVENT_TYPE_CHAR_MODS:
                if (e->GLFW_invoke_CharMods && win)
                    ((char_mods_fn)e->GLFW_invoke_CharMods)(win, (unsigned int)ev.i1, ev.i2);
                break;
            case EVENT_TYPE_SCROLL:
                if (e->GLFW_invoke_Scroll && win)
                    ((scroll_fn)e->GLFW_invoke_Scroll)(win, ev.i1 / 1000.0, ev.i2 / 1000.0);
                break;
            case EVENT_TYPE_WINDOW_SIZE:
                if (e->GLFW_invoke_WindowSize && win)
                    ((size_fn)e->GLFW_invoke_WindowSize)(win, ev.i1, ev.i2);
                if (e->GLFW_invoke_FramebufferSize && win)
                    ((size_fn)e->GLFW_invoke_FramebufferSize)(win, ev.i3, ev.i4);
                break;
            default:
                break;
        }
        pthread_mutex_lock(&g_queue_mu);
    }
    pthread_mutex_unlock(&g_queue_mu);
}

void booxinSetInjectorCallback(void *cb) { (void)cb; }

static _Atomic int g_cached_hit_type = 0;
static _Atomic int g_cached_held_kind = 0;

void booxinSetHitResultType(int type) {
    atomic_store(&g_cached_hit_type, type);
}

void booxinSetHeldItemKind(int kind) {
    atomic_store(&g_cached_held_kind, kind);
}

int booxinGetHitResultType(void) {
    return atomic_load(&g_cached_hit_type);
}

int booxinGetHeldItemKind(void) {
    return atomic_load(&g_cached_held_kind);
}
