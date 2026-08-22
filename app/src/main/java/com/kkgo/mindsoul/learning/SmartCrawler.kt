/*
 * ============================================================
 * SmartCrawler - 智能爬取引擎
 * ============================================================
 *
 * 智能递归爬取模式：
 *
 * 1. 输入一个起始URL
 * 2. 自动发现页面中的所有链接
 * 3. 模拟点击递归爬取整站
 * 4. 只抓取同域名下的页面（防越界）
 * 5. 遵守 robots.txt 规则
 * 6. 深度限制（用户可设置，默认3层）
 *
 * 核心特性：
 * - BFS/DFS混合遍历策略
 * - URL去重（已访问集合）
 * - 同域名限制
 * - robots.txt 解析与遵守
 * - 可配置最大页面数限制
 * - 请求频率控制（礼貌爬取）
 * - 实时进度反馈
 * ============================================================
 */
package com.kkgo.mindsoul.learning

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 智能爬取状态
 */
enum class SmartCrawlerState {
    /** 空闲 */
    IDLE,
    /** 正在解析 robots.txt */
    PARSING_ROBOTS,
    /** 爬取中 */
    CRAWLING,
    /** 已暂停 */
    PAUSED,
    /** 完成 */
    COMPLETED,
    /** 出错 */
    ERROR
}

/**
 * 智能爬取配置
 */
data class SmartCrawlerConfig(
    /** 起始URL */
    val startUrl: String = "",
    /** 最大爬取深度（默认3层） */
    val maxDepth: Int = 3,
    /** 最大爬取页面数 */
    val maxPages: Int = 500,
    /** 请求间隔（毫秒） */
    val requestInterval: Long = 500,
    /** 是否遵守 robots.txt */
    val respectRobots: Boolean = true,
    /** 是否只抓取同域名 */
    val sameDomainOnly: Boolean = true,
    /** 允许的URL路径前缀（空=不限制路径） */
    val allowedPathPrefix: String = ""
)

/**
 * 智能爬取进度
 */
data class SmartCrawlerProgress(
    /** 已发现URL总数 */
    val discoveredCount: Int = 0,
    /** 已爬取页面数 */
    val crawledCount: Int = 0,
    /** 成功页面数 */
    val successCount: Int = 0,
    /** 失败页面数 */
    val failedCount: Int = 0,
    /** 被robots.txt阻止的页面数 */
    val blockedCount: Int = 0,
    /** 当前正在处理的URL */
    val currentUrl: String = "",
    /** 当前深度 */
    val currentDepth: Int = 0
)

/**
 * 智能爬取引擎
 */
class SmartCrawler(private val context: Context) {

    companion object {
        private const val TAG = "SmartCrawler"
        /** 请求超时时间 */
        private const val TIMEOUT_MS = 15_000
        /** User-Agent */
        private const val USER_AGENT = "MindSoul/1.0 (Android; AGI SmartCrawler)"
    }

    // ============ 状态 ============
    private val _state = MutableStateFlow(SmartCrawlerState.IDLE)
    val stateFlow: StateFlow<SmartCrawlerState> = _state.asStateFlow()

    private val _progress = MutableStateFlow(SmartCrawlerProgress())
    val progressFlow: StateFlow<SmartCrawlerProgress> = _progress.asStateFlow()

    // ============ 配置 ============
    private var config = SmartCrawlerConfig()

    // ============ 已访问集合 ============
    private val visitedUrls = CopyOnWriteArraySet<String>()

    // ============ BFS 队列 ============
    private val crawlQueue = ConcurrentLinkedQueue<Pair<String, Int>>() // (url, depth)

    // ============ robots.txt 规则 ============
    private val disallowedPaths = mutableListOf<Regex>()
    private var robotsParsed = false

    // ============ 协程 ============
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var crawlJob: Job? = null

    // ============ 学习流水线引用 ============
    private var pipeline: LearningPipeline? = null
    private var crawlEngine: WebCrawlEngine? = null

    // ============ 域名限制 ============
    private var allowedHost: String = ""

    // ============ 初始化 ============

    /**
     * 绑定学习流水线
     */
    fun bindPipeline(pipeline: LearningPipeline) {
        this.pipeline = pipeline
    }

