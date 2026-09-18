#include "booxin_environ.h"

#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <dlfcn.h>
#include <jni.h>
#include <stdint.h>
#include <stdio.h>
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
int booxinInit(void);
int booxinInitOpenGL(void);
void booxin_egl_detach_window(void);
int booxin_egl_attach_window(void);

static JavaVM *g_vm = NULL;

/* Minimal Vulkan Android ABI (avoid pulling vulkan.h into the bridge). */
typedef uint32_t BooxinVkFlags;
typedef uint64_t BooxinVkSurfaceKHR;
typedef struct BooxinVkAndroidSurfaceCreateInfoKHR {
    int32_t sType; /* VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR = 1000008000 */
    const void *pNext;
    BooxinVkFlags flags;
    ANativeWindow *window;
} BooxinVkAndroidSurfaceCreateInfoKHR;

typedef struct BooxinVkPresentInfoKHR {
    int32_t sType;
    const void *pNext;
    uint32_t waitSemaphoreCount;
    const void *pWaitSemaphores;
    uint32_t swapchainCount;
    const void *pSwapchains;
    const uint32_t *pImageIndices;
    int32_t *pResults;
} BooxinVkPresentInfoKHR;

/* VkExtent2D + subset of VkSurfaceCapabilitiesKHR we need. */
typedef struct BooxinVkExtent2D {
    uint32_t width;
    uint32_t height;
} BooxinVkExtent2D;

typedef struct BooxinVkSurfaceCapabilitiesKHR {
    uint32_t minImageCount;
    uint32_t maxImageCount;
    BooxinVkExtent2D currentExtent;
    BooxinVkExtent2D minImageExtent;
    BooxinVkExtent2D maxImageExtent;
    uint32_t maxImageArrayLayers;
    uint32_t supportedTransforms;
    uint32_t currentTransform;
    uint32_t supportedCompositeAlpha;
    uint32_t supportedUsageFlags;
} BooxinVkSurfaceCapabilitiesKHR;

/* VkSwapchainCreateInfoKHR layout (KHR swapchain). */
typedef struct BooxinVkSwapchainCreateInfoKHR {
    int32_t sType;
    const void *pNext;
    uint32_t flags;
    uint64_t surface;
    uint32_t minImageCount;
    int32_t imageFormat;
    int32_t imageColorSpace;
    BooxinVkExtent2D imageExtent;
    uint32_t imageArrayLayers;
    uint32_t imageUsage;
    int32_t imageSharingMode;
    uint32_t queueFamilyIndexCount;
    const uint32_t *pQueueFamilyIndices;
    uint32_t preTransform;
    uint32_t compositeAlpha;
    int32_t presentMode;
    uint32_t clipped;
    uint64_t oldSwapchain;
} BooxinVkSwapchainCreateInfoKHR;

typedef int32_t (*booxin_vkCreateAndroidSurfaceKHR_fn)(
    void *instance,
    const BooxinVkAndroidSurfaceCreateInfoKHR *pCreateInfo,
    const void *pAllocator,
    BooxinVkSurfaceKHR *pSurface);
typedef int32_t (*booxin_vkQueuePresentKHR_fn)(void *queue, const BooxinVkPresentInfoKHR *pPresentInfo);
typedef int32_t (*booxin_vkCreateSwapchainKHR_fn)(
    void *device,
    const BooxinVkSwapchainCreateInfoKHR *pCreateInfo,
    const void *pAllocator,
    uint64_t *pSwapchain);
typedef int32_t (*booxin_vkGetPhysicalDeviceSurfaceCapabilitiesKHR_fn)(
    void *physicalDevice,
    uint64_t surface,
    BooxinVkSurfaceCapabilitiesKHR *pSurfaceCapabilities);
typedef void *(*booxin_vkGetInstanceProcAddr_fn)(void *instance, const char *name);
typedef void *(*booxin_vkGetDeviceProcAddr_fn)(void *device, const char *name);
typedef void *(*bytehook_hook_all_fn)(
    const char *pathname_regex,
    const char *sym_name,
    void *new_func,
    void *hooked,
    void *hooked_arg);

