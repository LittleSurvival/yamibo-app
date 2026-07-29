package me.thenano.yamibo.yamibo_app.profile.settings.cloud

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import me.thenano.yamibo.yamibo_app.repository.appsync.AppSyncServicePhase
import me.thenano.yamibo.yamibo_app.repository.appsync.AppSyncServiceStatus
import me.thenano.yamibo.yamibo_app.repository.appsync.AppSyncChangeAction
import me.thenano.yamibo.yamibo_app.repository.appsync.AppSyncChangeDirection
import me.thenano.yamibo.yamibo_app.repository.appsync.AppSyncChangeSummary

class CloudSyncUiStateTest {
    @Test
    fun expiredAuthenticationShowsDurableInlineAttentionState() {
        val state = status(
            phase = AppSyncServicePhase.PausedAuth,
            message = "Cached FormHash expired",
        ).toUiState(backgroundSchedulerAvailable = true)

        assertEquals(CloudSyncStatus.Unavailable, state.status)
        assertEquals("登入狀態需要刷新", state.statusHeadline)
        val notice = assertNotNull(state.notice)
        assertEquals("Cached FormHash expired", notice.message)
        assertEquals(CloudSyncNoticeSeverity.Warning, notice.severity)
        assertTrue(state.details.any { it.label == "最近結果" && it.value.contains("FormHash") })
    }

    @Test
    fun schedulerAvailabilityAndAutomaticStateAreReportedSeparately() {
        val active = status(
            phase = AppSyncServicePhase.Active,
            automaticEnabled = true,
        )

        val available = active.toUiState(backgroundSchedulerAvailable = true)
        val unavailable = active.toUiState(backgroundSchedulerAvailable = false)

        assertTrue(available.automaticAvailable)
        assertEquals("已啟用", available.automaticStatus)
        assertFalse(unavailable.automaticAvailable)
        assertEquals("此平台尚未提供背景同步", unavailable.automaticStatus)
    }

    @Test
    fun retryAndQuarantineRemainVisibleAfterRunCompletes() {
        listOf(
            AppSyncServicePhase.RetryPending to "等待重試",
            AppSyncServicePhase.Quarantined to "有資料需要檢查",
        ).forEach { (phase, headline) ->
            val state = status(phase = phase, message = "typed failure")
                .toUiState(backgroundSchedulerAvailable = true)

            assertEquals(CloudSyncOperation.Idle, state.operation)
            assertEquals(headline, state.statusHeadline)
            assertEquals("typed failure", state.statusSupport)
            assertNotNull(state.notice)
        }
    }

    @Test
    fun verifiedTimestampIsFormattedInsteadOfShowingRawEpochMillis() {
        val state = status(phase = AppSyncServicePhase.Active)
            .toUiState(backgroundSchedulerAvailable = true)

        assertEquals(
            "1970/01/01 08:00",
            state.details.single { it.label == "最後驗證" }.value,
        )
    }

    @Test
    fun latestChangesExposeModuleDirectionAndActionWithoutEntityContent() {
        val state = status(
            phase = AppSyncServicePhase.Active,
            changes = listOf(
                AppSyncChangeSummary(
                    direction = AppSyncChangeDirection.Received,
                    domainId = "settings",
                    action = AppSyncChangeAction.Enabled,
                    count = 2,
                ),
                AppSyncChangeSummary(
                    direction = AppSyncChangeDirection.Uploaded,
                    domainId = "favorite.item",
                    action = AppSyncChangeAction.Deleted,
                    count = 1,
                ),
            ),
        ).toUiState(backgroundSchedulerAvailable = true)

        assertEquals(
            listOf(
                CloudSyncChangeDetail("從雲端套用", "設定", "開啟 2"),
                CloudSyncChangeDetail("上傳至雲端", "收藏項目", "刪除 1"),
            ),
            state.changes,
        )
    }

    @Test
    fun forceConfirmationRemainsDisabledUntilCountdownFinishes() {
        assertFalse(forceConfirmationEnabled(10))
        assertFalse(forceConfirmationEnabled(1))
        assertTrue(forceConfirmationEnabled(0))
    }

    private fun status(
        phase: AppSyncServicePhase,
        message: String = "ready",
        automaticEnabled: Boolean = false,
        changes: List<AppSyncChangeSummary> = emptyList(),
    ) = AppSyncServiceStatus(
        phase = phase,
        message = message,
        automaticEnabled = automaticEnabled,
        pendingOperationCount = 3,
        lastVerifiedAtEpochMillis = 123,
        changeSummaries = changes,
    )
}
