package me.thenano.yamibo.yamibo_app.repository.appsync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import me.thenano.yamibo.yamibo_app.Database
import me.thenano.yamibo.yamibo_app.repository.BookMarkRepository
import me.thenano.yamibo.yamibo_app.repository.AndroidReadHistoryRepository
import me.thenano.yamibo.yamibo_app.repository.DetailNoteRepository
import me.thenano.yamibo.yamibo_app.repository.ReadHistoryRepository
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.DatabaseSyncDomainMaterializer
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncBulkDeleteProofFields
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.OperationReducer
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.SqlDelightSyncDomainStateAdapter
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncInstallationState
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncAccountBinding
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncDomainId
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncEntityId
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperationKind
import me.thenano.yamibo.yamibo_app.repository.bookmark.BookMarkRepositoryImpl
import me.thenano.yamibo.yamibo_app.repository.detailnote.DetailNoteRepositoryImpl
import me.thenano.yamibo.yamibo_app.repository.favorite.FavoriteStoreRepositoryImpl
import me.thenano.yamibo.yamibo_app.repository.settings.MangaReaderSettingsRepository
import me.thenano.yamibo.yamibo_app.repository.settings.NovelReaderSettingsRepository
import me.thenano.yamibo.yamibo_app.repository.settings.TouchZoneLayout
import io.github.littlesurvival.dto.value.TagId
import io.github.littlesurvival.dto.value.PostId
import io.github.littlesurvival.dto.value.ThreadId
import me.thenano.yamibo.yamibo_app.store.appsync.SqlDelightAppSyncOperationStore
import me.thenano.yamibo.yamibo_app.store.appsync.LocalSyncOperationDraft
import me.thenano.yamibo.yamibo_app.store.settings.SettingsStore

class AppSyncLocalMutationRoutingTest {
    @Test
    fun repeatedBatchPatchesUsePriorAcceptedStateAndKeepSequencesContiguous() {
        val fixture = activeFixture()
        val first = LocalSyncOperationDraft(SyncDomainId("reading.thread"), SyncEntityId("42"),
            kind = SyncOperationKind.Put, fields = mapOf("page" to "1", "threadName" to "unchanged"))
        fun patch(page: String) = first.copy(kind = SyncOperationKind.Patch, fields = first.fields + ("page" to page))
        val recorded = fixture.recorder.recordBatch(listOf(first, patch("1"), patch("2"), patch("02"), patch("1"))) {
            // Preparation is private staging, not an early write to the projection table.
            assertTrue(fixture.db.appSyncOperationQueries.getResolvedEntities().executeAsList().isEmpty())
        }
        assertEquals(listOf(SyncOperationKind.Put, SyncOperationKind.Patch, SyncOperationKind.Patch), recorded.map { it.kind })
        assertEquals(listOf(1L, 2L, 3L), recorded.map { it.sequence.value })
        assertEquals(listOf(mapOf("page" to "2"), mapOf("page" to "1")), recorded.drop(1).map { it.fields })
        val reduced = OperationReducer().reduce(operations = recorded)
        assertTrue(reduced.quarantined.isEmpty())
        assertEquals("1", reduced.entities.values.single().fields.getValue("page").value)
        assertEquals("unchanged", reduced.entities.values.single().fields.getValue("threadName").value)
        assertEquals(4L, fixture.store.installation()?.nextSequence)
    }

    @Test
    fun repeatedCommandCompactionDoesNotLeakStateIntoTheNextCommand() {
        val fixture = activeFixture()
        val first = LocalSyncOperationDraft(SyncDomainId("reading.thread"), SyncEntityId("42"),
            kind = SyncOperationKind.Patch, fields = mapOf("page" to "1"))
        val second = first.copy(fields = mapOf("page" to "2"))
        assertEquals(2, fixture.recorder.recordCommand { listOf(first, first, second, second) }.size)
        assertTrue(fixture.recorder.recordCommand { listOf(second, second) }.isEmpty())
        assertEquals(1, fixture.recorder.recordCommand { listOf(first, first) }.size)
        assertEquals(listOf(1L, 2L, 3L), fixture.store.pendingOperations().map { it.sequence.value })
    }

    @Test
    fun batchStagingUsesMonotonicReducerWinnersRatherThanLastInputValues() {
        val fixture = activeFixture()
        val initial = LocalSyncOperationDraft(SyncDomainId("reading.time"), SyncEntityId("2026-09-17"),
            kind = SyncOperationKind.Put, fields = mapOf("dateKey" to "2026-09-17", "durationMillis" to "100"))
        val lower = initial.copy(kind = SyncOperationKind.Patch, fields = mapOf("durationMillis" to "50"))
        val sameAsWinner = lower.copy(fields = mapOf("durationMillis" to "100"))
        val recorded = fixture.recorder.recordBatch(listOf(initial, lower, lower, sameAsWinner)) {}
        // With existing shared batch causality the 50s lose to 100. A simple map overlay
        // would incorrectly suppress the second 50 and emit the redundant final 100.
        assertEquals(listOf("100", "50", "50"), recorded.map { it.fields.getValue("durationMillis") })
        val reduced = OperationReducer().reduce(operations = recorded)
        assertTrue(reduced.quarantined.isEmpty())
        assertEquals("100", reduced.entities.values.single().fields.getValue("durationMillis").value)
    }

    @Test
    fun batchDeleteAndRecreationNeverReuseTheOldGenerationAsTheComparisonBase() {
        val fixture = activeFixture()
        val initial = LocalSyncOperationDraft(SyncDomainId("reading.thread"), SyncEntityId("42"),
            kind = SyncOperationKind.Put, fields = mapOf("page" to "1"))
        val deletion = initial.copy(kind = SyncOperationKind.Delete)
        val recreation = initial.copy(entityGeneration = 2)
        val unchanged = recreation.copy(kind = SyncOperationKind.Patch)
        val changed = unchanged.copy(fields = mapOf("page" to "2"))
        val operations = fixture.recorder.recordBatch(listOf(initial, deletion, recreation, unchanged, changed, changed)) {}
        assertEquals(listOf(1L, 1L, 2L, 2L), operations.map { it.entityGeneration })
        assertEquals(listOf(SyncOperationKind.Put, SyncOperationKind.Delete, SyncOperationKind.Put, SyncOperationKind.Patch),
            operations.map { it.kind })
        val reduced = OperationReducer().reduce(operations = operations)
        assertTrue(reduced.quarantined.isEmpty())
        val result = reduced.entities.values.single()
        assertEquals(2L, result.key.generation)
        assertNull(result.tombstone)
        assertEquals("2", result.fields.getValue("page").value)
    }

    @Test
    fun invalidHistoryPatchCannotBecomeTheBaseForDroppingALaterValidPatch() {
        val fixture = activeFixture()
        val source = AppSyncSyntheticCorpus.create().journal.operations.first { it.domainId.value == "reading.tag-catalog" }
        val initial = LocalSyncOperationDraft(source.domainId, source.entityId, kind = source.kind, fields = source.fields)
        val invalid = initial.copy(kind = SyncOperationKind.Patch, fields = mapOf("threadPage" to "99"))
        val valid = invalid.copy(fields = source.fields + ("threadPage" to "99"))
        val recorded = fixture.recorder.recordBatch(listOf(initial, invalid, valid, valid)) {}
        assertEquals(3, recorded.size)
        assertEquals(setOf("tagId", "lastVisitTime", "threadPage"), recorded.last().fields.keys)
        val reduced = OperationReducer().reduce(operations = recorded)
        assertEquals(1, reduced.quarantined.size)
        assertEquals("99", reduced.entities.values.single().fields.getValue("threadPage").value)
    }

