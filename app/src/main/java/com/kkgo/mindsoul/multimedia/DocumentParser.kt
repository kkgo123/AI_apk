/*
 * ============================================================
 * DocumentParser - 全格式文档解析器
 * ============================================================
 *
 * 支持全格式文档的纯文本提取：
 * 1. PDF → 文本（自研轻量解析器）
 * 2. Word (.docx) → 文本（XML 解包）
 * 3. TXT/MD → 直接读取
 * 4. HTML → 纯文本（标签剥离）
 * 5. CSV/JSON → 结构化文本
 * 6. EPUB → 文本（ZIP + HTML）
 * 7. RTF → 文本（简易 RTF 解析）
 *
 * 所有解析均为纯手写 Kotlin 实现，
 * 不依赖任何第三方文档解析库。
 * ============================================================
 */
package com.kkgo.mindsoul.multimedia

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import java.io.*
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * 文档解析结果
 */
data class DocumentParseResult(
    /** 提取的纯文本 */
    val text: String,
    /** 文件格式 */
    val format: String,
    /** 页数（如适用） */
    val pageCount: Int = 0,
    /** 字符数 */
    val charCount: Int = text.length,
    /** 处理耗时（毫秒） */
    val durationMs: Long = 0,
    /** 元数据 */
    val metadata: Map<String, String> = emptyMap()
)

/**
 * 文档格式枚举
 */
enum class DocFormat(val extension: String, val mimeType: String) {
    PDF("pdf", "application/pdf"),
    DOCX("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    TXT("txt", "text/plain"),
    MD("md", "text/markdown"),
    HTML("html", "text/html"),
    CSV("csv", "text/csv"),
    JSON("json", "application/json"),
    EPUB("epub", "application/epub+zip"),
    RTF("rtf", "application/rtf"),
    UNKNOWN("unknown", "application/octet-stream");

    companion object {
        /** 根据文件扩展名识别格式 */
        fun fromExtension(filename: String): DocFormat {
            val ext = filename.substringAfterLast('.', "").lowercase()
            return entries.firstOrNull { it.extension == ext } ?: UNKNOWN
        }
    }
}

/**
 * 全格式文档解析器
 */
class DocumentParser(private val context: Context) {

    companion object {
        private const val TAG = "DocumentParser"
        /** 最大解析文件大小（50MB） */
        private const val MAX_FILE_SIZE = 50L * 1024 * 1024
        /** 文本提取最大字符数（防止内存溢出） */
        private const val MAX_TEXT_LENGTH = 5_000_000
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ============ 初始化 ============

    fun initialize() {
        Log.i(TAG, "[初始化] 文档解析器就绪，支持: ${DocFormat.entries.filter { it != DocFormat.UNKNOWN }.joinToString { it.extension }}")
    }

    fun destroy() {
        scope.cancel()
        Log.i(TAG, "[销毁] 文档解析器已释放")
    }

    // ============ 统一解析接口 ============

    /**
     * 解析文档文件
     *
     * 自动识别文件格式，选择对应的解析器。
     *
     * @param filePath 文件路径
     * @return 解析结果
     */
    suspend fun parse(filePath: String): DocumentParseResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        Log.d(TAG, "[解析] 开始: $filePath")

        val file = File(filePath)
        if (!file.exists()) {
            return@withContext DocumentParseResult("", "unknown", durationMs = System.currentTimeMillis() - startTime)
        }

        if (file.length() > MAX_FILE_SIZE) {
            Log.w(TAG, "[解析] 文件过大: ${file.length()} bytes")
            return@withContext DocumentParseResult(
                "", DocFormat.fromExtension(file.name).extension,
                durationMs = System.currentTimeMillis() - startTime,
                metadata = mapOf("error" to "文件过大(${file.length() / 1024 / 1024}MB)")
            )
        }

        val format = DocFormat.fromExtension(file.name)
        val result = when (format) {
            DocFormat.PDF -> parsePDF(file)
            DocFormat.DOCX -> parseDOCX(file)
            DocFormat.TXT, DocFormat.MD -> parsePlainText(file)
            DocFormat.HTML -> parseHTML(file)
            DocFormat.CSV -> parseCSV(file)
            DocFormat.JSON -> parseJSON(file)
            DocFormat.EPUB -> parseEPUB(file)
            DocFormat.RTF -> parseRTF(file)
            DocFormat.UNKNOWN -> parseAsText(file)
        }

        val duration = System.currentTimeMillis() - startTime
        Log.i(TAG, "[解析] 完成: ${format.extension}, ${result.charCount} 字, ${result.pageCount} 页, ${duration}ms")
        result.copy(durationMs = duration)
    }

