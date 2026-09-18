package me.thenano.yamibo.yamibo_app.repository.appsync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.coroutines.runBlocking
import me.thenano.yamibo.yamibo_app.repository.BackupRepository
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.DatabaseSyncDomainMaterializer
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.SqlDelightSyncDomainStateAdapter
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.ResolvedSyncEntity
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.ResolvedSyncField
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.SyncEntityKey
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.BackupSnapshotMigrationPlanner
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.OperationReducer
import me.thenano.yamibo.yamibo_app.repository.appsync.domain.SyncDomainRegistry
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncBulkDeleteProofFields
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.*
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncCheckpointEnvelopeCodec
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncCheckpointValidation
import me.thenano.yamibo.yamibo_app.repository.backup.*
import me.thenano.yamibo.yamibo_app.repository.settings.AppSettingsRepository
import me.thenano.yamibo.yamibo_app.repository.settings.MangaReaderSettingsRepository
import me.thenano.yamibo.yamibo_app.repository.settings.NovelReaderSettingsRepository
import me.thenano.yamibo.yamibo_app.repository.settings.core.*
import me.thenano.yamibo.yamibo_app.repository.appsync.schema.*

class AppSyncPortableBoundaryTest {
    @Test
    fun legacyInventoryCoversEveryDomainAndDeclaredContractField() {
        val registry = SyncDomainRegistry.Default
        assertEquals(registry.domainIds.map { it.value }.toSet(), AppSyncLegacyFieldRegistry.fieldsByDomain.keys)
        registry.domainIds.forEach { domain ->
            val contract = requireNotNull(registry.contractFor(domain))
            val contractFields = contract.requiredFieldsByKind.values.flatten() +
                contract.allowedFieldsByKind.values.flatten() + contract.monotonicNumericFields
            contractFields.forEach { field ->
                assertTrue(AppSyncLegacyFieldRegistry.permits(domain.value, field) ||
                    AppSyncPortabilityPolicy.fieldDeclarations.any { it.domain == domain.value && it.field == field },
                    "Unclassified contract field ${domain.value}/$field")
            }
        }
    }

    @Test
    fun unknownFieldsAndDomainsFailClosedIncludingCoverAliasesAndKnownNamesInWrongDomains() {
        AppSyncLegacyFieldRegistry.fieldsByDomain.forEach { (domain, declared) ->
            val entityId = if (domain == "settings") "appsettings.language" else "synthetic"
            val input = declared.associateWith { "fixture" } + mapOf(
                "futureCache" to "<html>private sentinel</html>",
                "coverImage" to "data:image/png;base64,private",
                "coverUrl" to "https://example.test/private",
                "threadCover" to "file:///private",
            )
            assertEquals(declared, portableAppSyncFields(domain, entityId, input).keys)
            val checked = assertIs<AppSyncPortableEntityResult.Portable>(
                AppSyncPortabilityPolicy.sanitizeFields(domain, entityId, input))
            assertEquals(declared, checked.fields.keys)
        }
        assertTrue(portableAppSyncFields("future.domain", "id", mapOf("title" to "private")).isEmpty())
        assertTrue(portableAppSyncFields("reading.image", "id", mapOf("content" to "private")).isEmpty())
        assertTrue(portableAppSyncFields("settings", "new.language", mapOf("value" to "private")).isEmpty())
    }

    @Test
    fun legacyDeleteAuthorizationSurvivesFieldAllowlist() {
        val proof = mapOf(AppSyncBulkDeleteProofFields.SCOPE to "reading-history:all-combined",
            AppSyncBulkDeleteProofFields.COUNT to "101", AppSyncBulkDeleteProofFields.EXPIRES_AT to "9000")
        assertEquals(proof, portableAppSyncFields("reading.thread", "synthetic", proof))
        assertTrue(portableAppSyncFields("future.domain", "synthetic", proof).isEmpty())
    }

