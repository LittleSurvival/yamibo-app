package me.thenano.yamibo.yamibo_app.confirmation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AppConfirmationControllerTest {
    @Test
    fun delayedHostReceivesRequestsInFifoOrderWithoutDrops() = runBlocking {
        val controller = AppConfirmationController(this)
        val requests = (1..100).map { index ->
            async { controller.request(event("message-$index")) }
        }

        val deliveries = controller.deliveries.take(requests.size).toList()
        assertEquals((1..100).map { "message-$it" }, deliveries.map { it.event.message })
        deliveries.forEach { assertTrue(controller.resolve(it.id, AppConfirmationResult.Confirmed)) }
        assertTrue(requests.all { it.await() == AppConfirmationResult.Confirmed })
        controller.close()
    }

    @Test
    fun confirmAndDismissReturnTypedResults() = runBlocking {
        val controller = AppConfirmationController(this)
        val confirmed = async { controller.request(event("confirm")) }
        val confirmDelivery = controller.deliveries.first()
        assertTrue(controller.resolve(confirmDelivery.id, AppConfirmationResult.Confirmed))
        assertEquals(AppConfirmationResult.Confirmed, confirmed.await())

        val dismissed = async { controller.request(event("dismiss")) }
        val dismissDelivery = controller.deliveries.first()
        assertTrue(controller.resolve(dismissDelivery.id, AppConfirmationResult.Dismissed))
        assertEquals(AppConfirmationResult.Dismissed, dismissed.await())
        controller.close()
    }

    @Test
    fun deliveryCanOnlyBeResolvedOnce() = runBlocking {
        val controller = AppConfirmationController(this)
        val request = async { controller.request(event("once")) }
        val delivery = controller.deliveries.first()

        assertTrue(controller.resolve(delivery.id, AppConfirmationResult.Confirmed))
        assertFalse(controller.resolve(delivery.id, AppConfirmationResult.Dismissed))
        assertEquals(AppConfirmationResult.Confirmed, request.await())
        controller.close()
    }

    @Test
    fun requesterCancellationRejectsLaterResolution() = runBlocking {
        val controller = AppConfirmationController(this)
        val request = launch { controller.request(event("cancel")) }
        val delivery = controller.deliveries.first()

        request.cancelAndJoin()
        yield()

        assertFalse(controller.resolve(delivery.id, AppConfirmationResult.Confirmed))
        controller.close()
    }

    @Test
    fun closeCancelsPendingRequestAndRejectsNewRequests() = runBlocking {
        val controller = AppConfirmationController(this)
        val pending = async { controller.request(event("pending")) }
        controller.deliveries.first()

        controller.close()

        assertFailsWith<CancellationException> { pending.await() }
        assertFailsWith<IllegalStateException> { controller.request(event("closed")) }
        Unit
    }

    private fun event(message: String) = AppConfirmationEvent(
        title = "title",
        message = message,
        confirmLabel = "confirm",
        dismissLabel = "dismiss",
    )
}
