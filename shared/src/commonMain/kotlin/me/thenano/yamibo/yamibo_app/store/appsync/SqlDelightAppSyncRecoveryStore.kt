package me.thenano.yamibo.yamibo_app.store.appsync

import kotlinx.serialization.json.Json
import me.thenano.yamibo.yamibo_app.Database
import me.thenano.yamibo.yamibo_app.AppSyncRecoverySession as DatabaseRecoverySession
import me.thenano.yamibo.yamibo_app.repository.appsync.domain.stableAppSyncFingerprint
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncRecoveryPhase
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncRecoveryMode
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncRecoverySegmentWrite
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncRecoverySession
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncOperationLifecycle
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncInstallationState
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncAccountBinding
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncIdentityGenerator
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperation
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperationCodec
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncCausalContext
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncSequence
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncReplicaKey
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperationId
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.ResolvedSyncEntity
import me.thenano.yamibo.yamibo_app.repository.appsync.withoutExcludedAppSyncPayloads
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncIndexEnvelopeCodec
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncIndexValidation
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncV3DocumentCodec
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncV3DocumentRead
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncV3PayloadKind
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncV3SegmentConfiguration
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncVerifiedCanonicalCheckpoint
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString

internal data class NativeRecoveryIndexIntent(val body: String, val targetBlogId: Long?, val baseSha256: String?)
internal data class NativeRecoveryPayload(val body: String, val kind: AppSyncV3PayloadKind, val identity: String)

