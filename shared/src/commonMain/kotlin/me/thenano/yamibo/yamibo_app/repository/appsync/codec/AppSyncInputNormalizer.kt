package me.thenano.yamibo.yamibo_app.repository.appsync.codec

import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncCodecErrorKind
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncError
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncFormat
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncResult

class AppSyncInputNormalizer(
    private val maxRawTextChars: Int = AppSyncFormat.MAX_RAW_TEXT_CHARS,
) {
    fun normalize(raw: String): AppSyncResult<String> {
        if (raw.length > maxRawTextChars) {
            return AppSyncResult.Failure(
                AppSyncError.Codec(
                    AppSyncCodecErrorKind.PayloadTooLarge,
                    "Raw app-sync text exceeds $maxRawTextChars characters",
                ),
            )
        }
        return AppSyncResult.Success(
            raw
                .replace(NBSP_ENTITY, "")
                .replace('\u00A0'.toString(), "")
                .trim(),
        )
    }

    private companion object {
        val NBSP_ENTITY = Regex("""&(?:nbsp|#160|#x0*a0);""", RegexOption.IGNORE_CASE)
    }
}