    @Test
    fun compactBatchRollbackLeavesNoSequencesProjectionsOrLocalWritesAndCanRetry() {
        val fixture = activeFixture()
        val draft = LocalSyncOperationDraft(SyncDomainId("reading.thread"), SyncEntityId("42"),
            kind = SyncOperationKind.Patch, fields = mapOf("page" to "1"))
        assertFailsWith<IllegalStateException> {
            fixture.recorder.recordBatch(listOf(draft, draft)) {
                assertEquals(1, it.size)
                fixture.db.appSyncOperationQueries.recordKnownSyncSettingKey("batch-rollback")
                error("synthetic abort")
            }
        }
        assertTrue(fixture.store.pendingOperations().isEmpty())
        assertTrue(fixture.db.appSyncOperationQueries.getResolvedEntities().executeAsList().isEmpty())
        assertFalse(fixture.db.appSyncOperationQueries.getKnownSyncSettingKeys().executeAsList().contains("batch-rollback"))
        assertEquals(1L, fixture.store.installation()?.nextSequence)
        assertEquals(1L, fixture.recorder.recordBatch(listOf(draft, draft)) {}.single().sequence.value)
    }

    @Test
    fun malformedUnknownFieldNamesAreFilteredBeforeOperationConstruction() {
        val fixture = activeFixture()
        var called = false
        val result = fixture.recorder.record("reading.thread", "42", SyncOperationKind.Patch,
            mapOf("bad unknown key" to "private", "page" to "1")) { called = true }
        assertTrue(called)
        assertEquals(mapOf("page" to "1"), result?.fields)
    }

    @Test
    fun readingUpdatesSendOnlyChangedFieldsAndStillRestoreOnAnotherDevice() = runBlocking {
        val source = activeFixture()
        val delegate = AndroidReadHistoryRepository(source.db)
        val repository = OperationRecordingReadHistoryRepository(delegate, source.recorder)
        repository.savePosition(sampleThread())
        val updated = sampleThread().copy(page = 2, lastVisitTime = 2)
        repository.savePosition(updated)
        repository.savePosition(updated)
        val operations = source.store.pendingOperations()
        assertEquals(2, operations.size)
        assertEquals(mapOf("page" to "2", "lastVisitTime" to "2"), operations.last().fields)
        assertEquals(3L, source.store.installation()?.nextSequence)
        assertEquals(updated, delegate.getPosition(updated.threadId, updated.threadType, updated.authorId))
        val target = activeFixture()
        val materializer = SqlDelightSyncDomainStateAdapter(target.db,
            DatabaseSyncDomainMaterializer(target.db, MapSettingsStore()), nowMillis = { 100 })
        applyAllOperations(source, materializer)
        assertEquals(updated, AndroidReadHistoryRepository(target.db).getPosition(
            updated.threadId, updated.threadType, updated.authorId))
    }

    @Test
    fun typedNoOpPreservesCallbacksAndSequencesAcrossAllRecordingPaths() {
        val fixture = activeFixture()
        val draft = LocalSyncOperationDraft(SyncDomainId("reading.thread"), SyncEntityId("42"),
            kind = SyncOperationKind.Patch, fields = mapOf("page" to "2", "anchorPostRatio" to "0.5"))
        fixture.recorder.recordBatch(listOf(draft)) {}
        val equivalent = draft.copy(fields = mapOf("page" to "+0002", "anchorPostRatio" to "5e-1"))
        var callbacks = 0
        assertNull(fixture.recorder.record("reading.thread", "42", equivalent.kind, equivalent.fields) {
            assertNull(it); callbacks++
        })
        assertTrue(fixture.recorder.recordBatch(listOf(equivalent)) { assertTrue(it.isEmpty()); callbacks++ }.isEmpty())
        assertTrue(fixture.recorder.recordCommand { callbacks++; listOf(equivalent) }.isEmpty())
        assertEquals(3, callbacks)
        assertEquals(1, fixture.store.pendingOperations().size)
        assertEquals(2L, fixture.store.installation()?.nextSequence)
    }

    @Test
    fun explicitNullDiffersFromAbsentAndRepeatedBatchTargetsAreNotComparedToStaleState() {
        val fixture = activeFixture()
        val draft = LocalSyncOperationDraft(SyncDomainId("reading.thread"), SyncEntityId("42"),
            kind = SyncOperationKind.Patch, fields = mapOf("page" to "1"))
        fixture.recorder.recordBatch(listOf(draft)) {}
        val clear = draft.copy(fields = mapOf("anchorPostRatio" to null))
        assertEquals(mapOf("anchorPostRatio" to null), fixture.recorder.recordBatch(listOf(clear)) {}.single().fields)
        assertTrue(fixture.recorder.recordBatch(listOf(clear)) {}.isEmpty())
        val changes = listOf(draft.copy(fields = mapOf("page" to "2")), draft)
        assertEquals(2, fixture.recorder.recordBatch(changes) {}.size)
        val reduction = OperationReducer().reduce(operations = fixture.store.pendingOperations())
        assertTrue(reduction.quarantined.isEmpty())
        assertEquals("1", reduction.entities.values.single().fields.getValue("page").value)
        assertEquals(listOf(1L, 2L, 3L, 4L), fixture.store.pendingOperations().map { it.sequence.value })
    }

    @Test
    fun compactHistoryPatchesRetainLegacyReaderRequiredIdentityAndTimestamp() {
        val fixture = activeFixture()
        val domains = setOf("reading.tag-catalog", "reading.rss-search", "reading.rss-catalog")
        AppSyncSyntheticCorpus.create().journal.operations.filter { it.domainId.value in domains }.forEach { source ->
            fixture.recorder.record(source.domainId.value, source.entityId.value, source.kind, source.fields) {}
            val update = source.fields + ("firstVisibleItemOffset" to "99")
            val patch = requireNotNull(fixture.recorder.record(source.domainId.value, source.entityId.value,
                SyncOperationKind.Patch, update) {})
            val identity = if (source.domainId.value == "reading.tag-catalog") "tagId" else "subscriptionSyncId"
            assertEquals(setOf(identity, "lastVisitTime", "firstVisibleItemOffset"), patch.fields.keys)
        }
        assertTrue(OperationReducer().reduce(operations = fixture.store.pendingOperations()).quarantined.isEmpty())
    }