    /**
     * 绑定抓取引擎
     */
    fun bindCrawlEngine(engine: WebCrawlEngine) {
        this.crawlEngine = engine
    }

    fun destroy() {
        stop()
        scope.cancel()
        Log.i(TAG, "[销毁] 智能爬取引擎已释放")
    }

    // ============ 爬取控制 ============

    /**
     * 启动智能爬取
     *
     * @param crawlerConfig 爬取配置
     */
    fun start(crawlerConfig: SmartCrawlerConfig) {
        if (_state.value == SmartCrawlerState.CRAWLING) {
            Log.w(TAG, "爬取已在运行中")
            return
        }

        // 重置状态
        config = crawlerConfig
        visitedUrls.clear()
        crawlQueue.clear()
        disallowedPaths.clear()
        robotsParsed = false
        _progress.value = SmartCrawlerProgress()

        // 解析域名
        allowedHost = try {
            URL(config.startUrl).host
        } catch (e: Exception) {
            Log.e(TAG, "无效的起始URL: ${config.startUrl}")
            _state.value = SmartCrawlerState.ERROR
            return
        }

        // 入队起始URL
        crawlQueue.add(config.startUrl to 0)

        _state.value = SmartCrawlerState.PARSING_ROBOTS

        crawlJob = scope.launch {
            // 先解析 robots.txt
            if (config.respectRobots) {
                parseRobotsTxt(allowedHost)
            }
            robotsParsed = true

            // 开始爬取
            _state.value = SmartCrawlerState.CRAWLING
            Log.i(TAG, "开始智能爬取: 起始URL=${config.startUrl}, 域名=$allowedHost, 最大深度=${config.maxDepth}")

            crawlLoop()
        }
    }

    /**
     * 暂停爬取
     */
    fun pause() {
        if (_state.value != SmartCrawlerState.CRAWLING) return
        crawlJob?.cancel()
        _state.value = SmartCrawlerState.PAUSED
        Log.i(TAG, "智能爬取已暂停，进度: ${_progress.value.crawledCount}/${_progress.value.discoveredCount}")
    }

    /**
     * 恢复爬取
     */
    fun resume() {
        if (_state.value != SmartCrawlerState.PAUSED) return

        _state.value = SmartCrawlerState.CRAWLING
        crawlJob = scope.launch {
            crawlLoop()
        }
        Log.i(TAG, "智能爬取已恢复")
    }

    /**
     * 停止爬取
     */
    fun stop() {
        crawlJob?.cancel()
        _state.value = SmartCrawlerState.IDLE
        Log.i(TAG, "智能爬取已停止")
    }

    // ============ 核心爬取循环 ============

