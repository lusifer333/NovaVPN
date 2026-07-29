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

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Convert a [ServerConfig] into a complete Xray JSON configuration string
     * with TUN inbound for Android VPN mode.
     *
     * @param config The parsed server configuration to convert.
     * @param tunFd The TUN interface file descriptor (from VpnService.Builder.establish()).
     * @param dnsServers DNS server addresses to use (e.g. ["8.8.8.8", "1.1.1.1"]).
     * @param routes Routes to forward through the VPN (e.g. ["0.0.0.0/0"]).
     * @return A pretty-printed Xray JSON string.
     */
    fun toXrayJson(
        config: ServerConfig,
        tunFd: Int,
        dnsServers: List<String> = listOf("8.8.8.8", "1.1.1.1"),
        routes: List<String> = listOf("0.0.0.0/0")
    ): String {
        val root = buildJsonObject {
            put("log", buildLogSection())
            put("inbounds", buildInbounds(tunFd))
            put("outbounds", buildOutbounds(config))
            put("routing", buildRouting())
            put("dns", buildDns(dnsServers))
        }
        val jsonStr = Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), root)
        Timber.tag(TAG).d("Generated Xray config:\n%s", jsonStr)
        return jsonStr
    }

    // ------------------------------------------------------------------
    // Log section
    // ------------------------------------------------------------------

    private fun buildLogSection(): JsonObject = buildJsonObject {
        put("loglevel", JsonPrimitive("debug"))
        put("access", JsonPrimitive("/dev/null"))
        put("error", JsonPrimitive("/dev/null"))
    }

    // ------------------------------------------------------------------
    // Inbounds
    // ------------------------------------------------------------------

    /**
     * TUN inbound (primary) + SOCKS/HTTP inbounds (fallback for testing).
     *
     * TUN inbound uses the pre-existing TUN fd from VpnService.Builder.establish().
     * In Xray 1.8.0+, the `tun` protocol accepts a pre-opened fd via the `fd`
     * setting. The process must inherit this fd (clear FD_CLOEXEC).
     *
     * SOCKS5 and HTTP inbounds are kept for debugging: if TUN doesn't work,
     * users can test with a local proxy client at 127.0.0.1:10808/10809.
     */
    private fun buildInbounds(tunFd: Int): JsonArray = buildJsonArray {
        // Primary: TUN inbound for VPN traffic
        add(buildJsonObject {
            put("protocol", JsonPrimitive("tun"))
            put("tag", JsonPrimitive("tun-in"))
            put("settings", buildJsonObject {
                put("fd", JsonPrimitive(tunFd))
                put("mtu", JsonPrimitive(1500))
                put("udp", JsonPrimitive(true))
            })
            put("sniffing", buildJsonObject {
                put("enabled", JsonPrimitive(true))
                put("destOverride", buildJsonArray {
                    add(JsonPrimitive("http"))
                    add(JsonPrimitive("tls"))
                })
            })
        })
        // Fallback: SOCKS5 for local proxy testing
        add(buildJsonObject {
            put("listen", JsonPrimitive("127.0.0.1"))
            put("port", 10808)
            put("protocol", JsonPrimitive("socks"))
            put("settings", buildJsonObject {
                put("auth", JsonPrimitive("noauth"))
                put("udp", JsonPrimitive(true))
            })
            put("tag", JsonPrimitive("socks-in"))
        })
        // Fallback: HTTP proxy
        add(buildJsonObject {
            put("listen", JsonPrimitive("127.0.0.1"))
            put("port", 10809)
            put("protocol", JsonPrimitive("http"))
            put("settings", buildJsonObject { })
            put("tag", JsonPrimitive("http-in"))
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
    private fun buildOutbounds(config: ServerConfig): JsonArray = buildJsonArray {
        add(buildProxyOutbound(config))
        add(buildDirectOutbound())
        add(buildBlockOutbound())
    }

    /**
     * Build the main proxy outbound from the server configuration.
     * Dispatches to protocol-specific builders.
     */
    private fun buildProxyOutbound(config: ServerConfig): JsonObject = buildJsonObject {
        put("tag", JsonPrimitive("proxy"))

        when (config.protocol) {
            Protocol.VMess -> {
                put("protocol", JsonPrimitive("vmess"))
                put("settings", buildVmessSettings(config))
                put("streamSettings", buildStreamSettings(config))
            }
            Protocol.VLESS -> {
                put("protocol", JsonPrimitive("vless"))
                put("settings", buildVlessSettings(config))
                put("streamSettings", buildStreamSettings(config))
            }
            Protocol.Trojan -> {
                put("protocol", JsonPrimitive("trojan"))
                put("settings", buildTrojanSettings(config))
                put("streamSettings", buildStreamSettings(config))
            }
            Protocol.Shadowsocks -> {
                put("protocol", JsonPrimitive("shadowsocks"))
                put("settings", buildShadowsocksSettings(config))
            }
            Protocol.SOCKS5 -> {
                put("protocol", JsonPrimitive("socks"))
                put("settings", buildSocksSettings(config))
                put("streamSettings", buildStreamSettings(config))
            }
            Protocol.HTTP -> {
                put("protocol", JsonPrimitive("http"))
                put("settings", buildHttpSettings(config))
                put("streamSettings", buildStreamSettings(config))
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
    private fun buildStreamSettings(config: ServerConfig): JsonObject = buildJsonObject {
        // Network (transport protocol)
        val network = when (config.transport) {
            Transport.TCP -> "tcp"
            Transport.WebSocket -> "ws"
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
                put("tlsSettings", buildTlsSettings(config))
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
            Transport.gRPC -> put("grpcSettings", buildGrpcSettings(config))
            Transport.QUIC -> put("quicSettings", buildQuicSettings(config))
            Transport.HTTP -> put("httpSettings", buildHttpTransportSettings(config))
            else -> { /* TCP needs no extra settings */ }
        }
    }

    private fun buildTlsSettings(config: ServerConfig): JsonObject {
        val raw = parseRawConfig(config.rawConfig)
        val serverName = raw?.get("serverName")?.jsonPrimitive?.content
            ?: raw?.get("sni")?.jsonPrimitive?.content
            ?: config.address
        val fingerprint = raw?.get("fingerprint")?.jsonPrimitive?.content ?: "chrome"
        val alpn = raw?.get("alpn")?.jsonPrimitive?.content

        return buildJsonObject {
            put("serverName", JsonPrimitive(serverName))
            put("fingerprint", JsonPrimitive(fingerprint))
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
            if (host != null) {
                put("headers", buildJsonObject {
                    put("Host", JsonPrimitive(host))
                })
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

    // ------------------------------------------------------------------
    // Routing
    // ------------------------------------------------------------------

    /**
     * Routing: traffic from TUN inbound gets sent to the proxy outbound.
     * SOCKS/HTTP inbounds (fallback) also route to proxy for testing.
     * DNS traffic on port 53 is explicitly routed to proxy to prevent
     * DNS_PROBE_POSSIBLE errors.
     */
    private fun buildRouting(): JsonObject = buildJsonObject {
        put("domainStrategy", JsonPrimitive("AsIs"))
        put("rules", buildJsonArray {
            // Route DNS traffic through proxy
            add(buildJsonObject {
                put("type", JsonPrimitive("field"))
                put("port", JsonPrimitive("53"))
                put("outboundTag", JsonPrimitive("proxy"))
            })
            // Route all inbound traffic through proxy
            add(buildJsonObject {
                put("type", JsonPrimitive("field"))
                val _inboundTags = buildJsonArray {
                    add(JsonPrimitive("tun-in"))
                    add(JsonPrimitive("socks-in"))
                    add(JsonPrimitive("http-in"))
                }
                put("inboundTag", _inboundTags)
                put("outboundTag", JsonPrimitive("proxy"))
            })
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
