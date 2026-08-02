package com.novavpn.domain.probe

import com.novavpn.domain.model.ServerConfig

/**
 * Outcome of the real-delay (stage 3) relay probe.
 *
 * @param ok true when a real HTTP round-trip (Karing-style urltest:
 *   https://www.gstatic.com/generate_204 → 204) through the actual engine
 *   completed within the timeout.
 * @param e2eMs elapsed milliseconds of the round-trip, null on failure.
 */
data class RealDelayOutcome(
    val ok: Boolean,
    val e2eMs: Long? = null
)

/**
 * Real-delay probe — stage 3 of the mine filler, Karing/sing-box style.
 *
 * [start] boots ONE shared engine session that carries every candidate
 * server as an outbound (mirroring sing-box's `urltest` outbound: many
 * outbounds in one core, no per-server spawn). [probe] then performs a
 * REAL HTTP request through that server's tunnel and measures the delay;
 * [stop] tears the session down. This is the only stage that proves the
 * server actually RELAYS data (handshake-yes / data-no servers fail
 * here), and one shared session keeps it fast — zero process churn, so
 * the phone never freezes.
 *
 * Implementations are platform-bound (engine binary); tests use fakes.
 */
interface RealDelayProber {

    /** Start the shared engine session with all [candidates]. Idempotent. */
    suspend fun start(candidates: List<ServerConfig>): Boolean

    /** Real HTTP round-trip through [serverId]'s tunnel; false if it doesn't relay. */
    suspend fun probe(serverId: String): RealDelayOutcome

    /** Tear the shared session down. Safe when never started. */
    suspend fun stop()
}