    @Test
    fun reductionAndCheckpointScrubUnknownFieldsFromValuesAndWinningOperations() {
        val device = SyncDeviceId("synthetic")
        val epoch = SyncDeviceEpoch("epoch")
        val sequence = SyncSequence(1)
        val operation = SyncOperation(SyncOperation.idFor(device, epoch, sequence), device, epoch, sequence,
            SyncAccountBinding("test"), SyncDomainId("reading.thread"), SyncEntityId("42"),
            kind = SyncOperationKind.Patch, fields = mapOf("page" to "2", "futureCache" to "private sentinel"),
            createdAtEpochMillis = 1, origin = SyncOperationOrigin.UserAction)
        val source = ResolvedSyncEntity(SyncEntityKey(operation.domainId, operation.entityId, 1),
            fields = operation.fields.mapValues { ResolvedSyncField(it.value, operation) })
        val reduced = OperationReducer().reduce(current = mapOf(source.key to source), operations = listOf(operation))
        assertTrue(reduced.quarantined.isEmpty())
        assertEquals(setOf("page"), reduced.entities.getValue(source.key).fields.keys)
        assertEquals(mapOf("page" to "2"), reduced.appliedOperations.single().fields)
        val codec = AppSyncCheckpointEnvelopeCodec()
        val payload = codec.createPayload("portable", operation.accountBinding, SyncCausalContext(),
            YamiboBackupFile(appVersionCode = 1, createdAt = 1), listOf(source), emptyList(), 1)
        assertEquals(mapOf("page" to "2"), payload.resolvedEntities.single().fields.getValue("page").operation.fields)
        assertFailsWith<IllegalArgumentException> { codec.encode(payload.copy(resolvedEntities = listOf(source))) }
        val unknown = source.copy(key = source.key.copy(domainId = SyncDomainId("future.domain")))
        assertTrue(listOf(unknown).withoutExcludedAppSyncPayloads().isEmpty())
        assertTrue(operation.fields.containsKey("futureCache")) // Historical evidence is not modified in place.
    }

    @Test
    fun allProductionSettingsRequireExplicitClassification() {
        val store = MutableTestSettingsStore()
        val keys = listOf(AppSettingsRepository(store), NovelReaderSettingsRepository(store),
            MangaReaderSettingsRepository(store)).flatMap { it.exportableSettingItems }.map { it.storageKey }
        assertTrue(keys.isNotEmpty())
        assertEquals(emptyList(), keys.filterNot(AppSyncPortabilityPolicy::isSettingDeclared))
    }

    @Test
    fun canonicalSettingsMatchProductionTypesAndRoundTripEveryPortableDefault() {
        val store = MutableTestSettingsStore()
        val items = listOf(AppSettingsRepository(store), NovelReaderSettingsRepository(store),
            MangaReaderSettingsRepository(store)).flatMap { it.exportableSettingItems }
            .filter { AppSyncPortabilityPolicy.isSettingPortable(it.storageKey) }
        assertEquals(items.map { it.storageKey }.toSet(), AppSyncCanonicalSettings.entries.keys)
        items.forEach { item ->
            val expected = when (item) {
                is BoolSetting -> AppSyncValueType.Boolean
                is IntSetting -> AppSyncValueType.Integer
                is FloatSetting -> AppSyncValueType.Decimal
                is EnumSetting<*> -> AppSyncValueType.Enum
                is StringSetting -> AppSyncValueType.Text
                else -> error("Unclassified setting producer")
            }
            assertEquals(expected, AppSyncCanonicalSettings.entries.getValue(item.storageKey).type, item.storageKey)
            val value = (item.default as? Enum<*>)?.name ?: item.default.toString()
            val normalized = assertIs<AppSyncCanonicalFieldsResult.Accepted>(AppSyncCanonicalNormalizer.normalize(
                "settings", item.storageKey, mapOf("value" to value)), item.storageKey)
            assertTrue(normalized.exclusions.isEmpty(), item.storageKey)
            val encoded = AppSyncCanonicalFieldCodec.encode("settings", item.storageKey, normalized.fields)
            assertEquals(normalized.fields, AppSyncCanonicalFieldCodec.decode("settings", item.storageKey, encoded))
        }
    }

