package me.thenano.yamibo.yamibo_app.repository.rss

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.littlesurvival.YamiboClient
import io.github.littlesurvival.core.YamiboResult
import io.github.littlesurvival.dto.model.PageNav
import io.github.littlesurvival.dto.model.ThreadSummary
import io.github.littlesurvival.dto.page.HomePage
import io.github.littlesurvival.dto.page.ProfilePage
import io.github.littlesurvival.dto.page.SearchPage
import io.github.littlesurvival.dto.page.ForumPage
import io.github.littlesurvival.dto.value.FormHash
import io.github.littlesurvival.dto.value.ForumId
import io.github.littlesurvival.dto.value.SearchId
import io.github.littlesurvival.dto.value.ThreadId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import me.thenano.yamibo.yamibo_app.Database
import me.thenano.yamibo.yamibo_app.repository.AuthRepository
import me.thenano.yamibo.yamibo_app.repository.ForumRepository
import me.thenano.yamibo.yamibo_app.repository.RssSearchSubscriptionRepository
import me.thenano.yamibo.yamibo_app.repository.appsync.AppSyncMutationRecorder
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.DatabaseSyncDomainMaterializer
import me.thenano.yamibo.yamibo_app.repository.appsync.engine.SqlDelightSyncDomainStateAdapter
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncInstallationState
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncAccountBinding
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperationKind
import me.thenano.yamibo.yamibo_app.store.appsync.SqlDelightAppSyncOperationStore
import me.thenano.yamibo.yamibo_app.store.auth.CookieStore
import me.thenano.yamibo.yamibo_app.store.auth.UserStore
import me.thenano.yamibo.yamibo_app.store.settings.SettingsStore

class RssSearchSubscriptionRepositoryImplTest {
    @Test
    fun searchFailuresAreReportedWithoutRefreshingOrSubmittingAgain() = runBlocking {
        val failures: List<YamiboResult<Nothing>> = listOf(
            YamiboResult.Failure("搜尋過於頻繁"),
            YamiboResult.Failure("[HTTP 200] 請求失敗"),
            YamiboResult.NoPermission("沒有搜尋權限"),
            YamiboResult.NotLoggedIn,
            YamiboResult.Maintenance,
            YamiboResult.WafChallenge(statusCode = 405, url = "https://bbs.yamibo.com/search.php?mod=forum"),
        )
        for (failure in failures) {
            val auth = FakeAuthRepository()
            val forum = FakeForumRepository(nextFailure = failure)
            val repository = RssSearchSubscriptionRepositoryImpl(inMemoryDatabase(), auth, forum)
            val id = createSubscription(repository, SearchPage(query = "app", totalCount = 0, threads = emptyList()))

            val result = repository.refresh(id)

            if (failure is YamiboResult.WafChallenge) {
                assertEquals(failure, result)
            } else {
                assertEquals(failure.message(), assertIs<YamiboResult.Failure>(result).reason)
            }
            assertEquals(1, auth.refreshCalls)
            assertEquals(listOf(FormHash("fresh")), forum.submittedFormHashes)
            assertEquals(emptyList(), forum.pageRequests)
        }
    }

    @Test
    fun refreshAndFirstPageUseRefreshedFormHashEvenWithoutLocalProfile() = runBlocking {
        val db = inMemoryDatabase()
        val auth = FakeAuthRepository().apply { profile = null }
        val page = SearchPage(searchId = SearchId(11), query = "app", totalCount = 1, threads = listOf(thread(1, "Existing")))
        val forum = FakeForumRepository(nextSearch = page)
        val repository = RssSearchSubscriptionRepositoryImpl(db, auth, forum)
        val id = createSubscription(repository, page)

        assertIs<YamiboResult.Success<RssSearchSubscriptionRepository.RefreshSummary>>(repository.refresh(id))
        auth.profile = UserStore.Preview.copy(formHash = FormHash("expired"))
        assertEquals("Existing", repository.getCatalogPage(id, 1)!!.tagPage.threadSummaries.single().title)

        assertEquals(2, auth.refreshCalls)
        assertEquals(listOf(FormHash("fresh"), FormHash("fresh")), forum.submittedFormHashes)
        assertEquals(emptyList(), forum.pageRequests)
    }

