package me.thenano.yamibo.yamibo_app.repository.appsync.engine

import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncInstallation
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncInstallationState
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.*
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.*
import me.thenano.yamibo.yamibo_app.repository.appsync.schema.*

internal enum class AppSyncV2JournalFailure { Compatibility, Writer, InvalidJournal, OperationExport }
internal sealed interface AppSyncV2JournalPreparation {
    data class Ready(val payload: AppSyncJournalPayload, val envelope: String, val fingerprint: String) : AppSyncV2JournalPreparation
    data class NeedsAttention(val reason: AppSyncV2JournalFailure) : AppSyncV2JournalPreparation
}

/** Prepares immutable rollback bytes only. Durable publication must recheck capability and
 * preserve native roots; preparation neither writes the provider nor acknowledges sources.
 */
internal class AppSyncSanitizedV2JournalPreparation(private val canWrite: () -> Boolean = { false }) {
    fun prepare(installation: AppSyncInstallation, journal: AppSyncCanonicalJournal): AppSyncV2JournalPreparation {
        if (!canWrite()) return fail(AppSyncV2JournalFailure.Compatibility)
        val replica = "${installation.deviceId.value}:${installation.deviceEpoch.value}"
        if (installation.state != AppSyncInstallationState.Active || installation.accountBinding?.value != journal.block.accountBinding ||
            installation.deviceId.value != journal.deviceId || installation.deviceEpoch.value != journal.deviceEpoch ||
            installation.writerNonce.value != journal.writerNonce ||
            maxOf(journal.lastSequence, journal.publishedThroughSequence ?: 0, journal.observed[replica] ?: 0) >= installation.nextSequence)
            return fail(AppSyncV2JournalFailure.Writer)
        val result = encodeFrozen(journal)
        return if (canWrite()) result else fail(AppSyncV2JournalFailure.Compatibility)
    }

    /** Pure conversion for checking retained evidence after its original writer has changed.
     * This grants no publication permission; new sessions must use prepare and provider gates.
     */
    internal fun encodeFrozen(journal: AppSyncCanonicalJournal): AppSyncV2JournalPreparation {
        if (journal.protocolReadVersion < 3) return fail(AppSyncV2JournalFailure.Compatibility)
        try { AppSyncCanonicalJournalCodec().encode(journal) }
        catch (_: Exception) { return fail(AppSyncV2JournalFailure.InvalidJournal) }
        val exported = AppSyncSanitizedV2OperationExporter().export(journal.block, allowPortableEventIdentity = true)
        if (exported !is AppSyncV2OperationExport.Ready) return fail(AppSyncV2JournalFailure.OperationExport)
        return try {
            val codec = AppSyncJournalEnvelopeCodec()
            val payload = codec.wirePayload(AppSyncJournalPayload(SyncAccountBinding(journal.block.accountBinding), SyncDeviceId(journal.deviceId),
                SyncDeviceEpoch(journal.deviceEpoch), SyncWriterNonce(journal.writerNonce), journal.firstSequence, journal.lastSequence,
                exported.operations.sortedBy { it.sequence.value }, SyncCausalContext(journal.observed),
                journal.acknowledgements.sortedBy { it.checkpointId }.map {
                    AppSyncCheckpointAcknowledgement(it.checkpointId, SyncCausalContext(it.coverage))
                }, journal.heartbeatAtEpochMillis, journal.protocolReadVersion, 2, journal.appVersion, journal.publishedThroughSequence))
            val envelope = codec.encodeSanitizedFallback(payload)
            val verified = codec.validate(envelope) as? AppSyncJournalValidation.Valid
                ?: return fail(AppSyncV2JournalFailure.InvalidJournal)
            if (verified.envelope.payload != payload) return fail(AppSyncV2JournalFailure.InvalidJournal)
            AppSyncV2JournalPreparation.Ready(payload, envelope, verified.envelope.fingerprint)
        } catch (_: Exception) { fail(AppSyncV2JournalFailure.InvalidJournal) }
    }

    private fun fail(reason: AppSyncV2JournalFailure) = AppSyncV2JournalPreparation.NeedsAttention(reason)
}
