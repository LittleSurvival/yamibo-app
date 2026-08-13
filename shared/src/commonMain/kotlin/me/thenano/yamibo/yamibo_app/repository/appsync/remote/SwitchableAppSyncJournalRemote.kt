package me.thenano.yamibo.yamibo_app.repository.appsync.remote

import io.github.littlesurvival.dto.value.FormHash
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncCheckpointPublishResult
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncCheckpointRetentionResult
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncJournalLoadResult
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncJournalPublishResult
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncJournalRemote
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.AppSyncJournalRetirementRemoteResult
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncJournalRetirementIntent
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncAccountBinding
import me.thenano.yamibo.yamibo_app.repository.settings.AppSyncBackend

/**
 * 依 [AppSyncBackend] 設定在論壇 Blog 與網盤兩個 remote 之間切換（二選一）。
 *
 * 切換後端需由呼叫端重置 installation 狀態為 Unbound，重新走 Seed/Join；
 * 兩個後端是獨立雲端儲存，資料不互通。
 */
internal class SwitchableAppSyncJournalRemote(
    private val forumRemote: AppSyncJournalRemote,
    private val panCloudRemoteProvider: () -> AppSyncJournalRemote?,
    private val backendProvider: () -> AppSyncBackend,
) : AppSyncJournalRemote {

    private val delegate: AppSyncJournalRemote
        get() {
            val panCloud = panCloudRemoteProvider()
            return if (backendProvider() == AppSyncBackend.PAN_CLOUD && panCloud != null) {
                panCloud
            } else {
                forumRemote
            }
        }

    override suspend fun loadJournals(
        accountBinding: SyncAccountBinding,
        forceDiscovery: Boolean,
    ): AppSyncJournalLoadResult = delegate.loadJournals(accountBinding, forceDiscovery)

    override suspend fun publishOwnJournal(
        payload: AppSyncJournalPayload,
        expectedFingerprint: String?,
        formHash: FormHash,
    ): AppSyncJournalPublishResult = delegate.publishOwnJournal(payload, expectedFingerprint, formHash)

    override suspend fun publishCheckpoint(
        payload: AppSyncCheckpointPayload,
        formHash: FormHash,
    ): AppSyncCheckpointPublishResult = delegate.publishCheckpoint(payload, formHash)

    override suspend fun enforceCheckpointRetention(
        accountBinding: SyncAccountBinding,
        formHash: FormHash,
        maximumCheckpoints: Int,
        pinnedCheckpointIds: Set<String>,
    ): AppSyncCheckpointRetentionResult =
        delegate.enforceCheckpointRetention(accountBinding, formHash, maximumCheckpoints, pinnedCheckpointIds)

    override suspend fun publishRetirementIndex(
        intent: AppSyncJournalRetirementIntent,
        formHash: FormHash,
    ): AppSyncJournalRetirementRemoteResult = delegate.publishRetirementIndex(intent, formHash)

    override suspend fun deleteRetiredJournal(
        intent: AppSyncJournalRetirementIntent,
        formHash: FormHash,
    ): AppSyncJournalRetirementRemoteResult = delegate.deleteRetiredJournal(intent, formHash)

    override suspend fun deleteAllVerifiedSyncData(
        accountBinding: SyncAccountBinding,
        formHash: FormHash,
    ): AppSyncCloudResetResult = delegate.deleteAllVerifiedSyncData(accountBinding, formHash)

    override fun clearLinkCache(accountBinding: SyncAccountBinding): Int =
        delegate.clearLinkCache(accountBinding)
}
