package com.novavpn.engine.api

import com.novavpn.domain.model.Protocol
import com.novavpn.domain.model.ServerConfig
import timber.log.Timber

/**
 * Validates a [ServerConfig] before it is passed to an engine for execution.
 *
 * Catches missing or invalid fields early, preventing engine process crashes
 * due to malformed configuration.
 */
object ConfigValidator {

    private const val TAG = "ConfigValidator"

    /**
     * Validate the given server configuration.
     *
     * @return [Result.success] if the config passes all checks,
     *         [Result.failure] with a descriptive [EngineError] otherwise.
     */
    fun validate(config: ServerConfig): Result<Unit> {
        Timber.tag(TAG).d("Validating config: %s (%s:%d, protocol=%s)",
            config.name, config.address, config.port, config.protocol)

        // 1. Address must be non-empty
        if (config.address.isBlank()) {
            return fail(EngineError.ErrorCode.CONFIG_PARSE_FAILURE,
                "Server address is empty")
        }

        // 2. Port must be valid
        if (config.port <= 0 || config.port > 65535) {
            return fail(EngineError.ErrorCode.CONFIG_PARSE_FAILURE,
                "Invalid port: ${config.port}")
        }

        // 3. Protocol must be supported
        if (config.protocol == Protocol.Unknown) {
            return fail(EngineError.ErrorCode.CONFIG_PARSE_FAILURE,
                "Unknown protocol in server config: ${config.name}")
        }

        // 4. Must have non-empty connection credentials (UUID / password)
        if (config.rawConfig.isBlank()) {
            return fail(EngineError.ErrorCode.CONFIG_PARSE_FAILURE,
                "Server config has no raw connection data")
        }

        // 5. Protocol-specific checks
        when (config.protocol) {
            Protocol.VMess, Protocol.VLESS -> {
                // Must contain an "id" (UUID) field
                if (!hasJsonField(config.rawConfig, "id")) {
                    return fail(EngineError.ErrorCode.CONFIG_PARSE_FAILURE,
                        "${config.protocol.displayName} config missing 'id' field")
                }
            }
            Protocol.Trojan, Protocol.Shadowsocks, Protocol.SOCKS5, Protocol.HTTP -> {
                if (!hasJsonField(config.rawConfig, "password") &&
                    !hasJsonField(config.rawConfig, "method")) {
                    // TLS Reality may not need password on the surface
                    Timber.tag(TAG).d("No password/method field for %s — proceeding",
                        config.protocol.displayName)
                }
            }
            Protocol.Unknown -> { /* caught above */ }
        }

        // 6. Security-specific checks
        if (config.security == com.novavpn.domain.model.Security.Reality) {
            if (!hasJsonField(config.rawConfig, "publicKey")) {
                Timber.tag(TAG).w("Reality security without 'publicKey' field")
            }
        }

        Timber.tag(TAG).i("Config validation PASSED for %s (%s)", config.name, config.id.take(8))
        return Result.success(Unit)
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun fail(code: EngineError.ErrorCode, message: String): Result<Unit> {
        Timber.tag(TAG).w("Config validation FAILED: %s", message)
        return Result.failure(EngineError(code, message))
    }

    /**
     * Quick check whether the raw JSON config contains a top-level field.
     * Returns `true` if the field exists (even with empty value).
     */
    private fun hasJsonField(rawConfig: String, field: String): Boolean {
        if (rawConfig.isBlank()) return false
        return try {
            val element = kotlinx.serialization.json.Json
                { ignoreUnknownKeys = true; isLenient = true }
                .parseToJsonElement(rawConfig)
            if (element is kotlinx.serialization.json.JsonObject) {
                element.containsKey(field)
            } else false
        } catch (_: Exception) {
            false
        }
    }
}