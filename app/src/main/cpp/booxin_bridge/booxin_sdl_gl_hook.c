/*
 * Android SDL3 + MobileGlues:
 * SDL is linked against system libEGL; LWJGL loads MobileGlues for gl*.
 * Redirect libEGL.so EGL entry points to MobileGlues so one stack owns the context.
 * Also force GLES-safe GL attributes when MG EGL is unavailable.
 *
 * 26.3 probe window: Android SDL allows one window. Destroy probe then recreate
 * the real window; Android_JNI_GetNativeWindow may return null after DestroyWindow,
 * so fall back to the launcher-retained ANativeWindow.
 */
#include "booxin_environ.h"

#include <android/log.h>
#include <android/native_window.h>
#include <dlfcn.h>
#include <jni.h>
#include <EGL/egl.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define LOG_TAG "BooxinSdlGl"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

enum {
    BOOXIN_SDL_GL_ACCUM_RED_SIZE = 8,
    BOOXIN_SDL_GL_STEREO = 12,
    BOOXIN_SDL_GL_CONTEXT_MAJOR_VERSION = 17,
    BOOXIN_SDL_GL_CONTEXT_MINOR_VERSION = 18,
    BOOXIN_SDL_GL_CONTEXT_FLAGS = 19,
    BOOXIN_SDL_GL_CONTEXT_PROFILE_MASK = 20,
    BOOXIN_SDL_GL_FRAMEBUFFER_SRGB_CAPABLE = 23,
    BOOXIN_SDL_GL_CONTEXT_NO_ERROR = 26,
};
#define BOOXIN_SDL_GL_CONTEXT_PROFILE_CORE 0x0001
#define BOOXIN_SDL_GL_CONTEXT_PROFILE_ES 0x0004
#define BOOXIN_SDL_GL_CONTEXT_FORWARD_COMPATIBLE_FLAG 0x0002
#define BOOXIN_SDL_HINT_OVERRIDE 2

typedef int (*sdl_bool_ii_fn)(int attr, int value);
typedef void *(*sdl_gl_create_ctx_fn)(void *window);
typedef int (*sdl_gl_make_current_fn)(void *window, void *context);
typedef int (*sdl_set_hint_pri_fn)(const char *name, const char *value, int priority);
typedef int (*sdl_set_hint_fn)(const char *name, const char *value);
typedef void *(*sdl_create_window_fn)(const char *title, int w, int h, uint64_t flags);
typedef void *(*sdl_create_window_props_fn)(uint32_t props);
typedef void (*sdl_destroy_window_fn)(void *window);
typedef void **(*sdl_get_windows_fn)(int *count);
typedef const char *(*sdl_get_error_fn)(void);
typedef void (*sdl_free_fn)(void *mem);

typedef int (*bytehook_init_fn)(int mode, int debug);
typedef void *(*bytehook_hook_single_fn)(
    const char *callee_path_name,
    const char *caller_path_name,
    const char *sym_name,
    void *new_func,
    void *hooked,
    void *hooked_arg);
typedef void *(*bytehook_hook_all_fn)(
    const char *caller_path_name,
    const char *sym_name,
    void *new_func,
    void *hooked,
    void *hooked_arg);
typedef ANativeWindow *(*android_jni_get_nw_fn)(void);
typedef int (*sdl_set_window_size_fn)(void *window, int w, int h);
typedef int (*sdl_show_window_fn)(void *window);
typedef int (*sdl_raise_window_fn)(void *window);
typedef uint64_t (*sdl_get_window_flags_fn)(void *window);

static sdl_bool_ii_fn g_real_gl_set_attr = NULL;
static sdl_gl_create_ctx_fn g_real_gl_create_ctx = NULL;
static sdl_gl_make_current_fn g_real_gl_make_current = NULL;
static sdl_create_window_fn g_real_create_window = NULL;
static sdl_create_window_props_fn g_real_create_window_props = NULL;
static sdl_destroy_window_fn g_real_destroy_window = NULL;
static sdl_get_windows_fn g_real_get_windows = NULL;
static sdl_get_error_fn g_real_get_error = NULL;
static sdl_free_fn g_real_sdl_free = NULL;
static sdl_set_window_size_fn g_real_set_window_size = NULL;
static sdl_show_window_fn g_real_show_window = NULL;
static sdl_raise_window_fn g_real_raise_window = NULL;
static sdl_get_window_flags_fn g_real_get_window_flags = NULL;
static android_jni_get_nw_fn g_real_android_get_nw = NULL;
static void *g_mg_handle = NULL;
static int g_egl_redirected = 0;
static int g_use_mg_egl = 0;
static int g_nw_hooked = 0;
static JavaVM *g_jvm = NULL;
static int g_clearing_windows = 0;
static int g_swap_log_left = 16;
static void *g_swap_prev = NULL;
static void *g_create_win_prev = NULL;
static EGLBoolean (*g_mg_egl_swap)(EGLDisplay, EGLSurface) = NULL;
static EGLSurface (*g_mg_egl_create_win)(EGLDisplay, EGLConfig, EGLNativeWindowType, const EGLint *) = NULL;
static EGLint (*g_mg_egl_get_error)(void) = NULL;
static void *(*g_mg_egl_get_proc)(const char *name) = NULL;
static void *g_get_proc_prev = NULL;

typedef int (*sdl_gl_swap_window_fn)(void *window);
static sdl_gl_swap_window_fn g_real_gl_swap_window = NULL;

/* SDL3: probe often has HIDDEN (0x8); SwapWindow may skip present until shown. */
#define BOOXIN_SDL_WINDOW_HIDDEN 0x00000008ull
#define BOOXIN_SDL_WINDOW_OPENGL 0x00000002ull
#define BOOXIN_SDL_WINDOW_RESIZABLE 0x00000020ull
#define BOOXIN_SDL_WINDOW_HIGH_PIXEL_DENSITY 0x00002000ull

