package com.novavpn.engine.xray

import com.novavpn.domain.model.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Regression tests for [XrayConfigParser] — ensures that given a valid
 * [ServerConfig] with JSON rawConfig, the generated Xray JSON config
 * contains all required fields.
 *
 * Tests also cover legacy URL fallbacks so existing database entries
 * continue to work after subscription refresh.
 */
class XrayConfigParserTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val dummyTunFd = 42
    private val dns = listOf("8.8.8.8")
    private val routes = listOf("0.0.0.0/0")

    // ------------------------------------------------------------------
    // 1. VLESS config builder
    // ------------------------------------------------------------------

    @Test
    fun `VLESS config with JSON rawConfig produces valid outbound`() {
        val rawConfig = """{"id":"a6b4c8d0-e1f2-3a4b-5c6d-7e8f9a0b1c2d","encryption":"none","flow":"xtls-rprx-vision","sni":"example.com","fingerprint":"chrome"}"""
        val config = ServerConfig(
            name = "VLESS Test",
            address = "server.example.com",
            port = 443,
            protocol = Protocol.VLESS,
            transport = Transport.TCP,
            security = Security.TLS,
            rawConfig = rawConfig,
            engineFormat = EngineFormat.XrayJson
        )
        val xrayJson = XrayConfigParser.toXrayJson(config, dummyTunFd, dns, routes)
        val root = parseJson(xrayJson)
        val outbounds = root["outbounds"]?.jsonArray ?: fail("outbounds missing")
        val proxy = outbounds[0].jsonObject

        assertEquals("vless", proxy["protocol"]?.jsonPrimitive?.content)
        val settings = proxy["settings"]?.jsonObject
        val vnext = settings?.get("vnext")?.jsonArray
        assertNotNull("vnext must exist", vnext)
        val user = vnext!![0].jsonObject["users"]?.jsonArray?.get(0)?.jsonObject
        assertNotNull("users[0] must exist", user)
        assertEquals("a6b4c8d0-e1f2-3a4b-5c6d-7e8f9a0b1c2d", user!!["id"]?.jsonPrimitive?.content)
        assertEquals("xtls-rprx-vision", user["flow"]?.jsonPrimitive?.content)

        // streamSettings must have tls
        val stream = proxy["streamSettings"]?.jsonObject
        assertNotNull("streamSettings must exist", stream)
        assertEquals("tls", stream!!["security"]?.jsonPrimitive?.content)
    }

    @Test
    fun `VLESS config with legacy vless URL fallback extracts id`() {
        val config = ServerConfig(
            name = "Legacy VLESS",
            address = "old.example.com",
            port = 443,
            protocol = Protocol.VLESS,
            transport = Transport.TCP,
            security = Security.TLS,
            rawConfig = "vless://a6b4c8d0-e1f2-3a4b-5c6d-7e8f9a0b1c2d@old.example.com:443?encryption=none&flow=xtls-rprx-vision",
            engineFormat = EngineFormat.XrayJson
        )
        val xrayJson = XrayConfigParser.toXrayJson(config, dummyTunFd, dns, routes)
        val root = parseJson(xrayJson)
        val outbound = root["outbounds"]?.jsonArray?.get(0)?.jsonObject ?: fail("outbound missing")
        val user = outbound["settings"]?.jsonObject?.get("vnext")?.jsonArray
            ?.get(0)?.jsonObject?.get("users")?.jsonArray?.get(0)?.jsonObject
        assertNotNull("user must exist", user)
        assertEquals("a6b4c8d0-e1f2-3a4b-5c6d-7e8f9a0b1c2d", user!!["id"]?.jsonPrimitive?.content)
    }

    // ------------------------------------------------------------------
    // 2. VMess config builder
    // ------------------------------------------------------------------

    @Test
    fun `VMess config with JSON rawConfig produces valid UUID`() {
        val rawConfig = """{"add":"vmess.example.com","port":8443,"id":"uuid-vmess-1234","aid":0,"net":"ws","type":"none","tls":"tls","path":"/vmess","host":"vmess.example.com"}"""
        val config = ServerConfig(
            name = "VMess Test",
            address = "vmess.example.com",
            port = 8443,
            protocol = Protocol.VMess,
            transport = Transport.WebSocket,
            security = Security.TLS,
            rawConfig = rawConfig,
            engineFormat = EngineFormat.XrayJson
        )
        val xrayJson = XrayConfigParser.toXrayJson(config, dummyTunFd, dns, routes)
        val root = parseJson(xrayJson)
        val outbound = root["outbounds"]?.jsonArray?.get(0)?.jsonObject ?: fail("outbound missing")

        assertEquals("vmess", outbound["protocol"]?.jsonPrimitive?.content)
        val vnext = outbound["settings"]?.jsonObject?.get("vnext")?.jsonArray
        val user = vnext?.get(0)?.jsonObject?.get("users")?.jsonArray?.get(0)?.jsonObject
        assertNotNull("user must exist", user)
        assertEquals("uuid-vmess-1234", user!!["id"]?.jsonPrimitive?.content)
    }

    // ------------------------------------------------------------------
    // 3. Trojan config builder
    // ------------------------------------------------------------------

    @Test
    fun `Trojan config with JSON rawConfig produces valid password`() {
        val rawConfig = """{"password":"trojan-pass-123","flow":"","sni":"trojan.example.com","fingerprint":"chrome"}"""
        val config = ServerConfig(
            name = "Trojan Test",
            address = "trojan.example.com",
            port = 443,
            protocol = Protocol.Trojan,
            transport = Transport.TCP,
            security = Security.TLS,
            rawConfig = rawConfig,
            engineFormat = EngineFormat.XrayJson
        )
        val xrayJson = XrayConfigParser.toXrayJson(config, dummyTunFd, dns, routes)
        val root = parseJson(xrayJson)
        val outbound = root["outbounds"]?.jsonArray?.get(0)?.jsonObject ?: fail("outbound missing")

        assertEquals("trojan", outbound["protocol"]?.jsonPrimitive?.content)
        val server = outbound["settings"]?.jsonObject?.get("servers")?.jsonArray?.get(0)?.jsonObject
        assertNotNull("server must exist", server)
        assertEquals("trojan-pass-123", server!!["password"]?.jsonPrimitive?.content)
    }

    @Test
    fun `Trojan config with legacy trojan URL fallback extracts password`() {
        val config = ServerConfig(
            name = "Legacy Trojan",
            address = "old-trojan.example.com",
            port = 443,
            protocol = Protocol.Trojan,
            transport = Transport.TCP,
            security = Security.TLS,
            rawConfig = "trojan://legacy-password@old-trojan.example.com:443?security=tls",
            engineFormat = EngineFormat.XrayJson
        )
        val xrayJson = XrayConfigParser.toXrayJson(config, dummyTunFd, dns, routes)
        val root = parseJson(xrayJson)
        val server = root["outbounds"]?.jsonArray?.get(0)?.jsonObject
            ?.get("settings")?.jsonObject?.get("servers")?.jsonArray?.get(0)?.jsonObject
        assertNotNull("server must exist", server)
        assertEquals("legacy-password", server!!["password"]?.jsonPrimitive?.content)
    }

    // ------------------------------------------------------------------
    // 4. Shadowsocks config builder
    // ------------------------------------------------------------------

    @Test
    fun `Shadowsocks config with JSON rawConfig produces method and password`() {
        val rawConfig = """{"method":"chacha20-ietf-poly1305","password":"ss-password","plugin":"obfs-local","plugin_opts":"obfs=http"}"""
        val config = ServerConfig(
            name = "SS Test",
            address = "ss.example.com",
            port = 8443,
            protocol = Protocol.Shadowsocks,
            transport = Transport.TCP,
            security = Security.None,
            rawConfig = rawConfig,
            engineFormat = EngineFormat.XrayJson
        )
        val xrayJson = XrayConfigParser.toXrayJson(config, dummyTunFd, dns, routes)
        val root = parseJson(xrayJson)
        val outbound = root["outbounds"]?.jsonArray?.get(0)?.jsonObject ?: fail("outbound missing")

        assertEquals("shadowsocks", outbound["protocol"]?.jsonPrimitive?.content)
        val server = outbound["settings"]?.jsonObject?.get("servers")?.jsonArray?.get(0)?.jsonObject
        assertNotNull("server must exist", server)
        assertEquals("chacha20-ietf-poly1305", server!!["method"]?.jsonPrimitive?.content)
        assertEquals("ss-password", server["password"]?.jsonPrimitive?.content)
        assertEquals("obfs-local", server["plugin"]?.jsonPrimitive?.content)
    }

    @Test
    fun `Shadowsocks config with legacy ss URL fallback works`() {
        val creds = java.util.Base64.getEncoder().encodeToString("aes-256-gcm:legacy-pass".toByteArray())
        val rawUrl = "ss://$creds@legacy-ss.example.com:443"
        val config = ServerConfig(
            name = "Legacy SS",
            address = "legacy-ss.example.com",
            port = 443,
            protocol = Protocol.Shadowsocks,
            transport = Transport.TCP,
            security = Security.None,
            rawConfig = rawUrl,
            engineFormat = EngineFormat.XrayJson
        )
        val xrayJson = XrayConfigParser.toXrayJson(config, dummyTunFd, dns, routes)
        val root = parseJson(xrayJson)
        val server = root["outbounds"]?.jsonArray?.get(0)?.jsonObject
            ?.get("settings")?.jsonObject?.get("servers")?.jsonArray?.get(0)?.jsonObject
        assertNotNull("server must exist", server)
        assertEquals("aes-256-gcm", server!!["method"]?.jsonPrimitive?.content)
        assertEquals("legacy-pass", server["password"]?.jsonPrimitive?.content)
    }

    // ------------------------------------------------------------------
    // 5. Shared config structure tests
    // ------------------------------------------------------------------

    @Test
    fun `generated config always has TUN inbound`() {
        val config = minimalConfig(Protocol.VLESS, """{"id":"uuid","encryption":"none"}""")
        val xrayJson = XrayConfigParser.toXrayJson(config, dummyTunFd, dns, routes)
        val root = parseJson(xrayJson)

        val inbounds = root["inbounds"]?.jsonArray
        assertNotNull("inbounds must exist", inbounds)
        val tunInbound = inbounds!!.find { it.jsonObject["protocol"]?.jsonPrimitive?.content == "tun" }
        assertNotNull("TUN inbound must exist", tunInbound)
        assertEquals(dummyTunFd, tunInbound.jsonObject["settings"]?.jsonObject?.get("fd")?.jsonPrimitive?.content?.toIntOrNull())
    }

    @Test
    fun `generated config has DNS section`() {
        val config = minimalConfig(Protocol.VLESS, """{"id":"uuid","encryption":"none"}""")
        val xrayJson = XrayConfigParser.toXrayJson(config, dummyTunFd, dns, routes)
        val root = parseJson(xrayJson)

        val dnsConfig = root["dns"]?.jsonObject
        assertNotNull("dns section must exist", dnsConfig)
        val servers = dnsConfig!!["servers"]?.jsonArray
        assertTrue("DNS servers must be present", servers?.isNotEmpty() == true)
    }

    @Test
    fun `generated config has direct and block outbounds`() {
        val config = minimalConfig(Protocol.VLESS, """{"id":"uuid","encryption":"none"}""")
        val xrayJson = XrayConfigParser.toXrayJson(config, dummyTunFd, dns, routes)
        val root = parseJson(xrayJson)

        val outbounds = root["outbounds"]?.jsonArray ?: fail("outbounds missing")
        val tags = outbounds.map { it.jsonObject["tag"]?.jsonPrimitive?.content }
        assertTrue("direct outbound should exist", tags.contains("direct"))
        assertTrue("block outbound should exist", tags.contains("block"))
        assertTrue("proxy outbound should exist", tags.contains("proxy"))
    }

    @Test
    fun `generated config routing routes tun-in to proxy`() {
        val config = minimalConfig(Protocol.VLESS, """{"id":"uuid","encryption":"none"}""")
        val xrayJson = XrayConfigParser.toXrayJson(config, dummyTunFd, dns, routes)
        val root = parseJson(xrayJson)

        val routing = root["routing"]?.jsonObject ?: fail("routing missing")
        val rules = routing["rules"]?.jsonArray ?: fail("routing rules missing")
        val tunRule = rules.find {
            it.jsonObject["inboundTag"]?.jsonArray?.any { tag ->
                tag.jsonPrimitive?.content == "tun-in"
            } == true
        }
        assertNotNull("Routing rule for tun-in must exist", tunRule)
        assertEquals("proxy", tunRule!!.jsonObject["outboundTag"]?.jsonPrimitive?.content)
    }

    // ------------------------------------------------------------------
    // Helper
    // ------------------------------------------------------------------

    private fun minimalConfig(protocol: Protocol, rawConfig: String) = ServerConfig(
        name = "Test",
        address = "test.example.com",
        port = 443,
        protocol = protocol,
        transport = Transport.TCP,
        security = Security.TLS,
        rawConfig = rawConfig,
        engineFormat = EngineFormat.XrayJson
    )

    private fun parseJson(text: String): JsonObject {
        return json.parseToJsonElement(text).jsonObject
    }
}
