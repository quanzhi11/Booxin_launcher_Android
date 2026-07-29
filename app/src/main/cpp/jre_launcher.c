/*
 * Booxin JVM launcher.
 *
 * Uses JNI_CreateJavaVM (not JLI_Launch) to avoid re-exec on Android noexec /data
 * and to keep control when HotSpot init fails. Stdout/stderr are piped to logcat.
 */

#include <android/log.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <limits.h>
#include <pthread.h>
#include <signal.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define LOG_TAG "BooxinJvm"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)

typedef jint (*JNI_CreateJavaVM_func)(JavaVM **pvm, void **penv, void *args);
typedef void (*SetupBridgeWindow_fn)(JNIEnv *, jclass, jobject);

/* Stored from ART before embedded JVM starts; re-bound into HotSpot after JNI_CreateJavaVM. */
static jobject g_bridge_surface = NULL;
static pthread_mutex_t g_bridge_mutex = PTHREAD_MUTEX_INITIALIZER;

typedef void (*HookFn)(JNIEnv *);

typedef struct {
    JavaVMOption *opts;
    int nOpts;
    char *mainClass;
    char **gameArgs;
    int nGameArgs;
    char *classpath; /* raw cp string for URLClassLoader */
} ParsedArgs;

typedef struct {
    int argc;
    char **argv;
    jint result;
} LaunchCtx;

static char **to_argv(JNIEnv *env, jobjectArray arr, int *outArgc) {
    const int argc = (*env)->GetArrayLength(env, arr);
    char **argv = calloc((size_t)(argc + 1), sizeof(char *));
    if (!argv) return NULL;
    for (int i = 0; i < argc; i++) {
        jstring s = (jstring)(*env)->GetObjectArrayElement(env, arr, i);
        const char *u = (*env)->GetStringUTFChars(env, s, NULL);
        argv[i] = u ? strdup(u) : strdup("");
        (*env)->ReleaseStringUTFChars(env, s, u);
        (*env)->DeleteLocalRef(env, s);
    }
    argv[argc] = NULL;
    *outArgc = argc;
    return argv;
}

static void free_argv(char **argv, int argc) {
    if (!argv) return;
    for (int i = 0; i < argc; i++) free(argv[i]);
    free(argv);
}

static void free_parsed(ParsedArgs *p) {
    for (int i = 0; i < p->nOpts; i++) free((void *)p->opts[i].optionString);
    free(p->opts);
    for (int i = 0; i < p->nGameArgs; i++) free(p->gameArgs[i]);
    free(p->gameArgs);
    free(p->mainClass);
    free(p->classpath);
}

static bool parse_args(char **argv, int argc, ParsedArgs *out) {
    memset(out, 0, sizeof(*out));
    out->opts = calloc((size_t)argc + 4, sizeof(JavaVMOption));
    out->gameArgs = calloc((size_t)argc, sizeof(char *));
    if (!out->opts || !out->gameArgs) return false;

    bool nextIsClasspath = false;
    bool seenMain = false;
    char *cp = NULL;

    for (int i = 1; i < argc; i++) {
        char *a = argv[i];
        if (seenMain) {
            out->gameArgs[out->nGameArgs++] = strdup(a);
            continue;
        }
        if (nextIsClasspath) {
            cp = strdup(a);
            nextIsClasspath = false;
            continue;
        }
        if (strcmp(a, "-cp") == 0 || strcmp(a, "-classpath") == 0) {
            nextIsClasspath = true;
            continue;
        }
        if (strncmp(a, "-Djava.class.path=", 18) == 0) {
            if (!cp) cp = strdup(a + 18);
            continue; /* will re-add as single option below */
        }
        if (a[0] == '-') {
            out->opts[out->nOpts].optionString = strdup(a);
            out->nOpts++;
            continue;
        }
        out->mainClass = strdup(a);
        seenMain = true;
    }

    if (cp) {
        out->classpath = strdup(cp);
        size_t len = strlen("-Djava.class.path=") + strlen(cp) + 1;
        char *opt = malloc(len);
        if (opt) {
            snprintf(opt, len, "-Djava.class.path=%s", cp);
            memmove(&out->opts[1], &out->opts[0], (size_t)out->nOpts * sizeof(JavaVMOption));
            out->opts[0].optionString = opt;
            out->nOpts++;
        }
        free(cp);
    }
    return out->mainClass != NULL;
}

static void log_exception(JNIEnv *jenv, const char *where) {
    if (!(*jenv)->ExceptionCheck(jenv)) return;
    jthrowable ex = (*jenv)->ExceptionOccurred(jenv);
    (*jenv)->ExceptionClear(jenv);
    jclass exCls = (*jenv)->GetObjectClass(jenv, ex);
    jmethodID toString = (*jenv)->GetMethodID(jenv, exCls, "toString", "()Ljava/lang/String;");
    jmethodID getMsg = (*jenv)->GetMethodID(jenv, exCls, "getMessage", "()Ljava/lang/String;");
    if (toString) {
        jstring js = (jstring)(*jenv)->CallObjectMethod(jenv, ex, toString);
        if (js) {
            const char *msg = (*jenv)->GetStringUTFChars(jenv, js, NULL);
            LOGE("%s: %s", where, msg ? msg : "(null)");
            if (msg) (*jenv)->ReleaseStringUTFChars(jenv, js, msg);
            (*jenv)->DeleteLocalRef(jenv, js);
        }
    }
    if (getMsg) {
        jstring js = (jstring)(*jenv)->CallObjectMethod(jenv, ex, getMsg);
        if (js) {
            const char *msg = (*jenv)->GetStringUTFChars(jenv, js, NULL);
            LOGE("%s message: %s", where, msg ? msg : "(null)");
            if (msg) (*jenv)->ReleaseStringUTFChars(jenv, js, msg);
            (*jenv)->DeleteLocalRef(jenv, js);
        }
    }
    (*jenv)->DeleteLocalRef(jenv, exCls);
    (*jenv)->DeleteLocalRef(jenv, ex);
}

/* Pipe stdout/stderr to logcat so HotSpot/Minecraft messages are visible. */
static int g_log_pipe[2] = {-1, -1};
static pthread_t g_log_thread;
static volatile int g_log_running = 0;

static void *stdout_reader(void *arg) {
    (void)arg;
    char buf[512];
    ssize_t n;
    while (g_log_running && (n = read(g_log_pipe[0], buf, sizeof(buf) - 1)) > 0) {
        buf[n] = '\0';
        /* split lines */
        char *start = buf;
        for (char *p = buf; *p; p++) {
            if (*p == '\n') {
                *p = '\0';
                if (start[0]) LOGI("[jvm] %s", start);
                start = p + 1;
            }
        }
        if (start[0]) LOGI("[jvm] %s", start);
    }
    return NULL;
}

static void start_stdio_capture(void) {
    if (pipe(g_log_pipe) != 0) {
        LOGE("pipe failed: %s", strerror(errno));
        return;
    }
    /* make write end cloexec optional; redirect stdout/stderr */
    fflush(stdout);
    fflush(stderr);
    dup2(g_log_pipe[1], STDOUT_FILENO);
    dup2(g_log_pipe[1], STDERR_FILENO);
    close(g_log_pipe[1]);
    g_log_pipe[1] = -1;
    g_log_running = 1;
    pthread_create(&g_log_thread, NULL, stdout_reader, NULL);
}

static void stop_stdio_capture(void) {
    g_log_running = 0;
    if (g_log_pipe[0] >= 0) {
        close(g_log_pipe[0]);
        g_log_pipe[0] = -1;
    }
    pthread_join(g_log_thread, NULL);
}

static void reset_signals(void) {
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    for (int s = SIGHUP; s < NSIG; s++) {
        if (s == SIGKILL || s == SIGSTOP) continue;
        /* Boardwalk/FCL: ignore SIGSEGV so stray native faults in GLES/hooks
         * do not kill the whole :game process. Do NOT pump GLFW callbacks
         * from the ART UI thread — that crashes even with this in place. */
        sa.sa_handler = (s == SIGSEGV) ? SIG_IGN : SIG_DFL;
        sigaction(s, &sa, NULL);
    }
}

