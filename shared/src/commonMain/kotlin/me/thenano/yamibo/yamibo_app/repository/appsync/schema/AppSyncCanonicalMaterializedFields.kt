package me.thenano.yamibo.yamibo_app.repository.appsync.schema

import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperationKind
import me.thenano.yamibo.yamibo_app.repository.backup.favoriteUpdateEventIdentity
import me.thenano.yamibo.yamibo_app.repository.rss.rssSearchSubscriptionSyncId

/** Local database values only. Never use these synthesized fields as operation provenance. */
internal object AppSyncCanonicalMaterializedFields {
    data class RssParent(val title: String, val query: String, val forumId: Long?)

    fun restore(projection: AppSyncCanonicalProjection, rssParent: RssParent? = null): Map<String, String?> {
        val domain = requireNotNull(AppSyncCanonicalSchema.domainsById[projection.domainId])
        val key = AppSyncCanonicalEntityKeys.parse(domain.id, projection.entityId)
        require(projection.tombstone == null && projection.relation?.kind != SyncOperationKind.RelationRemove) {
            "Removed entity has no materialized values"
        }
        projection.fields.forEach { (id, winner) ->
            require(winner.domainId == domain.id && winner.entityId == projection.entityId &&
                winner.generation == projection.generation && id in winner.fields &&
                winner.kind in setOf(SyncOperationKind.Put, SyncOperationKind.Patch, SyncOperationKind.RelationAdd)) {
                "Invalid materialized provenance"
            }
        }
        val values = projection.values()
        AppSyncCanonicalFieldCodec.encode(domain.name, projection.entityId, values)
        val result = values.mapKeys { domain.fieldsById.getValue(it.key).name }
            .mapValues { it.value.legacyValue() }.toMutableMap()
        result.putAll(key.derivedFields())
        // Empty presentation values satisfy local non-null columns without inventing remote data.
        domain.fields.values.filter { it.classification == AppSyncFieldClass.BoundedPresentation }
            .forEach { if (it.name !in result) result[it.name] = "" }
        domain.fields.values.filter { it.classification == AppSyncFieldClass.Cache && !it.nullable }
            .forEach { result[it.name] = "" }
        if (domain.id == 12 || domain.id == 13) {
            val parent = requireNotNull(rssParent) { "RSS parent is unavailable" }
            require(rssSearchSubscriptionSyncId(parent.query, parent.forumId) == projection.entityId) {
                "RSS parent identity mismatch"
            }
            result["subscriptionTitle"] = parent.title
            result["subscriptionQuery"] = parent.query
        }
        if (domain.id == 17) {
            val discriminator = result["sourceDiscriminator"] ?: defaultAppSyncEventDiscriminator(result["detailIds"])
            require(!discriminator.isNullOrBlank()) { "Event identity evidence is unavailable" }
            val identity = favoriteUpdateEventIdentity(
                requireNotNull(result["targetType"]), requireNotNull(result["targetId"]).toLong(),
                requireNotNull(result["authorId"]).toLong(), requireNotNull(result["mode"]),
                requireNotNull(result["detailIds"]).split(',').filter(String::isNotBlank).map(String::toLong),
                requireNotNull(result["ambiguous"]).toBooleanStrict(), requireNotNull(result["detectedAt"]).toLong(),
                requireNotNull(result["summary"]), result["title"].orEmpty(), discriminator,
            )
            require(identity.syncId == projection.entityId) { "Event identity mismatch" }
            result["sourceDiscriminator"] = discriminator
        }
        return result.toMap()
    }
}