    @Test
    fun paginationWithExistingSearchIdDoesNotRefreshOrRequireLocalFormHash() = runBlocking {
        val db = inMemoryDatabase()
        val auth = FakeAuthRepository().apply {
            profile = null
            refreshResult = YamiboResult.Failure("Refresh must not be called")
        }
        val seed = SearchPage(searchId = SearchId(11), query = "app", totalCount = 2, threads = listOf(thread(1, "First")))
        val forum = FakeForumRepository().apply {
            nextPage = seed.copy(threads = listOf(thread(2, "Second")), pageNav = PageNav(currentPage = 2, totalPages = 2))
        }
        val repository = RssSearchSubscriptionRepositoryImpl(db, auth, forum)
        val id = createSubscription(repository, seed)

        assertEquals("Second", repository.getCatalogPage(id, 2)!!.tagPage.threadSummaries.single().title)

        assertEquals(0, auth.refreshCalls)
        assertEquals(emptyList(), forum.submittedFormHashes)
        assertEquals(listOf(SearchId(11) to 2), forum.pageRequests)
    }

    @Test
    fun paginationWithoutSearchIdRefreshesBeforeCreatingSearch() = runBlocking {
        val db = inMemoryDatabase()
        val auth = FakeAuthRepository()
        val seed = SearchPage(query = "app", totalCount = 1, threads = listOf(thread(1, "First")))
        val forum = FakeForumRepository(nextSearch = seed.copy(searchId = SearchId(12))).apply {
            nextPage = seed.copy(searchId = SearchId(12), threads = listOf(thread(2, "Second")), pageNav = PageNav(currentPage = 2, totalPages = 2))
        }
        val repository = RssSearchSubscriptionRepositoryImpl(db, auth, forum)
        val id = createSubscription(repository, seed)

        assertEquals("Second", repository.getCatalogPage(id, 2)!!.tagPage.threadSummaries.single().title)

        assertEquals(1, auth.refreshCalls)
        assertEquals(listOf(FormHash("fresh")), forum.submittedFormHashes)
        assertEquals(listOf(SearchId(12) to 2), forum.pageRequests)
    }

    @Test
    fun profileRefreshFailurePreservesCachedCatalogAndWafChallenge() = runBlocking {
        val db = inMemoryDatabase()
        val auth = FakeAuthRepository().apply { refreshResult = YamiboResult.Failure("offline") }
        val seed = SearchPage(query = "app", totalCount = 1, threads = listOf(thread(1, "Cached")))
        val forum = FakeForumRepository()
        val repository = RssSearchSubscriptionRepositoryImpl(db, auth, forum)
        val id = createSubscription(repository, seed)

        assertEquals("offline", assertIs<YamiboResult.Failure>(repository.refresh(id)).reason)
        assertEquals("Cached", repository.getCatalogPage(id, 1)!!.tagPage.threadSummaries.single().title)
        assertEquals(RssSearchSubscriptionRepository.RefreshStatus.Failed, repository.getSubscription(id)!!.lastRefreshStatus)
        val challenge = YamiboResult.WafChallenge(statusCode = 405, url = "https://bbs.yamibo.com/home.php")
        auth.refreshResult = challenge
        assertEquals(challenge, repository.refresh(id))
        assertEquals("Cached", repository.getCatalogPage(id, 1)!!.tagPage.threadSummaries.single().title)

        assertEquals(4, auth.refreshCalls)
        assertEquals(emptyList(), forum.submittedFormHashes)
        assertEquals(emptyList(), forum.pageRequests)
    }

