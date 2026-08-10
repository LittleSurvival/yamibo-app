package me.thenano.yamibo.yamibo_app.thread.image

import me.thenano.yamibo.yamibo_app.i18n.i18n

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.ActivityNotFoundException
import android.util.Log
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import android.graphics.Bitmap
import coil3.imageLoader
import coil3.request.ErrorResult
import coil3.request.SuccessResult
import coil3.BitmapImage
import coil3.PlatformContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import me.thenano.yamibo.yamibo_app.util.buildImageRequest
import me.thenano.yamibo.yamibo_app.util.imageErrorForDiagnostics
import me.thenano.yamibo.yamibo_app.util.normalizeImageUrl

private const val ImageActionLogTag = "YamiboImageAction"

private fun logImageRequestFailure(result: Any, url: String) {
    if (result is ErrorResult) {
        logImageActionFailure("Image request failed", result.throwable, url)
    } else {
        Log.e(ImageActionLogTag, "Image request returned ${result::class.simpleName}")
    }
}

private fun logImageActionFailure(message: String, error: Throwable, url: String) {
    if (normalizeImageUrl(url).startsWith("data:", ignoreCase = true)) {
        Log.e(ImageActionLogTag, "$message: ${imageErrorForDiagnostics(error.message.orEmpty(), url)}")
    } else {
        Log.e(ImageActionLogTag, message, error)
    }
}

private data class ImageDownloadResult(
    val file: File? = null,
    val errorMessage: String? = null,
)

private fun detailedDownloadFailure(action: String, error: Throwable?, url: String): String {
    val rawMessage = imageErrorForDiagnostics(error?.message.orEmpty(), url)
    val detail = when {
        rawMessage.contains("401") || rawMessage.contains("403") ->
            i18n("HTTP {}，可能是登入狀態或圖片 Referer 已失效", rawMessage.substringAfter("HTTP ").substringBefore(' ').ifBlank { rawMessage })
        rawMessage.contains("404") ->
            i18n("HTTP 404，圖片來源不存在或已被移除")
        rawMessage.isNotBlank() -> rawMessage
        else -> i18n("圖片請求沒有回傳可用錯誤原因")
    }
    return i18n("{}失敗：{}", action, detail)
}

private fun detailedDownloadFailureFromResult(action: String, result: Any, url: String): String {
    val error = (result as? ErrorResult)?.throwable
    return detailedDownloadFailure(action, error, url)
}

private fun detailedSaveFailure(reason: String): ImageActionResult =
    ImageActionResult(errorMessage = i18n("儲存圖片失敗：{}", reason))

private suspend fun downloadImage(context: Context, url: String, cookie: String, referer: String, fileName: String): ImageDownloadResult {
    return withContext(Dispatchers.IO) {
        try {
            val request = buildImageRequest(
                context = context,
                url = url,
                cookie = cookie,
                referer = referer,
                enableCrossfade = false,
            )

            val result = context.imageLoader.execute(request)
            if (result is SuccessResult) {
                val diskCacheKey = result.diskCacheKey
                if (diskCacheKey != null) {
                    val snapshot = context.imageLoader.diskCache?.openSnapshot(diskCacheKey)
                    if (snapshot != null) {
                        val sourcePath = snapshot.data
                        val imagesDir = File(context.cacheDir, "images")
                        imagesDir.mkdirs()
                        val file = File(imagesDir, fileName)
                        val sourceFile = sourcePath.toFile()
                        sourceFile.copyTo(file, overwrite = true)
                        snapshot.close()
                        return@withContext ImageDownloadResult(file = file)
                    }
                }
                
                // Fallback: compress decoded bitmap
                val image = result.image
                val bitmap = (image as? BitmapImage)?.bitmap
                if (bitmap != null) {
                    val imagesDir = File(context.cacheDir, "images")
                    imagesDir.mkdirs()
                    val file = File(imagesDir, fileName)
                    FileOutputStream(file).use { 
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)
                    }
                    return@withContext ImageDownloadResult(file = file)
                }
            }
            logImageRequestFailure(result, url)
            ImageDownloadResult(errorMessage = detailedDownloadFailureFromResult(i18n("下載圖片"), result, url))
        } catch (e: Exception) {
            logImageActionFailure("Image download failed", e, url)
            ImageDownloadResult(errorMessage = detailedDownloadFailure(i18n("下載圖片"), e, url))
        }
    }
}

