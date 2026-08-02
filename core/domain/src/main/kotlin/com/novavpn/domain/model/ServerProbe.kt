package com.novavpn.domain.model

/**
 * Result of the single Karing-style urltest probe (one test + one ping):
 * a real HTTP round-trip (https://www.gstatic.com/generate_204 → 204)
 * through the server's tunnel on the shared engine.
 *
 * [e2eOk] is the verdict (the test), [e2eMs] is the measured end-to-end
 * delay (the ping). A server is usable ([healthy]) only when it actually
 * relayed HTTP traffic — there is no separate handshake stage anymore.
 */
data class ServerProbeResult(
    val serverId: String = "",
    val e2eOk: Boolean = false,
    val e2eMs: Long? = null
) {
    val healthy: Boolean get() = e2eOk
}
