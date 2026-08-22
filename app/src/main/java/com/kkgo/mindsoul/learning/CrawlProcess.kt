/*
 * ============================================================
 * CrawlProcess - 抓取多进程管理器
 * ============================================================
 *
 * 管理多个独立的网址抓取进程，每个进程独立运行：
 *
 * 1. 进程创建与参数配置
 *    - URL模板解析（识别{var}占位符）
 *    - 起始值/结束值自动推断递增规则
 *    - 支持纯数字、带前缀数字、混合字符、字母序列
 *
 * 2. 进程生命周期管理
 *    - 运行中 / 暂停 / 恢复 / 完成 / 错误
 *    - 最大并发限制（默认3个）
 *    - 完成后自动从列表消失
 *
 * 3. 进度跟踪
 *    - 当前值、总数、已完成数、百分比
 *    - 实时状态流
 *
 * 变动值设计（核心）：
 *   - 检测URL模板中的{var}占位符
 *   - 分析用户输入的起始/结束值
 *   - 自动推断递增规则：
 *     * 纯数字：0 → 99999
 *     * 带前缀数字：a001 → a999（自动识别前缀+数字部分）
 *     * 混合字符：page_001 → page_999
 *     * 字母序列：aaa → zzz
 * ============================================================
 */
package com.kkgo.mindsoul.learning

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 进程状态
 */
enum class CrawlProcessState {
    /** 等待中 */
    PENDING,
    /** 运行中 */
    RUNNING,
    /** 已暂停 */
    PAUSED,
    /** 已完成 */
    COMPLETED,
    /** 出错 */
    ERROR
}

/**
 * 变动值类型
 */
enum class VarType {
    /** 纯数字：0 → 99999 */
    PURE_NUMBER,
    /** 带前缀数字：a001 → a999 */
    PREFIX_NUMBER,
    /** 混合字符：page_001 → page_999 */
    MIXED,
    /** 字母序列：aaa → zzz */
    ALPHA_SEQUENCE
}

/**
 * 变动值解析结果
 */
data class VarPattern(
    /** 变动值类型 */
    val type: VarType,
    /** 前缀部分（如 "a"、"page_"） */
    val prefix: String = "",
    /** 起始数字值 */
    val startNum: Long = 0,
    /** 结束数字值 */
    val endNum: Long = 0,
    /** 数字部分的最小位数（补零用） */
    val numDigits: Int = 0,
    /** 起始字母序列 */
    val startAlpha: String = "",
    /** 结束字母序列 */
    val endAlpha: String = "",
    /** 后缀部分 */
    val suffix: String = ""
) {
    /**
     * 获取总数
     */
    fun total(): Long {
        return when (type) {
            VarType.ALPHA_SEQUENCE -> {
                // 计算字母序列的总数
                alphaToIndex(endAlpha) - alphaToIndex(startAlpha) + 1
            }
            else -> endNum - startNum + 1
        }
    }

    /**
     * 根据偏移量获取当前值
     * @param offset 从起始值开始的偏移量（0-based）
     */
    fun valueAt(offset: Long): String {
        return when (type) {
            VarType.PURE_NUMBER -> {
                (startNum + offset).toString()
            }
            VarType.PREFIX_NUMBER -> {
                val num = startNum + offset
                val numStr = num.toString().padStart(numDigits, '0')
                "$prefix$numStr$suffix"
            }
            VarType.MIXED -> {
                val num = startNum + offset
                val numStr = num.toString().padStart(numDigits, '0')
                "$prefix$numStr$suffix"
            }
            VarType.ALPHA_SEQUENCE -> {
                indexToAlpha(alphaToIndex(startAlpha) + offset)
            }
        }
    }

    /**
     * 字母序列转索引（a=0, b=1, ..., z=25, aa=26, ...）
     */
    private fun alphaToIndex(alpha: String): Long {
        if (alpha.isEmpty()) return 0
        var result = 0L
        for (c in alpha) {
            result = result * 26 + (c - 'a' + 1)
        }
        return result
    }

    /**
     * 索引转字母序列
     */
    private fun indexToAlpha(index: Long): String {
        if (index <= 0) return startAlpha
        var idx = index
        val len = startAlpha.length
        // 简化处理：固定长度字母序列
        val chars = CharArray(len)
        for (i in (len - 1) downTo 0) {
            chars[i] = ('a' + ((idx - 1) % 26).toInt()).also { idx = (idx - 1) / 26 }
        }
        return String(chars)
    }
}

