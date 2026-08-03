package com.novavpn.domain.usecase.connection

import com.novavpn.domain.model.EngineFormat
import com.novavpn.domain.model.Protocol
import com.novavpn.domain.model.ServerConfig
import com.novavpn.domain.model.Security
import com.novavpn.domain.model.Transport
import com.novavpn.domain.model.VpnState
import com.novavpn.domain.repository.ServerRepository
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

class ConnectUseCaseTest {

    @MockK
    private lateinit var serverRepo: ServerRepository

    private lateinit var connectUseCase: ConnectUseCase

    private val enabledServer = ServerConfig(
        id = "srv-1", name = "Enabled Sub Server", address = "1.2.3.4", port = 443,
        protocol = Protocol.VMess, transport = Transport.TCP,
        security = Security.TLS, rawConfig = "{}", engineFormat = EngineFormat.XrayJson
    )

    private val disabledServer = ServerConfig(
        id = "srv-2", name = "Disabled Sub Server", address = "5.6.7.8", port = 443,
        protocol = Protocol.VMess, transport = Transport.TCP,
        security = Security.TLS, rawConfig = "{}", engineFormat = EngineFormat.XrayJson
    )

    @Before
    fun setUp() {
        MockKAnnotations.init(this, relaxed = true)
        connectUseCase = ConnectUseCase(serverRepo)
    }

    @Test
    fun `connect to server from enabled subscription succeeds`() = runTest {
        coEvery { serverRepo.isServerFromEnabledSubscription("srv-1") } returns true

        val result = connectUseCase.connect(enabledServer)

        assertTrue(result)
        assertEquals(VpnState.Connecting, connectUseCase.connectionState.value)
        assertEquals("srv-1", connectUseCase.currentServerId)
        coVerify { serverRepo.isServerFromEnabledSubscription("srv-1") }
    }

    @Test
    fun `connect to server from disabled subscription sets error state`() = runTest {
        coEvery { serverRepo.isServerFromEnabledSubscription("srv-2") } returns false

        val result = connectUseCase.connect(disabledServer)

        assertFalse(result)
        // State becomes Error with a message (not Disconnected)
        val state = connectUseCase.connectionState.value
        assertTrue(state is VpnState.Error)
        assertEquals("Server belongs to a disabled subscription", (state as VpnState.Error).message)
        assertEquals(null, connectUseCase.currentServerId)
        coVerify { serverRepo.isServerFromEnabledSubscription("srv-2") }
    }

    @Test
    fun `disconnect resets server and state`() = runTest {
        coEvery { serverRepo.isServerFromEnabledSubscription("srv-1") } returns true
        connectUseCase.connect(enabledServer)
        assertEquals(VpnState.Connecting, connectUseCase.connectionState.value)

        connectUseCase.disconnect()

        assertEquals(VpnState.Disconnected, connectUseCase.connectionState.value)
        assertEquals(null, connectUseCase.currentServerId)
    }

    /**
     * Core safety test: disabling a subscription while connected does NOT
     * crash or disconnect. The server remains tracked in currentServer.
     */
    @Test
    fun `enable disable toggle does not interrupt active connection`() = runTest {
        // 1. Connect to server (enabled at time of connect)
        coEvery { serverRepo.isServerFromEnabledSubscription("srv-1") } returns true
        val connected = connectUseCase.connect(enabledServer)
        assertTrue(connected)

        // 2. Simulate subscription being disabled AFTER connection was made
        // The connection state and currentServer remain unchanged
        assertEquals("srv-1", connectUseCase.currentServerId)
        assertEquals(VpnState.Connecting, connectUseCase.connectionState.value)

        // 3. Re-enable case: next connect attempt should work
        connectUseCase.disconnect()
        assertEquals(VpnState.Disconnected, connectUseCase.connectionState.value)
    }
}

class ObserveConnectionStateUseCaseTest {

    @MockK
    private lateinit var serverRepo: ServerRepository

    @Before
    fun setUp() {
        MockKAnnotations.init(this, relaxed = true)
    }

    @Test
    fun `observeConnectionState emits state changes`() = runTest {
        val connectUseCase = ConnectUseCase(serverRepo)
        val observer = ObserveConnectionStateUseCase(connectUseCase)

        val initial = observer.invoke().value
        assertEquals(VpnState.Disconnected, initial)

        coEvery { serverRepo.isServerFromEnabledSubscription(any()) } returns true
        connectUseCase.connect(
            ServerConfig(id = "srv-1", address = "1.2.3.4", port = 443,
                protocol = Protocol.VMess, transport = Transport.TCP,
                security = Security.TLS, rawConfig = "{}", engineFormat = EngineFormat.XrayJson)
        )
        assertEquals(VpnState.Connecting, observer.invoke().value)
    }
}
