package me.thenano.yamibo.yamibo_app.qa

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.lang.reflect.Proxy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import me.thenano.yamibo.yamibo_app.*
import me.thenano.yamibo.yamibo_app.feedback.AppFeedbackController
import me.thenano.yamibo.yamibo_app.navigation.ComposableNavigator
import me.thenano.yamibo.yamibo_app.navigation.LocalNavigator
import me.thenano.yamibo.yamibo_app.profile.download.DownloadQueueScreen
import me.thenano.yamibo.yamibo_app.repository.DownloadRepository
import me.thenano.yamibo.yamibo_app.repository.ServerNoticeRepository
import me.thenano.yamibo.yamibo_app.repository.download.*
import me.thenano.yamibo.yamibo_app.repository.servernotice.ForumErrorMessageResolver
import me.thenano.yamibo.yamibo_app.repository.servernotice.ServerNotice
import me.thenano.yamibo.yamibo_app.repository.settings.AppSettingsRepository
import me.thenano.yamibo.yamibo_app.store.settings.AndroidSettingsStore
import me.thenano.yamibo.yamibo_app.task.AppTaskManager

/** Validation-only host: real screen, synthetic repository, independent package. */
class DownloadContentQaActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var reads by mutableIntStateOf(0)
    private var summaries by mutableIntStateOf(0)
    private var qaProgress by mutableIntStateOf(0)
    private var failNext = false
    private var scanDelay = 100L
    private val items = (1..5_000).map { page ->
        DownloadedContentItem(ThreadPageDownloadKey(42, page), "QA large book", "QA page $page", page.toLong(), 1, 100)
    }
    private var groups = listOf(group(items))
    private val active = DownloadQueueEntry(ThreadPageDownloadKey(50, 1), "QA active", DownloadStatus.Downloading, updatedAt = 1)
    private val queue = MutableStateFlow(items.map {
        DownloadQueueEntry(it.key, it.title, DownloadStatus.Downloaded, updatedAt = it.downloadedAt)
    } + active)

    private val repository = Proxy.newProxyInstance(
        DownloadRepository::class.java.classLoader,
        arrayOf(DownloadRepository::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getQueue" -> queue
            "isStorageReady" -> true
            "getSummary" -> {
                summaries++
                DownloadQueueSummary(downloading = queue.value.count { it.status == DownloadStatus.Downloading }, downloaded = 5_000)
            }
            "getDownloadedContentSummary" -> error("Unexpected separate summary scan")
            "getDownloadedContentGroups" -> {
                reads++
                Thread.sleep(scanDelay)
                scanDelay = 100L
                if (failNext) {
                    failNext = false
                    error("QA storage failure")
                }
                groups
            }
            "toString" -> "DownloadContentQA"
            "hashCode" -> 42
            else -> error("Unexpected QA method: ${method.name}")
        }
    } as DownloadRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = AppSettingsRepository(AndroidSettingsStore(this))
        val feedback = AppFeedbackController()
        val tasks = AppTaskManager(scope, feedback)
        val notices = object : ServerNoticeRepository {
            override val notice = MutableStateFlow<ServerNotice?>(null)
            override suspend fun refresh(): ServerNotice? = null
            override suspend fun active524Message(): String? = null
        }
        setContent {
            CompositionLocalProvider(
                LocalDownloadRepository provides repository,
                LocalAppSettingsRepository provides settings,
                LocalAppFeedbackController provides feedback,
                LocalAppTaskManager provides tasks,
                LocalForumErrorMessageResolver provides ForumErrorMessageResolver(notices),
                LocalNavigator provides remember { ComposableNavigator() },
            ) {
                MaterialTheme {
                    Column(Modifier.fillMaxSize().padding(top = 28.dp)) {
                        Text("reads=$reads summaries=$summaries progress=$qaProgress")
                        Row {
                            Button(onClick = {
                                scope.launch {
                                    repeat(100) { index ->
                                        qaProgress = index + 1
                                        queue.value = queue.value.map { entry ->
                                            if (entry.key == active.key) entry.copy(progressCurrent = qaProgress, updatedAt = 20_000L + qaProgress) else entry
                                        }
                                        delay(20)
                                    }
                                }
                            }) { Text("PROGRESS") }
                            Button(onClick = {
                                groups = listOf(group(items.dropLast(1)))
                                queue.value = queue.value.filterNot { it.key == items.last().key }
                            }) { Text("REMOVE") }
                            Button(onClick = {
                                failNext = true
                                groups = listOf(group(items.dropLast(2)))
                                queue.value = queue.value.filterNot { it.key == items[items.lastIndex - 1].key }
                            }) { Text("ERROR") }
                        }
                        Box(Modifier.weight(1f)) { DownloadQueueScreen() }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun group(entries: List<DownloadedContentItem>) = DownloadedContentGroup(
        "qa-book", "QA large book", DownloadedContentGroupType.Thread,
        entries.size, entries.size, entries.size * 100L, entries,
    )
}
