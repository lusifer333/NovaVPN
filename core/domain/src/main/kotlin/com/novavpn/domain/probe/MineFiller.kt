package com.novavpn.domain.probe

import com.novavpn.domain.model.ServerConfig
import com.novavpn.domain.model.ServerProbeResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.onAwait
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * One subscription group fed to the mine filler.
 */
data class ProfileServers(
    val profileId: String,
    val profileName: String,
    val servers: List<ServerConfig>
)

/**
 * Outcome of a mine-fill run.
 *
 * @param mine the curated healthy servers, best-first (sorted by real
 *   delay, ascending) — may be smaller than capacity (partial mine).
 * @param results per-server probe outcomes for every tested server.
 */
data class MineFillResult(
    val mine: List<ServerConfig>,
    val results: Map<String, ServerProbeResult>
)

/**
 * The mine filler (پر کردن معدن).
 *
 * Streaming three-stage pipeline, one config at a time, early-exit at
 * every stage:
 *
 *  1. Merged probe (TCP connect + TLS handshake on ONE socket) — parallel
 *     across a probe batch ([probeParallelism], default 100).
 *  2. Real-delay relay probe through the actual engine — only for the
 *     greens of the current batch, as a bounded wave ([e2eParallelism],
 *     default 3 engines).
 *  3. A config enters the mine only when BOTH stages passed.
 *
 * Profiles are processed in order; each profile's proportional share (see
 * [MineCapacity]) is filled before moving to the next profile. The whole
 * run stops the moment the mine is full. A dead server costs one fast
 * probe; only greens pay the expensive engine round-trip.
 *
 * Pure JVM — [RealDelayProber] is injected (fake in unit tests, real
 * engine implementation on device).
 */
class MineFiller(
    private val prober: ServerProber,
    private val realDelayProber: RealDelayProber
) {

    /**
     * Fill the mine.
     *
     * @param profiles subscription groups, processed in order.
     * @param probeParallelism concurrent merged probes (stage 1+2).
     * @param e2eParallelism concurrent real-delay engines (stage 3 wave).
     * @param onResult per-server outcome, emitted as soon as each stage of
     *   that server completes (a green server emits twice: after 1+2 and
     *   after 3).
     * @param onMine current mine contents, emitted on every change.
     */
    suspend fun fill(
        profiles: List<ProfileServers>,
        probeParallelism: Int = DEFAULT_PROBE_PARALLELISM,
        e2eParallelism: Int = DEFAULT_E2E_PARALLELISM,
        onResult: (ServerProbeResult) -> Unit = {},
        onMine: (List<ServerConfig>) -> Unit = {}
    ): MineFillResult {
        val totalServers = profiles.sumOf { it.servers.size }
        if (totalServers <= 0) return MineFillResult(emptyList(), emptyMap())

        val capacity = MineCapacity.capacityOf(totalServers)
        val results = mutableMapOf<String, ServerProbeResult>()
        val mine = mutableListOf<ServerConfig>()
        val mineIds = mutableSetOf<String>()
        val filledByProfile = mutableMapOf<String, Int>()
        val e2eSemaphore = Semaphore(e2eParallelism.coerceAtLeast(1))

        profileLoop@ for (profile in profiles) {
            if (profile.servers.isEmpty()) continue
            if (mine.size >= capacity) break
            val share = MineCapacity.profileShare(
                capacity = capacity,
                totalServers = totalServers,
                profileServers = profile.servers.size
            )
            if (share <= 0) continue

            for (batch in profile.servers.chunked(probeParallelism.coerceAtLeast(1))) {
                val profileFilled = filledByProfile[profile.profileId] ?: 0
                if (mine.size >= capacity || profileFilled >= share) break

                // ── Stage 1+2: merged probes across the batch, parallel ──
                val batchOutcomes = coroutineScope {
                    batch.map { server ->
                        async { prober.fastTlsProbe(server) }
                    }.map { it.await() }
                }
                val greens = mutableListOf<Pair<ServerConfig, ServerProbeResult>>()
                for ((server, result) in batch.zip(batchOutcomes)) {
                    results[result.serverId] = result
                    onResult(result)
                    if (result.green) greens += server to result
                }
                if (greens.isEmpty()) continue

                // ── Stage 3: real-delay wave on the greens, early-stop ──
                val wave = e2eWave(
                    greens = greens,
                    semaphore = e2eSemaphore,
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
                if (wave) continue@profileLoop
            }
        }

        // Best-first: sorted by real delay (ascending), green-only.
        val sorted = mine.sortedBy { results[it.id]?.e2eMs ?: Long.MAX_VALUE }
        return MineFillResult(sorted, results)
    }

    /**
     * Runs real-delay probes for the batch's greens as a bounded wave.
     * Results are consumed in COMPLETION order (fastest relay first), so
     * the mine fills best-first while the wave still runs; early-stop is
     * preserved. Returns true when this profile's share (or the whole
     * mine) filled — the caller then moves to the next profile / stops.
     */
    private suspend fun e2eWave(
        greens: List<Pair<ServerConfig, ServerProbeResult>>,
        semaphore: Semaphore,
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
        val deferreds = greens.map { (server, base) ->
            async {
                val outcome = semaphore.withPermit { realDelayProber.probe(server) }
                server to base.copy(e2eOk = outcome.ok, e2eMs = outcome.e2eMs)
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
        const val DEFAULT_PROBE_PARALLELISM = 100
        const val DEFAULT_E2E_PARALLELISM = 3
    }
}
