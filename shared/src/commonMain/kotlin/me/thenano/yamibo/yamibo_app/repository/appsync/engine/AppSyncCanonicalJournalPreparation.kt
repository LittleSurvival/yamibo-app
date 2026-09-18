package me.thenano.yamibo.yamibo_app.repository.appsync.engine

import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncInstallation
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.*
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.*
import me.thenano.yamibo.yamibo_app.repository.appsync.schema.*

internal enum class AppSyncCanonicalJournalFailure {
    Account, Writer, InvalidDocument, SourceImport, OperationCollision, ProofCollision,
    SequenceGap, Coverage, Acknowledgement, Budget,
}
internal sealed interface AppSyncCanonicalJournalPreparationResult {
    data class Ready(val journal: AppSyncCanonicalJournal, val envelope: String,
        val fingerprint: String, val sourceOperationIds: Set<SyncOperationId>) : AppSyncCanonicalJournalPreparationResult
    data class NeedsAttention(val reason: AppSyncCanonicalJournalFailure) : AppSyncCanonicalJournalPreparationResult
}

/** Builds one immutable publication body. The caller still needs the persisted reader-cohort
 * gate, remote compare/readback, durable retries and acknowledgement transaction. No pruning.
 */
internal class AppSyncCanonicalJournalPreparation {
    fun prepare(installation: AppSyncInstallation, local: AppSyncCanonicalCheckpoint,
        existing: AppSyncV3DocumentRead.Journal?, sources: List<SyncOperation>,
        acknowledgements: List<AppSyncVerifiedCanonicalCheckpoint>, heartbeat: Long,
        appVersion: String): AppSyncCanonicalJournalPreparationResult {
        fun fail(reason: AppSyncCanonicalJournalFailure) = AppSyncCanonicalJournalPreparationResult.NeedsAttention(reason)
        val account = installation.accountBinding?.value ?: return fail(AppSyncCanonicalJournalFailure.Account)
        if (local.accountBinding != account || sources.any { it.accountBinding.value != account })
            return fail(AppSyncCanonicalJournalFailure.Account)
        if (sources.size.toLong() + (existing?.document?.block?.operations?.size ?: 0) > 100_000 || acknowledgements.size > 1024)
            return fail(AppSyncCanonicalJournalFailure.Budget)
        val device = installation.deviceId.value
        val epoch = installation.deviceEpoch.value
        val replica = "$device:$epoch"
        val codec = AppSyncV3DocumentCodec()
        val checkpointCodec = AppSyncCanonicalCheckpointCodec()
        try { checkpointCodec.encode(local) } catch (_: Exception) { return fail(AppSyncCanonicalJournalFailure.InvalidDocument) }
        val prior = existing?.document
        if (prior != null) {
            if (prior.block.accountBinding != account) return fail(AppSyncCanonicalJournalFailure.Account)
            if (prior.deviceId != device || prior.deviceEpoch != epoch || prior.writerNonce != installation.writerNonce.value)
                return fail(AppSyncCanonicalJournalFailure.Writer)
            val rebuilt = try { codec.discover(codec.encodeJournal(replica, prior), account, AppSyncV3PayloadKind.Journal) }
                catch (_: Exception) { return fail(AppSyncCanonicalJournalFailure.InvalidDocument) }
            if (rebuilt != existing) return fail(AppSyncCanonicalJournalFailure.InvalidDocument)
        }
        val oldPublished = maxOf(prior?.publishedThroughSequence ?: 0L, prior?.lastSequence ?: 0L, prior?.observed?.get(replica) ?: 0L)
        if (oldPublished >= installation.nextSequence) return fail(AppSyncCanonicalJournalFailure.Writer)
        val operations = linkedMapOf<Long, AppSyncCanonicalOperation>()
        val proofs = linkedMapOf<String, AppSyncCanonicalDeleteProof>()
        prior?.block?.operations.orEmpty().forEach { operations[it.sequence] = it }
        prior?.block?.authorizations.orEmpty().forEach { proofs[it.authorizationId] = it }
        val ids = linkedSetOf<SyncOperationId>()
        for (source in sources) {
            if (source.deviceId.value != device || source.deviceEpoch.value != epoch || source.sequence.value >= installation.nextSequence)
                return fail(AppSyncCanonicalJournalFailure.Writer)
            if (source.sequence.value <= oldPublished && prior?.block?.operations?.none { it.sequence == source.sequence.value } == true)
                return fail(AppSyncCanonicalJournalFailure.SequenceGap)
            val imported = AppSyncCanonicalOperationImporter().import(account, source)
            // Skipping an excluded/no-op legacy source would punch a hole in the writer stream.
            if (imported !is AppSyncCanonicalOperationImport.Accepted) return fail(AppSyncCanonicalJournalFailure.SourceImport)
            val previous = operations.put(source.sequence.value, imported.operation)
            if (previous != null && previous != imported.operation) return fail(AppSyncCanonicalJournalFailure.OperationCollision)
            imported.proof?.let { proof ->
                val old = proofs.put(proof.authorizationId, proof)
                if (old != null && old != proof) return fail(AppSyncCanonicalJournalFailure.ProofCollision)
            }
            ids += source.operationId
        }
        val ordered = operations.values.sortedBy { it.sequence }
        if (ordered.any { it.sequence >= installation.nextSequence }) return fail(AppSyncCanonicalJournalFailure.Writer)
        if (ordered.zipWithNext().any { (a, b) -> a.sequence == Long.MAX_VALUE || b.sequence != a.sequence + 1 })
            return fail(AppSyncCanonicalJournalFailure.SequenceGap)
        val first = ordered.firstOrNull()?.sequence ?: 0L
        val last = ordered.lastOrNull()?.sequence ?: 0L
        if ((first > 1 && prior == null) || (prior?.block?.operations?.isEmpty() == true &&
                oldPublished < Long.MAX_VALUE && first > oldPublished + 1))
            return fail(AppSyncCanonicalJournalFailure.SequenceGap)
        val published = maxOf(oldPublished, last)
        if ((local.coverage[replica] ?: 0L) != published) return fail(AppSyncCanonicalJournalFailure.Coverage)
        fun dominates(coverage: Map<String, Long>) = coverage.all { (key, value) -> (local.coverage[key] ?: 0L) >= value }
        if (prior != null && !dominates(prior.observed)) return fail(AppSyncCanonicalJournalFailure.Coverage)
        val acks = linkedMapOf<String, AppSyncCanonicalAcknowledgement>()
        prior?.acknowledgements.orEmpty().forEach {
            if (!dominates(it.coverage)) return fail(AppSyncCanonicalJournalFailure.Acknowledgement)
            acks[it.checkpointId] = it
        }
        val acknowledgementFingerprints = linkedMapOf<String, String>()
        for (verified in acknowledgements) {
            val checkpoint = verified.document
            if (checkpoint.accountBinding != account || !dominates(checkpoint.coverage)) return fail(AppSyncCanonicalJournalFailure.Acknowledgement)
            val bytes = try { checkpointCodec.encode(checkpoint) } catch (_: Exception) { return fail(AppSyncCanonicalJournalFailure.InvalidDocument) }
            if (bytes.sha256().hex() != verified.fingerprint) return fail(AppSyncCanonicalJournalFailure.Acknowledgement)
            val priorFingerprint = acknowledgementFingerprints.put(checkpoint.checkpointId, verified.fingerprint)
            if (priorFingerprint != null && priorFingerprint != verified.fingerprint) return fail(AppSyncCanonicalJournalFailure.Acknowledgement)
            val ack = AppSyncCanonicalAcknowledgement(checkpoint.checkpointId, checkpoint.coverage)
            val old = acks.put(ack.checkpointId, ack)
            if (old != null && old != ack) return fail(AppSyncCanonicalJournalFailure.Acknowledgement)
        }
        val journal = AppSyncCanonicalJournal(AppSyncCanonicalOperationBlock(account, ordered, proofs.values.sortedBy { it.authorizationId }),
            device, epoch, installation.writerNonce.value, first, last, local.coverage,
            acks.values.sortedBy { it.checkpointId }, heartbeat, 3, 3, appVersion, published)
        return try {
            if (OperationReducer().reduceCanonical(local, journal.block).quarantined.isNotEmpty())
                return fail(AppSyncCanonicalJournalFailure.SourceImport)
            val body = codec.encodeJournal(replica, journal)
            val read = codec.discover(body, account, AppSyncV3PayloadKind.Journal) as AppSyncV3DocumentRead.Journal
            AppSyncCanonicalJournalPreparationResult.Ready(read.document, body, read.metadata.canonicalFingerprint, ids)
        } catch (_: Exception) { fail(AppSyncCanonicalJournalFailure.InvalidDocument) }
    }
}
