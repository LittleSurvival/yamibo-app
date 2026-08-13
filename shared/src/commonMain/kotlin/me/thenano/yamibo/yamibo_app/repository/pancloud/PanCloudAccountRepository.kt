package me.thenano.yamibo.yamibo_app.repository.pancloud

import me.thenano.yamibo.yamibo_app.repository.settings.AppSettingsRepository

/**
 * 网盘账户服务：注册/登录/登出/会话恢复，以及 `yamibo` 文件夹的幂等绑定。
 *
 * 凭证（refresh_token）持久化在 [AppSettingsRepository]（key 已从备份排除），
 * access_token 仅保存在 [PanCloudApiClient] 内存中，过期后由 onUnauthorized 钩子自动刷新。
 */
class PanCloudAccountRepository(
    private val apiClient: PanCloudApiClient,
    private val appSettings: AppSettingsRepository,
) {
    data class AccountStatus(
        val loggedIn: Boolean,
        val username: String?,
        val folderId: String?,
    )

    val status: AccountStatus
        get() = AccountStatus(
            loggedIn = appSettings.panCloudRefreshToken.getValue().isNotBlank(),
            username = appSettings.panCloudUsername.getValue().takeIf { it.isNotBlank() },
            folderId = appSettings.panCloudFolderId.getValue().takeIf { it.isNotBlank() },
        )

    init {
        apiClient.onUnauthorized = { refreshAccessToken() }
    }

    suspend fun register(
        username: String,
        password: String,
        email: String? = null,
    ): Result<AccountStatus> = runCatching {
        adoptAuth(apiClient.register(username, password, email))
    }

    suspend fun login(username: String, password: String): Result<AccountStatus> = runCatching {
        adoptAuth(apiClient.login(username, password))
    }

    /** App 启动时用已保存的 refresh_token 恢复登录态并刷新 access_token。 */
    suspend fun restoreSession(): Result<AccountStatus> = runCatching {
        val refreshToken = appSettings.panCloudRefreshToken.getValue()
        if (refreshToken.isBlank()) return@runCatching status
        adoptAuth(apiClient.refresh(refreshToken))
    }

    suspend fun logout(): Result<Unit> = runCatching {
        runCatching { apiClient.logout() }
        clearAuth()
    }

    /** 幂等确保 `yamibo` 文件夹存在，返回其 ID（用于上传/列出备份）。 */
    suspend fun ensureFolderBound(): Result<String> = runCatching {
        val existing = appSettings.panCloudFolderId.getValue()
        if (existing.isNotBlank()) return@runCatching existing
        val root = apiClient.listFiles(parentId = null)
        val yamibo = root.firstOrNull { it.type == FOLDER_TYPE && it.name == FOLDER_NAME }
        val folderId = yamibo?.id ?: apiClient.createFolder(FOLDER_NAME, null).id
        appSettings.panCloudFolderId.setValue(folderId)
        folderId
    }

    private suspend fun adoptAuth(result: PanCloudAuthResult): AccountStatus {
        apiClient.accessToken = result.accessToken
        appSettings.panCloudRefreshToken.setValue(result.refreshToken)
        appSettings.panCloudUsername.setValue(result.user.username)
        // 文件夹绑定失败不阻塞注册/登录，后续备份前会再次绑定。
        ensureFolderBound()
        return status
    }

    private suspend fun refreshAccessToken(): Boolean {
        val refreshToken = appSettings.panCloudRefreshToken.getValue()
        if (refreshToken.isBlank()) return false
        return runCatching {
            val result = apiClient.refresh(refreshToken)
            apiClient.accessToken = result.accessToken
            appSettings.panCloudRefreshToken.setValue(result.refreshToken)
            appSettings.panCloudUsername.setValue(result.user.username)
            true
        }.getOrDefault(false)
    }

    private fun clearAuth() {
        apiClient.accessToken = null
        appSettings.panCloudRefreshToken.setValue("")
        appSettings.panCloudUsername.setValue("")
        appSettings.panCloudFolderId.setValue("")
    }

    companion object {
        const val FOLDER_NAME = "yamibo"
        const val FOLDER_TYPE = "folder"
    }
}