static booxin_vkCreateAndroidSurfaceKHR_fn g_real_vkCreateAndroidSurfaceKHR = NULL;
static booxin_vkQueuePresentKHR_fn g_real_vkQueuePresentKHR = NULL;
static booxin_vkCreateSwapchainKHR_fn g_real_vkCreateSwapchainKHR = NULL;
static booxin_vkGetPhysicalDeviceSurfaceCapabilitiesKHR_fn g_real_vkGetCaps = NULL;
static booxin_vkGetInstanceProcAddr_fn g_real_vkGetInstanceProcAddr = NULL;
static booxin_vkGetDeviceProcAddr_fn g_real_vkGetDeviceProcAddr = NULL;
static void *g_vulkan_loader_handle = NULL;
static int g_vk_android_hooks = 0;
/* VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR */
static uint32_t g_vk_current_transform = 0x1u;
static BooxinVkExtent2D g_vk_current_extent = {0, 0};

typedef void *(*booxin_dlsym_fn)(void *handle, const char *symbol);
static booxin_dlsym_fn g_real_dlsym = NULL;

static int32_t booxin_vkQueuePresentKHR(void *queue, const BooxinVkPresentInfoKHR *pPresentInfo);
static void *booxin_vkGetDeviceProcAddr(void *device, const char *name);
static int32_t booxin_vkCreateSwapchainKHR(
    void *device,
    const BooxinVkSwapchainCreateInfoKHR *pCreateInfo,
    const void *pAllocator,
    uint64_t *pSwapchain);

static int32_t booxin_vkCreateAndroidSurfaceKHR(
    void *instance,
    const BooxinVkAndroidSurfaceCreateInfoKHR *pCreateInfo,
    const void *pAllocator,
    BooxinVkSurfaceKHR *pSurface) {
    BooxinVkAndroidSurfaceCreateInfoKHR info;
    if (!g_real_vkCreateAndroidSurfaceKHR || !pCreateInfo) return -3; /* VK_ERROR_INITIALIZATION_FAILED */
    info = *pCreateInfo;
    ANativeWindow *retained = booxin_ensure_native_window();
    if (retained && info.window != retained) {
        LOGI("vkCreateAndroidSurfaceKHR: force retained=%p (was %p) %dx%d",
             (void *)retained, (void *)info.window,
             ANativeWindow_getWidth(retained), ANativeWindow_getHeight(retained));
        info.window = retained;
    } else if (retained) {
        static int once;
        if (!once) {
            once = 1;
            LOGI("vkCreateAndroidSurfaceKHR: retained already %p %dx%d",
                 (void *)retained,
                 ANativeWindow_getWidth(retained), ANativeWindow_getHeight(retained));
        }
    } else {
        LOGW("vkCreateAndroidSurfaceKHR: no retained ANativeWindow");
    }
    return g_real_vkCreateAndroidSurfaceKHR(instance, &info, pAllocator, pSurface);
}

static int32_t booxin_vkQueuePresentKHR(void *queue, const BooxinVkPresentInfoKHR *pPresentInfo) {
    int32_t rc = g_real_vkQueuePresentKHR
        ? g_real_vkQueuePresentKHR(queue, pPresentInfo)
        : -3;
    /* VK_SUCCESS=0, VK_SUBOPTIMAL_KHR=1000001003 */
    if (rc == 0 || rc == 1000001003) {
        booxin_note_sdl_present();
    }
    return rc;
}

static int32_t booxin_vkGetPhysicalDeviceSurfaceCapabilitiesKHR(
    void *physicalDevice,
    uint64_t surface,
    BooxinVkSurfaceCapabilitiesKHR *pSurfaceCapabilities) {
    int32_t rc = g_real_vkGetCaps
        ? g_real_vkGetCaps(physicalDevice, surface, pSurfaceCapabilities)
        : -3;
    if (rc == 0 && pSurfaceCapabilities) {
        g_vk_current_transform = pSurfaceCapabilities->currentTransform;
        g_vk_current_extent = pSurfaceCapabilities->currentExtent;
        LOGI("vkGetCaps: currentTransform=0x%x extent=%ux%u",
             (unsigned)g_vk_current_transform,
             (unsigned)g_vk_current_extent.width,
             (unsigned)g_vk_current_extent.height);
    }
    return rc;
}

/**
 * 2026-09 横屏：caps.currentTransform 常为 ROTATE_90。
 * 客户端用同值做 preTransform 时合成器不再转，画面会横过来（触控层正常）。
 * 这里改成 IDENTITY，让系统自己转；宽高不动。
 * 曾试过改回对齐 currentTransform，无效。
 */
