import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.security.MessageDigest
import java.util.Properties

abstract class GenerateI18nResourcesTask : DefaultTask() {
    @get:InputFile
    @get:Optional
    abstract val configFile: RegularFileProperty

    @get:Input
    abstract val scanDirs: ListProperty<String>

    @get:Internal
    abstract val rootProjectDir: DirectoryProperty

    @get:InputFile
    @get:Optional
    abstract val glossary: RegularFileProperty

    @get:InputFile
    @get:Optional
    abstract val baseTranslations: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:Optional
    abstract val composeAssetResourcesDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputComposeResources: DirectoryProperty

    @get:OutputDirectory
    abstract val outputKotlin: DirectoryProperty

    @get:OutputDirectory
    abstract val reportDir: DirectoryProperty

    @get:Input
    abstract val defaultLanguage: Property<String>

    @get:Input
    abstract val fallbackToSource: Property<Boolean>

    @get:Input
    abstract val failOnPlaceholderMismatch: Property<Boolean>

    @get:Input
    abstract val failOnMissingTranslation: Property<Boolean>

    @get:Input
    abstract val failOnMissingTerm: Property<Boolean>

    @get:Input
    abstract val apiFunctionName: Property<String>

    @get:Input
    abstract val runtimePackage: Property<String>

    @get:Input
    abstract val resImportPackage: Property<String>

    @TaskAction
    fun generate() {
        val settings = loadSettings()
        val apiName = settings.apiFunctionName
        require(apiName == "i18n") { "Only lowercase i18n is supported; configured apiFunctionName=$apiName" }

        val allCalls = scanSources(settings.scanDirs, apiName)
        val calls = allCalls.distinctBy { it.source }.sortedBy { it.source }
        val glossaryRows = readGlossary(settings.translationFiles)
        validateMissingTerms(allCalls, glossaryRows, settings.failOnMissingTerm)

        val entries = calls.map { call ->
            val key = keyFor(call.source)
            val placeholders = placeholdersIn(call.source)
            val translations = languages.associateWith { language ->
                resolveTranslation(call.source, language, glossaryRows)
            }
            I18nEntry(call, key, placeholders, translations)
        }

        validate(entries, settings.defaultLanguage, settings.failOnPlaceholderMismatch, settings.failOnMissingTranslation)
        writeResources(entries, settings.outputComposeResources, settings.composeAssetResourcesDir)
        writeRuntime(entries, settings.outputKotlin, apiName, settings.runtimePackage, settings.resImportPackage)
        writeReports(entries, settings.reportDir)
    }

    private fun loadSettings(): Settings {
        val props = Properties()
        val config = configFile.orNull?.asFile
        if (config != null && config.isFile) {
            config.inputStream().use { props.load(it) }
        }

        fun prop(name: String): String? = props.getProperty(name)?.trim()?.takeIf { it.isNotEmpty() }
        fun boolProp(name: String, fallback: Boolean): Boolean = prop(name)?.toBooleanStrictOrNull() ?: fallback
        fun dirsProp(name: String, fallback: List<String>): List<String> =
            prop(name)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: fallback

        val root = rootProjectDir.get().asFile
        fun fileProp(name: String, fallback: File?): File? =
            prop(name)?.let { root.resolve(it).normalize() } ?: fallback

        return Settings(
            scanDirs = dirsProp("scanDirs", scanDirs.get()).map { root.resolve(it).normalize() },
            translationFiles = listOfNotNull(
                fileProp("glossary", glossary.orNull?.asFile)
                    ?.let { TranslationFile(it, isBaseTranslation = false) },
                fileProp("baseTranslations", baseTranslations.orNull?.asFile)
                    ?.let { TranslationFile(it, isBaseTranslation = true) },
            ),
            composeAssetResourcesDir = composeAssetResourcesDir.orNull?.asFile,
            outputComposeResources = prop("outputComposeResources")
                ?.let { root.resolve(it).normalize() }
                ?: outputComposeResources.get().asFile,
            outputKotlin = prop("outputKotlin")?.let { root.resolve(it).normalize() } ?: outputKotlin.get().asFile,
            reportDir = prop("reportDir")?.let { root.resolve(it).normalize() } ?: reportDir.get().asFile,
            defaultLanguage = normalizeLanguage(prop("defaultLanguage") ?: defaultLanguage.get()),
            fallbackToSource = boolProp("fallbackToSource", fallbackToSource.get()),
            failOnPlaceholderMismatch = boolProp("failOnPlaceholderMismatch", failOnPlaceholderMismatch.get()),
            failOnMissingTranslation = boolProp("failOnMissingTranslation", failOnMissingTranslation.get()),
            failOnMissingTerm = boolProp("failOnMissingTerm", failOnMissingTerm.get()),
            apiFunctionName = prop("apiFunctionName") ?: apiFunctionName.get(),
            runtimePackage = prop("runtimePackage") ?: runtimePackage.get(),
            resImportPackage = prop("resImportPackage") ?: resImportPackage.get(),
        )
    }

