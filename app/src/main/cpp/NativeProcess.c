/*
 * NativeProcess.c — JNI bridge for hev-socks5-tunnel library API
 *
 * Calls upstream hev-socks5-tunnel v2.16.0 library API directly in-process,
 * passing the VpnService TUN file descriptor. No fork/exec, no custom CLI.
 *
 * Library API (from <hev-socks5-tunnel.h>):
 *   hev_socks5_tunnel_main_from_file(config_path, tun_fd) — BLOCKS until quit
 *   hev_socks5_tunnel_quit()                               — signals stop
 *   hev_socks5_tunnel_stats(...)                           — traffic counters
 */

#include <jni.h>
#include <pthread.h>
#include <string.h>
#include <stdlib.h>
#include <unistd.h>
#include <errno.h>
#include <sys/ioctl.h>
#include <linux/if.h>
#include <linux/if_tun.h>
#include <android/log.h>

#include <hev-socks5-tunnel.h>

#define LOG_TAG "NativeBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/* Forward declaration of the tunnel thread function */
static void *tunnel_thread_entry(void *arg);

/* Per-call state for the tunnel thread */
typedef struct {
    char *config_path;     /* owned, freed by thread */
    int tun_fd;            /* VpnService TUN fd (closed after tunnel returns) */
} TunnelStartArgs;

static pthread_t tunnel_thread_id;
static volatile int tunnel_running;

/* ──────────────────────────────────────────────
 * JNI: nativeGetTunName(tunFd: Int): String
 *
 * Retrieves the TUN interface name (e.g. "tun0") from the fd
 * returned by VpnService.Builder.establish(), via TUNGETIFF ioctl.
 * Returns null if the ioctl fails (caller handles it).
 * ────────────────────────────────────────────── */
JNIEXPORT jstring JNICALL
Java_com_novavpn_app_service_NativeBridgeRunner_nativeGetTunName(
    JNIEnv *env, jclass clazz, jint tun_fd)
{
    struct ifreq ifr;
    memset(&ifr, 0, sizeof(ifr));

    if (ioctl(tun_fd, TUNGETIFF, &ifr) < 0) {
        LOGE("nativeGetTunName: TUNGETIFF failed for fd=%d: %s",
             tun_fd, strerror(errno));
        return NULL;
    }

    LOGI("nativeGetTunName: fd=%d -> %s", tun_fd, ifr.ifr_name);
    return (*env)->NewStringUTF(env, ifr.ifr_name);
}

/* ──────────────────────────────────────────────
 * JNI: nativeStartTunnel(configPath: String, tunFd: Int): Boolean
 *
 * Launches hev-socks5-tunnel in a background pthread.
 * Returns true on success (thread started), false if already running.
 * The thread joins automatically when the tunnel exits after quit().
 * ────────────────────────────────────────────── */
JNIEXPORT jboolean JNICALL
Java_com_novavpn_app_service_NativeBridgeRunner_nativeStartTunnel(
    JNIEnv *env, jclass clazz,
    jstring config_path_j, jint tun_fd)
{
    if (tunnel_running) {
        LOGE("nativeStartTunnel: tunnel already running");
        return JNI_FALSE;
    }

    const char *path_utf = (*env)->GetStringUTFChars(env, config_path_j, NULL);
    if (!path_utf) {
        LOGE("nativeStartTunnel: GetStringUTFChars failed");
        return JNI_FALSE;
    }

    TunnelStartArgs *args = malloc(sizeof(TunnelStartArgs));
    if (!args) {
        LOGE("nativeStartTunnel: malloc failed");
        (*env)->ReleaseStringUTFChars(env, config_path_j, path_utf);
        return JNI_FALSE;
    }

    args->config_path = strdup(path_utf);
    args->tun_fd = (int)tun_fd;
    (*env)->ReleaseStringUTFChars(env, config_path_j, path_utf);

    if (!args->config_path) {
        LOGE("nativeStartTunnel: strdup failed");
        free(args);
        return JNI_FALSE;
    }

    tunnel_running = 1;

    int ret = pthread_create(&tunnel_thread_id, NULL,
                             tunnel_thread_entry, args);
    if (ret != 0) {
        LOGE("nativeStartTunnel: pthread_create failed: %d", ret);
        tunnel_running = 0;
        free(args->config_path);
        free(args);
        return JNI_FALSE;
    }

    /* Detach — the thread cleans up and exits on its own */
    pthread_detach(tunnel_thread_id);

    LOGI("nativeStartTunnel: started (config=%s, fd=%d)", path_utf, tun_fd);
    return JNI_TRUE;
}

