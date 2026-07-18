package me.thenano.yamibo.yamibo_app.feedback

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppFeedbackControllerTest {
    @Test
    fun delayedConsumerKeepsOnlyRecentInformationalEventsInOrder() = runBlocking {
        val controller = AppFeedbackController()
        val expected = (1..200).map { "message-$it" }

        expected.forEach(controller::post)

        val actual = controller.deliveries.take(64).toList().map { it.event.message }
        assertEquals(expected.takeLast(64), actual)
    }

    @Test
    fun delayedConsumerReceivesEveryDurableEvent() = runBlocking {
        val controller = AppFeedbackController()
        val expected = (1..200).map { "error-$it" }

        expected.forEach { message ->
            controller.post(AppFeedbackEvent(message, kind = AppFeedbackKind.Error))
        }

        val actual = controller.deliveries.take(expected.size).toList().map { it.event.message }
        assertEquals(expected, actual)
    }

    @Test
    fun deliveryIdsAreStableAndResolutionHandlerIsReleasedWithDelivery() = runBlocking {
        val controller = AppFeedbackController()
        var resolved: AppFeedbackResult? = null
        val id = controller.post(
            AppFeedbackEvent(message = "retry", actionLabel = "Retry", withDismissAction = true),
            onResult = { resolved = it },
        )

        val delivery = controller.deliveries.take(1).toList().single()
        assertEquals(id, delivery.id)

        assertTrue(controller.resolve(delivery, AppFeedbackResult.ActionPerformed))
        assertEquals(AppFeedbackResult.ActionPerformed, resolved)
        assertFalse(controller.resolve(delivery, AppFeedbackResult.Dismissed))
        assertEquals(AppFeedbackResult.ActionPerformed, resolved)
    }
}
