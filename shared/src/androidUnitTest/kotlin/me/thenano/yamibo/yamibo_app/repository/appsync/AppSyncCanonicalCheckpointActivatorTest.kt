package me.thenano.yamibo.yamibo_app.repository.appsync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import okio.ByteString.Companion.encodeUtf8
import kotlinx.coroutines.async
import kotlin.test.*
import me.thenano.yamibo.yamibo_app.Database
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.*
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncOperationLifecycle
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncInstallationState
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncRecoveryPhase
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.*
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.*
import me.thenano.yamibo.yamibo_app.repository.appsync.schema.*
import me.thenano.yamibo.yamibo_app.store.appsync.*
import me.thenano.yamibo.yamibo_app.store.settings.SettingsStore

class AppSyncCanonicalCheckpointActivatorTest {
    private fun legacyMigrationCloud(): AppSyncJournalLoadResult.Success {
        val codec = AppSyncCheckpointEnvelopeCodec()
        val payload = codec.createPayload("legacy-base", account, SyncCausalContext(),
            me.thenano.yamibo.yamibo_app.repository.backup.YamiboBackupFile(appVersionCode = 1, createdAt = 1), emptyList(), emptyList(), 1)
        val body = codec.encode(payload)
        val parsed = assertIs<AppSyncCheckpointValidation.Valid>(codec.validate(body)).envelope
        val index = AppSyncIndexEnvelopeCodec().encode(AppSyncIndexPayload(account,
            checkpoints = listOf(AppSyncIndexCheckpointReference(payload.checkpointId, 123, parsed.fingerprint)), updatedAtEpochMillis = 1))
        val source = assertNotNull(AppSyncVerifiedLegacyCheckpoint.verify(account.value, 123, index, body))
        return AppSyncJournalLoadResult.Success(emptyList(), checkpoints = listOf(LoadedAppSyncCheckpoint("123", parsed)),
            authoritativeDiscovery = true, verifiedLegacyCheckpoints = listOf(source))
    }

    private fun Fixture.legacyPublishingEnvironment(cloud: AppSyncJournalLoadResult.Success):
        Pair<AppSyncV3SegmentPublisherTest.Provider, AppSyncNativeRecoveryContinuation> {
        val provider = AppSyncV3SegmentPublisherTest.Provider()
        val source = cloud.verifiedLegacyCheckpoints.single()
        val payload = source.read().payload
        val index = AppSyncIndexEnvelopeCodec().encode(AppSyncIndexPayload(account,
            checkpoints = listOf(AppSyncIndexCheckpointReference(payload.checkpointId, source.blogId.toInt(), source.fingerprint)),
            updatedAtEpochMillis = 1))
        val selection = AppSyncBlogClassSelection.Existing(io.github.littlesurvival.dto.value.BlogClassId(7))
        provider.artifacts[124] = AppSyncBlogWriteRequest(io.github.littlesurvival.dto.value.BlogId(124), APP_SYNC_INDEX_TITLE,
            index, selection, io.github.littlesurvival.dto.value.FormHash("test"))
        val recovery = SqlDelightAppSyncRecoveryStore(db)
        val blogs = SqlDelightAppSyncRemoteBlogStore(db).also { it.saveClassId(account, selection.classId) }
        return provider to AppSyncNativeRecoveryContinuation(provider, store, recovery, blogs, activator(), { now }, { true },
            legacyStarter = AppSyncLegacyMigrationStarter(db, store, recovery, { now }, { true }))
    }

    @Test fun engineMigratesLegacyCloudThroughPublicationActivationAndCleanupInOneRun() = fixture {
        val source = append()
        val cloud = legacyMigrationCloud()
        val (provider, continuation) = legacyPublishingEnvironment(cloud)
        val result = assertIs<OperationSyncResult.Converged>(synchronize(cloud, continuation))
        assertEquals(1, result.acknowledgedLocalCount)
        assertEquals(listOf(true), forcedLoads)
        assertEquals(18, preferences.values["novelreadersettings.fontsize"])
        val recovery = SqlDelightAppSyncRecoveryStore(db)
        val session = assertNotNull(recovery.recoverySession(account))
        assertEquals(AppSyncRecoveryPhase.Completed, session.phase)
        assertTrue(session.indexCommitted)
        assertTrue(store.pendingOperations().isEmpty())
        assertEquals(1L, state.read(account.value)?.coverage?.get(source.replicaKey.stableKey))
        assertEquals(3, provider.posts.size)
        assertNotNull(db.appSyncNativeCompletionQueries.getForSession(session.sessionId).executeAsOneOrNull())
        assertNull(db.appSyncOperationQueries.getRecoveryPayload(session.sessionId).executeAsOneOrNull())
        assertNull(db.appSyncOperationQueries.getRunLease().executeAsOneOrNull())
    }

    @Test fun engineResumesUnindexedFrozenMigrationAndPreservesEditsMadeDuringRetry() = fixture {
        val first = append()
        val cloud = legacyMigrationCloud()
        val (provider, continuation) = legacyPublishingEnvironment(cloud)
        provider.onList = { if (provider.posts.size >= 2) provider.listFailureAt = 1 }
        assertIs<OperationSyncResult.RetryScheduled>(synchronize(cloud, continuation))
        val recovery = SqlDelightAppSyncRecoveryStore(db)
        val session = assertNotNull(recovery.recoverySession(account))
        val payload = recovery.nativePayload(session.sessionId)
        assertNull(state.read(account.value))
        assertEquals(listOf(first), store.pendingOperations())
        val later = append("22")
        val discovered = AppSyncV3DocumentCodec().discover(payload.body, account.value, AppSyncV3PayloadKind.Checkpoint)
        val retryCloud = cloud.copy(canonicalDocuments = listOf(LoadedAppSyncCanonicalDocument("102", discovered)))
        now = assertNotNull(session.nextRetryAtEpochMillis)
        provider.onList = {}
        provider.listFailureAt = null
        val blogs = SqlDelightAppSyncRemoteBlogStore(db)
        val restarted = AppSyncNativeRecoveryContinuation(provider, store, SqlDelightAppSyncRecoveryStore(db), blogs,
            activator(), { now }, { true }, legacyStarter = AppSyncLegacyMigrationStarter(db, store, recovery, { now }, { true }))
        assertEquals(1, assertIs<OperationSyncResult.Converged>(synchronize(retryCloud, restarted)).acknowledgedLocalCount)
        assertEquals(AppSyncRecoveryPhase.Completed, recovery.session(session.sessionId)?.phase)
        assertEquals(3, provider.posts.size)
        assertEquals(listOf(later), store.pendingOperations())
        assertEquals(22, preferences.values["novelreadersettings.fontsize"])
        assertEquals(2L, state.read(account.value)?.coverage?.get(first.replicaKey.stableKey))
    }

    @Test fun migrationRetryCannotAdoptAnUnrelatedUnindexedNativeCheckpoint() = fixture {
        append()
        val cloud = legacyMigrationCloud()
        val (provider, continuation) = legacyPublishingEnvironment(cloud)
        provider.onList = { if (provider.posts.size >= 2) provider.listFailureAt = 1 }
        assertIs<OperationSyncResult.RetryScheduled>(synchronize(cloud, continuation))
        val recovery = SqlDelightAppSyncRecoveryStore(db)
        val session = assertNotNull(recovery.recoverySession(account))
        now = assertNotNull(session.nextRetryAtEpochMillis)
        provider.onList = {}
        provider.listFailureAt = null
        val unrelated = AppSyncV3DocumentCodec().discover(AppSyncV3DocumentCodec().encodeCheckpoint(checkpoint),
            account.value, AppSyncV3PayloadKind.Checkpoint)
        val result = synchronize(cloud.copy(canonicalDocuments = listOf(LoadedAppSyncCanonicalDocument("999", unrelated))), continuation)
        assertIs<OperationSyncResult.PausedProvider>(result)
        assertEquals(AppSyncRecoveryPhase.NeedsAttention, recovery.session(session.sessionId)?.phase)
        assertEquals(2, provider.posts.size)
        assertNull(state.read(account.value))
        assertEquals(1, store.pendingOperations().size)
    }

    @Test fun legacyMigrationFreezesPendingAndSourceEvidenceWithoutLocalActivation() = fixture {
        val source = append()
        val cloud = legacyMigrationCloud()
        val recovery = SqlDelightAppSyncRecoveryStore(db)
        val starter = AppSyncLegacyMigrationStarter(db, store, recovery, { now }, { true })
        val id = starter.start(account, cloud).getOrThrow()
        val frozen = recovery.nativePayload(id)
        val checkpoint = assertIs<AppSyncV3DocumentRead.Checkpoint>(AppSyncV3DocumentCodec().discover(
            frozen.body, account.value, AppSyncV3PayloadKind.Checkpoint)).document
        assertEquals(1L, checkpoint.coverage[source.replicaKey.stableKey])
        assertTrue(checkpoint.checkpointId.startsWith("v3-"))
        assertNull(state.read(account.value))
        assertTrue(preferences.values.isEmpty())
        assertEquals(listOf(source), store.pendingOperations())
        val evidence = assertNotNull(recovery.legacyMigrationSource(id))
        assertEquals("legacy-base", evidence.checkpointId)
        assertEquals(cloud.verifiedLegacyCheckpoints.single().fingerprint, evidence.fingerprint)
        val later = append("22")
        now++
        assertTrue(starter.start(account, cloud).isFailure)
        val restarted = SqlDelightAppSyncRecoveryStore(db)
        assertEquals(frozen, restarted.nativePayload(id))
        assertEquals(evidence, restarted.legacyMigrationSource(id))
        assertEquals(listOf(source, later), store.pendingOperations())
        restarted.freezeLegacyMigrationSource(id, cloud.verifiedLegacyCheckpoints.single())
        assertEquals(evidence, restarted.legacyMigrationSource(id))
    }

    @Test fun legacyMigrationFreezeRollsBackAndProtectsExistingNativeState() = fixture {
        val source = append()
        val cloud = legacyMigrationCloud()
        val recovery = SqlDelightAppSyncRecoveryStore(db)
        val starter = AppSyncLegacyMigrationStarter(db, store, recovery, { now }, { true })
        assertFailsWith<IllegalStateException> {
            db.transaction {
                starter.start(account, cloud).getOrThrow()
                error("rollback after payload and source freeze")
            }
        }
        assertNull(recovery.recoverySession(account))
        assertEquals(listOf(source), store.pendingOperations())
        var checks = 0
        assertTrue(AppSyncLegacyMigrationStarter(db, store, recovery, { now }, { ++checks < 2 }).start(account, cloud).isFailure)
        assertNull(recovery.recoverySession(account))
        assertIs<AppSyncCanonicalActivationResult.Applied>(activator().activate(verified()))
        assertTrue(starter.start(account, cloud).isFailure)
        assertNull(recovery.recoverySession(account))
    }

    @Test fun partiallyCorruptedMigrationBindingCannotBeRecreated() = fixture {
        val cloud = legacyMigrationCloud()
        val recovery = SqlDelightAppSyncRecoveryStore(db)
        val id = AppSyncLegacyMigrationStarter(db, store, recovery, { now }, { true }).start(account, cloud).getOrThrow()
        driver.execute(null, "UPDATE AppSyncRecoveryPayload SET legacySourceFingerprint = NULL", 0)
        assertFails { recovery.legacyMigrationSource(id) }
        assertFails { recovery.freezeLegacyMigrationSource(id, cloud.verifiedLegacyCheckpoints.single()) }
        assertNotNull(recovery.nativePayload(id))
        assertNull(state.read(account.value))
    }

