package com.novavpn.engine.api

/**
 * Typed error representing all engine-related failures.
 *
 * Provides a machine-readable [ErrorCode] and a human-readable [message]
 * for logging and UI display.
 */
class EngineError(
    val code: ErrorCode,
    override val message: String,
    cause: Throwable? = null
) : Exception(message, cause) {

    enum class ErrorCode {
        /** The configuration could not be parsed. */
        CONFIG_PARSE_FAILURE,

        /** The engine binary is missing or inaccessible. */
        ENGINE_NOT_FOUND,

        /** The engine process failed to start. */
        ENGINE_START_FAILURE,

        /** The engine stopped unexpectedly. */
        ENGINE_CRASHED,

        /** The engine did not respond within the timeout. */
        ENGINE_TIMEOUT,

        /** Permission denied (VPN, notification, etc.). */
        PERMISSION_DENIED,

        /** Internal error. */
        INTERNAL_ERROR,

        /** Unknown/unclassified error. */
        UNKNOWN,
    }
}
