package me.thenano.yamibo.yamibo_app.repository.appsync.engine

import me.thenano.yamibo.yamibo_app.repository.appsync.operation.*
import me.thenano.yamibo.yamibo_app.repository.appsync.schema.*
import me.thenano.yamibo.yamibo_app.repository.backup.YamiboBackupFile

/** Compares portable live values only; a matching snapshot supplies no operation provenance. */
internal object AppSyncCanonicalSnapshotValidation {
    fun matches(checkpoint: AppSyncCanonicalCheckpoint, snapshot: YamiboBackupFile): Boolean = try {
        val plan = BackupSnapshotMigrationPlanner().planWithDiagnostics(snapshot)
        require(plan.skippedOrphanRssHistoryCount == 0 && plan.drafts.size <= 100_000)
        val expected = linkedMapOf<Pair<Int, String>, Map<Int, AppSyncCanonicalValue>>()
        for (draft in plan.drafts) {
            require(draft.kind in setOf(SyncOperationKind.Put, SyncOperationKind.RelationAdd))
            val normalized = AppSyncCanonicalNormalizer.normalize(draft.domainId.value, draft.entityId.value, draft.fields)
            if (normalized is AppSyncCanonicalFieldsResult.Excluded) continue
            require(normalized is AppSyncCanonicalFieldsResult.Accepted)
            val domain = AppSyncCanonicalSchema.domains.getValue(draft.domainId.value)
            val identity = AppSyncCanonicalEntityKeys.parse(domain.id, draft.entityId.value).legacyIdentity()
            require(expected.put(domain.id to identity, normalized.fields) == null) { "Duplicate snapshot entity" }
        }
        val live = checkpoint.entities.filter {
            it.tombstone == null && it.relation?.kind != SyncOperationKind.RelationRemove
        }.associateBy { it.domainId to it.entityId }
        require(expected.keys == live.keys) { "Snapshot and resolved entity sets differ" }
        expected.forEach { (key, fields) ->
            val actual = live.getValue(key).values()
            val domain = AppSyncCanonicalSchema.domainsById.getValue(key.first)
            require((fields.keys + actual.keys).all { id ->
                val left = fields[id]
                val right = actual[id]
                if (left == right) true
                else {
                    val descriptor = domain.fieldsById.getValue(id)
                    fun absent(value: AppSyncCanonicalValue?) = value == null ||
                        (descriptor.nullable && value.legacyValue() == null) ||
                        (descriptor.classification == AppSyncFieldClass.BoundedPresentation && value.legacyValue() == "")
                    absent(left) && absent(right)
                }
            }) { "Snapshot and resolved portable values differ" }
        }
        true
    } catch (_: Exception) { false }
}
