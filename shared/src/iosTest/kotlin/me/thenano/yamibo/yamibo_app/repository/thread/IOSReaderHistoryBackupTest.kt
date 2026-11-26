package me.thenano.yamibo.yamibo_app.repository.thread

import io.github.littlesurvival.dto.value.PostId
import io.github.littlesurvival.dto.value.ThreadId
import io.github.littlesurvival.dto.value.UserId
import kotlinx.coroutines.runBlocking
import me.thenano.yamibo.yamibo_app.Database
import me.thenano.yamibo.yamibo_app.repository.BackupRepository
import me.thenano.yamibo.yamibo_app.repository.IOSBackupStorageProvider
import me.thenano.yamibo.yamibo_app.repository.IOSReadHistoryRepository
import me.thenano.yamibo.yamibo_app.repository.ReadHistoryRepository.*
import me.thenano.yamibo.yamibo_app.repository.backup.BackupRepositoryImpl
import me.thenano.yamibo.yamibo_app.repository.settings.AppSettingsRepository
import me.thenano.yamibo.yamibo_app.store.settings.IOSSettingsStore
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class IOSReaderHistoryBackupTest {
    private val driver = readerDatabaseDriver()
    private val db = Database(driver)
    private val history = IOSReadHistoryRepository(db)
    private val tid = ThreadId(140)
    private val author = UserId(7)
    private val post = PostId(1403)
    private val image = ImageReadingHistory(PostId(1401), tid, 2, 5, lastVisitTime = 9000,
        anchor = ImageAnchor(post, "marker-a", 1, 3, author))
    private val text = ThreadReadingHistory(ThreadEntryType.Novel, "QA", tid, null, null, null, null,
        author, 3, post, "QA floor", anchorPostId = 1403, anchorBlockId = "paragraph-2",
        anchorBlockType = "Text", anchorBlockRatio = 0.25f, lastVisitTime = 9000,
        floorAuthor = author, imageSource = "marker-a", imageOccurrence = 1)

    @AfterTest fun close() { driver.close() }

    @Test fun currentBackupRestoresTextAndImageAnchorsIntoFreshNativeDatabase() = runBlocking {
        val settings = IOSSettingsStore()
        val files = IOSBackupStorageProvider(AppSettingsRepository(settings))
        fun backups(database: Database) = BackupRepositoryImpl(database, settings, emptyList(), files, 12)
        history.savePosition(text)
        history.saveImagePosition(image)
        val file = backups(db).createBackup(false, "qa-reader-${Uuid.random()}").getOrThrow()
        try {
            val targetDriver = readerDatabaseDriver()
            try {
                val target = Database(targetDriver)
                val restored = IOSReadHistoryRepository(target)
                assertNull(restored.getImagePosition(image.postId))
                assertNull(restored.getPosition(tid, text.threadType, author))
                val summary = backups(target).restoreBackup(file.uri, BackupRepository.RestoreMode.Overwrite).getOrThrow()
                assertEquals(2, summary.readingHistory)
                assertEquals(text, restored.getPosition(tid, text.threadType, author))
                assertEquals(image, restored.getImagePosition(image.postId))
            } finally {
                targetDriver.close()
            }
        } finally {
            files.deleteBackupFile(file).getOrThrow()
        }
    }

    @Test fun olderWritesAndSameTimeLegacyImageReplayKeepTheCorrectPosition() = runBlocking {
        history.saveImagePosition(image)
        history.saveImagePosition(image.copy(pageIndex = 0, lastVisitTime = 8000, anchor = null))
        assertEquals(image, history.getImagePosition(image.postId))
        val legacy = image.copy(pageIndex = 1, anchor = null)
        repeat(2) { history.saveImagePosition(legacy) }
        assertEquals(legacy, history.getImagePosition(image.postId))
        history.savePosition(text)
        history.savePosition(text.copy(anchorBlockId = "older", lastVisitTime = 8000))
        assertEquals(text, history.getPosition(tid, text.threadType, author))
    }
}
