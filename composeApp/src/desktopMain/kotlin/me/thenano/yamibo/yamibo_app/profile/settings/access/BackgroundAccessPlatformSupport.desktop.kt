package me.thenano.yamibo.yamibo_app.profile.settings.access

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import me.thenano.yamibo.yamibo_app.profile.settings.access.BackgroundAccessRepository
import me.thenano.yamibo.yamibo_app.profile.settings.access.BackgroundAccessRepository.*

internal class DesktopBackgroundAccess(private val trayAvailable: () -> Boolean) : BackgroundAccessRepository {
    private fun snapshot() = SetupState(
        summary = I18nText("桌面背景工作"),
        items = listOf(SetupItem(
            title = I18nText("系統匣"),
            subtitle = I18nText(if (trayAvailable()) "關閉視窗後繼續工作；從系統匣重新開啟或退出" else "系統匣不可用；請保留視窗或最小化以繼續背景工作"),
            status = SetupStatus.Info,
        )),
        platformNote = I18nText("程式退出或電腦休眠時不執行工作；系統可能封鎖通知，請檢查系統通知設定。"),
    )
    override val state = MutableStateFlow(snapshot())
    override suspend fun refresh() { state.value = snapshot() }
    override fun runAction(action: SetupAction) {
        // This adapter exposes no Android permission actions.
    }
}

// Desktop permissions are controlled by the OS, not Android's runtime permission launcher.
@Composable
actual fun rememberBackgroundAccessNotificationPermissionRequester(onPermissionHandled: () -> Unit): (() -> Unit)? = null

@Composable
actual fun BackgroundAccessResumeRefreshEffect(onResume: () -> Unit) {
    val callback = rememberUpdatedState(onResume)
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) callback.value()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}
