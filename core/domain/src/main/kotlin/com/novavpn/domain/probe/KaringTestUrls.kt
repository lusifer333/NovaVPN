package com.novavpn.domain.probe

/**
 * The Karing-style config-test URLs (mirrors Karing's `SettingConfig.kUrlTestList`):
 * the connectivity-check endpoints the config test may target. [defaultTestUrl]
 * is the FIRST one (gstatic /generate_204), matching Karing's default
 * `urlTest = kUrlTestList[0]`.
 *
 * Lives in the domain layer so both the engine (TrafficProbe) and the UI
 * (config-test screen) can reference it without a module cycle.
 */
object KaringTestUrls {

    const val defaultTestUrl: String = "https://www.gstatic.com/generate_204"

    val all: List<String> = listOf(
        "https://www.gstatic.com/generate_204",
        "http://www.msftconnecttest.com/connecttest.txt",
        "http://cp.cloudflare.com/generate_204",
        "https://checkip.amazonaws.com",
        "http://connectivity-check.ubuntu.com",
        "http://detectportal.firefox.com/success.txt",
    )
}
