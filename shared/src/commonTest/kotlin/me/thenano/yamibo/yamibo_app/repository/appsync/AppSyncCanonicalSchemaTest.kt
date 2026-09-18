package me.thenano.yamibo.yamibo_app.repository.appsync

import kotlin.test.*
import me.thenano.yamibo.yamibo_app.repository.appsync.domain.SyncDomainRegistry
import me.thenano.yamibo.yamibo_app.repository.appsync.schema.*

class AppSyncCanonicalSchemaTest {
    @Test
    fun stableSchemaCoversAllLegacyDomainsFieldsAndProofWithoutPortableDuplication() {
        assertEquals(SyncDomainRegistry.REQUIRED_DOMAIN_IDS.map { it.value }.toSet(), AppSyncCanonicalSchema.domains.keys)
        assertEquals((1..19).toList(), AppSyncCanonicalSchema.domainsById.keys.sorted())
        AppSyncLegacyFieldRegistry.fieldsByDomain.forEach { (name, fields) ->
            val domain = AppSyncCanonicalSchema.domains.getValue(name)
            assertTrue(domain.fields.keys.containsAll(fields))
            assertEquals(domain.fields.size, domain.fieldsById.size)
            assertTrue(domain.fields.values.all { it.id in 1..127 && it.maxUtf8Bytes > 0 })
            assertTrue(domain.fields.values.count { it.classification == AppSyncFieldClass.BoundedPresentation } <= 1)
            AppSyncLegacyFieldRegistry.proofFields.forEach { assertFalse(domain.fields.getValue(it).portable) }
        }
        assertEquals(8, AppSyncCanonicalSchema.domains.getValue("reading.thread").id)
        assertEquals(27, AppSyncCanonicalSchema.domains.getValue("reading.thread").fields.getValue("page").id)
        assertEquals(19, AppSyncCanonicalSchema.domains.getValue("detail-note").fields.getValue("content").id)
        assertEquals(1, AppSyncCanonicalSettings.entries.getValue("appsettings.thememode").id)
        assertEquals(49, AppSyncCanonicalSettings.entries.size)
    }

    @Test
    fun normalizedScalarsPreserveTypesAndHaveCanonicalLegacyForms() {
        val cases = listOf(
            Triple(AppSyncValueType.Integer, "+00042", AppSyncCanonicalValue.Integer(42)),
            Triple(AppSyncValueType.Integer, Long.MIN_VALUE.toString(), AppSyncCanonicalValue.Integer(Long.MIN_VALUE)),
            Triple(AppSyncValueType.Decimal, "1e2", AppSyncCanonicalValue.Decimal(100.0)),
            Triple(AppSyncValueType.Decimal, "-0.0", AppSyncCanonicalValue.Decimal(0.0)),
            Triple(AppSyncValueType.Boolean, "false", AppSyncCanonicalValue.Boolean(false)),
            Triple(AppSyncValueType.Identifier, "stable-id", AppSyncCanonicalValue.Identifier("stable-id")),
            Triple(AppSyncValueType.Enum, "Normal", AppSyncCanonicalValue.Enum("Normal")),
            Triple(AppSyncValueType.Text, "001", AppSyncCanonicalValue.Text("001")),
        )
        cases.forEach { (type, text, expected) ->
            assertEquals(expected, parseCanonicalValue(type, text))
            assertEquals(expected, parseCanonicalValue(type, requireNotNull(expected.legacyValue())))
        }
        assertNull(parseCanonicalValue(AppSyncValueType.Integer, "9223372036854775808"))
        assertNull(parseCanonicalValue(AppSyncValueType.Boolean, "TRUE"))
        assertNull(parseCanonicalValue(AppSyncValueType.Decimal, "NaN"))
        assertNull(parseCanonicalValue(AppSyncValueType.Decimal, "Infinity"))
    }

    @Test
    fun titlesUseUtf8BoundaryAndAreOmittedNotTruncated() {
        val exact = "百".repeat(170) + "ab"
        val valid = accepted("favorite.item", mapOf("title" to exact))
        assertEquals(AppSyncCanonicalValue.Text(exact), valid.fields[6])
        val oversized = accepted("favorite.item", mapOf("title" to "$exact!", "createdAt" to "1"))
        assertEquals(mapOf(7 to AppSyncCanonicalValue.Integer(1)), oversized.fields)
        assertEquals(AppSyncCanonicalIssueReason.FieldBudget, oversized.exclusions.single().reason)
        assertEquals(513, oversized.exclusions.single().actualBytes)
        assertFalse(oversized.exclusions.toString().contains(exact))
    }

