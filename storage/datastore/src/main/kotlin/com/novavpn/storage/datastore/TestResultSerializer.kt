package com.novavpn.storage.datastore

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import com.novavpn.domain.probe.TestResultEntry
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream

/**
 * DataStore serializer for persisted config-test results (Test Configs
 * screen). The tested-server list survives app restarts; a server is only
 * removed when a NEW re-test returns negative.
 */
object TestResultSerializer : Serializer<List<TestResultEntry>> {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    private val serializer = ListSerializer(TestResultEntry.serializer())

    override val defaultValue: List<TestResultEntry> = emptyList()

    override suspend fun readFrom(input: InputStream): List<TestResultEntry> {
        return try {
            json.decodeFromString(serializer, input.readBytes().decodeToString())
        } catch (e: SerializationException) {
            throw CorruptionException("Cannot read test results", e)
        }
    }

    override suspend fun writeTo(t: List<TestResultEntry>, output: OutputStream) {
        output.write(json.encodeToString(serializer, t).encodeToByteArray())
    }
}