typedef void (*bytehook_hooked_cb)(
    void *task_stub, int status_code,
    const char *caller_path_name, const char *sym_name,
    void *new_func, void *prev_func, void *hooked_arg);

static void on_egl_hooked(
    void *task_stub, int status_code,
    const char *caller_path_name, const char *sym_name,
    void *new_func, void *prev_func, void *hooked_arg) {
    (void)task_stub;
    (void)status_code;
    (void)caller_path_name;
    (void)new_func;
    (void)hooked_arg;
    if (!sym_name || !prev_func) return;
    if (strcmp(sym_name, "eglSwapBuffers") == 0) {
        g_swap_prev = prev_func;
        LOGI("eglSwapBuffers trampoline=%p", prev_func);
    } else if (strcmp(sym_name, "eglCreateWindowSurface") == 0) {
        g_create_win_prev = prev_func;
        LOGI("eglCreateWindowSurface trampoline=%p", prev_func);
    } else if (strcmp(sym_name, "eglGetProcAddress") == 0) {
        g_get_proc_prev = prev_func;
        LOGI("eglGetProcAddress trampoline=%p", prev_func);
    }
}

static atomic_ullong g_sdl_present_count = 0;

void booxin_note_sdl_present(void) {
    atomic_fetch_add_explicit(&g_sdl_present_count, 1ULL, memory_order_relaxed);
}

unsigned long long booxin_sdl_present_count(void) {
    return atomic_load_explicit(&g_sdl_present_count, memory_order_relaxed);
}

static EGLBoolean booxin_egl_swap_buffers(EGLDisplay dpy, EGLSurface surface) {
    EGLBoolean (*real)(EGLDisplay, EGLSurface) = NULL;
    if (g_swap_prev)
        real = (EGLBoolean (*)(EGLDisplay, EGLSurface))g_swap_prev;
    else if (g_mg_egl_swap)
        real = g_mg_egl_swap;
    EGLBoolean ok = real ? real(dpy, surface) : EGL_FALSE;
    if (ok) booxin_note_sdl_present();
    if (g_swap_log_left > 0) {
        g_swap_log_left--;
        EGLint err = g_mg_egl_get_error ? g_mg_egl_get_error() : -1;
        LOGI("eglSwapBuffers dpy=%p surf=%p -> %d err=0x%x real=%p",
             (void *)dpy, (void *)surface, (int)ok, (int)err, (void *)real);
    }
    return ok;
}

static EGLSurface booxin_egl_create_window_surface(
    EGLDisplay dpy, EGLConfig config, EGLNativeWindowType win, const EGLint *attrib) {
    /*
     * SDL may pass a transient/probe ANW; TextureView BufferQueues often never
     * deliver consumer updates under MG. Always prefer the launcher-retained
     * SurfaceView/TextureView window from setupBridgeWindow.
     */
    ANativeWindow *retained = booxin_ensure_native_window();
    if (retained) {
        int rw = ANativeWindow_getWidth(retained);
        int rh = ANativeWindow_getHeight(retained);
        int aw = 0, ah = 0;
        if (win) {
            aw = ANativeWindow_getWidth((ANativeWindow *)win);
            ah = ANativeWindow_getHeight((ANativeWindow *)win);
        }
        /* Prefer launcher TextureView ANW over SDL probe/transient windows. */
        int substitute = !win || win != (EGLNativeWindowType)retained;
        if (substitute && win && aw >= rw && ah >= rh && aw > 64 && ah > 64) {
            /* Caller already has a full-size window — keep it. */
            substitute = 0;
        }
        if (substitute) {
            LOGI("eglCreateWindowSurface: substitute retained=%p %dx%d for win=%p %dx%d",
                 (void *)retained, rw, rh, (void *)win, aw, ah);
            win = (EGLNativeWindowType)retained;
        }
    }
    EGLSurface (*real)(EGLDisplay, EGLConfig, EGLNativeWindowType, const EGLint *) = NULL;
    if (g_create_win_prev)
        real = (EGLSurface (*)(EGLDisplay, EGLConfig, EGLNativeWindowType, const EGLint *))
            g_create_win_prev;
    else if (g_mg_egl_create_win)
        real = g_mg_egl_create_win;
    EGLSurface s = real ? real(dpy, config, win, attrib) : EGL_NO_SURFACE;
    EGLint err = g_mg_egl_get_error ? g_mg_egl_get_error() : -1;
    int aw = 0, ah = 0;
    if (win) {
        aw = ANativeWindow_getWidth((ANativeWindow *)win);
        ah = ANativeWindow_getHeight((ANativeWindow *)win);
    }
    LOGI("eglCreateWindowSurface win=%p %dx%d -> surf=%p err=0x%x real=%p",
         (void *)win, aw, ah, (void *)s, (int)err, (void *)real);
    return s;
}

/*
 * SDL resolves most EGL entry points via eglGetProcAddress after dlopen(MG).
 * PLT hooks alone never see SwapBuffers. Return our wrappers so present is logged
 * and stays on the same MG stack.
 */
static void *booxin_egl_get_proc_address(const char *name) {
    if (name) {
        if (strcmp(name, "eglSwapBuffers") == 0)
            return (void *)&booxin_egl_swap_buffers;
        if (strcmp(name, "eglCreateWindowSurface") == 0)
            return (void *)&booxin_egl_create_window_surface;
        if (strcmp(name, "eglGetProcAddress") == 0)
            return (void *)&booxin_egl_get_proc_address;
    }
    void *(*real)(const char *) = NULL;
    if (g_get_proc_prev)
        real = (void *(*)(const char *))g_get_proc_prev;
    else if (g_mg_egl_get_proc)
        real = g_mg_egl_get_proc;
    void *p = real ? real(name) : NULL;
    if (name && (strstr(name, "Swap") || strstr(name, "WindowSurface"))) {
        LOGI("eglGetProcAddress(%s) -> %p", name, p);
    }
    return p;
}