/**
 * 抓取进程信息
 */
data class CrawlProcessInfo(
    /** 进程唯一ID */
    val id: String = System.nanoTime().toString(),
    /** URL模板（如 http://abc.com/a{var}.html） */
    val urlTemplate: String,
    /** 变动值模式 */
    val varPattern: VarPattern,
    /** 当前状态 */
    var state: CrawlProcessState = CrawlProcessState.PENDING,
    /** 当前进度偏移（0-based） */
    var currentOffset: Long = 0,
    /** 已抓取成功数 */
    var successCount: Long = 0,
    /** 失败数 */
    var failCount: Long = 0,
    /** 创建时间 */
    val createdAt: Long = System.currentTimeMillis(),
    /** 错误信息 */
    var errorMessage: String = "",
    /** 每次请求间隔（毫秒） */
    val intervalMs: Long = 200L,
    /** 随机延迟最小值（毫秒） */
    val randomDelayMin: Long = 10,
    /** 随机延迟最大值（毫秒） */
    val randomDelayMax: Long = 400,
    // ============ 学习统计（实时更新） ============
    /** 已学到的神经元数量 */
    var learnedNeurons: Int = 0,
    /** 已学到的关键词数量 */
    var learnedKeywords: Int = 0,
    /** 关键词条数量 */
    var learnedKeywordEntries: Int = 0,
    /** 概念节点数量 */
    var learnedConceptNodes: Int = 0,
    /** 概要搜引条数 */
    var learnedSummaryIndex: Int = 0
) {
    /** 总数 */
    val total: Long get() = varPattern.total()
    /** 当前值 */
    val currentValue: String get() = varPattern.valueAt(currentOffset)
    /** 当前URL */
    val currentUrl: String get() = urlTemplate.replace("{var}", currentValue)
    /** 进度百分比 */
    val progress: Float get() = if (total > 0) currentOffset.toFloat() / total else 0f
    /** 是否已结束 */
    val isFinished: Boolean get() = state == CrawlProcessState.COMPLETED || state == CrawlProcessState.ERROR
}

/**
 * 抓取多进程管理器
 */
class CrawlProcessManager {

    companion object {
        private const val TAG = "CrawlProcessMgr"
        /** 最大并发进程数 */
        const val MAX_CONCURRENT = 3
        /** 单次请求超时（毫秒） */
        const val REQUEST_TIMEOUT = 15_000
        /** 请求间隔（毫秒），避免过于频繁 */
        const val REQUEST_INTERVAL = 200L
    }

    // ============ 状态流 ============
    private val _processes = MutableStateFlow<List<CrawlProcessInfo>>(emptyList())
    val processesFlow: StateFlow<List<CrawlProcessInfo>> = _processes.asStateFlow()

    // ============ 进程Map ============
    private val processMap = mutableMapOf<String, CrawlProcessInfo>()
    private val processJobs = mutableMapOf<String, Job>()

    // ============ 并发控制 ============
    private val semaphore = Semaphore(MAX_CONCURRENT)

    // ============ 协程 ============
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ============ 抓取引擎引用 ============
    private var crawlEngine: WebCrawlEngine? = null

    // ============ 学习流水线引用（用于获取学习统计） ============
    private var learningPipeline: LearningPipeline? = null

    // ============ 持久化上下文 ============
    private var appContext: Context? = null

    /** 检查点文件名 */
    private val checkpointFileName = "crawl_checkpoint.json"

    /**
     * 设置抓取引擎
     */
    fun setCrawlEngine(engine: WebCrawlEngine) {
        this.crawlEngine = engine
    }

    /**
     * 设置学习流水线（用于获取实时学习统计）
     */
    fun setLearningPipeline(pipeline: LearningPipeline) {
        this.learningPipeline = pipeline
    }

    /**
     * 设置应用上下文（用于持久化）
     */
    fun setContext(context: Context) {
        this.appContext = context
    }

    // ============ 变动值自动推断 ============

