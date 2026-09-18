package me.thenano.yamibo.yamibo_app.repository.appsync.remote

import io.github.littlesurvival.dto.value.BlogClassId
import io.github.littlesurvival.dto.value.BlogId
import kotlinx.coroutines.CancellationException
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncCloudResult
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncCloudConfigDefaults
import okio.ByteString.Companion.encodeUtf8

internal sealed interface AppSyncV3CandidateScan {
    data class Complete(val blogIds: List<BlogId>) : AppSyncV3CandidateScan
    data object Unknown : AppSyncV3CandidateScan
    data object FormExpired : AppSyncV3CandidateScan
}

/** Fresh, bounded class discovery. A partial scan never grants permission to create again. */
internal class AppSyncV3ArtifactReconciler(
    private val provider: AppSyncBlogProvider,
    private val classId: BlogClassId,
    private val className: String = AppSyncCloudConfigDefaults.BLOG_CLASS_NAME,
    private val maxPages: Int = 100,
    private val maxArtifacts: Int = 10_000,
) {
    init { require(maxPages in 1..100 && maxArtifacts in 1..10_000) }

    suspend fun discover(title: String, sha256: String): AppSyncV3ArtifactDiscovery = try {
        scan(title, sha256)
    } catch (cancelled: CancellationException) { throw cancelled
    } catch (_: Exception) { AppSyncV3ArtifactDiscovery.Unknown }

    private suspend fun scan(title: String, sha256: String): AppSyncV3ArtifactDiscovery {
        if (title.isBlank() || !sha256.matches(Regex("[0-9a-f]{64}"))) return AppSyncV3ArtifactDiscovery.Unknown
        val candidates = when (val result = findCandidates(title)) {
            is AppSyncV3CandidateScan.Complete -> result.blogIds
            AppSyncV3CandidateScan.FormExpired -> return AppSyncV3ArtifactDiscovery.FormExpired
            AppSyncV3CandidateScan.Unknown -> return AppSyncV3ArtifactDiscovery.Unknown
        }
        var found: BlogId? = null
        for (id in candidates) {
            val blog = when (val result = provider.fetchBlog(id)) {
                is AppSyncCloudResult.VerifiedSuccess -> result.value
                AppSyncCloudResult.NotLoggedIn, is AppSyncCloudResult.FormExpired -> return AppSyncV3ArtifactDiscovery.FormExpired
                else -> return AppSyncV3ArtifactDiscovery.Unknown
            }
            if (blog.blogInfo.blogId != id || blog.blogInfo.title != title) return AppSyncV3ArtifactDiscovery.Unknown
            val body = appSyncReaderText(blog.rootBlog.contentHtml)
            if (body.encodeUtf8().sha256().hex() == sha256) {
                if (found != null) return AppSyncV3ArtifactDiscovery.Conflict
                found = id
            } else if (title.startsWith(AppSyncV3SegmentCodec.SEGMENT_TITLE_PREFIX))
                return AppSyncV3ArtifactDiscovery.Conflict
        }
        return found?.let { AppSyncV3ArtifactDiscovery.Found(it) } ?: AppSyncV3ArtifactDiscovery.Absent
    }

    suspend fun findCandidates(title: String): AppSyncV3CandidateScan = try {
        scanCandidates(title)
    } catch (cancelled: CancellationException) { throw cancelled
    } catch (_: Exception) { AppSyncV3CandidateScan.Unknown }

    private suspend fun scanCandidates(title: String): AppSyncV3CandidateScan {
        if (title.isBlank()) return AppSyncV3CandidateScan.Unknown
        val seen = mutableSetOf<Int>()
        val candidates = mutableListOf<BlogId>()
        var expectedTotal: Int? = null
        var completed = false
        for (index in 1..maxPages) {
            val page = when (val result = provider.fetchMyBlogs(classId, index)) {
                is AppSyncCloudResult.VerifiedSuccess -> result.value
                AppSyncCloudResult.NotLoggedIn, is AppSyncCloudResult.FormExpired -> return AppSyncV3CandidateScan.FormExpired
                else -> return AppSyncV3CandidateScan.Unknown
            }
            if (page.blogClasses.none { it.id == classId && it.name == className }) return AppSyncV3CandidateScan.Unknown
            val nav = page.pageNav
            if (nav?.currentPage != null && nav.currentPage != index) return AppSyncV3CandidateScan.Unknown
            val total = nav?.totalPages
            if (total != null) {
                if (total < index || total > maxPages || (expectedTotal != null && expectedTotal != total))
                    return AppSyncV3CandidateScan.Unknown
                expectedTotal = total
            }
            for (summary in page.blogs) {
                // Repeated rows can indicate a moving or ignored pagination cursor.
                if (seen.size >= maxArtifacts || summary.bId.value <= 0 || !seen.add(summary.bId.value))
                    return AppSyncV3CandidateScan.Unknown
                if (summary.title.removePrefix("[$className] ").trim() == title) candidates += summary.bId
            }
            val next = nav?.nextPageIndex
            if (nav?.nextUrl != null && next == null) return AppSyncV3CandidateScan.Unknown
            if (next != null && (next != index + 1 || (total != null && next > total))) return AppSyncV3CandidateScan.Unknown
            if (next == null && (expectedTotal == null || index == expectedTotal)) {
                completed = true
                break
            }
        }
        return if (completed) AppSyncV3CandidateScan.Complete(candidates) else AppSyncV3CandidateScan.Unknown
    }
}
