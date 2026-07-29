package com.novavpn.subscription.parser

import com.novavpn.domain.model.Protocol
import com.novavpn.domain.model.Security
import com.novavpn.domain.model.Transport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

/**
 * Regression tests for [SubscriptionParser] — ensures every protocol parser
 * produces valid JSON rawConfig that can be consumed by [XrayConfigParser]
 * and validated by [ConfigValidator].
 *
 * Any future change to a parser MUST NOT:
 * - Drop or corrupt fields in rawConfig
 * - Change rawConfig from JSON to a non-JSON format
 * - Lose the "id", "password", or "method" fields
 */
class SubscriptionParserTest {

    private val parser = SubscriptionParser
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Parse rawConfig string into a JsonObject. */
    private fun parseRaw(rawConfig: String) = try {
        json.parseToJsonElement(rawConfig).jsonObject
    } catch (_: Exception) { null }

    // ------------------------------------------------------------------
    // 1. VLESS parser
    // ------------------------------------------------------------------

    @Test
    fun `VLESS link produces JSON rawConfig with id`() {
        val link = "vless://a6b4c8d0-e1f2-3a4b-5c6d-7e8f9a0b1c2d@server.example.com:443?encryption=none&security=tls&type=tcp&flow=xtls-rprx-vision&sni=example.com&fp=chrome#MyServer"
        val config = parser.parseVlessLink(link)
        assertNotNull("VLESS link should parse", config)
        assertEquals("Protocol should be VLESS", Protocol.VLESS, config!!.protocol)
        assertEquals("server.example.com", config.address)
        assertEquals(443, config.port)
        assertEquals(Transport.TCP, config.transport)
        assertEquals(Security.TLS, config.security)

        val raw = parseRaw(config.rawConfig)
        assertNotNull("rawConfig must be valid JSON", raw)

        assertEquals("UUID should be preserved",
            "a6b4c8d0-e1f2-3a4b-5c6d-7e8f9a0b1c2d",
            raw["id"]?.jsonPrimitive?.content)
        assertEquals("flow should be preserved",
            "xtls-rprx-vision",
            raw["flow"]?.jsonPrimitive?.content)
        assertEquals("sni should be preserved",
            "example.com",
            raw["sni"]?.jsonPrimitive?.content)
        assertEquals("encryption should be preserved",
            "none",
            raw["encryption"]?.jsonPrimitive?.content)
    }

    @Test
    fun `VLESS Reality link preserves all Reality fields`() {
        val link = "vless://b6c4d8e0-f1a2-3b4c-5d6e-7f8a9b0c1d2e@reality.example.com:8443?encryption=none&security=reality&type=tcp&sni=www.google.com&fp=chrome&pbk=RealityPublicKeyHere&sid=1234abcd&spx=%2F#RealityServer"
        val config = parser.parseVlessLink(link)
        assertNotNull("VLESS Reality link should parse", config)
        assertEquals(Security.Reality, config!!.security)

        val raw = parseRaw(config.rawConfig) ?: fail("rawConfig must be valid JSON")
        assertEquals("b6c4d8e0-f1a2-3b4c-5d6e-7f8a9b0c1d2e", raw["id"]?.jsonPrimitive?.content)
        assertEquals("www.google.com", raw["sni"]?.jsonPrimitive?.content)
        assertEquals("chrome", raw["fingerprint"]?.jsonPrimitive?.content)
        assertEquals("RealityPublicKeyHere", raw["publicKey"]?.jsonPrimitive?.content)
        assertEquals("1234abcd", raw["shortId"]?.jsonPrimitive?.content)
    }

    @Test
    fun `VLESS with WebSocket transport preserves path and host`() {
        val link = "vless://uuid@ws.example.com:443?encryption=none&security=none&type=ws&path=%2Fvless&host=ws.example.com"
        val config = parser.parseVlessLink(link)
        assertNotNull(config)
        assertEquals(Transport.WebSocket, config!!.transport)

        val raw = parseRaw(config.rawConfig) ?: fail("rawConfig must be valid JSON")
        assertEquals("/vless", raw["path"]?.jsonPrimitive?.content)
        assertEquals("ws.example.com", raw["host"]?.jsonPrimitive?.content)
    }

