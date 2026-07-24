package me.thenano.yamibo.yamibo_app.thread.reader.components.thread

import me.thenano.yamibo.yamibo_app.Logger
import java.text.BreakIterator
import java.util.Locale

internal actual fun createPlatformTextBoundarySegmenter(
    fallback: TextBoundarySegmenter,
): TextBoundarySegmenter = AndroidBreakIteratorTextBoundarySegmenter(fallback)

private class AndroidBreakIteratorTextBoundarySegmenter(
    private val fallback: TextBoundarySegmenter,
) : TextBoundarySegmenter {
    override fun sentenceBoundaries(text: String, locale: String?): IntArray =
        boundaries(text, locale, BreakIterator::getSentenceInstance)
            ?: fallback.sentenceBoundaries(text, locale)

    override fun lineBreakBoundaries(text: String, locale: String?): IntArray =
        boundaries(text, locale, BreakIterator::getLineInstance)
            ?: fallback.lineBreakBoundaries(text, locale)

    override fun graphemeBoundaries(text: String, locale: String?): IntArray =
        boundaries(text, locale, BreakIterator::getCharacterInstance)
            ?: fallback.graphemeBoundaries(text, locale)

    private fun boundaries(
        text: String,
        localeTag: String?,
        factory: (Locale) -> BreakIterator,
    ): IntArray? = runCatching {
        val locale = localeTag
            ?.takeIf { it.isNotBlank() }
            ?.let(Locale::forLanguageTag)
            ?.takeUnless { it.language.isBlank() }
            ?: Locale.getDefault()
        val iterator = factory(locale).apply { setText(text) }
        buildList {
            var boundary = iterator.first()
            while (boundary != BreakIterator.DONE) {
                if (boundary > 0) add(boundary)
                boundary = iterator.next()
            }
        }.toIntArray()
    }
        .onFailure { Logger.d(TAG, "BreakIterator boundary detection failed; using fallback segmenter", it) }
        .getOrNull()
}

private const val TAG = "TextBoundarySegmenter"
