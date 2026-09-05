#include "booxin_environ.h"

#include <android/hardware_buffer.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <dlfcn.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define LOG_TAG "BooxinJvm"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

static EGLDisplay g_display = EGL_NO_DISPLAY;
static EGLSurface g_surface = EGL_NO_SURFACE;
static EGLContext g_context = EGL_NO_CONTEXT;
static EGLConfig g_config;
static void *g_current = NULL;
static int g_initialized = 0;
static atomic_int g_surface_stale = 0;
static atomic_int g_detach_requested = 0;
static pthread_mutex_t g_egl_mu = PTHREAD_MUTEX_INITIALIZER;

static EGLint g_gles_api = EGL_OPENGL_ES_API;
static int g_gles_version = 3;

/*
 * REL / MCrender / MobileGlues / GL4ES wrap CreateContext / DestroyContext /
 * MakeCurrent (/ SwapBuffers). Display/config/surface stay on system libEGL.
 * Translator TinywrapperEGL only tracks contexts created through its own
 * eglCreateContext — system-only create leaves glGetString null → strlen SIGSEGV.
 */
static void *g_rel_lib = NULL;
static void *g_mcrender_lib = NULL;
static void *g_mobileglues_lib = NULL;
static void *g_gl4es_lib = NULL;
static int g_use_rel_ctx = 0;
static int g_use_mcrender_ctx = 0;
static int g_use_mobileglues_ctx = 0;
static int g_use_gl4es_ctx = 0;

/*
 * LWJGL memUTF8(glGetString / glGetStringi) does strlen on the pointer.
 * MobileGlues (seen on ColorOS / Adreno) may return:
 *   - NULL for EXTENSIONS/VENDOR while VERSION is fine, or
 *   - a non-NULL dangling / unreadable pointer → SIGSEGV in __strlen_aarch64
 * right after "glGetString(VERSION)=4.0.0 MobileGlues …".
 * Always copy into our buffers; reject unreadable addresses via write()+EFAULT.
 */
typedef const unsigned char *(*fn_glGetString)(unsigned int name);
typedef const unsigned char *(*fn_glGetStringi)(unsigned int name, unsigned int index);
typedef void *(*fn_eglGetProcAddress)(const char *name);
static fn_glGetString g_real_glGetString = NULL;
static fn_glGetStringi g_real_glGetStringi = NULL;
static fn_eglGetProcAddress g_real_eglGPA = NULL;
static int g_glgetstring_hooked = 0;
static const unsigned char g_empty_gl_str[] = "";
/* Stable slots so concurrent VENDOR/RENDERER/VERSION/EXTENSIONS stay valid. */
static unsigned char g_gl_str_vendor[512];
static unsigned char g_gl_str_renderer[512];
static unsigned char g_gl_str_version[512];
static unsigned char g_gl_str_extensions[16384];
static unsigned char g_gl_str_misc[1024];
static unsigned char g_gl_str_stringi[512];

/** True if [p, p+n) is readable (write to pipe → EFAULT on bad pages, no signal). */
static int bytes_readable(const void *p, size_t n) {
    if (!p || n == 0) return 0;
    int fds[2];
    if (pipe(fds) != 0) return 0;
    const char *cur = (const char *)p;
    size_t left = n;
    int ok = 1;
    while (left > 0) {
        size_t chunk = left > 512 ? 512 : left;
        ssize_t w = write(fds[1], cur, chunk);
        if (w < 0 || (size_t)w != chunk) {
            ok = 0;
            break;
        }
        cur += chunk;
        left -= chunk;
    }
    close(fds[0]);
    close(fds[1]);
    return ok;
}

static const unsigned char *copy_gl_cstr(
    const unsigned char *s, unsigned char *dst, size_t dst_sz) {
    if (!s || !dst || dst_sz < 2) return g_empty_gl_str;
    if (!bytes_readable(s, 1)) return g_empty_gl_str;
    size_t off = 0;
    while (off + 1 < dst_sz) {
        size_t want = dst_sz - 1 - off;
        if (want > 256) want = 256;
        if (!bytes_readable(s + off, want)) {
            /* Shrink probe to find NUL before the bad page. */
            while (off + 1 < dst_sz) {
                if (!bytes_readable(s + off, 1)) {
                    dst[0] = 0;
                    return g_empty_gl_str;
                }
                dst[off] = s[off];
                if (s[off] == 0) return dst;
                off++;
            }
            break;
        }
        memcpy(dst + off, s + off, want);
        for (size_t i = 0; i < want; i++) {
            if (dst[off + i] == 0) return dst;
        }
        off += want;
    }
    dst[dst_sz - 1] = 0;
    return dst;
}

static unsigned char *gl_str_slot(unsigned int name) {
    switch (name) {
        case 0x1F00: return g_gl_str_vendor;      /* GL_VENDOR */
        case 0x1F01: return g_gl_str_renderer;    /* GL_RENDERER */
        case 0x1F02: return g_gl_str_version;     /* GL_VERSION */
        case 0x1F03: return g_gl_str_extensions;  /* GL_EXTENSIONS */
        default: return g_gl_str_misc;            /* GLSL / etc. */
    }
}

static size_t gl_str_slot_size(unsigned int name) {
    switch (name) {
        case 0x1F00: return sizeof(g_gl_str_vendor);
        case 0x1F01: return sizeof(g_gl_str_renderer);
        case 0x1F02: return sizeof(g_gl_str_version);
        case 0x1F03: return sizeof(g_gl_str_extensions);
        default: return sizeof(g_gl_str_misc);
    }
}

static const unsigned char *booxin_glGetString(unsigned int name) {
    const unsigned char *s = g_real_glGetString ? g_real_glGetString(name) : NULL;
    if (!s) return g_empty_gl_str;
    return copy_gl_cstr(s, gl_str_slot(name), gl_str_slot_size(name));
}

static const unsigned char *booxin_glGetStringi(unsigned int name, unsigned int index) {
    const unsigned char *s =
        g_real_glGetStringi ? g_real_glGetStringi(name, index) : NULL;
    if (!s) return g_empty_gl_str;
    return copy_gl_cstr(s, g_gl_str_stringi, sizeof(g_gl_str_stringi));
}

/* MC 1.21+ GlSampler: maxAnisotropy must be in [1, deviceMax]. MCrender
 * advertises GL_EXT_texture_filter_anisotropic but glGetFloatv(MAX) → 0. */
#define GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT 0x84FFu
typedef void (*fn_glGetFloatv)(unsigned int pname, float *params);
static fn_glGetFloatv g_real_mcr_glGetFloatv = NULL;
static int g_mcr_aniso_fix = 0;

