package me.thenano.yamibo.yamibo_app.repository.appsync.schema

import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8

internal data class AppSyncCanonicalAcknowledgement(val checkpointId: String, val coverage: Map<String, Long>)

internal data class AppSyncCanonicalJournal(
    val block: AppSyncCanonicalOperationBlock,
    val deviceId: String,
    val deviceEpoch: String,
    val writerNonce: String,
    val firstSequence: Long,
    val lastSequence: Long,
    val observed: Map<String, Long>,
    val acknowledgements: List<AppSyncCanonicalAcknowledgement>,
    val heartbeatAtEpochMillis: Long,
    val protocolReadVersion: Int,
    val protocolWriteVersion: Int,
    val appVersion: String,
    val publishedThroughSequence: Long?,
)

/** Raw journal document, with all protocol metadata and no compression or Base64. */
internal class AppSyncCanonicalJournalCodec(
    private val maximumBytes: Int = 16 * 1024 * 1024,
    private val maximumMetadataEntries: Int = 100_000,
    private val operationCodec: AppSyncCanonicalOperationBlockCodec = AppSyncCanonicalOperationBlockCodec(),
) {
    init { require(maximumBytes > 0 && maximumMetadataEntries > 0) }

    fun encode(journal: AppSyncCanonicalJournal): ByteString {
        validate(journal)
        val output = Buffer().write(MAGIC).writeByte(1)
        output.text(journal.deviceId)
        output.text(journal.deviceEpoch)
        output.text(journal.writerNonce)
        output.uint(journal.firstSequence.toULong())
        output.uint(journal.lastSequence.toULong())
        var entries = journal.observed.size.toLong() + journal.acknowledgements.size
        require(entries <= maximumMetadataEntries) { "Journal metadata limit exceeded" }
        output.coverage(journal.observed)
        output.uint(journal.acknowledgements.size.toULong())
        journal.acknowledgements.sortedBy { it.checkpointId }.forEach { acknowledgement ->
            entries += acknowledgement.coverage.size
            require(entries <= maximumMetadataEntries) { "Journal metadata limit exceeded" }
            output.text(acknowledgement.checkpointId)
            output.coverage(acknowledgement.coverage)
        }
        output.uint(((journal.heartbeatAtEpochMillis shl 1) xor (journal.heartbeatAtEpochMillis shr 63)).toULong())
        output.uint(journal.protocolReadVersion.toULong())
        output.uint(journal.protocolWriteVersion.toULong())
        output.text(journal.appVersion)
        val published = journal.publishedThroughSequence
        output.writeByte(if (published == null) 0 else 1)
        if (published != null) output.uint(published.toULong())
        val block = operationCodec.encode(journal.block)
        output.uint(block.size.toULong())
        output.write(block)
        checkSize(output)
        return output.readByteString()
    }

    fun decode(expectedAccount: String, expectedDevice: String?, expectedEpoch: String?,
        bytes: ByteString): AppSyncCanonicalJournal = try {
        require(bytes.size <= maximumBytes) { "Journal byte limit exceeded" }
        val source = Buffer().write(bytes)
        require(source.readByteString(4) == MAGIC && source.readByte().toInt() == 1) { "Unknown canonical journal" }
        val device = source.text()
        val epoch = source.text()
        require((expectedDevice == null || device == expectedDevice) &&
            (expectedEpoch == null || epoch == expectedEpoch)) { "Journal owner mismatch" }
        val nonce = source.text()
        val first = source.nonnegative()
        val last = source.nonnegative()
        var remainingEntries = maximumMetadataEntries
        fun coverage(): Map<String, Long> {
            val count = source.count(remainingEntries)
            remainingEntries -= count
            val result = linkedMapOf<String, Long>()
            var previous: String? = null
            repeat(count) {
                val replica = source.text()
                require(previous == null || previous < replica) { "Noncanonical journal coverage" }
                previous = replica
                result[replica] = source.nonnegative()
            }
            return result
        }
        val observed = coverage()
        val acknowledgementCount = source.count(remainingEntries)
        remainingEntries -= acknowledgementCount
        val acknowledgements = ArrayList<AppSyncCanonicalAcknowledgement>()
        repeat(acknowledgementCount) {
            val id = source.text()
            require(acknowledgements.isEmpty() || acknowledgements.last().checkpointId < id) { "Noncanonical acknowledgements" }
            acknowledgements += AppSyncCanonicalAcknowledgement(id, coverage())
        }
        val heartbeat = source.uint().let { (it shr 1).toLong() xor -(it and 1uL).toLong() }
        val readVersion = source.version()
        val writeVersion = source.version()
        val appVersion = source.text()
        val published = when (source.readByte().toInt()) {
            0 -> null
            1 -> source.nonnegative()
            else -> error("Invalid published sequence presence")
        }
        val block = operationCodec.decode(expectedAccount, source.readByteString(source.count(maximumBytes).toLong()))
        require(source.exhausted()) { "Trailing journal bytes" }
        val result = AppSyncCanonicalJournal(block, device, epoch, nonce, first, last, observed, acknowledgements,
            heartbeat, readVersion, writeVersion, appVersion, published)
        require(encode(result) == bytes) { "Noncanonical journal" }
        result
    } catch (_: Exception) {
        throw IllegalArgumentException("Invalid canonical journal")
    }

    private fun validate(journal: AppSyncCanonicalJournal) {
        require(journal.firstSequence >= 0 && journal.lastSequence >= 0) { "Negative journal range" }
        require(journal.protocolWriteVersion == 3 && journal.protocolReadVersion >= 3) { "Invalid v3 protocol capability" }
        require(journal.acknowledgements.size <= maximumMetadataEntries &&
            journal.acknowledgements.map { it.checkpointId }.toSet().size == journal.acknowledgements.size) { "Duplicate or excessive acknowledgements" }
        val operations = journal.block.operations.sortedBy { it.sequence }
        require(operations.all { it.deviceId == journal.deviceId && it.deviceEpoch == journal.deviceEpoch }) { "Journal operation owner mismatch" }
        if (operations.isEmpty()) {
            require(journal.firstSequence == 0L && journal.lastSequence == 0L) { "Empty journal must have zero range" }
        } else {
            require(journal.firstSequence == operations.first().sequence && journal.lastSequence == operations.last().sequence) { "Journal range mismatch" }
            operations.zipWithNext().forEach { (left, right) ->
                require(left.sequence < Long.MAX_VALUE && right.sequence == left.sequence + 1) { "Noncontiguous journal sequence" }
            }
        }
        journal.publishedThroughSequence?.let {
            require(it >= journal.lastSequence && it >= (journal.observed["${journal.deviceId}:${journal.deviceEpoch}"] ?: 0)) { "Invalid published watermark" }
        }
    }

    private fun Buffer.coverage(values: Map<String, Long>) {
        require(values.size <= maximumMetadataEntries) { "Journal metadata limit exceeded" }
        uint(values.size.toULong())
        values.entries.sortedBy { it.key }.forEach { (key, value) ->
            require(value >= 0) { "Negative journal watermark" }
            text(key); uint(value.toULong()); checkSize(this)
        }
    }
    private fun checkSize(buffer: Buffer) { require(buffer.size <= maximumBytes) { "Journal byte limit exceeded" } }
    private fun Buffer.text(value: String) {
        require(value.isNotBlank() && value.length <= 1024 && value.none { it.code < 32 || it.code == 127 }) { "Invalid journal metadata" }
        val bytes = value.encodeToByteArray(throwOnInvalidSequence = true)
        require(bytes.size <= 1024) { "Journal metadata string limit exceeded" }
        uint(bytes.size.toULong()); write(bytes); checkSize(this)
    }
    private fun Buffer.text(): String = readByteArray(count(1024).toLong()).decodeToString(throwOnInvalidSequence = true)
    private fun Buffer.count(limit: Int): Int = uint().also {
        require(it <= limit.toULong() && it <= size.toULong()) { "Journal allocation limit exceeded" }
    }.toInt()
    private fun Buffer.nonnegative(): Long = uint().also { require(it <= Long.MAX_VALUE.toULong()) { "Journal integer overflow" } }.toLong()
    private fun Buffer.version(): Int = uint().also { require(it <= Int.MAX_VALUE.toULong()) { "Protocol version overflow" } }.toInt()
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
            require(index < 9 || next <= 1) { "Journal integer overflow" }
            result = result or ((next and 127).toULong() shl (index * 7))
            if (next and 128 == 0) { require(index == 0 || next != 0) { "Nonminimal journal integer" }; return result }
        }
        error("Unterminated journal integer")
    }
    private companion object { val MAGIC = "YJR3".encodeUtf8() }
}
