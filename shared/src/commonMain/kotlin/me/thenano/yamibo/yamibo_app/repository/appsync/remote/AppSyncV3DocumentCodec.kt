package me.thenano.yamibo.yamibo_app.repository.appsync.remote

import me.thenano.yamibo.yamibo_app.repository.appsync.schema.*

internal sealed interface AppSyncV3DocumentRead {
    data class Journal(val document: AppSyncCanonicalJournal, val metadata: AppSyncV3EnvelopeMetadata) : AppSyncV3DocumentRead
    data class Checkpoint(val document: AppSyncCanonicalCheckpoint, val metadata: AppSyncV3EnvelopeMetadata) : AppSyncV3DocumentRead
    data class Unsupported(val schemaVersion: Int, val codecVersion: Int, val compressorId: Int) : AppSyncV3DocumentRead
    data class Invalid(val transportError: AppSyncV3EnvelopeError? = null) : AppSyncV3DocumentRead
}

/** Full transport + canonical validation. Production materialization/capability rollout is separate. */
internal class AppSyncV3DocumentCodec(
    private val envelope: AppSyncV3EnvelopeCodec = AppSyncV3EnvelopeCodec(),
    private val journal: AppSyncCanonicalJournalCodec = AppSyncCanonicalJournalCodec(),
    private val checkpoint: AppSyncCanonicalCheckpointCodec = AppSyncCanonicalCheckpointCodec(),
) {
    /** Discovery verifies the account and inner/outer identity, but does not establish index
     * membership or freshness. Activation still requires separately verified index evidence.
     */
    fun discover(text: String, expectedAccount: String, expectedKind: AppSyncV3PayloadKind): AppSyncV3DocumentRead =
        when (val verified = envelope.decode(text, expectedAccount, expectedKind, null)) {
            is AppSyncV3EnvelopeRead.Invalid -> AppSyncV3DocumentRead.Invalid(verified.reason)
            is AppSyncV3EnvelopeRead.Unsupported -> AppSyncV3DocumentRead.Unsupported(
                verified.schemaVersion, verified.codecVersion, verified.compressorId)
            is AppSyncV3EnvelopeRead.VerifiedBytes -> try {
                when (expectedKind) {
                    AppSyncV3PayloadKind.Checkpoint -> AppSyncV3DocumentRead.Checkpoint(
                        checkpoint.decode(expectedAccount, verified.metadata.identity, verified.bytes), verified.metadata)
                    AppSyncV3PayloadKind.Journal -> {
                        val document = journal.decode(expectedAccount, null, null, verified.bytes)
                        require(verified.metadata.identity == "${document.deviceId}:${document.deviceEpoch}") { "Journal identity mismatch" }
                        AppSyncV3DocumentRead.Journal(document, verified.metadata)
                    }
                }
            } catch (_: Exception) { AppSyncV3DocumentRead.Invalid() }
        }

    fun encodeJournal(identity: String, document: AppSyncCanonicalJournal): String = envelope.encode(
        AppSyncV3PayloadKind.Journal, document.block.accountBinding, identity, journal.encode(document))

    fun encodeCheckpoint(document: AppSyncCanonicalCheckpoint): String = envelope.encode(
        AppSyncV3PayloadKind.Checkpoint, document.accountBinding, document.checkpointId, checkpoint.encode(document))

    fun readJournal(text: String, expectedAccount: String, expectedIdentity: String,
        expectedDevice: String, expectedEpoch: String): AppSyncV3DocumentRead = read(
        text, expectedAccount, AppSyncV3PayloadKind.Journal, expectedIdentity,
    ) { verified ->
        AppSyncV3DocumentRead.Journal(journal.decode(expectedAccount, expectedDevice, expectedEpoch, verified.bytes), verified.metadata)
    }

    fun readCheckpoint(text: String, expectedAccount: String, expectedId: String): AppSyncV3DocumentRead = read(
        text, expectedAccount, AppSyncV3PayloadKind.Checkpoint, expectedId,
    ) { verified ->
        AppSyncV3DocumentRead.Checkpoint(checkpoint.decode(expectedAccount, expectedId, verified.bytes), verified.metadata)
    }

    private fun read(text: String, account: String, kind: AppSyncV3PayloadKind, identity: String,
        decode: (AppSyncV3EnvelopeRead.VerifiedBytes) -> AppSyncV3DocumentRead): AppSyncV3DocumentRead =
        when (val verified = envelope.decode(text, account, kind, identity)) {
            is AppSyncV3EnvelopeRead.Invalid -> AppSyncV3DocumentRead.Invalid(verified.reason)
            is AppSyncV3EnvelopeRead.Unsupported -> AppSyncV3DocumentRead.Unsupported(
                verified.schemaVersion, verified.codecVersion, verified.compressorId)
            is AppSyncV3EnvelopeRead.VerifiedBytes -> try { decode(verified) } catch (_: Exception) {
                AppSyncV3DocumentRead.Invalid()
            }
        }
}