static void booxin_mcr_glGetFloatv(unsigned int pname, float *params) {
    if (g_real_mcr_glGetFloatv) {
        g_real_mcr_glGetFloatv(pname, params);
    } else if (params) {
        params[0] = 0.f;
    }
    if (pname == GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT && params && params[0] < 1.f) {
        params[0] = 16.f;
    }
}

/** GPA wrap: LWJGL resolves glGetString(i) via eglGetProcAddress and bypasses PLT hooks. */
static void *booxin_safe_eglGetProcAddress(const char *name) {
    if (name) {
        if (!strcmp(name, "glGetString") && g_real_glGetString) {
            return (void *)booxin_glGetString;
        }
        if (!strcmp(name, "glGetStringi") && g_real_glGetStringi) {
            return (void *)booxin_glGetStringi;
        }
        if (g_mcr_aniso_fix &&
            (!strcmp(name, "glGetFloatv") || !strcmp(name, "glGetFloat"))) {
            return (void *)booxin_mcr_glGetFloatv;
        }
    }
    return g_real_eglGPA ? g_real_eglGPA(name) : NULL;
}

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

/* LWJGL SharedLibrary uses dlsym(libmobileglues, "glGetString") and calls the
 * raw pointer — PLT hooks never see it. Rewrite those dlsym results. */
typedef void *(*fn_dlsym)(void *handle, const char *name);
static fn_dlsym g_real_dlsym = NULL;
static void *g_dlsym_prev = NULL;
static int g_dlsym_glstr_hooked = 0;

static void on_dlsym_glstr_hooked(
    void *task_handle,
    int status_code,
    const char *caller_path_name,
    const char *sym_name,
    void *new_func,
    void *prev_func,
    void *hooked_arg) {
    (void)task_handle;
    (void)status_code;
    (void)caller_path_name;
    (void)sym_name;
    (void)new_func;
    (void)hooked_arg;
    if (prev_func) g_dlsym_prev = prev_func;
}

static void *booxin_dlsym_glstr(void *handle, const char *name) {
    fn_dlsym real = NULL;
    if (g_dlsym_prev)
        real = (fn_dlsym)g_dlsym_prev;
    else if (g_real_dlsym)
        real = g_real_dlsym;
    void *p = real ? real(handle, name) : NULL;
    if (!name || !p) return p;
    if (!strcmp(name, "glGetString") && g_real_glGetString) {
        return (void *)booxin_glGetString;
    }
    if (!strcmp(name, "glGetStringi") && g_real_glGetStringi) {
        return (void *)booxin_glGetStringi;
    }
    if (!strcmp(name, "eglGetProcAddress") && g_real_eglGPA) {
        return (void *)booxin_safe_eglGetProcAddress;
    }
    return p;
}

static void install_dlsym_glgetstring_rewrite(void *bh) {
    if (g_dlsym_glstr_hooked || !bh) return;
    bytehook_hook_all_fn hook_all =
        (bytehook_hook_all_fn)dlsym(bh, "bytehook_hook_all");
    bytehook_hook_single_fn hook =
        (bytehook_hook_single_fn)dlsym(bh, "bytehook_hook_single");
    g_real_dlsym = dlsym;
    void *stub = NULL;
    if (hook_all) {
        stub = hook_all(NULL, "dlsym", (void *)booxin_dlsym_glstr,
                        (void *)on_dlsym_glstr_hooked, NULL);
        LOGI("glGetString guard: hook_all dlsym stub=%p", stub);
    }
    if (!stub && hook) {
        stub = hook("libdl.so", NULL, "dlsym", (void *)booxin_dlsym_glstr,
                    (void *)on_dlsym_glstr_hooked, NULL);
        LOGI("glGetString guard: hook_single libdl dlsym stub=%p", stub);
    }
    if (!stub && hook) {
        stub = hook("libc.so", NULL, "dlsym", (void *)booxin_dlsym_glstr,
                    (void *)on_dlsym_glstr_hooked, NULL);
        LOGI("glGetString guard: hook_single libc dlsym stub=%p", stub);
    }
    g_dlsym_glstr_hooked = stub ? 1 : 0;
    if (!g_dlsym_glstr_hooked) {
        LOGW("glGetString guard: dlsym rewrite failed — ColorOS strlen crash may remain");
    }
}

static void *load_bytehook_for_mcr(void) {
    const char *nd = getenv("BOOXIN_NATIVEDIR");
    if (!nd || !nd[0]) nd = getenv("POJAV_NATIVEDIR");
    if (nd && nd[0]) {
        char path[512];
        snprintf(path, sizeof(path), "%s/libbytehook.so", nd);
        void *bh = dlopen(path, RTLD_NOW | RTLD_GLOBAL);
        if (bh) return bh;
    }
    return dlopen("libbytehook.so", RTLD_NOW | RTLD_GLOBAL);
}

/** Clamp MAX_TEXTURE_MAX_ANISOTROPY so 1.21 createSampler does not see max=0. */
static void install_mcrender_anisotropy_fix(void *lib) {
    if (g_mcr_aniso_fix || !lib) return;
    g_real_mcr_glGetFloatv = (fn_glGetFloatv)dlsym(lib, "glGetFloatv");
    if (!g_real_eglGPA) {
        g_real_eglGPA = (fn_eglGetProcAddress)dlsym(lib, "eglGetProcAddress");
    }
    if (!g_real_mcr_glGetFloatv) {
        LOGW("MCrender anisotropy fix: glGetFloatv missing");
        return;
    }

    void *bh = load_bytehook_for_mcr();
    if (bh) {
        bytehook_init_fn init = (bytehook_init_fn)dlsym(bh, "bytehook_init");
        bytehook_hook_single_fn hook =
            (bytehook_hook_single_fn)dlsym(bh, "bytehook_hook_single");
        if (init) init(0, 0);
        if (hook) {
            void *s1 = hook("libmcrender.so", NULL, "glGetFloatv",
                            (void *)booxin_mcr_glGetFloatv, NULL, NULL);
            void *s2 = NULL;
            if (g_real_eglGPA) {
                s2 = hook("libmcrender.so", NULL, "eglGetProcAddress",
                          (void *)booxin_safe_eglGetProcAddress, NULL, NULL);
            }
            LOGI("MCrender anisotropy fix bytehook glGetFloatv=%p eglGPA=%p", s1, s2);
            g_mcr_aniso_fix = 1;
            return;
        }
    }
    /* bytehook unavailable: eglGetProcAddress wrap still helps LWJGL probes. */
    LOGW("MCrender anisotropy fix: bytehook missing, eglGPA wrap only");
    g_mcr_aniso_fix = 1;
}

