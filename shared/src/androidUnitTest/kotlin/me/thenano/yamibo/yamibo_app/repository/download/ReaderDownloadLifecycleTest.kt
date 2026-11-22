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
            "isReady" -> true
            "listManifests" -> {
                scans++
                List(librarySize) { index -> ThreadPageDownloadManifest(
                    key = ThreadPageDownloadKey(5000 + index, 1), title = "other", downloadedAt = 1,
                    sourceTotalPages = 1, pageKind = DownloadPageKind.LastAtDownloadTime) } + listOfNotNull(storedManifest)
            }
            "listTagMangaManifests", "listRssMangaManifests", "readQueue" -> emptyList<Any>()
            "readThreadPage" -> storedBytes
            "readPageRecord" -> {
                pageOpens++
                assertEquals(key, args[0])
                storedBytes?.let { it to storedManifest }
            }
            "readManifest" -> storedManifest
            "looseImages" -> emptyMap<String, String>()
            "writeQueue" -> Unit
            "publishItem" -> {
                val manifest = Json.decodeFromString<ThreadPageDownloadManifest>((args[2] as ByteArray).decodeToString())
                (args[4] as? Path)?.let { fs.copy(it, directory / checkNotNull(manifest.archiveId)) }
                storedManifest = manifest
                storedBytes = args[3] as? ByteArray
                Unit
            }
            "openArchive" -> (directory / (args[1] as String)).takeIf(fs::exists)?.let { path ->
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
                return url.encodeToByteArray()
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
        assertEquals(1, storedManifest!!.missingImageCount)
        val local = assertNotNull(repo.getDownloadedPage(key))
        assertTrue(local.posts.single().images.first().url.startsWith(ArchivedImage.PREFIX))
        assertTrue(MissingThreadImage.isReaderUri(local.posts.single().images.last().url))
        requests.clear()
        unavailable = null
        assertIs<YamiboResult.Success<ThreadPage>>(refresh(repo))
        assertEquals(listOf(second), requests)
        assertEquals(0, storedManifest!!.missingImageCount)
        assertEquals(DownloadStatus.Downloaded, repo.getStatus(key))
        requests.clear()
        assertIs<YamiboResult.Success<ThreadPage>>(refresh(repo))
        assertTrue(requests.isEmpty())
    }

    @Test fun failedRefreshPreservesPreviousBodyAndArchiveGeneration() = runBlocking {
        body = page("original", first)
        val repo = repository()
        assertIs<YamiboResult.Success<ThreadPage>>(refresh(repo))
        val old = storedManifest
        body = page("new revision", first, second)
        assertIs<YamiboResult.Failure>(refresh(repo))
        assertEquals(old, storedManifest)
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

    @AfterTest fun cleanUp() {
        driver.close()
        fs.deleteRecursively(directory)
    }
}
