package me.thenano.yamibo.yamibo_app.util.time

import me.thenano.yamibo.yamibo_app.i18n.i18n

expect fun currentTimeMillis(): Long


fun formatRelativeTime(timestamp: Long): String {
    val elapsed = (currentTimeMillis() - timestamp).coerceAtLeast(0L)
    val minutes = elapsed / 1000L / 60L
    val hours = minutes / 60L
    val days = hours / 24L
    return when {
        days > 0L -> i18n("{}天前", days)
        hours > 0L -> i18n("{}小時前", hours)
        minutes > 0L -> i18n("{}分鐘前", minutes)
        else -> i18n("剛剛")
    }
}

fun isLeapYear(year: Int): Boolean {
    return (year % 4 == 0 && year % 100 != 0) || (year % 400 == 0)
}

fun formatDate(timestamp: Long): String {
    val (year, month, day) = datePartsAtUtcPlus8(timestamp)
    return "$year/$month/$day"
}

fun formatTime(timestamp: Long): String {
    val adjustedMs = timestamp + 8 * 60 * 60 * 1000L
    val totalMinutes = (adjustedMs / (60 * 1000L)) % (24 * 60)
    val hours = (totalMinutes / 60).toInt()
    val minutes = (totalMinutes % 60).toInt()
    return "${hours.toString().padStart(2, '0')}:${minutes.toString().padStart(2, '0')}"
}

fun formatDateTime(timestamp: Long): String {
    val (year, month, day) = datePartsAtUtcPlus8(timestamp)
    val adjustedMs = timestamp + 8 * 60 * 60 * 1000L
    val totalMinutes = (adjustedMs / (60 * 1000L)) % (24 * 60)
    val hours = (totalMinutes / 60).toInt()
    val minutes = (totalMinutes % 60).toInt()
    return "$year/${month.toString().padStart(2, '0')}/${day.toString().padStart(2, '0')} ${hours.toString().padStart(2, '0')}:${minutes.toString().padStart(2, '0')}"
}

private fun datePartsAtUtcPlus8(timestamp: Long): Triple<Int, Int, Int> {
    val utcPlus8OffsetMillis = 8L * 60L * 60L * 1000L
    val totalDays = (timestamp + utcPlus8OffsetMillis).floorDiv(24L * 60L * 60L * 1000L)
    var year = 1970
    var remainingDays = totalDays
    while (true) {
        val daysInYear = if (isLeapYear(year)) 366L else 365L
        if (remainingDays < daysInYear) break
        remainingDays -= daysInYear
        year++
    }
    val monthDays = intArrayOf(31, if (isLeapYear(year)) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
    var month = 1
    for (days in monthDays) {
        if (remainingDays < days) break
        remainingDays -= days
        month++
    }
    return Triple(year, month, remainingDays.toInt() + 1)
}

fun currentLocalDateKey(): String {
    return currentLocalDateKeyAt(currentTimeMillis())
}

fun currentLocalDateKeyAt(epochMillis: Long): String {
    val utcPlus8OffsetMillis = 8L * 60L * 60L * 1000L
    val epochDay = (epochMillis + utcPlus8OffsetMillis).floorDiv(86_400_000L)
    val (year, month, day) = civilFromDays(epochDay)
    return "${year.toString().padStart(4, '0')}-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}"
}

private fun civilFromDays(epochDay: Long): Triple<Int, Int, Int> {
    val z = epochDay + 719468L
    val era = if (z >= 0) z / 146097L else (z - 146096L) / 146097L
    val doe = z - era * 146097L
    val yoe = (doe - doe / 1460L + doe / 36524L - doe / 146096L) / 365L
    val y = yoe + era * 400L
    val doy = doe - (365L * yoe + yoe / 4L - yoe / 100L)
    val mp = (5L * doy + 2L) / 153L
    val d = doy - (153L * mp + 2L) / 5L + 1L
    val m = mp + if (mp < 10L) 3L else -9L
    val year = (y + if (m <= 2L) 1L else 0L).toInt()
    return Triple(year, m.toInt(), d.toInt())
}
