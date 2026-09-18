package me.thenano.yamibo.yamibo_app.repository.appsync.engine

import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncInstallation
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.*
import me.thenano.yamibo.yamibo_app.repository.appsync.schema.*

/** Reconstructs this writer's retained cloud tail before a new native publication.
 * Only index-verified checkpoint coverage can replace a missing/legacy prefix; a local
 * overlay or another writer's sequence must never be substituted for that proof.
 */
internal object AppSyncCanonicalJournalBaseline {
    fun prepare(installation: AppSyncInstallation, cloud: AppSyncCanonicalCloudPlan.Ready): Result<AppSyncV3DocumentRead.Journal> = runCatching {
        val account = requireNotNull(installation.accountBinding).value
        val device = installation.deviceId.value
        val epoch = installation.deviceEpoch.value
        val replica = "$device:$epoch"
        val checkpoint = cloud.checkpoint.document
        require(checkpoint.accountBinding == account && cloud.canonicalOperations.accountBinding == account)
        require(AppSyncCanonicalCheckpointCodec().encode(checkpoint).sha256().hex() == cloud.checkpoint.fingerprint)
        val covered = checkpoint.coverage[replica] ?: 0L
        val native = cloud.nativeJournals.filter { it.document.deviceId == device && it.document.deviceEpoch == epoch }
        val legacy = cloud.legacyJournals.filter { it.deviceId.value == device && it.deviceEpoch.value == epoch }
        require(native.size + legacy.size <= 1024)
        val codec = AppSyncV3DocumentCodec()
        val observed = checkpoint.coverage.toMutableMap()
        val acks = linkedMapOf<String, AppSyncCanonicalAcknowledgement>()
        fun observe(values: Map<String, Long>) {
            values.forEach { (key, value) -> observed[key] = maxOf(observed[key] ?: 0L, value) }
        }
        fun acknowledge(ack: AppSyncCanonicalAcknowledgement) {
            val prior = acks.put(ack.checkpointId, ack)
            require(prior == null || prior == ack) { "Checkpoint acknowledgement collision" }
        }
        var published = covered
        var heartbeat = 0L
        for (read in native) {
            val journal = read.document
            require(journal.block.accountBinding == account && journal.writerNonce == installation.writerNonce.value)
            require(codec.discover(codec.encodeJournal(replica, journal), account, AppSyncV3PayloadKind.Journal) == read)
            published = maxOf(published, journal.lastSequence, journal.publishedThroughSequence ?: 0L,
                journal.observed[replica] ?: 0L)
            heartbeat = maxOf(heartbeat, journal.heartbeatAtEpochMillis)
            observe(journal.observed)
            journal.acknowledgements.forEach(::acknowledge)
        }
        for (journal in legacy) {
            require(journal.accountBinding.value == account && journal.writerNonce == installation.writerNonce)
            require(AppSyncJournalEnvelopeCodec().validatePayload(journal) == null)
            published = maxOf(published, journal.lastSequence, journal.publishedThroughSequence ?: 0L,
                journal.observed.asStableMap()[replica] ?: 0L)
            heartbeat = maxOf(heartbeat, journal.heartbeatAtEpochMillis)
            observe(journal.observed.asStableMap())
            journal.checkpointAcknowledgements.forEach { acknowledge(AppSyncCanonicalAcknowledgement(it.checkpointId, it.coverage.asStableMap())) }
        }
        acknowledge(AppSyncCanonicalAcknowledgement(checkpoint.checkpointId, checkpoint.coverage))
        val operations = linkedMapOf<Long, AppSyncCanonicalOperation>()
        val proofs = linkedMapOf<String, AppSyncCanonicalDeleteProof>()
        fun proof(value: AppSyncCanonicalDeleteProof) {
            val previous = proofs.put(value.authorizationId, value)
            require(previous == null || previous == value) { "Cloud proof collision" }
        }
        fun operation(value: AppSyncCanonicalOperation) {
            if (value.deviceId != device || value.deviceEpoch != epoch || value.sequence <= covered) return
            val previous = operations.put(value.sequence, value)
            require(previous == null || previous == value) { "Cloud operation collision" }
            require(operations.size <= 100_000)
        }
        cloud.canonicalOperations.authorizations.forEach(::proof)
        cloud.canonicalOperations.operations.forEach(::operation)
        // Include validated physical aliases; no distinct-by-replica can erase their tail.
        native.forEach { read ->
            read.document.block.authorizations.forEach(::proof)
            read.document.block.operations.forEach(::operation)
        }
        val importer = AppSyncCanonicalOperationImporter()
        for (source in cloud.legacyOperations + legacy.flatMap { it.operations }) {
            require(source.accountBinding.value == account)
            if (source.deviceId.value != device || source.deviceEpoch.value != epoch || source.sequence.value <= covered) continue
            val imported = importer.import(account, source) as? AppSyncCanonicalOperationImport.Accepted
            requireNotNull(imported) { "Uncovered legacy sequence cannot be omitted" }
            operation(imported.operation)
            imported.proof?.let(::proof)
        }
        val tail = operations.values.sortedBy { it.sequence }
        published = maxOf(published, tail.lastOrNull()?.sequence ?: 0L)
        require(published < installation.nextSequence) { "Cloud writer is ahead of this installation" }
        if (tail.isEmpty()) require(published == covered) { "Missing published cloud tail" }
        else {
            require(covered < Long.MAX_VALUE && tail.first().sequence == covered + 1)
            require(tail.last().sequence == published)
            require(tail.zipWithNext().all { (a, b) -> a.sequence < Long.MAX_VALUE && b.sequence == a.sequence + 1 })
        }
        observed[replica] = published
        val proofIds = tail.mapNotNull { it.authorizationId }.toSet()
        require(proofs.keys.containsAll(proofIds))
        val baseline = AppSyncCanonicalJournal(AppSyncCanonicalOperationBlock(account, tail,
            proofIds.sorted().map(proofs::getValue)), device, epoch, installation.writerNonce.value,
            tail.firstOrNull()?.sequence ?: 0, tail.lastOrNull()?.sequence ?: 0, observed,
            acks.values.sortedBy { it.checkpointId }, heartbeat, 3, 3, "unknown", published)
        codec.discover(codec.encodeJournal(replica, baseline), account, AppSyncV3PayloadKind.Journal) as AppSyncV3DocumentRead.Journal
    }
}
