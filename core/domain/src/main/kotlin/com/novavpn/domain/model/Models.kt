package com.novavpn.domain.model

import kotlinx.serialization.Serializable

/**
 * Represents a VPN subscription source.
 */
@Serializable
data class Subscription(
    val id: String = "",
    val name: String = "Untitled",
    val url: String = "",
    val isEnabled: Boolean = true,
    val lastUpdated: Long = 0L,
    val autoUpdate: Boolean = true,
    val updateIntervalHours: Int = 24
)

/**
 * Supported proxy protocols.
 */
@Serializable
enum class Protocol(val displayName: String) {
    VMess("VMess"),
    VLESS("VLESS"),
    Trojan("Trojan"),
    Shadowsocks("Shadowsocks"),
    SOCKS5("SOCKS5"),
    HTTP("HTTP"),
    Unknown("Unknown")
}

/**
 * Supported transport protocols.
 */
@Serializable
enum class Transport(val displayName: String) {
    TCP("TCP"),
    WebSocket("WebSocket"),
    XHTTP("XHTTP"),
    gRPC("gRPC"),
    QUIC("QUIC"),
    HTTP("HTTP"),
    Unknown("Unknown")
}

/**
 * Security layer.
 */
@Serializable
enum class Security(val displayName: String) {
    None("None"),
    TLS("TLS"),
    Reality("Reality"),
    Unknown("Unknown")
}

/**
 * A parsed proxy server configuration.
 */
@Serializable
data class ServerConfig(
    val id: String = "",
    val subscriptionId: String = "",
    val name: String = "Unknown Server",
    val address: String = "",
    val port: Int = 443,
    val protocol: Protocol = Protocol.Unknown,
    val transport: Transport = Transport.Unknown,
    val security: Security = Security.None,
    val rawConfig: String = "",
    val engineFormat: EngineFormat = EngineFormat.XrayJson
)

/**
 * Engine-agnostic config format.
 */
@Serializable
enum class EngineFormat {
    XrayJson,
    SingboxJson,
    ClashMeta,
    SIP008,
    PlainJson
}

/**
 * VPN connection state.
 */
enum class ConnectionState {
    Disconnected,
    Connecting,
    Connected,
    Disconnecting,
    Error
}

/**
 * Engine runtime state.
 */
enum class EngineRuntimeState {
    Idle,
    Preparing,
    Starting,
    Running,
    Stopping,
    Crashed
}

/**
 * Connection statistics snapshot.
 */
@Serializable
data class ConnectionStats(
    val bytesReceived: Long = 0L,
    val bytesSent: Long = 0L,
    val sessionStartTime: Long = 0L,
    val sessionDuration: Long = 0L
)

/**
 * Server connectivity test result.
 */
@Serializable
data class TestResult(
    val serverId: String = "",
    val timestamp: Long = 0L,
    val connectionSuccess: Boolean = false,
    val latencyMs: Long = -1L,
    val dnsSuccess: Boolean = false,
    val downloadSpeedBps: Long = -1L,
    val errorMessage: String = ""
) {
    val isHealthy: Boolean get() = connectionSuccess && dnsSuccess && latencyMs >= 0
}

/**
 * Smart score for ranking servers.
 */