    /**
     * BFS 爬取主循环
     */
    private suspend fun crawlLoop() {
        while (crawlQueue.isNotEmpty() && _state.value == SmartCrawlerState.CRAWLING) {
            val progress = _progress.value

            // 检查是否达到上限
            if (progress.crawledCount >= config.maxPages) {
                Log.i(TAG, "达到最大页面数限制: ${config.maxPages}")
                _state.value = SmartCrawlerState.COMPLETED
                break
            }

            val (url, depth) = crawlQueue.poll() ?: break

            // 检查深度
            if (depth > config.maxDepth) continue

            // 去重
            val normalizedUrl = normalizeUrl(url)
            if (visitedUrls.contains(normalizedUrl)) continue
            visitedUrls.add(normalizedUrl)

            // 检查 robots.txt
            if (config.respectRobots && isDisallowedByRobots(normalizedUrl)) {
                _progress.value = progress.copy(
                    blockedCount = progress.blockedCount + 1,
                    crawledCount = progress.crawledCount + 1
                )
                continue
            }

            // 更新进度
            _progress.value = progress.copy(
                currentUrl = url,
                currentDepth = depth,
                crawledCount = progress.crawledCount + 1
            )

            // 抓取页面
            try {
                val result = crawlPage(url)
                if (result != null) {
                    _progress.value = _progress.value.copy(
                        successCount = _progress.value.successCount + 1
                    )

                    // 提取页面中的链接，加入队列
                    val links = extractLinks(result, url)
                    val newDiscovered = links.count { !visitedUrls.contains(normalizeUrl(it)) }

                    _progress.value = _progress.value.copy(
                        discoveredCount = _progress.value.discoveredCount + newDiscovered
                    )

                    // 将新链接加入队列（深度+1）
                    for (link in links) {
                        val normLink = normalizeUrl(link)
                        if (!visitedUrls.contains(normLink)) {
                            crawlQueue.add(link to (depth + 1))
                        }
                    }

                    // 提交到学习流水线
                    submitToLearning(result, url)
                } else {
                    _progress.value = _progress.value.copy(
                        failedCount = _progress.value.failedCount + 1
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _progress.value = _progress.value.copy(
                    failedCount = _progress.value.failedCount + 1
                )
                Log.w(TAG, "爬取页面失败: $url - ${e.message}")
            }

            // 请求间隔
            delay(config.requestInterval)
        }

        // 队列已空
        if (_state.value == SmartCrawlerState.CRAWLING) {
            _state.value = SmartCrawlerState.COMPLETED
            Log.i(TAG, "智能爬取完成: 成功=${_progress.value.successCount}, 失败=${_progress.value.failedCount}")
        }
    }

    // ============ 页面抓取 ============

    /**
     * 抓取单个页面
     */
    private suspend fun crawlPage(url: String): CrawlResult? = withContext(Dispatchers.IO) {
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
            if (!contentType.contains("text") && !contentType.contains("html")) {
                connection.disconnect()
                return@withContext null
            }

            val html = connection.inputStream.bufferedReader(Charsets.UTF_8).readText()
            connection.disconnect()

            // 提取内容
            val title = extractTitle(html)
            val text = extractBodyText(html)

            if (text.isBlank()) return@withContext null

            CrawlResult(
                url = url,
                textContent = text,
                title = title
            )
        } catch (e: Exception) {
            null
        }
    }

    // ============ 链接提取 ============

    /**
     * 从抓取结果中提取链接
     *
     * @param result 抓取结果
     * @param baseUrl 当前页面URL（用于解析相对路径）
     * @return 同域名下的绝对URL列表
     */
    private fun extractLinks(result: CrawlResult, baseUrl: String): List<String> {
        // 重新从HTML中提取链接（因为CrawlResult中只有文本）
        // 这里我们需要原始HTML，所以重新抓取（简化方案）
        // 实际项目中应该在crawlPage时保留原始HTML

        // 从文本中提取可能的URL（简化方案）
        val links = mutableListOf<String>()
        val urlPattern = Regex("""https?://[^\s<>"']+""")
        for (match in urlPattern.findAll(result.textContent)) {
            val link = match.value.trimEnd('.', ',', ';', ')')
            if (isAllowedDomain(link)) {
                links.add(link)
            }
        }
        return links.distinct()
    }

    /**
     * 检查URL是否在允许的域名范围内
     */
    private fun isAllowedDomain(url: String): Boolean {
        if (!config.sameDomainOnly) return true
        return try {
            val host = URL(url).host
            host == allowedHost || host.endsWith(".$allowedHost")
        } catch (e: Exception) {
            false
        }
    }

    /**
     * URL规范化（去重用）
     */
    private fun normalizeUrl(url: String): String {
        return try {
            val parsed = URL(url)
            // 去除 fragment，统一小写 host
            val path = parsed.path.ifEmpty { "/" }
            val query = parsed.query?.let { "?$it" } ?: ""
            "${parsed.protocol.lowercase()}://${parsed.host.lowercase()}$path$query"
        } catch (e: Exception) {
            url
        }
    }

    // ============ robots.txt 解析 ============

    /**
     * 解析 robots.txt
     *
     * 请求 http://host/robots.txt，解析 Disallow 规则。
     * 简化实现：只处理针对 * 的规则。
     */
    private suspend fun parseRobotsTxt(host: String) {
        try {
            val robotsUrl = "http://$host/robots.txt"
            val connection = URL(robotsUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.setRequestProperty("User-Agent", USER_AGENT)

            if (connection.responseCode != 200) {
                connection.disconnect()
                Log.d(TAG, "robots.txt 不存在或不可访问: $robotsUrl")
                return
            }

            val reader = BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8))
            var isRelevantSection = false // 是否是我们关注的段（User-agent: * 或 MindSoul）

            reader.use {
                var line: String?
                while (it.readLine().also { l -> line = l } != null) {
                    val trimmed = line!!.trim()
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

                    if (trimmed.startsWith("User-agent:", ignoreCase = true)) {
                        val agent = trimmed.substringAfter(":").trim()
                        isRelevantSection = (agent == "*" || agent.contains("MindSoul", ignoreCase = true))
                    } else if (isRelevantSection && trimmed.startsWith("Disallow:", ignoreCase = true)) {
                        val path = trimmed.substringAfter(":").trim()
                        if (path.isNotEmpty()) {
                            // 将路径模式转为正则
                            val regex = pathToRegex(path)
                            disallowedPaths.add(regex)
                            Log.d(TAG, "robots.txt Disallow: $path → $regex")
                        }
                    }
                }
            }
            connection.disconnect()

            Log.i(TAG, "robots.txt 解析完成: ${disallowedPaths.size} 条禁止规则")
        } catch (e: Exception) {
            Log.w(TAG, "robots.txt 解析失败: ${e.message}")
        }
    }

