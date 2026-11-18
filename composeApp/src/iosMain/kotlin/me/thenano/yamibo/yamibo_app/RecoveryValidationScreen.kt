package me.thenano.yamibo.yamibo_app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.littlesurvival.core.YamiboResult
import io.github.littlesurvival.dto.model.ForumSummary
import io.github.littlesurvival.dto.page.ForumCategory
import io.github.littlesurvival.dto.page.HomePage
import io.github.littlesurvival.dto.value.ForumId
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.thenano.yamibo.yamibo_app.components.feedback.YamiboErrorContent
import me.thenano.yamibo.yamibo_app.history.ReadHistoryPage
import me.thenano.yamibo.yamibo_app.home.HomePageScreen
import me.thenano.yamibo.yamibo_app.home.nativeHomeNetworkAvailability
import me.thenano.yamibo.yamibo_app.navigation.IInAppLinkResolvingScreen
import me.thenano.yamibo.yamibo_app.repository.*
import me.thenano.yamibo.yamibo_app.repository.inapplinknavigation.*
import me.thenano.yamibo.yamibo_app.thread.image.ImageViewer
import platform.Foundation.NSProcessInfo

private val scenario = NSProcessInfo.processInfo.environment["YAMIBO_QA_CASE"]?.toString().orEmpty()
internal val simulatedPath = if (scenario.startsWith("home")) MutableStateFlow(false) else null
private val longMessage = "Fixture text ".repeat(120)
private fun page(title: String) = HomePage(emptyList(), listOf(ForumCategory(title,
    listOf(ForumSummary(ForumId(49), "Fixture forum", "/forum-49-1.html")))))

@Composable
fun RecoveryValidationScreen() {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var attempts by remember { mutableIntStateOf(0) }
    val forum = LocalForumRepository.current
    val history = LocalReadHistoryRepository.current
    val homeRepo = remember {
        object : ForumRepository by forum {
            override fun getCachedHomePage() = if (scenario == "home-cache") page("QA cached") else null
            override suspend fun fetchHomePage(): YamiboResult<HomePage> {
                attempts++
                if (attempts == 1) {
                    if (scenario == "home-race") {
                        simulatedPath!!.value = true
                        delay(350)
                    } else if (scenario != "home-offline") {
                        scope.launch { delay(7000); simulatedPath!!.value = true }
                    }
                    if (scenario == "home-business") return YamiboResult.Failure("QA business failure")
                    return YamiboResult.Failure("[Network] Error Domain=NSURLErrorDomain Code=-1009")
                }
                if (scenario == "home-resume" && attempts == 2) {
                    return YamiboResult.Failure("[Network] Error Domain=NSURLErrorDomain Code=-1009")
                }
                return YamiboResult.Success(page("QA fresh"))
            }
        }
    }
    val link = remember {
        object : InAppLinkNavigationRepository {
            override suspend fun resolve(url: String, context: InAppLinkContext, onProgress: (String) -> Unit): InAppLinkResolveResult {
                attempts++
                return InAppLinkResolveResult.Failed(InAppLinkTarget.ForumTarget(ForumId(49)), longMessage)
            }
        }
    }
    val historyRepo = remember {
        object : ReadHistoryRepository by history {
            override suspend fun getCombinedHistoryPageByFilters(filters: Set<ReadHistoryRepository.HistoryFilter>, page: Int,
                pageSize: Int): List<ReadHistoryRepository.AnyReadingHistory> {
                attempts++
                error(longMessage)
            }
        }
    }
    val scale = if (scenario.startsWith("home") || scenario == "native") 1f else 1.7f
    CompositionLocalProvider(LocalForumRepository provides homeRepo, LocalReadHistoryRepository provides historyRepo,
        LocalInAppLinkNavigationRepository provides link, LocalDensity provides Density(density.density, scale)) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            val frame = when (scenario) {
                "card", "diagnostic" -> Modifier.height(230.dp)
                "image" -> Modifier.width(300.dp).height(105.dp)
                "link", "history" -> Modifier.fillMaxWidth().height(330.dp)
                else -> Modifier.weight(1f)
            }
            Box(frame) {
                when (scenario) {
                    "native" -> {
                        var ready by remember { mutableStateOf(false) }
                        LaunchedEffect(Unit) { ready = nativeHomeNetworkAvailability().first { it } }
                        Text("QA native path=$ready")
                    }
                    "card", "diagnostic" -> YamiboErrorContent(
                        if (scenario == "card") longMessage else "[Network] Code=-1009\nCookie: fixture-secret\n$longMessage",
                        onRetry = { attempts++ })
                    "image" -> ImageViewer("https://example.invalid/fixture.png", fillContainer = true,
                        blockedErrorMessage = longMessage, onReload = { attempts++ })
                    "history" -> ReadHistoryPage()
                    "link" -> IInAppLinkResolvingScreen("https://bbs.yamibo.com/forum-49-1.html").Content()
                    else -> HomePageScreen()
                }
            }
            Text("QA attempts=$attempts")
        }
    }
}