@Serializable
data class ServerScore(
    val serverId: String = "",
    val connectionSuccessRate: Double = 0.0,
    val averageLatencyMs: Long = -1L,
    val recentSuccessRate: Double = 0.0,
    val reconnectCount: Int = 0,
    val disconnectCount: Int = 0,
    val lastSuccessfulTime: Long = 0L,
    val startupTimeMs: Long = -1L,
    val dnsSuccessRate: Double = 0.0,
    val speedSampleBps: Long = -1L,
    val lastTestTime: Long = 0L
) {
    companion object {
        private const val CONNECTION_WEIGHT = 0.30
        private const val LATENCY_WEIGHT = 0.20
        private const val RECENT_SUCCESS_WEIGHT = 0.20
        private const val STABILITY_WEIGHT = 0.10
        private const val DNS_WEIGHT = 0.10
        private const val SPEED_WEIGHT = 0.10
    }

    /**
     * Composite intelligence score (0.0 - 100.0).
     * Higher is better.
     */
    fun calculate(): Double {
        var score = 0.0

        // Connection success rate (0-1)
        score += connectionSuccessRate.coerceIn(0.0, 1.0) * CONNECTION_WEIGHT * 100.0

        // Latency: lower is better, cap at 5000ms
        val latencyScore = if (averageLatencyMs > 0) {
            ((1.0 - (averageLatencyMs.toDouble() / 5000.0)).coerceIn(0.0, 1.0)) * 100.0
        } else 0.0
        score += latencyScore * LATENCY_WEIGHT

        // Recent success rate (last 10 attempts)
        score += recentSuccessRate.coerceIn(0.0, 1.0) * RECENT_SUCCESS_WEIGHT * 100.0

        // Stability: penalty for reconnects/disconnects
        val stability = (1.0 - ((reconnectCount + disconnectCount).coerceAtMost(20) / 20.0)).coerceIn(0.0, 1.0)
        score += stability * STABILITY_WEIGHT * 100.0

        // DNS success rate
        score += dnsSuccessRate.coerceIn(0.0, 1.0) * DNS_WEIGHT * 100.0

        // Speed score: cap at 100 Mbps
        val speedScore = if (speedSampleBps > 0) {
            ((speedSampleBps.toDouble() / 100_000_000.0).coerceIn(0.0, 1.0)) * 100.0
        } else 0.0
        score += speedScore * SPEED_WEIGHT

        return score.coerceIn(0.0, 100.0)
    }
}

/**
 * Log severity level.
 */
@Serializable
enum class LogLevel {
    Debug,
    Info,
    Warning,
    Error
}

/**
 * A single log entry.
 */
@Serializable
data class LogEntry(
    val id: Long = 0L,
    val timestamp: Long = 0L,
    val level: LogLevel = LogLevel.Info,
    val tag: String = "NovaVPN",
    val message: String = ""
)

/**
 * Supported VPN engines.
 */
@Serializable
enum class EngineType(
    val displayName: String,
    /** Override lib name for jniLibs (e.g. libhev-socks5-tunnel.so). */
    val libName: String? = null
) {
    Xray("Xray Core"),
    HevSocks5Tunnel("hev-socks5-tunnel", libName = "libhev-socks5-tunnel.so"),
    Unknown("Unknown")
}

/**
 * User preferences/settings.
 */
@Serializable
data class AppSettings(
    val selectedEngine: EngineType = EngineType.Xray,
    val customDns: String = "",
    val enableFakeDns: Boolean = false,
    val enableBlockQuic: Boolean = true,
    val enableTlsFragment: Boolean = false,
    val enableTcpKeepAlive: Boolean = false,
    val enableIPv6: Boolean = false,
    val enablePerAppVpn: Boolean = false,
    val enableSplitTunnel: Boolean = false,
    val enableAlwaysOnVpn: Boolean = false,
    val enableNotifications: Boolean = true,
    // Karing-style urltest config-test settings (v0.17.0).
    // urlTestUrl: the reachability URL the config test hits; default is
    // Karing's first/default test URL (gstatic /generate_204).
    // urlTestTimeoutSec: 1..15s (Karing's url_test_timeout range).
    val urlTestUrl: String = "https://www.gstatic.com/generate_204",
    // 5s default (was 15s): on a low-end device a long per-attempt timeout
    // makes "Test All" hang for ages — 5s is enough for the Karing parity
    // and keeps the whole wave snappy on weak hardware.
    val urlTestTimeoutSec: Int = 5,
    val theme: ThemeMode = ThemeMode.System,
    val language: String = "system",
    // 0 = pre-migration (no marker persisted yet). Existing JSON without this
    // field decodes to 0, so the one-time default migration in
    // SettingsSerializer.migrate() runs and stamps 2 back.
    val settingsVersion: Int = 0
)

/**
 * Theme selection.
 */
@Serializable
enum class ThemeMode {
    Light,
    Dark,
    System
}

/**
 * App configuration constants.
 */
object NovaConfig {
    const val VERSION = "1.0.0"
    const val DB_NAME = "novavpn.db"
    const val LOG_TAG = "NovaVPN"
    const val NOTIFICATION_ID = 1001
    const val VPN_SESSION_NAME = "NovaVPN"
    const val PREF_NAME = "novavpn_prefs"
    const val MAX_LOG_ENTRIES = 10_000
    const val SCORE_HISTORY_SIZE = 50
    const val MAX_BACKUPS = 20
}
