package me.thenano.yamibo.yamibo_app.confirmation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

class AppConfirmationController(scope: CoroutineScope) {
    private sealed interface Command {
        data class Request(
            val event: AppConfirmationEvent,
            val result: CompletableDeferred<AppConfirmationResult>,
        ) : Command

        data class Resolve(
            val id: AppConfirmationId,
            val result: AppConfirmationResult,
            val accepted: CompletableDeferred<Boolean>,
        ) : Command

        data class Cancel(
            val result: CompletableDeferred<AppConfirmationResult>,
        ) : Command
    }

    private val commandChannel = Channel<Command>(Channel.UNLIMITED)
    private val deliveryChannel = Channel<AppConfirmationDelivery>(Channel.UNLIMITED)
    private val controllerJob: Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        val pending = linkedMapOf<AppConfirmationId, CompletableDeferred<AppConfirmationResult>>()
        var nextId = 0L
        try {
            for (command in commandChannel) {
                when (command) {
                    is Command.Request -> {
                        val id = AppConfirmationId(++nextId)
                        pending[id] = command.result
                        deliveryChannel.send(AppConfirmationDelivery(id, command.event, command.result))
                    }

                    is Command.Resolve -> {
                        val result = pending.remove(command.id)
                        command.accepted.complete(result?.complete(command.result) == true)
                    }

                    is Command.Cancel -> {
                        val entry = pending.entries.firstOrNull { it.value === command.result }
                        if (entry != null) {
                            pending.remove(entry.key)
                            command.result.cancel(CancellationException("Confirmation requester was cancelled"))
                        }
                    }
                }
            }
        } finally {
            val cause = CancellationException("App confirmation controller is closed")
            pending.values.forEach { it.cancel(cause) }
            deliveryChannel.cancel(cause)
        }
    }

    val deliveries: Flow<AppConfirmationDelivery> = deliveryChannel.receiveAsFlow()

    suspend fun request(event: AppConfirmationEvent): AppConfirmationResult {
        val result = CompletableDeferred<AppConfirmationResult>()
        check(commandChannel.trySend(Command.Request(event, result)).isSuccess) {
            "App confirmation controller is closed"
        }
        return try {
            result.await()
        } finally {
            if (!result.isCompleted) {
                commandChannel.trySend(Command.Cancel(result))
            }
        }
    }

    suspend fun resolve(id: AppConfirmationId, result: AppConfirmationResult): Boolean {
        val accepted = CompletableDeferred<Boolean>()
        if (commandChannel.trySend(Command.Resolve(id, result, accepted)).isFailure) return false
        return accepted.await()
    }

    fun close() {
        commandChannel.close()
        controllerJob.cancel(CancellationException("App confirmation controller is closed"))
    }
}
