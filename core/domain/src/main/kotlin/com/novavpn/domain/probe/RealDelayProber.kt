package com.novavpn.domain.probe

import com.novavpn.domain.model.ServerConfig

/**
 * Outcome of the real-delay (stage 3) relay probe.
 *
 * @param ok true when a real round-trip (SOCKS5 CONNECT + reply) through
 *   the actual engine completed within the timeout.
 * @param e2eMs elapsed milliseconds of the round-trip, null on failure.
 */
data class RealDelayOutcome(
    val ok: Boolean,
    val e2eMs: Long? = null
)

/**
 * Real-delay probe — stage 3 of the mine filler.
 *
 * Implementations start the ACTUAL engine (xray, ...) with the server
 * config, connect a SOCKS5 client to its loopback inbound and perform a
 * real relay round-trip, measuring the delay. This is the only stage that
 * proves the server actually RELAYS data (handshake-yes / data-no servers
 * fail here).
 *
 * Implementations are platform-bound (engine binary); tests use fakes.
 */
interface RealDelayProber {
    suspend fun probe(server: ServerConfig): RealDelayOutcome
}