static jobject build_url_classloader(JNIEnv *jenv, const char *classpath) {
    if (!classpath || !classpath[0]) {
        LOGE("classpath empty");
        return NULL;
    }

    jclass fileCls = (*jenv)->FindClass(jenv, "java/io/File");
    jmethodID fileCtor = (*jenv)->GetMethodID(jenv, fileCls, "<init>", "(Ljava/lang/String;)V");
    jmethodID toURI = (*jenv)->GetMethodID(jenv, fileCls, "toURI", "()Ljava/net/URI;");
    jclass uriCls = (*jenv)->FindClass(jenv, "java/net/URI");
    jmethodID toURL = (*jenv)->GetMethodID(jenv, uriCls, "toURL", "()Ljava/net/URL;");
    jclass urlCls = (*jenv)->FindClass(jenv, "java/net/URL");
    log_exception(jenv, "URL helpers");

    /* count entries */
    int n = 1;
    for (const char *p = classpath; *p; p++) if (*p == ':') n++;

    jobjectArray urls = (*jenv)->NewObjectArray(jenv, n, urlCls, NULL);
    char *copy = strdup(classpath);
    char *save = NULL;
    char *tok = strtok_r(copy, ":", &save);
    int idx = 0;
    int ok = 0;
    while (tok && idx < n) {
        jstring path = (*jenv)->NewStringUTF(jenv, tok);
        jobject file = (*jenv)->NewObject(jenv, fileCls, fileCtor, path);
        jobject uri = (*jenv)->CallObjectMethod(jenv, file, toURI);
        jobject url = uri ? (*jenv)->CallObjectMethod(jenv, uri, toURL) : NULL;
        if (url && !(*jenv)->ExceptionCheck(jenv)) {
            (*jenv)->SetObjectArrayElement(jenv, urls, idx, url);
            ok++;
        } else {
            log_exception(jenv, "bad classpath entry");
            LOGW("skip cp entry: %s", tok);
        }
        if (path) (*jenv)->DeleteLocalRef(jenv, path);
        if (file) (*jenv)->DeleteLocalRef(jenv, file);
        if (uri) (*jenv)->DeleteLocalRef(jenv, uri);
        if (url) (*jenv)->DeleteLocalRef(jenv, url);
        idx++;
        tok = strtok_r(NULL, ":", &save);
    }
    free(copy);
    LOGI("URLClassLoader entries ok=%d / %d", ok, n);

    jclass clCls = (*jenv)->FindClass(jenv, "java/lang/ClassLoader");
    jmethodID getSys = (*jenv)->GetStaticMethodID(
        jenv, clCls, "getSystemClassLoader", "()Ljava/lang/ClassLoader;");
    jobject parent = (*jenv)->CallStaticObjectMethod(jenv, clCls, getSys);
    log_exception(jenv, "getSystemClassLoader");

    jclass urlClCls = (*jenv)->FindClass(jenv, "java/net/URLClassLoader");
    jmethodID urlCtor = (*jenv)->GetMethodID(
        jenv, urlClCls, "<init>", "([Ljava/net/URL;Ljava/lang/ClassLoader;)V");
    jobject loader = (*jenv)->NewObject(jenv, urlClCls, urlCtor, urls, parent);
    if ((*jenv)->ExceptionCheck(jenv)) {
        log_exception(jenv, "URLClassLoader.<init>");
        return NULL;
    }
    return loader;
}

static SetupBridgeWindow_fn resolve_setup_bridge_window(void *pojav_lib) {
    if (!pojav_lib) return NULL;
    return (SetupBridgeWindow_fn)dlsym(
        pojav_lib, "Java_org_lwjgl_glfw_CallbackBridge_setupBridgeWindow");
}

static void *open_pojavexec(void);

static void register_callbackbridge_send_natives(JNIEnv *env) {
    if (!env) return;
    void *lib = open_pojavexec();
    if (!lib) return;

    jclass cbCls = (*env)->FindClass(env, "org/lwjgl/glfw/CallbackBridge");
    if (!cbCls || (*env)->ExceptionCheck(env)) {
        if ((*env)->ExceptionCheck(env)) log_exception(env, "FindClass CallbackBridge register");
        LOGW("RegisterNatives skipped: CallbackBridge class missing");
        return;
    }

    void *set_stack = dlsym(lib, "critical_set_stackqueue");
    if (!set_stack) set_stack = dlsym(lib, "noncritical_set_stackqueue");
    void *send_char = dlsym(lib, "critical_send_char");
    if (!send_char) send_char = dlsym(lib, "noncritical_send_char");
    void *send_char_mods = dlsym(lib, "critical_send_char_mods");
    if (!send_char_mods) send_char_mods = dlsym(lib, "noncritical_send_char_mods");
    void *send_key = dlsym(lib, "critical_send_key");
    if (!send_key) send_key = dlsym(lib, "noncritical_send_key");
    void *send_cursor = dlsym(lib, "critical_send_cursor_pos");
    if (!send_cursor) send_cursor = dlsym(lib, "noncritical_send_cursor_pos");
    void *send_mouse = dlsym(lib, "critical_send_mouse_button");
    if (!send_mouse) send_mouse = dlsym(lib, "noncritical_send_mouse_button");
    void *send_scroll = dlsym(lib, "critical_send_scroll");
    if (!send_scroll) send_scroll = dlsym(lib, "noncritical_send_scroll");
    void *send_size = dlsym(lib, "critical_send_screen_size");
    if (!send_size) send_size = dlsym(lib, "noncritical_send_screen_size");

    JNINativeMethod methods[] = {
        { "nativeSetUseInputStackQueue", "(Z)V", set_stack },
        { "nativeSendChar", "(C)Z", send_char },
        { "nativeSendCharMods", "(CI)Z", send_char_mods },
        { "nativeSendKey", "(IIII)V", send_key },
        { "nativeSendCursorPos", "(FF)V", send_cursor },
        { "nativeSendMouseButton", "(III)V", send_mouse },
        { "nativeSendScroll", "(DD)V", send_scroll },
        { "nativeSendScreenSize", "(II)V", send_size },
    };

    int count = 0;
    for (size_t i = 0; i < sizeof(methods) / sizeof(methods[0]); i++) {
        if (methods[i].fnPtr) count++;
    }
    if (count == 0) {
        LOGW("RegisterNatives skipped: no send_* symbols resolved");
        (*env)->DeleteLocalRef(env, cbCls);
        return;
    }

    JNINativeMethod resolved[8];
    int idx = 0;
    for (size_t i = 0; i < sizeof(methods) / sizeof(methods[0]); i++) {
        if (methods[i].fnPtr) resolved[idx++] = methods[i];
    }

    jint rc = (*env)->RegisterNatives(env, cbCls, resolved, idx);
    if ((*env)->ExceptionCheck(env)) {
        log_exception(env, "RegisterNatives CallbackBridge send_*");
    }
    LOGI("RegisterNatives CallbackBridge send_* rc=%d count=%d cursor=%p mouse=%p key=%p",
         (int)rc, idx, send_cursor, send_mouse, send_key);
    (*env)->DeleteLocalRef(env, cbCls);
}

typedef jint (*JNI_OnLoad_func)(JavaVM *, void *);

static void *open_pojavexec(void) {
    /* Prefer the already-loaded handle (staged System.load path). Avoid a second
     * copy from APK nativeLibraryDir — separate pojav_environ / br_init = crash. */
    void *lib = dlopen("libpojavexec.so", RTLD_LAZY | RTLD_NOLOAD);
    if (lib) return lib;
    const char *nativeDir = getenv("POJAV_NATIVEDIR");
    if (!nativeDir || !nativeDir[0]) nativeDir = getenv("FCL_NATIVEDIR");
    if (nativeDir && nativeDir[0]) {
        char path[PATH_MAX];
        snprintf(path, sizeof(path), "%s/libpojavexec.so", nativeDir);
        lib = dlopen(path, RTLD_LAZY | RTLD_GLOBAL);
        if (lib) return lib;
    }
    return dlopen("libpojavexec.so", RTLD_LAZY | RTLD_GLOBAL);
}

/* pojavexec JNI_OnLoad must run twice: once on ART (dalvikJavaVMPtr), once on
 * HotSpot (runtimeJavaVMPtr). We dlopen pojavexec without going through
 * System.loadLibrary, so both calls must be explicit. */
static bool call_pojav_jni_onload(JavaVM *vm, JNIEnv *env, const char *label) {
    if (!vm) return false;
    void *lib = open_pojavexec();
    if (!lib) {
        LOGE("%s: dlopen pojavexec: %s", label, dlerror());
        return false;
    }
    JNI_OnLoad_func onLoad = (JNI_OnLoad_func)dlsym(lib, "JNI_OnLoad");
    if (!onLoad) {
        LOGE("%s: dlsym JNI_OnLoad: %s", label, dlerror());
        return false;
    }
    jint ver = onLoad(vm, NULL);
    if (env && (*env)->ExceptionCheck(env)) {
        log_exception(env, label);
        return false;
    }
    LOGI("%s: pojavexec JNI_OnLoad ok (version 0x%x)", label, (int)ver);
    return true;
}

/** Absolute path to staged libpojavexec.so (same file ART already mapped). */
static bool pojavexec_staged_path(char *out, size_t outLen) {
    const char *nativeDir = getenv("POJAV_NATIVEDIR");
    if (!nativeDir || !nativeDir[0]) nativeDir = getenv("FCL_NATIVEDIR");
    if (!nativeDir || !nativeDir[0]) return false;
    snprintf(out, outLen, "%s/libpojavexec.so", nativeDir);
    return access(out, R_OK) == 0;
}

