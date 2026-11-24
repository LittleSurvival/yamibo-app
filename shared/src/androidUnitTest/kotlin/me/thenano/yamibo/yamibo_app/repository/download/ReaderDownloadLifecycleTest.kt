package me.thenano.yamibo.yamibo_app.repository.download

import io.github.littlesurvival.core.YamiboResult
import io.github.littlesurvival.dto.model.*
import io.github.littlesurvival.dto.page.*
import io.github.littlesurvival.dto.value.*
import java.lang.reflect.Proxy
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.*
import me.thenano.yamibo.yamibo_app.Database
import me.thenano.yamibo.yamibo_app.repository.ThreadRepository
import me.thenano.yamibo.yamibo_app.repository.thread.readerDatabaseDriver
import no.synth.kmpzip.io.SeekableSource
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.SYSTEM
import okio.ByteString.Companion.encodeUtf8
import okio.buffer
import okio.use
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ReaderDownloadLifecycleTest {
    private val fs = FileSystem.SYSTEM
    private val directory = Files.createTempDirectory("reader-download-test").toString().toPath()
    private val driver = readerDatabaseDriver()
    private val key = ThreadPageDownloadKey(140, 1, 7)
    private val first = "https://reader.invalid/first.jpg"
    private val second = "https://reader.invalid/second.jpg"
    private val requests = mutableListOf<String>()
    private var unavailable: String? = second
    private var librarySize = 0
    private var scans = 0
    private var pageOpens = 0
    private var owner = 7
    private var imageOnly: TagMangaChapterManifest? = null
    private val binaries = mutableMapOf<String, ByteArray>()
    private val loose = mutableMapOf<String, Path>()
    private var body = page("old", first, second)
    private var storedBytes: ByteArray? = null
    private var storedManifest: ThreadPageDownloadManifest? = null

    private inline fun <reified T> mock(crossinline answer: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            answer(method.name.substringBefore('-'), args ?: emptyArray())
        } as T

    private fun page(title: String, vararg urls: String): ThreadPage = ThreadPage(
        thread = ThreadInfo(ThreadId(140), title, ForumSummary(ForumId(30), "QA", "")),
        posts = listOf(Post(pid = PostId(1402), floor = 2, title = "", author = User(UserId(7), "A"),
            timeCreate = TimeInfo("", null, 0), contentHtml = urls.joinToString("") { "<img src='$it'>" },
            images = urls.map { PostImage(it, "") }, tags = Tags(), poll = null)))

    private fun repository(): DownloadRepositoryImpl {
        val storage = mock<DownloadStorageProvider> { name, args -> when (name) {
            "getStorageKey", "getSelectedFolderLabel" -> "fixture"
            "getOfflineFiles" -> PathOfflineFiles(fs) { directory / "library" }
            "isReady" -> true
            "listManifests" -> {
                scans++
                List(librarySize) { index -> ThreadPageDownloadManifest(
                    key = ThreadPageDownloadKey(5000 + index, 1), title = "other", downloadedAt = 1,
                    sourceTotalPages = 1, pageKind = DownloadPageKind.LastAtDownloadTime) } + listOfNotNull(storedManifest)
            }
            "listTagMangaManifests" -> listOfNotNull(imageOnly)
            "listRssMangaManifests", "readQueue" -> emptyList<Any>()
            "readThreadPage" -> storedBytes.takeIf { args[0] == key }
            "readPageRecord" -> {
                pageOpens++
                assertEquals(key, args[0])
                storedBytes?.let { it to storedManifest }
            }
            "readManifest" -> storedManifest?.takeIf { it.key == args[0] }
            "readTagMangaManifest" -> imageOnly?.takeIf { it.key == args[0] }
            "readRssMangaManifest" -> null
            "deleteItems" -> {
                val removed = args[1] as Set<*>
                if (key in removed) { storedBytes = null; storedManifest = null }
                if (imageOnly?.key in removed) imageOnly = null
                emptyMap<DownloadTaskKey, Throwable>()
            }
            "looseImages" -> loose.mapValues { it.value.toString() }
            "openLoose" -> fs.source((args[0] as String).toPath())
            "writeQueue" -> Unit
            "publishItem" -> {
                val manifest = Json.decodeFromString<ThreadPageDownloadManifest>((args[2] as ByteArray).decodeToString())
                (args[4] as? Path)?.let { fs.copy(it, directory / checkNotNull(manifest.archiveId)) }
                storedManifest = manifest
                storedBytes = args[3] as? ByteArray
                Unit
            }
            "openArchive" -> (args[1] as String).takeIf { it.isNotEmpty() }?.let { directory / it }?.takeIf(fs::exists)?.let { path ->
                val data = fs.read(path) { readByteArray() }
                object : SeekableSource {
                    override val size = data.size.toLong()
                    override fun read(position: Long, into: ByteArray, offset: Int, length: Int): Int {
                        if (position >= size) return -1
                        val count = minOf(length, data.size - position.toInt())
                        data.copyInto(into, offset, position.toInt(), position.toInt() + count)
                        return count
                    }
                    override fun close() = Unit
                }
            }
            else -> error("unexpected storage operation: $name")
        } }
        val thread = mock<ThreadRepository> { name, _ -> when (name) {
            "fetchThread" -> YamiboResult.Success(body)
            "setCachedThread", "getCachedThread" -> null
            else -> error("unexpected thread operation: $name")
        } }
        val fetcher = object : DownloadImageFetcher({ "" }) {
            override suspend fun fetch(url: String): ByteArray {
                synchronized(requests) { requests += url }
                if (url == unavailable) error("HTTP 503")
                return binaries[url] ?: url.encodeToByteArray()
            }
        }
        return DownloadRepositoryImpl(thread, storageProvider = storage, imageFetcher = fetcher,
            cacheDirectory = directory / "cache", database = Database(driver), accountId = { owner })
    }

    private suspend fun refresh(repo: DownloadRepositoryImpl) = repo.refreshPage(ThreadId(140), body.thread.title, UserId(7), 1)

    @Test fun firstPartialIsReadableAndRetryOnlyTransfersItsMissingImage() = runBlocking {
        val repo = repository()
        assertIs<YamiboResult.Failure>(refresh(repo))
        val partial = repo.queue.value.single()
        assertEquals(DownloadStatus.Failed, partial.status)
        assertEquals(1 to 2, partial.progressCurrent to partial.progressTotal)
        assertEquals(1, repo.getManifest(key)!!.missingImageCount)
        val local = assertNotNull(repo.getDownloadedPage(key))
        assertTrue(local.posts.single().images.first().url.startsWith(ArchivedImage.PREFIX))
        assertTrue(MissingThreadImage.isReaderUri(local.posts.single().images.last().url))
        requests.clear()
        unavailable = null
        assertIs<YamiboResult.Success<ThreadPage>>(refresh(repo))
        assertEquals(listOf(second), requests)
        assertEquals(0, repo.getManifest(key)!!.missingImageCount)
        assertEquals(DownloadStatus.Downloaded, repo.getStatus(key))
        requests.clear()
        assertIs<YamiboResult.Success<ThreadPage>>(refresh(repo))
        assertTrue(requests.isEmpty())
    }

    @Test fun failedRefreshPreservesPreviousBodyAndArchiveGeneration() = runBlocking {
        body = page("original", first)
        val repo = repository()
        assertIs<YamiboResult.Success<ThreadPage>>(refresh(repo))
        val old = assertNotNull(repo.getManifest(key))
        body = page("new revision", first, second)
        assertIs<YamiboResult.Failure>(refresh(repo))
        assertEquals(old, repo.getManifest(key))
        assertEquals("original", repo.getDownloadedPage(key)?.thread?.title)
        assertTrue(repo.queue.value.single().refreshSource)
        owner = 8
        assertNull(repo.getDownloadedPage(key))
        assertEquals(DownloadStatus.NotDownloaded, repo.getStatus(key))
    }

    @Test fun openingTargetReaderAvoidsTenThousandUnrelatedDownloads() = runBlocking {
        librarySize = 10_000
        storedBytes = Json.encodeToString(page("target")).encodeToByteArray()
        storedManifest = ThreadPageDownloadManifest(key = key, title = "target", downloadedAt = 1,
            sourceTotalPages = 1, pageKind = DownloadPageKind.LastAtDownloadTime, owner = owner)
        val repo = repository()
        assertEquals(DownloadStatus.Downloaded, repo.getStatus(key))
        assertEquals("target", repo.getDownloadedPage(key)?.thread?.title)
        assertEquals(1, pageOpens)
        assertEquals(0, scans)
        assertEquals(10_001, repo.getDownloadedContentGroups().sumOf { it.itemCount })
        assertEquals(1, scans)
    }

    private fun seedCbz(corrupt: Boolean = false): String {
        unavailable = null
        body = page("legacy CBZ", first, second, first)
        storedBytes = Json.encodeToString(body).encodeToByteArray()
        val images = listOf(first, second).mapIndexed { i, url ->
            DownloadedImage(url, "$i.jpg", url.encodeToByteArray().size.toLong(), packed = true)
        }
        storedManifest = ThreadPageDownloadManifest(key, "legacy CBZ", 10, 1,
            DownloadPageKind.LastAtDownloadTime, images = images, archiveId = "old.cbz", owner = owner)
        ZipOutputStream((directory / "old.cbz").toFile().outputStream()).use { zip ->
            for (img in images) {
                zip.putNextEntry(ZipEntry(img.fileName))
                zip.write(if (corrupt && img == images.last()) byteArrayOf(9) else img.sourceUrl.encodeToByteArray())
                zip.closeEntry()
            }
        }
        return images.first().fileName
    }

    @Test fun offlineCbzMigrationKeepsOldUrisAndIsIdempotent() = runBlocking {
        seedCbz()
        val repo = repository()
        val oldPage = assertNotNull(repo.getDownloadedPage(key))
        val oldUri = oldPage.posts.single().images.first().url
        assertTrue(repo.migrateImages { _, _ -> }.isEmpty())
        assertNull(storedManifest)
        assertTrue(assertNotNull(repo.getManifest(key)).shared)
        assertEquals(first, repo.openArchivedImage(oldUri).buffer().use { it.readUtf8() })
        val after = assertNotNull(repo.getDownloadedPage(key)).posts.single().images
        assertEquals(3, after.size)
        assertEquals(after.first().url, after.last().url)
        assertEquals(first, repo.openArchivedImage(after.first().url).buffer().use { it.readUtf8() })
        val sizes = repo.getDownloadedContentSummary()
        assertEquals(2, sizes.imageCount)
        assertEquals((first.length + second.length).toLong(), sizes.imageBytes)
        assertEquals(emptyMap(), repo.migrateImages { _, _ -> })
        assertTrue(requests.isEmpty())
    }

    @Test fun damagedCbzKeepsItsSourceAndRetriesOffline() = runBlocking {
        seedCbz(corrupt = true)
        val repo = repository()
        assertNotNull(repo.migrateImages { _, _ -> }[key])
        assertNotNull(storedManifest)
        assertFalse(assertNotNull(repo.getManifest(key)).shared)
        seedCbz()
        assertTrue(repo.migrateImages { _, _ -> }.isEmpty())
        assertTrue(requests.isEmpty())
    }

    @Test fun optInRemovesDamagedArchiveAndKeepsReadableImagesBodyAndMissingPositions() = runBlocking {
        seedCbz(corrupt = true)
        val repo = repository()
        var removed = 0
        assertTrue(repo.migrateImages(true, { removed++ }) { _, _ -> }.isEmpty())
        assertEquals(1, removed)
        assertNull(storedManifest)
        val saved = assertNotNull(repo.getManifest(key))
        assertEquals(1, saved.missingImageCount)
        val post = assertNotNull(repo.getDownloadedPage(key)).posts.single()
        assertEquals(body.posts.single().pid, post.pid)
        assertEquals(first, repo.openArchivedImage(post.images.first().url).buffer().use { it.readUtf8() })
        assertEquals(second, MissingThreadImage.fromReaderUri(post.images[1].url)?.sourceUrl)
        assertEquals(post.images.first().url, post.images.last().url)
        assertTrue(repo.migrateImages(true, { removed++ }) { _, _ -> }.isEmpty())
        assertEquals(1, removed)
        assertTrue(requests.isEmpty())
    }

    @Test fun optInHandlesUnreadableZipDirectoryButKeepsMissingFileAndForeignMembers() = runBlocking {
        seedCbz()
        val archive = directory / checkNotNull(storedManifest?.archiveId)
        val original = fs.read(archive) { readByteArray() }
        val repo = repository()
        var removed = 0
        fs.delete(archive)
        assertNotNull(repo.migrateImages(true, { removed++ }) { _, _ -> }[key])
        fs.write(archive) { write(original.copyOf(8)) }
        assertNotNull(repo.migrateImages { _, _ -> }[key])
        assertNotNull(storedManifest)
        ZipOutputStream(java.nio.file.Files.newOutputStream(java.nio.file.Paths.get(archive.toString()))).use { zip ->
            zip.putNextEntry(ZipEntry("personal.png"))
            zip.write(byteArrayOf(1))
            zip.closeEntry()
        }
        assertNotNull(repo.migrateImages(true, { removed++ }) { _, _ -> }[key])
        assertEquals(0, removed)
        fs.write(archive) { write(original.copyOf(8)) }
        assertTrue(repo.migrateImages(true, { removed++ }) { _, _ -> }.isEmpty())
        assertEquals(1, removed)
        assertEquals(2, repo.getManifest(key)?.missingImageCount)
        assertEquals(body.posts.single().pid, repo.getDownloadedPage(key)?.posts?.single()?.pid)
        assertTrue(requests.isEmpty())
    }

    @Test fun threadAndRssShareOnePidAndImageFilesAfterRemovingOneClaim() = runBlocking {
        unavailable = null
        body = page("shared", first, second, first)
        val repo = repository()
        assertIs<YamiboResult.Success<ThreadPage>>(refresh(repo))
        requests.clear()
        val rss = RssMangaChapterDownloadKey(9, 140, 7)
        assertIs<YamiboResult.Success<*>>(repo.refreshRssMangaChapter(rss))
        assertTrue(requests.isEmpty())
        val groups = repo.getDownloadedContentGroups(true)
        assertEquals(1, groups.size)
        assertEquals(1, groups.single().items.size)
        assertEquals(setOf(key, rss), groups.single().items.single().sourceKeys.toSet())
        val images = directory / "library/7/images"
        assertEquals(2, fs.list(images).sumOf { fs.list(it).size })
        val packs = fs.list(directory / "library/7/threads/140").filter { it.name.endsWith(".zip") }
        assertEquals(1, packs.size)
        java.util.zip.ZipFile(packs.single().toFile()).use { zip -> assertEquals(listOf("1402.json"), zip.entries().toList().map { it.name }) }
        assertEquals(3, assertNotNull(repo.getRssMangaChapterImages(rss)).size)
        assertTrue(repo.clearPage(key).isSuccess)
        val uri = assertNotNull(repo.getRssMangaChapterImages(rss)).first()
        assertEquals(first, repo.openArchivedImage(uri).buffer().use { it.readUtf8() })
        assertTrue(repo.clearRssMangaChapter(rss).isSuccess)
        assertEquals(0, fs.list(images).sumOf { fs.list(it).size })
        assertTrue(requests.isEmpty())
    }

    @Test fun imageOnlyTagCbzKeepsItsEntryAndOriginalOrder() = runBlocking {
        seedCbz()
        val tag = TagMangaChapterDownloadKey(42, 140, 7)
        imageOnly = TagMangaChapterManifest(tag, "legacy", title = "images only", tagPage = 1,
            imageCount = 3, downloadedAt = 2, images = storedManifest!!.images, archiveId = "old.cbz",
            sourceOrder = listOf(first, second, first))
        storedBytes = null
        storedManifest = null
        val repo = repository()
        val old = assertNotNull(repo.getTagMangaChapterImages(tag))
        assertTrue(repo.migrateImages { _, _ -> }.isEmpty())
        assertNull(imageOnly)
        assertNull(repo.getDownloadedPage(key))
        val images = assertNotNull(repo.getTagMangaChapterImages(tag))
        assertEquals(images.first(), images.last())
        assertEquals(3, images.size)
        assertEquals(second, repo.openArchivedImage(old[1]).buffer().use { it.readUtf8() })
        assertTrue(requests.isEmpty())
    }

    @Test fun looseMigrationRemapsPreciseAnchorAndUsesNoNetwork() = runBlocking {
        seedCbz()
        val old = storedManifest!!
        storedManifest = old.copy(archiveId = null, images = old.images.map { it.copy(packed = false) })
        fs.delete(directory / "old.cbz")
        for (image in old.images) {
            val path = directory / image.fileName
            fs.write(path) { writeUtf8(image.sourceUrl) }
            loose[image.fileName] = path
        }
        val oldId = loose.getValue("0.jpg").toString().encodeUtf8().sha256().hex()
        val repo = repository()
        Database(driver).imageReadingHistoryQueries.upsert(1402, 140, 2, 3, 2, 4, 1234)
        Database(driver).imageReadingHistoryQueries.saveAnchor(1402, 1234, 1402, oldId, 1, 1, 7)
        assertTrue(repo.migrateImages { _, _ -> }.isEmpty())
        val anchor = Database(driver).imageReadingHistoryQueries.getAnchor(1402, 1234).executeAsOne()
        assertEquals(me.thenano.yamibo.yamibo_app.repository.thread.readerImageSourceId(first), anchor.sourceId)
        assertEquals(1L, anchor.occurrence)
        assertTrue(requests.isEmpty())
    }

    @Test fun identicalBytesFromUnknownUrlsLeaveOnePhysicalFileAndDistinctPositions() = runBlocking {
        unavailable = null
        binaries[second] = first.encodeToByteArray()
        val repo = repository()
        assertIs<YamiboResult.Success<*>>(refresh(repo))
        val result = repo.getDownloadedPage(key)!!.posts.single().images.map { it.url }
        assertNotEquals(result[0], result[1])
        assertEquals(first, repo.openArchivedImage(result[1]).buffer().use { it.readUtf8() })
        assertEquals(1, repo.getDownloadedContentSummary().imageCount)
        assertEquals(first.length.toLong(), repo.getDownloadedContentSummary().imageBytes)
        val blobs = directory / "library/7/images"
        assertEquals(1, fs.list(blobs).sumOf { fs.list(it).size })
    }

    @Test fun refreshedPostAndImageOrderReachRssWithoutOverwritingWithOlderEdit() = runBlocking {
        unavailable = null
        val repo = repository()
        val rss = RssMangaChapterDownloadKey(9, 140, 7)
        assertIs<YamiboResult.Success<*>>(repo.refreshRssMangaChapter(rss))
        val newBody = page("corrected", second, second, first)
        body = newBody.copy(posts = newBody.posts.map { it.copy(lastEditedTime = TimeInfo("edited", null, 100)) })
        assertIs<YamiboResult.Success<*>>(refresh(repo))
        val after = repo.getRssMangaChapterImages(rss)!!
        assertEquals(3, after.size)
        assertEquals(after[0], after[1])
        assertEquals(second, repo.openArchivedImage(after[0]).buffer().use { it.readUtf8() })
        body = page("stale", first)
        assertIs<YamiboResult.Failure>(repo.refreshRssMangaChapter(rss))
        assertEquals("corrected", repo.getDownloadedPage(key)!!.thread.title)
        assertEquals(3, repo.getRssMangaChapterImages(rss)!!.size)
    }

    @Test fun storedCoverFollowsReplacedBytesAndLegacySourceSurvivesRemovalOfOneClaim() = runBlocking {
        unavailable = null
        seedCbz()
        val repo = repository()
        val oldLink = assertNotNull(repo.getDownloadedPage(key)).posts.single().images.first().url
        assertTrue(repo.migrateImages { _, _ -> }.isEmpty())
        val direct = assertNotNull(repo.getDownloadedPage(key)).posts.single().images.first().url
        val rss = RssMangaChapterDownloadKey(9, 140, 7)
        assertIs<YamiboResult.Success<*>>(repo.refreshRssMangaChapter(rss))
        binaries[first] = "revised".encodeToByteArray()
        body = body.copy(posts = body.posts.map { it.copy(lastEditedTime = TimeInfo("edited", null, 100)) })
        assertIs<YamiboResult.Success<*>>(refresh(repo))
        assertEquals("revised", repo.openArchivedImage(direct).buffer().use { it.readUtf8() })
        repo.clearPage(key).getOrThrow()
        assertEquals("revised", repo.openArchivedImage(oldLink).buffer().use { it.readUtf8() })
        owner = 8
        assertFails { repo.openArchivedImage(direct) }
        assertFails { repo.openArchivedImage(oldLink) }
        Unit
    }

    @Test fun rebuildAfterInterruptedPublicationRemovesStalePidsAndRestoresAliases() = runBlocking {
        seedCbz()
        val repo = repository()
        assertTrue(repo.migrateImages { _, _ -> }.isEmpty())
        val index = Database(driver).readerDownloadQueries
        index.clearAliases("fixture", owner.toLong())
        index.putPage("fixture", "7/stale", 7, 999, 7, 9, "{}", "old")
        index.putFloor("fixture", "7/stale", 9999)
        index.markLibraryDirty("fixture", 7, 999)
        repo.getDownloadedContentGroups()
        assertNull(index.pageForPid("fixture", 7, 999, 7, 9999).executeAsOneOrNull())
        assertNotNull(index.imageAlias("fixture", 7, first).executeAsOneOrNull())
        assertTrue(index.dirtyLibraryThreads("fixture", 7).executeAsList().isEmpty())
        assertEquals(1L, index.pageForPid("fixture", 7, 140, 7, 1402).executeAsOneOrNull())
        assertTrue(requests.isEmpty())
    }

    @Test fun readerRetryRetiresOnlyTheDamagedCbzBeforeFetchAndCanRetryAnOldUriAfterFailure() = runBlocking {
        seedCbz(corrupt = true)
        val repo = repository()
        val urls = assertNotNull(repo.getDownloadedPage(key)).posts.single().images.map { it.url }
        val other = checkNotNull(storedManifest)
        imageOnly = TagMangaChapterManifest(TagMangaChapterDownloadKey(5, 241, 7), "tag", title = "unrelated",
            tagPage = 1, imageCount = other.images.size, downloadedAt = 1, images = other.images, archiveId = other.archiveId)
        owner = 8
        assertTrue(repo.retryArchivedImage(urls[1]).isFailure)
        assertNotNull(storedManifest)
        owner = 7
        unavailable = second
        assertTrue(repo.retryArchivedImage(urls[1]).isFailure) // HTTP 503 after retiring the CBZ
        assertNull(storedManifest)
        assertNotNull(imageOnly)
        assertEquals(listOf(second), requests)
        assertEquals(1, repo.getManifest(key)?.missingImageCount)
        assertEquals(first, repo.openArchivedImage(urls.first()).buffer().use { it.readUtf8() })
        unavailable = null
        val fixed = repo.retryArchivedImage(urls[1]).getOrThrow()
        assertEquals(second, repo.openArchivedImage(fixed).buffer().use { it.readUtf8() })
        assertEquals(0, repo.getManifest(key)?.missingImageCount)
        assertEquals(listOf(second, second), requests)
        assertTrue(repo.getDownloadedPage(key)!!.posts.single().images.none { MissingThreadImage.isReaderUri(it.url) })
    }

    @AfterTest fun cleanUp() {
        driver.close()
        fs.deleteRecursively(directory)
    }
}