static int booxin_sdl_gl_swap_window(void *window) {
    int rc = g_real_gl_swap_window ? g_real_gl_swap_window(window) : -1;
    /* SDL3: true(1) on success. */
    if (rc == 1) booxin_note_sdl_present();
    if (g_swap_log_left > 0) {
        g_swap_log_left--;
        LOGI("SDL_GL_SwapWindow win=%p rc=%d presents=%llu",
             window, rc, (unsigned long long)booxin_sdl_present_count());
    }
    return rc;
}

static void *load_bytehook(void) {
    const char *nd = getenv("BOOXIN_NATIVEDIR");
    if (!nd || !nd[0]) nd = getenv("POJAV_NATIVEDIR");
    if (!nd || !nd[0]) nd = getenv("FCL_NATIVEDIR");
    if (nd && nd[0]) {
        char path[512];
        snprintf(path, sizeof(path), "%s/libbytehook.so", nd);
        void *bh = dlopen(path, RTLD_NOW | RTLD_GLOBAL);
        if (bh) {
            LOGI("bytehook loaded: %s", path);
            return bh;
        }
        LOGW("bytehook NATIVEDIR fail %s: %s", path, dlerror());
    }
    void *bh = dlopen("libbytehook.so", RTLD_NOW | RTLD_GLOBAL);
    if (bh) LOGI("bytehook loaded via soname");
    else LOGW("bytehook soname fail: %s", dlerror());
    return bh;
}

static void *(*g_real_dlsym)(void *handle, const char *name) = NULL;
static void *g_dlsym_prev = NULL;
static int g_dlsym_hooked = 0;

static void on_dlsym_hooked(
    void *task_stub, int status_code,
    const char *caller_path_name, const char *sym_name,
    void *new_func, void *prev_func, void *hooked_arg) {
    (void)task_stub;
    (void)status_code;
    (void)caller_path_name;
    (void)sym_name;
    (void)new_func;
    (void)hooked_arg;
    if (prev_func) g_dlsym_prev = prev_func;
}

static void *booxin_dlsym(void *handle, const char *name) {
    void *(*real)(void *, const char *) = NULL;
    if (g_dlsym_prev)
        real = (void *(*)(void *, const char *))g_dlsym_prev;
    else if (g_real_dlsym)
        real = g_real_dlsym;
    void *p = real ? real(handle, name) : NULL;
    if (!name) return p;
    /* SDL: dlopen(MobileGlues) then dlsym EGL entry points — rewrite those. */
    if (strcmp(name, "eglGetProcAddress") == 0) {
        if (p && !g_mg_egl_get_proc)
            g_mg_egl_get_proc = (void *(*)(const char *))p;
        LOGI("dlsym(%s) -> wrapper (real=%p)", name, p);
        return (void *)&booxin_egl_get_proc_address;
    }
    if (strcmp(name, "eglSwapBuffers") == 0) {
        if (p && !g_mg_egl_swap)
            g_mg_egl_swap = (EGLBoolean (*)(EGLDisplay, EGLSurface))p;
        LOGI("dlsym(%s) -> wrapper (real=%p)", name, p);
        return (void *)&booxin_egl_swap_buffers;
    }
    if (strcmp(name, "eglCreateWindowSurface") == 0) {
        if (p && !g_mg_egl_create_win)
            g_mg_egl_create_win =
                (EGLSurface (*)(EGLDisplay, EGLConfig, EGLNativeWindowType, const EGLint *))p;
        LOGI("dlsym(%s) -> wrapper (real=%p)", name, p);
        return (void *)&booxin_egl_create_window_surface;
    }
    return p;
}

static int install_dlsym_hook(void) {
    if (g_dlsym_hooked) return 0;
    void *bh = load_bytehook();
    if (!bh) return -1;
    bytehook_init_fn init = (bytehook_init_fn)dlsym(bh, "bytehook_init");
    if (init) init(0, 0);
    bytehook_hook_single_fn hook =
        (bytehook_hook_single_fn)dlsym(bh, "bytehook_hook_single");
    bytehook_hook_all_fn hook_all =
        (bytehook_hook_all_fn)dlsym(bh, "bytehook_hook_all");
    g_real_dlsym = dlsym;
    void *stub = NULL;
    if (hook_all) {
        stub = hook_all(NULL, "dlsym", (void *)&booxin_dlsym,
                        (void *)&on_dlsym_hooked, NULL);
        LOGI("hook_all dlsym stub=%p", stub);
    }
    if (!stub && hook) {
        stub = hook("libdl.so", NULL, "dlsym", (void *)&booxin_dlsym,
                    (void *)&on_dlsym_hooked, NULL);
        LOGI("hook_single libdl dlsym stub=%p", stub);
    }
    if (!stub && hook) {
        stub = hook("libc.so", NULL, "dlsym", (void *)&booxin_dlsym,
                    (void *)&on_dlsym_hooked, NULL);
        LOGI("hook_single libc dlsym stub=%p", stub);
    }
    g_dlsym_hooked = stub ? 1 : 0;
    return g_dlsym_hooked ? 0 : -2;
}

static void *load_gl_translator(void) {
    if (g_mg_handle) return g_mg_handle;
    const char *path = getenv("SDL_EGL_LIBRARY");
    if (!path || !path[0]) path = getenv("LIBGL_EGL");
    if (!path || !path[0]) path = getenv("SDL_OPENGL_LIBRARY");
    if (!path || !path[0]) path = getenv("LIBGL_NAME");
    if (!path || !path[0]) path = "libmobileglues.so";
    g_mg_handle = dlopen(path, RTLD_NOW | RTLD_GLOBAL);
    if (!g_mg_handle && strstr(path, "mobileglues") == NULL) {
        g_mg_handle = dlopen("libmobileglues.so", RTLD_NOW | RTLD_GLOBAL);
    }
    if (!g_mg_handle) {
        LOGW("dlopen GL/EGL translator failed path=%s: %s", path, dlerror());
    } else {
        LOGI("GL/EGL translator handle=%p path=%s", g_mg_handle, path);
        g_mg_egl_get_error = (EGLint (*)(void))dlsym(g_mg_handle, "eglGetError");
    }
    return g_mg_handle;
}

