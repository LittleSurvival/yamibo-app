package me.thenano.yamibo.yamibo_app.profile.sign

import me.thenano.yamibo.yamibo_app.i18n.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import io.github.littlesurvival.YamiboRoute
import kotlinx.coroutines.launch
import me.thenano.yamibo.yamibo_app.LocalAuthRepository
import me.thenano.yamibo.yamibo_app.navigation.LocalNavigator
import me.thenano.yamibo.yamibo_app.navigation.Navigatable
import me.thenano.yamibo.yamibo_app.LocalSignRepository
import me.thenano.yamibo.yamibo_app.webview.PlatformWebViewScreen
import io.github.littlesurvival.core.YamiboResult

/** UI-confined: a parsed current browser page is evidence even if the HTTP client is challenged. */
internal class SemiAutomaticSignCheck {
    private var finished = false
    private var checking = false

    fun finish(action: () -> Unit) {
        if (finished) return
        finished = true
        action()
    }

    suspend fun check(
        observedPageValid: Boolean,
        fetchPage: suspend () -> YamiboResult<*>,
        onCleared: () -> Unit,
        onMaintenance: () -> Unit,
    ) {
        if (finished) return
        if (observedPageValid) {
            finish(onCleared)
            return
        }
        if (checking) return
        checking = true
        try {
            val result = fetchPage()
            if (finished) return
            when (result) {
                is YamiboResult.Success -> finish(onCleared)
                is YamiboResult.Maintenance -> finish(onMaintenance)
                else -> Unit
            }
        } finally {
            checking = false
        }
    }
}

internal class ISignWebView(
    private val semiAutomatic: Boolean,
    private val onCfCleared: () -> Unit = {},
    private val onResultObserved: () -> Unit = {},
    private val onMaintenanceObserved: () -> Unit = {},
    private val onLoadFailed: (String) -> Unit = {},
) : Navigatable {
    override val id = buildId("sign-webview", semiAutomatic)

    @Composable
    override fun Content() {
        SignWebViewScreen(
            semiAutomatic = semiAutomatic,
            onCfCleared = onCfCleared,
            onResultObserved = onResultObserved,
            onMaintenanceObserved = onMaintenanceObserved,
            onLoadFailed = onLoadFailed,
        )
    }
}

@Composable
private fun SignWebViewScreen(
    semiAutomatic: Boolean,
    onCfCleared: () -> Unit,
    onResultObserved: () -> Unit,
    onMaintenanceObserved: () -> Unit,
    onLoadFailed: (String) -> Unit,
) {
    val navigator = LocalNavigator.current
    val authRepository = LocalAuthRepository.current
    val signRepository = LocalSignRepository.current
    val scope = rememberCoroutineScope()
    val autoSignCheck = remember(semiAutomatic) { SemiAutomaticSignCheck() }

    fun maybeStartSemiAutoSign(observedPageValid: Boolean = false) {
        if (!semiAutomatic) return
        authRepository.syncCookieFromWebView()
        scope.launch {
            autoSignCheck.check(
                observedPageValid = observedPageValid,
                fetchPage = { signRepository.fetchPageInfo() },
                onCleared = {
                    navigator.pop()
                    onCfCleared()
                },
                onMaintenance = {
                    onMaintenanceObserved()
                    navigator.pop()
                },
            )
        }
    }

    PlatformWebViewScreen(
        initialUrl = YamiboRoute.Sign.build(),
        initialTitle = i18n("每日簽到"),
        useBackIcon = true,
        captureHtml = true,
        onHtmlAvailable = { _, html ->
            if (isCloudflareChallengeHtml(html)) {
                return@PlatformWebViewScreen
            }
            val pageInfo = signRepository.cacheObservedHtml(html)
            val isResolvedResultPage = isSignResultPageHtml(html)
            if (semiAutomatic && isMaintenancePageHtml(html)) {
                autoSignCheck.finish {
                    onMaintenanceObserved()
                    navigator.pop()
                }
                return@PlatformWebViewScreen
            }
            if (pageInfo != null || isResolvedResultPage) {
                maybeStartSemiAutoSign(observedPageValid = pageInfo != null)
            }
            if (isResolvedResultPage) {
                onResultObserved()
            }
        },
        onLoadError = { _, description ->
            if (semiAutomatic) {
                autoSignCheck.finish {
                    onLoadFailed(description.ifBlank { i18n("簽到頁載入失敗") })
                    navigator.pop()
                }
            }
        },
    )
}
