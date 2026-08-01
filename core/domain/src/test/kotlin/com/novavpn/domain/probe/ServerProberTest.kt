package com.novavpn.domain.probe

import com.novavpn.domain.model.CertStatus
import com.novavpn.domain.model.ServerConfig
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

class ServerProberTest {

    private val prober = ServerProber()

    private fun server(addr: String, port: Int, raw: String = ""): ServerConfig =
        ServerConfig(id = "$addr:$port", name = "s", address = addr, port = port, rawConfig = raw)

    /** Server that accepts TCP connections but does NOT speak TLS. */
    private fun plainListener(): Pair<ServerSocket, Thread> {
        val listener = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val thread = Thread {
            runCatching {
                while (true) listener.accept().use { it.getInputStream().read() }
            }
        }
        thread.isDaemon = true
        thread.start()
        return listener to thread
    }

    // ------------------------------------------------------------------
    // Stage 1 — TCP fast ping
    // ------------------------------------------------------------------

    @Test
    fun `fastProbe measures RTT against live listener`() = runBlocking {
        val (listener, _) = plainListener()
        try {
            val r = prober.fastProbe(server("127.0.0.1", listener.localPort), timeoutMs = 2000)
            assertTrue("tcpOk should be true for a live listener", r.tcpOk)
            assertNotNull(r.tcpMs)
            assertTrue(r.tcpMs!! >= 0)
        } finally {
            listener.close()
        }
    }

    @Test
    fun `fastProbe fails on closed port`() = runBlocking {
        val s = ServerSocket(0)
        val port = s.localPort
        s.close()
        val r = prober.fastProbe(server("127.0.0.1", port), timeoutMs = 1500)
        assertFalse(r.tcpOk)
        assertNull(r.tcpMs)
    }

    @Test
    fun `fastProbe fails on blank address`() = runBlocking {
        val r = prober.fastProbe(server("", 443), timeoutMs = 500)
        assertFalse(r.tcpOk)
    }

    // ------------------------------------------------------------------
    // Stage 2 — TLS handshake
    // ------------------------------------------------------------------

    @Test
    fun `tlsProbe fails against plain TCP server`() = runBlocking {
        val (listener, _) = plainListener()
        try {
            val cfg = server("127.0.0.1", listener.localPort)
            val stage1 = prober.fastProbe(cfg, timeoutMs = 2000)
            assertTrue(stage1.tcpOk)
            val merged = prober.tlsProbeAll(
                listOf(cfg),
                mapOf(cfg.id to stage1),
                tlsTimeoutMs = 1500
            )
            val r = merged[cfg.id]!!
            assertTrue(r.tcpOk)
            assertFalse("handshake must fail against a non-TLS server", r.tlsOk)
            assertEquals(CertStatus.NONE, r.certStatus)
        } finally {
            listener.close()
        }
    }

    // ------------------------------------------------------------------
    // Two-stage pipeline
    // ------------------------------------------------------------------

    @Test
    fun `probeAll runs stage 2 only for stage-1 passes`() = runBlocking {
        val (listener, _) = plainListener()
        val dead = ServerSocket(0)
        val deadPort = dead.localPort
        dead.close()
        try {
            val live = server("127.0.0.1", listener.localPort)
            val deadSrv = server("127.0.0.1", deadPort)
            val res = prober.probeAll(listOf(live, deadSrv), tlsTimeoutMs = 1500)

            assertEquals(2, res.size)
            val liveRes = res[live.id]!!
            assertTrue(liveRes.tcpOk)
            assertNotNull(liveRes.tcpMs)
            // TLS was attempted (stage 1 passed) but the plain listener rejects it
            assertFalse(liveRes.tlsOk)
            assertEquals(CertStatus.NONE, liveRes.certStatus)
            assertFalse(liveRes.healthy)

            val deadRes = res[deadSrv.id]!!
            assertFalse(deadRes.tcpOk)
            assertFalse(deadRes.tlsOk)
            assertEquals(CertStatus.NONE, deadRes.certStatus)
        } finally {
            listener.close()
        }
    }

    // ------------------------------------------------------------------
    // Certificate classification
    // ------------------------------------------------------------------

    @Test
    fun `classifyCert marks chain valid when trust manager accepts`() {
        val tm = mockk<X509TrustManager>()
        every { tm.checkServerTrusted(any(), any()) } returns Unit
        val cert = mockk<X509Certificate>()
        every { cert.publicKey.algorithm } returns "RSA"
        assertEquals(CertStatus.VALID, prober.classifyCert(listOf(cert), tm))
    }

    @Test
    fun `classifyCert detects self-signed when subject equals issuer`() {
        val tm = mockk<X509TrustManager>()
        every { tm.checkServerTrusted(any(), any()) } throws CertificateException("no trust")
        val cert = mockk<X509Certificate>()
        every { cert.publicKey.algorithm } returns "RSA"
        val principal = mockk<X500Principal>()
        every { cert.subjectX500Principal } returns principal
        every { cert.issuerX500Principal } returns principal
        assertEquals(CertStatus.SELF_SIGNED, prober.classifyCert(listOf(cert), tm))
    }

    @Test
    fun `classifyCert reports invalid chain when not self-signed`() {
        val tm = mockk<X509TrustManager>()
        every { tm.checkServerTrusted(any(), any()) } throws CertificateException("no trust")
        val cert = mockk<X509Certificate>()
        every { cert.publicKey.algorithm } returns "RSA"
        every { cert.subjectX500Principal } returns mockk<X500Principal>()
        every { cert.issuerX500Principal } returns mockk<X500Principal>()
        assertEquals(CertStatus.INVALID_CHAIN, prober.classifyCert(listOf(cert), tm))
    }

    @Test
    fun `classifyCert empty chain is NONE`() {
        assertEquals(CertStatus.NONE, prober.classifyCert(emptyList(), mockk()))
    }

    // ------------------------------------------------------------------
    // SNI extraction
    // ------------------------------------------------------------------

    @Test
    fun `extractServerName from xray json tls settings`() {
        val raw = """{"outbounds":[{"streamSettings":{"tlsSettings":{"serverName":"cdn.example.com"}}}]}"""
        assertEquals("cdn.example.com", prober.extractServerName(raw, "1.2.3.4"))
    }

    @Test
    fun `extractServerName from reality settings uses first server name`() {
        val raw = """{"outbounds":[{"streamSettings":{"realitySettings":{"serverNames":["impersonate.com","x.com"]}}}]}"""
        assertEquals("impersonate.com", prober.extractServerName(raw, "1.2.3.4"))
    }

    @Test
    fun `extractServerName falls back to address on garbage config`() {
        assertEquals("1.2.3.4", prober.extractServerName("not json at all {", "1.2.3.4"))
        assertEquals("1.2.3.4", prober.extractServerName("", "1.2.3.4"))
    }

    @Test
    fun `extractServerName from vless uri param`() {
        val uri = "vless://uuid@1.2.3.4:443?security=tls&sni=my-sni.example.com#x"
        assertEquals("my-sni.example.com", prober.extractServerName(uri, "1.2.3.4"))
    }

    @Test
    fun `extractServerName from ws config without tls falls back`() {
        val raw = """{"outbounds":[{"streamSettings":{"network":"ws","wsSettings":{"path":"/"}}}]}"""
        assertEquals("1.2.3.4", prober.extractServerName(raw, "1.2.3.4"))
    }
}
