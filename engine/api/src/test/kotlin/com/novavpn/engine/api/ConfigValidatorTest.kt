package com.novavpn.engine.api

import com.novavpn.domain.model.EngineFormat
import com.novavpn.domain.model.Protocol
import com.novavpn.domain.model.ServerConfig
import com.novavpn.domain.model.Transport
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfigValidatorTest {

    @Test
    fun `valid VMess config passes`() {
        val config = ServerConfig(
            id = "t1", name = "Test", address = "1.2.3.4", port = 443,
            protocol = Protocol.VMess, transport = Transport.TCP,
            rawConfig = """{"id":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee","aid":0}"""
        )
        assertTrue(ConfigValidator.validate(config).isSuccess)
    }

    @Test
    fun `valid VLESS config passes`() {
        val config = ServerConfig(
            id = "t2", name = "Test", address = "example.com", port = 8443,
            protocol = Protocol.VLESS, transport = Transport.WebSocket,
            rawConfig = """{"id":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee","encryption":"none"}"""
        )
        assertTrue(ConfigValidator.validate(config).isSuccess)
    }

    @Test
    fun `empty address fails`() {
        val config = ServerConfig(
            id = "t3", name = "", address = "", port = 443,
            protocol = Protocol.VMess, transport = Transport.TCP, rawConfig = "{}"
        )
        assertTrue(ConfigValidator.validate(config).isFailure)
    }

    @Test
    fun `invalid port zero fails`() {
        val config = ServerConfig(
            id = "t4", name = "", address = "1.2.3.4", port = 0,
            protocol = Protocol.VMess, transport = Transport.TCP, rawConfig = "{}"
        )
        assertTrue(ConfigValidator.validate(config).isFailure)
    }

    @Test
    fun `port over 65535 fails`() {
        val config = ServerConfig(
            id = "t5", name = "", address = "1.2.3.4", port = 70000,
            protocol = Protocol.VMess, transport = Transport.TCP, rawConfig = "{}"
        )
        assertTrue(ConfigValidator.validate(config).isFailure)
    }

    @Test
    fun `Unknown protocol fails`() {
        val config = ServerConfig(
            id = "t6", name = "", address = "1.2.3.4", port = 443,
            protocol = Protocol.Unknown, transport = Transport.TCP, rawConfig = "{}"
        )
        assertTrue(ConfigValidator.validate(config).isFailure)
    }

    @Test
    fun `blank rawConfig fails`() {
        val config = ServerConfig(
            id = "t7", name = "", address = "1.2.3.4", port = 443,
            protocol = Protocol.VMess, transport = Transport.TCP, rawConfig = ""
        )
        assertTrue(ConfigValidator.validate(config).isFailure)
    }

    @Test
    fun `VMess missing uuid field fails`() {
        val config = ServerConfig(
            id = "t8", name = "", address = "1.2.3.4", port = 443,
            protocol = Protocol.VMess, transport = Transport.TCP,
            rawConfig = """{"aid":0}"""  // no "id" field
        )
        assertTrue(ConfigValidator.validate(config).isFailure)
    }

    @Test
    fun `valid Trojan config passes`() {
        val config = ServerConfig(
            id = "t9", name = "Trojan", address = "trojan.example.com", port = 443,
            protocol = Protocol.Trojan, transport = Transport.TCP,
            rawConfig = """{"password":"mypassword","flow":""}"""
        )
        assertTrue(ConfigValidator.validate(config).isSuccess)
    }

    @Test
    fun `valid Shadowsocks config passes`() {
        val config = ServerConfig(
            id = "t10", name = "SS", address = "ss.example.com", port = 1080,
            protocol = Protocol.Shadowsocks, transport = Transport.TCP,
            rawConfig = """{"password":"secret","method":"chacha20-ietf-poly1305"}"""
        )
        assertTrue(ConfigValidator.validate(config).isSuccess)
    }
}