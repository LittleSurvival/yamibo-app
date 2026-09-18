package me.thenano.yamibo.yamibo_app.repository.appsync

import me.thenano.yamibo.yamibo_app.repository.appsync.schema.AppSyncCanonicalSettings

/** Whether a value may leave the installation that produced it. */
internal enum class AppSyncPortability {
    Portable,
    DeviceLocal,
    Cache,
}

internal data class AppSyncSettingPortability(
    val canonicalSuffix: String,
    val portability: AppSyncPortability,
    val includeInLocalBackup: Boolean,
    val semanticLimitBytes: Int? = null,
)

internal data class AppSyncFieldPortability(
    val domain: String,
    val field: String,
    val portability: AppSyncPortability,
    val semanticLimitBytes: Int? = null,
    val sanitizer: (String?) -> String? = { it },
)

internal sealed interface AppSyncPortableEntityResult {
    data class Portable(val fields: Map<String, String?>) : AppSyncPortableEntityResult

    /** Identifiers are deliberately redacted; field values must never be copied here. */
    data class NeedsAttention(
        val domain: String,
        val redactedEntityId: String,
        val encodedBytes: Int,
        val limitBytes: Int,
    ) : AppSyncPortableEntityResult
}

/**
 * The single source of truth for AppSync and backup portability decisions.
 *
 * Settings use normalized suffix matching because historical registries used both dotted and
 * prefixed keys. Portable settings require an explicit declaration; arbitrary prefixes must not
 * gain permission merely by ending with a known preference name. Local backup rules are separate.
 */
internal object AppSyncPortabilityPolicy {
    const val MAX_SANITIZED_ENTITY_BYTES: Int = 256 * 1024

    val settingDeclarations: List<AppSyncSettingPortability> = listOf(
        local("favoritelastcategoryid"),
        cache("signpagehtmlcache"),
        cache("signpagehtmlcacheupdatedat"),
        local("favoriteupdatehiddenrunid"),
        local("appupdatelastcheckat"),
        local("appupdateignoredversioncode"),
        local("backupfolderuri"),
        local("backuplastautobackupat"),
        local("appsynccapacityv2readsenabled"),
        local("appsynccapacityv2writesenabled"),
        local("appsynccapacityautomaticlegacyrecoveryenabled"),
        local("appsynccapacitycleanupdryrun"),
        local("appsynccapacitycleanupdeletionenabled"),
        local("signinlaunchreminderdismisseddate").copy(includeInLocalBackup = true),
        local("signinlaunchreminderdismisstoday").copy(includeInLocalBackup = true),
    )

    private val portableSettingKeys: Set<String> = AppSyncCanonicalSettings.entries.keys

    fun isSettingDeclared(key: String): Boolean = setting(key) != null ||
        key.lowercase() in portableSettingKeys

    val fieldDeclarations: List<AppSyncFieldPortability> = listOf(
        "favorite.item" to "coverUrl",
        "favorite.update-event" to "coverUrl",
        "reading.thread" to "threadCover",
        "reading.tag-manga" to "coverUrl",
        "reading.tag-catalog" to "coverUrl",
        "reading.rss-search" to "coverUrl",
        "reading.rss-catalog" to "coverUrl",
    ).map { (domain, field) ->
        AppSyncFieldPortability(domain, field, AppSyncPortability.Cache)
    }

    fun setting(key: String): AppSyncSettingPortability? {
        val normalized = normalizeSettingKey(key)
        return settingDeclarations.firstOrNull { normalized.endsWith(it.canonicalSuffix) }
    }

    fun settingPortability(key: String): AppSyncPortability =
        setting(key)?.portability ?: if (key.lowercase() in portableSettingKeys) {
            AppSyncPortability.Portable
        } else AppSyncPortability.DeviceLocal

    fun field(domain: String, field: String): AppSyncFieldPortability =
        fieldDeclarations.firstOrNull { it.domain == domain && it.field == field }
            ?: AppSyncFieldPortability(
                domain = domain,
                field = field,
                portability = if (AppSyncLegacyFieldRegistry.permits(domain, field)) {
                    AppSyncPortability.Portable
                } else AppSyncPortability.DeviceLocal,
                semanticLimitBytes = semanticLimitForDomain(domain),
            )

