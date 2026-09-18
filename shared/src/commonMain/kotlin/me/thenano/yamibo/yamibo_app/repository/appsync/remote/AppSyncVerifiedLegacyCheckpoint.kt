package me.thenano.yamibo.yamibo_app.repository.appsync.remote

/** Proof of legacy index membership only. It is never canonical cleanup authorization. */
internal class AppSyncVerifiedLegacyCheckpoint private constructor(
    val account: String,
    val blogId: Long,
    val fingerprint: String,
    val indexFingerprint: String,
    private val checkpointEnvelope: String,
) {
    /** Parse the original immutable envelope again so caller-owned mutable collections cannot
     * change the contents to which the index proof is bound.
     */
    fun read(): ParsedAppSyncCheckpointEnvelope {
        val parsed = (AppSyncCheckpointEnvelopeCodec().validate(checkpointEnvelope) as? AppSyncCheckpointValidation.Valid)?.envelope
        requireNotNull(parsed)
        require(parsed.payload.accountBinding.value == account && parsed.fingerprint == fingerprint)
        return parsed
    }

    companion object {
        fun verify(expectedAccount: String, blogId: Long, indexReaderHtml: String,
            checkpointEnvelope: String): AppSyncVerifiedLegacyCheckpoint? {
            if (blogId !in 1..Int.MAX_VALUE.toLong()) return null
            val index = (AppSyncIndexEnvelopeCodec().validateReaderHtml(indexReaderHtml) as? AppSyncIndexValidation.Valid)?.envelope ?: return null
            if (index.payload.accountBinding.value != expectedAccount) return null
            val checkpoint = (AppSyncCheckpointEnvelopeCodec().validate(checkpointEnvelope) as? AppSyncCheckpointValidation.Valid)?.envelope ?: return null
            if (checkpoint.payload.accountBinding.value != expectedAccount) return null
            val reference = index.payload.checkpoints.singleOrNull { it.blogId.toLong() == blogId } ?: return null
            if (index.payload.checkpoints.count { it.checkpointId == reference.checkpointId } != 1 ||
                reference.checkpointId != checkpoint.payload.checkpointId || reference.fingerprint != checkpoint.fingerprint) return null
            return AppSyncVerifiedLegacyCheckpoint(expectedAccount, blogId, checkpoint.fingerprint, index.fingerprint, checkpointEnvelope)
        }
    }
}