    /**
     * 分析并推断变动值模式
     *
     * 根据起始值和结束值，自动推断：
     * - 变动值类型（纯数字/前缀数字/混合/字母序列）
     * - 前缀/后缀
     * - 数字位数（补零规则）
     *
     * @param startValue 起始值（如 "0"、"a001"、"page_001"、"aaa"）
     * @param endValue 结束值（如 "99999"、"a999"、"page_999"、"zzz"）
     * @return 解析出的变动值模式
     */
    fun analyzeVarPattern(startValue: String, endValue: String): VarPattern {
        // 情况1：纯数字
        if (startValue.all { it.isDigit() } && endValue.all { it.isDigit() }) {
            return VarPattern(
                type = VarType.PURE_NUMBER,
                startNum = startValue.toLongOrNull() ?: 0,
                endNum = endValue.toLongOrNull() ?: 0
            )
        }

        // 情况2：纯字母序列（a-z）
        if (startValue.all { it.isLetter() && it.isLowerCase() } &&
            endValue.all { it.isLetter() && it.isLowerCase() }) {
            return VarPattern(
                type = VarType.ALPHA_SEQUENCE,
                startAlpha = startValue,
                endAlpha = endValue
            )
        }

        // 情况3/4：带前缀数字 或 混合字符
        // 提取前缀（字母+符号部分）和数字部分
        val startParts = splitPrefixNumber(startValue)
        val endParts = splitPrefixNumber(endValue)

        if (startParts != null && endParts != null) {
            val (startPrefix, startNum, startDigits) = startParts
            val (endPrefix, endNum, endDigits) = endParts

            // 提取后缀
            val startSuffix = extractSuffix(startValue, startPrefix, startNum.toString().padStart(startDigits, '0'))
            val endSuffix = extractSuffix(endValue, endPrefix, endNum.toString().padStart(endDigits, '0'))

            // 判断是前缀数字还是混合
            val type = if (startPrefix.isNotEmpty() || endPrefix.isNotEmpty()) {
                VarType.PREFIX_NUMBER
            } else {
                VarType.MIXED
            }

            val maxDigits = maxOf(startDigits, endDigits)

            return VarPattern(
                type = type,
                prefix = startPrefix.ifEmpty { endPrefix },
                startNum = startNum,
                endNum = endNum,
                numDigits = maxDigits,
                suffix = startSuffix.ifEmpty { endSuffix }
            )
        }

        // 兜底：当作纯数字处理
        Log.w(TAG, "无法识别变动值模式，使用纯数字默认: start=$startValue, end=$endValue")
        return VarPattern(
            type = VarType.PURE_NUMBER,
            startNum = 0,
            endNum = 100
        )
    }

    /**
     * 拆分前缀和数字部分
     * 返回 (前缀, 数字, 数字位数) 或 null
     */
    private fun splitPrefixNumber(value: String): Triple<String, Long, Int>? {
        // 匹配模式：前缀（字母+符号） + 数字 + 可能的后缀
        val regex = Regex("""^([a-zA-Z_\-\.]*?)(\d+)(.*)$""")
        val match = regex.find(value) ?: return null

        val prefix = match.groupValues[1]
        val numStr = match.groupValues[2]
        val suffix = match.groupValues[3]

        val num = numStr.toLongOrNull() ?: return null
        val digits = numStr.length

        return Triple(prefix, num, digits)
    }

    /**
     * 提取后缀
     */
    private fun extractSuffix(full: String, prefix: String, numStr: String): String {
        val startIdx = prefix.length + numStr.length
        return if (startIdx < full.length) full.substring(startIdx) else ""
    }

    // ============ 进程管理 ============

    /**
     * 创建新抓取进程
     *
     * @param urlTemplate URL模板（含{var}占位符）
     * @param startValue 起始值
     * @param endValue 结束值
     * @param intervalMs 每次请求间隔（毫秒），默认200ms
     * @param randomDelayMin 随机延迟最小值（毫秒），默认10ms
     * @param randomDelayMax 随机延迟最大值（毫秒），默认400ms
     * @return 进程ID，失败返回null
     */
    fun createProcess(
        urlTemplate: String,
        startValue: String,
        endValue: String,
        intervalMs: Long = 200L,
        randomDelayMin: Long = 10,
        randomDelayMax: Long = 400
    ): String? {
        // 验证URL模板
        if (!urlTemplate.contains("{var}")) {
            Log.e(TAG, "URL模板缺少{var}占位符: $urlTemplate")
            return null
        }

        // 解析变动值模式
        val varPattern = analyzeVarPattern(startValue, endValue)
        val total = varPattern.total()

        if (total <= 0 || total > 1_000_000) {
            Log.e(TAG, "无效的抓取范围: total=$total")
            return null
        }

        val process = CrawlProcessInfo(
            urlTemplate = urlTemplate,
            varPattern = varPattern,
            intervalMs = intervalMs,
            randomDelayMin = randomDelayMin,
            randomDelayMax = randomDelayMax
        )

        processMap[process.id] = process
        notifyProcessesChanged()

        Log.i(TAG, "创建进程: ${process.id}, URL: $urlTemplate, 总数: $total, 类型: ${varPattern.type}, 间隔: ${intervalMs}ms, 随机延迟: ${randomDelayMin}-${randomDelayMax}ms")
        return process.id
    }

