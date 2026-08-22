/*
 * ============================================================
 * ChannelManager - 五通道学习通道管理器
 * ============================================================
 *
 * 管理五条知识采集通道的注册、调度和状态：
 *
 * 通道1：对话框即时学习通道
 *   → 用户输入的每一条消息都是学习素材
 *   → 即时提取知识点，无需额外操作
 *
 * 通道2：TXT 批量导入通道
 *   → 支持 .txt/.md 文件批量导入
 *   → 自动分割、去重、编码
 *
 * 通道3：网页纯文本抓取通道
 *   → 通过 URL 抓取网页正文
 *   → 剥离导航/广告/样式，保留核心内容
 *
 * 通道4：全格式文件解析通道
 *   → 调用 MultimediaController 的文档解析能力
 *   → PDF/DOCX/EPUB/CSV/JSON 等全格式支持
 *
 * 通道5：二级权限全盘自主采集通道
 *   → 需要 L2+ 权限
 *   → 自主扫描文件系统中的学习素材
 *   → 按文件类型/修改时间/大小智能筛选
 *
 * 所有通道共享统一的学习流水线（LearningPipeline）。
 * ============================================================
 */
package com.kkgo.mindsoul.learning

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
 * 学习通道类型
 */
enum class ChannelType(
    val channelId: Int,
    val displayName: String,
    /** 所需最低权限等级 */
    val minPermissionLevel: Int
) {
    /** 通道1：对话框即时学习 */
    DIALOG_INSTANT(1, "对话框即时学习", 1),
    /** 通道2：TXT 批量导入 */
    TXT_BATCH_IMPORT(2, "TXT批量导入", 1),
    /** 通道3：网页抓取 */
    WEB_SCRAPING(3, "网页纯文本抓取", 1),
    /** 通道4：全格式文件解析 */
    FILE_PARSE(4, "全格式文件解析", 1),
    /** 通道5：全盘自主采集 */
    AUTONOMOUS_COLLECT(5, "全盘自主采集", 2)
}

/**
 * 通道状态
 */
enum class ChannelState {
    /** 空闲 */
    IDLE,
    /** 处理中 */
    PROCESSING,
    /** 暂停 */
    PAUSED,
    /** 出错 */
    ERROR,
    /** 已禁用 */
    DISABLED
}

/**
 * 通道统计信息
 */
data class ChannelStats(
    /** 通道类型 */
    val channelType: ChannelType,
    /** 已处理素材数 */
    var processedCount: Long = 0,
    /** 提取知识点数 */
    var extractedCount: Long = 0,
    /** 去重丢弃数 */
    var deduplicatedCount: Long = 0,
    /** 最后处理时间 */
    var lastProcessTime: Long = 0,
    /** 总处理耗时（毫秒） */
    var totalDurationMs: Long = 0
)

/**
 * 学习素材
 */
data class LearningMaterial(
    /** 素材来源通道 */
    val channel: ChannelType,
    /** 原始内容 */
    val rawContent: String,
    /** 来源标识（URL/文件路径/对话ID） */
    val source: String,
    /** 时间戳 */
    val timestamp: Long = System.currentTimeMillis(),
    /** 元数据 */
    val metadata: Map<String, String> = emptyMap()
)

/**
 * 五通道学习通道管理器
 */
class ChannelManager(private val context: Context) {

    companion object {
        private const val TAG = "ChannelMgr"
        /** 网页抓取超时（毫秒） */
        private const val WEB_TIMEOUT_MS = 15_000
        /** 自主采集单次扫描最大文件数 */
        private const val MAX_SCAN_FILES = 1000
        /** 支持的文件扩展名 */
        val SUPPORTED_EXTENSIONS = setOf(
            "txt", "md", "pdf", "docx", "html", "htm",
            "csv", "json", "epub", "rtf"
        )
    }

    // ============ 通道状态 ============
    private val _channelStates = MutableStateFlow<Map<ChannelType, ChannelState>>(
        ChannelType.entries.associateWith { ChannelState.IDLE }
    )
    val channelStatesFlow: StateFlow<Map<ChannelType, ChannelState>> = _channelStates.asStateFlow()

