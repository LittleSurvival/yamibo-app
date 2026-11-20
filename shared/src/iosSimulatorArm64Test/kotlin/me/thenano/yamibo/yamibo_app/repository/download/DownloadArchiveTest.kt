package me.thenano.yamibo.yamibo_app.repository.download

import io.github.littlesurvival.core.YamiboResult
import io.github.littlesurvival.dto.page.RatePopoutPage
import io.github.littlesurvival.dto.page.RateResultPopoutPage
import io.github.littlesurvival.dto.page.AddFavoriteResult
import io.github.littlesurvival.dto.page.ThreadPage
import io.github.littlesurvival.dto.page.VotersPopoutScreen
import io.github.littlesurvival.dto.value.FormHash
import io.github.littlesurvival.dto.value.ForumId
import io.github.littlesurvival.dto.value.PollOptionId
import io.github.littlesurvival.dto.value.PostId
import io.github.littlesurvival.dto.value.ThreadId
import io.github.littlesurvival.dto.value.UserId
import kotlin.test.*
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import me.thenano.yamibo.yamibo_app.Database
import me.thenano.yamibo.yamibo_app.db.DatabaseFactory
import me.thenano.yamibo.yamibo_app.repository.ThreadRepository
import me.thenano.yamibo.yamibo_app.repository.settings.AppSettingsRepository
import me.thenano.yamibo.yamibo_app.store.settings.IOSSettingsStore
import okio.ByteString.Companion.decodeBase64
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer
import okio.use
import platform.Foundation.NSTemporaryDirectory

class DownloadArchiveTest {
    private val fs = FileSystem.SYSTEM
    private val folder = NSTemporaryDirectory().toPath() / "cbz-qa-${Uuid.random()}"
    private val settings = AppSettingsRepository(IOSSettingsStore()).apply { backupFolderUri.setValue(folder.toString()) }
    private val store = IOSDownloadStorageProvider(settings)
    private val db = Database(DatabaseFactory().createDriver())
    private val key = ThreadPageDownloadKey(970001, 1)
    private val dir = folder / "YamiboDownloads" / key.stableId
    private val png = checkNotNull("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a7ZkAAAAASUVORK5CYII=".decodeBase64()).toByteArray()
    private val text = """{"thread":{"tid":970001,"title":"CBZ benchmark","forum":{"fid":1,"name":"QA Forum","url":"forum.php?fid=1","description":null,"todayCount":null,"themeCount":null,"rank":null,"iconUrl":null},"categoryTag":null,"totalReplies":null,"totalViews":null,"replyUrl":null},"posts":[{"pid":43,"floor":1,"isPinned":false,"manageButtons":[],"title":"QA MANGA","author":{"uid":7,"name":"QA Author","avatarUrl":null},"timeCreate":{"text":"2026-10-24","specialText":null,"epoch":0},"lastEditedTime":null,"contentHtml":"<img src=\"https://bbs.yamibo.com/qa-pack/0.png\"><img src=\"https://bbs.yamibo.com/qa-pack/1.png\">","images":[{"url":"https://bbs.yamibo.com/qa-pack/0.png","alt":null},{"url":"https://bbs.yamibo.com/qa-pack/1.png","alt":null}],"tags":{"value":[]},"poll":null,"attachments":[],"comments":[],"rateBlock":{"rateParticipatePeople":0,"rateTotalScore":0,"rates":[]}}],"pageNav":{"nextUrl":null,"nextPageIndex":null,"prevUrl":null,"prevPageIndex":null,"currentPage":1,"totalPages":20}}"""
    private val samplePage = Json { ignoreUnknownKeys = true }.decodeFromString<ThreadPage>(text)
    private val urls = samplePage.posts.first().images.map { it.url }
    private var allowSecond = true
    private var fetchCount = 0
    private val threads = object : ThreadRepository {
        override suspend fun fetchThread(
        tid: ThreadId,
        authorId: UserId?,
        page: Int,
        reverse: Boolean,
    ): YamiboResult<ThreadPage> = YamiboResult.Success(samplePage)
        override suspend fun fetchFindPost(
        tid: ThreadId,
        postId: PostId,
        authorId: UserId?,
    ): YamiboResult<ThreadPage> = error("unexpected network request")
        override suspend fun addFavorite(tid: ThreadId, formHash: FormHash): YamiboResult<AddFavoriteResult> = error("unexpected network request")
        override suspend fun votePoll(fId: ForumId, tId: ThreadId, pollOptionIds: List<PollOptionId>, formHash: FormHash): YamiboResult<String> = error("unexpected network request")
        override suspend fun fetchRatePopoutPage(tId: ThreadId, pId: PostId): YamiboResult<RatePopoutPage> = error("unexpected network request")
        override suspend fun fetchRateResults(tId: ThreadId, pId: PostId): YamiboResult<RateResultPopoutPage> = error("unexpected network request")
        override suspend fun fetchVoters(tId: ThreadId, pollOptionId: PollOptionId?, page: Int): YamiboResult<VotersPopoutScreen> = error("unexpected network request")
        override suspend fun ratePost(tId: ThreadId, pId: PostId, score: Int, reason: String, formHash: FormHash, noticeAuthor: Boolean): YamiboResult<String> = error("unexpected network request")
        override suspend fun commentPost(tId: ThreadId, pId: PostId, message: String, formHash: FormHash): YamiboResult<String> = error("unexpected network request")
        override fun getCachedThread(tid: ThreadId, authorId: UserId?, page: Int): ThreadPage? = null
        override fun setCachedThread(tid: ThreadId, authorId: UserId?, page: Int, threadPage: ThreadPage) = Unit
        override fun clearCachedThread(tid: ThreadId) = Unit
    }

