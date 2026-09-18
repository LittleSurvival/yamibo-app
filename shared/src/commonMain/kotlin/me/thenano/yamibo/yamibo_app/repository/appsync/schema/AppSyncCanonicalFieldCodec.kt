package me.thenano.yamibo.yamibo_app.repository.appsync.schema

import okio.Buffer

/**
 * Canonical field tuples: count, then ascending (field ID, scalar tag, value).
 * Positive IDs/counts/lengths use minimal unsigned LEB128, integers zig-zag LEB128, finite
 * decimals IEEE-754 binary64 big-endian, strings length-prefixed strict UTF-8. Null has tag 0.
 * Domain and entity identity belong to the enclosing operation, never to this field body.
 * All currently assigned field IDs fit one byte. No compression or Base64 is performed here.
 */
internal object AppSyncCanonicalFieldCodec {
    fun encode(domain: String, entityId: String, fields: Map<Int, AppSyncCanonicalValue>,
        strings: AppSyncCanonicalStringTable = AppSyncCanonicalStringTable.EMPTY): ByteArray {
        verify(domain, entityId, fields)
        val output = Buffer()
        output.writeUnsigned(fields.size.toULong())
        fields.entries.sortedBy { it.key }.forEach { (id, value) ->
            output.writeUnsigned(id.toULong())
            when (value) {
                AppSyncCanonicalValue.Null -> output.writeByte(0)
                is AppSyncCanonicalValue.Text -> output.writeText(AppSyncValueType.Text, value.value, strings)
                is AppSyncCanonicalValue.Identifier -> output.writeText(AppSyncValueType.Identifier, value.value, strings)
                is AppSyncCanonicalValue.Enum -> output.writeText(AppSyncValueType.Enum, value.value, strings)
                is AppSyncCanonicalValue.Integer -> {
                    output.writeByte(AppSyncValueType.Integer.tag)
                    output.writeUnsigned(((value.value shl 1) xor (value.value shr 63)).toULong())
                }
                is AppSyncCanonicalValue.Decimal -> {
                    output.writeByte(AppSyncValueType.Decimal.tag)
                    output.writeLong(value.value.toBits())
                }
                is AppSyncCanonicalValue.Boolean -> {
                    output.writeByte(AppSyncValueType.Boolean.tag)
                    output.writeByte(if (value.value) 1 else 0)
                }
            }
        }
        require(output.size <= AppSyncCanonicalSchema.ENTITY_BYTES) { "Canonical field byte budget exceeded" }
        return output.readByteArray()
    }

    fun decode(domain: String, entityId: String, bytes: ByteArray,
        strings: AppSyncCanonicalStringTable = AppSyncCanonicalStringTable.EMPTY): Map<Int, AppSyncCanonicalValue> {
        require(bytes.size <= AppSyncCanonicalSchema.ENTITY_BYTES) { "Canonical field byte budget exceeded" }
        val schema = requireNotNull(AppSyncCanonicalSchema.domains[domain]) { "Unknown canonical domain" }
        val source = Buffer().write(bytes)
        val count = source.readUnsigned()
        require(count <= schema.fields.size.toULong()) { "Canonical field count exceeded" }
        val fields = linkedMapOf<Int, AppSyncCanonicalValue>()
        var previousId = 0
        repeat(count.toInt()) {
            val rawId = source.readUnsigned()
            require(rawId <= Int.MAX_VALUE.toULong()) { "Invalid canonical field ID" }
            val id = rawId.toInt()
            require(id > previousId) { "Duplicate or unordered canonical fields" }
            val field = requireNotNull(schema.fieldsById[id]?.takeIf { it.portable }) { "Excluded canonical field" }
            previousId = id
            val wireTag = source.readByte().toInt() and 255
            val reference = wireTag and 128 != 0
            val tag = wireTag and 127
            require(!reference || tag in 1..3) { "Invalid canonical reference tag" }
            val value = when (tag) {
                0 -> AppSyncCanonicalValue.Null
                AppSyncValueType.Text.tag, AppSyncValueType.Identifier.tag, AppSyncValueType.Enum.tag -> {
                    val text = if (reference) strings.resolve(source.readUnsigned()) else {
                        val length = source.readUnsigned()
                        require(length <= field.maxUtf8Bytes.toULong() && length <= source.size.toULong()) {
                            "Canonical string length exceeded"
                        }
                        source.readByteArray(length.toLong()).decodeToString(throwOnInvalidSequence = true)
                    }
                    require(text.encodeToByteArray().size <= field.maxUtf8Bytes) { "Canonical string length exceeded" }
                    when (tag) {
                        AppSyncValueType.Text.tag -> AppSyncCanonicalValue.Text(text)
                        AppSyncValueType.Identifier.tag -> AppSyncCanonicalValue.Identifier(text)
                        else -> AppSyncCanonicalValue.Enum(text)
                    }
                }
                AppSyncValueType.Integer.tag -> source.readUnsigned().let {
                    AppSyncCanonicalValue.Integer((it shr 1).toLong() xor -(it and 1uL).toLong())
                }
                AppSyncValueType.Decimal.tag -> AppSyncCanonicalValue.Decimal(Double.fromBits(source.readLong()))
                AppSyncValueType.Boolean.tag -> {
                    val boolean = source.readByte().toInt()
                    require(boolean in 0..1) { "Invalid canonical boolean" }
                    AppSyncCanonicalValue.Boolean(boolean == 1)
                }
                else -> throw IllegalArgumentException("Unknown canonical scalar tag")
            }
            fields[id] = value
        }
        require(source.exhausted()) { "Trailing canonical field bytes" }
        verify(domain, entityId, fields)
        return fields
    }

    private fun verify(domain: String, entityId: String, fields: Map<Int, AppSyncCanonicalValue>) {
        val schema = requireNotNull(AppSyncCanonicalSchema.domains[domain]) { "Unknown canonical domain" }
        val legacy = fields.entries.associate { (id, value) ->
            requireNotNull(schema.fieldsById[id]) { "Unknown canonical field" }.name to value.legacyValue()
        }
        val normalized = AppSyncCanonicalNormalizer.normalize(domain, entityId, legacy)
        require(normalized is AppSyncCanonicalFieldsResult.Accepted && normalized.exclusions.isEmpty() &&
            normalized.fields == fields) { "Noncanonical or nonportable fields" }
    }

    private fun Buffer.writeText(type: AppSyncValueType, value: String, strings: AppSyncCanonicalStringTable) {
        strings.indexOf(value)?.let { index ->
            writeByte(type.tag or 128)
            writeUnsigned(index.toULong())
            return
        }
        val encoded = value.encodeToByteArray(throwOnInvalidSequence = true)
        writeByte(type.tag)
        writeUnsigned(encoded.size.toULong())
        write(encoded)
    }

    private fun Buffer.writeUnsigned(value: ULong) {
        var remaining = value
        do {
            val next = (remaining and 127uL).toInt()
            remaining = remaining shr 7
            writeByte(next or if (remaining == 0uL) 0 else 128)
        } while (remaining != 0uL)
    }

    private fun Buffer.readUnsigned(): ULong {
        var result = 0uL
        for (index in 0..9) {
            val next = readByte().toInt() and 255
            require(index < 9 || next <= 1) { "Canonical integer overflow" }
            result = result or ((next and 127).toULong() shl (index * 7))
            if (next and 128 == 0) {
                require(index == 0 || next != 0) { "Nonminimal canonical integer" }
                return result
            }
        }
        throw IllegalArgumentException("Unterminated canonical integer")
    }
}
