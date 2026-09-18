package me.thenano.yamibo.yamibo_app.repository.appsync.remote

import me.thenano.yamibo.yamibo_app.repository.appsync.schema.AppSyncCanonicalCheckpoint

/** Created only by validating both fetched index and checkpoint bodies with exact bindings. */
internal class AppSyncVerifiedCanonicalCheckpoint private constructor(
    val document: AppSyncCanonicalCheckpoint,
    val blogId: Long,
    val fingerprint: String,
    val indexFingerprint: String,
) {
    companion object {
        fun verify(expectedAccount: String, blogId: Long, indexBody: String,
            checkpointBody: String): AppSyncVerifiedCanonicalCheckpoint? {
            if (blogId <= 0 || blogId > Int.MAX_VALUE) return null
            val index = (AppSyncIndexEnvelopeCodec().validate(indexBody) as? AppSyncIndexValidation.Valid)?.envelope ?: return null
            if (index.payload.accountBinding.value != expectedAccount) return null
            val reference = index.payload.checkpoints.singleOrNull { it.blogId.toLong() == blogId } ?: return null
            if (index.payload.checkpoints.count { it.checkpointId == reference.checkpointId } != 1) return null
            val read = AppSyncTransportEnvelopeDispatcher().readCheckpoint(checkpointBody, expectedAccount, reference.checkpointId)
                as? AppSyncDispatchedDocument.CanonicalCheckpoint ?: return null
            if (reference.fingerprint != read.envelope.metadata.canonicalFingerprint) return null
            return AppSyncVerifiedCanonicalCheckpoint(read.envelope.document, blogId,
                read.envelope.metadata.canonicalFingerprint, index.fingerprint)
        }
    }
}