static int redirect_egl_symbol_in(
    bytehook_hook_single_fn hook, const char *lib, const char *name, void *target) {
    if (!hook || !lib || !name || !target) return -1;
    void *stub = hook(lib, NULL, name, target, (void *)&on_egl_hooked, NULL);
    LOGI("redirect %s %s -> %p stub=%p", lib, name, target, stub);
    return stub ? 0 : -3;
}

static int redirect_egl_symbol(bytehook_hook_single_fn hook, const char *name) {
    if (!g_mg_handle || !hook) return -1;
    void *mg_sym = dlsym(g_mg_handle, name);
    if (!mg_sym) {
        LOGW("MG missing %s", name);
        return -2;
    }
    void *target = mg_sym;
    if (strcmp(name, "eglSwapBuffers") == 0) {
        g_mg_egl_swap = (EGLBoolean (*)(EGLDisplay, EGLSurface))mg_sym;
        target = (void *)&booxin_egl_swap_buffers;
    } else if (strcmp(name, "eglCreateWindowSurface") == 0) {
        g_mg_egl_create_win =
            (EGLSurface (*)(EGLDisplay, EGLConfig, EGLNativeWindowType, const EGLint *))mg_sym;
        target = (void *)&booxin_egl_create_window_surface;
    } else if (strcmp(name, "eglGetProcAddress") == 0) {
        g_mg_egl_get_proc = (void *(*)(const char *))mg_sym;
        target = (void *)&booxin_egl_get_proc_address;
    } else if (strcmp(name, "eglGetError") == 0) {
        g_mg_egl_get_error = (EGLint (*)(void))mg_sym;
    }

    /*
     * SDL loads SDL_EGL_LIBRARY (MobileGlues) directly — hooking only libEGL.so
     * never sees CreateWindowSurface/SwapBuffers. Hook MG too; trampoline from
     * on_egl_hooked avoids recursion into our wrapper.
     */
    int ok = 0;
    if (redirect_egl_symbol_in(hook, "libEGL.so", name, target) == 0) ok++;

    const char *egl_path = getenv("SDL_EGL_LIBRARY");
    if (!egl_path || !egl_path[0]) egl_path = getenv("LIBGL_EGL");
    if (egl_path && egl_path[0]) {
        if (redirect_egl_symbol_in(hook, egl_path, name, target) == 0) ok++;
    }
    if (redirect_egl_symbol_in(hook, "libmobileglues.so", name, target) == 0) ok++;

    const char *nd = getenv("BOOXIN_NATIVEDIR");
    if (!nd || !nd[0]) nd = getenv("POJAV_NATIVEDIR");
    if (nd && nd[0]) {
        char path[512];
        snprintf(path, sizeof(path), "%s/libmobileglues.so", nd);
        if (redirect_egl_symbol_in(hook, path, name, target) == 0) ok++;
    }

    if (strcmp(name, "eglSwapBuffers") == 0 ||
        strcmp(name, "eglCreateWindowSurface") == 0 ||
        strcmp(name, "eglGetProcAddress") == 0) {
        void *bh = load_bytehook();
        bytehook_hook_all_fn hook_all =
            bh ? (bytehook_hook_all_fn)dlsym(bh, "bytehook_hook_all") : NULL;
        if (hook_all) {
            void *stub = hook_all(NULL, name, target, (void *)&on_egl_hooked, NULL);
            LOGI("hook_all %s -> %p stub=%p", name, target, stub);
            if (stub) ok++;
        }
    }
    return ok > 0 ? 0 : -3;
}

/** Point EGL entry points at MobileGlues / REL before SDL creates a context. */
static int redirect_libegl_to_mobileglues(void) {
    if (g_egl_redirected) return 0;
    if (!load_gl_translator()) return -1;
    void *bh = load_bytehook();
    if (!bh) {
        LOGW("libbytehook.so unavailable — cannot redirect EGL");
        return -2;
    }
    bytehook_init_fn init = (bytehook_init_fn)dlsym(bh, "bytehook_init");
    bytehook_hook_single_fn hook =
        (bytehook_hook_single_fn)dlsym(bh, "bytehook_hook_single");
    if (init) {
        int irc = init(0, 0);
        LOGI("bytehook_init rc=%d", irc);
    }
    if (!hook) {
        LOGW("bytehook_hook_single missing");
        return -3;
    }

    static const char *k_syms[] = {
        "eglGetDisplay",
        "eglGetPlatformDisplay",
        "eglInitialize",
        "eglTerminate",
        "eglGetConfigs",
        "eglChooseConfig",
        "eglGetConfigAttrib",
        "eglCreateWindowSurface",
        "eglCreatePbufferSurface",
        "eglDestroySurface",
        "eglCreateContext",
        "eglDestroyContext",
        "eglMakeCurrent",
        "eglSwapBuffers",
        "eglSwapInterval",
        "eglQuerySurface",
        "eglQueryContext",
        "eglGetCurrentDisplay",
        "eglGetCurrentContext",
        "eglGetCurrentSurface",
        "eglGetError",
        "eglBindAPI",
        "eglQueryAPI",
        "eglWaitClient",
        "eglWaitGL",
        "eglWaitNative",
        "eglReleaseThread",
        "eglGetProcAddress",
        NULL
    };
    int ok = 0, fail = 0;
    for (int i = 0; k_syms[i]; i++) {
        int rc = redirect_egl_symbol(hook, k_syms[i]);
        if (rc == 0) ok++;
        else if (rc == -2) { /* optional */ }
        else fail++;
    }
    g_egl_redirected = (ok > 0);
    g_use_mg_egl = g_egl_redirected;
    LOGI("EGL->MobileGlues redirects ok=%d fail=%d", ok, fail);

    /*
     * SDL does dlopen(MG)+dlsym for EGL — PLT hooks never see those pointers.
     * Hook dlsym AFTER redirect setup so later SDL lookups get our wrappers.
     */
    int dsrc = install_dlsym_hook();
    LOGI("install_dlsym_hook rc=%d", dsrc);

    return g_egl_redirected ? 0 : -4;
}

