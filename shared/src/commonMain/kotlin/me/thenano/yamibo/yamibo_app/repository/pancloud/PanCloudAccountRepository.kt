package me.thenano.yamibo.yamibo_app.repository.pancloud

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.thenano.yamibo.yamibo_app.repository.settings.AppSettingsRepository

/** 网盘会话状态。 */
enum class PanCloudSessionState {
    /** access_token 有效（刚登录/刷新成功）。 */
    Active,

    /** 无 refresh_token（未登录/已登出）。 */
    LoggedOut,

    /** refresh_token 过期且密码重登失败，需用户重新登录。 */
    Expired,

    /** 有 refresh_token 但尚未验证（启动初始）。 */
    Unknown,
}

/**
 * 网盘账户服务：注册/登录/登出/会话恢复，以及 `yamibo` 文件夹的幂等绑定。
 *
 * 凭证（refresh_token / 密码）持久化在传入的 [SettingsStore]（Android 用加密 store，
 * iOS 暂用 NSUserDefaults），且已从备份排除。access_token 仅保存在 [PanCloudApiClient] 内存中。
 *
 * 会话状态通过 [sessionState] 暴露：
 * - access_token 失效 → 自动 refresh（透明）。
 * - refresh_token 过期 → 用保存的密码自动重登；密码也失效 → 标记 [PanCloudSessionState.Expired]。
 */
class PanCloudAccountRepository(
    private val apiClient: PanCloudApiClient,
    private val appSettings: AppSettingsRepository,
) {

    data class AccountStatus(
        val state: PanCloudSessionState,
        val username: String?,
        val folderId: String?,
    ) {
        val loggedIn: Boolean get() = state == PanCloudSessionState.Active
    }

    private val mutableSession = MutableStateFlow(
        AccountStatus(
            state = if (appSettings.panCloudRefreshToken.getValue().isNotBlank()) {
                PanCloudSessionState.Unknown
            } else {
                PanCloudSessionState.LoggedOut
            },
            username = appSettings.panCloudUsername.getValue().takeIf { it.isNotBlank() },
            folderId = appSettings.panCloudFolderId.getValue().takeIf { it.isNotBlank() },
        ),
    )
    val sessionState: StateFlow<AccountStatus> = mutableSession.asStateFlow()

    val status: AccountStatus
        get() = mutableSession.value

    init {
        apiClient.onUnauthorized = { refreshAccessToken() }
    }

    suspend fun register(
        username: String,
        password: String,
        email: String? = null,
    ): Result<AccountStatus> = runCatching {
        adoptAuth(apiClient.register(username, password, email), password)
    }

    suspend fun login(username: String, password: String): Result<AccountStatus> = runCatching {
        adoptAuth(apiClient.login(username, password), password)
    }

    /** 用已保存的 refresh_token 恢复登录态；refresh 过期则尝试密码自动重登。 */
    suspend fun restoreSession(): Result<AccountStatus> = runCatching {
        val refreshToken = appSettings.panCloudRefreshToken.getValue()
        if (refreshToken.isBlank()) {
            updateSession(PanCloudSessionState.LoggedOut)
            return@runCatching status
        }
        val refreshResult = runCatching { apiClient.refresh(refreshToken) }
        if (refreshResult.isSuccess) {
            adoptAuth(refreshResult.getOrThrow(), password = null)
        } else {
            val error = refreshResult.exceptionOrNull()
            if (error is PanCloudApiException && error.statusCode == 401) {
                if (reloginWithSavedPassword()) return@runCatching status
                clearAuth()
                updateSession(PanCloudSessionState.Expired)
            }
            throw error ?: IllegalStateException("refresh failed")
        }
    }

    suspend fun logout(): Result<Unit> = runCatching {
        runCatching { apiClient.logout() }
        clearAuth()
        updateSession(PanCloudSessionState.LoggedOut)
    }

    /** 幂等确保 `yamibo` 文件夹存在，返回其 ID（用于上传/列出备份）。 */
    suspend fun ensureFolderBound(): Result<String> = runCatching {
        val existing = appSettings.panCloudFolderId.getValue()
        if (existing.isNotBlank()) return@runCatching existing
        val root = apiClient.listFiles(parentId = null)
        val yamibo = root.firstOrNull { it.type == FOLDER_TYPE && it.name == FOLDER_NAME }
        val folderId = yamibo?.id ?: apiClient.createFolder(FOLDER_NAME, null).id
        appSettings.panCloudFolderId.setValue(folderId)
        updateSession(mutableSession.value.state)
        folderId
    }

    private suspend fun adoptAuth(result: PanCloudAuthResult, password: String?): AccountStatus {
        applyTokens(result)
        if (password != null) {
            appSettings.panCloudPassword.setValue(password)
        }
        ensureFolderBound()
        updateSession(PanCloudSessionState.Active)
        return status
    }

    private fun applyTokens(result: PanCloudAuthResult) {
        apiClient.accessToken = result.accessToken
        appSettings.panCloudRefreshToken.setValue(result.refreshToken)
        appSettings.panCloudUsername.setValue(result.user.username)
    }

    private suspend fun refreshAccessToken(): Boolean {
        val refreshToken = appSettings.panCloudRefreshToken.getValue()
        if (refreshToken.isBlank()) {
            updateSession(PanCloudSessionState.LoggedOut)
            return false
        }
        val refreshResult = runCatching { apiClient.refresh(refreshToken) }
        if (refreshResult.isSuccess) {
            applyTokens(refreshResult.getOrThrow())
            updateSession(PanCloudSessionState.Active)
            return true
        }
        val error = refreshResult.exceptionOrNull()
        if (error is PanCloudApiException && error.statusCode == 401) {
            if (reloginWithSavedPassword()) return true
            clearAuth()
            updateSession(PanCloudSessionState.Expired)
        }
        return false
    }

    /** 用保存的密码重新登录以续期；失败返回 false。 */
    private suspend fun reloginWithSavedPassword(): Boolean {
        val username = appSettings.panCloudUsername.getValue()
        val password = appSettings.panCloudPassword.getValue()
        if (username.isBlank() || password.isBlank()) return false
        return runCatching {
            adoptAuth(apiClient.login(username, password), password = null)
            true
        }.getOrDefault(false)
    }

    private fun clearAuth() {
        apiClient.accessToken = null
        appSettings.panCloudRefreshToken.setValue("")
        appSettings.panCloudUsername.setValue("")
        appSettings.panCloudFolderId.setValue("")
        appSettings.panCloudPassword.setValue("")
    }

    private fun updateSession(state: PanCloudSessionState) {
        mutableSession.value = AccountStatus(
            state = state,
            username = appSettings.panCloudUsername.getValue().takeIf { it.isNotBlank() },
            folderId = appSettings.panCloudFolderId.getValue().takeIf { it.isNotBlank() },
        )
    }

    companion object {
        const val FOLDER_NAME = "yamibo"
        const val FOLDER_TYPE = "folder"
    }
}
