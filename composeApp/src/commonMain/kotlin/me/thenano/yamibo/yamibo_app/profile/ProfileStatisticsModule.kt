package me.thenano.yamibo.yamibo_app.profile

import me.thenano.yamibo.yamibo_app.i18n.i18n
import me.thenano.yamibo.yamibo_app.components.controls.appVerticalScroll as verticalScroll

import YamiboIcons
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.thenano.yamibo.yamibo_app.LocalBookMarkRepository
import me.thenano.yamibo.yamibo_app.LocalFavoriteRepository
import me.thenano.yamibo.yamibo_app.LocalReadHistoryRepository
import me.thenano.yamibo.yamibo_app.components.navigation.YamiboTopBar
import me.thenano.yamibo.yamibo_app.navigation.LocalNavigator
import me.thenano.yamibo.yamibo_app.repository.FavoriteStoreRepository.FavoriteCategory
import me.thenano.yamibo.yamibo_app.repository.FavoriteStoreRepository.FavoriteTargetType
import me.thenano.yamibo.yamibo_app.repository.ReadHistoryRepository
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme
import me.thenano.yamibo.yamibo_app.util.time.currentLocalDateKeyAt
import me.thenano.yamibo.yamibo_app.util.time.currentTimeMillis
import kotlin.math.ceil
import kotlin.math.max

