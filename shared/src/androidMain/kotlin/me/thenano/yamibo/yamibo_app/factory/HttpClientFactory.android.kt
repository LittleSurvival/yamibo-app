package me.thenano.yamibo.yamibo_app.factory

import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import me.thenano.yamibo.yamibo_app.network.WafCookieHandshake

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
actual object HttpClientFactory {

    actual fun create(
        defaultHeaders: DefaultRequest.DefaultRequestBuilder.() -> Unit
    ): HttpClient = HttpClient(OkHttp) {

        install(DefaultRequest) {
            defaultHeaders()
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 30_000
            requestTimeoutMillis = 45_000
        }
        install(WafCookieHandshake)

        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                }
            )
        }
    }
}
