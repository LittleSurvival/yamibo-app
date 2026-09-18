package me.thenano.yamibo.yamibo_app.repository.appsync

import kotlin.test.*
import me.thenano.yamibo.yamibo_app.repository.appsync.schema.*
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperationKind
import me.thenano.yamibo.yamibo_app.repository.rss.rssSearchSubscriptionSyncId

class AppSyncCanonicalMaterializedFieldsTest {
    private val corpus by lazy { AppSyncSyntheticCorpus.create() }
    private fun operation(domain: String): AppSyncCanonicalOperation {
        val source = corpus.journal.operations.first { it.domainId.value == domain }
        return assertIs<AppSyncCanonicalOperationImport.Accepted>(
            AppSyncCanonicalOperationImporter().import(corpus.journal.accountBinding.value, source)).operation
    }
    private fun projection(op: AppSyncCanonicalOperation) = AppSyncCanonicalProjection(
        op.domainId, op.entityId, op.generation, op.fields.mapValues { op },
        relation = op.takeIf { it.kind == SyncOperationKind.RelationAdd })

    @Test fun allDomainsRestoreIdentityWithoutChangingWinningEvidence() {
        AppSyncCanonicalSchema.domains.values.forEach { domain ->
            val op = operation(domain.name)
            val entity = projection(op)
            val parent = if (domain.id in setOf(12, 13)) {
                val fields = corpus.journal.operations.first { it.domainId.value == "rss.search-subscription" && it.entityId.value == op.entityId }.fields
                AppSyncCanonicalMaterializedFields.RssParent(fields.getValue("title")!!, fields.getValue("query")!!, fields["forumId"]?.toLong())
            } else null
            val restored = AppSyncCanonicalMaterializedFields.restore(entity, parent)
            AppSyncCanonicalEntityKeys.parse(domain.id, op.entityId).derivedFields().forEach { (key, value) ->
                assertEquals(value, restored[key], domain.name)
            }
            val portable = restored.filterKeys { domain.fields[it]?.portable == true }
            assertEquals(op.fields, assertIs<AppSyncCanonicalFieldsResult.Accepted>(
                AppSyncCanonicalNormalizer.normalize(domain.name, op.entityId, portable)).fields, domain.name)
            assertEquals(entity, projection(op))
            assertFalse("coverUrl" in restored)
            assertFalse("threadCover" in restored)
        }
    }

    @Test fun missingTitleGetsOnlyALocalPlaceholder() {
        val op = operation("favorite.item").let { it.copy(fields = it.fields - 6) }
        val entity = projection(op)
        assertEquals("", AppSyncCanonicalMaterializedFields.restore(entity)["title"])
        assertFalse(6 in entity.fields)
    }

    @Test fun rssRequiresMatchingParentAndUsesCurrentParentLabels() {
        val op = operation("reading.rss-search")
        assertFailsWith<IllegalArgumentException> { AppSyncCanonicalMaterializedFields.restore(projection(op)) }
        val parent = AppSyncCanonicalMaterializedFields.RssParent("Updated parent", "new query", null)
        assertFailsWith<IllegalArgumentException> { AppSyncCanonicalMaterializedFields.restore(projection(op), parent) }
        val matched = op.copy(entityId = rssSearchSubscriptionSyncId(parent.query, parent.forumId))
        val restored = AppSyncCanonicalMaterializedFields.restore(projection(matched), parent)
        assertEquals(parent.title, restored["subscriptionTitle"])
        assertEquals(parent.query, restored["subscriptionQuery"])
    }

    @Test fun invalidEventAndForeignProvenanceAreRejected() {
        val event = operation("favorite.update-event")
        val invalid = event.copy(fields = event.fields + (4 to AppSyncCanonicalValue.Integer(999999)))
        assertFailsWith<IllegalArgumentException> { AppSyncCanonicalMaterializedFields.restore(projection(invalid)) }
        val entity = projection(event)
        assertFailsWith<IllegalArgumentException> {
            AppSyncCanonicalMaterializedFields.restore(entity.copy(entityId = "event:0000000000000000"))
        }
        assertFailsWith<IllegalArgumentException> {
            AppSyncCanonicalMaterializedFields.restore(entity.copy(tombstone = event.copy(kind = SyncOperationKind.Delete)))
        }
    }
}
