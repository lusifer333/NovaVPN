package com.novavpn.domain.probe

/**
 * Options that shape how the mine fill's engine sessions are built.
 *
 * The probe must mirror the real connection settings as closely as
 * possible — a server that only works WITH TLS fragmentation would be
 * admitted as healthy by a plain probe, then die on first real connect
 * (and vice versa). [XrayConfigParser.buildMineConfig] receives these
 * and applies them to every probe outbound.
 *
 * @param fragmentTls apply Patterniha TLS fragmentation to eligible
 *   (TLS-over-TCP) probe outbounds — same rule as the real config:
 *   Reality and QUIC are never fragmented.
 * @param keepAlive emit client-side TCP keepalive sockopt on probe
 *   outbounds. Default true to match the engine's default behaviour.
 * @param url the Karing-style reachability URL the probe HTTP round-trip
 *   targets (default gstatic /generate_204). The full selectable set lives
 *   in the engine layer ([TrafficProbe.karingTestUrls]); this only carries
 *   the chosen one.
 * @param timeoutMs per-attempt probe timeout (default 15s, Karing's
 *   url_test_timeout max).
 */
data class ProbeOptions(
    val fragmentTls: Boolean = false,
    val keepAlive: Boolean = true,
    val url: String = KaringTestUrls.defaultTestUrl,
    /** Per-attempt HTTP timeout. Default FAST (3.5s) — a dead relay must not
     *  block the mine fill for the Karing urlTestTimeout (default 15s). Only
     *  the config-test screen / urltest loop pass the user's chosen
     *  urlTestTimeoutSec here. */
    val timeoutMs: Int = KaringTestUrls.XRAY_E2E_TIMEOUT_MS
)