    @Test
    fun createPersistsSearchPageSeedAndCatalogFetchesDynamically() = runBlocking {
        val db = inMemoryDatabase()
        val forum = FakeForumRepository(
            nextSearch = SearchPage(
                searchId = SearchId(11),
                query = "app",
                totalCount = 2,
                threads = listOf(thread(1, "Old"), thread(2, "New")),
                pageNav = PageNav(currentPage = 1, totalPages = 2),
                forumId = ForumId(10),
            )
        )
        val repository = RssSearchSubscriptionRepositoryImpl(db, FakeAuthRepository(), forum)

        val created = assertIs<YamiboResult.Success<Long>>(
            repository.createFromSearch(
                title = "App RSS",
                query = "app",
                forumId = ForumId(10),
                forumName = "管理版",
                searchPage = SearchPage(
                    searchId = SearchId(10),
                    query = "app",
                    totalCount = 1,
                    threads = listOf(thread(1, "Old")),
                    pageNav = PageNav(currentPage = 1, totalPages = 2),
                    forumId = ForumId(10),
                ),
            )
        ).value

        val seededCatalog = repository.getCachedCatalogPage(created, 1)!!
        assertEquals(1, seededCatalog.totalResults)
        assertEquals(2, seededCatalog.tagPage.pageNav?.totalPages)
        assertEquals(1, db.rssSearchSubscriptionResultQueries.countBySubscription(created).executeAsOne())

        val fetchedCatalog = repository.getCatalogPage(created, 1)!!
        assertEquals(2, fetchedCatalog.totalResults)
        assertEquals(2, fetchedCatalog.tagPage.pageNav?.totalPages)
        repository.markRead(created, 1)
        assertTrue(1L in repository.getCatalogPage(created, 1)!!.readThreadIds)

        val refreshed = assertIs<YamiboResult.Success<RssSearchSubscriptionRepository.RefreshSummary>>(
            repository.refresh(created)
        ).value
        assertEquals(0, refreshed.newCount)

        val catalog = repository.getCatalogPage(created, 1)!!
        assertEquals(2, catalog.totalResults)
        assertTrue(1L in catalog.readThreadIds)
        assertEquals(1, repository.subscriptions.value.single().unreadCount)

        repository.delete(created)
        assertNull(repository.getSubscription(created))
        assertEquals(emptyList(), repository.subscriptions.value)
    }

    @Test
    fun duplicateKeywordAndForumReturnsExistingSubscription() = runBlocking {
        val db = inMemoryDatabase()
        val forum = FakeForumRepository()
        val repository = RssSearchSubscriptionRepositoryImpl(db, FakeAuthRepository(), forum)
        val first = assertIs<YamiboResult.Success<Long>>(
            repository.createFromSearch(
                title = "Custom",
                query = " app ",
                forumId = ForumId(10),
                forumName = "管理版",
                searchPage = SearchPage(query = "app", totalCount = 0, threads = emptyList()),
            )
        ).value
        val second = assertIs<YamiboResult.Success<Long>>(
            repository.createFromSearch(
                title = "Other",
                query = "app",
                forumId = ForumId(10),
                forumName = "管理版",
                searchPage = SearchPage(query = "app", totalCount = 0, threads = emptyList()),
            )
        ).value

        assertEquals(first, second)
        assertEquals(1, repository.subscriptions.value.size)
        assertEquals("app", repository.subscriptions.value.single().title)
    }

    @Test
    fun refreshFailurePreservesPreviousCatalogRows() = runBlocking {
        val db = inMemoryDatabase()
        val forum = FakeForumRepository(nextSearch = SearchPage(query = "app", totalCount = 1, threads = listOf(thread(1, "Existing"))))
        val repository = RssSearchSubscriptionRepositoryImpl(db, FakeAuthRepository(), forum)
        val id = assertIs<YamiboResult.Success<Long>>(
            repository.createFromSearch(
                title = "App RSS",
                query = "app",
                forumId = null,
                forumName = null,
                searchPage = SearchPage(query = "app", totalCount = 0, threads = emptyList()),
            )
        ).value
        assertEquals("Existing", repository.getCatalogPage(id, 1)!!.tagPage.threadSummaries.single().title)
        forum.nextFailure = YamiboResult.Failure("boom")

        assertIs<YamiboResult.Failure>(repository.refresh(id))

        val catalog = repository.getCatalogPage(id, 1)!!
        assertEquals(1, catalog.totalResults)
        assertEquals("Existing", catalog.tagPage.threadSummaries.single().title)
        assertEquals(RssSearchSubscriptionRepository.RefreshStatus.Failed, repository.getSubscription(id)!!.lastRefreshStatus)
    }

