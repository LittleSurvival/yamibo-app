package me.thenano.yamibo.yamibo_app.repository.servernotice

import io.github.littlesurvival.core.YamiboResult
import io.ktor.http.Url
import me.thenano.yamibo.yamibo_app.repository.ServerNoticeRepository

/** Recognizes yamibo-api's HTTP envelope, never arbitrary response body text. */
fun isForum524Failure(message: String): Boolean {
    val match = HTTP_ENVELOPE.find(message) ?: return false
    if (match.groupValues[1] != "524") return false
    val url = runCatching { Url(match.groupValues[2]) }.getOrNull() ?: return false
    return url.protocol.name == "https" && url.host.equals("bbs.yamibo.com", ignoreCase = true) &&
        url.port == 443 && url.user == null && url.password == null
}

private val HTTP_ENVELOPE = Regex(
    """^\[HTTP (\d{3})][^\r\n]*\r?\n[ \t]*url[ \t]*:[ \t]*(\S+)[ \t]*\r?\n[ \t]*body[ \t]*:""",
)

class ForumErrorMessageResolver(private val notices: ServerNoticeRepository) {
    suspend fun resolve(result: YamiboResult<*>): String = when (result) {
        is YamiboResult.Failure -> resolve(result.reason)
        else -> result.message()
    }

    suspend fun resolve(original: String): String =
        if (isForum524Failure(original)) notices.active524Message() ?: original else original
}