    fun isEntityPortable(domain: String, entityId: String): Boolean =
        domain in AppSyncLegacyFieldRegistry.fieldsByDomain &&
            (domain != "settings" || isSettingPortable(entityId))

    fun isSettingPortable(key: String): Boolean =
        settingPortability(key) == AppSyncPortability.Portable

    fun includeSettingInLocalBackup(key: String): Boolean =
        setting(key)?.includeInLocalBackup ?: true

    fun sanitizeFields(
        domain: String,
        entityId: String,
        fields: Map<String, String?>,
    ): AppSyncPortableEntityResult {
        if (domain == "settings" && !isSettingPortable(entityId)) {
            return AppSyncPortableEntityResult.Portable(emptyMap())
        }
        val sanitized = fields.filterKeys { field(domain, it).portability == AppSyncPortability.Portable }
            .mapValues { (field, value) ->
            val declaration = field(domain, field)
            when (declaration.portability) {
                AppSyncPortability.DeviceLocal, AppSyncPortability.Cache -> null
                AppSyncPortability.Portable -> declaration.sanitizer(value)
            }
        }
        val fieldViolation = sanitized.entries.firstNotNullOfOrNull { (field, value) ->
            val limit = field(domain, field).semanticLimitBytes ?: return@firstNotNullOfOrNull null
            value?.encodeToByteArray()?.size?.takeIf { it > limit }?.let { it to limit }
        }
        val encodedBytes = encodedFieldBytes(sanitized)
        val violation = fieldViolation ?: encodedBytes.takeIf { it > MAX_SANITIZED_ENTITY_BYTES }
            ?.let { it to MAX_SANITIZED_ENTITY_BYTES }
        return if (violation == null) {
            AppSyncPortableEntityResult.Portable(sanitized)
        } else {
            AppSyncPortableEntityResult.NeedsAttention(
                domain = domain,
                redactedEntityId = redactEntityId(entityId),
                encodedBytes = violation.first,
                limitBytes = violation.second,
            )
        }
    }

    private fun encodedFieldBytes(fields: Map<String, String?>): Int = fields.entries.sumOf { (key, value) ->
        key.encodeToByteArray().size + (value?.encodeToByteArray()?.size ?: 0) + 2
    }

    private fun redactEntityId(entityId: String): String =
        "id(len=${entityId.length},hash=${entityId.hashCode().toUInt().toString(16)})"

    private fun normalizeSettingKey(key: String): String =
        key.filter(Char::isLetterOrDigit).lowercase()

    private fun semanticLimitForDomain(domain: String): Int = when {
        domain == "detail-note" -> 128 * 1024
        domain == "settings" -> 64 * 1024
        domain == "favorite.update-event" -> 64 * 1024
        domain.startsWith("reading.") -> 32 * 1024
        domain.startsWith("favorite.") -> 32 * 1024
        domain == "bookmark" -> 32 * 1024
        domain.startsWith("rss.") -> 32 * 1024
        else -> 64 * 1024
    }

    private fun local(suffix: String) = AppSyncSettingPortability(
        canonicalSuffix = suffix,
        portability = AppSyncPortability.DeviceLocal,
        includeInLocalBackup = false,
    )

    private fun cache(suffix: String) = AppSyncSettingPortability(
        canonicalSuffix = suffix,
        portability = AppSyncPortability.Cache,
        includeInLocalBackup = false,
    )
}

internal fun portableRemoteUrlOrNull(value: String?): String? {
    val candidate = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val schemeLength = when {
        candidate.startsWith("https://", ignoreCase = true) -> "https://".length
        candidate.startsWith("http://", ignoreCase = true) -> "http://".length
        else -> return null
    }
    val address = candidate.substring(schemeLength)
    if (address.isBlank() || address.startsWith("data:", ignoreCase = true)) return null
    if (candidate.any(Char::isWhitespace)) return null
    return candidate
}
