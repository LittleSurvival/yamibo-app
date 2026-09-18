package me.thenano.yamibo.yamibo_app.repository.appsync

import kotlin.time.measureTimedValue
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.*
import okio.ByteString.Companion.decodeBase64
import okio.Buffer
import okio.GzipSource
import okio.buffer

/** Same harness is available to Android and iOS commonTest runners. Host timing is not a device gate. */
internal object AppSyncCorpusBaseline {
    data class Measurement(
        val kind: String,
        val rawBytes: Int,
        val compressedBytes: Int,
        val base64Chars: Int,
        val envelopeChars: Int,
        val segments: Int,
        val minimumPublicationWrites: Int,
        val encodeNanos: Long,
        val decodeNanos: Long,
        // These must come from a platform profiler, never inferred from input length or heap deltas.
        val peakMemoryBytes: Long? = null,
        val deviceProfile: String = "unmeasured",
    )
    fun measure(corpus: AppSyncSyntheticCorpus.Corpus): List<Measurement> {
        val journalCodec = AppSyncJournalEnvelopeCodec()
        val checkpointCodec = AppSyncCheckpointEnvelopeCodec()
        val checkpoint = corpus.checkpoint()
        return listOf(
            measure(AppSyncSegmentPayloadKind.Journal,
                { journalCodec.encode(corpus.journal) },
                { check(journalCodec.validate(it) is AppSyncJournalValidation.Valid) }),
            measure(AppSyncSegmentPayloadKind.Checkpoint,
                { checkpointCodec.encode(checkpoint) },
                { check(checkpointCodec.validate(it) is AppSyncCheckpointValidation.Valid) }),
        )
    }

    private fun measure(kind: AppSyncSegmentPayloadKind, encode: () -> String, decode: (String) -> Unit): Measurement {
        val encoded = measureTimedValue(encode)
        val decoded = measureTimedValue { decode(encoded.value) }
        val base64 = encoded.value.lineSequence().single { it.startsWith("payload=") }.substringAfter("gzip-base64:")
        val compressed = requireNotNull(base64.decodeBase64())
        val rawBytes = GzipSource(Buffer().write(compressed)).buffer().use { it.readByteArray().size }
        val fits = AppSyncPayloadBudget().measure(encoded.value).fitsTarget
        val count = if (fits) 0 else AppSyncSegmentEnvelopeCodec().split(encoded.value,
            AppSyncSyntheticCorpus.account.value, kind, "synthetic", "baseline").size
        return Measurement(kind.name, rawBytes, compressed.size,
            base64.length, encoded.value.length, count, if (fits) 2 else count + 2,
            encoded.duration.inWholeNanoseconds, decoded.duration.inWholeNanoseconds)
    }
}
