package me.thenano.yamibo.yamibo_app.repository.download

import java.io.FileNotFoundException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidDownloadStorageProviderTest {
    @Test
    fun onlyCompletedThreadDirectoriesAreTraversedAsManifests() {
        assertTrue(isCompletedThreadDownloadDirectory("thread_42_page_1_author_all"))
        assertFalse(isCompletedThreadDownloadDirectory("queue.json"))
        assertFalse(isCompletedThreadDownloadDirectory("tag_manga_8"))
        assertFalse(isCompletedThreadDownloadDirectory("thread_42_page_1_author_all.tmp"))
        assertFalse(isCompletedThreadDownloadDirectory("thread_42_page_1_author_all.previous"))
    }

    @Test
    fun missingDocumentIsRecognizedThroughWrappedCause() {
        val missing = IllegalArgumentException(
            "Failed to determine document relationship",
            FileNotFoundException("Missing file"),
        )

        assertTrue(missing.isMissingDocumentFailure())
        assertFalse(IllegalArgumentException("Invalid document id").isMissingDocumentFailure())
    }
}