    @Test
    fun malformedTextAndUnpairedSurrogatesNeverEnterPresentation() {
        listOf("<html>secret</html>", "file:///secret", "content://secret", "data:image/png,secret",
            "a\uD800b", "\u0000private").forEach { bad ->
            val result = accepted("favorite.item", mapOf("title" to bad))
            assertTrue(result.fields.isEmpty())
            assertEquals(1, result.exclusions.size)
            assertFalse(result.exclusions.toString().contains("secret"))
        }
    }

    @Test
    fun essentialErrorsBlockWholeEntityWhileNotesRetainExactAuthoredText() {
        val note = " <b>authored</b> content://a\n"
        assertEquals(AppSyncCanonicalValue.Text(note), accepted("detail-note", mapOf("content" to note)).fields[19])
        val failure = assertIs<AppSyncCanonicalFieldsResult.NeedsAttention>(AppSyncCanonicalNormalizer.normalize(
            "detail-note", "private-identity", mapOf("createdAt" to "1", "content" to "x".repeat(128 * 1024 + 1))))
        assertEquals(AppSyncCanonicalIssueReason.FieldBudget, failure.issue.reason)
        assertFalse(failure.toString().contains("private-identity"))
        assertIs<AppSyncCanonicalFieldsResult.NeedsAttention>(AppSyncCanonicalNormalizer.normalize(
            "rss.search-subscription", "id", mapOf("query" to "file:///private")))
        assertIs<AppSyncCanonicalFieldsResult.NeedsAttention>(AppSyncCanonicalNormalizer.normalize(
            "reading.thread", "id", mapOf("page" to "not-a-number")))
    }

    @Test
    fun knownNullsAreDistinctFromMissingFieldsAndRequiredNullFails() {
        assertEquals(mapOf(31 to AppSyncCanonicalValue.Null), accepted("reading.thread", mapOf("anchorPostRatio" to null)).fields)
        assertTrue(accepted("reading.thread", emptyMap()).fields.isEmpty())
        assertIs<AppSyncCanonicalFieldsResult.NeedsAttention>(AppSyncCanonicalNormalizer.normalize(
            "reading.thread", "id", mapOf("page" to null)))
    }

    @Test
    fun derivedParentCacheAndUnknownFieldsAreExcludedWithRedactedDiagnostics() {
        val fields = mapOf("subscriptionSyncId" to "private-id", "subscriptionTitle" to "parent",
            "subscriptionQuery" to "private-query", "coverUrl" to "file:///cover", "futureCache" to "private",
            "postTitle" to "post", "threadTitle" to "thread", "threadPage" to "2")
        val result = accepted("reading.rss-catalog", fields)
        assertEquals(mapOf(48 to AppSyncCanonicalValue.Integer(2)), result.fields)
        assertEquals(7, result.exclusions.size)
        assertFalse(result.toString().contains("private"))
        assertFalse(result.toString().contains("futureCache"))
        assertEquals(result, accepted("reading.rss-catalog", fields.entries.reversed().associate { it.toPair() }))
        assertIs<AppSyncCanonicalFieldsResult.Excluded>(AppSyncCanonicalNormalizer.normalize("future.domain", "id", fields))
    }

    @Test
    fun settingValuesFollowTheDeclaredTypeNotAnUntrustedLegacyTypeTag() {
        val parsed = assertIs<AppSyncCanonicalFieldsResult.Accepted>(AppSyncCanonicalNormalizer.normalize(
            "settings", "appsettings.ismangamode", mapOf("type" to "string", "value" to "true")))
        assertEquals(mapOf(2 to AppSyncCanonicalValue.Boolean(true)), parsed.fields)
        assertIs<AppSyncCanonicalFieldsResult.Excluded>(AppSyncCanonicalNormalizer.normalize(
            "settings", "appsettings.futurecache", mapOf("value" to "private")))
        assertIs<AppSyncCanonicalFieldsResult.NeedsAttention>(AppSyncCanonicalNormalizer.normalize(
            "settings", "appsettings.ismangamode", mapOf("value" to "not-a-bool")))
    }

    private fun accepted(domain: String, fields: Map<String, String?>) =
        assertIs<AppSyncCanonicalFieldsResult.Accepted>(AppSyncCanonicalNormalizer.normalize(domain, "synthetic", fields))
}
