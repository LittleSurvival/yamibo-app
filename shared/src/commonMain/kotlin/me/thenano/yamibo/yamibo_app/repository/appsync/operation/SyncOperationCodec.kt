package me.thenano.yamibo.yamibo_app.repository.appsync.operation

import kotlinx.serialization.json.Json

internal class SyncOperationCodec(
    private val json: Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
        explicitNulls = true
    },
) {
    fun encode(operation: SyncOperation): String =
        json.encodeToString(SyncOperation.serializer(), operation)

    fun decode(encoded: String): Result<SyncOperation> = runCatching {
        json.decodeFromString(SyncOperation.serializer(), encoded)
    }
}
