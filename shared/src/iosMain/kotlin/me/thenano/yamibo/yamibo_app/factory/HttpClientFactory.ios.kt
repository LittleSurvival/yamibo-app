package me.thenano.yamibo.yamibo_app.factory

import io.ktor.client.*
import io.ktor.client.engine.darwin.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import io.github.littlesurvival.fetch.FetchFactory
import me.thenano.yamibo.yamibo_app.factory.plugin.WafCookieHandshakePlugin
import me.thenano.yamibo.yamibo_app.network.WKWebViewCookieBridge
import platform.Foundation.setHTTPShouldHandleCookies

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "unused")
actual object HttpClientFactory {
    actual fun create(defaultHeaders: DefaultRequest.DefaultRequestBuilder.() -> Unit): HttpClient =
        HttpClient(Darwin) {
            install(DefaultRequest) {
                defaultHeaders()
                if (headers[HttpHeaders.UserAgent] == null) {
                    headers[HttpHeaders.UserAgent] = FetchFactory.Device.MOBILE.userAgent
                }
            }
            // Explicit request cookies and the WAF plugin own this session. NSURLSession must
            // not replace them with a separate, possibly stale Foundation cookie jar.
            engine {
                configureRequest {
                    setHTTPShouldHandleCookies(false)
                    setAllowsCellularAccess(true)
                }
            }
            install(HttpTimeout) {
                connectTimeoutMillis = 15_000
                socketTimeoutMillis = 30_000
                requestTimeoutMillis = 45_000
            }
            install(WafCookieHandshakePlugin) {
                platformCookieHeader = { WKWebViewCookieBridge.cookieHeaderForUrl(it.toString()) }
                userAgent = FetchFactory.Device.MOBILE.userAgent
            }
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                    }
                )
            }
        }
}
