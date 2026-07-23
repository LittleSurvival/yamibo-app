package me.thenano.yamibo.yamibo_app.repository.appsync.migration

import kotlinx.serialization.json.JsonObject

interface AppSyncMigration {
    val fromVersion: Int
    val toVersion: Int
        get() = fromVersion + 1

    fun migrate(input: JsonObject): JsonObject
}
