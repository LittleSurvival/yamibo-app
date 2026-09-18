package me.thenano.yamibo.yamibo_app.repository.appsync.remote

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.encodeUtf8

@Serializable
internal data class AppSyncV3SegmentReference(val blogId: Int, val sha256: String)

@Serializable
internal data class AppSyncV3Segment(val account: String, val kind: AppSyncV3PayloadKind,
    val identity: String, val generation: String, val index: Int, val count: Int,
    val chunk: String, val next: AppSyncV3SegmentReference? = null)

@Serializable
internal data class AppSyncV3SegmentRoot(val metadata: AppSyncV3EnvelopeMetadata,
    val generation: String, val envelopeSha256: String, val envelopeChars: Int,
    val count: Int, val head: AppSyncV3SegmentReference)

internal data class AppSyncV3SegmentPlan(val metadata: AppSyncV3EnvelopeMetadata,
    val envelopeSha256: String, val envelopeChars: Int, val drafts: List<AppSyncV3Segment>)

internal sealed interface AppSyncV3SegmentRead {
    data class Verified(val envelope: String, val document: AppSyncV3DocumentRead) : AppSyncV3SegmentRead
    data class Invalid(val reason: String) : AppSyncV3SegmentRead
}

/** Splits the already compressed/text-adapted native envelope. No second compression or
 * Base64 layer. Publish tail first, substituting authoritative physical references before
 * encoding each preceding segment. Planning/root construction never proves remote writes.
 */