static int32_t booxin_vkCreateSwapchainKHR(
    void *device,
    const BooxinVkSwapchainCreateInfoKHR *pCreateInfo,
    const void *pAllocator,
    uint64_t *pSwapchain) {
    BooxinVkSwapchainCreateInfoKHR info;
    int32_t rc;
    /* VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR */
    const uint32_t identity = 0x1u;
    if (!g_real_vkCreateSwapchainKHR || !pCreateInfo) return -3;
    info = *pCreateInfo;
    if (g_vk_current_transform != 0 &&
        g_vk_current_transform != identity &&
        info.preTransform != identity) {
        LOGI("vkCreateSwapchainKHR: preTransform 0x%x → IDENTITY (let compositor apply 0x%x) extent=%ux%u",
             (unsigned)info.preTransform, (unsigned)g_vk_current_transform,
             (unsigned)info.imageExtent.width, (unsigned)info.imageExtent.height);
        info.preTransform = identity;
    } else {
        LOGI("vkCreateSwapchainKHR: preTransform=0x%x current=0x%x extent=%ux%u",
             (unsigned)info.preTransform, (unsigned)g_vk_current_transform,
             (unsigned)info.imageExtent.width, (unsigned)info.imageExtent.height);
    }
    rc = g_real_vkCreateSwapchainKHR(device, &info, pAllocator, pSwapchain);
    if (rc != 0) {
        LOGW("vkCreateSwapchainKHR rc=%d (IDENTITY may be unsupported; retry real transform)",
             rc);
        if (info.preTransform == identity &&
            g_vk_current_transform != 0 &&
            g_vk_current_transform != identity) {
            info = *pCreateInfo;
            info.preTransform = g_vk_current_transform;
            rc = g_real_vkCreateSwapchainKHR(device, &info, pAllocator, pSwapchain);
            LOGW("vkCreateSwapchainKHR fallback preTransform=0x%x rc=%d",
                 (unsigned)info.preTransform, rc);
        }
    }
    return rc;
}

static void *booxin_vkGetInstanceProcAddr(void *instance, const char *name) {
    void *fn = g_real_vkGetInstanceProcAddr
        ? g_real_vkGetInstanceProcAddr(instance, name)
        : NULL;
    if (!name || !fn) return fn;
    if (strcmp(name, "vkCreateAndroidSurfaceKHR") == 0) {
        g_real_vkCreateAndroidSurfaceKHR = (booxin_vkCreateAndroidSurfaceKHR_fn)fn;
        return (void *)&booxin_vkCreateAndroidSurfaceKHR;
    }
    if (strcmp(name, "vkGetPhysicalDeviceSurfaceCapabilitiesKHR") == 0) {
        g_real_vkGetCaps = (booxin_vkGetPhysicalDeviceSurfaceCapabilitiesKHR_fn)fn;
        return (void *)&booxin_vkGetPhysicalDeviceSurfaceCapabilitiesKHR;
    }
    if (strcmp(name, "vkGetDeviceProcAddr") == 0) {
        g_real_vkGetDeviceProcAddr = (booxin_vkGetDeviceProcAddr_fn)fn;
        return (void *)&booxin_vkGetDeviceProcAddr;
    }
    if (strcmp(name, "vkCreateSwapchainKHR") == 0) {
        g_real_vkCreateSwapchainKHR = (booxin_vkCreateSwapchainKHR_fn)fn;
        return (void *)&booxin_vkCreateSwapchainKHR;
    }
    if (strcmp(name, "vkQueuePresentKHR") == 0) {
        g_real_vkQueuePresentKHR = (booxin_vkQueuePresentKHR_fn)fn;
        return (void *)&booxin_vkQueuePresentKHR;
    }
    return fn;
}

static void *booxin_vkGetDeviceProcAddr(void *device, const char *name) {
    void *fn = g_real_vkGetDeviceProcAddr
        ? g_real_vkGetDeviceProcAddr(device, name)
        : NULL;
    if (!name || !fn) return fn;
    if (strcmp(name, "vkCreateSwapchainKHR") == 0) {
        g_real_vkCreateSwapchainKHR = (booxin_vkCreateSwapchainKHR_fn)fn;
        LOGI("vkGetDeviceProcAddr vkCreateSwapchainKHR → wrap %p", fn);
        return (void *)&booxin_vkCreateSwapchainKHR;
    }
    if (strcmp(name, "vkQueuePresentKHR") == 0) {
        g_real_vkQueuePresentKHR = (booxin_vkQueuePresentKHR_fn)fn;
        return (void *)&booxin_vkQueuePresentKHR;
    }
    return fn;
}

