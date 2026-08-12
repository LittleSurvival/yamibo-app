package me.thenano.yamibo.yamibo_app.repository.appsync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.littlesurvival.dto.value.FormHash
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import me.thenano.yamibo.yamibo_app.Database
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncBootstrapResult
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncBootstrapMode
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.CapturedBootstrapSnapshot
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncJournalLoadResult
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncJournalPublishResult
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncJournalRemote
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.BootstrapCoordinator
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.LoadedAppSyncJournal
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.LoadedAppSyncCheckpoint
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.OperationReductionResult
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.OperationReducer
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.OperationSyncEngine
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.OperationSyncResult
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.ResolvedSyncEntity
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.SyncDomainStateAdapter
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.SyncEntityKey
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncInstallationState
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncOperationLifecycle
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncAccountBinding
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncCausalContext
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncDomainId
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncEntityId
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperationKind
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperationOrigin
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncJournalPayload
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncCheckpointPayload
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.ParsedAppSyncCheckpointEnvelope
import me.thenano.yamibo.yamibo_app.repository.appsync.rollout.AppSyncRolloutEvidenceReport
import me.thenano.yamibo.yamibo_app.repository.appsync.rollout.RolloutDemandEvidence
import me.thenano.yamibo.yamibo_app.repository.appsync.rollout.RolloutDemandOutcome
import me.thenano.yamibo.yamibo_app.repository.appsync.rollout.RolloutFixedPointObservation
import me.thenano.yamibo.yamibo_app.repository.backup.YamiboBackupFile
import me.thenano.yamibo.yamibo_app.repository.rss.rssSearchSubscriptionSyncId
import me.thenano.yamibo.yamibo_app.store.appsync.SqlDelightAppSyncOperationStore
import me.thenano.yamibo.yamibo_app.store.appsync.LocalSyncOperationDraft

class OperationSyncEngineTest {
    private val account = SyncAccountBinding("account")
    private val formHash = FormHash("form")

    @Test
    fun freshInstallationCannotPublishBeforeBootstrap() = runBlocking {
        val fixture = fixture()
        fixture.store.initialize("generation")

        val result = fixture.engine.synchronize(account, formHash)

        assertIs<OperationSyncResult.RebootstrapRequired>(result)
        assertEquals(0, fixture.remote.publishCount)
    }

    @Test
    fun activeDeviceWithAuthoritativelyEmptyCloudRequestsForcePushWithoutHeartbeat() = runBlocking {
        val fixture = fixture()
        activate(fixture)
        val loadCountBeforeSync = fixture.remote.loadCount

        val result = fixture.engine.synchronize(
            accountBinding = account,
            formHash = formHash,
            detectEmptyCloud = true,
        )

        assertIs<OperationSyncResult.EmptyCloud>(result)
        assertEquals(loadCountBeforeSync + 2, fixture.remote.loadCount)
        assertEquals(0, fixture.remote.publishCount)
    }

    @Test
    fun bootstrapIsPullOnlyAndRotatesFreshEpoch() = runBlocking {
        val fixture = fixture()
        val before = fixture.store.initialize("generation")

        val result = fixture.bootstrap.bootstrap(account)

        assertIs<AppSyncBootstrapResult.Ready>(result)
        assertEquals(0, fixture.remote.publishCount)
        val after = requireNotNull(fixture.store.installation())
        assertEquals(AppSyncInstallationState.Active, after.state)
        assertNotEquals(before.deviceEpoch, after.deviceEpoch)
    }

    @Test
    fun failedCloudLoadDoesNotCaptureApplyOrPublishLocalMigration() = runBlocking {
        val remote = FakeJournalRemote().also {
            it.loadFailure = AppSyncJournalLoadResult.RetryableFailure("offline")
        }
        val fixture = fixture(remote, migrationDrafts = listOf(migrationSetting("dark")))
        fixture.store.initialize("generation")

        assertIs<AppSyncBootstrapResult.RetryableFailure>(fixture.bootstrap.bootstrap(account))
        assertTrue(fixture.store.pendingOperations().isEmpty())
        assertTrue(fixture.domain.currentState().isEmpty())
        assertEquals(0, remote.publishCount)
    }

    @Test
    fun migrationCaptureFailureCannotPublishOrModifyCloudState() = runBlocking {
        val fixture = fixture(
            captureLocalSnapshot = { error("local database read failed") },
        )
        fixture.store.initialize("generation")

        assertIs<AppSyncBootstrapResult.Paused>(fixture.bootstrap.bootstrap(account))
        assertTrue(fixture.store.pendingOperations().isEmpty())
        assertTrue(fixture.domain.currentState().isEmpty())
        assertEquals(0, fixture.remote.publishCount)
    }

    @Test
    fun localMigrationRemainsPendingAfterSuccessfulCloudLoad() = runBlocking {
        val fixture = fixture(migrationDrafts = listOf(migrationSetting("dark")))
        fixture.store.initialize("generation")

        assertIs<AppSyncBootstrapResult.Ready>(fixture.bootstrap.bootstrap(account))

        val operation = fixture.store.pendingOperations().single()
        assertEquals(SyncOperationOrigin.Migration, operation.origin)
        assertEquals("dark", fixture.domain.value("settings", "theme", "value"))
        assertEquals(AppSyncInstallationState.Active, fixture.store.installation()?.state)
    }

    @Test
    fun bootstrapWithoutCheckpointReplacesStaleResolvedStateBeforeLocalMigration() = runBlocking {
        val fixture = fixture(migrationDrafts = listOf(migrationSetting("dark")))
        fixture.store.initialize("generation")
        fixture.domain.apply(
            OperationReducer().reduce(
                operations = listOf(standaloneSettingOperation("stale")),
            ),
        )

        assertIs<AppSyncBootstrapResult.Ready>(fixture.bootstrap.bootstrap(account))

        assertEquals("dark", fixture.domain.value("settings", "theme", "value"))
        assertEquals(1, fixture.store.pendingOperations().size)
        assertEquals(SyncOperationOrigin.Migration, fixture.store.pendingOperations().single().origin)
    }

