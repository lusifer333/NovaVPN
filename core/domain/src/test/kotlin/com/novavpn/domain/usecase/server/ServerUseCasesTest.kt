package com.novavpn.domain.usecase.server

import com.novavpn.domain.model.EngineFormat
import com.novavpn.domain.model.Protocol
import com.novavpn.domain.model.ServerConfig
import com.novavpn.domain.model.ServerScore
import com.novavpn.domain.model.Security
import com.novavpn.domain.model.Transport
import com.novavpn.domain.repository.ServerRepository
import com.novavpn.domain.repository.StatisticsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.impl.annotations.MockK
import io.mockk.junit4.MockKRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class GetBestServerUseCaseTest {

    @get:Rule
    val mockkRule = MockKRule(this)

    @MockK
    private lateinit var serverRepo: ServerRepository

    @MockK
    private lateinit var statsRepo: StatisticsRepository

    private lateinit var useCase: GetBestServerUseCase

    private val enabledServer = ServerConfig(
        id = "srv-1", name = "Best", address = "1.2.3.4", port = 443,
        protocol = Protocol.VMess, transport = Transport.TCP,
        security = Security.TLS, rawConfig = "{}", engineFormat = EngineFormat.XrayJson
    )

    private val disabledServer = ServerConfig(
        id = "srv-2", name = "Disabled Sub", address = "5.6.7.8", port = 443,
        protocol = Protocol.VMess, transport = Transport.TCP,
        security = Security.TLS, rawConfig = "{}", engineFormat = EngineFormat.XrayJson
    )

    @Before
    fun setUp() {
        useCase = GetBestServerUseCase(serverRepo, statsRepo)
    }

    @Test
    fun `returns best server from enabled subscription`() = runTest {
        val score = ServerScore(serverId = "srv-1", connectionSuccessRate = 1.0)
        coEvery { statsRepo.getAllScores() } returns listOf(score)
        coEvery { serverRepo.getById("srv-1") } returns enabledServer
        coEvery { serverRepo.isServerFromEnabledSubscription("srv-1") } returns true

        val result = useCase()

        assertNotNull(result)
        assertEquals("srv-1", result!!.id)
        coVerify { serverRepo.isServerFromEnabledSubscription("srv-1") }
    }

    @Test
    fun `skips servers from disabled subscriptions`() = runTest {
        val score = ServerScore(serverId = "srv-2", connectionSuccessRate = 1.0)
        coEvery { statsRepo.getAllScores() } returns listOf(score)
        coEvery { serverRepo.getById("srv-2") } returns disabledServer
        coEvery { serverRepo.isServerFromEnabledSubscription("srv-2") } returns false
        // Fallback: no selectable servers
        coEvery { serverRepo.observeSelectable() } returns flowOf(emptyList())

        val result = useCase()

        assertNull(result)
        coVerify { serverRepo.isServerFromEnabledSubscription("srv-2") }
    }

    @Test
    fun `falls back to selectable servers when scores empty`() = runTest {
        coEvery { statsRepo.getAllScores() } returns emptyList()
        coEvery { serverRepo.observeSelectable() } returns flowOf(listOf(enabledServer))

        val result = useCase()

        assertNotNull(result)
        assertEquals("srv-1", result!!.id)
    }
}

class IsServerFromEnabledSubscriptionUseCaseTest {

    @get:Rule
    val mockkRule = MockKRule(this)

    @MockK
    private lateinit var repo: ServerRepository

    private lateinit var useCase: IsServerFromEnabledSubscriptionUseCase

    @Before
    fun setUp() {
        useCase = IsServerFromEnabledSubscriptionUseCase(repo)
    }

    @Test
    fun `returns true when server from enabled subscription`() = runTest {
        coEvery { repo.isServerFromEnabledSubscription("srv-1") } returns true

        val result = useCase("srv-1")

        assertEquals(true, result)
    }

    @Test
    fun `returns false when server from disabled subscription`() = runTest {
        coEvery { repo.isServerFromEnabledSubscription("srv-1") } returns false

        val result = useCase("srv-1")

        assertEquals(false, result)
    }
}

class ObserveSelectableServersUseCaseTest {

    @get:Rule
    val mockkRule = MockKRule(this)

    @MockK
    private lateinit var repo: ServerRepository

    private lateinit var useCase: ObserveSelectableServersUseCase

    @Before
    fun setUp() {
        useCase = ObserveSelectableServersUseCase(repo)
    }

    @Test
    fun `emits selectable servers from repository`() = runTest {
        val servers = listOf(
            ServerConfig(id = "srv-1", address = "1.2.3.4", port = 443,
                protocol = Protocol.VMess, transport = Transport.TCP,
                security = Security.TLS, rawConfig = "{}", engineFormat = EngineFormat.XrayJson)
        )
        coEvery { repo.observeSelectable() } returns flowOf(servers)

        val result = useCase().first()

        assertEquals(1, result.size)
        assertEquals("srv-1", result[0].id)
    }
}