internal class AppSyncV3SegmentCodec(private val budget: AppSyncPayloadBudget = AppSyncPayloadBudget(),
    private val maximumSegments: Int = 4096, private val maximumEnvelopeChars: Int = 16 * 1024 * 1024 + 4096,
    private val documents: AppSyncV3DocumentCodec = AppSyncV3DocumentCodec()) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    init { require(maximumSegments in 2..4096 && maximumEnvelopeChars > 0) }

    fun plan(envelope: String, account: String, kind: AppSyncV3PayloadKind): AppSyncV3SegmentPlan {
        require(envelope.length in 1..maximumEnvelopeChars) { "V3 envelope exceeds segmentation bounds" }
        val metadata = metadata(documents.discover(envelope, account, kind))
            ?: error("V3 segment source is not a supported canonical document")
        val digest = sha(envelope)
        // Reserve the largest possible physical reference/count before any segment is sent.
        encodeRoot(AppSyncV3SegmentRoot(metadata, digest, digest, envelope.length, maximumSegments,
            AppSyncV3SegmentReference(Int.MAX_VALUE, "f".repeat(64))))
        val chunks = mutableListOf<String>()
        var offset = 0
        while (offset < envelope.length) {
            require(chunks.size < maximumSegments) { "V3 segment count exceeded" }
            var low = 1
            var high = minOf(envelope.length - offset, budget.targetChars)
            var accepted = 0
            while (low <= high) {
                val candidate = low + (high - low) / 2
                var length = candidate
                // Never split a Unicode scalar in the account/identity header.
                if (offset + length < envelope.length && envelope[offset + length - 1].isHighSurrogate() &&
                    envelope[offset + length].isLowSurrogate()) length--
                val sample = AppSyncV3Segment(account, kind, metadata.identity, digest, maximumSegments - 2,
                    maximumSegments, envelope.substring(offset, offset + length),
                    AppSyncV3SegmentReference(Int.MAX_VALUE, "f".repeat(64)))
                if (length > 0 && fits(wrap(SEGMENT, AppSyncV3Segment.serializer(), sample))) {
                    accepted = length; low = candidate + 1
                } else high = candidate - 1
            }
            require(accepted > 0) { "V3 segment metadata cannot fit transport budget" }
            chunks += envelope.substring(offset, offset + accepted)
            offset += accepted
        }
        return AppSyncV3SegmentPlan(metadata, digest, envelope.length, chunks.mapIndexed { index, chunk ->
            AppSyncV3Segment(account, kind, metadata.identity, digest, index, chunks.size, chunk)
        })
    }

    fun encodeSegment(draft: AppSyncV3Segment, next: AppSyncV3SegmentReference? = draft.next): String {
        val segment = draft.copy(next = next)
        validate(segment)
        return wrap(SEGMENT, AppSyncV3Segment.serializer(), segment).also { require(fits(it)) { "V3 segment exceeds transport budget" } }
    }

    fun reference(blogId: Int, encodedSegment: String): AppSyncV3SegmentReference {
        require(blogId > 0)
        decodeSegment(encodedSegment).getOrThrow()
        return AppSyncV3SegmentReference(blogId, sha(encodedSegment))
    }

    fun root(plan: AppSyncV3SegmentPlan, head: AppSyncV3SegmentReference): AppSyncV3SegmentRoot =
        AppSyncV3SegmentRoot(plan.metadata, plan.envelopeSha256, plan.envelopeSha256,
            plan.envelopeChars, plan.drafts.size, head).also(::validate)

    fun encodeRoot(root: AppSyncV3SegmentRoot): String {
        validate(root)
        return wrap(ROOT, AppSyncV3SegmentRoot.serializer(), root).also { require(fits(it)) { "V3 root exceeds transport budget" } }
    }

    fun decodeRoot(text: String): Result<AppSyncV3SegmentRoot> = runCatching {
        unwrap(text, ROOT, AppSyncV3SegmentRoot.serializer()).also(::validate)
    }
    fun decodeSegment(text: String): Result<AppSyncV3Segment> = runCatching {
        unwrap(text, SEGMENT, AppSyncV3Segment.serializer()).also(::validate)
    }

    suspend fun reconstruct(root: AppSyncV3SegmentRoot, expectedAccount: String,
        expectedKind: AppSyncV3PayloadKind, load: suspend (Int) -> String?): AppSyncV3SegmentRead {
        fun invalid(reason: String) = AppSyncV3SegmentRead.Invalid(reason)
        try { encodeRoot(root) } catch (_: Exception) { return invalid("Invalid root bounds") }
        if (root.metadata.accountBinding != expectedAccount || root.metadata.kind != expectedKind) return invalid("Root binding mismatch")
        var next: AppSyncV3SegmentReference? = root.head
        val seen = hashSetOf<Int>()
        val content = StringBuilder()
        repeat(root.count) { index ->
            val reference = next ?: return invalid("Incomplete chain")
            if (!seen.add(reference.blogId)) return invalid("Cyclic chain")
            val body = load(reference.blogId) ?: return invalid("Missing segment")
            if (!fits(body) || sha(body) != reference.sha256) return invalid("Segment integrity mismatch")
            val segment = decodeSegment(body).getOrElse { return invalid("Invalid segment") }
            if (segment.account != expectedAccount || segment.kind != expectedKind || segment.identity != root.metadata.identity ||
                segment.generation != root.generation || segment.index != index || segment.count != root.count)
                return invalid("Segment chain binding mismatch")
            if (content.length.toLong() + segment.chunk.length > root.envelopeChars) return invalid("Envelope length exceeded")
            content.append(segment.chunk)
            next = segment.next
        }
        if (next != null || content.length != root.envelopeChars) return invalid("Chain length mismatch")
        val envelope = content.toString()
        if (sha(envelope) != root.envelopeSha256) return invalid("Envelope integrity mismatch")
        val document = documents.discover(envelope, expectedAccount, expectedKind)
        if (metadata(document) != root.metadata) return invalid("Canonical document mismatch")
        return AppSyncV3SegmentRead.Verified(envelope, document)
    }

    private fun validate(segment: AppSyncV3Segment) {
        require(binding(segment.account) && binding(segment.identity) && digest(segment.generation))
        require(segment.count in 1..maximumSegments && segment.index in 0 until segment.count)
        require(segment.chunk.isNotEmpty())
        segment.chunk.encodeToByteArray(throwOnInvalidSequence = true)
        require((segment.next == null) == (segment.index == segment.count - 1))
        segment.next?.let { require(it.blogId > 0 && digest(it.sha256)) }
    }
    private fun validate(root: AppSyncV3SegmentRoot) {
        val m = root.metadata
        require(m.schemaVersion == 3 && m.codecVersion == 1 && m.compressorId == 1)
        require(binding(m.accountBinding) && binding(m.identity) && digest(m.canonicalFingerprint) && m.uncompressedLength in 0..64 * 1024 * 1024)
        require(root.count in 1..maximumSegments && root.envelopeChars in 1..maximumEnvelopeChars)
        require(digest(root.generation) && root.generation == root.envelopeSha256)
        require(root.head.blogId > 0 && digest(root.head.sha256))
    }
    private fun metadata(document: AppSyncV3DocumentRead): AppSyncV3EnvelopeMetadata? = when (document) {
        is AppSyncV3DocumentRead.Journal -> document.metadata
        is AppSyncV3DocumentRead.Checkpoint -> document.metadata
        else -> null
    }
    private fun <T> wrap(marker: String, serializer: KSerializer<T>, value: T): String {
        val payload = json.encodeToString(serializer, value)
        return "[$marker:BEGIN]\nsha256=${sha(payload)}\npayload=$payload\n[$marker:END]"
    }
    private fun <T> unwrap(text: String, marker: String, serializer: KSerializer<T>): T {
        require(fits(text))
        val lines = text.split('\n', limit = 5)
        require(lines.size == 4 && lines[0] == "[$marker:BEGIN]" && lines[3] == "[$marker:END]")
        require(lines[1].startsWith("sha256=") && lines[2].startsWith("payload="))
        val payload = lines[2].removePrefix("payload=")
        require(sha(payload) == lines[1].removePrefix("sha256="))
        val decoded = json.decodeFromString(serializer, payload)
        require(wrap(marker, serializer, decoded) == text) { "Noncanonical v3 transport wrapper" }
        return decoded
    }
    private fun fits(text: String): Boolean = text.length <= budget.targetChars && text.encodeUtf8().size <= budget.targetChars
    private fun sha(text: String) = text.encodeUtf8().sha256().hex()
    private fun digest(text: String) = text.length == 64 && text.all { it in '0'..'9' || it in 'a'..'f' }
    private fun binding(text: String) = text.isNotBlank() && text == text.trim() && text.none { it.code < 32 || it.code == 127 } &&
        runCatching { text.encodeToByteArray(throwOnInvalidSequence = true).size <= 1024 }.getOrDefault(false)

    companion object {
        const val SEGMENT = "YAMIBO_APP_SYNC_SEGMENT:v3"
        const val ROOT = "YAMIBO_APP_SYNC_ROOT:v3"
        const val SEGMENT_TITLE_PREFIX = "Yamibo App Sync Segment - DO NOT EDIT - v3 - "
        fun segmentTitle(kind: AppSyncV3PayloadKind, generation: String, index: Int): String =
            "$SEGMENT_TITLE_PREFIX${kind.name.lowercase()}-${generation.take(24)}-$index"
    }
}
