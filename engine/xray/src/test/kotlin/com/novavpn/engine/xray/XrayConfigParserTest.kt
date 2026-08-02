package com.novavpn.engine.xray

import com.novavpn.domain.model.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Regression tests for [XrayConfigParser].
 * Ensures config builders produce valid Xray JSON with required fields.
 */
class XrayConfigParserTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val dns = listOf("8.8.8.8")
    private val routes = listOf("0.0.0.0/0")

    private fun parseObj(text: String) = json.parseToJsonElement(text).jsonObject
    private fun proxyOutbound(root: JsonObject): JsonObject =
        root["outbounds"]!!.jsonArray!![0]!!.jsonObject

    private fun vlessUser(root: JsonObject): JsonObject {
        val vnext = proxyOutbound(root)["settings"]!!.jsonObject!!["vnext"]!!.jsonArray!!
        val usersObj = vnext[0]!!.jsonObject["users"]!!.jsonArray!!
        return usersObj[0]!!.jsonObject
    }

    private fun trojanServer(root: JsonObject): JsonObject {
        val servers = proxyOutbound(root)["settings"]!!.jsonObject!!["servers"]!!.jsonArray!!
        return servers[0]!!.jsonObject
    }

    private fun ssServer(root: JsonObject): JsonObject {
        val servers = proxyOutbound(root)["settings"]!!.jsonObject!!["servers"]!!.jsonArray!!
        return servers[0]!!.jsonObject
    }

    // ------------------------------------------------------------------
    // 1. VLESS config builder
    // ------------------------------------------------------------------

    @Test
    fun `VLESS config with JSON rawConfig has id in users`() {
        val root = gen(Protocol.VLESS, """{"id":"uuid-vless-1234","encryption":"none","flow":"xtls-rprx-vision"}""")
        assertEquals("vless", proxyOutbound(root)["protocol"]!!.jsonPrimitive.content)
        assertEquals("uuid-vless-1234", vlessUser(root)["id"]!!.jsonPrimitive.content)
        assertEquals("xtls-rprx-vision", vlessUser(root)["flow"]!!.jsonPrimitive.content)
    }

    @Test
    fun `VLESS with legacy vless URL fallback extracts id`() {
        val root = gen(Protocol.VLESS,
            "vless://uuid-legacy@old.example.com:443?encryption=none&flow=xtls-rprx-vision")
        assertEquals("uuid-legacy", vlessUser(root)["id"]!!.jsonPrimitive.content)
    }

    // ------------------------------------------------------------------
    // 2. VMess config builder
    // ------------------------------------------------------------------

    @Test
    fun `VMess config with JSON rawConfig has UUID in vnext users`() {
        val root = gen(Protocol.VMess,
            """{"add":"vmess.example.com","port":8443,"id":"uuid-vmess-5678","aid":0,"net":"ws","tls":"tls"}""")
        assertEquals("vmess", proxyOutbound(root)["protocol"]!!.jsonPrimitive.content)
        val vnextArr = proxyOutbound(root)["settings"]!!.jsonObject!!["vnext"]!!.jsonArray!!
        val user = vnextArr[0]!!.jsonObject["users"]!!.jsonArray!![0]!!.jsonObject
        assertEquals("uuid-vmess-5678", user["id"]!!.jsonPrimitive.content)
    }

    // ------------------------------------------------------------------
    // 3. Trojan config builder
    // ------------------------------------------------------------------

    @Test
    fun `Trojan config with JSON rawConfig has password in servers`() {
        val root = gen(Protocol.Trojan, """{"password":"trojan-pass","sni":"t.example.com"}""")
        assertEquals("trojan", proxyOutbound(root)["protocol"]!!.jsonPrimitive.content)
        assertEquals("trojan-pass", trojanServer(root)["password"]!!.jsonPrimitive.content)
    }

    @Test
    fun `Trojan with legacy trojan URL fallback extracts password`() {
        val root = gen(Protocol.Trojan, "trojan://legacy-pass@old-trojan.example.com:443")
        assertEquals("legacy-pass", trojanServer(root)["password"]!!.jsonPrimitive.content)
    }

    @Test
    fun `TLS WS outbound pins panel chain and omits removed allowInsecure`() {
        val config = ServerConfig(
            name = "Worker",
            address = "104.17.148.22",
            port = 443,
            protocol = Protocol.Trojan,
            transport = Transport.WebSocket,
            security = Security.TLS,
            rawConfig = """{"serverName":"divine-morning-53a7.nahan-1-tarkibi.workers.dev","path":"/divooneop","host":"divine-morning-53a7.nahan-1-tarkibi.workers.dev","fingerprint":"chrome","type":"ws"}""",
            engineFormat = EngineFormat.XrayJson
        )
        val root = parseObj(XrayConfigParser.toXrayJson(config, dns, routes))
        val tls = proxyOutbound(root)["streamSettings"]!!.jsonObject["tlsSettings"]!!.jsonObject
        // Xray >= 26 rejects allowInsecure (exit 23); pinnedPeerCertSha256 is the replacement.
        assertFalse("allowInsecure must not be emitted (removed in Xray 26)",
            tls.containsKey("allowInsecure"))
        val pin = tls["pinnedPeerCertSha256"]!!.jsonPrimitive.content
        assertTrue("GTS WE1 intermediate pinned", pin.startsWith(
            "1dfc1605fbad358d8bc844f76d15203fac9ca5c1a79fd4857ffaf2864fbebf96"))
        assertTrue("GTS Root R4 pinned", pin.contains(
            "76b27b80a58027dc3cf1da68dac17010ed93997d0b603e2fadbe85012493b5a7"))
        assertEquals("serverName preserved",
            "divine-morning-53a7.nahan-1-tarkibi.workers.dev",
            tls["serverName"]!!.jsonPrimitive.content)
    }

    @Test
    fun `streamSettings includes TCP keepalive sockopt`() {
        val config = ServerConfig(
            name = "KeepAlive",
            address = "104.17.148.22",
            port = 443,
            protocol = Protocol.Trojan,
            transport = Transport.WebSocket,
            security = Security.TLS,
            rawConfig = """{"serverName":"worker.example.com","path":"/ws","host":"worker.example.com"}""",
            engineFormat = EngineFormat.XrayJson
        )
        val root = parseObj(XrayConfigParser.toXrayJson(config, dns, routes))
        val sockopt = proxyOutbound(root)["streamSettings"]!!.jsonObject["sockopt"]!!.jsonObject
        // Client-side TCP keepalive: mitigates Cloudflare/middlebox idle drops
        // that surface as "websocket: close 1005 (no status)".
        assertEquals(15, sockopt["tcpKeepAliveInterval"]!!.jsonPrimitive.int)
        assertEquals(60, sockopt["tcpKeepAliveIdle"]!!.jsonPrimitive.int)
    }

    // ------------------------------------------------------------------
    // 4. Shadowsocks config builder
    // ------------------------------------------------------------------

    @Test
    fun `Shadowsocks config with JSON rawConfig has method and password`() {
        val root = gen(Protocol.Shadowsocks,
            """{"method":"chacha20-ietf-poly1305","password":"ss-pass","plugin":"obfs-local"}""")
        assertEquals("shadowsocks", proxyOutbound(root)["protocol"]!!.jsonPrimitive.content)
        assertEquals("chacha20-ietf-poly1305", ssServer(root)["method"]!!.jsonPrimitive.content)
        assertEquals("ss-pass", ssServer(root)["password"]!!.jsonPrimitive.content)
        assertEquals("obfs-local", ssServer(root)["plugin"]!!.jsonPrimitive.content)
    }

    @Test
    fun `Shadowsocks with legacy ss URL fallback extracts method and password`() {
        val creds = java.util.Base64.getEncoder().encodeToString("aes-256-gcm:legacy-ss-pass".toByteArray())
        val rawUrl = "ss://$creds@ss.example.com:443"
        val root = gen(Protocol.Shadowsocks, rawUrl)
        assertEquals("aes-256-gcm", ssServer(root)["method"]!!.jsonPrimitive.content)
        assertEquals("legacy-ss-pass", ssServer(root)["password"]!!.jsonPrimitive.content)
    }

    // ------------------------------------------------------------------
    // 5. Shared config structure (SOCKS5 architecture — TUN is handled
    //    by hev-socks5-tunnel bridge, NOT Xray)
    // ------------------------------------------------------------------

    @Test
    fun `generated config has SOCKS5 inbound on 127-0-0-1 10808`() {
        val root = gen(Protocol.VLESS, """{"id":"x","encryption":"none"}""")
        val inbounds = root["inbounds"]!!.jsonArray!!
        val socksInbound = inbounds[0]!!.jsonObject
        assertEquals("socks", socksInbound["protocol"]!!.jsonPrimitive.content)
        assertEquals("127.0.0.1", socksInbound["listen"]!!.jsonPrimitive.content)
        assertEquals(10808, socksInbound["port"]!!.jsonPrimitive.content.toInt())
        assertEquals("socks-in", socksInbound["tag"]!!.jsonPrimitive.content)
    }

    @Test
    fun `generated config has HTTP inbound as fallback`() {
        val root = gen(Protocol.VLESS, """{"id":"x","encryption":"none"}""")
        val inbounds = root["inbounds"]!!.jsonArray!!
        val httpInbound = inbounds[1]!!.jsonObject
        assertEquals("http", httpInbound["protocol"]!!.jsonPrimitive.content)
        assertEquals("127.0.0.1", httpInbound["listen"]!!.jsonPrimitive.content)
        assertEquals(10809, httpInbound["port"]!!.jsonPrimitive.content.toInt())
        assertEquals("http-in", httpInbound["tag"]!!.jsonPrimitive.content)
    }

    @Test
    fun `generated config has DNS section with servers`() {
        val root = gen(Protocol.VLESS, """{"id":"x","encryption":"none"}""")
        val dnsConfig = root["dns"]!!.jsonObject!!
        assertTrue("DNS servers must be present", dnsConfig["servers"]!!.jsonArray!!.isNotEmpty())
    }

    @Test
    fun `generated config has proxy, direct and block outbounds`() {
        val root = gen(Protocol.VLESS, """{"id":"x","encryption":"none"}""")
        val outbounds = root["outbounds"]!!.jsonArray!!
        // Collect tags
        val tags = (0 until outbounds.size).map { outbounds[it]!!.jsonObject["tag"]!!.jsonPrimitive.content }
        assertTrue("proxy outbound", tags.contains("proxy"))
        assertTrue("direct outbound", tags.contains("direct"))
        assertTrue("block outbound", tags.contains("block"))
    }

    @Test
    fun `full config matches SOCKS5 architecture expected by hev-socks5-tunnel`() {
        // This test validates the complete config structure against the
        // hev-socks5-tunnel architecture:
        //   TUN fd → hev-socks5-tunnel → Xray SOCKS5(:10808) → outbound
        //
        // Xray should NOT have a TUN inbound (TUN is handled by bridge).
        // Xray should have SOCKS5 + HTTP inbounds for the bridge to forward to.
        val root = gen(Protocol.VLESS, """{"id":"x","encryption":"none"}""")

        // --- inbounds ---
        val inbounds = root["inbounds"]!!.jsonArray!!
        assertEquals("2 inbounds (SOCKS5 + HTTP)", 2, inbounds.size)

        val socksIn = inbounds[0]!!.jsonObject
        assertEquals("socks", socksIn["protocol"]!!.jsonPrimitive.content)
        assertEquals("127.0.0.1", socksIn["listen"]!!.jsonPrimitive.content)
        assertEquals(10808, socksIn["port"]!!.jsonPrimitive.content.toInt())
        assertEquals("socks-in", socksIn["tag"]!!.jsonPrimitive.content)
        assertTrue("SOCKS5 must have noauth", socksIn["settings"]!!.jsonObject["auth"]!!.jsonPrimitive.content == "noauth")
        assertTrue("SOCKS5 must support UDP", socksIn["settings"]!!.jsonObject["udp"]!!.jsonPrimitive.content == "true")

        val httpIn = inbounds[1]!!.jsonObject
        assertEquals("http", httpIn["protocol"]!!.jsonPrimitive.content)
        assertEquals("127.0.0.1", httpIn["listen"]!!.jsonPrimitive.content)
        assertEquals(10809, httpIn["port"]!!.jsonPrimitive.content.toInt())
        assertEquals("http-in", httpIn["tag"]!!.jsonPrimitive.content)

        // No TUN inbound
        val protocols = inbounds.map { it.jsonObject["protocol"]!!.jsonPrimitive.content }
        assertFalse("TUN inbound must NOT be in Xray config", protocols.contains("tun"))

        // --- outbounds ---
        val outbounds = root["outbounds"]!!.jsonArray!!
        val tags = outbounds.map { it.jsonObject["tag"]!!.jsonPrimitive.content }
        assertTrue(tags.contains("proxy"))
        assertTrue(tags.contains("direct"))
        assertTrue(tags.contains("block"))

        // --- routing ---
        val rules = root["routing"]!!.jsonObject["rules"]!!.jsonArray!!
        val inboundTags = rules.flatMap { rule ->
            val tagsArr = rule.jsonObject["inboundTag"]?.jsonArray ?: return@flatMap emptyList()
            tagsArr.map { it.jsonPrimitive.content }
        }
        assertTrue(inboundTags.contains("socks-in"))
        assertTrue(inboundTags.contains("http-in"))
        assertFalse(inboundTags.contains("tun-in"))

        // --- dns ---
        val dnsServers = root["dns"]!!.jsonObject["servers"]!!.jsonArray!!
        assertTrue(dnsServers.isNotEmpty())
    }

    // ------------------------------------------------------------------
    // QUIC block (Smart Routing) regression tests
    // ------------------------------------------------------------------

    @Test
    fun `blockQuic injects sniffing with quic destOverride and a block rule first`() {
        val root = gen(Protocol.VLESS, """{"id":"x","encryption":"none"}""", blockQuic = true)

        // Inbound sniffing on both inbounds
        val inbounds = root["inbounds"]!!.jsonArray!!
        inbounds.forEach { inbound ->
            val sniffing = inbound.jsonObject["sniffing"]!!.jsonObject
            assertTrue("sniffing enabled", sniffing["enabled"]!!.jsonPrimitive.content == "true")
            val destOverride = sniffing["destOverride"]!!.jsonArray!!.map { it.jsonPrimitive.content }
            assertTrue("quic sniffing", destOverride.contains("quic"))
            assertTrue("tls sniffing", destOverride.contains("tls"))
            assertTrue("http sniffing", destOverride.contains("http"))
        }

        // Routing: QUIC -> block must be the FIRST rule
        val rules = root["routing"]!!.jsonObject["rules"]!!.jsonArray!!
        val first = rules[0]!!.jsonObject
        val protocols = first["protocol"]!!.jsonArray!!.map { it.jsonPrimitive.content }
        assertEquals(listOf("quic"), protocols)
        assertEquals("block", first["outboundTag"]!!.jsonPrimitive.content)
    }

    @Test
    fun `default config has no sniffing and no quic rule`() {
        val root = gen(Protocol.VLESS, """{"id":"x","encryption":"none"}""")
        val inbounds = root["inbounds"]!!.jsonArray!!
        inbounds.forEach { inbound ->
            assertNull("no sniffing by default", inbound.jsonObject["sniffing"])
        }
        val rules = root["routing"]!!.jsonObject["rules"]!!.jsonArray!!
        rules.forEach { rule ->
            assertNull("no quic protocol rule", rule.jsonObject["protocol"])
        }
    }

    // ------------------------------------------------------------------
    // TLS fragmentation (Patterniha method) regression tests
    // ------------------------------------------------------------------

    @Test
    fun `fragmentTls injects fragment-out freedom outbound with tlshello fragment`() {
        val root = gen(Protocol.VLESS, """{"id":"x","encryption":"none"}""", fragmentTls = true)

        val outbounds = root["outbounds"]!!.jsonArray!!
        val fragment = outbounds.first { it.jsonObject["tag"]!!.jsonPrimitive.content == "fragment-out" }.jsonObject

        assertEquals("freedom", fragment["protocol"]!!.jsonPrimitive.content)
        val fragmentSettings = fragment["settings"]!!.jsonObject["fragment"]!!.jsonObject
        assertEquals("tlshello", fragmentSettings["packets"]!!.jsonPrimitive.content)
        // Xray 26 Int32Range format — the classic arrays (lengths/delays) are rejected
        assertEquals("5-94", fragmentSettings["length"]!!.jsonPrimitive.content)
        assertEquals("1-1", fragmentSettings["interval"]!!.jsonPrimitive.content)
        assertEquals("0-0", fragmentSettings["maxSplit"]!!.jsonPrimitive.content)
    }

    @Test
    fun `fragmentTls routes proxy through dialerProxy and blinds tls fingerprint`() {
        val root = gen(Protocol.Trojan, """{"id":"x","password":"x"}""", fragmentTls = true)

        val outbounds = root["outbounds"]!!.jsonArray!!
        val proxy = outbounds.first { it.jsonObject["tag"]!!.jsonPrimitive.content == "proxy" }.jsonObject

        // dialerProxy inside the existing keepalive sockopt
        val sockopt = proxy["streamSettings"]!!.jsonObject["sockopt"]!!.jsonObject
        assertEquals("fragment-out", sockopt["dialerProxy"]!!.jsonPrimitive.content)
        assertEquals("60", sockopt["tcpKeepAliveIdle"]!!.jsonPrimitive.content)

        // uTLS blind: random fingerprint + explicit cipher suites
        val tls = proxy["streamSettings"]!!.jsonObject["tlsSettings"]!!.jsonObject
        assertEquals("random", tls["fingerprint"]!!.jsonPrimitive.content)
        val suites = tls["cipherSuites"]!!.jsonPrimitive.content
        assertTrue("has TLS 1.3 suites", suites.contains("TLS_AES_256_GCM_SHA384"))
        assertTrue("has TLS 1.2 suites", suites.contains("TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384"))
        assertTrue("pins kept", tls.containsKey("pinnedPeerCertSha256"))
    }

    @Test
    fun `default config has no fragment outbound and no dialerProxy`() {
        val root = gen(Protocol.Trojan, """{"id":"x","password":"x"}""")

        val outbounds = root["outbounds"]!!.jsonArray!!
        assertTrue(
            "no fragment-out by default",
            outbounds.none { it.jsonObject["tag"]!!.jsonPrimitive.content == "fragment-out" }
        )
        val proxy = outbounds.first { it.jsonObject["tag"]!!.jsonPrimitive.content == "proxy" }.jsonObject
        val sockopt = proxy["streamSettings"]!!.jsonObject["sockopt"]!!.jsonObject
        assertNull("no dialerProxy by default", sockopt["dialerProxy"])
        val tls = proxy["streamSettings"]!!.jsonObject["tlsSettings"]!!.jsonObject
        assertEquals("chrome", tls["fingerprint"]!!.jsonPrimitive.content)
        assertNull("no cipherSuites by default", tls["cipherSuites"])
    }

    @Test
    fun `QUIC transport never fragments even with fragment enabled`() {
        // Regression guard for the TLS-fragment corruption: QUIC (and UDP
        // h3) must stay pristine — the fragment-out only ever applies to
        // TCP/TLS ClientHellos, never to QUIC transport.
        val config = ServerConfig(
            name = "Test", address = "test.example.com", port = 443,
            protocol = Protocol.VLESS, transport = Transport.QUIC,
            security = Security.None,
            rawConfig = "\"\"\"{\"id\":\"x\",\"encryption\":\"none\"}\"\"\"",
            engineFormat = EngineFormat.XrayJson
        )
        val root = parseObj(XrayConfigParser.toXrayJson(config, dns, routes, fragmentTls = true))
        val outbounds = root["outbounds"]!!.jsonArray!!
        assertTrue("no fragment-out for QUIC", outbounds.none { it.jsonObject["tag"]?.jsonPrimitive?.content == "fragment-out" })
        val proxy = outbounds.first { it.jsonObject["tag"]!!.jsonPrimitive.content == "proxy" }.jsonObject
        val sockopt = proxy["streamSettings"]!!.jsonObject["sockopt"]!!.jsonObject
        assertNull("no dialerProxy for QUIC", sockopt["dialerProxy"])
    }

    @Test
    fun `Reality server never emits fragment outbound`() {
        // Reality is transport-level security inside streamSettings; the
        // fragment knock (dialerProxy) MUST NOT be attached to it.
        val config = ServerConfig(
            name = "Test", address = "test.example.com", port = 443,
            protocol = Protocol.VLESS, transport = Transport.WebSocket,
            security = Security.Reality,
            rawConfig = """"{"id":"x","encryption":"none","flow":"","serverName":"test.example.com","publicKey":"ZWFkW1NpZ25l","shortId":"abcd","spiderX":""}""",
            engineFormat = EngineFormat.XrayJson
        )
        val root = parseObj(XrayConfigParser.toXrayJson(config, dns, routes, fragmentTls = true))
        val outbounds = root["outbounds"]!!.jsonArray!!
        assertTrue("no fragment-out for Reality", outbounds.none { o -> o.jsonObject["tag"]?.jsonPrimitive?.content == "fragment-out" })
        val proxy = outbounds.first { it.jsonObject["tag"]!!.jsonPrimitive.content == "proxy" }.jsonObject
        val sockopt = proxy["streamSettings"]!!.jsonObject["sockopt"]!!
        assertNull("no dialerProxy for Reality", sockopt.jsonObject["dialerProxy"])
    }

    @Test
    fun `keepAlive off omits tcp keepalive sockopt`() {
        val root = gen(Protocol.Trojan, """"{"id":"x","password":"x"}"""", keepAlive = false)
        val proxy = proxyOutbound(root)
        val sockopt = proxy["streamSettings"]!!.jsonObject["sockopt"]!!.jsonObject
        assertNull("no tcpKeepAliveIdle", sockopt["tcpKeepAliveIdle"])
        assertNull("no tcpKeepAliveInterval", sockopt["tcpKeepAliveInterval"])
    }

    @Test
    fun `fakeDns enables fake-IP pool and local dns routing`() {
        val root = gen(Protocol.VLESS, """""{"id":"x","encryption":"none"}"""", fakeDns = true)

        // dns section carries the fakeip pool + UseIP query strategy
        val dnsObj = root["dns"]!!.jsonObject
        assertTrue("UseIP query strategy", dnsObj["queryStrategy"]!!.jsonPrimitive.content == "UseIP")
        val fakeip = dnsObj["fakeip"]!!.jsonObject
        assertEquals("true", fakeip["enabled"]!!.jsonPrimitive.content)
        assertEquals("198.18.0.0/15", fakeip["ip4"]!!.jsonPrimitive.content)

        // dedicated dns-out freedom outbound exists
        val outbounds = root["outbounds"]!!.jsonArray!!
        assertTrue("dns-out present", outbounds.any { it.jsonObject["tag"]?.jsonPrimitive?.content == "dns-out" })

        // routing: port-53 → dns-out, fake-IP pool → proxy, IPIfNonMatch strategy
        val routing = root["routing"]!!.jsonObject
        assertEquals("IPIfNonMatch", routing["domainStrategy"]!!.jsonPrimitive.content)
        val rules = routing["rules"]!!.jsonArray!!
        assertTrue("port 53→dns-out", rules.any { r ->
            r.jsonObject["port"]?.jsonPrimitive?.content == "53" &&
                r.jsonObject["outboundTag"]?.jsonPrimitive?.content == "dns-out"
        })
    }

    @Test
    fun `fakeDns adds sniffing to inbounds for fake-IP remapping`() {
        val root = gen(Protocol.Trojan, """"{"id":"x","password":"x"}"""", fakeDns = true)
        val inbounds = root["inbounds"]!!.jsonArray!!
        val socks = inbounds.first { it.jsonObject["tag"]!!.jsonPrimitive.content == "socks-in" }.jsonObject
        val sniffing = socks["sniffing"]!!.jsonObject
        assertEquals("true", sniffing["enabled"]!!.jsonPrimitive.content)
        assertTrue("destOverride includes http", sniffing["destOverride"]!!.jsonArray.any { it.jsonPrimitive.content == "http" })
        assertTrue("destOverride includes tls", sniffing["destOverride"]!!.jsonArray.any { it.jsonPrimitive.content == "tls" })
    }

    // ------------------------------------------------------------------
    // Helper: generate full Xray config and parse it
    // ------------------------------------------------------------------

    private fun gen(
        proto: Protocol,
        rawConfig: String,
        blockQuic: Boolean = false,
        fragmentTls: Boolean = false,
        keepAlive: Boolean = true,
        fakeDns: Boolean = false
    ): JsonObject {
        val config = ServerConfig(
            name = "Test",
            address = "test.example.com",
            port = 443,
            protocol = proto,
            transport = Transport.TCP,
            security = if (proto == Protocol.Trojan) Security.TLS else Security.None,
            rawConfig = rawConfig,
            engineFormat = EngineFormat.XrayJson
        )
        val jsonStr = XrayConfigParser.toXrayJson(
            config, dns, routes,
            blockQuic = blockQuic, fragmentTls = fragmentTls,
            keepAlive = keepAlive, fakeDns = fakeDns
        )
        return parseObj(jsonStr)
    }
}
