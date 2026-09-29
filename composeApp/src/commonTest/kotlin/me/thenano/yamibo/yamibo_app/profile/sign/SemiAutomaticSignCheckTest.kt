package me.thenano.yamibo.yamibo_app.profile.sign

import io.github.littlesurvival.core.YamiboResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SemiAutomaticSignCheckTest {
    @Test
    fun terminalBrowserErrorSuppressesPendingAndSubsequentCompletion() = runTest {
        val check = SemiAutomaticSignCheck()
        val response = CompletableDeferred<YamiboResult<*>>()
        var exits = 0
        launch { check.check(false, { response.await() }, { error("Already closed") }, { error("Already closed") }) }
        runCurrent()
        check.finish { exits++ }
        check.finish { error("Duplicate error") }
        response.complete(YamiboResult.Success(Unit))
        runCurrent()
        check.check(true, { error("Already closed") }, { error("Already closed") }, { error("Already closed") })
        assertEquals(1, exits)
    }

    @Test
    fun parsedBrowserPageReturnsWithoutAnotherHttpRequestAndOnlyOnce() = runTest {
        val check = SemiAutomaticSignCheck()
        var returned = 0
        repeat(2) {
            check.check(true, { error("Must use current browser evidence") }, { returned++ }, { error("maintenance") })
        }
        assertEquals(1, returned)
    }

    @Test
    fun browserCompletionWinsOverPendingHttpCheck() = runTest {
        for (lateResult in listOf(YamiboResult.Success(Unit), YamiboResult.Maintenance, YamiboResult.Failure("WAF"))) {
            val check = SemiAutomaticSignCheck()
            val response = CompletableDeferred<YamiboResult<*>>()
            var returned = 0
            var maintenance = 0
            launch { check.check(false, { response.await() }, { returned++ }, { maintenance++ }) }
            runCurrent()
            check.check(true, { error("No duplicate request") }, { returned++ }, { maintenance++ })
            response.complete(lateResult)
            runCurrent()
            assertEquals(1, returned)
            assertEquals(0, maintenance)
        }
    }

    @Test
    fun failedCheckDoesNotExitAndAllowsLaterValidPage() = runTest {
        val check = SemiAutomaticSignCheck()
        var returned = 0
        check.check(false, { YamiboResult.Failure("challenge") }, { returned++ }, { error("maintenance") })
        assertEquals(0, returned)
        check.check(true, { error("No request") }, { returned++ }, { error("maintenance") })
        assertEquals(1, returned)
    }
}