    /**
     * 批量解析多个文件
     */
    suspend fun parseBatch(filePaths: List<String>): List<DocumentParseResult> {
        return filePaths.map { parse(it) }
    }

    // ============ 各格式解析器 ============

    /**
     * PDF 文本提取
     *
     * 简易 PDF 解析：
     * 1. 查找 BT...ET 文本对象
     * 2. 提取 Tj/TJ 操作符中的字符串
     * 3. 处理十六进制编码文本
     *
     * 注：这是简化版实现。
     * 完整版需处理字体编码映射、CMap 表等。
     */
    private fun parsePDF(file: File): DocumentParseResult {
        val bytes = file.readBytes()
        val text = StringBuilder()
        var pageCount = 0

        // 统计页数（/Type /Page）
        val pagePattern = Regex("/Type\\s*/Page[^s]")
        pageCount = pagePattern.findAll(String(bytes, Charsets.ISO_8859_1)).count().coerceAtLeast(1)

        // 提取文本对象
        val content = String(bytes, Charsets.ISO_8859_1)

        // 方法1：提取 BT...ET 块中的 Tj 操作符
        val btEtPattern = Regex("BT(.*?)ET", RegexOption.DOT_MATCHES_ALL)
        val tjPattern = Regex("""\(([^)]*)\)\s*Tj""")
        val tjArrayPattern = Regex("""\[(.*?)\]\s*TJ""", RegexOption.DOT_MATCHES_ALL)

        for (match in btEtPattern.findAll(content)) {
            val block = match.groupValues[1]

            // 直接字符串 Tj
            for (tj in tjPattern.findAll(block)) {
                val str = tj.groupValues[1]
                    .replace("\\n", "\n")
                    .replace("\\t", "\t")
                    .replace("\\(", "(")
                    .replace("\\)", ")")
                    .replace("\\\\", "\\")
                if (str.isNotBlank()) text.append(str)
            }

            // 数组形式 TJ
            for (tjArr in tjArrayPattern.findAll(block)) {
                val arrContent = tjArr.groupValues[1]
                val strParts = Regex("""\(([^)]*)\)""").findAll(arrContent)
                val line = strParts.joinToString("") { it.groupValues[1] }
                if (line.isNotBlank()) text.append(line)
            }

            text.append("\n")
        }

        val result = text.toString().trim()
        val truncated = if (result.length > MAX_TEXT_LENGTH) result.substring(0, MAX_TEXT_LENGTH) else result

        return DocumentParseResult(
            text = truncated,
            format = "pdf",
            pageCount = pageCount,
            metadata = mapOf("fileSize" to file.length().toString())
        )
    }

    /**
     * DOCX 文本提取
     *
     * DOCX 本质是一个 ZIP 包，核心内容在 word/document.xml 中。
     * 流程：
     * 1. 解包 ZIP
     * 2. 读取 word/document.xml
     * 3. 提取 XML 中的纯文本（<w:t> 标签内容）
     */
    private fun parseDOCX(file: File): DocumentParseResult {
        val text = StringBuilder()
        var pageCount = 1

        try {
            ZipFile(file).use { zip ->
                // 读取主文档
                val docEntry = zip.getEntry("word/document.xml")
                if (docEntry != null) {
                    val xml = zip.getInputStream(docEntry).bufferedReader().readText()
                    // 提取 <w:t> 标签中的文本
                    val wTextPattern = Regex("""<w:t[^>]*>([^<]*)</w:t>""")
                    var lastWasParagraph = false
                    val lines = xml.split("<w:p ", "<w:p>")

                    for (line in lines) {
                        val texts = wTextPattern.findAll(line).map { it.groupValues[1] }.toList()
                        if (texts.isNotEmpty()) {
                            if (lastWasParagraph) text.append("\n")
                            text.append(texts.joinToString(""))
                            lastWasParagraph = true
                        }
                    }
                }

                // 估算页数（按每页约3000字符）
                pageCount = (text.length / 3000).coerceAtLeast(1)
            }
        } catch (e: Exception) {
            Log.e(TAG, "[DOCX] 解析失败: ${e.message}")
        }

        return DocumentParseResult(
            text = text.toString().trim(),
            format = "docx",
            pageCount = pageCount
        )
    }

