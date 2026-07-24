package me.thenano.yamibo.yamibo_app.util

import kotlin.test.Test
import kotlin.test.assertEquals

class StorageSizeTest {
    @Test
    fun formatStorageSizeKeepsRoundedKilobyteStyle() {
        assertEquals("0.0 kB", formatStorageSize(0))
        assertEquals("0.5 kB", formatStorageSize(512))
        assertEquals("1.0 kB", formatStorageSize(1024))
        assertEquals("1.5 kB", formatStorageSize(1536))
        assertEquals("1.0 MB", formatStorageSize(1024L * 1024L))
        assertEquals("1.23 MB", formatStorageSize(1_294_991))
    }

    @Test
    fun formatDownloadedByteSizeKeepsByteAndTruncatedUppercaseStyle() {
        assertEquals("0 B", formatDownloadedByteSize(0))
        assertEquals("512 B", formatDownloadedByteSize(512))
        assertEquals("1.0 KB", formatDownloadedByteSize(1024))
        assertEquals("1.5 KB", formatDownloadedByteSize(1536))
        assertEquals("1.0 MB", formatDownloadedByteSize(1024L * 1024L))
        assertEquals("1.2 MB", formatDownloadedByteSize(1_294_991))
    }
}
