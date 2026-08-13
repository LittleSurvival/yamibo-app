package me.thenano.yamibo.yamibo_app.repository.appsync.remote

import io.github.littlesurvival.dto.value.FormHash
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncCheckpointPublishResult
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncCheckpointRetentionResult
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncJournalLoadResult
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncJournalPublishResult
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncJournalRemote
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncJournalRetirementRemoteResult
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.LoadedAppSyncCheckpoint
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.LoadedAppSyncJournal
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncJournalRetirementIntent
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncAccountBinding
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncReplicaKey
import me.thenano.yamibo.yamibo_app.repository.pancloud.PanCloudAccountRepository
import me.thenano.yamibo.yamibo_app.repository.pancloud.PanCloudApiClient
import me.thenano.yamibo.yamibo_app.util.time.currentTimeMillis

/**
 * 网盘实现 [AppSyncJournalRemote]：把 AppSync 的 Index / Journal / Checkpoint 映射为
 * 网盘 `yamibo` 文件夹内的文件，复用 AppSync 引擎层的多设备增量合并。
 *
 * - Index   → `index.json`
 * - Journal → `journal-<replicaKey>.json`（single-writer，每设备只写自己的文件）
 * - Checkpoint → `checkpoint-<id>.json`
 *
 * 论坛 Blog 的 `formHash` 参数在此忽略；文件内容即对应 codec 的 `encode(...)` 输出。
 */