/**
 * HotSpot System.load(absolutePath) via bridge-patch helper so @CallerSensitive
 * sees AppClassLoader (same as GLFW.System.loadLibrary). Direct JNI System.load
 * uses another classloader → "already loaded in another classloader".
 */
static bool hotspot_system_load_pojavexec(JNIEnv *env) {
    char path[PATH_MAX];
    if (!pojavexec_staged_path(path, sizeof(path))) {
        LOGE("hotspot System.load: staged libpojavexec.so missing");
        return false;
    }
    jclass loaderCls = (*env)->FindClass(env, "org/lwjgl/glfw/BooxinPojavLoader");
    if (!loaderCls || (*env)->ExceptionCheck(env)) {
        log_exception(env, "FindClass BooxinPojavLoader");
        /* Fallback: raw System.load (may hit classloader mismatch). */
        jclass systemCls = (*env)->FindClass(env, "java/lang/System");
        if (!systemCls || (*env)->ExceptionCheck(env)) {
            log_exception(env, "FindClass System");
            return false;
        }
        jmethodID loadMid = (*env)->GetStaticMethodID(env, systemCls, "load", "(Ljava/lang/String;)V");
        if (!loadMid || (*env)->ExceptionCheck(env)) {
            log_exception(env, "System.load mid");
            return false;
        }
        jstring jpath = (*env)->NewStringUTF(env, path);
        (*env)->CallStaticVoidMethod(env, systemCls, loadMid, jpath);
        (*env)->DeleteLocalRef(env, jpath);
        if ((*env)->ExceptionCheck(env)) {
            log_exception(env, "System.load(pojavexec)");
            return false;
        }
        LOGI("HotSpot System.load(%s) ok (fallback)", path);
        return true;
    }
    jmethodID loadMid =
        (*env)->GetStaticMethodID(env, loaderCls, "loadAbsolute", "(Ljava/lang/String;)V");
    if (!loadMid || (*env)->ExceptionCheck(env)) {
        log_exception(env, "BooxinPojavLoader.loadAbsolute mid");
        return false;
    }
    jstring jpath = (*env)->NewStringUTF(env, path);
    (*env)->CallStaticVoidMethod(env, loaderCls, loadMid, jpath);
    (*env)->DeleteLocalRef(env, jpath);
    if ((*env)->ExceptionCheck(env)) {
        log_exception(env, "BooxinPojavLoader.loadAbsolute");
        return false;
    }
    LOGI("HotSpot BooxinPojavLoader.loadAbsolute(%s) ok", path);
    return true;
}

static void log_hotspot_pump_diag(JNIEnv *env) {
    if (!env) return;
    jclass loaderCls = (*env)->FindClass(env, "org/lwjgl/glfw/BooxinPojavLoader");
    if (!loaderCls || (*env)->ExceptionCheck(env)) {
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        return;
    }
    jmethodID diagMid =
        (*env)->GetStaticMethodID(env, loaderCls, "pumpDiag", "()Ljava/lang/String;");
    if (!diagMid || (*env)->ExceptionCheck(env)) {
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        return;
    }
    jstring jdiag = (jstring)(*env)->CallStaticObjectMethod(env, loaderCls, diagMid);
    if ((*env)->ExceptionCheck(env) || !jdiag) {
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        return;
    }
    const char *utf = (*env)->GetStringUTFChars(env, jdiag, NULL);
    void *lib = open_pojavexec();
    void *sym = lib ? dlsym(lib, "pojavPumpEvents") : NULL;
    LOGI("HotSpot %s | dlsym pojavPumpEvents=%p", utf ? utf : "?", sym);
    if (utf) (*env)->ReleaseStringUTFChars(env, jdiag, utf);
    (*env)->DeleteLocalRef(env, jdiag);
}

/*
 * Redirect GLFW.Functions.{StartPumping,PumpEvents,StopPumping} to the
 * already-mapped libpojavexec (RTLD_NOLOAD). LWJGL SharedLibrary can resolve
 * symbols from a second copy of the .so; ART CriticalNative then fills queue A
 * while HotSpot pumps empty queue B — mouseBtn logs, game never clicks.
 * Replacing the function pointers forces both sides onto the same environ.
 */

/*
 * Dump / pump helpers need FCL environ.h layout (must stay in sync with
 * libpojavexec). Defined early so pump wrappers can refresh JNIEnv.
 */
typedef struct {
    int type;
    int i1;
    int i2;
    int i3;
    int i4;
} BooxinGlfwInputEvent;

#define BOOXIN_EVENT_WINDOW_SIZE 8000

struct booxin_pojav_environ_s {
    void *pojavWindow;
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
};

/*
 * Mouse/cursor GLFW callbacks are plain C function pointers
 * (window, button, action, mods) — they do NOT use runtimeJNIEnvPtr_JRE.
 * LWJGL trampolines then CallVoidMethod on the current thread's HotSpot JNIEnv.
 * We still refresh jreEnv for framebuffer/window-size JNI paths inside pump.
 */
static void booxin_bind_hotspot_jnienv(void) {
    void *lib = open_pojavexec();
    if (!lib) return;
    struct booxin_pojav_environ_s **pp =
        (struct booxin_pojav_environ_s **)dlsym(lib, "pojav_environ");
    if (!pp || !*pp || !(*pp)->runtimeJavaVMPtr) return;
    JavaVM *hs = (*pp)->runtimeJavaVMPtr;
    JNIEnv *hsEnv = NULL;
    if ((*hs)->GetEnv(hs, (void **)&hsEnv, JNI_VERSION_1_4) == JNI_OK && hsEnv) {
        (*pp)->runtimeJNIEnvPtr_JRE = hsEnv;
    }
}

static JNIEnv *booxin_get_hotspot_env(struct booxin_pojav_environ_s *e, int *attached) {
    *attached = 0;
    if (!e || !e->runtimeJavaVMPtr) return NULL;
    JavaVM *hs = e->runtimeJavaVMPtr;
    JNIEnv *env = NULL;
    jint st = (*hs)->GetEnv(hs, (void **)&env, JNI_VERSION_1_4);
    if (st == JNI_OK) return env;
    if (st == JNI_EDETACHED) {
        if ((*hs)->AttachCurrentThread(hs, &env, NULL) != 0) return NULL;
        *attached = 1;
        return env;
    }
    return NULL;
}

static jclass g_hooks_cls = NULL;
static jmethodID g_hooks_deliver = NULL;

typedef void (*booxin_mouse_btn_fn)(void *window, int button, int action, int mods);
typedef void (*booxin_cursor_pos_fn)(void *window, double x, double y);
typedef void (*booxin_cursor_enter_fn)(void *window, int entered);

static double g_fwd_last_x = -1.0;
static double g_fwd_last_y = -1.0;
static int g_fwd_last_btn[3] = {-1, -1, -1};
static bool g_fwd_cursor_entered = false;
static atomic_int g_fwd_native_log = 0;

static jclass load_class_via_loader(JNIEnv *env, jobject loader, const char *binary_name) {
    if (!env || !loader || !binary_name) return NULL;
    jclass clCls = (*env)->FindClass(env, "java/lang/ClassLoader");
    if (!clCls || (*env)->ExceptionCheck(env)) {
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        return NULL;
    }
    jmethodID loadClass = (*env)->GetMethodID(
        env, clCls, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
    if (!loadClass || (*env)->ExceptionCheck(env)) {
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        (*env)->DeleteLocalRef(env, clCls);
        return NULL;
    }
    jstring jname = (*env)->NewStringUTF(env, binary_name);
    jclass cls = (jclass)(*env)->CallObjectMethod(env, loader, loadClass, jname);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        cls = NULL;
    }
    (*env)->DeleteLocalRef(env, jname);
    (*env)->DeleteLocalRef(env, clCls);
    return cls;
}