static int booxin_sdl_gl_set_attribute(int attr, int value) {
    int orig = value;
    /* Even with MG EGL, Android configs are ES — CORE still → EGL_BAD_ATTRIBUTE. */
    if (attr == BOOXIN_SDL_GL_CONTEXT_PROFILE_MASK) {
        value = BOOXIN_SDL_GL_CONTEXT_PROFILE_ES;
    } else if (attr == BOOXIN_SDL_GL_CONTEXT_MAJOR_VERSION) {
        if (value < 2) value = 3;
        if (value > 3) value = 3;
    } else if (attr == BOOXIN_SDL_GL_CONTEXT_MINOR_VERSION) {
        if (value > 2) value = 0;
    } else if (attr == BOOXIN_SDL_GL_CONTEXT_FLAGS) {
        value &= ~BOOXIN_SDL_GL_CONTEXT_FORWARD_COMPATIBLE_FLAG;
    } else if (attr >= BOOXIN_SDL_GL_ACCUM_RED_SIZE && attr <= BOOXIN_SDL_GL_STEREO) {
        return 1;
    } else if (attr == BOOXIN_SDL_GL_FRAMEBUFFER_SRGB_CAPABLE ||
               attr == BOOXIN_SDL_GL_CONTEXT_NO_ERROR) {
        return 1;
    }
    if (value != orig) {
        LOGI("SDL_GL_SetAttribute remap attr=%d %d→%d", attr, orig, value);
    }
    if (!g_real_gl_set_attr) return 0;
    return g_real_gl_set_attr(attr, value);
}

static void *booxin_sdl_gl_create_context(void *window) {
    void *ctx = g_real_gl_create_ctx ? g_real_gl_create_ctx(window) : NULL;
    LOGI("SDL_GL_CreateContext window=%p → %p mg_egl=%d", window, ctx, g_use_mg_egl);
    return ctx;
}

static int booxin_sdl_gl_make_current(void *window, void *context) {
    return g_real_gl_make_current ? g_real_gl_make_current(window, context) : 0;
}

static int rebind_long_field(JNIEnv *env, jclass fnCls, const char *name, void *fn) {
    jfieldID fid = (*env)->GetStaticFieldID(env, fnCls, name, "J");
    if (!fid || (*env)->ExceptionCheck(env)) {
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        LOGW("rebind: field %s missing", name);
        return -1;
    }
    jlong cur = (*env)->GetStaticLongField(env, fnCls, fid);
    jlong hook = (jlong)(uintptr_t)fn;
    (*env)->SetStaticLongField(env, fnCls, fid, hook);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
        return -2;
    }
    jlong now = (*env)->GetStaticLongField(env, fnCls, fid);
    LOGI("rebind %s %lld → %lld", name, (long long)cur, (long long)now);
    return (now == hook) ? 0 : -3;
}

/**
 * Minecraft 26.3+ creates a small probe SDL window (GL backend detect), then the
 * real game window. Android SDL only allows one window.
 *
 * Destroy+recreate needs Android_JNI_GetNativeWindow to succeed after the probe
 * releases its ANativeWindow. HotSpot cannot FindClass(SDLActivity), and Java
 * getNativeSurface can return null after DestroyWindow on some paths — fall back
 * to the launcher-retained ANativeWindow from setupBridgeWindow.
 */
static ANativeWindow *booxin_android_jni_get_native_window(void) {
    ANativeWindow *win = NULL;
    if (g_real_android_get_nw) {
        win = g_real_android_get_nw();
        if (win) return win;
    }
    win = booxin_ensure_native_window();
    if (win) {
        ANativeWindow_acquire(win); /* CreateWindow / DestroyWindow own one release */
        LOGI("GetNativeWindow fallback retained=%p %dx%d",
             (void *)win, ANativeWindow_getWidth(win), ANativeWindow_getHeight(win));
        return win;
    }
    LOGW("GetNativeWindow: Java null and no retained window");
    return NULL;
}

static int install_get_native_window_hook(void *sdl_handle) {
    if (g_nw_hooked) return 0;
    if (!sdl_handle) return -1;
    void *sym = dlsym(sdl_handle, "Android_JNI_GetNativeWindow");
    if (!sym) {
        LOGW("Android_JNI_GetNativeWindow not exported");
        return -2;
    }
    g_real_android_get_nw = (android_jni_get_nw_fn)sym;

    void *bh = load_bytehook();
    if (!bh) return -3;
    bytehook_init_fn init = (bytehook_init_fn)dlsym(bh, "bytehook_init");
    if (init) init(0, 0);

    bytehook_hook_all_fn hook_all =
        (bytehook_hook_all_fn)dlsym(bh, "bytehook_hook_all");
    bytehook_hook_single_fn hook_single =
        (bytehook_hook_single_fn)dlsym(bh, "bytehook_hook_single");

    void *stub = NULL;
    const char *nd = getenv("BOOXIN_NATIVEDIR");
    if (!nd || !nd[0]) nd = getenv("POJAV_NATIVEDIR");
    char sdl_path[512];
    sdl_path[0] = 0;
    if (nd && nd[0])
        snprintf(sdl_path, sizeof(sdl_path), "%s/libSDL3.so", nd);

    if (hook_all) {
        stub = hook_all(NULL, "Android_JNI_GetNativeWindow",
                        (void *)&booxin_android_jni_get_native_window, NULL, NULL);
        LOGI("hook_all Android_JNI_GetNativeWindow stub=%p", stub);
    }
    if (!stub && hook_single && sdl_path[0]) {
        stub = hook_single(sdl_path, NULL, "Android_JNI_GetNativeWindow",
                           (void *)&booxin_android_jni_get_native_window, NULL, NULL);
        LOGI("hook_single path Android_JNI_GetNativeWindow stub=%p", stub);
    }
    if (!stub && hook_single) {
        stub = hook_single("libSDL3.so", NULL, "Android_JNI_GetNativeWindow",
                           (void *)&booxin_android_jni_get_native_window, NULL, NULL);
        LOGI("hook_single soname Android_JNI_GetNativeWindow stub=%p", stub);
    }
    g_nw_hooked = stub ? 1 : 0;
    if (!g_nw_hooked) {
        /* Still keep g_real_android_get_nw — may call fallback only from our path. */
        LOGW("GetNativeWindow hook failed — recreate may need retained fallback via other path");
        return -4;
    }
    return 0;
}