/**
 * Never let glGetString(i) return NULL or unreadable pointers — LWJGL
 * createCapabilities does strlen on them. Hook translator exports + GPA + dlsym
 * (ColorOS / Adreno: LWJGL often dlsyms libmobileglues directly and bypasses PLT).
 */
static void install_glgetstring_null_guard(void *lib, const char *soname) {
    if (g_glgetstring_hooked || !lib || !soname) return;
    fn_glGetString real = (fn_glGetString)dlsym(lib, "glGetString");
    if (!real) {
        LOGW("glGetString guard: symbol missing in %s", soname);
        return;
    }
    g_real_glGetString = real;
    g_real_glGetStringi = (fn_glGetStringi)dlsym(lib, "glGetStringi");
    if (!g_real_eglGPA) {
        g_real_eglGPA = (fn_eglGetProcAddress)dlsym(lib, "eglGetProcAddress");
    }
    void *bh = load_bytehook_for_mcr();
    if (bh) {
        bytehook_init_fn init = (bytehook_init_fn)dlsym(bh, "bytehook_init");
        bytehook_hook_single_fn hook =
            (bytehook_hook_single_fn)dlsym(bh, "bytehook_hook_single");
        if (init) init(0, 0);
        if (hook) {
            void *stub = hook(soname, NULL, "glGetString",
                              (void *)booxin_glGetString, NULL, NULL);
            void *stub_i = NULL;
            if (g_real_glGetStringi) {
                stub_i = hook(soname, NULL, "glGetStringi",
                              (void *)booxin_glGetStringi, NULL, NULL);
            }
            void *stub_gpa = NULL;
            if (g_real_eglGPA) {
                stub_gpa = hook(soname, NULL, "eglGetProcAddress",
                                (void *)booxin_safe_eglGetProcAddress, NULL, NULL);
            }
            LOGI("glGetString guard hooked %s str=%p stri=%p gpa=%p",
                 soname, stub, stub_i, stub_gpa);
        }
        /* Critical on ColorOS: rewrite LWJGL SharedLibrary dlsym results. */
        install_dlsym_glgetstring_rewrite(bh);
        g_glgetstring_hooked = 1;
        LOGI("glGetString guard ready soname=%s dlsym_rewrite=%d",
             soname, g_dlsym_glstr_hooked);
        return;
    }
    LOGW("glGetString guard: bytehook unavailable — dlsym_EGL / GPA path only");
    g_glgetstring_hooked = 1;
    LOGW("glGetString guard: bytehook missing (soname=%s)", soname);
}

typedef EGLDisplay (*fn_eglGetDisplay)(EGLNativeDisplayType);
typedef EGLBoolean (*fn_eglInitialize)(EGLDisplay, EGLint *, EGLint *);
typedef EGLBoolean (*fn_eglTerminate)(EGLDisplay);
typedef EGLBoolean (*fn_eglChooseConfig)(EGLDisplay, const EGLint *, EGLConfig *, EGLint, EGLint *);
typedef EGLBoolean (*fn_eglGetConfigAttrib)(EGLDisplay, EGLConfig, EGLint, EGLint *);
typedef EGLBoolean (*fn_eglBindAPI)(EGLenum);
typedef EGLSurface (*fn_eglCreateWindowSurface)(EGLDisplay, EGLConfig, EGLNativeWindowType, const EGLint *);
typedef EGLSurface (*fn_eglCreatePbufferSurface)(EGLDisplay, EGLConfig, const EGLint *);
typedef EGLBoolean (*fn_eglDestroySurface)(EGLDisplay, EGLSurface);
typedef EGLContext (*fn_eglCreateContext)(EGLDisplay, EGLConfig, EGLContext, const EGLint *);
typedef EGLBoolean (*fn_eglDestroyContext)(EGLDisplay, EGLContext);
typedef EGLBoolean (*fn_eglMakeCurrent)(EGLDisplay, EGLSurface, EGLSurface, EGLContext);
typedef EGLBoolean (*fn_eglSwapBuffers)(EGLDisplay, EGLSurface);
typedef EGLBoolean (*fn_eglSwapInterval)(EGLDisplay, EGLint);
typedef EGLContext (*fn_eglGetCurrentContext)(void);
typedef EGLint (*fn_eglGetError)(void);

static fn_eglGetDisplay p_eglGetDisplay;
static fn_eglInitialize p_eglInitialize;
static fn_eglTerminate p_eglTerminate;
static fn_eglChooseConfig p_eglChooseConfig;
static fn_eglGetConfigAttrib p_eglGetConfigAttrib;
static fn_eglBindAPI p_eglBindAPI;
static fn_eglCreateWindowSurface p_eglCreateWindowSurface;
static fn_eglCreatePbufferSurface p_eglCreatePbufferSurface;
static fn_eglDestroySurface p_eglDestroySurface;
static fn_eglCreateContext p_eglCreateContext;
static fn_eglDestroyContext p_eglDestroyContext;
static fn_eglMakeCurrent p_eglMakeCurrent;
static fn_eglSwapBuffers p_eglSwapBuffers;
static fn_eglSwapInterval p_eglSwapInterval;
static fn_eglGetCurrentContext p_eglGetCurrentContext;
static fn_eglGetError p_eglGetError;
static EGLint g_native_format = 0;

static void launch_log_egl(const char *fmt, ...) {
    const char *ll = getenv("BOOXIN_LAUNCH_LOG");
    if (!ll || !ll[0]) return;
    FILE *f = fopen(ll, "a");
    if (!f) return;
    va_list ap;
    va_start(ap, fmt);
    vfprintf(f, fmt, ap);
    va_end(ap);
    fputc('\n', f);
    fclose(f);
}

int booxinInitOpenGL(void);
void booxin_egl_detach_window(void);
int booxin_egl_attach_window(void);
static void egl_park_pbuffer_locked(void);

static int path_is_readable(const char *path) {
    return path && path[0] && access(path, R_OK) == 0;
}

static const char *rel_lib_path(void) {
    /* Prefer BOOXIN_EGL / LIBGL_NAME — never LIBGL_EGL (that is host libEGL for REL).
     * Do NOT scan natives/ for leftover librel.so — that wrongly activates REL
     * under MobileGlues and SIGSEGVs in eglCreateContext. */
    const char *path = getenv("BOOXIN_EGL");
    if (path_is_readable(path) && strstr(path, "librel")) return path;
    path = getenv("LIBGL_NAME");
    if (path_is_readable(path) && strstr(path, "librel")) return path;
    path = getenv("SDL_OPENGL_LIBRARY");
    if (path_is_readable(path) && strstr(path, "librel")) return path;
    return NULL;
}