    /**
     * 纯文本/TXT/Markdown 读取
     */
    private fun parsePlainText(file: File): DocumentParseResult {
        val text = file.readText(Charsets.UTF_8)
        val truncated = if (text.length > MAX_TEXT_LENGTH) text.substring(0, MAX_TEXT_LENGTH) else text
        val lines = text.lines().size
        val ext = file.extension.lowercase()
        return DocumentParseResult(
            text = truncated,
            format = ext,
            pageCount = (lines / 50).coerceAtLeast(1),
            metadata = mapOf("lineCount" to lines.toString())
        )
    }

    /**
     * HTML 纯文本提取
     *
     * 剥离所有 HTML 标签，保留文本内容。
     * 处理 <script>、<style> 等特殊标签（直接跳过）。
     */
    private fun parseHTML(file: File): DocumentParseResult {
        val html = file.readText(Charsets.UTF_8)
        val text = stripHtmlTags(html)
        return DocumentParseResult(
            text = text,
            format = "html",
            pageCount = (text.length / 3000).coerceAtLeast(1)
        )
    }

    /**
     * CSV 解析为可读文本
     */
    private fun parseCSV(file: File): DocumentParseResult {
        val lines = file.readLines(Charsets.UTF_8)
        val text = StringBuilder()

        for (line in lines) {
            // 简单的 CSV 分割（处理引号内的逗号）
            val fields = parseCSVLine(line)
            text.appendLine(fields.joinToString(" | "))
        }

        return DocumentParseResult(
            text = text.toString().trim(),
            format = "csv",
            pageCount = 1,
            metadata = mapOf("rowCount" to lines.size.toString())
        )
    }

    /**
     * JSON 格式化为可读文本
     */
    private fun parseJSON(file: File): DocumentParseResult {
        val json = file.readText(Charsets.UTF_8)
        // 简化：提取所有键值对的文本内容
        val text = StringBuilder()
        extractJsonValues(json, text)
        return DocumentParseResult(
            text = text.toString().trim(),
            format = "json",
            pageCount = 1
        )
    }

