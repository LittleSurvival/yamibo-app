package me.thenano.yamibo.yamibo_app.repository.favorite

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.littlesurvival.YamiboClient
import io.github.littlesurvival.core.YamiboResult
import io.github.littlesurvival.dto.page.FavoriteItem
import io.github.littlesurvival.dto.page.FavoritePage
import io.github.littlesurvival.dto.page.FavoriteType
import io.github.littlesurvival.dto.page.AddFavoriteResult
import io.github.littlesurvival.dto.page.ProfilePage
import io.github.littlesurvival.dto.page.RatePopoutPage
import io.github.littlesurvival.dto.page.RateResultPopoutPage
import io.github.littlesurvival.dto.page.ThreadPage
import io.github.littlesurvival.dto.page.VotersPopoutScreen
import io.github.littlesurvival.dto.value.FavoriteId
import io.github.littlesurvival.dto.value.FormHash
import io.github.littlesurvival.dto.value.ForumId
import io.github.littlesurvival.dto.value.PollOptionId
import io.github.littlesurvival.dto.value.PostId
import io.github.littlesurvival.dto.value.ThreadId
import io.github.littlesurvival.dto.value.UserId
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import me.thenano.yamibo.yamibo_app.Database
import me.thenano.yamibo.yamibo_app.repository.AuthRepository
import me.thenano.yamibo.yamibo_app.repository.FavoriteRepository
import me.thenano.yamibo.yamibo_app.repository.ThreadRepository
import me.thenano.yamibo.yamibo_app.store.auth.CookieStore
import me.thenano.yamibo.yamibo_app.store.auth.UserStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FavoriteSyncRepositoryImplTest {
    @Test
    fun syncingOneFavoriteDoesNotFetchRemoteFavoritePages() = runBlocking {
        val db = inMemoryDatabase()
        val localRepository = FavoriteStoreRepositoryImpl(db)
        localRepository.addNormalThreadFavorite(
            tid = ThreadId(42),
            title = "Favorite",
            coverUrl = null,
            lastUpdatedTime = null,
            forumId = null,
            forumName = null,
        )
        val item = localRepository.getAllFavoriteItems().single()
        val favoriteRepository = CountingFavoriteRepository()
        val threadRepository = CountingThreadRepository()
        val repository = FavoriteSyncRepositoryImpl(
            db = db,
            authRepository = FakeFavoriteSyncAuthRepository(),
            favoriteRepository = favoriteRepository,
            localFavoriteRepository = localRepository,
            threadRepository = threadRepository,
        )

        val result = repository.syncLocalFavoriteItem(item.id)

        assertTrue(result.success)
        assertEquals(1, threadRepository.addFavoriteCalls)
        assertEquals(0, favoriteRepository.fetchFavoritesCalls)
        assertTrue(repository.hasRemoteFavorite(item.id))
        assertEquals(88L, db.favoriteSyncRemoteThreadQueries.getByItemId(item.id).executeAsOne().remoteFavoriteId)
    }

    @Test
    fun syncingOneFavoriteAlwaysSendsAddEvenWhenRemoteIdIsAlreadyStored() = runBlocking {
        val db = inMemoryDatabase()
        val localRepository = FavoriteStoreRepositoryImpl(db)
        localRepository.addNormalThreadFavorite(
            tid = ThreadId(41),
            title = "Favorite",
            coverUrl = null,
            lastUpdatedTime = null,
            forumId = null,
            forumName = null,
        )
        val item = localRepository.getAllFavoriteItems().single()
        db.favoriteSyncRemoteThreadQueries.upsertMapping(
            threadId = 41,
            remoteFavoriteId = 77,
            remoteFavoritedOrder = 77,
            itemId = item.id,
            lastSeenAt = 1,
            lastSyncedAt = 1,
        )
        val threadRepository = CountingThreadRepository()
        val repository = FavoriteSyncRepositoryImpl(
            db = db,
            authRepository = FakeFavoriteSyncAuthRepository(),
            favoriteRepository = CountingFavoriteRepository(),
            localFavoriteRepository = localRepository,
            threadRepository = threadRepository,
        )

        val result = repository.syncLocalFavoriteItem(item.id)

        assertTrue(result.success)
        assertEquals(1, threadRepository.addFavoriteCalls)
        assertEquals(88L, db.favoriteSyncRemoteThreadQueries.getByItemId(item.id).executeAsOne().remoteFavoriteId)
    }

    @Test
    fun failedAddRequestPreservesPreviouslyStoredRemoteId() = runBlocking {
        val db = inMemoryDatabase()
        val localRepository = FavoriteStoreRepositoryImpl(db)
        localRepository.addNormalThreadFavorite(
            tid = ThreadId(40),
            title = "Favorite",
            coverUrl = null,
            lastUpdatedTime = null,
            forumId = null,
            forumName = null,
        )
        val item = localRepository.getAllFavoriteItems().single()
        db.favoriteSyncRemoteThreadQueries.upsertMapping(
            threadId = 40,
            remoteFavoriteId = 76,
            remoteFavoritedOrder = 76,
            itemId = item.id,
            lastSeenAt = 1,
            lastSyncedAt = 1,
        )
        val threadRepository = CountingThreadRepository().apply {
            addFavoriteOperation = { YamiboResult.Failure("failed") }
        }
        val repository = FavoriteSyncRepositoryImpl(
            db = db,
            authRepository = FakeFavoriteSyncAuthRepository(),
            favoriteRepository = CountingFavoriteRepository(),
            localFavoriteRepository = localRepository,
            threadRepository = threadRepository,
        )

        val result = repository.syncLocalFavoriteItem(item.id)

        assertTrue(!result.success)
        assertEquals(1, threadRepository.addFavoriteCalls)
        assertEquals(76L, db.favoriteSyncRemoteThreadQueries.getByItemId(item.id).executeAsOne().remoteFavoriteId)
    }

    @Test
    fun cancellationAfterRequestStartsPreservesRemoteSyncIntent() = runBlocking {
        val db = inMemoryDatabase()
        val localRepository = FavoriteStoreRepositoryImpl(db)
        localRepository.addNormalThreadFavorite(
            tid = ThreadId(43),
            title = "Favorite",
            coverUrl = null,
            lastUpdatedTime = null,
            forumId = null,
            forumName = null,
        )
        val item = localRepository.getAllFavoriteItems().single()
        val requestStarted = CompletableDeferred<Unit>()
        val responseGate = CompletableDeferred<Unit>()
        val threadRepository = CountingThreadRepository().apply {
            addFavoriteOperation = {
                requestStarted.complete(Unit)
                responseGate.await()
                YamiboResult.Success(AddFavoriteResult("ok", FavoriteId(89)))
            }
        }
        val repository = FavoriteSyncRepositoryImpl(
            db = db,
            authRepository = FakeFavoriteSyncAuthRepository(),
            favoriteRepository = CountingFavoriteRepository(),
            localFavoriteRepository = localRepository,
            threadRepository = threadRepository,
        )

        val syncJob = async { repository.syncLocalFavoriteItem(item.id) }
        requestStarted.await()
        syncJob.cancelAndJoin()

        assertTrue(repository.hasRemoteFavorite(item.id))
        assertNull(db.favoriteSyncRemoteThreadQueries.getByItemId(item.id).executeAsOne().remoteFavoriteId)
    }

    @Test
    fun explicitRemoteFailureRemovesRemoteSyncIntent() = runBlocking {
        val db = inMemoryDatabase()
        val localRepository = FavoriteStoreRepositoryImpl(db)
        localRepository.addNormalThreadFavorite(
            tid = ThreadId(44),
            title = "Favorite",
            coverUrl = null,
            lastUpdatedTime = null,
            forumId = null,
            forumName = null,
        )
        val item = localRepository.getAllFavoriteItems().single()
        val threadRepository = CountingThreadRepository().apply {
            addFavoriteOperation = { YamiboResult.Failure("failed") }
        }
        val repository = FavoriteSyncRepositoryImpl(
            db = db,
            authRepository = FakeFavoriteSyncAuthRepository(),
            favoriteRepository = CountingFavoriteRepository(),
            localFavoriteRepository = localRepository,
            threadRepository = threadRepository,
        )

        val result = repository.syncLocalFavoriteItem(item.id)

        assertTrue(!result.success)
        assertTrue(!repository.hasRemoteFavorite(item.id))
    }

    @Test
    fun unresolvedRemoteMappingRetriesAddRequestInsteadOfReportingCachedSuccess() = runBlocking {
        val db = inMemoryDatabase()
        val localRepository = FavoriteStoreRepositoryImpl(db)
        localRepository.addNormalThreadFavorite(
            tid = ThreadId(45),
            title = "Favorite",
            coverUrl = null,
            lastUpdatedTime = null,
            forumId = null,
            forumName = null,
        )
        val item = localRepository.getAllFavoriteItems().single()
        val threadRepository = CountingThreadRepository().apply {
            addFavoriteOperation = {
                if (addFavoriteCalls == 1) {
                    YamiboResult.Success(AddFavoriteResult("ok", null))
                } else {
                    YamiboResult.Success(AddFavoriteResult("ok", FavoriteId(90)))
                }
            }
        }
        val repository = FavoriteSyncRepositoryImpl(
            db = db,
            authRepository = FakeFavoriteSyncAuthRepository(),
            favoriteRepository = CountingFavoriteRepository(),
            localFavoriteRepository = localRepository,
            threadRepository = threadRepository,
        )

        val unresolved = repository.syncLocalFavoriteItem(item.id)
        val retried = repository.syncLocalFavoriteItem(item.id)

        assertTrue(unresolved.success)
        assertEquals("百合會已收藏，但未取得收藏識別碼", unresolved.message)
        assertTrue(retried.success)
        assertEquals(2, threadRepository.addFavoriteCalls)
        assertEquals(90L, db.favoriteSyncRemoteThreadQueries.getByItemId(item.id).executeAsOne().remoteFavoriteId)
    }

    @Test
    fun fullSyncAlignsFetchedFavoriteIdWithMatchingLocalItem() = runBlocking {
        val db = inMemoryDatabase()
        val localRepository = FavoriteStoreRepositoryImpl(db)
        localRepository.ensureDefaults()
        val category = localRepository.getDefaultCategory()
        localRepository.addNormalThreadFavorite(
            tid = ThreadId(49),
            title = "Favorite",
            coverUrl = null,
            lastUpdatedTime = null,
            forumId = null,
            forumName = null,
            categoryIds = listOf(category.id),
        )
        val item = localRepository.getAllFavoriteItems().single()
        db.favoriteSyncRemoteThreadQueries.upsertMapping(
            threadId = 49,
            remoteFavoriteId = null,
            remoteFavoritedOrder = null,
            itemId = item.id,
            lastSeenAt = 1,
            lastSyncedAt = 1,
        )
        val favoriteRepository = CountingFavoriteRepository().apply {
            fetchFavoritesOperation = { _, type, _ ->
                YamiboResult.Success(
                    FavoritePage(
                        type = type,
                        items = listOf(
                            FavoriteItem(
                                name = "Favorite",
                                url = "forum.php?mod=viewthread&tid=49",
                                favId = FavoriteId(490),
                            ),
                        ),
                    ),
                )
            }
        }
        val threadRepository = CountingThreadRepository()
        val repository = FavoriteSyncRepositoryImpl(
            db = db,
            authRepository = FakeFavoriteSyncAuthRepository(),
            favoriteRepository = favoriteRepository,
            localFavoriteRepository = localRepository,
            threadRepository = threadRepository,
        )

        val runId = repository.startRemoteImport(category.id)
        repository.runImport(runId)

        val mapping = db.favoriteSyncRemoteThreadQueries.getByItemId(item.id).executeAsOne()
        assertEquals(490L, mapping.remoteFavoriteId)
        assertEquals(item.id, mapping.itemId)
        assertEquals(0, threadRepository.addFavoriteCalls)
    }

    @Test
    fun cancellationPersistedByAnotherRepositoryStopsImportBeforeNetworkWork() = runBlocking {
        val db = inMemoryDatabase()
        val localRepository = FavoriteStoreRepositoryImpl(db)
        localRepository.ensureDefaults()
        val category = localRepository.getDefaultCategory()
        val favoriteRepository = CountingFavoriteRepository().apply {
            fetchFavoritesOperation = { _, type, _ ->
                YamiboResult.Success(FavoritePage(type = type, items = emptyList()))
            }
        }
        fun repository() = FavoriteSyncRepositoryImpl(
            db = db,
            authRepository = FakeFavoriteSyncAuthRepository(),
            favoriteRepository = favoriteRepository,
            localFavoriteRepository = localRepository,
            threadRepository = CountingThreadRepository(),
        )
        val owner = repository()
        val runId = owner.startRemoteImport(category.id)
        owner.interruptRun(runId)

        val separateWorkerInstance = repository()
        separateWorkerInstance.runImport(runId)

        assertEquals(0, favoriteRepository.fetchFavoritesCalls)
        assertEquals(
            me.thenano.yamibo.yamibo_app.repository.FavoriteSyncRepository.FavoriteSyncStatus.INTERRUPTED,
            separateWorkerInstance.getLatestSnapshot()?.status,
        )
    }

    @Test
    fun requestCompletingAfterCancellationCannotRestoreRunningState() = runBlocking {
        val db = inMemoryDatabase()
        val localRepository = FavoriteStoreRepositoryImpl(db)
        localRepository.ensureDefaults()
        val category = localRepository.getDefaultCategory()
        val requestStarted = CompletableDeferred<Unit>()
        val responseGate = CompletableDeferred<Unit>()
        val favoriteRepository = CountingFavoriteRepository().apply {
            fetchFavoritesOperation = { _, type, _ ->
                YamiboResult.Success(
                    FavoritePage(
                        type = type,
                        items = listOf(
                            FavoriteItem(
                                name = "Remote",
                                url = "forum.php?mod=viewthread&tid=99",
                                favId = FavoriteId(999),
                            ),
                        ),
                    ),
                )
            }
        }
        val threadRepository = CountingThreadRepository().apply {
            fetchThreadOperation = {
                requestStarted.complete(Unit)
                responseGate.await()
                YamiboResult.Failure("cancelled transport")
            }
        }
        val repository = FavoriteSyncRepositoryImpl(
            db = db,
            authRepository = FakeFavoriteSyncAuthRepository(),
            favoriteRepository = favoriteRepository,
            localFavoriteRepository = localRepository,
            threadRepository = threadRepository,
        )
        val runId = repository.startRemoteImport(category.id)
        val work = async { repository.runImport(runId) }
        requestStarted.await()

        repository.interruptRun(runId)
        responseGate.complete(Unit)
        work.await()

        assertEquals(
            me.thenano.yamibo.yamibo_app.repository.FavoriteSyncRepository.FavoriteSyncStatus.INTERRUPTED,
            repository.getLatestSnapshot()?.status,
        )
        assertEquals(0, localRepository.getAllFavoriteItems().size)
    }

    @Test
    fun syncingOneFavoriteDoesNotModifyAnotherFavoriteMapping() = runBlocking {
        val db = inMemoryDatabase()
        val localRepository = FavoriteStoreRepositoryImpl(db)
        listOf(50, 51).forEach { tid ->
            localRepository.addNormalThreadFavorite(
                tid = ThreadId(tid),
                title = "Favorite $tid",
                coverUrl = null,
                lastUpdatedTime = null,
                forumId = null,
                forumName = null,
            )
        }
        val items = localRepository.getAllFavoriteItems()
        val target = items.first { it.targetId == 50L }
        val untouched = items.first { it.targetId == 51L }
        val repository = FavoriteSyncRepositoryImpl(
            db = db,
            authRepository = FakeFavoriteSyncAuthRepository(),
            favoriteRepository = CountingFavoriteRepository(),
            localFavoriteRepository = localRepository,
            threadRepository = CountingThreadRepository(),
        )

        assertTrue(repository.syncLocalFavoriteItem(target.id).success)

        assertEquals(88L, db.favoriteSyncRemoteThreadQueries.getByItemId(target.id).executeAsOne().remoteFavoriteId)
        assertNull(db.favoriteSyncRemoteThreadQueries.getByItemId(untouched.id).executeAsOneOrNull())
    }

    @Test
    fun remoteRemovalUsesStoredResponseIdWithoutFetchingFavoritePages() = runBlocking {
        val db = inMemoryDatabase()
        val localRepository = FavoriteStoreRepositoryImpl(db)
        localRepository.addNormalThreadFavorite(
            tid = ThreadId(45),
            title = "Favorite",
            coverUrl = null,
            lastUpdatedTime = null,
            forumId = null,
            forumName = null,
        )
        val item = localRepository.getAllFavoriteItems().single()
        val remoteRepository = CountingFavoriteRepository()
        val repository = FavoriteSyncRepositoryImpl(
            db = db,
            authRepository = FakeFavoriteSyncAuthRepository(),
            favoriteRepository = remoteRepository,
            localFavoriteRepository = localRepository,
            threadRepository = CountingThreadRepository(),
        )
        assertTrue(repository.syncLocalFavoriteItem(item.id).success)

        val result = repository.removeLocalFavoriteItem(item.id, removeRemote = true)

        assertTrue(result.success)
        assertEquals(0, remoteRepository.fetchFavoritesCalls)
        assertEquals(listOf(FavoriteId(88)), remoteRepository.removedFavoriteIds)
        assertTrue(localRepository.getAllFavoriteItems().isEmpty())
    }

    @Test
    fun unresolvedRemoteIdDoesNotFetchFavoritePagesOrDeleteLocalItem() = runBlocking {
        val db = inMemoryDatabase()
        val localRepository = FavoriteStoreRepositoryImpl(db)
        localRepository.addNormalThreadFavorite(
            tid = ThreadId(46),
            title = "Favorite",
            coverUrl = null,
            lastUpdatedTime = null,
            forumId = null,
            forumName = null,
        )
        val item = localRepository.getAllFavoriteItems().single()
        val remoteRepository = CountingFavoriteRepository()
        val threadRepository = CountingThreadRepository().apply {
            addFavoriteOperation = { YamiboResult.Success(AddFavoriteResult("ok", null)) }
        }
        val repository = FavoriteSyncRepositoryImpl(
            db = db,
            authRepository = FakeFavoriteSyncAuthRepository(),
            favoriteRepository = remoteRepository,
            localFavoriteRepository = localRepository,
            threadRepository = threadRepository,
        )
        assertTrue(repository.syncLocalFavoriteItem(item.id).success)

        val result = repository.removeLocalFavoriteItem(item.id, removeRemote = true)

        assertTrue(!result.success)
        assertEquals(
            "百合會收藏識別碼不可用。請到收藏頁使用「同步百合會收藏」重新對齊識別碼後再試。",
            result.message,
        )
        assertEquals(0, remoteRepository.fetchFavoritesCalls)
        assertTrue(remoteRepository.removedFavoriteIds.isEmpty())
        assertEquals(1, localRepository.getAllFavoriteItems().size)
    }

    private fun inMemoryDatabase(): Database {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        return Database(driver)
    }
}

