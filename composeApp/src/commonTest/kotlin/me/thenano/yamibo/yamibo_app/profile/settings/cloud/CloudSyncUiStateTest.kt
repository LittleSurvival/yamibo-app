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
import me.thenano.yamibo.yamibo_app.repository.appsync.AppSyncStatusMessage
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
        assertEquals(AppSyncServicePhase.PausedAuth, state.phase)
        val notice = assertNotNull(state.notice)
        assertEquals(AppSyncStatusMessage.External("Cached FormHash expired"), notice.message)
        assertEquals(CloudSyncNoticeSeverity.Warning, notice.severity)
        assertTrue(
            state.details.any {
                it.label == CloudSyncDetailLabel.LatestResult &&
                    it.value == CloudSyncDetailValue.StatusMessage(
                        AppSyncStatusMessage.External("Cached FormHash expired"),
                    )
            },
        )
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
        assertEquals(CloudSyncAutomaticStatus.Enabled, available.automaticStatus)
        assertFalse(unavailable.automaticAvailable)
        assertEquals(CloudSyncAutomaticStatus.Unsupported, unavailable.automaticStatus)
    }

    @Test
    fun retryAndQuarantineRemainVisibleAfterRunCompletes() {
        listOf(
            AppSyncServicePhase.RetryPending,
            AppSyncServicePhase.Quarantined,
        ).forEach { phase ->
            val state = status(phase = phase, message = "typed failure")
                .toUiState(backgroundSchedulerAvailable = true)

            assertEquals(CloudSyncOperation.Idle, state.operation)
            assertEquals(phase, state.phase)
            assertEquals(AppSyncStatusMessage.External("typed failure"), state.statusMessage)
            assertNotNull(state.notice)
        }
    }

    @Test
    fun providerMaintenanceMessageRemainsUntranslatedDiagnosticText() {
        val state = status(
            phase = AppSyncServicePhase.RetryPending,
            message = "maintenance",
        ).toUiState(backgroundSchedulerAvailable = true)

        val expected = AppSyncStatusMessage.External("maintenance")
        assertEquals(expected, state.statusMessage)
        assertEquals(expected, assertNotNull(state.notice).message)
        assertEquals(
            CloudSyncDetailValue.StatusMessage(expected),
            state.details.single { it.label == CloudSyncDetailLabel.LatestResult }.value,
        )
    }

    @Test
    fun knownModuleIdsResolveWithoutStringToStringLabelMapping() {
        CloudSyncModuleKind.entries
            .filterNot { it == CloudSyncModuleKind.Unknown }
            .forEach { kind ->
                assertEquals(kind, CloudSyncModuleKind.fromDomainId(requireNotNull(kind.domainId)))
            }
        assertEquals(
            CloudSyncModuleKind.Unknown,
            CloudSyncModuleKind.fromDomainId("future.domain"),
        )
    }

    @Test
    fun newlySynchronizedHistoryModulesNeverExposeInternalDomainIds() {
        val expected = mapOf(
            "reading.tag-catalog" to CloudSyncModuleKind.ReadingTagCatalog,
            "reading.rss-search" to CloudSyncModuleKind.ReadingRssSearch,
            "reading.rss-catalog" to CloudSyncModuleKind.ReadingRssCatalog,
        )

        expected.forEach { (domainId, kind) ->
            assertEquals(kind, CloudSyncModuleKind.fromDomainId(domainId))
        }
    }

    @Test
    fun verifiedTimestampIsFormattedInsteadOfShowingRawEpochMillis() {
        val state = status(phase = AppSyncServicePhase.Active)
            .toUiState(backgroundSchedulerAvailable = true)

        assertEquals(
            "1970/01/01 08:00",
            (state.details.single { it.label == CloudSyncDetailLabel.LastVerified }.value as
                CloudSyncDetailValue.Timestamp).value,
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
                CloudSyncChangeDetail(
                    AppSyncChangeDirection.Received,
                    module(CloudSyncModuleKind.Settings, "settings"),
                    AppSyncChangeAction.Enabled,
                    2,
                ),
                CloudSyncChangeDetail(
                    AppSyncChangeDirection.Uploaded,
                    module(CloudSyncModuleKind.FavoriteItem, "favorite.item"),
                    AppSyncChangeAction.Deleted,
                    1,
                ),
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
                CloudSyncChangeDetail(
                    AppSyncChangeDirection.Received,
                    module(CloudSyncModuleKind.FavoriteUpdateEvent, "favorite.update-event"),
                    AppSyncChangeAction.Read,
                    2,
                ),
                CloudSyncChangeDetail(
                    AppSyncChangeDirection.Uploaded,
                    module(CloudSyncModuleKind.FavoriteUpdateEvent, "favorite.update-event"),
                    AppSyncChangeAction.Dismissed,
                    1,
                ),
                CloudSyncChangeDetail(
                    AppSyncChangeDirection.Uploaded,
                    module(
                        CloudSyncModuleKind.FavoriteUpdateCategoryFilter,
                        "favorite.update-category-filter",
                    ),
                    AppSyncChangeAction.Disabled,
                    1,
                ),
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
                direction = AppSyncChangeDirection.Received,
                module = module(
                    CloudSyncModuleKind.FavoriteUpdateEvent,
                    "favorite.update-event",
                ),
                action = AppSyncChangeAction.Added,
                count = 7,
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

        val detail = state.details.single { it.label == CloudSyncDetailLabel.JournalCleanup }
        val raw = (detail.value as CloudSyncDetailValue.Journal).value
        assertEquals(
            me.thenano.yamibo.yamibo_app.repository.appsync.AppSyncJournalRetirementMessage.External(
                "等待 checkpoint 完整覆蓋與所有活躍 replica 確認",
            ),
            raw,
        )
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

    private fun module(kind: CloudSyncModuleKind, domainId: String) =
        CloudSyncModule(kind, domainId)
}
