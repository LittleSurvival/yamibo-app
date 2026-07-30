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
import me.thenano.yamibo.yamibo_app.repository.appsync.AppSyncPeriodicIntervals
import me.thenano.yamibo.yamibo_app.repository.appsync.AppSyncScheduleSettings
import me.thenano.yamibo.yamibo_app.repository.appsync.AppSyncJournalRetirementState
import me.thenano.yamibo.yamibo_app.repository.appsync.AppSyncJournalRetirementStatus
import me.thenano.yamibo.yamibo_app.util.time.FixedScheduleInterval

class CloudSyncUiStateTest {
    @Test
    fun schedulingPolicyAndCanonicalOptionOrderReachTheUi() {
        val state = status(
            phase = AppSyncServicePhase.Active,
            automaticEnabled = true,
        ).copy(
            scheduleSettings = AppSyncScheduleSettings(
                syncOnAppStart = true,
                syncOnForegroundExit = true,
                periodicInterval = FixedScheduleInterval.Days2,
            ),
        ).toUiState(backgroundSchedulerAvailable = true)

        assertTrue(state.syncOnAppStart)
        assertTrue(state.syncOnForegroundExit)
        assertEquals(FixedScheduleInterval.Days2, state.periodicInterval)
        assertEquals(AppSyncPeriodicIntervals, state.periodicIntervalOptions)
    }

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
    fun providerMaintenanceCodeIsRenderedAsUserFacingText() {
        val state = status(
            phase = AppSyncServicePhase.RetryPending,
            message = "maintenance",
        ).toUiState(backgroundSchedulerAvailable = true)

        val expected = "Yamibo 正在維護，將稍後自動重試"
        assertEquals(expected, state.statusSupport)
        assertEquals(expected, assertNotNull(state.notice).message)
        assertEquals(expected, state.details.single { it.label == "最近結果" }.value)
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

    @Test
    fun favoriteUpdateChangesUseSpecificModulesAndLifecycleActions() {
        val state = status(
            phase = AppSyncServicePhase.Active,
            changes = listOf(
                AppSyncChangeSummary(
                    AppSyncChangeDirection.Received,
                    "favorite.update-event",
                    AppSyncChangeAction.Read,
                    2,
                ),
                AppSyncChangeSummary(
                    AppSyncChangeDirection.Uploaded,
                    "favorite.update-event",
                    AppSyncChangeAction.Dismissed,
                    1,
                ),
                AppSyncChangeSummary(
                    AppSyncChangeDirection.Uploaded,
                    "favorite.update-category-filter",
                    AppSyncChangeAction.Disabled,
                    1,
                ),
            ),
        ).toUiState(backgroundSchedulerAvailable = true)

        assertEquals(
            listOf(
                CloudSyncChangeDetail("從雲端套用", "最近更新", "標為已讀 2"),
                CloudSyncChangeDetail("上傳至雲端", "最近更新", "忽略 1"),
                CloudSyncChangeDetail("上傳至雲端", "分類更新範圍", "關閉 1"),
            ),
            state.changes,
        )
    }

    @Test
    fun favoriteUpdateDetailsRemainBoundedAndVisibleWithRemainingCount() {
        val state = status(
            phase = AppSyncServicePhase.Active,
            changes = listOf(
                AppSyncChangeSummary(
                    AppSyncChangeDirection.Received,
                    "favorite.update-event",
                    AppSyncChangeAction.Added,
                    7,
                    details = listOf("更新一", "更新二", "更新三", "更新四", "更新五"),
                    remainingDetailCount = 2,
                ),
            ),
        ).toUiState(backgroundSchedulerAvailable = true)

        assertEquals(
            CloudSyncChangeDetail(
                direction = "從雲端套用",
                module = "最近更新",
                summary = "新增 7",
                details = listOf("更新一", "更新二", "更新三", "更新四", "更新五"),
                remainingDetailCount = 2,
            ),
            state.changes.single(),
        )
    }

    @Test
    fun journalRetirementStatusIsShownWithoutRawIdentityData() {
        val state = status(AppSyncServicePhase.Active).copy(
            journalRetirementStatus = AppSyncJournalRetirementStatus(
                AppSyncJournalRetirementState.Blocked,
                "等待 checkpoint 完整覆蓋與所有活躍 replica 確認",
            ),
        ).toUiState(backgroundSchedulerAvailable = true)

        val detail = state.details.single { it.label == "Journal 清理" }
        assertEquals("等待 checkpoint 完整覆蓋與所有活躍 replica 確認", detail.value)
        assertFalse(detail.value.contains("blogId"))
        assertFalse(detail.value.contains("fingerprint"))
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