static void *existing_sdl_window(void) {
    if (!g_real_get_windows) return NULL;
    int count = 0;
    void **wins = g_real_get_windows(&count);
    void *existing = (wins && count > 0) ? wins[0] : NULL;
    if (wins && g_real_sdl_free) g_real_sdl_free(wins);
    return existing;
}

static int error_is_one_window(void) {
    if (!g_real_get_error) return 0;
    const char *err = g_real_get_error();
    return err && strstr(err, "one window") != NULL;
}

/** ART ClassLoader path — HotSpot FindClass(SDLActivity) always fails. */
static void resync_sdl_android_surface(void) {
    JavaVM *art = NULL;
    if (booxin_environ) art = booxin_environ->dalvikJavaVMPtr;
    if (!art) {
        LOGW("resync surface: no ART JavaVM");
        return;
    }
    JNIEnv *env = NULL;
    int attached = 0;
    jint st = (*art)->GetEnv(art, (void **)&env, JNI_VERSION_1_6);
    if (st == JNI_EDETACHED) {
        if ((*art)->AttachCurrentThread(art, &env, NULL) != 0 || !env) {
            LOGW("resync surface: ART AttachCurrentThread failed");
            return;
        }
        attached = 1;
    } else if (st != JNI_OK || !env) {
        LOGW("resync surface: ART GetEnv failed %d", (int)st);
        return;
    }

    jclass boot = NULL;
    if (booxin_environ && booxin_environ->bridgeClazz) {
        jclass classCls = (*env)->FindClass(env, "java/lang/Class");
        jmethodID getCl = classCls
            ? (*env)->GetMethodID(env, classCls, "getClassLoader",
                                  "()Ljava/lang/ClassLoader;")
            : NULL;
        jobject loader = (getCl && booxin_environ->bridgeClazz)
            ? (*env)->CallObjectMethod(env, booxin_environ->bridgeClazz, getCl)
            : NULL;
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
            loader = NULL;
        }
        if (loader) {
            jclass clCls = (*env)->FindClass(env, "java/lang/ClassLoader");
            jmethodID load = clCls
                ? (*env)->GetMethodID(env, clCls, "loadClass",
                                      "(Ljava/lang/String;)Ljava/lang/Class;")
                : NULL;
            jstring name = (*env)->NewStringUTF(
                env, "com.booxin.launcher.core.launch.BooxinSdlBootstrap");
            if (load && name) {
                boot = (jclass)(*env)->CallObjectMethod(env, loader, load, name);
                if ((*env)->ExceptionCheck(env)) {
                    (*env)->ExceptionClear(env);
                    boot = NULL;
                }
            }
            if (name) (*env)->DeleteLocalRef(env, name);
            (*env)->DeleteLocalRef(env, loader);
            if (clCls) (*env)->DeleteLocalRef(env, clCls);
        }
        if (classCls) (*env)->DeleteLocalRef(env, classCls);
    }

    if (!boot) {
        boot = (*env)->FindClass(
            env, "com/booxin/launcher/core/launch/BooxinSdlBootstrap");
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
            boot = NULL;
        }
    }

    if (!boot) {
        LOGW("resync surface: BooxinSdlBootstrap missing on ART");
        if (attached) (*art)->DetachCurrentThread(art);
        return;
    }

    jmethodID mid = (*env)->GetStaticMethodID(env, boot, "resyncNativeSurface", "()Z");
    if (mid) {
        jboolean ok = (*env)->CallStaticBooleanMethod(env, boot, mid);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
            LOGW("resync surface: resyncNativeSurface threw");
        } else {
            LOGI("resync surface: resyncNativeSurface → %d", (int)ok);
        }
    } else {
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        LOGW("resync surface: method missing");
    }
    (*env)->DeleteLocalRef(env, boot);
    if (attached) (*art)->DetachCurrentThread(art);
}

static int destroy_existing_sdl_windows(const char *reason) {
    if (g_clearing_windows) return 0;
    if (!g_real_destroy_window) return -1;
    g_clearing_windows = 1;
    int count = 0;
    int destroyed = 0;
    if (g_real_get_windows) {
        void **wins = g_real_get_windows(&count);
        if (wins) {
            for (int i = 0; i < count; i++) {
                if (wins[i]) {
                    LOGI("DestroyWindow[%d/%d] %p (%s)", i + 1, count, wins[i], reason);
                    g_real_destroy_window(wins[i]);
                    destroyed++;
                }
            }
            if (g_real_sdl_free) g_real_sdl_free(wins);
        }
    }
    g_clearing_windows = 0;
    LOGI("cleared %d SDL window(s) (%s)", destroyed, reason);
    return destroyed;
}