/** Prefer GLFW's ClassLoader (same CP as bridge-patch) over bare SystemClassLoader. */
static jclass load_booxin_input_hooks(JNIEnv *env) {
    jclass hooks = NULL;

    jclass glfw = (*env)->FindClass(env, "org/lwjgl/glfw/GLFW");
    if (glfw && !(*env)->ExceptionCheck(env)) {
        jclass classCls = (*env)->FindClass(env, "java/lang/Class");
        jmethodID getCl = classCls
            ? (*env)->GetMethodID(env, classCls, "getClassLoader", "()Ljava/lang/ClassLoader;")
            : NULL;
        jobject loader = (getCl) ? (*env)->CallObjectMethod(env, glfw, getCl) : NULL;
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
            loader = NULL;
        }
        if (loader) {
            hooks = load_class_via_loader(env, loader, "org.lwjgl.glfw.BooxinInputHooks");
            (*env)->DeleteLocalRef(env, loader);
        }
        if (classCls) (*env)->DeleteLocalRef(env, classCls);
        (*env)->DeleteLocalRef(env, glfw);
    } else if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
    }
    if (hooks) return hooks;

    jclass clCls = (*env)->FindClass(env, "java/lang/ClassLoader");
    if (clCls && !(*env)->ExceptionCheck(env)) {
        jmethodID getSys = (*env)->GetStaticMethodID(
            env, clCls, "getSystemClassLoader", "()Ljava/lang/ClassLoader;");
        jobject sysLoader = getSys
            ? (*env)->CallStaticObjectMethod(env, clCls, getSys) : NULL;
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
            sysLoader = NULL;
        }
        if (sysLoader) {
            hooks = load_class_via_loader(env, sysLoader, "org.lwjgl.glfw.BooxinInputHooks");
            (*env)->DeleteLocalRef(env, sysLoader);
        }
        (*env)->DeleteLocalRef(env, clCls);
    } else if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
    }
    if (hooks) return hooks;

    hooks = (*env)->FindClass(env, "org/lwjgl/glfw/BooxinInputHooks");
    if (!hooks || (*env)->ExceptionCheck(env)) {
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        return NULL;
    }
    return hooks;
}

static bool cache_input_hooks_deliver(JNIEnv *env) {
    if (g_hooks_cls && g_hooks_deliver) return true;
    jclass local = load_booxin_input_hooks(env);
    if (!local) {
        static int warned;
        if (!warned++) LOGW("BooxinInputHooks class not found on HotSpot classpath");
        return false;
    }
    jmethodID mid = (*env)->GetStaticMethodID(env, local, "deliverInput", "(JDDIII)V");
    if (!mid || (*env)->ExceptionCheck(env)) {
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        static int warned;
        if (!warned++) LOGW("BooxinInputHooks.deliverInput not found — rebuild lwjgl-bridge-patch.jar");
        (*env)->DeleteLocalRef(env, local);
        return false;
    }
    if (!g_hooks_cls) {
        g_hooks_cls = (jclass)(*env)->NewGlobalRef(env, local);
    }
    g_hooks_deliver = mid;
    (*env)->DeleteLocalRef(env, local);
    LOGI("cached BooxinInputHooks.deliverInput cls=%p", (void *)g_hooks_cls);
    return true;
}

static struct booxin_pojav_environ_s *booxin_get_environ(void) {
    void *lib = open_pojavexec();
    if (!lib) return NULL;
    struct booxin_pojav_environ_s **pp =
        (struct booxin_pojav_environ_s **)dlsym(lib, "pojav_environ");
    return (pp && *pp) ? *pp : NULL;
}

static jlong booxin_glfw_window_jlong(struct booxin_pojav_environ_s *e, void *window) {
    if (e && e->showingWindow) return (jlong)e->showingWindow;
    if (window) return (jlong)(intptr_t)window;
    if (e && e->mainWindowBundle) return (jlong)(intptr_t)e->mainWindowBundle;
    if (e && e->pojavWindow) return (jlong)(intptr_t)e->pojavWindow;
    return 0;
}

static void booxin_read_pojav_cursor(struct booxin_pojav_environ_s *e, double *cx, double *cy) {
    const uint8_t *tail = (const uint8_t *)e + 0x27000;
    memcpy(cx, tail + 0x140, sizeof(double));
    memcpy(cy, tail + 0x148, sizeof(double));
}

static void *booxin_resolve_window(struct booxin_pojav_environ_s *e, void *window) {
    if (e && e->showingWindow) return (void *)(long)e->showingWindow;
    if (window) return window;
    if (e && e->mainWindowBundle) return e->mainWindowBundle;
    if (e && e->pojavWindow) return e->pojavWindow;
    return NULL;
}

static void *booxin_tail_ptr(struct booxin_pojav_environ_s *e, size_t off) {
    void *p = NULL;
    memcpy(&p, (const uint8_t *)e + 0x27000 + off, sizeof(p));
    return p;
}

static void booxin_read_mouse_buttons(struct booxin_pojav_environ_s *e,
                                      int *b0, int *b1, int *b2) {
    *b0 = *b1 = *b2 = 0;
    /* libpojavexec: mouseDownBuffer pointer lives at environ+0x27000+0x1a0 */
    jbyte *buf = (jbyte *)booxin_tail_ptr(e, 0x1a0);
    if (!buf) buf = e->mouseDownBuffer;
    if (!buf) return;
    *b0 = buf[0];
    *b1 = buf[1];
    *b2 = buf[2];
}

/** MC GLFW window — showingWindow, NOT pojavWindow (internal stub). */
static void *booxin_mc_glfw_window(struct booxin_pojav_environ_s *e, void *window) {
    long showing = 0;
    memcpy(&showing, (const uint8_t *)e + 0x27000 + 0x1c8, sizeof(showing));
    if (showing) return (void *)showing;
    if (e && e->showingWindow) return (void *)(long)e->showingWindow;
    if (e && e->mainWindowBundle) return e->mainWindowBundle;
    if (window) return window;
    if (e && e->pojavWindow) return e->pojavWindow;
    return NULL;
}

/**
 * Invoke MC-registered GLFW trampolines on the HotSpot render thread.
 * Callbacks must be read from binary offsets (C struct layout drifts).
 * Button snapshot must be taken BEFORE pojavPumpEvents.
 */
static void booxin_deliver_glfw_native(struct booxin_pojav_environ_s *e, void *winPtr,
                                       double cx, double cy, int b0, int b1, int b2) {
    if (!e || !winPtr) return;
    /* isInputReady at +0x1d0 */
    if (!((const uint8_t *)e + 0x27000)[0x1d0]) return;
    booxin_bind_hotspot_jnienv();

    void *enterCb = booxin_tail_ptr(e, 0x1f0);
    void *posCb = booxin_tail_ptr(e, 0x1f8);
    void *mouseCb = booxin_tail_ptr(e, 0x210);

    if (!g_fwd_cursor_entered && enterCb) {
        ((booxin_cursor_enter_fn)enterCb)(winPtr, 1);
        g_fwd_cursor_entered = true;
    }

    if (cx != g_fwd_last_x || cy != g_fwd_last_y) {
        if (posCb) {
            ((booxin_cursor_pos_fn)posCb)(winPtr, cx, cy);
            int n = atomic_fetch_add(&g_fwd_native_log, 1) + 1;
            if (n <= 12 || (n % 600) == 0) {
                LOGI("fwdNative pos=%.0f,%.0f win=%p", cx, cy, winPtr);
            }
        }
        g_fwd_last_x = cx;
        g_fwd_last_y = cy;
    }

    int states[3] = {b0, b1, b2};
    for (int btn = 0; btn < 3; btn++) {
        if (g_fwd_last_btn[btn] == states[btn]) continue;
        if (mouseCb) {
            ((booxin_mouse_btn_fn)mouseCb)(winPtr, btn, states[btn], 0);
            LOGI("fwdNative btn=%d act=%d at %.0f,%.0f win=%p", btn, states[btn], cx, cy, winPtr);
        }
        g_fwd_last_btn[btn] = states[btn];
    }
}

/** Always try Java MC-ClassLoader delivery as well (native trampoline may be stale). */
static void booxin_forward_input_callbacks(void *window, double cx, double cy,
                                           int b0, int b1, int b2) {
    struct booxin_pojav_environ_s *e = booxin_get_environ();
    if (!e) return;
    if (!((const uint8_t *)e + 0x27000)[0x1d0]) return;

    int attached = 0;
    JNIEnv *env = booxin_get_hotspot_env(e, &attached);
    if (!env) {
        static int noEnv;
        if (!noEnv++) LOGW("deliverInput: no HotSpot JNIEnv on pump thread");
        return;
    }
    e->runtimeJNIEnvPtr_JRE = env;
    if (!cache_input_hooks_deliver(env)) {
        if (attached) (*e->runtimeJavaVMPtr)->DetachCurrentThread(e->runtimeJavaVMPtr);
        return;
    }

    jlong win = (jlong)(intptr_t)window;
    if (!win) win = (jlong)(intptr_t)booxin_mc_glfw_window(e, NULL);
    (*env)->CallStaticVoidMethod(env, g_hooks_cls, g_hooks_deliver, win, cx, cy, b0, b1, b2);
    if ((*env)->ExceptionCheck(env)) {
        log_exception(env, "BooxinInputHooks.deliverInput");
    }

    if (attached) (*e->runtimeJavaVMPtr)->DetachCurrentThread(e->runtimeJavaVMPtr);
}

/*
 * Do NOT replace GLFW_invoke_* with logging wrappers: early hooks lock stale
 * pre-MC stubs. Stock pojavPumpEvents must call trampolines installed by
 * glfwSet*Callback. Extra fwdNative/Java deliver was removed (FCL does not).
 */
