package me.thenano.yamibo.yamibo_app.repository.backup

import kotlin.test.*

class FavoriteUpdateEventIdentityTest {
    private fun original() = favoriteUpdateEventIdentity("ThreadNormal", 42, null, "latest", emptyList(), true,
        123, "legacy summary", "Legacy title")

    @Test fun portableLegacyIdentityPreservesTheHistoricalGoldenFingerprint() {
        val original = original()
        assertEquals("event:f94df80396e265cb", original.syncId)
        val portable = portableLegacyFavoriteUpdateDiscriminator("ThreadNormal", 42, null, "latest", original.sourceDiscriminator)
        val restored = favoriteUpdateEventIdentity("ThreadNormal", 42, 0, "latest", emptyList(), true,
            123, "display summary", "display title", portable)
        assertEquals(original.syncId, restored.syncId)
        assertEquals(original.sourceFingerprint, restored.sourceFingerprint)
        assertFalse(portable.contains("Legacy title"))
        assertFalse(portable.contains("legacy summary"))
        assertEquals(portable, restored.sourceDiscriminator)
    }

    @Test fun portableIdentityCannotBeTransplantedOrUsedAsImmutableDetailEvidence() {
        val portable = portableLegacyFavoriteUpdateDiscriminator("ThreadNormal", 42, null, "latest", original().sourceDiscriminator)
        fun read(type: String = "ThreadNormal", target: Long = 42, author: Long? = null, mode: String = "latest",
            details: List<Long> = emptyList(), ambiguous: Boolean = true, evidence: String = portable) =
            favoriteUpdateEventIdentity(type, target, author, mode, details, ambiguous, 123, "", "", evidence)
        assertFails { read(type = "ThreadImage") }
        assertFails { read(target = 43) }
        assertFails { read(author = 1) }
        assertFails { read(mode = "other") }
        assertFails { read(details = listOf(1)) }
        assertFails { read(ambiguous = false) }
        assertFails { read(evidence = portable + "|extra") }
        assertFails { read(evidence = portable.dropLast(1)) }
    }
}