    private fun coveredSnapshot(): YamiboBackupFile {
        val original = expandedBackup()
        val sentinel = "https://example.test/private-cover"
        return original.copy(
            favorites = original.favorites.copy(items = original.favorites.items.map { it.copy(coverUrl = sentinel) }),
            readingState = original.readingState.let { it.copy(
                threadHistory = it.threadHistory.map { h -> h.copy(threadCover = sentinel) },
                tagMangaHistory = it.tagMangaHistory.map { h -> h.copy(coverUrl = sentinel) },
                tagCatalogHistory = it.tagCatalogHistory.map { h -> h.copy(coverUrl = sentinel) },
                rssSearchHistory = it.rssSearchHistory.map { h -> h.copy(coverUrl = sentinel) },
                rssCatalogHistory = it.rssCatalogHistory.map { h -> h.copy(coverUrl = sentinel) },
            ) },
            favoriteUpdates = original.favoriteUpdates.copy(events = original.favoriteUpdates.events.map {
                it.copy(coverUrl = sentinel)
            }),
        )
    }

    private fun resolved(snapshot: YamiboBackupFile): Collection<ResolvedSyncEntity> {
        val operations = BackupSnapshotMigrationPlanner().plan(snapshot).mapIndexed { index, draft ->
            val device = SyncDeviceId("d")
            val epoch = SyncDeviceEpoch("e")
            val sequence = SyncSequence(index.toLong() + 1)
            SyncOperation(SyncOperation.idFor(device, epoch, sequence), device, epoch, sequence,
                SyncAccountBinding("test"), draft.domainId, draft.entityId, kind = draft.kind,
                fields = draft.fields, createdAtEpochMillis = 1, origin = SyncOperationOrigin.Migration)
        }
        val state = OperationReducer().reduce(operations = operations)
        assertTrue(state.quarantined.isEmpty(), state.quarantined.map { it.reason }.toString())
        return state.entities.values
    }

    @Test
    fun checkpointRemovesCoversFromEverySnapshotDomainButLocalBackupIsUnchanged() {
        val source = coveredSnapshot()
        val sentinel = "https://example.test/private-cover"
        val localCodec = CloudBackupPayloadCodec()
        val localEncoded = localCodec.encode(source).getOrThrow()
        val codec = AppSyncCheckpointEnvelopeCodec()
        val payload = codec.createPayload("portable", SyncAccountBinding("test"), SyncCausalContext(),
            source, resolvedEntities = resolved(source), tombstones = emptyList(), createdAtEpochMillis = 1)
        val validation = codec.validate(codec.encode(payload))
        val restored = assertIs<AppSyncCheckpointValidation.Valid>(validation, validation.toString()).envelope.snapshot
        assertEquals(7, listOf(restored.favorites.items, restored.readingState.threadHistory,
            restored.readingState.tagMangaHistory, restored.readingState.tagCatalogHistory,
            restored.readingState.rssSearchHistory, restored.readingState.rssCatalogHistory,
            restored.favoriteUpdates.events).count { it.isNotEmpty() })
        assertFalse(Json.encodeToString(YamiboBackupFile.serializer(), restored).contains(sentinel))
        assertEquals(source, localCodec.decode(localEncoded).getOrThrow())
        assertEquals(localEncoded, localCodec.encode(source).getOrThrow())
    }

    @Test
    fun checkpointReplacementPreservesAllLocalCoversButDoesNotResurrectDeletedEntities() = runBlocking {
        val fixture = BackupValidationHarness()
        val source = coveredSnapshot()
        fixture.put("covers", source)
        fixture.repository.restoreBackup("covers", BackupRepository.RestoreMode.Overwrite).getOrThrow()
        val adapter = SqlDelightSyncDomainStateAdapter(fixture.db,
            DatabaseSyncDomainMaterializer(fixture.db, fixture.settings), nowMillis = { 200 })
        val entities = resolved(source)
        fixture.db.transaction { adapter.adoptCheckpointWithinTransaction(entities) }
        assertEquals(List(7) { "https://example.test/private-cover" }, localCovers(fixture))
        assertTrue(fixture.db.appSyncOperationQueries.getResolvedEntities().executeAsList().none {
            it.encodedState.contains("private-cover")
        })
        fixture.db.transaction { adapter.adoptCheckpointWithinTransaction(emptyList()) }
        assertTrue(localCovers(fixture).isEmpty())
    }

