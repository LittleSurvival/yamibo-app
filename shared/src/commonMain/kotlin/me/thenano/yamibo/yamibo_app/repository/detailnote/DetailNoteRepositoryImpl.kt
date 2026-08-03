package me.thenano.yamibo.yamibo_app.repository.detailnote

import me.thenano.yamibo.yamibo_app.Database
import me.thenano.yamibo.yamibo_app.repository.DetailNoteRepository
import me.thenano.yamibo.yamibo_app.repository.appsync.AppSyncMutationRecorder
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperationKind
import me.thenano.yamibo.yamibo_app.util.time.currentTimeMillis

class DetailNoteRepositoryImpl internal constructor(
    db: Database,
    private val mutationRecorder: AppSyncMutationRecorder? = null,
) : DetailNoteRepository {
    private val queries = db.detailNoteQueries

    override suspend fun getNote(
        targetType: DetailNoteRepository.TargetType,
        targetId: Long,
        authorId: Long,
    ): DetailNoteRepository.DetailNote? {
        return queries.getByTarget(targetType.name, targetId, authorId)
            .executeAsOneOrNull()
            ?.let {
                DetailNoteRepository.DetailNote(
                    targetType = DetailNoteRepository.TargetType.valueOf(it.targetType),
                    targetId = it.targetId,
                    authorId = it.authorId,
                    content = it.content,
                    createdAt = it.createdAt,
                    updatedAt = it.updatedAt,
                )
            }
    }

    override suspend fun saveNote(
        targetType: DetailNoteRepository.TargetType,
        targetId: Long,
        authorId: Long,
        content: String,
    ) {
        val normalizedContent = content.trim()
        if (normalizedContent.isBlank()) {
            deleteNote(targetType, targetId, authorId)
            return
        }
        val now = currentTimeMillis()
        val existing = getNote(targetType, targetId, authorId)
        val createdAt = existing?.createdAt ?: now
        val mutation = {
            queries.upsert(
                targetType = targetType.name,
                targetId = targetId,
                authorId = authorId,
                content = normalizedContent,
                createdAt = createdAt,
                updatedAt = now,
            )
        }
        mutationRecorder?.record(
            domain = DOMAIN,
            entityId = entityId(targetType, targetId, authorId),
            kind = if (existing == null) SyncOperationKind.Put else SyncOperationKind.Patch,
            fields = mapOf(
                "targetType" to targetType.name,
                "targetId" to targetId.toString(),
                "authorId" to authorId.toString(),
                "content" to normalizedContent,
                "createdAt" to createdAt.toString(),
                "updatedAt" to now.toString(),
            ),
        ) { mutation() } ?: mutation.takeIf { mutationRecorder == null }?.invoke()
    }

    override suspend fun deleteNote(
        targetType: DetailNoteRepository.TargetType,
        targetId: Long,
        authorId: Long,
    ) {
        val existing = getNote(targetType, targetId, authorId) ?: return
        val mutation = {
            queries.deleteByTarget(targetType.name, targetId, authorId)
        }
        mutationRecorder?.record(
            domain = DOMAIN,
            entityId = entityId(targetType, targetId, authorId),
            kind = SyncOperationKind.Delete,
            fields = mapOf(
                "targetType" to targetType.name,
                "targetId" to targetId.toString(),
                "authorId" to authorId.toString(),
                "content" to existing.content,
                "createdAt" to existing.createdAt.toString(),
                "updatedAt" to currentTimeMillis().toString(),
            ),
        ) { mutation() } ?: mutation.takeIf { mutationRecorder == null }?.invoke()
    }

    private fun entityId(
        targetType: DetailNoteRepository.TargetType,
        targetId: Long,
        authorId: Long,
    ): String = "${targetType.name}|$targetId|$authorId"

    private companion object {
        const val DOMAIN = "detail-note"
    }
}