/* ──────────────────────────────────────────────
 * Tunnel thread: calls the blocking library API.
 * When hev_socks5_tunnel_quit() is called from another thread,
 * the library unblocks and this function returns.
 * ────────────────────────────────────────────── */
static void *tunnel_thread_entry(void *arg)
{
    TunnelStartArgs *args = (TunnelStartArgs *)arg;
    int res;

    LOGI("tunnel thread: entering hev_socks5_tunnel_main_from_file(%s, %d)",
         args->config_path, args->tun_fd);

    res = hev_socks5_tunnel_main_from_file(args->config_path, args->tun_fd);

    LOGI("tunnel thread: exited with result %d", res);

    /* IMPORTANT: Do NOT close args->tun_fd here.
     * The TUN fd is owned by NovaVpnService (ParcelFileDescriptor) and
     * is closed by the service in its own teardown sequence.
     * The upstream library also does not close it (tun_fd_local stays 0
     * when fd is passed externally). If we close it here, the service
     * would be trying to close an already-released fd. */

    tunnel_running = 0;

    free(args->config_path);
    free(args);
    return NULL;
}

/* ──────────────────────────────────────────────
 * JNI: nativeStopTunnel()
 *
 * Signals the running tunnel to quit.
 * The library's quit is asynchronous — the thread exits when it
 * finishes cleaning up.
 * ────────────────────────────────────────────── */
JNIEXPORT void JNICALL
Java_com_novavpn_app_service_NativeBridgeRunner_nativeStopTunnel(
    JNIEnv *env, jclass clazz)
{
    if (!tunnel_running) {
        LOGI("nativeStopTunnel: not running, ignored");
        return;
    }

    LOGI("nativeStopTunnel: calling hev_socks5_tunnel_quit()");
    hev_socks5_tunnel_quit();
}

/* ──────────────────────────────────────────────
 * JNI: nativeGetTunnelRunning(): Boolean
 * ────────────────────────────────────────────── */
JNIEXPORT jboolean JNICALL
Java_com_novavpn_app_service_NativeBridgeRunner_nativeGetTunnelRunning(
    JNIEnv *env, jclass clazz)
{
    return tunnel_running ? JNI_TRUE : JNI_FALSE;
}

/* ──────────────────────────────────────────────
 * JNI: nativeGetTunnelStats(): LongArray?
 *
 * Returns [tx_packets, tx_bytes, rx_packets, rx_bytes] or null.
 * ────────────────────────────────────────────── */
JNIEXPORT jlongArray JNICALL
Java_com_novavpn_app_service_NativeBridgeRunner_nativeGetTunnelStats(
    JNIEnv *env, jclass clazz)
{
    size_t tx_packets = 0, tx_bytes = 0;
    size_t rx_packets = 0, rx_bytes = 0;

    hev_socks5_tunnel_stats(&tx_packets, &tx_bytes,
                            &rx_packets, &rx_bytes);

    jlong stats[4];
    stats[0] = (jlong)tx_packets;
    stats[1] = (jlong)tx_bytes;
    stats[2] = (jlong)rx_packets;
    stats[3] = (jlong)rx_bytes;

    jlongArray result = (*env)->NewLongArray(env, 4);
    if (result) {
        (*env)->SetLongArrayRegion(env, result, 0, 4, stats);
    }
    return result;
}
