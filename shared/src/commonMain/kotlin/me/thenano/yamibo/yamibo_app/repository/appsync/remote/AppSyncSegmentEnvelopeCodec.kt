package me.thenano.yamibo.yamibo_app.repository.appsync.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.thenano.yamibo.yamibo_app.repository.appsync.domain.stableAppSyncFingerprint

internal enum class AppSyncSegmentPayloadKind { Journal, Checkpoint }

@Serializable
internal data class AppSyncSegmentPayload(
    val protocolVersion: Int = 2,
    val accountBinding: String,
    val kind: String,
    val identity: String,
    val generationId: String,
    val index: Int,
    val count: Int,
    val chunk: String,
    val chunkFingerprint: String,
    val nextBlogId: String? = null,
)

@Serializable
internal data class AppSyncSegmentRootManifest(
    val protocolVersion: Int = 2,
    val accountBinding: String,
    val kind: String,
    val identity: String,
    val generationId: String,
    val headBlogId: String,
    val segmentCount: Int,
    val totalEncodedChars: Int,
    val envelopeFingerprint: String,
)

internal data class AppSyncSegmentDraft(
    val payload: AppSyncSegmentPayload,
)

internal sealed interface AppSyncSegmentReconstruction {
    data class Valid(val canonicalEnvelope: String) : AppSyncSegmentReconstruction
    data class Invalid(val reason: String) : AppSyncSegmentReconstruction
}

