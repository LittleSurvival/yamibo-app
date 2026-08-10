package me.thenano.yamibo.yamibo_app.thread.image

import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.request.ErrorResult
import coil3.request.SuccessResult
import coil3.toBitmap
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import platform.Foundation.NSData
import platform.Foundation.dataWithBytes
import platform.UIKit.*
import me.thenano.yamibo.yamibo_app.Logger
import me.thenano.yamibo.yamibo_app.util.buildImageRequest
import me.thenano.yamibo.yamibo_app.util.imageErrorForDiagnostics
import me.thenano.yamibo.yamibo_app.util.normalizeImageUrl
import me.thenano.yamibo.yamibo_app.i18n.i18n

private data class ImageBytesResult(
    val bytes: ByteArray? = null,
    val errorMessage: String? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as ImageBytesResult

        if (!bytes.contentEquals(other.bytes)) return false
        if (errorMessage != other.errorMessage) return false

        return true
    }

    override fun hashCode(): Int {
        var result = bytes?.contentHashCode() ?: 0
        result = 31 * result + errorMessage.hashCode()
        return result
    }
}

private suspend fun downloadImageBytes(
    context: PlatformContext,
    url: String,
    cookie: String,
    referer: String
): ImageBytesResult {
    return withContext(Dispatchers.Default) {
        try {
            val imageLoader = SingletonImageLoader.get(context)

            val request = buildImageRequest(
                context = context,
                url = url,
                cookie = cookie,
                referer = referer,
                enableCrossfade = false,
            )

            val result = imageLoader.execute(request)
            if (result !is SuccessResult) {
                val detail = imageErrorForDiagnostics(
                    (result as? ErrorResult)?.throwable?.message
                        ?: i18n("圖片請求沒有回傳可用錯誤原因"),
                    url,
                )
                return@withContext ImageBytesResult(errorMessage = i18n("下載圖片失敗：{}", detail))
            }

            val diskCacheKey = result.diskCacheKey
            if (diskCacheKey != null) {
                val diskCache = imageLoader.diskCache
                val snapshot = diskCache?.openSnapshot(diskCacheKey)
                if (snapshot != null) {
                    val bytes = diskCache.fileSystem.read(snapshot.data) { readByteArray() }
                    snapshot.close()
                    return@withContext ImageBytesResult(bytes = bytes)
                }
            }

            val bytes = result.image.toPngByteArray()
            if (bytes != null) {
                ImageBytesResult(bytes = bytes)
            } else {
                ImageBytesResult(errorMessage = i18n("下載圖片失敗：圖片已下載但無法轉換成 PNG"))
            }
        } catch (e: Exception) {
            val detail = imageErrorForDiagnostics(e.message ?: i18n("未知錯誤"), url)
            if (normalizeImageUrl(url).startsWith("data:", ignoreCase = true)) {
                Logger.w(TAG, "Image request failed: $detail")
            } else {
                Logger.e(TAG, "Image request failed", e)
            }
            ImageBytesResult(errorMessage = i18n("下載圖片失敗：{}", detail))
        }
    }
}

private fun coil3.Image.toPngByteArray(): ByteArray? {
    return try {
        val skiaImage = Image.makeFromBitmap(toBitmap())
        val data = skiaImage.encodeToData(EncodedImageFormat.PNG) ?: return null
        ByteArray(data.size).also { bytes ->
            data.bytes.copyInto(bytes)
        }
    } catch (error: Exception) {
        Logger.w(TAG, "Failed to encode image as PNG", error)
        null
    }
}

private const val TAG = "ImageActionHandler"

@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toUIImage(): UIImage? {
    val nsData = usePinned { pinned ->
        NSData.dataWithBytes(pinned.addressOf(0), size.toULong())
    }
    return UIImage.imageWithData(nsData)
}

private fun UIViewController.topMostViewController(): UIViewController {
    var current = this
    while (current.presentedViewController != null) {
        current = current.presentedViewController!!
    }
    return current
}

actual suspend fun copyImageToClipboard(
    context: PlatformContext,
    url: String,
    cookie: String,
    referer: String
): ImageActionResult {
    val result = downloadImageBytes(context, url, cookie, referer)
    val bytes = result.bytes ?: return ImageActionResult(errorMessage = result.errorMessage)
    val image = bytes.toUIImage() ?: return ImageActionResult(errorMessage = i18n("複製圖片失敗：圖片資料無法轉換為系統圖片"))
    withContext(Dispatchers.Main) {
        UIPasteboard.generalPasteboard.image = image
    }
    return ImageActionResult(successMessage = i18n("已複製圖片"))
}

actual suspend fun shareImageToApp(
    context: PlatformContext,
    url: String,
    cookie: String,
    referer: String
): ImageActionResult {
    val result = downloadImageBytes(context, url, cookie, referer)
    val bytes = result.bytes ?: return ImageActionResult(errorMessage = result.errorMessage)
    val image = bytes.toUIImage() ?: return ImageActionResult(errorMessage = i18n("分享圖片失敗：圖片資料無法轉換為系統圖片"))
    withContext(Dispatchers.Main) {
        val activityViewController = UIActivityViewController(listOf(image), null)
        val rootViewController = UIApplication.sharedApplication.keyWindow?.rootViewController
            ?: return@withContext
        rootViewController.topMostViewController()
            .presentViewController(activityViewController, animated = true, completion = null)
    }
    return ImageActionResult()
}

@OptIn(ExperimentalForeignApi::class)
actual suspend fun saveImageToGallery(
    context: PlatformContext,
    url: String,
    cookie: String,
    referer: String
) : ImageActionResult {
    val result = downloadImageBytes(context, url, cookie, referer)
    val bytes = result.bytes ?: return ImageActionResult(errorMessage = result.errorMessage)
    val image = bytes.toUIImage() ?: return ImageActionResult(errorMessage = i18n("儲存圖片失敗：圖片資料無法轉換為系統圖片"))
    withContext(Dispatchers.Main) {
        UIImageWriteToSavedPhotosAlbum(image, null, null, null)
    }
    return ImageActionResult(successMessage = i18n("已儲存圖片至相簿"))
}
