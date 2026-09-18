package me.thenano.yamibo.yamibo_app.store.appsync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.thenano.yamibo.yamibo_app.Database
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncJournalLoadResult
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncInstallation
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncInstallationState
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncAccountBinding
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.*
import me.thenano.yamibo.yamibo_app.repository.appsync.schema.AppSyncCanonicalJournalCodec
import okio.ByteString.Companion.encodeUtf8

@Serializable
internal data class AppSyncReaderObservation(val replica: String, val writerNonce: String,
    val remoteId: String, val fingerprint: String, val readVersion: Int, val heartbeat: Long)

@Serializable
internal data class AppSyncReaderCohortEvidence(val version: Int = 1, val account: String,
    val observedAt: Long, val readers: List<AppSyncReaderObservation>)

/** Reader metadata only. A persisted compatible observation is necessary, never sufficient:
 * rollout, local reader readiness and device benchmark gates must also pass at each write.
 * Cached/failed loads revoke evidence; a writer must request a complete fresh scan.
 */
internal class SqlDelightAppSyncReaderCohortStore(private val db: Database,
    private val maximumAgeMillis: Long = 5 * 60 * 1_000L,
    private val inactiveAfterMillis: Long = 90L * 24 * 60 * 60 * 1_000) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    init { require(maximumAgeMillis > 0 && inactiveAfterMillis > 0) }

    fun observe(account: SyncAccountBinding, result: AppSyncJournalLoadResult, now: Long) {
        // Invalidate first, including if validation/serialization fails after a prior good scan.
        db.appSyncReaderCohortQueries.invalidate(account.value)
        val cloud = result as? AppSyncJournalLoadResult.Success ?: return
        if (!cloud.authoritativeDiscovery || now < 0 || cloud.canonicalReadIssues.isNotEmpty() ||
            cloud.retirementDiscoveryIssues.isNotEmpty() || cloud.journals.size + cloud.canonicalDocuments.size > 1024) return
        if (cloud.journals.sumOf { it.payload.operations.size.toLong() } + cloud.canonicalDocuments.sumOf {
                (it.document as? AppSyncV3DocumentRead.Journal)?.document?.block?.operations?.size?.toLong() ?: 0L
            } > 100_000L || cloud.canonicalDocuments.sumOf {
                (it.document as? AppSyncV3DocumentRead.Journal)?.metadata?.uncompressedLength?.toLong()?.coerceAtLeast(0) ?: 0L
            } > 64L * 1024 * 1024) return
        val readers = mutableListOf<AppSyncReaderObservation>()
        val codec = AppSyncJournalEnvelopeCodec()
        for (loaded in cloud.journals) {
            val journal = loaded.payload
            if (journal.accountBinding != account || codec.validatePayload(journal) != null) return
            readers += AppSyncReaderObservation("${journal.deviceId.value}:${journal.deviceEpoch.value}",
                journal.writerNonce.value, loaded.remoteId, loaded.fingerprint,
                journal.protocolReadVersion, journal.heartbeatAtEpochMillis)
        }
        for (loaded in cloud.canonicalDocuments) {
            when (val document = loaded.document) {
                is AppSyncV3DocumentRead.Checkpoint -> if (document.document.accountBinding != account.value) return
                is AppSyncV3DocumentRead.Journal -> {
                    val journal = document.document
                    val bytes = try { AppSyncCanonicalJournalCodec().encode(journal) }
                        catch (_: Exception) { return }
                    val fingerprint = bytes.sha256().hex()
                    val replica = "${journal.deviceId}:${journal.deviceEpoch}"
                    if (journal.block.accountBinding != account.value || document.metadata.accountBinding != account.value ||
                        document.metadata.kind != AppSyncV3PayloadKind.Journal || document.metadata.identity != replica ||
                        document.metadata.canonicalFingerprint != fingerprint || document.metadata.schemaVersion != 3 ||
                        document.metadata.codecVersion != 1 || document.metadata.compressorId != 1 ||
                        document.metadata.uncompressedLength != bytes.size) return
                    readers += AppSyncReaderObservation(replica, journal.writerNonce, loaded.remoteId, fingerprint,
                        journal.protocolReadVersion, journal.heartbeatAtEpochMillis)
                }
                else -> return
            }
        }
        if (readers.isEmpty() || readers.any { it.replica.isBlank() || it.replica.length > 2048 ||
                it.writerNonce.isBlank() || it.writerNonce.length > 1024 || it.remoteId.toIntOrNull()?.let { id -> id > 0 } != true ||
                it.fingerprint.isBlank() || it.fingerprint.length > 128 || it.readVersion < 1 || it.heartbeat < 0 }) return
        val unique = readers.distinct()
        if (unique.groupBy { it.replica }.any { (_, history) -> history.map { it.writerNonce }.distinct().size != 1 } ||
            unique.groupBy { it.remoteId }.any { it.value.size != 1 } ||
            !unique.map { it.replica }.toSet().containsAll(cloud.indexedReplicaKeys)) return
        val evidence = AppSyncReaderCohortEvidence(account = account.value, observedAt = now,
            readers = unique.sortedWith(compareBy({ it.replica }, { it.remoteId }, { it.fingerprint })))
        val encoded = json.encodeToString(AppSyncReaderCohortEvidence.serializer(), evidence)
        db.appSyncReaderCohortQueries.putEvidence(account.value, encoded, encoded.encodeUtf8().sha256().hex())
    }

    fun evidence(account: SyncAccountBinding): AppSyncReaderCohortEvidence? {
        val row = db.appSyncReaderCohortQueries.getEvidence(account.value).executeAsOneOrNull() ?: return null
        if (row.evidenceJson.length > 4 * 1024 * 1024 || row.evidenceJson.encodeUtf8().sha256().hex() != row.evidenceSha256) return null
        val result = try { json.decodeFromString(AppSyncReaderCohortEvidence.serializer(), row.evidenceJson) }
            catch (_: Exception) { return null }
        return result.takeIf { it.version == 1 && it.account == account.value && it.readers.size in 1..1024 }
    }

    fun canWrite(installation: AppSyncInstallation, now: Long, writerEnabled: Boolean,
        localReaderReady: Boolean, benchmarksApproved: Boolean): Boolean {
        return writerEnabled && benchmarksApproved && canWriteSanitizedV2(installation, now, localReaderReady)
    }

    /** Sanitized v2 needs the updated reader contract even though the envelope is v2.
     * Rolling back the v3 writer must not revoke read capability or bypass cohort evidence.
     * A true result does not authorize replacing/deleting an indexed native artifact.
     */
    fun canWriteSanitizedV2(installation: AppSyncInstallation, now: Long, localReaderReady: Boolean): Boolean {
        if (!localReaderReady || installation.state != AppSyncInstallationState.Active) return false
        val account = installation.accountBinding ?: return false
        val evidence = evidence(account) ?: return false
        if (now < evidence.observedAt || now - evidence.observedAt > maximumAgeMillis) return false
        val own = "${installation.deviceId.value}:${installation.deviceEpoch.value}"
        val ownHistory = evidence.readers.filter { it.replica == own }
        if (ownHistory.isEmpty() || ownHistory.any { it.writerNonce != installation.writerNonce.value }) return false
        fun active(reader: AppSyncReaderObservation): Boolean = reader.heartbeat >= evidence.observedAt ||
            evidence.observedAt - reader.heartbeat <= inactiveAfterMillis
        if (ownHistory.none(::active)) return false
        // Age against the authoritative observation, not against a later clock that might
        // silently age an incompatible reader out without discovering its newer heartbeat.
        // Retained generations are observations of the same writer, not duplicate devices.
        // Keep every active observation: a newer compatible artifact cannot hide an active
        // incompatible one, even when both claim the same writer nonce.
        return evidence.readers.filter(::active).all { it.readVersion >= 3 }
    }
}