    @Test
    fun failedCheckpointReplacementRollsBackLocalCoversAndReleasesTemporaryState() = runBlocking {
        val fixture = BackupValidationHarness()
        val source = coveredSnapshot()
        fixture.put("covers", source)
        fixture.repository.restoreBackup("covers", BackupRepository.RestoreMode.Overwrite).getOrThrow()
        val adapter = SqlDelightSyncDomainStateAdapter(fixture.db,
            DatabaseSyncDomainMaterializer(fixture.db, fixture.settings), nowMillis = { 200 })
        val entities = resolved(source)
        val invalid = entities.map { if (it.key.domainId.value == "favorite.category") it.copy(fields = emptyMap()) else it }
        assertFailsWith<IllegalArgumentException> {
            adapter.adoptCheckpoint(invalid)
        }
        assertEquals(List(7) { "https://example.test/private-cover" }, localCovers(fixture))
        fixture.db.transaction { adapter.adoptCheckpointWithinTransaction(entities) }
        assertEquals(List(7) { "https://example.test/private-cover" }, localCovers(fixture))
    }

    private fun localCovers(fixture: BackupValidationHarness): List<String?> = with(fixture.db) {
        localFavoriteItemQueries.getAll().executeAsList().map { it.coverUrl } +
            readingHistoryQueries.getAllForBackup().executeAsList().map { it.threadCover } +
            mangaTagReadingHistoryQueries.getAll().executeAsList().map { it.coverUrl } +
            tagCatalogReadingHistoryQueries.getAll().executeAsList().map { it.coverUrl } +
            rssSearchReadingHistoryQueries.getAll().executeAsList().map { it.coverUrl } +
            rssCatalogReadingHistoryQueries.getAll().executeAsList().map { it.coverUrl } +
            favoriteUpdateEventQueries.getAll().executeAsList().map { it.coverUrl }
    }

    @Test
    fun checkpointCleansWinningOperationsAndRejectsBypassedProjectionSanitizer() {
        val operation = SyncOperation(
            operationId = SyncOperation.idFor(SyncDeviceId("d"), SyncDeviceEpoch("e"), SyncSequence(1)),
            deviceId = SyncDeviceId("d"), deviceEpoch = SyncDeviceEpoch("e"), sequence = SyncSequence(1),
            accountBinding = SyncAccountBinding("test"), domainId = SyncDomainId("reading.thread"),
            entityId = SyncEntityId("42"), kind = SyncOperationKind.Patch,
            fields = mapOf("page" to "2", "threadCover" to "data:image/png;base64,secret"),
            createdAtEpochMillis = 1, origin = SyncOperationOrigin.UserAction,
        )
        val entity = ResolvedSyncEntity(SyncEntityKey(operation.domainId, operation.entityId, 1),
            fields = operation.fields.mapValues { ResolvedSyncField(it.value, operation) })
        val codec = AppSyncCheckpointEnvelopeCodec()
        val payload = codec.createPayload("portable", operation.accountBinding, SyncCausalContext(),
            YamiboBackupFile(appVersionCode = 1, createdAt = 1), listOf(entity), emptyList(), 1)
        val result = payload.resolvedEntities.single()
        assertEquals(setOf("page"), result.fields.keys)
        assertEquals(mapOf("page" to "2"), result.fields.getValue("page").operation.fields)
        assertEquals(operation.operationId, result.fields.getValue("page").operation.operationId)
        assertFailsWith<IllegalArgumentException> { codec.encode(payload.copy(resolvedEntities = listOf(entity))) }
        assertTrue(operation.fields.containsKey("threadCover"))
    }
}
