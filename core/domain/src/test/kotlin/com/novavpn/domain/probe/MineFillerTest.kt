package com.novavpn.domain.probe

import com.novavpn.domain.model.CertStatus
import com.novavpn.domain.model.ServerConfig
import com.novavpn.domain.model.ServerProbeResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MineFillerTest {

    private fun server(id: String): ServerConfig =
        ServerConfig(id = id, name = id, address = "127.0.0.1", port = 1)

    private fun green(id: String): ServerProbeResult =
        ServerProbeResult(serverId = id, tcpOk = true, tcpMs = 10, tlsOk = true, tlsMs = 20, certStatus = CertStatus.VALID)

    private fun dead(id: String): ServerProbeResult = ServerProbeResult(serverId = id)

    /** Prober whose fastTlsProbe outcome is decided per server id. */
    private fun proberOf(greens: Set<String>, dead: Set<String> = emptySet()): ServerProber {
        val p = mockk<ServerProber>()
        coEvery { p.fastTlsProbe(any(), any()) } answers {
            val s = firstArg<ServerConfig>()
            when {
                s.id in greens -> green(s.id)
                else -> dead(s.id)
            }
        }
        return p
    }

    /** E2E prober whose session always starts; probe outcome per server id. */
    private fun e2eOf(okIds: Set<String>): RealDelayProber {
        val p = mockk<RealDelayProber>()
        coEvery { p.start(any()) } returns true
        coEvery { p.probe(any()) } answers {
            val id = firstArg<String>()
            if (id in okIds) RealDelayOutcome(true, 120) else RealDelayOutcome(false)
        }
        coEvery { p.stop() } just Runs
        return p
    }

    // ------------------------------------------------------------------

    @Test
    fun `dead server fails at stage 1 and never reaches real delay`() {
        val e2e = e2eOf(okIds = emptySet())
        val filler = MineFiller(proberOf(greens = emptySet()), e2e)
        runBlocking {
            val r = filler.fill(
                listOf(ProfileServers("p1", "P1", listOf(server("a"), server("b"), server("c")))),
                probeParallelism = 2
            )
            assertEquals(0, r.mine.size)
            assertEquals(3, r.results.size)
            assertTrue(r.results.values.none { it.green })
        }
        coVerify(exactly = 0) { e2e.probe(any()) }
    }

    @Test
    fun `healthy server passes all three stages and enters the mine`() {
        val filler = MineFiller(proberOf(greens = setOf("a")), e2eOf(okIds = setOf("a")))
        runBlocking {
            val r = filler.fill(listOf(ProfileServers("p1", "P1", listOf(server("a")))))
            assertEquals(listOf("a"), r.mine.map { it.id })
            val res = r.results["a"]!!
            assertTrue(res.healthy)
            assertEquals(120L, res.e2eMs)
        }
    }

    @Test
    fun `handshake-green but relay-dead server does not enter the mine`() {
        // TLS passes but the real-delay relay fails (the handshake-yes/data-no case)
        val filler = MineFiller(proberOf(greens = setOf("a")), e2eOf(okIds = emptySet()))
        runBlocking {
            val r = filler.fill(listOf(ProfileServers("p1", "P1", listOf(server("a")))))
            assertEquals(0, r.mine.size)
            assertTrue(r.results["a"]!!.green)
            assertTrue(!r.results["a"]!!.healthy)
        }
    }

    @Test
    fun `fill stops the moment the mine is full`() {
        // 4 servers, capacity = clamp(ceil(4*0.3),3,12) = 3 → after 3 healthy, the 4th must NOT be probed
        val prober = proberOf(greens = setOf("a", "b", "c", "d"))
        val filler = MineFiller(prober, e2eOf(okIds = setOf("a", "b", "c", "d")))
        runBlocking {
            val r = filler.fill(listOf(ProfileServers("p1", "P1", listOf(server("a"), server("b"), server("c"), server("d")))))
            assertEquals(3, r.mine.size)
            assertEquals(setOf("a", "b", "c"), r.mine.map { it.id }.toSet())
        }
    }

    @Test
    fun `profile shares are respected - large profile cannot starve small one`() {
        // A=5 servers (share 1), B=50 servers (share 11), capacity 12
        // B's servers come first in the list but A must get its slot
        val bServers = (0 until 50).map { server("b$it") }
        val aServers = (0 until 5).map { server("a$it") }
        val allIds = bServers.map { it.id } + aServers.map { it.id }
        val filler = MineFiller(proberOf(greens = allIds.toSet()), e2eOf(okIds = allIds.toSet()))
        runBlocking {
            val r = filler.fill(
                listOf(
                    ProfileServers("B", "B", bServers),
                    ProfileServers("A", "A", aServers)
                ),
                probeParallelism = 100
            )
            assertEquals(12, r.mine.size)
            // A has 5 servers and share 1 → exactly one of a* in the mine
            val aInMine = r.mine.count { it.id.startsWith("a") }
            assertEquals(1, aInMine)
        }
    }

    @Test
    fun `partial mine when not enough healthy servers`() {
        // 10 servers, only 2 healthy → mine has 2, no hang
        val ids = (0 until 10).map { "s$it" }
        val filler = MineFiller(proberOf(greens = setOf("s0", "s1")), e2eOf(okIds = setOf("s0", "s1")))
        runBlocking {
            val r = filler.fill(listOf(ProfileServers("p1", "P1", ids.map { server(it) })))
            assertEquals(2, r.mine.size)
        }
    }

    @Test
    fun `mine is sorted by real delay ascending`() {
        val prober = mockk<ServerProber>()
        coEvery { prober.fastTlsProbe(any(), any()) } answers {
            green(firstArg<ServerConfig>().id)
        }
        val e2e = mockk<RealDelayProber>()
        coEvery { e2e.start(any()) } returns true
        coEvery { e2e.probe(any()) } coAnswers {
            val id = firstArg<String>()
            if (id == "s5") {
                delay(500) // slowest relay: loses the race to the mine
                RealDelayOutcome(true, 500)
            } else {
                val ms = id.removePrefix("s").toLong() // s1→1, s2→2, s3→3
                RealDelayOutcome(true, ms)
            }
        }
        coEvery { e2e.stop() } just Runs
        val filler = MineFiller(prober, e2e)
        runBlocking {
            val r = filler.fill(
                listOf(ProfileServers("p1", "P1", listOf(server("s5"), server("s1"), server("s3"), server("s2")))),
                probeParallelism = 100,
                e2eParallelism = 3
            )
            // Fast relays complete instantly and fill the mine (capacity 3);
            // the 500 ms relay is cancelled — fastest 3 win, sorted ascending.
            assertEquals(listOf("s1", "s2", "s3"), r.mine.map { it.id })
        }
    }

    @Test
    fun `empty input yields empty mine`() {
        val filler = MineFiller(proberOf(greens = emptySet()), e2eOf(okIds = emptySet()))
        runBlocking {
            val r = filler.fill(emptyList())
            assertEquals(0, r.mine.size)
            assertTrue(r.results.isEmpty())
        }
    }

    @Test
    fun `engine start failure admits nobody - no false positives`() {
        // The shared Karing-style session could not boot → stage 3 is
        // unavailable, and handshake-only servers must NOT fill the mine.
        val e2e = mockk<RealDelayProber>()
        coEvery { e2e.start(any()) } returns false
        coEvery { e2e.probe(any()) } returns RealDelayOutcome(false)
        coEvery { e2e.stop() } just Runs
        val filler = MineFiller(proberOf(greens = setOf("a", "b")), e2e)
        runBlocking {
            val r = filler.fill(listOf(ProfileServers("p1", "P1", listOf(server("a"), server("b")))))
            assertEquals(0, r.mine.size)
            assertTrue(r.results.values.none { it.healthy })
        }
        coVerify(exactly = 0) { e2e.probe(any()) }
    }

    @Test
    fun `engine session starts once with all candidates and stops after fill`() {
        val e2e = e2eOf(okIds = setOf("a"))
        val filler = MineFiller(proberOf(greens = setOf("a")), e2e)
        runBlocking {
            filler.fill(listOf(ProfileServers("p1", "P1", listOf(server("a")))))
        }
        coVerify(exactly = 1) { e2e.start(any()) }
        coVerify(exactly = 1) { e2e.stop() }
    }
}
