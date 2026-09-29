package me.thenano.yamibo.yamibo_app.desktop

import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import me.thenano.yamibo.yamibo_app.repository.DownloadRepository
import me.thenano.yamibo.yamibo_app.repository.ThreadRepository
import me.thenano.yamibo.yamibo_app.repository.download.*
import me.thenano.yamibo.yamibo_app.repository.settings.AppSettingsRepository
import me.thenano.yamibo.yamibo_app.store.settings.DesktopSettingsStore
import kotlin.test.*

class DesktopDownloadRepositoryTest {
    @Test fun selectingAndSwitchingFoldersLoadsOnlyTheirOwnQueues() = runBlocking {
        fixture { settings, a, b, fetcher ->
            val keyA = ThreadPageDownloadKey(11, 1)
            val keyB = ThreadPageDownloadKey(22, 1)
            val entriesA = listOf(entry(keyA))
            val entriesB = listOf(entry(keyB))
            val storeA = DesktopDownloadStorageProvider(settings, a)
            val storeB = DesktopDownloadStorageProvider(settings, b)
            storeA.writeQueue(entriesA)
            storeB.writeQueue(entriesB)
            val owner = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext.job))
            try {
                val repository = DesktopDownloadRepository(settings.backupFolderUri.stateFlow, owner) { folder, child ->
                    real(settings, folder, child, fetcher)
                }
                assertFalse(repository.isStorageReady())
                assertTrue(repository.queue.value.isEmpty())
                settings.backupFolderUri.setValue(a)
                repository.getSummary()
                repository.queue.first { it == entriesA }
                settings.backupFolderUri.setValue(b)
                repository.getSummary()
                repository.queue.first { it == entriesB }
                assertEquals(entriesA, storeA.readQueue())
                assertEquals(entriesB, storeB.readQueue())
                repository.clearPage(keyB).getOrThrow()
                repository.queue.first { it.isEmpty() }
                // Switching joins pending persistence before reopening either folder.
                settings.backupFolderUri.setValue(a)
                repository.getSummary()
                repository.queue.first { it == entriesA }
                assertEquals(entriesA, storeA.readQueue())
                assertTrue(storeB.readQueue().isEmpty())
            } finally { owner.coroutineContext.job.cancelAndJoin() }
        }
    }

    @Test fun switchJoinsForegroundAndBackgroundWorkBeforeOpeningNewFolder() = runBlocking {
        fixture { settings, a, b, fetcher ->
            val oldEntries = listOf(entry(ThreadPageDownloadKey(11, 1)))
            val newEntries = listOf(entry(ThreadPageDownloadKey(22, 1)))
            val storeA = DesktopDownloadStorageProvider(settings, a)
            val storeB = DesktopDownloadStorageProvider(settings, b)
            storeA.writeQueue(oldEntries)
            storeB.writeQueue(newEntries)
            settings.backupFolderUri.setValue(a)
            val started = CompletableDeferred<Unit>()
            val foregroundClosing = CompletableDeferred<Unit>()
            val backgroundClosing = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val openedB = CompletableDeferred<Unit>()
            val owner = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext.job))
            try {
                val repository = DesktopDownloadRepository(settings.backupFolderUri.stateFlow, owner) { folder, child ->
                    val backing = real(settings, folder, child, fetcher)
                    if (folder == b) {
                        openedB.complete(Unit)
                        backing
                    } else {
                        child.launch(start = CoroutineStart.UNDISPATCHED) {
                            try { awaitCancellation() } finally {
                                backgroundClosing.complete(Unit)
                                withContext(NonCancellable) { release.await() }
                            }
                        }
                        object : DownloadRepository by backing {
                            override suspend fun getSummary(): DownloadQueueSummary {
                                backing.getSummary()
                                started.complete(Unit)
                                try { awaitCancellation() } finally {
                                    foregroundClosing.complete(Unit)
                                    withContext(NonCancellable) {
                                        release.await()
                                        // Simulate a blocking filesystem write finishing after selection changed.
                                        storeA.writeQueue(oldEntries)
                                    }
                                }
                            }
                        }
                    }
                }
                val caller = async { repository.getSummary() }
                started.await()
                settings.backupFolderUri.setValue(b)
                foregroundClosing.await()
                backgroundClosing.await()
                assertFalse(openedB.isCompleted)
                release.complete(Unit)
                openedB.await()
                caller.join()
                assertTrue(caller.isCancelled)
                repository.getSummary()
                repository.queue.first { it == newEntries }
                assertEquals(oldEntries, storeA.readQueue())
                assertEquals(newEntries, storeB.readQueue())
            } finally {
                release.complete(Unit)
                owner.coroutineContext.job.cancelAndJoin()
            }
        }
    }

    private fun real(settings: AppSettingsRepository, folder: String, scope: CoroutineScope, fetcher: DownloadImageFetcher) =
        DownloadRepositoryImpl(threadRepository = noNetwork, storageProvider = DesktopDownloadStorageProvider(settings, folder),
            imageFetcher = fetcher, scope = scope)

    private fun entry(key: ThreadPageDownloadKey) = DownloadQueueEntry(key, "測試", DownloadStatus.Paused,
        updatedAt = System.currentTimeMillis())

    private val noNetwork = Proxy.newProxyInstance(ThreadRepository::class.java.classLoader,
        arrayOf(ThreadRepository::class.java)) { _, method, _ -> error("Unexpected remote call: ${method.name}") } as ThreadRepository

    private suspend fun fixture(test: suspend CoroutineScope.(AppSettingsRepository, String, String, DownloadImageFetcher) -> Unit) =
        withTimeout(10_000) {
            val root = Files.createTempDirectory(Files.createDirectories(Path.of("../.tmp/qa")), "download-switch-")
            val fetcher = DownloadImageFetcher { "" }
            try {
                val settings = AppSettingsRepository(DesktopSettingsStore(root.resolve("settings.properties")))
                val a = Files.createDirectory(root.resolve("a")).toUri().toString()
                val b = Files.createDirectory(root.resolve("b")).toUri().toString()
                test(settings, a, b, fetcher)
            } finally {
                fetcher.close()
                Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
            }
        }
}