static void booxin_hook_input_callbacks(void) {
    /* intentionally empty — see comment above */
}

static void (*g_pojav_start_pumping)(void) = NULL;
static void (*g_pojav_pump_events)(void *) = NULL;
static void (*g_pojav_stop_pumping)(void) = NULL;
static struct booxin_pojav_environ_s **g_pojav_environ_pp = NULL;

static void resolve_pojav_pump_syms(void) {
    if (g_pojav_pump_events && g_pojav_environ_pp) return;
    void *lib = open_pojavexec();
    if (!lib) return;
    if (!g_pojav_start_pumping)
        g_pojav_start_pumping = (void (*)(void))dlsym(lib, "pojavStartPumping");
    if (!g_pojav_pump_events)
        g_pojav_pump_events = (void (*)(void *))dlsym(lib, "pojavPumpEvents");
    if (!g_pojav_stop_pumping)
        g_pojav_stop_pumping = (void (*)(void))dlsym(lib, "pojavStopPumping");
    if (!g_pojav_environ_pp)
        g_pojav_environ_pp =
            (struct booxin_pojav_environ_s **)dlsym(lib, "pojav_environ");
}

/*
 * Thin wrappers: cache dlsym once. Previous code dlopen/dlsym every frame and
 * stalled the HotSpot render thread (~1s input lag, no smooth look).
 */
static void booxin_start_pumping(void) {
    resolve_pojav_pump_syms();
    if (g_pojav_start_pumping) g_pojav_start_pumping();
}

static void booxin_stop_pumping(void) {
    resolve_pojav_pump_syms();
    if (g_pojav_stop_pumping) g_pojav_stop_pumping();
}

/*
 * FCL path: ART CriticalNative → stack queue → stock pojavPumpEvents only.
 * Do NOT force shouldUpdateMouse every frame — that fires CursorPos on every
 * glfwPollEvents and cooks the CPU (~80% on title screen). Dirty bit is set
 * from ART via nativeMarkMousePositionDirty when the finger actually moves.
 */
static void booxin_pump_events(void *window) {
    if (!g_pojav_pump_events) resolve_pojav_pump_syms();
    if (g_pojav_pump_events) g_pojav_pump_events(window);
}

static bool patch_hotspot_pump_function_pointers(JNIEnv *env) {
    if (!env) return false;
    jclass fnCls = (*env)->FindClass(env, "org/lwjgl/glfw/GLFW$Functions");
    if (!fnCls || (*env)->ExceptionCheck(env)) {
        log_exception(env, "FindClass GLFW$Functions");
        return false;
    }
    jfieldID startFid = (*env)->GetStaticFieldID(env, fnCls, "StartPumping", "J");
    jfieldID pumpFid = (*env)->GetStaticFieldID(env, fnCls, "PumpEvents", "J");
    jfieldID stopFid = (*env)->GetStaticFieldID(env, fnCls, "StopPumping", "J");
    if (!startFid || !pumpFid || !stopFid || (*env)->ExceptionCheck(env)) {
        log_exception(env, "GLFW$Functions pump fields");
        return false;
    }
    jlong oldPump = (*env)->GetStaticLongField(env, fnCls, pumpFid);
    jlong newStart = (jlong)(uintptr_t)booxin_start_pumping;
    jlong newPump = (jlong)(uintptr_t)booxin_pump_events;
    jlong newStop = (jlong)(uintptr_t)booxin_stop_pumping;
    (*env)->SetStaticLongField(env, fnCls, startFid, newStart);
    (*env)->SetStaticLongField(env, fnCls, pumpFid, newPump);
    (*env)->SetStaticLongField(env, fnCls, stopFid, newStop);
    jlong checkPump = (*env)->GetStaticLongField(env, fnCls, pumpFid);
    LOGI("patched GLFW pump fns oldPump=%p newPump=%p checkPump=%p start=%p stop=%p",
         (void *)(uintptr_t)oldPump, (void *)(uintptr_t)newPump,
         (void *)(uintptr_t)checkPump,
         (void *)(uintptr_t)newStart, (void *)(uintptr_t)newStop);
    if (checkPump != newPump) {
        LOGW("JNI could not overwrite final PumpEvents — trying Unsafe via Java");
        jclass loaderCls = (*env)->FindClass(env, "org/lwjgl/glfw/BooxinPojavLoader");
        if (loaderCls && !(*env)->ExceptionCheck(env)) {
            jmethodID mid = (*env)->GetStaticMethodID(
                env, loaderCls, "forcePumpFunctionPointers", "(JJJ)Z");
            if (mid && !(*env)->ExceptionCheck(env)) {
                jboolean ok = (*env)->CallStaticBooleanMethod(
                    env, loaderCls, mid, newStart, newPump, newStop);
                LOGI("Unsafe pump patch ok=%d", (int)ok);
            } else if ((*env)->ExceptionCheck(env)) {
                log_exception(env, "forcePumpFunctionPointers mid");
            }
        } else if ((*env)->ExceptionCheck(env)) {
            log_exception(env, "FindClass BooxinPojavLoader for Unsafe patch");
        }
    }
    (*env)->DeleteLocalRef(env, fnCls);
    cache_input_hooks_deliver(env);
    return true;
}

/**
 * Force stack-queue + input-ready so ART touch is not silently dropped.
 *
 * FCL libpojavexec exports RegisterNatives helpers as critical_set_stackqueue /
 * critical_send_* (NOT JavaCritical_…nativeSetUseInputStackQueue). nativeSetInputReady
 * is the exception: it also has a JavaCritical_ export for direct CriticalNative link.
 */
static void force_input_bridge_ready(const char *where) {
    void *lib = open_pojavexec();
    if (!lib) return;
    typedef void (*set_stack_fn)(jboolean);
    typedef jboolean (*set_ready_fn)(jboolean);
    /* Prefer FCL critical_* symbols; fall back if a different pojav build is used. */
    set_stack_fn setStack = (set_stack_fn)dlsym(lib, "critical_set_stackqueue");
    const char *stackSym = "critical_set_stackqueue";
    if (!setStack) {
        setStack = (set_stack_fn)dlsym(lib, "noncritical_set_stackqueue");
        stackSym = "noncritical_set_stackqueue";
    }
    if (!setStack) {
        setStack = (set_stack_fn)dlsym(
            lib, "JavaCritical_org_lwjgl_glfw_CallbackBridge_nativeSetUseInputStackQueue");
        stackSym = "JavaCritical_…nativeSetUseInputStackQueue";
    }
    set_ready_fn setReady =
        (set_ready_fn)dlsym(lib, "JavaCritical_org_lwjgl_glfw_CallbackBridge_nativeSetInputReady");
    const char *readySym = "JavaCritical_…nativeSetInputReady";
    if (!setReady) {
        setReady = (set_ready_fn)dlsym(lib, "Java_org_lwjgl_glfw_CallbackBridge_nativeSetInputReady");
        readySym = "Java_…nativeSetInputReady";
    }
    /* MUST enable stack-queue: critical_send_cursor_pos only writes cursorX/Y
     * (offset 0x27140) when isUseStackQueueCall=1. Direct path invokes the
     * CursorPos callback but leaves glfwGetCursorPos stuck at 0,0 — no GUI hover. */
    if (setStack) setStack(JNI_TRUE);
    jboolean stack = JNI_FALSE;
    if (setReady) stack = setReady(JNI_TRUE);
    LOGI("%s: force ready stackQ_ret=%d setStack=%p(%s) setReady=%p(%s)",
         where, (int)stack, (void *)setStack, stackSym, (void *)setReady, readySym);
    if (!setStack || !setReady) {
        LOGW("%s: incomplete force ready — touch/mouse may be dropped", where);
    }
}

/**
 * Ensure HotSpot GLFW is initialized BEFORE pojavexec JNI_OnLoad "Saving JVM".
 * FCL binds mouseDownBuffer from GLFW static fields during that path; if
 * System.load runs first, FindClass(GLFW) re-enters loadLibrary while the .so
 * is still loading and buffers can stay NULL — clicks then hit a null write
 * (SIGSEGV is ignored in this process → silent no-op).
 */
static bool preinit_hotspot_glfw(JNIEnv *env) {
    if (!env) return false;
    jclass glfwCls = (*env)->FindClass(env, "org/lwjgl/glfw/GLFW");
    if (!glfwCls || (*env)->ExceptionCheck(env)) {
        log_exception(env, "preinit FindClass GLFW");
        LOGE("preinit HotSpot GLFW failed");
        return false;
    }
    /* Touch a static field so <clinit> finishes (buffers + loadLibrary). */
    jfieldID widthFid =
        (*env)->GetStaticFieldID(env, glfwCls, "mGLFWWindowWidth", "I");
    if (widthFid && !(*env)->ExceptionCheck(env)) {
        jint w = (*env)->GetStaticIntField(env, glfwCls, widthFid);
        LOGI("preinit HotSpot GLFW ok mGLFWWindowWidth=%d", (int)w);
    } else {
        log_exception(env, "preinit GLFW field");
    }
    (*env)->DeleteLocalRef(env, glfwCls);
    return !(*env)->ExceptionCheck(env);
}