    @Test
    fun settingsNoOpKeepsWinnerAndTimestampWhileChangedValuesStillPublish() {
        val fixture = activeFixture()
        val delegate = MapSettingsStore()
        val settings = OperationRecordingSettingsStore(fixture.db, delegate, fixture.recorder)
        val key = "appsettings.ismangamode"
        settings.putBoolean(key, true)
        val first = fixture.db.appSyncOperationQueries.getSyncSettingValue(key).executeAsOne()
        settings.putBoolean(key, true)
        assertEquals(first, fixture.db.appSyncOperationQueries.getSyncSettingValue(key).executeAsOne())
        assertEquals(1, fixture.store.pendingOperations().size)
        settings.putBoolean(key, false)
        assertEquals(mapOf("value" to "false"), fixture.store.pendingOperations().last().fields)
        assertEquals(false, delegate.getBoolean(key, true))
        assertEquals(2, fixture.store.pendingOperations().size)
    }

    @Test
    fun equivalentFloatSettingKeepsProvenanceAcrossNegativeZeroFormatting() {
        val fixture = activeFixture()
        val settings = OperationRecordingSettingsStore(fixture.db, MapSettingsStore(), fixture.recorder)
        val key = "novelreadersettings.linespacing"
        settings.putFloat(key, -0.0f)
        val before = fixture.db.appSyncOperationQueries.getSyncSettingValue(key).executeAsOne()
        settings.putFloat(key, 0.0f)
        val after = fixture.db.appSyncOperationQueries.getSyncSettingValue(key).executeAsOne()
        assertEquals(before.winnerOperationId, after.winnerOperationId)
        assertEquals(before.updatedAtEpochMillis, after.updatedAtEpochMillis)
        assertEquals(1, fixture.store.pendingOperations().size)
        assertEquals(0.0f, settings.getFloat(key, 1.0f))
    }

    @Test
    fun deletesDropStaleBodiesButKeepAuthorizedProofAndLegacyRelationIdentity() {
        val fixture = activeFixture()
        val stale = mapOf("page" to "1", "threadName" to "stale title")
        assertTrue(requireNotNull(fixture.recorder.record("reading.thread", "42", SyncOperationKind.Delete,
            stale) {}).fields.isEmpty())
        val draft = LocalSyncOperationDraft(SyncDomainId("reading.thread"), SyncEntityId("43"),
            kind = SyncOperationKind.Delete, fields = stale)
        val authorized = fixture.recorder.recordAuthorizedDeleteBatch(listOf(draft), "reading-history:selected") {}.single()
        assertEquals(AppSyncLegacyFieldRegistry.proofFields, authorized.fields.keys)
        val relationFields = mapOf("targetType" to "ThreadNormal", "targetId" to "42", "authorId" to "0",
            "categorySyncId" to "category", "createdAt" to "1")
        val relation = requireNotNull(fixture.recorder.record("favorite.item-category", "relation",
            SyncOperationKind.RelationRemove, relationFields) {})
        assertEquals(setOf("targetType", "targetId", "authorId", "categorySyncId"), relation.fields.keys)
    }

    @Test
    fun noOpLocalDatabaseMutationRollsBackWhenCallbackFails() {
        val fixture = activeFixture()
        fixture.recorder.record("reading.thread", "42", SyncOperationKind.Patch, mapOf("page" to "1")) {}
        assertFailsWith<IllegalStateException> {
            fixture.recorder.record("reading.thread", "42", SyncOperationKind.Patch, mapOf("page" to "1")) {
                assertNull(it)
                fixture.db.appSyncOperationQueries.recordKnownSyncSettingKey("synthetic-rollback")
                error("synthetic failure")
            }
        }
        assertEquals(1, fixture.store.pendingOperations().size)
        assertEquals(2L, fixture.store.installation()?.nextSequence)
        assertFalse(fixture.db.appSyncOperationQueries.getKnownSyncSettingKeys().executeAsList().contains("synthetic-rollback"))
    }

    @Test
    fun unknownEntityRemainsLocalForSingleBatchAndCommandRecording() {
        val fixture = activeFixture()
        var mutations = 0
        val draft = LocalSyncOperationDraft(
            domainId = SyncDomainId("future.domain"),
            entityId = SyncEntityId("private-id"),
            kind = SyncOperationKind.Put, fields = mapOf("value" to "private payload"),
        )
        assertNull(fixture.recorder.record("future.domain", "private-id", SyncOperationKind.Put, draft.fields) {
            assertNull(it)
            mutations++
        })
        assertTrue(fixture.recorder.recordBatch(listOf(draft)) { assertTrue(it.isEmpty()); mutations++ }.isEmpty())
        assertTrue(fixture.recorder.recordCommand { mutations++; listOf(draft) }.isEmpty())
        assertEquals(3, mutations)
        assertTrue(fixture.store.pendingOperations().isEmpty())
        assertTrue(fixture.db.appSyncOperationQueries.getResolvedEntities().executeAsList().isEmpty())
    }

    @Test
    fun unknownFieldsNeverEnterRecordedOperationsOrProjections() {
        val fixture = activeFixture()
        var localValue: String? = null
        fixture.recorder.record("reading.thread", "42", SyncOperationKind.Patch,
            mapOf("page" to "2", "futureCache" to "private sentinel")) {
            localValue = "private sentinel"
        }
        assertEquals("private sentinel", localValue)
        assertEquals(mapOf("page" to "2"), fixture.store.pendingOperations().single().fields)
        assertFalse(fixture.db.appSyncOperationQueries.getResolvedEntities().executeAsOne().encodedState.contains("sentinel"))
    }

    @Test
    fun mixedBatchAndCommandKeepKnownFieldsAndContiguousSequences() {
        val fixture = activeFixture()
        val excluded = LocalSyncOperationDraft(SyncDomainId("future.domain"), SyncEntityId("private"),
            kind = SyncOperationKind.Put, fields = mapOf("value" to "private sentinel"))
        val known = excluded.copy(domainId = SyncDomainId("reading.thread"), entityId = SyncEntityId("42"),
            kind = SyncOperationKind.Patch, fields = mapOf("page" to "2", "futureCache" to "private sentinel"))
        val localOnlySetting = excluded.copy(domainId = SyncDomainId("settings"),
            entityId = SyncEntityId("appsettings.futureCache"))
        var mutations = 0
        val drafts = listOf(excluded, known, localOnlySetting)
        fixture.recorder.recordBatch(drafts) { mutations++; assertEquals(1, it.size) }
        fixture.recorder.recordCommand { mutations++; drafts }
        assertEquals(2, mutations)
        assertEquals(listOf(1L), fixture.store.pendingOperations().map { it.sequence.value })
        assertTrue(fixture.store.pendingOperations().all { it.fields == mapOf("page" to "2") })
        assertFalse(fixture.db.appSyncOperationQueries.getResolvedEntities().executeAsOne().encodedState.contains("sentinel"))
    }

    @Test
    fun authorizedBatchCountsOnlyPortableEntities() {
        val fixture = activeFixture()
        val known = LocalSyncOperationDraft(SyncDomainId("settings"), SyncEntityId("appsettings.language"),
            kind = SyncOperationKind.Delete, fields = emptyMap())
        val excluded = known.copy(entityId = SyncEntityId("appsettings.futureCache"))
        var mutations = 0
        val recorded = fixture.recorder.recordAuthorizedDeleteBatch(listOf(known, excluded), "settings:test") {
            mutations++
        }.single()
        assertEquals(1, mutations)
        assertEquals("1", recorded.fields[AppSyncBulkDeleteProofFields.COUNT])
        val authorization = fixture.db.appSyncOperationQueries
            .getBulkDeleteAuthorization(requireNotNull(recorded.bulkDeleteAuthorizationId)).executeAsOne()
        assertEquals(1L, authorization.operationCount)
        assertEquals(known.entityId, recorded.entityId)
    }