private class CountingFavoriteRepository : FavoriteRepository {
    var fetchFavoritesCalls = 0
    var fetchFavoritesOperation: suspend (UserId?, FavoriteType, Int) -> YamiboResult<FavoritePage> = { _, _, _ ->
        error("syncLocalFavoriteItem must not fetch remote favorite pages")
    }
    val removedFavoriteIds = mutableListOf<FavoriteId>()

    override suspend fun fetchFavorites(
        userId: UserId?,
        type: FavoriteType,
        page: Int,
    ): YamiboResult<FavoritePage> {
        fetchFavoritesCalls += 1
        return fetchFavoritesOperation(userId, type, page)
    }

    override suspend fun removeFavorite(favoriteId: FavoriteId, formHash: FormHash): YamiboResult<String> {
        removedFavoriteIds += favoriteId
        return YamiboResult.Success("ok")
    }
}

private class CountingThreadRepository : ThreadRepository {
    var addFavoriteCalls = 0
    var addFavoriteOperation: suspend () -> YamiboResult<AddFavoriteResult> = {
        YamiboResult.Success(AddFavoriteResult("ok", FavoriteId(88)))
    }
    var fetchThreadOperation: suspend () -> YamiboResult<ThreadPage> = { error("unused") }

    override suspend fun addFavorite(tid: ThreadId, formHash: FormHash): YamiboResult<AddFavoriteResult> {
        addFavoriteCalls += 1
        return addFavoriteOperation()
    }