static void log_pojav_environ(const char *where) {
    void *lib = open_pojavexec();
    if (!lib) return;
    void ***pp = (void ***)dlsym(lib, "pojav_environ");
    if (!pp || !*pp) {
        LOGW("%s: pojav_environ missing", where);
        return;
    }
    void *win = **pp;
    const char *renderer = getenv("POJAV_RENDERER");
    const char *egl = getenv("POJAVEXEC_EGL");
    LOGI("%s: pojav_environ=%p window=%p POJAV_RENDERER=%s POJAVEXEC_EGL=%s",
         where, (void *)*pp, win,
         renderer ? renderer : "(null)",
         egl ? egl : "(null)");
}

static bool call_setup_bridge_window(JNIEnv *env, jobject surface) {
    if (!surface) {
        LOGE("setupBridgeWindow: null surface");
        return false;
    }

    void *lib = open_pojavexec();
    if (!lib) {
        LOGE("setupBridgeWindow dlopen pojavexec: %s", dlerror());
        return false;
    }

    SetupBridgeWindow_fn fn = resolve_setup_bridge_window(lib);
    if (!fn) {
        LOGE("setupBridgeWindow dlsym: %s", dlerror());
        return false;
    }

    jclass cbCls = (*env)->FindClass(env, "org/lwjgl/glfw/CallbackBridge");
    if (!cbCls || (*env)->ExceptionCheck(env)) {
        if ((*env)->ExceptionCheck(env)) log_exception(env, "FindClass CallbackBridge");
        LOGE("setupBridgeWindow: CallbackBridge class missing");
        return false;
    }

    fn(env, cbCls, surface);
    if ((*env)->ExceptionCheck(env)) {
        log_exception(env, "setupBridgeWindow");
        (*env)->DeleteLocalRef(env, cbCls);
        return false;
    }
    (*env)->DeleteLocalRef(env, cbCls);
    LOGI("setupBridgeWindow ok");
    log_pojav_environ("after setupBridgeWindow");
    return true;
}

static void init_pojav_hooks(JNIEnv *env);

static void preload_pojav_deps(void) {
    /* Do NOT preload libgl4es_114.so / MobileGlues here — early MG constructors
     * fight ART, and APK holy-gl4es must not be pulled in before LWJGL libname. */
    const char *libs[] = {
        "libbytehook.so",
        "liblinkerhook.so",
        "libdriver_helper.so",
        "libfcl.so",
        NULL
    };
    for (int i = 0; libs[i]; i++) {
        void *h = dlopen(libs[i], RTLD_LAZY | RTLD_GLOBAL);
        if (!h) LOGW("preload %s: %s", libs[i], dlerror());
    }
}