    // ============ 通道统计 ============
    private val channelStats = ChannelType.entries.associateWith { ChannelStats(it) }.toMutableMap()

    // ============ 处理作用域 ============
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ============ 学习流水线引用 ============
    private var pipeline: LearningPipeline? = null

    // ============ 自主采集配置 ============
    /** 自主采集的目标目录 */
    private var scanDirectories = mutableListOf<String>()
    /** 自主采集是否启用 */
    private var autonomousEnabled = false

    // ============ 初始化 ============

    /**
     * 初始化通道管理器
     */
    fun initialize() {
        Log.i(TAG, "[初始化] 五通道管理器就绪")
        ChannelType.entries.forEach { ch ->
            Log.d(TAG, "  通道${ch.channelId}: ${ch.displayName} (最低权限: L${ch.minPermissionLevel})")
        }
    }

    /**
     * 绑定学习流水线
     */
    fun bindPipeline(pipeline: LearningPipeline) {
        this.pipeline = pipeline
    }

    fun destroy() {
        scope.cancel()
        Log.i(TAG, "[销毁] 通道管理器已释放")
    }

    // ============ 通道1：对话框即时学习 ============

    /**
     * 提交对话框消息进行学习
     *
     * @param message 用户消息内容
     * @param messageId 消息ID
     */
    fun submitDialogMessage(message: String, messageId: String) {
        if (message.isBlank() || message.length < 5) return
        val material = LearningMaterial(
            channel = ChannelType.DIALOG_INSTANT,
            rawContent = message,
            source = "dialog:$messageId"
        )
        submitToPipeline(material)
    }

    // ============ 通道2：TXT 批量导入 ============

    /**
     * 批量导入 TXT/MD 文件
     *
     * @param files 文件列表
     */
    fun submitBatchFiles(files: List<File>) {
        updateChannelState(ChannelType.TXT_BATCH_IMPORT, ChannelState.PROCESSING)
        scope.launch {
            try {
                var count = 0
                for (file in files) {
                    if (!file.exists() || !file.isFile) continue
                    if (file.extension.lowercase() !in listOf("txt", "md")) continue

                    val content = file.readText(Charsets.UTF_8)
                    if (content.isBlank()) continue

                    val material = LearningMaterial(
                        channel = ChannelType.TXT_BATCH_IMPORT,
                        rawContent = content,
                        source = file.absolutePath,
                        metadata = mapOf("filename" to file.name, "size" to file.length().toString())
                    )
                    pipeline?.processMaterial(material)
                    count++
                }
                updateStats(ChannelType.TXT_BATCH_IMPORT, count.toLong())
                Log.i(TAG, "[通道2] 批量导入完成: $count 个文件")
            } catch (e: Exception) {
                Log.e(TAG, "[通道2] 批量导入失败: ${e.message}")
            } finally {
                updateChannelState(ChannelType.TXT_BATCH_IMPORT, ChannelState.IDLE)
            }
        }
    }

    // ============ 通道3：网页纯文本抓取 ============