    @Test
    fun excludedOnlyPatchRemainsLocalWithoutAllocatingOperationOrProjection() {
        val fixture = activeFixture()
        val draft = LocalSyncOperationDraft(SyncDomainId("reading.thread"), SyncEntityId("42"),
            kind = SyncOperationKind.Patch, fields = mapOf("futureCache" to "private"))
        var mutations = 0
        fixture.recorder.record("reading.thread", "42", draft.kind, draft.fields) { assertNull(it); mutations++ }
        fixture.recorder.recordBatch(listOf(draft)) { assertTrue(it.isEmpty()); mutations++ }
        fixture.recorder.recordCommand { mutations++; listOf(draft) }
        assertEquals(3, mutations)
        assertEquals(1L, fixture.store.installation()?.nextSequence)
        assertTrue(fixture.store.pendingOperations().isEmpty())
        assertTrue(fixture.db.appSyncOperationQueries.getResolvedEntities().executeAsList().isEmpty())
    }

    @Test
    fun localMutationNeverLoadsTheFullResolvedState() = runBlocking {
        val db = inMemoryDatabase()
        val store = SqlDelightAppSyncOperationStore(db).also {
            it.initialize("generation")
            it.bindAccount(SyncAccountBinding("account"), AppSyncInstallationState.Active)
        }
        var fullStateReads = 0
        val domainState = SqlDelightSyncDomainStateAdapter(
            db = db,
            materializer = DatabaseSyncDomainMaterializer(db, MapSettingsStore()),
            nowMillis = { 100 },
            onFullStateRead = { fullStateReads++ },
        )
        val recorder = AppSyncMutationRecorder(true, store, domainState, nowMillis = { 100 })
        val repository = OperationRecordingReadHistoryRepository(
            AndroidReadHistoryRepository(db),
            recorder,
        )

        repository.savePosition(sampleThread())
        repository.savePosition(sampleThread().copy(page = 2, lastVisitTime = 2))

        assertEquals(0, fullStateReads)
        assertEquals(2, store.pendingOperations().size)
    }

    @Test
    fun entityScopedGenerationAdvancesAfterTombstoneWithoutReadingUnrelatedState() = runBlocking {
        val db = inMemoryDatabase()
        val store = SqlDelightAppSyncOperationStore(db).also {
            it.initialize("generation")
            it.bindAccount(SyncAccountBinding("account"), AppSyncInstallationState.Active)
        }
        var fullStateReads = 0
        val domainState = SqlDelightSyncDomainStateAdapter(
            db = db,
            materializer = DatabaseSyncDomainMaterializer(db, MapSettingsStore()),
            nowMillis = { 100 },
            onFullStateRead = { fullStateReads++ },
        )
        val recorder = AppSyncMutationRecorder(true, store, domainState, nowMillis = { 100 })
        val delegate = AndroidReadHistoryRepository(db)
        val repository = OperationRecordingReadHistoryRepository(delegate, recorder)
        val history = sampleThread()

        repository.savePosition(history)
        repository.deleteHistory(history.threadId, history.threadType, history.authorId)
        repository.savePosition(history.copy(page = 3, lastVisitTime = 3))

        assertEquals(0, fullStateReads)
        assertEquals(
            listOf(1L, 1L, 2L),
            store.pendingOperations().map { it.entityGeneration },
        )
        assertEquals(
            listOf(SyncOperationKind.Put, SyncOperationKind.Delete, SyncOperationKind.Put),
            store.pendingOperations().map { it.kind },
        )
    }

    @Test
    fun activeDetailNoteMutationCommitsDataAndOperationTogether() = runBlocking {
        val fixture = activeFixture()
        val repository = DetailNoteRepositoryImpl(fixture.db, fixture.recorder)

        repository.saveNote(DetailNoteRepository.TargetType.NovelThread, 12, 34, "note")

        assertEquals("note", repository.getNote(DetailNoteRepository.TargetType.NovelThread, 12, 34)?.content)
        val operation = fixture.store.pendingOperations().single()
        assertEquals("detail-note", operation.domainId.value)
        assertEquals("NovelThread|12|34", operation.entityId.value)
        assertEquals(SyncOperationKind.Put, operation.kind)
    }

    @Test
    fun bookmarkBecomingEmptyCreatesDeleteTombstone() = runBlocking {
        val fixture = activeFixture()
        val repository = BookMarkRepositoryImpl(fixture.db, fixture.recorder)

        repository.setBookmarked(BookMarkRepository.TargetType.ThreadPost, 12, 34, "post", true)
        repository.setBookmarked(BookMarkRepository.TargetType.ThreadPost, 12, 34, "post", false)

        assertEquals(null, repository.getEntry(BookMarkRepository.TargetType.ThreadPost, 12, 34))
        assertEquals(
            listOf(SyncOperationKind.Put, SyncOperationKind.Delete),
            fixture.store.pendingOperations().map { it.kind },
        )
    }

    @Test
    fun unboundCanonicalSettingRemainsWritableWithoutPublishing() {
        val db = inMemoryDatabase()
        val store = SqlDelightAppSyncOperationStore(db).also { it.initialize("generation") }
        val settings = MapSettingsStore()
        val recorder = recorder(db, store)
        val recordingStore = OperationRecordingSettingsStore(db, settings, recorder)

        recordingStore.putString("appsettings.thememode", "dark")

        assertEquals("dark", recordingStore.getString("appsettings.thememode", "light"))
        assertEquals("dark", settings.getString("appsettings.thememode", "light"))
        assertTrue(store.pendingOperations().isEmpty())
        assertEquals(
            "local-pending-bootstrap-migration",
            db.appSyncOperationQueries.getSyncSettingValue("appsettings.thememode").executeAsOne().winnerOperationId,
        )
    }

    @Test
    fun deviceLocalCategorySelectionIgnoresCloudCanonicalValueAndDoesNotPublish() {
        val fixture = activeFixture()
        val settings = MapSettingsStore().also {
            it.putInt("appsettings.favoritelastcategoryid", 7)
        }
        fixture.db.appSyncOperationQueries.upsertSyncSettingValue(
            settingKey = "appsettings.favoritelastcategoryid",
            type = "int",
            value_ = "99",
            winnerOperationId = "remote-device",
            updatedAtEpochMillis = 1,
        )
        val recordingStore = OperationRecordingSettingsStore(fixture.db, settings, fixture.recorder)

        assertEquals(7, recordingStore.getInt("appsettings.favoritelastcategoryid", 0))
        recordingStore.putInt("appsettings.favoritelastcategoryid", 8)

        assertEquals(8, recordingStore.getInt("appsettings.favoritelastcategoryid", 0))
        assertTrue(fixture.store.pendingOperations().isEmpty())
        assertEquals(
            "99",
            fixture.db.appSyncOperationQueries
                .getSyncSettingValue("appsettings.favoritelastcategoryid")
                .executeAsOne()
                .settingValue,
        )
    }

