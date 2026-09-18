package me.thenano.yamibo.yamibo_app.repository.appsync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.littlesurvival.dto.model.TimeInfo
import io.github.littlesurvival.dto.model.User
import io.github.littlesurvival.dto.model.BlogSummary
import io.github.littlesurvival.dto.model.PageNav
import io.github.littlesurvival.dto.page.*
import io.github.littlesurvival.dto.value.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import me.thenano.yamibo.yamibo_app.Database
import me.thenano.yamibo.yamibo_app.repository.appsync.model.*
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.*
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.*
import me.thenano.yamibo.yamibo_app.repository.appsync.schema.*
import me.thenano.yamibo.yamibo_app.store.appsync.*
import okio.ByteString.Companion.encodeUtf8
import me.thenano.yamibo.yamibo_app.repository.appsync.domain.stableAppSyncFingerprint

class AppSyncV3SegmentPublisherTest {
    private fun Fixture.prepareFallbackIndex(): Pair<String, AppSyncVerifiedCanonicalCheckpoint> {
        val (id, original, identity) = prepareJournal()
        val cp = AppSyncCanonicalCheckpoint("retained-base", account.value, 1, emptyMap(), emptyList())
        val checkpointBody = AppSyncV3DocumentCodec().encodeCheckpoint(cp)
        val reference = AppSyncIndexCheckpointReference(cp.checkpointId, 700, AppSyncCanonicalCheckpointCodec().encode(cp).sha256().hex())
        val old = assertIs<AppSyncV3DocumentRead.Journal>(AppSyncV3DocumentCodec().discover(original, account.value, AppSyncV3PayloadKind.Journal))
        val index = AppSyncIndexEnvelopeCodec().encode(AppSyncIndexPayload(account,
            journals = listOf(AppSyncIndexJournalReference(identity, 701, old.metadata.canonicalFingerprint),
                AppSyncIndexJournalReference("other:epoch", 702, "other-fingerprint")),
            checkpoints = listOf(reference), updatedAtEpochMillis = 1))
        val selection = AppSyncBlogClassSelection.Existing(BlogClassId(7))
        provider.artifacts[700] = AppSyncBlogWriteRequest(BlogId(700), AppSyncJournalDefaults.checkpointTitle(cp.checkpointId), checkpointBody, selection, FormHash("test"))
        provider.artifacts[701] = AppSyncBlogWriteRequest(BlogId(701), "original-native-journal", original, selection, FormHash("test"))
        provider.artifacts[800] = AppSyncBlogWriteRequest(BlogId(800), APP_SYNC_INDEX_TITLE, index, selection, FormHash("test"))
        val frozen = AppSyncV3DocumentCodec().encodeJournal(identity, old.document.copy(
            acknowledgements = listOf(AppSyncCanonicalAcknowledgement(cp.checkpointId, cp.coverage))))
        recovery.pinPayload(id, "Journal", identity, 3) { frozen }
        recovery.pinSanitizedV2Payload(id) { true }
        recovery.startSegmentedJournal(id, 3)
        return id to assertNotNull(AppSyncVerifiedCanonicalCheckpoint.verify(account.value, 700, index, checkpointBody))
    }

    private fun Fixture.fallbackCommitter() = AppSyncSanitizedV2IndexCommitter(provider,
        SqlDelightAppSyncRecoveryStore(db), fallbackPublisher(), { 20 }, { true })

    @Test fun fallbackIndexLostResponseReconcilesWithoutAnotherPostAndPreservesNativeArtifacts() = fixture {
        val (id, checkpoint) = prepareFallbackIndex()
        val original = provider.artifacts.getValue(701)
        provider.timeoutAt = 3
        provider.storeTimedOut = true
        provider.onList = { if (provider.posts.size == 3) provider.failIndexRead = true }
        val selection = AppSyncBlogClassSelection.Existing(BlogClassId(7))
        assertIs<AppSyncSegmentIndexCommitResult.Retryable>(fallbackCommitter().commit(id, selection, FormHash("test"), checkpoint))
        assertEquals(3, provider.posts.size)
        assertFalse(recovery.session(id)!!.indexCommitted)
        provider.failIndexRead = false
        provider.onList = {}
        assertIs<AppSyncSegmentIndexCommitResult.Verified>(fallbackCommitter().commit(id, selection, FormHash("test"), checkpoint))
        assertEquals(3, provider.posts.size)
        assertEquals(AppSyncRecoveryPhase.ActivatingLocal, recovery.session(id)?.phase)
        assertEquals(listOf(pending), operations.pendingOperations())
        assertEquals(original, provider.artifacts[701])
        val index = assertIs<AppSyncIndexValidation.Valid>(AppSyncIndexEnvelopeCodec().validate(provider.artifacts.getValue(800).message)).envelope.payload
        assertEquals(checkpoint.fingerprint, index.checkpoints.single().fingerprint)
        assertEquals(702, index.journals.single { it.replicaKey == "other:epoch" }.blogId)
        assertEquals(recovery.session(id)?.rootFingerprint, index.journals.single { it.replicaKey != "other:epoch" }.fingerprint)
        assertFailsWith<IllegalArgumentException> { recovery.markNativeIndexCommitted(id, 800, provider.artifacts.getValue(800).message, 20) }
    }

    @Test fun fallbackIndexRejectsMissingBaseBeforeStagingAndConcurrentIndexChangesBeforePost() = fixture {
        val (id, checkpoint) = prepareFallbackIndex()
        val index = provider.artifacts.getValue(800)
        val parsed = assertIs<AppSyncIndexValidation.Valid>(AppSyncIndexEnvelopeCodec().validate(index.message)).envelope.payload
        provider.artifacts[800] = index.copy(message = AppSyncIndexEnvelopeCodec().encode(parsed.copy(checkpoints = emptyList())))
        val selection = AppSyncBlogClassSelection.Existing(BlogClassId(7))
        assertIs<AppSyncSegmentIndexCommitResult.Conflict>(fallbackCommitter().commit(id, selection, FormHash("test"), checkpoint))
        assertTrue(provider.posts.isEmpty())
        provider.artifacts[800] = index
        provider.onList = {
            if (recovery.nativeIndexIntent(id) != null) provider.artifacts[800] = index.copy(
                message = AppSyncIndexEnvelopeCodec().encode(parsed.copy(updatedAtEpochMillis = 99)))
        }
        assertIs<AppSyncSegmentIndexCommitResult.Conflict>(fallbackCommitter().commit(id, selection, FormHash("test"), checkpoint))
        assertEquals(2, provider.posts.size)
        assertFalse(recovery.session(id)!!.indexCommitted)
    }

    @Test fun fallbackIndexReadbackCannotOmitAcknowledgedCheckpointOrUseCanonicalFingerprintForV2Root() {
        for (omitCheckpoint in listOf(false, true)) fixture {
            val (id, checkpoint) = prepareFallbackIndex()
            val selection = AppSyncBlogClassSelection.Existing(BlogClassId(7))
            val root = assertIs<AppSyncSegmentPublishResult.ReadyToCommitIndex>(fallbackPublisher().publish(id, selection, FormHash("test")))
            val native = recovery.nativePayload(id)
            val doc = assertIs<AppSyncV3DocumentRead.Journal>(AppSyncV3DocumentCodec().discover(native.body, account.value, AppSyncV3PayloadKind.Journal))
            val bad = AppSyncIndexEnvelopeCodec().encode(AppSyncIndexPayload(account,
                journals = listOf(AppSyncIndexJournalReference(native.identity, root.rootBlogId.toInt(),
                    if (omitCheckpoint) root.rootFingerprint else doc.metadata.canonicalFingerprint)),
                checkpoints = if (omitCheckpoint) emptyList() else listOf(AppSyncIndexCheckpointReference(
                    checkpoint.document.checkpointId, 700, checkpoint.fingerprint)), updatedAtEpochMillis = 20))
            recovery.pinNativeIndexIntent(id, NativeRecoveryIndexIntent(bad, 800, provider.artifacts.getValue(800).message.encodeUtf8().sha256().hex()))
            assertFailsWith<IllegalArgumentException> { recovery.markSanitizedV2IndexCommitted(id, 800, bad, 20) }
            assertFalse(recovery.session(id)!!.indexCommitted)
            assertEquals(listOf(pending), operations.pendingOperations())
        }
    }

