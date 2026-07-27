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
        put("loglevel", "warning")
        put("access", "/dev/null")
        put("error", "/dev/null")
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
            put("listen", "127.0.0.1")
            put("port", 10808)
            put("protocol", "socks")
            put("settings", buildJsonObject {
                put("auth", "noauth")
                put("udp", true)
            })
            put("tag", "socks-in")
        })
        add(buildJsonObject {
            put("listen", "127.0.0.1")
            put("port", 10809)
            put("protocol", "http")
            put("settings", buildJsonObject { })
            put("tag", "http-in")
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
        put("tag", "proxy")

        when (config.protocol) {
            Protocol.VMess -> {
                put("protocol", "vmess")
                put("settings", buildVmessSettings(config))
                put("streamSettings", buildStreamSettings(config))
            }
            Protocol.VLESS -> {
                put("protocol", "vless")
                put("settings", buildVlessSettings(config))
                put("streamSettings", buildStreamSettings(config))
            }
            Protocol.Trojan -> {
                put("protocol", "trojan")
                put("settings", buildTrojanSettings(config))
                put("streamSettings", buildStreamSettings(config))
            }
            Protocol.Shadowsocks -> {
                put("protocol", "shadowsocks")
                put("settings", buildShadowsocksSettings(config))
            }
            Protocol.SOCKS5 -> {
                put("protocol", "socks")
                put("settings", buildSocksSettings(config))
                put("streamSettings", buildStreamSettings(config))
            }
            Protocol.HTTP -> {
                put("protocol", "http")
                put("settings", buildHttpSettings(config))
                put("streamSettings", buildStreamSettings(config))
            }
            Protocol.Unknown -> {
                put("protocol", "freedom")
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
                    put("address", config.address)
                    put("port", config.port)
                    put("users", buildJsonArray {
                        add(buildJsonObject {
                            put("id", id)
                            put("alterId", aid)
                            put("security", security)
                        })
                    })
                })
            })
        }
    }

    private fun buildVlessSettings(config: ServerConfig): JsonObject {
        val raw = parseRawConfig(config.rawConfig)
        val id = raw?.get("id")?.jsonPrimitive?.content ?: ""
        val encryption = raw?.get("encryption")?.jsonPrimitive?.content ?: "none"
        val flow = raw?.get("flow")?.jsonPrimitive?.contentOrNull

        return buildJsonObject {
            put("vnext", buildJsonArray {
                add(buildJsonObject {
                    put("address", config.address)
                    put("port", config.port)
                    put("users", buildJsonArray {
                        add(buildJsonObject {
                            put("id", id)
                            put("encryption", encryption)
                            if (flow != null) put("flow", flow)
                        })
                    })
                })
            })
        }
    }

    private fun buildTrojanSettings(config: ServerConfig): JsonObject {
        val raw = parseRawConfig(config.rawConfig)
        val password = raw?.get("password")?.jsonPrimitive?.content ?: ""
        val flow = raw?.get("flow")?.jsonPrimitive?.contentOrNull

        return buildJsonObject {
            put("servers", buildJsonArray {
                add(buildJsonObject {
                    put("address", config.address)
                    put("port", config.port)
                    put("password", password)
                    if (flow != null) put("flow", flow)
                })
            })
        }
    }

    private fun buildShadowsocksSettings(config: ServerConfig): JsonObject {
        val raw = parseRawConfig(config.rawConfig)
        val password = raw?.get("password")?.jsonPrimitive?.content ?: ""
        val method = raw?.get("method")?.jsonPrimitive?.content ?: "aes-256-gcm"
        val plugin = raw?.get("plugin")?.jsonPrimitive?.contentOrNull
        val pluginOpts = raw?.get("pluginOpts")?.jsonPrimitive?.contentOrNull
            ?: raw?.get("plugin_opts")?.jsonPrimitive?.contentOrNull

        return buildJsonObject {
            put("servers", buildJsonArray {
                add(buildJsonObject {
                    put("address", config.address)
                    put("port", config.port)
                    put("method", method)
                    put("password", password)
                    if (plugin != null) put("plugin", plugin)
                    if (pluginOpts != null) put("plugin_opts", pluginOpts)
                })
            })
        }
    }

    private fun buildSocksSettings(config: ServerConfig): JsonObject {
        val raw = parseRawConfig(config.rawConfig)
        val username = raw?.get("username")?.jsonPrimitive?.contentOrNull
        val password = raw?.get("password")?.jsonPrimitive?.contentOrNull

        return buildJsonObject {
            put("servers", buildJsonArray {
                add(buildJsonObject {
                    put("address", config.address)
                    put("port", config.port)
                    if (username != null) {
                        put("users", buildJsonArray {
                            add(buildJsonObject {
                                put("user", username)
                                if (password != null) put("pass", password)
                            })
                        })
                    }
                })
            })
        }
    }

    private fun buildHttpSettings(config: ServerConfig): JsonObject {
        val raw = parseRawConfig(config.rawConfig)
        val username = raw?.get("username")?.jsonPrimitive?.contentOrNull
        val password = raw?.get("password")?.jsonPrimitive?.contentOrNull

        return buildJsonObject {
            put("servers", buildJsonArray {
                add(buildJsonObject {
                    put("address", config.address)
                    put("port", config.port)
                    if (username != null) {
                        put("users", buildJsonArray {
                            add(buildJsonObject {
                                put("user", username)
                                if (password != null) put("pass", password)
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
        put("network", network)

        // Security layer
        when (config.security) {
            Security.TLS -> {
                put("security", "tls")
                put("tlsSettings", buildTlsSettings(config))
            }
            Security.Reality -> {
                put("security", "reality")
                put("realitySettings", buildRealitySettings(config))
            }
            Security.None -> {
                put("security", "none")
            }
            Security.Unknown -> {
                put("security", "none")
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
        val serverName = raw?.get("serverName")?.jsonPrimitive?.contentOrNull
            ?: raw?.get("sni")?.jsonPrimitive?.contentOrNull
            ?: config.address
        val fingerprint = raw?.get("fingerprint")?.jsonPrimitive?.contentOrNull ?: "chrome"
        val alpn = raw?.get("alpn")?.jsonPrimitive?.contentOrNull

        return buildJsonObject {
            put("serverName", serverName)
            put("fingerprint", fingerprint)
            if (alpn != null) {
                put("alpn", buildJsonArray {
                    alpn.split(",").forEach { add(it.trim()) }
                })
            }
        }
    }

    private fun buildRealitySettings(config: ServerConfig): JsonObject {
        val raw = parseRawConfig(config.rawConfig)
        val serverName = raw?.get("serverName")?.jsonPrimitive?.contentOrNull
            ?: raw?.get("sni")?.jsonPrimitive?.contentOrNull
            ?: config.address
        val fingerprint = raw?.get("fingerprint")?.jsonPrimitive?.contentOrNull ?: "chrome"
        val publicKey = raw?.get("publicKey")?.jsonPrimitive?.content ?: ""
        val shortId = raw?.get("shortId")?.jsonPrimitive?.contentOrNull ?: ""
        val spiderX = raw?.get("spiderX")?.jsonPrimitive?.contentOrNull ?: ""

        return buildJsonObject {
            put("serverName", serverName)
            put("fingerprint", fingerprint)
            put("publicKey", publicKey)
            put("shortId", shortId)
            put("spiderX", spiderX)
        }
    }

    private fun buildWsSettings(config: ServerConfig): JsonObject {
        val raw = parseRawConfig(config.rawConfig)
        val path = raw?.get("path")?.jsonPrimitive?.contentOrNull ?: "/"
        val host = raw?.get("host")?.jsonPrimitive?.contentOrNull
            ?: raw?.get("headers")?.jsonObject?.get("Host")?.jsonPrimitive?.contentOrNull

        return buildJsonObject {
            put("path", path)
            if (host != null) {
                put("headers", buildJsonObject {
                    put("Host", host)
                })
            }
        }
    }

    private fun buildGrpcSettings(config: ServerConfig): JsonObject {
        val raw = parseRawConfig(config.rawConfig)
        val serviceName = raw?.get("serviceName")?.jsonPrimitive?.contentOrNull ?: ""

        return buildJsonObject {
            put("serviceName", serviceName)
            put("multiMode", false)
        }
    }

    private fun buildQuicSettings(config: ServerConfig): JsonObject {
        val raw = parseRawConfig(config.rawConfig)
        val security = raw?.get("quicSecurity")?.jsonPrimitive?.contentOrNull ?: "none"
        val key = raw?.get("key")?.jsonPrimitive?.contentOrNull ?: ""

        return buildJsonObject {
            put("security", security)
            put("key", key)
            put("header", buildJsonObject {
                put("type", "none")
            })
        }
    }

    private fun buildHttpTransportSettings(config: ServerConfig): JsonObject {
        val raw = parseRawConfig(config.rawConfig)
        val path = raw?.get("path")?.jsonPrimitive?.contentOrNull ?: "/"
        val host = raw?.get("host")?.jsonPrimitive?.contentOrNull

        return buildJsonObject {
            put("path", path)
            if (host != null) {
                put("host", buildJsonArray {
                    host.split(",").forEach { add(it.trim()) }
                })
            }
        }
    }

    // ------------------------------------------------------------------
    // Static outbounds (direct + block)
    // ------------------------------------------------------------------

    private fun buildDirectOutbound(): JsonObject = buildJsonObject {
        put("protocol", "freedom")
        put("tag", "direct")
    }

    private fun buildBlockOutbound(): JsonObject = buildJsonObject {
        put("protocol", "blackhole")
        put("tag", "block")
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
        put("domainStrategy", "AsIs")
        put("rules", buildJsonArray {
            add(buildJsonObject {
                put("type", "field")
                put("inboundTag", buildJsonArray {
                    add("socks-in")
                    add("http-in")
                })
                put("outboundTag", "proxy")
            })
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
