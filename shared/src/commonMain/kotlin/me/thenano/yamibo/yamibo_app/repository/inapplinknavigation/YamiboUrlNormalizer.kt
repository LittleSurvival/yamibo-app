package me.thenano.yamibo.yamibo_app.repository.inapplinknavigation

fun normalizeYamiboUrl(raw: String): String {
    val cleaned = raw.trim().replace("&amp;", "&")
    return when {
        cleaned.startsWith("http://") || cleaned.startsWith("https://") -> cleaned
        cleaned.startsWith("//") -> "https:$cleaned"
        cleaned.startsWith("/") -> "https://bbs.yamibo.com$cleaned"
        else -> "https://bbs.yamibo.com/$cleaned"
    }
}