    @Test
    fun signPageCacheAndItsFreshnessTimestampRemainLocalOnly() {
        val fixture = activeFixture()
        val settings = MapSettingsStore()
        val recordingStore = OperationRecordingSettingsStore(fixture.db, settings, fixture.recorder)

        recordingStore.putString("appsettings.signpagehtmlcache", "<html>large cache</html>")
        recordingStore.putString("appsettings.signpagehtmlcacheupdatedat", "123456789")

        assertEquals("<html>large cache</html>", settings.getString("appsettings.signpagehtmlcache", ""))
        assertEquals("123456789", settings.getString("appsettings.signpagehtmlcacheupdatedat", ""))
        assertTrue(fixture.store.pendingOperations().isEmpty())
        assertEquals(
            null,
            fixture.db.appSyncOperationQueries
                .getSyncSettingValue("appsettings.signpagehtmlcache")
                .executeAsOneOrNull(),
        )
        assertEquals(
            null,
            fixture.db.appSyncOperationQueries
                .getSyncSettingValue("appsettings.signpagehtmlcacheupdatedat")
                .executeAsOneOrNull(),
        )
    }

    @Test
    fun inboundLegacyCacheCannotOverwriteLocalValueOrRemainInResolvedCheckpointState() {
        val db = inMemoryDatabase()
        val store = SqlDelightAppSyncOperationStore(db).also {
            it.initialize("generation")
            it.bindAccount(SyncAccountBinding("account"), AppSyncInstallationState.Active)
        }
        val settings = MapSettingsStore().also {
            it.putString("appsettings.signpagehtmlcache", "local-device-cache")
        }
        val evidence = mutableListOf<me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncPortabilityEvidence>()
        val domainState = SqlDelightSyncDomainStateAdapter(
            db = db,
            materializer = DatabaseSyncDomainMaterializer(db, settings, evidence::add),
            nowMillis = { 100 },
        )
        val legacy = store.appendLocalOperation(
            accountBinding = SyncAccountBinding("account"),
            domainId = me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncDomainId("settings"),
            entityId = me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncEntityId(
                "appsettings.signpagehtmlcache",
            ),
            entityGeneration = 1,
            kind = SyncOperationKind.Patch,
            fields = mapOf("type" to "string", "value" to "legacy-cloud-cache"),
            causalContext = me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncCausalContext(),
            createdAtEpochMillis = 1,
            origin = me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperationOrigin.UserAction,
        )

        domainState.apply(OperationReducer().reduce(operations = listOf(legacy)))

        assertEquals(
            "local-device-cache",
            settings.getString("appsettings.signpagehtmlcache", ""),
        )
        assertTrue(domainState.currentState().isEmpty())
        assertEquals("legacy-local-only", evidence.single().reason)
        assertFalse(evidence.single().redactedEntityId.contains("signpagehtmlcache"))
    }

    @Test
    fun threadCoverOperationsExcludeEveryCoverWithoutChangingLocalHistory() = runBlocking {
        val cases = listOf(
            "http://example.com/cover.jpg" to null,
            "https://example.com/cover.jpg" to null,
            "data:image/png;base64,AAAA" to null,
            "http://data:image/png;base64,AAAA" to null,
            "file:///tmp/cover.jpg" to null,
            "not a link" to null,
        )

        cases.forEachIndexed { index, (input, expected) ->
            val fixture = activeFixture()
            val repository = OperationRecordingReadHistoryRepository(
                AndroidReadHistoryRepository(fixture.db),
                fixture.recorder,
            )

            repository.savePosition(
                sampleThread().copy(threadId = ThreadId(index + 1), threadCover = input),
            )

            assertEquals(expected, fixture.store.pendingOperations().single().fields["threadCover"], input)
            assertEquals(input, fixture.db.readingHistoryQueries.getAllForBackup().executeAsList().single().threadCover)
        }
    }

    @Test
    fun mangaAndThreadTouchSettingsSyncAsIndependentEntities() {
        val source = activeFixture()
        val sourceSettings = MapSettingsStore()
        val recordingStore = OperationRecordingSettingsStore(source.db, sourceSettings, source.recorder)
        val sourceManga = MangaReaderSettingsRepository(recordingStore)
        val sourceThread = NovelReaderSettingsRepository(recordingStore)

        sourceManga.touchZone.setValue(TouchZoneLayout.EDGE)
        sourceManga.reverseTouchZones.setValue(true)
        sourceThread.threadTouchZone.setValue(TouchZoneLayout.KINDLE)
        sourceThread.threadReverseTouchZones.setValue(false)

        val expectedKeys = setOf(
            sourceManga.touchZone.storageKey,
            sourceManga.reverseTouchZones.storageKey,
            sourceThread.threadTouchZone.storageKey,
            sourceThread.threadReverseTouchZones.storageKey,
        )
        val operations = source.store.pendingOperations()
        assertEquals(expectedKeys, operations.mapTo(linkedSetOf()) { it.entityId.value })
        assertTrue(operations.all { it.domainId.value == "settings" })

        val targetDb = inMemoryDatabase()
        val targetSettings = MapSettingsStore()
        val targetDomain = SqlDelightSyncDomainStateAdapter(
            db = targetDb,
            materializer = DatabaseSyncDomainMaterializer(targetDb, targetSettings),
            nowMillis = { 200 },
        )
        targetDomain.apply(OperationReducer().reduce(operations = operations))

        val targetManga = MangaReaderSettingsRepository(targetSettings)
        val targetThread = NovelReaderSettingsRepository(targetSettings)
        assertEquals(TouchZoneLayout.EDGE, targetManga.touchZone.getValue())
        assertTrue(targetManga.reverseTouchZones.getValue())
        assertEquals(TouchZoneLayout.KINDLE, targetThread.threadTouchZone.getValue())
        assertFalse(targetThread.threadReverseTouchZones.getValue())
    }

    @Test
    fun unknownSettingStaysLocalWithoutOutboxOrSyncProjection() {
        val fixture = activeFixture()
        val local = MapSettingsStore()
        val recording = OperationRecordingSettingsStore(fixture.db, local, fixture.recorder)
        val key = "appsettings.futurehtmlcache"
        recording.putString(key, "<html>local cache</html>")
        assertEquals("<html>local cache</html>", local.getString(key, ""))
        assertEquals("<html>local cache</html>", recording.getString(key, ""))
        assertTrue(fixture.store.pendingOperations().isEmpty())
        assertNull(fixture.db.appSyncOperationQueries.getSyncSettingValue(key).executeAsOneOrNull())
        assertTrue(fixture.db.appSyncOperationQueries.getResolvedEntities().executeAsList().isEmpty())
    }

