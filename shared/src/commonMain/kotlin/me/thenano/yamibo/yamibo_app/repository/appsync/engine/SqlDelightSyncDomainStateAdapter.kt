package me.thenano.yamibo.yamibo_app.repository.appsync.engine

import kotlinx.serialization.json.Json
import me.thenano.yamibo.yamibo_app.Database
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncDomainId
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncEntityId
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperation

internal interface SyncDomainMaterializer {
    fun apply(entity: ResolvedSyncEntity)
    fun reconcileProjections()
    fun clearSyncableData() = Unit
}

internal class SqlDelightSyncDomainStateAdapter(
    db: Database,
    private val materializer: SyncDomainMaterializer,
    private val reducer: OperationReducer = OperationReducer(),
    private val json: Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
        explicitNulls = true
    },
    private val nowMillis: () -> Long,
    private val onFullStateRead: () -> Unit = {},
) : SyncDomainStateAdapter {
    private val queries = db.appSyncOperationQueries

    override fun currentState(): Map<SyncEntityKey, ResolvedSyncEntity> {
        onFullStateRead()
        return queries.getResolvedEntities().executeAsList().associate { row ->
            decodeEntity(row.encodedState)
        }
    }

    fun entityState(domainId: SyncDomainId, entityId: SyncEntityId): Map<SyncEntityKey, ResolvedSyncEntity> =
        queries.getResolvedEntitiesByDomainAndEntity(domainId.value, entityId.value)
            .executeAsList()
            .associate { row -> decodeEntity(row.encodedState) }

    fun currentGeneration(domainId: SyncDomainId, entityId: SyncEntityId): Long {
        val latest = entityState(domainId, entityId)
            .values
            .maxByOrNull { it.key.generation }
            ?: return 1
        return if (latest.tombstone != null || latest.relationPresent == false) {
            latest.key.generation + 1
        } else {
            latest.key.generation
        }
    }

    override fun apply(result: OperationReductionResult) {
        applyWithinTransaction(result)
        materializer.reconcileProjections()
    }

    override fun applyWithinTransaction(result: OperationReductionResult) {
        val ordered = result.entities.values.sortedWith(
            compareBy(
                { MATERIALIZATION_ORDER[it.key.domainId.value] ?: Int.MAX_VALUE },
                { it.key.entityId.value },
            ),
        )
        ordered.forEach { entity ->
            persist(entity)
            materializer.apply(entity)
        }
    }

    fun recordLocal(operation: SyncOperation) {
        val result = reducer.reduce(
            current = entityState(operation.domainId, operation.entityId),
            operations = listOf(operation),
        )
        result.entities.values.forEach(::persist)
    }

    override fun reconcileProjections() {
        materializer.reconcileProjections()
    }

    override fun adoptCheckpoint(entities: Collection<ResolvedSyncEntity>) {
        adoptCheckpointWithinTransaction(entities)
        materializer.reconcileProjections()
    }

    override fun adoptCheckpointWithinTransaction(entities: Collection<ResolvedSyncEntity>) {
        queries.clearResolvedEntities()
        materializer.clearSyncableData()
        entities.sortedWith(
            compareBy(
                { MATERIALIZATION_ORDER[it.key.domainId.value] ?: Int.MAX_VALUE },
                { it.key.entityId.value },
            ),
        ).forEach { entity ->
            persist(entity)
            materializer.apply(entity)
        }
    }

    override fun entityCount(domainId: SyncDomainId): Int =
        queries.getResolvedEntitiesByDomain(domainId.value).executeAsList().size

    private fun persist(entity: ResolvedSyncEntity) {
        queries.upsertResolvedEntity(
            entityKey = entity.key.stableKey(),
            domainId = entity.key.domainId.value,
            entityId = entity.key.entityId.value,
            entityGeneration = entity.key.generation,
            encodedState = json.encodeToString(ResolvedSyncEntity.serializer(), entity),
            updatedAtEpochMillis = nowMillis(),
        )
    }

    private fun decodeEntity(encodedState: String): Pair<SyncEntityKey, ResolvedSyncEntity> {
        val entity = json.decodeFromString(ResolvedSyncEntity.serializer(), encodedState)
        return entity.key to entity
    }

    private fun SyncEntityKey.stableKey(): String =
        "${domainId.value}|${entityId.value}|$generation"

    private companion object {
        val MATERIALIZATION_ORDER = mapOf(
            "settings" to 0,
            "favorite.category" to 10,
            "favorite.collection" to 20,
            "favorite.item" to 30,
            "rss.search-subscription" to 35,
            "favorite.item-category" to 40,
            "favorite.item-collection" to 50,
            "favorite.update-event" to 55,
            "favorite.update-fid-filter" to 56,
            "favorite.update-category-filter" to 57,
            "detail-note" to 60,
            "bookmark" to 70,
            "reading.thread" to 80,
            "reading.image" to 81,
            "reading.tag-manga" to 82,
            "reading.tag-catalog" to 83,
            "reading.rss-search" to 84,
            "reading.rss-catalog" to 85,
            "reading.time" to 86,
        )
    }
}
