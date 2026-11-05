package me.thenano.yamibo.yamibo_app.factory

import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.github.littlesurvival.fetch.FetchFactory
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import me.thenano.yamibo.yamibo_app.factory.plugin.WafCookieHandshakePlugin

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
actual object HttpClientFactory {

    actual fun create(
        defaultHeaders: DefaultRequest.DefaultRequestBuilder.() -> Unit
    ): HttpClient = HttpClient(OkHttp) {

        install(DefaultRequest) {
            defaultHeaders()
            if (headers[HttpHeaders.UserAgent] == null) {
                headers[HttpHeaders.UserAgent] = FetchFactory.Device.MOBILE.userAgent
            }
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 30_000
            requestTimeoutMillis = 45_000
        }
        install(WafCookieHandshakePlugin)

        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                }
            )
        }
    }
}