    @Test
    fun `VLESS with gRPC preserves serviceName`() {
        val link = "vless://uuid@grpc.example.com:443?encryption=none&security=tls&type=grpc&serviceName=my-service"
        val config = parser.parseVlessLink(link)
        assertNotNull(config)
        assertEquals(Transport.gRPC, config!!.transport)

        val raw = parseRaw(config.rawConfig) ?: fail("rawConfig must be valid JSON")
        assertEquals("my-service", raw["serviceName"]?.jsonPrimitive?.content)
    }

    // ------------------------------------------------------------------
    // 2. VMess parser
    // ------------------------------------------------------------------

    @Test
    fun `VMess link decodes base64 and stores JSON rawConfig`() {
        val b64 = "eyJhZGQiOiJ2bWVzcy5leGFtcGxlLmNvbSIsInBvcnQiOjg0NDMsImlkIjoiYTZiNGM4ZDAtZTFmMi0zYTRiLTVjNmQtN2U4ZjlhMGIxYzJkIiwiYWlkIjowLCJuZXQiOiJ3cyIsInR5cGUiOiJub25lIiwidGxzIjoidGxzIiwicGF0aCI6Ii92bWVzcyIsImhvc3QiOiJ2bWVzcy5leGFtcGxlLmNvbSIsInBzIjoiVk1lc3NTZXJ2ZXIifQ=="
        val link = "vmess://$b64"
        val config = parser.parseVmessLink(link)
        assertNotNull("VMess link should parse", config)
        assertEquals(Protocol.VMess, config!!.protocol)
        assertEquals("vmess.example.com", config.address)
        assertEquals(8443, config.port)
        assertEquals(Transport.WebSocket, config.transport)
        assertEquals(Security.TLS, config.security)

        val raw = parseRaw(config.rawConfig)
        assertNotNull("rawConfig must be valid JSON", raw)

        assertEquals("a6b4c8d0-e1f2-3a4b-5c6d-7e8f9a0b1c2d", raw["id"]?.jsonPrimitive?.content)
        assertEquals("vmess.example.com", raw["add"]?.jsonPrimitive?.content)
        assertEquals("8443", raw["port"]?.jsonPrimitive?.content)
    }

    @Test
    fun `VMess with TCP transport parses correctly`() {
        val b64 = "eyJhZGQiOiJ0Y3AuZXhhbXBsZS5jb20iLCJwb3J0Ijo0NDMsImlkIjoidXVpZC0xMjM0IiwiYWlkIjowLCJuZXQiOiJ0Y3AiLCJ0eXBlIjoibm9uZSIsInRscyI6Im5vbmUifQ=="
        val link = "vmess://$b64"
        val config = parser.parseVmessLink(link)
        assertNotNull(config)
        assertEquals(Transport.TCP, config!!.transport)
        assertEquals(Security.None, config.security)
    }

    // ------------------------------------------------------------------
    // 3. Trojan parser
    // ------------------------------------------------------------------

    @Test
    fun `Trojan link produces JSON rawConfig with password`() {
        val link = "trojan://my-secret-password@trojan.example.com:443?security=tls&type=tcp&sni=trojan.example.com&fp=chrome#TrojanServer"
        val config = parser.parseTrojanLink(link)
        assertNotNull("Trojan link should parse", config)
        assertEquals(Protocol.Trojan, config!!.protocol)
        assertEquals("trojan.example.com", config.address)
        assertEquals(Security.TLS, config.security)

        val raw = parseRaw(config.rawConfig)
        assertNotNull("rawConfig must be valid JSON", raw)
        assertEquals("my-secret-password", raw["password"]?.jsonPrimitive?.content)
        assertEquals("trojan.example.com", raw["sni"]?.jsonPrimitive?.content)
    }

