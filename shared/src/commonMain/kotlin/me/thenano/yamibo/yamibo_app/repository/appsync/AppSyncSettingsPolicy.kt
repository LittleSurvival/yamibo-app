package me.thenano.yamibo.yamibo_app.repository.appsync

import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncSetting
import me.thenano.yamibo.yamibo_app.repository.appsync.model.AppSyncSettingType
import me.thenano.yamibo.yamibo_app.repository.settings.core.BoolSetting
import me.thenano.yamibo.yamibo_app.repository.settings.core.EnumSetting
import me.thenano.yamibo.yamibo_app.repository.settings.core.FloatSetting
import me.thenano.yamibo.yamibo_app.repository.settings.core.IntSetting
import me.thenano.yamibo.yamibo_app.repository.settings.core.SettingItem
import me.thenano.yamibo.yamibo_app.repository.settings.core.SettingsRegistry
import me.thenano.yamibo.yamibo_app.repository.settings.core.StringSetting

class AppSyncSettingsPolicy(
    registries: List<SettingsRegistry>,
    syncableKeys: Set<String> = DEFAULT_SYNCABLE_KEYS,
) {
    private val itemsByKey = registries
        .flatMap(SettingsRegistry::exportableSettingItems)
        .associateBy(SettingItem<*>::storageKey)
    private val syncableItems: Map<String, SettingItem<*>> = itemsByKey
        .filterKeys(syncableKeys::contains)
        .toList()
        .sortedBy { it.first }
        .toMap()

    data class PreparedSetting(
        val item: SettingItem<*>,
        val previous: AppSyncSetting,
        val next: AppSyncSetting,
    )

    data class Preparation(
        val settings: List<PreparedSetting>,
        val skipped: Int,
        val warnings: List<String>,
    )

    fun export(): List<AppSyncSetting> = syncableItems.values.mapNotNull(::toWire)

    fun prepare(imported: List<AppSyncSetting>, overwrite: Boolean): Preparation {
        val actions = linkedMapOf<String, PreparedSetting>()
        val warnings = mutableListOf<String>()
        var skipped = 0

        imported.sortedBy(AppSyncSetting::key).forEach { wire ->
            val item = syncableItems[wire.key]
            if (item == null) {
                skipped += 1
                warnings += "Skipped unknown or local-only setting ${wire.key}"
                return@forEach
            }
            if (!accepts(item, wire)) {
                skipped += 1
                warnings += "Skipped invalid setting ${wire.key}"
                return@forEach
            }
            val previous = toWire(item) ?: return@forEach
            actions[wire.key] = PreparedSetting(item, previous, wire)
        }

        if (overwrite) {
            syncableItems.forEach { (key, item) ->
                if (key !in actions) {
                    val previous = toWire(item) ?: return@forEach
                    actions[key] = PreparedSetting(item, previous, defaultWire(item))
                }
            }
        }
        return Preparation(actions.values.toList(), skipped, warnings)
    }

    fun apply(prepared: PreparedSetting): Boolean = write(prepared.item, prepared.next)

    fun rollback(prepared: PreparedSetting): Boolean = write(prepared.item, prepared.previous)

    private fun accepts(item: SettingItem<*>, wire: AppSyncSetting): Boolean {
        if (typeOf(item) != wire.type) return false
        return when (item) {
            is IntSetting -> wire.value.toIntOrNull()?.let { it in item.min..item.max } == true
            is FloatSetting -> wire.value.toFloatOrNull()?.let { it.isFinite() && it in item.min..item.max } == true
            is BoolSetting -> wire.value.toBooleanStrictOrNull() != null
            is StringSetting -> item.allowedValues.isEmpty() || wire.value in item.allowedValues
            is EnumSetting<*> -> item.acceptsName(wire.value)
            else -> false
        }
    }

    private fun write(item: SettingItem<*>, wire: AppSyncSetting): Boolean = when (item) {
        is IntSetting -> wire.value.toIntOrNull()?.let(item::setValue) ?: false
        is FloatSetting -> wire.value.toFloatOrNull()?.let(item::setValue) ?: false
        is BoolSetting -> wire.value.toBooleanStrictOrNull()?.let(item::setValue) ?: false
        is StringSetting -> item.setValue(wire.value)
        is EnumSetting<*> -> item.setValueFromName(wire.value)
        else -> false
    }

    private fun toWire(item: SettingItem<*>): AppSyncSetting? {
        val type = typeOf(item) ?: return null
        val value = when (val current = item.getValue()) {
            is Enum<*> -> current.name
            else -> current.toString()
        }
        return AppSyncSetting(item.storageKey, type, value)
    }

    private fun defaultWire(item: SettingItem<*>): AppSyncSetting {
        val type = requireNotNull(typeOf(item))
        val value = when (val default = item.default) {
            is Enum<*> -> default.name
            else -> default.toString()
        }
        return AppSyncSetting(item.storageKey, type, value)
    }

    private fun typeOf(item: SettingItem<*>): AppSyncSettingType? = when (item) {
        is IntSetting -> AppSyncSettingType.Int
        is FloatSetting -> AppSyncSettingType.Float
        is BoolSetting -> AppSyncSettingType.Bool
        is StringSetting -> AppSyncSettingType.String
        is EnumSetting<*> -> AppSyncSettingType.Enum
        else -> null
    }

    companion object {
        /**
         * Default-deny list. Newly registered settings remain local until added here.
         * Authentication, caches, run IDs, timestamps and device paths are intentionally absent.
         */
        val DEFAULT_SYNCABLE_KEYS: Set<String> = setOf(
            "appsettings.thememode",
            "appsettings.themescheme",
            "appsettings.language",
            "appsettings.ismangamode",
            "appsettings.clearcacheonapplaunch",
            "appsettings.showhomeswiperimages",
            "appsettings.skipfavoriteremovalconfirm",
            "appsettings.favoriteaddsyncpromptenabled",
            "appsettings.favoriteaddsyncdefault",
            "appsettings.favoriteremovesyncpromptenabled",
            "appsettings.favoriteremovesyncdefault",
            "appsettings.favoritegridmode",
            "appsettings.favoritesortmode",
            "appsettings.favoritesortdescending",
            "appsettings.favoriteupdateinterval",
            "appsettings.favoriteupdateautodownload",
            "appsettings.downloadedcontentrefreshautoupdate",
            "appsettings.appupdatepreferredsourceindex",
            "appsettings.appupdatelaunchcheckthreshold",
            "appsettings.backupinterval",
            "appsettings.backupmaxautofiles",
            "appsettings.signinmode",
            "appsettings.signinlaunchreminderenabled",
            "appsettings.signinlaunchreminderdismisstoday",
            "appsettings.signinallowrepair",
            "appsettings.signinreminderfrequency",
            "appsettings.signindirectwebview",
            "novelreadersettings.fontsize",
            "novelreadersettings.linespacing",
            "novelreadersettings.contentwidthfraction",
            "novelreadersettings.keepsystembarsbackground",
            "novelreadersettings.chineseconversion",
            "novelreadersettings.threadreadermode",
            "novelreadersettings.scrollbuttondisplaymode",
            "novelreadersettings.scrollbuttondirectionthreshold",
            "novelreadersettings.scrollbuttonjumptarget",
            "novelreadersettings.showpageprogresshint",
            "mangareadersettings.readingmode",
            "mangareadersettings.touchzone",
        )
    }
}
