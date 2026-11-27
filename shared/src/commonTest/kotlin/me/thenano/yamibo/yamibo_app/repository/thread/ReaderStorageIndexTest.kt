package me.thenano.yamibo.yamibo_app.repository.thread

import app.cash.sqldelight.db.SqlDriver
import me.thenano.yamibo.yamibo_app.Database
import io.github.littlesurvival.dto.model.*
import io.github.littlesurvival.dto.page.*
import io.github.littlesurvival.dto.value.*
import kotlinx.serialization.json.*
import me.thenano.yamibo.yamibo_app.repository.download.*
import no.synth.kmpzip.io.SeekableSource
import okio.*
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import kotlin.time.TimeSource
import kotlin.test.*

internal expect fun readerDatabaseDriver(): SqlDriver

// Both platforms use the same posts and their actual native provider.
internal suspend fun runNativeStorageSample(database: Database, cache: Path, platform: String,
                                            provider: () -> DownloadStorageProvider): JsonObject {
    val clock = TimeSource.Monotonic
    val json = Json { ignoreUnknownKeys = true }
    val owner = 7
    val tid = 874311
    val pageCount = 12
    val perPage = 20
    val png = checkNotNull("iVBORw0KGgoAAAANSUhEUgAAAUAAAAIACAAAAADWS+qHAAAEH0lEQVR4nO3SoREAMAzEsKb7D90hDAIq8QT4fs5Q3HSNgJUFRgJGAkYCRgJGAkYCRgJGAkYCRgJGAkYCRgJGAkYCRgJGAkYCRgJGAkYCRgJGAkYCRgJGAkYCRgJGAkYCRgJGAkYCRgJGAkYCRgJGAkYCRgJGAkYCRgJGAkYCRgJGAkYCRgJGAkYCRgJGAkYCRgJGAkYCRgJGAkYCRgJGAkYCRgJGAkYCRgJGAkYCRgJGAkYCRgJGAkYCRgJGAkYCRgJGAkYCRgJGAkYCRgJGAkYCRgJGAkYCRgJGAkYCRgJGAkYCRgJGAkYCRgJGAkYCRgJGAkYCRgJGAkYCRgJGAkYCRgJGAkYCRgJGAkYCRgJGAkYCRgJGAkYCRgJGAkYCRgJGAkYCRgJGAkYCRgJGAkYCRgJGAkYCRgJGAkYCRgJGAkYCRgJGAkYCRgJGAkYCRgJGAkYCRgJGAkYCRgJGAkYCRgJGAkYCRgJGAkYCRgJGAkYCRgJGAkYCRgJGAkYCRgJGAkYCRgJGAkYCRgJGAkYCRgJGAkYCRgJGAkYCRgJGAkYCRgJGAkYCRgJGAkYCRgJGAgq4ywIjASMBIwEjASMBIwEjASMBIwEjASMBIwEjASMBIwEjASMBIwEjASMBIwEjASMBIwEjASMBIwEjASMBIwEjASMBIwEjASMBIwEjASMBIwEjASMBIwEjASMBIwEjASMBIwEjASMBq5M//M0CIwEjASMBIwEjASMBIwEjASMBIwEjASMBIwEjASMBIwEjASMBIwEjASMBIwEjASMBIwEjASMBIwEjASMBIwEjASMBIwEjASMBIwEjASMBIwEjASMBIwEjASMBIwEjASMBBdxlgZGAkYCRgJGAkYCRgJGAkYCRgJGAkYCRgJGAkYCRgJGAkYCRgJGAkYCRgJGAkYCRgJGAkYCRgJGAkYCRgJGAkYCRgJGAkYCRgJGAkYCRgJGAkYCRgJGAkYCRgJGAkYCRgJGAkYCRgJGAkYCRgJGAkYCRgJGAkYCRgJGAkYCRgJGAkYCRgJGAkYCRgJGAkYCRgJGAkYCRgJGAkYCRgJGAkYCRgJGAkYCRgJGAkYCRgJGAkYCRgJGAkYCRgJGAkYCRgJGAkYCRgJGAAu6ywEjASMBIwEjASMBIwEjASMBIwEjASMBIwEjASMBIwEjASMBIwEjASMBIwEjASMBIwEjASMBIwEjASMBIwEjASMBIwEjASMBIwEjASMBIwEjASMBIwEjASMBIwEjASMBIwEjASMBIwEjASMBIwEjASMBIwEjASMBIwEjASMBIwEjASMBIwEjASMBIwEjASMBIwEjASMBIwEjASMBIwEjASMBIwEjASMBIwEjASMBIwEjASMBIwEjASMBIwEjASMBIwEjASMBIwEhAAXdZ4DQP0VKEGwQI/AIAAAAASUVORK5CYII=".decodeBase64()).toByteArray()
    val digest = png.toByteString().sha256().hex()
    check(digest == "0e92708f9b5063336c4036998ab625845bcf983de1cc212d5bb3851df8d0fbc4")
    val urls = listOf("https://fixture.invalid/a.png", "https://fixture.invalid/b.png")
    fun text(pid: Int): String = buildString {
        var state = pid
        repeat(1024) {
            state = state * 1664525 + 1013904223
            append(('a'.code + (state ushr 27) % 26).toChar())
        }
    }
    val pages = (1..pageCount).map { page ->
        val posts = (1..perPage).map { offset ->
            val floor = (page - 1) * perPage + offset
            val pid = 100000 + floor
            val url = urls[(floor - 1) % urls.size]
            Post(pid = PostId(pid), floor = floor, title = "", author = User(UserId(owner), "QA"),
                timeCreate = TimeInfo("", null, 0), contentHtml = "<p>" + text(pid) + "</p><img src='$url'>",
                images = listOf(PostImage(url, "")), tags = Tags(), poll = null)
        }
        ThreadPage(thread = ThreadInfo(ThreadId(tid), "Storage fixture", ForumSummary(ForumId(30), "QA", "")), posts = posts)
    }
    val encoded = pages.map { json.encodeToString(it) }
    val pagePayload = encoded.sumOf { it.encodeToByteArray().size.toLong() }
    val fixtureHash = (encoded.joinToString("\n") + digest).encodeToByteArray().toByteString().sha256().hex()
    val writes = mutableListOf<Pair<String, Long>>()
    val opens = mutableListOf<String>()
    fun construct(): Pair<OfflineLibrary, DownloadStorageProvider> {
        val delegate = provider()
        val real = delegate.offlineFiles
        val metered = object : OfflineFiles by real {
            override fun open(storage: String, path: String): Source? {
                opens.add(path)
                return real.open(storage, path)
            }
            override fun seek(storage: String, path: String): SeekableSource? {
                opens.add(path)
                return real.seek(storage, path)
            }
            override fun install(storage: String, path: String, checksum: String, source: () -> Source) {
                var total = 0L
                real.install(storage, path, checksum) {
                    object : ForwardingSource(source()) {
                        override fun read(sink: Buffer, byteCount: Long): Long {
                            val read = super.read(sink, byteCount)
                            if (read > 0) total += read
                            return read
                        }
                    }
                }
                writes += path to total
            }
        }
        val measured = object : DownloadStorageProvider by delegate { override val offlineFiles = metered }
        return OfflineLibrary(measured, database, cache, json) { owner } to delegate
    }
    fun installed(files: List<Pair<String, Long>>) = buildJsonArray {
        files.forEach { (path, size) -> add(buildJsonObject { put("path", path); put("bytes", size) }) }
    }
    val (library, native) = construct()
    check(native.isReady())
    val storage = checkNotNull(native.getStorageKey())
    check(native.offlineFiles.names(storage, "$owner/threads").isEmpty())
    val begin = clock.markNow()
    val images = urls.map { library.storeImage(storage, owner, it, png) }
    check(images.map { it.digest }.toSet() == setOf(digest))
    suspend fun save(index: Int, page: ThreadPage, stamp: Long) {
        val key = ThreadPageDownloadKey(tid, index + 1)
        val meta = ThreadPageDownloadManifest(key, "Storage fixture", stamp, pageCount,
            if (index == pages.lastIndex) DownloadPageKind.LastAtDownloadTime else DownloadPageKind.Normal,
            images = images, shared = true, owner = owner)
        library.save(storage, meta, page, observedAt = stamp)
    }
    pages.forEachIndexed { index, page -> save(index, page, 1000L + index) }
    val firstWriteNs = begin.elapsedNow().inWholeNanoseconds
    val initial = writes.toList()
    val files = native.offlineFiles
    val blobs = files.names(storage, "$owner/images").flatMap { files.names(storage, "$owner/images/$it") }
    check(blobs == listOf(digest))
    check(initial.all { it.second > 0 })
    check(initial.count { it.first.contains("/images/") } == 1)
    check(initial.count { it.first.endsWith(".zip") } == pageCount)
    // New provider and new URI lookup cache. The OS page cache may remain warm.
    val (cold, _) = construct()
    opens.clear()
    val firstOpen = clock.markNow()
    val stream = checkNotNull(cold.openImage(storage, owner, digest))
    val handleNs = firstOpen.elapsedNow().inWholeNanoseconds
    val read = stream.buffer().use { it.readByteArray() }
    val readNs = firstOpen.elapsedNow().inWholeNanoseconds
    val imageOpens = opens.toList()
    check(imageOpens.size == 1 && imageOpens.single().endsWith("/$digest") && read.contentEquals(png))
    val middle = pageCount / 2
    val initialPage = pages[middle]
    var expectedPage = initialPage
    val edits = buildJsonArray {
        repeat(5) { edit ->
            val posts = initialPage.posts.toMutableList()
            val at = perPage / 2
            posts[at] = posts[at].copy(contentHtml = posts[at].contentHtml + "<p>EDIT-$edit</p>")
            expectedPage = initialPage.copy(posts = posts)
            writes.clear()
            val start = clock.markNow()
            save(middle, expectedPage, 2000L + edit)
            val elapsed = start.elapsedNow().inWholeNanoseconds
            check(writes.count { it.first.endsWith(".zip") } == 1 && writes.none { it.first.contains("/images/") })
            add(buildJsonObject { put("elapsed_ns", elapsed); put("installed", installed(writes)) })
        }
    }
    val (reopened, _) = construct()
    val bodies = pages.mapIndexed { index, page ->
        val (actual, manifest) = checkNotNull(reopened.covered(ThreadPageDownloadKey(tid, index + 1)))
        check(actual == (if (index == middle) expectedPage else page))
        check(manifest.images.map { it.digest } == listOf(digest, digest))
        json.encodeToString(actual).encodeToByteArray().toByteString().sha256().hex()
    }
    return buildJsonObject {
        put("schema", 1); put("platform", platform); put("fixture_sha256", fixtureHash)
        put("thread", tid); put("pages", pageCount); put("floors", pageCount * perPage)
        put("input_page_json_bytes", pagePayload); put("image_bytes", png.size)
        put("distinct_image_files", blobs.size); put("image_references", pageCount * perPage)
        put("image_sha256", digest); put("first_write_ns", firstWriteNs)
        put("initial_installs", installed(initial)); put("initial_installed_bytes", initial.sumOf { it.second })
        put("initial_final_files_bytes", initial.toMap().values.sum())
        put("payload_bytes", pagePayload + png.size)
        put("single_floor_edits", edits); put("first_image_open_ns", handleNs); put("first_image_through_read_ns", readNs)
        put("first_image_open_paths", JsonArray(imageOpens.map { JsonPrimitive(it) }))
        put("final_page_hashes", JsonArray(bodies.map { JsonPrimitive(it) }))
    }
}

