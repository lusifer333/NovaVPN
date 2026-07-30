/**
 * NativeProcess.c
 *
 * Minimal JNI bridge for fork()+execv() that preserves a TUN file descriptor
 * across the child process boundary.
 *
 * WHY this exists instead of ProcessBuilder:
 *   Android's forkAndExec() (in ProcessImpl_md.c) closes ALL fds >= 3 before
 *   exec(), regardless of FD_CLOEXEC.  This makes it impossible to pass a TUN
 *   fd (from VpnService.Builder.establish()) to a sub-process (hev-socks5-
 *   tunnel) via Java-level ProcessBuilder or Runtime.exec().
 *
 *   This native implementation calls fork() directly, clears FD_CLOEXEC on the
 *   TUN fd in the child, then execv().  The kernel POSIX semantics guarantee
 *   that a non-CLOEXEC fd survives exec() when called this way.
 */

#include <jni.h>
#include <unistd.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <fcntl.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <signal.h>

/* ───────────────────────────────────────────────────────────────────────────
 * Helper: build a NULL-terminated argv[] from a Java String[].
 * Returns allocated array (caller must free both the array and each element).
 * On failure sets *err_out and returns NULL.
 */
static char **build_argv(JNIEnv *env, const char *binary, jobjectArray args,
                          int *err_out) {
    jsize arg_count = (*env)->GetArrayLength(env, args);
    char **argv = malloc((arg_count + 2) * sizeof(char *));
    if (!argv) { *err_out = ENOMEM; return NULL; }

    argv[0] = strdup(binary);
    if (!argv[0]) { free(argv); *err_out = ENOMEM; return NULL; }

    for (jsize i = 0; i < arg_count; i++) {
        jstring js = (jstring)(*env)->GetObjectArrayElement(env, args, i);
        const char *utf = (*env)->GetStringUTFChars(env, js, NULL);
        if (!utf) {
            for (jsize j = 0; j <= i; j++) free(argv[j]);
            free(argv);
            *err_out = ENOMEM;
            return NULL;
        }
        argv[i + 1] = strdup(utf);
        (*env)->ReleaseStringUTFChars(env, js, utf);
        if (!argv[i + 1]) {
            for (jsize j = 0; j <= i; j++) free(argv[j]);
            free(argv);
            *err_out = ENOMEM;
            return NULL;
        }
    }
    argv[arg_count + 1] = NULL;
    return argv;
}

static void free_argv(char **argv, jsize arg_count) {
    for (jsize i = 0; i <= arg_count; i++) {
        if (argv[i]) free(argv[i]);
    }
    free(argv);
}

/* ───────────────────────────────────────────────────────────────────────────
 * JNI: nativeForkExec
 *
 * Signature:
 *   static native int nativeForkExec(String binaryPath, String[] args,
 *                                     int tunFd);
 *
 *   binaryPath  – absolute path to the executable
 *   args        – command-line arguments (NOT including the binary path)
 *   tunFd       – TUN fd to preserve in the child (FD_CLOEXEC cleared)
 *
 * Returns:
 *   > 0  – child PID (process launched successfully)
 *   <= 0 – negative errno on failure (-ENOMEM, -EACCES, …)
 */
JNIEXPORT jint JNICALL
Java_com_novavpn_app_service_NativeBridgeRunner_nativeForkExec(
    JNIEnv *env, jclass clazz,
    jstring binaryPath, jobjectArray args, jint tunFd) {

    const char *binary = (*env)->GetStringUTFChars(env, binaryPath, NULL);
    if (!binary) return -ENOMEM;

    /* Build argv[] */
    int err = 0;
    char **argv = build_argv(env, binary, args, &err);
    if (!argv) {
        (*env)->ReleaseStringUTFChars(env, binaryPath, binary);
        return -err;
    }
    jsize arg_count = (*env)->GetArrayLength(env, args);

    /* ── fork() ── */
    pid_t pid = fork();

    if (pid == 0) {
        /* ─── CHILD ─── */
        /* Clear FD_CLOEXEC on the TUN fd so it survives execv().
         * Belt-and-suspenders: Os.dup() in Java already produced a
         * non-CLOEXEC copy, but guard against any regression.  If
         * fcntl() silently fails on vendor ROMs the dup'd fd has no
         * CLOEXEC anyway, so this is purely defensive. */
        fcntl(tunFd, F_SETFD, 0);

        /* Replace process image with the bridge binary */
        execv(binary, argv);

        /* Only reached on execv failure */
        _exit(errno);
    }

    /* ─── PARENT ─── */
    free_argv(argv, arg_count);
    (*env)->ReleaseStringUTFChars(env, binaryPath, binary);

    if (pid < 0) return -errno;
    return (jint)pid;
}

/* ───────────────────────────────────────────────────────────────────────────
 * JNI: nativeIsAlive
 *
 * Checks process existence via kill(pid, 0) — does NOT reap the child
 * (status stays available for nativeWaitFor).
 *
 * Returns:
 *   1  – process is alive (still running, possibly zombie)
 *   0  – process has exited / no such process
 *  -1  – error
 */
JNIEXPORT jint JNICALL
Java_com_novavpn_app_service_NativeBridgeRunner_nativeIsAlive(
    JNIEnv *env, jclass clazz, jint pid) {

    if (pid <= 0) return 0;

    int rc = kill((pid_t)pid, 0);
    if (rc == 0) return 1;           /* alive */
    if (errno == ESRCH) return 0;    /* no such process (exited) */
    return -1;                       /* EPERM etc — unexpected */
}

/* ───────────────────────────────────────────────────────────────────────────
 * JNI: nativeWaitFor
 *
 * Busy-poll waitpid() with WNOHANG for up to timeoutMs milliseconds.
 * Reaps the child and returns its exit code.
 *
 * Returns:
 *   0–255  – child exit code (normal exit)
 *   0–255  – child exit code (even if killed by signal, this is 128+sig)
 *   -1     – waitpid error (ECHILD, etc.)
 *   -2     – timeout (child still alive after timeoutMs)
 */
JNIEXPORT jint JNICALL
Java_com_novavpn_app_service_NativeBridgeRunner_nativeWaitFor(
    JNIEnv *env, jclass clazz, jint pid, jint timeoutMs) {

    if (pid <= 0) return -1;

    int status;
    int stepMs = 50;
    int waited = 0;

    while (waited < timeoutMs) {
        pid_t result = waitpid((pid_t)pid, &status, WNOHANG);
        if (result == pid) {
            /* Reaped successfully */
            if (WIFEXITED(status))  return WEXITSTATUS(status);
            if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
            return -1;  /* unexpected */
        }
        if (result == -1 && errno == ECHILD) return -1;  /* already reaped */
        /* result == 0 → still running */
        usleep(stepMs * 1000);
        waited += stepMs;
    }
    return -2;  /* timeout */
}

/* ───────────────────────────────────────────────────────────────────────────
 * JNI: nativeKillProcess
 *
 * Sends SIGTERM, waits 200 ms, then SIGKILL.
 */
JNIEXPORT void JNICALL
Java_com_novavpn_app_service_NativeBridgeRunner_nativeKillProcess(
    JNIEnv *env, jclass clazz, jint pid) {

    if (pid <= 0) return;
    kill((pid_t)pid, SIGTERM);
    usleep(200000); /* 200 ms grace period */
    kill((pid_t)pid, SIGKILL);
    /* Reap immediately so we don't leave a zombie */
    int status;
    waitpid((pid_t)pid, &status, WNOHANG);
}
