package me.thenano.yamibo.yamibo_app.repository.appsync

import kotlin.test.*
import me.thenano.yamibo.yamibo_app.repository.appsync.schema.*
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.*
import okio.ByteString.Companion.encodeUtf8

class AppSyncV3DocumentCodecTest {
    private val codec = AppSyncV3DocumentCodec()
    private val journal = AppSyncCanonicalJournal(AppSyncCanonicalOperationBlock("account", emptyList()),
        "device", "epoch", "writer", 0, 0, mapOf("device:epoch" to 10), emptyList(), 1, 3, 3, "test", 10)
    private val checkpoint = AppSyncCanonicalCheckpoint("checkpoint", "account", 1, mapOf("device:epoch" to 10), emptyList())

    @Test
    fun bothRootsAreValidatedAndCompressedExactlyOnce() {
        val journalText = codec.encodeJournal("journal", journal)
        val checkpointText = codec.encodeCheckpoint(checkpoint)
        assertEquals(journal, assertIs<AppSyncV3DocumentRead.Journal>(codec.readJournal(journalText, "account", "journal", "device", "epoch")).document)
        assertEquals(checkpoint, assertIs<AppSyncV3DocumentRead.Checkpoint>(codec.readCheckpoint(checkpointText, "account", "checkpoint")).document)
        val envelope = AppSyncV3EnvelopeCodec()
        assertEquals(AppSyncCanonicalJournalCodec().encode(journal), assertIs<AppSyncV3EnvelopeRead.VerifiedBytes>(
            envelope.decode(journalText, "account", AppSyncV3PayloadKind.Journal, "journal")).bytes)
        assertEquals(AppSyncCanonicalCheckpointCodec().encode(checkpoint), assertIs<AppSyncV3EnvelopeRead.VerifiedBytes>(
            envelope.decode(checkpointText, "account", AppSyncV3PayloadKind.Checkpoint, "checkpoint")).bytes)
        assertEquals(journalText, codec.encodeJournal("journal", journal))
        assertEquals(checkpointText, codec.encodeCheckpoint(checkpoint))
    }

    @Test
    fun validTransportCannotHideWrongRootAccountIdentityOrOwner() {
        val envelope = AppSyncV3EnvelopeCodec()
        val wrongRoot = envelope.encode(AppSyncV3PayloadKind.Journal, "account", "journal", AppSyncCanonicalCheckpointCodec().encode(checkpoint))
        assertIs<AppSyncV3DocumentRead.Invalid>(codec.readJournal(wrongRoot, "account", "journal", "device", "epoch"))
        val wrongAccount = envelope.encode(AppSyncV3PayloadKind.Journal, "other", "journal", AppSyncCanonicalJournalCodec().encode(journal))
        assertIs<AppSyncV3DocumentRead.Invalid>(codec.readJournal(wrongAccount, "other", "journal", "device", "epoch"))
        assertIs<AppSyncV3DocumentRead.Invalid>(codec.readJournal(codec.encodeJournal("journal", journal), "account", "journal", "wrong-device", "epoch"))
        val wrongId = envelope.encode(AppSyncV3PayloadKind.Checkpoint, "account", "other", AppSyncCanonicalCheckpointCodec().encode(checkpoint))
        assertIs<AppSyncV3DocumentRead.Invalid>(codec.readCheckpoint(wrongId, "account", "other"))
        val invalid = envelope.encode(AppSyncV3PayloadKind.Checkpoint, "account", "checkpoint", "private-user-content".encodeUtf8())
        assertEquals(AppSyncV3DocumentRead.Invalid(), codec.readCheckpoint(invalid, "account", "checkpoint"))
    }

    @Test
    fun unsupportedTransportAndIntegrityFailureRemainDistinctFromValidatedDocuments() {
        val valid = codec.encodeCheckpoint(checkpoint)
        assertEquals(AppSyncV3DocumentRead.Unsupported(3, 2, 1), codec.readCheckpoint(valid.replace("codec=1", "codec=2"), "account", "checkpoint"))
        assertEquals(AppSyncV3DocumentRead.Invalid(AppSyncV3EnvelopeError.BindingMismatch), codec.readCheckpoint(valid, "other", "checkpoint"))
        val altered = valid.replace(Regex("integrity=[a-f0-9]{64}"), "integrity=${"0".repeat(64)}")
        assertEquals(AppSyncV3DocumentRead.Invalid(AppSyncV3EnvelopeError.Integrity), codec.readCheckpoint(altered, "account", "checkpoint"))
        val limited = AppSyncV3DocumentCodec(checkpoint = AppSyncCanonicalCheckpointCodec(maximumBytes = 1))
        assertEquals(AppSyncV3DocumentRead.Invalid(), limited.readCheckpoint(valid, "account", "checkpoint"))
    }
}