    private fun Fixture.prepareFallback(): String {
        val (id, body, identity) = prepareJournal()
        recovery.pinPayload(id, "Journal", identity, 3) { body }
        recovery.pinSanitizedV2Payload(id) { true }
        recovery.startSegmentedJournal(id, 3)
        return id
    }

    private fun Fixture.fallbackPublisher(target: Int = 4096, gate: suspend () -> Boolean = { true },
        discovery: suspend (String, String) -> AppSyncV3ArtifactDiscovery = AppSyncV3ArtifactReconciler(provider, BlogClassId(7))::discover) =
        AppSyncSanitizedV2SegmentPublisher(provider, SqlDelightAppSyncRecoveryStore(db), { 10 },
            AppSyncSegmentEnvelopeCodec(AppSyncPayloadBudget(target)), gate, discovery)

    @Test fun fallbackPublicationReconcilesLostRootAndRestartsWithFrozenConfiguration() = fixture {
        val id = prepareFallback()
        val native = recovery.nativePayload(id)
        val oldRoot = AppSyncBlogWriteRequest(BlogId(77), "original-native-root", native.body,
            AppSyncBlogClassSelection.Existing(BlogClassId(7)), FormHash("test"))
        provider.artifacts[77] = oldRoot
        provider.timeoutAt = 2
        provider.storeTimedOut = true
        val selection = AppSyncBlogClassSelection.Existing(BlogClassId(7))
        val first = assertIs<AppSyncSegmentPublishResult.ReadyToCommitIndex>(fallbackPublisher().publish(id, selection, FormHash("test")))
        assertEquals(2, provider.posts.size)
        assertEquals(AppSyncRecoveryPhase.CommittingIndex, recovery.session(id)?.phase)
        assertFalse(recovery.session(id)!!.indexCommitted)
        assertEquals(listOf(pending), operations.pendingOperations())
        assertEquals(first, fallbackPublisher(8192).publish(id, selection, FormHash("test")))
        assertEquals(4096, recovery.nativeSegmentConfiguration(id)?.targetChars)
        assertEquals(2, provider.posts.size)
        assertEquals(oldRoot, provider.artifacts[77])
        assertTrue(provider.posts.all { it.blogId == null })
        assertEquals(stableAppSyncFingerprint(first.rootBody), first.rootFingerprint)
        val root = AppSyncSegmentEnvelopeCodec().decodeRoot(first.rootBody).getOrThrow()
        assertEquals(2, root.protocolVersion)
        assertEquals(stableAppSyncFingerprint(recovery.sanitizedV2Payload(id)), root.envelopeFingerprint)
        val reconstructed = assertIs<AppSyncSegmentReconstruction.Valid>(AppSyncSegmentEnvelopeCodec().reconstruct(root) {
            blogId -> provider.artifacts[blogId.toInt()]?.message
        })
        assertEquals(recovery.sanitizedV2Payload(id), reconstructed.canonicalEnvelope)
    }

    @Test fun fallbackUnknownDiscoveryNeverCreatesAgainAndReadbackTamperingStopsPublication() = fixture {
        val id = prepareFallback()
        provider.timeoutAt = 1
        provider.storeTimedOut = true
        val selection = AppSyncBlogClassSelection.Existing(BlogClassId(7))
        val unknown = fallbackPublisher(discovery = { _, _ -> AppSyncV3ArtifactDiscovery.Unknown })
        repeat(2) { assertIs<AppSyncSegmentPublishResult.Retryable>(unknown.publish(id, selection, FormHash("test"))) }
        assertEquals(1, provider.posts.size)
        assertIs<AppSyncSegmentPublishResult.ReadyToCommitIndex>(fallbackPublisher().publish(id, selection, FormHash("test")))
        assertEquals(2, provider.posts.size)
        val segment = recovery.segmentWrites(id).single().blogId!!.toInt()
        provider.artifacts[segment] = provider.artifacts.getValue(segment).copy(message = "changed")
        assertIs<AppSyncSegmentPublishResult.Terminal>(fallbackPublisher().publish(id, selection, FormHash("test")))
        assertEquals(2, provider.posts.size)
    }

    @Test fun fallbackChecksGateAgainBeforeRootAndRejectsOrdinaryNativeSessions() = fixture {
        val selection = AppSyncBlogClassSelection.Existing(BlogClassId(7))
        assertIs<AppSyncSegmentPublishResult.Terminal>(fallbackPublisher().publish(session.sessionId, selection, FormHash("test")))
        val id = prepareFallback()
        assertIs<AppSyncSegmentPublishResult.Terminal>(fallbackPublisher(gate = { provider.posts.isEmpty() })
            .publish(id, selection, FormHash("test")))
        assertEquals(1, provider.posts.size)
        assertNull(recovery.session(id)?.rootBlogId)
        assertIs<AppSyncSegmentPublishResult.ReadyToCommitIndex>(fallbackPublisher().publish(id, selection, FormHash("test")))
        assertEquals(2, provider.posts.size)
    }
    @Test fun fallbackBindingRejectsNativePublishersBeforeProviderWrites() = fixture {
        val (id, body, identity) = prepareJournal()
        recovery.pinPayload(id, "Journal", identity, 3) { body }
        val wire = recovery.pinSanitizedV2Payload(id) { true }
        recovery.startSegmentedJournal(id, 3)
        assertEquals(wire, SqlDelightAppSyncRecoveryStore(db).sanitizedV2Payload(id))
        assertFalse(publisher().publish(id, body, AppSyncV3PayloadKind.Journal, identity,
            AppSyncBlogClassSelection.Existing(BlogClassId(7)), FormHash("test")) is AppSyncV3SegmentPublishResult.ReadyToCommitIndex)
        assertIs<AppSyncSegmentIndexCommitResult.Terminal>(committer().commit(id, body, AppSyncV3PayloadKind.Journal,
            identity, AppSyncBlogClassSelection.Existing(BlogClassId(7)), FormHash("test")))
        assertTrue(provider.posts.isEmpty())
        assertTrue(recovery.segmentWrites(id).isEmpty())
        driver.execute(null, "UPDATE AppSyncV2FallbackPayload SET envelope = envelope || 'x' WHERE sessionId = ?", 1) {
            bindString(0, id)
        }
        assertFailsWith<IllegalArgumentException> { recovery.sanitizedV2Payload(id) }
    }

