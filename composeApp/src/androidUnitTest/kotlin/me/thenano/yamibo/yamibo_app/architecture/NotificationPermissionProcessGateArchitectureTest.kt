package me.thenano.yamibo.yamibo_app.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotificationPermissionProcessGateArchitectureTest {
    @Test
    fun notificationPermissionRequestUsesProcessScopedAtomicGate() {
        val mainActivitySource = projectRoot().resolve(
            "composeApp/src/androidMain/kotlin/me/thenano/yamibo/yamibo_app/MainActivity.kt",
        ).readText()

        assertTrue("private val notificationPermissionRequested = AtomicBoolean(false)" in mainActivitySource)
        assertTrue("notificationPermissionRequested.compareAndSet(false, true)" in mainActivitySource)
        assertFalse("var notificationPermissionRequested = false" in mainActivitySource)
    }

    private fun projectRoot(): File {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        return generateSequence(File(userDir).absoluteFile) { it.parentFile }
            .firstOrNull { it.resolve("composeApp").isDirectory && it.resolve("shared").isDirectory }
            ?: error("Cannot locate project root from $userDir")
    }
}
