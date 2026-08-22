/*
 * ============================================================
 * WebCrawlEngine - 网址抓取引擎
 * ============================================================
 *
 * 网址抓取的核心引擎，负责：
 *
 * 1. 与 CrawlProcessManager 协作
 *    - 提供抓取结果的学习提交接口
 *    - 支持网页学习内容筛选（文字/图片/视频/代码）
 *
 * 2. 学习内容筛选开关
 *    - 文字抓取开关（默认开）
 *    - 图片抓取开关（默认关）
 *    - 视频抓取开关（默认关）
 *    - 网页代码抓取开关（默认关）
 *
 * 3. 内容过滤
 *    - 去噪（移除导航、广告、页脚等）
 *    - 提取正文核心内容
 *    - 可选提取图片URL、视频URL、源码
 * ============================================================
 */
package com.kkgo.mindsoul.learning

import java.io.ByteArrayOutputStream

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 网页学习内容筛选配置
 */
data class WebContentFilter(
    /** 是否抓取文字内容 */
    var enableText: Boolean = true,
    /** 是否抓取图片URL */
    var enableImages: Boolean = false,
    /** 是否抓取视频URL */
    var enableVideos: Boolean = false,
    /** 是否抓取网页源代码 */
    var enableSourceCode: Boolean = false
) {
    companion object {
        /** 默认配置 */
        fun default(): WebContentFilter = WebContentFilter()
    }
}

/**
 * 抓取结果
 */
data class CrawlResult(
    /** 原始URL */
    val url: String,
    /** 提取的文字内容 */
    val textContent: String = "",
    /** 提取的图片URL列表 */
    val imageUrls: List<String> = emptyList(),
    /** 提取的视频URL列表 */
    val videoUrls: List<String> = emptyList(),
    /** 网页源代码 */
    val sourceCode: String = "",
    /** 抓取时间 */
    val crawlTime: Long = System.currentTimeMillis(),
    /** 页面标题 */
    val title: String = ""
)

/**
 * 网址抓取引擎
 */
class WebCrawlEngine(private val context: Context) {

    companion object {
        private const val TAG = "WebCrawlEngine"
        /** 请求超时时间（毫秒） */
        private const val TIMEOUT_MS = 15_000
        /** User-Agent */
        private const val USER_AGENT = "MindSoul/1.0 (Android; AGI Learning)"
        /** 最大内容长度（字节），超过则截断 */
        private const val MAX_CONTENT_SIZE = 5 * 1024 * 1024 // 5MB
    }

    // ============ 筛选配置 ============
    private val _contentFilter = MutableStateFlow(WebContentFilter.default())
    val contentFilterFlow: StateFlow<WebContentFilter> = _contentFilter.asStateFlow()

    /** 当前筛选配置 */
    var contentFilter: WebContentFilter
        get() = _contentFilter.value
        set(value) { _contentFilter.value = value }

    // ============ 网络状态 ============
    @Volatile
    private var networkAvailable: Boolean = true

    /**
     * 设置网络可用状态，由 CrawlForegroundService 调用
     *
     * @param available true=网络可用, false=网络不可用
     */
    fun setNetworkAvailable(available: Boolean) {
        val previous = networkAvailable
        networkAvailable = available
        if (previous != available) {
            Log.i(TAG, "[网络状态] 网络${if (available) "已恢复" else "已断开"}")
        }
    }

    /**
     * 获取当前网络可用状态
     */
    fun isNetworkAvailable(): Boolean = networkAvailable

    // ============ 学习流水线引用 ============
    private var pipeline: LearningPipeline? = null

    /** 已学习的内容存储目录 */
    private val learnedDir: File by lazy {
        File(context.filesDir, "learned_web").also { if (!it.exists()) it.mkdirs() }
    }

    // ============ 初始化 ============

    /**
     * 初始化引擎
     */
    fun initialize() {
        // 加载保存的筛选配置
        loadFilterConfig()
        Log.i(TAG, "[初始化] 网址抓取引擎就绪，筛选配置: ${contentFilter}")
    }

    /**
     * 绑定学习流水线
     */
    fun bindPipeline(pipeline: LearningPipeline) {
        this.pipeline = pipeline
    }

    fun destroy() {
        saveFilterConfig()
        Log.i(TAG, "[销毁] 网址抓取引擎已释放")
    }

    // ============ 筛选配置持久化 ============

    /**
     * 保存筛选配置
     */
    private fun saveFilterConfig() {
        try {
            val filterFile = File(context.filesDir, "web_filter_config.txt")
            val config = buildString {
                appendLine("enableText=${contentFilter.enableText}")
                appendLine("enableImages=${contentFilter.enableImages}")
                appendLine("enableVideos=${contentFilter.enableVideos}")
                appendLine("enableSourceCode=${contentFilter.enableSourceCode}")
            }
            filterFile.writeText(config, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "保存筛选配置失败: ${e.message}")
        }
    }