    private fun repository() = DownloadRepositoryImpl(threadRepository = threads, storageProvider = store,
        database = db, cacheDirectory = folder / "cache", imageFetcher = object : DownloadImageFetcher({ "" }) {
            override suspend fun fetch(url: String): ByteArray {
                fetchCount++
                check(allowSecond || url != urls.last())
                return png
            }
        })

    private fun seed(item: ThreadPageDownloadKey = key, broken: Boolean = false) {
        val target = folder / "YamiboDownloads" / item.stableId
        fs.createDirectories(target / "images")
        val images = urls.mapIndexed { i, u ->
            val name = "$i.png"
            fs.write(target / "images" / name) { write(png) }
            DownloadedImage(u, name, png.size.toLong() + if (broken) 1 else 0)
        }
        fs.write(target / "manifest.json") { writeUtf8(Json.encodeToString(ThreadPageDownloadManifest(item, "Example", 99, 1, DownloadPageKind.Normal, images=images))) }
        fs.write(target / "thread_page.json") { writeUtf8(text) }
    }

    @Test fun migrateAndReadOfflineAcrossRestart() = runBlocking {
        seed()
        val repo = repository()
        val original = repo.getDownloadedPage(key)!!.posts.first().images.first().url
        db.contentCoverQueries.upsert("cbzqa", 970001, original, original, 0, 1)
        assertTrue(repo.migrateImages { _,_ -> }.isEmpty())
        assertFalse(fs.exists(dir / "images"))
        assertTrue(fs.exists(dir / "images.cbz"))
        val next = repository()
        val cover = db.contentCoverQueries.getByTarget("cbzqa",970001).executeAsOne().manualCoverUrl!!
        assertContentEquals(png, next.openArchivedImage(cover).buffer().use { it.readByteArray() })
        assertEquals(1,next.getDownloadedContentGroups().single().itemCount)
        assertFalse(next.getDownloadedContentGroups().single().items.single().needsMigration)
        assertEquals(0,fetchCount)
    }

    @Test fun failedMigrationAndInterruptedDirectoryStayReadable() = runBlocking {
        seed(broken=true)
        val other = key.copy(authorId=7)
        seed(other)
        fs.atomicMove(dir, dir.parent!! / "${dir.name}.previous")
        val repo = repository()
        val errors = repo.migrateImages { _,_ -> }
        assertEquals(setOf(key), errors.keys)
        assertTrue(repo.getDownloadedPage(key) != null)
        assertTrue(fs.exists(dir.parent!! / "${dir.name}.previous" / "images" / "0.png"))
        assertTrue(repo.clearThread(other).isSuccess)
        assertNull(repo.getDownloadedPage(other))
        assertTrue(repo.getDownloadedPage(key) != null)
    }

    @Test fun manualRepairAddsOnlyTheRequestedImage() = runBlocking {
        allowSecond=false
        val repo=repository()
        assertTrue(repo.refreshPage(ThreadId(key.tid),"QA",null,1) is YamiboResult.Success)
        val before=fs.read(dir / "images.cbz") { readByteString() }
        val post=repo.getDownloadedPage(key)!!.posts.first()
        val missing=assertNotNull(MissingThreadImage.fromReaderUri(post.images.last().url))
        val n=fetchCount
        assertTrue(repo.getDownloadedPage(key) != null)
        assertEquals(n,fetchCount)
        allowSecond=true
        assertTrue(repo.retryImage(missing).isSuccess)
        assertEquals(n+1,fetchCount)
        assertEquals(before,fs.read(dir / "images.cbz"){readByteString()})
        assertFalse(MissingThreadImage.isReaderUri(repo.getDownloadedPage(key)!!.posts.first().images.last().url))
    }
}
