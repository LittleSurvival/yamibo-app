package me.thenano.yamibo.yamibo_app.repository.appsync.schema

import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperationKind
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperationOrigin
import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncDomainId
import me.thenano.yamibo.yamibo_app.repository.appsync.domain.SyncDomainRegistry
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8

/** Intermediate portable operations, not reducer-ready legacy operations or a complete journal. */
internal data class AppSyncCanonicalOperation(
    val deviceId: String,
    val deviceEpoch: String,
    val sequence: Long,
    val domainId: Int,
    val entityId: String,
    val generation: Long,
    val kind: SyncOperationKind,
    val createdAtEpochMillis: Long,
    val origin: SyncOperationOrigin,
    val authorizationId: String? = null,
    val causalContext: Map<String, Long> = emptyMap(),
    val fields: Map<Int, AppSyncCanonicalValue> = emptyMap(),
)

internal data class AppSyncCanonicalOperationBlock(
    val accountBinding: String,
    val operations: List<AppSyncCanonicalOperation>,
    val authorizations: List<AppSyncCanonicalDeleteProof> = emptyList(),
)

internal data class AppSyncCanonicalDeleteProof(
    val authorizationId: String,
    val domainId: Int,
    val scope: String,
    val operationCount: Long,
    val expiresAtEpochMillis: Long,
)

/**
 * Internal block revision 2: YOB3, revision, string table, account, proof table, operation count, 12-tuples.
 * Entity keys are typed component tuples; deletion proofs are stored once per batch. This is not the v3 document root;
 * journal/checkpoint metadata and production adapters must be supplied before publication.
 * Explicit numeric IDs never depend on enum ordinals. All errors omit payload content.
 */
