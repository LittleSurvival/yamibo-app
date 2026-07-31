package me.thenano.yamibo.yamibo_app.store.appsync

import io.github.littlesurvival.dto.value.BlogClassId
import io.github.littlesurvival.dto.value.BlogId
import me.thenano.yamibo.yamibo_app.Database
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncAccountBinding

internal enum class AppSyncRemoteBlogKind {
    Index,
    Journal,
    Checkpoint,
}
internal data class StoredAppSyncRemoteBlog(
    val remoteKey: String,
    val kind: AppSyncRemoteBlogKind,
    val blogId: BlogId,
    val classId: BlogClassId?,
    val fingerprint: String?,
    val validatedAtEpochMillis: Long,
    val contentUpdatedAtEpochMillis: Long?,
)

internal interface AppSyncRemoteBlogStore {
    fun load(remoteKey: String): StoredAppSyncRemoteBlog?
    fun loadKind(kind: AppSyncRemoteBlogKind): List<StoredAppSyncRemoteBlog>
    fun save(blog: StoredAppSyncRemoteBlog)
    fun remove(remoteKey: String)
    fun clear()
    fun loadClassId(accountBinding: SyncAccountBinding): BlogClassId? = null
    fun saveClassId(accountBinding: SyncAccountBinding, classId: BlogClassId) = Unit
}

internal class SqlDelightAppSyncRemoteBlogStore(
    db: Database,
) : AppSyncRemoteBlogStore {
    private val queries = db.appSyncOperationQueries

    override fun load(remoteKey: String): StoredAppSyncRemoteBlog? =
        queries.getRemoteBlog(remoteKey).executeAsOneOrNull()?.toStored()

    override fun loadKind(kind: AppSyncRemoteBlogKind): List<StoredAppSyncRemoteBlog> =
        queries.getRemoteBlogsByKind(kind.name.uppercase()).executeAsList().map { it.toStored() }

    override fun save(blog: StoredAppSyncRemoteBlog) {
        queries.upsertRemoteBlog(
            remoteKey = blog.remoteKey,
            kind = blog.kind.name.uppercase(),
            blogId = blog.blogId.value.toLong(),
            classId = blog.classId?.value?.toLong(),
            fingerprint = blog.fingerprint,
            validatedAtEpochMillis = blog.validatedAtEpochMillis,
            contentUpdatedAtEpochMillis = blog.contentUpdatedAtEpochMillis,
        )
    }

    override fun remove(remoteKey: String) {
        queries.deleteRemoteBlog(remoteKey)
    }

    override fun clear() {
        queries.clearRemoteBlogs()
    }

    override fun loadClassId(accountBinding: SyncAccountBinding): BlogClassId? =
        queries.getRemoteClassLink(accountBinding.value)
            .executeAsOneOrNull()
            ?.toInt()
            ?.let(::BlogClassId)

    override fun saveClassId(
        accountBinding: SyncAccountBinding,
        classId: BlogClassId,
    ) {
        queries.upsertRemoteClassLink(
            accountBinding = accountBinding.value,
            classId = classId.value.toLong(),
        )
    }

    private fun me.thenano.yamibo.yamibo_app.AppSyncRemoteBlog.toStored() =
        StoredAppSyncRemoteBlog(
            remoteKey = remoteKey,
            kind = AppSyncRemoteBlogKind.entries.first { it.name.equals(kind, ignoreCase = true) },
            blogId = BlogId(blogId.toInt()),
            classId = classId?.toInt()?.let(::BlogClassId),
            fingerprint = fingerprint,
            validatedAtEpochMillis = validatedAtEpochMillis,
            contentUpdatedAtEpochMillis = contentUpdatedAtEpochMillis,
        )
}
