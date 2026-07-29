package me.thenano.yamibo.yamibo_app.repository.appsync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.littlesurvival.dto.value.FormHash
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import me.thenano.yamibo.yamibo_app.Database
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncBootstrapResult
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
import me.thenano.yamibo.yamibo_app.repository.backup.YamiboBackupFile
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
    fun failedCloudLoadDoesNotApplyOrPublishCapturedLocalMigration() = runBlocking {
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
            captureLocalMigrationDrafts = { error("local database read failed") },
        )
        fixture.store.initialize("generation")

        assertIs<AppSyncBootstrapResult.Paused>(fixture.bootstrap.bootstrap(account))
        assertTrue(fixture.store.pendingOperations().isEmpty())
        assertTrue(fixture.domain.currentState().isEmpty())
        assertEquals(0, fixture.remote.publishCount)
    }

    @Test
    fun localMigrationIsCreatedOnlyAfterSuccessfulCloudLoad() = runBlocking {
        val fixture = fixture(migrationDrafts = listOf(migrationSetting("dark")))
        fixture.store.initialize("generation")

        assertIs<AppSyncBootstrapResult.Ready>(fixture.bootstrap.bootstrap(account))

        val operation = fixture.store.pendingOperations().single()
        assertEquals(SyncOperationOrigin.Migration, operation.origin)
        assertEquals("dark", fixture.domain.value("settings", "theme", "value"))
        assertEquals(AppSyncInstallationState.Active, fixture.store.installation()?.state)
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
    fun resetBootstrapKeepsCloudValueAndImportsOnlyMissingLocalEntities() = runBlocking {
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

        assertIs<AppSyncBootstrapResult.Ready>(fixture.bootstrap.bootstrap(account))

        assertEquals("dark", fixture.domain.value("settings", "theme", "value"))
        assertEquals("kept", fixture.domain.value("settings", "local-only", "value"))
        val pending = fixture.store.pendingOperations()
        assertEquals(listOf("local-only"), pending.map { it.entityId.value })
        assertEquals(0, remote.publishCount)
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
    fun terminalCloudValidationFailureQuarantinesWithoutPublishing() = runBlocking {
        val fixture = fixture()
        activate(fixture)
        appendSetting(fixture, "dark")
        fixture.remote.loadFailure = AppSyncJournalLoadResult.TerminalFailure(
            "journal fingerprint mismatch",
        )

        assertIs<OperationSyncResult.Quarantined>(
            fixture.engine.synchronize(account, formHash),
        )

        assertEquals(0, fixture.remote.publishCount)
        assertEquals(1, fixture.store.pendingOperations().size)
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
        var converged = 0

        repeat(100) { index ->
            appendSetting(fixture, "value-$index")
            fixture.remote.acceptThenReturnUnknown = index % 10 == 0
            val first = fixture.engine.synchronize(account, formHash)
            fixture.remote.acceptThenReturnUnknown = false
            val final = if (first is OperationSyncResult.Converged) {
                first
            } else {
                fixture.engine.synchronize(account, formHash)
            }
            if (final is OperationSyncResult.Converged) converged += 1
        }

        val operations = fixture.store.allOutboxOperations()
        assertEquals(100, converged)
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

    private fun fixture(
        remote: FakeJournalRemote = FakeJournalRemote(),
        migrationDrafts: List<LocalSyncOperationDraft> = emptyList(),
        captureLocalMigrationDrafts: () -> List<LocalSyncOperationDraft> = { migrationDrafts },
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
            captureLocalMigrationDrafts = captureLocalMigrationDrafts,
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

    private class Fixture(
        val store: SqlDelightAppSyncOperationStore,
        val remote: FakeJournalRemote,
        val domain: FakeDomainState,
        var clock: Long = 1_000,
    ) {
        lateinit var engine: OperationSyncEngine
        lateinit var bootstrap: BootstrapCoordinator
    }

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
        var loadFailure: AppSyncJournalLoadResult? = null
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
