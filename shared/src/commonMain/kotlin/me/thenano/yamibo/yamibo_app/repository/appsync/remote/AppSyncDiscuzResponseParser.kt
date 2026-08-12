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
                messageText?.contains(OPERATION_SUCCEEDED) == true
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
        body.contains("backup01.jpg") ||
            body.contains(MAINTENANCE_TRADITIONAL) ||
            body.contains(MAINTENANCE_SIMPLIFIED) ||
            messageText?.contains(MAINTENANCE_TRADITIONAL) == true ||
            messageText?.contains(MAINTENANCE_SIMPLIFIED) == true

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
            text.contains("access denied", ignoreCase = true) ||
            text.contains(NO_PERMISSION_TRADITIONAL) ||
            text.contains(NO_PERMISSION_SIMPLIFIED)
    }

    private fun isFormExpired(messageText: String?): Boolean {
        val text = messageText.orEmpty()
        return text.contains("formhash", ignoreCase = true) ||
            text.contains("form expired", ignoreCase = true) ||
            text.contains("stale form", ignoreCase = true) ||
            text.contains(FORM_VALIDATION_TRADITIONAL) ||
            text.contains(FORM_VALIDATION_SIMPLIFIED) ||
            text.contains(INVALID_REFERRER_TRADITIONAL) ||
            text.contains(INVALID_REFERRER_SIMPLIFIED)
    }

    private const val BODY_PREVIEW_LIMIT = 300
    private const val OPERATION_SUCCEEDED = "\u64cd\u4f5c\u6210\u529f"
    private const val MAINTENANCE_TRADITIONAL = "\u7dad\u8b77"
    private const val MAINTENANCE_SIMPLIFIED = "\u7ef4\u62a4"
    private const val NO_PERMISSION_TRADITIONAL = "\u6c92\u6709\u6b0a\u9650"
    private const val NO_PERMISSION_SIMPLIFIED = "\u65e0\u6743\u9650"
    private const val FORM_VALIDATION_TRADITIONAL = "\u8868\u55ae\u9a57\u8b49"
    private const val FORM_VALIDATION_SIMPLIFIED = "\u8868\u5355\u9a8c\u8bc1"
    private const val INVALID_REFERRER_TRADITIONAL = "\u4f86\u8def\u4e0d\u6b63\u78ba"
    private const val INVALID_REFERRER_SIMPLIFIED = "\u6765\u8def\u4e0d\u6b63\u786e"
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
