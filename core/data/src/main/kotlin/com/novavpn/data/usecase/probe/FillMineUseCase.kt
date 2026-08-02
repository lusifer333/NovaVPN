package com.novavpn.data.usecase.probe

import com.novavpn.domain.model.ServerConfig
import com.novavpn.domain.model.ServerProbeResult
import com.novavpn.domain.probe.MineFillResult
import com.novavpn.domain.probe.MineFiller
import com.novavpn.domain.probe.ProbeOptions
import com.novavpn.domain.probe.ProfileServers
import com.novavpn.domain.probe.RealDelayProber
import javax.inject.Inject

/**
 * Runs the Karing-style mine fill (real-delay urltest over the catalog)
 * and returns the curated "mine" — fastest healthy relays first.
 *
 * Shared by the Profiles screen (Fill Mine) and the Home screen
 * auto-connect, so both pick the same way and neither freezes the UI:
 * the fill runs chunked engine sessions (bounded config size) and streams
 * results off the main thread.
 */
class FillMineUseCase @Inject constructor(
    private val realDelayProber: RealDelayProber
) {

    /**
     * @param profiles subscription groups, processed in order.
     * @param options engine-session settings mirroring the real connection
     *        (TLS fragment, TCP keepalive) so the probe verdict is honest.
     */
    suspend operator fun invoke(
        profiles: List<ProfileServers>,
        options: ProbeOptions = ProbeOptions(),
        previousMine: List<ServerConfig> = emptyList(),
        onResult: (ServerProbeResult) -> Unit = {},
        onMine: (List<ServerConfig>) -> Unit = {}
    ): MineFillResult = MineFiller(realDelayProber).fill(
        profiles = profiles,
        options = options,
        previousMine = previousMine,
        onResult = onResult,
        onMine = onMine
    )
}