    /**
     * 加载筛选配置
     */
    private fun loadFilterConfig() {
        try {
            val filterFile = File(context.filesDir, "web_filter_config.txt")
            if (!filterFile.exists()) return

            val lines = filterFile.readLines(Charsets.UTF_8)
            for (line in lines) {
                val parts = line.split("=")
                if (parts.size != 2) continue
                when (parts[0].trim()) {
                    "enableText" -> contentFilter.enableText = parts[1].trim().toBoolean()
                    "enableImages" -> contentFilter.enableImages = parts[1].trim().toBoolean()
                    "enableVideos" -> contentFilter.enableVideos = parts[1].trim().toBoolean()
                    "enableSourceCode" -> contentFilter.enableSourceCode = parts[1].trim().toBoolean()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "加载筛选配置失败: ${e.message}")
        }
    }

    // ============ 核心抓取 ============

    /**
     * 抓取单个URL（根据筛选配置返回对应内容）
     *
     * 增加网络状态检查：无网络时暂停等待，最多等待60秒。
     */
    suspend fun crawl(url: String): CrawlResult? = withContext(Dispatchers.IO) {
        // 检查网络状态，无网络时暂停等待
        if (!networkAvailable) {
            Log.i(TAG, "[网络检查] 网络不可用，等待恢复... URL: $url")
            // 最多等待60秒（每秒检查一次）
            var waited = 0
            while (!networkAvailable && waited < 60) {
                delay(1000)
                waited++
            }
            if (!networkAvailable) {
                Log.w(TAG, "[网络检查] 等待超时，跳过: $url")
                return@withContext null
            }
            Log.i(TAG, "[网络检查] 网络已恢复，继续爬取")
        }

        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.instanceFollowRedirects = true

            val responseCode = connection.responseCode
            if (responseCode != 200) {
                connection.disconnect()
                return@withContext null
            }

            val contentType = connection.contentType ?: ""
            if (!contentType.contains("text") && !contentType.contains("html") && !contentType.contains("json")) {
                connection.disconnect()
                return@withContext null
            }

            // 读取HTML内容
            val rawHtml = readWithLimit(connection.inputStream, MAX_CONTENT_SIZE)
            connection.disconnect()

            // 根据配置提取内容
            val result = buildCrawlResult(rawHtml, url)
            result
        } catch (e: Exception) {
            Log.w(TAG, "抓取失败: $url - ${e.message}")
            null
        }
    }

    /**
     * 根据筛选配置构建抓取结果
     */
    private fun buildCrawlResult(html: String, url: String): CrawlResult {
        val filter = contentFilter

        // 提取标题
        val title = extractTitle(html)

        // 提取文字内容
        val textContent = if (filter.enableText) {
            extractTextContent(html)
        } else ""

        // 提取图片URL
        val imageUrls = if (filter.enableImages) {
            extractImageUrls(html, url)
        } else emptyList()

        // 提取视频URL
        val videoUrls = if (filter.enableVideos) {
            extractVideoUrls(html, url)
        } else emptyList()

        // 源代码
        val sourceCode = if (filter.enableSourceCode) {
            html
        } else ""

        return CrawlResult(
            url = url,
            textContent = textContent,
            imageUrls = imageUrls,
            videoUrls = videoUrls,
            sourceCode = sourceCode,
            title = title
        )
    }

    // ============ 内容提取 ============

    /**
     * 提取页面标题
     */
    private fun extractTitle(html: String): String {
        val match = Regex("""<title[^>]*>(.*?)</title>""", RegexOption.IGNORE_CASE).find(html)
        return match?.groupValues?.get(1)?.trim() ?: ""
    }

    /**
     * 提取正文文字内容
     *
     * 去噪算法：
     * 1. 移除 script/style/nav/footer/header/aside/iframe
     * 2. 提取 body
     * 3. 移除所有 HTML 标签
     * 4. 清理空白行和多余空格
     */
    private fun extractTextContent(html: String): String {
        var text = html

        // 移除不需要的标签块
        val removeTags = listOf(
            "script", "style", "nav", "footer", "header", "aside",
            "iframe", "noscript", "svg", "form", "button", "input",
            "select", "textarea"
        )
        for (tag in removeTags) {
            text = Regex("""<$tag[^>]*>[\s\S]*?</$tag>""", RegexOption.IGNORE_CASE).replace(text, "")
        }

        // 提取 body
        val bodyMatch = Regex("""<body[^>]*>([\s\S]*)</body>""", RegexOption.IGNORE_CASE).find(text)
        if (bodyMatch != null) {
            text = bodyMatch.groupValues[1]
        }

        // 移除所有标签
        text = Regex("""<[^>]+>""").replace(text, " ")

        // 解码HTML实体
        text = decodeHtmlEntities(text)

        // 清理空白
        text = text.lines().joinToString("\n") { it.trim() }.trim()
        text = Regex("[ \\t]+").replace(text, " ")
        text = Regex("\n{3,}").replace(text, "\n\n")

        return text
    }

