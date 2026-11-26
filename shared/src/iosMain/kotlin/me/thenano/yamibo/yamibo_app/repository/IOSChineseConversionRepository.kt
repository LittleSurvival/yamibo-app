package me.thenano.yamibo.yamibo_app.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.thenano.yamibo.yamibo_app.repository.chineseconversion.ChineseConversionMode

/**
 * iOS placeholder for the native OpenCC backend.
 *
 * The common repository is ready, but the real iOS implementation must be linked from a macOS
 * build pipeline as an OpenCC-backed framework or static library. See `docs/ios-opencc-bridge.md`
 * for the native ABI contract expected by the app.
 */
class IOSChineseConversionRepository : ChineseConversionRepository {
    private val modeFlow = MutableStateFlow<ChineseConversionMode?>(null)
    override val currentMode: StateFlow<ChineseConversionMode?> = modeFlow.asStateFlow()

    override fun setConversionMode(mode: ChineseConversionMode?) {
        modeFlow.value = mode
    }

    override suspend fun convert(text: String, mode: ChineseConversionMode?): String = text
    override fun isModeAvailable(mode: ChineseConversionMode): Boolean = false
}
