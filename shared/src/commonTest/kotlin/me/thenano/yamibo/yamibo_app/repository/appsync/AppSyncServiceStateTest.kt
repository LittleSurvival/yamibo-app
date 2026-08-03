package me.thenano.yamibo.yamibo_app.repository.appsync

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncInstallationState

class AppSyncServiceStateTest {
    @Test
    fun retryablePauseNeverRoutesThroughBootstrap() {
        assertFalse(AppSyncInstallationState.PausedAuth.requiresBootstrapForSync())
        assertFalse(AppSyncInstallationState.PausedProvider.requiresBootstrapForSync())
    }

    @Test
    fun onlyUninitializedOrExplicitRebootstrapStatesRouteThroughBootstrap() {
        assertTrue(AppSyncInstallationState.Unbound.requiresBootstrapForSync())
        assertTrue(AppSyncInstallationState.Bootstrapping.requiresBootstrapForSync())
        assertTrue(AppSyncInstallationState.RebootstrapRequired.requiresBootstrapForSync())
        assertFalse(AppSyncInstallationState.Active.requiresBootstrapForSync())
        assertFalse(AppSyncInstallationState.Quarantined.requiresBootstrapForSync())
    }

    @Test
    fun quarantineAloneBlocksRegularForegroundAndBackgroundSyncRouting() {
        AppSyncInstallationState.entries.forEach { state ->
            assertTrue(state.blocksRegularSync() == (state == AppSyncInstallationState.Quarantined))
        }
    }
}