    @Test fun migrationSourceColumnsPreserveExistingPayloadWithoutInventingEvidence() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            driver.execute(null, "CREATE TABLE AppSyncRecoveryPayload(sessionId TEXT PRIMARY KEY, canonicalEnvelope TEXT NOT NULL)", 0)
            driver.execute(null, "INSERT INTO AppSyncRecoveryPayload VALUES ('old', 'frozen')", 0)
            Database.Schema.migrate(driver, oldVersion = 57, newVersion = 58)
            val values = driver.executeQuery(null,
                "SELECT canonicalEnvelope, legacySourceBlogId, legacySourceCheckpointId, legacySourceFingerprint, legacySourceIndexFingerprint FROM AppSyncRecoveryPayload",
                { cursor ->
                    assertTrue(cursor.next().value)
                    app.cash.sqldelight.db.QueryResult.Value((0..4).map { cursor.getString(it) })
                }, 0).value
            assertEquals(listOf("frozen", null, null, null, null), values)
        }
    }

    @Test fun nativeCheckpointCadencePublishesVerifiedReplacementAndCleansCoveredHistory() {
        for (count in listOf(63, 64)) fixture {
            val sources = List(count) { append() }
            val (provider, continuation) = nativePublishingEnvironment()
            val recovery = SqlDelightAppSyncRecoveryStore(db)
            val form = io.github.littlesurvival.dto.value.FormHash("test")
            val baseCloud = AppSyncCanonicalCloudPlan.Ready(verified(), AppSyncCanonicalOperationBlock(account.value, emptyList()), emptyList())
            val first = kotlinx.coroutines.runBlocking { continuation.resume(account, form, baseCloud) }
            assertEquals(count, assertIs<OperationSyncResult.Converged>(first).acknowledgedLocalCount)
            val previous = assertNotNull(recovery.recoverySession(account))
            assertEquals(me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncRecoveryMode.SegmentedJournal, previous.mode)
            val payload = recovery.nativePayload(previous.sessionId)
            val journal = assertIs<AppSyncV3DocumentRead.Journal>(AppSyncV3DocumentCodec().discover(payload.body, account.value, AppSyncV3PayloadKind.Journal))
            val cloud = baseCloud.copy(canonicalOperations = journal.document.block, nativeJournals = listOf(journal))
            now++
            var later: SyncOperation? = null
            if (count == 64) {
                provider.timeoutAt = provider.posts.size + 1
                provider.storeTimedOut = true
                provider.onList = {
                    if (later == null) {
                        later = append("22")
                        state.recordLocalBatch(account.value, listOf(requireNotNull(later)))
                        preferences.values["novelreadersettings.fontsize"] = 22
                    }
                }
            }
            val second = kotlinx.coroutines.runBlocking { continuation.resume(account, form, cloud) }
            assertIs<OperationSyncResult.Converged>(second)
            val session = assertNotNull(recovery.recoverySession(account))
            assertEquals(AppSyncRecoveryPhase.Completed, session.phase)
            if (count == 63) {
                assertEquals(me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncRecoveryMode.SegmentedJournal, session.mode)
                assertEquals(63, store.allOutboxOperations().size)
                assertNull(db.appSyncNativeCompletionQueries.getForSession(session.sessionId).executeAsOneOrNull())
            } else {
                assertEquals(me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncRecoveryMode.SegmentedCheckpoint, session.mode)
                val receipt = db.appSyncNativeCompletionQueries.getForSession(session.sessionId).executeAsOne()
                val checkpoint = store.verifiedCheckpoints().single { it.checkpointId == receipt.checkpointId }
                assertEquals(64L, checkpoint.coverage.asStableMap()[sources.first().replicaKey.stableKey])
                assertEquals(listOf(assertNotNull(later)), store.pendingOperations())
                assertEquals(1, store.allOutboxOperations().size)
                assertEquals(22, preferences.values["novelreadersettings.fontsize"])
                assertNull(db.appSyncOperationQueries.getRecoveryPayload(session.sessionId).executeAsOneOrNull())
                assertTrue(db.appSyncRetainedJournalQueries.getForAccount(account.value).executeAsList().isEmpty())
                assertTrue(receipt.payloadBytesRemoved > 0)
                assertEquals(6, provider.posts.size)
            }
        }
    }

    private fun AppSyncNativeJournalStarter.startBlocking(account: SyncAccountBinding, cloud: AppSyncCanonicalCloudPlan.Ready) =
        kotlinx.coroutines.runBlocking { start(account, cloud) }

    @Test fun fallbackStarterFreezesBothFormatsAtomicallyAndPreservesLaterPendingSources() = fixture {
        val first = append("20")
        val recovery = SqlDelightAppSyncRecoveryStore(db)
        val cloud = AppSyncCanonicalCloudPlan.Ready(verified(), AppSyncCanonicalOperationBlock(account.value, emptyList()), emptyList())
        val starter = AppSyncNativeJournalStarter(db, store, recovery, state, activator(), { now }, { true }, sanitizedV2Fallback = true)
        val id = starter.startBlocking(account, cloud).getOrThrow()
        val envelope = recovery.sanitizedV2Payload(id)
        val parsed = assertIs<AppSyncJournalValidation.Valid>(AppSyncJournalEnvelopeCodec().validate(envelope)).envelope.payload
        assertEquals(2, parsed.protocolWriteVersion)
        assertEquals(listOf(first.operationId), parsed.operations.map { it.operationId })
        assertEquals(setOf(first.operationId.value), recovery.session(id)?.sourceOperationIds)
        assertEquals(AppSyncRecoveryPhase.Classifying, recovery.session(id)?.phase)
        assertEquals(listOf(first), store.pendingOperations())
        val later = append("22")
        val restarted = SqlDelightAppSyncRecoveryStore(db)
        assertEquals(envelope, restarted.sanitizedV2Payload(id))
        assertEquals(listOf(first, later), store.pendingOperations())
        db.appSyncV2FallbackPayloadQueries.deleteForSession(id)
        assertFailsWith<IllegalStateException> {
            db.transaction {
                recovery.pinSanitizedV2Payload(id) { true }
                error("abort")
            }
        }
        assertFalse(recovery.hasSanitizedV2Payload(id))
        assertEquals(envelope, recovery.pinSanitizedV2Payload(id) { true })
        recovery.rollbackPreCommit(id)
        assertFalse(recovery.hasSanitizedV2Payload(id))
        assertEquals(listOf(first, later), store.pendingOperations())
    }

    private fun Fixture.runFallback(provider: AppSyncV3SegmentPublisherTest.Provider, id: String,
        canWrite: Boolean = true, cloud: AppSyncCanonicalCloudPlan.Ready = AppSyncCanonicalCloudPlan.Ready(
            verified(), AppSyncCanonicalOperationBlock(account.value, emptyList()), emptyList())) = kotlinx.coroutines.runBlocking {
        val recovery = SqlDelightAppSyncRecoveryStore(db)
        val selection = AppSyncBlogClassSelection.Existing(io.github.littlesurvival.dto.value.BlogClassId(7))
        val publisher = AppSyncSanitizedV2SegmentPublisher(provider, recovery, { now }, canWrite = { canWrite },
            discover = AppSyncV3ArtifactReconciler(provider, selection.classId)::discover)
        val committer = AppSyncSanitizedV2IndexCommitter(provider, recovery, publisher, { now }, { canWrite })
        AppSyncSanitizedV2CommitCoordinator(committer, recovery, activator(), { now }, { canWrite })
            .commit(id, selection, io.github.littlesurvival.dto.value.FormHash("test"), cloud)
    }

    private fun Fixture.startFallback(): Pair<SqlDelightAppSyncRecoveryStore, String> {
        val recovery = SqlDelightAppSyncRecoveryStore(db)
        val cloud = AppSyncCanonicalCloudPlan.Ready(verified(), AppSyncCanonicalOperationBlock(account.value, emptyList()), emptyList())
        val starter = AppSyncNativeJournalStarter(db, store, recovery, state, activator(), { now }, { true }, sanitizedV2Fallback = true)
        return recovery to starter.startBlocking(account, cloud).getOrThrow()
    }

    @Test fun fallbackCoordinatorCompletesPublicationActivationAndReceiptRetryWithoutSecondTrigger() = fixture {
        val first = append("20")
        val (recovery, id) = startFallback()
        val provider = nativePublishingEnvironment().first
        val originalCheckpoint = provider.artifacts[123]
        val later = append("22")
        assertEquals(AppSyncRecoveryPhase.Classifying, recovery.session(id)?.phase)
        val result = assertIs<AppSyncSegmentedJournalCommitResult.Verified>(runFallback(provider, id))
        assertEquals(setOf(first.operationId.value), result.acknowledgedOperationIds)
        assertEquals(AppSyncRecoveryPhase.Completed, recovery.session(id)?.phase)
        assertEquals(listOf(later), store.pendingOperations())
        assertEquals(22, preferences.values["novelreadersettings.fontsize"])
        assertEquals(originalCheckpoint, provider.artifacts[123])
        assertEquals(3, provider.posts.size)
        val cp = assertIs<AppSyncCanonicalPendingMergeResult.Ready>(AppSyncCanonicalPendingMerge()
            .prepare(checkpoint, listOf(first), "covered-fallback-coordinator", now)).checkpoint
        assertIs<AppSyncCanonicalActivationResult.Applied>(activator().activate(verified(cp)))
        assertFalse(recovery.hasSanitizedV2Payload(id))
        assertTrue(recovery.usesSanitizedV2Transport(id))
        preferences.values["novelreadersettings.fontsize"] = 30
        assertIs<AppSyncSegmentedJournalCommitResult.Verified>(runFallback(provider, id, canWrite = false))
        assertEquals(3, provider.posts.size)
        assertEquals(30, preferences.values["novelreadersettings.fontsize"])
        assertEquals(listOf(later), store.pendingOperations())
    }

    @Test fun fallbackCoordinatorResumesCommittedSettingsWithWritesDisabledAfterDurableDeadline() = fixture {
        val first = append("20")
        val (recovery, id) = startFallback()
        val provider = nativePublishingEnvironment().first
        val remoteDevice = SyncDeviceId("fallback-remote")
        val remote = first.copy(deviceId = remoteDevice,
            operationId = SyncOperation.idFor(remoteDevice, first.deviceEpoch, first.sequence),
            fields = first.fields + ("value" to "26"), createdAtEpochMillis = 100)
        val imported = assertIs<AppSyncCanonicalOperationImport.Accepted>(AppSyncCanonicalOperationImporter().import(account.value, remote))
        val cloud = AppSyncCanonicalCloudPlan.Ready(verified(), AppSyncCanonicalOperationBlock(account.value, listOf(imported.operation)), emptyList())
        preferences.fail = true
        assertIs<AppSyncSegmentedJournalCommitResult.Retryable>(runFallback(provider, id, cloud = cloud))
        assertTrue(assertNotNull(recovery.session(id)).indexCommitted)
        assertEquals(1L, recovery.session(id)?.retryCount)
        assertEquals(listOf(first), store.pendingOperations())
        val later = append("22")
        preferences.fail = false
        assertIs<AppSyncSegmentedJournalCommitResult.Retryable>(runFallback(provider, id, canWrite = false, cloud = cloud))
        assertEquals(1L, recovery.session(id)?.retryCount)
        now = assertNotNull(recovery.session(id)?.nextRetryAtEpochMillis)
        assertIs<AppSyncSegmentedJournalCommitResult.Verified>(runFallback(provider, id, canWrite = false, cloud = cloud))
        assertEquals(listOf(later), store.pendingOperations())
        assertEquals(22, preferences.values["novelreadersettings.fontsize"])
        assertEquals(3, provider.posts.size)
    }

    @Test fun fallbackCoordinatorPersistsAttemptBudgetAndRequiresExplicitResumeAfterThirdFailure() = fixture {
        val first = append()
        val (recovery, id) = startFallback()
        val provider = nativePublishingEnvironment().first
        assertIs<AppSyncSegmentedJournalCommitResult.Terminal>(runFallback(provider, id, canWrite = false))
        assertEquals(AppSyncRecoveryPhase.Classifying, recovery.session(id)?.phase)
        assertEquals(0L, recovery.session(id)?.retryCount)
        for (attempt in 1..3) {
            provider.timeoutAt = attempt
            val result = runFallback(provider, id)
            if (attempt < 3) {
                assertIs<AppSyncSegmentedJournalCommitResult.Retryable>(result)
                assertIs<AppSyncSegmentedJournalCommitResult.Retryable>(runFallback(provider, id))
                now = assertNotNull(recovery.session(id)?.nextRetryAtEpochMillis)
            } else assertIs<AppSyncSegmentedJournalCommitResult.Terminal>(result)
            assertEquals(attempt.toLong(), recovery.session(id)?.retryCount)
            assertEquals(attempt, provider.posts.size)
        }
        assertEquals(AppSyncRecoveryPhase.NeedsAttention, recovery.session(id)?.phase)
        assertEquals(listOf(first), store.pendingOperations())
        assertIs<AppSyncSegmentedJournalCommitResult.Terminal>(runFallback(provider, id))
        assertEquals(3, provider.posts.size)
        assertNotNull(recovery.resumeRetryExhaustedRecovery(account, ++now))
        provider.timeoutAt = null
        assertIs<AppSyncSegmentedJournalCommitResult.Verified>(runFallback(provider, id))
        assertTrue(store.pendingOperations().isEmpty())
    }

    @Test fun canonicalCoordinatorsRejectWrongFrozenFormatWithoutChangingSessionOrChargingAttempts() {
        for (fallback in listOf(false, true)) fixture {
            val first = append()
            val (recovery, id) = stageNativeJournal(first, fallback = fallback)
            val before = recovery.session(id)
            val provider = nativePublishingEnvironment().first
            val cloud = AppSyncCanonicalCloudPlan.Ready(verified(), AppSyncCanonicalOperationBlock(account.value, emptyList()), emptyList())
            val result = if (fallback) commitNative(recovery, id, cloud, now) else runFallback(provider, id)
            assertIs<AppSyncSegmentedJournalCommitResult.Terminal>(result)
            assertEquals(before, recovery.session(id))
            assertEquals(listOf(first), store.pendingOperations())
            assertTrue(provider.posts.isEmpty())
        }
    }

    private fun Fixture.fallbackContinuation(provider: AppSyncV3SegmentPublisherTest.Provider,
        prefer: () -> Boolean = { true }, allowed: () -> Boolean = { true }, nativeAllowed: () -> Boolean = { false }): AppSyncNativeRecoveryContinuation {
        val recovery = SqlDelightAppSyncRecoveryStore(db)
        val blogs = SqlDelightAppSyncRemoteBlogStore(db).also {
            it.saveClassId(account, io.github.littlesurvival.dto.value.BlogClassId(7))
        }
        val nativeGate = { !prefer() && nativeAllowed() }
        val fallbackGate = { prefer() && allowed() }
        return AppSyncNativeRecoveryContinuation(provider, store, recovery, blogs, activator(), { now }, nativeGate,
            journalStarter = AppSyncNativeJournalStarter(db, store, recovery, state, activator(), { now }, nativeGate),
            preferSanitizedV2 = prefer, canWriteSanitizedV2 = fallbackGate,
            sanitizedV2Starter = AppSyncNativeJournalStarter(db, store, recovery, state, activator(), { now }, fallbackGate,
                sanitizedV2Fallback = true))
    }

    @Test fun fallbackRemoteRoundTripRetainsNativeHistoryAndAllowsNextPublication() = fixture {
        val provider = nativePublishingEnvironment().first
        val own = assertNotNull(store.installation())
        val identity = "${own.deviceId.value}:${own.deviceEpoch.value}"
        val journal = AppSyncCanonicalJournal(AppSyncCanonicalOperationBlock(account.value, emptyList()),
            own.deviceId.value, own.deviceEpoch.value, own.writerNonce.value, 0, 0, emptyMap(), emptyList(), now, 3, 3, "test", 0)
        val body = AppSyncV3DocumentCodec().encodeJournal(identity, journal)
        val original = assertNotNull(provider.artifacts[123]).copy(blogId = io.github.littlesurvival.dto.value.BlogId(125),
            title = AppSyncJournalDefaults.journalTitle(own.deviceId, own.deviceEpoch), message = body)
        provider.artifacts[125] = original
        provider.artifacts[124] = assertNotNull(provider.artifacts[124]).copy(message = AppSyncIndexEnvelopeCodec().encode(
            AppSyncIndexPayload(account, journals = listOf(AppSyncIndexJournalReference(identity, 125,
                AppSyncCanonicalJournalCodec().encode(journal).sha256().hex())),
                checkpoints = listOf(AppSyncIndexCheckpointReference(checkpoint.checkpointId, 123, verified().fingerprint)),
                updatedAtEpochMillis = now)))
        val blogs = SqlDelightAppSyncRemoteBlogStore(db).also {
            it.saveClassId(account, io.github.littlesurvival.dto.value.BlogClassId(7))
        }
        val remote = YamiboAppSyncJournalRemote(provider, blogs, nowMillis = { now })
        val cohort = SqlDelightAppSyncReaderCohortStore(db)
        fun load(): AppSyncJournalLoadResult.Success {
            val loaded = assertIs<AppSyncJournalLoadResult.Success>(kotlinx.coroutines.runBlocking { remote.loadJournals(account, true) })
            assertTrue(loaded.authoritativeDiscovery)
            assertTrue(loaded.canonicalReadIssues.isEmpty())
            assertTrue(loaded.retirementDiscoveryIssues.isEmpty())
            assertTrue(loaded.canonicalDocuments.any { it.remoteId == "125" })
            cohort.observe(account, loaded, now)
            assertTrue(cohort.canWriteSanitizedV2(assertNotNull(store.installation()), now, true))
            return loaded
        }
        repeat(2) { index ->
            now++
            append((20 + index).toString())
            val cloud = load()
            val result = synchronize(cloud, fallbackContinuation(provider, allowed = {
                cohort.canWriteSanitizedV2(assertNotNull(store.installation()), now, true)
            }))
            assertIs<OperationSyncResult.Converged>(result, "round $index: $result")
            assertTrue(store.pendingOperations().isEmpty())
            assertEquals(original, provider.artifacts[125])
        }
        val finalCloud = load()
        assertEquals(2, finalCloud.journals.size)
        assertEquals(3, assertNotNull(cohort.evidence(account)).readers.size)
        assertIs<AppSyncCanonicalCloudPlan.Ready>(AppSyncCanonicalCloudPlanner().prepare(account, assertNotNull(store.installation()), finalCloud))
        assertEquals(6, provider.posts.size)
        val restricted = YamiboAppSyncJournalRemote(provider, blogs, nowMillis = { now },
            capacityFlags = AppSyncCapacityFeatureFlags(v2ReadsEnabled = false))
        val indexedOnly = assertIs<AppSyncJournalLoadResult.Success>(kotlinx.coroutines.runBlocking { restricted.loadJournals(account, true) })
        assertEquals(1, indexedOnly.journals.size)
        assertEquals(2L, indexedOnly.journals.single().payload.lastSequence)
        assertIs<AppSyncCanonicalCloudPlan.Ready>(AppSyncCanonicalCloudPlanner().prepare(account, assertNotNull(store.installation()), indexedOnly))
        val indexRequest = assertNotNull(provider.artifacts[124])
        val indexPayload = assertIs<AppSyncIndexValidation.Valid>(AppSyncIndexEnvelopeCodec().validate(indexRequest.message)).envelope.payload
        val rootId = indexPayload.journals.single().blogId
        val root = assertNotNull(provider.artifacts[rootId])
        provider.hiddenIds = setOf(rootId, 123)
        val omitted = assertIs<AppSyncJournalLoadResult.Success>(kotlinx.coroutines.runBlocking { restricted.loadJournals(account, true) })
        assertTrue(omitted.retirementDiscoveryIssues.isEmpty())
        assertEquals(rootId.toString(), omitted.journals.single().remoteId)
        assertEquals(1, omitted.verifiedCanonicalCheckpoints.size)
        provider.artifacts.remove(rootId)
        val missing = assertIs<AppSyncJournalLoadResult.Success>(kotlinx.coroutines.runBlocking { remote.loadJournals(account, true) })
        assertTrue(missing.canonicalDocuments.any { it.remoteId == "125" })
        assertTrue(missing.retirementDiscoveryIssues.isNotEmpty())
        cohort.observe(account, missing, now)
        assertFalse(cohort.canWriteSanitizedV2(assertNotNull(store.installation()), now, true))
        assertIs<AppSyncCanonicalCloudPlan.NeedsAttention>(AppSyncCanonicalCloudPlanner().prepare(account, assertNotNull(store.installation()), missing))
        provider.artifacts[rootId] = root
        provider.artifacts[124] = indexRequest.copy(message = AppSyncIndexEnvelopeCodec().encode(indexPayload.copy(
            journals = indexPayload.journals.map { it.copy(fingerprint = "wrong-root-fingerprint") })))
        val mismatched = assertIs<AppSyncJournalLoadResult.Success>(kotlinx.coroutines.runBlocking { remote.loadJournals(account, true) })
        assertTrue(mismatched.retirementDiscoveryIssues.isNotEmpty())
        assertIs<AppSyncCanonicalCloudPlan.NeedsAttention>(AppSyncCanonicalCloudPlanner().prepare(account, assertNotNull(store.installation()), mismatched))
    }

    @Test fun discoveryRequiresEveryIndexedCheckpointEvenWhenAnotherValidBaseExists() = fixture {
        val provider = nativePublishingEnvironment().first
        val index = assertNotNull(provider.artifacts[124])
        val payload = assertIs<AppSyncIndexValidation.Valid>(AppSyncIndexEnvelopeCodec().validate(index.message)).envelope.payload
        val remote = YamiboAppSyncJournalRemote(provider, SqlDelightAppSyncRemoteBlogStore(db), nowMillis = { now })
        provider.artifacts[124] = index.copy(message = AppSyncIndexEnvelopeCodec().encode(payload.copy(
            checkpoints = payload.checkpoints + AppSyncIndexCheckpointReference("missing-checkpoint", 999, "missing-fingerprint"))))
        val missing = assertIs<AppSyncJournalLoadResult.Success>(kotlinx.coroutines.runBlocking { remote.loadJournals(account, true) })
        assertEquals(1, missing.verifiedCanonicalCheckpoints.size)
        assertTrue(missing.retirementDiscoveryIssues.isNotEmpty())
        assertIs<AppSyncCanonicalCloudPlan.NeedsAttention>(AppSyncCanonicalCloudPlanner().prepare(account, assertNotNull(store.installation()), missing))
        provider.artifacts[124] = index.copy(message = AppSyncIndexEnvelopeCodec().encode(payload.copy(
            checkpoints = payload.checkpoints.map { it.copy(fingerprint = "mismatched") })))
        val corrupt = assertIs<AppSyncJournalLoadResult.Success>(kotlinx.coroutines.runBlocking { remote.loadJournals(account, true) })
        assertTrue(corrupt.retirementDiscoveryIssues.isNotEmpty())
        assertTrue(corrupt.verifiedCanonicalCheckpoints.isEmpty())
    }

    @Test fun engineFallbackDispatchForcesDiscoveryAndCompletesWithNativeWriterDisabled() = fixture {
        val first = append("20")
        val provider = nativePublishingEnvironment().first
        val continuation = fallbackContinuation(provider)
        val cloud = AppSyncJournalLoadResult.Success(emptyList(), verifiedCanonicalCheckpoints = listOf(verified()))
        val result = assertIs<OperationSyncResult.Converged>(synchronize(cloud, continuation))
        assertEquals(1, result.acknowledgedLocalCount)
        assertTrue(forcedLoads.all { it })
        assertEquals(1, loadCalls)
        val recovery = SqlDelightAppSyncRecoveryStore(db)
        val session = assertNotNull(recovery.recoverySession(account))
        assertEquals(AppSyncRecoveryPhase.Completed, session.phase)
        val payload = assertIs<AppSyncJournalValidation.Valid>(AppSyncJournalEnvelopeCodec()
            .validate(recovery.sanitizedV2Payload(session.sessionId))).envelope.payload
        assertEquals(2, payload.protocolWriteVersion)
        assertEquals(listOf(first.operationId), payload.operations.map { it.operationId })
        assertTrue(store.pendingOperations().isEmpty())
        assertEquals(3, provider.posts.size)
    }

    @Test fun engineFallbackModeChangePreservesFrozenFormatAndRequiresCompatibleExplicitResume() = fixture {
        val first = append()
        val provider = nativePublishingEnvironment().first
        val recovery = SqlDelightAppSyncRecoveryStore(db)
        var prefer = true
        var allowed = false
        fun continuation() = fallbackContinuation(provider, { prefer }, { allowed }, { true })
        val cloud = AppSyncJournalLoadResult.Success(emptyList(), verifiedCanonicalCheckpoints = listOf(verified()))
        assertIs<OperationSyncResult.PausedProvider>(synchronize(cloud, continuation()))
        assertNull(recovery.recoverySession(account))
        assertTrue(provider.posts.isEmpty())
        allowed = true
        provider.timeoutAt = 1
        assertIs<OperationSyncResult.RetryScheduled>(synchronize(cloud, continuation()))
        val frozen = assertNotNull(recovery.recoverySession(account))
        val bytes = recovery.sanitizedV2Payload(frozen.sessionId)
        now = assertNotNull(frozen.nextRetryAtEpochMillis)
        prefer = false
        assertIs<OperationSyncResult.PausedProvider>(synchronize(cloud, continuation()))
        assertEquals(AppSyncRecoveryPhase.NeedsAttention, recovery.session(frozen.sessionId)?.phase)
        assertEquals("native-compatibility", recovery.session(frozen.sessionId)?.lastErrorCategory)
        assertEquals(1, provider.posts.size)
        assertEquals(bytes, recovery.sanitizedV2Payload(frozen.sessionId))
        assertEquals(listOf(first), store.pendingOperations())
        prefer = true
        assertNotNull(recovery.resumeRetryExhaustedRecovery(account, ++now))
        provider.timeoutAt = null
        assertIs<OperationSyncResult.Converged>(synchronize(cloud, continuation()))
        assertEquals(AppSyncRecoveryPhase.Completed, recovery.session(frozen.sessionId)?.phase)
        assertEquals(bytes, recovery.sanitizedV2Payload(frozen.sessionId))
        assertTrue(store.pendingOperations().isEmpty())
    }

    @Test fun engineResumesCommittedFallbackWhenBothWriterModesAreDisabled() = fixture {
        val first = append()
        val (recovery, id) = stageNativeJournal(first, fallback = true)
        val provider = nativePublishingEnvironment().first
        val cloud = AppSyncJournalLoadResult.Success(emptyList(), verifiedCanonicalCheckpoints = listOf(verified()))
        preferences.fail = true
        assertIs<OperationSyncResult.RetryScheduled>(synchronize(cloud, fallbackContinuation(provider, { false }, { false })))
        assertEquals(listOf(first), store.pendingOperations())
        val later = append("22")
        preferences.fail = false
        now = assertNotNull(recovery.session(id)?.nextRetryAtEpochMillis)
        assertIs<OperationSyncResult.Converged>(synchronize(cloud, fallbackContinuation(provider, { false }, { false })))
        assertEquals(AppSyncRecoveryPhase.Completed, recovery.session(id)?.phase)
        assertEquals(listOf(later), store.pendingOperations())
        assertEquals(22, preferences.values["novelreadersettings.fontsize"])
        assertTrue(provider.posts.isEmpty())
        assertTrue(forcedLoads.all { it })
    }

    @Test fun ordinaryCanonicalSyncDrainsRetainedJournalsWithoutAnotherTrigger() = fixture {
        retainJournalHistory(17)
        val cp = assertNotNull(state.read(account.value)).copy(checkpointId = "drain-all")
        val cloud = AppSyncCanonicalCloudPlan.Ready(verified(cp), AppSyncCanonicalOperationBlock(account.value, emptyList()), emptyList())
        val result = kotlinx.coroutines.runBlocking { activator().activateAndDrain(cloud) }
        val applied = assertIs<AppSyncCanonicalActivationResult.Applied>(result)
        assertFalse(applied.cleanupPending)
        assertEquals(17, applied.removedLocalRows)
        assertTrue(db.appSyncRetainedJournalQueries.getForAccount(account.value).executeAsList().isEmpty())
        assertEquals(17L, db.appSyncLocalPruneQueries.getAudit(account.value).executeAsOne().removedRetainedJournals)
    }

    @Test fun ordinaryCleanupCancellationPreservesBatchProgressAndLaterPendingEdits() = fixture {
        val sources = List(260) { append() }
        store.markAcknowledged(sources.map { it.operationId }.toSet(), now)
        val cp = assertIs<AppSyncCanonicalPendingMergeResult.Ready>(AppSyncCanonicalPendingMerge()
            .prepare(checkpoint, sources, "large-reader-checkpoint", now)).checkpoint
        val cloud = AppSyncCanonicalCloudPlan.Ready(verified(cp), AppSyncCanonicalOperationBlock(account.value, emptyList()), emptyList())
        kotlinx.coroutines.runBlocking {
            val work = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { activator().activateAndDrain(cloud) }
            assertFalse(work.isCompleted)
            assertEquals(132, store.allOutboxOperations().size)
            val later = append("22")
            state.recordLocalBatch(account.value, listOf(later))
            preferences.values["novelreadersettings.fontsize"] = 22
            work.cancel()
            work.join()
            assertEquals(133, store.allOutboxOperations().size)
            val resumed = assertIs<AppSyncCanonicalActivationResult.Applied>(activator().activateAndDrain(cloud))
            assertFalse(resumed.cleanupPending)
            assertEquals(132, resumed.removedLocalRows)
            assertEquals(listOf(later), store.pendingOperations())
            assertEquals(22, preferences.values["novelreadersettings.fontsize"])
            assertEquals(260L, db.appSyncLocalPruneQueries.getAudit(account.value).executeAsOne().removedRows)
        }
    }

    @Test fun fallbackRetainedCompanionMustMatchCanonicalBeforeCoveredPayloadDeletion() = fixture {
        val id = retainJournalHistory(1, fallback = true).single()
        val original = db.appSyncRetainedJournalQueries.getBySession(id).executeAsOne()
        val expectedBytes = original.canonicalEnvelope.encodeToByteArray().size.toLong() +
            original.indexIntentBody.encodeToByteArray().size + assertNotNull(original.fallbackEnvelope).encodeToByteArray().size
        val cp = assertNotNull(state.read(account.value)).copy(checkpointId = "covered-fallback")
        val outboxBytes = cp.coverage.entries.sumOf { (replica, sequence) ->
            db.appSyncLocalPruneQueries.getCandidates(account.value, replica, sequence, 128).executeAsList().sumOf { it.payloadBytes }
        }
        // Even a matching hash cannot authorize a different v2 representation of the journal.
        val corrupt = assertNotNull(original.fallbackEnvelope) + "tampered"
        driver.execute(null, "UPDATE AppSyncRetainedJournal SET fallbackEnvelope = ?, fallbackEnvelopeSha256 = ?", 2) {
            bindString(0, corrupt); bindString(1, corrupt.encodeUtf8().sha256().hex())
        }
        assertIs<AppSyncCanonicalActivationResult.NeedsAttention>(activator().activate(verified(cp)))
        assertEquals(1, store.allOutboxOperations().size)
        assertNotNull(db.appSyncRetainedJournalQueries.getBySession(id).executeAsOneOrNull())
        assertTrue(db.appSyncLocalPruneQueries.getAudit(account.value).executeAsList().isEmpty())
        driver.execute(null, "UPDATE AppSyncRetainedJournal SET fallbackEnvelope = ?, fallbackEnvelopeSha256 = ?", 2) {
            bindString(0, original.fallbackEnvelope); bindString(1, original.fallbackEnvelopeSha256)
        }
        assertIs<AppSyncCanonicalActivationResult.Applied>(activator().activate(verified(cp)))
        assertNull(db.appSyncRetainedJournalQueries.getBySession(id).executeAsOneOrNull())
        val audit = db.appSyncLocalPruneQueries.getAudit(account.value).executeAsOne()
        assertEquals(1L, audit.removedRetainedJournals)
        assertEquals(expectedBytes + outboxBytes, audit.removedPayloadBytes)
    }

    @Test fun retainedFallbackMigrationDoesNotInventCompanionsForNativeHistory() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            Database.Schema.migrate(driver, oldVersion = 55, newVersion = 56)
            driver.execute(null, "ALTER TABLE AppSyncRetainedJournal ADD COLUMN lastCheckedCheckpointFingerprint TEXT", 0)
            driver.execute(null, """INSERT INTO AppSyncRetainedJournal(sessionId, accountBinding, generationId, payloadIdentity,
                canonicalEnvelope, envelopeFingerprint, rootBlogId, rootFingerprint, indexIntentBody, indexIntentSha256,
                verifiedIndexBlogId, verifiedIndexFingerprint, indexVerifiedAtEpochMillis, completedAtEpochMillis)
                VALUES ('old', 'account', 'generation', 'identity', 'canonical', 'fingerprint', 1, 'root', 'index', 'sha', 2, 'index-fp', 3, 4)""", 0)
            Database.Schema.migrate(driver, oldVersion = 59, newVersion = 60)
            val row = Database(driver).appSyncRetainedJournalQueries.getBySession("old").executeAsOne()
            assertEquals("canonical", row.canonicalEnvelope)
            assertNull(row.fallbackEnvelope)
            assertNull(row.fallbackEnvelopeSha256)
        }
    }

    @Test fun corruptRetainedIndexPreservesPayloadAndRollsBackEarlierOutboxDeletion() = fixture {
        val id = retainJournalHistory(1).single()
        val cp = assertNotNull(state.read(account.value)).copy(checkpointId = "covered-retained")
        driver.execute(null, "UPDATE AppSyncRetainedJournal SET indexIntentBody = 'corrupt'", 0)
        assertIs<AppSyncCanonicalActivationResult.NeedsAttention>(activator().activate(verified(cp)))
        assertNotNull(db.appSyncRetainedJournalQueries.getBySession(id).executeAsOneOrNull())
        assertEquals(1, store.allOutboxOperations().size)
        assertNull(db.appSyncRetainedJournalQueries.getBySession(id).executeAsOne().lastCheckedCheckpointFingerprint)
        assertTrue(db.appSyncLocalPruneQueries.getAudit(account.value).executeAsList().isEmpty())
    }

    @Test fun retainedJournalCleanupHasBoundedPersistentProgressAndRechecksNewCheckpoints() = listOf(false, true).forEach { fallback -> fixture {
        val ids = retainJournalHistory(17, fallback)
        val pruner = me.thenano.yamibo.yamibo_app.repository.appsync.cleanup.AppSyncCanonicalLocalPruner(db, state)
        assertTrue(pruner.prune(verified(), now).hasMore)
        assertEquals(8, ids.count { db.appSyncRetainedJournalQueries.getBySession(it).executeAsOne().lastCheckedCheckpointFingerprint != null })
        assertTrue(pruner.prune(verified(), now).hasMore)
        assertFalse(pruner.prune(verified(), now).hasMore)
        assertEquals(17, db.appSyncRetainedJournalQueries.getForAccount(account.value).executeAsList().size)
        val cp = assertNotNull(state.read(account.value)).copy(checkpointId = "all-retained-history")
        val proof = verified(cp)
        assertIs<AppSyncCanonicalActivationResult.Applied>(activator().activate(proof))
        assertEquals(9, db.appSyncRetainedJournalQueries.getForAccount(account.value).executeAsList().size)
        val restarted = me.thenano.yamibo.yamibo_app.repository.appsync.cleanup.AppSyncCanonicalLocalPruner(db, state)
        assertTrue(restarted.prune(proof, now).hasMore)
        assertFalse(restarted.prune(proof, now).hasMore)
        assertTrue(db.appSyncRetainedJournalQueries.getForAccount(account.value).executeAsList().isEmpty())
        val audit = db.appSyncLocalPruneQueries.getAudit(account.value).executeAsList().single { it.checkpointFingerprint == proof.fingerprint }
        assertEquals(17L, audit.removedRetainedJournals)
        assertEquals(17L, audit.removedRows)
        assertTrue(audit.removedPayloadBytes > 0)
        assertEquals(0L, restarted.prune(proof, now).removedPayloadBytes)
        assertEquals(audit, db.appSyncLocalPruneQueries.getAudit(account.value).executeAsOne())
    } }

    @Test fun retainedCleanupCursorDeletionAndAuditAllRollBackTogether() = listOf(false, true).forEach { fallback -> fixture {
        val id = retainJournalHistory(1, fallback).single()
        val pruner = me.thenano.yamibo.yamibo_app.repository.appsync.cleanup.AppSyncCanonicalLocalPruner(db, state)
        assertFailsWith<IllegalStateException> {
            db.transaction {
                pruner.prune(verified(), now)
                assertNotNull(db.appSyncRetainedJournalQueries.getBySession(id).executeAsOne().lastCheckedCheckpointFingerprint)
                error("interrupted cursor")
            }
        }
        assertNull(db.appSyncRetainedJournalQueries.getBySession(id).executeAsOne().lastCheckedCheckpointFingerprint)
        val cp = assertNotNull(state.read(account.value)).copy(checkpointId = "covered-retained")
        assertFailsWith<IllegalStateException> {
            db.transaction {
                assertIs<AppSyncCanonicalActivationResult.Applied>(activator().activate(verified(cp)))
                assertNull(db.appSyncRetainedJournalQueries.getBySession(id).executeAsOneOrNull())
                error("interrupted deletion")
            }
        }
        assertNotNull(db.appSyncRetainedJournalQueries.getBySession(id).executeAsOneOrNull())
        assertTrue(db.appSyncLocalPruneQueries.getAudit(account.value).executeAsList().isEmpty())
        assertIs<AppSyncCanonicalActivationResult.Applied>(activator().activate(verified(cp)))
        assertNull(db.appSyncRetainedJournalQueries.getBySession(id).executeAsOneOrNull())
    } }

    @Test fun retainedCleanupMigrationCreatesNoImplicitProgress() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            Database.Schema.migrate(driver, oldVersion = 53, newVersion = 57)
            val db = Database(driver)
            assertTrue(db.appSyncRetainedJournalQueries.getUnchecked(account.value, "checkpoint", 8).executeAsList().isEmpty())
            db.appSyncLocalPruneQueries.recordAudit(account.value, "checkpoint", 1)
            assertEquals(0L, db.appSyncLocalPruneQueries.getAudit(account.value).executeAsOne().removedRetainedJournals)
        }
    }

    @Test fun replacingCompletedJournalPreservesFrozenEvidenceOutsideTheActiveSessionSlot() = listOf(false, true).forEach { fallback -> fixture {
        val first = append()
        val (recovery, id) = stageNativeJournal(first, fallback = fallback)
        val cloud = AppSyncCanonicalCloudPlan.Ready(verified(), AppSyncCanonicalOperationBlock(account.value, emptyList()), emptyList())
        activator().activateJournalRecovery(recovery, id, cloud)
        val frozen = db.appSyncOperationQueries.getRecoveryPayload(id).executeAsOne()
        val companion = db.appSyncV2FallbackPayloadQueries.getForSession(id).executeAsOneOrNull()
        val later = append("22")
        val next = recovery.createOrResumeSegmentedJournal(account, setOf(later.operationId.value), "next-publication", now)
        assertNotEquals(id, next.sessionId)
        assertNull(recovery.session(id))
        val retained = db.appSyncRetainedJournalQueries.getBySession(id).executeAsOne()
        assertEquals(companion?.envelope, retained.fallbackEnvelope)
        assertEquals(companion?.envelopeSha256, retained.fallbackEnvelopeSha256)
        assertFalse(recovery.hasSanitizedV2Payload(id))
        assertEquals(frozen.canonicalEnvelope, retained.canonicalEnvelope)
        assertEquals(frozen.indexIntentBody, retained.indexIntentBody)
        assertEquals(frozen.verifiedIndexFingerprint, retained.verifiedIndexFingerprint)
        assertEquals(frozen.envelopeFingerprint, retained.envelopeFingerprint)
        assertEquals(300L, retained.rootBlogId)
        assertEquals(listOf(id), db.appSyncRetainedJournalQueries.getForAccount(account.value).executeAsList())
        assertTrue(db.appSyncRetainedJournalQueries.getForAccount("other-account").executeAsList().isEmpty())
        recovery.expireCompletedRecoveryMetadata(now + 60L * 24 * 60 * 60 * 1000)
        assertEquals(retained, db.appSyncRetainedJournalQueries.getBySession(id).executeAsOne())
        assertEquals(listOf(later), store.pendingOperations())
        assertEquals(next, SqlDelightAppSyncRecoveryStore(db).recoverySession(account))
    } }

    @Test fun interruptedSessionReplacementRollsBackPreservationAndOldSessionRemoval() = listOf(false, true).forEach { fallback -> fixture {
        val first = append()
        val (recovery, id) = stageNativeJournal(first, fallback = fallback)
        activator().activateJournalRecovery(recovery, id,
            AppSyncCanonicalCloudPlan.Ready(verified(), AppSyncCanonicalOperationBlock(account.value, emptyList()), emptyList()))
        val frozen = recovery.nativePayload(id)
        val companion = if (fallback) recovery.sanitizedV2Payload(id) else null
        assertFailsWith<IllegalStateException> {
            db.transaction {
                recovery.createOrResumeSegmentedJournal(account, emptySet(), "next-publication", now)
                assertNotNull(db.appSyncRetainedJournalQueries.getBySession(id).executeAsOneOrNull())
                error("interrupted before new payload is pinned")
            }
        }
        assertEquals(AppSyncRecoveryPhase.Completed, recovery.session(id)?.phase)
        assertEquals(frozen, recovery.nativePayload(id))
        assertEquals(companion, if (fallback) recovery.sanitizedV2Payload(id) else null)
        assertNull(db.appSyncRetainedJournalQueries.getBySession(id).executeAsOneOrNull())
    } }

    @Test fun invalidCompletedJournalEvidenceCannotBeDiscardedByNextSession() = fixture {
        val first = append()
        val (recovery, id) = stageNativeJournal(first)
        activator().activateJournalRecovery(recovery, id,
            AppSyncCanonicalCloudPlan.Ready(verified(), AppSyncCanonicalOperationBlock(account.value, emptyList()), emptyList()))
        db.appSyncOperationQueries.markNativeRecoveryIndexVerified(400, "corrupt", 7, id)
        assertFailsWith<IllegalArgumentException> { recovery.createOrResumeSegmentedJournal(account, emptySet(), "next", now) }
        assertEquals(AppSyncRecoveryPhase.Completed, recovery.session(id)?.phase)
        assertNotNull(db.appSyncOperationQueries.getRecoveryPayload(id).executeAsOneOrNull())
        assertNull(db.appSyncRetainedJournalQueries.getBySession(id).executeAsOneOrNull())
    }

    @Test fun retainedJournalMigrationStartsWithoutInventedEvidence() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            Database.Schema.migrate(driver, oldVersion = 55, newVersion = 56)
            assertTrue(Database(driver).appSyncRetainedJournalQueries.getForAccount(account.value).executeAsList().isEmpty())
        }
    }

    @Test fun corruptCompletedJournalIndexEvidenceRollsBackAllPayloadDeletion() = fixture {
        val first = append()
        val (recovery, id) = stageNativeJournal(first)
        val cloud = AppSyncCanonicalCloudPlan.Ready(verified(), AppSyncCanonicalOperationBlock(account.value, emptyList()), emptyList())
        assertIs<AppSyncCanonicalActivationResult.Applied>(activator().activateJournalRecovery(recovery, id, cloud))
        val frozen = db.appSyncOperationQueries.getRecoveryPayload(id).executeAsOne()
        val cp = assertIs<AppSyncCanonicalPendingMergeResult.Ready>(AppSyncCanonicalPendingMerge()
            .prepare(checkpoint, listOf(first), "covered", 10)).checkpoint
        db.appSyncOperationQueries.markNativeRecoveryIndexVerified(requireNotNull(frozen.verifiedIndexBlogId), "corrupt",
            requireNotNull(frozen.indexVerifiedAtEpochMillis), id)
        assertIs<AppSyncCanonicalActivationResult.NeedsAttention>(activator().activate(verified(cp)))
        assertNotNull(db.appSyncOperationQueries.getRecoveryPayload(id).executeAsOneOrNull())
        assertNotNull(store.outboxOperation(first.operationId.value))
        assertNull(db.appSyncNativeCompletionQueries.getForSession(id).executeAsOneOrNull())
        db.appSyncOperationQueries.markNativeRecoveryIndexVerified(requireNotNull(frozen.verifiedIndexBlogId),
            requireNotNull(frozen.verifiedIndexFingerprint), requireNotNull(frozen.indexVerifiedAtEpochMillis), id)
        assertIs<AppSyncCanonicalActivationResult.Applied>(activator().activate(verified(cp)))
        assertNull(db.appSyncOperationQueries.getRecoveryPayload(id).executeAsOneOrNull())
    }

    @Test fun completedJournalPayloadWaitsForCheckpointCoverageThenExpiresItsReceipt() = listOf(false, true).forEach { fallback -> fixture {
        val first = append()
        val (recovery, id) = stageNativeJournal(first, fallback = fallback)
        val baseCloud = AppSyncCanonicalCloudPlan.Ready(verified(), AppSyncCanonicalOperationBlock(account.value, emptyList()), emptyList())
        assertIs<AppSyncCanonicalActivationResult.Applied>(activator().activateJournalRecovery(recovery, id, baseCloud))
        val frozenBytes = db.appSyncNativeCompletionQueries.getPayloadBytes(id).executeAsOne() +
            (if (fallback) recovery.sanitizedV2Payload(id).encodeToByteArray().size.toLong() else 0L)
        assertTrue(frozenBytes > 0)
        assertEquals(0L, recovery.pruneCompletedNativeJournal(verified()))
        assertNotNull(db.appSyncOperationQueries.getRecoveryPayload(id).executeAsOneOrNull())
        val covered = assertIs<AppSyncCanonicalPendingMergeResult.Ready>(AppSyncCanonicalPendingMerge()
            .prepare(checkpoint, listOf(first), "covered-journal", 10)).checkpoint
        val later = append("22")
        val applied = assertIs<AppSyncCanonicalActivationResult.Applied>(activator().activate(verified(covered)))
        assertEquals(1, applied.removedLocalRows)
        assertTrue(applied.removedLocalPayloadBytes > frozenBytes)
        assertNull(db.appSyncOperationQueries.getRecoveryPayload(id).executeAsOneOrNull())
        assertTrue(recovery.segmentWrites(id).isEmpty())
        assertFalse(recovery.hasSanitizedV2Payload(id))
        val receipt = db.appSyncNativeCompletionQueries.getForSession(id).executeAsOne()
        assertEquals(covered.checkpointId, receipt.checkpointId)
        assertEquals(frozenBytes, receipt.payloadBytesRemoved)
        assertTrue(SqlDelightAppSyncRecoveryStore(db).usesNativeTransport(id))
        assertEquals(0L, recovery.pruneCompletedNativeJournal(verified(covered)))
        assertIs<AppSyncCanonicalActivationResult.Applied>(activator().activateJournalRecovery(recovery, id, baseCloud))
        assertEquals(listOf(later), store.pendingOperations())
        recovery.expireCompletedRecoveryMetadata(now + 30L * 24 * 60 * 60 * 1000)
        assertNull(recovery.session(id))
        assertEquals(listOf(later), store.pendingOperations())
    } }

    @Test fun journalReclamationRequiresCoverageOfObservedRemoteDependencies() = fixture {
        val first = append()
        val remoteDevice = SyncDeviceId("remote-device")
        val remote = first.copy(deviceId = remoteDevice, operationId = SyncOperation.idFor(remoteDevice, first.deviceEpoch, first.sequence))
        val imported = assertIs<AppSyncCanonicalOperationImport.Accepted>(AppSyncCanonicalOperationImporter().import(account.value, remote))
        val block = AppSyncCanonicalOperationBlock(account.value, listOf(imported.operation))
        val (recovery, id) = stageNativeJournal(first, mapOf(remote.replicaKey.stableKey to 1L))
        val cloud = AppSyncCanonicalCloudPlan.Ready(verified(), block, emptyList())
        assertIs<AppSyncCanonicalActivationResult.Applied>(activator().activateJournalRecovery(recovery, id, cloud))
        val ownOnly = assertIs<AppSyncCanonicalPendingMergeResult.Ready>(AppSyncCanonicalPendingMerge()
            .prepare(checkpoint, listOf(first), "own-only", 10)).checkpoint
        assertIs<AppSyncCanonicalActivationResult.Applied>(activator().activate(verified(ownOnly), block))
        assertNotNull(db.appSyncOperationQueries.getRecoveryPayload(id).executeAsOneOrNull())
        assertNull(db.appSyncNativeCompletionQueries.getForSession(id).executeAsOneOrNull())
        val all = assertNotNull(state.read(account.value)).copy(checkpointId = "all-history")
        assertIs<AppSyncCanonicalActivationResult.Applied>(activator().activate(verified(all)))
        assertNull(db.appSyncOperationQueries.getRecoveryPayload(id).executeAsOneOrNull())
        assertNotNull(db.appSyncNativeCompletionQueries.getForSession(id).executeAsOneOrNull())
    }

    @Test fun journalPayloadReclamationRollsBackWithItsReceiptAndCoveredSourceDeletion() = fixture {
        val first = append()
        val (recovery, id) = stageNativeJournal(first)
        val cloud = AppSyncCanonicalCloudPlan.Ready(verified(), AppSyncCanonicalOperationBlock(account.value, emptyList()), emptyList())
        activator().activateJournalRecovery(recovery, id, cloud)
        val frozen = recovery.nativePayload(id)
        val cp = assertIs<AppSyncCanonicalPendingMergeResult.Ready>(AppSyncCanonicalPendingMerge()
            .prepare(checkpoint, listOf(first), "covered", 10)).checkpoint
        assertFailsWith<IllegalStateException> {
            db.transaction {
                assertIs<AppSyncCanonicalActivationResult.Applied>(activator().activate(verified(cp)))
                assertNull(db.appSyncOperationQueries.getRecoveryPayload(id).executeAsOneOrNull())
                error("interrupted cleanup")
            }
        }
        assertEquals(frozen, recovery.nativePayload(id))
        assertNotNull(store.outboxOperation(first.operationId.value))
        assertNull(db.appSyncNativeCompletionQueries.getForSession(id).executeAsOneOrNull())
        assertIs<AppSyncCanonicalActivationResult.Applied>(activator().activate(verified(cp)))
        assertTrue(recovery.usesNativeTransport(id))
    }

    @Test fun nativeContinuationStartsPublishesAndActivatesWhilePreservingALaterEdit() = fixture {
        val first = append()
        val recovery = SqlDelightAppSyncRecoveryStore(db)
        val provider = AppSyncV3SegmentPublisherTest.Provider().also { it.timeoutAt = 1; it.storeTimedOut = true }
        val selection = AppSyncBlogClassSelection.Existing(io.github.littlesurvival.dto.value.BlogClassId(7))
        val form = io.github.littlesurvival.dto.value.FormHash("test")
        val proof = verified()
        val index = AppSyncIndexEnvelopeCodec().encode(AppSyncIndexPayload(account,
            checkpoints = listOf(AppSyncIndexCheckpointReference(checkpoint.checkpointId, 123, proof.fingerprint)), updatedAtEpochMillis = 11))
        provider.artifacts[124] = AppSyncBlogWriteRequest(io.github.littlesurvival.dto.value.BlogId(124), APP_SYNC_INDEX_TITLE, index, selection, form)
        provider.artifacts[123] = AppSyncBlogWriteRequest(io.github.littlesurvival.dto.value.BlogId(123),
            AppSyncJournalDefaults.checkpointTitle(checkpoint.checkpointId), AppSyncV3DocumentCodec().encodeCheckpoint(checkpoint), selection, form)
        var later: SyncOperation? = null
        provider.onList = {
            if (later == null) {
                later = append("22")
                state.recordLocalBatch(account.value, listOf(requireNotNull(later)))
                preferences.values["novelreadersettings.fontsize"] = 22
            }
        }
        val blogs = SqlDelightAppSyncRemoteBlogStore(db).also { it.saveClassId(account, selection.classId) }
        val starter = AppSyncNativeJournalStarter(db, store, recovery, state, activator(), { now }, { true })
        val continuation = AppSyncNativeRecoveryContinuation(provider, store, recovery, blogs, activator(), { now }, { true }, starter)
        val cloud = AppSyncCanonicalCloudPlan.Ready(proof, AppSyncCanonicalOperationBlock(account.value, emptyList()), emptyList())
        val result = kotlinx.coroutines.runBlocking { continuation.resume(account, form, cloud) }
        assertEquals(1, assertIs<OperationSyncResult.Converged>(result).acknowledgedLocalCount)
        val session = assertNotNull(recovery.recoverySession(account))
        assertEquals(AppSyncRecoveryPhase.Completed, session.phase)
        assertTrue(session.indexCommitted)
        assertEquals(setOf(first.operationId.value), session.sourceOperationIds)
        assertEquals(listOf(assertNotNull(later)), store.pendingOperations())
        assertEquals(22, preferences.values["novelreadersettings.fontsize"])
        assertEquals(3, provider.posts.size) // One segment, one root, one index, despite the lost first response.
        assertEquals(APP_SYNC_INDEX_TITLE, provider.posts.last().title)
    }

    @Test fun newNativeJournalFreezesSourcesAndTransportBeforeAnyPublication() = fixture {
        val first = append()
        val recovery = SqlDelightAppSyncRecoveryStore(db)
        val cloud = AppSyncCanonicalCloudPlan.Ready(verified(), AppSyncCanonicalOperationBlock(account.value, emptyList()), emptyList())
        val starter = AppSyncNativeJournalStarter(db, store, recovery, state, activator(), { now }, { true })
        val id = starter.startBlocking(account, cloud).getOrThrow()
        assertEquals(AppSyncRecoveryPhase.Classifying, recovery.session(id)?.phase)
        assertEquals(setOf(first.operationId.value), recovery.session(id)?.sourceOperationIds)
        val frozen = recovery.nativePayload(id)
        val read = assertIs<AppSyncV3DocumentRead.Journal>(AppSyncV3DocumentCodec().discover(frozen.body, account.value, AppSyncV3PayloadKind.Journal))
        assertEquals(listOf(first.sequence.value), read.document.block.operations.map { it.sequence })
        assertEquals(3, read.document.protocolWriteVersion)
        assertEquals(listOf(first), store.pendingOperations())
        val later = append("22")
        assertTrue(starter.startBlocking(account, cloud).isFailure)
        assertEquals(frozen, SqlDelightAppSyncRecoveryStore(db).nativePayload(id))
        assertEquals(listOf(first, later), store.pendingOperations())
    }

    @Test fun nativeStarterAcknowledgesOnlyTheIndexedCheckpointPrefix() = fixture {
        val first = append()
        val cp = assertIs<AppSyncCanonicalPendingMergeResult.Ready>(AppSyncCanonicalPendingMerge()
            .prepare(checkpoint, listOf(first), "covered", 10)).checkpoint
        val second = append("22")
        val recovery = SqlDelightAppSyncRecoveryStore(db)
        val cloud = AppSyncCanonicalCloudPlan.Ready(verified(cp), AppSyncCanonicalOperationBlock(account.value, emptyList()), emptyList())
        val id = AppSyncNativeJournalStarter(db, store, recovery, state, activator(), { now }, { true }).startBlocking(account, cloud).getOrThrow()
        assertEquals(listOf(second), store.pendingOperations())
        assertEquals(setOf(second.operationId.value), recovery.session(id)?.sourceOperationIds)
        val frozen = recovery.nativePayload(id)
        val journal = assertIs<AppSyncV3DocumentRead.Journal>(AppSyncV3DocumentCodec().discover(frozen.body, account.value, AppSyncV3PayloadKind.Journal)).document
        assertEquals(listOf(2L), journal.block.operations.map { it.sequence })
        assertEquals(cp.coverage, journal.acknowledgements.single().coverage)
    }

    @Test fun nativeStarterRollsBackFrozenSessionWhenAcknowledgementFails() = fixture {
        val first = append()
        val cp = assertIs<AppSyncCanonicalPendingMergeResult.Ready>(AppSyncCanonicalPendingMerge()
            .prepare(checkpoint, listOf(first), "covered", 10)).checkpoint
        val recovery = SqlDelightAppSyncRecoveryStore(db)
        val broken = object : AppSyncOperationStore by store {
            override fun markAcknowledged(operationIds: Set<SyncOperationId>, atEpochMillis: Long) {
                store.markAcknowledged(operationIds, atEpochMillis)
                error("interrupted after frozen payload and acknowledgement")
            }
        }
        val cloud = AppSyncCanonicalCloudPlan.Ready(verified(cp), AppSyncCanonicalOperationBlock(account.value, emptyList()), emptyList())
        assertTrue(AppSyncNativeJournalStarter(db, broken, recovery, state, activator(), { now }, { true }).startBlocking(account, cloud).isFailure)
        assertNull(recovery.recoverySession(account))
        assertEquals(listOf(first), store.pendingOperations())
        assertNotNull(state.read(account.value))
        val id = AppSyncNativeJournalStarter(db, store, recovery, state, activator(), { now }, { true }).startBlocking(account, cloud).getOrThrow()
        assertTrue(recovery.usesNativeTransport(id))
        assertTrue(store.pendingOperations().isEmpty())
    }

    @Test fun nativeStarterWaitsForSettingsAndRechecksRolloutBeforeFreezing() = fixture {
        val first = append()
        val recovery = SqlDelightAppSyncRecoveryStore(db)
        val cloud = AppSyncCanonicalCloudPlan.Ready(verified(), AppSyncCanonicalOperationBlock(account.value, emptyList()), emptyList())
        preferences.fail = true
        assertTrue(AppSyncNativeJournalStarter(db, store, recovery, state, activator(), { now }, { true }).startBlocking(account, cloud).isFailure)
        assertNull(recovery.recoverySession(account))
        preferences.fail = false
        var checks = 0
        assertTrue(AppSyncNativeJournalStarter(db, store, recovery, state, activator(), { now }, { ++checks < 3 }).startBlocking(account, cloud).isFailure)
        assertEquals(3, checks)
        assertNull(recovery.recoverySession(account))
        assertEquals(listOf(first), store.pendingOperations())
    }

    @Test fun nativeCheckpointCompletionPurgesFrozenBodiesAndRetainsReplayReceipt() = fixture {
        val pending = append()
        val (recovery, id) = stageNativeCheckpoint()
        val before = db.appSyncNativeCompletionQueries.getPayloadBytes(id).executeAsOne()
        assertTrue(before > 0)
        assertFailsWith<IllegalArgumentException> { recovery.completeNativeCheckpointCleanup(id, now) }
        assertNotNull(db.appSyncOperationQueries.getRecoveryPayload(id).executeAsOneOrNull())
        val completed = assertIs<AppSyncCanonicalActivationResult.Applied>(activator().activateRecovery(recovery, id))
        assertEquals(before, completed.removedLocalPayloadBytes)
        assertNull(db.appSyncOperationQueries.getRecoveryPayload(id).executeAsOneOrNull())
        assertTrue(recovery.segmentWrites(id).isEmpty())
        val receipt = db.appSyncNativeCompletionQueries.getForSession(id).executeAsOne()
        assertEquals(before, receipt.payloadBytesRemoved)
        assertEquals(1L, receipt.segmentRowsRemoved)
        assertEquals(300L, receipt.rootBlogId)
        assertEquals(400L, receipt.indexBlogId)
        assertTrue(SqlDelightAppSyncRecoveryStore(db).usesNativeTransport(id))
        assertEquals(0, assertIs<AppSyncCanonicalActivationResult.Applied>(activator().activateRecovery(recovery, id)).removedLocalRows)
        assertFailsWith<IllegalArgumentException> {
            recovery.pinPayload(id, "Checkpoint", checkpoint.checkpointId, 3) { error("Must not regenerate") }
        }
        assertEquals(listOf(pending), store.pendingOperations())
        assertEquals(receipt, db.appSyncNativeCompletionQueries.getForSession(id).executeAsOne())
    }

    @Test fun interruptedFinalCleanupRollsBackReceiptPayloadDeletionAndCompletionTogether() = fixture {
        val (recovery, id) = stageNativeCheckpoint()
        val proof = recovery.nativeCheckpointForActivation(id)
        assertIs<AppSyncCanonicalActivationResult.Applied>(activator().activate(proof))
        recovery.beginNativeCheckpointCleanup(id, now)
        val payload = db.appSyncOperationQueries.getRecoveryPayload(id).executeAsOne()
        val segments = recovery.segmentWrites(id)
        assertFailsWith<IllegalStateException> {
            db.transaction {
                me.thenano.yamibo.yamibo_app.repository.appsync.cleanup.AppSyncCanonicalLocalPruner(db, state)
                    .pruneRecoveryCheckpoint(id, now)
                assertNull(db.appSyncOperationQueries.getRecoveryPayload(id).executeAsOneOrNull())
                assertNotNull(db.appSyncNativeCompletionQueries.getForSession(id).executeAsOneOrNull())
                error("interrupt final transaction")
            }
        }
        assertEquals(AppSyncRecoveryPhase.Cleaning, recovery.session(id)?.phase)
        assertEquals(payload, db.appSyncOperationQueries.getRecoveryPayload(id).executeAsOne())
        assertEquals(segments, recovery.segmentWrites(id))
        assertNull(db.appSyncNativeCompletionQueries.getForSession(id).executeAsOneOrNull())
        assertIs<AppSyncCanonicalActivationResult.Applied>(activator().activateRecovery(SqlDelightAppSyncRecoveryStore(db), id))
        assertEquals(AppSyncRecoveryPhase.Completed, recovery.session(id)?.phase)
    }

    @Test fun nativeCompletionReceiptExpiresWithoutRemovingCurrentCanonicalStateOrPendingEdits() = fixture {
        val pending = append()
        val (recovery, id) = stageNativeCheckpoint()
        activator().activateRecovery(recovery, id)
        val head = state.read(account.value)
        val retention = 30L * 24 * 60 * 60 * 1000
        db.appSyncLocalPruneQueries.recordAudit(account.value, "a".repeat(64), now)
        recovery.expireCompletedRecoveryMetadata(now + retention - 1)
        assertNotNull(db.appSyncNativeCompletionQueries.getForSession(id).executeAsOneOrNull())
        assertEquals(1, db.appSyncLocalPruneQueries.getAudit(account.value).executeAsList().size)
        recovery.expireCompletedRecoveryMetadata(now + retention)
        assertNull(db.appSyncNativeCompletionQueries.getForSession(id).executeAsOneOrNull())
        assertNull(recovery.session(id))
        assertEquals(head, state.read(account.value))
        assertEquals(listOf(pending), store.pendingOperations())
        assertTrue(db.appSyncLocalPruneQueries.getAudit(account.value).executeAsList().isEmpty())
        recovery.expireCompletedRecoveryMetadata(now + retention + 1)
    }

    @Test fun nativeReceiptMigrationDoesNotInventCompletionEvidence() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            driver.execute(null, "CREATE TABLE AppSyncRecoverySession(sessionId TEXT PRIMARY KEY)", 0)
            Database.Schema.migrate(driver, oldVersion = 54, newVersion = 55)
            assertNull(Database(driver).appSyncNativeCompletionQueries.getForSession("missing").executeAsOneOrNull())
        }
    }

    @Test fun checkpointCleanupResumesBatchesWithoutReplayingSettingsOrDeletingLaterEdits() = fixture {
        val sources = List(260) { append("18") }
        val cp = assertIs<AppSyncCanonicalPendingMergeResult.Ready>(AppSyncCanonicalPendingMerge()
            .prepare(checkpoint, sources, "covered", 10)).checkpoint
        store.markAcknowledged(sources.map { it.operationId }.toSet(), 16)
        val (recovery, id) = stageNativeCheckpoint(document = cp)
        val first = assertIs<AppSyncCanonicalActivationResult.Applied>(activator().activateRecovery(recovery, id))
        assertEquals(128, first.removedLocalRows)
        assertTrue(first.cleanupPending)
        assertEquals(AppSyncRecoveryPhase.Cleaning, recovery.session(id)?.phase)
        assertNull(recovery.session(id)?.completedAtEpochMillis)
        val later = append("22")
        state.recordLocalBatch(account.value, listOf(later))
        preferences.values["novelreadersettings.fontsize"] = 22
        AppSyncRecoveryAttempts(recovery, { now }).recordFailure(id, AppSyncRecoveryFailureCategory.Network)
        assertEquals(1L, recovery.session(id)?.retryCount)
        now = assertNotNull(recovery.session(id)?.nextRetryAtEpochMillis)
        val restarted = SqlDelightAppSyncRecoveryStore(db)
        val second = assertIs<AppSyncCanonicalActivationResult.Applied>(activator().activateRecovery(restarted, id))
        assertEquals(128, second.removedLocalRows)
        assertTrue(second.cleanupPending)
        assertEquals(0L, restarted.session(id)?.retryCount)
        assertNull(restarted.session(id)?.nextRetryAtEpochMillis)
        val last = assertIs<AppSyncCanonicalActivationResult.Applied>(activator().activateRecovery(restarted, id))
        assertEquals(4, last.removedLocalRows)
        assertFalse(last.cleanupPending)
        assertEquals(AppSyncRecoveryPhase.Completed, restarted.session(id)?.phase)
        assertEquals(listOf(later), store.pendingOperations())
        assertEquals(22, preferences.values["novelreadersettings.fontsize"])
        assertEquals(260L, db.appSyncLocalPruneQueries.getAudit(account.value).executeAsOne().removedRows)
        assertEquals(0, assertIs<AppSyncCanonicalActivationResult.Applied>(activator().activateRecovery(restarted, id)).removedLocalRows)
    }

    @Test fun cancelledCoordinatorRetainsCleaningAndNextRunDrainsAllRemainingBatches() = fixture {
        val sources = List(260) { append("18") }
        val cp = assertIs<AppSyncCanonicalPendingMergeResult.Ready>(AppSyncCanonicalPendingMerge()
            .prepare(checkpoint, sources, "covered", 10)).checkpoint
        store.markAcknowledged(sources.map { it.operationId }.toSet(), 16)
        val (recovery, id) = stageNativeCheckpoint(document = cp)
        val continuation = nativeContinuation(recovery)
        val cloud = AppSyncCanonicalCloudPlan.Ready(verified(cp), AppSyncCanonicalOperationBlock(account.value, emptyList()), emptyList())
        kotlinx.coroutines.runBlocking {
            val work = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                continuation.resume(account, io.github.littlesurvival.dto.value.FormHash("test"), cloud)
            }
            val immediate = if (work.isCompleted) work.await() else null
            assertEquals(AppSyncRecoveryPhase.Cleaning, recovery.session(id)?.phase, immediate.toString())
            work.cancel()
            work.join()
            assertTrue(work.isCancelled)
            assertEquals(132, store.allOutboxOperations().size)
            assertEquals(0L, recovery.session(id)?.retryCount)
            assertNull(recovery.session(id)?.nextRetryAtEpochMillis)
            assertIs<OperationSyncResult.Converged>(nativeContinuation(SqlDelightAppSyncRecoveryStore(db))
                .resume(account, io.github.littlesurvival.dto.value.FormHash("test"), cloud))
        }
        assertEquals(AppSyncRecoveryPhase.Completed, recovery.session(id)?.phase)
        assertTrue(store.allOutboxOperations().isEmpty())
        assertEquals(260L, db.appSyncLocalPruneQueries.getAudit(account.value).executeAsOne().removedRows)
    }
    @Test fun verifiedRemoteCoveragePrunesOnlyCoveredAcknowledgedPayloadAfterCanonicalRebuild() = fixture {
        val excluded = store.appendLocalOperation(account, SyncDomainId("settings"), SyncEntityId("future.private.cache"), 1,
            SyncOperationKind.Put, mapOf("type" to "string", "value" to "合成快取".repeat(20_000)),
            store.causalContext(), 1, SyncOperationOrigin.UserAction)
        val cp = checkpoint.copy(coverage = mapOf(excluded.replicaKey.stableKey to excluded.sequence.value))
        store.markAcknowledged(setOf(excluded.operationId), 2)
        val later = append("22")
        val uncovered = append("22")
        store.markAcknowledged(setOf(uncovered.operationId), 3)
        val result = assertIs<AppSyncCanonicalActivationResult.Applied>(activator().activate(verified(cp)))
        assertEquals(1, result.removedLocalRows)
        assertTrue(result.removedLocalPayloadBytes >= 240_000)
        assertNull(store.outboxOperation(excluded.operationId.value))
        assertEquals(listOf(later), store.pendingOperations())
        assertNotNull(store.outboxOperation(uncovered.operationId.value))
        assertEquals(22, preferences.values["novelreadersettings.fontsize"])
        assertTrue(state.read(account.value)!!.entities.none { it.entityId == excluded.entityId.value })
        val audit = db.appSyncLocalPruneQueries.getAudit(account.value).executeAsOne()
        assertEquals(result.removedLocalPayloadBytes, audit.removedPayloadBytes)
        assertEquals(1L, audit.removedRows)
        val again = assertIs<AppSyncCanonicalActivationResult.Applied>(activator().activate(verified(cp)))
        assertEquals(0, again.removedLocalRows)
        assertEquals(audit, db.appSyncLocalPruneQueries.getAudit(account.value).executeAsOne())
    }

    @Test fun localPruneRequiresInstalledIndexEvidenceAndReconciledSettings() = fixture {
        val source = append()
        val prepared = assertIs<AppSyncCanonicalPendingMergeResult.Ready>(AppSyncCanonicalPendingMerge()
            .prepare(checkpoint, listOf(source), "covered", 10)).checkpoint
        val proof = verified(prepared)
        val pruner = me.thenano.yamibo.yamibo_app.repository.appsync.cleanup.AppSyncCanonicalLocalPruner(db, state)
        store.markAcknowledged(setOf(source.operationId), 16)
        assertFailsWith<IllegalArgumentException> { pruner.prune(proof, now) }
        preferences.fail = true
        assertFalse(assertIs<AppSyncCanonicalActivationResult.Applied>(activator().activate(proof)).settingsReconciled)
        assertFailsWith<IllegalArgumentException> { pruner.prune(proof, now) }
        assertNotNull(store.outboxOperation(source.operationId.value))
        assertTrue(db.appSyncLocalPruneQueries.getAudit(account.value).executeAsList().isEmpty())
        preferences.fail = false
        assertEquals(1, assertIs<AppSyncCanonicalActivationResult.Applied>(activator().activate(proof)).removedLocalRows)
    }

    @Test fun boundedLocalPruneRollsBackWithAuditAndResumesWithoutDuplicateAccounting() = fixture {
        val sources = listOf(append("18"), append("20"), append("22"))
        val cp = assertIs<AppSyncCanonicalPendingMergeResult.Ready>(AppSyncCanonicalPendingMerge()
            .prepare(checkpoint, sources, "covered", 10)).checkpoint
        val proof = verified(cp)
        assertIs<AppSyncCanonicalActivationResult.Applied>(activator().activate(proof))
        store.markAcknowledged(sources.map { it.operationId }.toSet(), 16)
        val pruner = me.thenano.yamibo.yamibo_app.repository.appsync.cleanup.AppSyncCanonicalLocalPruner(db, state)
        assertFailsWith<IllegalStateException> {
            db.transaction {
                assertEquals(1, pruner.prune(proof, now, 1).removedRows)
                error("interrupt transaction")
            }
        }
        assertEquals(3, store.allOutboxOperations().size)
        assertTrue(db.appSyncLocalPruneQueries.getAudit(account.value).executeAsList().isEmpty())
        repeat(3) { assertEquals(1, pruner.prune(proof, now + it, 1).removedRows) }
        assertEquals(0, pruner.prune(proof, now, 1).removedRows)
        assertTrue(store.allOutboxOperations().isEmpty())
        assertEquals(3L, db.appSyncLocalPruneQueries.getAudit(account.value).executeAsOne().removedRows)
        assertEquals(22, preferences.values["novelreadersettings.fontsize"])
        pruner.prune(proof, now + me.thenano.yamibo.yamibo_app.repository.appsync.cleanup.AppSyncCanonicalLocalPruner.RETENTION_MILLIS)
        assertTrue(db.appSyncLocalPruneQueries.getAudit(account.value).executeAsList().isEmpty())
    }

    @Test fun activeRecoveryProtectsSourcesEvenWhenRemoteCheckpointCoversThem() = fixture {
        val source = append()
        val cp = assertIs<AppSyncCanonicalPendingMergeResult.Ready>(AppSyncCanonicalPendingMerge()
            .prepare(checkpoint, listOf(source), "covered", 10)).checkpoint
        val recovery = SqlDelightAppSyncRecoveryStore(db)
        recovery.createOrResume(account, setOf(source.operationId.value), "active-source", 1)
        store.markAcknowledged(setOf(source.operationId), 16)
        val result = assertIs<AppSyncCanonicalActivationResult.Applied>(activator().activate(verified(cp)))
        assertEquals(0, result.removedLocalRows)
        assertNotNull(store.outboxOperation(source.operationId.value))
        assertTrue(db.appSyncLocalPruneQueries.getAudit(account.value).executeAsList().isEmpty())
    }

    @Test fun corruptedHeadOrWrongCheckpointEvidenceCannotAuthorizeLocalPruning() = fixture {
        val source = append()
        val cp = assertIs<AppSyncCanonicalPendingMergeResult.Ready>(AppSyncCanonicalPendingMerge()
            .prepare(checkpoint, listOf(source), "covered", 10)).checkpoint
        val proof = verified(cp)
        activator().activate(proof)
        store.markAcknowledged(setOf(source.operationId), 16)
        val pruner = me.thenano.yamibo.yamibo_app.repository.appsync.cleanup.AppSyncCanonicalLocalPruner(db, state)
        assertFailsWith<IllegalArgumentException> { pruner.prune(verified(cp.copy(checkpointId = "other")), now) }
        val row = db.appSyncCanonicalStateQueries.getState().executeAsOne()
        db.appSyncCanonicalStateQueries.putState(row.accountBinding, row.checkpointId, row.canonicalPayload, "0".repeat(64))
        assertFailsWith<IllegalArgumentException> { pruner.prune(proof, now) }
        assertNotNull(store.outboxOperation(source.operationId.value))
        assertTrue(db.appSyncLocalPruneQueries.getAudit(account.value).executeAsList().isEmpty())
    }

    @Test fun pruneAuditMigrationCreatesNoInventedDeletionEvidence() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            Database.Schema.migrate(driver, oldVersion = 53, newVersion = 54)
            Database.Schema.migrate(driver, oldVersion = 54, newVersion = 57)
            assertTrue(Database(driver).appSyncLocalPruneQueries.getAudit(account.value).executeAsList().isEmpty())
        }
    }

    @Test fun engineResumesCommittedNativeRecoveryWhenWritesAreDisabledAndDoesNotRepublish() = fixture {
        val source = append()
        val (recovery, id) = stageNativeJournal(source)
        val continuation = nativeContinuation(recovery)
        val cloud = AppSyncJournalLoadResult.Success(emptyList(), verifiedCanonicalCheckpoints = listOf(verified()))
        val result = assertIs<OperationSyncResult.Converged>(synchronize(cloud, continuation))
        assertEquals(1, result.acknowledgedLocalCount)
        assertEquals(AppSyncRecoveryPhase.Completed, recovery.session(id)?.phase)
        assertEquals(18, preferences.values["novelreadersettings.fontsize"])
        assertNotNull(state.read(account.value))
        assertNull(db.appSyncOperationQueries.getRunLease().executeAsOneOrNull())
        assertFalse(continuation.hasPending(account))
    }

    @Test fun nativeWriteGateNeedsExplicitResumeAndLegacyCloudCannotBypassIt() = fixture {
        val source = append()
        val (recovery, id) = stageNativeCheckpoint(committed = false)
        val continuation = nativeContinuation(recovery)
        val cloud = AppSyncJournalLoadResult.Success(emptyList(), verifiedCanonicalCheckpoints = listOf(verified()))
        assertIs<OperationSyncResult.PausedProvider>(synchronize(cloud, continuation))
        assertEquals(AppSyncRecoveryPhase.NeedsAttention, recovery.session(id)?.phase)
        assertEquals("native-compatibility", recovery.session(id)?.lastErrorCategory)
        assertEquals(0L, recovery.session(id)?.retryCount)
        assertNull(state.read(account.value))
        val reads = loadCalls
        assertIs<OperationSyncResult.PausedProvider>(synchronize(cloud, continuation))
        assertEquals(reads, loadCalls)
        recovery.resumeRetryExhaustedRecovery(account, now)
        assertEquals(AppSyncRecoveryPhase.CommittingIndex, recovery.session(id)?.phase)
        assertIs<OperationSyncResult.PausedProvider>(synchronize(AppSyncJournalLoadResult.Success(emptyList()), continuation))
        assertEquals("native-cloud-validation", recovery.session(id)?.lastErrorCategory)
        assertEquals(listOf(source), store.pendingOperations())
        assertNull(state.read(account.value))
        assertFalse(recovery.session(id)!!.indexCommitted)
    }

    @Test fun nativeCloudReadFailuresRespectDurableDeadlineAndExhaustionBeforeReadingAgain() = fixture {
        append()
        val (recovery, id) = stageNativeCheckpoint()
        val continuation = nativeContinuation(recovery)
        val failure = AppSyncJournalLoadResult.RetryableFailure("offline")
        repeat(3) { attempt ->
            assertIs<OperationSyncResult.RetryScheduled>(synchronize(failure, continuation))
            assertEquals((attempt + 1).toLong(), recovery.session(id)?.retryCount)
            val reads = loadCalls
            if (attempt < 2) {
                assertIs<OperationSyncResult.RetryScheduled>(synchronize(failure, continuation))
                assertEquals(reads, loadCalls)
                now = assertNotNull(recovery.session(id)?.nextRetryAtEpochMillis)
            }
        }
        assertEquals(AppSyncRecoveryPhase.NeedsAttention, recovery.session(id)?.phase)
        val reads = loadCalls
        assertTrue(forcedLoads.all { it })
        assertIs<OperationSyncResult.PausedProvider>(synchronize(failure, continuation))
        assertEquals(reads, loadCalls)
        assertNull(state.read(account.value))
        assertNull(db.appSyncOperationQueries.getRunLease().executeAsOneOrNull())
    }

    @Test fun nativeGateExpiringInsideCommitRemainsExplicitlyResumableWithoutChargingPayloadFailure() = fixture {
        append()
        val (recovery, id) = stageNativeCheckpoint(committed = false)
        var gateChecks = 0
        val continuation = nativeContinuation(recovery) { ++gateChecks <= 2 }
        val cloud = AppSyncJournalLoadResult.Success(emptyList(), verifiedCanonicalCheckpoints = listOf(verified()))
        assertIs<OperationSyncResult.PausedProvider>(synchronize(cloud, continuation))
        assertEquals(AppSyncRecoveryPhase.NeedsAttention, recovery.session(id)?.phase)
        assertEquals("native-compatibility", recovery.session(id)?.lastErrorCategory)
        assertEquals(0L, recovery.session(id)?.retryCount)
        recovery.resumeRetryExhaustedRecovery(account, now)
        assertEquals(AppSyncRecoveryPhase.CommittingIndex, recovery.session(id)?.phase)
        assertNull(recovery.session(id)?.lastErrorCategory)
        assertNull(state.read(account.value))
    }

    @Test fun unexpectedNativeReadExceptionAlsoPersistsOneFailureAndReleasesLease() = fixture {
        append()
        val (recovery, id) = stageNativeCheckpoint()
        val continuation = nativeContinuation(recovery)
        loadThrows = true
        val cloud = AppSyncJournalLoadResult.Success(emptyList())
        assertIs<OperationSyncResult.RetryScheduled>(synchronize(cloud, continuation))
        assertEquals(1L, recovery.session(id)?.retryCount)
        assertNotNull(recovery.session(id)?.nextRetryAtEpochMillis)
        assertNull(db.appSyncOperationQueries.getRunLease().executeAsOneOrNull())
        val reads = loadCalls
        assertIs<OperationSyncResult.RetryScheduled>(synchronize(cloud, continuation))
        assertEquals(reads, loadCalls)
        assertEquals(1L, recovery.session(id)?.retryCount)
    }

    @Test fun journalRecoveryMergesOtherDeviceStateAndWaitsForSettingsBeforeAcknowledgingOnlyFrozenSources() = fixture {
        val published = append()
        val (recovery, id) = stageNativeJournal(published)
        val latest = remoteCheckpoint(published)
        val cloud = AppSyncCanonicalCloudPlan.Ready(verified(latest), AppSyncCanonicalOperationBlock(account.value, emptyList()), emptyList())
        preferences.fail = true
        assertIs<AppSyncSegmentedJournalCommitResult.Retryable>(commitNative(recovery, id, cloud, 100))
        assertEquals(AppSyncRecoveryPhase.ActivatingLocal, recovery.session(id)?.phase)
        assertEquals(listOf(published), store.pendingOperations())
        assertEquals(latest.entities, state.read(account.value)?.entities)
        val later = append("22")
        preferences.fail = false
        val completed = assertIs<AppSyncSegmentedJournalCommitResult.Verified>(commitNative(
            SqlDelightAppSyncRecoveryStore(db), id, cloud, assertNotNull(recovery.session(id)?.nextRetryAtEpochMillis)))
        assertEquals(setOf(published.operationId.value), completed.acknowledgedOperationIds)
        assertEquals(AppSyncRecoveryPhase.Completed, recovery.session(id)?.phase)
        assertEquals(listOf(later), store.pendingOperations())
        assertEquals(AppSyncOperationLifecycle.Acknowledged, store.allOutboxOperations().single { it.first == published }.second)
        assertEquals(22, preferences.values["novelreadersettings.fontsize"])
        assertEquals(latest.coverage, store.verifiedCheckpoints().single().coverage.asStableMap())
        assertEquals(2L, state.read(account.value)?.coverage?.get(published.replicaKey.stableKey))
        preferences.values["novelreadersettings.fontsize"] = 30
        assertIs<AppSyncCanonicalActivationResult.Applied>(activator().activateJournalRecovery(recovery, id, cloud))
        assertEquals(30, preferences.values["novelreadersettings.fontsize"])
    }

    @Test fun fallbackActivationRetriesSettingsAndAcknowledgesOnlyFrozenSources() = fixture {
        val published = append()
        val (recovery, id) = stageNativeJournal(published, fallback = true)
        val frozen = recovery.sanitizedV2Payload(id)
        val cloud = AppSyncCanonicalCloudPlan.Ready(verified(), AppSyncCanonicalOperationBlock(account.value, emptyList()), emptyList())
        preferences.fail = true
        assertFalse(assertIs<AppSyncCanonicalActivationResult.Applied>(
            activator().activateJournalRecovery(recovery, id, cloud)).settingsReconciled)
        assertEquals(AppSyncRecoveryPhase.ActivatingLocal, recovery.session(id)?.phase)
        assertEquals(listOf(published), store.pendingOperations())
        val later = append("22")
        preferences.fail = false
        val restarted = SqlDelightAppSyncRecoveryStore(db)
        assertTrue(assertIs<AppSyncCanonicalActivationResult.Applied>(
            activator().activateJournalRecovery(restarted, id, cloud)).settingsReconciled)
        assertEquals(AppSyncRecoveryPhase.Completed, restarted.session(id)?.phase)
        assertEquals(listOf(later), store.pendingOperations())
        assertEquals(AppSyncOperationLifecycle.Acknowledged, store.outboxOperation(published.operationId.value)?.second)
        assertEquals(22, preferences.values["novelreadersettings.fontsize"])
        assertEquals(frozen, restarted.sanitizedV2Payload(id))
        val session = assertNotNull(restarted.session(id))
        val root = db.appSyncOperationQueries.getRemoteBlog("journal-root:${session.generationId}").executeAsOne()
        assertEquals(session.rootFingerprint, root.fingerprint)
        assertEquals(16, root.fingerprint?.length)
        preferences.values["novelreadersettings.fontsize"] = 30
        assertIs<AppSyncCanonicalActivationResult.Applied>(activator().activateJournalRecovery(restarted, id, cloud))
        assertEquals(30, preferences.values["novelreadersettings.fontsize"])
        assertEquals(listOf(later), store.pendingOperations())
    }

    @Test fun fallbackActivationRejectsTamperedCompanionWithoutAcknowledgement() = fixture {
        val published = append()
        val (recovery, id) = stageNativeJournal(published, fallback = true)
        driver.execute(null, "UPDATE AppSyncV2FallbackPayload SET envelope = 'tampered' WHERE sessionId = ?", 1) {
            bindString(0, id)
        }
        val cloud = AppSyncCanonicalCloudPlan.Ready(verified(), AppSyncCanonicalOperationBlock(account.value, emptyList()), emptyList())
        assertIs<AppSyncCanonicalActivationResult.NeedsAttention>(activator().activateJournalRecovery(recovery, id, cloud))
        assertNull(state.read(account.value))
        assertTrue(preferences.values.isEmpty())
        assertEquals(listOf(published), store.pendingOperations())
        assertEquals(AppSyncRecoveryPhase.ActivatingLocal, recovery.session(id)?.phase)
    }

    @Test fun conflictingJournalRecoveryEvidenceCannotWriteProjectionOrAcknowledge() = fixture {
        val published = append()
        val (recovery, id) = stageNativeJournal(published)
        val original = recovery.nativeJournalForActivation(id).document.block.operations.single()
        val conflicting = assertIs<AppSyncCanonicalOperationImport.Accepted>(AppSyncCanonicalOperationImporter().import(
            account.value, published.copy(fields = published.fields + ("value" to "30")))).operation
        assertNotEquals(original, conflicting)
        val cloud = AppSyncCanonicalCloudPlan.Ready(verified(), AppSyncCanonicalOperationBlock(account.value, listOf(conflicting)), emptyList())
        assertIs<AppSyncCanonicalActivationResult.NeedsAttention>(activator().activateJournalRecovery(recovery, id, cloud))
        assertNull(state.read(account.value))
        assertTrue(preferences.values.isEmpty())
        assertTrue(store.verifiedCheckpoints().isEmpty())
        assertEquals(listOf(published), store.pendingOperations())
        assertEquals(AppSyncRecoveryPhase.ActivatingLocal, recovery.session(id)?.phase)
    }

    @Test fun journalObservedHistoryMustBePresentBeforeAnyProjectionOrAcknowledgement() = fixture {
        val published = append()
        val (recovery, id) = stageNativeJournal(published, observed = mapOf("missing:epoch" to 7L))
        val cloud = AppSyncCanonicalCloudPlan.Ready(verified(), AppSyncCanonicalOperationBlock(account.value, emptyList()), emptyList())
        assertIs<AppSyncCanonicalActivationResult.NeedsAttention>(activator().activateJournalRecovery(recovery, id, cloud))
        assertNull(state.read(account.value))
        assertTrue(store.verifiedCheckpoints().isEmpty())
        assertEquals(listOf(published), store.pendingOperations())
        assertEquals(AppSyncRecoveryPhase.ActivatingLocal, recovery.session(id)?.phase)
    }

    @Test fun engineRecoveryHookRunsUnderLeaseBeforeGenericActivationAndReleasesOnReturn() = fixture {
        val pending = append()
        var calls = 0
        val cloud = AppSyncJournalLoadResult.Success(emptyList(), verifiedCanonicalCheckpoints = listOf(verified()))
        val expected = OperationSyncResult.RetryScheduled("native recovery continuation")
        assertEquals(expected, synchronize(cloud) { binding, _, plan ->
            calls++
            assertEquals(account, binding)
            assertEquals(checkpoint.checkpointId, plan.checkpoint.document.checkpointId)
            assertNotNull(db.appSyncOperationQueries.getRunLease().executeAsOneOrNull())
            assertNull(state.read(account.value))
            expected
        })
        assertEquals(1, calls)
        assertNull(db.appSyncOperationQueries.getRunLease().executeAsOneOrNull())
        assertNull(state.read(account.value))
        assertEquals(listOf(pending), store.pendingOperations())
    }

    @Test fun invalidCanonicalCloudCannotInvokeRecoveryHookAndNullHookKeepsReaderBehavior() = fixture {
        val pending = append()
        val cloud = AppSyncJournalLoadResult.Success(emptyList(), verifiedCanonicalCheckpoints = listOf(verified()))
        var calls = 0
        assertIs<OperationSyncResult.PausedProvider>(synchronize(cloud.copy(canonicalReadIssues = listOf("corrupt"))) { _, _, _ ->
            calls++; error("Invalid cloud must not reach recovery")
        })
        assertEquals(0, calls)
        assertNull(state.read(account.value))
        store.updateState(AppSyncInstallationState.Active)
        assertIs<OperationSyncResult.PausedProvider>(synchronize(cloud) { _, _, _ -> calls++; null })
        assertEquals(1, calls)
        assertNotNull(state.read(account.value))
        assertEquals(listOf(pending), store.pendingOperations())
    }
    @Test fun nativeCoordinatorRetriesCheckpointSettingsWithoutRepeatingRemotePublication() = fixture {
        val pending = append()
        val latest = remoteCheckpoint(pending)
        val cloud = AppSyncCanonicalCloudPlan.Ready(verified(latest), AppSyncCanonicalOperationBlock(account.value, emptyList()), emptyList())
        val (recovery, id) = stageNativeCheckpoint()
        var now = 100L
        val provider = object : AppSyncBlogProvider {
            override suspend fun fetchMyBlogs(blogClassId: io.github.littlesurvival.dto.value.BlogClassId?, page: Int): Nothing = error("No remote scan during activation")
            override suspend fun fetchBlog(blogId: io.github.littlesurvival.dto.value.BlogId): Nothing = error("No remote read during activation")
            override suspend fun submitBlog(request: AppSyncBlogWriteRequest): Nothing = error("No remote write during activation")
            override suspend fun deleteBlog(request: AppSyncBlogDeleteRequest): Nothing = error("No cleanup during activation")
        }
        val publisher = AppSyncV3SegmentPublisher(provider, recovery, { now })
        val committer = AppSyncV3IndexCommitter(provider, recovery, publisher, { now })
        fun run() = kotlinx.coroutines.runBlocking {
            AppSyncV3CommitCoordinator(committer, recovery, { now },
                AppSyncCanonicalCheckpointActivator(db, store, state, materializer, { now }), { true })
                .commit(id, "unused after verified index", checkpoint.checkpointId,
                    AppSyncBlogClassSelection.Existing(io.github.littlesurvival.dto.value.BlogClassId(7)),
                    io.github.littlesurvival.dto.value.FormHash("test"), cloud)
        }
        preferences.fail = true
        assertIs<AppSyncSegmentedJournalCommitResult.Retryable>(run())
        assertEquals(AppSyncRecoveryPhase.ActivatingLocal, recovery.session(id)?.phase)
        assertEquals(1L, recovery.session(id)?.retryCount)
        now = assertNotNull(recovery.session(id)?.nextRetryAtEpochMillis)
        preferences.fail = false
        val result = assertIs<AppSyncSegmentedJournalCommitResult.Verified>(run())
        assertTrue(result.acknowledgedOperationIds.isEmpty())
        assertEquals(AppSyncRecoveryPhase.Completed, recovery.session(id)?.phase)
        assertEquals(listOf(pending), store.pendingOperations())
        assertEquals(26, preferences.values["novelreadersettings.fontsize"])
        assertEquals(setOf(checkpoint.checkpointId, latest.checkpointId), store.verifiedCheckpoints().map { it.checkpointId }.toSet())
        assertEquals(latest.coverage, store.verifiedCheckpoints().single { it.checkpointId == latest.checkpointId }.coverage.asStableMap())
    }

    @Test fun staleRecoveryCannotReplaceAnAlreadyActivatedRemoteHeadWithoutLatestCloudEvidence() = fixture {
        val pending = append()
        val latest = remoteCheckpoint(pending)
        assertIs<AppSyncCanonicalActivationResult.Applied>(activator().activate(verified(latest)))
        val (recovery, id) = stageNativeCheckpoint()
        val before = state.read(account.value)
        val evidence = store.verifiedCheckpoints()
        assertIs<AppSyncCanonicalActivationResult.NeedsAttention>(activator().activateRecovery(recovery, id))
        assertEquals(before, state.read(account.value))
        assertEquals(evidence, store.verifiedCheckpoints())
        assertEquals(26, preferences.values["novelreadersettings.fontsize"])
        assertEquals(AppSyncRecoveryPhase.ActivatingLocal, recovery.session(id)?.phase)
        val cloud = AppSyncCanonicalCloudPlan.Ready(verified(latest), AppSyncCanonicalOperationBlock(account.value, emptyList()), emptyList())
        assertIs<AppSyncCanonicalActivationResult.Applied>(activator().activateRecovery(recovery, id, cloud))
        assertEquals(AppSyncRecoveryPhase.Completed, recovery.session(id)?.phase)
        assertEquals(before?.coverage, state.read(account.value)?.coverage)
        assertEquals(listOf(pending), store.pendingOperations())
    }
    @Test fun nativeRecoveryWaitsForPreferencesAndPreservesEditsAddedDuringRetry() = fixture {
        val pending = append()
        val (recovery, id) = stageNativeCheckpoint()
        preferences.fail = true
        val first = assertIs<AppSyncCanonicalActivationResult.Applied>(activator().activateRecovery(recovery, id))
        assertFalse(first.settingsReconciled)
        assertEquals(AppSyncRecoveryPhase.ActivatingLocal, recovery.session(id)?.phase)
        assertNotNull(state.read(account.value))
        assertEquals(listOf(pending), store.pendingOperations())
        val later = append("22")
        assertIs<AppSyncCanonicalLocalUpdate.Recorded>(state.recordLocalBatch(account.value, listOf(later)))
        preferences.fail = false
        val restarted = SqlDelightAppSyncRecoveryStore(db)
        assertTrue(assertIs<AppSyncCanonicalActivationResult.Applied>(activator().activateRecovery(restarted, id)).settingsReconciled)
        assertEquals(AppSyncRecoveryPhase.Completed, restarted.session(id)?.phase)
        assertEquals(22, preferences.values["novelreadersettings.fontsize"])
        assertEquals(listOf(pending, later), store.pendingOperations())
        assertEquals(checkpoint.coverage, store.verifiedCheckpoints().single().coverage.asStableMap())
        val head = db.appSyncCanonicalStateQueries.getState().executeAsOne()
        preferences.values["novelreadersettings.fontsize"] = 26
        assertIs<AppSyncCanonicalActivationResult.Applied>(activator().activateRecovery(restarted, id))
        assertEquals(26, preferences.values["novelreadersettings.fontsize"])
        val replayed = db.appSyncCanonicalStateQueries.getState().executeAsOne()
        assertEquals(head.checkpointId, replayed.checkpointId)
        assertEquals(head.canonicalSha256, replayed.canonicalSha256)
        assertContentEquals(head.canonicalPayload, replayed.canonicalPayload)
    }

    @Test fun nativeRecoveryRequiresCommittedIndexAndUnchangedInstallationBeforeProjection() = fixture {
        append()
        val (recovery, id) = stageNativeCheckpoint(committed = false)
        assertIs<AppSyncCanonicalActivationResult.NeedsAttention>(activator().activateRecovery(recovery, id))
        assertNull(state.read(account.value))
        assertTrue(store.verifiedCheckpoints().isEmpty())
        recovery.markNativeIndexCommitted(id, 400, assertNotNull(recovery.nativeIndexIntent(id)).body, 12)
        assertFailsWith<IllegalArgumentException> { recovery.beginNativeCheckpointCleanup(id, 20) }
        store.rotateDeviceEpoch(account, AppSyncInstallationState.Active)
        assertIs<AppSyncCanonicalActivationResult.NeedsAttention>(activator().activateRecovery(recovery, id))
        assertNull(state.read(account.value))
        assertEquals(AppSyncRecoveryPhase.ActivatingLocal, recovery.session(id)?.phase)
    }

    @Test fun activationGuardFailureCannotWriteProjectionOrCheckpointEvidence() = fixture {
        val pending = append()
        assertIs<AppSyncCanonicalActivationResult.NeedsAttention>(activator().activate(verified(), beforeDatabaseActivation = {
            error("Recovery changed before transaction")
        }))
        assertNull(state.read(account.value))
        assertTrue(store.verifiedCheckpoints().isEmpty())
        assertEquals(listOf(pending), store.pendingOperations())
    }
    @Test fun preferenceReconciliationRetainsReferencedDeletionProofs() = fixture {
        val delete = AppSyncCanonicalOperation("remote-device", "remote-epoch", 1, 1,
            "novelreadersettings.fontsize", 1, SyncOperationKind.Delete, 15,
            SyncOperationOrigin.UserAction, "proof", emptyMap(), emptyMap())
        val head = checkpoint.copy(coverage = mapOf("remote-device:remote-epoch" to 1L),
            entities = listOf(AppSyncCanonicalProjection(1, delete.entityId, 1, tombstone = delete)),
            authorizations = listOf(AppSyncCanonicalDeleteProof("proof", 1, "settings", 1, 100)))
        preferences.values[delete.entityId] = 18
        state.replace(account.value, head)
        assertTrue(state.reconcileSettings(account.value))
        assertFalse(delete.entityId in preferences.values)
        assertEquals(0L, db.appSyncCanonicalStateQueries.getState().executeAsOne().settingsReconciliationPending)
    }

    @Test fun failedPreferenceReconciliationSurvivesLocalEditsAndResumesFromLatestHead() = fixture {
        append()
        preferences.fail = true
        assertFalse(assertIs<AppSyncCanonicalActivationResult.Applied>(activator().activate(verified())).settingsReconciled)
        assertEquals(1L, db.appSyncCanonicalStateQueries.getState().executeAsOne().settingsReconciliationPending)
        val edit = append("22")
        assertIs<AppSyncCanonicalLocalUpdate.Recorded>(state.recordLocalBatch(account.value, listOf(edit)))
        assertEquals(1L, db.appSyncCanonicalStateQueries.getState().executeAsOne().settingsReconciliationPending)
        preferences.fail = false
        val restarted = SqlDelightCanonicalCheckpointState(db, DatabaseSyncDomainMaterializer(db, preferences))
        assertTrue(restarted.reconcileSettings(account.value))
        assertEquals(22, preferences.values["novelreadersettings.fontsize"])
        assertEquals(0L, db.appSyncCanonicalStateQueries.getState().executeAsOne().settingsReconciliationPending)
        // Once reconciled, a later ordinary preference edit must not replay an old mirror.
        preferences.values["novelreadersettings.fontsize"] = 26
        assertTrue(restarted.reconcileSettings(account.value))
        assertEquals(26, preferences.values["novelreadersettings.fontsize"])
    }

    @Test fun canonicalSnapshotAuditUsesTypedFieldsAndRepairsOnlyActualChanges() = fixture {
        val pending = append()
        activator().activate(verified())
        val planner = LocalProjectionRepairPlanner()
        val draft = LocalSyncOperationDraft(pending.domainId, pending.entityId, kind = SyncOperationKind.Put,
            fields = pending.fields + mapOf("value" to "018", "cache" to "local-only"))
        assertTrue(planner.plan(listOf(draft), assertNotNull(state.read(account.value))).isEmpty())
        val edited = draft.copy(fields = draft.fields + ("value" to "22"))
        val repairs = planner.plan(listOf(edited), assertNotNull(state.read(account.value)))
        assertEquals(1, repairs.size)
        store.appendLocalOperations(account, repairs, store.causalContext(), 25, SyncOperationOrigin.Migration) {
            assertIs<AppSyncCanonicalLocalUpdate.Recorded>(state.recordLocalBatch(account.value, it))
        }
        assertTrue(planner.plan(listOf(edited), assertNotNull(state.read(account.value))).isEmpty())
        assertEquals(2, store.pendingOperations().size)
        assertTrue(db.appSyncOperationQueries.getResolvedEntities().executeAsList().isEmpty())
    }

    @Test fun canonicalSnapshotAuditRejectsInvalidEssentialValuesAndDoesNotInferMissingRowDeletes() = fixture {
        val pending = append()
        activator().activate(verified())
        val planner = LocalProjectionRepairPlanner()
        val head = assertNotNull(state.read(account.value))
        val invalid = LocalSyncOperationDraft(pending.domainId, pending.entityId, kind = SyncOperationKind.Put,
            fields = pending.fields + ("value" to "not-an-integer"))
        assertFailsWith<IllegalArgumentException> { planner.plan(listOf(invalid), head) }
        assertTrue(planner.plan(emptyList(), head).isEmpty())
        assertEquals(head, state.read(account.value))
        assertEquals(listOf(pending), store.pendingOperations())
    }

    @Test fun engineActivatesVerifiedCanonicalCloudAndPreservesPendingEdits() = fixture {
        val pending = append()
        val cloud = AppSyncJournalLoadResult.Success(emptyList(), verifiedCanonicalCheckpoints = listOf(verified()))
        val result = synchronize(cloud)
        assertIs<OperationSyncResult.PausedProvider>(result)
        assertTrue(result.reason.contains("Canonical state applied"))
        assertNotNull(state.read(account.value))
        assertEquals(18, preferences.values["novelreadersettings.fontsize"])
        assertEquals(listOf(pending), store.pendingOperations())
        assertTrue(store.verifiedCheckpoints().single().coverage.asStableMap().isEmpty())
    }

    @Test fun engineCannotFallBackToLegacyOrEmptyCloudAfterCanonicalActivation() = fixture {
        append()
        assertIs<AppSyncCanonicalActivationResult.Applied>(activator().activate(verified()))
        val before = state.read(account.value)
        val outbox = store.allOutboxOperations()
        val result = assertIs<OperationSyncResult.PausedProvider>(synchronize(AppSyncJournalLoadResult.Success(emptyList())))
        assertTrue(result.reason.contains("verified v3 cloud base"))
        assertEquals(before, state.read(account.value))
        assertEquals(outbox, store.allOutboxOperations())
    }

    @Test fun engineDoesNotActivateInvalidCloudOrReportFailedSettingsAsApplied() = fixture {
        val pending = append()
        val invalid = AppSyncJournalLoadResult.Success(emptyList(), verifiedCanonicalCheckpoints = listOf(verified()),
            canonicalReadIssues = listOf("corrupt journal"))
        assertTrue(assertIs<OperationSyncResult.PausedProvider>(synchronize(invalid)).reason.contains("validation"))
        assertNull(state.read(account.value))
        assertTrue(store.verifiedCheckpoints().isEmpty())
        store.updateState(AppSyncInstallationState.Active)
        preferences.fail = true
        val valid = invalid.copy(canonicalReadIssues = emptyList())
        assertTrue(assertIs<OperationSyncResult.PausedProvider>(synchronize(valid)).reason.contains("settings reconciliation"))
        assertNotNull(state.read(account.value))
        assertEquals(listOf(pending), store.pendingOperations())
        store.updateState(AppSyncInstallationState.Active)
        preferences.fail = false
        assertTrue(assertIs<OperationSyncResult.PausedProvider>(synchronize(valid)).reason.contains("Canonical state applied"))
        assertEquals(18, preferences.values["novelreadersettings.fontsize"])
    }

    @Test fun laterNativeJournalAndPendingEditsCommitTogetherWithoutExpandingRemoteCheckpointCoverage() = fixture {
        val pending = append()
        val device = SyncDeviceId("remote-device")
        val epoch = SyncDeviceEpoch("remote-epoch")
        val remote = pending.copy(deviceId = device, deviceEpoch = epoch,
            operationId = SyncOperation.idFor(device, epoch, pending.sequence),
            fields = pending.fields + ("value" to "26"), createdAtEpochMillis = 100)
        val imported = assertIs<AppSyncCanonicalOperationImport.Accepted>(AppSyncCanonicalOperationImporter().import(account.value, remote))
        val block = AppSyncCanonicalOperationBlock(account.value, listOf(imported.operation))
        assertIs<AppSyncCanonicalActivationResult.Applied>(activator().activate(verified(), block))
        assertEquals(26, preferences.values["novelreadersettings.fontsize"])
        val local = assertNotNull(state.read(account.value))
        assertEquals(mapOf(remote.replicaKey.stableKey to 1L, pending.replicaKey.stableKey to 1L), local.coverage)
        assertEquals(local.coverage, store.causalContext().asStableMap())
        assertTrue(store.isApplied(remote.operationId))
        assertEquals(listOf(pending), store.pendingOperations())
        assertTrue(store.verifiedCheckpoints().single().coverage.asStableMap().isEmpty())
        assertIs<AppSyncCanonicalActivationResult.Applied>(activator().activate(verified(), block))
        assertEquals(local, state.read(account.value))
    }

    private val account = SyncAccountBinding("account")
    private val checkpoint = AppSyncCanonicalCheckpoint("remote", account.value, 10, emptyMap(), emptyList())
    private fun verified(cp: AppSyncCanonicalCheckpoint = checkpoint): AppSyncVerifiedCanonicalCheckpoint {
        val fingerprint = AppSyncCanonicalCheckpointCodec().encode(cp).sha256().hex()
        val index = AppSyncIndexEnvelopeCodec().encode(AppSyncIndexPayload(account,
            checkpoints = listOf(AppSyncIndexCheckpointReference(cp.checkpointId, 123, fingerprint)), updatedAtEpochMillis = 11))
        return assertNotNull(AppSyncVerifiedCanonicalCheckpoint.verify(account.value, 123, index,
            AppSyncV3DocumentCodec().encodeCheckpoint(cp)))
    }

    private class Preferences : SettingsStore {
        var fail = false
        val values = mutableMapOf<String, Any>()
        private fun write(key: String, value: Any) { check(!fail); values[key] = value }
        override fun getInt(key: String, defaultValue: Int) = values[key] as? Int ?: defaultValue
        override fun putInt(key: String, value: Int) = write(key, value)
        override fun getFloat(key: String, defaultValue: Float) = values[key] as? Float ?: defaultValue
        override fun putFloat(key: String, value: Float) = write(key, value)
        override fun getString(key: String, defaultValue: String) = values[key] as? String ?: defaultValue
        override fun putString(key: String, value: String) = write(key, value)
        override fun getBoolean(key: String, defaultValue: Boolean) = values[key] as? Boolean ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) = write(key, value)
        override fun remove(key: String) { check(!fail); values.remove(key) }
        override fun hasKey(key: String) = key in values
    }

    private inner class Fixture(val db: Database, val driver: JdbcSqliteDriver) {
        fun nativePublishingEnvironment(): Pair<AppSyncV3SegmentPublisherTest.Provider, AppSyncNativeRecoveryContinuation> {
            val provider = AppSyncV3SegmentPublisherTest.Provider()
            val selection = AppSyncBlogClassSelection.Existing(io.github.littlesurvival.dto.value.BlogClassId(7))
            val form = io.github.littlesurvival.dto.value.FormHash("test")
            val index = AppSyncIndexEnvelopeCodec().encode(AppSyncIndexPayload(account,
                checkpoints = listOf(AppSyncIndexCheckpointReference(checkpoint.checkpointId, 123, verified().fingerprint)), updatedAtEpochMillis = 11))
            provider.artifacts[124] = AppSyncBlogWriteRequest(io.github.littlesurvival.dto.value.BlogId(124), APP_SYNC_INDEX_TITLE, index, selection, form)
            provider.artifacts[123] = AppSyncBlogWriteRequest(io.github.littlesurvival.dto.value.BlogId(123),
                AppSyncJournalDefaults.checkpointTitle(checkpoint.checkpointId), AppSyncV3DocumentCodec().encodeCheckpoint(checkpoint), selection, form)
            val recovery = SqlDelightAppSyncRecoveryStore(db)
            val blogs = SqlDelightAppSyncRemoteBlogStore(db).also { it.saveClassId(account, selection.classId) }
            val starter = AppSyncNativeJournalStarter(db, store, recovery, state, activator(), { now }, { true })
            return provider to AppSyncNativeRecoveryContinuation(provider, store, recovery, blogs, activator(), { now }, { true }, starter)
        }
        fun retainJournalHistory(count: Int, fallback: Boolean = false): List<String> {
            val recovery = SqlDelightAppSyncRecoveryStore(db)
            val cloud = AppSyncCanonicalCloudPlan.Ready(verified(), AppSyncCanonicalOperationBlock(account.value, emptyList()), emptyList())
            repeat(count) {
                val (_, id) = stageNativeJournal(append(), fallback = fallback)
                assertIs<AppSyncCanonicalActivationResult.Applied>(activator().activateJournalRecovery(recovery, id, cloud))
            }
            val placeholder = recovery.createOrResumeSegmentedJournal(account, emptySet(), "next-after-retained", now)
            recovery.rollbackPreCommit(placeholder.sessionId)
            return db.appSyncRetainedJournalQueries.getForAccount(account.value).executeAsList()
        }
        var now = 20L
        var loadCalls = 0
        var loadThrows = false
        val forcedLoads = mutableListOf<Boolean>()
        val store = SqlDelightAppSyncOperationStore(db).also {
            it.initialize("database"); it.bindAccount(account, AppSyncInstallationState.Active)
        }
        val preferences = Preferences()
        val materializer = DatabaseSyncDomainMaterializer(db, preferences)
        val state = SqlDelightCanonicalCheckpointState(db, materializer)
        fun nativeContinuation(recovery: SqlDelightAppSyncRecoveryStore, canWrite: () -> Boolean = { false }): AppSyncNativeRecoveryContinuation {
            val provider = object : AppSyncBlogProvider {
                override suspend fun fetchMyBlogs(blogClassId: io.github.littlesurvival.dto.value.BlogClassId?, page: Int): Nothing = error("Unexpected native scan")
                override suspend fun fetchBlog(blogId: io.github.littlesurvival.dto.value.BlogId): Nothing = error("Unexpected native read")
                override suspend fun submitBlog(request: AppSyncBlogWriteRequest): Nothing = error("Unexpected native write")
                override suspend fun deleteBlog(request: AppSyncBlogDeleteRequest): Nothing = error("Unexpected native cleanup")
            }
            val blogs = SqlDelightAppSyncRemoteBlogStore(db)
            blogs.saveClassId(account, io.github.littlesurvival.dto.value.BlogClassId(7))
            return AppSyncNativeRecoveryContinuation(provider, store, recovery, blogs, activator(), { now }, canWrite)
        }
        fun commitNative(recovery: SqlDelightAppSyncRecoveryStore, id: String, cloud: AppSyncCanonicalCloudPlan.Ready,
            now: Long) = kotlinx.coroutines.runBlocking {
            val provider = object : AppSyncBlogProvider {
                override suspend fun fetchMyBlogs(blogClassId: io.github.littlesurvival.dto.value.BlogClassId?, page: Int): Nothing = error("No remote scan during activation")
                override suspend fun fetchBlog(blogId: io.github.littlesurvival.dto.value.BlogId): Nothing = error("No remote read during activation")
                override suspend fun submitBlog(request: AppSyncBlogWriteRequest): Nothing = error("No remote write during activation")
                override suspend fun deleteBlog(request: AppSyncBlogDeleteRequest): Nothing = error("No cleanup during activation")
            }
            val publisher = AppSyncV3SegmentPublisher(provider, recovery, { now })
            val committer = AppSyncV3IndexCommitter(provider, recovery, publisher, { now })
            AppSyncV3CommitCoordinator(committer, recovery, { now }, activator(), { true }).commit(id, "frozen", "identity",
                AppSyncBlogClassSelection.Existing(io.github.littlesurvival.dto.value.BlogClassId(7)),
                io.github.littlesurvival.dto.value.FormHash("test"), cloud)
        }
        fun stageNativeJournal(source: SyncOperation, observed: Map<String, Long> = emptyMap(), fallback: Boolean = false): Pair<SqlDelightAppSyncRecoveryStore, String> {
            val recovery = SqlDelightAppSyncRecoveryStore(db)
            val session = recovery.createOrResumeSegmentedJournal(account, setOf(source.operationId.value), "journal-source-${source.operationId.value}", 1)
            val imported = assertIs<AppSyncCanonicalOperationImport.Accepted>(AppSyncCanonicalOperationImporter().import(account.value, source))
            val identity = source.replicaKey.stableKey
            val journal = AppSyncCanonicalJournal(AppSyncCanonicalOperationBlock(account.value, listOf(imported.operation)),
                source.deviceId.value, source.deviceEpoch.value, session.targetWriterNonce.value,
                source.sequence.value, source.sequence.value, observed, if (fallback) listOf(AppSyncCanonicalAcknowledgement(checkpoint.checkpointId, checkpoint.coverage)) else emptyList(),
                1, 3, 3, "test", source.sequence.value)
            recovery.pinPayload(session.sessionId, "Journal", identity, 3) { AppSyncV3DocumentCodec().encodeJournal(identity, journal) }
            if (fallback) recovery.pinSanitizedV2Payload(session.sessionId) { true }
            recovery.startSegmentedJournal(session.sessionId, 2)
            recovery.saveSegmentIntent(session.sessionId, 0, 1, "segment", null)
            recovery.markSegmentVerified(session.sessionId, 0, "segment", 200, 3)
            recovery.transition(session.sessionId, AppSyncRecoveryPhase.PublishingSegments, AppSyncRecoveryPhase.PublishingRoot, 4)
            val rootFingerprint = "b".repeat(if (fallback) 16 else 64)
            recovery.pinNativeRootIntent(session.sessionId, rootFingerprint)
            recovery.markRootVerified(session.sessionId, 300, rootFingerprint, 5)
            val index = AppSyncIndexEnvelopeCodec().encode(AppSyncIndexPayload(account,
                journals = listOf(AppSyncIndexJournalReference(identity, 300,
                    if (fallback) rootFingerprint else AppSyncCanonicalJournalCodec().encode(journal).sha256().hex())),
                checkpoints = if (fallback) listOf(AppSyncIndexCheckpointReference(checkpoint.checkpointId, 123, verified().fingerprint)) else emptyList(),
                updatedAtEpochMillis = 6))
            recovery.pinNativeIndexIntent(session.sessionId, NativeRecoveryIndexIntent(index, null, null))
            if (fallback) recovery.markSanitizedV2IndexCommitted(session.sessionId, 400, index, 7)
            else recovery.markNativeIndexCommitted(session.sessionId, 400, index, 7)
            return recovery to session.sessionId
        }
        fun remoteCheckpoint(source: SyncOperation): AppSyncCanonicalCheckpoint {
            val device = SyncDeviceId("new-remote"); val epoch = SyncDeviceEpoch("remote-epoch")
            val remote = source.copy(deviceId = device, deviceEpoch = epoch,
                operationId = SyncOperation.idFor(device, epoch, source.sequence),
                fields = source.fields + ("value" to "26"), createdAtEpochMillis = 100)
            return assertIs<AppSyncCanonicalPendingMergeResult.Ready>(AppSyncCanonicalPendingMerge().prepare(
                checkpoint, listOf(remote), "latest-remote", 101)).checkpoint
        }
        fun stageNativeCheckpoint(committed: Boolean = true, document: AppSyncCanonicalCheckpoint = checkpoint): Pair<SqlDelightAppSyncRecoveryStore, String> {
            val checkpoint = document
            val recovery = SqlDelightAppSyncRecoveryStore(db)
            val session = recovery.createOrResumeSegmentedCheckpoint(account, checkpoint.checkpointId, "source", 1)
            recovery.pinPayload(session.sessionId, "Checkpoint", checkpoint.checkpointId, 3) {
                AppSyncV3DocumentCodec().encodeCheckpoint(checkpoint)
            }
            recovery.startSegmentedJournal(session.sessionId, 2)
            recovery.saveSegmentIntent(session.sessionId, 0, 1, "segment", null)
            recovery.markSegmentVerified(session.sessionId, 0, "segment", 200, 3)
            recovery.transition(session.sessionId, AppSyncRecoveryPhase.PublishingSegments, AppSyncRecoveryPhase.PublishingRoot, 4)
            recovery.pinNativeRootIntent(session.sessionId, "a".repeat(64))
            recovery.markRootVerified(session.sessionId, 300, "a".repeat(64), 5)
            val index = AppSyncIndexEnvelopeCodec().encode(AppSyncIndexPayload(account,
                checkpoints = listOf(AppSyncIndexCheckpointReference(checkpoint.checkpointId, 300,
                    AppSyncCanonicalCheckpointCodec().encode(checkpoint).sha256().hex())), updatedAtEpochMillis = 6))
            recovery.pinNativeIndexIntent(session.sessionId, NativeRecoveryIndexIntent(index, null, null))
            if (committed) recovery.markNativeIndexCommitted(session.sessionId, 400, index, 7)
            return recovery to session.sessionId
        }
        fun synchronize(cloud: AppSyncJournalLoadResult, recovery: AppSyncCanonicalRecoveryContinuation? = null,
            onResume: suspend (SyncAccountBinding, io.github.littlesurvival.dto.value.FormHash, AppSyncCanonicalCloudPlan.Ready) -> OperationSyncResult? = { _, _, _ -> null }): OperationSyncResult = kotlinx.coroutines.runBlocking {
            val remote = object : AppSyncJournalRemote {
                override suspend fun loadJournals(accountBinding: SyncAccountBinding, forceDiscovery: Boolean): AppSyncJournalLoadResult {
                    loadCalls++; forcedLoads += forceDiscovery
                    check(!loadThrows) { "Unexpected load failure" }
                    return cloud
                }
                override suspend fun publishOwnJournal(payload: AppSyncJournalPayload, expectedFingerprint: String?,
                    formHash: io.github.littlesurvival.dto.value.FormHash): AppSyncJournalPublishResult =
                    error("Canonical reader must not publish a legacy journal")
            }
            val legacy = object : SyncDomainStateAdapter {
                override fun currentState(): Map<SyncEntityKey, ResolvedSyncEntity> = error("Legacy state read")
                override fun apply(result: OperationReductionResult) = error("Legacy state write")
            }
            OperationSyncEngine(store, remote, legacy, nowMillis = { now }, ownerId = { "canonical-reader-test" },
                activateCanonical = { activator().activate(it.checkpoint, it.canonicalOperations, it.legacyOperations) },
                hasCanonicalState = { db.appSyncCanonicalStateQueries.getState().executeAsOneOrNull() != null },
                canonicalRecovery = recovery ?: object : AppSyncCanonicalRecoveryContinuation {
                    override suspend fun resume(account: SyncAccountBinding, formHash: io.github.littlesurvival.dto.value.FormHash,
                        cloud: AppSyncCanonicalCloudPlan.Ready) = onResume(account, formHash, cloud)
                })
                .synchronize(account, io.github.littlesurvival.dto.value.FormHash("test"), detectEmptyCloud = true,
                    forceDiscovery = recovery?.hasPending(account) == true)
        }
        fun activator(operations: AppSyncOperationStore = store) =
            AppSyncCanonicalCheckpointActivator(db, operations, state, materializer, { now })
        fun append(value: String = "18") = store.appendLocalOperation(account,
            SyncDomainId("settings"), SyncEntityId("novelreadersettings.fontsize"), 1,
            SyncOperationKind.Put, mapOf("type" to "int", "value" to value), store.causalContext(),
            15, SyncOperationOrigin.UserAction)
    }
    private fun fixture(test: Fixture.() -> Unit) {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            Database.Schema.create(driver)
            Fixture(Database(driver), driver).test()
        }
    }

    @Test fun discardedAndSupersededSourcesCannotResurrectDuringActivation() {
        for (lifecycle in listOf(AppSyncOperationLifecycle.DiscardedByForcePull,
            AppSyncOperationLifecycle.DiscardedByRebootstrap, AppSyncOperationLifecycle.SupersededByRecovery)) fixture {
            val obsolete = append("invalid historical body")
            when (lifecycle) {
                AppSyncOperationLifecycle.DiscardedByForcePull -> {
                    store.replaceWithVerifiedCloudState(OperationReducer().reduce(operations = emptyList()),
                        SyncCausalContext(), emptySet(), 16) {}
                }
                AppSyncOperationLifecycle.SupersededByRecovery -> {
                    db.appSyncOperationQueries.markOperationsSupersededByRecovery(listOf(obsolete.operationId.value))
                    store.rotateDeviceEpoch(account, AppSyncInstallationState.Active)
                }
                else -> store.rotateDeviceEpoch(account, AppSyncInstallationState.Active)
            }
            val pending = append("22")
            val before = store.allOutboxOperations()
            assertEquals(lifecycle, before.first { it.first.operationId == obsolete.operationId }.second)
            val result = assertIs<AppSyncCanonicalActivationResult.Applied>(activator().activate(verified()))
            assertEquals(1, result.pendingOperationCount)
            assertEquals(22, preferences.values["novelreadersettings.fontsize"])
            val local = assertNotNull(state.read(account.value))
            assertEquals(mapOf(pending.replicaKey.stableKey to 1L), local.coverage)
            assertEquals(before, store.allOutboxOperations())
            assertEquals(listOf(pending), store.pendingOperations())
            assertFalse(store.isApplied(obsolete.operationId))
            assertTrue(store.isApplied(pending.operationId))
        }
    }

    @Test fun acknowledgedAndCompactedSourcesStillCompleteOlderCheckpointCoverage() {
        for (compacted in listOf(false, true)) fixture {
            val source = append()
            store.markAcknowledged(setOf(source.operationId), 16)
            if (compacted) store.markCompacted(setOf(source.operationId))
            val before = store.allOutboxOperations()
            val applied = assertIs<AppSyncCanonicalActivationResult.Applied>(activator().activate(verified()))
            assertEquals(0, applied.pendingOperationCount)
            assertEquals(18, preferences.values["novelreadersettings.fontsize"])
            assertEquals(mapOf(source.replicaKey.stableKey to 1L), state.read(account.value)?.coverage)
            assertEquals(before, store.allOutboxOperations())
            assertTrue(store.verifiedCheckpoints().single().coverage.asStableMap().isEmpty())
        }
    }

    @Test fun oldAccountAcknowledgedHistoryCannotEnterNewAccountActivation() = fixture {
        val historical = append()
        store.markAcknowledged(setOf(historical.operationId), 16)
        val other = SyncAccountBinding("other-account")
        store.completeBootstrap(other, OperationReducer().reduce(operations = emptyList()), SyncCausalContext(),
            emptySet(), 17, true, null) {}
        val pending = assertNotNull(recorder().record("settings", "novelreadersettings.fontsize", SyncOperationKind.Put,
            mapOf("type" to "int", "value" to "22")) {})
        val target = checkpoint.copy(accountBinding = other.value)
        val index = AppSyncIndexEnvelopeCodec().encode(AppSyncIndexPayload(other,
            checkpoints = listOf(AppSyncIndexCheckpointReference(target.checkpointId, 123,
                AppSyncCanonicalCheckpointCodec().encode(target).sha256().hex())), updatedAtEpochMillis = 18))
        val evidence = assertNotNull(AppSyncVerifiedCanonicalCheckpoint.verify(other.value, 123, index,
            AppSyncV3DocumentCodec().encodeCheckpoint(target)))
        val before = store.allOutboxOperations()
        val result = assertIs<AppSyncCanonicalActivationResult.Applied>(activator().activate(evidence))
        assertEquals(1, result.pendingOperationCount)
        assertEquals(22, preferences.values["novelreadersettings.fontsize"])
        assertEquals(mapOf(pending.replicaKey.stableKey to 1L), state.read(other.value)?.coverage)
        assertEquals(before, store.allOutboxOperations())
        assertFalse(store.isApplied(historical.operationId))
        assertEquals(AppSyncOperationLifecycle.Acknowledged,
            store.allOutboxOperations().first { it.first == historical }.second)
    }

    @Test fun pendingEditSurvivesAndRemoteCoverageNeverClaimsLocalOperations() = fixture {
        val pending = append()
        val outbox = store.allOutboxOperations()
        val result = assertIs<AppSyncCanonicalActivationResult.Applied>(activator().activate(verified()))
        assertEquals(1, result.pendingOperationCount)
        assertTrue(result.settingsReconciled)
        assertEquals(18, preferences.values["novelreadersettings.fontsize"])
        assertEquals(outbox, store.allOutboxOperations())
        assertTrue(store.isApplied(pending.operationId))
        val local = assertNotNull(state.read(account.value))
        assertTrue(local.checkpointId.startsWith("local:"))
        assertEquals(1L, local.coverage[pending.replicaKey.stableKey])
        val remote = store.verifiedCheckpoints().single()
        assertEquals("remote", remote.checkpointId)
        assertTrue(remote.coverage.asStableMap().isEmpty())
        assertEquals(verified().fingerprint, remote.payloadFingerprint)
        assertIs<AppSyncCanonicalActivationResult.Applied>(activator().activate(verified()))
        assertEquals(local, state.read(account.value))
        append("22")
        assertIs<AppSyncCanonicalActivationResult.Applied>(activator().activate(verified()))
        assertEquals(22, preferences.values["novelreadersettings.fontsize"])
        assertEquals(2, store.pendingOperations().size)
        assertNotEquals(local.checkpointId, state.read(account.value)?.checkpointId)
    }

    @Test fun failureAfterNestedAdoptionRollsBackStateReceiptsCoverageAndCheckpoint() = fixture {
        val pending = append()
        val outbox = store.allOutboxOperations()
        val beforeCoverage = store.causalContext()
        val failing = object : AppSyncOperationStore by store {
            override fun adoptCheckpoint(checkpointId: String, blogId: Long?, coverage: SyncCausalContext,
                payloadFingerprint: String, createdAtEpochMillis: Long, verifiedAtEpochMillis: Long,
                laterReduction: OperationReductionResult, domainMutation: (OperationReductionResult) -> Unit) {
                store.adoptCheckpoint(checkpointId, blogId, coverage, payloadFingerprint, createdAtEpochMillis,
                    verifiedAtEpochMillis, laterReduction, domainMutation)
                error("injected after nested transaction")
            }
        }
        assertIs<AppSyncCanonicalActivationResult.NeedsAttention>(activator(failing).activate(verified()))
        assertNull(state.read(account.value))
        assertTrue(db.appSyncOperationQueries.getSyncSettingValues().executeAsList().isEmpty())
        assertFalse(store.isApplied(pending.operationId))
        assertEquals(beforeCoverage, store.causalContext())
        assertEquals(outbox, store.allOutboxOperations())
        assertTrue(store.verifiedCheckpoints().isEmpty())
        assertTrue(preferences.values.isEmpty())
    }

    @Test fun preferenceFailureReportsCommittedStateAndRetryReconciles() = fixture {
        append()
        preferences.fail = true
        val first = assertIs<AppSyncCanonicalActivationResult.Applied>(activator().activate(verified()))
        assertFalse(first.settingsReconciled)
        val committed = assertNotNull(state.read(account.value))
        assertEquals(1, store.verifiedCheckpoints().size)
        preferences.fail = false
        assertTrue(assertIs<AppSyncCanonicalActivationResult.Applied>(activator().activate(verified())).settingsReconciled)
        assertEquals(committed, state.read(account.value))
        assertEquals(18, preferences.values["novelreadersettings.fontsize"])
        assertEquals(1, store.pendingOperations().size)
    }

    @Test fun unimportablePendingEditLeavesOriginalEvidenceUntouched() = fixture {
        append("not-an-int")
        val outbox = store.allOutboxOperations()
        assertIs<AppSyncCanonicalActivationResult.NeedsAttention>(activator().activate(verified()))
        assertEquals(outbox, store.allOutboxOperations())
        assertNull(state.read(account.value))
        assertTrue(store.verifiedCheckpoints().isEmpty())
    }

    @Test fun activationRejectsInstallationAccountMismatch() = fixture {
        store.bindAccount(SyncAccountBinding("other"), AppSyncInstallationState.Active)
        assertIs<AppSyncCanonicalActivationResult.NeedsAttention>(activator().activate(verified()))
        assertNull(db.appSyncCanonicalStateQueries.getState().executeAsOneOrNull())
        assertTrue(store.verifiedCheckpoints().isEmpty())
    }

    private fun Fixture.recorder() = AppSyncMutationRecorder(true, store,
        SqlDelightSyncDomainStateAdapter(db, materializer, nowMillis = { 30 }), { 30 }, state)

    @Test fun recorderUsesCanonicalValuesForNoOpsAndKeepsLegacyProvenanceEmpty() = fixture {
        append()
        activator().activate(verified())
        val recorder = recorder()
        val sequence = store.installation()?.nextSequence
        var callbacks = 0
        assertNull(recorder.record("settings", "novelreadersettings.fontsize", SyncOperationKind.Patch,
            mapOf("type" to "int", "value" to "018")) { callbacks++ })
        assertEquals(sequence, store.installation()?.nextSequence)
        assertEquals(1, callbacks)
        val operation = assertNotNull(recorder.record("settings", "novelreadersettings.fontsize", SyncOperationKind.Patch,
            mapOf("type" to "int", "value" to "22")) { callbacks++ })
        assertEquals(2, callbacks)
        assertEquals("22", state.read(account.value)?.entities?.single()?.values()?.values?.first { it.legacyValue() == "22" }?.legacyValue())
        assertEquals(operation.sequence.value, state.read(account.value)?.coverage?.get(operation.replicaKey.stableKey))
        assertTrue(db.appSyncOperationQueries.getResolvedEntities().executeAsList().isEmpty())
        assertEquals(2, store.pendingOperations().size)
        assertEquals(18, preferences.values["novelreadersettings.fontsize"])
    }

    @Test fun recorderBatchComparesAgainstEarlierCanonicalOperationsInSameCommand() = fixture {
        append()
        activator().activate(verified())
        fun draft(value: String) = LocalSyncOperationDraft(SyncDomainId("settings"),
            SyncEntityId("novelreadersettings.fontsize"), kind = SyncOperationKind.Patch,
            fields = mapOf("type" to "int", "value" to value))
        val created = recorder().recordBatch(listOf(draft("22"), draft("22"), draft("18"))) {}
        assertEquals(listOf("22", "18"), created.map { it.fields["value"] })
        assertEquals(listOf(2L, 3L), created.map { it.sequence.value })
        assertTrue(state.read(account.value)!!.entities.single().values().values.any { it.legacyValue() == "18" })
        assertEquals(3, store.pendingOperations().size)
    }

    @Test fun recorderImportFailureCommitsOriginalSourceAndLocalMutation() = fixture {
        append()
        activator().activate(verified())
        val previous = state.read(account.value)
        val created = assertNotNull(recorder().record("settings", "novelreadersettings.fontsize", SyncOperationKind.Patch,
            mapOf("type" to "int", "value" to "invalid")) { preferences.values["local-edit"] = "retained" })
        assertEquals("retained", preferences.values["local-edit"])
        assertEquals("invalid", store.pendingOperations().last().fields["value"])
        assertEquals(created, store.pendingOperations().last())
        assertEquals(previous, state.read(account.value))
        assertEquals(AppSyncInstallationState.Quarantined, store.installation()?.state)
    }

    @Test fun recorderOversizedNotePreservesUntruncatedSourceAndLocalEdit() = fixture {
        activator().activate(verified())
        val source = AppSyncSyntheticCorpus.create().journal.operations.first { it.domainId.value == "detail-note" }
        val content = "x".repeat(128 * 1024 + 1)
        val previous = state.read(account.value)
        val created = assertNotNull(recorder().record(source.domainId.value, source.entityId.value, SyncOperationKind.Put,
            source.fields + ("content" to content)) { preferences.values["local-note"] = content })
        assertEquals(content, preferences.values["local-note"])
        assertEquals(content, created.fields["content"])
        assertEquals(created, store.pendingOperations().single())
        assertEquals(previous, state.read(account.value))
        assertEquals(AppSyncInstallationState.Quarantined, store.installation()?.state)
    }

    @Test fun recorderCallbackFailureRollsBackOutboxAndCanonicalProvenance() = fixture {
        append()
        activator().activate(verified())
        val previous = state.read(account.value)
        val outbox = store.allOutboxOperations()
        val sequence = store.installation()?.nextSequence
        assertFailsWith<IllegalStateException> {
            recorder().record("settings", "novelreadersettings.fontsize", SyncOperationKind.Patch,
                mapOf("type" to "int", "value" to "22")) { error("local mutation failed") }
        }
        assertEquals(outbox, store.allOutboxOperations())
        assertEquals(previous, state.read(account.value))
        assertEquals(sequence, store.installation()?.nextSequence)
    }

    @Test fun failedCanonicalBatchAndLaterCommandsRetainCorrectiveEdits() = fixture {
        append()
        activator().activate(verified())
        val previous = state.read(account.value)
        fun draft(value: String) = LocalSyncOperationDraft(SyncDomainId("settings"),
            SyncEntityId("novelreadersettings.fontsize"), kind = SyncOperationKind.Patch,
            fields = mapOf("type" to "int", "value" to value))
        val recorder = recorder()
        val batch = recorder.recordBatch(listOf(draft("invalid"), draft("18"))) {}
        assertEquals(listOf("invalid", "18"), batch.map { it.fields["value"] })
        assertEquals(AppSyncInstallationState.Quarantined, store.installation()?.state)
        val correction = assertNotNull(recorder.record("settings", "novelreadersettings.fontsize", SyncOperationKind.Patch,
            mapOf("type" to "int", "value" to "18")) {})
        assertEquals(4L, correction.sequence.value)
        assertEquals(listOf("18", "invalid", "18", "18"), store.pendingOperations().map { it.fields["value"] })
        assertEquals(previous, state.read(account.value))
    }

    @Test fun quarantinedGenerationChangeDoesNotSuppressFollowingCorrection() = fixture {
        append()
        activator().activate(verified())
        val previous = state.read(account.value)
        val invalid = LocalSyncOperationDraft(SyncDomainId("settings"), SyncEntityId("novelreadersettings.fontsize"),
            entityGeneration = 2, kind = SyncOperationKind.Patch, fields = mapOf("type" to "int", "value" to "22"))
        val correction = invalid.copy(entityGeneration = 1, fields = mapOf("type" to "int", "value" to "18"))
        val operations = recorder().recordBatch(listOf(invalid, correction)) {}
        assertEquals(listOf(2L, 1L), operations.map { it.entityGeneration })
        assertEquals(listOf("22", "18"), operations.map { it.fields["value"] })
        assertEquals(3, store.pendingOperations().size)
        assertEquals(previous, state.read(account.value))
        assertEquals(AppSyncInstallationState.Quarantined, store.installation()?.state)
    }

    @Test fun canonicalRecorderImportsCompleteNineteenDomainCorpus() = fixture {
        activator().activate(verified())
        val sources = AppSyncSyntheticCorpus.create().journal.operations
        val created = recorder().recordCommand {
            sources.map { LocalSyncOperationDraft(it.domainId, it.entityId, it.entityGeneration, it.kind, it.fields,
                it.bulkDeleteAuthorizationId) }
        }
        assertEquals(AppSyncCanonicalSchema.domains.keys, created.map { it.domainId.value }.toSet())
        assertEquals(AppSyncInstallationState.Active, store.installation()?.state)
        val canonical = assertNotNull(state.read(account.value))
        assertEquals(AppSyncCanonicalSchema.domainsById.keys, canonical.entities.map { it.domainId }.toSet())
        assertEquals(created.size.toLong(), canonical.coverage[created.first().replicaKey.stableKey])
        assertEquals(created, store.pendingOperations())
        assertTrue(db.appSyncOperationQueries.getResolvedEntities().executeAsList().isEmpty())
        val merged = assertIs<AppSyncCanonicalPendingMergeResult.Ready>(AppSyncCanonicalPendingMerge().prepare(
            checkpoint, created, canonical.checkpointId, canonical.createdAtEpochMillis))
        assertEquals(canonical, merged.checkpoint)
    }

    @Test fun recorderGenerationReadsCanonicalTombstoneWithoutLegacyProjection() = fixture {
        append()
        activator().activate(verified())
        val recorder = recorder()
        recorder.record("settings", "novelreadersettings.fontsize", SyncOperationKind.Delete, emptyMap()) {}
        assertEquals(2L, recorder.currentGeneration("settings", "novelreadersettings.fontsize"))
        assertTrue(db.appSyncOperationQueries.getResolvedEntities().executeAsList().isEmpty())
    }

    @Test fun cloudResetPreparationKeepsCanonicalHeadUntilReplacementCommits() = fixture {
        append()
        activator().activate(verified())
        val previous = state.read(account.value)
        val sources = store.allOutboxOperations()
        store.prepareForCloudReset()
        assertNull(store.installation()?.accountBinding)
        assertEquals(previous, state.read(account.value))
        assertEquals(sources, store.allOutboxOperations())
        val legacy = SqlDelightSyncDomainStateAdapter(db, materializer, nowMillis = { 30 })
        store.completeBootstrap(account, OperationReducer().reduce(operations = emptyList()), SyncCausalContext(),
            emptySet(), 30, false, null) { legacy.adoptCheckpointWithinTransaction(it.entities.values) }
        assertNull(state.read(account.value))
        assertEquals(AppSyncInstallationState.Active, store.installation()?.state)
        assertTrue(db.appSyncOperationQueries.getSyncSettingValues().executeAsList().isEmpty())
    }

    @Test fun accountRebootstrapClearsSupersededCanonicalBindingAtomically() = fixture {
        append()
        activator().activate(verified())
        val other = SyncAccountBinding("another-account")
        val legacy = SqlDelightSyncDomainStateAdapter(db, materializer, nowMillis = { 30 })
        store.completeBootstrap(other, OperationReducer().reduce(operations = emptyList()), SyncCausalContext(),
            emptySet(), 30, true, null) { legacy.adoptCheckpointWithinTransaction(it.entities.values) }
        assertNull(state.read(other.value))
        assertEquals(other, store.installation()?.accountBinding)
        assertTrue(store.pendingOperations().isEmpty())
        assertEquals(AppSyncOperationLifecycle.DiscardedByRebootstrap, store.allOutboxOperations().single().second)
        assertNotNull(recorder().record("settings", "novelreadersettings.fontsize", SyncOperationKind.Put,
            mapOf("type" to "int", "value" to "22")) {})
        assertEquals(other, store.pendingOperations().single().accountBinding)
        assertEquals(1, db.appSyncOperationQueries.getResolvedEntities().executeAsList().size)
    }

    @Test fun forcePullReplacementClearsCanonicalHeadButOuterFailureRestoresIt() = fixture {
        append()
        activator().activate(verified())
        val previous = state.read(account.value)
        val before = store.allOutboxOperations()
        val installation = store.installation()
        val settings = db.appSyncOperationQueries.getSyncSettingValues().executeAsList()
        val legacy = SqlDelightSyncDomainStateAdapter(db, materializer, nowMillis = { 30 })
        fun replace() = store.replaceWithVerifiedCloudState(OperationReducer().reduce(operations = emptyList()),
            SyncCausalContext(), emptySet(), 30) { legacy.adoptCheckpointWithinTransaction(it.entities.values) }
        assertFailsWith<IllegalStateException> {
            db.transaction {
                replace()
                assertNull(state.read(account.value))
                error("injected after replacement")
            }
        }
        assertEquals(previous, state.read(account.value))
        assertEquals(before, store.allOutboxOperations())
        assertEquals(installation, store.installation())
        assertEquals(settings, db.appSyncOperationQueries.getSyncSettingValues().executeAsList())
        replace()
        assertNull(state.read(account.value))
        assertEquals(AppSyncOperationLifecycle.DiscardedByForcePull, store.allOutboxOperations().single().second)
    }

    @Test fun failedBootstrapAndWriterRotationRetainCanonicalEvidence() = fixture {
        append()
        activator().activate(verified())
        val previous = state.read(account.value)
        val before = store.installation()
        assertFailsWith<IllegalStateException> {
            store.completeBootstrap(account, OperationReducer().reduce(operations = emptyList()), SyncCausalContext(),
                emptySet(), 30, true, null) { error("materialization failed") }
        }
        assertEquals(previous, state.read(account.value))
        assertEquals(before, store.installation())
        store.rotateDeviceEpoch(account, AppSyncInstallationState.RebootstrapRequired)
        assertEquals(previous, state.read(account.value))
    }

    @Test fun localBatchRecordsProvenanceWithoutReplayingMaterializedValuesOrAcknowledgingSources() = fixture {
        append()
        assertIs<AppSyncCanonicalActivationResult.Applied>(activator().activate(verified()))
        val remote = store.verifiedCheckpoints()
        val materialized = db.appSyncOperationQueries.getSyncSettingValues().executeAsList()
        preferences.values["novelreadersettings.fontsize"] = 99
        val created = store.appendLocalCommand(account, store.causalContext(), 30, SyncOperationOrigin.UserAction,
            localMutation = { listOf(LocalSyncOperationDraft(SyncDomainId("settings"),
                SyncEntityId("novelreadersettings.fontsize"), kind = SyncOperationKind.Patch,
                fields = mapOf("type" to "int", "value" to "22"))) },
            afterOperationsCreated = {
                assertTrue(assertIs<AppSyncCanonicalLocalUpdate.Recorded>(state.recordLocalBatch(account.value, it)).changed)
            })
        val local = assertNotNull(state.read(account.value))
        assertTrue(local.entities.single().values().values.any { it.legacyValue() == "22" })
        assertEquals(2L, local.coverage[created.single().replicaKey.stableKey])
        assertEquals(materialized, db.appSyncOperationQueries.getSyncSettingValues().executeAsList())
        assertEquals(99, preferences.values["novelreadersettings.fontsize"])
        assertEquals(remote, store.verifiedCheckpoints())
        assertEquals(2, store.pendingOperations().size)
        assertFalse(assertIs<AppSyncCanonicalLocalUpdate.Recorded>(state.recordLocalBatch(account.value, created)).changed)
        assertEquals(local, state.read(account.value))
    }

    @Test fun localBatchAndOutboxRollbackTogetherAfterCanonicalWrite() = fixture {
        append()
        activator().activate(verified())
        val previous = state.read(account.value)
        val before = store.allOutboxOperations()
        val nextSequence = store.installation()?.nextSequence
        assertFailsWith<IllegalStateException> {
            store.appendLocalCommand(account, store.causalContext(), 30, SyncOperationOrigin.UserAction,
                localMutation = { listOf(LocalSyncOperationDraft(SyncDomainId("settings"),
                    SyncEntityId("novelreadersettings.fontsize"), kind = SyncOperationKind.Patch,
                    fields = mapOf("type" to "int", "value" to "24"))) },
                afterOperationsCreated = {
                    assertIs<AppSyncCanonicalLocalUpdate.Recorded>(state.recordLocalBatch(account.value, it))
                    error("injected after canonical write")
                })
        }
        assertEquals(previous, state.read(account.value))
        assertEquals(before, store.allOutboxOperations())
        assertEquals(nextSequence, store.installation()?.nextSequence)
    }

    @Test fun localImportFailureKeepsOriginalOutboxAndLastCanonicalState() = fixture {
        append()
        activator().activate(verified())
        val previous = state.read(account.value)
        val invalid = append("invalid")
        val sources = store.allOutboxOperations()
        val result = assertIs<AppSyncCanonicalLocalUpdate.NeedsAttention>(state.recordLocalBatch(account.value, listOf(invalid)))
        assertEquals(AppSyncPendingMergeFailure.ImportFailure, result.failure.reason)
        assertEquals(previous, state.read(account.value))
        assertEquals(sources, store.allOutboxOperations())
        assertEquals(2, store.pendingOperations().size)
    }

    @Test fun localRecordingRequiresActivationAndCorrectInstallationAccount() = fixture {
        val operation = append()
        assertIs<AppSyncCanonicalLocalUpdate.NotActivated>(state.recordLocalBatch(account.value, listOf(operation)))
        activator().activate(verified())
        val previous = state.read(account.value)
        store.bindAccount(SyncAccountBinding("other"), AppSyncInstallationState.Active)
        assertFailsWith<IllegalArgumentException> { state.recordLocalBatch(account.value, emptyList()) }
        assertEquals(previous, state.read(account.value))
    }

    @Test fun commandBatchingDoesNotChangeCanonicalIdentity() {
        fixture {
            activator().activate(verified())
            val first = append("18")
            val second = append("22")
            assertIs<AppSyncCanonicalLocalUpdate.Recorded>(state.recordLocalBatch(account.value, listOf(first, second)))
            val batched = assertNotNull(state.read(account.value))
            fixture {
                activator().activate(verified())
                assertIs<AppSyncCanonicalLocalUpdate.Recorded>(state.recordLocalBatch(account.value, listOf(first)))
                assertIs<AppSyncCanonicalLocalUpdate.Recorded>(state.recordLocalBatch(account.value, listOf(second)))
                assertEquals(batched, state.read(account.value))
            }
        }
    }

    @Test fun excludedLocalSequenceAdvancesCoverageWithoutCreatingAnEntity() = fixture {
        activator().activate(verified())
        val operation = store.appendLocalOperation(account, SyncDomainId("settings"), SyncEntityId("future.secret"), 1,
            SyncOperationKind.Put, mapOf("type" to "string", "value" to "private"), store.causalContext(),
            15, SyncOperationOrigin.UserAction)
        val result = assertIs<AppSyncCanonicalLocalUpdate.Recorded>(state.recordLocalBatch(account.value, listOf(operation)))
        assertEquals(1, result.excludedCount)
        val local = assertNotNull(state.read(account.value))
        assertEquals(1L, local.coverage[operation.replicaKey.stableKey])
        assertTrue(local.entities.isEmpty())
        assertEquals(listOf(operation), store.pendingOperations())
        assertTrue(store.verifiedCheckpoints().single().coverage.asStableMap().isEmpty())
    }
}