    @Test fun migration58StartsWithoutInventingFallbackEvidence() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            driver.execute(null, "CREATE TABLE AppSyncRecoverySession (sessionId TEXT NOT NULL PRIMARY KEY)", 0)
            driver.execute(null, "INSERT INTO AppSyncRecoverySession VALUES ('existing')", 0)
            Database.Schema.migrate(driver, 58, 59)
            assertNull(Database(driver).appSyncV2FallbackPayloadQueries.getForSession("existing").executeAsOneOrNull())
        }
    }
    private val account = SyncAccountBinding("account")
    private val kind = AppSyncV3PayloadKind.Checkpoint
    private inner class Fixture(val db: Database, val driver: JdbcSqliteDriver) {
        val operations = SqlDelightAppSyncOperationStore(db).also {
            it.initialize("database"); it.bindAccount(account, AppSyncInstallationState.Active)
        }
        val pending = operations.appendLocalOperation(account, SyncDomainId("settings"), SyncEntityId("novelreadersettings.fontsize"),
            1, SyncOperationKind.Put, mapOf("type" to "int", "value" to "18"), SyncCausalContext(), 1, SyncOperationOrigin.UserAction)
        val recovery = SqlDelightAppSyncRecoveryStore(db)
        val session = recovery.createOrResumeSegmentedCheckpoint(account, "checkpoint", "source", 2).also {
            recovery.startSegmentedJournal(it.sessionId, 3)
        }
        val codec = AppSyncV3SegmentCodec(AppSyncPayloadBudget(4096))
        val document = AppSyncCanonicalCheckpoint("checkpoint", account.value, 1,
            (1..200).associate { (it.toString().encodeUtf8().sha256().hex() + ":epoch") to 1L }, emptyList())
        val envelope = AppSyncV3DocumentCodec().encodeCheckpoint(document)
        val provider = Provider()
        fun publisher(gate: suspend () -> Boolean = { true }, discovery: suspend (String, String) -> AppSyncV3ArtifactDiscovery = AppSyncV3ArtifactReconciler(provider, BlogClassId(7))::discover) =
            AppSyncV3SegmentPublisher(provider, SqlDelightAppSyncRecoveryStore(db), { 10 }, codec, gate, discovery)
        fun committer() = AppSyncV3IndexCommitter(provider, SqlDelightAppSyncRecoveryStore(db), publisher(), { 20 }, { true })
        suspend fun commit() = committer().commit(session.sessionId, envelope, kind, "checkpoint",
            AppSyncBlogClassSelection.Existing(BlogClassId(7)), FormHash("test"))
        fun prepareJournal(): Triple<String, String, String> {
            recovery.rollbackPreCommit(session.sessionId)
            val staged = recovery.createOrResumeSegmentedJournal(account, setOf(pending.operationId.value), "journal", 2)
            val imported = assertIs<AppSyncCanonicalOperationImport.Accepted>(AppSyncCanonicalOperationImporter().import(account.value, pending))
            val identity = "${staged.targetDeviceId.value}:${staged.targetDeviceEpoch.value}"
            val journal = AppSyncCanonicalJournal(AppSyncCanonicalOperationBlock(account.value, listOf(imported.operation)),
                staged.targetDeviceId.value, staged.targetDeviceEpoch.value, staged.targetWriterNonce.value,
                pending.sequence.value, pending.sequence.value, emptyMap(), emptyList(), 1, 3, 3, "test", pending.sequence.value)
            return Triple(staged.sessionId, AppSyncV3DocumentCodec().encodeJournal(identity, journal), identity)
        }
        suspend fun publish(publisher: AppSyncV3SegmentPublisher = publisher(), source: String = envelope) = publisher.publish(
            session.sessionId, source, kind, "checkpoint", AppSyncBlogClassSelection.Existing(BlogClassId(7)), FormHash("test"))
    }
    private fun fixture(block: suspend Fixture.() -> Unit): Unit = runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { Database.Schema.create(it); Fixture(Database(it), it).block() }
    }

    private fun Fixture.prepareLegacyMigration(): Pair<String, NativeRecoveryPayload> {
        recovery.rollbackPreCommit(session.sessionId)
        val codec = AppSyncCheckpointEnvelopeCodec()
        val payload = codec.createPayload("legacy-base", account, SyncCausalContext(),
            me.thenano.yamibo.yamibo_app.repository.backup.YamiboBackupFile(appVersionCode = 1, createdAt = 1), emptyList(), emptyList(), 1)
        val body = codec.encode(payload)
        val parsed = assertIs<AppSyncCheckpointValidation.Valid>(codec.validate(body)).envelope
        val index = AppSyncIndexEnvelopeCodec().encode(AppSyncIndexPayload(account,
            checkpoints = listOf(AppSyncIndexCheckpointReference(payload.checkpointId, 700, parsed.fingerprint)), updatedAtEpochMillis = 1))
        val source = assertNotNull(AppSyncVerifiedLegacyCheckpoint.verify(account.value, 700, index, body))
        val cloud = me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncJournalLoadResult.Success(emptyList(),
            checkpoints = listOf(me.thenano.yamibo.yamibo_app.repository.appsync.engine.LoadedAppSyncCheckpoint("700", parsed)),
            authoritativeDiscovery = true, verifiedLegacyCheckpoints = listOf(source))
        val id = me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncLegacyMigrationStarter(db, operations, recovery,
            { 10 }, { true }).start(account, cloud).getOrThrow()
        provider.artifacts[800] = AppSyncBlogWriteRequest(BlogId(800), APP_SYNC_INDEX_TITLE, index,
            AppSyncBlogClassSelection.Existing(BlogClassId(7)), FormHash("test"))
        recovery.startSegmentedJournal(id, 11)
        return id to recovery.nativePayload(id)
    }
    private suspend fun Fixture.commitMigration(id: String, payload: NativeRecoveryPayload) = committer().commit(
        id, payload.body, payload.kind, payload.identity, AppSyncBlogClassSelection.Existing(BlogClassId(7)), FormHash("test"))

    @Test fun legacyMigrationRejectsMissingOrChangedSourceIndexBeforeAnyPublication() = fixture {
        val (id, payload) = prepareLegacyMigration()
        val request = provider.artifacts.getValue(800)
        val codec = AppSyncIndexEnvelopeCodec()
        val original = assertIs<AppSyncIndexValidation.Valid>(codec.validate(request.message)).envelope.payload
        val wrong = listOf(original.copy(checkpoints = emptyList()), original.copy(updatedAtEpochMillis = 2),
            original.copy(checkpoints = original.checkpoints.map { it.copy(blogId = 701) }),
            original.copy(checkpoints = original.checkpoints.map { it.copy(fingerprint = "changed") }))
        provider.artifacts.remove(800)
        assertIs<AppSyncSegmentIndexCommitResult.Conflict>(commitMigration(id, payload))
        for (changed in wrong) {
            provider.artifacts[800] = request.copy(message = codec.encode(changed))
            assertIs<AppSyncSegmentIndexCommitResult.Conflict>(commitMigration(id, payload))
        }
        assertTrue(provider.posts.isEmpty())
        assertNull(recovery.nativeIndexIntent(id))
        assertEquals(listOf(pending), operations.pendingOperations())
        provider.artifacts[800] = request
        assertIs<AppSyncSegmentIndexCommitResult.Verified>(commitMigration(id, payload))
        val written = assertIs<AppSyncIndexValidation.Valid>(codec.validate(provider.posts.last().message)).envelope.payload
        assertTrue(original.checkpoints.single() in written.checkpoints)
        assertEquals(listOf(pending), operations.pendingOperations())
    }

    @Test fun legacyMigrationLostIndexResponseResumesTheExactFrozenIntentWithoutAnotherPost() = fixture {
        val (id, payload) = prepareLegacyMigration()
        assertIs<AppSyncV3SegmentPublishResult.ReadyToCommitIndex>(publisher().publish(id, payload.body, payload.kind, payload.identity,
            AppSyncBlogClassSelection.Existing(BlogClassId(7)), FormHash("test")))
        provider.timeoutAt = provider.posts.size + 1
        provider.storeTimedOut = true
        provider.onList = { if (provider.posts.size == provider.timeoutAt) provider.failIndexRead = true }
        assertIs<AppSyncSegmentIndexCommitResult.Retryable>(commitMigration(id, payload))
        val count = provider.posts.size
        val intent = assertNotNull(recovery.nativeIndexIntent(id))
        assertEquals(false, recovery.session(id)?.indexCommitted)
        provider.onList = {}
        provider.failIndexRead = false
        assertIs<AppSyncSegmentIndexCommitResult.Verified>(commitMigration(id, payload))
        assertEquals(count, provider.posts.size)
        assertEquals(intent, recovery.nativeIndexIntent(id))
        assertEquals(true, recovery.session(id)?.indexCommitted)
        assertEquals(listOf(pending), operations.pendingOperations())
    }

    @Test fun nativeCommitEvidenceCannotDropTheFrozenLegacySource() = fixture {
        val (id, payload) = prepareLegacyMigration()
        val root = assertIs<AppSyncV3SegmentPublishResult.ReadyToCommitIndex>(publisher().publish(id, payload.body, payload.kind, payload.identity,
            AppSyncBlogClassSelection.Existing(BlogClassId(7)), FormHash("test")))
        val incomplete = AppSyncIndexEnvelopeCodec().encode(AppSyncIndexPayload(account,
            checkpoints = listOf(AppSyncIndexCheckpointReference(payload.identity, root.rootBlogId.value,
                root.root.metadata.canonicalFingerprint)), updatedAtEpochMillis = 20))
        recovery.pinNativeIndexIntent(id, NativeRecoveryIndexIntent(incomplete, 800, provider.artifacts.getValue(800).message.encodeUtf8().sha256().hex()))
        assertFails { recovery.markNativeIndexCommitted(id, 800, incomplete, 20) }
        assertEquals(false, recovery.session(id)?.indexCommitted)
        assertEquals(AppSyncRecoveryPhase.CommittingIndex, recovery.session(id)?.phase)
        assertEquals(listOf(pending), operations.pendingOperations())
    }

    @Test fun legacyIndexChangeDuringSegmentPublicationPreventsIndexCommit() = fixture {
        val (id, payload) = prepareLegacyMigration()
        val original = provider.artifacts.getValue(800)
        val codec = AppSyncIndexEnvelopeCodec()
        val parsed = assertIs<AppSyncIndexValidation.Valid>(codec.validate(original.message)).envelope.payload
        provider.onList = {
            if (provider.posts.isNotEmpty()) provider.artifacts[800] = original.copy(message = codec.encode(parsed.copy(updatedAtEpochMillis = 2)))
        }
        assertIs<AppSyncSegmentIndexCommitResult.Conflict>(commitMigration(id, payload))
        assertTrue(provider.posts.isNotEmpty())
        assertTrue(provider.posts.none { it.title == APP_SYNC_INDEX_TITLE })
        assertNull(recovery.nativeIndexIntent(id))
        assertEquals(false, recovery.session(id)?.indexCommitted)
        assertEquals(listOf(pending), operations.pendingOperations())
    }

    @Test fun everyArtifactIsReadBackAndRestartReusesFrozenGenerationWithoutAcknowledgement() = fixture {
        val result = assertIs<AppSyncV3SegmentPublishResult.ReadyToCommitIndex>(publish())
        val count = provider.posts.size
        assertEquals(codec.plan(envelope, account.value, kind).drafts.size + 1, count)
        assertEquals(count, provider.reads)
        assertEquals(AppSyncRecoveryPhase.CommittingIndex, recovery.session(session.sessionId)?.phase)
        assertEquals(false, recovery.session(session.sessionId)?.indexCommitted)
        val changed = AppSyncV3DocumentCodec().encodeCheckpoint(document.copy(createdAtEpochMillis = 99))
        val replay = assertIs<AppSyncV3SegmentPublishResult.ReadyToCommitIndex>(publish(publisher(), changed))
        assertEquals(result, replay)
        assertEquals(count, provider.posts.size)
        assertEquals(count * 2, provider.reads)
        assertEquals(listOf(pending), operations.pendingOperations())
        assertTrue(operations.verifiedCheckpoints().isEmpty())
        assertTrue(provider.posts.none { it.title == APP_SYNC_INDEX_TITLE })
    }

    @Test fun unknownSegmentOutcomeCannotCreateAgainUntilAuthoritativeAbsence() = fixture {
        provider.timeoutAt = 2
        assertIs<AppSyncV3SegmentPublishResult.Retryable>(publish(publisher(discovery = { _, _ -> AppSyncV3ArtifactDiscovery.Unknown })))
        assertEquals(2, provider.posts.size)
        assertEquals(1, recovery.segmentWrites(session.sessionId).count { it.blogId != null })
        provider.timeoutAt = null
        assertIs<AppSyncV3SegmentPublishResult.Retryable>(publish(publisher(discovery = { _, _ -> AppSyncV3ArtifactDiscovery.Unknown })))
        assertEquals(2, provider.posts.size)
        assertIs<AppSyncV3SegmentPublishResult.ReadyToCommitIndex>(publish())
        assertEquals(listOf(pending), operations.pendingOperations())
    }

    @Test fun lostSegmentAndRootResponsesAreReconciledWithoutDuplicatePosts() = fixture {
        val segments = codec.plan(envelope, account.value, kind).drafts.size
        provider.storeTimedOut = true
        provider.timeoutAt = 2
        assertIs<AppSyncV3SegmentPublishResult.Retryable>(publish(publisher(discovery = { _, _ -> AppSyncV3ArtifactDiscovery.Unknown })))
        provider.timeoutAt = segments + 1
        var unknownRoot = true
        val discovery: suspend (String, String) -> AppSyncV3ArtifactDiscovery = { title, sha ->
            if (title.startsWith(AppSyncJournalDefaults.CHECKPOINT_TITLE_PREFIX) && unknownRoot) AppSyncV3ArtifactDiscovery.Unknown
            else provider.discover(title, sha)
        }
        assertIs<AppSyncV3SegmentPublishResult.Retryable>(publish(publisher(discovery = discovery)))
        assertEquals(segments + 1, provider.posts.size)
        assertEquals(AppSyncRecoveryPhase.PublishingRoot, recovery.session(session.sessionId)?.phase)
        assertNotNull(db.appSyncOperationQueries.getRecoveryPayload(session.sessionId).executeAsOne().rootIntentFingerprint)
        unknownRoot = false
        assertIs<AppSyncV3SegmentPublishResult.ReadyToCommitIndex>(publish(publisher(discovery = discovery)))
        assertEquals(segments + 1, provider.posts.size)
    }

    @Test fun gateAndAuthenticationStopWritesAndReadbackMismatchCannotAdvanceProgress() = fixture {
        assertIs<AppSyncV3SegmentPublishResult.Disabled>(publish(AppSyncV3SegmentPublisher(provider, recovery, { 10 }, codec)))
        assertTrue(provider.posts.isEmpty())
        var checks = 0
        assertIs<AppSyncV3SegmentPublishResult.Disabled>(publish(publisher(gate = { ++checks == 1 })))
        assertTrue(provider.posts.isEmpty())
        provider.authOnRead = true
        assertIs<AppSyncV3SegmentPublishResult.FormExpired>(publish())
        assertTrue(recovery.segmentWrites(session.sessionId).none { it.blogId != null })
        provider.authOnRead = false
        provider.wrongRead = true
        assertIs<AppSyncV3SegmentPublishResult.NeedsAttention>(publish())
        assertTrue(recovery.segmentWrites(session.sessionId).none { it.blogId != null })
        assertEquals(1, provider.posts.size)
    }

    @Test fun restartKeepsFrozenPlanEvenWhenAllRuntimePlanningDefaultsChange() = fixture {
        provider.timeoutAt = 2
        assertIs<AppSyncV3SegmentPublishResult.Retryable>(publish(publisher(discovery = { _, _ -> AppSyncV3ArtifactDiscovery.Unknown })))
        assertEquals(codec.configuration, recovery.nativeSegmentConfiguration(session.sessionId))
        assertIs<AppSyncV3SegmentPublishResult.NeedsAttention>(publish(publisher(discovery = { _, _ -> AppSyncV3ArtifactDiscovery.Conflict })))
        assertEquals(2, provider.posts.size)
        val verified = recovery.segmentWrites(session.sessionId).single { it.blogId != null }
        provider.timeoutAt = null
        val changed = AppSyncV3SegmentPublisher(provider, SqlDelightAppSyncRecoveryStore(db), { 10 },
            AppSyncV3SegmentCodec(AppSyncPayloadBudget(8192), maximumSegments = 2, maximumEnvelopeChars = 100),
            { true }, provider::discover)
        val resumed = assertIs<AppSyncV3SegmentPublishResult.ReadyToCommitIndex>(publish(changed))
        assertEquals(codec.plan(envelope, account.value, kind).drafts.size, resumed.root.count)
        assertEquals(verified, recovery.segmentWrites(session.sessionId).single { it.segmentIndex == verified.segmentIndex })
        assertEquals(codec.configuration, recovery.nativeSegmentConfiguration(session.sessionId))
        assertFailsWith<IllegalArgumentException> {
            recovery.pinNativeSegmentConfiguration(session.sessionId, codec.configuration.copy(targetChars = 8192))
        }
    }

    @Test fun migratedUnconfiguredPlanIsPinnedOnlyAfterEveryExistingIntentMatches() = fixture {
        provider.timeoutAt = 2
        assertIs<AppSyncV3SegmentPublishResult.Retryable>(publish(publisher(discovery = { _, _ -> AppSyncV3ArtifactDiscovery.Unknown })))
        driver.execute(null, "UPDATE AppSyncRecoveryPayload SET segmentPlanVersion=NULL, segmentTargetChars=NULL, " +
            "segmentMaximumCount=NULL, segmentMaximumEnvelopeChars=NULL", 0)
        val changed = AppSyncV3SegmentPublisher(provider, recovery, { 10 }, AppSyncV3SegmentCodec(AppSyncPayloadBudget(8192)),
            { true }, provider::discover)
        assertIs<AppSyncV3SegmentPublishResult.NeedsAttention>(publish(changed))
        assertNull(recovery.nativeSegmentConfiguration(session.sessionId))
        assertEquals(2, provider.posts.size)
        provider.timeoutAt = null
        assertIs<AppSyncV3SegmentPublishResult.ReadyToCommitIndex>(publish())
        assertEquals(codec.configuration, recovery.nativeSegmentConfiguration(session.sessionId))
    }

    @Test fun corruptOrUnsupportedConfigurationCannotProduceRemoteSideEffects() = fixture {
        provider.timeoutAt = 2
        assertIs<AppSyncV3SegmentPublishResult.Retryable>(publish(publisher(discovery = { _, _ -> AppSyncV3ArtifactDiscovery.Unknown })))
        val rows = recovery.segmentWrites(session.sessionId)
        for (assignment in listOf("segmentPlanVersion=2", "segmentTargetChars=NULL", "segmentMaximumCount=1",
            "segmentMaximumEnvelopeChars=4294967396")) {
            driver.execute(null, "UPDATE AppSyncRecoveryPayload SET $assignment", 0)
            assertIs<AppSyncV3SegmentPublishResult.NeedsAttention>(publish())
            assertEquals(2, provider.posts.size)
            assertEquals(rows, recovery.segmentWrites(session.sessionId))
            driver.execute(null, "UPDATE AppSyncRecoveryPayload SET segmentPlanVersion=1, segmentTargetChars=4096, " +
                "segmentMaximumCount=4096, segmentMaximumEnvelopeChars=16781312", 0)
        }
    }

    @Test fun migrationPreservesLegacyPayloadAndDoesNotInventPlanningEvidence() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            driver.execute(null, "CREATE TABLE AppSyncRecoveryPayload(sessionId TEXT PRIMARY KEY, canonicalEnvelope TEXT)", 0)
            driver.execute(null, "INSERT INTO AppSyncRecoveryPayload VALUES ('old', 'frozen-body')", 0)
            Database.Schema.migrate(driver, oldVersion = 52, newVersion = 53)
            driver.executeQuery(null, "SELECT canonicalEnvelope, segmentPlanVersion, segmentTargetChars, " +
                "segmentMaximumCount, segmentMaximumEnvelopeChars FROM AppSyncRecoveryPayload WHERE sessionId='old'", { cursor ->
                assertTrue(cursor.next().value)
                assertEquals("frozen-body", cursor.getString(0))
                for (column in 1..4) assertNull(cursor.getLong(column))
                app.cash.sqldelight.db.QueryResult.Value(Unit)
            }, 0)
        }
    }

    @Test fun discoveryCompletesPaginationBeforeClaimingFoundOrAbsent() = fixture {
        assertIs<AppSyncV3SegmentPublishResult.ReadyToCommitIndex>(publish())
        provider.pageSize = 1
        val artifact = provider.artifacts.entries.last()
        val reconciler = AppSyncV3ArtifactReconciler(provider, BlogClassId(7))
        assertEquals(AppSyncV3ArtifactDiscovery.Found(BlogId(artifact.key)),
            reconciler.discover(artifact.value.title, artifact.value.message.encodeUtf8().sha256().hex()))
        assertEquals(provider.artifacts.size, provider.listReads)
        provider.listFailureAt = 2
        assertEquals(AppSyncV3ArtifactDiscovery.Unknown, reconciler.discover("missing", "0".repeat(64)))
        provider.listFailureAt = null
        assertEquals(AppSyncV3ArtifactDiscovery.Absent, reconciler.discover("missing", "0".repeat(64)))
        assertEquals(AppSyncV3ArtifactDiscovery.Unknown,
            AppSyncV3ArtifactReconciler(provider, BlogClassId(7), maxPages = 1).discover("missing", "0".repeat(64)))
        provider.repeatPage = true
        assertEquals(AppSyncV3ArtifactDiscovery.Unknown, reconciler.discover("missing", "0".repeat(64)))
    }

    @Test fun nativeIndexCommitRequiresExactFrozenCanonicalReferenceAndPersistsEvidenceAtomically() = fixture {
        val root = assertIs<AppSyncV3SegmentPublishResult.ReadyToCommitIndex>(publish())
        val reference = AppSyncIndexCheckpointReference("checkpoint", root.rootBlogId.value, root.root.metadata.canonicalFingerprint)
        val postsBefore = provider.posts.size
        val legacy = AppSyncSegmentIndexCommitter(provider, SqlDelightAppSyncRemoteBlogStore(db), recovery, nowMillis = { 20 })
        assertIs<AppSyncSegmentIndexCommitResult.Terminal>(legacy.commitCheckpointRoot(session.sessionId, "checkpoint",
            AppSyncBlogClassSelection.Existing(BlogClassId(7)), FormHash("test")))
        assertEquals(postsBefore, provider.posts.size)
        assertEquals(0, provider.listReads)
        val index = AppSyncIndexPayload(account, checkpoints = listOf(reference), updatedAtEpochMillis = 20)
        fun body(value: AppSyncIndexPayload = index) = AppSyncIndexEnvelopeCodec().encode(value).replace("\n", "<br>")
        recovery.pinNativeIndexIntent(session.sessionId, NativeRecoveryIndexIntent(AppSyncIndexEnvelopeCodec().encode(index), null, null))
        assertFailsWith<IllegalArgumentException> { recovery.markIndexCommitted(session.sessionId, 20) }
        for (bad in listOf(index.copy(accountBinding = SyncAccountBinding("other")),
            index.copy(checkpoints = emptyList()),
            index.copy(checkpoints = listOf(reference.copy(blogId = 999))),
            index.copy(checkpoints = listOf(reference.copy(fingerprint = root.root.envelopeSha256))))) {
            assertFailsWith<IllegalArgumentException> { recovery.markNativeIndexCommitted(session.sessionId, 900, body(bad), 20) }
            assertEquals(false, recovery.session(session.sessionId)?.indexCommitted)
            assertNull(db.appSyncOperationQueries.getRecoveryPayload(session.sessionId).executeAsOne().verifiedIndexBlogId)
        }
        assertFailsWith<IllegalStateException> {
            db.transaction {
                recovery.markNativeIndexCommitted(session.sessionId, 900, body(), 21)
                error("abort outer transaction")
            }
        }
        assertEquals(AppSyncRecoveryPhase.CommittingIndex, recovery.session(session.sessionId)?.phase)
        assertNull(db.appSyncOperationQueries.getRecoveryPayload(session.sessionId).executeAsOne().verifiedIndexBlogId)
        recovery.markNativeIndexCommitted(session.sessionId, 900, body(), 22)
        val restarted = SqlDelightAppSyncRecoveryStore(db)
        restarted.markNativeIndexCommitted(session.sessionId, 900, body(), 23)
        assertEquals(AppSyncRecoveryPhase.ActivatingLocal, restarted.session(session.sessionId)?.phase)
        val evidence = db.appSyncOperationQueries.getRecoveryPayload(session.sessionId).executeAsOne()
        assertEquals(900L, evidence.verifiedIndexBlogId)
        assertEquals(22L, evidence.indexVerifiedAtEpochMillis)
        assertNotNull(evidence.verifiedIndexFingerprint)
        assertFailsWith<IllegalArgumentException> { restarted.markNativeIndexCommitted(session.sessionId, 901, body(), 24) }
        assertEquals(listOf(pending), operations.pendingOperations())
        assertTrue(operations.verifiedCheckpoints().isEmpty())
    }

    @Test fun journalIndexCommitBindsWriterReplicaAndRequiresCanonicalFingerprint() = fixture {
        recovery.rollbackPreCommit(session.sessionId)
        val journalSession = recovery.createOrResumeSegmentedJournal(account, setOf(pending.operationId.value), "journal", 2)
        recovery.startSegmentedJournal(journalSession.sessionId, 3)
        val identity = "${journalSession.targetDeviceId.value}:${journalSession.targetDeviceEpoch.value}"
        val journal = AppSyncCanonicalJournal(AppSyncCanonicalOperationBlock(account.value, emptyList()),
            journalSession.targetDeviceId.value, journalSession.targetDeviceEpoch.value, journalSession.targetWriterNonce.value,
            0, 0, emptyMap(), emptyList(), 1, 3, 3, "test", 0)
        val frozen = AppSyncV3DocumentCodec().encodeJournal(identity, journal)
        val root = assertIs<AppSyncV3SegmentPublishResult.ReadyToCommitIndex>(publisher().publish(journalSession.sessionId,
            frozen, AppSyncV3PayloadKind.Journal, identity, AppSyncBlogClassSelection.Existing(BlogClassId(7)), FormHash("test")))
        val ref = AppSyncIndexJournalReference(identity, root.rootBlogId.value, root.root.metadata.canonicalFingerprint)
        fun index(reference: AppSyncIndexJournalReference) = AppSyncIndexEnvelopeCodec().encode(
            AppSyncIndexPayload(account, journals = listOf(reference), updatedAtEpochMillis = 20))
        recovery.pinNativeIndexIntent(journalSession.sessionId, NativeRecoveryIndexIntent(index(ref), null, null))
        for (bad in listOf(ref.copy(replicaKey = "other"), ref.copy(fingerprint = null), ref.copy(fingerprint = root.root.envelopeSha256))) {
            assertFailsWith<IllegalArgumentException> { recovery.markNativeIndexCommitted(journalSession.sessionId, 900, index(bad), 20) }
        }
        assertEquals(false, recovery.session(journalSession.sessionId)?.indexCommitted)
        recovery.markNativeIndexCommitted(journalSession.sessionId, 900, index(ref), 21)
        assertEquals(AppSyncRecoveryPhase.ActivatingLocal, recovery.session(journalSession.sessionId)?.phase)
        // This synthetic journal contains no source operation: publication alone cannot acknowledge it.
        assertFailsWith<IllegalArgumentException> { recovery.activateCommittedSession(journalSession.sessionId, 22) }
        assertEquals(AppSyncRecoveryPhase.ActivatingLocal, recovery.session(journalSession.sessionId)?.phase)
        assertEquals(listOf(pending), operations.pendingOperations())
    }

    @Test fun nativeCommitCreatesIndexOnlyAfterRootAndPreservesPendingSources() = fixture {
        assertIs<AppSyncSegmentIndexCommitResult.Verified>(commit())
        assertEquals(APP_SYNC_INDEX_TITLE, provider.posts.last().title)
        val index = assertIs<AppSyncIndexValidation.Valid>(AppSyncIndexEnvelopeCodec().validate(provider.posts.last().message)).envelope.payload
        assertEquals(recovery.session(session.sessionId)?.rootBlogId, index.checkpoints.single().blogId.toLong())
        val row = db.appSyncOperationQueries.getRecoveryPayload(session.sessionId).executeAsOne()
        assertNotNull(row.verifiedIndexBlogId)
        assertNotNull(row.indexIntentSha256)
        assertEquals(provider.posts.last().message, recovery.nativeIndexIntent(session.sessionId)?.body)
        val count = provider.posts.size
        assertIs<AppSyncSegmentIndexCommitResult.Verified>(commit())
        assertEquals(count, provider.posts.size)
        assertEquals(listOf(pending), operations.pendingOperations())
        assertTrue(operations.verifiedCheckpoints().isEmpty())
        assertFailsWith<IllegalArgumentException> { recovery.activateCommittedSession(session.sessionId, 30) }
        assertEquals(AppSyncRecoveryPhase.ActivatingLocal, recovery.session(session.sessionId)?.phase)
    }

    @Test fun nativeJournalActivationAcknowledgesOnlyExactPublishedSourcesAndRollsBackAtomically() = fixture {
        recovery.rollbackPreCommit(session.sessionId)
        val journalSession = recovery.createOrResumeSegmentedJournal(account, setOf(pending.operationId.value), "journal", 2)
        recovery.startSegmentedJournal(journalSession.sessionId, 3)
        val imported = assertIs<AppSyncCanonicalOperationImport.Accepted>(AppSyncCanonicalOperationImporter().import(account.value, pending))
        val identity = "${journalSession.targetDeviceId.value}:${journalSession.targetDeviceEpoch.value}"
        val document = AppSyncCanonicalJournal(AppSyncCanonicalOperationBlock(account.value, listOf(imported.operation)),
            journalSession.targetDeviceId.value, journalSession.targetDeviceEpoch.value, journalSession.targetWriterNonce.value,
            pending.sequence.value, pending.sequence.value, emptyMap(), emptyList(), 1, 3, 3, "test", pending.sequence.value)
        val frozen = AppSyncV3DocumentCodec().encodeJournal(identity, document)
        suspend fun commitJournal() = committer().commit(journalSession.sessionId, frozen, AppSyncV3PayloadKind.Journal, identity,
            AppSyncBlogClassSelection.Existing(BlogClassId(7)), FormHash("test"))
        assertIs<AppSyncSegmentIndexCommitResult.Verified>(commitJournal())
        val later = operations.appendLocalOperation(account, SyncDomainId("settings"), SyncEntityId("novelreadersettings.fontsize"),
            1, SyncOperationKind.Put, mapOf("type" to "int", "value" to "19"), SyncCausalContext(), 30, SyncOperationOrigin.UserAction)
        val before = operations.installation()
        assertFailsWith<IllegalStateException> {
            db.transaction {
                recovery.activateCommittedSession(journalSession.sessionId, 31)
                error("abort activation")
            }
        }
        assertEquals(before, operations.installation())
        assertEquals(listOf(pending, later), operations.pendingOperations())
        assertEquals(AppSyncRecoveryPhase.ActivatingLocal, recovery.session(journalSession.sessionId)?.phase)
        val laterFields = db.appSyncOperationQueries.getOutboxOperation(later.operationId.value).executeAsOne().fieldsJson
        // An unrelated damaged row must neither be decoded nor acknowledged by this session.
        driver.execute(null, "UPDATE AppSyncOutbox SET fieldsJson = ? WHERE operationId = ?", 2) {
            bindString(0, "invalid-json"); bindString(1, later.operationId.value)
        }
        SqlDelightAppSyncRecoveryStore(db).activateCommittedSession(journalSession.sessionId, 32)
        val untouched = db.appSyncOperationQueries.getOutboxOperation(later.operationId.value).executeAsOne()
        assertEquals("invalid-json", untouched.fieldsJson)
        assertEquals("PENDING_LOCAL", untouched.lifecycle)
        driver.execute(null, "UPDATE AppSyncOutbox SET fieldsJson = ? WHERE operationId = ?", 2) {
            bindString(0, laterFields); bindString(1, later.operationId.value)
        }
        assertEquals(listOf(later), operations.pendingOperations())
        assertEquals(AppSyncOperationLifecycle.Acknowledged, operations.allOutboxOperations().single { it.first == pending }.second)
        assertEquals(AppSyncRecoveryPhase.Completed, recovery.session(journalSession.sessionId)?.phase)
        assertEquals(before?.nextSequence, operations.installation()?.nextSequence)
        val activated = operations.installation()
        recovery.activateCommittedSession(journalSession.sessionId, 33)
        assertEquals(activated, operations.installation())
        val count = provider.posts.size
        assertIs<AppSyncSegmentIndexCommitResult.Verified>(commitJournal())
        assertEquals(count, provider.posts.size)
        assertTrue(operations.verifiedCheckpoints().isEmpty())
    }

    @Test fun nativeCommitReconcilesLostIndexResponseAfterRestartWithoutSecondPost() = fixture {
        assertIs<AppSyncV3SegmentPublishResult.ReadyToCommitIndex>(publish())
        provider.timeoutAt = provider.posts.size + 1
        provider.storeTimedOut = true
        provider.failIndexRead = true
        assertIs<AppSyncSegmentIndexCommitResult.Retryable>(commit())
        assertEquals(false, recovery.session(session.sessionId)?.indexCommitted)
        val intent = assertNotNull(recovery.nativeIndexIntent(session.sessionId))
        val count = provider.posts.size
        provider.failIndexRead = false
        assertIs<AppSyncSegmentIndexCommitResult.Verified>(commit())
        assertEquals(count, provider.posts.size)
        assertEquals(intent, recovery.nativeIndexIntent(session.sessionId))
        assertEquals(listOf(pending), operations.pendingOperations())
    }

    @Test fun nativeIndexUpdateKeepsOtherReferencesAndRejectsConcurrentBaseChange() = fixture {
        assertIs<AppSyncV3SegmentPublishResult.ReadyToCommitIndex>(publish())
        val base = AppSyncIndexPayload(account, journals = listOf(AppSyncIndexJournalReference("other", 700, "other-sha")),
            checkpoints = listOf(AppSyncIndexCheckpointReference("old", 701, "old-sha")), updatedAtEpochMillis = 5)
        fun request(value: AppSyncIndexPayload) = AppSyncBlogWriteRequest(BlogId(800), APP_SYNC_INDEX_TITLE,
            AppSyncIndexEnvelopeCodec().encode(value), AppSyncBlogClassSelection.Existing(BlogClassId(7)), FormHash("test"))
        provider.artifacts[800] = request(base)
        provider.onList = { call -> if (call == 2) provider.artifacts[800] = request(base.copy(updatedAtEpochMillis = 6)) }
        val count = provider.posts.size
        assertIs<AppSyncSegmentIndexCommitResult.Conflict>(commit())
        assertEquals(count, provider.posts.size)
        val intent = assertNotNull(recovery.nativeIndexIntent(session.sessionId))
        assertEquals(800L, intent.targetBlogId)
        provider.onList = {}
        assertIs<AppSyncSegmentIndexCommitResult.Conflict>(commit())
        assertEquals(count, provider.posts.size)
        // Restore exactly the observed base; the immutable intent is now safe to retry.
        provider.artifacts[800] = request(base)
        assertIs<AppSyncSegmentIndexCommitResult.Verified>(commit())
        assertEquals(BlogId(800), provider.posts.last().blogId)
        val merged = assertIs<AppSyncIndexValidation.Valid>(AppSyncIndexEnvelopeCodec().validate(provider.posts.last().message)).envelope.payload
        assertEquals(base.journals, merged.journals)
        assertTrue(base.checkpoints.single() in merged.checkpoints)
        assertEquals(2, merged.checkpoints.size)
    }

    @Test fun nativeIndexGateAndIncompleteDiscoveryCannotCreateOrCommit() = fixture {
        val disabled = AppSyncV3IndexCommitter(provider, recovery, publisher(), { 20 })
        assertIs<AppSyncSegmentIndexCommitResult.Terminal>(disabled.commit(session.sessionId, envelope, kind, "checkpoint",
            AppSyncBlogClassSelection.Existing(BlogClassId(7)), FormHash("test")))
        assertTrue(provider.posts.isEmpty())
        assertIs<AppSyncV3SegmentPublishResult.ReadyToCommitIndex>(publish())
        val count = provider.posts.size
        provider.listFailureAt = 1
        assertIs<AppSyncSegmentIndexCommitResult.Retryable>(commit())
        assertNull(recovery.nativeIndexIntent(session.sessionId))
        provider.listFailureAt = null
        var checks = 0
        val gated = AppSyncV3IndexCommitter(provider, recovery, publisher(), { 20 }, { ++checks == 1 })
        assertIs<AppSyncSegmentIndexCommitResult.Terminal>(gated.commit(session.sessionId, envelope, kind, "checkpoint",
            AppSyncBlogClassSelection.Existing(BlogClassId(7)), FormHash("test")))
        assertNotNull(recovery.nativeIndexIntent(session.sessionId))
        assertEquals(count, provider.posts.size)
        assertEquals(false, recovery.session(session.sessionId)?.indexCommitted)
    }

    @Test fun nativeIndexMustRetainTheCheckpointUsedByTheCurrentCloudPlan() = fixture {
        assertIs<AppSyncV3SegmentPublishResult.ReadyToCommitIndex>(publish())
        val required = AppSyncCanonicalCheckpoint("required-base", account.value, 1, emptyMap(), emptyList())
        val reference = AppSyncIndexCheckpointReference(required.checkpointId, 700,
            AppSyncCanonicalCheckpointCodec().encode(required).sha256().hex())
        val base = AppSyncIndexPayload(account, checkpoints = listOf(reference), updatedAtEpochMillis = 5)
        val body = AppSyncIndexEnvelopeCodec().encode(base)
        val verified = assertNotNull(AppSyncVerifiedCanonicalCheckpoint.verify(account.value, 700, body,
            AppSyncV3DocumentCodec().encodeCheckpoint(required)))
        suspend fun attempt() = committer().commit(session.sessionId, envelope, kind, "checkpoint",
            AppSyncBlogClassSelection.Existing(BlogClassId(7)), FormHash("test"), requiredCheckpoint = verified)
        val posts = provider.posts.size
        assertIs<AppSyncSegmentIndexCommitResult.Conflict>(attempt())
        assertEquals(posts, provider.posts.size)
        assertNull(recovery.nativeIndexIntent(session.sessionId))
        val request = AppSyncBlogWriteRequest(BlogId(800), APP_SYNC_INDEX_TITLE, body,
            AppSyncBlogClassSelection.Existing(BlogClassId(7)), FormHash("test"))
        provider.artifacts[800] = request.copy(message = AppSyncIndexEnvelopeCodec().encode(base.copy(
            checkpoints = listOf(reference.copy(blogId = 701)))))
        assertIs<AppSyncSegmentIndexCommitResult.Conflict>(attempt())
        assertEquals(posts, provider.posts.size)
        assertNull(recovery.nativeIndexIntent(session.sessionId))
        provider.artifacts[800] = request
        assertIs<AppSyncSegmentIndexCommitResult.Verified>(attempt())
        val written = assertIs<AppSyncIndexValidation.Valid>(AppSyncIndexEnvelopeCodec().validate(provider.posts.last().message))
        assertTrue(reference in written.envelope.payload.checkpoints)
    }

    @Test fun nativeCoordinatorContinuesFromStagingThroughVerifiedActivationInOneRun() = fixture {
        val (id, frozen, identity) = prepareJournal()
        val coordinator = AppSyncV3CommitCoordinator(committer(), recovery, { 100 }, canRun = { true })
        suspend fun run() = coordinator.commit(id, frozen, identity, AppSyncBlogClassSelection.Existing(BlogClassId(7)), FormHash("test"))
        val result = assertIs<AppSyncSegmentedJournalCommitResult.Verified>(run())
        assertEquals(setOf(pending.operationId.value), result.acknowledgedOperationIds)
        assertEquals(AppSyncRecoveryPhase.Completed, recovery.session(id)?.phase)
        assertTrue(operations.pendingOperations().isEmpty())
        assertEquals(pending, operations.allOutboxOperations().single().first)
        val posts = provider.posts.size
        assertEquals(result, run())
        assertEquals(posts, provider.posts.size)
    }

    @Test fun legacyPublisherAndCoordinatorCannotAdvanceOrChargeNativeRecovery() = fixture {
        val (id, frozen, identity) = prepareJournal()
        recovery.startSegmentedJournal(id, 3)
        recovery.pinPayload(id, "Journal", identity, 3) { frozen }
        val oldPublisher = AppSyncSegmentPublisher(provider, recovery, nowMillis = { 20 })
        val oldCommitter = AppSyncSegmentIndexCommitter(provider, SqlDelightAppSyncRemoteBlogStore(db), recovery, nowMillis = { 20 })
        val oldCoordinator = AppSyncSegmentedJournalCommitCoordinator(oldPublisher, oldCommitter, recovery, { 20 })
        val selection = AppSyncBlogClassSelection.Existing(BlogClassId(7))
        val form = FormHash("test")
        val before = recovery.session(id)
        assertIs<AppSyncSegmentPublishResult.Terminal>(oldPublisher.publish(id, frozen, AppSyncSegmentPayloadKind.Journal, identity, selection, form))
        assertIs<AppSyncSegmentedJournalCommitResult.Terminal>(oldCoordinator.commit(id, frozen, identity, selection, form))
        assertEquals(before, recovery.session(id))
        assertTrue(provider.posts.isEmpty())
        assertEquals(0, provider.listReads)
        assertIs<AppSyncSegmentIndexCommitResult.Verified>(committer().commit(id, frozen, AppSyncV3PayloadKind.Journal, identity, selection, form))
        val committed = recovery.session(id)
        val count = provider.posts.size
        assertIs<AppSyncSegmentedJournalCommitResult.Terminal>(oldCoordinator.commit(id, frozen, identity, selection, form))
        assertEquals(committed, recovery.session(id))
        assertEquals(AppSyncRecoveryPhase.ActivatingLocal, recovery.session(id)?.phase)
        assertEquals(count, provider.posts.size)
        assertEquals(listOf(pending), operations.pendingOperations())
    }

    @Test fun nativeCoordinatorCannotChargeOrReplaceFrozenLegacyPayload() = fixture {
        val (id, frozen, identity) = prepareJournal()
        recovery.startSegmentedJournal(id, 3)
        recovery.pinPayload(id, "Journal", identity) { "legacy-frozen-body" }
        val before = recovery.session(id)
        val result = AppSyncV3CommitCoordinator(committer(), recovery, { 100 }, canRun = { true }).commit(
            id, frozen, identity, AppSyncBlogClassSelection.Existing(BlogClassId(7)), FormHash("test"))
        assertIs<AppSyncSegmentedJournalCommitResult.Terminal>(result)
        assertEquals(before, recovery.session(id))
        assertEquals(2L, recovery.payloadTransportVersion(id))
        assertEquals("legacy-frozen-body", recovery.pinPayload(id, "Journal", identity) { error("Must retain frozen source") })
        assertTrue(provider.posts.isEmpty())
        assertEquals(0, provider.listReads)
        assertEquals(listOf(pending), operations.pendingOperations())
    }

    @Test fun nativeCoordinatorRespectsDurableRetryDeadlineAndStopsAtThirdFailure() = fixture {
        val (id, frozen, identity) = prepareJournal()
        var now = 100L
        suspend fun run() = AppSyncV3CommitCoordinator(committer(), SqlDelightAppSyncRecoveryStore(db), { now }, canRun = { true })
            .commit(id, frozen, identity, AppSyncBlogClassSelection.Existing(BlogClassId(7)), FormHash("test"))
        provider.timeoutAt = 1
        assertIs<AppSyncSegmentedJournalCommitResult.Retryable>(run())
        assertEquals(1L, recovery.session(id)?.retryCount)
        assertIs<AppSyncSegmentedJournalCommitResult.Retryable>(run())
        assertEquals(1, provider.posts.size)
        assertEquals(1L, recovery.session(id)?.retryCount)
        now = assertNotNull(recovery.session(id)?.nextRetryAtEpochMillis)
        provider.timeoutAt = 2
        assertIs<AppSyncSegmentedJournalCommitResult.Retryable>(run())
        assertEquals(2L, recovery.session(id)?.retryCount)
        now = assertNotNull(recovery.session(id)?.nextRetryAtEpochMillis)
        provider.timeoutAt = 3
        assertIs<AppSyncSegmentedJournalCommitResult.Terminal>(run())
        assertEquals(AppSyncRecoveryPhase.NeedsAttention, recovery.session(id)?.phase)
        assertEquals(3L, recovery.session(id)?.retryCount)
        assertIs<AppSyncSegmentedJournalCommitResult.Terminal>(run())
        assertEquals(3, provider.posts.size)
        assertEquals(listOf(pending), operations.pendingOperations())
        assertNotNull(recovery.resumeRetryExhaustedRecovery(account, now + 1))
        provider.timeoutAt = null
        now++
        assertIs<AppSyncSegmentedJournalCommitResult.Verified>(run())
        assertTrue(operations.pendingOperations().isEmpty())
    }

    @Test fun indexAcknowledgementAloneAndDuplicateCandidatesNeverGrantCommit() = fixture {
        assertIs<AppSyncV3SegmentPublishResult.ReadyToCommitIndex>(publish())
        provider.skipStoreAt = provider.posts.size + 1
        assertIs<AppSyncSegmentIndexCommitResult.Retryable>(commit())
        assertEquals(false, recovery.session(session.sessionId)?.indexCommitted)
        val intent = assertNotNull(recovery.nativeIndexIntent(session.sessionId))
        val indexRequest = provider.posts.last()
        provider.artifacts[800] = indexRequest
        provider.artifacts[801] = indexRequest
        val count = provider.posts.size
        assertIs<AppSyncSegmentIndexCommitResult.Conflict>(commit())
        assertEquals(count, provider.posts.size)
        assertEquals(intent, recovery.nativeIndexIntent(session.sessionId))
        assertEquals(false, recovery.session(session.sessionId)?.indexCommitted)
        provider.artifacts.remove(801)
        provider.authOnRead = true
        assertIs<AppSyncSegmentIndexCommitResult.FormExpired>(commit())
        assertEquals(count, provider.posts.size)
        provider.authOnRead = false
        assertIs<AppSyncSegmentIndexCommitResult.Verified>(commit())
        assertEquals(count, provider.posts.size)
    }

    @Test fun discoveryRejectsDuplicateMatchesAndUnreadableCandidates() = fixture {
        assertIs<AppSyncV3SegmentPublishResult.ReadyToCommitIndex>(publish())
        val artifact = provider.artifacts.entries.last()
        val title = artifact.value.title
        val sha = artifact.value.message.encodeUtf8().sha256().hex()
        val reconciler = AppSyncV3ArtifactReconciler(provider, BlogClassId(7))
        provider.authOnRead = true
        assertEquals(AppSyncV3ArtifactDiscovery.FormExpired, reconciler.discover(title, sha))
        provider.authOnRead = false
        provider.missingRead = true
        assertEquals(AppSyncV3ArtifactDiscovery.Unknown, reconciler.discover(title, sha))
        provider.missingRead = false
        provider.artifacts[999] = artifact.value
        assertEquals(AppSyncV3ArtifactDiscovery.Conflict, reconciler.discover(title, sha))
        provider.artifacts[999] = artifact.value.copy(message = "older generation")
        assertEquals(AppSyncV3ArtifactDiscovery.Found(BlogId(artifact.key)), reconciler.discover(title, sha))
        provider.wrongClass = true
        assertEquals(AppSyncV3ArtifactDiscovery.Unknown, reconciler.discover(title, sha))
    }

    internal class Provider : AppSyncBlogProvider {
        val posts = mutableListOf<AppSyncBlogWriteRequest>()
        val artifacts = linkedMapOf<Int, AppSyncBlogWriteRequest>()
        var timeoutAt: Int? = null
        var storeTimedOut = false
        var skipStoreAt: Int? = null
        var authOnRead = false
        var wrongRead = false
        var reads = 0
        var pageSize = 10_000
        var listReads = 0
        var listFailureAt: Int? = null
        var repeatPage = false
        var missingRead = false
        var wrongClass = false
        var failIndexRead = false
        var onList: (Int) -> Unit = {}
        fun discover(title: String, sha: String): AppSyncV3ArtifactDiscovery {
            val ids = artifacts.filterValues { it.title == title && it.message.encodeUtf8().sha256().hex() == sha }.keys
            return when (ids.size) {
                0 -> AppSyncV3ArtifactDiscovery.Absent
                1 -> AppSyncV3ArtifactDiscovery.Found(BlogId(ids.single()))
                else -> AppSyncV3ArtifactDiscovery.Conflict
            }
        }
        override suspend fun fetchMyBlogs(blogClassId: BlogClassId?, page: Int): AppSyncCloudResult<UserSpaceBlogPage> {
            listReads++
            onList(listReads)
            if (page == listFailureAt) return AppSyncCloudResult.Timeout("list interrupted")
            val total = maxOf(1, (artifacts.size + pageSize - 1) / pageSize)
            val rows = artifacts.entries.drop((if (repeatPage) 0 else page - 1) * pageSize).take(pageSize)
            return AppSyncCloudResult.VerifiedSuccess(UserSpaceBlogPage(
                blogs = rows.map { (id, request) -> BlogSummary("[${AppSyncCloudConfigDefaults.BLOG_CLASS_NAME}] ${request.title}", BlogId(id),
                    "https://example.invalid/blog", "", User(UserId(1), "test", null), TimeInfo("test", epoch = 1)) },
                pageNav = PageNav(currentPage = page, totalPages = total),
                blogClasses = listOf(BlogPageClassInfo(if (wrongClass) "wrong" else AppSyncCloudConfigDefaults.BLOG_CLASS_NAME, BlogClassId(7))),
            ))
        }
        override suspend fun submitBlog(request: AppSyncBlogWriteRequest): AppSyncCloudResult<AppSyncPostAcknowledgement> {
            posts += request
            val id = request.blogId?.value ?: (100 + posts.size)
            if (posts.size != skipStoreAt && (posts.size != timeoutAt || storeTimedOut)) artifacts[id] = request
            return if (posts.size == timeoutAt) AppSyncCloudResult.Timeout("lost response")
                else AppSyncCloudResult.VerifiedSuccess(AppSyncPostAcknowledgement(null, listOf(BlogId(id))))
        }
        override suspend fun fetchBlog(blogId: BlogId): AppSyncCloudResult<BlogPage> {
            reads++
            if (authOnRead) return AppSyncCloudResult.NotLoggedIn
            if (missingRead) return AppSyncCloudResult.NotFound
            val artifact = artifacts[blogId.value] ?: return AppSyncCloudResult.NotFound
            if (failIndexRead && artifact.title == APP_SYNC_INDEX_TITLE) return AppSyncCloudResult.Timeout("index read interrupted")
            return AppSyncCloudResult.VerifiedSuccess(BlogPage(BlogInfo(blogId, artifact.title),
                BlogComment(author = User(UserId(1), "test", null), contentHtml = if (wrongRead) "wrong" else artifact.message.replace("\n", "<br>"),
                    timeInfo = TimeInfo("test", epoch = 1)), emptyList()))
        }
        override suspend fun deleteBlog(request: AppSyncBlogDeleteRequest): AppSyncCloudResult<AppSyncPostAcknowledgement> = error("No cleanup before index commit")
    }
}