    /**
     * 提取图片URL
     */
    private fun extractImageUrls(html: String, baseUrl: String): List<String> {
        val urls = mutableListOf<String>()
        // 匹配 img 标签的 src 属性
        val imgPattern = Regex("""<img[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        for (match in imgPattern.findAll(html)) {
            val src = match.groupValues[1]
            val fullUrl = resolveUrl(src, baseUrl)
            if (fullUrl != null) urls.add(fullUrl)
        }
        return urls.distinct()
    }

    /**
     * 提取视频URL
     */
    private fun extractVideoUrls(html: String, baseUrl: String): List<String> {
        val urls = mutableListOf<String>()

        // 匹配 video 标签的 src
        val videoPattern = Regex("""<video[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        for (match in videoPattern.findAll(html)) {
            val fullUrl = resolveUrl(match.groupValues[1], baseUrl)
            if (fullUrl != null) urls.add(fullUrl)
        }

        // 匹配 source 标签（在 video 内）
        val sourcePattern = Regex("""<source[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        for (match in sourcePattern.findAll(html)) {
            val fullUrl = resolveUrl(match.groupValues[1], baseUrl)
            if (fullUrl != null) urls.add(fullUrl)
        }

        return urls.distinct()
    }

    /**
     * 解析相对URL为绝对URL
     */
    private fun resolveUrl(src: String, baseUrl: String): String? {
        if (src.isBlank()) return null
        return try {
            URL(URL(baseUrl), src).toString()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 解码HTML实体
     */
    private fun decodeHtmlEntities(text: String): String {
        return text
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&mdash;", "—")
            .replace("&ndash;", "–")
            .replace("&hellip;", "…")
    }

    /**
     * 限制读取大小
     */
    private fun readWithLimit(inputStream: java.io.InputStream, maxBytes: Int): String {
        val buffer = ByteArrayOutputStream()
        val data = ByteArray(8192)
        var totalRead = 0
        var count: Int

        while (inputStream.read(data).also { count = it } != -1) {
            totalRead += count
            if (totalRead > maxBytes) {
                buffer.write(data, 0, count - (totalRead - maxBytes))
                break
            }
            buffer.write(data, 0, count)
        }
        inputStream.close()
        return buffer.toString(Charsets.UTF_8.name())
    }

    // ============ 学习提交 ============

    /**
     * 将抓取结果提交到学习流水线
     */
    fun submitLearnedContent(result: CrawlResult, url: String) {
        val content = buildString {
            if (result.title.isNotBlank()) {
                appendLine("【${result.title}】")
                appendLine()
            }
            if (result.textContent.isNotBlank()) {
                append(result.textContent)
            }
            if (result.imageUrls.isNotEmpty()) {
                appendLine()
                appendLine("--- 图片 ---")
                result.imageUrls.forEach { appendLine(it) }
            }
            if (result.videoUrls.isNotEmpty()) {
                appendLine()
                appendLine("--- 视频 ---")
                result.videoUrls.forEach { appendLine(it) }
            }
        }

        if (content.isBlank()) return

        val material = LearningMaterial(
            channel = ChannelType.WEB_SCRAPING,
            rawContent = content,
            source = url,
            metadata = mapOf(
                "title" to result.title,
                "images" to result.imageUrls.size.toString(),
                "videos" to result.videoUrls.size.toString()
            )
        )

        // 异步提交到流水线
        CoroutineScope(Dispatchers.IO).launch {
            pipeline?.processMaterial(material)
        }

        // 保存到本地
        saveLearnedContent(result)

        Log.d(TAG, "提交学习内容: $url (${content.length}字)")
    }

    /**
     * 保存已学习的内容到本地
     */
    private fun saveLearnedContent(result: CrawlResult) {
        try {
            val timestamp = System.currentTimeMillis()
            val filename = "crawl_${timestamp}_${result.url.hashCode().toUInt()}.dat"
            val file = File(learnedDir, filename)

            val content = buildString {
                appendLine("URL: ${result.url}")
                appendLine("标题: ${result.title}")
                appendLine("时间: ${result.crawlTime}")
                appendLine()
                appendLine("--- 正文 ---")
                appendLine(result.textContent.take(50000))
                if (result.imageUrls.isNotEmpty()) {
                    appendLine()
                    appendLine("--- 图片URL ---")
                    result.imageUrls.forEach { appendLine(it) }
                }
                if (result.videoUrls.isNotEmpty()) {
                    appendLine()
                    appendLine("--- 视频URL ---")
                    result.videoUrls.forEach { appendLine(it) }
                }
                if (result.sourceCode.isNotBlank()) {
                    appendLine()
                    appendLine("--- 源代码 ---")
                    appendLine(result.sourceCode.take(100000))
                }
            }

            file.writeText(content, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "保存学习内容失败: ${e.message}")
        }
    }
}
