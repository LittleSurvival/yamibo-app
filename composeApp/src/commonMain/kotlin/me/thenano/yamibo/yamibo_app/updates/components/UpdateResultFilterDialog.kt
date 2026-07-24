package me.thenano.yamibo.yamibo_app.updates.components

import androidx.compose.runtime.Composable
import me.thenano.yamibo.yamibo_app.components.controls.YamiboMultiSelectDialog
import me.thenano.yamibo.yamibo_app.i18n.i18n
import me.thenano.yamibo.yamibo_app.repository.FavoriteStoreRepository
import me.thenano.yamibo.yamibo_app.repository.FavoriteUpdateRepository

internal const val UPDATE_RESULT_FILTER_ALL = "all"

internal data class UpdateResultFilterOption(
    val key: String,
    val label: String,
    val count: Int,
)

@Composable
internal fun UpdateResultFilterDialog(
    options: List<UpdateResultFilterOption>,
    selectedKeys: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
) {
    val allOption = options.firstOrNull { it.key == UPDATE_RESULT_FILTER_ALL }
    val selected = options.filter { it.key in selectedKeys }.toSet().ifEmpty { allOption?.let(::setOf).orEmpty() }
    YamiboMultiSelectDialog(
        title = i18n("篩選更新結果"),
        options = options,
        selected = selected,
        onConfirm = { selectedOptions ->
            onConfirm(selectedOptions.map { it.key }.toSet())
        },
        onCancel = onDismiss,
        label = { "${it.label} (${it.count})" },
        toggleSelection = { option, current ->
            when {
                option.key == UPDATE_RESULT_FILTER_ALL -> setOf(option)
                current.any { it.key == UPDATE_RESULT_FILTER_ALL } -> setOf(option)
                option in current -> current - option
                else -> current + option
            }
        },
    )
}

internal fun buildUpdateResultFilterOptions(
    events: List<FavoriteUpdateRepository.UpdateEvent>,
): List<UpdateResultFilterOption> {
    val options = mutableListOf(UpdateResultFilterOption(UPDATE_RESULT_FILTER_ALL, i18n("全部"), events.size))
    val tagCount = events.count { it.targetType == FavoriteStoreRepository.FavoriteTargetType.TagManga }
    if (tagCount > 0) {
        options += UpdateResultFilterOption("tag", i18n("標籤"), tagCount)
    }
    val rssCount = events.count { it.targetType == FavoriteStoreRepository.FavoriteTargetType.RssSearch }
    if (rssCount > 0) {
        options += UpdateResultFilterOption("rss", i18n("RSS"), rssCount)
    }
    options += events
        .filter {
            it.targetType != FavoriteStoreRepository.FavoriteTargetType.TagManga &&
                it.targetType != FavoriteStoreRepository.FavoriteTargetType.RssSearch
        }
        .mapNotNull { event ->
            val fid = event.fid ?: return@mapNotNull null
            val label = event.forumName?.takeIf { it.isNotBlank() } ?: i18n("版塊 {}", fid)
            fid to label
        }
        .groupingBy { it }
        .eachCount()
        .entries
        .sortedWith(compareByDescending<Map.Entry<Pair<Int, String>, Int>> { it.value }.thenBy { it.key.second })
        .map { (fidAndLabel, count) ->
            val (fid, label) = fidAndLabel
            UpdateResultFilterOption("fid:$fid", label, count)
        }
    return options
}

internal fun normalizeUpdateResultFilterKeys(
    keys: Set<String>,
    options: List<UpdateResultFilterOption>,
): Set<String> {
    val validKeys = options.map { it.key }.toSet()
    val normalized = keys.filterTo(linkedSetOf()) { it in validKeys }
    return if (normalized.isEmpty() || UPDATE_RESULT_FILTER_ALL in normalized || normalized.size == validKeys.size - 1) {
        setOf(UPDATE_RESULT_FILTER_ALL)
    } else {
        normalized
    }
}

internal fun filterUpdateEvents(
    events: List<FavoriteUpdateRepository.UpdateEvent>,
    selectedKeys: Set<String>,
    options: List<UpdateResultFilterOption>,
): List<FavoriteUpdateRepository.UpdateEvent> {
    if (!isUpdateResultFilterRestricted(selectedKeys, options)) return events
    return events.filter { event ->
        val key = when (event.targetType) {
            FavoriteStoreRepository.FavoriteTargetType.TagManga -> {
                "tag"
            }
            FavoriteStoreRepository.FavoriteTargetType.RssSearch -> {
                "rss"
            }
            else -> {
                event.fid?.let { "fid:$it" }
            }
        }
        key != null && key in selectedKeys
    }
}

internal fun isUpdateResultFilterRestricted(
    selectedKeys: Set<String>,
    options: List<UpdateResultFilterOption>,
): Boolean {
    return options.size > 1 && UPDATE_RESULT_FILTER_ALL !in selectedKeys
}

internal fun updateResultFilterLabel(
    selectedKeys: Set<String>,
    options: List<UpdateResultFilterOption>,
): String {
    if (!isUpdateResultFilterRestricted(selectedKeys, options)) return i18n("全部")
    val selectedLabels = options.filter { it.key in selectedKeys }.map { it.label }
    return when {
        selectedLabels.isEmpty() -> i18n("全部")
        selectedLabels.size == 1 -> selectedLabels.first()
        selectedLabels.size <= 3 -> selectedLabels.joinToString("、")
        else -> i18n("{} 項", selectedLabels.size)
    }
}
