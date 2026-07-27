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
        return try {
            json.decodeFromString(
                AppSettings.serializer(),
                input.readAll().decodeToString()
            )
        } catch (e: SerializationException) {
            throw CorruptionException("Cannot read AppSettings", e)
        }
    }

    override suspend fun writeTo(t: AppSettings, output: OutputStream) {
        output.write(
            json.encodeToString(AppSettings.serializer(), t).encodeToByteArray()
        )
    }
}