    @Test
    fun bootstrapAdoptsVerifiedCheckpointStateAndCoverage() = runBlocking {
        val remote = FakeJournalRemote()
        val operation = standaloneSettingOperation("dark")
        val resolved = OperationReducer().reduce(operations = listOf(operation))
            .entities.values.single()
        val coverage = SyncCausalContext().advance(operation.replicaKey, operation.sequence)
        remote.checkpoints += LoadedAppSyncCheckpoint(
            remoteId = "42",
            envelope = ParsedAppSyncCheckpointEnvelope(
                payload = AppSyncCheckpointPayload(
                    checkpointId = "checkpoint-1",
                    accountBinding = account,
                    coverage = coverage,
                    encodedSnapshot = "fixture",
                    resolvedEntities = listOf(resolved),
                    tombstones = emptyList(),
                    createdAtEpochMillis = 100,
                ),
                snapshot = YamiboBackupFile(appVersionCode = 1, createdAt = 100),
                fingerprint = "fingerprint",
            ),
        )
        val fixture = fixture(remote)
        fixture.store.initialize("generation")

        assertIs<AppSyncBootstrapResult.Ready>(fixture.bootstrap.bootstrap(account))

        assertEquals("dark", fixture.domain.value("settings", "theme", "value"))
        assertEquals(coverage.asStableMap(), fixture.store.causalContext().asStableMap())
    }

    @Test
    fun competingCheckpointCandidatesReplayUncoveredJournalsToConvergence() = runBlocking {
        val remote = FakeJournalRemote()
        val theme = standaloneSettingOperation(
            value = "dark",
            entity = "theme",
            deviceValue = "remote-a",
        )
        val font = standaloneSettingOperation(
            value = "large",
            entity = "font",
            deviceValue = "remote-b",
        )
        listOf("checkpoint-a" to theme, "checkpoint-b" to font).forEach {
            (checkpointId, operation) ->
            val resolved = OperationReducer().reduce(operations = listOf(operation))
                .entities.values.single()
            val coverage = SyncCausalContext().advance(operation.replicaKey, operation.sequence)
            remote.checkpoints += LoadedAppSyncCheckpoint(
                remoteId = checkpointId,
                envelope = ParsedAppSyncCheckpointEnvelope(
                    payload = AppSyncCheckpointPayload(
                        checkpointId = checkpointId,
                        accountBinding = account,
                        coverage = coverage,
                        encodedSnapshot = "fixture",
                        resolvedEntities = listOf(resolved),
                        tombstones = emptyList(),
                        createdAtEpochMillis = 100,
                    ),
                    snapshot = YamiboBackupFile(appVersionCode = 1, createdAt = 100),
                    fingerprint = "fingerprint-$checkpointId",
                ),
            )
            remote.seed(
                AppSyncJournalPayload(
                    accountBinding = account,
                    deviceId = operation.deviceId,
                    deviceEpoch = operation.deviceEpoch,
                    writerNonce = me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncWriterNonce(
                        "writer-${operation.deviceId.value}",
                    ),
                    firstSequence = 1,
                    lastSequence = 1,
                    operations = listOf(operation),
                    observed = coverage,
                    heartbeatAtEpochMillis = 100,
                ),
            )
        }
        val fixture = fixture(remote)
        fixture.store.initialize("generation")

        assertIs<AppSyncBootstrapResult.Ready>(fixture.bootstrap.bootstrap(account))

        assertEquals("dark", fixture.domain.value("settings", "theme", "value"))
        assertEquals("large", fixture.domain.value("settings", "font", "value"))
        assertEquals(0, remote.publishCount)
    }

    @Test
    fun activeDevicePersistsAndAcknowledgesDiscoveredCheckpoint() = runBlocking {
        val fixture = fixture()
        activate(fixture)
        val checkpoint = LoadedAppSyncCheckpoint(
            remoteId = "91",
            envelope = ParsedAppSyncCheckpointEnvelope(
                payload = AppSyncCheckpointPayload(
                    checkpointId = "checkpoint-from-peer",
                    accountBinding = account,
                    coverage = SyncCausalContext(),
                    encodedSnapshot = "fixture",
                    resolvedEntities = emptyList(),
                    tombstones = emptyList(),
                    createdAtEpochMillis = 100,
                ),
                snapshot = YamiboBackupFile(appVersionCode = 1, createdAt = 100),
                fingerprint = "peer-checkpoint-fingerprint",
            ),
        )
        fixture.remote.checkpoints += checkpoint

        assertIs<OperationSyncResult.Converged>(
            fixture.engine.synchronize(account, formHash),
        )

        assertEquals(
            "checkpoint-from-peer",
            fixture.store.verifiedCheckpoints().single().checkpointId,
        )
        val installation = requireNotNull(fixture.store.installation())
        assertEquals(
            listOf("checkpoint-from-peer"),
            fixture.remote.ownCheckpointAcknowledgementIds(installation),
        )
    }

