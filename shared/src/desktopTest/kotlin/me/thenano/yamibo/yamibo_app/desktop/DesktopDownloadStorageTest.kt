package me.thenano.yamibo.yamibo_app.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.net.URI
import java.util.Comparator
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import me.thenano.yamibo.yamibo_app.repository.download.*
import me.thenano.yamibo.yamibo_app.repository.settings.AppSettingsRepository
import me.thenano.yamibo.yamibo_app.store.settings.DesktopSettingsStore
import kotlin.test.*

class DesktopDownloadStorageTest {
    @Test fun listingSkipsCorruptManifestsAndPreservesHealthyDownloads() = runBlocking {
        val root = Files.createTempDirectory("yamibo-download-list-")
        try {
            val settings = AppSettingsRepository(DesktopSettingsStore(root.resolve("settings.properties")))
            settings.backupFolderUri.setValue(root.toUri().toString())
            var reports = 0
            val storage = DesktopDownloadStorageProvider(settings) { reports++ }
            val thread = ThreadPageDownloadManifest(ThreadPageDownloadKey(123, 1), "正常文章", 10L, 1,
                DownloadPageKind.LastAtDownloadTime)
            val tag = TagMangaChapterManifest(TagMangaChapterDownloadKey(12, 34, 56), "標籤",
                title = "正常章節", tagPage = 1, imageCount = 0, downloadedAt = 10L, images = emptyList())
            val rss = RssMangaChapterManifest(RssMangaChapterDownloadKey(78L, 34, 56), "訂閱", "搜尋",
                title = "正常章節", subscriptionPage = 1, imageCount = 0, downloadedAt = 10L, images = emptyList())
            storage.writeThreadPage(thread.key, Json.encodeToString(thread).encodeToByteArray(), byteArrayOf(), emptyList())
            storage.writeTagMangaChapter(tag.key, Json.encodeToString(tag).encodeToByteArray(), emptyList())
            storage.writeRssMangaChapter(rss.key, Json.encodeToString(rss).encodeToByteArray(), emptyList())
            val corrupt = "損壞 manifest".encodeToByteArray()
            val threadKey = ThreadPageDownloadKey(456, 1)
            val tagKey = TagMangaChapterDownloadKey(12, 35, 57)
            val rssKey = RssMangaChapterDownloadKey(78L, 35, 57)
            storage.writeThreadPage(threadKey, corrupt, byteArrayOf(), emptyList())
            storage.writeTagMangaChapter(tagKey, corrupt, emptyList())
            storage.writeRssMangaChapter(rssKey, corrupt, emptyList())
            repeat(2) {
                assertEquals(listOf(thread), storage.listManifests())
                assertEquals(listOf(tag), storage.listTagMangaManifests())
                assertEquals(listOf(rss), storage.listRssMangaManifests())
            }
            assertEquals(1, reports)
            listOf(threadKey.stableId, "${tagKey.tagStableId}/${tagKey.chapterStableId}",
                "${rssKey.rssStableId}/${rssKey.chapterStableId}").forEach {
                assertContentEquals(corrupt, Files.readAllBytes(root.resolve("YamiboDownloads/$it/manifest.json")))
            }
        } finally {
            Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
    @Test fun corruptQueueCannotBeOverwrittenByANewQueue() = runBlocking {
        val root = Files.createTempDirectory("yamibo-corrupt-queue-")
        try {
            val settings = AppSettingsRepository(DesktopSettingsStore(root.resolve("settings.properties")))
            settings.backupFolderUri.setValue(root.toUri().toString())
            val storage = DesktopDownloadStorageProvider(settings)
            storage.writeQueue(emptyList())
            val queueFile = root.resolve("YamiboDownloads/queue.json")
            Files.writeString(queueFile, "unreadable original queue")
            assertFailsWith<kotlinx.serialization.SerializationException> { storage.readQueue() }
            assertFailsWith<kotlinx.serialization.SerializationException> { storage.writeQueue(emptyList()) }
            assertEquals("unreadable original queue", Files.readString(queueFile))
            Files.list(queueFile.parent).use { assertEquals(listOf(queueFile), it.toList()) }
        } finally {
            Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
    @Test fun unavailableOrCorruptOfflineReadsReportOnceWithoutDeletingData() = runBlocking {
        val root = Files.createTempDirectory("yamibo-unavailable-download-")
        try {
            val settings = AppSettingsRepository(DesktopSettingsStore(root.resolve("settings.properties")))
            val folder = Files.createDirectory(root.resolve("downloads"))
            settings.backupFolderUri.setValue(folder.toUri().toString())
            var reports = 0
            val storage = DesktopDownloadStorageProvider(settings) { reports++ }
            val key = ThreadPageDownloadKey(123, 1)
            Files.delete(folder)
            assertNull(storage.readThreadPage(key))
            assertNull(storage.readManifest(key))
            assertTrue(storage.listManifests().isEmpty())
            assertTrue(storage.listTagMangaManifests().isEmpty())
            assertTrue(storage.listRssMangaManifests().isEmpty())
            assertEquals(1, reports)
            assertFalse(Files.exists(folder))
            Files.createDirectory(folder)
            val content = "保留內容".encodeToByteArray()
            storage.writeThreadPage(key, "broken json".encodeToByteArray(), content, emptyList())
            assertNull(storage.readManifest(key))
            assertContentEquals(content, storage.readThreadPage(key))
            assertEquals(1, reports)
            assertEquals("broken json", Files.readString(folder.resolve("YamiboDownloads")
                .resolve(key.stableId).resolve("manifest.json")))
        } finally {
            Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
    @Test fun tagAndRssChaptersSurviveSettingsAndStorageRestart() = runBlocking {
        val root = Files.createTempDirectory("yamibo-manga-download-")
        try {
            val settingsPath = root.resolve("settings.properties")
            val settings = AppSettingsRepository(DesktopSettingsStore(settingsPath))
            settings.backupFolderUri.setValue(root.toUri().toString())
            val storage = DesktopDownloadStorageProvider(settings)
            val tagKey = TagMangaChapterDownloadKey(12, 34, 56)
            val rssKey = RssMangaChapterDownloadKey(78L, 34, 56)
            val image = PendingDownloadedImage("中文封面.png", byteArrayOf(1, 2, 3))
            val images = listOf(DownloadedImage("https://example.com/cover.png", image.fileName, 3L))
            val tag = TagMangaChapterManifest(tagKey, "標籤", title = "章節", tagPage = 2,
                imageCount = 1, downloadedAt = 100L, images = images)
            val rss = RssMangaChapterManifest(rssKey, "訂閱", "中文搜尋", title = "章節",
                subscriptionPage = 3, imageCount = 1, downloadedAt = 200L, images = images)
            storage.writeTagMangaChapter(tagKey, Json.encodeToString(tag).encodeToByteArray(), listOf(image))
            storage.writeRssMangaChapter(rssKey, Json.encodeToString(rss).encodeToByteArray(), listOf(image))
            val reopened = DesktopDownloadStorageProvider(AppSettingsRepository(DesktopSettingsStore(settingsPath)))
            assertEquals(tag, reopened.readTagMangaManifest(tagKey))
            assertEquals(rss, reopened.readRssMangaManifest(rssKey))
            assertEquals(listOf(tag), reopened.listTagMangaManifests())
            assertEquals(listOf(rss), reopened.listRssMangaManifests())
            assertTrue(reopened.listManifests().isEmpty())
            val tagUri = assertNotNull(reopened.resolveTagMangaImageUri(tagKey, image.fileName))
            val rssUri = assertNotNull(reopened.resolveRssMangaImageUri(rssKey, image.fileName))
            assertNotEquals(tagUri, rssUri)
            assertContentEquals(image.bytes, Files.readAllBytes(Path.of(URI(tagUri))))
            assertContentEquals(image.bytes, Files.readAllBytes(Path.of(URI(rssUri))))
            reopened.deleteTagMangaChapter(tagKey)
            assertNull(reopened.readTagMangaManifest(tagKey))
            assertEquals(rss, reopened.readRssMangaManifest(rssKey))
            reopened.deleteRssMangaChapter(rssKey)
            assertTrue(reopened.listRssMangaManifests().isEmpty())
        } finally {
            Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
    @Test fun readingWithoutASelectedFolderIsACacheMiss() = runBlocking {
        val root = Files.createTempDirectory("yamibo-unconfigured-download-")
        try {
            val storage = DesktopDownloadStorageProvider(AppSettingsRepository(DesktopSettingsStore(root.resolve("settings.properties"))))
            assertFalse(storage.isReady())
            assertNull(storage.readThreadPage(ThreadPageDownloadKey(123, 1)))
            assertNull(storage.readManifest(ThreadPageDownloadKey(123, 1)))
            assertNull(storage.resolveImageUri(ThreadPageDownloadKey(123, 1), "image.png"))
            assertTrue(storage.listManifests().isEmpty())
            assertTrue(storage.readQueue().isEmpty())
            Files.list(root).use { assertEquals(0L, it.count()) }
        } finally { Files.delete(root) }
    }
    @Test fun downloadsSurviveRestartAndRejectUnsafeReplacement() = runBlocking {
        val root = Files.createTempDirectory("yamibo-download-test-")
        try {
            val settings = AppSettingsRepository(DesktopSettingsStore(root.resolve("settings.properties")))
            settings.backupFolderUri.setValue(root.toUri().toString())
            val storage = DesktopDownloadStorageProvider(settings)
            val key = ThreadPageDownloadKey(123, 1)
            val manifest = ThreadPageDownloadManifest(key, "測試", 10L, 1, DownloadPageKind.LastAtDownloadTime)
            val content = "離線文章".encodeToByteArray()
            val image = PendingDownloadedImage("封面.png", byteArrayOf(1, 2, 3))
            storage.writeThreadPage(key, Json.encodeToString(manifest).encodeToByteArray(), content, listOf(image))
            assertEquals(listOf(manifest), storage.listManifests())
            assertContentEquals(image.bytes, Files.readAllBytes(Path.of(URI(storage.resolveImageUri(key, image.fileName)))))
            assertFailsWith<IllegalArgumentException> {
                storage.writeThreadPage(key, byteArrayOf(), byteArrayOf(), listOf(PendingDownloadedImage("../escape", byteArrayOf())))
            }
            assertContentEquals(content, storage.readThreadPage(key))
            val queue = listOf(DownloadQueueEntry(key, "測試", DownloadStatus.Paused))
            storage.writeQueue(queue)
            val reopened = DesktopDownloadStorageProvider(settings)
            assertEquals(queue, reopened.readQueue())
            assertContentEquals(content, reopened.readThreadPage(key))
            val dir = root.resolve("YamiboDownloads").resolve(key.stableId)
            Files.move(dir, dir.resolveSibling("${key.stableId}.previous"))
            assertContentEquals(content, reopened.readThreadPage(key))
            reopened.deleteThread(key)
            assertNull(reopened.readThreadPage(key))
            assertTrue(reopened.listManifests().isEmpty())
        } finally {
            Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
}
