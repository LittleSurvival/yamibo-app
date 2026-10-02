package me.thenano.yamibo.yamibo_app.notification.bridge

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotificationProtocolTest {
    private val origin = "https://bbs.yamibo.com"
    private val eventId = "12345678-0000-4000-8000-000000000001"
    private val cursor = "0123456789abcdef".repeat(3)

    private fun signal(
        source: String = "\"notification_id\":9223372036854775807",
        timestamp: String = "1790812800",
    ): String =
        """{"version":1,"site_id":"yamibo","event_id":"$eventId","timestamp":$timestamp,"source":{$source}}"""

    @Test
    fun exactHttpsOriginAllowsTrailingSlashCaseInsensitiveHostAndDefaultPort() {
        assertEquals(origin, trustedNotificationOrigin(origin, origin))
        assertEquals(origin, trustedNotificationOrigin("$origin/", "$origin/"))
        assertEquals("https://BBS.YAMIBO.COM", trustedNotificationOrigin("https://BBS.YAMIBO.COM", origin))
        assertEquals("$origin:443", trustedNotificationOrigin("$origin:443/", origin))
        assertEquals(origin, trustedNotificationOrigin(origin, "$origin:443/"))
        assertEquals("$origin:8443", trustedNotificationOrigin("$origin:8443", "$origin:8443"))
    }

    @Test
    fun untrustedOrNonOriginConfigurationNeverReceivesForumCookies() {
        listOf(
            "", " ", "not a URL", "http://bbs.yamibo.com", "//bbs.yamibo.com",
            "https://evil.example", "https://bbs.yamibo.com.evil.example",
            "https://bbs.yamibo.com@evil.example", "https://user:password@bbs.yamibo.com",
            "https://user@bbs.yamibo.com", "$origin:8443", "$origin/path", "$origin//",
            "$origin/notification-api", "$origin?site=yamibo", "$origin?", "$origin#fragment",
            "$origin#", "$origin/%2e", "$origin\\@evil.example", "$origin\n",
        ).forEach { candidate ->
            assertNull(trustedNotificationOrigin(candidate, origin), candidate)
        }
    }

    @Test
    fun invalidNativeForumOriginFailsClosedToo() {
        listOf("http://bbs.yamibo.com", "$origin/path", "$origin?token=secret", "$origin#x", "").forEach {
            assertNull(trustedNotificationOrigin(origin, it), it)
        }
    }

    @Test
    fun everySupportedSignalAcceptsLongSourceIdsWithoutFloatOrIntConversion() {
        val accepted = listOf(
            "notification.upsert" to "\"notification_id\":9223372036854775807",
            "pm.created" to "\"pmid\":2147483648,\"plid\":9223372036854775807,\"pmtype\":1",
            "pm.announcement" to "\"gpmid\":9223372036854775807",
        )
        accepted.forEach { (type, source) ->
            assertEquals(NotificationSignal(type, eventId), parseNotificationSignal(type, signal(source)), type)
        }
    }

    @Test
    fun signalRetainsOnlyTypeAndEventIdEvenWhenOptionalContentIsPresent() {
        val raw = signal().dropLast(1) + ",\"content_html\":\"<script>untrusted()</script>\",\"url\":\"https://evil.example\"}"
        assertEquals(NotificationSignal("notification.upsert", eventId), parseNotificationSignal("notification.upsert", raw))
    }

    @Test
    fun unsupportedTypeVersionAndSiteAreRejected() {
        listOf("message", "resync", "notification.deleted", "PM.CREATED", "").forEach {
            assertNull(parseNotificationSignal(it, signal()), it)
        }
        listOf(
            signal().replace("\"version\":1", "\"version\":2"),
            signal().replace("\"version\":1,", ""),
            signal().replace("\"site_id\":\"yamibo\"", "\"site_id\":\"other\""),
            signal().replace("\"site_id\":\"yamibo\",", ""),
        ).forEach { assertNull(parseNotificationSignal("notification.upsert", it), it) }
    }

    @Test
    fun malformedJsonMissingFieldsAndInvalidSourceIdsAreRejected() {
        listOf(
            "", "{", "[]", "null", "true", "\"text\"", "{}", signal() + "{}",
            signal().replace("\"source\":{\"notification_id\":9223372036854775807}", "\"source\":null"),
            signal().replace("\"source\":{\"notification_id\":9223372036854775807}", "\"source\":[]"),
            signal(""), signal("\"notification_id\":0"), signal("\"notification_id\":-1"),
            signal("\"notification_id\":1.5"), signal("\"notification_id\":9223372036854775808"),
            signal("\"notification_id\":true"), signal("\"notification_id\":null"),
            signal(timestamp = "-1"), signal(timestamp = "1.5"), signal(timestamp = "null"),
            signal().replace("\"timestamp\":1790812800,", ""),
        ).forEach { assertNull(parseNotificationSignal("notification.upsert", it), it) }
        assertNull(parseNotificationSignal("pm.created", signal("\"pmid\":1")))
        assertNull(parseNotificationSignal("pm.created", signal("\"plid\":1")))
        assertNull(parseNotificationSignal("pm.announcement", signal("\"gpmid\":0")))
    }

    @Test
    fun numericEnvelopeFieldsMustBeJsonNumbersRatherThanQuotedStrings() {
        listOf(
            signal().replace("\"version\":1", "\"version\":\"1\""),
            signal(timestamp = "\"1790812800\""),
            signal("\"notification_id\":\"9223372036854775807\""),
        ).forEach { assertNull(parseNotificationSignal("notification.upsert", it), it) }
    }

    @Test
    fun eventIdMustBeAJsonStringWithBoundedNonControlText() {
        listOf("1", "true", "null", "\"\"", "\"${"x".repeat(129)}\"", "\"bad\\nvalue\"", "\"bad\\u0000value\"").forEach {
            val raw = signal().replace("\"event_id\":\"$eventId\"", "\"event_id\":$it")
            assertNull(parseNotificationSignal("notification.upsert", raw), raw)
        }
        assertNull(parseNotificationSignal("notification.upsert", signal().replace("\"event_id\":\"$eventId\",", "")))
    }

    @Test
    fun eventIdMatchesServerLowercaseUuidV4Contract() {
        listOf("arbitrary", eventId.replace("4000", "1000"), eventId.replace("8000", "7000"),
            "abcdefab-0000-4000-8000-000000000001".uppercase()).forEach { invalid ->
            assertNull(parseNotificationSignal("notification.upsert", signal().replace(eventId, invalid)))
        }
    }

    @Test
    fun zeroTimestampIsAcceptedAsANonnegativeUnixTimestamp() {
        assertNotNull(parseNotificationSignal("notification.upsert", signal(timestamp = "0")))
    }

    @Test
    fun oversizedSignalIsRejectedBeforeJsonParsingAndBoundaryIsAccepted() {
        val base = signal().dropLast(1) + ",\"padding\":\"\"}"
        val atLimit = base.replace("\"padding\":\"\"", "\"padding\":\"${"x".repeat(MAX_FRAME_CHARS - base.length)}\"")
        assertEquals(MAX_FRAME_CHARS, atLimit.length)
        assertNotNull(parseNotificationSignal("notification.upsert", atLimit))
        assertNull(parseNotificationSignal("notification.upsert", atLimit + " "))
    }

    @Test
    fun sseCommentsCrLfUnknownFieldsAndMultilineDataAreParsed() {
        val parser = NotificationFrameParser()
        listOf(": heartbeat\r", "retry: 15000\r", "unknown: ignored\r", "id: $cursor\r", "event: notification.upsert\r", "data: {\r", "data:   \"version\":1\r", "data: }\r").forEach {
            assertNull(parser.accept(it))
        }
        assertEquals(NotificationFrame(cursor, "notification.upsert", "{\n  \"version\":1\n}"), parser.accept("\r"))
    }

    @Test
    fun sseOpaqueCursorRemainsIndependentFromEventId() {
        val parser = NotificationFrameParser()
        parser.accept("id: $cursor")
        parser.accept("event: notification.upsert")
        parser.accept("data: ${signal()}")
        val frame = assertNotNull(parser.accept(""))
        assertEquals(cursor, frame.id)
        assertEquals(eventId, assertNotNull(parseNotificationSignal(frame.type, frame.data)).eventId)
        assertTrue(cursorPattern.matches(assertNotNull(frame.id)))
        assertFalse(cursorPattern.matches(eventId))
        assertFalse(cursorPattern.matches(cursor.uppercase()))
        assertFalse(cursorPattern.matches(cursor + "0"))
    }

    @Test
    fun sseNulIdIsIgnoredAndExactlyOneOptionalSpaceIsStripped() {
        val parser = NotificationFrameParser()
        parser.accept("id: $cursor")
        parser.accept("id: bad\u0000cursor")
        parser.accept("data:  leading space")
        parser.accept("data:no space")
        assertEquals(NotificationFrame(cursor, "message", " leading space\nno space"), parser.accept(""))
    }

    @Test
    fun sseEmptyFramesDoNotDispatchAndCompletedFramesResetTheirState() {
        val parser = NotificationFrameParser()
        assertNull(parser.accept(": heartbeat"))
        assertNull(parser.accept(""))
        parser.accept("id: $cursor")
        parser.accept("event: notification.upsert")
        parser.accept("data: first")
        assertEquals(NotificationFrame(cursor, "notification.upsert", "first"), parser.accept(""))
        parser.accept("data: second")
        assertEquals(NotificationFrame(null, "message", "second"), parser.accept(""))
        parser.accept("data")
        assertEquals(NotificationFrame(null, "message", ""), parser.accept(""))
    }

    @Test
    fun sseFrameLimitIncludesCommentsAndUnknownFieldsAndResetsAtDelimiter() {
        val parser = NotificationFrameParser()
        assertNull(parser.accept(":" + "x".repeat(MAX_FRAME_CHARS - 1)))
        assertNull(parser.accept(""))
        assertNull(parser.accept("data: ok"))
        assertEquals("ok", assertNotNull(parser.accept("")).data)
        assertFailsWith<IllegalArgumentException> { NotificationFrameParser().accept("x".repeat(MAX_FRAME_CHARS + 1)) }
        val accumulated = NotificationFrameParser()
        accumulated.accept("unknown:" + "x".repeat(MAX_FRAME_CHARS - 8))
        assertFailsWith<IllegalArgumentException> { accumulated.accept("data: x") }
    }

    @Test
    fun exponentialBackoffUsesHalfToFullJitterAndCapsAtOneMinute() {
        val expectations = listOf(-1 to 1_000L, 0 to 1_000L, 1 to 2_000L, 2 to 4_000L, 3 to 8_000L, 4 to 16_000L, 5 to 32_000L, 6 to 60_000L, Int.MAX_VALUE to 60_000L)
        expectations.forEach { (attempt, ceiling) ->
            val random = Random(42)
            repeat(100) {
                val delay = reconnectDelayMillis(attempt, random)
                assertTrue(delay in (ceiling / 2)..ceiling, "attempt=$attempt delay=$delay ceiling=$ceiling")
            }
        }
    }

    @Test
    fun sessionRenewalOccursTwentySecondsEarlyWithinBoundedDelay() {
        val now = 1_790_812_800_000L
        assertEquals(1_000L, renewalDelayMillis(now - 1, now))
        assertEquals(1_000L, renewalDelayMillis(now, now))
        assertEquals(1_000L, renewalDelayMillis(now + 20_000, now))
        assertEquals(1_000L, renewalDelayMillis(now + 21_000, now))
        assertEquals(1_001L, renewalDelayMillis(now + 21_001, now))
        assertEquals(40_000L, renewalDelayMillis(now + 60_000, now))
        assertEquals(280_000L, renewalDelayMillis(now + 300_000, now))
        assertEquals(280_000L, renewalDelayMillis(Long.MAX_VALUE, now))
    }
}
