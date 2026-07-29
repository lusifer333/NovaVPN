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
    private val dummyTunFd = 42
    private val dns = listOf("8.8.8.8")
    private val routes = listOf("0.0.0.0/0")

    private fun parseObj(text: String) = json.parseToJsonElement(text).jsonObject
    private fun proxyOutbound(root: JsonObject): JsonObject =
        root["outbounds"]!!.jsonArray!![0]!!.jsonObject

    private fun vlessUser(root: JsonObject): JsonObject {
        return proxyOutbound(root)["settings"]!!.jsonObject!!
            ["vnext"]!!.jsonArray!![0]!!.jsonObject
            ["users"]!!.jsonArray!![0]!!.jsonObject
    }

    private fun trojanServer(root: JsonObject): JsonObject =
        proxyOutbound(root)["settings"]!!.jsonObject!!["servers"]!!.jsonArray!![0]!!.jsonObject

    private fun ssServer(root: JsonObject): JsonObject =
        proxyOutbound(root)["settings"]!!.jsonObject!!["servers"]!!.jsonArray!![0]!!.jsonObject

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
        val user = proxyOutbound(root)["settings"]!!.jsonObject!!["vnext"]!!.jsonArray!![0]!!.jsonObject
            ["users"]!!.jsonArray!![0]!!.jsonObject
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
    // 5. Shared config structure
    // ------------------------------------------------------------------

    @Test
    fun `generated config has TUN inbound with correct fd`() {
        val root = gen(Protocol.VLESS, """{"id":"x","encryption":"none"}""")
        val inbounds = root["inbounds"]!!.jsonArray!!
        val tunInbound = inbounds[0]!!.jsonObject
        assertEquals("tun", tunInbound["protocol"]!!.jsonPrimitive.content)
        assertEquals(dummyTunFd, tunInbound["settings"]!!.jsonObject!!["fd"]!!.jsonPrimitive.content.toInt())
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
    fun `routing routes tun-in inbound to proxy outbound`() {
        val root = gen(Protocol.VLESS, """{"id":"x","encryption":"none"}""")
        val rules = root["routing"]!!.jsonObject!!["rules"]!!.jsonArray!!
        val tunInboundTags = (0 until rules.size).flatMap { ri ->
            val tags = rules[ri]!!.jsonObject["inboundTag"]?.jsonArray ?: return@flatMap emptyList()
            (0 until tags.size).map { tags[it]!!.jsonPrimitive.content }
        }
        assertTrue("tun-in inbound tag must exist", tunInboundTags.contains("tun-in"))
    }

    // ------------------------------------------------------------------
    // Helper: generate full Xray config and parse it
    // ------------------------------------------------------------------

    private fun gen(proto: Protocol, rawConfig: String): JsonObject {
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
        val jsonStr = XrayConfigParser.toXrayJson(config, dummyTunFd, dns, routes)
        return parseObj(jsonStr)
    }
}
