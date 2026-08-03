package com.novavpn.storage.datastore

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import com.novavpn.domain.model.AppSettings
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream

/**
 * DataStore serializer for [AppSettings].
 * Enables reading/writing settings as JSON via DataStore.
 */
object SettingsSerializer : Serializer<AppSettings> {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    override val defaultValue: AppSettings = AppSettings()

    override suspend fun readFrom(input: InputStream): AppSettings {
        var decoded: AppSettings
        try {
            decoded = json.decodeFromString(
                AppSettings.serializer(),
                input.readBytes().decodeToString()
            )
        } catch (e: SerializationException) {
            throw CorruptionException("Cannot read AppSettings", e)
        }
        return migrate(decoded)
    }

    /**
     * One-time migration of stored settings to the current defaults.
     * Because [Json.encodeDefaults] is true, every field is persisted, so a
     * field whose default just changed (e.g. `enableBlockQuic` → true in
     * v0.16.27) keeps its OLD stored value for existing installs. We tag each
     * write with [AppSettings.settingsVersion]; a value below 1 means the data
     * predates this migration, so we overlay the new defaults onto the four
     * toggles whose default changed and record version 1.
     */
    private fun migrate(current: AppSettings): AppSettings {
        var s = current
        if (s.settingsVersion < 1) {
            s = s.copy(
                enableBlockQuic = true,     // was false → BLOCK QUIC now on by default
                enableTlsFragment = false,
                enableTcpKeepAlive = false, // was true → off
                enableIPv6 = false,         // was true → off
                settingsVersion = 1
            )
        }
        // v2 (v0.17.0): Karing-style config-test settings.
        // Newly added fields decode to their defaults for pre-v2 data, so no
        // overlay is strictly needed — but stamp 2 so future v3 moves are
        // unambiguous. urlTestUrl/urlTestTimeoutSec keep their default values
        // (defaults are the desired ones here).
        if (s.settingsVersion < 2) {
            s = s.copy(
                settingsVersion = 2
            )
        }
        return s
    }

    override suspend fun writeTo(t: AppSettings, output: OutputStream) {
        output.write(
            json.encodeToString(AppSettings.serializer(), t).encodeToByteArray()
        )
    }
}
