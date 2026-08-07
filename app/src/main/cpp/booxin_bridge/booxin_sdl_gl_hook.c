/*
 * Android SDL3 + MobileGlues:
 * SDL is linked against system libEGL; LWJGL loads MobileGlues for gl*.
 * Redirect libEGL.so EGL entry points to MobileGlues so one stack owns the context.
 * Also force GLES-safe GL attributes when MG EGL is unavailable.
 */
#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>
#include <EGL/egl.h>
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

typedef int (*bytehook_init_fn)(int mode, int debug);
typedef void *(*bytehook_hook_single_fn)(
    const char *callee_path_name,
    const char *caller_path_name,
    const char *sym_name,
    void *new_func,
    void *hooked,
    void *hooked_arg);

static sdl_bool_ii_fn g_real_gl_set_attr = NULL;
static sdl_gl_create_ctx_fn g_real_gl_create_ctx = NULL;
static sdl_gl_make_current_fn g_real_gl_make_current = NULL;
static void *g_mg_handle = NULL;
static int g_egl_redirected = 0;
static int g_use_mg_egl = 0;

static void *load_bytehook(void) {
    void *bh = dlopen("libbytehook.so", RTLD_NOW | RTLD_GLOBAL);
    if (bh) return bh;
    const char *nd = getenv("BOOXIN_NATIVEDIR");
    if (!nd || !nd[0]) nd = getenv("POJAV_NATIVEDIR");
    if (nd && nd[0]) {
        char path[512];
        snprintf(path, sizeof(path), "%s/libbytehook.so", nd);
        bh = dlopen(path, RTLD_NOW | RTLD_GLOBAL);
    }
    return bh;
}

static void *load_mobileglues(void) {
    if (g_mg_handle) return g_mg_handle;
    const char *path = getenv("SDL_OPENGL_LIBRARY");
    if (!path || !path[0]) path = getenv("LIBGL_NAME");
    if (!path || !path[0]) path = "libmobileglues.so";
    g_mg_handle = dlopen(path, RTLD_NOW | RTLD_GLOBAL);
    if (!g_mg_handle) g_mg_handle = dlopen("libmobileglues.so", RTLD_NOW | RTLD_GLOBAL);
    if (!g_mg_handle) {
        LOGW("dlopen MobileGlues failed: %s", dlerror());
    } else {
        LOGI("MobileGlues handle=%p path=%s", g_mg_handle, path);
    }
    return g_mg_handle;
}

static int redirect_egl_symbol(bytehook_hook_single_fn hook, const char *name) {
    if (!g_mg_handle || !hook) return -1;
    void *mg_sym = dlsym(g_mg_handle, name);
    if (!mg_sym) {
        LOGW("MG missing %s", name);
        return -2;
    }
    void *stub = hook("libEGL.so", NULL, name, mg_sym, NULL, NULL);
    LOGI("redirect libEGL %s → MG %p stub=%p", name, mg_sym, stub);
    return stub ? 0 : -3;
}

/** Point system libEGL symbols at MobileGlues before SDL creates a context. */
static int redirect_libegl_to_mobileglues(void) {
    if (g_egl_redirected) return 0;
    if (!load_mobileglues()) return -1;
    void *bh = load_bytehook();
    if (!bh) {
        LOGW("libbytehook.so unavailable — cannot redirect libEGL");
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
        else if (rc == -2) { /* optional symbol */ }
        else fail++;
    }
    g_egl_redirected = (ok > 0);
    g_use_mg_egl = g_egl_redirected;
    LOGI("libEGL→MobileGlues redirects ok=%d fail=%d", ok, fail);
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

int booxin_sdl_force_gles(void *sdl_handle) {
    if (!sdl_handle) return -1;

    const char *gl = getenv("SDL_OPENGL_LIBRARY");
    if (!gl || !gl[0]) gl = getenv("LIBGL_NAME");
    const char *egl = getenv("SDL_EGL_LIBRARY");
    if (!egl || !egl[0]) egl = getenv("LIBGL_EGL");
    if (!egl || !egl[0]) egl = gl;

    int red = redirect_libegl_to_mobileglues();
    LOGI("redirect_libegl_to_mobileglues rc=%d", red);

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

    g_real_gl_set_attr(BOOXIN_SDL_GL_CONTEXT_PROFILE_MASK, BOOXIN_SDL_GL_CONTEXT_PROFILE_ES);
    g_real_gl_set_attr(BOOXIN_SDL_GL_CONTEXT_MAJOR_VERSION, 3);
    g_real_gl_set_attr(BOOXIN_SDL_GL_CONTEXT_MINOR_VERSION, 0);
    LOGI("default GLES3 (mg_egl=%d)", g_use_mg_egl);
    return 0;
}

int booxin_sdl_rebind_lwjgl_gl_set_attribute(JNIEnv *env) {
    if (!env || !g_real_gl_set_attr) return -1;
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
    (*env)->DeleteLocalRef(env, fnCls);
    return rc;
}
