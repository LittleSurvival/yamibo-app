package me.thenano.yamibo.yamibo_app.profile

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LoginWafPrewarmTest {
    @Test
    fun marksPrewarmCompleteAfterUnexpectedFailure() = runBlocking {
        var completed = false

        runLoginWafPrewarm(
            prewarm = { error("unexpected") },
            onComplete = { completed = true },
        )

        assertTrue(completed)
    }

    @Test
    fun marksPrewarmCompleteAndPropagatesCancellation() {
        var completed = false

        assertFailsWith<CancellationException> {
            runBlocking {
                runLoginWafPrewarm(
                    prewarm = { throw CancellationException("cancelled") },
                    onComplete = { completed = true },
                )
            }
        }

        assertTrue(completed)
    }
}