    override suspend fun fetchThread(tid: ThreadId, authorId: UserId?, page: Int, reverse: Boolean): YamiboResult<ThreadPage> = fetchThreadOperation()
    override suspend fun fetchFindPost(tid: ThreadId, postId: PostId, authorId: UserId?): YamiboResult<ThreadPage> = error("unused")
    override suspend fun votePoll(fId: ForumId, tId: ThreadId, pollOptionIds: List<PollOptionId>, formHash: FormHash): YamiboResult<String> = error("unused")
    override suspend fun fetchRatePopoutPage(tId: ThreadId, pId: PostId): YamiboResult<RatePopoutPage> = error("unused")
    override suspend fun fetchRateResults(tId: ThreadId, pId: PostId): YamiboResult<RateResultPopoutPage> = error("unused")
    override suspend fun fetchVoters(tId: ThreadId, pollOptionId: PollOptionId?, page: Int): YamiboResult<VotersPopoutScreen> = error("unused")
    override suspend fun ratePost(tId: ThreadId, pId: PostId, score: Int, reason: String, formHash: FormHash, noticeAuthor: Boolean): YamiboResult<String> = error("unused")
    override suspend fun commentPost(tId: ThreadId, pId: PostId, message: String, formHash: FormHash): YamiboResult<String> = error("unused")
    override fun getCachedThread(tid: ThreadId, authorId: UserId?, page: Int): ThreadPage? = null
    override fun setCachedThread(tid: ThreadId, authorId: UserId?, page: Int, threadPage: ThreadPage) = Unit
    override fun clearCachedThread(tid: ThreadId) = Unit
}

private class FakeFavoriteSyncAuthRepository : AuthRepository {
    override val cookieStore: CookieStore = object : CookieStore {
        override fun save(value: String) = Unit
        override fun load(): String? = null
        override fun clear() = Unit
    }
    override val userStore: UserStore = object : UserStore {
        override fun load(): ProfilePage? = UserStore.Preview
        override fun save(userInfo: ProfilePage) = Unit
        override fun clear() = Unit
    }
    override val yamiboClient: YamiboClient = YamiboClient()

    override suspend fun isLoggedIn(): Boolean = true
    override suspend fun fetchStatus(): YamiboResult<Boolean> = YamiboResult.Success(true)
    override suspend fun startLoginDetect(onSuccess: suspend () -> Unit, onTimeOut: () -> Unit) = onSuccess()
    override fun syncCookieFromWebView() = Unit
    override fun currentUser(): ProfilePage? = UserStore.Preview
    override suspend fun logOut() = Unit
}
