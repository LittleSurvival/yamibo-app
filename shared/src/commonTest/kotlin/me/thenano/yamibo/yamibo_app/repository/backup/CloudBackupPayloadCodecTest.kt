package me.thenano.yamibo.yamibo_app.repository.backup

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CloudBackupPayloadCodecTest {
    private val codec = CloudBackupPayloadCodec()

    @Test
    fun roundTripUsesExistingBackupWireModel() {
        val original = YamiboBackupFile(
            appVersionCode = 5,
            createdAt = 1234L,
            settings = listOf(
                BackupSetting("reader.fontSize", BackupSettingType.Float, "18.5"),
            ),
            notes = listOf(
                BackupDetailNote("thread", 42L, 7L, "note", 100L, 200L),
            ),
        )

        val encoded = codec.encode(original).getOrThrow()
        val decoded = codec.decode(encoded).getOrThrow()

        assertTrue(encoded.startsWith("yamibo-app-sync:gzip-base64:1:"))
        assertEquals(original, decoded)
    }

    @Test
    fun rejectsUnframedInput() {
        val failure = codec.decode("plain text")

        assertTrue(failure.isFailure)
    }

    @Test
    fun legacySchemaOneDefaultsExpandedSectionsToEmpty() {
        val legacy = """
            {
              "schemaVersion": 1,
              "appVersionCode": 4,
              "createdAt": 10,
              "readingState": {
                "threadHistory": [],
                "imageHistory": [],
                "tagMangaHistory": [],
                "readingTimeStats": []
              }
            }
        """.trimIndent()
        val decoded = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }.decodeFromString(YamiboBackupFile.serializer(), legacy)

        assertEquals(emptyList(), decoded.readingState.tagCatalogHistory)
        assertEquals(emptyList(), decoded.readingState.rssSearchHistory)
        assertEquals(emptyList(), decoded.readingState.rssCatalogHistory)
        assertEquals(emptyList(), decoded.readingState.chapterState)
        assertEquals(BackupFavoriteUpdates(), decoded.favoriteUpdates)
    }

    @Test
    fun eventIdentityIgnoresDisplayAndDeviceTimeWhenSourceEvidenceExists() {
        val first = favoriteUpdateEventIdentity(
            targetType = "ThreadNormal",
            targetId = 42,
            authorId = 7,
            mode = "NormalThread",
            detailIds = listOf(9, 8, 9),
            ambiguous = false,
            detectedAt = 100,
            summary = "first",
            title = "old title",
        )
        val second = favoriteUpdateEventIdentity(
            targetType = "ThreadNormal",
            targetId = 42,
            authorId = 7,
            mode = "NormalThread",
            detailIds = listOf(8, 9),
            ambiguous = false,
            detectedAt = 999,
            summary = "second",
            title = "new title",
        )

        assertEquals(first, second)
    }

    @Test
    fun eventWithoutEvidenceMustBeExplicitlyAmbiguous() {
        assertFailsWith<IllegalArgumentException> {
            favoriteUpdateEventIdentity(
                targetType = "ThreadNormal",
                targetId = 42,
                authorId = null,
                mode = "NormalThread",
                detailIds = emptyList(),
                ambiguous = false,
                detectedAt = 100,
                summary = "missing evidence",
                title = "title",
            )
        }
    }

    @Test
    fun localAndAppSyncScopesAreExplicit() {
        assertTrue("FavoriteUpdateEvent" in PortableDomainManifest.included(PortableSnapshotScope.LocalBackup))
        assertTrue("FavoriteUpdateEvent" in PortableDomainManifest.included(PortableSnapshotScope.AppSync))
        assertTrue("FavoriteUpdateFidChoice" in PortableDomainManifest.included(PortableSnapshotScope.AppSync))
        assertTrue("FavoriteUpdateCategoryChoice" in PortableDomainManifest.included(PortableSnapshotScope.AppSync))
        assertTrue(PortableDomainManifest.declarations.all {
            it.localBackup == PortableDomainDisposition.Included ||
                !it.exclusionReason.isNullOrBlank()
        })
    }
}
