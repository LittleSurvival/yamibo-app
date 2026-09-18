package me.thenano.yamibo.yamibo_app.repository.appsync

import kotlin.test.*
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.*
import me.thenano.yamibo.yamibo_app.repository.appsync.schema.*

class AppSyncTransportDocumentReaderTest {
    private val codec = AppSyncV3DocumentCodec()
    private val dispatcher = AppSyncTransportEnvelopeDispatcher()
    private val checkpoint = AppSyncCanonicalCheckpoint("checkpoint α", "account & user", 1, emptyMap(), emptyList())
    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    @Test fun readerHtmlPreservesIntegrityProtectedLinesAndEscapedBindingCharacters() {
        val text = codec.encodeCheckpoint(checkpoint)
        val escaped = escape(text)
        val wrappers = listOf("<pre>$escaped</pre>", escaped.replace("\n", "<br>"),
            escaped.replace("\n", "<br>\n"), escaped.lines().joinToString("") { "<p>$it</p>" },
            escaped.lines().joinToString("\n") { "<div>$it</div>" })
        wrappers.forEach { html ->
            val restored = appSyncReaderText(html)
            assertEquals(text, restored)
            val result = assertIs<AppSyncDispatchedDocument.CanonicalCheckpoint>(
                dispatcher.readCheckpoint(restored, checkpoint.accountBinding, checkpoint.checkpointId))
            assertEquals(checkpoint, result.envelope.document)
        }
    }

    @Test fun framingAmbiguityAndCorruptionDoNotFallBackToLegacy() {
        val text = codec.encodeCheckpoint(checkpoint)
        listOf("<p>unexpected text</p><pre>${escape(text)}</pre>", "<pre>${escape(text)}\n${escape(text)}</pre>",
            "<pre>${escape(text.replace("length=", "length=9"))}</pre>").forEach {
            assertIs<AppSyncDispatchedDocument.Invalid>(dispatcher.readCheckpoint(appSyncReaderText(it), checkpoint.accountBinding, checkpoint.checkpointId))
        }
        assertIs<AppSyncDispatchedDocument.Unsupported>(dispatcher.readCheckpoint(text.replace("codec=1", "codec=2"),
            checkpoint.accountBinding, checkpoint.checkpointId))
        assertIs<AppSyncDispatchedDocument.Invalid>(dispatcher.readCheckpoint(text, "other", checkpoint.checkpointId))
    }

    @Test fun legacyJournalAndCheckpointRemainReadableWithExplicitBindings() {
        val corpus = AppSyncSyntheticCorpus.create()
        val journal = corpus.journal
        val journalText = AppSyncJournalEnvelopeCodec().encode(journal)
        assertIs<AppSyncDispatchedDocument.LegacyJournal>(dispatcher.readJournal(appSyncReaderText("<pre>${escape(journalText)}</pre>"),
            journal.accountBinding.value, "unused-v1-identity", journal.deviceId.value, journal.deviceEpoch.value))
        assertIs<AppSyncDispatchedDocument.Invalid>(dispatcher.readJournal(journalText, journal.accountBinding.value,
            "unused-v1-identity", "other", journal.deviceEpoch.value))
        val cp = corpus.checkpoint()
        val text = AppSyncCheckpointEnvelopeCodec().encode(cp)
        assertIs<AppSyncDispatchedDocument.LegacyCheckpoint>(dispatcher.readCheckpoint(text, cp.accountBinding.value, cp.checkpointId))
        assertIs<AppSyncDispatchedDocument.Invalid>(dispatcher.readCheckpoint(text, cp.accountBinding.value, "wrong"))
    }

    @Test fun segmentedV3ChecksOuterAndInnerBindingsAndCompleteChain() {
        val text = codec.encodeCheckpoint(checkpoint)
        val segment = AppSyncSegmentEnvelopeCodec(AppSyncPayloadBudget(4096))
        val drafts = segment.split(text, checkpoint.accountBinding, AppSyncSegmentPayloadKind.Checkpoint, checkpoint.checkpointId, "generation")
        val ids = drafts.indices.map { "blog-$it" }
        val bodies = drafts.mapIndexed { index, draft -> segment.encodeSegment(segment.withNextBlogId(draft, ids.getOrNull(index + 1))) }
        val root = segment.root(drafts, ids.first(), text)
        val encoded = segment.encodeRoot(root)
        val read = dispatcher.readCheckpoint(encoded, checkpoint.accountBinding, checkpoint.checkpointId, ids.zip(bodies).toMap()::get)
        assertEquals(checkpoint, assertIs<AppSyncDispatchedDocument.CanonicalCheckpoint>(read).envelope.document)
        assertIs<AppSyncDispatchedDocument.Invalid>(dispatcher.readCheckpoint(encoded, checkpoint.accountBinding, checkpoint.checkpointId))
        assertIs<AppSyncDispatchedDocument.Invalid>(dispatcher.readCheckpoint(segment.encodeRoot(root.copy(accountBinding = "other")),
            checkpoint.accountBinding, checkpoint.checkpointId, ids.zip(bodies).toMap()::get))
    }

    @Test fun journalReaderChecksOwnerBeforeReturningCanonicalDocument() {
        val journal = AppSyncCanonicalJournal(AppSyncCanonicalOperationBlock("account", emptyList()),
            "device", "epoch", "writer", 0, 0, emptyMap(), emptyList(), 1, 3, 3, "test", null)
        val text = codec.encodeJournal("device:epoch", journal)
        assertEquals(journal, assertIs<AppSyncDispatchedDocument.CanonicalJournal>(dispatcher.readJournal(text,
            "account", "device:epoch", "device", "epoch")).envelope.document)
        assertIs<AppSyncDispatchedDocument.Invalid>(dispatcher.readJournal(text, "account", "device:epoch", "wrong", "epoch"))
    }
}