static void *reuse_existing_window(void *existing, int w, int h, const char *why) {
    if (!existing) return NULL;
    uint64_t fl = g_real_get_window_flags ? g_real_get_window_flags(existing) : 0;
    LOGW("CreateWindow one-window — reuse %p flags=0x%llx (%s)",
         existing, (unsigned long long)fl, why);
    /* Probe is often created HIDDEN; SDL may skip SwapWindow until shown. */
    if (g_real_show_window) {
        int rc = g_real_show_window(existing);
        LOGI("ShowWindow rc=%d", rc);
    }
    if (g_real_raise_window) {
        int rc = g_real_raise_window(existing);
        LOGI("RaiseWindow rc=%d", rc);
    }
    if (g_real_set_window_size && w > 0 && h > 0) {
        int rc = g_real_set_window_size(existing, w, h);
        LOGI("SetWindowSize %dx%d rc=%d", w, h, rc);
    }
    fl = g_real_get_window_flags ? g_real_get_window_flags(existing) : 0;
    LOGI("reuse window now flags=0x%llx", (unsigned long long)fl);
    return existing;
}

static uint64_t sanitize_window_flags(uint64_t flags) {
    uint64_t out = flags;
    if (out & BOOXIN_SDL_WINDOW_HIDDEN) {
        LOGI("CreateWindow strip HIDDEN from flags 0x%llx", (unsigned long long)flags);
        out &= ~BOOXIN_SDL_WINDOW_HIDDEN;
    }
    out |= BOOXIN_SDL_WINDOW_OPENGL;
    return out;
}

static void upgrade_probe_size(int *w, int *h) {
    if (!w || !h) return;
    if (*w >= 640 && *h >= 640) return;
    ANativeWindow *nw = booxin_ensure_native_window();
    if (!nw) return;
    int rw = ANativeWindow_getWidth(nw);
    int rh = ANativeWindow_getHeight(nw);
    if (rw > 0 && rh > 0) {
        LOGI("CreateWindow upgrade probe %dx%d → %dx%d", *w, *h, rw, rh);
        *w = rw;
        *h = rh;
    }
}

static void *booxin_sdl_create_window(const char *title, int w, int h, uint64_t flags) {
    if (!g_real_create_window) return NULL;
    upgrade_probe_size(&w, &h);
    flags = sanitize_window_flags(flags);
    void *win = g_real_create_window(title, w, h, flags);
    if (win) {
        LOGI("CreateWindow ok %p %dx%d flags=0x%llx", win, w, h,
             (unsigned long long)flags);
        if (g_real_show_window) g_real_show_window(win);
        return win;
    }
    if (!error_is_one_window()) {
        LOGW("CreateWindow failed: %s", g_real_get_error ? g_real_get_error() : "?");
        return NULL;
    }

    void *existing = existing_sdl_window();
    ANativeWindow *retained = booxin_ensure_native_window();
    /* Destroy only when GetNativeWindow is hooked — otherwise recreate
     * hits "Could not fetch native window" and the probe is already gone. */
    if (existing && retained && g_nw_hooked) {
        destroy_existing_sdl_windows("CreateWindow one-window");
        resync_sdl_android_surface();
        win = g_real_create_window(title, w, h, flags);
        LOGI("CreateWindow recreate → %p %dx%d flags=0x%llx err=%s nw_hook=%d",
             win, w, h, (unsigned long long)flags,
             g_real_get_error ? g_real_get_error() : "?", g_nw_hooked);
        if (win) {
            if (g_real_show_window) g_real_show_window(win);
            return win;
        }
        LOGW("CreateWindow recreate failed after destroy");
        return NULL;
    }
    return reuse_existing_window(
        existing, w, h,
        g_nw_hooked ? "no retained NW" : "GetNativeWindow not hooked");
}

static void *booxin_sdl_create_window_with_properties(uint32_t props) {
    if (!g_real_create_window_props) return NULL;
    void *win = g_real_create_window_props(props);
    if (win) {
        LOGI("CreateWindowWithProperties ok %p props=%u", win, (unsigned)props);
        return win;
    }
    if (!error_is_one_window()) {
        LOGW("CreateWindowWithProperties failed: %s",
             g_real_get_error ? g_real_get_error() : "?");
        return NULL;
    }

    void *existing = existing_sdl_window();
    ANativeWindow *retained = booxin_ensure_native_window();
    if (existing && retained && g_nw_hooked) {
        destroy_existing_sdl_windows("CreateWindowWithProperties one-window");
        resync_sdl_android_surface();
        win = g_real_create_window_props(props);
        LOGI("CreateWindowWithProperties recreate → %p props=%u err=%s nw_hook=%d",
             win, (unsigned)props, g_real_get_error ? g_real_get_error() : "?",
             g_nw_hooked);
        if (win) return win;
        LOGW("CreateWindowWithProperties recreate failed after destroy");
        return NULL;
    }
    return reuse_existing_window(
        existing, 0, 0,
        g_nw_hooked ? "no retained NW" : "GetNativeWindow not hooked");
}