/** Codec for immutable v2 transport wrappers. Existing Journal/Checkpoint codecs remain canonical. */
internal class AppSyncSegmentEnvelopeCodec(
    private val budget: AppSyncPayloadBudget = AppSyncPayloadBudget(),
    private val json: Json = Json { encodeDefaults = true; ignoreUnknownKeys = false },
    private val maximumSegments: Int = 4_096,
    private val maximumDecodedEnvelopeChars: Int = 16 * 1024 * 1024,
) {
    fun split(
        canonicalEnvelope: String,
        accountBinding: String,
        kind: AppSyncSegmentPayloadKind,
        identity: String,
        generationId: String,
    ): List<AppSyncSegmentDraft> {
        require(canonicalEnvelope.length <= maximumDecodedEnvelopeChars) {
            "Canonical AppSync envelope exceeds decoded-size bound"
        }
        if (canonicalEnvelope.isEmpty()) return listOf(
            draft(accountBinding, kind, identity, generationId, 0, 1, ""),
        )

        val chunks = mutableListOf<String>()
        var offset = 0
        while (offset < canonicalEnvelope.length) {
            require(chunks.size < maximumSegments) { "AppSync envelope requires too many segments" }
            var low = 1
            var high = canonicalEnvelope.length - offset
            var accepted = 0
            while (low <= high) {
                val candidateLength = low + (high - low) / 2
                val candidate = draft(
                    accountBinding, kind, identity, generationId,
                    index = maximumSegments - 1,
                    count = maximumSegments,
                    chunk = canonicalEnvelope.substring(offset, offset + candidateLength),
                    nextBlogId = MAX_NEXT_ID_PLACEHOLDER,
                )
                if (budget.measure(encodeSegment(candidate.payload)).fitsTarget) {
                    accepted = candidateLength
                    low = candidateLength + 1
                } else {
                    high = candidateLength - 1
                }
            }
            require(accepted > 0) { "AppSync segment metadata cannot fit target budget" }
            chunks += canonicalEnvelope.substring(offset, offset + accepted)
            offset += accepted
        }
        val count = chunks.size
        return chunks.mapIndexed { index, chunk ->
            draft(accountBinding, kind, identity, generationId, index, count, chunk)
        }
    }

    fun encodeSegment(payload: AppSyncSegmentPayload): String {
        require(payload.protocolVersion == 2)
        require(payload.index in 0 until payload.count)
        require(payload.count in 1..maximumSegments)
        require(payload.chunkFingerprint == stableAppSyncFingerprint(payload.chunk))
        return wrap(SEGMENT_MARKER, json.encodeToString(AppSyncSegmentPayload.serializer(), payload))
    }

    fun encodeRoot(root: AppSyncSegmentRootManifest): String {
        require(root.protocolVersion == 2)
        require(root.segmentCount in 1..maximumSegments)
        require(root.totalEncodedChars in 0..maximumDecodedEnvelopeChars)
        return wrap(ROOT_MARKER, json.encodeToString(AppSyncSegmentRootManifest.serializer(), root))
            .also(budget::requireWithinTarget)
    }

    fun root(
        drafts: List<AppSyncSegmentDraft>,
        headBlogId: String,
        canonicalEnvelope: String,
    ): AppSyncSegmentRootManifest {
        require(drafts.isNotEmpty())
        val first = drafts.first().payload
        require(drafts.size == first.count)
        return AppSyncSegmentRootManifest(
            accountBinding = first.accountBinding,
            kind = first.kind,
            identity = first.identity,
            generationId = first.generationId,
            headBlogId = headBlogId,
            segmentCount = drafts.size,
            totalEncodedChars = canonicalEnvelope.length,
            envelopeFingerprint = stableAppSyncFingerprint(canonicalEnvelope),
        )
    }

    fun withNextBlogId(draft: AppSyncSegmentDraft, nextBlogId: String?): AppSyncSegmentPayload =
        draft.payload.copy(nextBlogId = nextBlogId).also {
            budget.requireWithinTarget(encodeSegment(it))
        }

    fun decodeRoot(text: String): Result<AppSyncSegmentRootManifest> = runCatching {
        json.decodeFromString(AppSyncSegmentRootManifest.serializer(), unwrap(text, ROOT_MARKER))
            .also { require(it.protocolVersion == 2); require(it.segmentCount in 1..maximumSegments) }
    }

    fun decodeSegment(text: String): Result<AppSyncSegmentPayload> = runCatching {
        json.decodeFromString(AppSyncSegmentPayload.serializer(), unwrap(text, SEGMENT_MARKER)).also {
            require(it.protocolVersion == 2)
            require(it.index in 0 until it.count)
            require(it.count in 1..maximumSegments)
            require(it.chunkFingerprint == stableAppSyncFingerprint(it.chunk))
        }
    }

    fun reconstruct(
        root: AppSyncSegmentRootManifest,
        loadSegmentBody: (String) -> String?,
    ): AppSyncSegmentReconstruction {
        if (root.protocolVersion != 2 || root.segmentCount !in 1..maximumSegments) {
            return invalid("Invalid root bounds")
        }
        val seenIds = mutableSetOf<String>()
        val chunks = ArrayList<String>(root.segmentCount)
        var blogId: String? = root.headBlogId
        repeat(root.segmentCount) { expectedIndex ->
            val id = blogId ?: return invalid("Segment chain ended early")
            if (!seenIds.add(id)) return invalid("Segment chain contains a cycle")
            val body = loadSegmentBody(id) ?: return invalid("Segment is missing")
            val segment = decodeSegment(body).getOrElse { return invalid("Segment is invalid") }
            if (segment.accountBinding != root.accountBinding ||
                segment.kind != root.kind || segment.identity != root.identity ||
                segment.generationId != root.generationId
            ) return invalid("Segment binding does not match root")
            if (segment.index != expectedIndex || segment.count != root.segmentCount) {
                return invalid("Segment order or count does not match root")
            }
            chunks += segment.chunk
            blogId = segment.nextBlogId
        }
        if (blogId != null) return invalid("Segment chain is longer than declared")
        val envelope = chunks.joinToString("")
        if (envelope.length != root.totalEncodedChars || envelope.length > maximumDecodedEnvelopeChars) {
            return invalid("Reconstructed size does not match root")
        }
        if (stableAppSyncFingerprint(envelope) != root.envelopeFingerprint) {
            return invalid("Reconstructed fingerprint does not match root")
        }
        return AppSyncSegmentReconstruction.Valid(envelope)
    }

    private fun draft(
        accountBinding: String,
        kind: AppSyncSegmentPayloadKind,
        identity: String,
        generationId: String,
        index: Int,
        count: Int,
        chunk: String,
        nextBlogId: String? = null,
    ) = AppSyncSegmentDraft(
        AppSyncSegmentPayload(
            accountBinding = accountBinding,
            kind = kind.name.lowercase(),
            identity = identity,
            generationId = generationId,
            index = index,
            count = count,
            chunk = chunk,
            chunkFingerprint = stableAppSyncFingerprint(chunk),
            nextBlogId = nextBlogId,
        ),
    )

    private fun wrap(marker: String, payload: String): String =
        "[$marker:BEGIN]\npayload=$payload\n[$marker:END]"

    private fun unwrap(text: String, marker: String): String {
        val begin = "[$marker:BEGIN]"
        val end = "[$marker:END]"
        val beginIndex = text.indexOf(begin)
        val endIndex = text.indexOf(end, (beginIndex + begin.length).coerceAtLeast(0))
        require(beginIndex >= 0 && endIndex > beginIndex) { "Segment envelope boundary is invalid" }
        val body = text.substring(beginIndex + begin.length, endIndex).trim()
        require(body.startsWith("payload=")) { "Segment envelope payload is missing" }
        return body.removePrefix("payload=").trim()
    }

    private fun invalid(reason: String) = AppSyncSegmentReconstruction.Invalid(reason)

    companion object {
        const val SEGMENT_MARKER = "YAMIBO_APP_SYNC_SEGMENT:v2"
        const val ROOT_MARKER = "YAMIBO_APP_SYNC_ROOT:v2"
        private const val MAX_NEXT_ID_PLACEHOLDER = "99999999999999999999999999999999"
    }
}