    @Test
    fun activeDeviceAdoptsCanonicalCheckpointStateBeforeAcknowledgingIt() = runBlocking {
        val fixture = fixture()
        activate(fixture)
        fixture.domain.apply(
            OperationReducer().reduce(
                operations = listOf(
                    standaloneSettingOperation("stale", deviceValue = "stale-device"),
                ),
            ),
        )
        val canonical = standaloneSettingOperation("dark", deviceValue = "canonical-device")
        val canonicalState = OperationReducer().reduce(operations = listOf(canonical))
            .entities.values.single()
        fixture.remote.checkpoints += LoadedAppSyncCheckpoint(
            remoteId = "93",
            envelope = ParsedAppSyncCheckpointEnvelope(
                payload = AppSyncCheckpointPayload(
                    checkpointId = "canonical-checkpoint",
                    accountBinding = account,
                    coverage = SyncCausalContext().advance(
                        canonical.replicaKey,
                        canonical.sequence,
                    ),
                    encodedSnapshot = "fixture",
                    resolvedEntities = listOf(canonicalState),
                    tombstones = emptyList(),
                    createdAtEpochMillis = 100,
                ),
                snapshot = YamiboBackupFile(appVersionCode = 1, createdAt = 100),
                fingerprint = "canonical-checkpoint-fingerprint",
            ),
        )

        assertIs<OperationSyncResult.Converged>(
            fixture.engine.synchronize(account, formHash),
        )

        assertEquals("dark", fixture.domain.value("settings", "theme", "value"))
        assertTrue(fixture.store.causalContext().includes(canonical))
        val installation = requireNotNull(fixture.store.installation())
        assertEquals(
            listOf("canonical-checkpoint"),
            fixture.remote.ownCheckpointAcknowledgementIds(installation),
        )
    }

    @Test
    fun interruptionAfterCheckpointDiscoveryResumesAcknowledgement() = runBlocking {
        val fixture = fixture()
        activate(fixture)
        fixture.remote.checkpoints += LoadedAppSyncCheckpoint(
            remoteId = "92",
            envelope = ParsedAppSyncCheckpointEnvelope(
                payload = AppSyncCheckpointPayload(
                    checkpointId = "checkpoint-before-interruption",
                    accountBinding = account,
                    coverage = SyncCausalContext(),
                    encodedSnapshot = "fixture",
                    resolvedEntities = emptyList(),
                    tombstones = emptyList(),
                    createdAtEpochMillis = 100,
                ),
                snapshot = YamiboBackupFile(appVersionCode = 1, createdAt = 100),
                fingerprint = "interrupted-checkpoint-fingerprint",
            ),
        )
        fixture.remote.throwOnPublish = true

        assertIs<OperationSyncResult.RetryScheduled>(
            fixture.engine.synchronize(account, formHash),
        )
        assertEquals(
            "checkpoint-before-interruption",
            fixture.store.verifiedCheckpoints().single().checkpointId,
        )

        fixture.remote.throwOnPublish = false
        val recovered = OperationSyncEngine(
            store = fixture.store,
            remote = fixture.remote,
            domainState = fixture.domain,
            nowMillis = { fixture.clock++ },
            ownerId = { "recovered-process" },
        )
        assertIs<OperationSyncResult.Converged>(
            recovered.synchronize(account, formHash),
        )
        val installation = requireNotNull(fixture.store.installation())
        assertEquals(
            listOf("checkpoint-before-interruption"),
            fixture.remote.ownCheckpointAcknowledgementIds(installation),
        )
    }

    @Test
    fun recreatedDatabasePullsCloudJournalBeforeAnyPublication() = runBlocking {
        val remote = FakeJournalRemote()
        val cloudOperation = standaloneSettingOperation("dark")
        remote.seed(
            AppSyncJournalPayload(
                accountBinding = account,
                deviceId = cloudOperation.deviceId,
                deviceEpoch = cloudOperation.deviceEpoch,
                writerNonce = me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncWriterNonce("remote-writer"),
                firstSequence = 1,
                lastSequence = 1,
                operations = listOf(cloudOperation),
                observed = SyncCausalContext().advance(
                    cloudOperation.replicaKey,
                    cloudOperation.sequence,
                ),
                heartbeatAtEpochMillis = 100,
            ),
        )
        val fixture = fixture(remote)
        fixture.store.initialize("new-database-generation")

        assertIs<AppSyncBootstrapResult.Ready>(fixture.bootstrap.bootstrap(account))

        assertEquals("dark", fixture.domain.value("settings", "theme", "value"))
        assertTrue(fixture.store.pendingOperations().isEmpty())
        assertEquals(0, remote.publishCount)
    }

    @Test
    fun establishedCloudJoinMigratesDivergentLegacyLocalStateAndKeepsRollback() = runBlocking {
        val remote = FakeJournalRemote()
        val cloudOperation = standaloneSettingOperation("dark")
        remote.seed(
            AppSyncJournalPayload(
                accountBinding = account,
                deviceId = cloudOperation.deviceId,
                deviceEpoch = cloudOperation.deviceEpoch,
                writerNonce = me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncWriterNonce("remote-writer"),
                firstSequence = 1,
                lastSequence = 1,
                operations = listOf(cloudOperation),
                observed = SyncCausalContext().advance(
                    cloudOperation.replicaKey,
                    cloudOperation.sequence,
                ),
                heartbeatAtEpochMillis = 100,
            ),
        )
        val fixture = fixture(
            remote,
            migrationDrafts = listOf(
                migrationSetting("system"),
                LocalSyncOperationDraft(
                    domainId = SyncDomainId("settings"),
                    entityId = SyncEntityId("local-only"),
                    kind = SyncOperationKind.Put,
                    fields = mapOf("type" to "string", "value" to "kept"),
                ),
            ),
        )
        fixture.store.initialize("new-database-generation")
        fixture.domain.apply(
            OperationReducer().reduce(
                operations = listOf(
                    standaloneSettingOperation("system", deviceValue = "legacy-local"),
                ),
            ),
        )

        val result = assertIs<AppSyncBootstrapResult.Ready>(fixture.bootstrap.bootstrap(account))

        assertEquals(AppSyncBootstrapMode.Join, result.mode)
        assertEquals("system", fixture.domain.value("settings", "theme", "value"))
        assertEquals("kept", fixture.domain.value("settings", "local-only", "value"))
        assertEquals(2, fixture.store.pendingOperations().size)
        assertEquals("rollback", fixture.store.latestBootstrapRollbackSnapshot()?.encodedSnapshot)
        assertEquals(AppSyncInstallationState.Active, fixture.store.installation()?.state)
        assertEquals(0, remote.publishCount)
    }