static int booxin_is_vulkan_loader_symbol(void *handle, const char *symbol, void *fn) {
    void *from_loader;
    if (!symbol || !fn || !g_vulkan_loader_handle || !g_real_dlsym) return 0;
    if (handle == g_vulkan_loader_handle) return 1;
    /* LWJGL 可能再次 dlopen libvulkan，handle 对不上时用符号地址比对。 */
    from_loader = g_real_dlsym(g_vulkan_loader_handle, symbol);
    return from_loader && from_loader == fn;
}

static void *booxin_dlsym(void *handle, const char *symbol) {
    void *fn = g_real_dlsym ? g_real_dlsym(handle, symbol) : NULL;
    if (!fn || !symbol) return fn;
    if (!booxin_is_vulkan_loader_symbol(handle, symbol, fn)) return fn;
    /* swapchain / present 多数从 GetDeviceProcAddr 出来，只包 Instance 不够。 */
    if (strcmp(symbol, "vkGetInstanceProcAddr") == 0) {
        g_real_vkGetInstanceProcAddr = (booxin_vkGetInstanceProcAddr_fn)fn;
        LOGI("dlsym vkGetInstanceProcAddr → wrap %p handle=%p", fn, handle);
        return (void *)&booxin_vkGetInstanceProcAddr;
    }
    if (strcmp(symbol, "vkGetDeviceProcAddr") == 0) {
        g_real_vkGetDeviceProcAddr = (booxin_vkGetDeviceProcAddr_fn)fn;
        LOGI("dlsym vkGetDeviceProcAddr → wrap %p handle=%p", fn, handle);
        return (void *)&booxin_vkGetDeviceProcAddr;
    }
    return fn;
}

static void on_dlsym_hooked(
    void *task_stub, int status_code,
    const char *caller_path_name, const char *sym_name,
    void *new_func, void *prev_func, void *hooked_arg) {
    (void)task_stub; (void)status_code; (void)caller_path_name;
    (void)sym_name; (void)new_func; (void)hooked_arg;
    if (prev_func) {
        g_real_dlsym = (booxin_dlsym_fn)prev_func;
        LOGI("dlsym trampoline=%p", prev_func);
    }
}

static void *load_bytehook_lib(void) {
    static void *bh = NULL;
    if (bh) return bh;
    const char *nd = getenv("BOOXIN_NATIVEDIR");
    if (!nd || !nd[0]) nd = getenv("POJAV_NATIVEDIR");
    if (nd && nd[0]) {
        char path[512];
        snprintf(path, sizeof(path), "%s/libbytehook.so", nd);
        bh = dlopen(path, RTLD_NOW);
    }
    if (!bh) bh = dlopen("libbytehook.so", RTLD_NOW);
    return bh;
}

/**
 * GLES paints via eglCreateWindowSurface → we force the launcher Surface.
 * Vulkan must wrap vkCreateAndroidSurfaceKHR the same way; otherwise Adreno
 * presents into a different BufferQueue (audio ok, black SurfaceView).
 * LWJGL uses dlsym(libvulkan, "vkGetInstanceProcAddr") — hook dlsym.
 */
static void booxin_install_vulkan_android_hooks(void *vulkan_handle) {
    if (g_vk_android_hooks) return;
    g_vulkan_loader_handle = vulkan_handle;
    void *bh = load_bytehook_lib();
    if (!bh) {
        LOGW("Vulkan Android hooks: bytehook missing");
        return;
    }
    typedef int (*bytehook_init_fn)(int, int);
    bytehook_init_fn init = (bytehook_init_fn)dlsym(bh, "bytehook_init");
    if (init) init(0, 0);
    bytehook_hook_all_fn hook_all = (bytehook_hook_all_fn)dlsym(bh, "bytehook_hook_all");
    if (!hook_all) {
        LOGW("Vulkan Android hooks: bytehook_hook_all missing");
        return;
    }
    if (!g_real_dlsym) {
        void *libdl = dlopen("libdl.so", RTLD_NOW);
        if (libdl) {
            g_real_dlsym = (booxin_dlsym_fn)dlsym(libdl, "dlsym");
        }
        if (!g_real_dlsym) {
            g_real_dlsym = (booxin_dlsym_fn)dlsym(RTLD_DEFAULT, "dlsym");
        }
    }
    void *stub = hook_all(NULL, "dlsym",
                          (void *)&booxin_dlsym,
                          (void *)&on_dlsym_hooked, NULL);
    g_vk_android_hooks = stub ? 1 : 0;
    LOGI("Vulkan Android hooks dlsym=%p real_dlsym=%p loader=%p installed=%d",
         stub, (void *)g_real_dlsym, vulkan_handle, g_vk_android_hooks);
}

