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
 *
 * Stdout/stderr from the child are redirected to a log file (logFilePath)
 * so that if the bridge binary crashes immediately after execv, its dying
 * words are captured for diagnostics.
 */

#include <jni.h>
#include <unistd.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <sys/stat.h>
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
 * Helper: open a log file and redirect stdout+stderr to it.
 * Called in the child process before execv().
 */
static void redirect_stdio_to_file(const char *logPath) {
    if (!logPath || logPath[0] == '\0') return;

    int logFd = open(logPath,
                     O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC,
                     S_IRUSR | S_IWUSR | S_IRGRP | S_IROTH);
    if (logFd < 0) return;

    dup2(logFd, STDOUT_FILENO);
    dup2(logFd, STDERR_FILENO);
    close(logFd);
}

/* ───────────────────────────────────────────────────────────────────────────
 * JNI: nativeForkExec
 *
 * Signature:
 *   static native int nativeForkExec(String binaryPath, String[] args,
 *                                     int tunFd, String logFilePath);
 *
 *   binaryPath   – absolute path to the executable
 *   args         – command-line arguments (NOT including the binary path)
 *   tunFd        – TUN fd to preserve in the child (FD_CLOEXEC cleared)
 *   logFilePath   – optional path to capture stdout+stderr (empty = no capture)
 *
 * Returns:
 *   > 0  – child PID (process launched successfully)
 *   <= 0 – negative errno on failure (-ENOMEM, -EACCES, …)
 */
JNIEXPORT jint JNICALL
Java_com_novavpn_app_service_NativeBridgeRunner_nativeForkExec(
    JNIEnv *env, jclass clazz,
    jstring binaryPath, jobjectArray args, jint tunFd,
    jstring logFilePath) {

    const char *binary = (*env)->GetStringUTFChars(env, binaryPath, NULL);
    if (!binary) return -ENOMEM;

    const char *logPath = NULL;
    if (logFilePath) {
        logPath = (*env)->GetStringUTFChars(env, logFilePath, NULL);
    }

    /* Build argv[] */
    int err = 0;
    char **argv = build_argv(env, binary, args, &err);
    if (!argv) {
        (*env)->ReleaseStringUTFChars(env, binaryPath, binary);
        if (logPath) (*env)->ReleaseStringUTFChars(env, logFilePath, logPath);
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

        /* Set O_NONBLOCK on the TUN fd — hev-socks5-tunnel expects
         * non-blocking I/O for its epoll-based event loop.  We use
         * F_GETFL first to preserve any existing flags (O_RDWR, etc.)
         * rather than raw F_SETFL which would clobber them. */
        int fl = fcntl(tunFd, F_GETFL, 0);
        if (fl >= 0) fcntl(tunFd, F_SETFL, fl | O_NONBLOCK);

        /* Capture stdout+stderr so bridge crash output is visible */
        redirect_stdio_to_file(logPath);

        /* Replace process image with the bridge binary */
        execv(binary, argv);

        /* Only reached on execv failure */
        _exit(errno);
    }

    /* ─── PARENT ─── */
    free_argv(argv, arg_count);
    (*env)->ReleaseStringUTFChars(env, binaryPath, binary);
    if (logPath) (*env)->ReleaseStringUTFChars(env, logFilePath, logPath);

    if (pid < 0) return -errno;
    return (jint)pid;
}

/* ───────────────────────────────────────────────────────────────────────────
 * JNI: nativeIsAlive
 *
 * Checks whether a child process is still alive using waitpid(WNOHANG).
 * Reaps the child status — once a process has exited, subsequent calls
 * return 0.
 *
 * Returns:
 *   1  – process is still running
 *   0  – process has exited (or was already reaped)
 */
JNIEXPORT jint JNICALL
Java_com_novavpn_app_service_NativeBridgeRunner_nativeIsAlive(
    JNIEnv *env, jclass clazz, jint pid) {

    if (pid <= 0) return 0;
    int status;
    pid_t result = waitpid((pid_t)pid, &status, WNOHANG);
    return (result == 0) ? 1 : 0;   /* 1 = alive, 0 = dead */
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
