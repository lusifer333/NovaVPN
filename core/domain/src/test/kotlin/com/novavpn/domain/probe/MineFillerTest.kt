package com.novavpn.domain.probe

import com.novavpn.domain.model.ServerConfig
import com.novavpn.domain.model.ServerProbeResult
import io.mockk.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MineFillerTest {

    private fun server(id: String): ServerConfig =
        ServerConfig(id = id, name = id, address = "127.0.0.1", port = 1)

    /** Karing urltest prober whose session always starts; verdict per server id. */
    private fun e2eOf(okIds: Set<String>): RealDelayProber {
        val p = mockk<RealDelayProber>()
        coEvery { p.start(any(), any()) } returns true
        coEvery { p.probe(any()) } answers {
            val id = firstArg<String>()
            if (id in okIds) RealDelayOutcome(true, 120) else RealDelayOutcome(false)
        }
        coEvery { p.stop() } returns Unit
        return p
    }

    // ------------------------------------------------------------------

    @Test
    fun `dead relay never enters the mine`() {
        // The only test is the urltest round-trip — no handshake stage
        // left to pass. A server that does not relay HTTP traffic fails.
        val filler = MineFiller(e2eOf(okIds = emptySet()))
        runBlocking {
            val r = filler.fill(
                listOf(ProfileServers("p1", "P1", listOf(server("a"), server("b"), server("c"))))
            )
            assertEquals(0, r.mine.size)
            assertEquals(3, r.results.size)
            assertTrue(r.results.values.none { it.healthy })
        }
    }

    @Test
    fun `healthy relay enters the mine with its round-trip delay`() {
        val filler = MineFiller(e2eOf(okIds = setOf("a")))
        runBlocking {
            val r = filler.fill(listOf(ProfileServers("p1", "P1", listOf(server("a")))))
            assertEquals(listOf("a"), r.mine.map { it.id })
            val res = r.results["a"]!!
            assertTrue(res.healthy)
            assertEquals(120L, res.e2eMs)
        }
    }

    @Test
    fun `fill stops the moment the mine is full`() {
        // 4 servers, capacity = clamp(ceil(4*0.10),3,12) = 3 → after 3 healthy,
        // the 4th must NOT be probed.
        val filler = MineFiller(e2eOf(okIds = setOf("a", "b", "c", "d")))
        runBlocking {
            val r = filler.fill(listOf(ProfileServers("p1", "P1", listOf(server("a"), server("b"), server("c"), server("d")))))
            assertEquals(3, r.mine.size)
            assertEquals(setOf("a", "b", "c"), r.mine.map { it.id }.toSet())
        }
    }

    @Test
    fun `profile shares are respected - large profile cannot starve small one`() {
        // A=5 servers (share 1), B=50 servers, capacity = clamp(ceil(55*0.10),3,12) = 6
        // B's servers come first in the list but A must get its slot.
        val bServers = (0 until 50).map { server("b$it") }
        val aServers = (0 until 5).map { server("a$it") }
        val allIds = bServers.map { it.id } + aServers.map { it.id }
        val filler = MineFiller(e2eOf(okIds = allIds.toSet()))
        runBlocking {
            val r = filler.fill(
                listOf(
                    ProfileServers("B", "B", bServers),
                    ProfileServers("A", "A", aServers)
                )
            )
            assertEquals(6, r.mine.size)
            // A has 5 servers and share 1 → exactly one of a* in the mine
            val aInMine = r.mine.count { it.id.startsWith("a") }
            assertEquals(1, aInMine)
        }
    }

    @Test
    fun `partial mine when not enough healthy servers`() {
        // 10 servers, only 2 healthy → mine has 2, no hang
        val ids = (0 until 10).map { "s$it" }
        val filler = MineFiller(e2eOf(okIds = setOf("s0", "s1")))
        runBlocking {
            val r = filler.fill(listOf(ProfileServers("p1", "P1", ids.map { server(it) })))
            assertEquals(2, r.mine.size)
        }
    }

    @Test
    fun `mine is sorted by real delay ascending`() {
        val e2e = mockk<RealDelayProber>()
        coEvery { e2e.start(any(), any()) } returns true
        coEvery { e2e.probe(any()) } coAnswers {
            val id = firstArg<String>()
            if (id == "s5") {
                delay(500) // slowest relay: loses the race to the mine
            } else {
                val ms = id.removePrefix("s").toLong() // s1→1, s2→2, s3→3
            }
            RealDelayOutcome(true, if (id == "s5") 500 else ms)
        }
        coEvery { e2e.stop() } returns Unit
        val filler = MineFiller(e2e)
        runBlocking {
            val r = filler.fill(
                listOf(ProfileServers("p1", "P1", listOf(server("s5"), server("s1"), server("s3"), server("s2")))),
                e2eParallelism = 3
            )
            // Fast relays complete instantly and fill the mine (capacity 3);
            // the 500 ms relay is cancelled — fastest 3 win, sorted ascending.
            assertEquals(listOf("s1", "s2", "s3"), r.mine.map { it.id })
        }
    }

    @Test
    fun `empty input yields empty mine`() {
        val filler = MineFiller(e2eOf(okIds = emptySet()))
        runBlocking {
            val r = filler.fill(emptyList())
            assertEquals(0, r.mine.size)
            assertTrue(r.results.isEmpty())
        }
    }

    @Test
    fun `engine start failure admits nobody - no false positives`() {
        // The shared Karing-style session could not boot → no server can
        // be tested, and the mine must stay empty.
        val e2e = mockk<RealDelayProber>()
        coEvery { e2e.start(any(), any()) } returns false
        coEvery { e2e.probe(any()) } returns RealDelayOutcome(false)
        coEvery { e2e.stop() } returns Unit
        val filler = MineFiller(e2e)
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
        val filler = MineFiller(e2e)
        runBlocking {
            filler.fill(listOf(ProfileServers("p1", "P1", listOf(server("a")))))
        }
        coVerify(exactly = 1) { e2e.start(any(), any()) }
        // The engine session is torn down at least once — the per-chunk
        // finally plus the outer safety net (both idempotent) → ≥ 1 stop.
        coVerify(atLeast = 1) { e2e.stop() }
    }

    @Test
    fun `large catalog is filled through bounded engine sessions`() {
        // 250 servers; healthy = every 17th id (0,17,..238 → 6 live in
        // chunk 1, 6 in chunk 2). capacity = clamp(ceil(250*0.10),3,12) = 12.
        // The fill must NOT emit one 250-outbound config: sessions stay
        // ≤ CHUNK_SIZE and the mine fills across two sessions.
        val healthy = (0 until 250 step 17).toSet()
        val sessions = mutableListOf<List<ServerConfig>>()
        val e2e = mockk<RealDelayProber>()
        coEvery { e2e.start(capture(sessions), any()) } returns true
        coEvery { e2e.probe(any()) } answers {
            val id = firstArg<String>()
            if (id.toInt() in healthy) RealDelayOutcome(true, 60) else RealDelayOutcome(false)
        }
        coEvery { e2e.stop() } returns Unit

        val filler = MineFiller(e2e)
        runBlocking {
            val r = filler.fill(
                listOf(ProfileServers("p1", "P1", (0 until 250).map { server("$it") })),
                e2eParallelism = 8
            )
            assertEquals(12, r.mine.size)
        }
        // chunk 1 admits 6, chunk 2 admits 6 → mine full after session 2
        assertEquals(2, sessions.size)
        // each session is stopped (per-chunk finally) plus the outer safety
        // net → stop counters at least as often as sessions started.
        coVerify(atLeast = sessions.size) { e2e.stop() }
        assertTrue(
            "every engine session must carry at most CHUNK_SIZE candidates",
            sessions.all { it.size <= MineFiller.CHUNK_SIZE }
        )
    }

    @Test
    fun `probe options are forwarded to every engine session`() {
        val options = ProbeOptions(fragmentTls = true, keepAlive = false)
        val e2e = mockk<RealDelayProber>()
        coEvery { e2e.start(any(), any()) } returns true
        coEvery { e2e.probe(any()) } returns RealDelayOutcome(true, 50)
        coEvery { e2e.stop() } returns Unit
        val filler = MineFiller(e2e)
        runBlocking {
            filler.fill(
                listOf(ProfileServers("p1", "P1", listOf(server("a"), server("b")))),
                options = options
            )
        }
        coVerify(exactly = 1) { e2e.start(any(), eq(options)) }
    }

    @Test
    fun `cancellation mid-fill still tears down the engine session`() {
        val e2e = mockk<RealDelayProber>()
        coEvery { e2e.start(any(), any()) } returns true
        coEvery { e2e.probe(any()) } coAnswers {
            delay(10_000)
            RealDelayOutcome(true, 1)
        }
        coEvery { e2e.stop() } returns Unit
        val filler = MineFiller(e2e)
        val job = CoroutineScope(Dispatchers.Default).launch {
            filler.fill(listOf(ProfileServers("p1", "P1", (0 until 20).map { server("s$it") })))
        }
        runBlocking {
            // Let the fill boot the session and park each probe in a long
            // delay before cancelling — otherwise cancel can race the start.
            delay(200)
            job.cancel()
            job.join()
        }
        // stop is idempotent; per-chunk finally + the outer safety net
        coVerify(atLeast = 1) { e2e.stop() }
    }

    // ------------------------------------------------------------------
    // previousMine (warm-start) tests
    // ------------------------------------------------------------------

    @Test
    fun `previousMine empty starts from scratch`() {
        val filler = MineFiller(e2eOf(okIds = setOf("a")))
        runBlocking {
            val r = filler.fill(
                listOf(ProfileServers("p1", "P1", listOf(server("a")))),
                previousMine = emptyList()
            )
            assertEquals(listOf("a"), r.mine.map { it.id })
            assertEquals(1, r.results.size)
        }
    }

    @Test
    fun `previousMine healthy servers seed the mine without re-probing`() {
        // All 3 servers were healthy in the previous run → warm start fills
        // the mine (capacity 3) immediately; no probe calls should fire.
        val prevMine = listOf(server("a"), server("b"), server("c"))
        val e2e = mockk<RealDelayProber>()
        coEvery { e2e.start(any(), any()) } returns true
        coEvery { e2e.probe(any()) } returns RealDelayOutcome(false)
        coEvery { e2e.stop() } returns Unit
        val filler = MineFiller(e2e)
        runBlocking {
            val r = filler.fill(
                listOf(ProfileServers("p1", "P1", listOf(server("a"), server("b"), server("c")))),
                previousMine = prevMine
            )
            assertEquals(3, r.mine.size)
            assertEquals(setOf("a", "b", "c"), r.mine.map { it.id }.toSet())
            // No probes fired — warm start filled the mine (capacity=3) before
            // the wave even started.
            coVerify(exactly = 0) { e2e.probe(any()) }
        }
    }

    @Test
    fun `previousMine dead servers are skipped - only healthy ones enter`() {
        // previousMine has 4 servers; only 2 are actually healthy in the
        // current catalog → those 2 enter, the other 2 are skipped.
        val prevMine = listOf(server("a"), server("b"), server("c"), server("d"))
        val e2e = e2eOf(okIds = setOf("a", "b"))
        val filler = MineFiller(e2e)
        runBlocking {
            val r = filler.fill(
                listOf(ProfileServers("p1", "P1", prevMine)),
                previousMine = prevMine
            )
            // capacity = clamp(ceil(4*0.10),3,12) = 3
            // warm: a, b enter (healthy); c, d fail probe → skipped
            assertEquals(2, r.mine.size)
            assertEquals(setOf("a", "b"), r.mine.map { it.id }.toSet())
        }
    }

    @Test
    fun `orphaned previousMine server (deleted from subscription) is filtered out`() {
        // previousMine has server("x") but it's not in any profile anymore.
        // It should be silently dropped and not enter the mine.
        val prevMine = listOf(server("x")) // deleted from catalog
        val e2e = e2eOf(okIds = emptySet())
        val filler = MineFiller(e2e)
        runBlocking {
            val r = filler.fill(
                listOf(ProfileServers("p1", "P1", listOf(server("a")))),
                previousMine = prevMine
            )
            assertEquals(1, r.mine.size)
            assertEquals("a", r.mine[0].id)
            assertTrue("x must not enter the mine", !r.mine.any { it.id == "x" })
        }
    }

    @Test
    fun `previousMine exactly fills capacity - no probes run`() {
        // 3 servers all healthy in prevMine; capacity=3 → warm start is exact.
        val prevMine = listOf(server("a"), server("b"), server("c"))
        val e2e = mockk<RealDelayProber>()
        coEvery { e2e.start(any(), any()) } returns true
        coEvery { e2e.probe(any()) } returns RealDelayOutcome(false)
        coEvery { e2e.stop() } returns Unit
        val filler = MineFiller(e2e)
        runBlocking {
            val r = filler.fill(
                listOf(ProfileServers("p1", "P1", listOf(server("a"), server("b"), server("c")))),
                previousMine = prevMine
            )
            assertEquals(3, r.mine.size)
            coVerify(exactly = 0) { e2e.start(any(), any()) }
            coVerify(exactly = 0) { e2e.probe(any()) }
            coVerify(atLeast = 1) { e2e.stop() }
        }
    }
}
