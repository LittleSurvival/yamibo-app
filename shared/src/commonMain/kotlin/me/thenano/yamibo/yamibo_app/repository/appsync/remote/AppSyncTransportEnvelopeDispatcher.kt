package me.thenano.yamibo.yamibo_app.repository.appsync.remote

/** Reader-first dispatch: v1 bodies remain valid while committed v2 roots reconstruct them. */
internal class AppSyncTransportEnvelopeDispatcher(
    private val segmentCodec: AppSyncSegmentEnvelopeCodec = AppSyncSegmentEnvelopeCodec(),
    private val journalCodec: AppSyncJournalEnvelopeCodec = AppSyncJournalEnvelopeCodec(),
    private val checkpointCodec: AppSyncCheckpointEnvelopeCodec = AppSyncCheckpointEnvelopeCodec(),
) {
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
    ): Result<String> {
        if (!body.contains(AppSyncSegmentEnvelopeCodec.ROOT_MARKER)) return Result.success(body)
        return runCatching {
            val root = segmentCodec.decodeRoot(body).getOrThrow()
            require(root.kind == expectedKind.name.lowercase()) { "Segmented payload kind is invalid" }
            when (val reconstructed = segmentCodec.reconstruct(root, loadSegmentBody)) {
                is AppSyncSegmentReconstruction.Valid -> reconstructed.canonicalEnvelope
                is AppSyncSegmentReconstruction.Invalid -> error(reconstructed.reason)
            }
        }
    }
}
