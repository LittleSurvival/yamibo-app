package me.thenano.yamibo.yamibo_app.thread.reader.components

internal sealed interface CatalogQuery {
    data object Empty : CatalogQuery
    data class Text(val value: String) : CatalogQuery
    data class Floor(val value: Long?) : CatalogQuery
}

internal fun parseCatalogQuery(input: String): CatalogQuery {
    val value = input.trim()
    if (value.isEmpty()) return CatalogQuery.Empty
    if (value.endsWith('#')) {
        val number = value.dropLast(1)
        val digits = number.removePrefix("-").removePrefix("+")
        if (digits.isNotEmpty() && digits.all { it in '0'..'9' }) {
            return CatalogQuery.Floor(number.toLongOrNull())
        }
    }
    return CatalogQuery.Text(value)
}

internal fun catalogFloorPage(floor: Long?, totalPages: Int): Int? {
    if (floor == null || floor <= 0 || floor > totalPages.toLong() * 20L) return null
    return ((floor - 1) / 20 + 1).toInt()
}

/** Ranges use original UTF-16 offsets, also used by AnnotatedString. */
internal fun catalogMatchRanges(text: String, query: String): List<IntRange> {
    if (query.isEmpty()) return emptyList()
    return buildList {
        var start = 0
        while (start < text.length) {
            val index = text.indexOf(query, start, ignoreCase = true)
            if (index < 0) break
            add(index until index + query.length)
            start = index + query.length
        }
    }
}
