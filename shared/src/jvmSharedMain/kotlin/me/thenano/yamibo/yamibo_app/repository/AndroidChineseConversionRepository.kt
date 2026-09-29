package me.thenano.yamibo.yamibo_app.repository

import com.github.houbb.opencc4j.util.ZhConverterUtil
import com.github.houbb.opencc4j.util.ZhTwConverterUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import me.thenano.yamibo.yamibo_app.repository.chineseconversion.ChineseConversionMode

class AndroidChineseConversionRepository : ChineseConversionRepository {
    private val modeFlow = MutableStateFlow<ChineseConversionMode?>(null)
    override val currentMode: StateFlow<ChineseConversionMode?> = modeFlow.asStateFlow()

    override fun setConversionMode(mode: ChineseConversionMode?) {
        modeFlow.value = mode
    }

    override suspend fun convert(text: String): String = withContext(Dispatchers.Default) {
        when (modeFlow.value) {
            null -> text
            ChineseConversionMode.Simplified -> ZhConverterUtil.toSimple(text).fixSimplifiedConversion()
            ChineseConversionMode.Traditional -> ZhTwConverterUtil.toTraditional(text).fixTaiwanTraditionalConversion()
        }
    }

    override fun isModeAvailable(mode: ChineseConversionMode): Boolean = true
}

private val simplifiedPhraseOverrides = listOf(
    "乾杯" to "干杯",
    "乾燥" to "干燥",
)

private val taiwanTraditionalPhraseOverrides = listOf(
    "後麵" to "後面",
    "桌麵" to "桌面",
    "頁麵" to "頁面",
    "畫麵" to "畫面",
    "介麵" to "介面",
    "裡麵" to "裡面",
)

private fun String.fixSimplifiedConversion(): String =
    simplifiedPhraseOverrides.fold(this) { text, (from, to) -> text.replace(from, to) }

private fun String.fixTaiwanTraditionalConversion(): String =
    taiwanTraditionalPhraseOverrides.fold(this) { text, (from, to) -> text.replace(from, to) }