    @Test
    fun retryAfterCloudLoadFailureDoesNotRecaptureMigrationOperations() = runBlocking {
        val remote = FakeJournalRemote().also {
            it.loadFailure = AppSyncJournalLoadResult.RetryableFailure("offline")
        }
        var captureCount = 0
        val fixture = fixture(
            remote = remote,
            captureLocalSnapshot = {
                captureCount++
                CapturedBootstrapSnapshot(listOf(migrationSetting("dark")), "rollback")
            },
        )
        fixture.store.initialize("generation")

        assertIs<AppSyncBootstrapResult.RetryableFailure>(fixture.bootstrap.bootstrap(account))
        remote.loadFailure = null
        assertIs<AppSyncBootstrapResult.Ready>(fixture.bootstrap.bootstrap(account))

        assertEquals(1, captureCount)
        assertEquals(1, fixture.store.pendingOperations().size)
        assertEquals("dark", fixture.domain.value("settings", "theme", "value"))
    }

    @Test
    fun failedBootstrapTransactionRollsBackProjectionAndInstallationActivation() {
        val database = Database(
            JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also(Database.Schema::create),
        )
        val store = SqlDelightAppSyncOperationStore(database)
        store.initialize("generation")
        store.captureBootstrapMigration(
            accountBinding = account,
            drafts = listOf(migrationSetting("dark")),
            createdAtEpochMillis = 1_000,
        )

        assertFailsWith<IllegalStateException> {
            store.completeBootstrap(
                accountBinding = account,
                result = OperationReducer().reduce(operations = emptyList()),
                coverage = SyncCausalContext(),
                cloudOperationIds = emptySet(),
                appliedAtEpochMillis = 1_001,
                rotateDeviceEpoch = false,
                checkpoint = null,
            ) {
                database.appSyncOperationQueries.upsertResolvedEntity(
                    entityKey = "settings|theme|1",
                    domainId = "settings",
                    entityId = "theme",
                    entityGeneration = 1,
                    encodedState = "fixture",
                    updatedAtEpochMillis = 1_001,
                )
                error("materialization failed")
            }
        }

        assertTrue(database.appSyncOperationQueries.getResolvedEntities().executeAsList().isEmpty())
        assertEquals(AppSyncInstallationState.Bootstrapping, store.installation()?.state)
        assertEquals(1, store.pendingOperations().size)
    }

    @Test
    fun establishedCloudJoinPreservesNonOverlappingLegacyLocalEntitiesForUpload() = runBlocking {
        val remote = FakeJournalRemote()
        val cloudOperation = standaloneSettingOperation("dark")
        remote.seed(journalPayload(cloudOperation))
        val fixture = fixture(
            remote,
            migrationDrafts = listOf(
                LocalSyncOperationDraft(
                    domainId = SyncDomainId("settings"),
                    entityId = SyncEntityId("local-only"),
                    kind = SyncOperationKind.Put,
                    fields = mapOf("type" to "string", "value" to "kept"),
                ),
            ),
        )
        fixture.store.initialize("new-database-generation")

        assertIs<AppSyncBootstrapResult.Ready>(fixture.bootstrap.bootstrap(account))

        assertEquals("dark", fixture.domain.value("settings", "theme", "value"))
        assertEquals("kept", fixture.domain.value("settings", "local-only", "value"))
        assertEquals(1, fixture.store.pendingOperations().size)
    }

    @Test
    fun sameAccountRebootstrapKeepsPendingUserOperationsInProjection() = runBlocking {
        val fixture = fixture()
        activate(fixture)
        appendSetting(fixture, "dark")

        assertIs<AppSyncBootstrapResult.Ready>(fixture.bootstrap.bootstrap(account))

        assertEquals("dark", fixture.domain.value("settings", "theme", "value"))
        assertEquals(1, fixture.store.pendingOperations().size)
        assertEquals(
            SyncOperationOrigin.UserAction,
            fixture.store.pendingOperations().single().origin,
        )
    }

    @Test
    fun twoDevicesConvergeThroughSeparateJournals() = runBlocking {
        val remote = FakeJournalRemote()
        val first = fixture(remote)
        val second = fixture(remote)
        activate(first)
        activate(second)
        appendSetting(first, "dark")

        assertIs<OperationSyncResult.Converged>(
            first.engine.synchronize(account, formHash),
        )
        assertIs<OperationSyncResult.Converged>(
            second.engine.synchronize(account, formHash),
        )

        assertEquals("dark", second.domain.value("settings", "theme", "value"))
        assertEquals(2, remote.journalCount)
    }

    @Test
    fun successfulPublishDoesNotRunASecondVerificationPull() = runBlocking {
        val fixture = fixture()
        activate(fixture)
        appendSetting(fixture, "dark")
        val loadsBeforeSync = fixture.remote.loadCount

        assertIs<OperationSyncResult.Converged>(
            fixture.engine.synchronize(account, formHash),
        )

        assertEquals(1, fixture.remote.loadCount - loadsBeforeSync)
        assertEquals(1, fixture.remote.publishCount)
    }

    @Test
    fun concurrentSameFieldConvergesWithoutUserChoice() = runBlocking {
        val remote = FakeJournalRemote()
        val first = fixture(remote)
        val second = fixture(remote)
        activate(first)
        activate(second)
        appendSetting(first, "dark")
        appendSetting(second, "light")

        first.engine.synchronize(account, formHash)
        second.engine.synchronize(account, formHash)
        first.engine.synchronize(account, formHash)

        assertEquals(
            first.domain.value("settings", "theme", "value"),
            second.domain.value("settings", "theme", "value"),
        )
        assertEquals(2, remote.journalCount)
    }

