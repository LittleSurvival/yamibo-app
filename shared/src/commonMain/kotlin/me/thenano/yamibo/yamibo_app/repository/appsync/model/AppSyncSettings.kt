package me.thenano.yamibo.yamibo_app.repository.appsync.model

import kotlinx.serialization.Serializable

@Serializable
data class AppSyncSetting(
    val key: String,
    val type: AppSyncSettingType,
    val value: String,
)

@Serializable
enum class AppSyncSettingType {
    Int,
    Float,
    Bool,
    String,
    Enum,
}
