package me.thenano.yamibo.yamibo_app.repository.appsync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.*
import me.thenano.yamibo.yamibo_app.Database
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.*
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncInstallationState
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.*
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.*
import me.thenano.yamibo.yamibo_app.repository.appsync.schema.*
import me.thenano.yamibo.yamibo_app.store.appsync.*
import me.thenano.yamibo.yamibo_app.store.settings.SettingsStore

class AppSyncCanonicalCheckpointActivatorTest {
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

    private inner class Fixture(val db: Database) {
        val store = SqlDelightAppSyncOperationStore(db).also {
            it.initialize("database"); it.bindAccount(account, AppSyncInstallationState.Active)
        }
        val preferences = Preferences()
        val materializer = DatabaseSyncDomainMaterializer(db, preferences)
        val state = SqlDelightCanonicalCheckpointState(db, materializer)
        fun activator(operations: AppSyncOperationStore = store) =
            AppSyncCanonicalCheckpointActivator(db, operations, state, materializer, { 20 })
        fun append(value: String = "18") = store.appendLocalOperation(account,
            SyncDomainId("settings"), SyncEntityId("novelreadersettings.fontsize"), 1,
            SyncOperationKind.Put, mapOf("type" to "int", "value" to value), store.causalContext(),
            15, SyncOperationOrigin.UserAction)
    }
    private fun fixture(test: Fixture.() -> Unit) {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            Database.Schema.create(driver)
            Fixture(Database(driver)).test()
        }
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
