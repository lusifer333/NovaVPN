package com.novavpn.data.repository

import com.novavpn.data.mapper.toDomain
import com.novavpn.data.mapper.toEntity
import com.novavpn.domain.model.*
import com.novavpn.storage.room.dao.ServerConfigDao
import com.novavpn.storage.room.entity.ServerConfigEntity
import io.mockk.*
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerRepositoryTest {

    @MockK
    private lateinit var dao: ServerConfigDao

    private lateinit var repo: ServerRepositoryImpl

    @Before
    fun setUp() {
        MockKAnnotations.init(this)
        repo = ServerRepositoryImpl(dao)
    }

    // ── Helper factory ────────────────────────────────────────────────────────

    private fun makeEntity(
        id: String = "srv-1",
        subscriptionId: String = "sub-1",
        name: String = "Server 1",
        address: String = "1.2.3.4",
        port: Int = 443,
        protocol: String = "VMess",
        transport: String = "TCP",
        security: String = "TLS",
        rawConfig: String = "{}",
        engineFormat: String = "XrayJson",
        isFavourite: Boolean = false,
        lastConnected: Long = 0L
    ) = ServerConfigEntity(
        id = id, subscriptionId = subscriptionId, name = name,
        address = address, port = port, protocol = protocol,
        transport = transport, security = security, rawConfig = rawConfig,
        engineFormat = engineFormat, isFavourite = isFavourite,
        lastConnected = lastConnected
    )

    private fun makeDomain(
        id: String = "srv-1",
        subscriptionId: String = "sub-1",
        name: String = "Server 1",
        address: String = "1.2.3.4",
        port: Int = 443,
        protocol: Protocol = Protocol.VMess,
        transport: Transport = Transport.TCP,
        security: Security = Security.TLS,
        rawConfig: String = "{}",
        engineFormat: EngineFormat = EngineFormat.XrayJson
    ) = ServerConfig(
        id = id, subscriptionId = subscriptionId, name = name,
        address = address, port = port, protocol = protocol,
        transport = transport, security = security, rawConfig = rawConfig,
        engineFormat = engineFormat
    )

    // ── observeSelectable ─────────────────────────────────────────────────────

    @Test
    fun `observeSelectable delegates to DAO and maps correctly`() = runTest {
        val entities = listOf(makeEntity(), makeEntity(id = "srv-2"))
        coEvery { dao.observeSelectable() } returns flowOf(entities)

        val result = repo.observeSelectable().first()

        assertEquals(2, result.size)
        assertEquals("srv-1", result[0].id)
        assertEquals("Server 1", result[0].name)
        coVerify { dao.observeSelectable() }
    }

    @Test
    fun `observeSelectable returns empty when no entities`() = runTest {
        coEvery { dao.observeSelectable() } returns flowOf(emptyList())

        val result = repo.observeSelectable().first()

        assertTrue(result.isEmpty())
        coVerify { dao.observeSelectable() }
    }

    // ── observeAll ────────────────────────────────────────────────────────────

    @Test
    fun `observeAll returns all entities mapped`() = runTest {
        val entities = listOf(makeEntity(), makeEntity(id = "srv-2"))
        coEvery { dao.observeAll() } returns flowOf(entities)

        val result = repo.observeAll().first()

        assertEquals(2, result.size)
        coVerify { dao.observeAll() }
    }

    // ── observeBySubscription ─────────────────────────────────────────────────

    @Test
    fun `observeBySubscription filters by subscriptionId`() = runTest {
        val subId = "sub-1"
        val entities = listOf(makeEntity(subscriptionId = subId))
        coEvery { dao.observeBySubscription(subId) } returns flowOf(entities)

        val result = repo.observeBySubscription(subId).first()

        assertEquals(1, result.size)
        assertEquals(subId, result[0].subscriptionId)
        coVerify { dao.observeBySubscription(subId) }
    }

    // ── isServerFromEnabledSubscription ───────────────────────────────────────

    @Test
    fun `isServerFromEnabledSubscription returns true when DAO count > 0`() = runTest {
        val serverId = "srv-1"
        coEvery { dao.isServerFromEnabledSubscription(serverId) } returns 1

        val result = repo.isServerFromEnabledSubscription(serverId)

        assertTrue(result)
        coVerify { dao.isServerFromEnabledSubscription(serverId) }
    }

    @Test
    fun `isServerFromEnabledSubscription returns false when DAO count == 0`() = runTest {
        val serverId = "srv-1"
        coEvery { dao.isServerFromEnabledSubscription(serverId) } returns 0

        val result = repo.isServerFromEnabledSubscription(serverId)

        assertFalse(result)
        coVerify { dao.isServerFromEnabledSubscription(serverId) }
    }

    // ── replaceForSubscription ────────────────────────────────────────────────

    @Test
    fun `replaceForSubscription generates UUID for blank server IDs`() = runTest {
        val subId = "sub-1"
        val servers = listOf(
            makeDomain(id = ""),  // blank ID — should get UUID
            makeDomain(id = "custom-id")  // existing ID — should keep
        )
        coEvery { dao.replaceForSubscription(any(), any()) } returns Unit

        repo.replaceForSubscription(subId, servers)

        // Verify the DAO was called with entities that have unique IDs
        coVerify {
            dao.replaceForSubscription(eq(subId), match { entities ->
                entities.size == 2 &&
                entities[0].id.isNotBlank() &&  // UUID generated
                entities[0].id != "" &&
                entities[1].id == "custom-id" && // preserved
                entities.all { it.subscriptionId == subId }
            })
        }
    }

    // ── getById ───────────────────────────────────────────────────────────────

    @Test
    fun `getById returns mapped entity when found`() = runTest {
        val entity = makeEntity()
        coEvery { dao.getById("srv-1") } returns entity

        val result = repo.getById("srv-1")

        assertEquals("srv-1", result?.id)
        assertEquals("Server 1", result?.name)
        coVerify { dao.getById("srv-1") }
    }

    @Test
    fun `getById returns null when not found`() = runTest {
        coEvery { dao.getById("nonexistent") } returns null

        val result = repo.getById("nonexistent")

        assertEquals(null, result)
        coVerify { dao.getById("nonexistent") }
    }

    // ── getLastConnected ──────────────────────────────────────────────────────

    @Test
    fun `getLastConnected returns most recent`() = runTest {
        val entity = makeEntity(id = "srv-2", lastConnected = 1000L)
        coEvery { dao.getLastConnected() } returns entity

        val result = repo.getLastConnected()

        assertEquals("srv-2", result?.id)
        coVerify { dao.getLastConnected() }
    }

    @Test
    fun `getLastConnected returns null when none`() = runTest {
        coEvery { dao.getLastConnected() } returns null

        val result = repo.getLastConnected()

        assertEquals(null, result)
        coVerify { dao.getLastConnected() }
    }

    // ── Delete / Favourite / SetLastConnected ─────────────────────────────────

    @Test
    fun `deleteBySubscription delegates to DAO`() = runTest {
        coEvery { dao.deleteBySubscription("sub-1") } returns Unit

        repo.deleteBySubscription("sub-1")

        coVerify { dao.deleteBySubscription("sub-1") }
    }

    @Test
    fun `setFavourite delegates to DAO`() = runTest {
        coEvery { dao.setFavourite("srv-1", true) } returns Unit

        repo.setFavourite("srv-1", true)

        coVerify { dao.setFavourite("srv-1", true) }
    }

    @Test
    fun `setLastConnected delegates to DAO with current time`() = runTest {
        coEvery { dao.setLastConnected(any(), any()) } returns Unit

        repo.setLastConnected("srv-1")

        coVerify { dao.setLastConnected(eq("srv-1"), any()) }
    }
}