actual suspend fun copyImageToClipboard(context: PlatformContext, url: String, cookie: String, referer: String): ImageActionResult {
    val download = downloadImage(context, url, cookie, referer, "copy_image.jpg")
    val file = download.file ?: return ImageActionResult(errorMessage = download.errorMessage)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newUri(context.contentResolver, "Image", uri)

    @Suppress("UsePropertyAccessSyntax")
    clipboard.setPrimaryClip(clip)

    return ImageActionResult(successMessage = i18n("已複製圖片"))
}

actual suspend fun shareImageToApp(context: PlatformContext, url: String, cookie: String, referer: String): ImageActionResult {
    val download = downloadImage(context, url, cookie, referer, "share_image.jpg")
    val file = download.file ?: return ImageActionResult(errorMessage = download.errorMessage)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "image/jpeg"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    // Context from Compose might not be an Activity context, so add FLAG_ACTIVITY_NEW_TASK
    val chooser = Intent.createChooser(intent, i18n("分享圖片")).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return try {
        context.startActivity(chooser)
        ImageActionResult()
    } catch (_: ActivityNotFoundException) {
        ImageActionResult(errorMessage = i18n("分享圖片失敗：找不到可分享圖片的 App"))
    } catch (e: Exception) {
        Log.e(ImageActionLogTag, "Sharing image failed", e)
        ImageActionResult(errorMessage = i18n("分享圖片失敗：{}", e.message ?: i18n("系統無法開啟分享畫面")))
    }
}

actual suspend fun saveImageToGallery(context: PlatformContext, url: String, cookie: String, referer: String): ImageActionResult {
    return withContext(Dispatchers.IO) {
        try {
            val request = buildImageRequest(
                context = context,
                url = url,
                cookie = cookie,
                referer = referer,
                enableCrossfade = false,
            )

            val result = context.imageLoader.execute(request)
            if (result is SuccessResult) {
                val fileName = "yamibo_${System.currentTimeMillis()}.jpg"

                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Yamibo")
                }

                val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                if (uri != null) {
                    val outputStream = context.contentResolver.openOutputStream(uri)
                        ?: return@withContext detailedSaveFailure(i18n("系統相簿無法開啟輸出串流"))
                    outputStream.use {
                        val diskCacheKey = result.diskCacheKey
                        var handled = false
                        if (diskCacheKey != null) {
                            val snapshot = context.imageLoader.diskCache?.openSnapshot(diskCacheKey)
                            if (snapshot != null) {
                                val sourceFile = snapshot.data.toFile()
                                sourceFile.inputStream().use { input -> input.copyTo(it) }
                                snapshot.close()
                                handled = true
                            }
                        }

                        if (!handled) {
                            val image = result.image
                            val bitmap = (image as? BitmapImage)?.bitmap
                            if (bitmap != null) {
                                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)
                                handled = true
                            }
                        }

                        if (handled) {
                            return@withContext ImageActionResult(successMessage = i18n("已儲存圖片至相簿"))
                        } else {
                            return@withContext detailedSaveFailure(i18n("圖片已下載，但無法取得原始檔案或 Bitmap"))
                        }
                    }
                } else {
                    return@withContext detailedSaveFailure(i18n("系統相簿無法建立檔案"))
                }
            } else {
                logImageRequestFailure(result, url)
                return@withContext ImageActionResult(errorMessage = detailedDownloadFailureFromResult(i18n("下載圖片"), result, url))
            }
        } catch (e: Exception) {
            logImageActionFailure("Saving image failed", e, url)
            return@withContext ImageActionResult(
                errorMessage = i18n(
                    "儲存圖片失敗：{}",
                    imageErrorForDiagnostics(e.message ?: i18n("系統相簿寫入失敗"), url),
                )
            )
        }
    }
}
