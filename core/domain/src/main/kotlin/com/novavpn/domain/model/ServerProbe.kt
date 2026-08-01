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
 * Result of the two-stage fast server test:
 *  1. [tcpOk]/[tcpMs] — raw TCP connect RTT (fast ping).
 *  2. [tlsOk]/[tlsMs] — trust-all TLS handshake (reliability check),
 *     plus [certStatus] as an informational certificate badge.
 *
 * A server counts as usable ([healthy]) only when BOTH stages pass.
 */
data class ServerProbeResult(
    val serverId: String = "",
    val tcpOk: Boolean = false,
    val tcpMs: Long? = null,
    val tlsOk: Boolean = false,
    val tlsMs: Long? = null,
    val certStatus: CertStatus = CertStatus.NONE
) {
    val healthy: Boolean get() = tcpOk && tlsOk
}