    /**
     * 启动进程
     */
    fun startProcess(processId: String) {
        val process = processMap[processId] ?: return
        if (process.state == CrawlProcessState.RUNNING) return

        process.state = CrawlProcessState.RUNNING
        notifyProcessesChanged()

        val job = scope.launch {
            semaphore.acquire() // 等待并发槽位
            try {
                runProcess(process)
            } finally {
                semaphore.release()
            }
        }
        processJobs[processId] = job
    }

    /**
     * 暂停进程
     */
    fun pauseProcess(processId: String) {
        val process = processMap[processId] ?: return
        if (process.state != CrawlProcessState.RUNNING) return

        process.state = CrawlProcessState.PAUSED
        processJobs[processId]?.cancel()
        processJobs.remove(processId)
        notifyProcessesChanged()

        Log.i(TAG, "暂停进程: $processId, 进度: ${process.currentOffset}/${process.total}")
    }

    /**
     * 恢复进程
     */
    fun resumeProcess(processId: String) {
        startProcess(processId)
    }

    /**
     * 删除进程
     */
    fun removeProcess(processId: String) {
        processJobs[processId]?.cancel()
        processJobs.remove(processId)
        processMap.remove(processId)
        notifyProcessesChanged()
    }

    /**
     * 删除所有已完成的进程
     */
    fun clearFinishedProcesses() {
        val finishedIds = processMap.filter { it.value.isFinished }.keys
        finishedIds.forEach { id ->
            processJobs.remove(id)
            processMap.remove(id)
        }
        if (finishedIds.isNotEmpty()) {
            notifyProcessesChanged()
            Log.i(TAG, "清理已完成进程: ${finishedIds.size} 个")
        }
    }

    /**
     * 获取所有进程
     */
    fun getAllProcesses(): List<CrawlProcessInfo> = processMap.values.toList()

    /**
     * 获取运行中的进程数
     */
    fun getRunningCount(): Int = processMap.values.count { it.state == CrawlProcessState.RUNNING }

    /**
     * 关闭所有进程
     */
    fun shutdown() {
        processJobs.values.forEach { it.cancel() }
        processJobs.clear()
        scope.cancel()
        Log.i(TAG, "抓取进程管理器已关闭")
    }

    // ============ 进程执行 ============

