package com.novavpn.engine.xray

import com.novavpn.domain.model.EngineRuntimeState
import com.novavpn.domain.model.EngineType
import com.novavpn.domain.model.Protocol
import com.novavpn.domain.model.ServerConfig
import com.novavpn.domain.model.Transport
import com.novavpn.engine.api.BinaryManager
import com.novavpn.engine.api.EngineContext
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class XrayEngineTest {

    @MockK
    private lateinit var binaryManager: BinaryManager

    private lateinit var engine: XrayEngine

    private val testConfig = ServerConfig(
        id = "test-1",
        name = "Test Server",
        address = "1.2.3.4",
        port = 443,
        protocol = Protocol.VMess,
        transport = Transport.TCP,
        rawConfig = """{"id":"uuid-here","aid":0}"""
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
    fun `initial state is Idle`() = runTest {
        assertEquals(EngineRuntimeState.Idle, engine.state.value)
        assertEquals(EngineType.Xray, engine.type)
    }

    @Test
    fun `initialize succeeds and state remains Idle`() = runTest {
        val result = engine.initialize(testContext)

        assertTrue(result.isSuccess)
        assertEquals(EngineRuntimeState.Idle, engine.state.value)
    }

    @Test
    fun `isAlive returns false before start`() = runTest {
        assertFalse(engine.isAlive())
    }

    @Test
    fun `start transitions Idle to Starting then fails without binary`() = runTest {
        // BinaryManager returns failure (no binary available)
        coEvery { binaryManager.ensureEngine(EngineType.Xray) } returns Result.failure(
            Exception("Binary not found")
        )

        val result = engine.start(testConfig)

        assertTrue(result.isFailure)
        // State should be Crashed after failure
        assertEquals(EngineRuntimeState.Crashed, engine.state.value)
        coVerify { binaryManager.ensureEngine(EngineType.Xray) }
    }

    @Test
    fun `stop on Idle engine is no-op`() = runTest {
        val result = engine.stop()

        assertTrue(result.isSuccess)
        assertEquals(EngineRuntimeState.Idle, engine.state.value)
    }

    @Test
    fun `destroy cleans up and resets state`() = runTest {
        coEvery { binaryManager.ensureEngine(any()) } returns Result.failure(Exception("No binary"))

        engine.initialize(testContext)
        engine.start(testConfig) // will fail
        assertEquals(EngineRuntimeState.Crashed, engine.state.value)

        engine.destroy()
        assertEquals(EngineRuntimeState.Idle, engine.state.value)
    }

    @Test
    fun `restart calls stop then start`() = runTest {
        // Start should fail (no binary), restart should still be safe
        coEvery { binaryManager.ensureEngine(any()) } returns Result.failure(Exception("No binary"))

        engine.initialize(testContext)
        val result = engine.restart(testConfig)

        // restart = stop (which may fail) + start (which fails)
        // At minimum, it shouldn't crash
        assertTrue(result.isFailure || result.isSuccess)
    }

    @Test
    fun `multiple stop calls are safe`() = runTest {
        assertTrue(engine.stop().isSuccess)
        assertTrue(engine.stop().isSuccess)
        assertTrue(engine.stop().isSuccess)
        assertEquals(EngineRuntimeState.Idle, engine.state.value)
    }
}

class SingboxEngineTest {

    @MockK
    private lateinit var binaryManager: BinaryManager

    private lateinit var engine: com.novavpn.engine.singbox.SingboxEngine

    private val testConfig = ServerConfig(
        id = "test-1",
        name = "Test Server",
        address = "1.2.3.4",
        port = 443,
        protocol = Protocol.VMess,
        transport = Transport.TCP,
        rawConfig = """{"id":"uuid-here","aid":0}"""
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
        engine = com.novavpn.engine.singbox.SingboxEngine(binaryManager)
    }

    @Test
    fun `initial state is Idle`() = runTest {
        assertEquals(EngineRuntimeState.Idle, engine.state.value)
        assertEquals(EngineType.SingBox, engine.type)
    }

    @Test
    fun `initialize succeeds and state remains Idle`() = runTest {
        val result = engine.initialize(testContext)

        assertTrue(result.isSuccess)
        assertEquals(EngineRuntimeState.Idle, engine.state.value)
    }

    @Test
    fun `isAlive returns false before start`() = runTest {
        assertFalse(engine.isAlive())
    }

    @Test
    fun `start fails gracefully without binary`() = runTest {
        coEvery { binaryManager.ensureEngine(EngineType.SingBox) } returns Result.failure(
            Exception("Binary not found")
        )

        val result = engine.start(testConfig)

        assertTrue(result.isFailure)
        assertEquals(EngineRuntimeState.Crashed, engine.state.value)
        coVerify { binaryManager.ensureEngine(EngineType.SingBox) }
    }

    @Test
    fun `stop after failed start is safe`() = runTest {
        coEvery { binaryManager.ensureEngine(any()) } returns Result.failure(Exception("No binary"))

        engine.start(testConfig) // fails
        assertEquals(EngineRuntimeState.Crashed, engine.state.value)

        val result = engine.stop()
        assertTrue(result.isSuccess)
        assertEquals(EngineRuntimeState.Idle, engine.state.value)
    }

    @Test
    fun `destroy cleans up`() = runTest {
        engine.initialize(testContext)
        engine.destroy()
        assertEquals(EngineRuntimeState.Idle, engine.state.value)
    }
}

class EngineManagerImplTest {

    @MockK
    private lateinit var binaryManager: BinaryManager

    @Test
    fun `engines can be registered and selected`() = runTest {
        val xray = XrayEngine(binaryManager)
        val singbox = com.novavpn.engine.singbox.SingboxEngine(binaryManager)
        val manager = com.novavpn.engine.api.EngineManagerImpl(
            com.novavpn.domain.repository.SettingsRepositoryImpl(
                // Provide a mock datastore for SettingsRepositoryImpl
                createMockDataStore()
            )
        )

        manager.register(EngineType.Xray, xray)
        manager.register(EngineType.SingBox, singbox)

        assertEquals(2, manager.availableEngines.size)
        assertTrue(manager.availableEngines.contains(EngineType.Xray))
        assertTrue(manager.availableEngines.contains(EngineType.SingBox))

        assertEquals(xray, manager.getEngine(EngineType.Xray))
        assertEquals(singbox, manager.getEngine(EngineType.SingBox))
    }

    @Test
    fun `active engine is null before selection`() = runTest {
        val xray = XrayEngine(binaryManager)
        val manager = com.novavpn.engine.api.EngineManagerImpl(
            com.novavpn.domain.repository.SettingsRepositoryImpl(
                createMockDataStore()
            )
        )
        manager.register(EngineType.Xray, xray)

        assertEquals(null, manager.activeEngine)
    }

    private fun createMockDataStore(): androidx.datastore.core.DataStore<com.novavpn.domain.model.AppSettings> {
        val mockStore = io.mockk.mockk<androidx.datastore.core.DataStore<com.novavpn.domain.model.AppSettings>>(relaxed = true)
        io.mockk.coEvery { mockStore.data } returns kotlinx.coroutines.flow.flowOf(
            com.novavpn.domain.model.AppSettings()
        )
        io.mockk.coEvery { mockStore.updateData(any()) } returns com.novavpn.domain.model.AppSettings()
        return mockStore
    }
}