package com.novavpn.domain.probe

import com.novavpn.domain.model.CertStatus
import com.novavpn.domain.model.ServerConfig
import com.novavpn.domain.model.ServerProbeResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.inject.Inject
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Ultra-fast two-stage server test:
 *
 * 1. **Fast stage** — raw TCP connect, RTT measured from SYN to SYN-ACK
 *    (~100-300ms per server, no TLS, no crypto).
 * 2. **Reliable stage** — trust-all TLS handshake, run only for servers
 *    that passed stage 1; also classifies the presented certificate chain
 *    (valid / self-signed / invalid) as an informational badge.
 *
 * Both stages run in parallel across servers, bounded by [parallelism]
 * (Semaphore), so a list of hundreds of servers completes in a few seconds.
 * Pure JVM — no Android dependencies, unit-testable on the host.
 */
class ServerProber @Inject constructor() {

    /** Stage 1: raw TCP connect RTT in ms. */
    suspend fun fastProbe(server: ServerConfig, timeoutMs: Long = DEFAULT_TCP_TIMEOUT_MS): ServerProbeResult {
        if (!server.hasValidEndpoint()) return ServerProbeResult(server.id)
        val start = System.nanoTime()
        return try {
            withContext(Dispatchers.IO) {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(server.address, server.port), timeoutMs.toInt())
                }
            }
            val rttMs = (System.nanoTime() - start) / 1_000_000
            ServerProbeResult(serverId = server.id, tcpOk = true, tcpMs = rttMs)
        } catch (e: Exception) {
            ServerProbeResult(server.id)
        }
    }

    /**
     * Fast stage for a whole list — parallel, bounded by [parallelism].
     * Returns results for every server (failed ones carry tcpOk=false).
     */
    suspend fun fastProbeAll(
        servers: List<ServerConfig>,
        tcpTimeoutMs: Long = DEFAULT_TCP_TIMEOUT_MS,
        parallelism: Int = DEFAULT_PARALLELISM
    ): Map<String, ServerProbeResult> =
        runBounded(servers, parallelism) { server -> fastProbe(server, tcpTimeoutMs) }
            .associateBy { it.serverId }

    /**
     * Reliable stage for the servers that passed stage 1.
     * Merges the TLS outcome onto [stage1] and returns the full map.
     */
    suspend fun tlsProbeAll(
        servers: List<ServerConfig>,
        stage1: Map<String, ServerProbeResult>,
        tlsTimeoutMs: Long = DEFAULT_TLS_TIMEOUT_MS,
        parallelism: Int = DEFAULT_PARALLELISM
    ): Map<String, ServerProbeResult> {
        val toTest = servers.filter { stage1[it.id]?.tcpOk == true }
        val outcomes = runBounded(toTest, parallelism) { server ->
            server.id to tlsHandshake(server, tlsTimeoutMs)
        }
        val merged = stage1.toMutableMap()
        for ((id, outcome) in outcomes) {
            val base = merged[id] ?: continue
            merged[id] = base.copy(
                tlsOk = outcome.tlsOk,
                tlsMs = outcome.tlsMs,
                certStatus = outcome.certStatus
            )
        }
        return merged
    }

    /** Full two-stage run (stage 2 only for stage-1 passes). */
    suspend fun probeAll(
        servers: List<ServerConfig>,
        tcpTimeoutMs: Long = DEFAULT_TCP_TIMEOUT_MS,
        tlsTimeoutMs: Long = DEFAULT_TLS_TIMEOUT_MS,
        parallelism: Int = DEFAULT_PARALLELISM
    ): Map<String, ServerProbeResult> {
        val stage1 = fastProbeAll(servers, tcpTimeoutMs, parallelism)
        return tlsProbeAll(servers, stage1, tlsTimeoutMs, parallelism)
    }

    // ------------------------------------------------------------------
    // TLS stage
    // ------------------------------------------------------------------

    private suspend fun tlsHandshake(server: ServerConfig, timeoutMs: Long): TlsOutcome {
        if (!server.hasValidEndpoint()) return TlsOutcome(false, null, CertStatus.NONE)
        val sni = extractServerName(server.rawConfig, server.address)
        val start = System.nanoTime()
        return try {
            withContext(Dispatchers.IO) {
                val socket = trustAllSslContext.socketFactory.createSocket() as SSLSocket
                socket.use {
                    socket.connect(InetSocketAddress(server.address, server.port), timeoutMs.toInt())
                    configureSni(socket, sni)
                    socket.soTimeout = timeoutMs.toInt()
                    socket.startHandshake()
                    val certs = socket.session.peerCertificates
                        .filterIsInstance<X509Certificate>()
                    TlsOutcome(
                        tlsOk = true,
                        tlsMs = (System.nanoTime() - start) / 1_000_000,
                        certStatus = classifyCert(certs, defaultTrustManager)
                    )
                }
            }
        } catch (e: Exception) {
            TlsOutcome(false, null, CertStatus.NONE)
        }
    }

    private fun configureSni(socket: SSLSocket, sni: String) {
        if (sni.isBlank() || isIpLiteral(sni)) return
        runCatching {
            socket.sslParameters = socket.sslParameters.apply {
                serverNames = listOf(SNIHostName(sni))
            }
        }
    }

    private fun isIpLiteral(host: String): Boolean =
        host.matches(Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")) || host.contains(":")

    /**
     * Informational certificate classification. [tm] is the system trust
     * manager used purely for the VALID/INVALID decision — the handshake
     * itself already succeeded on the trust-all socket.
     */
    internal fun classifyCert(chain: List<X509Certificate>, tm: X509TrustManager?): CertStatus {
        if (chain.isEmpty()) return CertStatus.NONE
        val trustManager = tm ?: return CertStatus.NONE
        return try {
            trustManager.checkServerTrusted(chain.toTypedArray(), chain[0].publicKey.algorithm)
            CertStatus.VALID
        } catch (e: CertificateException) {
            val leaf = chain[0]
            if (leaf.subjectX500Principal == leaf.issuerX500Principal) {
                CertStatus.SELF_SIGNED
            } else {
                CertStatus.INVALID_CHAIN
            }
        }
    }

    /**
     * Best-effort SNI extraction from the server's own config:
     * xray JSON `streamSettings.tlsSettings.serverName` or
     * `realitySettings.serverNames[0]`, or the `serverName`/`sni` URI param.
     * Needed so Reality servers (and CDN-fronted ones) answer a plain
     * ClientHello. Falls back to the server address.
     */
    internal fun extractServerName(rawConfig: String, fallback: String): String {
        if (rawConfig.isBlank()) return fallback
        val trimmed = rawConfig.trimStart()
        if (!trimmed.startsWith("{")) {
            // URI form: vless://host:port?params#fragment
            val params = trimmed.substringAfter("?", "").substringBefore("#").split("&")
            for (p in params) {
                val kv = p.split("=", limit = 2)
                if (kv.size == 2 && (kv[0] == "serverName" || kv[0] == "sni") && kv[1].isNotBlank()) {
                    return kv[1]
                }
            }
            return fallback
        }
        return try {
            val root = Json.parseToJsonElement(trimmed).jsonObject
            val outbounds = root["outbounds"]?.jsonArray ?: return fallback
            for (o in outbounds) {
                val stream = o.jsonObject["streamSettings"]?.jsonObject ?: continue
                stream["tlsSettings"]?.jsonObject
                    ?.get("serverName")?.jsonPrimitive?.contentOrNull?.let { return it }
                stream["realitySettings"]?.jsonObject
                    ?.get("serverNames")?.jsonArray
                    ?.firstOrNull()?.jsonPrimitive?.contentOrNull?.let { return it }
            }
            fallback
        } catch (e: Exception) {
            fallback
        }
    }

    private fun ServerConfig.hasValidEndpoint(): Boolean =
        address.isNotBlank() && port in 1..65535

    // ------------------------------------------------------------------
    // Parallelism helper
    // ------------------------------------------------------------------

    private suspend fun <T> runBounded(
        items: List<ServerConfig>,
        parallelism: Int,
        block: suspend (ServerConfig) -> T
    ): List<T> = coroutineScope {
        val sem = Semaphore(parallelism.coerceAtLeast(1))
        items.map { server ->
            async(Dispatchers.IO) { sem.withPermit { block(server) } }
        }.awaitAll()
    }

    // ------------------------------------------------------------------
    // TLS machinery (shared, lazy)
    // ------------------------------------------------------------------

    private val trustAllSslContext: SSLContext by lazy {
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(TrustAllX509TrustManager), SecureRandom())
        }
    }

    private val defaultTrustManager: X509TrustManager? by lazy {
        runCatching {
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(null as KeyStore?)
            tmf.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
        }.getOrNull()
    }

    private object TrustAllX509TrustManager : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    internal data class TlsOutcome(
        val tlsOk: Boolean,
        val tlsMs: Long?,
        val certStatus: CertStatus
    )

    private companion object {
        const val DEFAULT_TCP_TIMEOUT_MS = 1_500L
        const val DEFAULT_TLS_TIMEOUT_MS = 2_000L
        const val DEFAULT_PARALLELISM = 20
    }
}