    @Test
    fun remoteReadingProgressPreservesExistingLocalCover() = runBlocking {
        val source = activeFixture()
        val sourceHistory = OperationRecordingReadHistoryRepository(AndroidReadHistoryRepository(source.db), source.recorder)
        sourceHistory.savePosition(sampleThread().copy(page = 2, threadCover = "https://example.test/remote"))
        val target = activeFixture()
        val targetHistory = AndroidReadHistoryRepository(target.db)
        targetHistory.savePosition(sampleThread().copy(threadCover = "content://local-cover"))
        val adapter = SqlDelightSyncDomainStateAdapter(target.db,
            DatabaseSyncDomainMaterializer(target.db, MapSettingsStore()), nowMillis = { 200 })
        applyAllOperations(source, adapter)
        val row = target.db.readingHistoryQueries.getAllForBackup().executeAsList().single()
        assertEquals(2, row.page)
        assertEquals("content://local-cover", row.threadCover)
        assertFalse(target.db.appSyncOperationQueries.getResolvedEntities().executeAsList().single()
            .encodedState.contains("cover", ignoreCase = true))
    }

    @Test
    fun remoteSettingTombstoneDeletesCanonicalAndPlatformProjection() {
        val db = inMemoryDatabase()
        val store = SqlDelightAppSyncOperationStore(db).also {
            it.initialize("generation")
            it.bindAccount(SyncAccountBinding("account"), AppSyncInstallationState.Active)
        }
        val settings = MapSettingsStore()
        val domainState = SqlDelightSyncDomainStateAdapter(
            db = db,
            materializer = DatabaseSyncDomainMaterializer(db, settings),
            nowMillis = { 100 },
        )
        val recordingStore = OperationRecordingSettingsStore(
            db,
            settings,
            AppSyncMutationRecorder(true, store, domainState, nowMillis = { 100 }),
        )
        recordingStore.putString("appsettings.thememode", "dark")
        recordingStore.remove("appsettings.thememode")

        settings.putString("appsettings.thememode", "stale")
        db.appSyncOperationQueries.upsertSyncSettingValue(
            settingKey = "appsettings.thememode",
            type = "string",
            value_ = "stale",
            winnerOperationId = "stale",
            updatedAtEpochMillis = 1,
        )
        domainState.apply(OperationReducer().reduce(operations = store.pendingOperations()))

        assertFalse(settings.hasKey("appsettings.thememode"))
        assertEquals(
            null,
            db.appSyncOperationQueries.getSyncSettingValue("appsettings.thememode").executeAsOneOrNull(),
        )
    }

    @Test
    fun checkpointReplacementClearsStalePlatformSettingProjection() {
        val db = inMemoryDatabase()
        val settings = MapSettingsStore().also { it.putString("appsettings.thememode", "stale") }
        db.appSyncOperationQueries.upsertSyncSettingValue(
            settingKey = "appsettings.thememode",
            type = "string",
            value_ = "stale",
            winnerOperationId = "stale",
            updatedAtEpochMillis = 1,
        )
        val domainState = SqlDelightSyncDomainStateAdapter(
            db = db,
            materializer = DatabaseSyncDomainMaterializer(db, settings),
            nowMillis = { 100 },
        )

        domainState.adoptCheckpoint(emptyList())

        assertFalse(settings.hasKey("appsettings.thememode"))
        assertEquals(
            null,
            db.appSyncOperationQueries.getSyncSettingValue("appsettings.thememode").executeAsOneOrNull(),
        )
    }

    @Test
    fun favoriteCommandCreatesItemAndMembershipOperationsAtomically() = runBlocking {
        val fixture = activeFixture()
        val repository = FavoriteStoreRepositoryImpl(fixture.db, fixture.recorder)
        val category = repository.getDefaultCategory()
        fixture.store.markAcknowledged(
            fixture.store.pendingOperations().mapTo(linkedSetOf()) { it.operationId },
            atEpochMillis = 100,
        )

        repository.addTagMangaFavorite(
            tagId = TagId(77),
            tagName = "title",
            coverUrl = null,
            categoryIds = listOf(category.id),
            collectionIds = emptyList(),
        )
        repository.addTagMangaFavorite(
            tagId = TagId(77),
            tagName = "title",
            coverUrl = null,
            categoryIds = listOf(category.id),
            collectionIds = emptyList(),
        )

        val pending = fixture.store.pendingOperations()
        assertEquals(
            listOf("favorite.item", "favorite.item-category"),
            pending.map { it.domainId.value },
        )
        assertEquals(listOf(2L, 3L), pending.map { it.sequence.value })
        assertEquals(1, repository.getAllFavoriteItems().size)
        assertEquals(setOf(category.id), repository.getCategoryIdsForItem(repository.getAllFavoriteItems().single().id))
    }

    @Test
    fun confirmedBulkDeleteStoresPortableAuthorizationProof() {
        val fixture = activeFixture()
        var localMutationRan = false
        val operations = fixture.recorder.recordAuthorizedDeleteBatch(
            drafts = listOf("one", "two").map { entityId ->
                LocalSyncOperationDraft(
                    domainId = me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncDomainId(
                        "reading.thread",
                    ),
                    entityId = me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncEntityId(
                        entityId,
                    ),
                    kind = SyncOperationKind.Delete,
                    fields = emptyMap(),
                )
            },
            scopeKey = "reading-history:selected",
        ) {
            localMutationRan = true
        }

        assertTrue(localMutationRan)
        assertEquals(2, operations.size)
        val authorizationId = operations.map { it.bulkDeleteAuthorizationId }.distinct().single()
        assertEquals(
            setOf<String?>("2"),
            operations.mapTo(linkedSetOf()) {
                it.fields[AppSyncBulkDeleteProofFields.COUNT]
            },
        )
        assertEquals(
            "reading-history:selected",
            operations.first().fields[AppSyncBulkDeleteProofFields.SCOPE],
        )
        assertEquals(2L, fixture.store.loadBulkDeleteAuthorization(requireNotNull(authorizationId))?.operationCount)
    }

