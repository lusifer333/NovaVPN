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

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Convert a [ServerConfig] into a complete Xray JSON configuration string.
     *
     * @param config The parsed server configuration to convert.
     * @return A pretty-printed Xray JSON string.
     */
    fun toXrayJson(config: ServerConfig): String {
        val root = buildJsonObject {
            put("log", buildLogSection())
            put("inbounds", buildInbounds())
            put("outbounds", buildOutbounds(config))
            put("routing", buildRouting())
        }
        return Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), root)
    }

    // ------------------------------------------------------------------
    // Log section
    // ------------------------------------------------------------------

    private fun buildLogSection(): JsonObject = buildJsonObject {
        put("loglevel", JsonPrimitive("warning"))
        put("access", JsonPrimitive("/dev/null"))
        put("error", JsonPrimitive("/dev/null"))
    }

    // ------------------------------------------------------------------
    // Inbounds
    // ------------------------------------------------------------------

    /**
     * Two local inbounds that the VPN tunnel listens on:
     * - SOCKS5 on port 10808 (UDP enabled)
     * - HTTP on port 10809
     */
    private fun buildInbounds(): JsonArray = buildJsonArray {
        add(buildJsonObject {
            put("listen", JsonPrimitive("127.0.0.1"))
            put("port", 10808)
            put("protocol", JsonPrimitive("socks"))
            val _settings_1 = buildJsonObject {
                put("auth", JsonPrimitive("noauth"))
            put("settings", _settings_1)
                put("udp", JsonPrimitive(true))
            })
            put("tag", JsonPrimitive("socks-in"))
        })
        add(buildJsonObject {
            put("listen", JsonPrimitive("127.0.0.1"))
            put("port", 10809)
            put("protocol", JsonPrimitive("http"))
            val _settings_empty = buildJsonObject { }
            put("settings", _settings_empty)
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
                val _settings_empty = buildJsonObject { }
            put("settings", _settings_empty)
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
            val _vnext_2 = buildJsonArray {
                add(buildJsonObject {
                    put("address", JsonPrimitive(config.address))
                    put("port", JsonPrimitive(config.port))
                    val _users_1 = buildJsonArray {
                        add(buildJsonObject {
                            put("id", JsonPrimitive(id))
                            put("alterId", JsonPrimitive(aid))
                            put("security", JsonPrimitive(security))
                        })
                    })
                }
            put("vnext", _vnext_2)
            })
        }
    }

    private fun buildVlessSettings(config: ServerConfig): JsonObject {
        val raw = parseRawConfig(config.rawConfig)
        val id = raw?.get("id")?.jsonPrimitive?.content ?: ""
        val encryption = raw?.get("encryption")?.jsonPrimitive?.content ?: "none"
        val flow = raw?.get("flow")?.jsonPrimitive?.content

        return buildJsonObject {
            val _vnext_3 = buildJsonArray {
                add(buildJsonObject {
                    put("address", JsonPrimitive(config.address))
                    put("port", JsonPrimitive(config.port))
                    val _users_2 = buildJsonArray {
                        add(buildJsonObject {
                            put("id", JsonPrimitive(id))
                            put("encryption", JsonPrimitive(encryption))
                            if (flow != null) put("flow", JsonPrimitive(flow))
                        })
                    })
                }
            put("vnext", _vnext_3)
            })
        }
    }

    private fun buildTrojanSettings(config: ServerConfig): JsonObject {
        val raw = parseRawConfig(config.rawConfig)
        val password = raw?.get("password")?.jsonPrimitive?.content ?: ""
        val flow = raw?.get("flow")?.jsonPrimitive?.content

        return buildJsonObject {
            val _servers_4 = buildJsonArray {
                add(buildJsonObject {
                    put("address", JsonPrimitive(config.address))
                    put("port", JsonPrimitive(config.port))
                    put("password", JsonPrimitive(password))
                    if (flow != null) put("flow", JsonPrimitive(flow))
                }
            put("servers", _servers_4)
            })
        }
    }

    private fun buildShadowsocksSettings(config: ServerConfig): JsonObject {
        val raw = parseRawConfig(config.rawConfig)
        val password = raw?.get("password")?.jsonPrimitive?.content ?: ""
        val method = raw?.get("method")?.jsonPrimitive?.content ?: "aes-256-gcm"
        val plugin = raw?.get("plugin")?.jsonPrimitive?.content
        val pluginOpts = raw?.get("pluginOpts")?.jsonPrimitive?.content
            ?: raw?.get("plugin_opts")?.jsonPrimitive?.content

        return buildJsonObject {
            val _servers_5 = buildJsonArray {
                add(buildJsonObject {
                    put("address", JsonPrimitive(config.address))
                    put("port", JsonPrimitive(config.port))
                    put("method", JsonPrimitive(method))
                    put("password", JsonPrimitive(password))
                    if (plugin != null) put("plugin", JsonPrimitive(plugin))
                    if (pluginOpts != null) put("plugin_opts", JsonPrimitive(pluginOpts))
                }
            put("servers", _servers_5)
            })
        }
    }

    private fun buildSocksSettings(config: ServerConfig): JsonObject {
        val raw = parseRawConfig(config.rawConfig)
        val username = raw?.get("username")?.jsonPrimitive?.content
        val password = raw?.get("password")?.jsonPrimitive?.content

        return buildJsonObject {
            val _servers_6 = buildJsonArray {
                add(buildJsonObject {
                    put("address", JsonPrimitive(config.address))
                    put("port", JsonPrimitive(config.port))
                    if (username != null) {
                        val _users_3 = buildJsonArray {
                            add(buildJsonObject {
                                put("user", JsonPrimitive(username))
                                if (password != null) put("pass", JsonPrimitive(password))
                            })
                        })
                    }
                }
            put("servers", _servers_6)
            })
        }
    }

    private fun buildHttpSettings(config: ServerConfig): JsonObject {
        val raw = parseRawConfig(config.rawConfig)
        val username = raw?.get("username")?.jsonPrimitive?.content
        val password = raw?.get("password")?.jsonPrimitive?.content

        return buildJsonObject {
            val _servers_7 = buildJsonArray {
                add(buildJsonObject {
                    put("address", JsonPrimitive(config.address))
                    put("port", JsonPrimitive(config.port))
                    if (username != null) {
                        val _users_4 = buildJsonArray {
                            add(buildJsonObject {
                                put("user", JsonPrimitive(username))
                                if (password != null) put("pass", JsonPrimitive(password))
                            })
                        })
                    }
                }
            put("servers", _servers_7)
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
                    alpn.split(",").forEach { add(it.trim()) }
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
                val _headers_8 = buildJsonObject {
                    put("Host", JsonPrimitive(host))
                put("headers", _headers_8)
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
            val _header_9 = buildJsonObject {
                put("type", JsonPrimitive("none"))
            put("header", _header_9)
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
                    host.split(",").forEach { add(it.trim()) }
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
     * Simple routing: all traffic arriving on the SOCKS or HTTP inbound
     * gets sent to the "proxy" outbound. All other traffic bypasses the
     * proxy (direct).
     */
    private fun buildRouting(): JsonObject = buildJsonObject {
        put("domainStrategy", JsonPrimitive("AsIs"))
        val _rules_10 = buildJsonArray {
            add(buildJsonObject {
                put("type", JsonPrimitive("field"))
                val _inboundTags = buildJsonArray {
                    add("socks-in")
                    add("http-in")
                }
                put("inboundTag", _inboundTags)
                put("outboundTag", JsonPrimitive("proxy"))
            }
        put("rules", _rules_10)
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