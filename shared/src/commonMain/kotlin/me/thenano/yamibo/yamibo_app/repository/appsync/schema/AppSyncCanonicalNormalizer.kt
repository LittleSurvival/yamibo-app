package me.thenano.yamibo.yamibo_app.repository.appsync.schema

/** Typed equality for parseable legacy scalars; otherwise only exact equality is safe. */
internal fun equivalentAppSyncLegacyFieldValues(
    domain: String,
    entityId: String,
    field: String,
    previous: String?,
    next: String?,
): Boolean {
    if (previous == next) return true
    if (previous == null || next == null) return false
    val type = if (domain == "settings" && field == "value") {
        AppSyncCanonicalSettings.entries[entityId.lowercase()]?.type
    } else AppSyncCanonicalSchema.domains[domain]?.fields?.get(field)?.type
    if (type == null) return false
    val oldValue = parseCanonicalValue(type, previous) ?: return false
    val newValue = parseCanonicalValue(type, next) ?: return false
    return oldValue == newValue
}

internal enum class AppSyncCanonicalIssueReason {
    UnknownDomain, UnknownSetting, UnknownField, Excluded, InvalidUtf8, InvalidType,
    NullNotAllowed, NonPortableText, FieldBudget, EntityBudget,
}

/** Fixed numeric schema identities only: no user identifier, field name, value or parse exception. */
internal data class AppSyncCanonicalIssue(
    val domainId: Int?,
    val fieldId: Int?,
    val reason: AppSyncCanonicalIssueReason,
    val actualBytes: Int = 0,
    val limitBytes: Int = 0,
)

internal sealed interface AppSyncCanonicalFieldsResult {
    data class Accepted(
        val fields: Map<Int, AppSyncCanonicalValue>,
        val exclusions: List<AppSyncCanonicalIssue>,
    ) : AppSyncCanonicalFieldsResult
    data class NeedsAttention(val issue: AppSyncCanonicalIssue) : AppSyncCanonicalFieldsResult
    data class Excluded(val issue: AppSyncCanonicalIssue) : AppSyncCanonicalFieldsResult
}