static jint launch_embedded(LaunchCtx *ctx) {
    reset_signals();
    setenv("_JAVA_VERSION_SET", "true", 1);
    preload_pojav_deps();
    start_stdio_capture();

    void *libjvm = dlopen("libjvm.so", RTLD_LAZY | RTLD_GLOBAL);
    if (!libjvm) {
        LOGE("dlopen libjvm.so: %s", dlerror());
        stop_stdio_capture();
        return -2;
    }
    LOGI("libjvm.so loaded");

    JNI_CreateJavaVM_func createVM =
        (JNI_CreateJavaVM_func)dlsym(libjvm, "JNI_CreateJavaVM");
    if (!createVM) {
        LOGE("JNI_CreateJavaVM missing: %s", dlerror());
        stop_stdio_capture();
        return -3;
    }

    ParsedArgs pa;
    if (!parse_args(ctx->argv, ctx->argc, &pa)) {
        LOGE("parse_args failed (no main class?)");
        stop_stdio_capture();
        return -4;
    }
    LOGI("main=%s jvmOpts=%d gameArgs=%d cpLen=%d",
         pa.mainClass, pa.nOpts, pa.nGameArgs,
         pa.classpath ? (int)strlen(pa.classpath) : 0);

    JavaVMInitArgs vmArgs;
    vmArgs.version = 0x00010006; /* JNI_VERSION_1_6 */
    vmArgs.nOptions = pa.nOpts;
    vmArgs.options = pa.opts;
    vmArgs.ignoreUnrecognized = JNI_TRUE;

    JavaVM *jvm = NULL;
    JNIEnv *jenv = NULL;
    jint rc = createVM(&jvm, (void **)&jenv, &vmArgs);
    if (rc != JNI_OK || !jenv) {
        LOGE("JNI_CreateJavaVM failed: %d", (int)rc);
        free_parsed(&pa);
        stop_stdio_capture();
        return rc != 0 ? rc : -5;
    }
    LOGI("JVM created");

    /*
     * Register libpojavexec with THIS HotSpot VM via System.load(absolutePath).
     * Plain dlopen+JNI_OnLoad does NOT put the .so on HotSpot's JNI native-library
     * list, so GLFW.nglfwSetMouseButtonCallback later fails to link and mouseCb
     * stays NULL — ART then queues events that the game never receives.
     *
     * Order matters (FCL): initialize GLFW first so keyDownBuffer/mouseDownBuffer
     * exist, THEN System.load → JNI_OnLoad "Saving JVM" binds those buffers.
     * Early System.load before GLFW <clinit> can leave mouseDownBuffer NULL;
     * with SIGSEGV ignored here, every click becomes a silent no-op.
     */
    if (!preinit_hotspot_glfw(jenv)) {
        LOGW("GLFW preinit failed — continuing with System.load anyway");
    }
    if (!hotspot_system_load_pojavexec(jenv)) {
        LOGW("HotSpot System.load(pojavexec) failed — trying manual JNI_OnLoad");
        if (!call_pojav_jni_onload(jvm, jenv, "HotSpot")) {
            LOGW("HotSpot pojavexec JNI_OnLoad failed — input may be dead");
        }
    } else {
        log_pojav_environ("after HotSpot System.load(pojavexec)");
    }
    force_input_bridge_ready("after HotSpot pojavexec load");
    log_hotspot_pump_diag(jenv);
    if (!patch_hotspot_pump_function_pointers(jenv)) {
        LOGW("GLFW pump pointer patch failed — touch may not reach Minecraft");
    }
    log_hotspot_pump_diag(jenv);

    /* pojavexec hookExec/installLwjglDlopenHook crash in embedded HotSpot; rely on
     * JNI_OnLoad + POJAV_RENDERER env + ART setupBridgeWindow instead. */

    /* Prefer system classloader first (uses -Djava.class.path) */
    jclass clCls = (*jenv)->FindClass(jenv, "java/lang/ClassLoader");
    jmethodID getSys = (*jenv)->GetStaticMethodID(
        jenv, clCls, "getSystemClassLoader", "()Ljava/lang/ClassLoader;");
    jobject sysLoader = (*jenv)->CallStaticObjectMethod(jenv, clCls, getSys);
    log_exception(jenv, "getSystemClassLoader");

    jmethodID loadClass = (*jenv)->GetMethodID(
        jenv, clCls, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
    jstring mainName = (*jenv)->NewStringUTF(jenv, pa.mainClass);
    jclass mainCls = NULL;

    if (sysLoader && loadClass) {
        mainCls = (jclass)(*jenv)->CallObjectMethod(jenv, sysLoader, loadClass, mainName);
        if ((*jenv)->ExceptionCheck(jenv) || !mainCls) {
            log_exception(jenv, "system loadClass");
            mainCls = NULL;
        }
    }

    if (!mainCls) {
        LOGW("falling back to URLClassLoader");
        jobject urlLoader = build_url_classloader(jenv, pa.classpath);
        if (urlLoader) {
            mainCls = (jclass)(*jenv)->CallObjectMethod(jenv, urlLoader, loadClass, mainName);
            if ((*jenv)->ExceptionCheck(jenv) || !mainCls) {
                log_exception(jenv, "URLClassLoader loadClass");
                mainCls = NULL;
            }
        }
    }
    (*jenv)->DeleteLocalRef(jenv, mainName);

    if (!mainCls) {
        LOGE("failed to load main class %s", pa.mainClass);
        (*jvm)->DestroyJavaVM(jvm);
        free_parsed(&pa);
        stop_stdio_capture();
        return -6;
    }
    LOGI("Loaded %s", pa.mainClass);

    jmethodID mainMethod = (*jenv)->GetStaticMethodID(
        jenv, mainCls, "main", "([Ljava/lang/String;)V");
    if (!mainMethod) {
        log_exception(jenv, "GetStaticMethodID main");
        (*jvm)->DestroyJavaVM(jvm);
        free_parsed(&pa);
        stop_stdio_capture();
        return -7;
    }

    jclass strCls = (*jenv)->FindClass(jenv, "java/lang/String");
    jobjectArray argsArr = (*jenv)->NewObjectArray(jenv, pa.nGameArgs, strCls, NULL);
    for (int i = 0; i < pa.nGameArgs; i++) {
        jstring js = (*jenv)->NewStringUTF(jenv, pa.gameArgs[i]);
        (*jenv)->SetObjectArrayElement(jenv, argsArr, i, js);
        (*jenv)->DeleteLocalRef(jenv, js);
    }

    LOGI("Invoking main(%d args)", pa.nGameArgs);
    (*jenv)->CallStaticVoidMethod(jenv, mainCls, mainMethod, argsArr);
    if ((*jenv)->ExceptionCheck(jenv)) {
        log_exception(jenv, "main()");
        (*jvm)->DestroyJavaVM(jvm);
        free_parsed(&pa);
        stop_stdio_capture();
        return 1;
    }

    (*jvm)->DestroyJavaVM(jvm);
    free_parsed(&pa);
    stop_stdio_capture();
    LOGI("JVM exited cleanly");
    return 0;
}

static void *launch_thread(void *arg) {
    LaunchCtx *ctx = (LaunchCtx *)arg;
    ctx->result = launch_embedded(ctx);
    return NULL;
}

static void *ensure_pojavexec(void) {
    return open_pojavexec();
}

typedef jint (*PojavLaunchJvm_fn)(JNIEnv *, jclass, jobjectArray);

static void init_pojav_hooks(JNIEnv *env) {
    void *lib = ensure_pojavexec();
    if (!lib) {
        LOGE("init_pojav_hooks: pojavexec not loaded");
        return;
    }

    HookFn hookExec = (HookFn)dlsym(lib, "hookExec");
    if (hookExec) {
        hookExec(env);
        if ((*env)->ExceptionCheck(env)) {
            log_exception(env, "hookExec");
        } else {
            LOGI("hookExec ok");
        }
    } else {
        LOGW("hookExec not found: %s", dlerror());
    }

    HookFn installLwjglHook = (HookFn)dlsym(lib, "installLwjglDlopenHook");
    if (installLwjglHook) {
        installLwjglHook(env);
        if ((*env)->ExceptionCheck(env)) {
            log_exception(env, "installLwjglDlopenHook");
        } else {
            LOGI("installLwjglDlopenHook ok");
        }
    } else {
        LOGW("installLwjglDlopenHook not found: %s", dlerror());
    }

    register_callbackbridge_send_natives(env);
}

static jint launch_via_pojavexec(JNIEnv *env, jobjectArray argsArray) {
    void *lib = ensure_pojavexec();
    if (!lib) {
        LOGE("launch_via_pojavexec: dlopen pojavexec: %s", dlerror());
        return -8;
    }
    PojavLaunchJvm_fn launch = (PojavLaunchJvm_fn)dlsym(
        lib, "Java_com_oracle_dalvik_VMLauncher_launchJVM");
    if (!launch) {
        LOGE("launch_via_pojavexec: dlsym VMLauncher.launchJVM: %s", dlerror());
        return -9;
    }
    LOGI("delegating to pojavexec VMLauncher.launchJVM");
    jint code = launch(env, NULL, argsArray);
    if ((*env)->ExceptionCheck(env)) {
        log_exception(env, "VMLauncher.launchJVM");
        return 1;
    }
    return code;
}

JNIEXPORT jboolean JNICALL
Java_com_booxin_launcher_core_launch_NativeJvmLauncher_nativeInitializeHooks(
    JNIEnv *env, jclass clazz)
{
    (void)clazz;
    init_pojav_hooks(env);
    return JNI_TRUE;
}

/*
 * Dump pojav_environ input gates. Layout: struct booxin_pojav_environ_s above.
 * Used to diagnose "Java logs mouseBtn but game ignores clicks".
 */

JNIEXPORT void JNICALL
Java_com_booxin_launcher_core_launch_NativeJvmLauncher_nativeMarkMousePositionDirty(
    JNIEnv *env, jclass clazz)
{
    (void)env;
    (void)clazz;
    /* Cached pointer — never dlopen/dlsym on the touch hot path. */
    if (!g_pojav_environ_pp) resolve_pojav_pump_syms();
    if (!g_pojav_environ_pp || !*g_pojav_environ_pp) return;
    /* shouldUpdateMouse at +0x1d3 — never e->shouldUpdateMouse (struct drifts). */
    ((uint8_t *)(*g_pojav_environ_pp) + 0x27000)[0x1d3] = 1;
}

JNIEXPORT jboolean JNICALL
Java_com_booxin_launcher_core_launch_NativeJvmLauncher_nativeForcePumpInput(
    JNIEnv *env, jclass clazz)
{
    (void)env;
    (void)clazz;
    void *lib = open_pojavexec();
    if (!lib) return JNI_FALSE;
    struct booxin_pojav_environ_s **pp =
        (struct booxin_pojav_environ_s **)dlsym(lib, "pojav_environ");
    if (!pp || !*pp) return JNI_FALSE;
    struct booxin_pojav_environ_s *e = *pp;
    if (!e->runtimeJavaVMPtr || !e->showingWindow) return JNI_FALSE;
    if (!e->isInputReady) return JNI_FALSE;

    typedef void (*start_fn)(void);
    typedef void (*pump_fn)(void *);
    typedef void (*stop_fn)(void);
    /* Prefer our wrappers so mouseCb hooks + HotSpot JNIEnv bind apply. */
    start_fn start = booxin_start_pumping;
    pump_fn pump = booxin_pump_events;
    stop_fn stop = booxin_stop_pumping;
    if (!start || !pump || !stop) return JNI_FALSE;

    JavaVM *hs = e->runtimeJavaVMPtr;
    JNIEnv *hsEnv = NULL;
    int attached = 0;
    jint st = (*hs)->GetEnv(hs, (void **)&hsEnv, JNI_VERSION_1_4);
    if (st == JNI_EDETACHED) {
        if ((*hs)->AttachCurrentThread(hs, &hsEnv, NULL) != 0) return JNI_FALSE;
        attached = 1;
    } else if (st != JNI_OK) {
        return JNI_FALSE;
    }
    /* Callbacks / framebuffer hooks expect a HotSpot JNIEnv here. */
    e->runtimeJNIEnvPtr_JRE = hsEnv;

    start();
    pump((void *)(long)e->showingWindow);
    stop();

    if (attached) {
        (*hs)->DetachCurrentThread(hs);
    }
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_booxin_launcher_core_launch_NativeJvmLauncher_nativeInvokeCursorPosCallback(
    JNIEnv *env, jclass clazz, jfloat x, jfloat y)
{
    (void)env;
    (void)clazz;
    void *lib = open_pojavexec();
    if (!lib) return JNI_FALSE;
    struct booxin_pojav_environ_s **pp =
        (struct booxin_pojav_environ_s **)dlsym(lib, "pojav_environ");
    if (!pp || !*pp) return JNI_FALSE;
    struct booxin_pojav_environ_s *e = *pp;
    void *targetWindow = booxin_mc_glfw_window(e, NULL);
    if (!e->GLFW_invoke_CursorPos || !targetWindow) return JNI_FALSE;

    // Callback trampolines need a valid HotSpot JNIEnv for the *current thread*.
    JavaVM *hs = e->runtimeJavaVMPtr;
    if (!hs) return JNI_FALSE;
    JNIEnv *hsEnv = NULL;
    int attached = 0;
    jint st = (*hs)->GetEnv(hs, (void **)&hsEnv, JNI_VERSION_1_4);
    if (st == JNI_EDETACHED) {
        if ((*hs)->AttachCurrentThread(hs, &hsEnv, NULL) != 0) return JNI_FALSE;
        attached = 1;
    } else if (st != JNI_OK) {
        return JNI_FALSE;
    }
    e->runtimeJNIEnvPtr_JRE = hsEnv;

    e->cursorX = x;
    e->cursorY = y;
    e->cLastX = x;
    e->cLastY = y;
    ((booxin_cursor_pos_fn)e->GLFW_invoke_CursorPos)(targetWindow, x, y);
    if (attached) {
        (*hs)->DetachCurrentThread(hs);
    }
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_booxin_launcher_core_launch_NativeJvmLauncher_nativeInvokeMouseButtonCallback(
    JNIEnv *env, jclass clazz, jint button, jint action, jint mods)
{
    (void)env;
    (void)clazz;
    void *lib = open_pojavexec();
    if (!lib) return JNI_FALSE;
    struct booxin_pojav_environ_s **pp =
        (struct booxin_pojav_environ_s **)dlsym(lib, "pojav_environ");
    if (!pp || !*pp) return JNI_FALSE;
    struct booxin_pojav_environ_s *e = *pp;
    void *targetWindow = booxin_mc_glfw_window(e, NULL);
    if (!e->GLFW_invoke_MouseButton || !targetWindow) return JNI_FALSE;

    // Callback trampolines need a valid HotSpot JNIEnv for the *current thread*.
    JavaVM *hs = e->runtimeJavaVMPtr;
    if (!hs) return JNI_FALSE;
    JNIEnv *hsEnv = NULL;
    int attached = 0;
    jint st = (*hs)->GetEnv(hs, (void **)&hsEnv, JNI_VERSION_1_4);
    if (st == JNI_EDETACHED) {
        if ((*hs)->AttachCurrentThread(hs, &hsEnv, NULL) != 0) return JNI_FALSE;
        attached = 1;
    } else if (st != JNI_OK) {
        return JNI_FALSE;
    }
    e->runtimeJNIEnvPtr_JRE = hsEnv;

    ((booxin_mouse_btn_fn)e->GLFW_invoke_MouseButton)(
        targetWindow, button, action, mods);
    if (attached) {
        (*hs)->DetachCurrentThread(hs);
    }
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL
Java_com_booxin_launcher_core_launch_NativeJvmLauncher_nativeDumpInputBridge(
    JNIEnv *env, jclass clazz)
{
    (void)clazz;
    void *lib = open_pojavexec();
    if (!lib) {
        return (*env)->NewStringUTF(env, "pojavexec=null");
    }
    struct booxin_pojav_environ_s **pp =
        (struct booxin_pojav_environ_s **)dlsym(lib, "pojav_environ");
    if (!pp || !*pp) {
        return (*env)->NewStringUTF(env, "pojav_environ=null");
    }
    struct booxin_pojav_environ_s *e = *pp;
    /* libpojavexec (arm64) lays out the post-events fields at a fixed base
     * offset 0x27000 from pojav_environ — verified via critical_send_cursor_pos /
     * nglfwGetCursorPos disassembly. Our C struct mirror can drift; dump the
     * binary offsets so diagnostics match what Minecraft actually reads. */
    const uint8_t *base = (const uint8_t *)e;
    const uint8_t *tail = base + 0x27000;
    double cursorX = 0, cursorY = 0;
    memcpy(&cursorX, tail + 0x140, sizeof(cursorX));
    memcpy(&cursorY, tail + 0x148, sizeof(cursorY));
    int ready = tail[0x1d0];
    int cursorEnter = tail[0x1d1];
    int stackQ = tail[0x1d2];
    int shouldUpdateMouse = tail[0x1d3];
    long showing = 0;
    memcpy(&showing, tail + 0x1c8, sizeof(showing));
    void *mouseCb = NULL, *cursorCb = NULL, *keyCb = NULL;
    memcpy(&cursorCb, tail + 0x1f8, sizeof(cursorCb));
    memcpy(&keyCb, tail + 0x208, sizeof(keyCb));
    memcpy(&mouseCb, tail + 0x210, sizeof(mouseCb));
    size_t inIdx = 0, outIdx = 0;
    memcpy(&inIdx, tail + 0x130, sizeof(inIdx));
    memcpy(&outIdx, tail + 0x120, sizeof(outIdx));
    int mouseBtn0 = 0;
    if (e->mouseDownBuffer) mouseBtn0 = e->mouseDownBuffer[0];
    char buf[768];
    snprintf(buf, sizeof(buf),
             "env=%p ready=%d stackQ=%d shouldUpd=%d grab=%d cursorEnter=%d "
             "mouseCb=%p cursorCb=%p keyCb=%p "
             "mouseBuf=%p keyBuf=%p mouseBtn0=%d "
             "events=%zu inIdx=%zu outIdx=%zu "
             "cursor=%.1f,%.1f win=%dx%d showing=%ld pojavWindow=%p mainBundle=%p dvm=%p jvm=%p jreEnv=%p",
             (void *)e,
             ready,
             stackQ,
             shouldUpdateMouse,
             e->isGrabbing ? 1 : 0,
             cursorEnter,
             mouseCb,
             cursorCb,
             keyCb,
             (void *)e->mouseDownBuffer,
             (void *)e->keyDownBuffer,
             mouseBtn0,
             (size_t)atomic_load(&e->eventCounter),
             inIdx,
             outIdx,
             cursorX, cursorY,
             e->savedWidth, e->savedHeight,
             showing,
             e->pojavWindow,
             e->mainWindowBundle,
             (void *)e->dalvikJavaVMPtr,
             (void *)e->runtimeJavaVMPtr,
             (void *)e->runtimeJNIEnvPtr_JRE);
    LOGI("inputBridge: %s", buf);
    return (*env)->NewStringUTF(env, buf);
}

JNIEXPORT jboolean JNICALL
Java_com_booxin_launcher_core_launch_NativeJvmLauncher_nativeSetupBridgeWindow(
    JNIEnv *env, jclass clazz, jobject surface)
{
    (void)clazz;
    if (!surface) return JNI_FALSE;

    pthread_mutex_lock(&g_bridge_mutex);
    if (g_bridge_surface) {
        (*env)->DeleteGlobalRef(env, g_bridge_surface);
        g_bridge_surface = NULL;
    }
    g_bridge_surface = (*env)->NewGlobalRef(env, surface);
    pthread_mutex_unlock(&g_bridge_mutex);
    if (!g_bridge_surface) {
        LOGE("setupBridgeWindow: NewGlobalRef failed");
        return JNI_FALSE;
    }

    JavaVM *artVm = NULL;
    if ((*env)->GetJavaVM(env, &artVm) != JNI_OK || !artVm) {
        LOGE("setupBridgeWindow: GetJavaVM failed");
        return JNI_FALSE;
    }
    /* ART pass: saves dalvikJavaVMPtr before binding the Surface window. */
    if (!call_pojav_jni_onload(artVm, env, "ART")) {
        return JNI_FALSE;
    }
    log_pojav_environ("after ART JNI_OnLoad");

    return call_setup_bridge_window(env, surface) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_booxin_launcher_core_launch_NativeJvmLauncher_nativeDlopen(
    JNIEnv *env, jclass clazz, jstring path)
{
    const char *p = (*env)->GetStringUTFChars(env, path, NULL);
    if (!p) return JNI_FALSE;
    void *h = dlopen(p, RTLD_LAZY | RTLD_GLOBAL);
    if (!h) LOGE("dlopen %s: %s", p, dlerror());
    else LOGI("dlopen ok: %s", p);
    (*env)->ReleaseStringUTFChars(env, path, p);
    return h ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_booxin_launcher_core_launch_NativeJvmLauncher_nativeProbeJvm(
    JNIEnv *env, jclass clazz)
{
    void *lib = dlopen("libjvm.so", RTLD_LAZY | RTLD_GLOBAL);
    if (!lib) {
        LOGE("probe libjvm: %s", dlerror());
        return JNI_FALSE;
    }
    if (!dlsym(lib, "JNI_CreateJavaVM")) {
        LOGE("probe JNI_CreateJavaVM missing");
        return JNI_FALSE;
    }
    LOGI("probe ok (JNI_CreateJavaVM)");
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_booxin_launcher_core_launch_NativeJvmLauncher_nativeChdir(
    JNIEnv *env, jclass clazz, jstring path)
{
    const char *p = (*env)->GetStringUTFChars(env, path, NULL);
    if (!p) return JNI_FALSE;
    int r = chdir(p);
    if (r != 0) LOGE("chdir %s failed", p);
    (*env)->ReleaseStringUTFChars(env, path, p);
    return r == 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_booxin_launcher_core_launch_NativeJvmLauncher_nativeLaunchJvm(
    JNIEnv *env, jclass clazz,
    jobjectArray argsArray,
    jstring fullVersion,
    jstring dotVersion)
{
    (void)fullVersion;
    (void)dotVersion;
    (void)clazz;

    if (!argsArray) return -1;

    /* pojavexec VMLauncher uses JLI_Launch → exec(), which is blocked by SELinux on /data.
     * Always use embedded JNI_CreateJavaVM instead. */
    (void)launch_via_pojavexec; /* suppress unused-function warning */

    int argc = 0;
    char **argv = to_argv(env, argsArray, &argc);
    if (!argv || argc <= 0) return -1;

    LaunchCtx ctx = { .argc = argc, .argv = argv, .result = -1 };

    pthread_attr_t attr;
    pthread_t thread;
    pthread_attr_init(&attr);
    pthread_attr_setstacksize(&attr, 16 * 1024 * 1024);

    int cr = pthread_create(&thread, &attr, launch_thread, &ctx);
    pthread_attr_destroy(&attr);
    if (cr != 0) {
        LOGE("pthread_create: %d", cr);
        free_argv(argv, argc);
        return -4;
    }

    pthread_join(thread, NULL);
    free_argv(argv, argc);
    return ctx.result;
}