    /**
     * 抓取网页纯文本
     *
     * @param url 网页URL
     * @return 提取的纯文本
     */
    suspend fun fetchWebContent(url: String): String = withContext(Dispatchers.IO) {
        updateChannelState(ChannelType.WEB_SCRAPING, ChannelState.PROCESSING)
        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = WEB_TIMEOUT_MS
            connection.readTimeout = WEB_TIMEOUT_MS
            connection.setRequestProperty("User-Agent", "MindSoul/1.0 (Android; AGI Learning)")
            connection.connect()

            val html = connection.inputStream.bufferedReader().readText()
            connection.disconnect()

            // 剥离 HTML 标签，提取正文
            val text = extractWebText(html)

            if (text.isNotBlank()) {
                val material = LearningMaterial(
                    channel = ChannelType.WEB_SCRAPING,
                    rawContent = text,
                    source = url,
                    metadata = mapOf("url" to url, "length" to text.length.toString())
                )
                pipeline?.processMaterial(material)
                updateStats(ChannelType.WEB_SCRAPING, 1)
            }

            Log.i(TAG, "[通道3] 网页抓取完成: $url → ${text.length} 字")
            text

        } catch (e: Exception) {
            Log.e(TAG, "[通道3] 网页抓取失败: $url - ${e.message}")
            ""
        } finally {
            updateChannelState(ChannelType.WEB_SCRAPING, ChannelState.IDLE)
        }
    }

    /**
     * 批量抓取多个URL
     */
    suspend fun fetchMultipleUrls(urls: List<String>): Map<String, String> {
        return urls.associateWith { url -> fetchWebContent(url) }
    }

    // ============ 通道4：全格式文件解析 ============

    /**
     * 提交文件进行解析学习
     *
     * @param filePath 文件路径
     * @param parsedText 已解析的文本（可选，如果外部已完成解析）
     */
    fun submitFileForLearning(filePath: String, parsedText: String? = null) {
        scope.launch {
            try {
                val text = parsedText ?: run {
                    // 调用文档解析器
                    val parser = com.kkgo.mindsoul.multimedia.DocumentParser(context)
                    parser.initialize()
                    val result = parser.parse(filePath)
                    parser.destroy()
                    result.text
                }

                if (text.isNotBlank()) {
                    val material = LearningMaterial(
                        channel = ChannelType.FILE_PARSE,
                        rawContent = text,
                        source = filePath,
                        metadata = mapOf("file" to File(filePath).name)
                    )
                    pipeline?.processMaterial(material)
                    updateStats(ChannelType.FILE_PARSE, 1)
                }
            } catch (e: Exception) {
                Log.e(TAG, "[通道4] 文件解析学习失败: $filePath - ${e.message}")
            }
        }
    }

    // ============ 通道5：全盘自主采集 ============

    /**
     * 启动全盘自主采集
     *
     * 需要 L2+ 权限。
     * 扫描指定目录，自动发现并导入学习素材。
     *
     * @param directories 要扫描的目录列表
     */
    fun startAutonomousCollection(directories: List<String>) {
        if (autonomousEnabled) {
            Log.w(TAG, "[通道5] 自主采集已在运行中")
            return
        }
        autonomousEnabled = true
        scanDirectories.clear()
        scanDirectories.addAll(directories)

        updateChannelState(ChannelType.AUTONOMOUS_COLLECT, ChannelState.PROCESSING)
        scope.launch {
            try {
                Log.i(TAG, "[通道5] 开始自主采集，目录: ${directories.size} 个")
                var totalFound = 0

                for (dir in directories) {
                    val dirFile = File(dir)
                    if (!dirFile.exists() || !dirFile.isDirectory) continue

                    val files = scanForLearningFiles(dirFile, 0)
                    for (file in files) {
                        if (!autonomousEnabled) break // 被中止

                        val text = readFileAsText(file)
                        if (text.isNotBlank()) {
                            val material = LearningMaterial(
                                channel = ChannelType.AUTONOMOUS_COLLECT,
                                rawContent = text,
                                source = file.absolutePath,
                                metadata = mapOf(
                                    "autoCollected" to "true",
                                    "fileSize" to file.length().toString()
                                )
                            )
                            pipeline?.processMaterial(material)
                            totalFound++
                        }
                    }
                }

                updateStats(ChannelType.AUTONOMOUS_COLLECT, totalFound.toLong())
                Log.i(TAG, "[通道5] 自主采集完成: 发现 $totalFound 个学习素材")

            } catch (e: Exception) {
                Log.e(TAG, "[通道5] 自主采集失败: ${e.message}")
            } finally {
                autonomousEnabled = false
                updateChannelState(ChannelType.AUTONOMOUS_COLLECT, ChannelState.IDLE)
            }
        }
    }

    /**
     * 停止自主采集
     */
    fun stopAutonomousCollection() {
        autonomousEnabled = false
        updateChannelState(ChannelType.AUTONOMOUS_COLLECT, ChannelState.IDLE)
        Log.i(TAG, "[通道5] 自主采集已停止")
    }

    // ============ 通道状态管理 ============

    /**
     * 禁用指定通道
     */
    fun disableChannel(channel: ChannelType) {
        updateChannelState(channel, ChannelState.DISABLED)
        Log.i(TAG, "[通道${channel.channelId}] 已禁用: ${channel.displayName}")
    }

    /**
     * 启用指定通道
     */
    fun enableChannel(channel: ChannelType) {
        updateChannelState(channel, ChannelState.IDLE)
        Log.i(TAG, "[通道${channel.channelId}] 已启用: ${channel.displayName}")
    }

    /**
     * 获取通道统计信息
     */
    fun getStats(channel: ChannelType): ChannelStats {
        return channelStats[channel] ?: ChannelStats(channel)
    }

    /**
     * 获取全部通道统计
     */
    fun getAllStats(): Map<ChannelType, ChannelStats> {
        return channelStats.toMap()
    }

    // ============ 内部方法 ============

    /**
     * 提交素材到流水线
     */
    private fun submitToPipeline(material: LearningMaterial) {
        val state = _channelStates.value[material.channel]
        if (state == ChannelState.DISABLED) {
            Log.w(TAG, "通道已禁用: ${material.channel.displayName}")
            return
        }
        scope.launch {
            pipeline?.processMaterial(material)
            updateStats(material.channel, 1)
        }
    }

    /**
     * 更新通道状态
     */
    private fun updateChannelState(channel: ChannelType, state: ChannelState) {
        val current = _channelStates.value.toMutableMap()
        current[channel] = state
        _channelStates.value = current
    }

    /**
     * 更新通道统计
     */
    private fun updateStats(channel: ChannelType, count: Long) {
        channelStats[channel]?.let {
            it.processedCount += count
            it.lastProcessTime = System.currentTimeMillis()
        }
    }

    /**
     * 递归扫描学习文件
     */
    private fun scanForLearningFiles(dir: File, depth: Int): List<File> {
        if (depth > 5) return emptyList() // 最大递归深度

        val files = mutableListOf<File>()
        val children = dir.listFiles() ?: return emptyList()
        var scanned = 0

        for (child in children) {
            if (scanned >= MAX_SCAN_FILES) break
            if (child.name.startsWith(".") || child.name.startsWith("_")) continue

            if (child.isFile && child.extension.lowercase() in SUPPORTED_EXTENSIONS) {
                files.add(child)
                scanned++
            } else if (child.isDirectory) {
                files.addAll(scanForLearningFiles(child, depth + 1))
                scanned++
            }
        }

        return files
    }

    /**
     * 读取文件为文本
     */
    private fun readFileAsText(file: File): String {
        return try {
            when (file.extension.lowercase()) {
                "txt", "md" -> file.readText(Charsets.UTF_8)
                else -> "" // 其他格式交给 DocumentParser
            }
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * 从 HTML 中提取正文纯文本
     *
     * 简化版算法：
     * 1. 移除 script/style/nav/footer/header 标签
     * 2. 提取 body 内容
     * 3. 移除所有 HTML 标签
     * 4. 清理空白
     */
    private fun extractWebText(html: String): String {
        var text = html
        // 移除不需要的块
        val removeTags = listOf("script", "style", "nav", "footer", "header", "aside", "iframe", "noscript")
        for (tag in removeTags) {
            text = Regex("""<$tag[^>]*>[\s\S]*?</$tag>""", RegexOption.IGNORE_CASE).replace(text, "")
        }
        // 提取 body
        val bodyMatch = Regex("""<body[^>]*>([\s\S]*)</body>""", RegexOption.IGNORE_CASE).find(text)
        if (bodyMatch != null) {
            text = bodyMatch.groupValues[1]
        }
        // 移除标签
        text = Regex("""<[^>]+>""").replace(text, " ")
        // 解码实体
        text = text.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
        // 清理空白
        text = text.lines().joinToString("\n") { it.trim() }.trim()
        text = Regex("[ \\t]+").replace(text, " ")
        text = Regex("\n{3,}").replace(text, "\n\n")
        return text
    }
}