    @Test
    fun timeoutAfterAcceptedPostRetriesSameOperationId() = runBlocking {
        val fixture = fixture()
        activate(fixture)
        val operationId = appendSetting(fixture, "dark")
        fixture.remote.acceptThenReturnUnknown = true

        assertIs<OperationSyncResult.RetryScheduled>(
            fixture.engine.synchronize(account, formHash),
        )
        assertEquals(
            AppSyncOperationLifecycle.PublishedUnverified,
            fixture.store.allOutboxOperations().single().second,
        )

        fixture.remote.acceptThenReturnUnknown = false
        assertIs<OperationSyncResult.Converged>(
            fixture.engine.synchronize(account, formHash),
        )
        assertEquals(operationId, fixture.store.allOutboxOperations().single().first.operationId.value)
        assertEquals(
            AppSyncOperationLifecycle.Acknowledged,
            fixture.store.allOutboxOperations().single().second,
        )
    }

    @Test
    fun missingFormHashPausesBeforeLoadingOrPublishing() = runBlocking {
        val fixture = fixture()
        activate(fixture)
        appendSetting(fixture, "dark")
        val loadsBeforeSync = fixture.remote.loadCount

        assertIs<OperationSyncResult.PausedAuth>(
            fixture.engine.synchronize(account, formHash = null),
        )

        assertEquals(loadsBeforeSync, fixture.remote.loadCount)
        assertEquals(0, fixture.remote.publishCount)
    }

    @Test
    fun deviceInactiveForNinetyDaysMustRebootstrapBeforePublishing() = runBlocking {
        val fixture = fixture()
        activate(fixture)
        appendSetting(fixture, "dark")
        fixture.store.updateVerifiedHeartbeat(atEpochMillis = 1, journalBlogId = null)
        fixture.clock = 90L * 24 * 60 * 60 * 1_000 + 2

        assertIs<OperationSyncResult.RebootstrapRequired>(
            fixture.engine.synchronize(account, formHash),
        )

        assertEquals(0, fixture.remote.publishCount)
        assertEquals(
            AppSyncInstallationState.RebootstrapRequired,
            fixture.store.installation()?.state,
        )
    }

    @Test
    fun inactiveDeviceCannotFinishBootstrapWithoutVerifiedCheckpoint() = runBlocking {
        val fixture = fixture()
        activate(fixture)
        fixture.store.updateVerifiedHeartbeat(atEpochMillis = 1, journalBlogId = null)
        fixture.clock = 90L * 24 * 60 * 60 * 1_000 + 2

        assertIs<AppSyncBootstrapResult.Paused>(
            fixture.bootstrap.bootstrap(account),
        )

        assertEquals(AppSyncInstallationState.PausedProvider, fixture.store.installation()?.state)
        assertEquals(0, fixture.remote.publishCount)
    }

    @Test
    fun inactiveDeviceAdoptsVerifiedCheckpointAndRotatesEpoch() = runBlocking {
        val fixture = fixture()
        activate(fixture)
        val previousEpoch = requireNotNull(fixture.store.installation()).deviceEpoch
        fixture.store.updateVerifiedHeartbeat(atEpochMillis = 1, journalBlogId = null)
        fixture.clock = 90L * 24 * 60 * 60 * 1_000 + 2
        fixture.remote.checkpoints += LoadedAppSyncCheckpoint(
            remoteId = "93",
            envelope = ParsedAppSyncCheckpointEnvelope(
                payload = AppSyncCheckpointPayload(
                    checkpointId = "checkpoint-for-returning-device",
                    accountBinding = account,
                    coverage = SyncCausalContext(),
                    encodedSnapshot = "fixture",
                    resolvedEntities = emptyList(),
                    tombstones = emptyList(),
                    createdAtEpochMillis = 100,
                ),
                snapshot = YamiboBackupFile(appVersionCode = 1, createdAt = 100),
                fingerprint = "returning-device-checkpoint-fingerprint",
            ),
        )

        assertIs<AppSyncBootstrapResult.Ready>(
            fixture.bootstrap.bootstrap(account),
        )

        val installation = requireNotNull(fixture.store.installation())
        assertEquals(AppSyncInstallationState.Active, installation.state)
        assertNotEquals(previousEpoch, installation.deviceEpoch)
        assertEquals(
            "checkpoint-for-returning-device",
            fixture.store.verifiedCheckpoints().single().checkpointId,
        )
    }

    @Test
    fun unexpectedProviderExceptionKeepsPendingWorkAndReleasesLease() = runBlocking {
        val fixture = fixture()
        activate(fixture)
        val operationId = appendSetting(fixture, "dark")
        fixture.remote.throwOnLoad = true

        val result = fixture.engine.synchronize(account, formHash)

        assertIs<OperationSyncResult.RetryScheduled>(result)
        assertEquals(operationId, fixture.store.pendingOperations().single().operationId.value)
        assertEquals(null, fixture.store.currentLease())
        assertEquals(0, fixture.remote.publishCount)
    }

    @Test
    fun retryableJournalLoadUsesRemainingAttemptAndConverges() = runBlocking {
        val fixture = fixture()
        activate(fixture)
        appendSetting(fixture, "dark")
        fixture.remote.retryableLoadFailuresRemaining = 1
        val loadsBeforeSync = fixture.remote.loadCount

        val result = fixture.engine.synchronize(account, formHash)

        assertIs<OperationSyncResult.Converged>(result)
        assertEquals(loadsBeforeSync + 2, fixture.remote.loadCount)
        assertTrue(fixture.store.pendingOperations().isEmpty())
    }

    @Test
    fun terminalCloudValidationFailurePausesProviderWithoutPublishing() = runBlocking {
        val fixture = fixture()
        activate(fixture)
        appendSetting(fixture, "dark")
        fixture.remote.loadFailure = AppSyncJournalLoadResult.TerminalFailure(
            "journal fingerprint mismatch",
        )

        assertIs<OperationSyncResult.PausedProvider>(
            fixture.engine.synchronize(account, formHash),
        )

        assertEquals(0, fixture.remote.publishCount)
        assertEquals(1, fixture.store.pendingOperations().size)
        assertEquals(
            AppSyncInstallationState.PausedProvider,
            fixture.store.installation()?.state,
        )
    }

