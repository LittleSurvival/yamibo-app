package me.thenano.yamibo.yamibo_app.feedback

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow

class AppFeedbackController {
    private val durableDeliveryChannel = Channel<AppFeedbackDelivery>(Channel.UNLIMITED)
    private val informationalDeliveryChannel = Channel<AppFeedbackDelivery>(
        capacity = MAX_PENDING_INFORMATIONAL_DELIVERIES,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val latestGroupState = MutableStateFlow<Map<String, AppFeedbackId>>(emptyMap())
    private var nextId = 0L

    val deliveries: Flow<AppFeedbackDelivery> = merge(
        durableDeliveryChannel.receiveAsFlow(),
        informationalDeliveryChannel.receiveAsFlow(),
    )
    val latestGroups: StateFlow<Map<String, AppFeedbackId>> = latestGroupState.asStateFlow()

    fun post(
        event: AppFeedbackEvent,
        onResult: ((AppFeedbackResult) -> Unit)? = null,
    ): AppFeedbackId {
        val id = AppFeedbackId(++nextId)
        event.groupKey?.let { groupKey ->
            latestGroupState.value += (groupKey to id)
        }
        val channel = if (event.requiresDurableDelivery) {
            durableDeliveryChannel
        } else {
            informationalDeliveryChannel
        }
        check(channel.trySend(AppFeedbackDelivery(id, event, onResult)).isSuccess) {
            "App feedback controller is closed"
        }
        return id
    }

    fun post(
        message: String,
        duration: AppFeedbackDuration = AppFeedbackDuration.Short,
        kind: AppFeedbackKind = AppFeedbackKind.Info,
        actionLabel: String? = null,
        withDismissAction: Boolean = false,
        groupKey: String? = null,
    ): AppFeedbackId = post(
        AppFeedbackEvent(
            message = message,
            duration = duration,
            kind = kind,
            actionLabel = actionLabel,
            withDismissAction = withDismissAction,
            groupKey = groupKey,
        )
    )

    fun resolve(delivery: AppFeedbackDelivery, result: AppFeedbackResult): Boolean {
        val resolved = delivery.resolve(result)
        val groupKey = delivery.event.groupKey
        if (resolved && groupKey != null && latestGroupState.value[groupKey] == delivery.id) {
            latestGroupState.value -= groupKey
        }
        return resolved
    }

    fun isCurrent(delivery: AppFeedbackDelivery): Boolean = delivery.event.groupKey?.let { groupKey ->
        latestGroupState.value[groupKey] == delivery.id
    } ?: true

    fun close() {
        durableDeliveryChannel.close()
        informationalDeliveryChannel.close()
        latestGroupState.value = emptyMap()
    }

    private val AppFeedbackEvent.requiresDurableDelivery: Boolean
        get() = kind != AppFeedbackKind.Info || actionLabel != null || withDismissAction

    private companion object {
        const val MAX_PENDING_INFORMATIONAL_DELIVERIES = 64
    }
}
