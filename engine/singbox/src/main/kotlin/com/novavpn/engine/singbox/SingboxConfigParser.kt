package com.novavpn.engine.singbox

import com.novavpn.domain.model.Protocol
import com.novavpn.domain.model.Security
import com.novavpn.domain.model.ServerConfig
import com.novavpn.domain.model.Transport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Generates valid sing-box JSON configuration strings from [ServerConfig] domain models.
 *
 * Produces a full sing-box config with:
 * - **Logging** (info level with timestamps)
 * - **Inbounds** — SOCKS5 on 127.0.0.1:10808 and HTTP on 127.0.0.1:10809
 * - **Outbounds** — the proxy outbound built from the server config, plus
 *   direct and block fallback outbounds
 * - **Route** — a catch-all rule that sends traffic from the local inbounds
 *   to the proxy outbound
 *
 * Supported protocols: VMess, VLESS, Trojan, Shadowsocks.
 * Supported security layers: TLS, Reality.
 * Supported transports: TCP, WebSocket, gRPC, QUIC, HTTP.
 *
 * All JSON is constructed via `kotlinx.serialization.json` DSL —
 * no string templating is used.
 */
object SingboxConfigParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Convert a [ServerConfig] into a complete sing-box JSON configuration string.
     *
     * @param config The parsed server configuration to convert.
     * @return A pretty-printed sing-box JSON string.
     */
    fun toSingboxJson(config: ServerConfig): String {
        val root = buildJsonObject {
            put("log", buildLogSection())
            put("inbounds", buildInbounds())
            put("outbounds", buildOutbounds(config))
            put("route", buildRoute())
        }
        return Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), root)
    }

    // ------------------------------------------------------------------
    // Log section
    // ------------------------------------------------------------------

    private fun buildLogSection(): JsonObject = buildJsonObject {
        put("level", "info")
        put("output", "")
        put("timestamp", true)
    }

    // ------------------------------------------------------------------
    // Inbounds
    // ------------------------------------------------------------------

    /**
     * Two local inbounds that the VPN tunnel listens on:
     * - SOCKS5 on port 10808
     * - HTTP on port 10809
     */
    private fun buildInbounds(): JsonArray = buildJsonArray {
        add(buildJsonObject {
            put("type", "socks")
            put("tag", "socks-in")
            put("listen", "127.0.0.1")
            put("listen_port", 10808)
            put("sniff", true)
        })
        add(buildJsonObject {
            put("type", "http")
            put("tag", "http-in")
            put("listen", "127.0.0.1")
            put("listen_port", 10809)
            put("sniff", true)
        })
    }

    // ------------------------------------------------------------------
    // Outbounds
    // ------------------------------------------------------------------

    /**
     * Builds the full outbounds array:
     * 0. Proxy outbound (built from [config])
     * 1. Direct outbound — fallback for non-proxied traffic
     * 2. Block outbound — drops traffic that should be blocked
     */
    private fun buildOutbounds(config: ServerConfig): JsonArray = buildJsonArray {
        add(buildProxyOutbound(config))
        add(buildJsonObject {
            put("type", "direct")
            put("tag", "direct")
        })
        add(buildJsonObject {
            put("type", "block")
            put("tag", "block")
        })
    }

    /**
     * Build the main proxy outbound from the server configuration.
     * Dispatches to protocol-specific extension functions on [JsonObjectBuilder].
     */
    private fun buildProxyOutbound(config: ServerConfig): JsonObject = buildJsonObject {
        put("tag", "proxy")
        put("server", config.address)
        put("server_port", config.port)

        when (config.protocol) {
            Protocol.VMess -> {
                put("type", "vmess")
                vmessFields(config)
                tlsFields(config)
                transportFields(config)
            }
            Protocol.VLESS -> {
                put("type", "vless")
                vlessFields(config)
                tlsFields(config)
                transportFields(config)
            }
            Protocol.Trojan -> {
                put("type", "trojan")
                trojanFields(config)
                tlsFields(config)
                transportFields(config)
            }
            Protocol.Shadowsocks -> {
                put("type", "shadowsocks")
                shadowsocksFields(config)
            }
            Protocol.SOCKS5 -> {
                put("type", "socks")
                socksFields(config)
                tlsFields(config)
            }
            Protocol.HTTP -> {
                put("type", "http")
                httpFields(config)
                tlsFields(config)
            }
            Protocol.Unknown -> {
                put("type", "direct")
            }
        }
    }

    // ------------------------------------------------------------------
    // Protocol-specific field builders (extension functions on
    // JsonObjectBuilder — the receiver from buildJsonObject { })
    // ------------------------------------------------------------------

    private fun JsonObjectBuilder.vmessFields(config: ServerConfig) {
        val raw = parseRawConfig(config.rawConfig)
        val uuid = raw?.get("id")?.jsonPrimitive?.content ?: ""
        val security = raw?.get("security")?.jsonPrimitive?.content ?: "auto"
        val alterId = raw?.get("aid")?.jsonPrimitive?.content?.toIntOrNull() ?: 0

        put("uuid", uuid)
        put("security", security)
        put("alter_id", alterId)
    }

    private fun JsonObjectBuilder.vlessFields(config: ServerConfig) {
        val raw = parseRawConfig(config.rawConfig)
        val uuid = raw?.get("id")?.jsonPrimitive?.content ?: ""
        val flow = raw?.get("flow")?.jsonPrimitive?.content
        val encryption = raw?.get("encryption")?.jsonPrimitive?.content ?: "none"

        put("uuid", uuid)
        put("encryption", encryption)
        if (flow != null) put("flow", flow)
    }

    private fun JsonObjectBuilder.trojanFields(config: ServerConfig) {
        val raw = parseRawConfig(config.rawConfig)
        val password = raw?.get("password")?.jsonPrimitive?.content ?: ""

        put("password", password)
    }

    private fun JsonObjectBuilder.shadowsocksFields(config: ServerConfig) {
        val raw = parseRawConfig(config.rawConfig)
        val password = raw?.get("password")?.jsonPrimitive?.content ?: ""
        val method = raw?.get("method")?.jsonPrimitive?.content ?: "aes-256-gcm"
        val plugin = raw?.get("plugin")?.jsonPrimitive?.content
        val pluginOpts = raw?.get("pluginOpts")?.jsonPrimitive?.content
            ?: raw?.get("plugin_opts")?.jsonPrimitive?.content

        put("method", method)
        put("password", password)
        if (plugin != null) put("plugin", plugin)
        if (pluginOpts != null) put("plugin_opts", pluginOpts)
    }

    private fun JsonObjectBuilder.socksFields(config: ServerConfig) {
        val raw = parseRawConfig(config.rawConfig)
        val username = raw?.get("username")?.jsonPrimitive?.content
        val password = raw?.get("password")?.jsonPrimitive?.content

        if (username != null) {
            put("username", username)
            if (password != null) put("password", password)
        }
    }

    private fun JsonObjectBuilder.httpFields(config: ServerConfig) {
        val raw = parseRawConfig(config.rawConfig)
        val username = raw?.get("username")?.jsonPrimitive?.content
        val password = raw?.get("password")?.jsonPrimitive?.content

        if (username != null) {
            put("username", username)
            if (password != null) put("password", password)
        }
    }

    // ------------------------------------------------------------------
    // TLS fields
    // ------------------------------------------------------------------

    /**
     * Adds a `tls` object to the outbound when the config uses TLS or Reality security.
     */
    private fun JsonObjectBuilder.tlsFields(config: ServerConfig) {
        if (config.security == Security.None || config.security == Security.Unknown) return

        val raw = parseRawConfig(config.rawConfig)
        val serverName = raw?.get("serverName")?.jsonPrimitive?.content
            ?: raw?.get("sni")?.jsonPrimitive?.content
            ?: config.address

        when (config.security) {
            Security.TLS -> {
                put("tls", buildJsonObject {
                    put("enabled", true)
                    put("server_name", serverName)
                    put("insecure", false)
                    put("utls", buildJsonObject {
                        put("enabled", true)
                        val fingerprint = raw?.get("fingerprint")?.jsonPrimitive?.content ?: "chrome"
                        put("fingerprint", fingerprint)
                    })
                })
            }
            Security.Reality -> {
                val publicKey = raw?.get("publicKey")?.jsonPrimitive?.content ?: ""
                val shortId = raw?.get("shortId")?.jsonPrimitive?.content ?: ""
                val fingerprint = raw?.get("fingerprint")?.jsonPrimitive?.content ?: "chrome"

                put("tls", buildJsonObject {
                    put("enabled", true)
                    put("server_name", serverName)
                    put("insecure", false)
                    put("utls", buildJsonObject {
                        put("enabled", true)
                        put("fingerprint", fingerprint)
                    })
                    put("reality", buildJsonObject {
                        put("enabled", true)
                        put("public_key", publicKey)
                        put("short_id", shortId)
                    })
                })
            }
            else -> { /* no-op */ }
        }
    }

    // ------------------------------------------------------------------
    // Transport fields
    // ------------------------------------------------------------------

    /**
     * Adds a `transport` object to the outbound when the config uses a
     * non-TCP transport (WebSocket, gRPC, QUIC, HTTP).
     */
    private fun JsonObjectBuilder.transportFields(config: ServerConfig) {
        if (config.transport == Transport.TCP || config.transport == Transport.Unknown) return

        val raw = parseRawConfig(config.rawConfig)

        put("transport", buildJsonObject {
            when (config.transport) {
                Transport.WebSocket -> {
                    put("type", "ws")
                    val path = raw?.get("path")?.jsonPrimitive?.content ?: "/"
                    put("path", path)
                    val host = raw?.get("host")?.jsonPrimitive?.content
                        ?: raw?.get("headers")?.jsonObject?.get("Host")?.jsonPrimitive?.content
                    if (host != null) {
                        put("headers", buildJsonObject {
                            put("Host", host)
                        })
                    }
                }
                Transport.gRPC -> {
                    put("type", "grpc")
                    val serviceName = raw?.get("serviceName")?.jsonPrimitive?.content ?: ""
                    put("service_name", serviceName)
                }
                Transport.QUIC -> {
                    put("type", "quic")
                    val quicSecurity = raw?.get("quicSecurity")?.jsonPrimitive?.content ?: "none"
                    val key = raw?.get("key")?.jsonPrimitive?.content ?: ""
                    put("security", quicSecurity)
                    put("key", key)
                }
                Transport.HTTP -> {
                    put("type", "http")
                    val path = raw?.get("path")?.jsonPrimitive?.content ?: "/"
                    put("path", path)
                    val host = raw?.get("host")?.jsonPrimitive?.content
                    if (host != null) {
                        put("host", buildJsonArray {
                            host.split(",").forEach { add(it.trim()) }
                        })
                    }
                }
                else -> { /* TCP — no transport object needed */ }
            }
        })
    }

    // ------------------------------------------------------------------
    // Route
    // ------------------------------------------------------------------

    /**
     * Simple routing: traffic arriving on the SOCKS or HTTP inbound is
     * sent to the "proxy" outbound. All other traffic uses the default
     * (first) outbound, which is "proxy".
     */
    private fun buildRoute(): JsonObject = buildJsonObject {
        put("rules", buildJsonArray {
            add(buildJsonObject {
                put("inbound", buildJsonArray {
                    add("socks-in")
                    add("http-in")
                })
                put("outbound", "proxy")
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