    @Test
    fun accountMismatchRequiresBootstrapBeforeAnyProviderCall() = runBlocking {
        val fixture = fixture()
        activate(fixture)
        val loadsBeforeSync = fixture.remote.loadCount

        assertIs<OperationSyncResult.RebootstrapRequired>(
            fixture.engine.synchronize(SyncAccountBinding("other-account"), formHash),
        )

        assertEquals(loadsBeforeSync, fixture.remote.loadCount)
        assertEquals(0, fixture.remote.publishCount)
    }

    @Test
    fun writerNonceCollisionForcesNewEpochWithoutPublishing() = runBlocking {
        val fixture = fixture()
        activate(fixture)
        val before = requireNotNull(fixture.store.installation())
        fixture.remote.seed(
            AppSyncJournalPayload(
                accountBinding = account,
                deviceId = before.deviceId,
                deviceEpoch = before.deviceEpoch,
                writerNonce = me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncWriterNonce("other"),
                firstSequence = 0,
                lastSequence = 0,
                operations = emptyList(),
                observed = SyncCausalContext(),
                heartbeatAtEpochMillis = 1,
            ),
        )

        val result = fixture.engine.synchronize(account, formHash)

        assertIs<OperationSyncResult.RebootstrapRequired>(result)
        val after = requireNotNull(fixture.store.installation())
        assertNotEquals(before.deviceEpoch, after.deviceEpoch)
        assertEquals(0, fixture.remote.publishCount)
    }

    @Test
    fun interruptedJournalRewriteAfterCompactionRecoversWithoutAckLoss() = runBlocking {
        val fixture = fixture()
        activate(fixture)
        appendSetting(fixture, "dark")
        assertIs<OperationSyncResult.Converged>(
            fixture.engine.synchronize(account, formHash),
        )
        val operation = fixture.store.allOutboxOperations().single().first
        val coverage = SyncCausalContext().advance(operation.replicaKey, operation.sequence)
        fixture.store.saveVerifiedCheckpoint(
            me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncVerifiedCheckpoint(
                checkpointId = "checkpoint",
                blogId = 77,
                coverage = coverage,
                payloadFingerprint = "checkpoint-fingerprint",
                createdAtEpochMillis = fixture.clock,
                verifiedAtEpochMillis = fixture.clock,
            ),
        )
        val installation = requireNotNull(fixture.store.installation())
        fixture.remote.seed(
            AppSyncJournalPayload(
                accountBinding = account,
                deviceId = installation.deviceId,
                deviceEpoch = installation.deviceEpoch,
                writerNonce = installation.writerNonce,
                firstSequence = operation.sequence.value,
                lastSequence = operation.sequence.value,
                operations = listOf(operation),
                observed = coverage,
                checkpointAcknowledgements = listOf(
                    me.thenano.yamibo.yamibo_app.repository.appsync.remote
                        .AppSyncCheckpointAcknowledgement("checkpoint", coverage),
                ),
                heartbeatAtEpochMillis = fixture.clock,
            ),
        )
        fixture.remote.throwOnPublish = true

        assertIs<OperationSyncResult.RetryScheduled>(
            fixture.engine.synchronize(account, formHash),
        )
        assertEquals(
            AppSyncOperationLifecycle.Compacted,
            fixture.store.allOutboxOperations().single().second,
        )
        assertEquals(listOf(operation.operationId), fixture.remote.ownOperationIds(installation))

        fixture.remote.throwOnPublish = false
        assertIs<OperationSyncResult.Converged>(
            fixture.engine.synchronize(account, formHash),
        )

        assertTrue(fixture.remote.ownOperationIds(installation).isEmpty())
        assertEquals(
            AppSyncOperationLifecycle.Compacted,
            fixture.store.allOutboxOperations().single().second,
        )
    }

    @Test
    fun oneHundredEligibleDemandsConvergeWithinTwoWindowsWithoutAckLoss() = runBlocking {
        val fixture = fixture()
        activate(fixture)
        val evidence = mutableListOf<RolloutDemandEvidence>()
        val domains = listOf(
            DomainMutation("settings", { "theme" }) { index ->
                mapOf("value" to "value-$index")
            },
            DomainMutation("favorite.item", { "thread:$it" }) { index ->
                mapOf("title" to "title-$index")
            },
            DomainMutation(
                domain = "rss.search-subscription",
                entityId = { rssSearchSubscriptionSyncId("query-$it", null) },
                kind = SyncOperationKind.Put,
                fields = { index ->
                    mapOf(
                        "title" to "query-$index",
                        "query" to "query-$index",
                        "forumId" to null,
                        "forumName" to null,
                        "enabled" to "true",
                        "createdAt" to index.toString(),
                        "updatedAt" to index.toString(),
                    )
                },
            ),
            DomainMutation("reading.thread", { "thread:$it" }) { index ->
                mapOf("position" to index.toString())
            },
            DomainMutation("favorite.update-event", { "event:$it" }) { index ->
                mapOf("readAt" to (1_000 + index).toString())
            },
            DomainMutation("favorite.update-fid-filter", { "fid:$it" }) { index ->
                mapOf("fid" to index.toString(), "enabled" to (index % 2 == 0).toString())
            },
            DomainMutation("favorite.update-category-filter", { "category:category-$it" }) { index ->
                mapOf(
                    "categorySyncId" to "category-$index",
                    "enabled" to (index % 2 == 0).toString(),
                )
            },
        )

        repeat(100) { index ->
            val mutation = domains[index % domains.size]
            appendMutation(fixture, mutation, index)
            fixture.remote.acceptThenReturnUnknown = index % 10 == 0
            val first = fixture.engine.synchronize(account, formHash)
            fixture.remote.acceptThenReturnUnknown = false
            val final = if (first is OperationSyncResult.Converged) {
                first
            } else {
                fixture.engine.synchronize(account, formHash)
            }
            evidence += RolloutDemandEvidence(
                demandId = "controlled-$index",
                eligible = true,
                outcome = if (final is OperationSyncResult.Converged) {
                    RolloutDemandOutcome.Converged
                } else {
                    RolloutDemandOutcome.Failed
                },
                attempts = if (first is OperationSyncResult.Converged) 1 else 2,
                coveredDomains = setOf(mutation.domain),
                fixedPoint = if (final is OperationSyncResult.Converged) {
                    RolloutFixedPointObservation(
                        pendingNonQuarantinedCount = fixture.store.pendingOperations().size,
                        fetchedValidUnappliedCount = 0,
                        projectionMismatchCount = 0,
                        acknowledgedOperationLossCount = 0,
                    )
                } else {
                    null
                },
            )
        }

        val operations = fixture.store.allOutboxOperations()
        val report = AppSyncRolloutEvidenceReport.create(
            generatedAtEpochMillis = fixture.clock,
            demands = evidence,
        )
        assertEquals(100, report.convergedDemandCount)
        assertEquals(10, report.retryCount)
        assertEquals(domains.map { it.domain }.toSet(), evidence.flatMap { it.coveredDomains }.toSet())
        assertTrue(report.passesRetirementGate)
        assertEquals(100, operations.size)
        assertEquals(100, operations.map { it.first.operationId }.distinct().size)
        assertTrue(operations.all { it.second == AppSyncOperationLifecycle.Acknowledged })
        val installation = requireNotNull(fixture.store.installation())
        assertEquals(
            operations.map { it.first.operationId }.toSet(),
            fixture.remote.ownOperationIds(installation).toSet(),
        )
    }

