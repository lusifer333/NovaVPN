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
 */
data class ProbeOptions(
    val fragmentTls: Boolean = false,
    val keepAlive: Boolean = true
)
