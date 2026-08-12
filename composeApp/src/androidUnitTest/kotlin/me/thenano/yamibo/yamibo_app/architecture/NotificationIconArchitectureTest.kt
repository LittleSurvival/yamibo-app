package me.thenano.yamibo.yamibo_app.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotificationIconArchitectureTest {
    @Test
    fun everySmallIconUsesSharedNotificationMetadata() {
        val sourceRoot = projectRoot().resolve("composeApp/src/androidMain/kotlin")
        val calls = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().asSequence()
                    .filter { ".setSmallIcon(" in it }
                    .map { line -> file.relativeTo(sourceRoot).invariantSeparatorsPath to line.trim() }
            }
            .toList()

        assertTrue(calls.isNotEmpty(), "Expected at least one Android notification builder")
        val offenders = calls.filterNot { (_, line) ->
            line == ".setSmallIcon(AndroidNotificationMetadata.SMALL_ICON_RES_ID)"
        }
        assertTrue(offenders.isEmpty(), "Non-shared notification small icons: $offenders")
    }

    @Test
    fun dedicatedIconIsTransparentMonochromeLauncherSilhouette() {
        val icon = projectRoot()
            .resolve("composeApp/src/androidMain/res/drawable/ic_stat_yamibo.xml")
            .readText()

        assertTrue("#FFFFFFFF" in icon)
        assertTrue("android:pathData=" in icon)
        assertFalse("<gradient" in icon)
        assertFalse("android:color=" in icon)
        assertFalse("ic_launcher" in icon)
    }

    @Test
    fun sharedMetadataOwnsTheDedicatedResourceReference() {
        val metadata = projectRoot().resolve(
            "composeApp/src/androidMain/kotlin/me/thenano/yamibo/yamibo_app/notification/AndroidNotificationMetadata.kt",
        ).readText()

        assertEquals(1, Regex("R\\.drawable\\.ic_stat_yamibo").findAll(metadata).count())
        assertFalse("R.mipmap.ic_launcher" in metadata)
        assertFalse("R.drawable.ic_launcher_foreground" in metadata)
    }

    private fun projectRoot(): File {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        return generateSequence(File(userDir).absoluteFile) { it.parentFile }
            .firstOrNull { it.resolve("composeApp").isDirectory && it.resolve("shared").isDirectory }
            ?: error("Cannot locate project root from $userDir")
    }
}
