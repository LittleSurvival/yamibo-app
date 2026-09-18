package me.thenano.yamibo.yamibo_app.repository.appsync.schema

import me.thenano.yamibo.yamibo_app.repository.appsync.operation.SyncOperationKind
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8

internal data class AppSyncCanonicalProjection(
    val domainId: Int,
    val entityId: String,
    val generation: Long,
    val fields: Map<Int, AppSyncCanonicalOperation> = emptyMap(),
    val relation: AppSyncCanonicalOperation? = null,
    val tombstone: AppSyncCanonicalOperation? = null,
) {
    fun values(): Map<Int, AppSyncCanonicalValue> = fields.mapValues { (field, operation) -> operation.fields.getValue(field) }
}

internal data class AppSyncCanonicalCheckpoint(
    val checkpointId: String,
    val accountBinding: String,
    val createdAtEpochMillis: Long,
    val coverage: Map<String, Long>,
    val entities: List<AppSyncCanonicalProjection>,
    val authorizations: List<AppSyncCanonicalDeleteProof> = emptyList(),
)

/**
 * Canonical checkpoint root: raw operation block plus provenance indexes; no nested compression
 * or snapshot copies. A reader must still use the canonical materializer, not a legacy snapshot.
 */
internal class AppSyncCanonicalCheckpointCodec(
    private val maximumBytes: Int = 16 * 1024 * 1024,
    private val maximumEntities: Int = 100_000,
    private val maximumReferences: Int = 250_000,
    private val operationCodec: AppSyncCanonicalOperationBlockCodec = AppSyncCanonicalOperationBlockCodec(),
) {
    init { require(maximumBytes > 0 && maximumEntities > 0 && maximumReferences > 0) }

    fun encode(checkpoint: AppSyncCanonicalCheckpoint): ByteString {
        require(checkpoint.entities.size <= maximumEntities) { "Checkpoint entity limit exceeded" }
        require(checkpoint.coverage.size <= 100_000) { "Checkpoint coverage limit exceeded" }
        val entities = checkpoint.entities.sortedWith(entityOrder)
        entities.zipWithNext().forEach { (a, b) -> require(entityOrder.compare(a, b) != 0) { "Duplicate checkpoint entity" } }
        val unique = linkedMapOf<Triple<String, String, Long>, AppSyncCanonicalOperation>()
        var references = 0L
        entities.forEach { entity ->
            validateProjection(entity)
            val winners = entity.winners()
            references += winners.size
            require(references <= maximumReferences) { "Checkpoint reference limit exceeded" }
            winners.forEach { operation ->
                require((checkpoint.coverage["${operation.deviceId}:${operation.deviceEpoch}"] ?: 0) >= operation.sequence) {
                    "Checkpoint does not cover winning operation"
                }
                val identity = operation.identity()
                val prior = unique[identity]
                require(prior == null || prior == operation) { "Conflicting operation identity" }
                unique[identity] = operation
            }
        }
        val operations = unique.values.sortedWith(operationOrder)
        val indexes = operations.withIndex().associate { it.value.identity() to it.index }
        val block = operationCodec.encode(AppSyncCanonicalOperationBlock(checkpoint.accountBinding, operations, checkpoint.authorizations))
        val output = Buffer().write(MAGIC).writeByte(1)
        output.text(checkpoint.checkpointId)
        output.signed(checkpoint.createdAtEpochMillis)
        output.uint(checkpoint.coverage.size.toULong())
        checkpoint.coverage.entries.sortedBy { it.key }.forEach { (replica, sequence) ->
            require(sequence >= 0) { "Negative checkpoint watermark" }
            output.text(replica)
            output.uint(sequence.toULong())
            checkSize(output)
        }
        output.uint(block.size.toULong())
        output.write(block)
        checkSize(output)
        output.uint(entities.size.toULong())
        entities.forEach { entity ->
            output.uint(4uL)
            // An owner index supplies the domain, structured key and generation without repetition.
            output.uint(entity.winners().minOf { indexes.getValue(it.identity()) }.toULong())
            output.uint(entity.fields.size.toULong())
            entity.fields.entries.sortedBy { it.key }.forEach { (field, operation) ->
                output.uint(field.toULong())
                output.uint(indexes.getValue(operation.identity()).toULong())
            }
            output.uint(entity.relation?.let { indexes.getValue(it.identity()).toULong() + 1uL } ?: 0uL)
            output.uint(entity.tombstone?.let { indexes.getValue(it.identity()).toULong() + 1uL } ?: 0uL)
            checkSize(output)
        }
        checkSize(output)
        return output.readByteString()
    }

    fun decode(expectedAccount: String, expectedId: String, bytes: ByteString): AppSyncCanonicalCheckpoint = try {
        require(bytes.size <= maximumBytes) { "Checkpoint byte limit exceeded" }
        val source = Buffer().write(bytes)
        require(source.readByteString(4) == MAGIC && source.readByte().toInt() == 1) { "Unknown canonical checkpoint" }
        val id = source.text()
        require(id == expectedId) { "Checkpoint identity mismatch" }
        val createdAt = source.signed()
        val coverage = linkedMapOf<String, Long>()
        var previousReplica: String? = null
        repeat(source.count(100_000)) {
            val replica = source.text()
            require(previousReplica == null || previousReplica < replica) { "Noncanonical checkpoint coverage" }
            previousReplica = replica
            val sequence = source.uint()
            require(sequence <= Long.MAX_VALUE.toULong()) { "Checkpoint watermark overflow" }
            coverage[replica] = sequence.toLong()
        }
        val blockBytes = source.readByteString(source.count(maximumBytes).toLong())
        val block = operationCodec.decode(expectedAccount, blockBytes)
        val operations = block.operations
        fun operation(index: ULong): AppSyncCanonicalOperation {
            require(index < operations.size.toULong()) { "Checkpoint operation index out of bounds" }
            return operations[index.toInt()]
        }
        val entities = ArrayList<AppSyncCanonicalProjection>()
        var references = 0L
        repeat(source.count(maximumEntities)) {
            require(source.uint() == 4uL) { "Invalid checkpoint tuple arity" }
            val owner = operation(source.uint())
            val schema = AppSyncCanonicalSchema.domainsById.getValue(owner.domainId)
            val fieldCount = source.count(schema.fields.size)
            references += fieldCount
            require(references <= maximumReferences) { "Checkpoint reference limit exceeded" }
            val fields = linkedMapOf<Int, AppSyncCanonicalOperation>()
            var previousId = 0
            repeat(fieldCount) {
                val field = source.uint()
                require(field <= Int.MAX_VALUE.toULong() && field.toInt() > previousId) { "Noncanonical checkpoint field order" }
                previousId = field.toInt()
                fields[field.toInt()] = operation(source.uint())
            }
            val relation = source.uint().let { if (it == 0uL) null else operation(it - 1uL) }
            val tombstone = source.uint().let { if (it == 0uL) null else operation(it - 1uL) }
            references += (if (relation == null) 0 else 1) + (if (tombstone == null) 0 else 1)
            require(references <= maximumReferences) { "Checkpoint reference limit exceeded" }
            val entity = AppSyncCanonicalProjection(owner.domainId, owner.entityId, owner.generation, fields, relation, tombstone)
            validateProjection(entity)
            require(entities.isEmpty() || entityOrder.compare(entities.last(), entity) < 0) { "Noncanonical checkpoint entity order" }
            entities += entity
        }
        require(source.exhausted()) { "Trailing checkpoint bytes" }
        val result = AppSyncCanonicalCheckpoint(id, block.accountBinding, createdAt, coverage, entities, block.authorizations)
        // Enforces unique/minimal operation tables, owner references, proof reachability and coverage.
        require(encode(result) == bytes) { "Noncanonical checkpoint" }
        result
    } catch (_: Exception) {
        throw IllegalArgumentException("Invalid canonical checkpoint")
    }

    private fun validateProjection(entity: AppSyncCanonicalProjection) {
        require(entity.generation > 0) { "Invalid checkpoint generation" }
        AppSyncCanonicalEntityKeys.parse(entity.domainId, entity.entityId)
        val winners = entity.winners()
        require(winners.isNotEmpty()) { "Empty checkpoint entity" }
        require(winners.all { it.domainId == entity.domainId && it.entityId == entity.entityId && it.generation == entity.generation }) {
            "Checkpoint provenance belongs to another entity"
        }
        require(entity.tombstone == null || (entity.fields.isEmpty() && entity.relation == null && entity.tombstone.kind == SyncOperationKind.Delete)) {
            "Invalid checkpoint tombstone"
        }
        if (entity.domainId == 15 || entity.domainId == 16) {
            require(entity.relation != null && entity.tombstone == null) { "Missing relation provenance" }
            require(entity.relation.kind in setOf(SyncOperationKind.RelationAdd, SyncOperationKind.RelationRemove)) { "Invalid relation provenance" }
        } else require(entity.relation == null) { "Unexpected relation provenance" }
        val schema = AppSyncCanonicalSchema.domainsById.getValue(entity.domainId)
        entity.fields.forEach { (field, op) ->
            require(schema.fieldsById[field]?.portable == true && field in op.fields &&
                op.kind in setOf(SyncOperationKind.Put, SyncOperationKind.Patch, SyncOperationKind.RelationAdd)) {
                "Invalid checkpoint field provenance"
            }
        }
    }
    private fun checkSize(buffer: Buffer) { require(buffer.size <= maximumBytes) { "Checkpoint byte limit exceeded" } }
    private fun Buffer.text(value: String) {
        require(value.isNotBlank() && value.length <= 1024 && value.none { it.code < 32 || it.code == 127 }) { "Invalid checkpoint identity" }
        val bytes = value.encodeToByteArray(throwOnInvalidSequence = true)
        require(bytes.size <= 1024) { "Checkpoint identity budget exceeded" }
        uint(bytes.size.toULong()); write(bytes)
    }
    private fun Buffer.text(): String = readByteArray(count(1024).toLong()).decodeToString(throwOnInvalidSequence = true)
    private fun Buffer.count(limit: Int): Int = uint().also {
        require(it <= limit.toULong() && it <= size.toULong()) { "Checkpoint allocation limit exceeded" }
    }.toInt()
    private fun Buffer.signed(value: Long) = uint(((value shl 1) xor (value shr 63)).toULong())
    private fun Buffer.signed(): Long = uint().let { (it shr 1).toLong() xor -(it and 1uL).toLong() }
    private fun Buffer.uint(value: ULong) {
        var remaining = value
        do {
            val next = (remaining and 127uL).toInt(); remaining = remaining shr 7
            writeByte(next or if (remaining == 0uL) 0 else 128)
        } while (remaining != 0uL)
    }
    private fun Buffer.uint(): ULong {
        var result = 0uL
        for (index in 0..9) {
            val next = readByte().toInt() and 255
            require(index < 9 || next <= 1) { "Checkpoint integer overflow" }
            result = result or ((next and 127).toULong() shl (index * 7))
            if (next and 128 == 0) { require(index == 0 || next != 0) { "Nonminimal checkpoint integer" }; return result }
        }
        error("Unterminated checkpoint integer")
    }
    private companion object {
        val MAGIC = "YCP3".encodeUtf8()
        val entityOrder = compareBy<AppSyncCanonicalProjection>({ it.domainId }, { it.entityId }, { it.generation })
        val operationOrder = compareBy<AppSyncCanonicalOperation>({ it.deviceId }, { it.deviceEpoch }, { it.sequence })
        fun AppSyncCanonicalOperation.identity() = Triple(deviceId, deviceEpoch, sequence)
        fun AppSyncCanonicalProjection.winners() = fields.values.toList() + listOfNotNull(relation, tombstone)
    }
}
