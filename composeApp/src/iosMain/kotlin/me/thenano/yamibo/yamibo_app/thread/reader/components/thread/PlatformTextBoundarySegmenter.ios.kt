package me.thenano.yamibo.yamibo_app.thread.reader.components.thread

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import me.thenano.yamibo.yamibo_app.Logger
import platform.CoreFoundation.CFRangeMake
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFStringGetRangeOfComposedCharactersAtIndex
import platform.CoreFoundation.CFStringTokenizerAdvanceToNextToken
import platform.CoreFoundation.CFStringTokenizerCreate
import platform.CoreFoundation.CFStringTokenizerGetCurrentTokenRange
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreFoundation.kCFStringTokenizerTokenNone
import platform.CoreFoundation.kCFStringTokenizerUnitSentence
import platform.CoreFoundation.kCFStringTokenizerUnitWordBoundary

internal actual fun createPlatformTextBoundarySegmenter(
    fallback: TextBoundarySegmenter,
): TextBoundarySegmenter = IosCoreFoundationTextBoundarySegmenter(fallback)

@OptIn(ExperimentalForeignApi::class)
private class IosCoreFoundationTextBoundarySegmenter(
    private val fallback: TextBoundarySegmenter,
) : TextBoundarySegmenter {
    override fun sentenceBoundaries(text: String, locale: String?): IntArray =
        tokenizerBoundaries(text, kCFStringTokenizerUnitSentence)
            ?: fallback.sentenceBoundaries(text, locale)

    override fun lineBreakBoundaries(text: String, locale: String?): IntArray =
        tokenizerBoundaries(text, kCFStringTokenizerUnitWordBoundary)
            ?: fallback.lineBreakBoundaries(text, locale)

    override fun graphemeBoundaries(text: String, locale: String?): IntArray = runCatching {
        withCoreFoundationString(text) { string ->
            buildList {
                var index = 0L
                while (index < text.length) {
                    val range = CFStringGetRangeOfComposedCharactersAtIndex(string, index)
                    val end = range.useContents { location + length }
                    if (end <= index) break
                    add(end.toInt())
                    index = end
                }
            }.toIntArray()
        }
    }
        .onFailure { Logger.d(TAG, "CoreFoundation grapheme boundary detection failed; using fallback segmenter", it) }
        .getOrNull() ?: fallback.graphemeBoundaries(text, locale)

    private fun tokenizerBoundaries(text: String, unit: ULong): IntArray? = runCatching {
        withCoreFoundationString(text) { string ->
            val tokenizer = CFStringTokenizerCreate(
                alloc = null,
                string = string,
                range = CFRangeMake(0, text.length.toLong()),
                options = unit,
                locale = null,
            ) ?: error("Unable to create CFStringTokenizer")
            try {
                buildList {
                    while (CFStringTokenizerAdvanceToNextToken(tokenizer) != kCFStringTokenizerTokenNone) {
                        val range = CFStringTokenizerGetCurrentTokenRange(tokenizer)
                        val end = range.useContents { location + length }
                        if (end > 0) add(end.toInt())
                    }
                }.toIntArray()
            } finally {
                CFRelease(tokenizer)
            }
        }
    }
        .onFailure { Logger.d(TAG, "CoreFoundation tokenizer boundary detection failed; using fallback segmenter", it) }
        .getOrNull()

    private inline fun <T> withCoreFoundationString(text: String, block: (platform.CoreFoundation.CFStringRef) -> T): T {
        val string = CFStringCreateWithCString(null, text, kCFStringEncodingUTF8)
            ?: error("Unable to create CFString")
        return try {
            block(string)
        } finally {
            CFRelease(string)
        }
    }
}

private const val TAG = "TextBoundarySegmenter"
