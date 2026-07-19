/*
 * Booxin JVM launcher — Pojav/FCL style.
 *
 * Runs JLI_Launch in the isolated :game process (no UI/HWUI threads).
 * _JAVA_VERSION_SET prevents re-exec of bin/java on Android noexec /data.
 */

#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>
#include <pthread.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define LOG_TAG "BooxinJvm"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

typedef jint (*JLI_Launch_func)(
    int argc, char **argv,
    int jargc, const char **jargv,
    int appclassc, const char **appclassv,
    const char *fullversion, const char *dotversion,
    const char *pname, const char *lname,
    jboolean javaargs, jboolean cpwildcard, jboolean javaw, jint ergo
);

typedef struct {
    int    argc;
    char **argv;
    const char *full;
    const char *dot;
    jint   result;
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

static void reset_signals(void) {
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    for (int s = SIGHUP; s < NSIG; s++) {
        if (s == SIGKILL || s == SIGSTOP) continue;
        sa.sa_handler = (s == SIGSEGV) ? SIG_IGN : SIG_DFL;
        sigaction(s, &sa, NULL);
    }
}

static jint call_jli_launch(LaunchCtx *ctx) {
    reset_signals();
    setenv("_JAVA_VERSION_SET", "true", 1);
    setenv("JDK_JAVA_OPTIONS", "", 0);

    void *libjli = dlopen("libjli.so", RTLD_LAZY | RTLD_GLOBAL);
    if (!libjli) {
        LOGE("libjli.so: %s", dlerror());
        return -2;
    }

    JLI_Launch_func launch = (JLI_Launch_func)dlsym(libjli, "JLI_Launch");
    if (!launch) {
        LOGE("JLI_Launch: %s", dlerror());
        return -3;
    }

    const char *prog = (ctx->argv && ctx->argv[0]) ? ctx->argv[0] : "java";
    const char *full = ctx->full ? ctx->full : "21.0.1-internal";
    const char *dot  = ctx->dot  ? ctx->dot  : "21.0.1";

    LOGI("JLI_Launch argc=%d", ctx->argc);
    return launch(
        ctx->argc, ctx->argv,
        0, NULL, 0, NULL,
        full, dot, prog, prog,
        JNI_FALSE, JNI_TRUE, JNI_FALSE, 0
    );
}

static void *jli_thread(void *arg) {
    LaunchCtx *ctx = (LaunchCtx *)arg;
    ctx->result = call_jli_launch(ctx);
    LOGI("JLI_Launch exit=%d", ctx->result);
    return NULL;
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
    void *lib = dlopen("libjli.so", RTLD_LAZY | RTLD_GLOBAL);
    if (!lib) {
        LOGE("probe libjli: %s", dlerror());
        return JNI_FALSE;
    }
    if (!dlsym(lib, "JLI_Launch")) {
        LOGE("probe JLI_Launch missing");
        return JNI_FALSE;
    }
    LOGI("probe ok (JLI_Launch)");
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
    int argc = 0;
    char **argv = to_argv(env, argsArray, &argc);
    if (!argv || argc <= 0) return -1;

    const char *full = (*env)->GetStringUTFChars(env, fullVersion, NULL);
    const char *dot  = (*env)->GetStringUTFChars(env, dotVersion, NULL);

    LaunchCtx ctx = { .argc = argc, .argv = argv, .full = full, .dot = dot, .result = -1 };

    pthread_attr_t attr;
    pthread_t thread;
    pthread_attr_init(&attr);
    pthread_attr_setstacksize(&attr, 16 * 1024 * 1024);

    int cr = pthread_create(&thread, &attr, jli_thread, &ctx);
    pthread_attr_destroy(&attr);
    if (cr != 0) {
        LOGE("pthread_create: %d", cr);
        if (full) (*env)->ReleaseStringUTFChars(env, fullVersion, full);
        if (dot)  (*env)->ReleaseStringUTFChars(env, dotVersion, dot);
        free_argv(argv, argc);
        return -4;
    }

    pthread_join(thread, NULL);

    if (full) (*env)->ReleaseStringUTFChars(env, fullVersion, full);
    if (dot)  (*env)->ReleaseStringUTFChars(env, dotVersion, dot);
    free_argv(argv, argc);
    return ctx.result;
}
