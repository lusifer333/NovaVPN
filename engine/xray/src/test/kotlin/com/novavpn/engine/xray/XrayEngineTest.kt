package com.novavpn.engine.xray

import com.novavpn.domain.model.EngineRuntimeState
import com.novavpn.domain.model.EngineType
import com.novavpn.domain.model.Protocol
import com.novavpn.domain.model.ServerConfig
import com.novavpn.domain.model.Transport
import com.novavpn.engine.api.EngineContext
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class XrayEngineTest {

    @MockK
    private lateinit var binaryManager: com.novavpn.engine.api.BinaryManager

    private lateinit var engine: XrayEngine

    private val validConfig = ServerConfig(
        id = "test-1", name = "Test Server", address = "1.2.3.4", port = 443,
        protocol = Protocol.VMess, transport = Transport.TCP,
        rawConfig = """{"id":"uuid-here","aid":0}"""
    )

    private val invalidConfig = ServerConfig(
        id = "bad", name = "Bad Server", address = "", port = 0,
        protocol = Protocol.Unknown, transport = Transport.Unknown,
        rawConfig = ""
    )

    private val testContext = object : EngineContext {
        override val isVpnPermissionGranted = true
        override val tunFileDescriptor = 42
        override val dnsServers = listOf("8.8.8.8")
        override val routes = listOf("0.0.0.0/0")
    }

    @Before
    fun setUp() {
        MockKAnnotations.init(this, relaxed = true)
        engine = XrayEngine(binaryManager)
    }

    @Test
    fun `initial state is Idle`() {
        assertEquals(EngineRuntimeState.Idle, engine.state.value)
        assertEquals(EngineType.Xray, engine.type)
    }

    @Test
    fun `initialize succeeds`() = runTest {
        val result = engine.initialize(testContext)
        assertTrue(result.isSuccess)
        assertEquals(EngineRuntimeState.Idle, engine.state.value)
    }

    @Test
    fun `isAlive returns false before start`() = runTest {
        assertFalse(engine.isAlive())
    }

    @Test
    fun `start transitions Idle to Preparing to Starting to Crashed without binary`() = runTest {
        coEvery { binaryManager.ensureEngine(EngineType.Xray) } returns Result.failure(
            Exception("Binary not found")
        )

        val result = engine.start(validConfig)

        assertTrue(result.isFailure)
        // State should be Crashed after failure
        assertEquals(EngineRuntimeState.Crashed, engine.state.value)
        coVerify { binaryManager.ensureEngine(EngineType.Xray) }
    }

    @Test
    fun `start with invalid config is rejected in Preparing phase`() = runTest {
        val result = engine.start(invalidConfig)

        assertTrue("Invalid config should be rejected", result.isFailure)
        assertEquals("State should be Crashed after invalid config",
            EngineRuntimeState.Crashed, engine.state.value)
        // BinaryManager should NOT be called — validation happens first
        coVerify(inverse = true) { binaryManager.ensureEngine(any()) }
    }

    @Test
    fun `start with empty address is rejected`() = runTest {
        val bad = validConfig.copy(address = "")
        val result = engine.start(bad)
        assertTrue(result.isFailure)
        assertEquals(EngineRuntimeState.Crashed, engine.state.value)
    }

    @Test
    fun `start with invalid port is rejected`() = runTest {
        val bad = validConfig.copy(port = 0)
        val result = engine.start(bad)
        assertTrue(result.isFailure)
        assertEquals(EngineRuntimeState.Crashed, engine.state.value)
    }

    @Test
    fun `start with Unknown protocol is rejected`() = runTest {
        val bad = validConfig.copy(protocol = Protocol.Unknown)
        val result = engine.start(bad)
        assertTrue(result.isFailure)
        assertEquals(EngineRuntimeState.Crashed, engine.state.value)
    }

    @Test
    fun `stop on Idle engine is no-op`() = runTest {
        assertTrue(engine.stop().isSuccess)
        assertEquals(EngineRuntimeState.Idle, engine.state.value)
    }

    @Test
    fun `destroy cleans up and resets state`() = runTest {
        engine.initialize(testContext)
        engine.destroy()
        assertEquals(EngineRuntimeState.Idle, engine.state.value)
    }

    @Test
    fun `multiple stop calls are safe`() = runTest {
        assertTrue(engine.stop().isSuccess)
        assertTrue(engine.stop().isSuccess)
        assertTrue(engine.stop().isSuccess)
        assertEquals(EngineRuntimeState.Idle, engine.state.value)
    }
}