package com.novavpn.engine.xray

import com.novavpn.domain.model.Protocol
import com.novavpn.domain.model.Security
import com.novavpn.domain.model.ServerConfig
import com.novavpn.domain.model.Transport
import com.novavpn.domain.probe.KaringTestUrls
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import timber.log.Timber

/**
 * Generates valid Xray-core JSON configuration strings from [ServerConfig] domain models.
 *
 * Produces a full Xray config with:
 * - **Logging** (warning level, no access/error files)
 * - **Inbounds** — SOCKS5 on 127.0.0.1:10808 and HTTP on 127.0.0.1:10809
 * - **Outbounds** — the proxy outbound built from the server config, plus
 *   direct and block fallback outbounds
 * - **Routing** — a catch-all rule that sends traffic from the local inbounds
 *   to the proxy outbound
 *
 * Supported protocols: VMess, VLESS, Trojan, Shadowsocks.
 * Supported security layers: TLS, Reality.
 * Supported transports: TCP, WebSocket, gRPC, QUIC, HTTP.
 *
 * All JSON is constructed via `kotlinx.serialization.json` DSL —
 * no string templating is used.
 */
object XrayConfigParser {

    private const val TAG = "XrayConfig"

    // Hex SHA-256 fingerprints of the worker/panel TLS chain
    // (CN=nahan-1-tarkibi.workers.dev -> GTS WE1 -> GTS Root R4), captured
    // 2026-07-31 from the live chain (openssl s_client -showcerts).
    // Xray >= 26 removed "allowInsecure"; pinnedPeerCertSha256 is the
    // replacement. Pinning the intermediate + root keeps REAL chain
    // verification (leaf -> pinned CA + serverName check) while bypassing
    // device trust stores that lack the Google roots. Update if the panel
    // switches CAs.
    private const val PINNED_PEER_CERT_SHA256 =
        "1dfc1605fbad358d8bc844f76d15203fac9ca5c1a79fd4857ffaf2864fbebf96," +
        "76b27b80a58027dc3cf1da68dac17010ed93997d0b603e2fadbe85012493b5a7"

    // Custom TLS cipher suite list applied to the proxy outbound when TLS
    // fragmentation is enabled: an explicit mixed TLS 1.3/1.2 suite set so
    // the uTLS "random" fingerprint never advertises a suite list that DPI
    // fingerprints (Patterniha method, anti-filter).
    private const val TLS_FRAGMENT_CIPHER_SUITES =
        "TLS_AES_256_GCM_SHA384:TLS_CHACHA20_POLY1305_SHA256:" +
        "TLS_AES_128_GCM_SHA256:TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384:" +
        "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384:TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256:" +
        "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256:TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256:" +
        "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256:TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA:" +
        "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA:TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256:" +
        "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256"

