package me.thenano.yamibo.yamibo_app.repository.servernotice

import io.github.littlesurvival.core.YamiboResult
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText

/** Validation branch only: real HTTP 524, adapted to yamibo-api's error envelope. */
object ServerNoticeQaHttp {
    suspend fun failure(): YamiboResult.Failure {
        val client = HttpClient()
        return try {
            val response = client.get("http://10.0.2.2:8768/forum")
            YamiboResult.Failure("[HTTP ${response.status.value}] 請求失敗\n  url    : https://bbs.yamibo.com/forum.php\n  body   : ${response.bodyAsText()}")
        } finally { client.close() }
    }
}