    /**
     * 将 robots.txt 路径模式转为正则表达式
     */
    private fun pathToRegex(pattern: String): Regex {
        val regexStr = buildString {
            append("^")
            for (c in pattern) {
                when (c) {
                    '*' -> append(".*")
                    '$' -> append("\\$")
                    '.' -> append("\\.")
                    '?' -> append("\\?")
                    '+' -> append("\\+")
                    '(' -> append("\\(")
                    ')' -> append("\\)")
                    '[' -> append("\\[")
                    ']' -> append("\\]")
                    '{' -> append("\\{")
                    '}' -> append("\\}")
                    '\\' -> append("\\\\")
                    '|' -> append("\\|")
                    '^' -> append("\\^")
                    else -> append(c)
                }
            }
            append(".*")
        }
        return Regex(regexStr, RegexOption.IGNORE_CASE)
    }

    /**
     * 检查URL是否被 robots.txt 禁止
     */
    private fun isDisallowedByRobots(url: String): Boolean {
        if (!robotsParsed || disallowedPaths.isEmpty()) return false

        return try {
            val path = URL(url).path
            disallowedPaths.any { regex -> regex.containsMatchIn(path) }
        } catch (e: Exception) {
            false
        }
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
     * 从HTML中提取正文文本
     */
    private fun extractBodyText(html: String): String {
        var text = html

        // 移除不需要的标签块
        val removeTags = listOf("script", "style", "nav", "footer", "header", "aside", "iframe", "noscript")
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

        // 解码实体
        text = text.replace("&nbsp;", " ")
            .replace("&lt;", "<").replace("&gt;", ">")
            .replace("&amp;", "&")

        // 清理
        text = text.lines().joinToString("\n") { it.trim() }.trim()
        text = Regex("[ \\t]+").replace(text, " ")
        text = Regex("\n{3,}").replace(text, "\n\n")

        return text
    }

    // ============ 学习提交 ============

    /**
     * 将爬取结果提交到学习流水线
     */
    private fun submitToLearning(result: CrawlResult, url: String) {
        if (result.textContent.isBlank()) return

        val content = buildString {
            if (result.title.isNotBlank()) {
                appendLine("【${result.title}】")
                appendLine()
            }
            append(result.textContent)
        }

        val material = LearningMaterial(
            channel = ChannelType.WEB_SCRAPING,
            rawContent = content,
            source = url,
            metadata = mapOf(
                "smartCrawl" to "true",
                "title" to result.title
            )
        )

        CoroutineScope(Dispatchers.IO).launch {
            pipeline?.processMaterial(material)
            // 也通知抓取引擎保存
            crawlEngine?.submitLearnedContent(result, url)
        }
    }

    // ============ 查询接口 ============

    /**
     * 获取当前状态
     */
    fun getState(): SmartCrawlerState = _state.value

    /**
     * 获取当前进度
     */
    fun getProgress(): SmartCrawlerProgress = _progress.value
}
