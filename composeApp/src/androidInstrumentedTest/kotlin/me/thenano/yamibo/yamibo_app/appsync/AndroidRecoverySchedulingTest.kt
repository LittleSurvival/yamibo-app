package me.thenano.yamibo.yamibo_app.appsync

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import me.thenano.yamibo.yamibo_app.repository.appsync.AppSyncRecoveryWorkLedger
import me.thenano.yamibo.yamibo_app.store.appsync.AppSyncRecoveryWorkRequest
import me.thenano.yamibo.yamibo_app.util.time.FixedScheduleInterval
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Uses the real WorkManager database, constraints and unique-chain implementation.
 * Synthetic ledger deadlines prevent execution or access to any provider/account.
 */
@RunWith(AndroidJUnit4::class)
class AndroidRecoverySchedulingTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager = WorkManager.getInstance(context)
    private val scheduler = AndroidAppSyncBackgroundScheduler(context)
    private val ledger = SyntheticLedger()

    @Before
    fun setUp() = clearWork()

    @After
    fun tearDown() = clearWork()

    private fun clearWork() {
        manager.cancelUniqueWork(MANUAL).result.get(20, TimeUnit.SECONDS)
        manager.cancelUniqueWork(PERIODIC).result.get(20, TimeUnit.SECONDS)
        manager.cancelUniqueWork(LIFECYCLE).result.get(20, TimeUnit.SECONDS)
        manager.pruneWork().result.get(20, TimeUnit.SECONDS)
    }

    @Test
    fun concurrentCallbacksAttachToOnePersistedRequest() = runBlocking {
        coroutineScope {
            List(8) { async { AndroidAppSyncBackgroundScheduler(context).continueRecovery(ledger) } }.awaitAll()
        }
        val request = requireNotNull(ledger.recoveryWorkRequest())
        assertNotNull(request.enqueuedAtEpochMillis)
        val active = manager.getWorkInfosForUniqueWork(MANUAL).get().filterNot { it.state.isFinished }
        assertEquals(listOf(UUID.fromString(request.requestId)), active.map { it.id })
        assertEquals(WorkInfo.State.ENQUEUED, active.single().state)
        assertTrue(active.single().nextScheduleTimeMillis >= request.notBeforeEpochMillis - 1_000)

        // Recreating the scheduler reconciles the same durable WorkManager UUID.
        AndroidAppSyncBackgroundScheduler(context).continueRecovery(ledger)
        coroutineScope { List(8) { async { scheduler.runManual() } }.awaitAll() }
        assertEquals(request, ledger.recoveryWorkRequest())
        assertEquals(1, manager.getWorkInfosForUniqueWork(MANUAL).get().count { !it.state.isFinished })
    }

    @Test
    fun disablingAutomaticSyncPreservesManualRecovery() = runBlocking {
        scheduler.continueRecovery(ledger)
        val id = UUID.fromString(requireNotNull(ledger.recoveryWorkRequest()).requestId)
        scheduler.setEnabled(true, FixedScheduleInterval.Hours24)
        scheduler.setEnabled(false, FixedScheduleInterval.Hours24)
        // Drain the same serial WorkManager operation queue before inspecting.
        manager.cancelUniqueWork(LIFECYCLE).result.get(20, TimeUnit.SECONDS)
        assertEquals(WorkInfo.State.ENQUEUED, manager.getWorkInfoById(id).get()?.state)
        assertTrue(manager.getWorkInfosForUniqueWork(PERIODIC).get().all { it.state.isFinished })
        scheduler.reconcileRecoveryWork(ledger)
        assertNotNull(ledger.recoveryWorkRequest()?.enqueuedAtEpochMillis)
    }

    @Test
    fun cancellationRemovesWaitingEvidenceAndResumeUsesNewIdentity() = runBlocking {
        scheduler.continueRecovery(ledger)
        val old = requireNotNull(ledger.recoveryWorkRequest())
        manager.cancelWorkById(UUID.fromString(old.requestId)).result.get(20, TimeUnit.SECONDS)
        scheduler.reconcileRecoveryWork(ledger)
        assertNull(ledger.recoveryWorkRequest())
        scheduler.continueRecovery(ledger)
        val resumed = requireNotNull(ledger.recoveryWorkRequest())
        assertNotEquals(old.requestId, resumed.requestId)
        assertNotNull(resumed.enqueuedAtEpochMillis)
        assertEquals(WorkInfo.State.ENQUEUED, manager.getWorkInfoById(UUID.fromString(resumed.requestId)).get()?.state)
    }

    @Test
    fun missingEnqueueIsNotReportedAsWaitingAndCanBeRecovered() = runBlocking {
        val prepared = requireNotNull(ledger.prepareRecoveryWork(UUID.randomUUID().toString()))
        scheduler.reconcileRecoveryWork(ledger)
        assertEquals(prepared, ledger.recoveryWorkRequest())
        assertNull(prepared.enqueuedAtEpochMillis)
        scheduler.continueRecovery(ledger)
        assertEquals(prepared.requestId, ledger.recoveryWorkRequest()?.requestId)
        assertNotNull(ledger.recoveryWorkRequest()?.enqueuedAtEpochMillis)

        manager.cancelWorkById(UUID.fromString(prepared.requestId)).result.get(20, TimeUnit.SECONDS)
        manager.pruneWork().result.get(20, TimeUnit.SECONDS)
        assertNull(manager.getWorkInfoById(UUID.fromString(prepared.requestId)).get())
        scheduler.reconcileRecoveryWork(ledger)
        assertNull(ledger.recoveryWorkRequest())
    }

    private class SyntheticLedger : AppSyncRecoveryWorkLedger {
        private var request: AppSyncRecoveryWorkRequest? = null

        @Synchronized override fun recoveryWorkRequest() = request

        @Synchronized override fun prepareRecoveryWork(proposedId: String): AppSyncRecoveryWorkRequest =
            request ?: AppSyncRecoveryWorkRequest(
                "synthetic-recovery", proposedId, System.currentTimeMillis() + TimeUnit.DAYS.toMillis(1), null, null,
            ).also { request = it }

        @Synchronized override fun confirmRecoveryWorkEnqueued(requestId: String): Boolean {
            val current = request?.takeIf { it.requestId == requestId } ?: return false
            request = current.copy(enqueuedAtEpochMillis = current.enqueuedAtEpochMillis ?: System.currentTimeMillis())
            return true
        }

        override fun beginRecoveryWork(requestId: String): Boolean = error("Delayed fixture must never execute")

        @Synchronized override fun retireRecoveryWork(requestId: String) {
            if (request?.requestId == requestId) request = null
        }
    }

    companion object {
        private const val MANUAL = "yamibo-app-sync-manual-recovery"
        private const val PERIODIC = "yamibo-app-sync-periodic"
        private const val LIFECYCLE = "yamibo-app-sync-lifecycle"
    }
}
