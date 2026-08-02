package com.novavpn.domain.probe

import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Mine (معدن) capacity model — the curated reservoir of healthy servers.
 *
 * - Total capacity scales with the number of servers across all profiles:
 *   `clamp(ceil(total × 0.20), 3, 12)`.
 * - Each profile receives a proportional share of the capacity
 *   (`max(1, round(capacity × profileServers / total))`), so one huge
 *   subscription cannot starve the others. A share never exceeds the
 *   number of servers the profile actually has.
 *
 * Pure JVM — unit-testable on the host.
 */
object MineCapacity {

    const val MIN_CAPACITY = 3
    const val MAX_CAPACITY = 12

    /**
     * Target mine capacity ratio: 10% of the catalog (was 20%, reduced
     * to keep only the best relays — a tighter mine means better quality
     * and lower churn on each fill cycle).
     */
    const val TARGET_RATIO: Double = 0.10

    /** Total mine capacity for [totalServers] servers across all profiles. */
    fun capacityOf(totalServers: Int): Int {
        if (totalServers <= 0) return 0
        val scaled = ceil(totalServers * TARGET_RATIO).toInt()
        return scaled.coerceIn(MIN_CAPACITY, MAX_CAPACITY)
    }

    /**
     * The share of the mine reserved for one profile.
     *
     * @param capacity total mine capacity (see [capacityOf]).
     * @param totalServers servers across ALL profiles.
     * @param profileServers servers inside THIS profile.
     */
    fun profileShare(capacity: Int, totalServers: Int, profileServers: Int): Int {
        if (capacity <= 0 || totalServers <= 0 || profileServers <= 0) return 0
        val proportional = (capacity * (profileServers.toDouble() / totalServers)).roundToInt()
        return proportional.coerceIn(1, profileServers)
    }

    /**
     * Per-profile shares for a list of profile sizes. Every non-empty
     * profile gets at least one slot; a share never exceeds the profile's
     * own server count. NOTE: independent proportional rounding can make
     * the SUM of shares exceed [capacity] — the filler stops at capacity
     * regardless, so overshoot is harmless by design.
     */
    fun profileShares(capacity: Int, totalServers: Int, profileSizes: List<Int>): List<Int> =
        profileSizes.map { profileShare(capacity, totalServers, it) }
}
