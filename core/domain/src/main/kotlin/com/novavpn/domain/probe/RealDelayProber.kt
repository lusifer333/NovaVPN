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
 * [start] boots an engine session that carries the given [candidates] as
 * outbounds (mirroring sing-box's `urltest` outbound: many outbounds in
 * one core, no per-server spawn). The mine filler calls [start] once per
 * bounded chunk (≤100 candidates) so the generated config stays small —
 * Android's RLIMIT_NOFILE (~1024) caps how many outbound sockets a single
 * session may open, and one giant session with thousands of outbounds
 * freezes the device. [probe] then performs a REAL HTTP request through
 * that server's tunnel and measures the delay; [stop] tears the session
 * down. This is the only stage that proves the server actually RELAYS
 * data (handshake-yes / data-no servers fail here), and bounded sessions
 * keep it fast — zero process churn per chunk, so the phone never freezes.
 *
 * Implementations are platform-bound (engine binary); tests use fakes.
 */
interface RealDelayProber {

    /**
     * Start the engine session with all [candidates]. Idempotent.
     * [options] mirror the real connection settings (TLS fragmentation,
     * TCP keepalive) so the probe measures the same path the app will use.
     */
    suspend fun start(candidates: List<ServerConfig>, options: ProbeOptions = ProbeOptions()): Boolean

    /** Real HTTP round-trip through [serverId]'s tunnel; false if it doesn't relay. */
    suspend fun probe(serverId: String): RealDelayOutcome

    /** Tear the shared session down. Safe when never started. */
    suspend fun stop()
}
