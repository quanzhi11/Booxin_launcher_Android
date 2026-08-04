#include "booxin_environ.h"

#include <android/log.h>
#include <android/native_window.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>

#define LOG_TAG "BooxinEnv"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

booxin_environ_t g_booxin_environ;
booxin_environ_t *pojav_environ = &g_booxin_environ;

/* Retained ANativeWindow so Surface teardown does not leave a dangling pointer. */
static ANativeWindow *g_retained_window = NULL;
static pthread_mutex_t g_window_mu = PTHREAD_MUTEX_INITIALIZER;

void booxin_environ_init(void) {
    static int once;
    if (once) return;
    once = 1;
    memset(&g_booxin_environ, 0, sizeof(g_booxin_environ));
    pojav_environ = &g_booxin_environ;
    g_booxin_environ.keyDownBuffer = (jbyte *)calloc(317, 1);
    g_booxin_environ.mouseDownBuffer = (jbyte *)calloc(8, 1);
    g_booxin_environ.isUseStackQueueCall = true;
}

void booxin_retain_native_window(ANativeWindow *win) {
    pthread_mutex_lock(&g_window_mu);
    if (g_retained_window == win) {
        if (pojav_environ) pojav_environ->nativeWindow = win;
        pthread_mutex_unlock(&g_window_mu);
        return;
    }
    ANativeWindow *old = g_retained_window;
    g_retained_window = NULL;
    if (pojav_environ) pojav_environ->nativeWindow = NULL;
    if (win) {
        ANativeWindow_acquire(win);
        g_retained_window = win;
        if (pojav_environ) {
            pojav_environ->nativeWindow = win;
            pojav_environ->savedWidth = ANativeWindow_getWidth(win);
            pojav_environ->savedHeight = ANativeWindow_getHeight(win);
        }
        LOGI("retain native window=%p %dx%d",
             (void *)win,
             pojav_environ ? pojav_environ->savedWidth : 0,
             pojav_environ ? pojav_environ->savedHeight : 0);
    }
    pthread_mutex_unlock(&g_window_mu);
    if (old) ANativeWindow_release(old);
}

ANativeWindow *booxin_ensure_native_window(void) {
    pthread_mutex_lock(&g_window_mu);
    ANativeWindow *win = g_retained_window;
    if (!win && pojav_environ)
        win = (ANativeWindow *)pojav_environ->nativeWindow;
    if (win) {
        if (!g_retained_window) {
            ANativeWindow_acquire(win);
            g_retained_window = win;
            LOGW("ensure: adopted environ window=%p (was not retained)", (void *)win);
        }
        if (pojav_environ) pojav_environ->nativeWindow = win;
    } else {
        LOGW("ensure: no native window (environ=%p)", (void *)pojav_environ);
    }
    pthread_mutex_unlock(&g_window_mu);
    return win;
}
