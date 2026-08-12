package me.thenano.yamibo.yamibo_app.profile.sign

import io.github.littlesurvival.core.YamiboResult
import me.thenano.yamibo.yamibo_app.event.AppEvent
import me.thenano.yamibo.yamibo_app.event.events.SignStatusChangedEvent
import me.thenano.yamibo.yamibo_app.repository.SignRepository

internal fun shouldEmitSignStatusChanged(
    result: YamiboResult<SignRepository.ActionResult>,
): Boolean = result is YamiboResult.Success && result.value.pageInfo.hasSignedToday

internal fun shouldDismissSignReminderFor(event: AppEvent): Boolean =
    event === SignStatusChangedEvent
