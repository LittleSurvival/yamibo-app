package me.thenano.yamibo.yamibo_app.appsync

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import me.thenano.yamibo.yamibo_app.repository.appsync.AppSyncRecoveryWorkLedger
import me.thenano.yamibo.yamibo_app.store.appsync.AppSyncRecoveryWorkRequest
import org.junit.Assert.*
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/** Run stages separately with a host-triggered process stop/reboot between stage 1 and 2.
 * A normal suite run checks persistence across fixture reconstruction only.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class AndroidRecoveryRestartTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager = WorkManager.getInstance(context)
    private val scheduler = AndroidAppSyncBackgroundScheduler(context)
    private val ledger = PersistentFixtureLedger(context)

    @Test
    fun stage1Enqueue() = runBlocking {
        manager.cancelUniqueWork(MANUAL).result.get(20, TimeUnit.SECONDS)
        manager.pruneWork().result.get(20, TimeUnit.SECONDS)
        ledger.clear()
        scheduler.continueRecovery(ledger)
        assertNotNull(ledger.recoveryWorkRequest()?.enqueuedAtEpochMillis)
    }

    @Test
    fun stage2VerifyAfterRestart() = runBlocking {
        val before = requireNotNull(ledger.recoveryWorkRequest()) { "Run stage1Enqueue first" }
        val info = requireNotNull(manager.getWorkInfoById(UUID.fromString(before.requestId)).get())
        assertEquals(WorkInfo.State.ENQUEUED, info.state)
        assertTrue(info.nextScheduleTimeMillis >= before.notBeforeEpochMillis - 1_000)
        scheduler.reconcileRecoveryWork(ledger)
        scheduler.continueRecovery(ledger)
        assertEquals(before, ledger.recoveryWorkRequest())
        assertEquals(
            listOf(UUID.fromString(before.requestId)),
            manager.getWorkInfosForUniqueWork(MANUAL).get().filterNot { it.state.isFinished }.map { it.id },
        )
    }

    @Test
    fun stage3Cleanup() {
        manager.cancelUniqueWork(MANUAL).result.get(20, TimeUnit.SECONDS)
        manager.pruneWork().result.get(20, TimeUnit.SECONDS)
        ledger.clear()
    }

    /** Instrumentation-only surrogate: production SQL lifecycle tests live in shared. */
    private class PersistentFixtureLedger(context: Context) : AppSyncRecoveryWorkLedger {
        private val prefs = context.getSharedPreferences("appsync-restart-test", Context.MODE_PRIVATE)

        @Synchronized override fun recoveryWorkRequest(): AppSyncRecoveryWorkRequest? {
            val id = prefs.getString("id", null) ?: return null
            return AppSyncRecoveryWorkRequest(
                "synthetic-restart", id, prefs.getLong("deadline", 0),
                if (prefs.contains("enqueued")) prefs.getLong("enqueued", 0) else null, null,
            )
        }

        @Synchronized override fun prepareRecoveryWork(proposedId: String): AppSyncRecoveryWorkRequest {
            recoveryWorkRequest()?.let { return it }
            check(prefs.edit().putString("id", proposedId)
                .putLong("deadline", System.currentTimeMillis() + TimeUnit.DAYS.toMillis(1)).commit())
            return requireNotNull(recoveryWorkRequest())
        }

        @Synchronized override fun confirmRecoveryWorkEnqueued(requestId: String): Boolean {
            val current = recoveryWorkRequest()?.takeIf { it.requestId == requestId } ?: return false
            if (current.enqueuedAtEpochMillis == null) {
                check(prefs.edit().putLong("enqueued", System.currentTimeMillis()).commit())
            }
            return true
        }

        override fun beginRecoveryWork(requestId: String): Boolean = error("Delayed fixture must never execute")

        @Synchronized override fun retireRecoveryWork(requestId: String) {
            if (recoveryWorkRequest()?.requestId == requestId) clear()
        }

        @Synchronized fun clear() { check(prefs.edit().clear().commit()) }
    }

    companion object {
        private const val MANUAL = "yamibo-app-sync-manual-recovery"
    }
}