    @Test
    fun appSyncRecordsOnlyDurableSubscriptionMutations() = runBlocking {
        val db = inMemoryDatabase()
        val store = SqlDelightAppSyncOperationStore(db).also {
            it.initialize("generation")
            it.bindAccount(SyncAccountBinding("account"), AppSyncInstallationState.Active)
        }
        val recorder = AppSyncMutationRecorder(
            enabled = true,
            store = store,
            domainState = SqlDelightSyncDomainStateAdapter(
                db = db,
                materializer = DatabaseSyncDomainMaterializer(db, MapSettingsStore()),
                nowMillis = { 100 },
            ),
            nowMillis = { 100 },
        )
        val forum = FakeForumRepository(
            nextSearch = SearchPage(query = "app", totalCount = 0, threads = emptyList()),
        )
        val repository = RssSearchSubscriptionRepositoryImpl(
            db,
            FakeAuthRepository(),
            forum,
            recorder,
        )
        val id = assertIs<YamiboResult.Success<Long>>(
            repository.createFromSearch(
                title = "app",
                query = " app ",
                forumId = null,
                forumName = null,
                searchPage = SearchPage(query = "app", totalCount = 0, threads = emptyList()),
            ),
        ).value

        repository.refresh(id)
        repository.rename(id, "App feed")
        repository.setEnabled(id, false)
        repository.delete(id)
        repository.createFromSearch(
            title = "app",
            query = "app",
            forumId = null,
            forumName = null,
            searchPage = SearchPage(query = "app", totalCount = 0, threads = emptyList()),
        )

        val operations = store.allOutboxOperations().map { it.first }
        assertEquals(5, operations.size)
        assertTrue(operations.all { it.domainId.value == "rss.search-subscription" })
        assertEquals(
            listOf(
                SyncOperationKind.Put,
                SyncOperationKind.Patch,
                SyncOperationKind.Patch,
                SyncOperationKind.Delete,
                SyncOperationKind.Put,
            ),
            operations.map { it.kind },
        )
        assertEquals(1, operations.first().entityGeneration)
        assertEquals(2, operations.last().entityGeneration)
        assertTrue("lastRefreshStatus" !in operations.first().fields)
        assertTrue("lastSearchId" !in operations.first().fields)
    }

    private suspend fun createSubscription(repository: RssSearchSubscriptionRepositoryImpl, page: SearchPage): Long =
        assertIs<YamiboResult.Success<Long>>(
            repository.createFromSearch("app", "app", null, null, page),
        ).value

    private fun inMemoryDatabase(): Database {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        return Database(driver)
    }

    private fun thread(id: Int, title: String): ThreadSummary = ThreadSummary(
        tid = ThreadId(id),
        title = title,
        fid = ForumId(10),
        hasPoll = false,
        url = "https://bbs.yamibo.com/forum.php?mod=viewthread&tid=$id",
    )

    private class MapSettingsStore : SettingsStore {
        private val values = mutableMapOf<String, Any>()
        override fun getInt(key: String, defaultValue: Int) = values[key] as? Int ?: defaultValue
        override fun putInt(key: String, value: Int) = set(key, value)
        override fun getFloat(key: String, defaultValue: Float) = values[key] as? Float ?: defaultValue
        override fun putFloat(key: String, value: Float) = set(key, value)
        override fun getString(key: String, defaultValue: String) =
            values[key] as? String ?: defaultValue
        override fun putString(key: String, value: String) = set(key, value)
        override fun getBoolean(key: String, defaultValue: Boolean) =
            values[key] as? Boolean ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) = set(key, value)
        override fun remove(key: String) {
            values.remove(key)
        }
        override fun hasKey(key: String) = key in values
        private fun set(key: String, value: Any) {
            values[key] = value
        }
    }
}

