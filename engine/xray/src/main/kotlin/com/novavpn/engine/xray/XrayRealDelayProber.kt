package com.novavpn.engine.xray

import android.content.Context
import android.net.ConnectivityManager
import com.novavpn.domain.model.EngineType
import com.novavpn.domain.model.Protocol
import com.novavpn.domain.model.ServerConfig
import com.novavpn.domain.probe.ProbeOptions
import com.novavpn.domain.probe.RealDelayOutcome
import com.novavpn.domain.probe.RealDelayProber
import com.novavpn.engine.api.BinaryManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import timber.log.Timber
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stage-3 real-delay prober backed by the REAL xray engine — Karing-style.
 *
 * sing-box's `urltest` outbound answers "does this server work?" with one
 * REAL HTTP request through the tunnel (https://www.gstatic.com/generate_204
 * → 204) and reports its delay. We replicate exactly that on the xray
 * core:
 *
 *  - [start] boots ONE shared xray instance carrying ALL candidate servers
 *    as outbounds, each pinned to its own SOCKS5 inbound
 *    (PROBE_BASE_PORT + index) by an inboundTag routing rule. This mirrors
 *    sing-box's "many outbounds in one core" urltest model: one process
 *    for the whole fill run, zero per-server spawns → fast, and the phone
 *    never freezes (the previous implementation spawned a fresh xray per
 *    probe).
 *  - [probe] dials that server's pinned inbound and runs a real HTTP
 *    round-trip through the tunnel (SOCKS5 CONNECT → TLS → GET
 *    /generate_204 → 204), up to [ATTEMPTS] tries of [ATTEMPT_TIMEOUT_MS]
 *    like Karing. Only servers that actually RELAY HTTP traffic pass.
 *  - [stop] hard-kills the shared instance.
 *
 * ## Isolation
 *
 * - The probe instance uses its OWN socks ports (10818+, vs. the VPN
 *   engine's 10808) so a fill run never collides with an active VPN
 *   connection.
 * - The child is bound to the active (underlying) network before fork, the
 *   same trick the VPN engine uses to bypass the tunnel — otherwise the
 *   probe's own traffic would loop through the VPN.
 *
 * Cancellation-safe: [awaitProbeReady] polls with [delay]/[ensureActive],
 * and a cancelled start/stop kills the process.
 */
@Singleton
class XrayRealDelayProber @Inject constructor(
    private val binaryManager: BinaryManager,
    @ApplicationContext private val appContext: Context
) : RealDelayProber {

    private var process: Process? = null
    private var configFile: File? = null
    private val portByServerId = HashMap<String, Int>()

    override suspend fun start(
        candidates: List<ServerConfig>,
        options: ProbeOptions
    ): Boolean = withContext(Dispatchers.IO) {
        if (process?.isAlive == true) return@withContext true
        var ok = false
        try {
            val binaryPath = binaryManager.ensureEngine(EngineType.Xray).getOrThrow()
            val engineDir = binaryManager.getEngineDirectory(EngineType.Xray)

            // Unknown-protocol configs would compile to a freedom outbound
            // and "pass" without relaying anything — never test those.
            val testable = candidates.filter { it.protocol != Protocol.Unknown }
            if (testable.isEmpty()) return@withContext false

            val json = XrayConfigParser.buildMineConfig(
                servers = testable,
                basePort = PROBE_BASE_PORT,
                logDir = engineDir.absolutePath,
                fragmentTls = options.fragmentTls,
                keepAlive = options.keepAlive
            )
            configFile = File(engineDir, "probe-config-mine.json")
            configFile!!.writeText(json)
            portByServerId.clear()
            testable.forEachIndexed { i, s -> portByServerId[s.id] = PROBE_BASE_PORT + i }
            Timber.tag(TAG).i(
                "PROBE_START: mine session, %d candidate outbounds on ports %d..%d",
                testable.size, PROBE_BASE_PORT, PROBE_BASE_PORT + testable.size - 1
            )

            val pb = ProcessBuilder(binaryPath, "run", "-c", configFile!!.absolutePath)
            pb.redirectErrorStream(true)
            pb.environment()?.put("XRAY_LOCATION_ASSET", ".")

            // Bind the child to the underlying network before fork, so its
            // outbound sockets bypass the tunnel (same as XrayEngine.start()).
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val activeNet = cm?.activeNetwork
            if (activeNet != null) cm.bindProcessToNetwork(activeNet)
            val spawned = try {
                pb.start()
            } finally {
                if (activeNet != null) cm?.bindProcessToNetwork(null)
            }
            process = spawned

            ok = awaitProbeReady(spawned)
            if (!ok) {
                Timber.tag(TAG).w("PROBE_START_FAIL: xray never opened port %d", PROBE_BASE_PORT)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).w("PROBE_START_THREW: %s", e.message)
        } finally {
            if (!ok) cleanup()
        }
        ok
    }

    override suspend fun probe(serverId: String): RealDelayOutcome = withContext(Dispatchers.IO) {
        val port = portByServerId[serverId]
        if (process?.isAlive != true || port == null) return@withContext RealDelayOutcome(false)
        repeat(ATTEMPTS) { attempt ->
            val ms = TrafficProbe.httpRoundTrip(
                proxyHost = "127.0.0.1",
                proxyPort = port,
                timeoutMs = ATTEMPT_TIMEOUT_MS
            )
            if (ms != null) {
                Timber.tag(TAG).i(
                    "PROBE_E2E: %s -> %dms (204, attempt %d)",
                    serverId, ms, attempt + 1
                )
                return@withContext RealDelayOutcome(true, ms)
            }
        }
        Timber.tag(TAG).w(
            "PROBE_E2E: %s FAIL after %d attempts (relay dead or no 204)",
            serverId, ATTEMPTS
        )
        RealDelayOutcome(false)
    }

    override suspend fun stop() = withContext(Dispatchers.IO) {
        cleanup()
    }

    /** Hard-kills the shared instance and forgets the session state. */
    private fun cleanup() {
        try { process?.destroyForcibly() } catch (_: Exception) {}
        try { process?.waitFor(1, TimeUnit.SECONDS) } catch (_: Exception) {}
        process = null
        try { configFile?.delete() } catch (_: Exception) {}
        configFile = null
        portByServerId.clear()
    }

    /** Polls the probe SOCKS5 base port until it accepts connections. */
    private suspend fun awaitProbeReady(process: Process): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < INIT_WAIT_MS) {
            currentCoroutineContext().ensureActive()
            if (!process.isAlive) return false
            try {
                Socket().use {
                    it.connect(InetSocketAddress("127.0.0.1", PROBE_BASE_PORT), 200)
                }
                Timber.tag(TAG).i("PROBE_READY: base port %d accepting", PROBE_BASE_PORT)
                return true
            } catch (_: Exception) {
                // not ready yet
            }
            delay(200)
        }
        return false
    }

    private companion object {
        const val TAG = "XrayRealDelay"
        const val PROBE_BASE_PORT = 10818
        const val INIT_WAIT_MS = 12_000L
        const val ATTEMPT_TIMEOUT_MS = 15_000
        const val ATTEMPTS = 3
    }
}
