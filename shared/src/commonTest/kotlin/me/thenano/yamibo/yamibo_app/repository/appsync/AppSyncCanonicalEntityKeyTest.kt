package me.thenano.yamibo.yamibo_app.repository.appsync

import kotlin.test.*
import me.thenano.yamibo.yamibo_app.repository.appsync.schema.*

class AppSyncCanonicalEntityKeyTest {
    @Test
    fun productionCorpusKeysCoverEveryDomainAndReconstructDeclaredIdentityFields() {
        val operations = AppSyncSyntheticCorpus.create().journal.operations
        assertEquals(AppSyncCanonicalSchema.domainsById.keys, AppSyncCanonicalEntityKeys.layouts.keys)
        assertEquals(AppSyncCanonicalSchema.domains.keys, operations.map { it.domainId.value }.toSet())
        operations.forEach { op ->
            val schema = AppSyncCanonicalSchema.domains.getValue(op.domainId.value)
            val key = AppSyncCanonicalEntityKeys.parse(schema.id, op.entityId.value)
            assertEquals(op.entityId.value, key.legacyIdentity())
            key.derivedFields().forEach { (name, value) ->
                assertEquals(AppSyncFieldClass.Derived, schema.fields.getValue(name).classification)
                if (name in op.fields) assertEquals(op.fields[name], value, "domain=${schema.id}, field=$name")
            }
        }
    }

    @Test
    fun compositeComponentsAreTypedAndDoNotContainRedundantPrefixes() {
        val key = AppSyncCanonicalEntityKeys.parse(15, "ThreadNormal|42|0|category|with|separator")
        assertEquals(listOf(AppSyncCanonicalEntityKey.Component.Token("ThreadNormal"),
            AppSyncCanonicalEntityKey.Component.Number(42), AppSyncCanonicalEntityKey.Component.Number(0),
            AppSyncCanonicalEntityKey.Component.Token("category|with|separator")), key.components)
        assertEquals("ThreadNormal|42|0|category|with|separator", key.legacyIdentity())
        assertEquals("category|with|separator", key.derivedFields()["categorySyncId"])
        assertEquals(listOf(AppSyncCanonicalEntityKey.Component.Token("0123456789abcdef")),
            AppSyncCanonicalEntityKeys.parse(17, "event:0123456789abcdef").components)
        assertEquals(mapOf("fid" to "123"), AppSyncCanonicalEntityKeys.parse(18, "fid:123").derivedFields())
        assertEquals("Novel", AppSyncCanonicalEntityKeys.parse(8, "1|Novel|0|TagCatalog").derivedFields()["threadType"])
    }

    @Test
    fun malformedAliasesAndUnknownKeysCannotChangeAnEntityIdentity() {
        listOf(999 to "id", 2 to "ThreadNormal|1", 2 to "ThreadNormal|01|0", 2 to "ThreadNormal|+1|0",
            2 to "ThreadNormal|1|0|extra", 8 to "1|Unknown|0|Direct", 8 to "1|Normal|0|Unknown",
            17 to "event:NOT-A-FINGERPRINT", 3 to "wrong:0123456789abcdef", 18 to "fid:+1",
            1 to "unknown.setting", 4 to "\uD800", 4 to "\n", 4 to "中".repeat(342)).forEach { (domain, id) ->
            assertFails { AppSyncCanonicalEntityKeys.parse(domain, id) }
        }
        assertFails { AppSyncCanonicalEntityKey(9, listOf(AppSyncCanonicalEntityKey.Component.Token("1"))).legacyIdentity() }
        val valid = AppSyncCanonicalEntityKeys.parse(2, "ThreadNormal|${Long.MAX_VALUE}|${Long.MIN_VALUE}")
        assertEquals("ThreadNormal|${Long.MAX_VALUE}|${Long.MIN_VALUE}", valid.legacyIdentity())
    }
}
