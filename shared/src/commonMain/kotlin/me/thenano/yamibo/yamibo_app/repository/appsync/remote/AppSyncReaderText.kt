package me.thenano.yamibo.yamibo_app.repository.appsync.remote

import com.fleeksoft.ksoup.Ksoup

internal const val APP_SYNC_V3_ENVELOPE_MARKER = "YAMIBO_APP_SYNC_ENVELOPE:v3"

/** Discuz renders text with BR/paragraph wrappers. Keep v3's integrity-protected line boundaries intact.
 * No frame extraction: duplicate envelopes or unrelated visible text remain invalid.
 */
internal fun appSyncReaderText(html: String): String {
    // Bound DOM construction before the envelope codec's stricter transport limits.
    if (html.length > 32 * 1024 * 1024) return html
    return try {
        val body = Ksoup.parseBodyFragment(html).body()
        if (!html.contains(APP_SYNC_V3_ENVELOPE_MARKER) && !html.contains(AppSyncV3SegmentCodec.ROOT) &&
            !html.contains(AppSyncV3SegmentCodec.SEGMENT)) body.text()
        else {
            body.select("br,p,div,li").after("\n")
            body.wholeText().replace("\r\n", "\n").split('\n', limit = 64)
                .filter { it.isNotBlank() }.joinToString("\n")
        }
    } catch (_: Exception) { html }
}
