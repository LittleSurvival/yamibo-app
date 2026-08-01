package me.thenano.yamibo.yamibo_app.appsync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.littlesurvival.YamiboClient
import io.github.littlesurvival.dto.page.BlogPage
import io.github.littlesurvival.dto.page.UserSpaceBlogPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncCloudConfigDefaults
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncCloudResult
import me.thenano.yamibo.yamibo_app.repository.appsync.remote.YamiboAppSyncBlogProvider
import me.thenano.yamibo.yamibo_app.store.AndroidCookieStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.system.measureTimeMillis

class AppSyncProviderProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                runProbe(context.applicationContext)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun runProbe(context: Context) {
        val report = JSONObject()
            .put("schema", 1)
            .put("redacted", true)
            .put("startedAtEpochMillis", System.currentTimeMillis())
        val provider = YamiboAppSyncBlogProvider(
            cookieStore = AndroidCookieStore(context),
            yamiboClient = YamiboClient(timeoutMillis = REQUEST_TIMEOUT_MILLIS),
        )

        lateinit var firstResult: AppSyncCloudResult<UserSpaceBlogPage>
        val firstElapsed = measureTimeMillis {
            firstResult = provider.fetchMyBlogs()
        }
        report.put("initialDiscoveryMillis", firstElapsed)
        val first = (firstResult as? AppSyncCloudResult.VerifiedSuccess<UserSpaceBlogPage>)?.value
        if (first == null) {
            report.put("outcome", firstResult::class.simpleName ?: "unknown")
            writeReport(context, report)
            return
        }
        val syncClass = first.blogClasses.firstOrNull {
            it.name == AppSyncCloudConfigDefaults.BLOG_CLASS_NAME
        }
        if (syncClass == null) {
            report.put("outcome", "sync_class_missing")
            writeReport(context, report)
            return
        }

        val pageCounts = JSONArray()
        val pageLatencies = JSONArray()
        val authoritativeLatencies = JSONArray()
        val authoritativeBodyChars = JSONArray()
        val authoritativeKinds = JSONArray()
        var pageIndex = 1
        var totalSummaries = 0
        var authoritativeSuccesses = 0
        while (pageIndex <= MAX_PAGES) {
            lateinit var pageResult: AppSyncCloudResult<UserSpaceBlogPage>
            val pageElapsed = measureTimeMillis {
                pageResult = provider.fetchMyBlogs(syncClass.id, pageIndex)
            }
            pageLatencies.put(pageElapsed)
            val page = (pageResult as? AppSyncCloudResult.VerifiedSuccess<UserSpaceBlogPage>)
                ?.value
                ?: break
            pageCounts.put(page.blogs.size)
            totalSummaries += page.blogs.size
            page.blogs.take(MAX_AUTHORITATIVE_READS - authoritativeSuccesses).forEach { summary ->
                lateinit var blogResult: AppSyncCloudResult<BlogPage>
                val blogElapsed = measureTimeMillis {
                    blogResult = provider.fetchBlog(summary.bId)
                }
                authoritativeLatencies.put(blogElapsed)
                val blog = (blogResult as? AppSyncCloudResult.VerifiedSuccess<BlogPage>)?.value
                if (blog != null) {
                    authoritativeSuccesses += 1
                    authoritativeBodyChars.put(blog.rootBlog.contentHtml.length)
                    authoritativeKinds.put(classifyTitle(blog.blogInfo.title))
                }
            }
            val next = page.pageNav?.nextPageIndex
                ?: page.pageNav?.totalPages?.takeIf { pageIndex < it }?.let { pageIndex + 1 }
                ?: break
            if (next <= pageIndex) break
            pageIndex = next
        }

        report
            .put("outcome", "verified")
            .put("pagesRead", pageCounts.length())
            .put("pageBlogCounts", pageCounts)
            .put("pageReadMillis", pageLatencies)
            .put("privateBlogSummaryCount", totalSummaries)
            .put("authoritativeReadAttempts", authoritativeLatencies.length())
            .put("authoritativeReadSuccesses", authoritativeSuccesses)
            .put("authoritativeReadMillis", authoritativeLatencies)
            .put("authoritativeBodyChars", authoritativeBodyChars)
            .put("authoritativeKinds", authoritativeKinds)
        writeReport(context, report)
    }

    private fun classifyTitle(title: String): String = when {
        title.startsWith(JOURNAL_TITLE_PREFIX) -> "journal"
        title == INDEX_TITLE -> "index"
        title.startsWith(CHECKPOINT_TITLE_PREFIX) -> "checkpoint"
        else -> "other"
    }

    private fun writeReport(context: Context, report: JSONObject) {
        File(context.filesDir, REPORT_FILE).writeText(report.toString())
    }

    private companion object {
        const val ACTION =
            "me.thenano.yamibo.yamibo_app.debug.APP_SYNC_PROVIDER_PROBE"
        const val REPORT_FILE = "appsync-provider-probe.json"
        const val REQUEST_TIMEOUT_MILLIS = 60_000L
        const val MAX_PAGES = 20
        const val MAX_AUTHORITATIVE_READS = 10
        const val JOURNAL_TITLE_PREFIX = "Yamibo App Sync Journal - DO NOT EDIT - v1 - "
        const val INDEX_TITLE = "Yamibo App Sync Index - DO NOT EDIT - v1"
        const val CHECKPOINT_TITLE_PREFIX = "Yamibo App Sync Checkpoint - DO NOT EDIT - v1 - "
    }
}
