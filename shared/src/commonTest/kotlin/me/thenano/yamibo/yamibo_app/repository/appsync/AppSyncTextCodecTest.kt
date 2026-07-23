package me.thenano.yamibo.yamibo_app.repository.appsync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import me.thenano.yamibo.yamibo_app.repository.appsync.codec.AppSyncInputNormalizer
import me.thenano.yamibo.yamibo_app.repository.appsync.codec.AppSyncTextCodec
import me.thenano.yamibo.yamibo_app.repository.appsync.model.*

class AppSyncTextCodecTest {
    private val codec = AppSyncTextCodec()

    @Test
    fun emptySnapshotRoundTrips() {
        val snapshot = AppSyncEnvelope(exportedAtEpochMillis = 123, sourceAppVersionCode = 4)

        val encoded = assertIs<AppSyncResult.Success<String>>(codec.encode(snapshot)).value
        assertTrue(encoded.startsWith(AppSyncFormat.FULL_FRAME_PREFIX))

        val decoded = assertIs<AppSyncResult.Success<AppSyncEnvelope>>(codec.decode(encoded)).value
        assertEquals(snapshot, decoded)
    }

    @Test
    fun unicodeAndNullableFieldsRoundTrip() {
        val snapshot = AppSyncEnvelope(
            exportedAtEpochMillis = 456,
            sourceAppVersionCode = 4,
            readingHistory = AppSyncReadingHistory(
                thread = listOf(
                    AppSyncThreadHistory(
                        threadId = 42,
                        threadType = "Novel",
                        threadName = "百合＆少女",
                        threadCover = null,
                        authorId = 7,
                        page = 2,
                        postId = 99,
                        postTitle = "章節",
                        anchorPostId = 99,
                        lastVisitTime = 999,
                    ),
                ),
            ),
        )

        val encoded = assertIs<AppSyncResult.Success<String>>(codec.encode(snapshot)).value
        assertEquals(snapshot, assertIs<AppSyncResult.Success<AppSyncEnvelope>>(codec.decode(encoded)).value)
    }

    @Test
    fun namedNumericAndUnicodeNbspAreRemovedBeforeDecode() {
        val snapshot = AppSyncEnvelope(exportedAtEpochMillis = 1, sourceAppVersionCode = 4)
        val encoded = assertIs<AppSyncResult.Success<String>>(codec.encode(snapshot)).value
        val split = AppSyncFormat.FULL_FRAME_PREFIX.length + 8
        val variants = listOf("&nbsp;", "&NBSP;", "&#160;", "&#xA0;", "\u00A0")

        variants.forEach { artifact ->
            val captured = artifact + encoded.substring(0, split) + artifact + encoded.substring(split) + artifact
            assertEquals(snapshot, assertIs<AppSyncResult.Success<AppSyncEnvelope>>(codec.decode(captured)).value)
        }
    }

    @Test
    fun nonAllowlistedHtmlIsRejected() {
        val snapshot = AppSyncEnvelope(exportedAtEpochMillis = 1, sourceAppVersionCode = 4)
        val encoded = assertIs<AppSyncResult.Success<String>>(codec.encode(snapshot)).value
        val captured = encoded.replace(
            AppSyncFormat.FULL_FRAME_PREFIX,
            AppSyncFormat.FULL_FRAME_PREFIX + "<br>",
        )

        val failure = assertIs<AppSyncResult.Failure>(codec.decode(captured))
        assertEquals(AppSyncCodecErrorKind.InvalidBase64, assertIs<AppSyncError.Codec>(failure.error).kind)
    }

    @Test
    fun rawInputLimitIsCheckedBeforeNormalization() {
        val smallNormalizer = AppSyncInputNormalizer(maxRawTextChars = 8)
        val failure = assertIs<AppSyncResult.Failure>(smallNormalizer.normalize("&nbsp;&nbsp;"))
        assertEquals(AppSyncCodecErrorKind.PayloadTooLarge, assertIs<AppSyncError.Codec>(failure.error).kind)
    }

    @Test
    fun malformedFrameAndUnsupportedCodecAreTyped() {
        val malformed = assertIs<AppSyncResult.Failure>(codec.decode("not-a-frame"))
        assertEquals(AppSyncCodecErrorKind.MalformedFrame, assertIs<AppSyncError.Codec>(malformed.error).kind)

        val unsupported = assertIs<AppSyncResult.Failure>(
            codec.decode("${AppSyncFormat.FRAME_PREFIX}999:AAAA"),
        )
        assertEquals(AppSyncCodecErrorKind.UnsupportedCodecVersion, assertIs<AppSyncError.Codec>(unsupported.error).kind)
    }
}