static int want_rel(void) {
    /* Strict: only when launcher selected REL (token contains opengles3_rel). */
    const char *renderer = getenv("BOOXIN_RENDERER");
    if (renderer && strstr(renderer, "opengles3_rel")) return 1;
    return 0;
}

static const char *mcrender_lib_path(void) {
    const char *path = getenv("LIBGL_NAME");
    if (path_is_readable(path) && strstr(path, "mcrender")) return path;
    path = getenv("SDL_OPENGL_LIBRARY");
    if (path_is_readable(path) && strstr(path, "mcrender")) return path;
    return NULL;
}

static int want_mcrender(void) {
    if (mcrender_lib_path()) return 1;
    const char *s = getenv("LIBGL_STRING");
    if (s && strstr(s, "MCrender")) return 1;
    return 0;
}

static const char *mobileglues_lib_path(void) {
    const char *path = getenv("LIBGL_NAME");
    if (path_is_readable(path) && strstr(path, "mobileglues")) return path;
    path = getenv("SDL_OPENGL_LIBRARY");
    if (path_is_readable(path) && strstr(path, "mobileglues")) return path;
    path = getenv("BOOXIN_EGL");
    if (path_is_readable(path) && strstr(path, "mobileglues")) return path;
    /* Disguised as libgl4es_114.so — still load that path when STRING says MG. */
    const char *s = getenv("LIBGL_STRING");
    if (s && strstr(s, "MobileGlues")) {
        path = getenv("LIBGL_NAME");
        if (path_is_readable(path)) return path;
        const char *nd = getenv("BOOXIN_NATIVEDIR");
        if (!nd || !nd[0]) nd = getenv("POJAV_NATIVEDIR");
        if (nd && nd[0]) {
            static char buf[512];
            snprintf(buf, sizeof(buf), "%s/libmobileglues.so", nd);
            if (path_is_readable(buf)) return buf;
        }
    }
    return NULL;
}

static int want_mobileglues(void) {
    if (mobileglues_lib_path()) return 1;
    const char *s = getenv("LIBGL_STRING");
    if (s && strstr(s, "MobileGlues")) return 1;
    return 0;
}

/** Holy GL4ES only — never MG/REL/Krypton disguised as libgl4es_114. */
static const char *gl4es_lib_path(void) {
    const char *s = getenv("LIBGL_STRING");
    /* displayName for holy GL4ES is exactly "GL4ES". */
    if (!s || strcmp(s, "GL4ES") != 0) return NULL;
    const char *path = getenv("LIBGL_NAME");
    if (!path_is_readable(path)) return NULL;
    if (!strstr(path, "libgl4es")) return NULL;
    return path;
}

static int want_gl4es(void) {
    return gl4es_lib_path() != NULL;
}

static void bind_system_egl(void) {
    p_eglGetDisplay = eglGetDisplay;
    p_eglInitialize = eglInitialize;
    p_eglTerminate = eglTerminate;
    p_eglChooseConfig = eglChooseConfig;
    p_eglGetConfigAttrib = eglGetConfigAttrib;
    p_eglBindAPI = eglBindAPI;
    p_eglCreateWindowSurface = eglCreateWindowSurface;
    p_eglCreatePbufferSurface = eglCreatePbufferSurface;
    p_eglDestroySurface = eglDestroySurface;
    p_eglCreateContext = eglCreateContext;
    p_eglDestroyContext = eglDestroyContext;
    p_eglMakeCurrent = eglMakeCurrent;
    p_eglSwapBuffers = eglSwapBuffers;
    p_eglSwapInterval = eglSwapInterval;
    p_eglGetCurrentContext = eglGetCurrentContext;
    p_eglGetError = eglGetError;
    g_use_rel_ctx = 0;
    g_use_mcrender_ctx = 0;
    g_use_mobileglues_ctx = 0;
    g_use_gl4es_ctx = 0;
}

/** Bind full EGL entry points from translator (FCL: POJAVEXEC_EGL=libmobileglues). */
static int bind_full_egl_from_lib(void *lib, const char *tag) {
    void *gd = dlsym(lib, "eglGetDisplay");
    void *ini = dlsym(lib, "eglInitialize");
    void *term = dlsym(lib, "eglTerminate");
    void *ccfg = dlsym(lib, "eglChooseConfig");
    void *gca = dlsym(lib, "eglGetConfigAttrib");
    void *bind = dlsym(lib, "eglBindAPI");
    void *cwin = dlsym(lib, "eglCreateWindowSurface");
    void *cpb = dlsym(lib, "eglCreatePbufferSurface");
    void *ds = dlsym(lib, "eglDestroySurface");
    void *c = dlsym(lib, "eglCreateContext");
    void *d = dlsym(lib, "eglDestroyContext");
    void *m = dlsym(lib, "eglMakeCurrent");
    void *s = dlsym(lib, "eglSwapBuffers");
    void *si = dlsym(lib, "eglSwapInterval");
    void *gc = dlsym(lib, "eglGetCurrentContext");
    void *ge = dlsym(lib, "eglGetError");
    if (!gd || !ini || !ccfg || !cwin || !c || !d || !m || !s) {
        LOGE("%s missing full EGL exports gd=%p ini=%p ccfg=%p cwin=%p c=%p d=%p m=%p s=%p",
             tag, gd, ini, ccfg, cwin, c, d, m, s);
        return 0;
    }
    p_eglGetDisplay = (fn_eglGetDisplay)gd;
    p_eglInitialize = (fn_eglInitialize)ini;
    p_eglTerminate = term ? (fn_eglTerminate)term : eglTerminate;
    p_eglChooseConfig = (fn_eglChooseConfig)ccfg;
    p_eglGetConfigAttrib = gca ? (fn_eglGetConfigAttrib)gca : eglGetConfigAttrib;
    p_eglBindAPI = bind ? (fn_eglBindAPI)bind : eglBindAPI;
    p_eglCreateWindowSurface = (fn_eglCreateWindowSurface)cwin;
    p_eglCreatePbufferSurface = cpb ? (fn_eglCreatePbufferSurface)cpb : eglCreatePbufferSurface;
    p_eglDestroySurface = ds ? (fn_eglDestroySurface)ds : eglDestroySurface;
    p_eglCreateContext = (fn_eglCreateContext)c;
    p_eglDestroyContext = (fn_eglDestroyContext)d;
    p_eglMakeCurrent = (fn_eglMakeCurrent)m;
    p_eglSwapBuffers = (fn_eglSwapBuffers)s;
    p_eglSwapInterval = si ? (fn_eglSwapInterval)si : eglSwapInterval;
    p_eglGetCurrentContext = gc ? (fn_eglGetCurrentContext)gc : eglGetCurrentContext;
    p_eglGetError = ge ? (fn_eglGetError)ge : eglGetError;
    LOGI("%s: full EGL stack (GetDisplay…SwapBuffers)", tag);
    return 1;
}