class ReaderStorageIndexTest {
    private val driver = readerDatabaseDriver()
    private val db = Database(driver)
    @AfterTest fun close() { driver.close() }

    @Test fun copiesAreIsolatedByAccountStorageAndPagination() {
        val pages = db.readerDownloadQueries
        pages.putPage("disk-A","rss",7,140,7,1,"rss-item","generation-A")
        pages.putFloor("disk-A","rss",1402)
        pages.putPage("disk-A","full",7,140,0,2,"thread-item","generation-B")
        pages.putFloor("disk-A","full",1402)
        assertEquals(1L,pages.pageForPid("disk-A",7,140,7,1402).executeAsOne())
        assertEquals(2L,pages.pageForPid("disk-A",7,140,0,1402).executeAsOne())
        assertNull(pages.pageForPid("disk-A",8,140,7,1402).executeAsOneOrNull())
        assertNull(pages.pageForPid("disk-B",7,140,7,1402).executeAsOneOrNull())
        assertEquals("rss-item",pages.pageCopies("disk-A",7,140,7,1).executeAsOne().taskKey)
        assertTrue(pages.pageCopies("disk-A",7,140,0,1).executeAsList().isEmpty())
    }

    @Test fun deletingReadingHistoryClearsTheWorkResumeButRetainsMembershipAndDownload() {
        val works = db.smartComicWorkQueries
        val id = works.transactionWithResult {
            works.createWork(7,30,"series")
            works.newId().executeAsOne()
        }
        assertTrue(id > 0, "insert must return the created work on its writer connection")
        works.saveMember(7,30,140,id,"summary",0)
        works.visit(140,9000,id,7)
        works.saveProgress(7,30,140,1,5,6,1,9000)
        db.readerDownloadQueries.putPage("disk","copy",7,140,7,1,"copy","G")
        db.imageReadingHistoryQueries.upsert(1401,140,5,6,null,null,9000)
        db.imageReadingHistoryQueries.saveAnchor(1401,9000,1402,"img",1,1,7)
        db.imageReadingHistoryQueries.deleteAll()
        assertNull(works.workById(id,7).executeAsOne().lastTid)
        assertTrue(works.progress(7,30,listOf(140L)).executeAsList().isEmpty())
        assertNull(db.imageReadingHistoryQueries.getAnchor(1401,9000).executeAsOneOrNull())
        assertEquals(id,works.memberWork(7,30,140).executeAsOne())
        assertEquals("G",db.readerDownloadQueries.pageCopies("disk",7,140,7,1).executeAsOne().generation)
    }
}