    /**
     * EPUB 文本提取
     *
     * EPUB 本质是 ZIP 包含 XHTML 文件。
     * 流程：
     * 1. 解包 ZIP
     * 2. 找到 content.opf 获取阅读顺序
     * 3. 按顺序提取各章节 XHTML 的文本
     */
    private fun parseEPUB(file: File): DocumentParseResult {
        val text = StringBuilder()

        try {
            ZipFile(file).use { zip ->
                // 简化：直接提取所有 .xhtml/.html 文件
                val htmlEntries = zip.entries().asSequence()
                    .filter { it.name.endsWith(".xhtml") || it.name.endsWith(".html") }
                    .sortedBy { it.name }
                    .toList()

                for (entry in htmlEntries) {
                    val html = zip.getInputStream(entry).bufferedReader().readText()
                    val extracted = stripHtmlTags(html)
                    if (extracted.isNotBlank()) {
                        text.appendLine(extracted)
                        text.appendLine("---")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[EPUB] 解析失败: ${e.message}")
        }

        return DocumentParseResult(
            text = text.toString().trim(),
            format = "epub",
            pageCount = (text.length / 3000).coerceAtLeast(1)
        )
    }

    /**
     * RTF 简易解析
     *
     * 剥离 RTF 控制字，保留纯文本。
     */
    private fun parseRTF(file: File): DocumentParseResult {
        val rtf = file.readText(Charsets.UTF_8)
        val text = StringBuilder()
        var i = 0
        var depth = 0

        while (i < rtf.length) {
            when {
                rtf[i] == '{' -> depth++
                rtf[i] == '}' -> depth--
                rtf[i] == '\\' -> {
                    // 跳过控制字
                    i++
                    if (i < rtf.length) {
                        when (rtf[i]) {
                            '\\' -> text.append('\\')
                            '{' -> text.append('{')
                            '}' -> text.append('}')
                            'n' -> text.append('\n')
                            'r' -> {} // 跳过 \r
                            't' -> text.append('\t')
                            '\'' -> {
                                // 十六进制字符
                                if (i + 2 < rtf.length) {
                                    val hex = rtf.substring(i + 1, i + 3)
                                    val char = hex.toIntOrNull(16)?.toChar()
                                    if (char != null) text.append(char)
                                    i += 2
                                }
                            }
                            else -> {
                                // 跳过控制字名称
                                while (i < rtf.length && rtf[i].isLetter()) i++
                                // 跳过可选的空格参数
                                if (i < rtf.length && rtf[i] == ' ') i++
                            }
                        }
                    }
                }
                else -> {
                    if (rtf[i] != '\r' && rtf[i].code > 31) {
                        text.append(rtf[i])
                    }
                }
            }
            i++
        }

        return DocumentParseResult(
            text = text.toString().trim(),
            format = "rtf",
            pageCount = (text.length / 3000).coerceAtLeast(1)
        )
    }

    /**
     * 未知格式：尝试作为纯文本读取
     */
    private fun parseAsText(file: File): DocumentParseResult {
        return try {
            val text = file.readText(Charsets.UTF_8)
            DocumentParseResult(text = text, format = "text")
        } catch (e: Exception) {
            DocumentParseResult(text = "", format = "binary", metadata = mapOf("error" to "无法作为文本读取"))
        }
    }

    // ============ 工具方法 ============

    /**
     * HTML 标签剥离
     */
    private fun stripHtmlTags(html: String): String {
        var result = html
        // 移除 <script> 和 <style> 块
        result = Regex("""<script[^>]*>[\s\S]*?</script>""", RegexOption.IGNORE_CASE).replace(result, "")
        result = Regex("""<style[^>]*>[\s\S]*?</style>""", RegexOption.IGNORE_CASE).replace(result, "")
        // 移除所有标签
        result = Regex("""<[^>]+>""").replace(result, "")
        // 解码 HTML 实体
        result = result
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
        // 清理多余空白
        result = result.lines().joinToString("\n") { it.trim() }.trim()
        result = Regex("\n{3,}").replace(result, "\n\n")
        return result
    }

    /**
     * CSV 行解析（处理引号内的逗号）
     */
    private fun parseCSVLine(line: String): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false

        for (char in line) {
            when {
                char == '"' -> inQuotes = !inQuotes
                char == ',' && !inQuotes -> {
                    fields.add(current.toString().trim())
                    current.clear()
                }
                else -> current.append(char)
            }
        }
        fields.add(current.toString().trim())
        return fields
    }

    /**
     * 递归提取 JSON 中的字符串值
     */
    private fun extractJsonValues(json: String, output: StringBuilder) {
        // 简易 JSON 值提取：匹配 "key": "value" 和纯字符串值
        val kvPattern = Regex(""""([^"\\]+)"\s*:\s*"([^"\\]*)"""")
        for (match in kvPattern.findAll(json)) {
            output.appendLine("${match.groupValues[1]}: ${match.groupValues[2]}")
        }
        // 数组中的字符串值
        val arrPattern = Regex(""""([^"\\]{5,})"""")
        for (match in arrPattern.findAll(json)) {
            val value = match.groupValues[1]
            if (!output.contains(value)) {
                output.appendLine(value)
            }
        }
    }
}
