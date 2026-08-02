package com.novavpn.storage.datastore

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import com.novavpn.domain.model.ServerConfig
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream

/**
 * DataStore serializer for the curated mine (معدن) — the bounded list of
 * healthy relays produced by a mine fill, persisted as JSON so it survives
 * app restarts.
 */
object MineSerializer : Serializer<List<ServerConfig>> {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    private val serializer = ListSerializer(ServerConfig.serializer())

    override val defaultValue: List<ServerConfig> = emptyList()

    override suspend fun readFrom(input: InputStream): List<ServerConfig> {
        return try {
            json.decodeFromString(serializer, input.readBytes().decodeToString())
        } catch (e: SerializationException) {
            throw CorruptionException("Cannot read Mine reservoir", e)
        }
    }

    override suspend fun writeTo(t: List<ServerConfig>, output: OutputStream) {
        output.write(json.encodeToString(serializer, t).encodeToByteArray())
    }
}