/** Atomic conversion: a malformed/oversized essential field never returns a partial entity. */
internal object AppSyncCanonicalNormalizer {
    fun normalize(domainName: String, entityId: String, fields: Map<String, String?>): AppSyncCanonicalFieldsResult {
        val domain = AppSyncCanonicalSchema.domains[domainName]
            ?: return AppSyncCanonicalFieldsResult.Excluded(
                AppSyncCanonicalIssue(null, null, AppSyncCanonicalIssueReason.UnknownDomain))
        val setting = if (domainName == "settings") {
            AppSyncCanonicalSettings.entries[entityId.lowercase()]
                ?: return AppSyncCanonicalFieldsResult.Excluded(
                    AppSyncCanonicalIssue(domain.id, null, AppSyncCanonicalIssueReason.UnknownSetting))
        } else null
        val originalDiscriminator = fields["sourceDiscriminator"]
        val portableFields = if (domain.id == 17 && originalDiscriminator?.startsWith("legacy-ambiguous|") == true) {
            val digest = try {
                require(originalDiscriminator.length <= AppSyncCanonicalSchema.ENTITY_BYTES)
                require(fields["ambiguous"] == "true" && fields["detailIds"].isNullOrBlank())
                me.thenano.yamibo.yamibo_app.repository.backup.portableLegacyFavoriteUpdateDiscriminator(
                    requireNotNull(fields["targetType"]), requireNotNull(fields["targetId"]).toLong(),
                    requireNotNull(fields["authorId"]).toLong(), requireNotNull(fields["mode"]), originalDiscriminator)
                    .also { require("event:" + it.substringAfterLast('|') == entityId) }
            } catch (_: Exception) {
                return AppSyncCanonicalFieldsResult.NeedsAttention(AppSyncCanonicalIssue(domain.id, 64,
                    AppSyncCanonicalIssueReason.InvalidType))
            }
            fields + ("sourceDiscriminator" to digest)
        } else fields
        val result = linkedMapOf<Int, AppSyncCanonicalValue>()
        val exclusions = mutableListOf<AppSyncCanonicalIssue>()
        var bytes = 0L
        // Sort for deterministic diagnostics as well as deterministic field order.
        portableFields.entries.sortedBy { it.key }.forEach { (name, raw) ->
            val field = domain.fields[name]
            // Only the default immutable-detail discriminator is derivable. Custom production
            // discriminators (post/update/scan evidence) are essential identity evidence.
            if (domainName == "favorite.update-event" && name == "sourceDiscriminator" &&
                raw != null && raw == defaultAppSyncEventDiscriminator(fields["detailIds"])) {
                exclusions += AppSyncCanonicalIssue(domain.id, field?.id, AppSyncCanonicalIssueReason.Excluded)
                return@forEach
            }
            if (field == null || !field.portable) {
                exclusions += AppSyncCanonicalIssue(domain.id, field?.id,
                    if (field == null) AppSyncCanonicalIssueReason.UnknownField else AppSyncCanonicalIssueReason.Excluded)
                return@forEach
            }
            val encoded = raw?.let { runCatching { it.encodeToByteArray(throwOnInvalidSequence = true) }.getOrNull() }
            val type = if (field.name == "value" && setting != null) setting.type else field.type
            val parsed = raw?.let { parseCanonicalValue(type, it) }
            val reason = when {
                raw == null && !field.nullable -> AppSyncCanonicalIssueReason.NullNotAllowed
                raw != null && encoded == null -> AppSyncCanonicalIssueReason.InvalidUtf8
                encoded != null && encoded.size > field.maxUtf8Bytes -> AppSyncCanonicalIssueReason.FieldBudget
                raw != null && !field.allowMarkup && hasNonPortableText(raw) -> AppSyncCanonicalIssueReason.NonPortableText
                raw != null && parsed == null -> AppSyncCanonicalIssueReason.InvalidType
                else -> null
            }
            if (reason != null) {
                val issue = AppSyncCanonicalIssue(domain.id, field.id, reason, encoded?.size ?: 0, field.maxUtf8Bytes)
                if (field.classification == AppSyncFieldClass.BoundedPresentation) {
                    exclusions += issue
                    return@forEach
                }
                return AppSyncCanonicalFieldsResult.NeedsAttention(issue)
            }
            val value = parsed ?: AppSyncCanonicalValue.Null
            result[field.id] = value
            bytes += (encoded?.size ?: 0) + 8L // Conservative per-value framing allowance.
            if (bytes > AppSyncCanonicalSchema.ENTITY_BYTES) {
                return AppSyncCanonicalFieldsResult.NeedsAttention(AppSyncCanonicalIssue(domain.id, null,
                    AppSyncCanonicalIssueReason.EntityBudget, bytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    AppSyncCanonicalSchema.ENTITY_BYTES))
            }
        }
        return AppSyncCanonicalFieldsResult.Accepted(result.entries.sortedBy { it.key }.associate { it.toPair() }, exclusions)
    }

    private val markup = Regex("<[a-zA-Z!/?][^>]*>")
    private val localUri = Regex("(?:^|[\\s\"'(<])(?:data|file|content):", RegexOption.IGNORE_CASE)

    private fun hasNonPortableText(value: String): Boolean =
        markup.containsMatchIn(value) || localUri.containsMatchIn(value) ||
            value.any { it.code < 32 && it !in "\n\r\t" }
}

internal fun defaultAppSyncEventDiscriminator(detailIds: String?): String? {
    if (detailIds == null || detailIds.length > 32 * 1024) return null
    val ids = runCatching { detailIds.split(',').filter(String::isNotBlank).map(String::toLong).distinct().sorted() }.getOrNull()
        ?: return null
    return ids.takeIf { it.isNotEmpty() }?.let { "details:${it.joinToString(",")}" }
}
