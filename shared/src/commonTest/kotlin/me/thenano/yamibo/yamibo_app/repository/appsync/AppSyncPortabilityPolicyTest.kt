package me.thenano.yamibo.yamibo_app.repository.appsync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppSyncPortabilityPolicyTest {
    @Test
    fun allKnownDeviceLocalAndCacheAliasesAreExcludedEverywhere() {
        val aliases = listOf(
            "appsettings.signpagehtmlcache",
            "AppSettings.SignPageHtmlCacheUpdatedAt",
            "settings.backup_folder_uri",
            "appsettings.backuplastautobackupat",
            "appsettings.appupdatelastcheckat",
            "appsettings.appupdateignoredversioncode",
            "appsettings.favoriteupdatehiddenrunid",
            "appsettings.favoritelastcategoryid",
        )

        aliases.forEach { key ->
            assertFalse(AppSyncPortabilityPolicy.isSettingPortable(key), key)
            assertFalse(AppSyncPortabilityPolicy.includeSettingInLocalBackup(key), key)
            assertTrue(isAppSyncLocalOnlySetting(key), key)
        }
        assertTrue(AppSyncPortabilityPolicy.isSettingPortable("appsettings.theme"))
        assertTrue(AppSyncPortabilityPolicy.includeSettingInLocalBackup("appsettings.theme"))
    }

    @Test
    fun fieldPolicyDropsOnlyNonportableThreadCoverRepresentation() {
        val fields = mapOf("page" to "2", "threadCover" to "data:image/png;base64,AAAA")
        val result = assertIs<AppSyncPortableEntityResult.Portable>(
            AppSyncPortabilityPolicy.sanitizeFields("reading.thread", "42", fields),
        )

        assertEquals("2", result.fields["page"])
        assertNull(result.fields["threadCover"])
    }

    @Test
    fun entityFallbackReportsOnlyRedactedIdentity() {
        val secretId = "private-user-content"
        val result = assertIs<AppSyncPortableEntityResult.NeedsAttention>(
            AppSyncPortabilityPolicy.sanitizeFields(
                domain = "detail-note",
                entityId = secretId,
                fields = mapOf(
                    "content" to "x".repeat(100_000),
                    "title" to "y".repeat(100_000),
                    "summary" to "z".repeat(100_000),
                ),
            ),
        )

        assertFalse(result.redactedEntityId.contains(secretId))
        assertEquals(AppSyncPortabilityPolicy.MAX_SANITIZED_ENTITY_BYTES, result.limitBytes)
    }

    @Test
    fun everyFieldReceivesPortableMetadataAndDomainSpecificLimit() {
        val note = AppSyncPortabilityPolicy.field("detail-note", "content")
        val reading = AppSyncPortabilityPolicy.field("reading.image", "postTitle")
        val cover = AppSyncPortabilityPolicy.field("reading.thread", "threadCover")

        assertEquals(AppSyncPortability.Portable, note.portability)
        assertEquals(128 * 1024, note.semanticLimitBytes)
        assertEquals(32 * 1024, reading.semanticLimitBytes)
        assertEquals(8 * 1024, cover.semanticLimitBytes)
    }
}