    @Test
    fun `Trojan with WebSocket preserves path and host`() {
        val link = "trojan://password@ws-trojan.example.com:443?security=tls&type=ws&path=%2Ftrojan&host=ws-trojan.example.com"
        val config = parser.parseTrojanLink(link)
        assertNotNull(config)
        assertEquals(Transport.WebSocket, config!!.transport)

        val raw = parseRaw(config.rawConfig) ?: fail("rawConfig must be valid JSON")
        assertEquals("/trojan", raw["path"]?.jsonPrimitive?.content)
        assertEquals("ws-trojan.example.com", raw["host"]?.jsonPrimitive?.content)
    }

    // ------------------------------------------------------------------
    // 4. Shadowsocks parser
    // ------------------------------------------------------------------

    @Test
    fun `Shadowsocks SIP002 link produces JSON rawConfig`() {
        val creds = java.util.Base64.getEncoder().encodeToString("chacha20-ietf-poly1305:password".toByteArray())
        val link = "ss://$creds@ss.example.com:8443#SSTest"
        val config = parser.parseShadowsocksLink(link)
        assertNotNull("Shadowsocks link should parse", config)
        assertEquals(Protocol.Shadowsocks, config!!.protocol)
        assertEquals("ss.example.com", config.address)

        val raw = parseRaw(config.rawConfig)
        assertNotNull("rawConfig must be valid JSON", raw)
        assertEquals("chacha20-ietf-poly1305", raw["method"]?.jsonPrimitive?.content)
        assertEquals("password", raw["password"]?.jsonPrimitive?.content)
    }

    @Test
    fun `Shadowsocks with plugin preserves plugin fields`() {
        val creds = java.util.Base64.getEncoder().encodeToString("aes-256-gcm:password".toByteArray())
        val link = "ss://$creds@plugin.example.com:443?plugin=obfs-local&pluginOpts=obfs%3Dhttp"
        val config = parser.parseShadowsocksLink(link)
        assertNotNull(config)

        val raw = parseRaw(config!!.rawConfig) ?: fail("rawConfig must be valid JSON")
        assertEquals("aes-256-gcm", raw["method"]?.jsonPrimitive?.content)
        assertEquals("password", raw["password"]?.jsonPrimitive?.content)
        assertEquals("obfs-local", raw["plugin"]?.jsonPrimitive?.content)
        assertEquals("obfs=http", raw["plugin_opts"]?.jsonPrimitive?.content)
    }

    @Test
    fun `Shadowsocks legacy format without plugin parses`() {
        val decoded = "aes-256-gcm:password@legacy.example.com:443"
        val b64 = java.util.Base64.getEncoder().encodeToString(decoded.toByteArray())
        val link = "ss://$b64"
        val config = parser.parseShadowsocksLink(link)
        assertNotNull("Legacy Shadowsocks should parse", config)
        assertEquals("legacy.example.com", config!!.address)

        val raw = parseRaw(config.rawConfig) ?: fail("rawConfig must be valid JSON")
        assertEquals("aes-256-gcm", raw["method"]?.jsonPrimitive?.content)
        assertEquals("password", raw["password"]?.jsonPrimitive?.content)
    }

    // ------------------------------------------------------------------
    // 5. Multi-link parsing
    // ------------------------------------------------------------------

    @Test
    fun `multi-link subscription parses all protocols`() {
        val input = """
            vless://uuid@vless.example.com:443?security=tls&type=tcp#VLESS-Server
            trojan://pass@trojan.example.com:443?security=tls#Trojan-Server
        """.trimIndent()
        val results = parser.parse(input)
        assertEquals("Should parse 2 servers", 2, results.size)

        // Both must have valid JSON rawConfig
        results.forEach { cfg ->
            val raw = parseRaw(cfg.rawConfig)
            assertNotNull("rawConfig for ${cfg.protocol} must be JSON", raw)
        }
    }
}