internal class SqlDelightAppSyncRecoveryStore(
    private val db: Database,
    private val json: Json = Json { encodeDefaults = true; ignoreUnknownKeys = false },
) {
    private val queries = db.appSyncOperationQueries
    private val operationCodec = SyncOperationCodec(json)

    fun createOrResume(
        accountBinding: SyncAccountBinding,
        sourceOperationIds: Set<String>,
        replacementFingerprint: String,
        nowEpochMillis: Long,
        acknowledgedSourceOperationIds: Set<String> = emptySet(),
    ): AppSyncRecoverySession = createOrResumeSession(
        accountBinding,
        sourceOperationIds,
        replacementFingerprint,
        nowEpochMillis,
        AppSyncRecoveryMode.LegacyShadow,
        acknowledgedSourceOperationIds,
    )

    fun createOrResumeSegmentedJournal(
        accountBinding: SyncAccountBinding,
        sourceOperationIds: Set<String>,
        payloadFingerprint: String,
        nowEpochMillis: Long,
    ): AppSyncRecoverySession = createOrResumeSession(
        accountBinding,
        sourceOperationIds,
        payloadFingerprint,
        nowEpochMillis,
        AppSyncRecoveryMode.SegmentedJournal,
        emptySet(),
    )

    fun createOrResumeSegmentedCheckpoint(
        accountBinding: SyncAccountBinding,
        checkpointId: String,
        payloadFingerprint: String,
        nowEpochMillis: Long,
    ): AppSyncRecoverySession = createOrResumeSession(
        accountBinding,
        emptySet(),
        stableAppSyncFingerprint("$checkpointId|$payloadFingerprint"),
        nowEpochMillis,
        AppSyncRecoveryMode.SegmentedCheckpoint,
        emptySet(),
    )

    private fun createOrResumeSession(
        accountBinding: SyncAccountBinding,
        sourceOperationIds: Set<String>,
        replacementFingerprint: String,
        nowEpochMillis: Long,
        mode: AppSyncRecoveryMode,
        acknowledgedSourceOperationIds: Set<String>,
    ): AppSyncRecoverySession {
        require(sourceOperationIds.isNotEmpty() || mode != AppSyncRecoveryMode.LegacyShadow) {
            "Recovery requires source operations"
        }
        require(acknowledgedSourceOperationIds.all { it in sourceOperationIds }) {
            "Acknowledged recovery sources must belong to the session"
        }
        require(replacementFingerprint.isNotBlank()) { "Recovery fingerprint cannot be blank" }
        recoverySession(accountBinding)?.let { existing ->
            if (existing.phase == AppSyncRecoveryPhase.Completed) {
                db.transaction {
                    queries.deleteRecoverySegmentWrites(existing.sessionId)
                    queries.deleteRecoveryShadowOperations(existing.sessionId)
                    queries.deleteRecoveryPayload(existing.sessionId)
                    db.appSyncNativeCompletionQueries.deleteReceipt(existing.sessionId)
                    db.appSyncNativeCompletionQueries.deleteWork(existing.sessionId)
                    queries.deleteCompletedRecoverySession(existing.sessionId)
                }
            } else {
                require(existing.phase != AppSyncRecoveryPhase.NeedsAttention) {
                    "A recovery session requiring attention already exists for this account"
                }
                require(existing.sourceOperationIds == sourceOperationIds)
                require(existing.acknowledgedSourceOperationIds == acknowledgedSourceOperationIds)
                require(existing.replacementFingerprint == replacementFingerprint)
                require(existing.mode == mode)
                return existing
            }
        }
        val installation = requireNotNull(
            SqlDelightAppSyncOperationStore(db, json).installation(),
        ) { "AppSync installation is not initialized" }
        require(installation.accountBinding == accountBinding)
        val generationId = stableAppSyncFingerprint(
            "${accountBinding.value}|${installation.deviceId.value}|${installation.deviceEpoch.value}|" +
                "${mode.name}|$replacementFingerprint",
        )
        val sessionId = "recovery-${generationId.take(24)}"
        val shadow = mode == AppSyncRecoveryMode.LegacyShadow
        val targetDeviceId = if (shadow) SyncIdentityGenerator.deviceId() else installation.deviceId
        val targetDeviceEpoch = if (shadow) SyncIdentityGenerator.deviceEpoch() else installation.deviceEpoch
        val targetWriterNonce = if (shadow) SyncIdentityGenerator.writerNonce() else installation.writerNonce
        queries.insertRecoverySession(
            sessionId = sessionId,
            accountBinding = accountBinding.value,
            mode = mode.toDb(),
            sourceDeviceId = installation.deviceId.value,
            sourceDeviceEpoch = installation.deviceEpoch.value,
            targetDeviceId = targetDeviceId.value,
            targetDeviceEpoch = targetDeviceEpoch.value,
            targetWriterNonce = targetWriterNonce.value,
            targetFirstSequence = if (shadow) 1L else installation.nextSequence,
            generationId = generationId,
            sourceOperationIdsJson = json.encodeToString(sourceOperationIds.sorted()),
            acknowledgedSourceOperationIdsJson = json.encodeToString(
                acknowledgedSourceOperationIds.sorted(),
            ),
            replacementFingerprint = replacementFingerprint,
            phase = AppSyncRecoveryPhase.Classifying.toDb(),
            createdAtEpochMillis = nowEpochMillis,
            updatedAtEpochMillis = nowEpochMillis,
        )
        return requireNotNull(recoverySession(accountBinding))
    }

    fun recoverySession(accountBinding: SyncAccountBinding): AppSyncRecoverySession? =
        queries.getRecoverySessionByAccount(accountBinding.value).executeAsOneOrNull()?.toModel()

    fun session(sessionId: String): AppSyncRecoverySession? =
        queries.getRecoverySession(sessionId).executeAsOneOrNull()?.toModel()

    /** Freeze the first wire envelope before any remote intent; retries never regenerate it. */
    fun pinPayload(
        sessionId: String,
        payloadKind: String,
        payloadIdentity: String,
        transportVersion: Int = 2,
        canonicalEnvelope: () -> String,
    ): String = db.transactionWithResult {
        val session = requireSession(sessionId)
        require(transportVersion in 2..3) { "Unsupported recovery transport version" }
        require(transportVersion != 3 || session.mode != AppSyncRecoveryMode.LegacyShadow) {
            "Native v3 publication cannot use legacy shadow recovery"
        }
        require(payloadKind == if (session.mode == AppSyncRecoveryMode.SegmentedCheckpoint) "Checkpoint" else "Journal")
        require(payloadIdentity.isNotBlank())
        val existing = queries.getRecoveryPayload(sessionId).executeAsOneOrNull()
        if (existing != null) {
            require(existing.transportVersion == transportVersion.toLong()) { "Recovery transport version changed" }
            require(existing.payloadKind == payloadKind && existing.payloadIdentity == payloadIdentity) {
                "Recovery payload identity changed"
            }
            check(stableAppSyncFingerprint(existing.canonicalEnvelope) == existing.envelopeFingerprint) {
                "Recovery payload integrity check failed"
            }
            existing.canonicalEnvelope
        } else {
            require(db.appSyncNativeCompletionQueries.getForSession(sessionId).executeAsOneOrNull() == null) {
                "Completed native recovery cannot recreate a discarded payload"
            }
            val encoded = canonicalEnvelope()
            require(encoded.isNotBlank())
            if (transportVersion == 3) {
                val kind = if (payloadKind == "Journal")
                    me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncV3PayloadKind.Journal
                    else me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncV3PayloadKind.Checkpoint
                val document = me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncV3DocumentCodec()
                    .discover(encoded, session.accountBinding.value, kind)
                val metadata = when (document) {
                    is me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncV3DocumentRead.Journal -> {
                        require(document.document.deviceId == session.sourceDeviceId.value &&
                            document.document.deviceEpoch == session.sourceDeviceEpoch.value &&
                            document.document.writerNonce == session.targetWriterNonce.value) { "Native recovery writer changed" }
                        document.metadata
                    }
                    is me.thenano.yamibo.yamibo_app.repository.appsync.remote.AppSyncV3DocumentRead.Checkpoint -> document.metadata
                    else -> error("Native recovery payload is invalid or unsupported")
                }
                require(metadata.identity == payloadIdentity) { "Native recovery payload identity mismatch" }
            }
            queries.insertRecoveryPayload(
                sessionId, payloadKind, payloadIdentity, encoded, stableAppSyncFingerprint(encoded),
                transportVersion.toLong(),
            )
            encoded
        }
    }

    fun nativeSegmentConfiguration(sessionId: String): AppSyncV3SegmentConfiguration? {
        val row = requireNotNull(queries.getRecoveryPayload(sessionId).executeAsOneOrNull())
        require(row.transportVersion == 3L)
        val values = listOf(row.segmentPlanVersion, row.segmentTargetChars,
            row.segmentMaximumCount, row.segmentMaximumEnvelopeChars)
        if (values.all { it == null }) return null
        require(values.all { it != null && it in 1..Int.MAX_VALUE.toLong() }) { "Invalid persisted segment configuration" }
        return AppSyncV3SegmentConfiguration(values[0]!!.toInt(), values[1]!!.toInt(),
            values[2]!!.toInt(), values[3]!!.toInt())
    }

    /** Caller must validate all existing segment intents against this plan before pinning
     * configuration on a migrated payload that predates configuration persistence.
     */
    fun pinNativeSegmentConfiguration(sessionId: String, configuration: AppSyncV3SegmentConfiguration) = db.transaction {
        val existing = nativeSegmentConfiguration(sessionId)
        if (existing != null) {
            require(existing == configuration) { "Native segment configuration cannot change" }
        } else {
            queries.pinNativeSegmentConfiguration(configuration.version.toLong(), configuration.targetChars.toLong(),
                configuration.maximumSegments.toLong(), configuration.maximumEnvelopeChars.toLong(), sessionId)
            check(nativeSegmentConfiguration(sessionId) == configuration)
        }
    }

    /** Persist before the first native root POST. False means a prior attempt may have run;
     * the caller must reconcile it authoritatively before considering another create.
     */
    fun pinNativeRootIntent(sessionId: String, fingerprint: String): Boolean = db.transactionWithResult {
        val session = requireSession(sessionId)
        require(session.phase == AppSyncRecoveryPhase.PublishingRoot || session.phase == AppSyncRecoveryPhase.CommittingIndex)
        require(fingerprint.length == 64 && fingerprint.all { it in '0'..'9' || it in 'a'..'f' })
        val payload = requireNotNull(queries.getRecoveryPayload(sessionId).executeAsOneOrNull())
        require(payload.transportVersion == 3L) { "Native root intent requires a native payload" }
        check(stableAppSyncFingerprint(payload.canonicalEnvelope) == payload.envelopeFingerprint) {
            "Native recovery payload integrity check failed"
        }
        payload.rootIntentFingerprint?.let {
            require(it == fingerprint) { "Native root intent cannot change" }
            return@transactionWithResult false
        }
        require(session.phase == AppSyncRecoveryPhase.PublishingRoot)
        val writes = segmentWrites(sessionId)
        val count = writes.map { it.segmentCount }.distinct().singleOrNull()
        require(count != null && writes.size == count && writes.map { it.segmentIndex } == (0 until count).toList() &&
            writes.all { it.blogId != null && it.blogId in 1..Int.MAX_VALUE.toLong() && it.verifiedFingerprint == it.expectedFingerprint }) {
            "Native root intent requires a complete verified segment chain"
        }
        queries.pinNativeRootIntent(fingerprint, sessionId)
        check(queries.getRecoveryPayload(sessionId).executeAsOne().rootIntentFingerprint == fingerprint)
        true
    }

    fun activeSessions(accountBinding: SyncAccountBinding): List<AppSyncRecoverySession> =
        queries.getActiveRecoverySessions(accountBinding.value).executeAsList().map { it.toModel() }

    private fun DatabaseRecoverySession.toModel(): AppSyncRecoverySession =
        AppSyncRecoverySession(
            sessionId = sessionId,
            accountBinding = SyncAccountBinding(accountBinding),
            mode = AppSyncRecoveryMode.fromDb(mode),
                sourceDeviceId = me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncDeviceId(sourceDeviceId),
                sourceDeviceEpoch = me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncDeviceEpoch(sourceDeviceEpoch),
                targetDeviceId = me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncDeviceId(targetDeviceId),
                targetDeviceEpoch = me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncDeviceEpoch(targetDeviceEpoch),
                targetWriterNonce = me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncWriterNonce(targetWriterNonce),
                targetFirstSequence = targetFirstSequence,
                generationId = generationId,
                sourceOperationIds = json.decodeFromString<List<String>>(sourceOperationIdsJson).toSet(),
                acknowledgedSourceOperationIds = json.decodeFromString<List<String>>(
                    acknowledgedSourceOperationIdsJson,
                ).toSet(),
                replacementFingerprint = replacementFingerprint,
                phase = AppSyncRecoveryPhase.fromDb(phase),
                retryCount = retryCount,
                nextRetryAtEpochMillis = nextRetryAtEpochMillis,
                lastErrorCategory = lastErrorCategory,
                blockingDomain = blockingDomain,
                redactedBlockingEntity = redactedBlockingEntity,
                rootBlogId = rootBlogId,
                rootFingerprint = rootFingerprint,
                indexCommitted = indexCommitted != 0L,
                createdAtEpochMillis = createdAtEpochMillis,
                updatedAtEpochMillis = updatedAtEpochMillis,
                completedAtEpochMillis = completedAtEpochMillis,
                encodedChars = encodedChars?.toInt(),
                targetBudgetChars = targetBudgetChars.toInt(),
                retryIdentity = retryIdentity,
            )

    fun recordPayloadMeasurement(
        sessionId: String,
        encodedChars: Int,
        targetBudgetChars: Int,
        nowEpochMillis: Long,
    ) {
        require(encodedChars >= 0 && targetBudgetChars > 0)
        requireSession(sessionId)
        queries.updateRecoveryPayloadMeasurement(
            encodedChars.toLong(),
            targetBudgetChars.toLong(),
            nowEpochMillis,
            sessionId,
        )
    }

    fun stageOperations(sessionId: String, operations: List<SyncOperation>) {
        require(operations.isNotEmpty())
        val session = requireSession(sessionId)
        require(session.phase in setOf(AppSyncRecoveryPhase.Classifying, AppSyncRecoveryPhase.Staging))
        require(operations.all {
            it.accountBinding == session.accountBinding &&
                it.deviceId == session.targetDeviceId && it.deviceEpoch == session.targetDeviceEpoch
        })
        db.transaction {
            operations.forEach { operation ->
                queries.insertRecoveryShadowOperation(
                    sessionId = sessionId,
                    operationId = operation.operationId.value,
                    encodedOperation = operationCodec.encode(operation),
                )
            }
            transition(
                sessionId, session.phase, AppSyncRecoveryPhase.PublishingSegments,
                nowEpochMillis = session.updatedAtEpochMillis,
            )
        }
    }

    fun startSegmentedJournal(sessionId: String, nowEpochMillis: Long) {
        val session = requireSession(sessionId)
        require(session.mode in setOf(
            AppSyncRecoveryMode.SegmentedJournal,
            AppSyncRecoveryMode.SegmentedCheckpoint,
        ))
        require(session.phase in setOf(AppSyncRecoveryPhase.Classifying, AppSyncRecoveryPhase.Staging))
        transition(
            sessionId,
            session.phase,
            AppSyncRecoveryPhase.PublishingSegments,
            nowEpochMillis,
        )
    }

    fun shadowOperations(sessionId: String): List<SyncOperation> =
        queries.getRecoveryShadowOperations(sessionId).executeAsList().map {
            operationCodec.decode(it.encodedOperation).getOrThrow()
        }

    fun saveSegmentIntent(
        sessionId: String,
        segmentIndex: Int,
        segmentCount: Int,
        expectedFingerprint: String,
        nextBlogId: Long?,
    ) {
        requireSession(sessionId)
        require(segmentIndex in 0 until segmentCount)
        segmentWrites(sessionId).singleOrNull { it.segmentIndex == segmentIndex }?.let { existing ->
            require(existing.segmentCount == segmentCount) {
                "A durable segment intent cannot change its segment count"
            }
            require(existing.expectedFingerprint == expectedFingerprint) {
                "A durable segment intent cannot change its payload fingerprint"
            }
            require(existing.nextBlogId == nextBlogId) {
                "A durable segment intent cannot change its chain successor"
            }
            return
        }
        queries.upsertRecoverySegmentIntent(
            sessionId, segmentIndex.toLong(), segmentCount.toLong(), expectedFingerprint, nextBlogId,
        )
    }

    fun markSegmentVerified(
        sessionId: String,
        segmentIndex: Int,
        expectedFingerprint: String,
        blogId: Long,
        verifiedAtEpochMillis: Long,
    ) {
        requireSession(sessionId)
        db.transaction {
            queries.markRecoverySegmentVerified(
                blogId = blogId,
                verifiedFingerprint = expectedFingerprint,
                verifiedAtEpochMillis = verifiedAtEpochMillis,
                sessionId = sessionId,
                segmentIndex = segmentIndex.toLong(),
                expectedFingerprint = expectedFingerprint,
            )
            check(segmentWrites(sessionId).any {
                it.segmentIndex == segmentIndex && it.expectedFingerprint == expectedFingerprint &&
                    it.verifiedFingerprint == expectedFingerprint && it.blogId == blogId
            }) { "Segment verification did not match persisted intent" }
            queries.clearRecoveryRetry(sessionId)
        }
    }

    fun segmentWrites(sessionId: String): List<AppSyncRecoverySegmentWrite> =
        queries.getRecoverySegmentWrites(sessionId).executeAsList().map { row ->
            AppSyncRecoverySegmentWrite(
                sessionId = row.sessionId,
                segmentIndex = row.segmentIndex.toInt(),
                segmentCount = row.segmentCount.toInt(),
                expectedFingerprint = row.expectedFingerprint,
                nextBlogId = row.nextBlogId,
                blogId = row.blogId,
                verifiedFingerprint = row.verifiedFingerprint,
                verifiedAtEpochMillis = row.verifiedAtEpochMillis,
            )
        }

    fun markRootVerified(
        sessionId: String,
        rootBlogId: Long,
        rootFingerprint: String,
        verifiedAtEpochMillis: Long,
    ) {
        val session = requireSession(sessionId)
        queries.getRecoveryPayload(sessionId).executeAsOneOrNull()?.takeIf { it.transportVersion == 3L }?.let {
            require(rootBlogId in 1..Int.MAX_VALUE.toLong()) { "Native root Blog identity is invalid" }
            require(it.rootIntentFingerprint == rootFingerprint) { "Native root verification does not match its durable intent" }
        }
        require(session.phase == AppSyncRecoveryPhase.PublishingRoot)
        require(segmentWrites(sessionId).let { writes ->
            val declaredCount = writes.map { it.segmentCount }.distinct().singleOrNull()
            declaredCount != null &&
                writes.size == declaredCount &&
                writes.map { it.segmentIndex } == (0 until declaredCount).toList() &&
                writes.all {
                it.blogId != null && it.verifiedFingerprint == it.expectedFingerprint
            }
        }) { "Every segment must be verified before publishing the root" }
        queries.markRecoveryRootVerified(
            rootBlogId, rootFingerprint, verifiedAtEpochMillis, sessionId,
        )
        check(requireSession(sessionId).phase == AppSyncRecoveryPhase.CommittingIndex)
    }

    fun markIndexCommitted(sessionId: String, verifiedAtEpochMillis: Long) {
        val session = requireSession(sessionId)
        require(queries.getRecoveryPayload(sessionId).executeAsOneOrNull()?.transportVersion != 3L) {
            "Native recovery requires bound index readback evidence"
        }
        require(session.phase == AppSyncRecoveryPhase.CommittingIndex)
        queries.markRecoveryIndexCommitted(verifiedAtEpochMillis, sessionId)
        check(requireSession(sessionId).let {
            it.indexCommitted && it.phase == AppSyncRecoveryPhase.ActivatingLocal
        })
    }

    fun activateCommittedRecovery(sessionId: String, activatedAtEpochMillis: Long) = db.transaction {
        val session = requireSession(sessionId)
        require(session.mode == AppSyncRecoveryMode.LegacyShadow)
        if (session.phase == AppSyncRecoveryPhase.Completed) return@transaction
        require(session.phase == AppSyncRecoveryPhase.ActivatingLocal && session.indexCommitted)
        val rootBlogId = requireNotNull(session.rootBlogId)
        val rootFingerprint = requireNotNull(session.rootFingerprint)
        val installation = requireNotNull(SqlDelightAppSyncOperationStore(db, json).installation())
        require(installation.accountBinding == session.accountBinding)
        require(installation.deviceId == session.sourceDeviceId)
        require(installation.deviceEpoch == session.sourceDeviceEpoch)
        val shadow = shadowOperations(sessionId)
        require(shadow.isNotEmpty())
        require(shadow.map { it.sequence.value } ==
            (session.targetFirstSequence until session.targetFirstSequence + shadow.size).toList()
        ) { "Shadow operation sequence is not deterministic or contiguous" }
        require(session.sourceOperationIds.all {
            queries.getOutboxOperation(it).executeAsOneOrNull() != null
        }) { "A classified source operation disappeared before activation" }

        val operationStore = SqlDelightAppSyncOperationStore(db, json)
        val pending = operationStore.pendingOperations().filter { it.operationId.value !in session.sourceOperationIds }
        require(pending.all { it.accountBinding == session.accountBinding })
        val targetReplica = SyncReplicaKey(session.targetDeviceId, session.targetDeviceEpoch)
        val replacements = linkedMapOf<SyncOperationId, SyncOperation>()
        pending.forEachIndexed { index, source ->
            val sequence = SyncSequence(session.targetFirstSequence + shadow.size + index)
            val replacement = source.copy(
                operationId = SyncOperation.idFor(session.targetDeviceId, session.targetDeviceEpoch, sequence),
                deviceId = session.targetDeviceId,
                deviceEpoch = session.targetDeviceEpoch,
                sequence = sequence,
                causalContext = source.causalContext
                    .advance(source.replicaKey, source.sequence)
                    .advance(targetReplica, SyncSequence(sequence.value - 1)),
            )
            replacements[source.operationId] = replacement
            insertOperation(replacement, AppSyncOperationLifecycle.PendingLocal, null)
            queries.insertRecoveryCarryForward(
                source.operationId.value, replacement.operationId.value, sessionId,
                session.accountBinding.value, activatedAtEpochMillis,
            )
        }
        remapCarriedProvenance(replacements, activatedAtEpochMillis)
        shadow.forEach { operation ->
            require(queries.getOutboxOperation(operation.operationId.value).executeAsOneOrNull() == null)
            insertOperation(operation, AppSyncOperationLifecycle.Acknowledged, activatedAtEpochMillis)
        }
        if (session.acknowledgedSourceOperationIds.isNotEmpty()) {
            queries.markOperationsAcknowledged(
                acknowledgedAtEpochMillis = activatedAtEpochMillis,
                operationId = session.acknowledgedSourceOperationIds.toList(),
            )
        }
        val superseded = session.sourceOperationIds - session.acknowledgedSourceOperationIds
        if (superseded.isNotEmpty()) {
            queries.markOperationsSupersededByRecovery(superseded.toList())
        }
        queries.upsertRemoteBlog(
            remoteKey = "recovery-root:${session.generationId}",
            kind = AppSyncRemoteBlogKind.JournalRoot.name.uppercase(),
            blogId = rootBlogId,
            classId = null,
            fingerprint = rootFingerprint,
            validatedAtEpochMillis = activatedAtEpochMillis,
            contentUpdatedAtEpochMillis = activatedAtEpochMillis,
        )
        queries.activateRecoveryGeneration(
            accountBinding = session.accountBinding.value,
            deviceId = session.targetDeviceId.value,
            deviceEpoch = session.targetDeviceEpoch.value,
            writerNonce = session.targetWriterNonce.value,
            nextSequence = session.targetFirstSequence + shadow.size + replacements.size,
            journalBlogId = rootBlogId,
            lastVerifiedHeartbeatAt = activatedAtEpochMillis,
            deviceId_ = session.sourceDeviceId.value,
            deviceEpoch_ = session.sourceDeviceEpoch.value,
        )
        queries.upsertCausalWatermark(
            targetReplica.stableKey, session.targetFirstSequence + shadow.size + replacements.size - 1,
        )
        transition(
            sessionId = sessionId,
            expected = AppSyncRecoveryPhase.ActivatingLocal,
            next = AppSyncRecoveryPhase.Completed,
            nowEpochMillis = activatedAtEpochMillis,
        )
    }

    fun usesNativeTransport(sessionId: String): Boolean =
        payloadTransportVersion(sessionId) == 3L

    fun payloadTransportVersion(sessionId: String): Long? =
        queries.getRecoveryPayload(sessionId).executeAsOneOrNull()?.transportVersion
            ?: if (hasNativeCompletionReceipt(sessionId)) 3L else null

    private fun hasNativeCompletionReceipt(sessionId: String): Boolean {
        val receipt = db.appSyncNativeCompletionQueries.getForSession(sessionId).executeAsOneOrNull() ?: return false
        val session = requireSession(sessionId)
        require(session.phase == AppSyncRecoveryPhase.Completed && session.indexCommitted &&
            session.mode == AppSyncRecoveryMode.SegmentedCheckpoint &&
            receipt.accountBinding == session.accountBinding.value && receipt.generationId == session.generationId &&
            receipt.rootBlogId == session.rootBlogId && receipt.rootFingerprint == session.rootFingerprint &&
            receipt.completedAtEpochMillis == session.completedAtEpochMillis)
        return true
    }

    /** Called within the final proven local cleanup transaction, before reporting completion. */
    fun completeNativeCheckpointCleanup(sessionId: String, now: Long): Long = db.transactionWithResult {
        require(now >= 0)
        val session = requireSession(sessionId)
        require(session.phase == AppSyncRecoveryPhase.Cleaning)
        val verified = nativeCheckpointForActivation(sessionId)
        val head = requireNotNull(db.appSyncCanonicalStateQueries.getState().executeAsOneOrNull())
        require(head.accountBinding == session.accountBinding.value && head.settingsReconciliationPending == 0L)
        val headBytes = head.canonicalPayload.toByteString()
        require(headBytes.sha256().hex() == head.canonicalSha256)
        val canonical = me.thenano.yamibo.yamibo_app.repository.appsync.schema.AppSyncCanonicalCheckpointCodec()
            .decode(head.accountBinding, head.checkpointId, headBytes)
        require(verified.document.coverage.all { (replica, sequence) -> (canonical.coverage[replica] ?: 0L) >= sequence })
        require(verified.document.coverage.none { (replica, sequence) ->
            db.appSyncLocalPruneQueries.getCandidates(session.accountBinding.value, replica, sequence, 1).executeAsList().isNotEmpty()
        }) { "Covered payload cleanup is incomplete" }
        require(shadowOperations(sessionId).isEmpty()) { "Native checkpoint cannot discard unverified shadow operations" }
        val payload = queries.getRecoveryPayload(sessionId).executeAsOne()
        val receipts = db.appSyncNativeCompletionQueries
        val bytes = receipts.getPayloadBytes(sessionId).executeAsOne()
        val segments = segmentWrites(sessionId).size.toLong()
        receipts.insertReceipt(sessionId, session.accountBinding.value, session.generationId,
            verified.document.checkpointId, verified.fingerprint, requireNotNull(session.rootBlogId),
            requireNotNull(session.rootFingerprint), requireNotNull(payload.verifiedIndexBlogId),
            requireNotNull(payload.verifiedIndexFingerprint), bytes, segments, now)
        queries.deleteRecoverySegmentWrites(sessionId)
        queries.deleteRecoveryShadowOperations(sessionId)
        queries.deleteRecoveryPayload(sessionId)
        receipts.deleteWork(sessionId)
        transition(sessionId, AppSyncRecoveryPhase.Cleaning, AppSyncRecoveryPhase.Completed, now,
            retryCount = 0, retryIdentity = null)
        bytes
    }

    /** Bounded metadata maintenance. Only receipts whose native payload was already purged qualify. */
    fun expireCompletedRecoveryMetadata(now: Long) = db.transaction {
        require(now >= 0)
        val retention = 30L * 24 * 60 * 60 * 1000
        if (now >= retention) {
            db.appSyncLocalPruneQueries.expireAudit(now - retention)
            val receipts = db.appSyncNativeCompletionQueries
            receipts.getExpired(now - retention).executeAsList().forEach { id ->
                require(hasNativeCompletionReceipt(id))
                require(queries.getRecoveryPayload(id).executeAsOneOrNull() == null)
                receipts.deleteWork(id)
                receipts.deleteReceipt(id)
                queries.deleteCompletedRecoverySession(id)
            }
        }
    }

    fun nativePayload(sessionId: String): NativeRecoveryPayload {
        val session = requireSession(sessionId)
        val payload = requireNotNull(queries.getRecoveryPayload(sessionId).executeAsOneOrNull())
        require(payload.transportVersion == 3L)
        require(stableAppSyncFingerprint(payload.canonicalEnvelope) == payload.envelopeFingerprint)
        val kind = if (session.mode == AppSyncRecoveryMode.SegmentedCheckpoint) AppSyncV3PayloadKind.Checkpoint
            else { require(session.mode == AppSyncRecoveryMode.SegmentedJournal); AppSyncV3PayloadKind.Journal }
        val document = AppSyncV3DocumentCodec().discover(payload.canonicalEnvelope, session.accountBinding.value, kind)
        val metadata = when (document) {
            is AppSyncV3DocumentRead.Checkpoint -> document.metadata
            is AppSyncV3DocumentRead.Journal -> document.metadata
            else -> error("Invalid frozen native payload")
        }
        require(metadata.identity == payload.payloadIdentity && payload.payloadKind == kind.name)
        return NativeRecoveryPayload(payload.canonicalEnvelope, kind, payload.payloadIdentity)
    }

    fun nativeIndexIntent(sessionId: String): NativeRecoveryIndexIntent? {
        val row = queries.getRecoveryPayload(sessionId).executeAsOneOrNull() ?: return null
        val body = row.indexIntentBody ?: return null
        require(row.transportVersion == 3L && body.encodeUtf8().sha256().hex() == row.indexIntentSha256)
        return NativeRecoveryIndexIntent(body, row.indexTargetBlogId, row.indexBaseSha256)
    }

    fun pinNativeIndexIntent(sessionId: String, intent: NativeRecoveryIndexIntent): NativeRecoveryIndexIntent = db.transactionWithResult {
        require(requireSession(sessionId).phase == AppSyncRecoveryPhase.CommittingIndex)
        require(usesNativeTransport(sessionId))
        val previous = nativeIndexIntent(sessionId)
        if (previous != null) {
            require(previous == intent) { "Native index intent changed" }
            previous
        } else {
            require((intent.targetBlogId == null && intent.baseSha256 == null) ||
                (intent.targetBlogId != null && intent.targetBlogId in 1..Int.MAX_VALUE.toLong() &&
                    intent.baseSha256?.matches(Regex("[0-9a-f]{64}")) == true))
            val index = (AppSyncIndexEnvelopeCodec().validate(intent.body) as? AppSyncIndexValidation.Valid)?.envelope
            require(index?.payload?.accountBinding == requireSession(sessionId).accountBinding)
            queries.pinNativeRecoveryIndexIntent(intent.body, intent.body.encodeUtf8().sha256().hex(),
                intent.targetBlogId, intent.baseSha256, sessionId)
            requireNotNull(nativeIndexIntent(sessionId))
        }
    }

    /** Called only with a fetched Index body after the publisher verifies its physical ID/title.
     * Binds the canonical reference to the frozen payload and confirmed root, atomically with
     * the phase transition. This does not acknowledge operations or authorize cleanup.
     */
    fun markNativeIndexCommitted(sessionId: String, indexBlogId: Long, indexReaderHtml: String,
        verifiedAtEpochMillis: Long) = db.transaction {
        val session = requireSession(sessionId)
        require(session.phase == AppSyncRecoveryPhase.CommittingIndex ||
            (session.phase in setOf(AppSyncRecoveryPhase.ActivatingLocal, AppSyncRecoveryPhase.Cleaning) && session.indexCommitted))
        require(indexBlogId in 1..Int.MAX_VALUE.toLong() && verifiedAtEpochMillis >= 0)
        val payload = requireNotNull(queries.getRecoveryPayload(sessionId).executeAsOneOrNull())
        require(payload.transportVersion == 3L && payload.rootIntentFingerprint != null &&
            payload.rootIntentFingerprint == session.rootFingerprint && session.rootBlogId != null &&
            session.rootBlogId != indexBlogId)
        require(stableAppSyncFingerprint(payload.canonicalEnvelope) == payload.envelopeFingerprint)
        val index = (AppSyncIndexEnvelopeCodec().validateReaderHtml(indexReaderHtml) as? AppSyncIndexValidation.Valid)?.envelope
        requireNotNull(index) { "Native index readback is invalid" }
        require(index.payload.accountBinding == session.accountBinding)
        val intent = requireNotNull(nativeIndexIntent(sessionId)) { "Native index intent is missing" }
        require(intent.targetBlogId == null || intent.targetBlogId == indexBlogId)
        require(AppSyncIndexEnvelopeCodec().encode(index.payload) == intent.body) { "Native index differs from frozen intent" }
        val kind = AppSyncV3PayloadKind.valueOf(payload.payloadKind)
        val document = AppSyncV3DocumentCodec().discover(payload.canonicalEnvelope, session.accountBinding.value, kind)
        when (document) {
            is AppSyncV3DocumentRead.Checkpoint -> {
                require(document.document.checkpointId == payload.payloadIdentity)
                val reference = index.payload.checkpoints.distinct().singleOrNull { it.checkpointId == payload.payloadIdentity }
                require(reference?.blogId?.toLong() == session.rootBlogId &&
                    reference.fingerprint == document.metadata.canonicalFingerprint)
            }
            is AppSyncV3DocumentRead.Journal -> {
                require(document.document.deviceId == session.targetDeviceId.value &&
                    document.document.deviceEpoch == session.targetDeviceEpoch.value &&
                    document.document.writerNonce == session.targetWriterNonce.value)
                val key = SyncReplicaKey(session.targetDeviceId, session.targetDeviceEpoch).stableKey
                require(key == payload.payloadIdentity)
                val reference = index.payload.journals.distinct().singleOrNull { it.replicaKey == key }
                require(reference?.blogId?.toLong() == session.rootBlogId &&
                    reference.fingerprint == document.metadata.canonicalFingerprint)
            }
            else -> error("Frozen native payload is invalid")
        }
        if (session.indexCommitted) {
            require(payload.verifiedIndexBlogId == indexBlogId && payload.verifiedIndexFingerprint == index.fingerprint)
        } else {
            queries.markNativeRecoveryIndexVerified(indexBlogId, index.fingerprint, verifiedAtEpochMillis, sessionId)
            queries.markRecoveryIndexCommitted(verifiedAtEpochMillis, sessionId)
            check(requireSession(sessionId).let { it.indexCommitted && it.phase == AppSyncRecoveryPhase.ActivatingLocal })
        }
    }

    private fun insertOperation(operation: SyncOperation, lifecycle: AppSyncOperationLifecycle, acknowledgedAt: Long?) {
        queries.insertOutboxOperation(
            operationId = operation.operationId.value,
            deviceId = operation.deviceId.value,
            deviceEpoch = operation.deviceEpoch.value,
            sequence = operation.sequence.value,
            accountBinding = operation.accountBinding.value,
            domainId = operation.domainId.value,
            entityId = operation.entityId.value,
            entityGeneration = operation.entityGeneration,
            kind = operation.kind.name,
            fieldsJson = json.encodeToString(operation.fields),
            causalContextJson = json.encodeToString(SyncCausalContext.serializer(), operation.causalContext),
            createdAtEpochMillis = operation.createdAtEpochMillis,
            origin = operation.origin.name,
            bulkDeleteAuthorizationId = operation.bulkDeleteAuthorizationId,
            schemaVersion = operation.schemaVersion.toLong(),
            lifecycle = lifecycle.toDb(),
            acknowledgedAtEpochMillis = acknowledgedAt,
        )
    }

    private fun remapCarriedProvenance(replacements: Map<SyncOperationId, SyncOperation>, nowEpochMillis: Long) {
        replacements.values.map { it.domainId to it.entityId }.distinct().forEach { (domain, entity) ->
            queries.getResolvedEntitiesByDomainAndEntity(domain.value, entity.value).executeAsList().forEach { row ->
                val state = json.decodeFromString(ResolvedSyncEntity.serializer(), row.encodedState)
                val remapped = state.copy(
                    fields = state.fields.mapValues { (_, field) ->
                        replacements[field.operation.operationId]?.let { field.copy(operation = it) } ?: field
                    },
                    tombstone = state.tombstone?.let { replacements[it.operationId] ?: it },
                    relationOperation = state.relationOperation?.let { replacements[it.operationId] ?: it },
                )
                if (remapped != state) queries.updateRecoveryProvenance(
                    json.encodeToString(ResolvedSyncEntity.serializer(), remapped.withoutExcludedAppSyncPayloads()),
                    nowEpochMillis, row.entityKey,
                )
            }
        }
    }

    fun completeVerifiedRecoveryWithoutPublication(
        sessionId: String,
        completedAtEpochMillis: Long,
    ) {
        val session = requireSession(sessionId)
        require(session.mode == AppSyncRecoveryMode.LegacyShadow)
        require(session.phase in setOf(AppSyncRecoveryPhase.Classifying, AppSyncRecoveryPhase.Staging))
        require(shadowOperations(sessionId).isEmpty())
        require(session.sourceOperationIds.all {
            queries.getOutboxOperation(it).executeAsOneOrNull() != null
        }) { "A classified source operation disappeared before recovery completion" }
        db.transaction {
            if (session.acknowledgedSourceOperationIds.isNotEmpty()) {
                queries.markOperationsAcknowledged(
                    acknowledgedAtEpochMillis = completedAtEpochMillis,
                    operationId = session.acknowledgedSourceOperationIds.toList(),
                )
            }
            val superseded = session.sourceOperationIds - session.acknowledgedSourceOperationIds
            if (superseded.isNotEmpty()) {
                queries.markOperationsSupersededByRecovery(superseded.toList())
            }
            transition(
                sessionId,
                session.phase,
                AppSyncRecoveryPhase.Completed,
                completedAtEpochMillis,
            )
        }
    }

    fun activateCommittedSession(sessionId: String, activatedAtEpochMillis: Long) {
        val session = requireSession(sessionId)
        if (usesNativeTransport(sessionId)) {
            require(session.mode == AppSyncRecoveryMode.SegmentedJournal) {
                "Native checkpoint requires canonical projection activation"
            }
            activateNativeJournal(sessionId, activatedAtEpochMillis)
            return
        }
        when (session.mode) {
            AppSyncRecoveryMode.LegacyShadow ->
                activateCommittedRecovery(sessionId, activatedAtEpochMillis)
            AppSyncRecoveryMode.SegmentedJournal ->
                activateCommittedSegmentedJournal(sessionId, activatedAtEpochMillis)
            AppSyncRecoveryMode.SegmentedCheckpoint ->
                activateCommittedSegmentedCheckpoint(sessionId, activatedAtEpochMillis)
        }
    }

    fun nativeCheckpointForActivation(sessionId: String): AppSyncVerifiedCanonicalCheckpoint = db.transactionWithResult {
        val session = requireSession(sessionId)
        require(session.mode == AppSyncRecoveryMode.SegmentedCheckpoint && session.indexCommitted &&
            session.phase in setOf(AppSyncRecoveryPhase.ActivatingLocal, AppSyncRecoveryPhase.Cleaning))
        val installation = requireNotNull(SqlDelightAppSyncOperationStore(db, json).installation())
        require(installation.accountBinding == session.accountBinding && installation.deviceId == session.sourceDeviceId &&
            installation.deviceEpoch == session.sourceDeviceEpoch && installation.writerNonce == session.targetWriterNonce &&
            installation.state == AppSyncInstallationState.Active)
        val payload = requireNotNull(queries.getRecoveryPayload(sessionId).executeAsOneOrNull())
        val intent = requireNotNull(nativeIndexIntent(sessionId))
        markNativeIndexCommitted(sessionId, requireNotNull(payload.verifiedIndexBlogId), intent.body,
            requireNotNull(payload.indexVerifiedAtEpochMillis))
        requireNotNull(AppSyncVerifiedCanonicalCheckpoint.verify(session.accountBinding.value, requireNotNull(session.rootBlogId),
            intent.body, payload.canonicalEnvelope))
    }

    fun beginNativeCheckpointCleanup(sessionId: String, completedAtEpochMillis: Long) = db.transaction {
        val verified = nativeCheckpointForActivation(sessionId)
        val head = requireNotNull(db.appSyncCanonicalStateQueries.getState().executeAsOneOrNull())
        require(head.accountBinding == verified.document.accountBinding && head.settingsReconciliationPending == 0L)
        val adopted = requireNotNull(queries.getCheckpoint(verified.document.checkpointId).executeAsOneOrNull())
        require(adopted.blogId == verified.blogId && adopted.payloadFingerprint == verified.fingerprint && adopted.state == "VERIFIED")
        require(completedAtEpochMillis >= 0)
        queries.upsertRemoteBlog("checkpoint-root:${requireSession(sessionId).generationId}",
            AppSyncRemoteBlogKind.CheckpointRoot.name.uppercase(), verified.blogId, null, verified.fingerprint,
            completedAtEpochMillis, completedAtEpochMillis)
        transition(sessionId, AppSyncRecoveryPhase.ActivatingLocal, AppSyncRecoveryPhase.Cleaning, completedAtEpochMillis,
            retryCount = 0, retryIdentity = null)
    }

    fun nativeJournalForActivation(sessionId: String): AppSyncV3DocumentRead.Journal = db.transactionWithResult {
        val session = requireSession(sessionId)
        require(session.mode == AppSyncRecoveryMode.SegmentedJournal && session.indexCommitted)
        require(session.phase == AppSyncRecoveryPhase.ActivatingLocal)
        val payload = requireNotNull(queries.getRecoveryPayload(sessionId).executeAsOneOrNull())
        require(payload.transportVersion == 3L)
        val intent = requireNotNull(nativeIndexIntent(sessionId))
        markNativeIndexCommitted(sessionId, requireNotNull(payload.verifiedIndexBlogId), intent.body,
            requireNotNull(payload.indexVerifiedAtEpochMillis))
        val journal = AppSyncV3DocumentCodec().discover(payload.canonicalEnvelope, session.accountBinding.value,
            AppSyncV3PayloadKind.Journal) as? AppSyncV3DocumentRead.Journal
        requireNotNull(journal)
        val operations = SqlDelightAppSyncOperationStore(db, json)
        val installation = requireNotNull(operations.installation())
        require(installation.accountBinding == session.accountBinding && installation.deviceId == session.sourceDeviceId &&
            installation.deviceEpoch == session.sourceDeviceEpoch && installation.writerNonce == session.targetWriterNonce &&
            installation.state == AppSyncInstallationState.Active)
        val published = journal.document.block.operations.associateBy { Triple(it.deviceId, it.deviceEpoch, it.sequence) }
        val proofs = journal.document.block.authorizations.associateBy { it.authorizationId }
        val importer = me.thenano.yamibo.yamibo_app.repository.appsync.schema.AppSyncCanonicalOperationImporter()
        for (id in session.sourceOperationIds) {
            val (source, lifecycle) = requireNotNull(operations.outboxOperation(id)) { "Native publication source is missing" }
            require(lifecycle in setOf(AppSyncOperationLifecycle.PendingLocal, AppSyncOperationLifecycle.PublishedUnverified,
                AppSyncOperationLifecycle.Acknowledged))
            require(source.deviceId == session.sourceDeviceId && source.deviceEpoch == session.sourceDeviceEpoch)
            val imported = importer.import(session.accountBinding.value, source)
                as? me.thenano.yamibo.yamibo_app.repository.appsync.schema.AppSyncCanonicalOperationImport.Accepted
            requireNotNull(imported) { "Native publication does not represent this source" }
            val operation = imported.operation
            require(published[Triple(operation.deviceId, operation.deviceEpoch, operation.sequence)] == operation)
            require(imported.proof == operation.authorizationId?.let(proofs::get))
        }
        journal
    }

    private fun activateNativeJournal(sessionId: String, activatedAtEpochMillis: Long) = db.transaction {
        val session = requireSession(sessionId)
        require(session.mode == AppSyncRecoveryMode.SegmentedJournal && session.indexCommitted)
        if (session.phase == AppSyncRecoveryPhase.Completed) return@transaction
        require(activatedAtEpochMillis >= 0)
        val journal = nativeJournalForActivation(sessionId)
        if (session.sourceOperationIds.isNotEmpty()) queries.markOperationsAcknowledged(activatedAtEpochMillis, session.sourceOperationIds.toList())
        queries.upsertRemoteBlog("journal-root:${session.generationId}", AppSyncRemoteBlogKind.JournalRoot.name.uppercase(),
            requireNotNull(session.rootBlogId), null, journal.metadata.canonicalFingerprint, activatedAtEpochMillis, activatedAtEpochMillis)
        queries.updateInstallationHeartbeat(activatedAtEpochMillis, session.rootBlogId, AppSyncInstallationState.Active.name.uppercase())
        transition(sessionId, AppSyncRecoveryPhase.ActivatingLocal, AppSyncRecoveryPhase.Completed, activatedAtEpochMillis)
    }

    private fun activateCommittedSegmentedCheckpoint(
        sessionId: String,
        activatedAtEpochMillis: Long,
    ) {
        val session = requireSession(sessionId)
        require(session.phase == AppSyncRecoveryPhase.ActivatingLocal && session.indexCommitted)
        require(session.mode == AppSyncRecoveryMode.SegmentedCheckpoint)
        val rootBlogId = requireNotNull(session.rootBlogId)
        val rootFingerprint = requireNotNull(session.rootFingerprint)
        db.transaction {
            queries.upsertRemoteBlog(
                remoteKey = "checkpoint-root:${session.generationId}",
                kind = AppSyncRemoteBlogKind.CheckpointRoot.name.uppercase(),
                blogId = rootBlogId,
                classId = null,
                fingerprint = rootFingerprint,
                validatedAtEpochMillis = activatedAtEpochMillis,
                contentUpdatedAtEpochMillis = activatedAtEpochMillis,
            )
            transition(
                sessionId,
                AppSyncRecoveryPhase.ActivatingLocal,
                AppSyncRecoveryPhase.Completed,
                activatedAtEpochMillis,
            )
        }
    }

    private fun activateCommittedSegmentedJournal(
        sessionId: String,
        activatedAtEpochMillis: Long,
    ) {
        val session = requireSession(sessionId)
        require(session.phase == AppSyncRecoveryPhase.ActivatingLocal && session.indexCommitted)
        require(session.mode == AppSyncRecoveryMode.SegmentedJournal)
        val rootBlogId = requireNotNull(session.rootBlogId)
        val rootFingerprint = requireNotNull(session.rootFingerprint)
        val installation = requireNotNull(SqlDelightAppSyncOperationStore(db, json).installation())
        require(installation.accountBinding == session.accountBinding)
        require(installation.deviceId == session.sourceDeviceId)
        require(installation.deviceEpoch == session.sourceDeviceEpoch)
        require(session.sourceOperationIds.all {
            queries.getOutboxOperation(it).executeAsOneOrNull() != null
        })
        db.transaction {
            if (session.sourceOperationIds.isNotEmpty()) {
                queries.markOperationsAcknowledged(
                    acknowledgedAtEpochMillis = activatedAtEpochMillis,
                    operationId = session.sourceOperationIds.toList(),
                )
            }
            queries.upsertRemoteBlog(
                remoteKey = "journal-root:${session.generationId}",
                kind = AppSyncRemoteBlogKind.JournalRoot.name.uppercase(),
                blogId = rootBlogId,
                classId = null,
                fingerprint = rootFingerprint,
                validatedAtEpochMillis = activatedAtEpochMillis,
                contentUpdatedAtEpochMillis = activatedAtEpochMillis,
            )
            queries.updateInstallationHeartbeat(
                lastVerifiedHeartbeatAt = activatedAtEpochMillis,
                journalBlogId = rootBlogId,
                state = AppSyncInstallationState.Active.name.uppercase(),
            )
            transition(
                sessionId,
                AppSyncRecoveryPhase.ActivatingLocal,
                AppSyncRecoveryPhase.Completed,
                activatedAtEpochMillis,
            )
        }
    }

    fun resumeRetryExhaustedRecovery(
        accountBinding: SyncAccountBinding,
        nowEpochMillis: Long,
    ): AppSyncRecoverySession? = db.transactionWithResult {
        val session = recoverySession(accountBinding) ?: return@transactionWithResult null
        val retryExhausted = session.retryIdentity != null && session.lastErrorCategory == "retry-exhausted"
        val nativeResume = usesNativeTransport(session.sessionId) && session.lastErrorCategory in
            setOf("native-compatibility", "native-cloud-validation", "native-recovery-evidence")
        if (session.phase != AppSyncRecoveryPhase.NeedsAttention || (!retryExhausted && !nativeResume)) {
            return@transactionWithResult session
        }
        val writes = segmentWrites(session.sessionId)
        val resumedPhase = when {
            session.mode == AppSyncRecoveryMode.LegacyShadow &&
                shadowOperations(session.sessionId).isEmpty() -> AppSyncRecoveryPhase.Classifying
            writes.isEmpty() || writes.any {
                it.blogId == null || it.verifiedFingerprint != it.expectedFingerprint
            } -> AppSyncRecoveryPhase.PublishingSegments
            session.rootBlogId == null -> AppSyncRecoveryPhase.PublishingRoot
            !session.indexCommitted -> AppSyncRecoveryPhase.CommittingIndex
            else -> AppSyncRecoveryPhase.ActivatingLocal
        }
        transition(
            sessionId = session.sessionId,
            expected = AppSyncRecoveryPhase.NeedsAttention,
            next = resumedPhase,
            nowEpochMillis = nowEpochMillis,
            retryCount = 0,
            retryIdentity = null,
        )
        requireSession(session.sessionId)
    }
    fun transition(
        sessionId: String,
        expected: AppSyncRecoveryPhase,
        next: AppSyncRecoveryPhase,
        nowEpochMillis: Long,
        retryCount: Long = requireSession(sessionId).retryCount,
        retryIdentity: String? = requireSession(sessionId).retryIdentity,
        nextRetryAtEpochMillis: Long? = null,
        lastErrorCategory: String? = null,
        blockingDomain: String? = null,
        redactedBlockingEntity: String? = null,
    ) {
        queries.transitionRecoverySession(
            phase = next.toDb(),
            retryCount = retryCount,
            retryIdentity = retryIdentity,
            nextRetryAtEpochMillis = nextRetryAtEpochMillis,
            lastErrorCategory = lastErrorCategory,
            blockingDomain = blockingDomain,
            redactedBlockingEntity = redactedBlockingEntity,
            updatedAtEpochMillis = nowEpochMillis,
            completedAtEpochMillis = nowEpochMillis.takeIf { next == AppSyncRecoveryPhase.Completed },
            sessionId = sessionId,
            phase_ = expected.toDb(),
        )
        check(requireSession(sessionId).phase == next) { "Recovery phase transition was rejected" }
    }

    fun rollbackPreCommit(sessionId: String) {
        val session = requireSession(sessionId)
        require(!session.indexCommitted && session.phase < AppSyncRecoveryPhase.CommittingIndex) {
            "Committed or ambiguous recovery cannot be rolled back without discovery"
        }
        db.transaction {
            queries.deleteRecoverySegmentWrites(sessionId)
            queries.deleteRecoveryShadowOperations(sessionId)
            queries.deleteRecoveryPayload(sessionId)
            queries.deleteRecoverySession(sessionId)
        }
    }

    private fun requireSession(sessionId: String): AppSyncRecoverySession =
        requireNotNull(queries.getRecoverySession(sessionId).executeAsOneOrNull()?.toModel()) {
            "Recovery session does not exist: $sessionId"
        }
}

private fun AppSyncRecoveryPhase.toDb(): String =
    name.replace(Regex("(?<=[a-z0-9])([A-Z])"), "_$1").uppercase()

private fun AppSyncRecoveryPhase.Companion.fromDb(value: String): AppSyncRecoveryPhase =
    AppSyncRecoveryPhase.entries.firstOrNull { it.toDb() == value }
        ?: error("Unknown recovery phase: $value")

private fun AppSyncOperationLifecycle.toDb(): String =
    name.replace(Regex("(?<=[a-z0-9])([A-Z])"), "_$1").uppercase()

private fun AppSyncRecoveryMode.toDb(): String =
    name.replace(Regex("(?<=[a-z0-9])([A-Z])"), "_$1").uppercase()

private fun AppSyncRecoveryMode.Companion.fromDb(value: String): AppSyncRecoveryMode =
    AppSyncRecoveryMode.entries.firstOrNull { it.toDb() == value }
        ?: error("Unknown recovery mode: $value")