/** Bind CreateContext/DestroyContext/MakeCurrent; SwapBuffers optional (holy gl4es
 *  must keep system SwapBuffers or Mojang logo → black screen on many OEMs). */
static int bind_wrapped_context_egl(void *lib, const char *tag, int use_lib_swap) {
    void *c = dlsym(lib, "eglCreateContext");
    void *d = dlsym(lib, "eglDestroyContext");
    void *m = dlsym(lib, "eglMakeCurrent");
    void *s = dlsym(lib, "eglSwapBuffers");
    if (!s) {
        typedef void *(*get_proc_t)(const char *);
        get_proc_t get = (get_proc_t)dlsym(lib, "eglGetProcAddress");
        if (get) {
            s = get("eglSwapBuffers");
            LOGI("%s: eglSwapBuffers via eglGetProcAddress => %p", tag, s);
        }
    }
    if (!c || !d || !m) {
        LOGE("%s missing context EGL exports c=%p d=%p m=%p s=%p", tag, c, d, m, s);
        return 0;
    }
    p_eglCreateContext = (fn_eglCreateContext)c;
    p_eglDestroyContext = (fn_eglDestroyContext)d;
    p_eglMakeCurrent = (fn_eglMakeCurrent)m;
    if (use_lib_swap && s) {
        p_eglSwapBuffers = (fn_eglSwapBuffers)s;
        LOGI("%s: eglSwapBuffers=translator", tag);
    } else {
        p_eglSwapBuffers = eglSwapBuffers;
        LOGI("%s: eglSwapBuffers=system", tag);
    }
    return 1;
}

static int load_egl_fns(void) {
    if (p_eglGetDisplay) return 1;
    bind_system_egl();

    /* MCrender before REL: TinywrapperEGL must own CreateContext/MakeCurrent. */
    if (want_mcrender()) {
        const char *path = mcrender_lib_path();
        if (!path || !path[0]) {
            LOGE("MCrender selected but libmcrender path missing");
            return 0;
        }
        g_mcrender_lib = dlopen(path, RTLD_LAZY | RTLD_GLOBAL);
        if (!g_mcrender_lib) {
            LOGE("dlopen MCrender failed %s: %s", path, dlerror());
            return 0;
        }
        if (!bind_wrapped_context_egl(g_mcrender_lib, "MCrender", 1)) {
            return 0;
        }
        install_mcrender_anisotropy_fix(g_mcrender_lib);
        install_glgetstring_null_guard(g_mcrender_lib, "libmcrender.so");
        g_use_mcrender_ctx = 1;
        LOGI("EGL: system display + MCrender context path=%s", path);
        return 1;
    }

    if (want_mobileglues()) {
        const char *path = mobileglues_lib_path();
        if (!path || !path[0]) {
            LOGE("MobileGlues selected but libmobileglues path missing");
            return 0;
        }
        g_mobileglues_lib = dlopen(path, RTLD_NOW | RTLD_GLOBAL);
        if (!g_mobileglues_lib) {
            LOGE("dlopen MobileGlues RTLD_NOW failed %s: %s — retry LAZY", path, dlerror());
            g_mobileglues_lib = dlopen(path, RTLD_LAZY | RTLD_GLOBAL);
        }
        if (!g_mobileglues_lib) {
            LOGE("dlopen MobileGlues failed %s: %s", path, dlerror());
            return 0;
        }
        /* FCL/Zalith: POJAVEXEC_EGL=LIBGL_EGL=libmobileglues — full EGL via MG. */
        if (!bind_full_egl_from_lib(g_mobileglues_lib, "MobileGlues")) {
            return 0;
        }
        install_glgetstring_null_guard(g_mobileglues_lib, "libmobileglues.so");
        g_use_mobileglues_ctx = 1;
        LOGI("EGL: full MobileGlues stack path=%s", path);
        launch_log_egl("EGL: full MobileGlues stack mg_ctx=1 path=%s", path);
        return 1;
    }

    if (want_gl4es()) {
        const char *path = gl4es_lib_path();
        if (!path || !path[0]) {
            LOGE("GL4ES selected but libgl4es path missing");
            return 0;
        }
        g_gl4es_lib = dlopen(path, RTLD_LAZY | RTLD_GLOBAL);
        if (!g_gl4es_lib) {
            LOGE("dlopen GL4ES failed %s: %s", path, dlerror());
            LOGW("GL4ES: continuing with system EGL only");
            return 1;
        }
        /* Context via GL4ES; SwapBuffers must stay system — translator swap
         * blacks out after Mojang on ColorOS / many Adreno devices. */
        if (!bind_wrapped_context_egl(g_gl4es_lib, "GL4ES", 0)) {
            LOGW("GL4ES: context EGL bind failed, system EGL only");
            return 1;
        }
        install_glgetstring_null_guard(g_gl4es_lib, "libgl4es_114.so");
        g_use_gl4es_ctx = 1;
        LOGI("EGL: system display + GL4ES context path=%s", path);
        return 1;
    }

    if (!want_rel()) {
        LOGI("EGL: system (no translator wrap)");
        return 1;
    }
    const char *path = rel_lib_path();
    if (!path || !path[0] || strcmp(path, "libEGL.so") == 0) {
        LOGE("REL selected but librel path missing");
        return 0;
    }
    /* RTLD_LAZY: REL pulls many GLES symbols; RTLD_NOW can fail on some OEMs. */
    g_rel_lib = dlopen(path, RTLD_LAZY | RTLD_GLOBAL);
    if (!g_rel_lib) {
        LOGE("dlopen REL failed %s: %s", path, dlerror());
        /* Fall back to system EGL so glfwInit can still succeed; GL may be limited. */
        LOGW("REL: continuing with system EGL only");
        return 1;
    }
    if (!bind_wrapped_context_egl(g_rel_lib, "REL", 1)) {
        return 0;
    }
    install_glgetstring_null_guard(g_rel_lib, "librel.so");
    g_use_rel_ctx = 1;
    LOGI("EGL: system display + REL context path=%s", path);
    return 1;
}

