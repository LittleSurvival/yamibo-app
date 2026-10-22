package me.thenano.yamibo.yamibo_app.repository.servernotice

import kotlinx.serialization.json.Json
import me.thenano.yamibo.yamibo_app.store.settings.SettingsStore

/** Use the raw local store: a remote announcement must never become synced user settings. */
class ServerNoticeStore(private val settings: SettingsStore) {
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): ServerNotice? = runCatching {
        json.decodeFromString<ServerNotice>(settings.getString(KEY, ""))
    }.getOrNull()?.takeIf(ServerNotice::isValid)

    fun save(notice: ServerNotice) {
        settings.putString(KEY, json.encodeToString(notice))
    }

    private companion object { const val KEY = "server_notice.latest_524.v1" }
}