private class FakeAuthRepository : AuthRepository {
    var profile: ProfilePage? = UserStore.Preview.copy(formHash = FormHash("expired"))
    var refreshResult: YamiboResult<Boolean> = YamiboResult.Success(true)
    var refreshCalls = 0
    override val cookieStore: CookieStore = object : CookieStore {
        override fun save(value: String) = Unit
        override fun load(): String? = null
        override fun clear() = Unit
    }
    override val userStore: UserStore = object : UserStore {
        override fun load(): ProfilePage? = profile
        override fun save(userInfo: ProfilePage) { profile = userInfo }
        override fun clear() { profile = null }
    }
    override val yamiboClient: YamiboClient = YamiboClient()

    override suspend fun isLoggedIn(): Boolean = true
    override suspend fun fetchStatus(): YamiboResult<Boolean> {
        refreshCalls += 1
        if (refreshResult == YamiboResult.Success(true)) {
            userStore.save(UserStore.Preview.copy(formHash = FormHash("fresh")))
        }
        return refreshResult
    }
    override suspend fun startLoginDetect(onSuccess: suspend () -> Unit, onTimeOut: () -> Unit) = onSuccess()
    override fun syncCookieFromWebView() = Unit
    override fun currentUser(): ProfilePage? = userStore.load()
    override suspend fun logOut() = Unit
}

private class FakeForumRepository(
    var nextSearch: SearchPage? = null,
    var nextFailure: YamiboResult<Nothing>? = null,
) : ForumRepository {
    val submittedFormHashes = mutableListOf<FormHash>()
    val pageRequests = mutableListOf<Pair<SearchId, Int>>()
    var nextPage: SearchPage? = null
    override val favoriteForums = kotlinx.coroutines.flow.MutableStateFlow<
        Map<ForumId, io.github.littlesurvival.dto.value.FavoriteId?>
    >(emptyMap())

    override suspend fun fetchSearch(query: String, forumId: ForumId?, formHash: FormHash): YamiboResult<SearchPage> {
        submittedFormHashes += formHash
        nextFailure?.let { return it }
        return YamiboResult.Success(requireNotNull(nextSearch))
    }

    override suspend fun fetchSearchById(query: String, searchId: SearchId, page: Int): YamiboResult<SearchPage> {
        pageRequests += searchId to page
        return YamiboResult.Success(requireNotNull(nextPage))
    }

    override suspend fun fetchHomePage(): YamiboResult<HomePage> = error("Not used")
    override suspend fun fetchForum(
        fid: ForumId,
        page: Int,
        filterType: io.github.littlesurvival.dto.page.FilterType?,
        orderType: io.github.littlesurvival.dto.page.OrderType?,
    ): YamiboResult<ForumPage> = error("Not used")

    override suspend fun addFavorite(
        forumId: ForumId,
        formHash: FormHash,
    ): YamiboResult<io.github.littlesurvival.dto.page.AddFavoriteResult> = error("Not used")
    override suspend fun removeFavorite(forumId: ForumId, formHash: FormHash): YamiboResult<String> = error("Not used")
    override fun getCachedHomePage(): HomePage? = null
    override fun getCachedForumPage(
        fid: ForumId,
        page: Int,
        filterType: io.github.littlesurvival.dto.page.FilterType?,
        orderType: io.github.littlesurvival.dto.page.OrderType?,
    ): ForumPage? = null

    override fun setCachedForumPage(
        fid: ForumId,
        page: Int,
        forumPage: ForumPage,
        filterType: io.github.littlesurvival.dto.page.FilterType?,
        orderType: io.github.littlesurvival.dto.page.OrderType?,
    ) = Unit

    override fun clearCachedForum(fid: ForumId) = Unit
}
