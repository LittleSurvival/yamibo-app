package me.thenano.yamibo.yamibo_app.thread.reader.components.post.impl

import me.thenano.yamibo.yamibo_app.Logger
import me.thenano.yamibo.yamibo_app.i18n.i18n

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Document
import com.fleeksoft.ksoup.nodes.Element
import com.fleeksoft.ksoup.nodes.Node
import com.fleeksoft.ksoup.nodes.TextNode

object HtmlParser {

    private data class ParsedAttachment(
        val url: String,
        val iconUrl: String?,
        val fileName: String,
        val uploadInfo: String?,
        val statInfo: String?,
    )

    private data class PendingRuby(
        val start: Int,
        val ruby: HtmlBlock.RubyText,
    )

    /** Generate a stable content-hash based ID */
    private fun hashId(type: String, content: String, index: Int): String {
        val raw = "${type}_${index}_${content.take(64)}"
        return raw.hashCode().toUInt().toString(16)
    }

    /** Parses raw HTML string into a list of HtmlBlock for Compose to render */
    fun parseHtml(html: String): List<HtmlBlock> {
        // Keep HTML entities intact so Ksoup decodes spaces such as &nbsp; with their real width.
        val cleanHtml = html.replace("\r", "")
        val document: Document = Ksoup.parseBodyFragment(cleanHtml)
        
        // Filter out Discuz jammer (interference) elements to avoid garbage text in reader
        document.select("font.jammer").forEach { it.remove() }
        document.select(".jammer").forEach { it.remove() }
        document.select("[style]").forEach { element ->
            val style = element.attr("style").lowercase().replace(" ", "")
            if (style.contains("display:none")) {
                element.remove()
            }
        }
        
        val blocks = mutableListOf<HtmlBlock>()
        val globalBuilder = AnnotatedString.Builder()
        var lastCommitIndex = 0
        var currentLinkHref: String? = null
        var blockCounter = 0
        var currentAlign = TextAlign.Start
        var explicitBreaksSinceCommit = 0
        val pendingRubies = mutableListOf<PendingRuby>()

        fun trailingNewlineCount(): Int {
            val text = globalBuilder.toAnnotatedString().text
            var count = 0
            var index = text.length - 1
            while (index >= 0 && text[index] == '\n') {
                count++
                index--
            }
            return count
        }

        fun appendLineBreak(maxConsecutive: Int = 2, explicit: Boolean = false) {
            if (explicit) {
                explicitBreaksSinceCommit++
            }
            if (globalBuilder.length == 0 && !explicit) return
            if (trailingNewlineCount() < maxConsecutive) {
                globalBuilder.append("\n")
            }
        }

        fun appendCollapsibleSpace() {
            if (globalBuilder.length == 0) return
            val last = globalBuilder.toAnnotatedString().lastOrNull()
            if (last != null && last != ' ' && last != '\n' && last != '\u3000') {
                globalBuilder.append(" ")
            }
        }

        fun appendTextNodeText(text: String) {
            text.forEach { char ->
                when (char) {
                    '\u00A0' -> globalBuilder.append("\u3000")
                    ' ', '\n', '\t', '\u000C' -> appendCollapsibleSpace()
                    else -> globalBuilder.append(char.toString())
                }
            }
        }

        fun isBlockEdgeTrimChar(char: Char): Boolean {
            return char == ' ' || char == '\n' || char == '\t' || char == '\u000C'
        }

        fun commitText() {
            if (globalBuilder.length > lastCommitIndex) {
                val fullText = globalBuilder.toAnnotatedString()
                var start = lastCommitIndex
                var end = globalBuilder.length
                while (start < end && isBlockEdgeTrimChar(fullText.text[start])) start++
                while (end > start && isBlockEdgeTrimChar(fullText.text[end - 1])) end--
                val textStr = fullText.subSequence(start, end)
                
                if (textStr.isNotEmpty()) {
                    val aid = hashId("t", textStr.text, blockCounter++)
                    val rubies = pendingRubies
                        .filter { it.start >= start && it.ruby.end <= end }
                        .map {
                            it.ruby.copy(
                                start = it.ruby.start - start,
                                end = it.ruby.end - start,
                            )
                        }
                    blocks.add(HtmlBlock.Text(textStr, currentAlign, rubies = rubies, anchorId = aid))
                } else if (explicitBreaksSinceCommit > 0) {
                    val aid = hashId("t", "br", blockCounter++)
                    blocks.add(HtmlBlock.Text(AnnotatedString("\n"), currentAlign, anchorId = aid))
                }
                lastCommitIndex = globalBuilder.length
                explicitBreaksSinceCommit = 0
            }
        }

        fun parseNode(node: Node, parentAlign: TextAlign = TextAlign.Start) {
            when (node) {
                is TextNode -> {
                    appendTextNodeText(node.getWholeText())
                }
                is Element -> {
                    val tag = node.tagName().lowercase()
                    when (tag) {
                        "br" -> {
                            appendLineBreak(explicit = true)
                        }
                        "hr" -> {
                            commitText()
                            val aid = hashId("hr", blockCounter.toString(), blockCounter++)
                            blocks.add(HtmlBlock.Hr(anchorId = aid))
                        }
                        "img" -> {
                            commitText()
                            val srcRaw = node.attr("file").takeIf { it.isNotBlank() }
                                ?: node.attr("zoomfile").takeIf { it.isNotBlank() }
                                ?: node.attr("src")
                            val src = if (srcRaw.contains("none.gif")) "" else srcRaw
                            val alt = node.attr("alt").takeIf { it.isNotBlank() }
                            if (src.isNotBlank()) {
                                val aid = hashId("i", src, blockCounter++)
                                val normalizedSrc = src.lowercase()
                                val isEmoticon = normalizedSrc.contains("/static/image/smiley/") ||
                                    normalizedSrc.contains("static/image/smiley/") ||
                                    normalizedSrc.contains("/smiley/")
                                blocks.add(HtmlBlock.Image(src, alt, currentLinkHref, isEmoticon = isEmoticon, anchorId = aid))
                            }
                        }
                        "div" -> {
                            val clazz = node.attr("class")
                            val alignAttr = node.attr("align").lowercase()
                            val newAlign = when (alignAttr) {
                                "center" -> TextAlign.Center
                                "right" -> TextAlign.Right
                                "left" -> TextAlign.Left
                                else -> parentAlign
                            }
                            
                            when {
                                clazz.contains("showcollapse_box") -> {
                                    commitText()
                                    val titleNode = node.selectFirst(".showcollapse_title")
                                    val titleText = titleNode?.text()?.takeIf { it.isNotBlank() } ?: i18n("點擊展開 / 收起")
                                    titleNode?.remove()
                                    val innerBlocks = parseHtml(node.html())
                                    val aid = hashId("col", titleText, blockCounter++)
                                    blocks.add(HtmlBlock.Collapse(title = titleText, contentBlocks = innerBlocks, anchorId = aid))
                                }
                                clazz.contains("locked-content") -> {
                                    commitText()
                                    val costText = node.select(".locked-tip").text()
                                    val cost = costText.toIntOrNull() ?: 0
                                    val innerBlocks = parseHtml(node.html())
                                    val aid = hashId("lck", cost.toString(), blockCounter++)
                                    blocks.add(HtmlBlock.Locked(cost = cost, contentBlocks = innerBlocks, anchorId = aid))
                                }
                                clazz.contains("quote") || clazz.contains("blockquote") -> {
                                    commitText()
                                    val innerBlocks = parseHtml(node.html())
                                    val aid = hashId("q", node.text().take(32), blockCounter++)
                                    blocks.add(HtmlBlock.Quote(innerBlocks, anchorId = aid))
                                }
                                clazz.contains("blockcode") -> {
                                    commitText()
                                    val codeText = node.text()
                                    val aid = hashId("c", codeText.take(32), blockCounter++)
                                    blocks.add(HtmlBlock.Code(codeText, anchorId = aid))
                                }
                                else -> {
                                    // Handle nested alignment
                                    val prevAlign = currentAlign
                                    if (newAlign != prevAlign) {
                                        commitText()
                                        currentAlign = newAlign
                                    }
                                    
                                    appendLineBreak(maxConsecutive = 1)
                                    node.childNodes().forEach { parseNode(it, newAlign) }
                                    appendLineBreak(maxConsecutive = 1)
                                    
                                    if (newAlign != prevAlign) {
                                        commitText()
                                        currentAlign = prevAlign
                                    }
                                }
                            }
                        }
                        "table" -> {
                            // Detect if this is a multi-cell table (data table) or single-cell wrapper
                            val trs = node.select("tr")
                            val isDataTable = trs.any { tr ->
                                tr.select("td, th").size > 1
                            }

                            if (isDataTable) {
                                commitText()
                                val rows = trs.map { tr ->
                                    val cells = tr.select("td, th").map { cell ->
                                        val cellBlocks = parseHtml(cell.html())
                                        val isHeader = cell.tagName().lowercase() == "th" ||
                                            cell.select("strong, b").isNotEmpty()
                                        HtmlBlock.TableCell(blocks = cellBlocks, isHeader = isHeader)
                                    }
                                    HtmlBlock.TableRow(cells = cells)
                                }
                                val aid = hashId("tbl", rows.size.toString(), blockCounter++)
                                blocks.add(HtmlBlock.Table(rows = rows, anchorId = aid))
                            } else {
                                // Single-cell wrapper table — treat as inline content
                                appendLineBreak(maxConsecutive = 1)
                                node.childNodes().forEach { parseNode(it, parentAlign) }
                                appendLineBreak(maxConsecutive = 1)
                            }
                        }
                        "ul" -> {
                            val clazz = node.attr("class")
                            val attachment = parseAttachment(node)
                            if (clazz.contains("post_attlist") && attachment != null) {
                                commitText()
                                val aid = hashId("att", attachment.fileName, blockCounter++)
                                blocks.add(
                                    HtmlBlock.Attachment(
                                        url = attachment.url,
                                        iconUrl = attachment.iconUrl,
                                        fileName = attachment.fileName,
                                        uploadInfo = attachment.uploadInfo,
                                        statInfo = attachment.statInfo,
                                        anchorId = aid,
                                    )
                                )
                            } else {
                                appendLineBreak(maxConsecutive = 1)
                                node.childNodes().forEach { parseNode(it, parentAlign) }
                                appendLineBreak(maxConsecutive = 1)
                            }
                        }
                        "p", "ol", "tbody", "tr", "td", "th" -> {
                            appendLineBreak(maxConsecutive = 1)
                            node.childNodes().forEach { parseNode(it, parentAlign) }
                            appendLineBreak(maxConsecutive = 1)
                        }
                        "b", "strong" -> globalBuilder.withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { node.childNodes().forEach { parseNode(it, parentAlign) } }
                        "i", "em" -> globalBuilder.withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { node.childNodes().forEach { parseNode(it, parentAlign) } }
                        "u" -> globalBuilder.withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) { node.childNodes().forEach { parseNode(it, parentAlign) } }
                        "s", "strike" -> globalBuilder.withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { node.childNodes().forEach { parseNode(it, parentAlign) } }
                        "ruby" -> {
                            val rtNodes = node.children().filter { it.tagName().equals("rt", ignoreCase = true) }
                            val rubyText = rtNodes.joinToString(separator = "") { it.text() }.trim()
                            val start = globalBuilder.length
                            node.childNodes()
                                .filterNot {
                                    it is Element &&
                                        (it.tagName().equals("rt", ignoreCase = true) ||
                                            it.tagName().equals("rp", ignoreCase = true))
                                }
                                .forEach { parseNode(it, parentAlign) }
                            val end = globalBuilder.length
                            val baseText = globalBuilder.toAnnotatedString().substring(start, end).trim()
                            if (start < end && baseText.isNotBlank() && rubyText.isNotBlank()) {
                                val id = hashId("ruby", "$baseText|$rubyText", pendingRubies.size)
                                pendingRubies += PendingRuby(
                                    start = start,
                                    ruby = HtmlBlock.RubyText(
                                        id = id,
                                        start = start,
                                        end = end,
                                        baseText = baseText,
                                        rubyText = rubyText,
                                    ),
                                )
                            }
                        }
                        "rt" -> {
                            /**
                             * Handled by the parent <ruby>; ignore standalone rt to avoid duplicate text.
                             */
                        }
                        "rp" -> {
                            /**
                             * Browser fallback parentheses are not shown in native ruby rendering.
                             */
                        }
                        "a" -> {
                            val cloudflareEmail = decodeCloudflareEmail(node.attr("data-cfemail"))
                            val href = cloudflareEmail?.let { "mailto:$it" } ?: node.attr("href")
                            val prevLink = currentLinkHref
                            if (href.isNotBlank()) currentLinkHref = href
                            
                            val start = globalBuilder.length
                            if (cloudflareEmail != null) {
                                appendTextNodeText(cloudflareEmail)
                            } else {
                                node.childNodes().forEach { parseNode(it, parentAlign) }
                            }
                            val end = globalBuilder.length
                            
                            if (href.isNotBlank() && start < end) {
                                val textContent = globalBuilder.toAnnotatedString().substring(start, end)
                                if (textContent.trim().isNotEmpty()) {
                                    globalBuilder.addStringAnnotation("URL", href, start, end)
                                    globalBuilder.addStyle(SpanStyle(textDecoration = TextDecoration.Underline), start, end)
                                }
                            }
                            currentLinkHref = prevLink
                        }
                        "font" -> {
                            val colorAttr = node.attr("color")
                            val sizeAttr = node.attr("size")
                            val styleAttr = node.attr("style")
                            
                            var spanStyle = SpanStyle()
                            parseColor(colorAttr)?.let { spanStyle = spanStyle.copy(color = it) }
                            if (sizeAttr.isNotEmpty()) {
                                spanStyle = spanStyle.copy(fontSize = fontSizeToSp(sizeAttr))
                            }
                            if (styleAttr.isNotEmpty()) {
                                spanStyle = parseStylesFromStyleAttr(styleAttr, spanStyle)
                            }
                            
                            globalBuilder.withStyle(spanStyle) { node.childNodes().forEach { parseNode(it, parentAlign) } }
                        }
                        "span" -> {
                            val styleAttr = node.attr("style")
                            var spanStyle = SpanStyle()
                            if (styleAttr.isNotEmpty()) {
                                spanStyle = parseStylesFromStyleAttr(styleAttr, spanStyle)
                            }
                            globalBuilder.withStyle(spanStyle) { node.childNodes().forEach { parseNode(it, parentAlign) } }
                        }
                        else -> { node.childNodes().forEach { parseNode(it, parentAlign) } }
                    }
                }
                else -> { /** Other node types, ignore */ }
            }
        }

