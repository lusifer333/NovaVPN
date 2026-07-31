package com.novavpn.engine.xray

import com.novavpn.domain.model.Protocol
import com.novavpn.domain.model.Security
import com.novavpn.domain.model.ServerConfig
import com.novavpn.domain.model.Transport
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
        fragmentTls: Boolean = false
    ): String {
        val root = buildJsonObject {
            put("log", buildLogSection(logDir))
            put("inbounds", buildInbounds(blockQuic))
            put("outbounds", buildOutbounds(config, fragmentTls))
            put("routing", buildRouting(blockQuic))
            put("dns", buildDns(dnsServers))
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
    private fun buildInbounds(blockQuic: Boolean = false): JsonArray = buildJsonArray {
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
            if (blockQuic) {
                // Detect QUIC (UDP 443) so the routing rule can drop it:
                // browsers then fall back to TCP and dodge DPI tampering.
                put("sniffing", buildSniffing())
            }
        })
        // HTTP proxy fallback
        add(buildJsonObject {
            put("listen", JsonPrimitive("127.0.0.1"))
            put("port", 10809)
            put("protocol", JsonPrimitive("http"))
            put("settings", buildJsonObject { })
            put("tag", JsonPrimitive("http-in"))
            if (blockQuic) {
                put("sniffing", buildSniffing())
            }
        })
    }

    /**
     * Sniffing config: http + tls for normal traffic, quic for detecting
     * HTTP/3 handshakes. Only enabled when the "Block QUIC" toggle is on.
     */
    private fun buildSniffing(): JsonObject = buildJsonObject {
        put("enabled", JsonPrimitive(true))
        put("destOverride", buildJsonArray {
            add(JsonPrimitive("http"))
            add(JsonPrimitive("tls"))
            add(JsonPrimitive("quic"))
        })
    }

    // ------------------------------------------------------------------
    // Outbounds
    // ------------------------------------------------------------------

    /**
     * Builds the full outbounds array:
     * 0. Proxy outbound (built from [config])
     * 1. Direct (freedom) outbound — fallback for non-proxied traffic
     * 2. Block outbound — drops traffic that should be blocked
     */
    private fun buildOutbounds(config: ServerConfig, fragmentTls: Boolean = false): JsonArray = buildJsonArray {
        if (fragmentTls) {
            add(buildFragmentOutbound())
        }
        add(buildProxyOutbound(config, fragmentTls))
        add(buildDirectOutbound())
        add(buildBlockOutbound())
    }

    /**
     * Build the main proxy outbound from the server configuration.
     * Dispatches to protocol-specific builders.
     */
    private fun buildProxyOutbound(config: ServerConfig, fragmentTls: Boolean = false): JsonObject = buildJsonObject {
        put("tag", JsonPrimitive("proxy"))

        when (config.protocol) {
            Protocol.VMess -> {
                put("protocol", JsonPrimitive("vmess"))
                put("settings", buildVmessSettings(config))
                put("streamSettings", buildStreamSettings(config, fragmentTls))
            }
            Protocol.VLESS -> {
                put("protocol", JsonPrimitive("vless"))
                put("settings", buildVlessSettings(config))
                put("streamSettings", buildStreamSettings(config, fragmentTls))
            }
            Protocol.Trojan -> {
                put("protocol", JsonPrimitive("trojan"))
                put("settings", buildTrojanSettings(config))
                put("streamSettings", buildStreamSettings(config, fragmentTls))
            }
            Protocol.Shadowsocks -> {
                put("protocol", JsonPrimitive("shadowsocks"))
                put("settings", buildShadowsocksSettings(config))
            }
            Protocol.SOCKS5 -> {
                put("protocol", JsonPrimitive("socks"))
                put("settings", buildSocksSettings(config))
                put("streamSettings", buildStreamSettings(config, fragmentTls))
            }
            Protocol.HTTP -> {
                put("protocol", JsonPrimitive("http"))
                put("settings", buildHttpSettings(config))
                put("streamSettings", buildStreamSettings(config, fragmentTls))
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
     * Build the `streamSettings` block based on the config's transport and
     * security fields. Reality is a transport-level security layer (part of
     * streamSettings), while TLS is indicated by setting the security field.
     */
    private fun buildStreamSettings(config: ServerConfig, fragmentTls: Boolean = false): JsonObject = buildJsonObject {
        // Network (transport protocol)
        val network = when (config.transport) {
            Transport.TCP -> "tcp"
            Transport.WebSocket -> "ws"
            Transport.XHTTP -> "xhttp"
            Transport.gRPC -> "grpc"
            Transport.QUIC -> "quic"
            Transport.HTTP -> "http"
            Transport.Unknown -> "tcp"
        }
        put("network", JsonPrimitive(network))

        // Security layer
        when (config.security) {
            Security.TLS -> {
                put("security", JsonPrimitive("tls"))
                put("tlsSettings", buildTlsSettings(config, fragmentTls))
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

        // Transport-specific settings
        when (config.transport) {
            Transport.WebSocket -> put("wsSettings", buildWsSettings(config))
            Transport.XHTTP -> put("xhttpSettings", buildXhttpSettings(config))
            Transport.gRPC -> put("grpcSettings", buildGrpcSettings(config))
            Transport.QUIC -> put("quicSettings", buildQuicSettings(config))
            Transport.HTTP -> put("httpSettings", buildHttpTransportSettings(config))
            else -> { /* TCP needs no extra settings */ }
        }

        // TCP keepalive on the underlying connection: prevents middlebox /
        // Cloudflare idle-drops from silently killing WS sessions (the
        // recurring "websocket: close 1005 (no status)" churn on dead UDP
        // associations). Validated against the real Xray 26.3.27 binary
        // (xray -test -> Configuration OK).
        put("sockopt", buildJsonObject {
            put("tcpKeepAliveIdle", JsonPrimitive(60))
            put("tcpKeepAliveInterval", JsonPrimitive(15))
            if (fragmentTls) {
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

    private fun buildXhttpSettings(config: ServerConfig): JsonObject {
        val raw = parseRawConfig(config.rawConfig)
        val host = raw?.get("host")?.jsonPrimitive?.content
            ?: raw?.get("headers")?.jsonObject?.get("Host")?.jsonPrimitive?.content
        val path = raw?.get("path")?.jsonPrimitive?.content ?: "/"

        return buildJsonObject {
            put("path", JsonPrimitive(path))
            // XHTTP host is an array
            if (host != null) {
                put("host", buildJsonArray { add(JsonPrimitive(host)) })
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

    private fun buildQuicSettings(config: ServerConfig): JsonObject {
        val raw = parseRawConfig(config.rawConfig)
        val security = raw?.get("quicSecurity")?.jsonPrimitive?.content ?: "none"
        val key = raw?.get("key")?.jsonPrimitive?.content ?: ""

        return buildJsonObject {
            put("security", JsonPrimitive(security))
            put("key", JsonPrimitive(key))
            put("header", buildJsonObject {
                put("type", JsonPrimitive("none"))
            })
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
     */
    private fun buildRouting(blockQuic: Boolean = false): JsonObject = buildJsonObject {
        put("domainStrategy", JsonPrimitive("AsIs"))
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
            // Route DNS traffic through proxy
            add(buildJsonObject {
                put("type", JsonPrimitive("field"))
                put("port", JsonPrimitive("53"))
                put("outboundTag", JsonPrimitive("proxy"))
            })
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

    private fun buildDns(dnsServers: List<String>): JsonObject = buildJsonObject {
        put("servers", buildJsonArray {
            dnsServers.forEach { add(JsonPrimitive(it)) }
        })
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