    // RFC 5737-style private range used by the fake-IP pool (same pool
    // sing-box and v2rayN use); routed to the proxy, remapped to real
    // domains by xray's fakeip machinery.
    private const val FAKE_IP_POOL = "198.18.0.0/15"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Convert a [ServerConfig] into a complete Xray JSON configuration string
     * with SOCKS5 proxy inbound.  TUN fd management is handled exclusively
     * by NovaVpnService and hev-socks5-tunnel bridge.
     *
     * @param config The parsed server configuration to convert.
     * @param dnsServers DNS server addresses to use.
     * @param routes Routes to forward through the VPN.
     * @return A pretty-printed Xray JSON string.
     */
    fun toXrayJson(
        config: ServerConfig,
        dnsServers: List<String> = listOf("8.8.8.8", "1.1.1.1"),
        routes: List<String> = listOf("0.0.0.0/0"),
        logDir: String? = null,
        blockQuic: Boolean = false,
        fragmentTls: Boolean = false,
        keepAlive: Boolean = true,
        fakeDns: Boolean = false
    ): String {
        val root = buildJsonObject {
            put("log", buildLogSection(logDir))
            put("inbounds", buildInbounds(blockQuic, fakeDns))
            put("outbounds", buildOutbounds(config, fragmentTls, keepAlive, fakeDns))
            put("routing", buildRouting(blockQuic, fakeDns))
            put("dns", buildDns(dnsServers, fakeDns))
            put("policy", buildPolicy())
        }
        val jsonStr = Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), root)
        Timber.tag(TAG).d("Generated Xray config:\n%s", jsonStr)
        // Log inbound info for diagnostic
        val inbounds = root["inbounds"]?.jsonArray
        val firstInbound = inbounds?.get(0)?.jsonObject
        val inboundProto = firstInbound?.get("protocol")?.jsonPrimitive?.content ?: "unknown"
        Timber.tag(TAG).i("INBOUND_TYPE=%s, numInbounds=%d",
            inboundProto, inbounds?.size ?: 0)
        return jsonStr
    }

    /**
     * Karing-style urltest harness config: bounded engine sessions that
     * test chunked candidate lists (see [MineFiller.CHUNK_SIZE]).
     *
     * Each candidate gets its own SOCKS5 inbound (`basePort + index`) whose
     * routing rule pins it to that server's outbound — so the mine filler
     * probes server #i by dialing 127.0.0.1:(basePort+i) and the test
     * request actually RELAYS through server #i. This mirrors sing-box's
     * `urltest` outbound (many outbounds in one core, each tested with a
     * real HTTP round-trip): one process, zero per-server spawn churn.
     *
     * @param servers candidate servers; each becomes one outbound (tag
     *   `probe-out-<i>`) and one socks inbound (tag `probe-in-<i>`).
     * @param basePort first probe inbound port; port i sits at basePort + i.
     * @param logDir when non-null, xray writes debug access/error logs there.
     * @param fragmentTls apply Patterniha TLS fragmentation to eligible
     *   (TLS-over-TCP) probe outbounds — same rule as the real config:
     *   Reality and QUIC are never fragmented.
     * @param keepAlive emit client-side TCP keepalive sockopt on probe
     *   outbounds (matches the engine default).
     */
    fun buildMineConfig(
        servers: List<ServerConfig>,
        basePort: Int,
        logDir: String? = null,
        fragmentTls: Boolean = false,
        keepAlive: Boolean = true
    ): String {
        val root = buildJsonObject {
            put("log", buildLogSection(logDir))
            put("inbounds", buildJsonArray {
                servers.forEachIndexed { i, _ ->
                    add(buildJsonObject {
                        put("listen", JsonPrimitive("127.0.0.1"))
                        put("port", basePort + i)
                        put("protocol", JsonPrimitive("socks"))
                        put("settings", buildJsonObject {
                            put("auth", JsonPrimitive("noauth"))
                            put("udp", JsonPrimitive(false))
                        })
                        put("tag", JsonPrimitive("probe-in-$i"))
                    })
                }
            })
            put("outbounds", buildJsonArray {
                if (servers.any { fragmentEligible(it, fragmentTls) }) {
                    add(buildFragmentOutbound())
                }
                servers.forEachIndexed { i, server ->
                    add(buildProxyOutbound(server, fragmentTls, keepAlive, tag = "probe-out-$i"))
                }
                add(buildDirectOutbound())
                add(buildBlockOutbound())
            })
            put("routing", buildJsonObject {
                put("domainStrategy", JsonPrimitive("AsIs"))
                put("rules", buildJsonArray {
                    servers.forEachIndexed { i, _ ->
                        add(buildJsonObject {
                            put("type", JsonPrimitive("field"))
                            put("inboundTag", buildJsonArray { add(JsonPrimitive("probe-in-$i")) })
                            put("outboundTag", JsonPrimitive("probe-out-$i"))
                        })
                    }
                    // Catch-all: anything unexpected (stray packet, sniffed
                    // leak) goes direct instead of looping or dying.
                    add(buildJsonObject {
                        put("type", JsonPrimitive("field"))
                        put("network", JsonPrimitive("tcp,udp"))
                        put("outboundTag", JsonPrimitive("direct"))
                    })
                })
            })
        }
        return Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), root)
    }

    // ------------------------------------------------------------------
    // Log section
    // ------------------------------------------------------------------

    /**
     * Build a Karing-style AUTO-CONNECT (balancer) runtime config.
     *
     * Unlike [toXrayJson] (exactly one outbound), this config carries ALL
     * candidate servers as outbounds (tags `bal-out-<i>`), an `observatory`
     * (burst health ping — REAL HTTP probe, exactly like sing-box urltest)
     * and a `routing.balancers` leastping group (tag `bal-0`) that selects
     * the fastest ALIVE server continuously, in-core. The SOCKS inbound
     * routes to the balancer, so every connection automatically goes to the
     * best currently-healthy server — no external polling, no engine restart
     * on switch. This is the native Xray 26 equivalent of Karing's
     * sing-box urltest outbound (proved working on 26.3.27: dead outbounds
     * are marked dead by the observatory and traffic routes to the alive one).
     *
     * @param servers candidate servers; each becomes outbound `bal-out-<i>`.
     * @param logDir when non-null, debug logs (access + error) are written
     *        there — REQUIRED for active-outbound detection: the access log
     *        carries `taking platform initialized detour [bal-out-N]`.
     * @param probeUrl the observatory health-check URL (Karing urlTest).
     * @param probeIntervalMs how often the observatory re-pings (Karing
     *        tests continuously; 10s is the loop default elsewhere).
     * @param probeTimeoutMs per-attempt health-ping timeout.
     * @param fragmentTls apply TLS fragmentation to eligible outbounds.
     * @param keepAlive client TCP keepalive sockopt on outbounds.
     */
    fun buildBalancerConfig(
        servers: List<ServerConfig>,
        logDir: String? = null,
        probeUrl: String = KaringTestUrls.defaultTestUrl,
        probeIntervalMs: Long = 10_000L,
        probeTimeoutMs: Int = 3_500,
        fragmentTls: Boolean = false,
        keepAlive: Boolean = true
    ): String {
        val root = buildJsonObject {
            put("log", buildLogSection(logDir))
            put("inbounds", buildJsonArray {
                add(buildJsonObject {
                    put("listen", JsonPrimitive("127.0.0.1"))
                    put("port", 10808)
                    put("protocol", JsonPrimitive("socks"))
                    put("settings", buildJsonObject {
                        put("auth", JsonPrimitive("noauth"))
                        put("udp", JsonPrimitive(true))
                    })
                    put("tag", JsonPrimitive("socks-in"))
                    put("sniffing", buildSniffing(includeQuic = false))
                })
            })
            put("outbounds", buildJsonArray {
                if (servers.any { fragmentEligible(it, fragmentTls) }) {
                    add(buildFragmentOutbound())
                }
                servers.forEachIndexed { i, server ->
                    add(buildProxyOutbound(server, fragmentTls, keepAlive, tag = "bal-out-$i"))
                }
                add(buildDirectOutbound())
                add(buildBlockOutbound())
            })
            // In-core health ping — the native Xray 26 urltest.
            put("observatory", buildJsonObject {
                put("subjectSelector", buildJsonArray { add(JsonPrimitive("bal-out-")) })
                put("probeURL", JsonPrimitive(probeUrl))
                put("probeInterval", JsonPrimitive("${probeIntervalMs}ms"))
                put("enableConcurrency", JsonPrimitive(true))
            })
            put("routing", buildJsonObject {
                put("domainStrategy", JsonPrimitive("AsIs"))
                put("rules", buildJsonArray {
                    add(buildJsonObject {
                        put("type", JsonPrimitive("field"))
                        put("inboundTag", buildJsonArray { add(JsonPrimitive("socks-in")) })
                        put("balancerTag", JsonPrimitive("bal-0"))
                    })
                    // Catch-all: unexpected traffic goes direct, never loops.
                    add(buildJsonObject {
                        put("type", JsonPrimitive("field"))
                        put("network", JsonPrimitive("tcp,udp"))
                        put("outboundTag", JsonPrimitive("direct"))
                    })
                })
                put("balancers", buildJsonArray {
                    add(buildJsonObject {
                        put("tag", JsonPrimitive("bal-0"))
                        put("selector", buildJsonArray { add(JsonPrimitive("bal-out-")) })
                        put("strategy", buildJsonObject {
                            put("type", JsonPrimitive("leastping"))
                        })
                    })
                })
            })
        }
        return Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), root)
    }

    // ------------------------------------------------------------------
    // Log section
    // ------------------------------------------------------------------

    private fun buildLogSection(logDir: String? = null): JsonObject = buildJsonObject {
        if (logDir != null) {
            // Diagnostic mode: full debug + file logs so the app can mirror
            // Xray's per-connection/routing/outbound activity into logcat.
            put("loglevel", JsonPrimitive("debug"))
            put("access", JsonPrimitive("$logDir/access.log"))
            put("error", JsonPrimitive("$logDir/error.log"))
        } else {
            put("loglevel", JsonPrimitive("warning"))
            put("access", JsonPrimitive("/dev/null"))
            put("error", JsonPrimitive("/dev/null"))
        }
    }

    // ------------------------------------------------------------------
    // Inbounds
    // ------------------------------------------------------------------

    /**
     * SOCKS5 + HTTP inbounds for local proxy forwarding.
     *
     * Xray acts as a pure SOCKS5 proxy; hev-socks5-tunnel bridges TUN traffic
     * to the SOCKS5 port.  TUN fd management is NovaVpnService's responsibility.
     *
     * SOCKS5 and HTTP inbounds are kept for debugging: if Xray doesn't work,
     * users can test with a local proxy client at 127.0.0.1:10808/10809.
     */
    private fun buildInbounds(blockQuic: Boolean = false, fakeDns: Boolean = false): JsonArray = buildJsonArray {
        // SOCKS5 inbound for VPN traffic forwarding
        add(buildJsonObject {
            put("listen", JsonPrimitive("127.0.0.1"))
            put("port", 10808)
            put("protocol", JsonPrimitive("socks"))
            put("settings", buildJsonObject {
                put("auth", JsonPrimitive("noauth"))
                put("udp", JsonPrimitive(true))
            })
            put("tag", JsonPrimitive("socks-in"))
            if (blockQuic || fakeDns) {
                // Detect QUIC (UDP 443) so the routing rule can drop it:
                // browsers then fall back to TCP and dodge DPI tampering.
                // FakeDNS needs sniffing too: fake-IP connections are
                // remapped to their real domains via the sniffed name.
                put("sniffing", buildSniffing(includeQuic = blockQuic))
            }
        })
        // HTTP proxy fallback
        add(buildJsonObject {
            put("listen", JsonPrimitive("127.0.0.1"))
            put("port", 10809)
            put("protocol", JsonPrimitive("http"))
            put("settings", buildJsonObject { })
            put("tag", JsonPrimitive("http-in"))
            if (blockQuic || fakeDns) {
                put("sniffing", buildSniffing(includeQuic = blockQuic))
            }
        })
    }

    /**
     * Sniffing config: http + tls always (fake-IP remap depends on the
     * recovered domain), quic only when the "Block QUIC" toggle is on.
     */
    private fun buildSniffing(includeQuic: Boolean = false): JsonObject = buildJsonObject {
        put("enabled", JsonPrimitive(true))
        put("destOverride", buildJsonArray {
            add(JsonPrimitive("http"))
            add(JsonPrimitive("tls"))
            if (includeQuic) {
                add(JsonPrimitive("quic"))
            }
        })
    }

    // ------------------------------------------------------------------
    // Outbounds
    // ------------------------------------------------------------------

    /**
     * Builds the full outbounds array:
     * 0. Fragment-out (freedom) — ONLY when TLS fragmentation applies to
     *    this server (TLS over a TCP transport; Reality and QUIC are
     *    never fragmented — a fragmented Reality ClientHello breaks the
     *    server's auth, and fragmentation is TCP-only so QUIC would die)
     * 1. Proxy outbound (built from [config])
     * 2. dns-out — the built-in DNS module as an outbound, so FakeDNS
     *    routing can answer app DNS queries locally (fake-IP pool)
     * 3. Direct (freedom) outbound — fallback for non-proxied traffic
     * 4. Block outbound — drops traffic that should be blocked
     */
    private fun buildOutbounds(
        config: ServerConfig,
        fragmentTls: Boolean = false,
        keepAlive: Boolean = true,
        fakeDns: Boolean = false
    ): JsonArray = buildJsonArray {
        if (fragmentEligible(config, fragmentTls)) {
            add(buildFragmentOutbound())
        }
        add(buildProxyOutbound(config, fragmentTls, keepAlive))
        if (fakeDns) {
            add(buildDnsOutbound())
        }
        add(buildDirectOutbound())
        add(buildBlockOutbound())
    }

    /**
     * Build the main proxy outbound from the server configuration.
     * Dispatches to protocol-specific builders.
     */
    private fun buildProxyOutbound(
        config: ServerConfig,
        fragmentTls: Boolean = false,
        keepAlive: Boolean = true,
        tag: String = "proxy"
    ): JsonObject = buildJsonObject {
        put("tag", JsonPrimitive(tag))

        when (config.protocol) {
            Protocol.VMess -> {
                put("protocol", JsonPrimitive("vmess"))
                put("settings", buildVmessSettings(config))
                put("streamSettings", buildStreamSettings(config, fragmentTls, keepAlive))
            }
            Protocol.VLESS -> {
                put("protocol", JsonPrimitive("vless"))
                put("settings", buildVlessSettings(config))
                put("streamSettings", buildStreamSettings(config, fragmentTls, keepAlive))
            }
            Protocol.Trojan -> {
                put("protocol", JsonPrimitive("trojan"))
                put("settings", buildTrojanSettings(config))
                put("streamSettings", buildStreamSettings(config, fragmentTls, keepAlive))
            }
            Protocol.Shadowsocks -> {
                put("protocol", JsonPrimitive("shadowsocks"))
                put("settings", buildShadowsocksSettings(config))
            }
            Protocol.SOCKS5 -> {
                put("protocol", JsonPrimitive("socks"))
                put("settings", buildSocksSettings(config))
                put("streamSettings", buildStreamSettings(config, fragmentTls, keepAlive))
            }
            Protocol.HTTP -> {
                put("protocol", JsonPrimitive("http"))
                put("settings", buildHttpSettings(config))
                put("streamSettings", buildStreamSettings(config, fragmentTls, keepAlive))
            }
            Protocol.Unknown -> {
                put("protocol", JsonPrimitive("freedom"))
                put("settings", buildJsonObject { })
            }
        }
    }

    // ------------------------------------------------------------------
    // Protocol-specific settings builders
    // ------------------------------------------------------------------

    private fun buildVmessSettings(config: ServerConfig): JsonObject {
        val raw = parseRawConfig(config.rawConfig)
        val id = raw?.get("id")?.jsonPrimitive?.content ?: ""
        val aid = raw?.get("aid")?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        val security = raw?.get("security")?.jsonPrimitive?.content ?: "auto"

        return buildJsonObject {
            put("vnext", buildJsonArray {
                add(buildJsonObject {
                    put("address", JsonPrimitive(config.address))
                    put("port", JsonPrimitive(config.port))
                    put("users", buildJsonArray {
                        add(buildJsonObject {
                            put("id", JsonPrimitive(id))
                            put("alterId", JsonPrimitive(aid))
                            put("security", JsonPrimitive(security))
                        })
                    })
                })
            })
        }
    }

    private fun buildVlessSettings(config: ServerConfig): JsonObject {
        // Try parsing rawConfig as JSON first (new format from buildVlessRawJson)
        var raw = parseRawConfig(config.rawConfig)
        var id = raw?.get("id")?.jsonPrimitive?.content ?: ""
        var encryption = raw?.get("encryption")?.jsonPrimitive?.content ?: "none"
        var flow = raw?.get("flow")?.jsonPrimitive?.content

        // Fallback for legacy vless:// URL format stored in DB
        if (raw == null && config.rawConfig.startsWith("vless://")) {
            try {
                val withoutPrefix = config.rawConfig.removePrefix("vless://")
                val withoutHash = withoutPrefix.split("#").first()
                val withoutQuery = withoutHash.split("?").first()
                val atIdx = withoutQuery.indexOf('@')
                if (atIdx >= 0) {
                    id = withoutQuery.substring(0, atIdx)
                }
                // Try to extract flow and encryption from query params
                val qIdx = withoutHash.indexOf('?')
                if (qIdx >= 0) {
                    val queryStr = withoutHash.substring(qIdx + 1)
                    val qParams = queryStr.split("&").mapNotNull {
                        val eq = it.split("=", limit = 2)
                        if (eq.size == 2) eq[0] to eq[1] else null
                    }.toMap()
                    encryption = qParams["encryption"] ?: "none"
                    flow = qParams["flow"]
                }
                Timber.tag(TAG).d("Parsed VLESS id from legacy URL: %s", id.take(8))
            } catch (e: Exception) {
                Timber.tag(TAG).w("Failed to parse legacy VLESS URL: %s", e.message)
            }
        }

        val finalId = id
        val finalEncryption = encryption
        val finalFlow = flow

        return buildJsonObject {
            put("vnext", buildJsonArray {
                add(buildJsonObject {
                    put("address", JsonPrimitive(config.address))
                    put("port", JsonPrimitive(config.port))
                    put("users", buildJsonArray {
                        add(buildJsonObject {
                            put("id", JsonPrimitive(finalId))
                            put("encryption", JsonPrimitive(finalEncryption))
                            if (finalFlow != null) put("flow", JsonPrimitive(finalFlow))
                        })
                    })
                })
            })
        }
    }

    private fun buildTrojanSettings(config: ServerConfig): JsonObject {
        var raw = parseRawConfig(config.rawConfig)
        var password = raw?.get("password")?.jsonPrimitive?.content ?: ""
        var flow = raw?.get("flow")?.jsonPrimitive?.content

        // Fallback for legacy trojan:// URL format
        if (raw == null && config.rawConfig.startsWith("trojan://")) {
            try {
                val withoutPrefix = config.rawConfig.removePrefix("trojan://")
                val withoutHash = withoutPrefix.split("#").first()
                val withoutQuery = withoutHash.split("?").first()
                val atIdx = withoutQuery.indexOf('@')
                if (atIdx >= 0) {
                    password = withoutQuery.substring(0, atIdx)
                }
                val qIdx = withoutHash.indexOf('?')
                if (qIdx >= 0) {
                    val qParams = withoutHash.substring(qIdx + 1).split("&").mapNotNull {
                        val eq = it.split("=", limit = 2)
                        if (eq.size == 2) eq[0] to eq[1] else null
                    }.toMap()
                    flow = qParams["flow"]
                }
            } catch (_: Exception) { }
        }

        val finalPassword = password
        val finalFlow = flow

        return buildJsonObject {
            put("servers", buildJsonArray {
                add(buildJsonObject {
                    put("address", JsonPrimitive(config.address))
                    put("port", JsonPrimitive(config.port))
                    put("password", JsonPrimitive(finalPassword))
                    if (finalFlow != null) put("flow", JsonPrimitive(finalFlow))
                })
            })
        }
    }

    private fun buildShadowsocksSettings(config: ServerConfig): JsonObject {
        var raw = parseRawConfig(config.rawConfig)
        var password = raw?.get("password")?.jsonPrimitive?.content ?: ""
        var method = raw?.get("method")?.jsonPrimitive?.content ?: "aes-256-gcm"
        var plugin = raw?.get("plugin")?.jsonPrimitive?.content
        var pluginOpts = raw?.get("pluginOpts")?.jsonPrimitive?.content
            ?: raw?.get("plugin_opts")?.jsonPrimitive?.content

        // Fallback for legacy ss:// URL format
        if (raw == null && config.rawConfig.startsWith("ss://")) {
            try {
                val stripped = config.rawConfig.removePrefix("ss://")
                val withoutHash = stripped.split("#").first()
                val qIdx = withoutHash.indexOf('?')
                val userInfo = if (qIdx >= 0) withoutHash.substring(0, qIdx) else withoutHash
                val queryStr = if (qIdx >= 0) withoutHash.substring(qIdx + 1) else ""
                // Parse query params inline
                val qParams = if (queryStr.isNotBlank()) {
                    queryStr.split("&").mapNotNull {
                        val eq = it.split("=", limit = 2)
                        if (eq.size == 2) eq[0] to eq[1] else null
                    }.toMap()
                } else emptyMap()
                plugin = qParams["plugin"]
                pluginOpts = qParams["pluginOpts"] ?: qParams["plugin_opts"]

                val atIdx = userInfo.indexOf('@')
                if (atIdx >= 0) {
                    val encodedCreds = userInfo.substring(0, atIdx)
                    val decoded = try {
                        val b64 = encodedCreds.replace("-", "+").replace("_", "/")
                        String(java.util.Base64.getDecoder().decode(b64))
                    } catch (_: Exception) { null }
                    if (decoded != null) {
                        val colonIdx = decoded.indexOf(':')
                        if (colonIdx >= 0) {
                            method = decoded.substring(0, colonIdx)
                            password = decoded.substring(colonIdx + 1)
                        }
                    }
                }
            } catch (_: Exception) { }
        }

        return buildJsonObject {
            put("servers", buildJsonArray {
                add(buildJsonObject {
                    put("address", JsonPrimitive(config.address))
                    put("port", JsonPrimitive(config.port))
                    put("method", JsonPrimitive(method))
                    put("password", JsonPrimitive(password))
                    if (plugin != null) put("plugin", JsonPrimitive(plugin))
                    if (pluginOpts != null) put("plugin_opts", JsonPrimitive(pluginOpts))
                })
            })
        }
    }

    private fun buildSocksSettings(config: ServerConfig): JsonObject {
        val raw = parseRawConfig(config.rawConfig)
        val username = raw?.get("username")?.jsonPrimitive?.content
        val password = raw?.get("password")?.jsonPrimitive?.content

        return buildJsonObject {
            put("servers", buildJsonArray {
                add(buildJsonObject {
                    put("address", JsonPrimitive(config.address))
                    put("port", JsonPrimitive(config.port))
                    if (username != null) {
                        put("users", buildJsonArray {
                            add(buildJsonObject {
                                put("user", JsonPrimitive(username))
                                if (password != null) put("pass", JsonPrimitive(password))
                            })
                        })
                    }
                })
            })
        }
    }

    private fun buildHttpSettings(config: ServerConfig): JsonObject {
        val raw = parseRawConfig(config.rawConfig)
        val username = raw?.get("username")?.jsonPrimitive?.content
        val password = raw?.get("password")?.jsonPrimitive?.content

        return buildJsonObject {
            put("servers", buildJsonArray {
                add(buildJsonObject {
                    put("address", JsonPrimitive(config.address))
                    put("port", JsonPrimitive(config.port))
                    if (username != null) {
                        put("users", buildJsonArray {
                            add(buildJsonObject {
                                put("user", JsonPrimitive(username))
                                if (password != null) put("pass", JsonPrimitive(password))
                            })
                        })
                    }
                })
            })
        }
    }

    // ------------------------------------------------------------------
    // Stream settings (transport + security)
    // ------------------------------------------------------------------

    /**
     * Whether Patterniha TLS fragmentation applies to [config]:
     * ONLY plain TLS over a TCP transport. Reality must keep its real
     * ClientHello (fragmenting it breaks the server's auth handshake) and
     * QUIC is UDP — freedom's fragment dialer is TCP-only, so wiring it
     * into a QUIC outbound kills the stream instantly.
     */
    private fun fragmentEligible(config: ServerConfig, fragmentTls: Boolean): Boolean =
        fragmentTls && config.security == Security.TLS && config.transport != Transport.QUIC

    /**
     * Build the `streamSettings` block based on the config's transport and
     * security fields. Reality is a transport-level security layer (part of
     * streamSettings), while TLS is indicated by setting the security field.
     *
     * NOTE (Xray 26): the old QUIC transport (network:"quic") was REMOVED and
     * migrated to XHTTP stream-one H3 (network:"xhttp"). A `Transport.QUIC`
     * config therefore compiles to xhttp H3, NOT to a "quic" network — the
     * latter is rejected by xray 26 (exit error "The feature QUIC transport...
     * has been removed and migrated to XHTTP stream-one H3").
     */
    private fun buildStreamSettings(
        config: ServerConfig,
        fragmentTls: Boolean = false,
        keepAlive: Boolean = true
    ): JsonObject = buildJsonObject {
        // Network (transport protocol). QUIC is migrated to XHTTP H3 in Xray 26.
        val network = when (config.transport) {
            Transport.TCP -> "tcp"
            Transport.WebSocket -> "ws"
            Transport.XHTTP, Transport.QUIC -> "xhttp"
            Transport.gRPC -> "grpc"
            Transport.HTTP -> "http"
            Transport.Unknown -> "tcp"
        }
        put("network", JsonPrimitive(network))

        // Security layer
        val fragment = fragmentEligible(config, fragmentTls)
        when (config.security) {
            Security.TLS -> {
                put("security", JsonPrimitive("tls"))
                put("tlsSettings", buildTlsSettings(config, fragment))
            }
            Security.Reality -> {
                put("security", JsonPrimitive("reality"))
                put("realitySettings", buildRealitySettings(config))
            }
            Security.None -> {
                put("security", JsonPrimitive("none"))
            }
            Security.Unknown -> {
                put("security", JsonPrimitive("none"))
            }
        }

        // Transport-specific settings.
        when (config.transport) {
            Transport.WebSocket -> put("wsSettings", buildWsSettings(config))
            Transport.XHTTP, Transport.QUIC -> put("xhttpSettings", buildXhttpSettings(config))
            Transport.gRPC -> put("grpcSettings", buildGrpcSettings(config))
            Transport.HTTP -> put("httpSettings", buildHttpTransportSettings(config))
            else -> { /* TCP needs no extra settings */ }
        }

        // TCP keepalive on the underlying connection: prevents middlebox /
        // Cloudflare idle-drops from silently killing WS sessions (the
        // recurring "websocket: close 1005 (no status)" churn on dead UDP
        // associations). Validated against the real Xray 26.3.27 binary
        // (xray -test -> Configuration OK). User-toggleable via settings.
        put("sockopt", buildJsonObject {
            if (keepAlive) {
                put("tcpKeepAliveIdle", JsonPrimitive(60))
                put("tcpKeepAliveInterval", JsonPrimitive(15))
            }
            // Fragment is TCP-only: H3 (migrated QUIC) must NOT be fragmented.
            if (fragment) {
                // Patterniha TLS fragmentation: route the proxy dial through
                // the fragment-out freedom outbound so the TLS ClientHello is
                // split into small pieces (5-94 B, ~1 ms apart) that DPI can't
                // reassemble into a full fingerprintable handshake.
                put("dialerProxy", JsonPrimitive("fragment-out"))
            }
        })
    }

    private fun buildTlsSettings(config: ServerConfig, fragmentTls: Boolean = false): JsonObject {
        val raw = parseRawConfig(config.rawConfig)
        val serverName = raw?.get("serverName")?.jsonPrimitive?.content
            ?: raw?.get("sni")?.jsonPrimitive?.content
            ?: raw?.get("host")?.jsonPrimitive?.content
            ?: config.address
        val fingerprint = if (fragmentTls) {
            // Blind the DPI: randomized uTLS fingerprint so the handshake
            // never matches a known browser profile byte-for-byte.
            "random"
        } else {
            raw?.get("fingerprint")?.jsonPrimitive?.content ?: "chrome"
        }
        val alpn = raw?.get("alpn")?.jsonPrimitive?.content
        val allowInsecure = raw?.get("allowInsecure")?.jsonPrimitive?.content
        // Worker/panel endpoints are reached by IP while the device's system
        // trust store may not resolve the chain's roots (e.g. Google Trust
        // Services GTS Root R4 missing on custom ROMs) -> dial fails with
        // "x509: certificate signed by unknown authority" and every request
        // retries forever (session storm).
        // Xray >= 26 REMOVED "allowInsecure" (config rejected, exit 23); the
        // replacement is "pinnedPeerCertSha256" (comma-separated hex SHA-256
        // fingerprints). Pinning the panel chain's intermediate + root keeps
        // real chain verification while bypassing the broken device pool.
        // Explicit allowInsecure=0/false in the link opts back into plain
        // system-store verification (no pins emitted).
        val insecure = allowInsecure != "0" && allowInsecure != "false"

        return buildJsonObject {
            put("serverName", JsonPrimitive(serverName))
            put("fingerprint", JsonPrimitive(fingerprint))
            if (fragmentTls) {
                // Explicit mixed TLS 1.3/1.2 suite list — never advertise the
                // server's default suite set (anti-DPI, Patterniha method).
                put("cipherSuites", JsonPrimitive(TLS_FRAGMENT_CIPHER_SUITES))
            }
            if (insecure) {
                put("pinnedPeerCertSha256", JsonPrimitive(PINNED_PEER_CERT_SHA256))
            }
            if (alpn != null) {
                val _alpnArr = buildJsonArray {
                    alpn.split(",").forEach { add(JsonPrimitive(it.trim())) }
                }
                put("alpn", _alpnArr)
            }
        }
    }

    private fun buildRealitySettings(config: ServerConfig): JsonObject {
        val raw = parseRawConfig(config.rawConfig)
        val serverName = raw?.get("serverName")?.jsonPrimitive?.content
            ?: raw?.get("sni")?.jsonPrimitive?.content
            ?: config.address
        val fingerprint = raw?.get("fingerprint")?.jsonPrimitive?.content ?: "chrome"
        val publicKey = raw?.get("publicKey")?.jsonPrimitive?.content ?: ""
        val shortId = raw?.get("shortId")?.jsonPrimitive?.content ?: ""
        val spiderX = raw?.get("spiderX")?.jsonPrimitive?.content ?: ""

        return buildJsonObject {
            put("serverName", JsonPrimitive(serverName))
            put("fingerprint", JsonPrimitive(fingerprint))
            put("publicKey", JsonPrimitive(publicKey))
            put("shortId", JsonPrimitive(shortId))
            put("spiderX", JsonPrimitive(spiderX))
        }
    }

    private fun buildWsSettings(config: ServerConfig): JsonObject {
        val raw = parseRawConfig(config.rawConfig)
        val path = raw?.get("path")?.jsonPrimitive?.content ?: "/"
        val host = raw?.get("host")?.jsonPrimitive?.content
            ?: raw?.get("headers")?.jsonObject?.get("Host")?.jsonPrimitive?.content

        return buildJsonObject {
            put("path", JsonPrimitive(path))
            // Independent 'host' property — the old 'headers.Host' is deprecated
            if (host != null) {
                put("host", JsonPrimitive(host))
            }
        }
    }

    /**
     * Build `xhttpSettings` for the XHTTP transport (and for migrated QUIC→H3).
     *
     * Xray 26 `SplitHTTPConfig.host` is a STRING (an array is rejected:
     * "json: cannot unmarshal array into Go struct field
     * SplitHTTPConfig.outbounds.streamSettings.xhttpSettings.host of type
     * string"). `mode` selects the connection model: "auto" lets the client
     * negotiate HTTP/2 or H3; for a config that was originally QUIC (H3), the
     * caller keeps "auto" and Xray falls back to H3 when the server supports it.
     */
    private fun buildXhttpSettings(config: ServerConfig): JsonObject {
        val raw = parseRawConfig(config.rawConfig)
        val host = raw?.get("host")?.jsonPrimitive?.content
            ?: raw?.get("headers")?.jsonObject?.get("Host")?.jsonPrimitive?.content
            ?: raw?.get("serverName")?.jsonPrimitive?.content
            ?: raw?.get("sni")?.jsonPrimitive?.content
        val path = raw?.get("path")?.jsonPrimitive?.content ?: "/"
        val mode = raw?.get("mode")?.jsonPrimitive?.content ?: "auto"

        return buildJsonObject {
            put("mode", JsonPrimitive(mode))
            put("path", JsonPrimitive(path))
            if (host != null) {
                put("host", JsonPrimitive(host))
            }
        }
    }

    private fun buildGrpcSettings(config: ServerConfig): JsonObject {
        val raw = parseRawConfig(config.rawConfig)
        val serviceName = raw?.get("serviceName")?.jsonPrimitive?.content ?: ""

        return buildJsonObject {
            put("serviceName", JsonPrimitive(serviceName))
            put("multiMode", JsonPrimitive(false))
        }
    }

    private fun buildHttpTransportSettings(config: ServerConfig): JsonObject {
        val raw = parseRawConfig(config.rawConfig)
        val path = raw?.get("path")?.jsonPrimitive?.content ?: "/"
        val host = raw?.get("host")?.jsonPrimitive?.content

        return buildJsonObject {
            put("path", JsonPrimitive(path))
            if (host != null) {
                val _hostArr = buildJsonArray {
                    host.split(",").forEach { add(JsonPrimitive(it.trim())) }
                }
                put("host", _hostArr)
            }
        }
    }

    // ------------------------------------------------------------------
    // Static outbounds (direct + block)
    // ------------------------------------------------------------------

    private fun buildDirectOutbound(): JsonObject = buildJsonObject {
        put("protocol", JsonPrimitive("freedom"))
        put("tag", JsonPrimitive("direct"))
    }

    private fun buildBlockOutbound(): JsonObject = buildJsonObject {
        put("protocol", JsonPrimitive("blackhole"))
        put("tag", JsonPrimitive("block"))
    }

    /**
     * Patterniha-style TLS fragmentation outbound (Xray 26 format).
     *
     * The classic values ("lengths": ["5","94","1"], "delays": ["0"],
     * "maxSplit": "0") come from the pre-25.x freedom.fragment schema. Xray
     * 26 replaced the string arrays with Int32Range fields and RE-COMBINES
     * the fragments when the interval is 0 (freedom.go: "combine fragmented
     * tlshello if interval is 0") — so the faithful modern equivalent is:
     *   length "5-94"   → chunks of 5..94 B (mirrors the old cyclic 5/94/1)
     *   interval "1-1"  → 1 ms between pieces (non-zero so pieces actually
     *                     hit the wire as separate TCP segments)
     *   maxSplit "0-0"  → unlimited splits (like the old "0")
     * Validated with `xray -test` (Configuration OK) and a live local sink:
     * a 1706-byte ClientHello arrived in 32 separate TCP writes of 12-97 B.
     */
    private fun buildFragmentOutbound(): JsonObject = buildJsonObject {
        put("tag", JsonPrimitive("fragment-out"))
        put("protocol", JsonPrimitive("freedom"))
        put("settings", buildJsonObject {
            put("fragment", buildJsonObject {
                put("packets", JsonPrimitive("tlshello"))
                put("length", JsonPrimitive("5-94"))
                put("interval", JsonPrimitive("1-1"))
                put("maxSplit", JsonPrimitive("0-0"))
            })
        })
    }

    // ------------------------------------------------------------------
    // Routing
    // ------------------------------------------------------------------

    /**
     * Routing: traffic from TUN inbound gets sent to the proxy outbound.
     * SOCKS/HTTP inbounds (fallback) also route to proxy for testing.
     * DNS traffic on port 53 is explicitly routed to proxy to prevent
     * DNS_PROBE_POSSIBLE errors.
     *
     * With FakeDNS enabled, port-53 queries are answered LOCALLY by the
     * built-in DNS module (fake-IP pool, 198.18.0.0/15): apps get fake
     * IPs, connect to them, and xray remaps the connection to the real
     * domain via sniffing. The fake-IP range is routed to the proxy.
     */
    private fun buildRouting(blockQuic: Boolean = false, fakeDns: Boolean = false): JsonObject = buildJsonObject {
        // IPIfNonMatch: with fake-IP addresses in play, try the domain
        // rules first; only fall back to IP matching when the destination
        // has no known domain (the fake-IP pool rule below).
        put("domainStrategy", JsonPrimitive(if (fakeDns) "IPIfNonMatch" else "AsIs"))
        put("rules", buildJsonArray {
            if (blockQuic) {
                // MUST be first: Xray matches rules top-down, and the
                // catch-all inbound rule below would otherwise grab QUIC
                // before it reaches this one.
                add(buildJsonObject {
                    put("type", JsonPrimitive("field"))
                    put("protocol", buildJsonArray {
                        add(JsonPrimitive("quic"))
                    })
                    put("outboundTag", JsonPrimitive("block"))
                })
            }
            // Route DNS traffic through proxy — or, with FakeDNS, into the
            // local DNS module so it answers from the fake-IP pool.
            add(buildJsonObject {
                put("type", JsonPrimitive("field"))
                put("port", JsonPrimitive("53"))
                put("outboundTag", JsonPrimitive(if (fakeDns) "dns-out" else "proxy"))
            })
            if (fakeDns) {
                // Fake-IP pool (198.18.0.0/15) must reach the proxy; xray
                // remaps each fake-IP connection back to its real domain
                // (sniffed from the payload) before dialing the outbound.
                add(buildJsonObject {
                    put("type", JsonPrimitive("field"))
                    put("ip", buildJsonArray {
                        add(JsonPrimitive(FAKE_IP_POOL))
                    })
                    put("outboundTag", JsonPrimitive("proxy"))
                })
            }
            // Route all SOCKS/HTTP inbound traffic through proxy
            add(buildJsonObject {
                put("type", JsonPrimitive("field"))
                val _inboundTags = buildJsonArray {
                    add(JsonPrimitive("socks-in"))
                    add(JsonPrimitive("http-in"))
                }
                put("inboundTag", _inboundTags)
                put("outboundTag", JsonPrimitive("proxy"))
            })
        })
    }

    // ------------------------------------------------------------------
    // Policy section (for statistics)
    // ------------------------------------------------------------------

    private fun buildPolicy(): JsonObject = buildJsonObject {
        put("levels", buildJsonObject {
            put("0", buildJsonObject {
                put("connIdle", JsonPrimitive(300))
            })
        })
        put("system", buildJsonObject {
            put("statsInboundUplink", JsonPrimitive(true))
            put("statsInboundDownlink", JsonPrimitive(true))
            put("statsOutboundUplink", JsonPrimitive(true))
            put("statsOutboundDownlink", JsonPrimitive(true))
        })
    }

    // ------------------------------------------------------------------
    // DNS
    // ------------------------------------------------------------------

    private fun buildDns(dnsServers: List<String>, fakeDns: Boolean = false): JsonObject = buildJsonObject {
        put("servers", buildJsonArray {
            dnsServers.forEach { add(JsonPrimitive(it)) }
        })
        if (fakeDns) {
            // Fake-IP (fakeip) pool: xray answers A/AAAA queries with
            // addresses from 198.18.0.0/15, remembers domain→fake-IP, and
            // remaps connections to fake IPs back to the real domain.
            put("queryStrategy", JsonPrimitive("UseIP"))
            put("fakeip", buildJsonObject {
                put("enabled", JsonPrimitive(true))
                put("ip4", JsonPrimitive(FAKE_IP_POOL))
            })
        }
    }

    /**
     * The built-in DNS module exposed as an outbound (protocol "dns").
     * FakeDNS routing sends port-53 queries here so they are answered
     * LOCALLY from the fake-IP pool instead of leaking through the proxy.
     */
    private fun buildDnsOutbound(): JsonObject = buildJsonObject {
        put("protocol", JsonPrimitive("dns"))
        put("tag", JsonPrimitive("dns-out"))
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Parse the [rawConfig] string into a [JsonObject] for field extraction.
     * Returns `null` when the string is empty or not valid JSON.
     */
    private fun parseRawConfig(rawConfig: String): JsonObject? {
        if (rawConfig.isBlank()) return null
        return try {
            val element = json.parseToJsonElement(rawConfig)
            if (element is JsonObject) element else null
        } catch (_: Exception) {
            null
        }
    }
}