@Composable
fun ProfileStatisticsModule() {
    val colors = YamiboTheme.colors
    val navigator = LocalNavigator.current
    val favoriteRepository = LocalFavoriteRepository.current
    val readHistoryRepository = LocalReadHistoryRepository.current
    val bookMarkRepository = LocalBookMarkRepository.current

    var chartType by rememberSaveable { mutableStateOf(ProfileChartType.Bar) }
    var chartRange by rememberSaveable { mutableStateOf(ProfileStatsRange.Week) }
    var state by remember { mutableStateOf(ProfileStatisticsState.loading(chartRange)) }

    LaunchedEffect(chartRange, favoriteRepository, readHistoryRepository, bookMarkRepository) {
        state = ProfileStatisticsState.loading(chartRange)
        state = loadProfileStatistics(
            range = chartRange,
            favoriteRepository = favoriteRepository,
            readHistoryRepository = readHistoryRepository,
            bookMarkRepository = bookMarkRepository,
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.creamBackground),
    ) {
        YamiboTopBar(title = i18n("閱讀統計"), onBack = navigator::pop)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ProfileSectionCard(title = i18n("作品與章節")) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProfileMetricTile(i18n("書櫃"), "${state.shelfCount}", i18n("作品"), Modifier.weight(1f))
                    ProfileMetricTile(i18n("閱畢"), "${state.finishedWorkCount}", i18n("作品"), Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                ProfileMetricTile(i18n("章節"), "${state.readChapterCount}/${state.chapterCount}", i18n("已讀/總數"))
            }

            ProfileSectionCard(title = i18n("閱讀時數")) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProfileMetricTile(i18n("總時數"), formatDuration(state.allReadingMillis), i18n("全部"), Modifier.weight(1f))
                    ProfileMetricTile(i18n("本周時數"), formatDuration(state.weekReadingMillis), i18n("7 天"), Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                ProfileMetricTile(i18n("本月時數"), formatDuration(state.monthReadingMillis), i18n("本月"))
            }

            ProfileSectionCard(title = i18n("閱讀圖表")) {
                ProfileControlRow(
                    title = i18n("區間"),
                    options = ProfileStatsRange.entries,
                    selected = chartRange,
                    label = { it.label },
                    onSelect = { chartRange = it },
                )
                Spacer(Modifier.height(8.dp))
                ProfileControlRow(
                    title = i18n("圖表"),
                    options = ProfileChartType.entries,
                    selected = chartType,
                    label = { it.label },
                    onSelect = { chartType = it },
                )
                Spacer(Modifier.height(12.dp))
                ProfileReadingChart(
                    chartType = chartType,
                    points = state.readingDays,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(170.dp),
                )
            }

            ProfileSectionCard(title = i18n("收藏模塊比率")) {
                FavoriteRatioList(
                    ratios = state.favoriteRatios,
                    fallbackTypeRatios = state.favoriteTypeRatios,
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ProfileSectionCard(
    title: String,
    content: @Composable () -> Unit,
) {
    val colors = YamiboTheme.colors
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(18.dp),
        elevation = CardDefaults.cardElevation(2.dp),
        colors = CardDefaults.cardColors(containerColor = colors.creamSurface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = colors.orangeAccent.copy(alpha = 0.18f),
                    modifier = Modifier.size(34.dp),
                ) {
                    Icon(
                        imageVector = YamiboIcons.Statistics,
                        contentDescription = null,
                        tint = colors.brownPrimary,
                        modifier = Modifier.padding(8.dp),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    text = title,
                    color = colors.textDark,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                )
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun ProfileMetricTile(title: String, value: String, hint: String, modifier: Modifier = Modifier) {
    val colors = YamiboTheme.colors
    Column(
        modifier = modifier
            .background(colors.creamBackground.copy(alpha = 0.72f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(text = title, color = colors.brownPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(text = value, color = colors.textDark, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(text = hint, color = colors.brownLight, fontSize = 11.sp)
    }
}

@Composable
private fun <T> ProfileControlRow(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    val colors = YamiboTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = title, color = colors.brownPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(8.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { option ->
                val isSelected = option == selected
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = if (isSelected) colors.brownPrimary else colors.creamBackground,
                    modifier = Modifier.clickable { onSelect(option) },
                ) {
                    Text(
                        text = label(option),
                        color = if (isSelected) colors.creamSurface else colors.brownPrimary,
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileReadingChart(
    chartType: ProfileChartType,
    points: List<ReadingDayPoint>,
    modifier: Modifier = Modifier,
) {
    val colors = YamiboTheme.colors
    val textMeasurer = rememberTextMeasurer()
    val maxDurationMillis = points.maxOfOrNull { it.durationMillis } ?: 0L
    val yAxisMaxMillis = niceDurationAxisMax(maxDurationMillis)
    val values = points.map { it.durationMillis }
    Box(
        modifier = modifier.background(colors.creamBackground.copy(alpha = 0.54f), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (points.isEmpty() || maxDurationMillis <= 0L) {
            Text(text = i18n("尚無閱讀時數資料"), color = colors.brownLight, fontSize = 13.sp)
            return@Box
        }
        Canvas(modifier = Modifier.matchParentSize().padding(horizontal = 12.dp, vertical = 16.dp)) {
            val palette = listOf(
                colors.brownPrimary,
                colors.orangeAccent,
                colors.redAccent,
                colors.brownLight,
            )
            val labelStyle = TextStyle(
                color = colors.brownLight.copy(alpha = 0.88f),
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
            )
            val gridColor = colors.brownLight.copy(alpha = 0.24f)
            val axisColor = colors.brownLight.copy(alpha = 0.48f)
            when (chartType) {
                ProfileChartType.Bar -> {
                    val plot = chartPlotArea()
                    drawChartAxes(
                        points = points,
                        plot = plot,
                        yAxisMaxMillis = yAxisMaxMillis,
                        textMeasurer = textMeasurer,
                        labelStyle = labelStyle,
                        axisColor = axisColor,
                        gridColor = gridColor,
                        lineMode = false,
                    )
                    val slot = plot.width / values.size
                    val barWidth = max(5f, slot * 0.48f)
                    values.forEachIndexed { index, value ->
                        val barHeight = value.toFloat() / yAxisMaxMillis.toFloat() * plot.height
                        drawRoundRect(
                            color = colors.brownPrimary,
                            topLeft = Offset(plot.left + index * slot + (slot - barWidth) / 2f, plot.bottom - barHeight),
                            size = Size(barWidth, barHeight),
                            cornerRadius = CornerRadius(8f, 8f),
                        )
                    }
                }

                ProfileChartType.Histogram -> {
                    val plot = chartPlotArea()
                    drawChartAxes(
                        points = points,
                        plot = plot,
                        yAxisMaxMillis = yAxisMaxMillis,
                        textMeasurer = textMeasurer,
                        labelStyle = labelStyle,
                        axisColor = axisColor,
                        gridColor = gridColor,
                        lineMode = false,
                    )
                    val slot = plot.width / values.size
                    values.forEachIndexed { index, value ->
                        val barHeight = value.toFloat() / yAxisMaxMillis.toFloat() * plot.height
                        drawRect(
                            color = colors.brownPrimary.copy(alpha = 0.78f),
                            topLeft = Offset(plot.left + index * slot + 1f, plot.bottom - barHeight),
                            size = Size(max(1f, slot - 2f), barHeight),
                        )
                    }
                }

                ProfileChartType.Line -> {
                    val plot = chartPlotArea()
                    drawChartAxes(
                        points = points,
                        plot = plot,
                        yAxisMaxMillis = yAxisMaxMillis,
                        textMeasurer = textMeasurer,
                        labelStyle = labelStyle,
                        axisColor = axisColor,
                        gridColor = gridColor,
                        lineMode = true,
                    )
                    val step = if (values.size <= 1) plot.width else plot.width / values.lastIndex
                    val path = Path()
                    values.forEachIndexed { index, value ->
                        val point = Offset(
                            x = plot.left + index * step,
                            y = plot.bottom - value.toFloat() / yAxisMaxMillis.toFloat() * plot.height,
                        )
                        if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
                        drawCircle(color = colors.orangeAccent, radius = 4.5f, center = point)
                    }
                    drawPath(
                        path = path,
                        color = colors.brownPrimary,
                        style = Stroke(width = 4f, cap = StrokeCap.Round),
                    )
                }

                ProfileChartType.Pie -> {
                    val total = values.sum().toFloat().coerceAtLeast(1f)
                    var startAngle = -90f
                    val side = minOf(size.width, size.height)
                    val topLeft = Offset((size.width - side) / 2f, (size.height - side) / 2f)
                    values.forEachIndexed { index, value ->
                        val sweep = value.toFloat() / total * 360f
                        drawArc(
                            color = palette[index % palette.size],
                            startAngle = startAngle,
                            sweepAngle = sweep,
                            useCenter = true,
                            topLeft = topLeft,
                            size = Size(side, side),
                        )
                        startAngle += sweep
                    }
                }
            }
        }
    }
}

@Composable
private fun FavoriteRatioList(
    ratios: List<RatioItem>,
    fallbackTypeRatios: List<RatioItem>,
) {
    val colors = YamiboTheme.colors
    val displayRatios = ratios.ifEmpty { fallbackTypeRatios }
    if (displayRatios.isEmpty()) {
        Text(text = i18n("尚無收藏資料"), color = colors.brownLight, fontSize = 13.sp)
        return
    }
    val total = displayRatios.sumOf { it.count }.coerceAtLeast(1)
    val palette = listOf(colors.brownPrimary, colors.orangeAccent, colors.redAccent, colors.brownLight)
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        displayRatios.take(6).forEachIndexed { index, item ->
            val ratio = ceil(item.count.toDouble() * 100.0 / total.toDouble()).toInt().coerceAtMost(100)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(palette[index % palette.size], CircleShape),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = item.label,
                    color = colors.textDark,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(text = "${item.count} / ${ratio}%", color = colors.brownPrimary, fontSize = 12.sp)
            }
        }
    }
}

private suspend fun loadProfileStatistics(
    range: ProfileStatsRange,
    favoriteRepository: me.thenano.yamibo.yamibo_app.repository.FavoriteStoreRepository,
    readHistoryRepository: ReadHistoryRepository,
    bookMarkRepository: me.thenano.yamibo.yamibo_app.repository.BookMarkRepository,
): ProfileStatisticsState {
    val chartRangeKeys = dateKeysForRange(range, readHistoryRepository)
    val chartDurationByDate = readHistoryRepository.getReadingDurationDays(
        chartRangeKeys.startDateKey,
        chartRangeKeys.endDateKey,
    ).associateBy { it.dateKey }
    val favoriteItems = favoriteRepository.getAllFavoriteItems()
    val bookmarkEntries = bookMarkRepository.getAllEntries()
    val favoriteRatios = favoriteRepository.getCategories()
        .map { category -> category.toRatioItem(favoriteRepository) }
        .filter { it.count > 0 }
        .sortedByDescending { it.count }

    val favoriteTypeRatios = favoriteItems
        .groupingBy { it.targetType }
        .eachCount()
        .map { (type, count) -> RatioItem(type.label, count) }
        .sortedByDescending { it.count }

    val finishedWorkCount = bookmarkEntries
        .groupBy { "${it.targetType.name}:${it.parentId}" }
        .values
        .count { entries -> entries.isNotEmpty() && entries.all { it.read } }

    return ProfileStatisticsState(
        shelfCount = favoriteItems.size,
        allReadingMillis = readHistoryRepository.getReadingDurationTotal(ALL_START_DATE_KEY, chartRangeKeys.endDateKey),
        weekReadingMillis = readHistoryRepository.getReadingDurationTotal(dateKeysForLast(7).first(), chartRangeKeys.endDateKey),
        monthReadingMillis = readHistoryRepository.getReadingDurationTotal(currentMonthStartDateKey(), chartRangeKeys.endDateKey),
        chapterCount = bookmarkEntries.size,
        readChapterCount = bookmarkEntries.count { it.read },
        finishedWorkCount = finishedWorkCount,
        favoriteRatios = favoriteRatios,
        favoriteTypeRatios = favoriteTypeRatios,
        readingDays = chartRangeKeys.dateKeys.map { dateKey ->
            ReadingDayPoint(dateKey = dateKey, durationMillis = chartDurationByDate[dateKey]?.durationMillis ?: 0L)
        },
        loading = false,
    )
}

private suspend fun FavoriteCategory.toRatioItem(
    favoriteRepository: me.thenano.yamibo.yamibo_app.repository.FavoriteStoreRepository,
): RatioItem {
    val content = favoriteRepository.getCategoryContent(id)
    val itemIds = (
        content.directItems.map { it.id } +
            content.collections.flatMap { collection -> collection.items.map { it.id } }
        ).distinct()
    return RatioItem(name, itemIds.size)
}

private suspend fun dateKeysForRange(
    range: ProfileStatsRange,
    readHistoryRepository: ReadHistoryRepository,
): DateKeyRange {
    val endDateKey = currentLocalDateKeyAt(currentTimeMillis())
    if (range == ProfileStatsRange.All) {
        val recordedDays = readHistoryRepository.getReadingDurationDays(ALL_START_DATE_KEY, endDateKey)
            .map { it.dateKey }
        return DateKeyRange(
            startDateKey = recordedDays.firstOrNull() ?: ALL_START_DATE_KEY,
            endDateKey = endDateKey,
            dateKeys = recordedDays.ifEmpty { dateKeysForLast(7) },
        )
    }
    val dateKeys = dateKeysForLast(range.days ?: 7)
    return DateKeyRange(
        startDateKey = dateKeys.first(),
        endDateKey = endDateKey,
        dateKeys = dateKeys,
    )
}

private fun dateKeysForLast(days: Int): List<String> {
    val now = currentTimeMillis()
    return (days - 1 downTo 0).map { offset ->
        currentLocalDateKeyAt(now - offset * DAY_MILLIS)
    }
}

private fun currentMonthStartDateKey(): String {
    val current = currentLocalDateKeyAt(currentTimeMillis())
    return current.take(8) + "01"
}

private fun formatDuration(durationMillis: Long): String {
    val seconds = durationMillis / 1_000L
    val minutes = durationMillis / 60_000L
    val hours = minutes / 60L
    val remainingMinutes = minutes % 60L
    return when {
        hours > 0L -> "${hours}h ${remainingMinutes}m"
        minutes > 0L -> "${minutes}m"
        seconds > 0L -> "${seconds}s"
        else -> "0m"
    }
}

private fun DrawScope.chartPlotArea(): ChartPlotArea {
    val left = 42.dp.toPx()
    val top = 8.dp.toPx()
    val right = size.width - 6.dp.toPx()
    val bottom = size.height - 24.dp.toPx()
    return ChartPlotArea(
        left = left,
        top = top,
        right = right.coerceAtLeast(left + 1f),
        bottom = bottom.coerceAtLeast(top + 1f),
    )
}

private fun DrawScope.drawChartAxes(
    points: List<ReadingDayPoint>,
    plot: ChartPlotArea,
    yAxisMaxMillis: Long,
    textMeasurer: TextMeasurer,
    labelStyle: TextStyle,
    axisColor: androidx.compose.ui.graphics.Color,
    gridColor: androidx.compose.ui.graphics.Color,
    lineMode: Boolean,
) {
    drawLine(axisColor, Offset(plot.left, plot.top), Offset(plot.left, plot.bottom), strokeWidth = 1.4f)
    drawLine(axisColor, Offset(plot.left, plot.bottom), Offset(plot.right, plot.bottom), strokeWidth = 1.4f)

    val yTicks = listOf(0L, yAxisMaxMillis / 2L, yAxisMaxMillis)
    yTicks.distinct().forEach { tick ->
        val y = plot.bottom - tick.toFloat() / yAxisMaxMillis.toFloat() * plot.height
        drawLine(gridColor, Offset(plot.left, y), Offset(plot.right, y), strokeWidth = 1f)
        val label = formatAxisDuration(tick)
        val measured = textMeasurer.measure(label, labelStyle)
        drawText(
            textMeasurer = textMeasurer,
            text = label,
            topLeft = Offset(
                x = (plot.left - measured.size.width - 6.dp.toPx()).coerceAtLeast(0f),
                y = (y - measured.size.height / 2f).coerceIn(0f, size.height - measured.size.height),
            ),
            style = labelStyle,
        )
    }

    val xTicks = buildTimeAxisTicks(points)
    xTicks.forEach { tick ->
        val x = if (lineMode && points.size > 1) {
            plot.left + tick.index.toFloat() / points.lastIndex.toFloat() * plot.width
        } else {
            val slot = plot.width / points.size.toFloat()
            plot.left + tick.index * slot + slot / 2f
        }
        drawLine(gridColor, Offset(x, plot.top), Offset(x, plot.bottom), strokeWidth = 1f)
        val measured = textMeasurer.measure(tick.label, labelStyle)
        drawText(
            textMeasurer = textMeasurer,
            text = tick.label,
            topLeft = Offset(
                x = (x - measured.size.width / 2f).coerceIn(plot.left - measured.size.width / 2f, plot.right - measured.size.width / 2f),
                y = plot.bottom + 5.dp.toPx(),
            ),
            style = labelStyle,
        )
    }
}

private fun niceDurationAxisMax(durationMillis: Long): Long {
    if (durationMillis <= 0L) return 1_000L
    val steps = listOf(
        5_000L,
        10_000L,
        15_000L,
        30_000L,
        60_000L,
        2 * 60_000L,
        5 * 60_000L,
        10 * 60_000L,
        15 * 60_000L,
        30 * 60_000L,
        60 * 60_000L,
        2 * 60 * 60_000L,
        4 * 60 * 60_000L,
        6 * 60 * 60_000L,
        12 * 60 * 60_000L,
        24 * 60 * 60_000L,
    )
    return steps.firstOrNull { it >= durationMillis } ?: run {
        val dayMillis = 24 * 60 * 60_000L
        ceil(durationMillis.toDouble() / dayMillis.toDouble()).toLong() * dayMillis
    }
}

private fun formatAxisDuration(durationMillis: Long): String {
    if (durationMillis <= 0L) return "0"
    val seconds = durationMillis / 1_000L
    val minutes = seconds / 60L
    val hours = minutes / 60L
    val remainingMinutes = minutes % 60L
    val remainingSeconds = seconds % 60L
    return when {
        hours > 0L && remainingMinutes > 0L -> "${hours}h${remainingMinutes}m"
        hours > 0L -> "${hours}h"
        minutes > 0L && remainingSeconds > 0L -> "${minutes}m${remainingSeconds}s"
        minutes > 0L -> "${minutes}m"
        else -> "${seconds}s"
    }
}

private fun buildTimeAxisTicks(points: List<ReadingDayPoint>): List<ChartAxisTick> {
    if (points.isEmpty()) return emptyList()
    val includeYear = points.first().dateKey.take(4) != points.last().dateKey.take(4) || points.size > 365
    val indices = when {
        points.size <= 7 -> points.indices.toList()
        points.size <= 31 -> evenlySpacedIndices(points.lastIndex, 5)
        points.size <= 100 -> evenlySpacedIndices(points.lastIndex, 4)
        else -> evenlySpacedIndices(points.lastIndex, 5)
    }
    return indices.map { index ->
        ChartAxisTick(index = index, label = formatAxisDate(points[index].dateKey, includeYear))
    }
}

private fun evenlySpacedIndices(lastIndex: Int, count: Int): List<Int> {
    if (lastIndex <= 0) return listOf(0)
    return (0 until count)
        .map { tick -> (tick.toFloat() / (count - 1).toFloat() * lastIndex).toInt() }
        .distinct()
}

private fun formatAxisDate(dateKey: String, includeYear: Boolean): String {
    val parts = dateKey.split("-")
    if (parts.size != 3) return dateKey
    val year = parts[0].takeLast(2)
    val month = parts[1].trimStart('0').ifEmpty { "0" }
    val day = parts[2].trimStart('0').ifEmpty { "0" }
    return if (includeYear) "$year/$month/$day" else "$month/$day"
}

private val FavoriteTargetType.label: String
    get() = when (this) {
        FavoriteTargetType.ThreadNormal -> i18n("帖子")
        FavoriteTargetType.ThreadNovel -> i18n("小說")
        FavoriteTargetType.TagManga -> i18n("Tag 漫畫")
        FavoriteTargetType.RssSearch -> i18n("RSS 搜尋目錄")
    }

private enum class ProfileStatsRange(val label: String, val days: Int?) {
    Week(i18n("本周 7 天"), 7),
    Month(i18n("30 天"), 30),
    Season(i18n("90 天"), 90),
    Year(i18n("一年"), 365),
    All(i18n("全部"), null),
}

private enum class ProfileChartType(val label: String) {
    Bar(i18n("長條圖")),
    Histogram(i18n("直方圖")),
    Pie(i18n("圓餅圖")),
    Line(i18n("折線圖")),
}

private data class ProfileStatisticsState(
    val shelfCount: Int,
    val allReadingMillis: Long,
    val weekReadingMillis: Long,
    val monthReadingMillis: Long,
    val chapterCount: Int,
    val readChapterCount: Int,
    val finishedWorkCount: Int,
    val favoriteRatios: List<RatioItem>,
    val favoriteTypeRatios: List<RatioItem>,
    val readingDays: List<ReadingDayPoint>,
    val loading: Boolean,
) {
    companion object {
        fun loading(range: ProfileStatsRange): ProfileStatisticsState {
            return ProfileStatisticsState(
                shelfCount = 0,
                allReadingMillis = 0L,
                weekReadingMillis = 0L,
                monthReadingMillis = 0L,
                chapterCount = 0,
                readChapterCount = 0,
                finishedWorkCount = 0,
                favoriteRatios = emptyList(),
                favoriteTypeRatios = emptyList(),
                readingDays = dateKeysForLast(range.days ?: 7).map { ReadingDayPoint(it, 0L) },
                loading = true,
            )
        }
    }
}

private data class DateKeyRange(
    val startDateKey: String,
    val endDateKey: String,
    val dateKeys: List<String>,
)

private data class ReadingDayPoint(
    val dateKey: String,
    val durationMillis: Long,
)

private data class ChartAxisTick(
    val index: Int,
    val label: String,
)

private data class ChartPlotArea(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

private data class RatioItem(
    val label: String,
    val count: Int,
)

private const val DAY_MILLIS = 86_400_000L
private const val ALL_START_DATE_KEY = "0000-01-01"
