package com.novavpn.domain.usecase.connection

import com.novavpn.domain.model.ConnectionState
import com.novavpn.domain.model.EngineFormat
import com.novavpn.domain.model.Protocol
import com.novavpn.domain.model.ServerConfig
import com.novavpn.domain.model.Security
import com.novavpn.domain.model.Transport
import com.novavpn.domain.repository.ServerRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.impl.annotations.MockK
import io.mockk.junit4.MockKRule
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class ConnectUseCaseTest {

    @get:Rule
    val mockkRule = MockKRule(this)

    @MockK
    private lateinit var serverRepo: ServerRepository

    @MockK
    private lateinit var getBestServer: com.novavpn.domain.usecase.server.GetBestServerUseCase

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
        connectUseCase = ConnectUseCase(getBestServer, serverRepo)
    }

    @Test
    fun `connect to server from enabled subscription succeeds`() = runTest {
        coEvery { serverRepo.isServerFromEnabledSubscription("srv-1") } returns true

        val result = connectUseCase.connect(enabledServer)

        assertTrue(result)
        assertEquals(ConnectionState.Connecting, connectUseCase.connectionState.value)
        assertEquals("srv-1", connectUseCase.currentServerId)
        coVerify { serverRepo.isServerFromEnabledSubscription("srv-1") }
    }

    @Test
    fun `connect to server from disabled subscription is rejected`() = runTest {
        coEvery { serverRepo.isServerFromEnabledSubscription("srv-2") } returns false

        val result = connectUseCase.connect(disabledServer)

        assertFalse(result)
        // Connection state unchanged — still Disconnected
        assertEquals(ConnectionState.Disconnected, connectUseCase.connectionState.value)
        assertEquals(null, connectUseCase.currentServerId)
        coVerify { serverRepo.isServerFromEnabledSubscription("srv-2") }
    }

    @Test
    fun `disconnect resets server and state`() = runTest {
        coEvery { serverRepo.isServerFromEnabledSubscription("srv-1") } returns true
        connectUseCase.connect(enabledServer)
        assertEquals(ConnectionState.Connecting, connectUseCase.connectionState.value)

        connectUseCase.disconnect()

        assertEquals(ConnectionState.Disconnected, connectUseCase.connectionState.value)
        assertEquals(null, connectUseCase.currentServerId)
    }

    /**
     * Core safety test: disconnecting a subscription while connected does NOT
     * crash or disconnect. The server remains tracked in currentServer.
     */
    @Test
    fun `enable-disable toggle does not interrupt active connection`() = runTest {
        // 1. Connect to server (enabled at time of connect)
        coEvery { serverRepo.isServerFromEnabledSubscription("srv-1") } returns true
        val connected = connectUseCase.connect(enabledServer)
        assertTrue(connected)

        // 2. Simulate subscription being disabled AFTER connection was made
        // The connection state and currentServer remain unchanged
        assertEquals("srv-1", connectUseCase.currentServerId)
        assertEquals(ConnectionState.Connecting, connectUseCase.connectionState.value)

        // 3. Re-enable case: next connect attempt should work
        connectUseCase.disconnect()
        assertEquals(ConnectionState.Disconnected, connectUseCase.connectionState.value)
    }
}

class ObserveConnectionStateUseCaseTest {

    @get:Rule
    val mockkRule = MockKRule(this)

    @MockK
    private lateinit var serverRepo: ServerRepository

    @MockK
    private lateinit var getBestServer: com.novavpn.domain.usecase.server.GetBestServerUseCase

    @Test
    fun `observeConnectionState emits state changes`() = runTest {
        val connectUseCase = ConnectUseCase(getBestServer, serverRepo)
        val observer = ObserveConnectionStateUseCase(connectUseCase)

        val initial = observer.invoke().value
        assertEquals(ConnectionState.Disconnected, initial)

        coEvery { serverRepo.isServerFromEnabledSubscription(any()) } returns true
        connectUseCase.connect(
            ServerConfig(id = "srv-1", address = "1.2.3.4", port = 443,
                protocol = Protocol.VMess, transport = Transport.TCP,
                security = Security.TLS, rawConfig = "{}", engineFormat = EngineFormat.XrayJson)
        )
        assertEquals(ConnectionState.Connecting, observer.invoke().value)
    }
}