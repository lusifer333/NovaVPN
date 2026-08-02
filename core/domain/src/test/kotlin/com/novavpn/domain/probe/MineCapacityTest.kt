package com.novavpn.domain.probe

import org.junit.Assert.assertEquals
import org.junit.Test

class MineCapacityTest {

    // ------------------------------------------------------------------
    // Total capacity: clamp(ceil(total × 30%), 3, 12)
    // ------------------------------------------------------------------

    @Test
    fun `capacity scales with server count`() {
        assertEquals(0, MineCapacity.capacityOf(0))
        assertEquals(3, MineCapacity.capacityOf(5))     // ceil(1.5)=2 → clamped to 3
        assertEquals(3, MineCapacity.capacityOf(10))    // ceil(3)=3
        assertEquals(6, MineCapacity.capacityOf(20))    // ceil(6)=6
        assertEquals(9, MineCapacity.capacityOf(30))    // ceil(9)=9
        assertEquals(12, MineCapacity.capacityOf(50))   // 15 → clamped to 12
        assertEquals(12, MineCapacity.capacityOf(2000)) // capped
    }

    @Test
    fun `capacity never exceeds 12 even for huge profiles`() {
        assertEquals(MineCapacity.MAX_CAPACITY, MineCapacity.capacityOf(10_000))
    }

    @Test
    fun `capacity never drops below 3 for small lists`() {
        assertEquals(3, MineCapacity.capacityOf(1))
        assertEquals(3, MineCapacity.capacityOf(2))
        assertEquals(3, MineCapacity.capacityOf(3))
    }

    // ------------------------------------------------------------------
    // Per-profile proportional shares
    // ------------------------------------------------------------------

    @Test
    fun `share is proportional to profile size`() {
        // 55 servers total → capacity 12; A=5, B=50 → A=1, B=11
        val shares = MineCapacity.profileShares(12, 55, listOf(5, 50))
        assertEquals(1, shares[0])
        assertEquals(11, shares[1])
    }

    @Test
    fun `equal profiles split capacity evenly`() {
        val shares = MineCapacity.profileShares(6, 20, listOf(10, 10))
        assertEquals(3, shares[0])
        assertEquals(3, shares[1])
    }

    @Test
    fun `each profile gets at least one slot`() {
        // Two profiles, one tiny: 199 vs 1 of 200 → capacity 12
        val shares = MineCapacity.profileShares(12, 200, listOf(199, 1))
        assertEquals(12, shares[0]) // 199/200 of 12 → 11.94 → rounds to 12
        assertEquals(1, shares[1])  // tiny profile keeps its guaranteed slot
        // (sum may exceed capacity — proportional rounding; the filler
        // stops at capacity regardless, overshoot is harmless)
    }

    @Test
    fun `share never exceeds the profile's own server count`() {
        assertEquals(1, MineCapacity.profileShare(12, 100, 1))
        assertEquals(1, MineCapacity.profileShare(12, 100, 2)) // 12×0.02=0.24 → round 0 → min 1
        assertEquals(2, MineCapacity.profileShare(12, 100, 20)) // 12×0.20=2.4 → round 2
    }

    @Test
    fun `empty profile gets zero share`() {
        assertEquals(0, MineCapacity.profileShare(12, 100, 0))
        assertEquals(0, MineCapacity.profileShare(0, 100, 5))
    }
}
