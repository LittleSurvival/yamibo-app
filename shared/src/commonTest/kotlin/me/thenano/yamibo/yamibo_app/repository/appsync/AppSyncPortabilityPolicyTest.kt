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
        assertTrue(AppSyncPortabilityPolicy.isSettingPortable("appsettings.thememode"))
        assertTrue(AppSyncPortabilityPolicy.includeSettingInLocalBackup("appsettings.thememode"))
    }

    @Test
    fun fieldPolicyDropsEveryCoverRepresentation() {
        val fields = mapOf("page" to "2", "threadCover" to "data:image/png;base64,AAAA")
        val result = assertIs<AppSyncPortableEntityResult.Portable>(
            AppSyncPortabilityPolicy.sanitizeFields("reading.thread", "42", fields),
        )

        assertEquals("2", result.fields["page"])
        assertNull(result.fields["threadCover"])
        AppSyncPortabilityPolicy.fieldDeclarations.forEach { declaration ->
            listOf("https://example.test/cover.jpg", "http://example.test/c", "data:image/png;base64,AAAA",
                "file:///cover", "content://cover", null).forEach { cover ->
                val sanitized = assertIs<AppSyncPortableEntityResult.Portable>(
                    AppSyncPortabilityPolicy.sanitizeFields(declaration.domain, "42",
                        mapOf(declaration.field to cover)),
                )
                assertFalse(declaration.field in sanitized.fields)
            }
        }
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
                    "targetType" to "y".repeat(100_000),
                    "authorId" to "z".repeat(100_000),
                ),
            ),
        )

        assertFalse(result.redactedEntityId.contains(secretId))
        assertEquals(AppSyncPortabilityPolicy.MAX_SANITIZED_ENTITY_BYTES, result.limitBytes)
    }

    @Test
    fun declaredFieldsReceiveLimitsButUnknownFieldsAreNotPortable() {
        val note = AppSyncPortabilityPolicy.field("detail-note", "content")
        val reading = AppSyncPortabilityPolicy.field("reading.image", "pageIndex")
        val unknown = AppSyncPortabilityPolicy.field("reading.image", "postTitle")
        val cover = AppSyncPortabilityPolicy.field("reading.thread", "threadCover")

        assertEquals(AppSyncPortability.Portable, note.portability)
        assertEquals(128 * 1024, note.semanticLimitBytes)
        assertEquals(32 * 1024, reading.semanticLimitBytes)
        assertEquals(AppSyncPortability.DeviceLocal, unknown.portability)
        assertEquals(AppSyncPortability.Cache, cover.portability)
        assertNull(cover.semanticLimitBytes)
    }

    @Test
    fun unknownSettingsFailClosedWithoutChangingLocalBackupRules() {
        listOf("appsettings.futurehtmlcache", "untrusted.appsettings.thememode", "appsettings.theme").forEach {
            assertFalse(AppSyncPortabilityPolicy.isSettingPortable(it))
            assertTrue(AppSyncPortabilityPolicy.includeSettingInLocalBackup(it))
        }
    }
}
