package me.thenano.yamibo.yamibo_app.repository.appsync.schema

/** Sorted by Kotlin's UTF-16 code-unit ordering on every target. No locale-dependent ordering. */
internal class AppSyncCanonicalStringTable(entries: List<String>) {
    val entries = entries.toList()
    private val indices = this.entries.withIndex().associate { it.value to it.index }

    init {
        require(this.entries.zipWithNext().all { (a, b) -> a < b }) { "Noncanonical string table" }
        this.entries.forEach { it.encodeToByteArray(throwOnInvalidSequence = true) }
    }

    fun indexOf(value: String): Int? = indices[value]
    fun resolve(index: ULong): String {
        require(index < entries.size.toULong()) { "Invalid canonical string reference" }
        return entries[index.toInt()]
    }

    companion object {
        val EMPTY = AppSyncCanonicalStringTable(emptyList())

        /** Conservative cost: maximum possible reference width, plus table framing allowance. */
        fun select(counts: Map<String, Int>): AppSyncCanonicalStringTable {
            val referenceBytes = unsignedSize(counts.size.toULong())
            return AppSyncCanonicalStringTable(counts.filter { (value, count) ->
                val length = value.encodeToByteArray(throwOnInvalidSequence = true).size
                val entryBytes = length.toLong() + unsignedSize(length.toULong())
                count > 1 && (count - 1L) * entryBytes > count.toLong() * referenceBytes + 10
            }.keys.sorted())
        }

        private fun unsignedSize(value: ULong): Int {
            var remaining = value
            var size = 1
            while (remaining >= 128uL) { remaining = remaining shr 7; size++ }
            return size
        }
    }
}
