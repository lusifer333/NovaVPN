package com.novavpn.domain.model

/**
 * Single source of truth for VPN connection state.
 *
 * Every connection state is represented atomically — there is never a
 * situation where the header says "Connection Error" while the button
 * says "Disconnect" or the header says "Connecting…" while the button
 * says "Connect".
 *
 * @see VpnState.Error.message carries the human-readable error string.
 */
sealed interface VpnState {
    /** VPN is idle — not connected, no active session. */
    data object Disconnected : VpnState

    /** A connection attempt is in progress. */
    data object Connecting : VpnState

    /** VPN tunnel is active and routing traffic. */
    data object Connected : VpnState

    /** Graceful shutdown is in progress. */
    data object Disconnecting : VpnState

    /** A connection attempt or the active session encountered a failure. */
    data class Error(val message: String) : VpnState
}
