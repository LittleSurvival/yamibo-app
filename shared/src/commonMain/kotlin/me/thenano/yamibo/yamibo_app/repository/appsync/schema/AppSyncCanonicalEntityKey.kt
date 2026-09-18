package me.thenano.yamibo.yamibo_app.repository.appsync.schema

/** The enclosing operation owns domain/generation; the wire key contains only these components. */
internal data class AppSyncCanonicalEntityKey(val domainId: Int, val components: List<Component>) {
    sealed interface Component {
        data class Number(val value: Long) : Component
        data class Token(val value: String) : Component
    }

    fun legacyIdentity(): String = AppSyncCanonicalEntityKeys.legacyIdentity(this)
    fun derivedFields(): Map<String, String?> = AppSyncCanonicalEntityKeys.derivedFields(this)
}

internal object AppSyncCanonicalEntityKeys {
    enum class ComponentType { Number, Token }
    private val number = ComponentType.Number
    private val token = ComponentType.Token

    /** Explicit domain layouts. Unknown domains have no fallback opaque representation. */
    val layouts: Map<Int, List<ComponentType>> = mapOf(
        1 to listOf(token), 2 to listOf(token, number, number), 3 to listOf(token),
        4 to listOf(token), 5 to listOf(token), 6 to listOf(token, number, number),
        7 to listOf(token, number, number), 8 to listOf(number, token, number, token),
        9 to listOf(number), 10 to listOf(number), 11 to listOf(number),
        12 to listOf(token), 13 to listOf(token), 14 to listOf(token),
        15 to listOf(token, number, number, token), 16 to listOf(token, number, number, token),
        17 to listOf(token), 18 to listOf(number), 19 to listOf(token),
    )

    fun parse(domainId: Int, legacyIdentity: String): AppSyncCanonicalEntityKey {
        require(legacyIdentity.length <= 4096) { "Entity identity budget exceeded" }
        val layout = requireNotNull(layouts[domainId]) { "Unknown entity key domain" }
        val parts = when (domainId) {
            3, 12, 13 -> listOf(withoutPrefix(legacyIdentity, "rss:"))
            17 -> listOf(withoutPrefix(legacyIdentity, "event:"))
            18 -> listOf(withoutPrefix(legacyIdentity, "fid:"))
            19 -> listOf(withoutPrefix(legacyIdentity, "category:"))
            2, 6, 7, 8, 15, 16 -> legacyIdentity.split('|', limit = layout.size)
            else -> listOf(legacyIdentity)
        }
        require(parts.size == layout.size) { "Invalid entity key arity" }
        val components = parts.zip(layout).map { (value, type) ->
            when (type) {
                ComponentType.Number -> {
                    val parsed = value.toLongOrNull()
                    require(parsed != null && parsed.toString() == value) { "Noncanonical entity number" }
                    AppSyncCanonicalEntityKey.Component.Number(parsed)
                }
                ComponentType.Token -> AppSyncCanonicalEntityKey.Component.Token(value)
            }
        }
        return AppSyncCanonicalEntityKey(domainId, components).also(::validate)
    }

    fun validate(key: AppSyncCanonicalEntityKey) {
        val layout = requireNotNull(layouts[key.domainId]) { "Unknown entity key domain" }
        require(key.components.size == layout.size) { "Invalid entity key arity" }
        key.components.zip(layout).forEach { (component, type) ->
            when (type) {
                ComponentType.Number -> require(component is AppSyncCanonicalEntityKey.Component.Number) { "Invalid entity key type" }
                ComponentType.Token -> {
                    require(component is AppSyncCanonicalEntityKey.Component.Token) { "Invalid entity key type" }
                    val text = component.value
                    require(text.isNotBlank() && text.length <= 1024 && text.none { it.code < 32 || it.code == 127 } &&
                        text.encodeToByteArray(throwOnInvalidSequence = true).size <= 1024) { "Invalid entity key token" }
                }
            }
        }
        val parts = key.components.map(::legacyPart)
        when (key.domainId) {
            1 -> require(parts.single().lowercase() in AppSyncCanonicalSettings.entries) { "Unknown setting key" }
            2, 6, 7, 15, 16 -> require('|' !in parts[0]) { "Ambiguous entity key token" }
            3, 12, 13, 17 -> require(parts.single().length == 16 && parts.single().all { it in '0'..'9' || it in 'a'..'f' }) {
                "Invalid entity fingerprint"
            }
            8 -> require(parts[1] in setOf("Normal", "Novel") && parts[3] in setOf("Direct", "TagCatalog", "RssCatalog")) {
                "Unknown thread identity enum"
            }
        }
    }

    fun legacyIdentity(key: AppSyncCanonicalEntityKey): String {
        validate(key)
        val parts = key.components.map(::legacyPart)
        return when (key.domainId) {
            3, 12, 13 -> "rss:${parts.single()}"
            17 -> "event:${parts.single()}"
            18 -> "fid:${parts.single()}"
            19 -> "category:${parts.single()}"
            else -> parts.joinToString("|")
        }
    }

    /** Only identity-derived fields; parent joins and discriminator evidence are separate. */
    fun derivedFields(key: AppSyncCanonicalEntityKey): Map<String, String?> {
        validate(key)
        val parts = key.components.map(::legacyPart)
        return when (key.domainId) {
            1 -> mapOf("type" to when (AppSyncCanonicalSettings.entries.getValue(parts.single().lowercase()).type) {
                AppSyncValueType.Integer -> "int"
                AppSyncValueType.Decimal -> "float"
                AppSyncValueType.Boolean -> "bool"
                AppSyncValueType.Enum -> "enum"
                AppSyncValueType.Text, AppSyncValueType.Identifier -> "string"
            })
            2, 6 -> mapOf("targetType" to parts[0], "targetId" to parts[1], "authorId" to parts[2])
            7 -> mapOf("targetType" to parts[0], "parentId" to parts[1], "targetId" to parts[2])
            8 -> mapOf("threadId" to parts[0], "threadType" to parts[1], "authorId" to parts[2], "historyOrigin" to parts[3])
            9 -> mapOf("postId" to parts[0])
            10, 11 -> mapOf("tagId" to parts[0])
            12, 13 -> mapOf("subscriptionSyncId" to legacyIdentity(key))
            14 -> mapOf("dateKey" to parts[0])
            15, 16 -> mapOf("targetType" to parts[0], "targetId" to parts[1], "authorId" to parts[2],
                (if (key.domainId == 15) "categorySyncId" else "collectionSyncId") to parts[3])
            17 -> mapOf("sourceFingerprint" to parts[0])
            18 -> mapOf("fid" to parts[0])
            19 -> mapOf("categorySyncId" to parts[0])
            else -> emptyMap()
        }
    }

    private fun withoutPrefix(value: String, prefix: String): String {
        require(value.startsWith(prefix)) { "Invalid entity key prefix" }
        return value.removePrefix(prefix)
    }
    private fun legacyPart(component: AppSyncCanonicalEntityKey.Component) = when (component) {
        is AppSyncCanonicalEntityKey.Component.Number -> component.value.toString()
        is AppSyncCanonicalEntityKey.Component.Token -> component.value
    }
}
