package me.thenano.yamibo.yamibo_app.performance

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LatestLoadGenerationTest {
    @Test
    fun onlyNewestRequestCanPublish() {
        val gate = LatestLoadGeneration()
        val first = gate.begin()
        assertTrue(gate.isCurrent(first))

        val second = gate.begin()
        assertFalse(gate.isCurrent(first))
        assertTrue(gate.isCurrent(second))
    }
}
