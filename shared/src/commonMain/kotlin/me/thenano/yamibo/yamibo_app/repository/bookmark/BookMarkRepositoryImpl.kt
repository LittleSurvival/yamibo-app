package me.thenano.yamibo.yamibo_app.repository.bookmark

import me.thenano.yamibo.yamibo_app.Database
import me.thenano.yamibo.yamibo_app.repository.BookMarkRepository
import me.thenano.yamibo.yamibo_app.repository.appsync.AppSyncMutationRecorder
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperationKind
import me.thenano.yamibo.yamibo_app.util.time.currentTimeMillis
import me.thenano.yamibo.yamibo_app.LocalBookMark

class BookMarkRepositoryImpl internal constructor(
    db: Database,
    private val mutationRecorder: AppSyncMutationRecorder? = null,
) : BookMarkRepository {
    private val queries = db.localBookMarkQueries

    override suspend fun getEntry(
        targetType: BookMarkRepository.TargetType,
        parentId: Long,
        targetId: Long,
    ): BookMarkRepository.Entry? {
        return queries.getByTarget(targetType.name, parentId, targetId).executeAsOneOrNull()?.toEntry()
    }

    override suspend fun getEntriesByParent(
        targetType: BookMarkRepository.TargetType,
        parentId: Long,
    ): List<BookMarkRepository.Entry> {
        return queries.getByParent(targetType.name, parentId).executeAsList().map { it.toEntry() }
    }

    override suspend fun getAllEntries(): List<BookMarkRepository.Entry> {
        return queries.getAll().executeAsList().map { it.toEntry() }
    }

    override suspend fun setBookmarked(
        targetType: BookMarkRepository.TargetType,
        parentId: Long,
        targetId: Long,
        title: String,
        bookmarked: Boolean,
    ) {
        upsertState(targetType, parentId, targetId, title, bookmarked = bookmarked, read = null)
    }

    override suspend fun setRead(
        targetType: BookMarkRepository.TargetType,
        parentId: Long,
        targetId: Long,
        title: String,
        read: Boolean,
    ) {
        upsertState(targetType, parentId, targetId, title, bookmarked = null, read = read)
    }

    override suspend fun clearTarget(
        targetType: BookMarkRepository.TargetType,
        parentId: Long,
        targetId: Long,
    ) {
        val existing = queries.getByTarget(targetType.name, parentId, targetId)
            .executeAsOneOrNull()
            ?: return
        deleteExisting(existing)
    }

    override suspend fun clearParent(targetType: BookMarkRepository.TargetType, parentId: Long) {
        queries.getByParent(targetType.name, parentId).executeAsList().forEach(::deleteExisting)
    }

    private fun upsertState(
        targetType: BookMarkRepository.TargetType,
        parentId: Long,
        targetId: Long,
        title: String,
        bookmarked: Boolean?,
        read: Boolean?,
    ) {
        val existing = queries.getByTarget(targetType.name, parentId, targetId).executeAsOneOrNull()
        val now = currentTimeMillis()
        val nextBookmarked = bookmarked ?: ((existing?.bookmarked ?: 0L) != 0L)
        val nextRead = read ?: ((existing?.read ?: 0L) != 0L)
        val nextTitle = title.ifBlank { existing?.title.orEmpty() }
        val createdAt = existing?.createdAt ?: now
        val fields = fields(
            targetType = targetType.name,
            parentId = parentId,
            targetId = targetId,
            title = nextTitle,
            bookmarked = nextBookmarked,
            read = nextRead,
            createdAt = createdAt,
            updatedAt = now,
        )
        val delete = !nextBookmarked && !nextRead
        if (delete && existing == null) return
        mutate(
            entityId = entityId(targetType.name, parentId, targetId),
            kind = when {
                delete -> SyncOperationKind.Delete
                existing == null -> SyncOperationKind.Put
                else -> SyncOperationKind.Patch
            },
            fields = fields,
        ) {
            if (delete) {
                queries.deleteByTarget(targetType.name, parentId, targetId)
            } else {
                queries.upsert(
                    targetType = targetType.name,
                    parentId = parentId,
                    targetId = targetId,
                    title = nextTitle,
                    bookmarked = if (nextBookmarked) 1L else 0L,
                    read = if (nextRead) 1L else 0L,
                    createdAt = createdAt,
                    updatedAt = now,
                )
            }
        }
    }

    private fun deleteExisting(existing: LocalBookMark) {
        mutate(
            entityId = entityId(existing.targetType, existing.parentId, existing.targetId),
            kind = SyncOperationKind.Delete,
            fields = fields(
                targetType = existing.targetType,
                parentId = existing.parentId,
                targetId = existing.targetId,
                title = existing.title,
                bookmarked = existing.bookmarked != 0L,
                read = existing.read != 0L,
                createdAt = existing.createdAt,
                updatedAt = currentTimeMillis(),
            ),
        ) {
            queries.deleteByTarget(existing.targetType, existing.parentId, existing.targetId)
        }
    }

    private fun mutate(
        entityId: String,
        kind: SyncOperationKind,
        fields: Map<String, String?>,
        mutation: () -> Unit,
    ) {
        if (mutationRecorder == null) {
            mutation()
        } else {
            mutationRecorder.record(DOMAIN, entityId, kind, fields) { mutation() }
        }
    }

    private fun fields(
        targetType: String,
        parentId: Long,
        targetId: Long,
        title: String,
        bookmarked: Boolean,
        read: Boolean,
        createdAt: Long,
        updatedAt: Long,
    ) = mapOf(
        "targetType" to targetType,
        "parentId" to parentId.toString(),
        "targetId" to targetId.toString(),
        "title" to title,
        "bookmarked" to bookmarked.toString(),
        "read" to read.toString(),
        "createdAt" to createdAt.toString(),
        "updatedAt" to updatedAt.toString(),
    )

    private fun entityId(targetType: String, parentId: Long, targetId: Long): String =
        "$targetType|$parentId|$targetId"

    private fun LocalBookMark.toEntry(): BookMarkRepository.Entry {
        return BookMarkRepository.Entry(
            targetType = BookMarkRepository.TargetType.fromStorage(targetType),
            parentId = parentId,
            targetId = targetId,
            title = title,
            bookmarked = bookmarked != 0L,
            read = read != 0L,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
    }

    private companion object {
        const val DOMAIN = "bookmark"
    }
}
