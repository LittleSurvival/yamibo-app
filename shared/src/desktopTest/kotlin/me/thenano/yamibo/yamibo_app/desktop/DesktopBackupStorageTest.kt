package me.thenano.yamibo.yamibo_app.desktop

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import me.thenano.yamibo.yamibo_app.repository.backup.DesktopBackupStorageProvider
import me.thenano.yamibo.yamibo_app.repository.settings.AppSettingsRepository
import me.thenano.yamibo.yamibo_app.store.settings.DesktopSettingsStore
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopBackupStorageTest {
    @Test
    fun backupRoundTripRejectsOverwriteTraversalAndForeignDeletion() = runBlocking {
        val root = Files.createTempDirectory("yamibo-backup-test-")
        val folder = Files.createDirectory(root.resolve("備份"))
        val preferences = root.resolve("settings.properties")
        val foreign = root.resolve("foreign.yamibobak")
        val file = folder.resolve("中文-autobackup.yamibobak")
        try {
            val settings = AppSettingsRepository(DesktopSettingsStore(preferences))
            val storage = DesktopBackupStorageProvider(settings)
            storage.setSelectedFolder(folder.toUri().toString()).getOrThrow()
            val bytes = "備份內容".toByteArray(Charsets.UTF_8)
            val saved = storage.writeBackupFile(file.fileName.toString(), bytes).getOrThrow()
            assertTrue(saved.automatic)
            assertContentEquals(bytes, storage.readBackupFile(saved.uri).getOrThrow())
            assertEquals(listOf(saved), storage.listBackupFiles())
            assertEquals(bytes.size.toLong(), storage.getBackupStorageBytes())
            assertTrue(storage.writeBackupFile(saved.name, byteArrayOf(0)).isFailure)
            assertTrue(storage.writeBackupFile("../escape.yamibobak", bytes).isFailure)
            assertTrue(storage.writeBackupFile("..\\escape.yamibobak", bytes).isFailure)
            Files.write(foreign, bytes)
            assertTrue(storage.deleteBackupFile(saved.copy(uri = foreign.toUri().toString())).isFailure)
            assertContentEquals(bytes, Files.readAllBytes(foreign))
            val reopened = DesktopBackupStorageProvider(AppSettingsRepository(DesktopSettingsStore(preferences)))
            assertEquals(folder.toString(), reopened.getSelectedFolderLabel())
            reopened.deleteBackupFile(saved).getOrThrow()
            Files.list(folder).use { assertEquals(0L, it.count()) }
        } finally {
            Files.deleteIfExists(file)
            Files.deleteIfExists(foreign)
            Files.deleteIfExists(preferences)
            Files.deleteIfExists(folder)
            Files.deleteIfExists(root)
        }
    }
}
