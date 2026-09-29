package me.thenano.yamibo.yamibo_app.desktop

import io.github.littlesurvival.dto.model.*
import io.github.littlesurvival.dto.page.*
import io.github.littlesurvival.dto.value.*
import java.lang.reflect.Proxy
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlin.test.*
import me.thenano.yamibo.yamibo_app.repository.ThreadRepository
import me.thenano.yamibo.yamibo_app.repository.download.*
import me.thenano.yamibo.yamibo_app.repository.settings.AppSettingsRepository
import me.thenano.yamibo.yamibo_app.store.settings.DesktopSettingsStore

class DesktopOfflineRepositoryTest {
    @Test fun reconstructedRepositoryReadsSavedTextAndLocalImagesWithoutRemoteCalls() = runBlocking {
        val root = Files.createTempDirectory(Files.createDirectories(Path.of("../.tmp/qa")), "offline-repository-")
        val fetcher = DownloadImageFetcher { error("Offline reads must not request image credentials") }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val remote = Proxy.newProxyInstance(ThreadRepository::class.java.classLoader,
            arrayOf(ThreadRepository::class.java)) { _, method, _ ->
            calls.incrementAndGet()
            error("Offline read reached remote repository: ${method.name}")
        } as ThreadRepository
        try {
            val settingsPath = root.resolve("settings.properties")
            val settings = AppSettingsRepository(DesktopSettingsStore(settingsPath))
            settings.backupFolderUri.setValue(root.toUri().toString())
            val key = ThreadPageDownloadKey(123, 1)
            val source = "https://bbs.yamibo.com/data/attachment/offline-test.png"
            val imageBytes = java.io.ByteArrayOutputStream().apply {
                javax.imageio.ImageIO.write(java.awt.image.BufferedImage(2, 2,
                    java.awt.image.BufferedImage.TYPE_INT_RGB), "png", this)
            }.toByteArray()
            val page = ThreadPage(
                thread = ThreadInfo(ThreadId(123), "離線測試", ForumSummary(ForumId(1), "測試", "forum.php?fid=1")),
                posts = listOf(Post(pid = PostId(1), floor = 1, title = "章節",
                    author = User(UserId(7), "測試作者"), timeCreate = TimeInfo("2026-01-01", null, 0),
                    contentHtml = "<p>離線中文內容</p><img src=\"$source\">",
                    images = listOf(PostImage(source)), tags = Tags(), poll = null)),
                pageNav = PageNav(currentPage = 1, totalPages = 1),
            )
            val manifest = ThreadPageDownloadManifest(key, "離線測試", 1L, 1,
                DownloadPageKind.LastAtDownloadTime,
                images = listOf(DownloadedImage(source, "圖片.png", imageBytes.size.toLong())))
            DesktopDownloadStorageProvider(settings).writeThreadPage(key,
                Json.encodeToString(manifest).encodeToByteArray(), Json.encodeToString(page).encodeToByteArray(),
                listOf(PendingDownloadedImage("圖片.png", imageBytes)))
            val tagKey = TagMangaChapterDownloadKey(12, 34, 56)
            val rssKey = RssMangaChapterDownloadKey(78L, 34, 56)
            val tag = TagMangaChapterManifest(tagKey, "標籤", title = "離線章節", tagPage = 2,
                imageCount = 1, downloadedAt = 1L, images = manifest.images)
            val rss = RssMangaChapterManifest(rssKey, "訂閱", "中文搜尋", title = "離線章節",
                subscriptionPage = 3, imageCount = 1, downloadedAt = 1L, images = manifest.images)
            DesktopDownloadStorageProvider(settings).apply {
                writeTagMangaChapter(tagKey, Json.encodeToString(tag).encodeToByteArray(),
                    listOf(PendingDownloadedImage("圖片.png", imageBytes)))
                writeRssMangaChapter(rssKey, Json.encodeToString(rss).encodeToByteArray(),
                    listOf(PendingDownloadedImage("圖片.png", imageBytes)))
            }

            // Fresh settings, storage and repository instances: no in-memory downloaded-page cache.
            val restoredSettings = AppSettingsRepository(DesktopSettingsStore(settingsPath))
            val failures = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
            val repository = DesktopDownloadRepository(restoredSettings.backupFolderUri.stateFlow, scope) { folder, child ->
                DownloadRepositoryImpl(remote, storageProvider = DesktopDownloadStorageProvider(restoredSettings, folder),
                    imageFetcher = fetcher, scope = child, onBackgroundFailure = failures::add)
            }
            withTimeout(5_000) {
                assertEquals(DownloadStatus.Downloaded, repository.getStatus(key))
                val restored = assertNotNull(repository.getDownloadedPage(key))
                assertEquals(page.thread, restored.thread)
                assertTrue(restored.posts.single().contentHtml.contains("離線中文內容"))
                val local = restored.posts.single().images.single().url
                assertEquals("file", URI(local).scheme)
                assertTrue(restored.posts.single().contentHtml.contains(local))
                assertFalse(restored.posts.single().contentHtml.contains(source))
                assertContentEquals(imageBytes, Files.readAllBytes(Path.of(URI(local))))
                assertEquals(DownloadStatus.Downloaded, repository.getStatus(tagKey))
                assertEquals(DownloadStatus.Downloaded, repository.getStatus(rssKey))
                assertEquals(tag, repository.getTagMangaManifest(tagKey))
                assertEquals(rss, repository.getRssMangaManifest(rssKey))
                val tagImage = assertNotNull(repository.getTagMangaChapterImages(tagKey)).single()
                val rssImage = assertNotNull(repository.getRssMangaChapterImages(rssKey)).single()
                listOf(tagImage, rssImage).forEach { image ->
                    assertEquals("file", URI(image).scheme)
                    assertContentEquals(imageBytes, Files.readAllBytes(Path.of(URI(image))))
                    assertNotNull(javax.imageio.ImageIO.read(Path.of(URI(image)).toFile()))
                }
                assertNotEquals(tagImage, rssImage)
                assertEquals(3, repository.getSummary().downloaded)
                assertEquals(0, calls.get())
                assertTrue(failures.isEmpty())
            }
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
            fetcher.close()
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
}
