package me.thenano.yamibo.yamibo_app.repository.appsync.remote

import me.thenano.yamibo.yamibo_app.repository.appsync.schema.AppSyncCanonicalCheckpoint
import me.thenano.yamibo.yamibo_app.repository.appsync.schema.AppSyncCanonicalCheckpointCodec

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
            val read = AppSyncV3DocumentCodec().discover(checkpointBody, expectedAccount, AppSyncV3PayloadKind.Checkpoint)
                as? AppSyncV3DocumentRead.Checkpoint ?: return null
            return bind(expectedAccount, blogId, index, read)
        }

        /** Uses the actual fetched index reader body, never a re-encoded substitute. The
         * checkpoint was already transport-validated; canonical content is checked again so
         * an altered in-memory document cannot reuse another document's metadata/digest.
         */
        fun verifyDocument(expectedAccount: String, blogId: Long, indexReaderHtml: String,
            checkpoint: AppSyncV3DocumentRead.Checkpoint): AppSyncVerifiedCanonicalCheckpoint? {
            val index = (AppSyncIndexEnvelopeCodec().validateReaderHtml(indexReaderHtml) as? AppSyncIndexValidation.Valid)?.envelope
                ?: return null
            return bind(expectedAccount, blogId, index, checkpoint)
        }

        private fun bind(expectedAccount: String, blogId: Long, index: ParsedAppSyncIndexEnvelope,
            read: AppSyncV3DocumentRead.Checkpoint): AppSyncVerifiedCanonicalCheckpoint? {
            if (blogId <= 0 || blogId > Int.MAX_VALUE) return null
            if (index.payload.accountBinding.value != expectedAccount) return null
            val reference = index.payload.checkpoints.singleOrNull { it.blogId.toLong() == blogId } ?: return null
            if (index.payload.checkpoints.count { it.checkpointId == reference.checkpointId } != 1) return null
            if (read.document.accountBinding != expectedAccount || read.document.checkpointId != reference.checkpointId ||
                read.metadata.schemaVersion != 3 || read.metadata.codecVersion != 1 || read.metadata.compressorId != 1 ||
                read.metadata.accountBinding != expectedAccount || read.metadata.kind != AppSyncV3PayloadKind.Checkpoint ||
                read.metadata.identity != reference.checkpointId || reference.fingerprint != read.metadata.canonicalFingerprint) return null
            val bytes = try { AppSyncCanonicalCheckpointCodec().encode(read.document) } catch (_: Exception) { return null }
            if (bytes.size != read.metadata.uncompressedLength || bytes.sha256().hex() != reference.fingerprint) return null
            return AppSyncVerifiedCanonicalCheckpoint(read.document, blogId, reference.fingerprint, index.fingerprint)
        }
    }
}