internal class AppSyncCanonicalOperationBlockCodec(
    private val maximumBytes: Int = 8 * 1024 * 1024,
    private val maximumExpandedBytes: Int = 16 * 1024 * 1024,
    private val maximumOperations: Int = 100_000,
    private val maximumStrings: Int = 100_000,
) {
    init {
        require(maximumBytes > 0 && maximumExpandedBytes > 0 && maximumOperations > 0 && maximumStrings > 0)
    }

    fun encode(block: AppSyncCanonicalOperationBlock): ByteString {
        require(block.operations.size <= maximumOperations) { "Operation count exceeded" }
        require(block.authorizations.size <= maximumOperations) { "Authorization count exceeded" }
        verifyIdentity(block.accountBinding)
        var expanded = block.accountBinding.encodeToByteArray().size.toLong()
        val counts = mutableMapOf<String, Int>()
        fun count(value: String) { counts[value] = (counts[value] ?: 0) + 1 }
        count(block.accountBinding)
        val operations = block.operations.sortedWith(order)
        val proofs = block.authorizations.sortedBy { it.authorizationId }
        verifyProofs(proofs, operations)
        proofs.forEach {
            count(it.authorizationId)
            count(it.scope)
            expanded += it.authorizationId.encodeToByteArray().size + it.scope.encodeToByteArray().size + 48L
            require(expanded <= maximumExpandedBytes) { "Expanded operation budget exceeded" }
        }
        operations.zipWithNext().forEach { (a, b) -> require(order.compare(a, b) != 0) { "Duplicate operation identity" } }
        operations.forEach { operation ->
            expanded += verify(operation)
            require(expanded <= maximumExpandedBytes) { "Expanded operation budget exceeded" }
            count(operation.deviceId)
            count(operation.deviceEpoch)
            AppSyncCanonicalEntityKeys.parse(operation.domainId, operation.entityId).components.forEach {
                if (it is AppSyncCanonicalEntityKey.Component.Token) count(it.value)
            }
            operation.authorizationId?.let(::count)
            operation.causalContext.keys.forEach(::count)
            operation.fields.values.forEach { value -> stringValue(value)?.let(::count) }
        }
        require(expanded <= maximumExpandedBytes) { "Expanded operation budget exceeded" }
        val strings = AppSyncCanonicalStringTable.select(counts)
        require(strings.entries.size <= maximumStrings) { "String table count exceeded" }
        val output = Buffer().write(MAGIC).writeByte(2)
        output.uint(strings.entries.size.toULong())
        strings.entries.forEach { output.text(it); checkSize(output) }
        output.string(block.accountBinding, strings)
        output.uint(proofs.size.toULong())
        proofs.forEach { proof ->
            output.uint(5uL)
            output.string(proof.authorizationId, strings)
            output.uint(proof.domainId.toULong())
            output.string(proof.scope, strings)
            output.uint(proof.operationCount.toULong())
            output.uint(((proof.expiresAtEpochMillis shl 1) xor (proof.expiresAtEpochMillis shr 63)).toULong())
            checkSize(output)
        }
        output.uint(operations.size.toULong())
        operations.forEach { op ->
            output.uint(12uL)
            output.string(op.deviceId, strings)
            output.string(op.deviceEpoch, strings)
            output.uint(op.sequence.toULong())
            output.uint(op.domainId.toULong())
            output.entityKey(AppSyncCanonicalEntityKeys.parse(op.domainId, op.entityId), strings)
            output.uint(op.generation.toULong())
            output.uint(kindId(op.kind).toULong())
            output.uint(((op.createdAtEpochMillis shl 1) xor (op.createdAtEpochMillis shr 63)).toULong())
            output.uint(originId(op.origin).toULong())
            if (op.authorizationId == null) output.writeByte(0) else output.string(op.authorizationId, strings)
            output.uint(op.causalContext.size.toULong())
            op.causalContext.entries.sortedBy { it.key }.forEach { (replica, sequence) ->
                output.string(replica, strings)
                output.uint(sequence.toULong())
            }
            val fields = AppSyncCanonicalFieldCodec.encode(domain(op.domainId), op.entityId, op.fields, strings)
            output.uint(fields.size.toULong())
            output.write(fields)
            checkSize(output)
        }
        checkSize(output)
        return output.readByteString()
    }

    fun decode(expectedAccount: String, bytes: ByteString): AppSyncCanonicalOperationBlock = try {
        require(bytes.size <= maximumBytes) { "Operation block budget exceeded" }
        val source = Buffer().write(bytes)
        require(source.readByteString(4) == MAGIC && source.readByte().toInt() == 2) { "Unknown operation block" }
        val stringCount = source.count(maximumStrings)
        val table = ArrayList<String>()
        repeat(stringCount) {
            val value = source.text(128 * 1024)
            require(table.isEmpty() || table.last() < value) { "Noncanonical string table" }
            table += value
        }
        val strings = AppSyncCanonicalStringTable(table)
        val account = source.string(strings)
        require(account == expectedAccount) { "Operation block account mismatch" }
        val proofs = ArrayList<AppSyncCanonicalDeleteProof>()
        var expanded = account.encodeToByteArray().size.toLong()
        repeat(source.count(maximumOperations)) {
            require(source.uint() == 5uL) { "Invalid authorization tuple arity" }
            val id = source.string(strings)
            val domainId = source.uint().also { require(it <= Int.MAX_VALUE.toULong()) }.toInt()
            val scope = source.string(strings)
            val operationCount = source.positive()
            val expiresAt = source.uint().let { (it shr 1).toLong() xor -(it and 1uL).toLong() }
            require(proofs.isEmpty() || proofs.last().authorizationId < id) { "Noncanonical authorization table" }
            proofs += AppSyncCanonicalDeleteProof(id, domainId, scope, operationCount, expiresAt)
            expanded += id.encodeToByteArray().size + scope.encodeToByteArray().size + 48L
            require(expanded <= maximumExpandedBytes) { "Expanded operation budget exceeded" }
        }
        val count = source.count(maximumOperations)
        val operations = ArrayList<AppSyncCanonicalOperation>()
        repeat(count) {
            require(source.uint() == 12uL) { "Invalid operation tuple arity" }
            val device = source.string(strings)
            val epoch = source.string(strings)
            val sequence = source.positive()
            val domainId = source.uint().also { require(it <= Int.MAX_VALUE.toULong()) }.toInt()
            val domainName = domain(domainId)
            val entity = source.entityKey(domainId, strings).legacyIdentity()
            val generation = source.positive()
            val kind = when (source.uint()) {
                1uL -> SyncOperationKind.Put
                2uL -> SyncOperationKind.Patch
                3uL -> SyncOperationKind.Delete
                4uL -> SyncOperationKind.RelationAdd
                5uL -> SyncOperationKind.RelationRemove
                else -> error("Unknown operation kind")
            }
            val timestamp = source.uint().let { (it shr 1).toLong() xor -(it and 1uL).toLong() }
            val origin = when (source.uint()) {
                1uL -> SyncOperationOrigin.UserAction
                2uL -> SyncOperationOrigin.Migration
                3uL -> SyncOperationOrigin.RemoteReplay
                else -> error("Unknown operation origin")
            }
            val authorization = if (source[0].toInt() == 0) { source.skip(1); null } else source.string(strings)
            val contextCount = source.count(1024)
            val context = linkedMapOf<String, Long>()
            var previous: String? = null
            repeat(contextCount) {
                val replica = source.string(strings)
                require(previous == null || previous < replica) { "Noncanonical causal context" }
                previous = replica
                val watermark = source.uint()
                require(watermark <= Long.MAX_VALUE.toULong()) { "Invalid causal watermark" }
                context[replica] = watermark.toLong()
            }
            val fieldSize = source.count(AppSyncCanonicalSchema.ENTITY_BYTES)
            val fields = AppSyncCanonicalFieldCodec.decode(domainName, entity, source.readByteArray(fieldSize.toLong()), strings)
            val operation = AppSyncCanonicalOperation(device, epoch, sequence, domainId, entity, generation,
                kind, timestamp, origin, authorization, context, fields)
            expanded += verify(operation)
            require(expanded <= maximumExpandedBytes) { "Expanded operation budget exceeded" }
            require(operations.isEmpty() || order.compare(operations.last(), operation) < 0) { "Noncanonical operation order" }
            operations += operation
        }
        require(source.exhausted()) { "Trailing operation block bytes" }
        val result = AppSyncCanonicalOperationBlock(account, operations, proofs)
        // Also rejects unused tables, needless references, inline aliases and alternate dictionary choices.
        require(encode(result) == bytes) { "Noncanonical operation block" }
        result
    } catch (_: Exception) {
        throw IllegalArgumentException("Invalid canonical operation block")
    }

    private fun verify(op: AppSyncCanonicalOperation): Long {
        verifyIdentity(op.deviceId); verifyIdentity(op.deviceEpoch)
        AppSyncCanonicalEntityKeys.parse(op.domainId, op.entityId)
        require(op.kind in requireNotNull(SyncDomainRegistry.Default.contractFor(SyncDomainId(domain(op.domainId)))).allowedKinds) {
            "Operation kind is not allowed by canonical domain"
        }
        op.authorizationId?.let(::verifyIdentity)
        require(op.sequence > 0 && op.generation > 0) { "Invalid operation identity" }
        require(op.causalContext.size <= 1024) { "Causal context count exceeded" }
        op.causalContext.forEach { (key, value) -> verifyIdentity(key); require(value >= 0) { "Invalid causal watermark" } }
        if (op.kind == SyncOperationKind.Delete || op.kind == SyncOperationKind.RelationRemove) {
            require(op.fields.isEmpty()) { "Destructive operation must have empty fields" }
            require(op.origin != SyncOperationOrigin.Migration) { "Destructive operation requires authority" }
        }
        require(op.kind != SyncOperationKind.Patch || op.fields.isNotEmpty()) { "Empty canonical patch" }
        val fields = AppSyncCanonicalFieldCodec.encode(domain(op.domainId), op.entityId, op.fields)
        return fields.size.toLong() + 96 + op.deviceId.encodeToByteArray().size + op.deviceEpoch.encodeToByteArray().size +
            op.entityId.encodeToByteArray().size + (op.authorizationId?.encodeToByteArray()?.size ?: 0) +
            op.causalContext.keys.sumOf { it.encodeToByteArray().size.toLong() + 16 }
    }

    private fun verifyIdentity(value: String) {
        require(value.isNotBlank() && value.length <= 1024 && value.none { it.code < 32 || it.code == 127 } &&
            value.encodeToByteArray(throwOnInvalidSequence = true).size <= 1024) { "Invalid canonical identity" }
    }
    private fun verifyProofs(proofs: List<AppSyncCanonicalDeleteProof>, operations: List<AppSyncCanonicalOperation>) {
        require(proofs.size <= maximumOperations) { "Authorization count exceeded" }
        val byId = proofs.associateBy { it.authorizationId }
        require(byId.size == proofs.size) { "Duplicate authorization identity" }
        val references = operations.filter { it.authorizationId != null }.groupBy { it.authorizationId }
        require(references.keys == byId.keys) { "Missing or unused authorization proof" }
        proofs.forEach { proof ->
            verifyIdentity(proof.authorizationId); verifyIdentity(proof.scope); domain(proof.domainId)
            require(proof.operationCount > 0) { "Invalid authorization count" }
            val deletes = references.getValue(proof.authorizationId)
            require(deletes.size.toLong() <= proof.operationCount && deletes.all {
                it.kind == SyncOperationKind.Delete && it.domainId == proof.domainId &&
                    it.createdAtEpochMillis <= proof.expiresAtEpochMillis
            }) { "Authorization proof mismatch" }
        }
    }
    private fun domain(id: Int) = requireNotNull(AppSyncCanonicalSchema.domainsById[id]) { "Unknown canonical domain" }.name
    private fun Buffer.entityKey(key: AppSyncCanonicalEntityKey, strings: AppSyncCanonicalStringTable) {
        uint(key.components.size.toULong())
        key.components.forEach { component ->
            when (component) {
                is AppSyncCanonicalEntityKey.Component.Number -> uint(((component.value shl 1) xor (component.value shr 63)).toULong())
                is AppSyncCanonicalEntityKey.Component.Token -> string(component.value, strings)
            }
        }
    }
    private fun Buffer.entityKey(domainId: Int, strings: AppSyncCanonicalStringTable): AppSyncCanonicalEntityKey {
        val layout = requireNotNull(AppSyncCanonicalEntityKeys.layouts[domainId]) { "Unknown entity key domain" }
        require(count(4) == layout.size) { "Invalid entity key arity" }
        val components = layout.map { type ->
            when (type) {
                AppSyncCanonicalEntityKeys.ComponentType.Number -> uint().let {
                    AppSyncCanonicalEntityKey.Component.Number((it shr 1).toLong() xor -(it and 1uL).toLong())
                }
                AppSyncCanonicalEntityKeys.ComponentType.Token -> AppSyncCanonicalEntityKey.Component.Token(string(strings))
            }
        }
        return AppSyncCanonicalEntityKey(domainId, components).also(AppSyncCanonicalEntityKeys::validate)
    }
    private fun checkSize(buffer: Buffer) { require(buffer.size <= maximumBytes) { "Operation block budget exceeded" } }
    private fun Buffer.text(value: String) { val bytes = value.encodeToByteArray(throwOnInvalidSequence = true); uint(bytes.size.toULong()); write(bytes) }
    private fun Buffer.text(limit: Int): String = readByteArray(count(limit).toLong()).decodeToString(throwOnInvalidSequence = true)
    private fun Buffer.string(value: String, table: AppSyncCanonicalStringTable) {
        val index = table.indexOf(value)
        if (index == null) { writeByte(1); text(value) } else { writeByte(2); uint(index.toULong()) }
    }
    private fun Buffer.string(table: AppSyncCanonicalStringTable): String {
        val value = when (readByte().toInt()) {
            1 -> text(1024)
            2 -> table.resolve(uint())
            else -> error("Invalid canonical identity token")
        }
        verifyIdentity(value)
        return value
    }
    private fun Buffer.count(limit: Int): Int {
        val value = uint()
        require(value <= limit.toULong() && value <= size.toULong()) { "Canonical allocation limit exceeded" }
        return value.toInt()
    }
    private fun Buffer.positive(): Long = uint().also { require(it in 1uL..Long.MAX_VALUE.toULong()) }.toLong()
    private fun Buffer.uint(value: ULong) {
        var remaining = value
        do { val next = (remaining and 127uL).toInt(); remaining = remaining shr 7
            writeByte(next or if (remaining == 0uL) 0 else 128)
        } while (remaining != 0uL)
    }
    private fun Buffer.uint(): ULong {
        var result = 0uL
        for (index in 0..9) {
            val next = readByte().toInt() and 255
            require(index < 9 || next <= 1) { "Canonical integer overflow" }
            result = result or ((next and 127).toULong() shl (index * 7))
            if (next and 128 == 0) { require(index == 0 || next != 0) { "Nonminimal canonical integer" }; return result }
        }
        error("Unterminated canonical integer")
    }

    private companion object {
        val MAGIC = "YOB3".encodeUtf8()
        val order = compareBy<AppSyncCanonicalOperation>({ it.deviceId }, { it.deviceEpoch }, { it.sequence })
        fun kindId(kind: SyncOperationKind): Int = when (kind) {
            SyncOperationKind.Put -> 1; SyncOperationKind.Patch -> 2; SyncOperationKind.Delete -> 3
            SyncOperationKind.RelationAdd -> 4; SyncOperationKind.RelationRemove -> 5
        }
        fun originId(origin: SyncOperationOrigin): Int = when (origin) {
            SyncOperationOrigin.UserAction -> 1; SyncOperationOrigin.Migration -> 2; SyncOperationOrigin.RemoteReplay -> 3
        }
        fun stringValue(value: AppSyncCanonicalValue): String? = when (value) {
            is AppSyncCanonicalValue.Text -> value.value
            is AppSyncCanonicalValue.Identifier -> value.value
            is AppSyncCanonicalValue.Enum -> value.value
            else -> null
        }
    }
}