    @Test
    fun foregroundAndBackgroundEnginesShareDurableLease() = runBlocking {
        val fixture = fixture()
        activate(fixture)
        appendSetting(fixture, "dark")
        fixture.remote.loadGate = CompletableDeferred()
        fixture.remote.loadStarted = CompletableDeferred()
        val foreground = async {
            fixture.engine.synchronize(account, formHash)
        }
        fixture.remote.loadStarted?.await()
        val background = OperationSyncEngine(
            store = fixture.store,
            remote = fixture.remote,
            domainState = fixture.domain,
            nowMillis = { fixture.clock },
            ownerId = { "background-worker" },
        )

        assertIs<OperationSyncResult.AlreadyRunning>(
            background.synchronize(account, formHash),
        )

        fixture.remote.loadGate?.complete(Unit)
        assertIs<OperationSyncResult.Converged>(foreground.await())
        assertEquals(null, fixture.store.currentLease())
    }

    private suspend fun activate(fixture: Fixture) {
        fixture.store.initialize("generation")
        assertIs<AppSyncBootstrapResult.Ready>(fixture.bootstrap.bootstrap(account))
    }

    private fun appendSetting(fixture: Fixture, value: String): String {
        val operation = fixture.store.appendLocalOperation(
            accountBinding = account,
            domainId = SyncDomainId("settings"),
            entityId = SyncEntityId("theme"),
            entityGeneration = 1,
            kind = SyncOperationKind.Patch,
            fields = mapOf("value" to value),
            causalContext = fixture.store.causalContext(),
            createdAtEpochMillis = fixture.clock++,
            origin = SyncOperationOrigin.UserAction,
        )
        fixture.domain.apply(
            OperationReducer().reduce(
                current = fixture.domain.currentState(),
                operations = listOf(operation),
            ),
        )
        return operation.operationId.value
    }

    private fun appendMutation(
        fixture: Fixture,
        mutation: DomainMutation,
        index: Int,
    ): String {
        val operation = fixture.store.appendLocalOperation(
            accountBinding = account,
            domainId = SyncDomainId(mutation.domain),
            entityId = SyncEntityId(mutation.entityId(index)),
            entityGeneration = 1,
            kind = mutation.kind,
            fields = mutation.fields(index),
            causalContext = fixture.store.causalContext(),
            createdAtEpochMillis = fixture.clock++,
            origin = SyncOperationOrigin.UserAction,
        )
        fixture.domain.apply(
            OperationReducer().reduce(
                current = fixture.domain.currentState(),
                operations = listOf(operation),
            ),
        )
        return operation.operationId.value
    }

    private fun fixture(
        remote: FakeJournalRemote = FakeJournalRemote(),
        migrationDrafts: List<LocalSyncOperationDraft> = emptyList(),
        captureLocalSnapshot: () -> CapturedBootstrapSnapshot = {
            CapturedBootstrapSnapshot(migrationDrafts, "rollback")
        },
    ): Fixture {
        val database = Database(
            JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also(Database.Schema::create),
        )
        val store = SqlDelightAppSyncOperationStore(database)
        val domain = FakeDomainState()
        var ownerCounter = 0
        val fixture = Fixture(store, remote, domain)
        fixture.engine = OperationSyncEngine(
            store = store,
            remote = remote,
            domainState = domain,
            nowMillis = { fixture.clock++ },
            ownerId = { "owner-${ownerCounter++}" },
        )
        fixture.bootstrap = BootstrapCoordinator(
            store = store,
            remote = remote,
            domainState = domain,
            nowMillis = { fixture.clock++ },
            captureLocalSnapshot = captureLocalSnapshot,
        )
        return fixture
    }

    private fun migrationSetting(value: String) = LocalSyncOperationDraft(
        domainId = SyncDomainId("settings"),
        entityId = SyncEntityId("theme"),
        kind = SyncOperationKind.Put,
        fields = mapOf("type" to "string", "value" to value),
    )

    private fun standaloneSettingOperation(
        value: String,
        entity: String = "theme",
        deviceValue: String = "remote",
    ): me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperation {
        val device = me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncDeviceId(deviceValue)
        val epoch = me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncDeviceEpoch("epoch")
        val sequence = me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncSequence(1)
        return me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperation(
            operationId = me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperation.idFor(
                device,
                epoch,
                sequence,
            ),
            deviceId = device,
            deviceEpoch = epoch,
            sequence = sequence,
            accountBinding = account,
            domainId = SyncDomainId("settings"),
            entityId = SyncEntityId(entity),
            kind = SyncOperationKind.Put,
            fields = mapOf("type" to "string", "value" to value),
            createdAtEpochMillis = 10,
            origin = SyncOperationOrigin.UserAction,
        )
    }