static int egl_recreate_window_surface_locked(void) {
    if (g_display == EGL_NO_DISPLAY || !g_initialized) return 0;
    ANativeWindow *win = booxin_ensure_native_window();
    if (!win) {
        LOGW("egl recreate: no native window yet");
        launch_log_egl("egl recreate: no native window");
        /* Keep current surface (pause pbuffer) so the GL context stays current
         * while backgrounded — do not destroy first. */
        return 0;
    }
    if (g_surface != EGL_NO_SURFACE) {
        p_eglMakeCurrent(g_display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        p_eglDestroySurface(g_display, g_surface);
        g_surface = EGL_NO_SURFACE;
        g_current = NULL;
    }
    /* Pojav: setBuffersGeometry(…, EGL_NATIVE_VISUAL_ID) before CreateWindowSurface. */
    if (g_native_format != 0) {
        ANativeWindow_setBuffersGeometry(win, 0, 0, g_native_format);
    }
    g_surface = p_eglCreateWindowSurface(
        g_display, g_config, (EGLNativeWindowType)win, NULL);
    if (g_surface == EGL_NO_SURFACE) {
        EGLint err = p_eglGetError();
        LOGE("eglCreateWindowSurface failed: 0x%x — try 1x1 pbuffer", err);
        launch_log_egl("eglCreateWindowSurface failed: 0x%x — pbuffer fallback", err);
        if (p_eglCreatePbufferSurface) {
            const EGLint pb[] = { EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE };
            g_surface = p_eglCreatePbufferSurface(g_display, g_config, pb);
        }
        if (g_surface == EGL_NO_SURFACE) {
            LOGE("eglCreatePbufferSurface also failed: 0x%x", p_eglGetError());
            return 0;
        }
    }
    LOGI("egl window surface recreated on GL thread %dx%d fmt=%d",
         ANativeWindow_getWidth(win), ANativeWindow_getHeight(win), (int)g_native_format);
    launch_log_egl("egl surface ok %dx%d fmt=%d",
                   ANativeWindow_getWidth(win), ANativeWindow_getHeight(win),
                   (int)g_native_format);
    return 1;
}

static void read_renderer_env(void) {
    const char *renderer = getenv("BOOXIN_RENDERER");
    if (!renderer || !renderer[0]) renderer = getenv("POJAV_RENDERER");
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

int booxinInit(void) {
    booxin_environ_t *e = booxin_environ;
    ANativeWindow *win = booxin_ensure_native_window();
    if (!e || !win) {
        LOGE("booxinInit: native window missing (environ=%p window=%p retained_ensure=%p)",
             (void *)e, e ? e->nativeWindow : NULL, (void *)win);
        return 0;
    }
    ANativeWindow_acquire(win);
    e->nativeWindow = win;
    e->savedWidth = ANativeWindow_getWidth(win);
    e->savedHeight = ANativeWindow_getHeight(win);
    /* Format only (0×0) — matching Pojav gl_swap_surface. Passing explicit
     * WxH here can desync TextureView and make the whole frame look magnified. */
    ANativeWindow_setBuffersGeometry(
        win, 0, 0, AHARDWAREBUFFER_FORMAT_R8G8B8X8_UNORM);
    LOGI("booxinInit window=%dx%d", e->savedWidth, e->savedHeight);
    if (!booxinInitOpenGL()) {
        LOGE("booxinInit: booxinInitOpenGL failed");
        return 0;
    }
    return 1;
}

int booxinInitOpenGL(void) {
    /* Native path may still read POJAV_RENDERER; ensure after Java env freeze. */
    {
        const char *booxin = getenv("BOOXIN_RENDERER");
        const char *pojav = getenv("POJAV_RENDERER");
        if (!pojav || !pojav[0]) {
            if (booxin && strstr(booxin, "opengles3_rel")) {
                setenv("POJAV_RENDERER", "opengles3", 1);
            } else if (booxin && booxin[0]) {
                setenv("POJAV_RENDERER", booxin, 1);
            } else {
                setenv("POJAV_RENDERER", "opengles3", 1);
            }
        }
    }
    read_renderer_env();
    if (!load_egl_fns()) {
        LOGE("booxinInitOpenGL: EGL load failed");
        return 0;
    }
    if (g_display == EGL_NO_DISPLAY) {
        g_display = p_eglGetDisplay(EGL_DEFAULT_DISPLAY);
        if (g_display == EGL_NO_DISPLAY) {
            LOGE("eglGetDisplay failed");
            return 0;
        }
        if (!p_eglInitialize(g_display, NULL, NULL)) {
            LOGE("eglInitialize failed: 0x%x", p_eglGetError());
            return 0;
        }
    }

    /* Match Pojav gl_bridge: ES2_BIT + WINDOW|PBUFFER. ES3_BIT-only configs
     * fail MakeCurrent on some ColorOS/Adreno devices → glGetString null. */
    EGLint attribs[] = {
        EGL_BLUE_SIZE, 8,
        EGL_GREEN_SIZE, 8,
        EGL_RED_SIZE, 8,
        EGL_ALPHA_SIZE, 8,
        EGL_DEPTH_SIZE, 24,
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT | EGL_PBUFFER_BIT,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
        EGL_NONE
    };
    EGLint num = 0;
    if (!p_eglChooseConfig(g_display, attribs, &g_config, 1, &num) || num < 1) {
        LOGE("eglChooseConfig failed: 0x%x", p_eglGetError());
        launch_log_egl("eglChooseConfig failed: 0x%x", p_eglGetError());
        return 0;
    }
    g_native_format = 0;
    if (p_eglGetConfigAttrib) {
        p_eglGetConfigAttrib(g_display, g_config, EGL_NATIVE_VISUAL_ID, &g_native_format);
    }
    p_eglBindAPI(g_gles_api);
    g_initialized = 1;
    LOGI("booxinInitOpenGL ok gles=%d fmt=%d rel=%d mcr=%d mg=%d gl4=%d",
         g_gles_version, (int)g_native_format, g_use_rel_ctx, g_use_mcrender_ctx,
         g_use_mobileglues_ctx, g_use_gl4es_ctx);
    launch_log_egl("booxinInitOpenGL ok gles=%d fmt=%d mg_ctx=%d",
                   g_gles_version, (int)g_native_format, g_use_mobileglues_ctx);
    return 1;
}

void *booxinCreateContext(void *contextSrc) {
    if (!g_initialized && !booxinInitOpenGL()) return NULL;
    EGLint ctx_attribs[] = {
        EGL_CONTEXT_CLIENT_VERSION, g_gles_version >= 3 ? 3 : 2,
        EGL_NONE
    };
    /* Honor GLFW share hint (Pojav passes share bundle; we pass EGLContext). */
    EGLContext share = EGL_NO_CONTEXT;
    if (contextSrc && contextSrc != (void *)(intptr_t)-1) {
        share = (EGLContext)contextSrc;
    } else if (g_context != EGL_NO_CONTEXT) {
        share = g_context;
    }
    EGLContext ctx = p_eglCreateContext(g_display, g_config, share, ctx_attribs);
    if (ctx == EGL_NO_CONTEXT) {
        LOGE("eglCreateContext failed: 0x%x", p_eglGetError());
        launch_log_egl("eglCreateContext failed: 0x%x", p_eglGetError());
        return NULL;
    }
    if (g_context == EGL_NO_CONTEXT) g_context = ctx;

    booxin_environ_t *e = booxin_environ;
    if (e && e->nativeWindow && g_surface == EGL_NO_SURFACE) {
        pthread_mutex_lock(&g_egl_mu);
        egl_recreate_window_surface_locked();
        pthread_mutex_unlock(&g_egl_mu);
    }
    LOGI("booxinCreateContext %p rel=%d mcrender=%d mg=%d gl4es=%d",
         (void *)ctx, g_use_rel_ctx, g_use_mcrender_ctx,
         g_use_mobileglues_ctx, g_use_gl4es_ctx);
    launch_log_egl("booxinCreateContext %p mg=%d", (void *)ctx, g_use_mobileglues_ctx);
    return (void *)ctx;
}

void *booxinGetCurrentContext(void) {
    return g_current ? g_current : (void *)p_eglGetCurrentContext();
}

void booxinMakeCurrent(void *window) {
    if (!g_initialized && !booxinInitOpenGL()) return;
    EGLContext ctx = window ? (EGLContext)window : g_context;
    if (ctx == EGL_NO_CONTEXT) ctx = g_context;
    pthread_mutex_lock(&g_egl_mu);
    if (atomic_exchange(&g_detach_requested, 0)) {
        egl_park_pbuffer_locked();
        atomic_store(&g_surface_stale, 1);
    }
    if (atomic_load(&g_surface_stale) || g_surface == EGL_NO_SURFACE) {
        if (!egl_recreate_window_surface_locked()) {
            launch_log_egl("booxinMakeCurrent: surface recreate failed");
            atomic_store(&g_surface_stale, 1);
            /* Still try MakeCurrent on pause pbuffer if we have one. */
            if (g_surface == EGL_NO_SURFACE || ctx == EGL_NO_CONTEXT) {
                pthread_mutex_unlock(&g_egl_mu);
                return;
            }
        } else {
            atomic_store(&g_surface_stale, 0);
        }
    }
    if (!p_eglMakeCurrent(g_display, g_surface, g_surface, ctx)) {
        EGLint err = p_eglGetError();
        LOGE("eglMakeCurrent failed: 0x%x — recreate once", err);
        launch_log_egl("eglMakeCurrent failed: 0x%x — retry", err);
        atomic_store(&g_surface_stale, 1);
        if (!egl_recreate_window_surface_locked() ||
            !p_eglMakeCurrent(g_display, g_surface, g_surface, ctx)) {
            launch_log_egl("eglMakeCurrent retry failed: 0x%x", p_eglGetError());
            pthread_mutex_unlock(&g_egl_mu);
            return;
        }
        atomic_store(&g_surface_stale, 0);
    }
    g_current = (void *)ctx;
    if (booxin_environ) booxin_environ->showingWindow = (long)(intptr_t)ctx;
    LOGI("booxinMakeCurrent ok ctx=%p surface=%p", (void *)ctx, (void *)g_surface);
    /* Prove GL is live before LWJGL createCapabilities (ColorOS hides logcat). */
    {
        fn_glGetString probe = g_glgetstring_hooked ? booxin_glGetString : NULL;
        if (!probe) {
            probe = g_real_glGetString;
            if (!probe && g_mobileglues_lib)
                probe = (fn_glGetString)dlsym(g_mobileglues_lib, "glGetString");
            if (!probe && g_mcrender_lib)
                probe = (fn_glGetString)dlsym(g_mcrender_lib, "glGetString");
            if (!probe && g_gl4es_lib)
                probe = (fn_glGetString)dlsym(g_gl4es_lib, "glGetString");
            if (!probe && g_rel_lib)
                probe = (fn_glGetString)dlsym(g_rel_lib, "glGetString");
        }
        const unsigned char *ver = NULL;
        const unsigned char *ext = NULL;
        int ext_len = -1;
        if (probe == booxin_glGetString || g_glgetstring_hooked) {
            ver = booxin_glGetString(0x1F02 /* GL_VERSION */);
            ext = booxin_glGetString(0x1F03 /* GL_EXTENSIONS */);
            ext_len = ext ? (int)strlen((const char *)ext) : 0;
        } else if (probe) {
            /* Unwrapped path: never strlen a raw MG pointer (may be dangling). */
            const unsigned char *raw_v = probe(0x1F02);
            ver = copy_gl_cstr(raw_v, g_gl_str_version, sizeof(g_gl_str_version));
            const unsigned char *raw_e = probe(0x1F03);
            ext = copy_gl_cstr(raw_e, g_gl_str_extensions, sizeof(g_gl_str_extensions));
            ext_len = ext ? (int)strlen((const char *)ext) : 0;
        }
        launch_log_egl("booxinMakeCurrent ok ctx=%p glGetString(VERSION)=%s EXT_len=%d",
                       (void *)ctx,
                       ver && ver[0] ? (const char *)ver : "(null/empty)",
                       ext_len);
        if (!ver || !ver[0]) {
            launch_log_egl("WARNING: glGetString null after MakeCurrent — MG/context mismatch");
        }
    }
    {
        const char *force = getenv("FORCE_VSYNC");
        int interval = (force && force[0] == 't') ? 1 : 0;
        p_eglSwapInterval(g_display, interval);
    }
    pthread_mutex_unlock(&g_egl_mu);
}

static void egl_park_pbuffer_locked(void) {
    if (g_display == EGL_NO_DISPLAY || !g_initialized) return;
    if (g_surface != EGL_NO_SURFACE) {
        p_eglMakeCurrent(g_display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        p_eglDestroySurface(g_display, g_surface);
        g_surface = EGL_NO_SURFACE;
        g_current = NULL;
    }
    if (g_context != EGL_NO_CONTEXT && p_eglCreatePbufferSurface) {
        const EGLint pb[] = { EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE };
        g_surface = p_eglCreatePbufferSurface(g_display, g_config, pb);
        if (g_surface != EGL_NO_SURFACE &&
            p_eglMakeCurrent(g_display, g_surface, g_surface, g_context)) {
            g_current = (void *)g_context;
            LOGI("egl: parked on 1x1 pbuffer (JVM kept alive)");
            launch_log_egl("egl: pbuffer park ok");
            /* Release Android window only after EGL no longer references it. */
            booxin_retain_native_window(NULL);
            return;
        }
        LOGW("egl: pbuffer park failed 0x%x",
             p_eglGetError ? p_eglGetError() : -1);
    }
    g_current = NULL;
    booxin_retain_native_window(NULL);
}

void booxinSwapBuffers(void) {
    if (g_display == EGL_NO_DISPLAY) return;
    pthread_mutex_lock(&g_egl_mu);
    if (atomic_exchange(&g_detach_requested, 0)) {
        egl_park_pbuffer_locked();
        atomic_store(&g_surface_stale, 1);
        pthread_mutex_unlock(&g_egl_mu);
        return;
    }
    if (atomic_load(&g_surface_stale) || g_surface == EGL_NO_SURFACE) {
        if (egl_recreate_window_surface_locked()) {
            atomic_store(&g_surface_stale, 0);
            if (g_context != EGL_NO_CONTEXT) {
                if (!p_eglMakeCurrent(g_display, g_surface, g_surface, g_context)) {
                    LOGE("eglMakeCurrent(after recreate) failed: 0x%x", p_eglGetError());
                    atomic_store(&g_surface_stale, 1);
                    pthread_mutex_unlock(&g_egl_mu);
                    return;
                }
                g_current = (void *)g_context;
            }
        } else {
            /* Still backgrounded / no window — leave stale set, skip swap. */
            atomic_store(&g_surface_stale, 1);
            pthread_mutex_unlock(&g_egl_mu);
            return;
        }
    }
    if (g_surface != EGL_NO_SURFACE) {
        if (!p_eglSwapBuffers(g_display, g_surface)) {
            EGLint err = p_eglGetError();
            LOGW("SwapBuffers failed 0x%x — mark stale", err);
            p_eglMakeCurrent(g_display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            p_eglDestroySurface(g_display, g_surface);
            g_surface = EGL_NO_SURFACE;
            g_current = NULL;
            atomic_store(&g_surface_stale, 1);
        }
    }
    pthread_mutex_unlock(&g_egl_mu);
}

void booxin_egl_detach_window(void) {
    /* Request only — actual DestroySurface/MakeCurrent must run on the GL
     * (Render) thread. Doing it from Activity/UI crashes Adreno/MobileGlues
     * and kills :game → user lands on MainActivity. */
    atomic_store(&g_detach_requested, 1);
    atomic_store(&g_surface_stale, 1);
    LOGI("egl detach requested (GL thread will park on pbuffer)");
    launch_log_egl("egl detach requested");
}

int booxin_egl_attach_window(void) {
    if (!booxin_ensure_native_window()) {
        LOGW("egl attach: no native window");
        return 0;
    }
    atomic_store(&g_detach_requested, 0);
    /* Only mark stale — do NOT MakeCurrent / recreate on the UI/service thread. */
    atomic_store(&g_surface_stale, 1);
    LOGI("egl mark stale (attach / resume) — GL thread will recreate");
    launch_log_egl("egl attach: stale marked for GL-thread recreate");
    return 1;
}

void booxinSwapInterval(int interval) {
    if (g_display == EGL_NO_DISPLAY || !p_eglSwapInterval) return;
    const char *force = getenv("FORCE_VSYNC");
    if (!(force && force[0] == 't')) {
        interval = 0;
    }
    p_eglSwapInterval(g_display, interval);
}

void booxinSetWindowHint(int hint, int value) {
    (void)hint; (void)value;
}

void booxinTerminate(void) {
    if (g_display != EGL_NO_DISPLAY && p_eglMakeCurrent) {
        p_eglMakeCurrent(g_display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (g_surface != EGL_NO_SURFACE) p_eglDestroySurface(g_display, g_surface);
        if (g_context != EGL_NO_CONTEXT) p_eglDestroyContext(g_display, g_context);
        p_eglTerminate(g_display);
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
    void *wrap = NULL;
    if (g_use_mcrender_ctx) wrap = g_mcrender_lib;
    else if (g_use_mobileglues_ctx) wrap = g_mobileglues_lib;
    else if (g_use_gl4es_ctx) wrap = g_gl4es_lib;
    else if (g_use_rel_ctx) wrap = g_rel_lib;
    if (wrap && name) {
        if (!strcmp(name, "glGetString") && g_real_glGetString) {
            return (void *)booxin_glGetString;
        }
        if (!strcmp(name, "glGetStringi") && g_real_glGetStringi) {
            return (void *)booxin_glGetStringi;
        }
        /* Always wrap GPA so LWJGL createCapabilities never sees raw MG pointers. */
        if (!strcmp(name, "eglGetProcAddress") && g_real_eglGPA) {
            return (void *)booxin_safe_eglGetProcAddress;
        }
        if (g_use_mcrender_ctx && g_mcr_aniso_fix &&
            (!strcmp(name, "glGetFloatv") || !strcmp(name, "glGetFloat"))) {
            return (void *)booxin_mcr_glGetFloatv;
        }
        if (!strcmp(name, "eglCreateContext") ||
            !strcmp(name, "eglDestroyContext") ||
            !strcmp(name, "eglMakeCurrent") ||
            !strcmp(name, "eglSwapBuffers") ||
            (g_use_mobileglues_ctx && (
                !strcmp(name, "eglGetDisplay") ||
                !strcmp(name, "eglInitialize") ||
                !strcmp(name, "eglChooseConfig") ||
                !strcmp(name, "eglCreateWindowSurface") ||
                !strcmp(name, "eglDestroySurface") ||
                !strcmp(name, "eglGetCurrentContext") ||
                !strcmp(name, "eglGetError") ||
                !strcmp(name, "eglSwapInterval") ||
                !strcmp(name, "eglBindAPI")))) {
            void *sym = dlsym(wrap, name);
            if (sym) return sym;
        }
    }
    if (name && !strcmp(name, "glGetString") && g_real_glGetString) {
        return (void *)booxin_glGetString;
    }
    if (name && !strcmp(name, "glGetStringi") && g_real_glGetStringi) {
        return (void *)booxin_glGetStringi;
    }
    if (name && !strcmp(name, "eglGetProcAddress") && g_real_eglGPA) {
        return (void *)booxin_safe_eglGetProcAddress;
    }
    return dlsym(handle ? handle : RTLD_DEFAULT, name);
}

void *dlsym_OSMesa(void *handle, const char *name) {
    return dlsym(handle ? handle : RTLD_DEFAULT, name);
}
