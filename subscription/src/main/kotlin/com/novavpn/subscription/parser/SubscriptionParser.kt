package com.novavpn.subscription.parser

import com.novavpn.domain.model.EngineFormat
import com.novavpn.domain.model.Protocol
import com.novavpn.domain.model.Security
import com.novavpn.domain.model.ServerConfig
import com.novavpn.domain.model.Transport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.net.URLDecoder
import java.util.Base64
import kotlin.text.Charsets.UTF_8

/**
 * Parses subscription content (raw text) into a list of [ServerConfig] entries.
 *
 * Handles:
 * - Per-protocol proxy links (ss://, vmess://, vless://, trojan://)
 * - Base64-encoded subscription payloads
 * - JSON formats: SIP008 and Xray JSON (sing-box / xray config arrays)
 */
object SubscriptionParser {

    private const val TAG = "SubscriptionParser"

    // Relaxed JSON parser that ignores unknown keys.
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /**
     * Parse a raw subscription string into a list of [ServerConfig].
     * Returns an empty list on any unrecoverable input.
     */
    fun parse(raw: String): List<ServerConfig> {
        if (raw.isBlank()) {
            Timber.tag(TAG).w("parse: empty input")
            return emptyList()
        }

        return try {
            val trimmed = raw.trim()

            when {
                // Single proxy link
                isProxyLink(trimmed) -> listOfNotNull(parseSingleLink(trimmed))

                // Multi-line proxy links (one per line)
                hasMultipleProxyLinks(trimmed) -> parseMultiLinkLines(trimmed)

                // JSON payload (SIP008, Xray, or Sing-box array)
                trimmed.startsWith("[") || trimmed.startsWith("{") -> parseJsonPayload(trimmed)

                // Base64-encoded payload (the most common subscription format)
                isProbablyBase64(trimmed) -> parseBase64Payload(trimmed)

                else -> {
                    Timber.tag(TAG).w("parse: unrecognised format, treating as plain text lines")
                    // Last resort: try splitting by newline and parsing each line
                    parseMultiLinkLines(trimmed)
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "parse: unexpected error")
            emptyList()
        }
    }

    // ------------------------------------------------------------------
    // Public per-link parsers
    // ------------------------------------------------------------------

    /**
     * Parse a VMess link (vmess://...).
     *
     * The payload after "vmess://" is a Base64-encoded JSON object with fields:
     *   add, port, id, aid, scy, net, type, tls, path, host, ps (remark)
     */
    fun parseVmessLink(link: String): ServerConfig? {
        return try {
            val b64 = link.removePrefix("vmess://").trim()
            val decoded = decodeBase64UrlSafe(b64) ?: run {
                Timber.tag(TAG).w("parseVmessLink: failed to decode base64")
                return null
            }

            val obj = json.decodeFromString<JsonObject>(decoded)
            val add = obj["add"]?.jsonPrimitive?.content ?: ""
            val portStr = obj["port"]?.jsonPrimitive?.content ?: "0"
            val port = portStr.toIntOrNull() ?: 0

            if (add.isBlank() || port <= 0) {
                Timber.tag(TAG).w("parseVmessLink: missing address or port")
                return null
            }

            val id = obj["id"]?.jsonPrimitive?.content ?: ""
            val aid = obj["aid"]?.jsonPrimitive?.content ?: "0"
            val scy = obj["scy"]?.jsonPrimitive?.content ?: "auto"
            val net = obj["net"]?.jsonPrimitive?.content ?: "tcp"
            val type = obj["type"]?.jsonPrimitive?.content ?: "none"
            val tlsVal = obj["tls"]?.jsonPrimitive?.content ?: ""
            val path = obj["path"]?.jsonPrimitive?.content ?: ""
            val host = obj["host"]?.jsonPrimitive?.content ?: ""
            val ps = obj["ps"]?.jsonPrimitive?.content ?: ""

            val security = when {
                tlsVal.equals("tls", ignoreCase = true) -> Security.TLS
                tlsVal.equals("reality", ignoreCase = true) -> Security.Reality
                else -> Security.None
            }

            val transport = when (net.lowercase()) {
                "ws", "websocket" -> Transport.WebSocket
                "grpc" -> Transport.gRPC
                "quic" -> Transport.QUIC
                "h2", "http" -> Transport.HTTP
                else -> Transport.TCP
            }

            ServerConfig(
                name = ps.ifBlank { "${add}:${port}" },
                address = add,
                port = port,
                protocol = Protocol.VMess,
                transport = transport,
                security = security,
                rawConfig = link,
                engineFormat = EngineFormat.XrayJson
            )
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "parseVmessLink: failed to parse %s", link.take(60))
            null
        }
    }