JNIEXPORT void JNICALL
Java_org_lwjgl_glfw_CallbackBridge_setupBridgeWindow(JNIEnv *env, jclass cls, jobject surface) {
    (void)cls;
    if (!booxin_environ) booxin_environ_init();
    if (surface) {
        ANativeWindow *win = ANativeWindow_fromSurface(env, surface);
        if (win) {
            booxin_retain_native_window(win);
            ANativeWindow_release(win);
            /*
             * SDL (26.3+): do NOT create a Pojav/GLFW EGL window surface on the
             * shared ANativeWindow. That steals the only Android window surface;
             * SDL/MobileGlues then cannot present → TextureView frames=0.
             */
            const char *win_mode = getenv("BOOXIN_WINDOWING");
            int sdl_mode = win_mode && strcmp(win_mode, "sdl") == 0;
            if (sdl_mode) {
                LOGI("setupBridgeWindow SDL: retain only %dx%d (skip egl attach)",
                     booxin_environ->savedWidth, booxin_environ->savedHeight);
            } else if (!booxin_egl_attach_window()) {
                LOGW("setupBridgeWindow: egl attach deferred (GL not ready yet)");
            } else {
                LOGI("setupBridgeWindow %dx%d",
                     booxin_environ->savedWidth, booxin_environ->savedHeight);
            }
        } else {
            LOGW("setupBridgeWindow: ANativeWindow_fromSurface returned null");
        }
    } else {
        booxin_egl_detach_window();
        booxin_retain_native_window(NULL);
    }
}

JNIEXPORT void JNICALL
Java_net_kdt_pojavlaunch_utils_JREUtils_releaseBridgeWindow(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    booxin_egl_detach_window();
    booxin_retain_native_window(NULL);
}

