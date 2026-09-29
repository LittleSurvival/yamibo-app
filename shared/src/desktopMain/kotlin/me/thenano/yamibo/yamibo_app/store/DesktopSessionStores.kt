package me.thenano.yamibo.yamibo_app.store

import io.github.littlesurvival.dto.page.ProfilePage
import kotlinx.serialization.json.Json
import me.thenano.yamibo.yamibo_app.repository.AuthRepository
import me.thenano.yamibo.yamibo_app.store.auth.CookieStore
import me.thenano.yamibo.yamibo_app.store.auth.UserStore

class DesktopCookieStore(private val secrets: DesktopSecretStore) : CookieStore {
    @Volatile private var transientHeader: String? = null
    override fun load(): String? = transientHeader ?: secrets.get("authentication")
    @Synchronized override fun save(value: String) {
        val persistent = AuthRepository.completeAuthenticationCookies(value).entries.joinToString("; ") { "${it.key}=${it.value}" }
        if (persistent.isBlank()) secrets.remove("authentication")
        else if (secrets.get("authentication") != persistent) secrets.put("authentication", persistent)
        transientHeader = value
    }
    @Synchronized override fun clear() {
        secrets.remove("authentication")
        transientHeader = null
    }
}

class DesktopUserStore(private val secrets: DesktopSecretStore) : UserStore {
    private val json = Json { ignoreUnknownKeys = true }
    override fun load(): ProfilePage? = secrets.get("profile")?.let { json.decodeFromString(it) }
    override fun save(userInfo: ProfilePage) = secrets.put("profile", json.encodeToString(userInfo))
    override fun clear() = secrets.remove("profile")
}
