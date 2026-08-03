package com.novavpn.domain.probe

import kotlinx.serialization.Serializable

/**
 * A persisted config-test result: the server that was tested, its last
 * healthy verdict and the measured end-to-end delay (ms).
 *
 * Persisted so the Test Configs screen keeps its list across app restarts
 * (request v0.17.4): tested servers stay listed with their ping until a NEW
 * re-test of the same server returns negative (only then is it dropped).
 */
@Serializable
data class TestResultEntry(
    val serverId: String = "",
    val ok: Boolean = false,
    val e2eMs: Long? = null,
    val lastTestedAt: Long = 0L
)
