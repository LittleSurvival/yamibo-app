package me.thenano.yamibo.yamibo_app.desktop

import java.nio.file.Files
import me.thenano.yamibo.yamibo_app.db.DatabaseFactory
import me.thenano.yamibo.yamibo_app.core.cache.DiskCacheFactory
import me.thenano.yamibo.yamibo_app.store.settings.DesktopSettingsStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DesktopStorageTest {
    @Test fun diskCacheReopensAfterOwnerClosesItsDriver() = kotlinx.coroutines.runBlocking<Unit> {
        val root = Files.createTempDirectory("yamibo-cache-close-")
        try {
            val databaseFactory = DatabaseFactory(root.resolve("cache.db"))
            val first = DiskCacheFactory(databaseFactory, cacheDirPath = root.toString())
            try { first.create<String>("pages").set("page", "離線快取") }
            finally { first.close() }
            val reopened = DiskCacheFactory(databaseFactory, cacheDirPath = root.toString())
            try { assertEquals("離線快取", reopened.create<String>("pages").get("page")) }
            finally { reopened.close() }
        } finally {
            Files.walk(root).use { it.sorted(java.util.Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
    @Test
    fun preferencesSurviveRestartAndLeaveNoTemporaryFiles() {
        val root = Files.createTempDirectory("yamibo-settings-test-")
        val file = root.resolve("設定.properties")
        try {
            DesktopSettingsStore(file).apply {
                putString("字體", "繁體中文\n第二行")
                putInt("page", 3)
                putBoolean("tray", true)
                putFloat("indent", 2.5f)
            }
            DesktopSettingsStore(file).apply {
                assertEquals("繁體中文\n第二行", getString("字體", ""))
                assertEquals(3, getInt("page", 0))
                assertEquals(true, getBoolean("tray", false))
                assertEquals(2.5f, getFloat("indent", 0f))
                remove("page")
            }
            assertFalse(DesktopSettingsStore(file).hasKey("page"))
            Files.list(root).use { assertEquals(1L, it.count()) }
        } finally {
            Files.deleteIfExists(file)
            Files.deleteIfExists(root)
        }
    }

    @Test
    fun databaseCanBeCreatedAndReopenedWithoutRecreatingSchema() {
        val root = Files.createTempDirectory("yamibo-db-test-")
        val file = root.resolve("資料庫.db")
        try {
            repeat(2) { DatabaseFactory(file).createDriver().close() }
        } finally {
            Files.deleteIfExists(file)
            Files.deleteIfExists(root)
        }
    }
}
