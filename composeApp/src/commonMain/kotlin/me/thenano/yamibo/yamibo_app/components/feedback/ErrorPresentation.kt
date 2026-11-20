package me.thenano.yamibo.yamibo_app.components.feedback

internal enum class ErrorKind { Message, Network, Server, Technical }

internal data class ErrorPresentation(
    val kind: ErrorKind,
    val message: String,
    val diagnostics: String? = null,
)

internal fun errorPresentation(original: String): ErrorPresentation {
    val safe = sanitizeErrorDiagnostics(original).trim()
    val kind = when {
        safe.startsWith("[Network]") -> ErrorKind.Network
        safe.startsWith("[HTTP ") -> ErrorKind.Server
        safe.contains("Exception") || safe.contains("Error Domain=") || safe.startsWith("Traceback") -> ErrorKind.Technical
        else -> ErrorKind.Message
    }
    if (kind != ErrorKind.Message) return ErrorPresentation(kind, "", safe)
    // Keep the user-facing explanation accompanying a structural diagnostic code.
    val diagnosticStart = safe.indexOf("\n[")
    return if (diagnosticStart > 0) {
        ErrorPresentation(kind, safe.substring(0, diagnosticStart), safe)
    } else {
        ErrorPresentation(kind, safe)
    }
}

/** Only sanitized text may reach the details view, selection, or clipboard. */
internal fun sanitizeErrorDiagnostics(original: String): String {
    var safe = diagnosticUrl.replace(original) { match ->
        val url = match.value
        val schemeEnd = url.indexOf("://") + 3
        val authorityEnd = url.indexOfAny(charArrayOf('/', '?', '#'), schemeEnd).let {
            if (it < 0) url.length else it
        }
        val authority = url.substring(schemeEnd, authorityEnd).substringAfterLast('@')
        val path = url.substring(authorityEnd).substringBefore('?').substringBefore('#')
        val suffix = if ('?' in url || '#' in url) "?[redacted]" else ""
        url.substring(0, schemeEnd) + authority + path + suffix
    }
    safe = diagnosticHeader.replace(safe) { "${it.groupValues[1]}: [redacted]" }
    safe = diagnosticCredential.replace(safe) { "${it.groupValues[1]}[redacted]" }
    return diagnosticBearer.replace(safe, "Bearer [redacted]")
}

private val diagnosticUrl = Regex("https?://[^\\s<>\\\"']+", RegexOption.IGNORE_CASE)
private val diagnosticHeader = Regex(
    "\\b(Cookie|Set-Cookie|Authorization|Proxy-Authorization)[\\\"']?\\s*[:=]\\s*[^\\r\\n]*",
    RegexOption.IGNORE_CASE,
)
private val diagnosticCredential = Regex(
    "([\\\"']?\\b(?:password|passwd|pwd|token|access[_-]?token|refresh[_-]?token|id[_-]?token|" +
        "api[_-]?key|secret|client[_-]?secret|session(?:id|[_-](?:id|key|token))?|formhash|auth|\\w+_(?:auth|saltkey))" +
        "\\b[\\\"']?\\s*[:=]\\s*)(?:\\[redacted\\]|\\\"[^\\\"]*\\\"|'[^']*'|[^\\s,;&}\\]]+)",
    RegexOption.IGNORE_CASE,
)
private val diagnosticBearer = Regex("\\bBearer\\s+[^\\s,;]+", RegexOption.IGNORE_CASE)
