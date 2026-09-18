package me.thenano.yamibo.yamibo_app.repository.appsync.remote

internal sealed interface AppSyncDispatchedDocument {
    data class LegacyJournal(val envelope: ParsedAppSyncJournalEnvelope) : AppSyncDispatchedDocument
    data class LegacyCheckpoint(val envelope: ParsedAppSyncCheckpointEnvelope) : AppSyncDispatchedDocument
    data class CanonicalJournal(val envelope: AppSyncV3DocumentRead.Journal) : AppSyncDispatchedDocument
    data class CanonicalCheckpoint(val envelope: AppSyncV3DocumentRead.Checkpoint) : AppSyncDispatchedDocument
    data class Unsupported(val versions: AppSyncV3DocumentRead.Unsupported) : AppSyncDispatchedDocument
    data object Invalid : AppSyncDispatchedDocument
}

/** Reader-first dispatch: v1 bodies remain valid while committed v2 roots reconstruct them. */
internal class AppSyncTransportEnvelopeDispatcher(
    private val segmentCodec: AppSyncSegmentEnvelopeCodec = AppSyncSegmentEnvelopeCodec(),
    private val journalCodec: AppSyncJournalEnvelopeCodec = AppSyncJournalEnvelopeCodec(),
    private val checkpointCodec: AppSyncCheckpointEnvelopeCodec = AppSyncCheckpointEnvelopeCodec(),
    private val v3Codec: AppSyncV3DocumentCodec = AppSyncV3DocumentCodec(),
) {
    fun readCheckpoint(body: String, expectedAccount: String, expectedId: String,
        loadSegmentBody: (String) -> String? = { null }): AppSyncDispatchedDocument {
        val canonical = reconstructIfRoot(body, AppSyncSegmentPayloadKind.Checkpoint, loadSegmentBody, expectedAccount, expectedId)
            .getOrElse { return AppSyncDispatchedDocument.Invalid }
        if (canonical.contains(APP_SYNC_V3_ENVELOPE_MARKER)) return dispatched(v3Codec.readCheckpoint(canonical, expectedAccount, expectedId))
        return when (val result = checkpointCodec.validate(canonical)) {
            is AppSyncCheckpointValidation.Valid -> if (result.envelope.payload.accountBinding.value == expectedAccount &&
                result.envelope.payload.checkpointId == expectedId) AppSyncDispatchedDocument.LegacyCheckpoint(result.envelope)
                else AppSyncDispatchedDocument.Invalid
            is AppSyncCheckpointValidation.Invalid -> AppSyncDispatchedDocument.Invalid
        }
    }

    fun readJournal(body: String, expectedAccount: String, expectedIdentity: String,
        expectedDevice: String, expectedEpoch: String,
        loadSegmentBody: (String) -> String? = { null }): AppSyncDispatchedDocument {
        val canonical = reconstructIfRoot(body, AppSyncSegmentPayloadKind.Journal, loadSegmentBody, expectedAccount, expectedIdentity)
            .getOrElse { return AppSyncDispatchedDocument.Invalid }
        if (canonical.contains(APP_SYNC_V3_ENVELOPE_MARKER)) return dispatched(v3Codec.readJournal(
            canonical, expectedAccount, expectedIdentity, expectedDevice, expectedEpoch))
        return when (val result = journalCodec.validate(canonical)) {
            is AppSyncJournalValidation.Valid -> if (result.envelope.payload.accountBinding.value == expectedAccount &&
                result.envelope.payload.deviceId.value == expectedDevice && result.envelope.payload.deviceEpoch.value == expectedEpoch)
                AppSyncDispatchedDocument.LegacyJournal(result.envelope) else AppSyncDispatchedDocument.Invalid
            is AppSyncJournalValidation.Invalid -> AppSyncDispatchedDocument.Invalid
        }
    }

    private fun dispatched(document: AppSyncV3DocumentRead): AppSyncDispatchedDocument = when (document) {
        is AppSyncV3DocumentRead.Journal -> AppSyncDispatchedDocument.CanonicalJournal(document)
        is AppSyncV3DocumentRead.Checkpoint -> AppSyncDispatchedDocument.CanonicalCheckpoint(document)
        is AppSyncV3DocumentRead.Unsupported -> AppSyncDispatchedDocument.Unsupported(document)
        is AppSyncV3DocumentRead.Invalid -> AppSyncDispatchedDocument.Invalid
    }

    fun validateJournal(
        body: String,
        loadSegmentBody: (String) -> String? = { null },
    ): AppSyncJournalValidation {
        val canonical = reconstructIfRoot(body, AppSyncSegmentPayloadKind.Journal, loadSegmentBody)
            .getOrElse {
                return AppSyncJournalValidation.Invalid(
                    reason = it.message ?: "Segmented Journal is invalid",
                    markerPresent = true,
                )
            }
        return journalCodec.validate(canonical)
    }

    fun validateCheckpoint(
        body: String,
        loadSegmentBody: (String) -> String? = { null },
    ): AppSyncCheckpointValidation {
        val canonical = reconstructIfRoot(body, AppSyncSegmentPayloadKind.Checkpoint, loadSegmentBody)
            .getOrElse {
                return AppSyncCheckpointValidation.Invalid(
                    reason = it.message ?: "Segmented Checkpoint is invalid",
                    markerPresent = true,
                )
            }
        return checkpointCodec.validate(canonical)
    }

    private fun reconstructIfRoot(
        body: String,
        expectedKind: AppSyncSegmentPayloadKind,
        loadSegmentBody: (String) -> String?,
        expectedAccount: String? = null,
        expectedIdentity: String? = null,
    ): Result<String> {
        if (!body.contains(AppSyncSegmentEnvelopeCodec.ROOT_MARKER)) return Result.success(body)
        return runCatching {
            val root = segmentCodec.decodeRoot(body).getOrThrow()
            require(root.kind == expectedKind.name.lowercase()) { "Segmented payload kind is invalid" }
            require(expectedAccount == null || root.accountBinding == expectedAccount) { "Segmented account mismatch" }
            require(expectedIdentity == null || root.identity == expectedIdentity) { "Segmented identity mismatch" }
            when (val reconstructed = segmentCodec.reconstruct(root, loadSegmentBody)) {
                is AppSyncSegmentReconstruction.Valid -> reconstructed.canonicalEnvelope
                is AppSyncSegmentReconstruction.Invalid -> error(reconstructed.reason)
            }
        }
    }
}
