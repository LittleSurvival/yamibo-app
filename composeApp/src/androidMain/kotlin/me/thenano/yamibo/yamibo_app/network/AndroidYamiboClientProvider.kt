package me.thenano.yamibo.yamibo_app.network

import android.content.Context
import android.webkit.CookieManager
import io.github.littlesurvival.YamiboClient
import io.github.littlesurvival.YamiboRoute
import me.thenano.yamibo.yamibo_app.store.AndroidCookieStore
import me.thenano.yamibo.yamibo_app.util.auth.composeYamiboClientCookieHeader

/** Owns the single Yamibo client for the lifetime of the Android app process. */
internal object AndroidYamiboClientProvider {
    private val client: YamiboClient by lazy { YamiboClient(timeoutMillis = 60_000L) }

    fun get(context: Context): YamiboClient {
        val appContext = context.applicationContext
        val cookieHeader = composeYamiboClientCookieHeader(
            authenticationCookieHeader = AndroidCookieStore(appContext).load(),
            platformCookieHeader = CookieManager.getInstance().getCookie(YamiboRoute.Domain.build()),
        )
        client.setCookie(cookieHeader, importNox = true)
        return client
    }
}
