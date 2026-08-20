package me.thenano.yamibo.yamibo_app.favorite

import androidx.compose.runtime.Composable
import me.thenano.yamibo.yamibo_app.LocalAppFeedbackController
import me.thenano.yamibo.yamibo_app.LocalAppTaskManager
import me.thenano.yamibo.yamibo_app.LocalDownloadRepository
import me.thenano.yamibo.yamibo_app.LocalFavoriteRepository
import me.thenano.yamibo.yamibo_app.LocalRssSearchSubscriptionRepository
import me.thenano.yamibo.yamibo_app.i18n.i18n
import me.thenano.yamibo.yamibo_app.navigation.LocalNavigator
import me.thenano.yamibo.yamibo_app.profile.settings.ISettingsCategoryScreen
import me.thenano.yamibo.yamibo_app.thread.detail.components.CatalogDownloadAction
import me.thenano.yamibo.yamibo_app.thread.detail.components.CatalogDownloadActionSheet
import me.thenano.yamibo.yamibo_app.thread.reader.ReaderDownloadSheet
import me.thenano.yamibo.yamibo_app.task.AppTaskKey

internal enum class FavoritePostAddDownloadSurface {
    ThreadReader,
    TagCatalog,
    RssCatalog,
}

internal fun FavoriteTargetPayload.postAddDownloadSurface(): FavoritePostAddDownloadSurface = when (this) {
    is FavoriteTargetPayload.Thread -> FavoritePostAddDownloadSurface.ThreadReader
    is FavoriteTargetPayload.TagManga -> FavoritePostAddDownloadSurface.TagCatalog
    is FavoriteTargetPayload.RssSearch -> FavoritePostAddDownloadSurface.RssCatalog
}

/** Download choices shown only after a newly-created favorite. */
@Composable
internal fun FavoritePostAddDownloadSheet(
    target: FavoriteTargetPayload,
    doNotAskAgain: Boolean,
    onDoNotAskAgainChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val downloadRepository = LocalDownloadRepository.current
    val favoriteRepository = LocalFavoriteRepository.current
    val rssRepository = LocalRssSearchSubscriptionRepository.current
    val feedbackController = LocalAppFeedbackController.current
    val appTaskManager = LocalAppTaskManager.current
    val navigator = LocalNavigator.current

    fun enqueue(mode: FavoriteBatchDownloadMode) {
        onDismiss()
        appTaskManager.launch(AppTaskKey("download:favorite-post-add:${target.taskIdentity()}:$mode")) {
            if (!downloadRepository.isStorageReady()) {
                feedbackController.post(i18n("尚未設定下載資料夾"))
                navigator.navigate(ISettingsCategoryScreen("storage"))
                return@launch
            }
            val item = favoriteRepository.findFavoriteItem(target)
            if (item == null) {
                feedbackController.post(i18n("找不到剛新增的收藏"))
                return@launch
            }
            val result = enqueueFavoriteBatchDownloads(
                downloadRepository = downloadRepository,
                rssRepository = rssRepository,
                items = listOf(item),
                mode = mode,
            )
            feedbackController.post(favoriteBatchDownloadResultMessage(result))
        }
    }

    when (target.postAddDownloadSurface()) {
        FavoritePostAddDownloadSurface.ThreadReader -> ReaderDownloadSheet(
            onDismiss = onDismiss,
            showDownloadPage = false,
            showDownloadThread = true,
            showDownloadThreadExceptLastPage = true,
            showClearPage = false,
            showClearThread = false,
            onDownloadPage = {},
            onDownloadThread = { enqueue(FavoriteBatchDownloadMode.All) },
            onDownloadThreadExceptLastPage = { enqueue(FavoriteBatchDownloadMode.ExceptLastPage) },
            onClearPage = {},
            onClearThread = {},
            doNotAskAgain = doNotAskAgain,
            onDoNotAskAgainChange = onDoNotAskAgainChange,
        )

        FavoritePostAddDownloadSurface.TagCatalog -> CatalogDownloadActionSheet(
            title = i18n("標籤漫畫下載"),
            actions = listOf(CatalogDownloadAction(i18n("下載全部分頁")) { enqueue(FavoriteBatchDownloadMode.All) }),
            onDismiss = onDismiss,
            doNotAskAgain = doNotAskAgain,
            onDoNotAskAgainChange = onDoNotAskAgainChange,
        )

        FavoritePostAddDownloadSurface.RssCatalog -> CatalogDownloadActionSheet(
            title = i18n("RSS 漫畫下載"),
            actions = listOf(CatalogDownloadAction(i18n("下載全部分頁")) { enqueue(FavoriteBatchDownloadMode.All) }),
            onDismiss = onDismiss,
            doNotAskAgain = doNotAskAgain,
            onDoNotAskAgainChange = onDoNotAskAgainChange,
        )
    }
}
