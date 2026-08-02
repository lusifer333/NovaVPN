package com.novavpn.domain.probe

import com.novavpn.domain.model.ServerConfig
import com.novavpn.domain.model.ServerProbeResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** One subscription group fed to the mine filler. */
data class ProfileServers(
    val profileId: String,
    val profileName: String,
    val servers: List<ServerConfig>
)

/** Outcome of a mine-fill run. */
data class MineFillResult(
    val mine: List<ServerConfig>,
    val results: Map<String, ServerProbeResult>
)

/**
 * The mine filler (پر کردن معدن) — Karing-style: ONE test, ONE ping.
 *
 * The only test is the sing-box/Karing urltest: a real HTTP round-trip
 * (https://www.gstatic.com/generate_204 → 204) through the server's
 * tunnel, measured end-to-end. The 204 verdict is the test, the
 * round-trip time is the ping — both from the same request. There is no
 * separate handshake stage anymore: a server either relays traffic or it
 * doesn't, and only relaying servers enter the mine.
 *
 * All probes run against ONE shared engine session ([RealDelayProber.start]),
 * so the fill is fast and the phone never freezes (no per-server spawns).
 * Results stream in completion order (best-first) with early-stop: the
 * mine fills with the fastest relays first, per-profile shares (see
 * [MineCapacity]) are respected, and probes still running when the mine
 * is full are cancelled.
 *
 * Pure JVM — [RealDelayProber] is injected (fake in unit tests, real
 * engine implementation on device).
 */
class MineFiller(
    private val realDelayProber: RealDelayProber
) {

    /**
     * Fill the mine.
     *
     * @param profiles subscription groups, processed in order.
     * @param e2eParallelism concurrent real-delay probes (bounded wave).
     * @param onResult per-server outcome, emitted as soon as it completes.
     * @param onMine current mine contents, emitted on every change.
     */
    suspend fun fill(
        profiles: List<ProfileServers>,
        e2eParallelism: Int = DEFAULT_E2E_PARALLELISM,
        onResult: (ServerProbeResult) -> Unit = {},
        onMine: (List<ServerConfig>) -> Unit = {}
    ): MineFillResult {
        val totalServers = profiles.sumOf { it.servers.size }
        val allServers = profiles.flatMap { it.servers }
        var engineStarted = false
        try {
            if (totalServers > 0) {
                engineStarted = try {
                    realDelayProber.start(allServers)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    false
                }
            }
            if (totalServers <= 0) return MineFillResult(emptyList(), emptyMap())

            val capacity = MineCapacity.capacityOf(totalServers)
            val results = mutableMapOf<String, ServerProbeResult>()
            val mine = mutableListOf<ServerConfig>()
            val mineIds = mutableSetOf<String>()
            val filledByProfile = mutableMapOf<String, Int>()
            val semaphore = Semaphore(e2eParallelism.coerceAtLeast(1))

            profileLoop@ for (profile in profiles) {
                if (profile.servers.isEmpty()) continue
                if (mine.size >= capacity) break
                val share = MineCapacity.profileShare(
                    capacity = capacity,
                    totalServers = totalServers,
                    profileServers = profile.servers.size
                )
                if (share <= 0) continue

                // The single Karing urltest wave over this profile's servers.
                val filled = e2eWave(
                    servers = profile.servers,
                    semaphore = semaphore,
                    engineUp = engineStarted,
                    mine = mine,
                    mineIds = mineIds,
                    capacity = capacity,
                    profileId = profile.profileId,
                    share = share,
                    filledByProfile = filledByProfile,
                    results = results,
                    onResult = onResult,
                    onMine = onMine
                )
                if (filled) continue@profileLoop
            }

            // Best-first: sorted by round-trip delay (ascending).
            val sorted = mine.sortedBy { results[it.id]?.e2eMs ?: Long.MAX_VALUE }
            return MineFillResult(sorted, results)
        } finally {
            if (engineStarted) {
                try {
                    realDelayProber.stop()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // engine stop must never mask the fill result
                }
            }
        }
    }

    /**
     * Runs the Karing urltest probes for [servers] as a bounded wave.
     *
     * Results are consumed in COMPLETION order (fastest relay first), so
     * the mine fills best-first while the wave still runs; early-stop is
     * preserved. Returns true when this profile's share (or the whole
     * mine) filled — the caller then moves to the next profile / stops.
     */
    private suspend fun e2eWave(
        servers: List<ServerConfig>,
        semaphore: Semaphore,
        engineUp: Boolean,
        mine: MutableList<ServerConfig>,
        mineIds: MutableSet<String>,
        capacity: Int,
        profileId: String,
        share: Int,
        filledByProfile: MutableMap<String, Int>,
        results: MutableMap<String, ServerProbeResult>,
        onResult: (ServerProbeResult) -> Unit,
        onMine: (List<ServerConfig>) -> Unit
    ): Boolean = coroutineScope {
        val deferreds = servers.map { server ->
            async {
                // No engine session → nothing can pass (no false
                // positives): the mine stays empty rather than filling
                // with untested servers.
                val outcome = if (engineUp) {
                    semaphore.withPermit { realDelayProber.probe(server.id) }
                } else {
                    RealDelayOutcome(false)
                }
                server to ServerProbeResult(
                    serverId = server.id,
                    e2eOk = outcome.ok,
                    e2eMs = outcome.e2eMs
                )
            }
        }
        val remaining = deferreds.toMutableList()
        var filled = false
        try {
            while (remaining.isNotEmpty()) {
                if (mine.size >= capacity) break
                val (deferred, pair) =
                    select<Pair<Deferred<Pair<ServerConfig, ServerProbeResult>>, Pair<ServerConfig, ServerProbeResult>>> {
                        remaining.forEach { d -> d.onAwait { d to it } }
                    }
                remaining.remove(deferred)
                val (server, merged) = pair
                results[server.id] = merged
                onResult(merged)
                if (!merged.e2eOk) continue
                if (mineIds.add(server.id)) {
                    mine += server
                    filledByProfile[profileId] = (filledByProfile[profileId] ?: 0) + 1
                    onMine(mine.toList())
                }
                if (mine.size >= capacity) { filled = true; break }
                if ((filledByProfile[profileId] ?: 0) >= share) { filled = true; break }
            }
        } catch (e: CancellationException) {
            throw e
        } finally {
            remaining.forEach { if (!it.isCompleted) it.cancel() }
        }
        filled
    }

    private companion object {
        // sing-box's urltest exercises ALL outbounds at once; a bounded
        // wave keeps the phone responsive while still streaming fast.
        const val DEFAULT_E2E_PARALLELISM = 8
    }
}