    @Test
    fun allReadingHistoryModesRecordAndClearWithExactTombstones() = runBlocking {
        val fixture = activeFixture()
        val delegate = AndroidReadHistoryRepository(fixture.db)
        val repository = OperationRecordingReadHistoryRepository(delegate, fixture.recorder)
        delegate.recordReadingDuration("2026-08-01", 123)
        fixture.db.rssSearchSubscriptionQueries.insertSubscription(
            title = "RSS",
            query = "query",
            forumId = null,
            forumName = null,
            enabled = 1,
            createdAt = 1,
            updatedAt = 1,
            lastRefreshStartedAt = null,
            lastRefreshFinishedAt = null,
            lastRefreshStatus = null,
            lastRefreshMessage = null,
            lastSearchId = null,
            lastTotalCount = 0,
        )
        val rssId = fixture.db.rssSearchSubscriptionQueries.lastInsertedId().executeAsOne()
        val thread = sampleThread()
        val image = ReadHistoryRepository.ImageReadingHistory(
            PostId(2), ThreadId(1), 1, 10, lastVisitTime = 2,
        )
        val manga = ReadHistoryRepository.TagMangaReadingHistory(
            TagId(3), "manga", 1, ThreadId(3), "thread", 1, 10, lastVisitTime = 3,
        )
        val catalog = ReadHistoryRepository.TagCatalogReadingHistory(
            TagId(3), "catalog", 1, ThreadId(4), "thread", 1,
            PostId(4), "post", lastVisitTime = 4,
        )
        val rssSearch = ReadHistoryRepository.RssSearchReadingHistory(
            rssId, "RSS", "query", 1, ThreadId(5), "thread", 1, 10, lastVisitTime = 5,
        )
        val rssCatalog = ReadHistoryRepository.RssCatalogReadingHistory(
            rssId, "RSS", "query", 1, ThreadId(6), "thread", 1,
            PostId(6), "post", lastVisitTime = 6,
        )

        repository.savePosition(thread)
        repository.saveImagePosition(image)
        repository.saveTagMangaReaderModeHistory(manga)
        repository.saveTagCatalogThreadHistory(catalog)
        repository.saveRssSearchReaderModeHistory(rssSearch)
        repository.saveRssCatalogThreadHistory(rssCatalog)

        assertEquals(
            setOf(
                "reading.thread", "reading.image", "reading.tag-manga",
                "reading.tag-catalog", "reading.rss-search", "reading.rss-catalog",
            ),
            fixture.store.pendingOperations().mapTo(linkedSetOf()) { it.domainId.value },
        )
        fixture.store.markAcknowledged(
            fixture.store.pendingOperations().mapTo(linkedSetOf()) { it.operationId },
            atEpochMillis = 100,
        )

        repository.deleteAllCombinedHistory()

        val deletes = fixture.store.pendingOperations()
        assertEquals(6, deletes.size)
        assertTrue(deletes.all { it.kind == SyncOperationKind.Delete })
        assertEquals(
            setOf(
                "reading.thread", "reading.image", "reading.tag-manga",
                "reading.tag-catalog", "reading.rss-search", "reading.rss-catalog",
            ),
            deletes.mapTo(linkedSetOf()) { it.domainId.value },
        )
        assertEquals(0, delegate.getHistoryCount())
        assertTrue(delegate.getAllImageHistoryForSync().isEmpty())
        assertTrue(delegate.getAllTagMangaHistoryForSync().isEmpty())
        assertTrue(delegate.getAllTagCatalogHistoryForSync().isEmpty())
        assertTrue(delegate.getAllRssSearchHistoryForSync().isEmpty())
        assertTrue(delegate.getAllRssCatalogHistoryForSync().isEmpty())
        assertEquals(123, delegate.getReadingDurationTotal("2026-08-01", "2026-08-01"))
    }

    @Test
    fun clearAllEnumerationFailureLeavesHistoryAndOutboxUntouched() = runBlocking {
        val fixture = activeFixture()
        val base = AndroidReadHistoryRepository(fixture.db)
        val catalog = ReadHistoryRepository.TagCatalogReadingHistory(
            TagId(9), "catalog", 1, ThreadId(9), "thread", 1,
            PostId(9), "post", lastVisitTime = 9,
        )
        base.saveTagCatalogThreadHistory(catalog)
        val failing = object : ReadHistoryRepository by base {
            override suspend fun getAllTagCatalogHistoryForSync():
                List<ReadHistoryRepository.TagCatalogReadingHistory> = error("injected enumeration failure")
        }
        val repository = OperationRecordingReadHistoryRepository(failing, fixture.recorder)

        assertFailsWith<IllegalStateException> {
            repository.deleteAllCombinedHistory()
        }

        assertEquals(catalog, base.getTagCatalogThreadHistoryPosition(TagId(9)))
        assertTrue(fixture.store.pendingOperations().isEmpty())
    }

    @Test
    fun selectedDeleteDoesNotCrossTagHistoryModesAndLaterReadRecreatesGeneration() = runBlocking {
        val fixture = activeFixture()
        val delegate = AndroidReadHistoryRepository(fixture.db)
        val repository = OperationRecordingReadHistoryRepository(delegate, fixture.recorder)
        val manga = ReadHistoryRepository.TagMangaReadingHistory(
            TagId(7), "manga", 1, ThreadId(7), "thread", 1, 10, lastVisitTime = 7,
        )
        val catalog = ReadHistoryRepository.TagCatalogReadingHistory(
            TagId(7), "catalog", 1, ThreadId(8), "thread", 1,
            PostId(8), "post", lastVisitTime = 8,
        )
        repository.saveTagMangaReaderModeHistory(manga)
        repository.saveTagCatalogThreadHistory(catalog)
        fixture.store.markAcknowledged(
            fixture.store.pendingOperations().mapTo(linkedSetOf()) { it.operationId },
            atEpochMillis = 100,
        )

        repository.deleteCombinedHistoryBatch(listOf(manga))

        assertEquals(null, delegate.getTagMangaReaderModeHistoryPosition(TagId(7)))
        assertEquals(catalog, delegate.getTagCatalogThreadHistoryPosition(TagId(7)))
        val delete = fixture.store.pendingOperations().single()
        assertEquals("reading.tag-manga", delete.domainId.value)
        assertEquals(1L, delete.entityGeneration)
        fixture.store.markAcknowledged(setOf(delete.operationId), 101)

        repository.saveTagMangaReaderModeHistory(manga.copy(lastVisitTime = 9))

        val recreated = fixture.store.pendingOperations().single()
        assertEquals(SyncOperationKind.Put, recreated.kind)
        assertEquals(2L, recreated.entityGeneration)
    }