internal class PanCloudJournalRemote(
    private val apiClient: PanCloudApiClient,
    private val accountRepository: PanCloudAccountRepository,
    private val journalCodec: AppSyncJournalEnvelopeCodec = AppSyncJournalEnvelopeCodec(),
    private val indexCodec: AppSyncIndexEnvelopeCodec = AppSyncIndexEnvelopeCodec(),
    private val checkpointCodec: AppSyncCheckpointEnvelopeCodec = AppSyncCheckpointEnvelopeCodec(),
    private val nowMillis: () -> Long = ::currentTimeMillis,
) : AppSyncJournalRemote {

    @Suppress("UNUSED_PARAMETER")
    override suspend fun loadJournals(
        accountBinding: SyncAccountBinding,
        forceDiscovery: Boolean,
    ): AppSyncJournalLoadResult {
        val parentId = folderId()
            ?: return AppSyncJournalLoadResult.Success(
                journals = emptyList(),
                checkpoints = emptyList(),
                indexedReplicaKeys = emptySet(),
            )
        val files = try {
            apiClient.listFiles(parentId = parentId)
        } catch (error: Throwable) {
            return AppSyncJournalLoadResult.RetryableFailure(
                "網盤 list 失敗：${error.message ?: error::class.simpleName}",
            )
        }

        val journals = mutableListOf<LoadedAppSyncJournal>()
        val checkpoints = mutableListOf<LoadedAppSyncCheckpoint>()
        var indexedReplicaKeys = emptySet<String>()

        files.firstOrNull { it.name == INDEX_FILE }?.let { indexEntry ->
            downloadText(indexEntry.id)?.let { text ->
                when (val result = indexCodec.validate(text)) {
                    is AppSyncIndexValidation.Valid ->
                        indexedReplicaKeys = result.envelope.payload.journals
                            .map { it.replicaKey }
                            .toSet()
                    is AppSyncIndexValidation.Invalid -> Unit
                }
            }
        }

        for (file in files.filter { isJournalFile(it.name) }) {
            downloadText(file.id)?.let { text ->
                when (val result = journalCodec.validate(text)) {
                    is AppSyncJournalValidation.Valid ->
                        journals += LoadedAppSyncJournal(
                            remoteId = file.id,
                            fingerprint = result.envelope.fingerprint,
                            payload = result.envelope.payload,
                        )
                    is AppSyncJournalValidation.Invalid -> Unit
                }
            }
        }

        for (file in files.filter { isCheckpointFile(it.name) }) {
            downloadText(file.id)?.let { text ->
                when (val result = checkpointCodec.validate(text)) {
                    is AppSyncCheckpointValidation.Valid ->
                        checkpoints += LoadedAppSyncCheckpoint(
                            remoteId = file.id,
                            envelope = result.envelope,
                        )
                    is AppSyncCheckpointValidation.Invalid -> Unit
                }
            }
        }

        return AppSyncJournalLoadResult.Success(
            journals = journals,
            checkpoints = checkpoints,
            indexedReplicaKeys = indexedReplicaKeys,
        )
    }

    @Suppress("UNUSED_PARAMETER")
    override suspend fun publishOwnJournal(
        payload: AppSyncJournalPayload,
        expectedFingerprint: String?,
        formHash: FormHash,
    ): AppSyncJournalPublishResult {
        val parentId = folderId()
            ?: return AppSyncJournalPublishResult.TerminalFailure("網盤資料夾尚未綁定")
        val fileName = journalFileName(payload)

        val existing = try {
            apiClient.listFiles(parentId = parentId).firstOrNull { it.name == fileName }
        } catch (error: Throwable) {
            return AppSyncJournalPublishResult.Unknown(
                "網盤 list 失敗：${error.message ?: error::class.simpleName}",
            )
        }
        if (existing != null) {
            val existingText = downloadText(existing.id)
            if (existingText == null) {
                return AppSyncJournalPublishResult.Unknown("網盤下載既有 journal 失敗，未覆蓋")
            }
            when (val validation = journalCodec.validate(existingText)) {
                is AppSyncJournalValidation.Valid -> {
                    if (validation.envelope.payload.writerNonce != payload.writerNonce) {
                        return AppSyncJournalPublishResult.Conflict(
                            "Journal writer nonce belongs to another installation",
                        )
                    }
                    if (expectedFingerprint != null &&
                        validation.envelope.fingerprint != expectedFingerprint
                    ) {
                        return AppSyncJournalPublishResult.Conflict(
                            "Journal changed after the caller's verified load",
                        )
                    }
                }
                is AppSyncJournalValidation.Invalid ->
                    return AppSyncJournalPublishResult.TerminalFailure(
                        "既有 journal 檔案損壞，拒絕覆蓋：${validation.reason}",
                    )
            }
        }

        val encoded = journalCodec.encode(payload)
        val envelope = (journalCodec.validate(encoded) as AppSyncJournalValidation.Valid).envelope
        return try {
            if (existing != null) apiClient.deleteFile(existing.id)
            val uploaded = apiClient.uploadFile(
                bytes = encoded.encodeToByteArray(),
                name = fileName,
                mimeType = MIME_TYPE,
                parentId = parentId,
            )
            AppSyncJournalPublishResult.Verified(
                LoadedAppSyncJournal(
                    remoteId = uploaded.fileId,
                    fingerprint = envelope.fingerprint,
                    payload = payload,
                ),
            )
        } catch (error: Throwable) {
            AppSyncJournalPublishResult.Unknown(
                "網盤上傳 journal 失敗：${error.message ?: error::class.simpleName}",
            )
        }
    }

    @Suppress("UNUSED_PARAMETER")
    override suspend fun publishCheckpoint(
        payload: AppSyncCheckpointPayload,
        formHash: FormHash,
    ): AppSyncCheckpointPublishResult {
        val parentId = folderId()
            ?: return AppSyncCheckpointPublishResult.TerminalFailure("網盤資料夾尚未綁定")
        val fileName = checkpointFileName(payload.checkpointId)
        val encoded = checkpointCodec.encode(payload)
        val envelope = (checkpointCodec.validate(encoded) as AppSyncCheckpointValidation.Valid).envelope
        return try {
            val uploaded = apiClient.uploadFile(
                bytes = encoded.encodeToByteArray(),
                name = fileName,
                mimeType = MIME_TYPE,
                parentId = parentId,
            )
            AppSyncCheckpointPublishResult.Verified(
                LoadedAppSyncCheckpoint(uploaded.fileId, envelope),
            )
        } catch (error: Throwable) {
            AppSyncCheckpointPublishResult.Unknown(
                "網盤上傳 checkpoint 失敗：${error.message ?: error::class.simpleName}",
            )
        }
    }

    @Suppress("UNUSED_PARAMETER")
    override suspend fun enforceCheckpointRetention(
        accountBinding: SyncAccountBinding,
        formHash: FormHash,
        maximumCheckpoints: Int,
        pinnedCheckpointIds: Set<String>,
    ): AppSyncCheckpointRetentionResult {
        if (maximumCheckpoints <= 0) {
            return AppSyncCheckpointRetentionResult.TerminalFailure(
                "Checkpoint retention limit must be positive",
            )
        }
        val parentId = folderId()
            ?: return AppSyncCheckpointRetentionResult.NotNeeded
        val files = try {
            apiClient.listFiles(parentId = parentId).filter { isCheckpointFile(it.name) }
        } catch (error: Throwable) {
            return AppSyncCheckpointRetentionResult.RetryableFailure(
                "網盤 list 失敗：${error.message ?: error::class.simpleName}",
            )
        }
        if (files.size <= maximumCheckpoints && pinnedCheckpointIds.isEmpty()) {
            return AppSyncCheckpointRetentionResult.NotNeeded
        }
        val sorted = files.sortedByDescending { it.updatedAt ?: 0L }
        val pinned = sorted.filter { checkpointIdOf(it.name) in pinnedCheckpointIds }
        val retained = (
            pinned + sorted
                .filterNot { checkpointIdOf(it.name) in pinnedCheckpointIds }
                .take(maximumCheckpoints)
            ).distinctBy { it.id }
        val retainedIds = retained.mapTo(hashSetOf()) { it.id }
        val toDelete = files.filterNot { it.id in retainedIds }

        var deleted = 0
        for (file in toDelete) {
            runCatching { apiClient.deleteFile(file.id) }
                .onSuccess { deleted += 1 }
                .onFailure { return AppSyncCheckpointRetentionResult.RetryableFailure(it.message ?: "delete failed") }
        }

        val retainedCheckpointIds = retained.mapTo(linkedSetOf()) { checkpointIdOf(it.name) }
        return if (retained.size > maximumCheckpoints) {
            AppSyncCheckpointRetentionResult.StoragePressure(
                reason = "退休復原基準已固定；暫時保留 ${retained.size} 個 checkpoint",
                retainedCheckpointIds = retainedCheckpointIds,
                deletedBlogCount = deleted,
            )
        } else {
            AppSyncCheckpointRetentionResult.Verified(
                retainedCheckpointIds = retainedCheckpointIds,
                deletedBlogCount = deleted,
            )
        }
    }

    override suspend fun publishRetirementIndex(
        intent: AppSyncJournalRetirementIntent,
        formHash: FormHash,
    ): AppSyncJournalRetirementRemoteResult {
        val current = readIndex()
            ?: return AppSyncJournalRetirementRemoteResult.RetryableFailure(
                "尚未取得可驗證的同步 index",
            )
        val retirement = AppSyncIndexRetirementReference(
            replicaKey = intent.replicaKey,
            blogId = intent.sourceBlogId.toInt(),
            fingerprint = intent.fingerprint,
            publishedThroughSequence = intent.publishedThroughSequence,
            checkpointId = intent.checkpointId,
        )
        val payload = current.copy(
            journals = current.journals.filterNot { it.replicaKey == intent.replicaKey },
            retirements = current.retirements
                .filterNot { it.replicaKey == intent.replicaKey } + retirement,
            updatedAtEpochMillis = nowMillis(),
        )
        val write = writeIndex(payload)
        if (write.isFailure) {
            return AppSyncJournalRetirementRemoteResult.RetryableFailure(
                write.exceptionOrNull()?.message ?: "index write failed",
            )
        }
        val reloaded = readIndex()
        if (reloaded != null &&
            reloaded.retirements.any { it == retirement } &&
            reloaded.journals.none { it.replicaKey == intent.replicaKey }
        ) {
            return AppSyncJournalRetirementRemoteResult.Verified
        }
        return AppSyncJournalRetirementRemoteResult.RetryableFailure(
            "index retirement metadata reload verification failed",
        )
    }

    override suspend fun deleteRetiredJournal(
        intent: AppSyncJournalRetirementIntent,
        formHash: FormHash,
    ): AppSyncJournalRetirementRemoteResult {
        val parentId = folderId()
            ?: return AppSyncJournalRetirementRemoteResult.RetryableFailure("網盤資料夾尚未綁定")
        val fileName = JOURNAL_PREFIX + sanitize(intent.replicaKey) + FILE_SUFFIX
        val files = try {
            apiClient.listFiles(parentId = parentId)
        } catch (error: Throwable) {
            return AppSyncJournalRetirementRemoteResult.RetryableFailure(
                error.message ?: "list failed",
            )
        }
        val entry = files.firstOrNull { it.name == fileName }
            ?: return AppSyncJournalRetirementRemoteResult.Verified

        downloadText(entry.id)?.let { text ->
            when (val result = journalCodec.validate(text)) {
                is AppSyncJournalValidation.Valid -> {
                    if (result.envelope.fingerprint != intent.fingerprint ||
                        result.envelope.payload.resolvedPublishedThroughSequence() !=
                        intent.publishedThroughSequence
                    ) {
                        return AppSyncJournalRetirementRemoteResult.TerminalFailure(
                            "Journal changed after retirement proof",
                        )
                    }
                }
                is AppSyncJournalValidation.Invalid ->
                    return AppSyncJournalRetirementRemoteResult.TerminalFailure(
                        "Journal 損壞，無法驗證退休",
                    )
            }
        }
        return try {
            apiClient.deleteFile(entry.id)
            AppSyncJournalRetirementRemoteResult.Verified
        } catch (error: Throwable) {
            AppSyncJournalRetirementRemoteResult.RetryableFailure(error.message ?: "delete failed")
        }
    }

    private suspend fun folderId(): String? =
        accountRepository.ensureFolderBound().getOrNull()

    private suspend fun downloadText(fileId: String): String? =
        runCatching { apiClient.downloadFile(fileId).decodeToString() }.getOrNull()

    private suspend fun readIndex(): AppSyncIndexPayload? {
        val parentId = folderId() ?: return null
        val files = try {
            apiClient.listFiles(parentId = parentId)
        } catch (_: Throwable) {
            return null
        }
        val entry = files.firstOrNull { it.name == INDEX_FILE } ?: return null
        val text = downloadText(entry.id) ?: return null
        return when (val result = indexCodec.validate(text)) {
            is AppSyncIndexValidation.Valid -> result.envelope.payload
            is AppSyncIndexValidation.Invalid -> null
        }
    }

    private suspend fun writeIndex(payload: AppSyncIndexPayload): Result<Unit> {
        val parentId = folderId()
            ?: return Result.failure(IllegalStateException("網盤資料夾尚未綁定"))
        return runCatching {
            val encoded = indexCodec.encode(payload)
            val files = apiClient.listFiles(parentId = parentId)
            files.firstOrNull { it.name == INDEX_FILE }?.let { apiClient.deleteFile(it.id) }
            apiClient.uploadFile(encoded.encodeToByteArray(), INDEX_FILE, MIME_TYPE, parentId)
        }
    }

    private fun journalFileName(payload: AppSyncJournalPayload): String =
        JOURNAL_PREFIX + sanitize(
            SyncReplicaKey(payload.deviceId, payload.deviceEpoch).stableKey,
        ) + FILE_SUFFIX

    private fun checkpointFileName(checkpointId: String): String =
        CHECKPOINT_PREFIX + sanitize(checkpointId) + FILE_SUFFIX

    private fun isJournalFile(name: String): Boolean =
        name.startsWith(JOURNAL_PREFIX) && name.endsWith(FILE_SUFFIX)

    private fun isCheckpointFile(name: String): Boolean =
        name.startsWith(CHECKPOINT_PREFIX) && name.endsWith(FILE_SUFFIX)

    private fun checkpointIdOf(fileName: String): String =
        fileName.removePrefix(CHECKPOINT_PREFIX).removeSuffix(FILE_SUFFIX)

    private fun sanitize(value: String): String =
        value.replace(Regex("[^a-zA-Z0-9._-]"), "_")

    private companion object {
        const val INDEX_FILE = "index.json"
        const val JOURNAL_PREFIX = "journal-"
        const val CHECKPOINT_PREFIX = "checkpoint-"
        const val FILE_SUFFIX = ".json"
        const val MIME_TYPE = "application/octet-stream"
    }
}
