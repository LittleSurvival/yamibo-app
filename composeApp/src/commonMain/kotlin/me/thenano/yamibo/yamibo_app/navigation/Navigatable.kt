package me.thenano.yamibo.yamibo_app.navigation

import androidx.compose.runtime.Composable
import kotlin.jvm.JvmInline

@JvmInline
value class ScreenId(val value: String)

interface Navigatable {
    val id: ScreenId
    fun buildId(vararg param: Any?): ScreenId =
        ScreenId("${this::class.simpleName}_${param.joinToString("_")}")

    @Composable
    fun Content()
}