    @Test
    fun twoDeviceHistoryProjectionConvergesAcrossDifferentLocalRssIds() = runBlocking {
        val deviceA = activeFixture()
        val deviceB = activeFixture()
        val delegateA = AndroidReadHistoryRepository(deviceA.db)
        val repositoryA = OperationRecordingReadHistoryRepository(delegateA, deviceA.recorder)
        insertRssSubscription(deviceB.db, "dummy")
        val rssIdA = insertRssSubscription(deviceA.db, "query")
        val rssIdB = insertRssSubscription(deviceB.db, "query")
        assertFalse(rssIdA == rssIdB)
        val delegateB = AndroidReadHistoryRepository(deviceB.db)
        val domainB = SqlDelightSyncDomainStateAdapter(
            db = deviceB.db,
            materializer = DatabaseSyncDomainMaterializer(deviceB.db, MapSettingsStore()),
            nowMillis = { 100 },
        )
        val thread = sampleThread()
        val image = ReadHistoryRepository.ImageReadingHistory(
            PostId(2), ThreadId(1), 1, 10, lastVisitTime = 2,
        )
        val manga = ReadHistoryRepository.TagMangaReadingHistory(
            TagId(3), "manga", 1, ThreadId(3), "manga thread", 1, 10, lastVisitTime = 3,
        )
        val catalog = ReadHistoryRepository.TagCatalogReadingHistory(
            TagId(4), "catalog", 1, ThreadId(4), "catalog thread", 1,
            PostId(4), "catalog post", lastVisitTime = 4,
        )
        val rssSearch = ReadHistoryRepository.RssSearchReadingHistory(
            rssIdA, "RSS", "query", 1, ThreadId(5), "rss search", 1, 10, lastVisitTime = 5,
        )
        val rssCatalog = ReadHistoryRepository.RssCatalogReadingHistory(
            rssIdA, "RSS", "query", 1, ThreadId(6), "rss catalog", 1,
            PostId(6), "rss post", lastVisitTime = 6,
        )

        repositoryA.savePosition(thread)
        repositoryA.saveImagePosition(image)
        repositoryA.saveTagMangaReaderModeHistory(manga)
        repositoryA.saveTagCatalogThreadHistory(catalog)
        repositoryA.saveRssSearchReaderModeHistory(rssSearch)
        repositoryA.saveRssCatalogThreadHistory(rssCatalog)
        applyAllOperations(deviceA, domainB)

        assertEquals(thread, delegateB.getPosition(thread.threadId, thread.threadType, thread.authorId))
        assertEquals(image, delegateB.getImagePosition(image.postId))
        assertEquals(manga, delegateB.getTagMangaReaderModeHistoryPosition(manga.tagId))
        assertEquals(catalog, delegateB.getTagCatalogThreadHistoryPosition(catalog.tagId))
        assertEquals("rss search", delegateB.getRssSearchReaderModeHistoryPosition(rssIdB)?.threadTitle)
        assertEquals("rss catalog", delegateB.getRssCatalogThreadHistoryPosition(rssIdB)?.threadTitle)

        repositoryA.deleteCombinedHistoryBatch(listOf(manga, rssSearch))
        applyAllOperations(deviceA, domainB)

        assertEquals(null, delegateB.getTagMangaReaderModeHistoryPosition(manga.tagId))
        assertEquals(null, delegateB.getRssSearchReaderModeHistoryPosition(rssIdB))
        assertEquals(catalog, delegateB.getTagCatalogThreadHistoryPosition(catalog.tagId))
        assertEquals("rss catalog", delegateB.getRssCatalogThreadHistoryPosition(rssIdB)?.threadTitle)

        repositoryA.deleteAllCombinedHistory()
        applyAllOperations(deviceA, domainB)

        assertEquals(0, delegateB.getHistoryCount())
        assertTrue(delegateB.getAllImageHistoryForSync().isEmpty())
        assertTrue(delegateB.getAllTagMangaHistoryForSync().isEmpty())
        assertTrue(delegateB.getAllTagCatalogHistoryForSync().isEmpty())
        assertTrue(delegateB.getAllRssSearchHistoryForSync().isEmpty())
        assertTrue(delegateB.getAllRssCatalogHistoryForSync().isEmpty())

        repositoryA.savePosition(thread.copy(lastVisitTime = 101))
        repositoryA.saveImagePosition(image.copy(lastVisitTime = 102))
        repositoryA.saveTagMangaReaderModeHistory(manga.copy(lastVisitTime = 103))
        repositoryA.saveTagCatalogThreadHistory(catalog.copy(lastVisitTime = 104))
        repositoryA.saveRssSearchReaderModeHistory(rssSearch.copy(lastVisitTime = 105))
        repositoryA.saveRssCatalogThreadHistory(rssCatalog.copy(lastVisitTime = 106))
        val recreated = deviceA.store.allOutboxOperations().map { it.first }.filter {
            it.entityGeneration == 2L && it.kind == SyncOperationKind.Put
        }
        assertEquals(
            setOf(
                "reading.thread", "reading.image", "reading.tag-manga",
                "reading.tag-catalog", "reading.rss-search", "reading.rss-catalog",
            ),
            recreated.mapTo(linkedSetOf()) { it.domainId.value },
        )
        applyAllOperations(deviceA, domainB)

        assertEquals(101, delegateB.getPosition(thread.threadId, thread.threadType, thread.authorId)?.lastVisitTime)
        assertEquals(102, delegateB.getImagePosition(image.postId)?.lastVisitTime)
        assertEquals(103, delegateB.getTagMangaReaderModeHistoryPosition(manga.tagId)?.lastVisitTime)
        assertEquals(104, delegateB.getTagCatalogThreadHistoryPosition(catalog.tagId)?.lastVisitTime)
        assertEquals(105, delegateB.getRssSearchReaderModeHistoryPosition(rssIdB)?.lastVisitTime)
        assertEquals(106, delegateB.getRssCatalogThreadHistoryPosition(rssIdB)?.lastVisitTime)
    }

    private fun applyAllOperations(source: Fixture, target: SqlDelightSyncDomainStateAdapter) {
        val operations = source.store.allOutboxOperations().map { it.first }
        val reduction = OperationReducer().reduce(operations = operations)
        assertTrue(reduction.quarantined.isEmpty())
        target.apply(reduction)
    }

    private fun insertRssSubscription(db: Database, query: String): Long {
        db.rssSearchSubscriptionQueries.insertSubscription(
            title = query,
            query = query,
            forumId = null,
            forumName = null,
            enabled = 1,
            createdAt = 1,
            updatedAt = 1,
            lastRefreshStartedAt = null,
            lastRefreshFinishedAt = null,
            lastRefreshStatus = null,
            lastRefreshMessage = null,
            lastSearchId = null,
            lastTotalCount = 0,
        )
        return db.rssSearchSubscriptionQueries.lastInsertedId().executeAsOne()
    }

    private fun sampleThread() = ReadHistoryRepository.ThreadReadingHistory(
        threadType = ReadHistoryRepository.ThreadEntryType.Normal,
        threadName = "thread",
        threadId = ThreadId(1),
        threadCover = null,
        lastUpdatedTime = null,
        forumName = null,
        forumId = null,
        authorId = null,
        page = 1,
        postId = PostId(1),
        postTitle = "post",
        anchorPostId = 1,
        lastVisitTime = 1,
    )

    private fun activeFixture(): Fixture {
        val db = inMemoryDatabase()
        val store = SqlDelightAppSyncOperationStore(db).also {
            it.initialize("generation")
            it.bindAccount(SyncAccountBinding("account"), AppSyncInstallationState.Active)
        }
        return Fixture(db, store, recorder(db, store))
    }

    private fun recorder(
        db: Database,
        store: SqlDelightAppSyncOperationStore,
    ) = AppSyncMutationRecorder(
        enabled = true,
        store = store,
        domainState = SqlDelightSyncDomainStateAdapter(
            db = db,
            materializer = DatabaseSyncDomainMaterializer(db, MapSettingsStore()),
            nowMillis = { 100 },
        ),
        nowMillis = { 100 },
    )

    private fun inMemoryDatabase(): Database {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        return Database(driver)
    }

    private data class Fixture(
        val db: Database,
        val store: SqlDelightAppSyncOperationStore,
        val recorder: AppSyncMutationRecorder,
    )

    private class MapSettingsStore : SettingsStore {
        private val values = mutableMapOf<String, Any>()

        override fun getInt(key: String, defaultValue: Int) = values[key] as? Int ?: defaultValue
        override fun putInt(key: String, value: Int) = set(key, value)
        override fun getFloat(key: String, defaultValue: Float) = values[key] as? Float ?: defaultValue
        override fun putFloat(key: String, value: Float) = set(key, value)
        override fun getString(key: String, defaultValue: String) = values[key] as? String ?: defaultValue
        override fun putString(key: String, value: String) = set(key, value)
        override fun getBoolean(key: String, defaultValue: Boolean) = values[key] as? Boolean ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) = set(key, value)
        override fun remove(key: String) {
            values.remove(key)
        }
        override fun hasKey(key: String) = key in values

        private fun set(key: String, value: Any) {
            values[key] = value
        }
    }
}