    /**
     * 执行抓取进程
     */
    private suspend fun runProcess(process: CrawlProcessInfo) {
        Log.i(TAG, "开始执行进程: ${process.id}, 从偏移 ${process.currentOffset} 继续")

        val total = process.total

        try {
            while (process.currentOffset < total && process.state == CrawlProcessState.RUNNING) {
                val url = process.currentUrl

                try {
                    val result = crawlSingleUrl(url)
                    if (result != null) {
                        // 成功抓取
                        process.successCount++
                        // 提交到学习流水线
                        crawlEngine?.submitLearnedContent(CrawlResult(url = url, textContent = result ?: ""), url)
                    } else {
                        process.failCount++
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    process.failCount++
                    Log.w(TAG, "抓取失败: $url - ${e.message}")
                }

                process.currentOffset++
                // 更新学习统计
                updateProcessLearnStats(process)
                notifyProcessesChanged()

                // 每10个URL保存一次检查点，防止进度丢失
                if (process.currentOffset % 10 == 0L) {
                    saveCheckpoint()
                }

                // 请求间隔 + 随机延迟，避免过于频繁
                val randomDelay = if (process.randomDelayMax > process.randomDelayMin) {
                    (process.randomDelayMin..process.randomDelayMax).random()
                } else {
                    process.randomDelayMin
                }
                delay(process.intervalMs + randomDelay)
            }

            // 完成
            if (process.currentOffset >= total) {
                process.state = CrawlProcessState.COMPLETED
                Log.i(TAG, "进程完成: ${process.id}, 成功: ${process.successCount}, 失败: ${process.failCount}")
            }
        } catch (e: CancellationException) {
            // 被取消（暂停或移除）
            if (process.state == CrawlProcessState.RUNNING) {
                process.state = CrawlProcessState.PAUSED
            }
            throw e
        } catch (e: Exception) {
            process.state = CrawlProcessState.ERROR
            process.errorMessage = e.message ?: "未知错误"
            Log.e(TAG, "进程错误: ${process.id} - ${e.message}")
        }

        notifyProcessesChanged()
    }

    /**
     * 更新进程的学习统计数据
     *
     * 从 LearningPipeline 获取全局学习统计增量，
     * 按比例分配到当前进程。
     */
    private fun updateProcessLearnStats(process: CrawlProcessInfo) {
        val pipeline = learningPipeline ?: return
        try {
            val pipelineStats = pipeline.getStats()
            // 根据当前进程的成功抓取数，估算学习统计
            // 每次成功抓取的页面平均贡献的统计数据
            val processedPages = process.successCount.coerceAtLeast(1)
            process.learnedNeurons = (pipelineStats.totalEncoded / processedPages * process.successCount).toInt().coerceAtLeast(process.successCount.toInt())
            process.learnedKeywords = (pipelineStats.totalCausalExtracted / processedPages.coerceAtLeast(1) * process.successCount).toInt()
            process.learnedKeywordEntries = pipelineStats.totalCausalExtracted.toInt()
            process.learnedConceptNodes = pipelineStats.totalArchived.toInt()
            process.learnedSummaryIndex = pipelineStats.totalInput.toInt()
        } catch (e: Exception) {
            // 静默处理
        }
    }

    /**
     * 抓取单个URL
     *
     * @return 抓取到的文本内容，失败返回null
     */
    private suspend fun crawlSingleUrl(url: String): String? = withContext(Dispatchers.IO) {
        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = REQUEST_TIMEOUT
            connection.readTimeout = REQUEST_TIMEOUT
            connection.setRequestProperty("User-Agent", "MindSoul/1.0 (Android; AGI Learning)")
            connection.instanceFollowRedirects = true

            val responseCode = connection.responseCode
            if (responseCode != 200) {
                connection.disconnect()
                return@withContext null
            }

            val contentType = connection.contentType ?: ""
            // 只处理文本类型
            if (!contentType.contains("text") && !contentType.contains("html") && !contentType.contains("json")) {
                connection.disconnect()
                return@withContext null
            }

            val html = connection.inputStream.bufferedReader(Charsets.UTF_8).readText()
            connection.disconnect()

            // 提取正文
            extractTextFromHtml(html)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 从HTML中提取纯文本
     */
    private fun extractTextFromHtml(html: String): String {
        var text = html
        // 移除脚本、样式等
        val removeTags = listOf("script", "style", "nav", "footer", "header", "aside", "iframe", "noscript", "svg")
        for (tag in removeTags) {
            text = Regex("""<$tag[^>]*>[\s\S]*?</$tag>""", RegexOption.IGNORE_CASE).replace(text, "")
        }
        // 提取body
        val bodyMatch = Regex("""<body[^>]*>([\s\S]*)</body>""", RegexOption.IGNORE_CASE).find(text)
        if (bodyMatch != null) {
            text = bodyMatch.groupValues[1]
        }
        // 移除所有标签
        text = Regex("""<[^>]+>""").replace(text, " ")
        // 解码HTML实体
        text = text.replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
        // 清理空白
        text = text.lines().joinToString("\n") { it.trim() }.trim()
        text = Regex("[ \\t]+").replace(text, " ")
        text = Regex("\n{3,}").replace(text, "\n\n")
        return text
    }

    /**
     * 通知进程列表变化
     */
    private fun notifyProcessesChanged() {
        _processes.value = processMap.values.toList()
    }

    // ============ 任务持久化（检查点） ============

    /**
     * 保存当前爬取任务队列到磁盘（JSON序列化）
     *
     * 序列化内容包括：
     * - 每个进程的URL模板、变动值模式、当前偏移、状态
     * - 已完成列表和待处理列表
     *
     * 在爬取过程中定期调用，确保进度不丢失。
     */
    fun saveCheckpoint() {
        val ctx = appContext ?: return
        try {
            val processes = processMap.values.toList()
            if (processes.isEmpty()) return

            val jsonArray = JSONArray()
            for (process in processes) {
                // 只保存未完成的任务
                if (process.isFinished) continue

                val json = JSONObject().apply {
                    put("id", process.id)
                    put("urlTemplate", process.urlTemplate)
                    put("currentOffset", process.currentOffset)
                    put("successCount", process.successCount)
                    put("failCount", process.failCount)
                    put("state", process.state.name)
                    put("intervalMs", process.intervalMs)
                    put("randomDelayMin", process.randomDelayMin)
                    put("randomDelayMax", process.randomDelayMax)
                    put("createdAt", process.createdAt)

                    // 保存 VarPattern
                    val varJson = JSONObject().apply {
                        put("type", process.varPattern.type.name)
                        put("prefix", process.varPattern.prefix)
                        put("suffix", process.varPattern.suffix)
                        put("startNum", process.varPattern.startNum)
                        put("endNum", process.varPattern.endNum)
                        put("numDigits", process.varPattern.numDigits)
                        put("startAlpha", process.varPattern.startAlpha)
                        put("endAlpha", process.varPattern.endAlpha)
                    }
                    put("varPattern", varJson)
                }
                jsonArray.put(json)
            }

            val checkpointJson = JSONObject().apply {
                put("version", 1)
                put("timestamp", System.currentTimeMillis())
                put("processes", jsonArray)
            }

            val file = File(ctx.filesDir, checkpointFileName)
            file.writeText(checkpointJson.toString(2), Charsets.UTF_8)
            Log.i(TAG, "[检查点] 已保存 ${jsonArray.length()} 个未完成任务到磁盘")
        } catch (e: Exception) {
            Log.w(TAG, "[检查点] 保存失败: ${e.message}")
        }
    }

    /**
     * 从磁盘加载上次未完成的爬取任务继续
     *
     * App重启后调用，恢复上次未完成的爬取任务。
     * 加载后任务状态设为 PAUSED，需要用户手动恢复或由服务自动恢复。
     */
    fun loadCheckpoint() {
        val ctx = appContext ?: return
        try {
            val file = File(ctx.filesDir, checkpointFileName)
            if (!file.exists()) {
                Log.d(TAG, "[检查点] 无历史检查点文件")
                return
            }

            val content = file.readText(Charsets.UTF_8)
            val checkpointJson = JSONObject(content)

            val version = checkpointJson.optInt("version", 0)
            if (version < 1) {
                Log.w(TAG, "[检查点] 不支持的检查点版本: $version")
                return
            }

            val timestamp = checkpointJson.optLong("timestamp", 0)
            val ageHours = (System.currentTimeMillis() - timestamp) / (1000 * 60 * 60)
            if (ageHours > 72) {
                Log.w(TAG, "[检查点] 检查点已超过72小时，放弃恢复")
                file.delete()
                return
            }

            val processesArray = checkpointJson.getJSONArray("processes")
            var loadedCount = 0

            for (i in 0 until processesArray.length()) {
                val json = processesArray.getJSONObject(i)

                val urlTemplate = json.optString("urlTemplate", "")
                if (urlTemplate.isBlank()) continue

                // 恢复 VarPattern
                val varJson = json.getJSONObject("varPattern")
                val varType = VarType.valueOf(varJson.optString("type", "PURE_NUMBER"))
                val varPattern = VarPattern(
                    type = varType,
                    prefix = varJson.optString("prefix", ""),
                    suffix = varJson.optString("suffix", ""),
                    startNum = varJson.optLong("startNum", 0),
                    endNum = varJson.optLong("endNum", 0),
                    numDigits = varJson.optInt("numDigits", 0),
                    startAlpha = varJson.optString("startAlpha", ""),
                    endAlpha = varJson.optString("endAlpha", "")
                )

                val total = varPattern.total()
                val currentOffset = json.optLong("currentOffset", 0)

                // 跳过已完成的
                if (currentOffset >= total) continue

                val process = CrawlProcessInfo(
                    id = json.optString("id", System.nanoTime().toString()),
                    urlTemplate = urlTemplate,
                    varPattern = varPattern,
                    state = CrawlProcessState.PAUSED, // 加载后统一暂停，等待恢复
                    currentOffset = currentOffset,
                    successCount = json.optLong("successCount", 0),
                    failCount = json.optLong("failCount", 0),
                    createdAt = json.optLong("createdAt", System.currentTimeMillis()),
                    intervalMs = json.optLong("intervalMs", REQUEST_INTERVAL),
                    randomDelayMin = json.optLong("randomDelayMin", 10),
                    randomDelayMax = json.optLong("randomDelayMax", 400)
                )

                processMap[process.id] = process
                loadedCount++
            }

            if (loadedCount > 0) {
                notifyProcessesChanged()
                Log.i(TAG, "[检查点] 已恢复 $loadedCount 个未完成任务（创建于${ageHours}小时前）")
            }
        } catch (e: Exception) {
            Log.w(TAG, "[检查点] 加载失败: ${e.message}")
        }
    }
}