        document.body().childNodes().forEach { parseNode(it) }
        commitText()

        return blocks
    }

    private fun decodeCloudflareEmail(encoded: String): String? {
        if (encoded.length < 4 || encoded.length % 2 != 0) return null

        return runCatching {
            val bytes = encoded.chunked(2).map { it.toInt(16) }
            val key = bytes.first()
            bytes.drop(1)
                .map { (it xor key).toByte() }
                .toByteArray()
                .decodeToString()
                .takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun parseAttachment(node: Element): ParsedAttachment? {
        val link = node.selectFirst("a[href]") ?: return null
        val href = link.attr("href").trim()
        if (href.isEmpty()) return null

        val iconUrl = link.selectFirst("img")?.attr("src")?.trim()?.takeIf { it.isNotEmpty() }
        val fileName = link.selectFirst(".link")?.text()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: link.selectFirst(".tit")?.ownText()?.trim()?.takeIf { it.isNotEmpty() }
            ?: link.text().lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
            ?: return null

        val metadata = link.select("p").map { it.text().trim() }.filter { it.isNotEmpty() }
        return ParsedAttachment(
            url = href,
            iconUrl = iconUrl,
            fileName = fileName,
            uploadInfo = metadata.getOrNull(0),
            statInfo = metadata.getOrNull(1),
        )
    }

    private fun parseColor(color: String?): Color? {
        if (color.isNullOrBlank()) return null
        val normalized = color.trim().trim('"', '\'')
        return try {
            if (normalized.startsWith("#")) {
                val hex = normalized.removePrefix("#")
                if (hex.length == 3) {
                    val r = hex[0].toString().repeat(2).toInt(16)
                    val g = hex[1].toString().repeat(2).toInt(16)
                    val b = hex[2].toString().repeat(2).toInt(16)
                    Color(r shl 16 or (g shl 8) or b or (0xFF shl 24))
                } else {
                    Color(hex.toLong(16) or 0xFF000000)
                }
            } else if (normalized.startsWith("rgb", ignoreCase = true)) {
                parseRgbColor(normalized)
            } else {
                when (normalized.lowercase()) {
                    "red" -> Color.Red
                    "blue" -> Color.Blue
                    "green" -> Color.Green
                    "yellow" -> Color.Yellow
                    "black" -> Color.Black
                    "white" -> Color.White
                    "grey", "gray" -> Color.Gray
                    "darkgreen" -> Color(0xFF006400)
                    "darkblue" -> Color(0xFF00008B)
                    "darkred" -> Color(0xFF8B0000)
                    "darkorange" -> Color(0xFFFF8C00)
                    "darkgray", "darkgrey" -> Color(0xFFA9A9A9)
                    "lightgray", "lightgrey" -> Color(0xFFD3D3D3)
                    "lightblue" -> Color(0xFFADD8E6)
                    "lightgreen" -> Color(0xFF90EE90)
                    "pink" -> Color(0xFFFFC0CB)
                    "orange" -> Color(0xFFFFA500)
                    "purple" -> Color(0xFF800080)
                    "skyblue" -> Color(0xFF87CEEB)
                    "palegreen" -> Color(0xFF98FB98)
                    "cyan" -> Color.Cyan
                    "magenta" -> Color.Magenta
                    else -> null
                }
            }
        } catch (error: Exception) {
            Logger.d("HtmlParser", "Failed to parse color value=$normalized", error)
            null
        }
    }

    private fun parseRgbColor(color: String): Color? {
        val values = color.substringAfter("(", missingDelimiterValue = "")
            .substringBeforeLast(")", missingDelimiterValue = "")
            .split(",")
            .map { it.trim() }
        if (values.size < 3) return null
        val red = parseRgbChannel(values[0]) ?: return null
        val green = parseRgbChannel(values[1]) ?: return null
        val blue = parseRgbChannel(values[2]) ?: return null
        val alpha = values.getOrNull(3)?.let { parseAlphaChannel(it) } ?: 1f
        return Color(red, green, blue, alpha)
    }

    private fun parseRgbChannel(value: String): Float? {
        return if (value.endsWith("%")) {
            value.removeSuffix("%").toFloatOrNull()?.let { (it / 100f).coerceIn(0f, 1f) }
        } else {
            value.toFloatOrNull()?.let { (it / 255f).coerceIn(0f, 1f) }
        }
    }

    private fun parseAlphaChannel(value: String): Float? {
        return if (value.endsWith("%")) {
            value.removeSuffix("%").toFloatOrNull()?.let { (it / 100f).coerceIn(0f, 1f) }
        } else {
            value.toFloatOrNull()?.coerceIn(0f, 1f)
        }
    }

    /** Standard HTML base font size in sp (font size="3") */
    private const val HTML_BASE_FONT_SIZE_SP = 16f

    private fun fontSizeToSp(size: String?): TextUnit {
        val absoluteSp = when (size) {
            "1" -> 12f
            "2" -> 14f
            "3" -> 16f // Standard base
            "4" -> 18f
            "5" -> 24f
            "6" -> 32f
            "7" -> 48f // Huge
            else -> 16f
        }
        return (absoluteSp / HTML_BASE_FONT_SIZE_SP).em
    }

    private fun parseStylesFromStyleAttr(styleAttr: String, spanStyle: SpanStyle): SpanStyle {
        var current = spanStyle
        val declarations = parseStyleDeclarations(styleAttr)
        declarations["color"]?.let { colorStr ->
            parseColor(colorStr)?.let { current = current.copy(color = it) }
        }
        declarations["background-color"]?.let { bgStr ->
            parseColor(bgStr)?.let { current = current.copy(background = it) }
        }

        // Support font-size: Npx / Npt / Nem
        val fontSizeMatch = declarations["font-size"]?.let { Regex("^([\\d.]+)\\s*(px|pt|em)$").find(it) }
        fontSizeMatch?.let { match ->
            val value = match.groupValues[1].toFloatOrNull()
            val unit = match.groupValues[2]
            if (value != null) {
                val emValue = when (unit) {
                    "px" -> value / HTML_BASE_FONT_SIZE_SP
                    "pt" -> (value * 4f / 3f) / HTML_BASE_FONT_SIZE_SP // 1pt ≈ 1.333px
                    "em" -> value
                    else -> null
                }
                emValue?.let { current = current.copy(fontSize = it.em) }
            }
        }
        return current
    }

    private fun parseStyleDeclarations(styleAttr: String): Map<String, String> {
        return styleAttr.split(";")
            .mapNotNull { declaration ->
                val separatorIndex = declaration.indexOf(':')
                if (separatorIndex <= 0) return@mapNotNull null
                val key = declaration.substring(0, separatorIndex).trim().lowercase()
                val value = declaration.substring(separatorIndex + 1).trim()
                if (key.isEmpty() || value.isEmpty()) null else key to value
            }
            .toMap()
    }
}

