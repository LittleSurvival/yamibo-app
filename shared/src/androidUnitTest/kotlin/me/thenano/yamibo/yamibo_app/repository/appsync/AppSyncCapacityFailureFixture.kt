package me.thenano.yamibo.yamibo_app.repository.appsync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import me.thenano.yamibo.yamibo_app.Database
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncInstallationState
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncAccountBinding
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncCausalContext
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncDomainId
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncEntityId
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperationKind
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperationOrigin
import me.thenano.yamibo.yamibo_app.repository.performance.FavoriteHistoryPerformanceFixture
import me.thenano.yamibo.yamibo_app.store.appsync.SqlDelightAppSyncOperationStore

/** Redacted, deterministic reproduction of the emulator capacity incident. */
internal object AppSyncCapacityFailureFixture {
    const val SIGN_CACHE_CHARS = 367_000
    const val SIGN_CACHE_OPERATION_COUNT = 3

    data class Loaded(
        val database: Database,
        val store: SqlDelightAppSyncOperationStore,
        val accountBinding: SyncAccountBinding,
    )

    fun create(): Loaded {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        val database = Database(driver)
        FavoriteHistoryPerformanceFixture.populate(database)
        val store = SqlDelightAppSyncOperationStore(database).also {
            it.initialize("capacity-regression")
        }
        val account = SyncAccountBinding("redacted-account")
        store.bindAccount(account, AppSyncInstallationState.Active)
        var now = 1_800_000_000_000L

        repeat(SIGN_CACHE_OPERATION_COUNT) { index ->
            append(
                store, account, "settings", "appsettings.signpagehtmlcache",
                SyncOperationKind.Patch,
                mapOf("type" to "string", "value" to syntheticCache(index)),
                now++,
            )
        }
        append(
            store, account, "settings", "appsettings.backupfolderuri",
            SyncOperationKind.Patch,
            mapOf("type" to "string", "value" to "content://redacted/tree/backup"),
            now++,
        )
        append(
            store, account, "reading.thread", "thread:legacy-cover",
            SyncOperationKind.Patch,
            mapOf("threadCover" to "data:image/png;base64," + "A".repeat(64_000)),
            now++,
        )
        append(
            store, account, "detail-note", "redacted-note",
            SyncOperationKind.Delete, emptyMap(), now++,
        )
        append(
            store, account, "favorite.item-category", "redacted-relation",
            SyncOperationKind.RelationRemove,
            mapOf(
                "targetType" to "ThreadNormal",
                "targetId" to "1",
                "authorId" to "0",
                "categorySyncId" to "redacted-category",
            ),
            now,
        )
        return Loaded(database, store, account)
    }

    private fun append(
        store: SqlDelightAppSyncOperationStore,
        account: SyncAccountBinding,
        domain: String,
        entity: String,
        kind: SyncOperationKind,
        fields: Map<String, String?>,
        now: Long,
    ) {
        store.appendLocalOperation(
            accountBinding = account,
            domainId = SyncDomainId(domain),
            entityId = SyncEntityId(entity),
            entityGeneration = 1,
            kind = kind,
            fields = fields,
            causalContext = SyncCausalContext(),
            createdAtEpochMillis = now,
            origin = SyncOperationOrigin.UserAction,
        )
    }

    private fun syntheticCache(index: Int): String = buildString(SIGN_CACHE_CHARS) {
        append("<html><body data-fixture=\"").append(index).append("\">")
        while (length < SIGN_CACHE_CHARS - 14) append("redacted-cache-block;")
        append("</body></html>")
    }.take(SIGN_CACHE_CHARS)
}