    private fun scanSources(scanRoots: List<File>, apiName: String): List<I18nCall> {
        val pattern = Regex("\\b${Regex.escape(apiName)}\\s*\\(\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
        return scanRoots
            .filter { it.exists() }
            .flatMap { root ->
                root.walkTopDown()
                    .filter { it.isFile && it.extension == "kt" && !it.inExcludedPath() }
                    .flatMap { file ->
                        val text = file.readText(Charsets.UTF_8)
                        pattern.findAll(text).map { match ->
                            val line = 1 + text.substring(0, match.range.first).count { it == '\n' }
                            val lineStart = text.lastIndexOf('\n', match.range.first).let { if (it < 0) 0 else it + 1 }
                            I18nCall(
                                file = file,
                                line = line,
                                column = match.range.first - lineStart + 1,
                                source = unescapeKotlinString(match.groupValues[1]),
                            )
                        }.toList()
                    }.toList()
            }
    }

    private fun readGlossary(files: List<TranslationFile>): List<GlossaryRow> =
        files.filter { it.file.isFile }.flatMap { translationFile ->
            val file = translationFile.file
            val rows = parseCsv(file.readText(Charsets.UTF_8))
            if (rows.isEmpty()) return@flatMap emptyList()
            val header = rows.first().map { it.trim().lowercase() }
            val data = rows.drop(1)
            val sourceIndex = header.indexOf("source").takeIf { it >= 0 }
            data.mapNotNull { row ->
                val values = languages.associateWith { language ->
                    val index = header.indexOf(language)
                    index.takeIf { it >= 0 }?.let { row.getOrNull(it).orEmpty() }.orEmpty()
                }
                val source = sourceIndex?.let { row.getOrNull(it).orEmpty() }
                    ?: values.values.firstOrNull { it.isNotBlank() }.orEmpty()
                if (source.isBlank() && values.values.all { it.isBlank() }) {
                    null
                } else {
                    GlossaryRow(source, values, translationFile.isBaseTranslation)
                }
            }
        }

    private fun resolveTranslation(
        source: String,
        language: String,
        glossaryRows: List<GlossaryRow>,
    ): String {
        val matchingRows = glossaryRows.filter { it.matches(source) }
        val glossaryRow = matchingRows.firstOrNull { !it.isBaseTranslation }
        val baseRow = matchingRows.firstOrNull { it.isBaseTranslation }

        if (language == "zh-cn") {
            val glossarySimplified = glossaryRow?.values?.get(language).orEmpty()
            val glossaryTraditional = glossaryRow?.values?.get("zh-tw").orEmpty()
            val baseSimplified = baseRow?.values?.get(language).orEmpty()
            val baseTraditional = baseRow?.values?.get("zh-tw").orEmpty()
            if (
                glossarySimplified.isNotBlank() &&
                glossarySimplified == glossaryTraditional &&
                baseSimplified.isNotBlank() &&
                baseSimplified != baseTraditional
            ) {
                return baseSimplified
            }
        }

        glossaryRow?.values?.get(language)?.takeIf { it.isNotBlank() }?.let { return it }
        baseRow?.values?.get(language)?.takeIf { it.isNotBlank() }?.let { return it }

        var converted = source
        glossaryRows
            .filter { it.source.isNotBlank() }
            .sortedByDescending { it.source.length }
            .forEach { row ->
                val replacement = row.values[language].orEmpty()
                if (replacement.isNotBlank()) {
                    converted = converted.replace(row.source, replacement)
                }
            }
        return if (converted != source) converted else source
    }

    private fun validate(entries: List<I18nEntry>, defaultLanguage: String, failPlaceholder: Boolean, failMissing: Boolean) {
        val keyConflicts = entries.groupBy { it.key }.filterValues { rows -> rows.map { it.call.source }.distinct().size > 1 }
        if (keyConflicts.isNotEmpty()) {
            throw GradleException("i18n key conflict: ${keyConflicts.keys.joinToString()}")
        }

        val errors = mutableListOf<String>()
        entries.forEach { entry ->
            val sourceCount = entry.placeholders.size
            entry.translations.forEach { (language, value) ->
                val targetCount = placeholdersIn(value).size
                if (targetCount != sourceCount) {
                    val message = "${entry.call.source} [$language] placeholder mismatch: source=$sourceCount target=$targetCount"
                    if (failPlaceholder) errors += message
                }
                if (failMissing && value == entry.call.source && language != defaultLanguage) {
                    errors += "${entry.call.source} [$language] missing translation"
                }
            }
        }
        if (errors.isNotEmpty()) throw GradleException(errors.joinToString(separator = "\n"))
    }

    private fun writeResources(entries: List<I18nEntry>, outputDir: File, assetResourcesDir: File?) {
        if (outputDir.exists()) outputDir.deleteRecursively()
        copyComposeAssets(assetResourcesDir, outputDir)
        languages.forEach { language ->
            val dirName = when (language) {
                "en" -> "values-en"
                "zh-tw" -> "values-zh-rTW"
                "zh-cn" -> "values-zh-rCN"
                else -> "values-$language"
            }
            val targetDir = outputDir.resolve(dirName)
            targetDir.mkdirs()
            targetDir.resolve("strings_i18n_auto.xml").writeText(buildString {
                appendLine("<resources>")
                entries.forEach { entry ->
                    appendLine("    <string name=\"${entry.key}\">${escapeXml(toAndroidFormat(entry.translations.getValue(language), entry.placeholders))}</string>")
                }
                appendLine("</resources>")
            }, Charsets.UTF_8)
        }
        val defaultDir = outputDir.resolve("values")
        defaultDir.mkdirs()
        outputDir.resolve("values-zh-rTW/strings_i18n_auto.xml")
            .copyTo(defaultDir.resolve("strings_i18n_auto.xml"), overwrite = true)
    }

    private fun copyComposeAssets(sourceDir: File?, outputDir: File) {
        if (sourceDir?.isDirectory != true) return
        sourceDir.walkTopDown()
            .filter { it.isFile }
            .filterNot { file ->
                file.name == "strings.xml" && file.parentFile?.name?.startsWith("values") == true
            }
            .forEach { source ->
                val target = outputDir.resolve(source.relativeTo(sourceDir).invariantSeparatorsPath)
                target.parentFile.mkdirs()
                source.copyTo(target, overwrite = true)
            }
    }

    private fun writeRuntime(entries: List<I18nEntry>, outputDir: File, apiName: String, packageName: String, resPackage: String) {
        if (outputDir.exists()) outputDir.deleteRecursively()
        val packageDir = outputDir.resolve(packageName.replace('.', '/'))
        packageDir.mkdirs()
        packageDir.resolve("I18nRuntime.kt").writeText(buildString {
            appendLine("package $packageName")
            appendLine()
            appendLine("import $resPackage.Res")
            appendLine("import $resPackage.*")
            appendLine()
            appendLine("fun $apiName(source: String): String = $apiName(source, *emptyArray<Any?>())")
            appendLine()
            appendLine("fun $apiName(source: String, vararg args: Any?): String = when (source) {")
            entries.forEach { entry ->
                val args = entry.placeholders.indices.joinToString(separator = "") { index ->
                    ",\n        args.getOrNull($index) ?: \"{}\""
                }
                appendLine("    \"${escapeKotlin(entry.call.source)}\" -> appString(Res.string.${entry.key}$args)")
            }
            appendLine("    else -> formatI18nFallback(source, args)")
            appendLine("}")
            appendLine()
            appendLine("private val I18nPlaceholderRegex = Regex(\"\\\\{\\\\}\")")
            appendLine()
            appendLine("private fun formatI18nFallback(source: String, args: Array<out Any?>): String {")
            appendLine("    var index = 0")
            appendLine("    return I18nPlaceholderRegex.replace(source) {")
            appendLine("        val value = args.getOrNull(index)")
            appendLine("        index += 1")
            appendLine("        value?.toString() ?: \"{}\"")
            appendLine("    }")
            appendLine("}")
        }, Charsets.UTF_8)

        packageDir.resolve("I18nRegistry.kt").writeText(buildString {
            appendLine("package $packageName")
            appendLine()
            appendLine("// Generated by generateI18nResources. Do not edit manually.")
            appendLine("object I18nRegistry {")
            appendLine("    val sourceToKey: Map<String, String> = mapOf(")
            entries.forEach { entry ->
                appendLine("        \"${escapeKotlin(entry.call.source)}\" to \"${entry.key}\",")
            }
            appendLine("    )")
            appendLine("}")
        }, Charsets.UTF_8)
    }

    private fun writeReports(entries: List<I18nEntry>, outputDir: File) {
        if (outputDir.exists()) outputDir.deleteRecursively()
        outputDir.mkdirs()
        val reportRows = entries.flatMap { entry ->
            languages.map { language ->
                val converted = toAndroidFormat(entry.translations.getValue(language), entry.placeholders)
                ReportRow(
                    file = entry.call.file.relativeTo(rootProjectDir.get().asFile).invariantSeparatorsPath,
                    line = entry.call.line,
                    column = entry.call.column,
                    source = entry.call.source,
                    key = entry.key,
                    converted = converted,
                    language = language,
                    placeholders = entry.placeholders.joinToString("|"),
                    status = if (looksNonTranslatable(entry.call.source)) "warning" else if (converted == entry.call.source) "fallback_original" else "generated",
                    reason = if (looksNonTranslatable(entry.call.source)) "looks non-translatable" else if (converted == entry.call.source) "no glossary or existing translation match" else "",
                )
            }
        }
        outputDir.resolve("i18n_report.csv").writeText(buildString {
            appendLine("file,line,column,source,key,converted,language,placeholders,status,reason")
            reportRows.forEach { row ->
                appendLine(listOf(row.file, row.line, row.column, row.source, row.key, row.converted, row.language, row.placeholders, row.status, row.reason).joinToString(",") { csvEscape(it.toString()) })
            }
        }, Charsets.UTF_8)
        outputDir.resolve("i18n_report.json").writeText(buildString {
            appendLine("[")
            reportRows.forEachIndexed { index, row ->
                append("  {")
                append("\"file\":\"${jsonEscape(row.file)}\",")
                append("\"line\":${row.line},")
                append("\"column\":${row.column},")
                append("\"source\":\"${jsonEscape(row.source)}\",")
                append("\"key\":\"${row.key}\",")
                append("\"converted\":\"${jsonEscape(row.converted)}\",")
                append("\"language\":\"${row.language}\",")
                append("\"placeholders\":\"${jsonEscape(row.placeholders)}\",")
                append("\"status\":\"${row.status}\",")
                append("\"reason\":\"${jsonEscape(row.reason)}\"")
                append("}")
                appendLine(if (index == reportRows.lastIndex) "" else ",")
            }
            appendLine("]")
        }, Charsets.UTF_8)
    }

    private fun File.inExcludedPath(): Boolean {
        val path = invariantSeparatorsPath
        return excludedPathParts.any { "/$it/" in "/$path/" }
    }

    private fun GlossaryRow.matches(source: String): Boolean =
        this.source == source || values.values.any { it.isNotBlank() && it == source }

    private fun keyFor(source: String): String {
        val slug = source
            .replace(Regex("""\{\}"""), " value ")
            .lowercase()
            .replace(Regex("""[^a-z0-9]+"""), "_")
            .trim('_')
            .ifBlank { "text" }
            .take(48)
            .trim('_')
        return "auto_${slug}_${sha256(source).take(8)}"
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun placeholdersIn(value: String): List<String> =
        Regex("""\{\}""").findAll(value).mapIndexed { index, _ -> index.toString() }.toList()

    private fun toAndroidFormat(value: String, placeholders: List<String>): String {
        var index = 0
        return Regex("""\{\}""").replace(value) {
            index += 1
            if (index <= placeholders.size) "%$index\$s" else "{}"
        }
    }

    private fun validateMissingTerms(calls: List<I18nCall>, glossaryRows: List<GlossaryRow>, failMissingTerm: Boolean) {
        if (!failMissingTerm) return

        val missing = calls
            .groupBy { it.source }
            .filterKeys { source -> glossaryRows.none { row -> row.matches(source) } }

        if (missing.isEmpty()) return

        val root = rootProjectDir.get().asFile
        val message = buildString {
            appendLine("Missing i18n terms for ${missing.size} source text(s).")
            appendLine("Every i18n(\"...\") source text must have a matching row in the configured glossary/base translation files.")
            missing.toSortedMap().forEach { (source, locations) ->
                appendLine()
                appendLine("i18n(\"${escapeKotlin(source)}\")")
                locations.sortedWith(compareBy<I18nCall> { it.file.invariantSeparatorsPath }.thenBy { it.line }.thenBy { it.column })
                    .forEach { call ->
                        val path = call.file.relativeTo(root).invariantSeparatorsPath
                        appendLine("  - $path:${call.line}:${call.column}")
                    }
            }
        }
        throw GradleException(message)
    }

    private fun parseCsv(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val cell = StringBuilder()
        var inQuotes = false
        var index = 0
        while (index < text.length) {
            val char = text[index]
            when {
                inQuotes && char == '"' && text.getOrNull(index + 1) == '"' -> {
                    cell.append('"')
                    index++
                }
                char == '"' -> inQuotes = !inQuotes
                !inQuotes && char == ',' -> {
                    row += cell.toString()
                    cell.clear()
                }
                !inQuotes && (char == '\n' || char == '\r') -> {
                    if (char == '\r' && text.getOrNull(index + 1) == '\n') index++
                    row += cell.toString()
                    cell.clear()
                    if (row.any { it.isNotBlank() }) rows += row.toList()
                    row.clear()
                }
                else -> cell.append(char)
            }
            index++
        }
        row += cell.toString()
        if (row.any { it.isNotBlank() }) rows += row.toList()
        return rows
    }

    private fun normalizeLanguage(value: String): String = value.replace('_', '-').lowercase()

    private fun looksNonTranslatable(value: String): Boolean =
        value.startsWith("/") ||
            value.startsWith("http://") ||
            value.startsWith("https://") ||
            value in setOf("GET", "POST", "PUT", "PATCH", "DELETE", "application/json", "Authorization") ||
            Regex("""^[a-z]+(/[A-Za-z0-9_-]+)+$""").matches(value) ||
            Regex("""^[A-Za-z_][A-Za-z0-9_.-]*$""").matches(value) && "_" in value

    private fun unescapeKotlinString(value: String): String =
        value.replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .replace("\\n", "\n")
            .replace("\\t", "\t")
            .replace("\\r", "\r")

    private fun escapeKotlin(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")

    private fun escapeXml(value: String): String =
        value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "\\'")

    private fun csvEscape(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"${value.replace("\"", "\"\"")}\"" else value

    private fun jsonEscape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")

    private data class Settings(
        val scanDirs: List<File>,
        val translationFiles: List<TranslationFile>,
        val composeAssetResourcesDir: File?,
        val outputComposeResources: File,
        val outputKotlin: File,
        val reportDir: File,
        val defaultLanguage: String,
        val fallbackToSource: Boolean,
        val failOnPlaceholderMismatch: Boolean,
        val failOnMissingTranslation: Boolean,
        val failOnMissingTerm: Boolean,
        val apiFunctionName: String,
        val runtimePackage: String,
        val resImportPackage: String,
    )

    private data class I18nCall(val file: File, val line: Int, val column: Int, val source: String)
    private data class TranslationFile(val file: File, val isBaseTranslation: Boolean)
    private data class GlossaryRow(
        val source: String,
        val values: Map<String, String>,
        val isBaseTranslation: Boolean,
    )
    private data class I18nEntry(
        val call: I18nCall,
        val key: String,
        val placeholders: List<String>,
        val translations: Map<String, String>,
    )
    private data class ReportRow(
        val file: String,
        val line: Int,
        val column: Int,
        val source: String,
        val key: String,
        val converted: String,
        val language: String,
        val placeholders: String,
        val status: String,
        val reason: String,
    )

    private companion object {
        val languages = listOf("en", "zh-tw", "zh-cn")
        val excludedPathParts = listOf("build", ".gradle", "generated", "test", "androidTest", "desktopTest")
    }
}
