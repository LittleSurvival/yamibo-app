package me.thenano.yamibo.yamibo_app.profile.sign

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.littlesurvival.core.YamiboResult
import me.thenano.yamibo.yamibo_app.event.AppEvent
import me.thenano.yamibo.yamibo_app.event.events.SignStatusChangedEvent
import me.thenano.yamibo.yamibo_app.repository.SignRepository

internal fun shouldEmitSignStatusChanged(
    result: YamiboResult<SignRepository.ActionResult>,
): Boolean = result is YamiboResult.Success && result.value.pageInfo.hasSignedToday

internal fun shouldDismissSignReminderFor(event: AppEvent): Boolean =
    event === SignStatusChangedEvent

/** Suppression survives consuming a notification and any slower launch-status result. */
internal class LaunchSignReminderState(suppressed: Boolean = false) {
    var suppressed by mutableStateOf(suppressed)
        private set
    var visible by mutableStateOf(false)
        private set

    fun onStatusChecked(signedToday: Boolean) {
        if (!suppressed) visible = !signedToday
    }

    fun dismiss() { visible = false }

    fun suppress() {
        suppressed = true
        visible = false
    }
}