int booxin_sdl_force_gles(void *sdl_handle) {
    if (!sdl_handle) return -1;

    const char *gl = getenv("SDL_OPENGL_LIBRARY");
    if (!gl || !gl[0]) gl = getenv("LIBGL_NAME");
    const char *egl = getenv("SDL_EGL_LIBRARY");
    if (!egl || !egl[0]) egl = getenv("LIBGL_EGL");
    if (!egl || !egl[0]) egl = gl;

    int red = redirect_libegl_to_mobileglues();
    LOGI("redirect_libegl_to_mobileglues rc=%d", red);
    if (red != 0) {
        /* Without this, SDL presents via system libEGL while LWJGL uses MobileGlues —
         * classic 26.3 black TextureView with working touch controls. */
        LOGW("CRITICAL: libEGL→MobileGlues redirect failed (rc=%d) — "
             "SDL may black-screen (GL init ok, no TextureView frames)", red);
    }

    sdl_set_hint_pri_fn set_hint_pri =
        (sdl_set_hint_pri_fn)dlsym(sdl_handle, "SDL_SetHintWithPriority");
    sdl_set_hint_fn set_hint =
        (sdl_set_hint_fn)dlsym(sdl_handle, "SDL_SetHint");
    if (set_hint_pri) {
        set_hint_pri("SDL_VIDEO_FORCE_EGL", "1", BOOXIN_SDL_HINT_OVERRIDE);
        set_hint_pri("SDL_OPENGL_ES_DRIVER", "1", BOOXIN_SDL_HINT_OVERRIDE);
        if (gl && gl[0]) set_hint_pri("SDL_OPENGL_LIBRARY", gl, BOOXIN_SDL_HINT_OVERRIDE);
        if (egl && egl[0]) set_hint_pri("SDL_EGL_LIBRARY", egl, BOOXIN_SDL_HINT_OVERRIDE);
    } else if (set_hint) {
        set_hint("SDL_VIDEO_FORCE_EGL", "1");
        set_hint("SDL_OPENGL_ES_DRIVER", "1");
    }

    void *set_attr = dlsym(sdl_handle, "SDL_GL_SetAttribute");
    void *create_ctx = dlsym(sdl_handle, "SDL_GL_CreateContext");
    void *make_cur = dlsym(sdl_handle, "SDL_GL_MakeCurrent");
    if (!set_attr) return -2;
    g_real_gl_set_attr = (sdl_bool_ii_fn)set_attr;
    g_real_gl_create_ctx = (sdl_gl_create_ctx_fn)create_ctx;
    g_real_gl_make_current = (sdl_gl_make_current_fn)make_cur;
    g_real_gl_swap_window = (sdl_gl_swap_window_fn)dlsym(sdl_handle, "SDL_GL_SwapWindow");

    g_real_create_window = (sdl_create_window_fn)dlsym(sdl_handle, "SDL_CreateWindow");
    g_real_create_window_props =
        (sdl_create_window_props_fn)dlsym(sdl_handle, "SDL_CreateWindowWithProperties");
    g_real_destroy_window = (sdl_destroy_window_fn)dlsym(sdl_handle, "SDL_DestroyWindow");
    g_real_get_windows = (sdl_get_windows_fn)dlsym(sdl_handle, "SDL_GetWindows");
    g_real_get_error = (sdl_get_error_fn)dlsym(sdl_handle, "SDL_GetError");
    g_real_sdl_free = (sdl_free_fn)dlsym(sdl_handle, "SDL_free");
    g_real_set_window_size = (sdl_set_window_size_fn)dlsym(sdl_handle, "SDL_SetWindowSize");
    g_real_show_window = (sdl_show_window_fn)dlsym(sdl_handle, "SDL_ShowWindow");
    g_real_raise_window = (sdl_raise_window_fn)dlsym(sdl_handle, "SDL_RaiseWindow");
    g_real_get_window_flags = (sdl_get_window_flags_fn)dlsym(sdl_handle, "SDL_GetWindowFlags");
    LOGI("SDL window hooks CreateWindow=%p Props=%p Destroy=%p GetWindows=%p SetSize=%p Show=%p",
         (void *)g_real_create_window, (void *)g_real_create_window_props,
         (void *)g_real_destroy_window, (void *)g_real_get_windows,
         (void *)g_real_set_window_size, (void *)g_real_show_window);

    int nwrc = install_get_native_window_hook(sdl_handle);
    LOGI("install_get_native_window_hook rc=%d hooked=%d", nwrc, g_nw_hooked);

    g_real_gl_set_attr(BOOXIN_SDL_GL_CONTEXT_PROFILE_MASK, BOOXIN_SDL_GL_CONTEXT_PROFILE_ES);
    g_real_gl_set_attr(BOOXIN_SDL_GL_CONTEXT_MAJOR_VERSION, 3);
    g_real_gl_set_attr(BOOXIN_SDL_GL_CONTEXT_MINOR_VERSION, 0);
    LOGI("default GLES3 (mg_egl=%d redirect_rc=%d)", g_use_mg_egl, red);
    /* Still return 0 so the game can try; UI must wait for TextureView frames. */
    return red == 0 ? 0 : 1;
}

int booxin_sdl_rebind_lwjgl_gl_set_attribute(JNIEnv *env) {
    if (!env || !g_real_gl_set_attr) return -1;
    (*env)->GetJavaVM(env, &g_jvm);
    jclass fnCls = (*env)->FindClass(env, "org/lwjgl/sdl/SDLVideo$Functions");
    if (!fnCls || (*env)->ExceptionCheck(env)) {
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        return -2;
    }
    int rc = 0;
    if (rebind_long_field(env, fnCls, "GL_SetAttribute",
                          (void *)&booxin_sdl_gl_set_attribute) != 0)
        rc = -3;
    if (g_real_gl_create_ctx &&
        rebind_long_field(env, fnCls, "GL_CreateContext",
                          (void *)&booxin_sdl_gl_create_context) != 0)
        rc = -4;
    if (g_real_gl_make_current &&
        rebind_long_field(env, fnCls, "GL_MakeCurrent",
                          (void *)&booxin_sdl_gl_make_current) != 0)
        rc = -5;
    /* 26.3 Android single-window: destroy probe window then recreate game window. */
    if (g_real_create_window &&
        rebind_long_field(env, fnCls, "CreateWindow",
                          (void *)&booxin_sdl_create_window) != 0)
        rc = -6;
    if (g_real_create_window_props &&
        rebind_long_field(env, fnCls, "CreateWindowWithProperties",
                          (void *)&booxin_sdl_create_window_with_properties) != 0)
        rc = -7;
    if (g_real_gl_swap_window &&
        rebind_long_field(env, fnCls, "GL_SwapWindow",
                          (void *)&booxin_sdl_gl_swap_window) != 0)
        rc = -8;
    (*env)->DeleteLocalRef(env, fnCls);
    return rc;
}
