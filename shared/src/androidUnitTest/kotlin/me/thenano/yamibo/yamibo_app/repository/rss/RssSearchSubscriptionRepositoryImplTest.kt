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

private class FakeForumRepository(
    var nextSearch: SearchPage? = null,
    var nextFailure: YamiboResult.Failure? = null,
) : ForumRepository {
    override val favoriteForums = kotlinx.coroutines.flow.MutableStateFlow<
        Map<ForumId, io.github.littlesurvival.dto.value.FavoriteId?>
    >(emptyMap())

    override suspend fun fetchSearch(query: String, forumId: ForumId?, formHash: FormHash): YamiboResult<SearchPage> {
        nextFailure?.let { return it }
        return YamiboResult.Success(requireNotNull(nextSearch))
    }

    override suspend fun fetchSearchById(query: String, searchId: SearchId, page: Int): YamiboResult<SearchPage> =
        error("Not used")

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
