package me.thenano.yamibo.yamibo_app.repository.forum

/** Search by the printed work name. The uploader and translation group can change between installments. */
internal data class SmartComicTitle(val query: String, val credit: String?, val chapter: Int?, val part: Int) {
    val nameKey = query.filterNot(Char::isWhitespace).lowercase()

    fun matches(other: SmartComicTitle): Boolean =
        nameKey.length >= 2 && nameKey == other.nameKey &&
            (credit == null || other.credit == null || credit == other.credit)
}

private val prefix = Regex("""^(?:[\[［][^\]］]+[\]］]|【[^】]+】|\([^()]+\)|（[^（）]+）)""")
private const val number = "[0-9零〇一二两兩三四五六七八九十百千]+"
private val numbered = Regex("""(?:第\s*|(?:stage|chapter|ch\.?|#)\s*)($number)(?:[.\-]([0-9]+))?(?=\s*(?:[話话章回卷話~～\-、：:\s]|$))|($number)(?:[.\-]([0-9]+))?\s*[话話章回](?=\D|$)|\s+([0-9]+)(?:[.\-]([0-9]+))?(?=\s|$|[+（(])""", RegexOption.IGNORE_CASE)
private val extra = Regex("""(?:发售图|發售圖|贺图|賀圖|附赠|附贈|番外|特別篇|特别篇|幕间|幕間|短篇|短編|后记|後記)""")
private val missingNumber = Regex("""第[话話]\s*$""")
private val alias = Regex("""[（(][^（）()]+[）)]\s*$""")
private val subpart = Regex("""^[话話章回]?\s*其\s*([0-9]+)""")
private val namedSubpart = Regex("""(?:第\s*)?$number\s*[话話章回]\s*(?:[（(]\s*([上中下])篇?\s*[）)]|([上中下])篇)""")
private val printedRoles = Regex("""(?:漫画|漫畫|原作|作画|作畫)\s*[：:]""")
private val digits = "零一二三四五六七八九"

private fun chapterNumber(s: String): Int? {
    s.toIntOrNull()?.let { return it }
    var total = 0
    var digit = 0
    for (c in s.replace('〇', '零').replace('两', '二').replace('兩', '二')) {
        val unit = when (c) { '十' -> 10; '百' -> 100; '千' -> 1000; else -> 0 }
        if (unit > 0) {
            total += digit.coerceAtLeast(1) * unit
            digit = 0
        } else {
            val value = digits.indexOf(c)
            if (value < 0) return null
            digit = digit * 10 + value
        }
    }
    return total + digit
}

internal fun smartComicTitle(title: String): SmartComicTitle {
    var body = title.trim().map { if (it in '０'..'９') '0' + (it - '０') else it }.joinToString("")
    var credit: String? = null
    var prefixCount = 0
    while (true) {
        val match = prefix.find(body) ?: break
        val rest = body.substring(match.range.last + 1).trim()
        if (rest.isEmpty() || numbered.find(rest)?.range?.first == 0) break
        val token = match.value.drop(1).dropLast(1).trim()
        if ((body.startsWith("[") || prefixCount > 0 || printedRoles.containsMatchIn(token)) &&
            !listOf("漢化", "汉化", "連載", "连载", "转载", "轉載", "组", "組").any { it in token }) {
            credit = token.replace(printedRoles, "").split('×', '／').map { it.trim().lowercase() }.sorted().joinToString("×")
        }
        body = rest
        prefixCount++
    }
    val numberedMatch = numbered.find(body)
    val marker = listOfNotNull(numberedMatch?.range?.first, extra.find(body)?.range?.first,
        missingNumber.find(body)?.range?.first).filter { it > 0 }.minOrNull() ?: body.length
    val stem = body.take(marker).trim().trimEnd('-', '：', ':').trimEnd()
    val query = stem.replace(alias, "").trim().ifEmpty { stem }.trim('《','》','「','」').trim()
    val parts = numberedMatch?.takeIf { it.range.first == marker }?.groupValues
    val index = listOf(1, 3, 5).firstOrNull { !parts?.get(it).isNullOrEmpty() }
    val part = index?.let { parts!![it + 1].toIntOrNull() }
        ?: numberedMatch?.let { subpart.find(body.substring(it.range.last + 1)) }
            ?.groupValues?.get(1)?.toIntOrNull()
        ?: index?.let { namedSubpart.matchEntire(body.substring(marker).trim()) }?.let { label ->
            "上中下".indexOf(label.groupValues[1].ifEmpty { label.groupValues[2] }) + 1
        }
    return SmartComicTitle(query, credit, index?.let { chapterNumber(parts!![it]) }, part ?: 0)
}