    /**
     * Parse a VLESS link (vless://...).
     *
     * Format: vless://uuid@host:port?security=...&type=...&encryption=...&headerType=...&path=...&host=...&flow=...#name
     */
    fun parseVlessLink(link: String): ServerConfig? {
        return try {
            val stripped = link.removePrefix("vless://")
            val hashIdx = stripped.indexOf('#')
            val name = if (hashIdx >= 0) URLDecoder.decode(stripped.substring(hashIdx + 1), "UTF-8") else ""
            val withoutName = if (hashIdx >= 0) stripped.substring(0, hashIdx) else stripped

            val qIdx = withoutName.indexOf('?')
            val userInfo = if (qIdx >= 0) withoutName.substring(0, qIdx) else withoutName
            val queryStr = if (qIdx >= 0) withoutName.substring(qIdx + 1) else ""

            val atIdx = userInfo.indexOf('@')
            if (atIdx < 0) {
                Timber.tag(TAG).w("parseVlessLink: missing @ separator")
                return null
            }
            val id = userInfo.substring(0, atIdx)
            val hostPort = userInfo.substring(atIdx + 1)
            val colonIdx = hostPort.lastIndexOf(':')
            val address = if (colonIdx >= 0) hostPort.substring(0, colonIdx) else hostPort
            val port = if (colonIdx >= 0) hostPort.substring(colonIdx + 1).toIntOrNull() ?: 443 else 443

            if (address.isBlank()) {
                Timber.tag(TAG).w("parseVlessLink: empty address")
                return null
            }

            val params = parseQueryParams(queryStr)
            val tlsVal = params["security"] ?: params["encryption"] ?: ""
            val net = params["type"] ?: "tcp"
            val path = params["path"] ?: ""
            val host = params["host"] ?: params["sni"] ?: ""

            val security = when {
                tlsVal.equals("tls", ignoreCase = true) -> Security.TLS
                tlsVal.equals("reality", ignoreCase = true) -> Security.Reality
                else -> Security.None
            }
            val transport = when (net.lowercase()) {
                "ws", "websocket" -> Transport.WebSocket
                "grpc" -> Transport.gRPC
                "quic" -> Transport.QUIC
                "h2", "http" -> Transport.HTTP
                else -> Transport.TCP
            }

            ServerConfig(
                name = name.ifBlank { "${address}:${port}" },
                address = address,
                port = port,
                protocol = Protocol.VLESS,
                transport = transport,
                security = security,
                rawConfig = link,
                engineFormat = EngineFormat.XrayJson
            )
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "parseVlessLink: failed to parse %s", link.take(60))
            null
        }
    }

    /**
     * Parse a Trojan link (trojan://...).
     *
     * Format: trojan://password@host:port?security=...&type=...&headerType=...&path=...&host=...&sni=...#name
     */
    fun parseTrojanLink(link: String): ServerConfig? {
        return try {
            val stripped = link.removePrefix("trojan://")
            val hashIdx = stripped.indexOf('#')
            val name = if (hashIdx >= 0) URLDecoder.decode(stripped.substring(hashIdx + 1), "UTF-8") else ""
            val withoutName = if (hashIdx >= 0) stripped.substring(0, hashIdx) else stripped

            val qIdx = withoutName.indexOf('?')
            val userInfo = if (qIdx >= 0) withoutName.substring(0, qIdx) else withoutName
            val queryStr = if (qIdx >= 0) withoutName.substring(qIdx + 1) else ""

            val atIdx = userInfo.indexOf('@')
            val password: String
            val hostPort: String
            if (atIdx >= 0) {
                password = userInfo.substring(0, atIdx)
                hostPort = userInfo.substring(atIdx + 1)
            } else {
                // trojan://password@host:port — try parsing as password: rest
                val firstColon = userInfo.indexOf(':')
                if (firstColon < 0) {
                    Timber.tag(TAG).w("parseTrojanLink: missing colon separator")
                    return null
                }
                password = userInfo.substring(0, firstColon)
                hostPort = userInfo.substring(firstColon + 1)
            }

            val colonIdx = hostPort.lastIndexOf(':')
            val address = if (colonIdx >= 0) hostPort.substring(0, colonIdx) else hostPort
            val port = if (colonIdx >= 0) hostPort.substring(colonIdx + 1).toIntOrNull() ?: 443 else 443

            if (address.isBlank()) {
                Timber.tag(TAG).w("parseTrojanLink: empty address")
                return null
            }

            val params = parseQueryParams(queryStr)
            val tlsVal = params["security"] ?: "tls" // Trojan always uses TLS
            val net = params["type"] ?: "tcp"
            val path = params["path"] ?: ""
            val host = params["host"] ?: params["sni"] ?: ""

            val security = when {
                tlsVal.equals("tls", ignoreCase = true) -> Security.TLS
                tlsVal.equals("reality", ignoreCase = true) -> Security.Reality
                else -> Security.TLS // Trojan always uses TLS
            }
            val transport = when (net.lowercase()) {
                "ws", "websocket" -> Transport.WebSocket
                "grpc" -> Transport.gRPC
                "quic" -> Transport.QUIC
                "h2", "http" -> Transport.HTTP
                else -> Transport.TCP
            }

            ServerConfig(
                name = name.ifBlank { "${address}:${port}" },
                address = address,
                port = port,
                protocol = Protocol.Trojan,
                transport = transport,
                security = security,
                rawConfig = link,
                engineFormat = EngineFormat.XrayJson
            )
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "parseTrojanLink: failed to parse %s", link.take(60))
            null
        }
    }

    /**
     * Parse a Shadowsocks link (ss://...).
     *
     * Format: ss://BASE64(method:password)@host:port#name
     * Or SIP002: ss://BASE64(method:password)@host:port/?plugin=...&... #name
     *
     * Older format: ss://BASE64(method:password@host:port)
     */
    fun parseShadowsocksLink(link: String): ServerConfig? {
        return try {
            val stripped = link.removePrefix("ss://").trim()
            val hashIdx = stripped.indexOf('#')
            val name = if (hashIdx >= 0) URLDecoder.decode(stripped.substring(hashIdx + 1), "UTF-8") else ""
            val withoutName = if (hashIdx >= 0) stripped.substring(0, hashIdx) else stripped

            val qIdx = withoutName.indexOf('?')
            val userInfo = if (qIdx >= 0) withoutName.substring(0, qIdx) else withoutName
            val queryStr = if (qIdx >= 0) withoutName.substring(qIdx + 1) else ""

            // Try legacy format first: userInfo is base64 of "method:password@host:port"
            val legacyDecoded = decodeBase64UrlSafe(userInfo)
            if (legacyDecoded != null && legacyDecoded.contains('@')) {
                // Legacy: base64(method:password)@host:port
                val atIdx = legacyDecoded.indexOf('@')
                val methodPassword = legacyDecoded.substring(0, atIdx)
                val hostPort = legacyDecoded.substring(atIdx + 1)
                val colonIdx = hostPort.lastIndexOf(':')
                val address = if (colonIdx >= 0) hostPort.substring(0, colonIdx) else hostPort
                val port = if (colonIdx >= 0) hostPort.substring(colonIdx + 1).toIntOrNull() ?: 443 else 443

                if (address.isBlank()) return null

                return ServerConfig(
                    name = name.ifBlank { "${address}:${port}" },
                    address = address,
                    port = port,
                    protocol = Protocol.Shadowsocks,
                    transport = Transport.TCP,
                    security = Security.None,
                    rawConfig = link,
                    engineFormat = EngineFormat.SIP008
                )
            }

            // SIP002 modern format: base64(method:password)@host:port
            val atIdx = userInfo.indexOf('@')
            if (atIdx < 0) {
                // Try decoding the whole userInfo as base64
                val fullDecoded = decodeBase64UrlSafe(userInfo)
                if (fullDecoded != null && fullDecoded.contains('@')) {
                    val atIdx2 = fullDecoded.indexOf('@')
                    val hostPort2 = fullDecoded.substring(atIdx2 + 1)
                    val colonIdx2 = hostPort2.lastIndexOf(':')
                    val address2 = if (colonIdx2 >= 0) hostPort2.substring(0, colonIdx2) else hostPort2
                    val port2 = if (colonIdx2 >= 0) hostPort2.substring(colonIdx2 + 1).toIntOrNull() ?: 443 else 443
                    if (address2.isBlank()) return null

                    return ServerConfig(
                        name = name.ifBlank { "${address2}:${port2}" },
                        address = address2,
                        port = port2,
                        protocol = Protocol.Shadowsocks,
                        transport = Transport.TCP,
                        security = Security.None,
                        rawConfig = link,
                        engineFormat = EngineFormat.SIP008
                    )
                }
                Timber.tag(TAG).w("parseShadowsocksLink: missing @ separator")
                return null
            }

            val encodedCreds = userInfo.substring(0, atIdx)
            val hostPort = userInfo.substring(atIdx + 1)
            val colonIdx = hostPort.lastIndexOf(':')
            val address = if (colonIdx >= 0) hostPort.substring(0, colonIdx) else hostPort
            val port = if (colonIdx >= 0) hostPort.substring(colonIdx + 1).toIntOrNull() ?: 443 else 443

            if (address.isBlank()) return null

            ServerConfig(
                name = name.ifBlank { "${address}:${port}" },
                address = address,
                port = port,
                protocol = Protocol.Shadowsocks,
                transport = Transport.TCP,
                security = Security.None,
                rawConfig = link,
                engineFormat = EngineFormat.SIP008
            )
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "parseShadowsocksLink: failed to parse %s", link.take(60))
            null
        }
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    private fun isProxyLink(s: String): Boolean {
        return s.startsWith("ss://") ||
                s.startsWith("vmess://") ||
                s.startsWith("vless://") ||
                s.startsWith("trojan://")
    }

    private fun hasMultipleProxyLinks(s: String): Boolean {
        val lines = s.lines().map { it.trim() }.filter { it.isNotBlank() }
        return lines.size > 1 && lines.any { isProxyLink(it) }
    }

    private fun parseSingleLink(link: String): ServerConfig? {
        return when {
            link.startsWith("vmess://") -> parseVmessLink(link)
            link.startsWith("vless://") -> parseVlessLink(link)
            link.startsWith("trojan://") -> parseTrojanLink(link)
            link.startsWith("ss://") -> parseShadowsocksLink(link)
            else -> {
                Timber.tag(TAG).w("parseSingleLink: unsupported protocol in %s", link.take(60))
                null
            }
        }
    }

    private fun parseMultiLinkLines(text: String): List<ServerConfig> {
        return text.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && isProxyLink(it) }
            .mapNotNull { parseSingleLink(it) }
    }

    /**
     * Parse a Base64-encoded payload. The decoded content may be:
     * - Newline-delimited proxy links
     * - A JSON array (SIP008 / Xray)
     * - A single proxy link
     */
    private fun parseBase64Payload(b64: String): List<ServerConfig> {
        val decoded = decodeBase64UrlSafe(b64) ?: run {
            Timber.tag(TAG).w("parseBase64Payload: invalid base64")
            return emptyList()
        }
        Timber.tag(TAG).d("parseBase64Payload: decoded %d chars", decoded.length)

        val trimmed = decoded.trim()
        return when {
            isProxyLink(trimmed) -> listOfNotNull(parseSingleLink(trimmed))
            hasMultipleProxyLinks(trimmed) -> parseMultiLinkLines(trimmed)
            trimmed.startsWith("[") || trimmed.startsWith("{") -> parseJsonPayload(trimmed)
            else -> {
                // Treat as plain text with potential line-based proxy links
                parseMultiLinkLines(trimmed)
            }
        }
    }

    /**
     * Parse a JSON payload — SIP008 format or Xray/Sing-box config array.
     */
    private fun parseJsonPayload(jsonStr: String): List<ServerConfig> {
        return try {
            val element = json.parseToJsonElement(jsonStr)
            when (element) {
                is JsonObject -> {
                    // Xray / sing-box config with "outbounds" array
                    val outbounds = element["outbounds"]?.jsonArray
                    if (outbounds != null) {
                        parseXrayOutbounds(outbounds)
                    } else {
                        // Try SIP008 object (single server)
                        parseSip008Server(element)?.let { listOf(it) } ?: emptyList()
                    }
                }
                is kotlinx.serialization.json.JsonArray -> {
                    // SIP008 array of server objects
                    element.mapNotNull { child ->
                        if (child is JsonObject) parseSip008Server(child) else null
                    }
                }
                else -> {
                    Timber.tag(TAG).w("parseJsonPayload: unexpected JSON element type")
                    emptyList()
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "parseJsonPayload: failed to parse JSON")
            emptyList()
        }
    }

    /**
     * Parse a SIP008 server object.
     *
     * Typical SIP008 object:
     * {
     *   "id": "...",
     *   "remarks": "...",
     *   "server": "...",
     *   "server_port": 443,
     *   "password": "...",
     *   "method": "chacha20-ietf-poly1305",
     *   "plugin": "...",
     *   "plugin_opts": "..."
     * }
     */
    private fun parseSip008Server(obj: JsonObject): ServerConfig? {
        return try {
            val server = obj["server"]?.jsonPrimitive?.content ?: ""
            val port = obj["server_port"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            if (server.isBlank() || port <= 0) return null

            val remarks = obj["remarks"]?.jsonPrimitive?.content ?: ""
            val method = obj["method"]?.jsonPrimitive?.content ?: ""
            val password = obj["password"]?.jsonPrimitive?.content ?: ""

            ServerConfig(
                name = remarks.ifBlank { "${server}:${port}" },
                address = server,
                port = port,
                protocol = Protocol.Shadowsocks,
                transport = Transport.TCP,
                security = Security.None,
                rawConfig = obj.toString(),
                engineFormat = EngineFormat.SIP008
            )
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "parseSip008Server: failed")
            null
        }
    }

    /**
     * Parse outbounds from an Xray / sing-box JSON config.
     *
     * Each outbound with protocol "vmess", "vless", "trojan", or "shadowsocks"
     * is extracted. Settings are read from the "settings" object (Xray) or
     * inline (sing-box).
     */
    private fun parseXrayOutbounds(outbounds: kotlinx.serialization.json.JsonArray): List<ServerConfig> {
        val results = mutableListOf<ServerConfig>()

        for (element in outbounds) {
            if (element !is JsonObject) continue
            try {
                val protocol = element["protocol"]?.jsonPrimitive?.content ?: continue
                val settings = element["settings"]?.jsonObject ?: continue
                val streamSettings = element["streamSettings"]?.jsonObject
                val tag = element["tag"]?.jsonPrimitive?.content ?: ""

                val vnext = settings["vnext"]?.jsonArray
                val servers = settings["servers"]?.jsonArray

                when {
                    protocol == "vmess" || protocol == "vless" -> {
                        if (vnext != null) {
                            for (v in vnext) {
                                if (v !is JsonObject) continue
                                val address = v["address"]?.jsonPrimitive?.content ?: ""
                                val port = v["port"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                                val id = v["id"]?.jsonPrimitive?.content ?: ""
                                if (address.isBlank() || port <= 0) continue

                                val proto = if (protocol == "vmess") Protocol.VMess else Protocol.VLESS
                                val (transport, security) = parseStreamSettings(streamSettings)

                                results.add(
                                    ServerConfig(
                                        name = tag.ifBlank { "${address}:${port}" },
                                        address = address,
                                        port = port,
                                        protocol = proto,
                                        transport = transport,
                                        security = security,
                                        rawConfig = element.toString(),
                                        engineFormat = EngineFormat.XrayJson
                                    )
                                )
                            }
                        }
                    }
                    protocol == "trojan" -> {
                        if (servers != null) {
                            for (s in servers) {
                                if (s !is JsonObject) continue
                                val address = s["address"]?.jsonPrimitive?.content ?: ""
                                val port = s["port"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                                if (address.isBlank() || port <= 0) continue

                                val (transport, security) = parseStreamSettings(streamSettings)

                                results.add(
                                    ServerConfig(
                                        name = tag.ifBlank { "${address}:${port}" },
                                        address = address,
                                        port = port,
                                        protocol = Protocol.Trojan,
                                        transport = transport,
                                        security = security,
                                        rawConfig = element.toString(),
                                        engineFormat = EngineFormat.XrayJson
                                    )
                                )
                            }
                        }
                    }
                    protocol == "shadowsocks" -> {
                        if (servers != null) {
                            for (s in servers) {
                                if (s !is JsonObject) continue
                                val address = s["address"]?.jsonPrimitive?.content ?: ""
                                val port = s["port"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                                if (address.isBlank() || port <= 0) continue

                                results.add(
                                    ServerConfig(
                                        name = tag.ifBlank { "${address}:${port}" },
                                        address = address,
                                        port = port,
                                        protocol = Protocol.Shadowsocks,
                                        transport = Transport.TCP,
                                        security = Security.None,
                                        rawConfig = element.toString(),
                                        engineFormat = EngineFormat.SIP008
                                    )
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "parseXrayOutbounds: skipping outbound")
            }
        }

        return results
    }

    /**
     * Extract [Transport] and [Security] from Xray stream settings.
     */
    private fun parseStreamSettings(ss: JsonObject?): Pair<Transport, Security> {
        if (ss == null) return Transport.TCP to Security.None

        val network = ss["network"]?.jsonPrimitive?.content ?: "tcp"
        val securityStr = ss["security"]?.jsonPrimitive?.content ?: "none"

        val transport = when (network.lowercase()) {
            "ws", "websocket" -> Transport.WebSocket
            "grpc" -> Transport.gRPC
            "quic" -> Transport.QUIC
            "h2", "http" -> Transport.HTTP
            else -> Transport.TCP
        }

        val security = when {
            securityStr.equals("tls", ignoreCase = true) -> Security.TLS
            securityStr.equals("reality", ignoreCase = true) -> Security.Reality
            else -> Security.None
        }

        return transport to security
    }

    /**
     * Parse URL query parameters into a map.
     */
    private fun parseQueryParams(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return query.split("&")
            .mapNotNull { param ->
                val eqIdx = param.indexOf('=')
                if (eqIdx < 0) return@mapNotNull null
                val key = URLDecoder.decode(param.substring(0, eqIdx), "UTF-8")
                val value = URLDecoder.decode(param.substring(eqIdx + 1), "UTF-8")
                key to value
            }
            .toMap()
    }

    /**
     * Decode a Base64 string, handling both standard and URL-safe variants.
     * Also attempts padding correction if the string is missing '=' padding.
     */
    private fun decodeBase64UrlSafe(encoded: String): String? {
        return try {
            val cleaned = encoded
                .replace("-", "+")
                .replace("_", "/")
                .replace(Regex("\\s"), "")

            // Pad to a multiple of 4 if needed
            val padded = when (cleaned.length % 4) {
                2 -> cleaned + "=="
                3 -> cleaned + "="
                else -> cleaned
            }

            val decodedBytes = Base64.getDecoder().decode(padded)
            String(decodedBytes, UTF_8)
        } catch (e: IllegalArgumentException) {
            // Some subscription payloads are not actually base64
            null
        }
    }

    /**
     * Quick heuristic check: a string is "probably" base64 if it's long enough
     * and only contains valid base64 characters.
     */
    private fun isProbablyBase64(s: String): Boolean {
        val cleaned = s.replace(Regex("\\s"), "")
        if (cleaned.length < 20) return false
        // Must match base64 charset
        return cleaned.matches(Regex("^[A-Za-z0-9+/=_-]+$"))
    }
}
