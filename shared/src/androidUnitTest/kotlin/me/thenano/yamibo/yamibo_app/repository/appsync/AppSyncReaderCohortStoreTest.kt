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

class AppSyncReaderCohortStoreTest {
    @Test fun migration47AddsAccountScopedEvidenceWithoutChangingExistingData() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            driver.execute(null, "CREATE TABLE retained (value TEXT NOT NULL)", 0)
            driver.execute(null, "INSERT INTO retained VALUES ('sentinel')", 0)
            Database.Schema.migrate(driver, 47, 48)
            val queries = Database(driver).appSyncReaderCohortQueries
            queries.putEvidence("first", "first-evidence", "digest")
            queries.putEvidence("second", "second-evidence", "digest")
            queries.invalidate("first")
            assertNull(queries.getEvidence("first").executeAsOneOrNull())
            assertEquals("second-evidence", queries.getEvidence("second").executeAsOne().evidenceJson)
            val sentinel = driver.executeQuery(null, "SELECT value FROM retained", { cursor ->
                cursor.next(); app.cash.sqldelight.db.QueryResult.Value(cursor.getString(0))
            }, 0).value
            assertEquals("sentinel", sentinel)
        }
    }

    @Test fun engineNotifiesEvidenceStoreBeforeHandlingAuthenticationFailure() = fixture {
        store.observe(account, cloud(journal()), 200)
        assertTrue(eligible())
        val remote = object : AppSyncJournalRemote {
            override suspend fun loadJournals(accountBinding: SyncAccountBinding, forceDiscovery: Boolean) = AppSyncJournalLoadResult.NotLoggedIn
            override suspend fun publishOwnJournal(payload: AppSyncJournalPayload, expectedFingerprint: String?,
                formHash: io.github.littlesurvival.dto.value.FormHash): AppSyncJournalPublishResult = error("unexpected write")
        }
        val domain = object : SyncDomainStateAdapter {
            override fun currentState(): Map<SyncEntityKey, ResolvedSyncEntity> = error("unexpected read")
            override fun apply(result: OperationReductionResult) = error("unexpected apply")
        }
        val engine = OperationSyncEngine(operations, remote, domain, nowMillis = { 201 }, ownerId = { "cohort-test" },
            observeCloud = { binding, result -> store.observe(binding, result, 201) })
        kotlinx.coroutines.runBlocking {
            assertIs<OperationSyncResult.PausedAuth>(engine.synchronize(account, io.github.littlesurvival.dto.value.FormHash("test")))
        }
        assertNull(store.evidence(account))
    }

    private val account = SyncAccountBinding("account")
    private inner class Fixture(val db: Database) {
        val operations = SqlDelightAppSyncOperationStore(db).also {
            it.initialize("database"); it.bindAccount(account, AppSyncInstallationState.Active)
        }
        val installation get() = requireNotNull(operations.installation())
        val store = SqlDelightAppSyncReaderCohortStore(db, maximumAgeMillis = 50, inactiveAfterMillis = 100)
        fun journal(reader: Int = 3, device: String = installation.deviceId.value,
            heartbeat: Long = 200, remoteId: String = "42", nonce: String = installation.writerNonce.value): LoadedAppSyncJournal {
            val payload = AppSyncJournalPayload(account, SyncDeviceId(device), installation.deviceEpoch,
                SyncWriterNonce(nonce), 0, 0, emptyList(), SyncCausalContext(), heartbeatAtEpochMillis = heartbeat,
                protocolReadVersion = reader)
            val codec = AppSyncJournalEnvelopeCodec()
            return LoadedAppSyncJournal(remoteId, assertIs<AppSyncJournalValidation.Valid>(codec.validate(codec.encode(payload))).envelope.fingerprint, payload)
        }
        fun cloud(vararg journals: LoadedAppSyncJournal) = AppSyncJournalLoadResult.Success(journals.toList(), authoritativeDiscovery = true)
        fun eligible(now: Long = 200) = store.canWrite(installation, now, true, true, true)
    }
    private fun fixture(block: Fixture.() -> Unit) {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use {
            Database.Schema.create(it); Fixture(Database(it)).block()
        }
    }

    @Test fun evidenceSurvivesStoreRestartButRequiresAllLiveGatesAndFreshness() = fixture {
        store.observe(account, cloud(journal()), 200)
        assertTrue(eligible())
        val restarted = SqlDelightAppSyncReaderCohortStore(db, 50, 100)
        assertEquals(store.evidence(account), restarted.evidence(account))
        assertTrue(restarted.canWrite(installation, 250, true, true, true))
        assertFalse(eligible(251))
        assertFalse(eligible(199))
        assertFalse(store.canWrite(installation, 200, false, true, true))
        assertFalse(store.canWrite(installation, 200, true, false, true))
        assertFalse(store.canWrite(installation, 200, true, true, false))
        assertFalse(store.canWrite(installation.copy(state = AppSyncInstallationState.Quarantined), 200, true, true, true))
        assertFalse(store.canWrite(installation.copy(writerNonce = SyncWriterNonce("restored")), 200, true, true, true))
        assertFalse(store.canWrite(installation.copy(accountBinding = SyncAccountBinding("other")), 200, true, true, true))
    }

    @Test fun incompatibleReaderAgesOnlyAtAuthoritativeObservationAndOwnEvidenceIsRequired() = fixture {
        store.observe(account, cloud(journal(), journal(2, "older", 100, "43")), 200)
        assertFalse(eligible())
        assertFalse(eligible(250)) // elapsed local time does not retire a reader
        store.observe(account, cloud(journal(), journal(2, "older", 99, "43")), 200)
        assertTrue(eligible())
        store.observe(account, cloud(journal(device = "other")), 200)
        assertFalse(eligible())
        store.observe(account, cloud(journal(heartbeat = 99)), 200)
        assertFalse(eligible())
    }

    @Test fun failedCachedIncompleteAndConflictingObservationsRevokePriorEvidence() = fixture {
        val good = cloud(journal())
        val failures = listOf<AppSyncJournalLoadResult>(AppSyncJournalLoadResult.NotLoggedIn,
            AppSyncJournalLoadResult.RetryableFailure("timeout"), good.copy(authoritativeDiscovery = false),
            good.copy(indexedReplicaKeys = setOf("missing:epoch")), good.copy(canonicalReadIssues = listOf("unsupported")),
            good.copy(retirementDiscoveryIssues = listOf("missing candidate")), cloud(),
            cloud(journal(), journal(remoteId = "43", nonce = "restored")),
            cloud(journal(), journal(device = "different", remoteId = "42")),
            good.copy(journals = List(1025) { journal() }))
        for (failure in failures) {
            store.observe(account, good, 200)
            assertTrue(eligible())
            store.observe(account, failure, 201)
            assertNull(store.evidence(account))
            assertFalse(eligible(201))
        }
    }

    @Test fun malformedAccountAndNegativeMetadataCannotEstablishEvidence() = fixture {
        val source = journal()
        for (bad in listOf(source.copy(payload = source.payload.copy(accountBinding = SyncAccountBinding("other"))),
            source.copy(payload = source.payload.copy(heartbeatAtEpochMillis = -1)),
            source.copy(payload = source.payload.copy(protocolReadVersion = 0)), source.copy(remoteId = "0"))) {
            store.observe(account, cloud(bad), 200)
            assertNull(store.evidence(account))
        }
    }

    @Test fun nativeReaderMetadataRequiresCanonicalBindingAndIntegrity() = fixture {
        val own = installation
        val journal = AppSyncCanonicalJournal(AppSyncCanonicalOperationBlock(account.value, emptyList()),
            own.deviceId.value, own.deviceEpoch.value, own.writerNonce.value, 0, 0, emptyMap(), emptyList(), 200, 3, 3, "test", 0)
        val codec = AppSyncV3DocumentCodec()
        val document = assertIs<AppSyncV3DocumentRead.Journal>(codec.discover(codec.encodeJournal(
            "${journal.deviceId}:${journal.deviceEpoch}", journal), account.value, AppSyncV3PayloadKind.Journal))
        val good = cloud().copy(canonicalDocuments = listOf(LoadedAppSyncCanonicalDocument("42", document)))
        store.observe(account, good, 200)
        assertTrue(eligible())
        for (metadata in listOf(document.metadata.copy(compressorId = 9), document.metadata.copy(uncompressedLength = 1),
            document.metadata.copy(canonicalFingerprint = "bad"), document.metadata.copy(identity = "different"))) {
            store.observe(account, good.copy(canonicalDocuments = listOf(LoadedAppSyncCanonicalDocument("42", document.copy(metadata = metadata)))), 200)
            assertFalse(eligible())
        }
    }

    @Test fun corruptPersistedEvidenceAndAccountSwitchDoNotAuthorizePublication() = fixture {
        store.observe(account, cloud(journal()), 200)
        val row = db.appSyncReaderCohortQueries.getEvidence(account.value).executeAsOne()
        db.appSyncReaderCohortQueries.putEvidence(account.value, row.evidenceJson + " ", row.evidenceSha256)
        assertFalse(eligible())
        assertNull(store.evidence(SyncAccountBinding("other")))
    }
}
