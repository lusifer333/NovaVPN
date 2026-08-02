package com.novavpn.domain.model

/**
 * Certificate verification outcome of the TLS probe stage.
 *
 * The handshake itself always uses a trust-all socket; this status is a
 * separate, informational check of the chain the server presented.
 */
enum class CertStatus {
    /** Chain verified against the system trust store. */
    VALID,

    /** Leaf's subject equals its issuer (self-signed). */
    SELF_SIGNED,

    /** Chain exists but failed verification (expired, unknown CA, ...). */
    INVALID_CHAIN,

    /** No TLS stage attempted or the handshake never completed. */
    NONE
}

/**
 * Result of the three-stage server test:
 *  1. [tcpOk]/[tcpMs] — raw TCP connect RTT (fast ping).
 *  2. [tlsOk]/[tlsMs] — trust-all TLS handshake (reliability check),
 *     plus [certStatus] as an informational certificate badge.
 *  3. [e2eOk]/[e2eMs] — real-delay relay round-trip through the actual
 *     engine (SOCKS5 CONNECT + DNS reply). Only run for servers that
 *     passed stages 1+2.
 *
 * A server counts as usable ([healthy]) only when ALL stages pass.
 * [green] is the two-stage definition (TCP + TLS) used before the
 * real-delay stage is attempted.
 */
data class ServerProbeResult(
    val serverId: String = "",
    val tcpOk: Boolean = false,
    val tcpMs: Long? = null,
    val tlsOk: Boolean = false,
    val tlsMs: Long? = null,
    val certStatus: CertStatus = CertStatus.NONE,
    val e2eOk: Boolean = false,
    val e2eMs: Long? = null
) {
    val green: Boolean get() = tcpOk && tlsOk
    val healthy: Boolean get() = green && e2eOk
}
