package me.thenano.yamibo.yamibo_app.repository.font

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import me.thenano.yamibo.yamibo_app.Logger
import me.thenano.yamibo.yamibo_app.repository.FontRepository
import me.thenano.yamibo.yamibo_app.repository.settings.AppSettingsRepository
import me.thenano.yamibo.yamibo_app.repository.settings.NovelReaderSettingsRepository
import me.thenano.yamibo.yamibo_app.store.settings.SettingsStore
import me.thenano.yamibo.yamibo_app.util.time.currentTimeMillis

class DefaultFontRepository(
    private val settingsStore: SettingsStore,
    private val appSettingsRepository: AppSettingsRepository,
    private val novelReaderSettingsRepository: NovelReaderSettingsRepository,
    private val platform: FontPlatform,
) : FontRepository {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val metadataKey = "font_repository.loaded_fonts"
    private val _fonts = MutableStateFlow(loadFonts())

    override val fonts: StateFlow<List<LoadedFont>> = _fonts
    override val platformSupportsFontLoading: Boolean
        get() = platform.supportsFontLoading
    override val platformUnavailableMessage: String?
        get() = platform.unavailableMessage

    override suspend fun loadFontFile(sourceUri: String, displayName: String?): FontLoadResult {
        val id = "font_${currentTimeMillis()}"
        return when (val result = platform.importFont(sourceUri, displayName, id)) {
            is FontImportResult.Success -> {
                val font = LoadedFont(
                    id = id,
                    name = result.name.ifBlank { result.fileName },
                    fileName = result.fileName,
                    platformPath = result.platformPath,
                    createdAt = currentTimeMillis(),
                )
                _fonts.value = (_fonts.value + font).distinctBy { it.id }
                saveFonts(_fonts.value)
                FontLoadResult.Success(font)
            }
            is FontImportResult.Unsupported -> FontLoadResult.Unsupported(result.message)
            is FontImportResult.Failure -> FontLoadResult.Failure(result.message)
        }
    }

    override fun listLoadedFonts(): List<LoadedFont> = _fonts.value

    override fun deleteFont(id: String): Boolean {
        val font = _fonts.value.firstOrNull { it.id == id } ?: return false
        val deleted = platform.deleteFont(font)
        _fonts.value = _fonts.value.filterNot { it.id == id }
        if (getAppFontId() == id) setAppFontId("")
        if (getReaderFontId() == id) setReaderFontId("")
        saveFonts(_fonts.value)
        return deleted
    }

    override fun getAppFontId(): String = appSettingsRepository.appFontId.getValue()
    override fun setAppFontId(id: String): Boolean = appSettingsRepository.appFontId.setValue(id)
    override fun getReaderFontId(): String = novelReaderSettingsRepository.readerFontId.getValue()
    override fun setReaderFontId(id: String): Boolean = novelReaderSettingsRepository.readerFontId.setValue(id)

    private fun loadFonts(): List<LoadedFont> {
        val raw = settingsStore.getString(metadataKey, "")
        if (raw.isBlank()) return emptyList()
        return try {
            json.decodeFromString<LoadedFontList>(raw).fonts
        } catch (error: SerializationException) {
            Logger.d("DefaultFontRepository", "Failed to decode loaded font metadata", error)
            emptyList()
        } catch (error: IllegalArgumentException) {
            Logger.d("DefaultFontRepository", "Invalid loaded font metadata", error)
            emptyList()
        }
    }

    private fun saveFonts(fonts: List<LoadedFont>) {
        settingsStore.putString(metadataKey, json.encodeToString(LoadedFontList(fonts)))
    }
}