JNIEXPORT jboolean JNICALL
Java_org_lwjgl_glfw_CallbackBridge_nativeSetInputReady(JNIEnv *env, jclass cls, jboolean ready) {
    (void)env; (void)cls;
    if (booxin_environ) booxin_environ->isInputReady = ready == JNI_TRUE;
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
JavaCritical_org_lwjgl_glfw_CallbackBridge_nativeSetInputReady(jboolean ready) {
    if (booxin_environ) booxin_environ->isInputReady = ready == JNI_TRUE;
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_org_lwjgl_glfw_CallbackBridge_nativeSetGrabbing(JNIEnv *env, jclass cls, jboolean grab) {
    (void)env;
    (void)cls;
    if (!booxin_environ) return;
    booxin_environ->isGrabbing = grab;

    /* HotSpot calls this; UI reads ART's CallbackBridge.isGrabbing.
     * Must notify ART — never call ART jclass with a HotSpot JNIEnv. */
    JavaVM *dalvik = booxin_environ->dalvikJavaVMPtr;
    if (!dalvik || !booxin_environ->bridgeClazz || !booxin_environ->method_onGrabStateChanged) {
        return;
    }
    JNIEnv *artEnv = NULL;
    int attached = 0;
    jint rc = (*dalvik)->GetEnv(dalvik, (void **)&artEnv, JNI_VERSION_1_6);
    if (rc == JNI_EDETACHED) {
        if ((*dalvik)->AttachCurrentThread(dalvik, &artEnv, NULL) != 0 || !artEnv) {
            LOGW("nativeSetGrabbing: AttachCurrentThread ART failed");
            return;
        }
        attached = 1;
    } else if (rc != JNI_OK || !artEnv) {
        return;
    }
    (*artEnv)->CallStaticVoidMethod(
        artEnv, booxin_environ->bridgeClazz, booxin_environ->method_onGrabStateChanged, grab);
    if ((*artEnv)->ExceptionCheck(artEnv)) {
        (*artEnv)->ExceptionDescribe(artEnv);
        (*artEnv)->ExceptionClear(artEnv);
    }
    if (attached) {
        (*dalvik)->DetachCurrentThread(dalvik);
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

#define SET_CB(field, value) do { if (booxin_environ) booxin_environ->field = (void *)(intptr_t)(value); } while (0)

JNIEXPORT jlong JNICALL
Java_org_lwjgl_glfw_GLFW_nglfwSetCharCallback(JNIEnv *env, jclass cls, jlong window, jlong cb) {
    (void)env; (void)cls; (void)window;
    jlong old = booxin_environ ? (jlong)(intptr_t)booxin_environ->GLFW_invoke_Char : 0;
    SET_CB(GLFW_invoke_Char, cb);
    return old;
}
JNIEXPORT jlong JNICALL
Java_org_lwjgl_glfw_GLFW_nglfwSetCharModsCallback(JNIEnv *env, jclass cls, jlong window, jlong cb) {
    (void)env; (void)cls; (void)window;
    jlong old = booxin_environ ? (jlong)(intptr_t)booxin_environ->GLFW_invoke_CharMods : 0;
    SET_CB(GLFW_invoke_CharMods, cb);
    return old;
}
JNIEXPORT jlong JNICALL
Java_org_lwjgl_glfw_GLFW_nglfwSetCursorEnterCallback(JNIEnv *env, jclass cls, jlong window, jlong cb) {
    (void)env; (void)cls; (void)window;
    jlong old = booxin_environ ? (jlong)(intptr_t)booxin_environ->GLFW_invoke_CursorEnter : 0;
    SET_CB(GLFW_invoke_CursorEnter, cb);
    return old;
}
JNIEXPORT jlong JNICALL
Java_org_lwjgl_glfw_GLFW_nglfwSetCursorPosCallback(JNIEnv *env, jclass cls, jlong window, jlong cb) {
    (void)env; (void)cls; (void)window;
    jlong old = booxin_environ ? (jlong)(intptr_t)booxin_environ->GLFW_invoke_CursorPos : 0;
    SET_CB(GLFW_invoke_CursorPos, cb);
    return old;
}
JNIEXPORT jlong JNICALL
Java_org_lwjgl_glfw_GLFW_nglfwSetFramebufferSizeCallback(JNIEnv *env, jclass cls, jlong window, jlong cb) {
    (void)env; (void)cls; (void)window;
    jlong old = booxin_environ ? (jlong)(intptr_t)booxin_environ->GLFW_invoke_FramebufferSize : 0;
    SET_CB(GLFW_invoke_FramebufferSize, cb);
    return old;
}
JNIEXPORT jlong JNICALL
Java_org_lwjgl_glfw_GLFW_nglfwSetKeyCallback(JNIEnv *env, jclass cls, jlong window, jlong cb) {
    (void)env; (void)cls; (void)window;
    jlong old = booxin_environ ? (jlong)(intptr_t)booxin_environ->GLFW_invoke_Key : 0;
    SET_CB(GLFW_invoke_Key, cb);
    return old;
}
JNIEXPORT jlong JNICALL
Java_org_lwjgl_glfw_GLFW_nglfwSetMouseButtonCallback(JNIEnv *env, jclass cls, jlong window, jlong cb) {
    (void)env; (void)cls; (void)window;
    jlong old = booxin_environ ? (jlong)(intptr_t)booxin_environ->GLFW_invoke_MouseButton : 0;
    SET_CB(GLFW_invoke_MouseButton, cb);
    return old;
}
JNIEXPORT jlong JNICALL
Java_org_lwjgl_glfw_GLFW_nglfwSetScrollCallback(JNIEnv *env, jclass cls, jlong window, jlong cb) {
    (void)env; (void)cls; (void)window;
    jlong old = booxin_environ ? (jlong)(intptr_t)booxin_environ->GLFW_invoke_Scroll : 0;
    SET_CB(GLFW_invoke_Scroll, cb);
    return old;
}
JNIEXPORT jlong JNICALL
Java_org_lwjgl_glfw_GLFW_nglfwSetWindowSizeCallback(JNIEnv *env, jclass cls, jlong window, jlong cb) {
    (void)env; (void)cls; (void)window;
    jlong old = booxin_environ ? (jlong)(intptr_t)booxin_environ->GLFW_invoke_WindowSize : 0;
    SET_CB(GLFW_invoke_WindowSize, cb);
    return old;
}

JNIEXPORT void JNICALL
Java_org_lwjgl_glfw_GLFW_nglfwSetShowingWindow(JNIEnv *env, jclass cls, jlong window) {
    (void)env; (void)cls;
    if (booxin_environ) booxin_environ->showingWindow = (long)window;
}

JNIEXPORT void JNICALL
Java_org_lwjgl_glfw_GLFW_nglfwGetCursorPos(JNIEnv *env, jclass cls, jlong window, jlong xpos, jlong ypos) {
    (void)env; (void)cls; (void)window;
    if (!booxin_environ) return;
    if (xpos) *((double *)(intptr_t)xpos) = booxin_environ->cursorX;
    if (ypos) *((double *)(intptr_t)ypos) = booxin_environ->cursorY;
}

JNIEXPORT void JNICALL
Java_org_lwjgl_glfw_GLFW_nglfwGetCursorPosA(JNIEnv *env, jclass cls, jlong window, jdoubleArray xpos, jdoubleArray ypos) {
    (void)window;
    if (!booxin_environ) return;
    if (xpos) {
        jdouble v = booxin_environ->cursorX;
        (*env)->SetDoubleArrayRegion(env, xpos, 0, 1, &v);
    }
    if (ypos) {
        jdouble v = booxin_environ->cursorY;
        (*env)->SetDoubleArrayRegion(env, ypos, 0, 1, &v);
    }
}

JNIEXPORT void JNICALL
JavaCritical_org_lwjgl_glfw_GLFW_nglfwGetCursorPosA(jlong window, jdouble *xpos, jdouble *ypos) {
    (void)window;
    if (!booxin_environ) return;
    if (xpos) *xpos = booxin_environ->cursorX;
    if (ypos) *ypos = booxin_environ->cursorY;
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
    (void)env;
    (void)cls;
    /* Mesa Zink / RenderPearl need a real Vulkan loader. GLES paths must not
     * set -Dorg.lwjgl.vulkan.libname; when unset, LWJGL never calls this. */
    static void *handle = NULL;
    static int attempted = 0;
    if (!attempted) {
        attempted = 1;
        const char *name = getenv("BOOXIN_VULKAN_LIB");
        if (name && name[0]) {
            handle = dlopen(name, RTLD_NOW | RTLD_LOCAL);
        }
        if (!handle) {
            handle = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
        }
        if (!handle) {
            handle = dlopen("/system/lib64/libvulkan.so", RTLD_NOW | RTLD_LOCAL);
        }
        if (!handle) {
            handle = dlopen("/vendor/lib64/libvulkan.so", RTLD_NOW | RTLD_LOCAL);
        }
        if (!handle) {
            handle = dlopen("/system/lib/libvulkan.so", RTLD_NOW | RTLD_LOCAL);
        }
        if (handle) {
            LOGI("Vulkan loader ready: %s", name && name[0] ? name : "libvulkan.so");
            booxin_install_vulkan_android_hooks(handle);
        } else {
            LOGW("Vulkan loader missing (dlopen libvulkan.so failed)");
        }
    }
    return (jlong)(intptr_t)handle;
}

JNIEXPORT jlong JNICALL
Java_org_lwjgl_vulkan_VK_getFpsAddress(JNIEnv *env, jclass cls) {
    (void)env;
    (void)cls;
    /* 2026-09: FPS_ADDRESS=0 会在 present 时 SIGSEGV，给一块静态计数。 */
    static int fps_counter;
    return (jlong)(intptr_t)&fps_counter;
}

/* Old ABI probe; package name is hardcoded in some load paths. */
JNIEXPORT void JNICALL
Java_com_tungsten_fclauncher_CriticalNativeTest_testCriticalNative(JNIEnv *env, jclass cls, jint a, jint b) {
    (void)env; (void)cls; (void)a; (void)b;
}

static void cache_bridge_methods(JNIEnv *env) {
    if (!booxin_environ) return;
    jclass cls = (*env)->FindClass(env, "org/lwjgl/glfw/CallbackBridge");
    if (!cls || (*env)->ExceptionCheck(env)) {
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        return;
    }
    booxin_environ->bridgeClazz = (*env)->NewGlobalRef(env, cls);
    booxin_environ->method_onGrabStateChanged =
        (*env)->GetStaticMethodID(env, cls, "onGrabStateChanged", "(Z)V");
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        booxin_environ->method_onGrabStateChanged = NULL;
    }
    booxin_environ->method_accessAndroidClipboard =
        (*env)->GetStaticMethodID(env, cls, "accessAndroidClipboard", "(ILjava/lang/String;)Ljava/lang/String;");
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        booxin_environ->method_accessAndroidClipboard = NULL;
    }
    (*env)->DeleteLocalRef(env, cls);
}

void booxin_bind_glfw_input_buffers(JNIEnv *env) {
    if (!env || !booxin_environ) return;
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
            booxin_environ->keyDownBuffer = (jbyte *)addr;
            LOGI("bound keyDownBuffer=%p cap=%lld", addr, (long long)cap);
        }
        (*env)->DeleteLocalRef(env, keyBuf);
    }
    if (mouseBuf) {
        void *addr = (*env)->GetDirectBufferAddress(env, mouseBuf);
        jlong cap = (*env)->GetDirectBufferCapacity(env, mouseBuf);
        if (addr && cap >= 8) {
            booxin_environ->mouseDownBuffer = (jbyte *)addr;
            LOGI("bound mouseDownBuffer=%p cap=%lld", addr, (long long)cap);
        }
        (*env)->DeleteLocalRef(env, mouseBuf);
    }
    (*env)->DeleteLocalRef(env, glfwCls);
}

