package com.novavpn.engine.xray

import android.content.Context
import android.net.ConnectivityManager
import com.novavpn.domain.model.EngineType
import com.novavpn.domain.model.ServerConfig
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
 * Stage-3 real-delay prober backed by the REAL xray engine.
 *
 * For every probe it spawns a fresh xray instance (same binary, same
 * config generation as the VPN engine), waits for its SOCKS5 inbound,
 * then performs a timed real round-trip — SOCKS5 CONNECT to 8.8.8.8:53 +
 * DNS-over-TCP query — through the tunnel. This is the only measurement
 * that proves the server actually RELAYS data, and it yields the true
 * relay delay ([RealDelayOutcome.e2eMs]).
 *
 * ## Isolation
 *
 * - The probe instance uses its OWN socks port (10818, vs. the VPN
 *   engine's 10808) so a fill run never collides with an active VPN
 *   connection. Probe xray processes are hard-killed in [finally].
 * - Spawn-per-probe (stateless): 3 concurrent instances are the E2E wave
 *   of the mine filler; each lives only for its own round-trip.
 * - Outbound sockets are bound to the active (underlying) network, the
 *   same trick the VPN engine uses to bypass the tunnel — otherwise the
 *   probe's own traffic would loop through the VPN.
 *
 * Cancellation-safe: [awaitProbeReady] polls with [delay]/[ensureActive],
 * and a cancelled probe kills its process in [finally].
 */
@Singleton
class XrayRealDelayProber @Inject constructor(
    private val binaryManager: BinaryManager,
    @ApplicationContext private val appContext: Context
) : RealDelayProber {

    override suspend fun probe(server: ServerConfig): RealDelayOutcome = withContext(Dispatchers.IO) {
        var process: Process? = null
        var configFile: File? = null
        try {
            val binaryPath = binaryManager.ensureEngine(EngineType.Xray).getOrThrow()
            val engineDir = binaryManager.getEngineDirectory(EngineType.Xray)

            // Same config the VPN engine would use, but the SOCKS inbound
            // moved to PROBE_PORT so a concurrent VPN session stays untouched.
            val json = XrayConfigParser.toXrayJson(
                config = server,
                logDir = engineDir.absolutePath
            )
                .replace("\"port\":10808", "\"port\":$PROBE_PORT")
                .replace("\"port\": 10808", "\"port\": $PROBE_PORT")
            configFile = File(engineDir, "probe-config-${server.id.hashCode()}.json")
            configFile!!.writeText(json)
            Timber.tag(TAG).i(
                "PROBE_SPAWN: %s (%s:%d, %s) port=%d",
                server.name, server.address, server.port, server.protocol, PROBE_PORT
            )

            val pb = ProcessBuilder(binaryPath, "run", "-c", configFile!!.absolutePath)
            pb.redirectErrorStream(true)
            pb.environment()?.put("XRAY_LOCATION_ASSET", ".")

            // Bind the child to the underlying network before fork, so its
            // outbound sockets bypass the tunnel (same as XrayEngine.start()).
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val activeNet = cm?.activeNetwork
            if (activeNet != null) cm.bindProcessToNetwork(activeNet)
            process = pb.start()
            if (activeNet != null) cm?.bindProcessToNetwork(null)

            if (!awaitProbeReady(process!!)) {
                Timber.tag(TAG).w("PROBE_INIT_FAIL: %s never opened port %d", server.name, PROBE_PORT)
                return@withContext RealDelayOutcome(false)
            }

            val ms = TrafficProbe.connectRoundTrip(
                proxyHost = "127.0.0.1",
                proxyPort = PROBE_PORT,
                timeoutMs = ROUNDTRIP_TIMEOUT_MS
            )
            Timber.tag(TAG).i(
                "PROBE_E2E: %s -> %s",
                server.name,
                if (ms != null) "${ms}ms" else "FAIL (relay dead)"
            )
            if (ms != null) RealDelayOutcome(true, ms) else RealDelayOutcome(false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).w("PROBE_THREW for %s: %s", server.name, e.message)
            RealDelayOutcome(false)
        } finally {
            try { process?.destroyForcibly() } catch (_: Exception) {}
            try { process?.waitFor(1, TimeUnit.SECONDS) } catch (_: Exception) {}
            try { configFile?.delete() } catch (_: Exception) {}
        }
    }

    /** Polls the probe SOCKS5 port until it accepts connections. */
    private suspend fun awaitProbeReady(process: Process): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < INIT_WAIT_MS) {
            currentCoroutineContext().ensureActive()
            if (!process.isAlive) return false
            try {
                Socket().use {
                    it.connect(InetSocketAddress("127.0.0.1", PROBE_PORT), 200)
                }
                Timber.tag(TAG).i("PROBE_READY: port %d accepting", PROBE_PORT)
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
        const val PROBE_PORT = 10818
        const val INIT_WAIT_MS = 12_000L
        const val ROUNDTRIP_TIMEOUT_MS = 6_000
    }
}
