package me.thenano.yamibo.yamibo_app.factory

import io.ktor.client.*
import io.ktor.client.plugins.*

/** Image requests accept image formats and prevent JSON content negotiation from adding its default. */
const val IMAGE_ACCEPT = "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8"

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
expect object HttpClientFactory {
    fun create(defaultHeaders: DefaultRequest.DefaultRequestBuilder.() -> Unit = {}): HttpClient
}
