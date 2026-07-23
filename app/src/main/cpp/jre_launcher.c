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
 * HotSpot System.load(absolutePath) so JNI native lookup for
 * GLFW.nglfwSet*Callback / CallbackBridge works inside the embedded JVM.
 */
static bool hotspot_system_load_pojavexec(JNIEnv *env) {
    char path[PATH_MAX];
    if (!pojavexec_staged_path(path, sizeof(path))) {
        LOGE("hotspot System.load: staged libpojavexec.so missing");
        return false;
    }
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
    LOGI("HotSpot System.load(%s) ok", path);
    return true;
}

/** Force stack-queue + input-ready so ART touch is not silently dropped. */
static void force_input_bridge_ready(const char *where) {
    void *lib = open_pojavexec();
    if (!lib) return;
    typedef void (*set_stack_fn)(jboolean);
    typedef jboolean (*set_ready_fn)(jboolean);
    set_stack_fn setStack = (set_stack_fn)dlsym(lib, "critical_set_stackqueue");
    set_ready_fn setReady =
        (set_ready_fn)dlsym(lib, "JavaCritical_org_lwjgl_glfw_CallbackBridge_nativeSetInputReady");
    if (setStack) setStack(JNI_TRUE);
    jboolean stack = JNI_FALSE;
    if (setReady) stack = setReady(JNI_TRUE);
    LOGI("%s: force ready stackQ_ret=%d setStack=%p setReady=%p",
         where, (int)stack, (void *)setStack, (void *)setReady);
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
     */
    if (!hotspot_system_load_pojavexec(jenv)) {
        LOGW("HotSpot System.load(pojavexec) failed — trying manual JNI_OnLoad");
        if (!call_pojav_jni_onload(jvm, jenv, "HotSpot")) {
            LOGW("HotSpot pojavexec JNI_OnLoad failed — input may be dead");
        }
    } else {
        log_pojav_environ("after HotSpot System.load(pojavexec)");
    }
    force_input_bridge_ready("after HotSpot pojavexec load");

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
 * Dump pojav_environ input gates. Layout must match FCL environ.h.
 * Used to diagnose "Java logs mouseBtn but game ignores clicks".
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
    char buf[512];
    snprintf(buf, sizeof(buf),
             "env=%p ready=%d stackQ=%d grab=%d cursorEnter=%d "
             "mouseCb=%p cursorCb=%p keyCb=%p "
             "events=%zu inIdx=%zu outIdx=%zu "
             "cursor=%.1f,%.1f win=%dx%d showing=%ld dvm=%p jvm=%p",
             (void *)e,
             e->isInputReady ? 1 : 0,
             e->isUseStackQueueCall ? 1 : 0,
             e->isGrabbing ? 1 : 0,
             e->isCursorEntered ? 1 : 0,
             e->GLFW_invoke_MouseButton,
             e->GLFW_invoke_CursorPos,
             e->GLFW_invoke_Key,
             (size_t)atomic_load(&e->eventCounter),
             e->inEventIndex,
             e->outEventIndex,
             e->cursorX, e->cursorY,
             e->savedWidth, e->savedHeight,
             e->showingWindow,
             (void *)e->dalvikJavaVMPtr,
             (void *)e->runtimeJavaVMPtr);
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