    @Test
    fun terminalPublishFailurePausesProviderAndKeepsPendingWork() = runBlocking {
        val fixture = fixture()
        activate(fixture)
        val operationId = appendSetting(fixture, "dark")
        fixture.remote.publishFailure = AppSyncJournalPublishResult.TerminalFailure(
            "provider rejected journal payload",
        )

        assertIs<OperationSyncResult.PausedProvider>(
            fixture.engine.synchronize(account, formHash),
        )

        assertEquals(operationId, fixture.store.pendingOperations().single().operationId.value)
        assertEquals(
            AppSyncInstallationState.PausedProvider,
            fixture.store.installation()?.state,
        )
    }

    private fun journalPayload(
        operation: me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperation,
    ) = AppSyncJournalPayload(
        accountBinding = account,
        deviceId = operation.deviceId,
        deviceEpoch = operation.deviceEpoch,
        writerNonce = me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncWriterNonce(
            "writer-${operation.deviceId.value}",
        ),
        firstSequence = operation.sequence.value,
        lastSequence = operation.sequence.value,
        operations = listOf(operation),
        observed = SyncCausalContext().advance(operation.replicaKey, operation.sequence),
        heartbeatAtEpochMillis = 100,
    )

    private class Fixture(
        val store: SqlDelightAppSyncOperationStore,
        val remote: FakeJournalRemote,
        val domain: FakeDomainState,
        var clock: Long = 1_000,
    ) {
        lateinit var engine: OperationSyncEngine
        lateinit var bootstrap: BootstrapCoordinator
    }

    private data class DomainMutation(
        val domain: String,
        val entityId: (Int) -> String,
        val kind: SyncOperationKind = SyncOperationKind.Patch,
        val fields: (Int) -> Map<String, String?>,
    )

    private class FakeDomainState : SyncDomainStateAdapter {
        private var entities = emptyMap<SyncEntityKey, ResolvedSyncEntity>()

        override fun currentState(): Map<SyncEntityKey, ResolvedSyncEntity> = entities

        override fun apply(result: OperationReductionResult) {
            entities = result.entities
        }

        fun value(domain: String, entity: String, field: String): String? =
            entities.entries
                .singleOrNull {
                    it.key.domainId == SyncDomainId(domain) &&
                        it.key.entityId == SyncEntityId(entity)
                }
                ?.value
                ?.fields
                ?.get(field)
                ?.value
    }

    private class FakeJournalRemote : AppSyncJournalRemote {
        private val journals = linkedMapOf<String, LoadedAppSyncJournal>()
        var publishCount = 0
        var loadCount = 0
        var acceptThenReturnUnknown = false
        var throwOnLoad = false
        var throwOnPublish = false
        var retryableLoadFailuresRemaining = 0
        var loadFailure: AppSyncJournalLoadResult? = null
        var publishFailure: AppSyncJournalPublishResult? = null
        var loadGate: CompletableDeferred<Unit>? = null
        var loadStarted: CompletableDeferred<Unit>? = null
        val checkpoints = mutableListOf<LoadedAppSyncCheckpoint>()

        val journalCount: Int
            get() = journals.size

        override suspend fun loadJournals(
            accountBinding: SyncAccountBinding,
            forceDiscovery: Boolean,
        ): AppSyncJournalLoadResult {
            loadCount++
            loadStarted?.complete(Unit)
            loadGate?.await()
            loadGate = null
            if (throwOnLoad) error("unexpected provider failure")
            if (retryableLoadFailuresRemaining > 0) {
                retryableLoadFailuresRemaining--
                return AppSyncJournalLoadResult.RetryableFailure("transient timeout")
            }
            return loadFailure ?: AppSyncJournalLoadResult.Success(
                journals.values.filter { it.payload.accountBinding == accountBinding },
                checkpoints,
            )
        }

        override suspend fun publishOwnJournal(
            payload: AppSyncJournalPayload,
            expectedFingerprint: String?,
            formHash: FormHash,
        ): AppSyncJournalPublishResult {
            if (throwOnPublish) error("interrupted journal rewrite")
            publishFailure?.let { return it }
            val key = "${payload.deviceId.value}:${payload.deviceEpoch.value}"
            val current = journals[key]
            if (expectedFingerprint != current?.fingerprint) {
                return AppSyncJournalPublishResult.Conflict("fingerprint changed")
            }
            publishCount++
            val fingerprint = payload.operations.joinToString("|") { it.operationId.value } +
                ":${payload.heartbeatAtEpochMillis}"
            val loaded = LoadedAppSyncJournal(key, fingerprint, payload)
            journals[key] = loaded
            return if (acceptThenReturnUnknown) {
                AppSyncJournalPublishResult.Unknown("timeout after accepted write")
            } else {
                AppSyncJournalPublishResult.Verified(loaded)
            }
        }

        fun seed(payload: AppSyncJournalPayload) {
            val key = "${payload.deviceId.value}:${payload.deviceEpoch.value}"
            journals[key] = LoadedAppSyncJournal(key, "seed", payload)
        }

        fun ownOperationIds(
            installation: me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncInstallation,
        ) = journals["${installation.deviceId.value}:${installation.deviceEpoch.value}"]
            ?.payload
            ?.operations
            .orEmpty()
            .map { it.operationId }

        fun ownCheckpointAcknowledgementIds(
            installation: me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncInstallation,
        ) = journals["${installation.deviceId.value}:${installation.deviceEpoch.value}"]
            ?.payload
            ?.checkpointAcknowledgements
            .orEmpty()
            .map { it.checkpointId }
    }
}
