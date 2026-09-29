package me.thenano.yamibo.yamibo_app.desktop

import java.nio.file.Files
import java.util.Comparator
import kotlinx.coroutines.runBlocking
import me.thenano.yamibo.yamibo_app.Database
import me.thenano.yamibo.yamibo_app.db.DatabaseFactory
import me.thenano.yamibo.yamibo_app.repository.BackupRepository
import me.thenano.yamibo.yamibo_app.repository.backup.BackupRepositoryImpl
import me.thenano.yamibo.yamibo_app.repository.backup.DesktopBackupStorageProvider
import me.thenano.yamibo.yamibo_app.repository.settings.*
import me.thenano.yamibo.yamibo_app.store.settings.DesktopSettingsStore
import kotlin.test.*

class DesktopBackupIntegrationTest {
    @Test fun realBackupFileRestoresReaderSettingsAndChapterStateIntoAnotherDatabase() = runBlocking {
        val root = Files.createTempDirectory("yamibo-backup-integration-")
        val sourceDriver = DatabaseFactory(root.resolve("source.db")).createDriver()
        val targetDriver = DatabaseFactory(root.resolve("target.db")).createDriver()
        try {
            val sourceDb = Database(sourceDriver)
            val targetDb = Database(targetDriver)
            val sourceStore = DesktopSettingsStore(root.resolve("source.properties"))
            val targetStore = DesktopSettingsStore(root.resolve("target.properties"))
            val sourceSettings = AppSettingsRepository(sourceStore)
            val targetSettings = AppSettingsRepository(targetStore)
            val sourceReader = NovelReaderSettingsRepository(sourceStore)
            val targetReader = NovelReaderSettingsRepository(targetStore)
            sourceReader.firstLineIndentChars.setValue(2.5f)
            targetReader.firstLineIndentChars.setValue(0f)
            sourceDb.localChapterStateQueries.upsert(
                targetType = "ThreadNormal", parentId = 10, targetId = 11,
                title = "中文章節", read = 1, progressPercent = 75, lastPageIndex = 3,
                totalPages = 4, updatedAt = 100,
            )
            val storage = DesktopBackupStorageProvider(sourceSettings)
            storage.setSelectedFolder(root.toUri().toString()).getOrThrow()
            val source = BackupRepositoryImpl(sourceDb, sourceStore,
                listOf(sourceSettings, sourceReader, MangaReaderSettingsRepository(sourceStore)), storage, 9)
            val target = BackupRepositoryImpl(targetDb, targetStore,
                listOf(targetSettings, targetReader, MangaReaderSettingsRepository(targetStore)),
                DesktopBackupStorageProvider(targetSettings), 9)
            val file = source.createBackup(false, "桌面往返").getOrThrow()
            target.restoreBackup(file.uri, BackupRepository.RestoreMode.Overwrite).getOrThrow()
            assertEquals(2.5f, NovelReaderSettingsRepository(DesktopSettingsStore(root.resolve("target.properties"))).firstLineIndentChars.getValue())
            val chapter = targetDb.localChapterStateQueries.getAll().executeAsOne()
            assertEquals("中文章節", chapter.title)
            assertEquals(75L, chapter.progressPercent)
            assertEquals(3L, chapter.lastPageIndex)
        } finally {
            sourceDriver.close()
            targetDriver.close()
            Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
}
