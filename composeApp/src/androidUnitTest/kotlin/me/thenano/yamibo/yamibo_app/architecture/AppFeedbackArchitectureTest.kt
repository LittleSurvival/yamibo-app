package me.thenano.yamibo.yamibo_app.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppFeedbackArchitectureTest {
    @Test
    fun snackbarDisplayIsOwnedOnlyByAppRoot() {
        val sourceRoot = projectRoot()
            .resolve("composeApp/src/commonMain/kotlin")
        val sources = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

        val stateOwners = sources.filter { "SnackbarHostState()" in it.readText() }
        val displayOwners = sources.filter { ".showSnackbar(" in it.readText() }
        val hostCallOwners = sources.filter {
            val text = it.readText()
            "YamiboSnackbarHost(" in text && it.name != "YamiboSnackbar.kt"
        }

        assertEquals(listOf("App.kt"), stateOwners.map(File::getName))
        assertEquals(listOf("App.kt"), displayOwners.map(File::getName))
        assertEquals(listOf("App.kt"), hostCallOwners.map(File::getName))
    }

    @Test
    fun appCodeDoesNotLaunchUnmanagedGlobalCoroutines() {
        val sourceRoot = projectRoot().resolve("composeApp/src/commonMain/kotlin")
        val offenders = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { "GlobalScope.launch" in it.readText() }
            .map { it.relativeTo(sourceRoot).invariantSeparatorsPath }
            .toList()

        assertTrue(offenders.isEmpty(), "Unmanaged global coroutine launches: $offenders")
    }

    @Test
    fun appFeedbackHostIsAboveNavigationStack() {
        val appSource = projectRoot()
            .resolve("composeApp/src/commonMain/kotlin/me/thenano/yamibo/yamibo_app/App.kt")
            .readText()

        assertTrue(
            ".zIndex(APP_FEEDBACK_Z_INDEX)" in appSource,
            "The root feedback host must render above pushed navigation screens",
        )
    }

    @Test
    fun cloudSyncUiUsesPageLocalOperationNotices() {
        val cloudSyncRoot = projectRoot().resolve(
            "composeApp/src/commonMain/kotlin/me/thenano/yamibo/yamibo_app/profile/settings/cloud",
        )
        val source = cloudSyncRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .joinToString("\n") { it.readText() }

        assertTrue("CloudSyncInlineNotice" in source)
        assertTrue("LocalAppFeedbackController" !in source)
        assertTrue("showSnackbar(" !in source)
    }

    private fun projectRoot(): File {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        return generateSequence(File(userDir).absoluteFile) { it.parentFile }
            .firstOrNull { it.resolve("composeApp").isDirectory && it.resolve("shared").isDirectory }
            ?: error("Cannot locate project root from $userDir")
    }
}
