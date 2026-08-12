package me.thenano.yamibo.yamibo_app.profile.sign

import io.github.littlesurvival.core.YamiboResult
import me.thenano.yamibo.yamibo_app.i18n.i18n
import me.thenano.yamibo.yamibo_app.repository.SignRepository

internal data class SignResultText(
    val noPermissionActionMessage: String,
    val localize: (String) -> String,
)

internal fun signResultText(): SignResultText = SignResultText(
    noPermissionActionMessage = i18n("目前無法自動簽到，請改用手動模式"),
    localize = ::i18n,
)

internal fun YamiboResult<SignRepository.ActionResult>.signActionFeedbackMessage(
    text: SignResultText = signResultText(),
): String = when (this) {
    is YamiboResult.Success -> value.message
    is YamiboResult.NoPermission -> text.noPermissionActionMessage
    is YamiboResult.NotLoggedIn,
    is YamiboResult.Maintenance,
    is YamiboResult.WafChallenge,
    is YamiboResult.Failure -> text.localize(message())
}

internal fun YamiboResult<SignRepository.SignPageInfo>.signInfoErrorMessage(
    text: SignResultText = signResultText(),
): String? = when (this) {
    is YamiboResult.Success -> null
    is YamiboResult.NotLoggedIn,
    is YamiboResult.Maintenance,
    is YamiboResult.WafChallenge -> text.localize(message())
    is YamiboResult.NoPermission,
    is YamiboResult.Failure -> message()
}
