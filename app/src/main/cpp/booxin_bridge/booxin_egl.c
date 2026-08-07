#include "booxin_environ.h"

#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <dlfcn.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#define LOG_TAG "BooxinEGL"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

static EGLDisplay g_display = EGL_NO_DISPLAY;
static EGLSurface g_surface = EGL_NO_SURFACE;
static EGLContext g_context = EGL_NO_CONTEXT;
static EGLConfig g_config;
static void *g_current = NULL;
static int g_initialized = 0;
/* UI thread must NOT eglMakeCurrent — only the GL thread recreates the window surface. */
static atomic_int g_surface_stale = 0;
static pthread_mutex_t g_egl_mu = PTHREAD_MUTEX_INITIALIZER;

static EGLint g_gles_api = EGL_OPENGL_ES_API;
static int g_gles_version = 3;

int pojavInitOpenGL(void);
void booxin_egl_detach_window(void);
int booxin_egl_attach_window(void);

/** Called only from the GL thread (MakeCurrent / SwapBuffers). */
static int egl_recreate_window_surface_locked(void) {
    if (g_display == EGL_NO_DISPLAY || !g_initialized) return 0;
    if (g_surface != EGL_NO_SURFACE) {
        eglMakeCurrent(g_display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        eglDestroySurface(g_display, g_surface);
        g_surface = EGL_NO_SURFACE;
        g_current = NULL;
    }
    ANativeWindow *win = booxin_ensure_native_window();
    if (!win) {
        LOGW("egl recreate: no native window yet");
        return 0;
    }
    g_surface = eglCreateWindowSurface(
        g_display, g_config, (EGLNativeWindowType)win, NULL);
    if (g_surface == EGL_NO_SURFACE) {
        LOGE("eglCreateWindowSurface failed: 0x%x", eglGetError());
        return 0;
    }
    LOGI("egl window surface recreated on GL thread %dx%d",
         ANativeWindow_getWidth(win), ANativeWindow_getHeight(win));
    return 1;
}

static void read_renderer_env(void) {
    const char *renderer = getenv("BOOXIN_RENDERER");
    if (!renderer || !renderer[0]) renderer = getenv("POJAV_RENDERER");
    /* Default GLES3; only holy GL4ES (opengles2) needs ES2 contexts. */
    g_gles_version = 3;
    if (renderer && strstr(renderer, "opengles2") &&
        !strstr(renderer, "opengles3") &&
        !strstr(renderer, "vulkan") &&
        !strstr(renderer, "gallium")) {
        g_gles_version = 2;
    }
    const char *libgl = getenv("LIBGL_ES");
    if (libgl && libgl[0] == '2') g_gles_version = 2;
    if (libgl && libgl[0] == '3') g_gles_version = 3;
}

int pojavInit(void) {
    booxin_environ_t *e = pojav_environ;
    ANativeWindow *win = booxin_ensure_native_window();
    if (!e || !win) {
        LOGE("pojavInit: native window missing (environ=%p window=%p retained_ensure=%p)",
             (void *)e, e ? e->nativeWindow : NULL, (void *)win);
        return 0;
    }
    ANativeWindow_acquire(win);
    e->nativeWindow = win;
    e->savedWidth = ANativeWindow_getWidth(win);
    e->savedHeight = ANativeWindow_getHeight(win);
    LOGI("pojavInit window=%dx%d", e->savedWidth, e->savedHeight);
    if (!pojavInitOpenGL()) {
        LOGE("pojavInit: pojavInitOpenGL failed");
        return 0;
    }
    return 1;
}

int pojavInitOpenGL(void) {
    read_renderer_env();
    if (g_display == EGL_NO_DISPLAY) {
        g_display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        if (g_display == EGL_NO_DISPLAY) {
            LOGE("eglGetDisplay failed");
            return 0;
        }
        if (!eglInitialize(g_display, NULL, NULL)) {
            LOGE("eglInitialize failed: 0x%x", eglGetError());
            return 0;
        }
    }

    EGLint attribs[] = {
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
        EGL_RENDERABLE_TYPE,
        g_gles_version >= 3 ? EGL_OPENGL_ES3_BIT : EGL_OPENGL_ES2_BIT,
        EGL_BLUE_SIZE, 8,
        EGL_GREEN_SIZE, 8,
        EGL_RED_SIZE, 8,
        EGL_ALPHA_SIZE, 8,
        EGL_DEPTH_SIZE, 24,
        EGL_NONE
    };
    EGLint num = 0;
    if (!eglChooseConfig(g_display, attribs, &g_config, 1, &num) || num < 1) {
        LOGE("eglChooseConfig failed: 0x%x", eglGetError());
        return 0;
    }
    eglBindAPI(g_gles_api);
    g_initialized = 1;
    LOGI("pojavInitOpenGL ok gles=%d", g_gles_version);
    return 1;
}

void *pojavCreateContext(void *contextSrc) {
    (void)contextSrc;
    if (!g_initialized && !pojavInitOpenGL()) return NULL;
    EGLint ctx_attribs[] = {
        EGL_CONTEXT_CLIENT_VERSION, g_gles_version >= 3 ? 3 : 2,
        EGL_NONE
    };
    EGLContext share = g_context != EGL_NO_CONTEXT ? g_context : EGL_NO_CONTEXT;
    EGLContext ctx = eglCreateContext(g_display, g_config, share, ctx_attribs);
    if (ctx == EGL_NO_CONTEXT) {
        LOGE("eglCreateContext failed: 0x%x", eglGetError());
        return NULL;
    }
    if (g_context == EGL_NO_CONTEXT) g_context = ctx;

    booxin_environ_t *e = pojav_environ;
    if (e && e->nativeWindow && g_surface == EGL_NO_SURFACE) {
        pthread_mutex_lock(&g_egl_mu);
        egl_recreate_window_surface_locked();
        pthread_mutex_unlock(&g_egl_mu);
    }
    LOGI("pojavCreateContext %p", (void *)ctx);
    return (void *)ctx;
}

void *pojavGetCurrentContext(void) {
    return g_current ? g_current : (void *)eglGetCurrentContext();
}

void pojavMakeCurrent(void *window) {
    if (!g_initialized && !pojavInitOpenGL()) return;
    EGLContext ctx = window ? (EGLContext)window : g_context;
    if (ctx == EGL_NO_CONTEXT) ctx = g_context;
    pthread_mutex_lock(&g_egl_mu);
    if (atomic_exchange(&g_surface_stale, 0) || g_surface == EGL_NO_SURFACE) {
        if (!egl_recreate_window_surface_locked()) {
            pthread_mutex_unlock(&g_egl_mu);
            return;
        }
    }
    if (!eglMakeCurrent(g_display, g_surface, g_surface, ctx)) {
        LOGE("eglMakeCurrent failed: 0x%x — will recreate", eglGetError());
        atomic_store(&g_surface_stale, 1);
        pthread_mutex_unlock(&g_egl_mu);
        return;
    }
    g_current = (void *)ctx;
    if (pojav_environ) pojav_environ->showingWindow = (long)(intptr_t)ctx;
    /* Prefer unlocked present unless FORCE_VSYNC=true. */
    {
        const char *force = getenv("FORCE_VSYNC");
        int interval = (force && force[0] == 't') ? 1 : 0;
        eglSwapInterval(g_display, interval);
    }
    pthread_mutex_unlock(&g_egl_mu);
}

void pojavSwapBuffers(void) {
    if (g_display == EGL_NO_DISPLAY) return;
    pthread_mutex_lock(&g_egl_mu);
    if (atomic_exchange(&g_surface_stale, 0) || g_surface == EGL_NO_SURFACE) {
        if (!egl_recreate_window_surface_locked()) {
            pthread_mutex_unlock(&g_egl_mu);
            return;
        }
        if (g_context != EGL_NO_CONTEXT) {
            if (!eglMakeCurrent(g_display, g_surface, g_surface, g_context)) {
                LOGE("eglMakeCurrent(after recreate) failed: 0x%x", eglGetError());
                atomic_store(&g_surface_stale, 1);
                pthread_mutex_unlock(&g_egl_mu);
                return;
            }
            g_current = (void *)g_context;
        }
    }
    if (g_surface != EGL_NO_SURFACE) {
        if (!eglSwapBuffers(g_display, g_surface)) {
            EGLint err = eglGetError();
            LOGW("SwapBuffers failed 0x%x — mark stale", err);
            /* Destroy on this GL thread; next frame recreates. */
            eglMakeCurrent(g_display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            eglDestroySurface(g_display, g_surface);
            g_surface = EGL_NO_SURFACE;
            g_current = NULL;
            atomic_store(&g_surface_stale, 1);
        }
    }
    pthread_mutex_unlock(&g_egl_mu);
}

/**
 * UI/binder thread: only mark the window surface stale.
 * Never eglMakeCurrent here — that steals the context from the render thread
 * and freezes the picture after resume (buttons still work).
 */
void booxin_egl_detach_window(void) {
    atomic_store(&g_surface_stale, 1);
    LOGI("egl mark stale (detach / pause)");
}

/** UI thread after setupBridgeWindow: new ANativeWindow is retained; GL thread will attach. */
int booxin_egl_attach_window(void) {
    if (!booxin_ensure_native_window()) {
        LOGW("egl attach: no native window");
        return 0;
    }
    atomic_store(&g_surface_stale, 1);
    LOGI("egl mark stale (attach / resume) — GL thread will recreate");
    return 1;
}

void pojavSwapInterval(int interval) {
    if (g_display == EGL_NO_DISPLAY) return;
    const char *force = getenv("FORCE_VSYNC");
    if (!(force && force[0] == 't')) {
        /* Ignore game/LWJGL requests for vsync — keep headroom for +FPS. */
        interval = 0;
    }
    eglSwapInterval(g_display, interval);
}

void pojavSetWindowHint(int hint, int value) {
    (void)hint; (void)value;
}

void pojavTerminate(void) {
    if (g_display != EGL_NO_DISPLAY) {
        eglMakeCurrent(g_display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (g_surface != EGL_NO_SURFACE) eglDestroySurface(g_display, g_surface);
        if (g_context != EGL_NO_CONTEXT) eglDestroyContext(g_display, g_context);
        eglTerminate(g_display);
    }
    g_display = EGL_NO_DISPLAY;
    g_surface = EGL_NO_SURFACE;
    g_context = EGL_NO_CONTEXT;
    g_current = NULL;
    g_initialized = 0;
}

void set_gl_bridge_tbl(void) {}
void set_osm_bridge_tbl(void) {}

void *dlsym_EGL(void *handle, const char *name) {
    return dlsym(handle ? handle : RTLD_DEFAULT, name);
}

void *dlsym_OSMesa(void *handle, const char *name) {
    return dlsym(handle ? handle : RTLD_DEFAULT, name);
}
