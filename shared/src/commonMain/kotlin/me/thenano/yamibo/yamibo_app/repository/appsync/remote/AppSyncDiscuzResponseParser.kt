package me.thenano.yamibo.yamibo_app.repository.appsync.remote

import com.fleeksoft.ksoup.Ksoup
import io.github.littlesurvival.dto.value.BlogId
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncCloudConfigDefaults
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncCloudResult

object AppSyncDiscuzResponseParser {
    fun parse(
        statusCode: Int,
        body: String,
        identityHintSources: List<String> = emptyList(),
    ): AppSyncCloudResult<AppSyncPostAcknowledgement> {
        val messageText = extractMessageText(body)
        val preview = safeBodyPreview(body, messageText)

        if (isIllegalRequest(body) || isNotLoggedIn(body, messageText)) {
            return AppSyncCloudResult.NotLoggedIn
        }
        if (statusCode == 503 || isMaintenance(body, messageText)) {
            return AppSyncCloudResult.Maintenance
        }
        if (isNoPermission(messageText)) {
            return AppSyncCloudResult.NoPermission(messageText.orEmpty())
        }
        if (isFormExpired(messageText)) {
            return AppSyncCloudResult.FormExpired(messageText)
        }
        if (statusCode !in 200..299) {
            return AppSyncCloudResult.HttpFailed(statusCode, messageText, preview)
        }

        val successHint =
            body.contains("succeedhandle", ignoreCase = true) ||
                messageText?.contains("????") == true
        val errorHint = body.contains("errorhandle", ignoreCase = true)
        if (successHint && !errorHint) {
            return AppSyncCloudResult.VerifiedSuccess(
                AppSyncPostAcknowledgement(
                    messageText = messageText,
                    candidateBlogIds = extractBlogIdHints(
                        buildList {
                            add(body)
                            addAll(identityHintSources)
                        },
                    ),
                ),
            )
        }
        if (errorHint) {
            return AppSyncCloudResult.HttpFailed(statusCode, messageText, preview)
        }

        return AppSyncCloudResult.ParseFailed(
            reason = "Discuz POST response did not contain a recognized acknowledgement",
            messageText = messageText,
            bodyPreview = preview,
        )
    }

    fun extractMessageText(body: String): String? {
        val html = body.substringAfter("<![CDATA[", body).substringBefore("]]>", body)
        return try {
            val doc = Ksoup.parse(html)
            val messageElement = doc.selectFirst("#messagetext p")
                ?: doc.selectFirst(".jump_c p")
                ?: return null
            messageElement.select("script").remove()
            messageElement.text().trim().ifEmpty { null }
        } catch (_: Throwable) {
            null
        }
    }

    fun safeBodyPreview(body: String?, messageText: String? = null): String? {
        if (!messageText.isNullOrBlank()) return messageText.take(BODY_PREVIEW_LIMIT)
        if (body.isNullOrBlank()) return null
        val redacted = body
            .replace(CONFIG_ENVELOPE_REGEX, "<config-envelope-redacted>")
            .replace(FORM_HASH_REGEX, "$1<redacted>")
            .replace(USER_ID_REGEX, "$1<redacted>")
            .replace(COOKIE_REGEX, "$1<redacted>")
        return redacted.take(BODY_PREVIEW_LIMIT)
    }

    private fun extractBlogIdHints(sources: List<String>): List<BlogId> {
        val hints = linkedSetOf<BlogId>()
        sources.forEach { source ->
            BLOG_ID_HINT_REGEX.findAll(source).forEach { match ->
                match.groupValues[1].toIntOrNull()?.let(::BlogId)?.let(hints::add)
            }
        }
        return hints.toList()
    }

    private fun isMaintenance(body: String, messageText: String?): Boolean =
        body.contains("瘥蝏湔") ||
            body.contains("backup01.jpg") ||
            messageText?.contains("蝏湔") == true ||
            messageText?.contains("蝬剛風") == true

    private fun isIllegalRequest(body: String): Boolean =
        body.contains("illegal request", ignoreCase = true) ||
            body.contains("request rejected", ignoreCase = true)

    private fun isNotLoggedIn(body: String, messageText: String?): Boolean {
        val text = messageText.orEmpty()
        return body.contains("pg_logging") ||
            body.contains("login", ignoreCase = true) && text.contains("login", ignoreCase = true) ||
            text.contains("not logged in", ignoreCase = true) ||
            text.contains("login required", ignoreCase = true) ||
            text.contains("please login", ignoreCase = true)
    }

    private fun isNoPermission(messageText: String?): Boolean {
        val text = messageText.orEmpty()
        return text.contains("no permission", ignoreCase = true) ||
            text.contains("permission denied", ignoreCase = true) ||
            text.contains("access denied", ignoreCase = true)
    }

    private fun isFormExpired(messageText: String?): Boolean {
        val text = messageText.orEmpty()
        return text.contains("formhash", ignoreCase = true) ||
            text.contains("form expired", ignoreCase = true) ||
            text.contains("stale form", ignoreCase = true)
    }

    private const val BODY_PREVIEW_LIMIT = 300
    private val BLOG_ID_HINT_REGEX =
        Regex("""(?:[?&](?:blogid|id)=|['"](?:blogid|id)['"]\s*[:=]\s*['"]?)(\d+)""")
    private val CONFIG_ENVELOPE_REGEX = Regex(
        """\Q[${AppSyncCloudConfigDefaults.MARKER}:BEGIN]\E[\s\S]*?\Q[${AppSyncCloudConfigDefaults.MARKER}:END]\E""",
    )
    private val FORM_HASH_REGEX =
        Regex("""(?i)(formhash(?:=|["']?\s*:\s*["']?))[^&"' <>\r\n]+""")
    private val USER_ID_REGEX = Regex("""(?i)((?:uid|authorid)=)\d+""")
    private val COOKIE_REGEX = Regex("""(?i)(cookie\s*[:=]\s*)[^\r\n]+""")
}