/* Published by HotSpot pump thread; ART only reads these. */
int booxinGetHitResultType(void);
int booxinGetHeldItemKind(void);

JNIEXPORT jint JNICALL
Java_org_lwjgl_glfw_CallbackBridge_nativeQueryHitResultType(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    return (jint)booxinGetHitResultType();
}

JNIEXPORT jint JNICALL
Java_org_lwjgl_glfw_CallbackBridge_nativeQueryHeldItemKind(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    return (jint)booxinGetHeldItemKind();
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    g_vm = vm;
    booxin_environ_init();
    JNIEnv *env = NULL;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        return JNI_VERSION_1_6;
    }
    /* ART can resolve android.* classes; HotSpot cannot.
     * Never assume "first JNI_OnLoad == ART" — Forge skips ART preload so the
     * first load is often HotSpot (mis-labeling broke grab/input). */
    int is_art = 0;
    {
        jclass act = (*env)->FindClass(env, "android/app/Activity");
        if (act && !(*env)->ExceptionCheck(env)) {
            is_art = 1;
            (*env)->DeleteLocalRef(env, act);
        } else if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
        }
    }

    if (is_art) {
        if (!booxin_environ->dalvikJavaVMPtr) {
            booxin_environ->dalvikJavaVMPtr = vm;
            booxin_environ->dalvikJNIEnvPtr_ANDROID = env;
            LOGI("JNI_OnLoad ART/dalvik");
            cache_bridge_methods(env);
        } else if (vm == booxin_environ->dalvikJavaVMPtr) {
            LOGI("JNI_OnLoad ART (already bound) — skip");
        }
    } else {
        if (!booxin_environ->runtimeJavaVMPtr) {
            booxin_environ->runtimeJavaVMPtr = vm;
            booxin_environ->runtimeJNIEnvPtr_JRE = env;
            LOGI("JNI_OnLoad HotSpot");
        } else if (vm == booxin_environ->runtimeJavaVMPtr) {
            LOGI("JNI_OnLoad HotSpot (already bound) — skip");
        } else {
            LOGW("JNI_OnLoad ignored unknown vm=%p dalvik=%p runtime=%p",
                 (void *)vm,
                 (void *)booxin_environ->dalvikJavaVMPtr,
                 (void *)booxin_environ->runtimeJavaVMPtr);
        }
    }
    /* Adopt process-global Surface if this mapping missed setupBridgeWindow. */
    {
        void **shared = (void **)dlsym(RTLD_DEFAULT, "booxin_shared_native_window");
        if (shared && *shared) {
            booxin_retain_native_window((ANativeWindow *)*shared);
        }
    }
    /* Do not FindClass GLFW here: System.load JNI_OnLoad would run
     * GLFW.<clinit> while the same .so is still loading (LWJGL "Failed to load
     * a library") and leave glfwInit unwired. Bind buffers after Functions patch. */
    critical_set_stackqueue(JNI_TRUE);
    booxin_environ->isInputReady = true;
    return JNI_VERSION_1_6;
}

/* Unused stubs some callers still dlsym. */
void installEMUIIteratorMititgation(JNIEnv *env) { (void)env; }
void installLwjglDlopenHook(JNIEnv *env) { (void)env; }
void hookExec(JNIEnv *env) { (void)env; }
void env_init() { booxin_environ_init(); }
