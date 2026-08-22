/*
 * ============================================================
 * LearningPipeline - 统一学习流水线
 * ============================================================
 *
 * 所有通道的素材最终汇入此流水线，经历统一的处理流程：
 *
 *   素材输入 → 编码 → 去冗余 → 提取因果逻辑 → 冷归档存储 → 空闲提炼公理入库
 *
 * 阶段详解：
 * 1. 素材编码：将原始文本编码为标准向量表示
 * 2. 去冗余：与已有知识比较，去除重复/高度相似内容
 * 3. 提取因果逻辑：从文本中抽取因果关系三元组
 * 4. 冷归档存储：将编码后的素材存入第三层冷归档系统
 * 5. 空闲提炼公理：利用空闲时间，从归档中提炼公理入库（第一层）
 *
 * 流水线是异步的，不阻塞主线程。
 * ============================================================
 */
package com.kkgo.mindsoul.learning

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 流水线处理阶段
 */
enum class PipelineStage {
    /** 编码阶段 */
    ENCODING,
    /** 去冗余阶段 */
    DEDUPLICATION,
    /** 因果提取阶段 */
    CAUSAL_EXTRACTION,
    /** 冷归档阶段 */
    COLD_ARCHIVE,
    /** 公理提炼阶段（空闲时执行） */
    AXIOM_DISTILLATION,
    /** 完成 */
    COMPLETED
}

/**
 * 流水线中的知识条目
 */
data class PipelineItem(
    /** 唯一ID */
    val id: String = System.nanoTime().toString(),
    /** 原始内容 */
    val rawContent: String,
    /** 来源 */
    val source: String,
    /** 内容哈希（用于去重） */
    val contentHash: String = computeHash(rawContent),
    /** 编码向量 */
    var encodedVector: FloatArray? = null,
    /** 提取的因果三元组 */
    val extractedCausal: MutableList<String> = mutableListOf(),
    /** 当前阶段 */
    var currentStage: PipelineStage = PipelineStage.ENCODING,
    /** 是否已去重（true=重复，将被丢弃） */
    var isDuplicate: Boolean = false,
    /** 处理时间戳 */
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        /** 计算内容 SHA-256 哈希 */
        fun computeHash(content: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val bytes = digest.digest(content.toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}

/**
 * 流水线统计
 */
data class PipelineStats(
    var totalInput: Long = 0,
    var totalEncoded: Long = 0,
    var totalDeduplicated: Long = 0,
    var totalCausalExtracted: Long = 0,
    var totalArchived: Long = 0,
    var totalAxiomDistilled: Long = 0,
    var totalDiscarded: Long = 0
)

/**
 * 学习增量统计数据
 *
 * 返回自某个时间点以来的学习增量，用于文件导入结果反馈。
 */
data class LearnStats(
    /** 学到的神经元数量（编码的向量数） */
    val neuronsLearned: Int = 0,
    /** 发现的关键词数量（因果提取数） */
    val keywordsFound: Int = 0,
    /** 关键词条数（因果三元组总数） */
    val keywordEntries: Int = 0,
    /** 概念节点数（归档条目数） */
    val conceptNodes: Int = 0,
    /** 概要搜引条数（输入素材总数） */
    val summaryIndex: Int = 0
)

/**
 * 统一学习流水线
 */
class LearningPipeline(private val context: Context) {

    companion object {
        private const val TAG = "LearningPipeline"
        /** 编码向量维度 */
        const val VECTOR_DIM = 128
        /** 去重相似度阈值 */
        const val DEDUP_SIMILARITY_THRESHOLD = 0.92f
        /** 最大队列长度 */
        const val MAX_QUEUE_SIZE = 10000
        /** 空闲提炼间隔（毫秒） */
        const val DISTILLATION_INTERVAL = 60_000L
    }

    // ============ 流水线状态 ============
    private val _currentStage = MutableStateFlow(PipelineStage.ENCODING)
    val currentStageFlow: StateFlow<PipelineStage> = _currentStage.asStateFlow()

    // ============ 队列 ============
    /** 待处理队列 */
    private val queue = ConcurrentLinkedQueue<PipelineItem>()
    /** 已处理队列（用于去重参考） */
    private val processedHashes = mutableSetOf<String>()
    /** 已处理向量（用于相似度去重） */
    private val processedVectors = mutableListOf<FloatArray>()

    // ============ 统计 ============
    private val stats = PipelineStats()

    // ============ 统计快照（用于增量统计） ============
    /** 历史统计快照列表，记录 (时间戳, 统计快照) */
    private val statsSnapshots = mutableListOf<Pair<Long, PipelineStats>>()

    /**
     * 记录当前统计快照
     *
     * 在每次处理完成一个条目后调用，
     * 用于后续 getLearnStatsSince 计算增量。
     */
    private fun recordStatsSnapshot() {
        val now = System.currentTimeMillis()
        // 仅保留最近 100 个快照，防止内存泄漏
        if (statsSnapshots.size > 100) {
            statsSnapshots.removeAt(0)
        }
        statsSnapshots.add(Pair(now, stats.copy()))
    }

    // ============ 知识提炼器 ============
    private lateinit var distiller: KnowledgeDistiller

    // ============ 协程 ============
    private val pipelineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** 处理信号量（控制并发） */
    private val semaphore = Semaphore(2)
    /** 空闲提炼任务 */
    private var distillationJob: Job? = null

    // ============ 运行控制 ============
    @Volatile
    private var isRunning = false

    // ============ 初始化 ============

    /**
     * 初始化学习流水线
     */
    fun initialize() {
        distiller = KnowledgeDistiller(context)
        distiller.initialize()

        // 启动流水线消费线程
        startPipeline()
        // 启动空闲提炼定时任务
        startDistillationSchedule()

        Log.i(TAG, "[初始化] 学习流水线就绪，向量维度: $VECTOR_DIM")
    }

    fun destroy() {
        isRunning = false
        distillationJob?.cancel()
        pipelineScope.cancel()
        distiller.destroy()
        Log.i(TAG, "[销毁] 学习流水线已释放")
    }

    // ============ 素材输入 ============

    /**
     * 提交学习素材
     *
     * 素材进入队列后，由流水线自动处理。
     *
     * @param material 学习素材
     */
    suspend fun processMaterial(material: LearningMaterial) {
        if (queue.size >= MAX_QUEUE_SIZE) {
            Log.w(TAG, "[队列] 已满($MAX_QUEUE_SIZE)，丢弃素材: ${material.source}")
            return
        }

        val item = PipelineItem(
            rawContent = material.rawContent,
            source = material.source
        )

        // 快速去重（基于哈希）
        if (processedHashes.contains(item.contentHash)) {
            stats.totalDiscarded++
            Log.d(TAG, "[去重] 哈希命中，丢弃: ${material.source}")
            return
        }

        queue.add(item)
        stats.totalInput++
        Log.d(TAG, "[入队] 素材: ${material.source}, 队列: ${queue.size}")
    }

    // ============ 流水线处理 ============

    /**
     * 启动流水线
     */
    private fun startPipeline() {
        if (isRunning) return
        isRunning = true

        pipelineScope.launch {
            while (isRunning) {
                val item = queue.poll()
                if (item != null) {
                    semaphore.acquire()
                    try {
                        processItem(item)
                    } finally {
                        semaphore.release()
                    }
                } else {
                    delay(500) // 队列为空时短暂休眠
                }
            }
        }

        Log.i(TAG, "[流水线] 已启动")
    }

    /**
     * 处理单个知识条目
     *
     * 完整流程：编码 → 去冗余 → 因果提取 → 冷归档
     */
    private suspend fun processItem(item: PipelineItem) {
        try {
            // 阶段1：编码
            _currentStage.value = PipelineStage.ENCODING
            encodeItem(item)
            stats.totalEncoded++

            // 阶段2：去冗余（向量相似度）
            _currentStage.value = PipelineStage.DEDUPLICATION
            if (checkSimilarity(item)) {
                item.isDuplicate = true
                stats.totalDeduplicated++
                stats.totalDiscarded++
                Log.d(TAG, "[去重] 向量相似，丢弃: ${item.source}")
                return
            }

            // 阶段3：提取因果逻辑
            _currentStage.value = PipelineStage.CAUSAL_EXTRACTION
            extractCausalLogic(item)
            stats.totalCausalExtracted++

            // 阶段4：冷归档存储
            _currentStage.value = PipelineStage.COLD_ARCHIVE
            archiveItem(item)
            stats.totalArchived++

            // 记录到已处理集合
            processedHashes.add(item.contentHash)
            item.encodedVector?.let { processedVectors.add(it) }

            item.currentStage = PipelineStage.COMPLETED
            // 记录统计快照（用于增量统计）
            recordStatsSnapshot()
            Log.d(TAG, "[完成] 知识条目处理完毕: ${item.source}")

        } catch (e: Exception) {
            Log.e(TAG, "[处理] 失败: ${item.source} - ${e.message}")
            stats.totalDiscarded++
        }
    }

    // ============ 阶段1：素材编码 ============

    /**
     * 将文本编码为向量
     *
     * 使用 TF-IDF + 哈希编码的简化方案：
     * 1. 分词（按字符 n-gram）
     * 2. 计算每个 n-gram 的哈希
     * 3. 映射到固定维度向量
     * 4. L2 归一化
     *
     * 这是一种不依赖任何第三方库的轻量级文本编码方案。
     */
    private fun encodeItem(item: PipelineItem) {
        val text = item.rawContent
        val vector = FloatArray(VECTOR_DIM)

        // 字符 trigram（3-gram）编码
        val ngramSize = 3
        if (text.length < ngramSize) {
            item.encodedVector = vector
            return
        }

        for (i in 0..text.length - ngramSize) {
            val ngram = text.substring(i, i + ngramSize)
            val hash = ngram.hashCode()
            val index = ((hash % VECTOR_DIM) + VECTOR_DIM) % VECTOR_DIM
            vector[index] += 1.0f
        }

        // TF 归一化（按总 n-gram 数）
        val totalNgrams = text.length - ngramSize + 1
        for (i in vector.indices) {
            vector[i] /= totalNgrams.toFloat()
        }

        // L2 归一化
        val norm = kotlin.math.sqrt(vector.sumOf { (it * it).toDouble() }).toFloat()
        if (norm > 0) {
            for (i in vector.indices) {
                vector[i] /= norm
            }
        }

        item.encodedVector = vector
    }

    // ============ 阶段2：去冗余 ============

    /**
     * 向量相似度去重
     *
     * 计算当前条目与已处理条目的余弦相似度，
     * 超过阈值则判定为重复。
     */
    private fun checkSimilarity(item: PipelineItem): Boolean {
        val vector = item.encodedVector ?: return false
        if (processedVectors.isEmpty()) return false

        for (existing in processedVectors) {
            val similarity = cosineSimilarity(vector, existing)
            if (similarity > DEDUP_SIMILARITY_THRESHOLD) {
                return true
            }
        }
        return false
    }

    /**
     * 余弦相似度计算
     */
    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dotProduct = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dotProduct += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = kotlin.math.sqrt(normA.toDouble()) * kotlin.math.sqrt(normB.toDouble())
        return if (denom > 0) (dotProduct / denom).toFloat() else 0f
    }

    // ============ 阶段3：因果逻辑提取 ============

    /**
     * 从文本中提取因果关系
     *
     * 基于规则的因果提取：
     * 1. 识别因果连接词（因为...所以、导致、由于、因此...）
     * 2. 分割前后文
     * 3. 构建因果三元组
     */
    private fun extractCausalLogic(item: PipelineItem) {
        val text = item.rawContent
        val causalPatterns = listOf(
            // 中文因果模式
            Regex("""因为(.{2,50}?)(?:所以|因此|于是|导致)(.{2,50})"""),
            Regex("""由于(.{2,50}?)(?:所以|因此|于是|导致)(.{2,50})"""),
            Regex("""(.{2,50}?)(?:的原因|的原因在于)(.{2,50})"""),
            Regex("""(.{2,50}?)→(.{2,50})"""),

            // 英文因果模式
            Regex("""because\s+(.+?)\s*(?:so|therefore|thus|leads?\s+to)\s+(.+)""", RegexOption.IGNORE_CASE),
            Regex("""(.+?)\s+(?:causes?|results?\s+in|leads?\s+to)\s+(.+)""", RegexOption.IGNORE_CASE),
            Regex("""due\s+to\s+(.+?)\s*,?\s*(.+)""", RegexOption.IGNORE_CASE)
        )

        for (pattern in causalPatterns) {
            for (match in pattern.findAll(text)) {
                val cause = match.groupValues[1].trim()
                val effect = match.groupValues[2].trim()
                if (cause.length >= 2 && effect.length >= 2) {
                    val causalStr = "CAUSE[$cause] → EFFECT[$effect]"
                    item.extractedCausal.add(causalStr)
                    Log.d(TAG, "[因果] 提取: $causalStr")
                }
            }
        }

        // 如果未提取到因果，尝试提取条件关系
        if (item.extractedCausal.isEmpty()) {
            val conditionPatterns = listOf(
                Regex("""如果(.{2,50}?)(?:那么|就|则)(.{2,50})"""),
                Regex("""当(.{2,50}?)(?:时|的时候)(?:，|,)?\s*(.{2,50})"""),
                Regex("""if\s+(.+?)\s*(?:then|,)\s*(.+)""", RegexOption.IGNORE_CASE)
            )

            for (pattern in conditionPatterns) {
                for (match in pattern.findAll(text)) {
                    val condition = match.groupValues[1].trim()
                    val consequence = match.groupValues[2].trim()
                    if (condition.length >= 2 && consequence.length >= 2) {
                        val causalStr = "CONDITION[$condition] → CONSEQUENCE[$consequence]"
                        item.extractedCausal.add(causalStr)
                    }
                }
            }
        }
    }

    // ============ 阶段4：冷归档存储 ============

    /**
     * 将知识条目存入冷归档系统
     *
     * 存入第三层冷归档（SQLite），等待后续提炼。
     */
    private suspend fun archiveItem(item: PipelineItem) {
        // 构建归档记录
        val record = buildString {
            appendLine("=== 知识条目 ===")
            appendLine("来源: ${item.source}")
            appendLine("时间: ${item.createdAt}")
            appendLine("哈希: ${item.contentHash}")
            appendLine()
            appendLine("--- 原文 ---")
            appendLine(item.rawContent.take(10000)) // 截取前1万字
            appendLine()

            if (item.extractedCausal.isNotEmpty()) {
                appendLine("--- 因果逻辑 ---")
                item.extractedCausal.forEach { appendLine(it) }
                appendLine()
            }

            if (item.encodedVector != null) {
                appendLine("--- 编码向量 ---")
                appendLine("维度: ${VECTOR_DIM}")
                appendLine("非零元素: ${item.encodedVector!!.count { it != 0f }}")
            }
        }

        // 存入归档文件
        try {
            val archiveDir = java.io.File(context.filesDir, "knowledge_archive")
            if (!archiveDir.exists()) archiveDir.mkdirs()

            val archiveFile = java.io.File(archiveDir, "${item.id}.dat")
            archiveFile.writeText(record, Charsets.UTF_8)
            Log.d(TAG, "[归档] 存储: ${item.id}")
        } catch (e: Exception) {
            Log.e(TAG, "[归档] 存储失败: ${e.message}")
        }
    }

    // ============ 阶段5：空闲提炼公理 ============

    /**
     * 启动空闲提炼定时任务
     *
     * 每隔 DISTILLATION_INTERVAL 毫秒，
     * 检查系统是否空闲，空闲则执行公理提炼。
     */
    private fun startDistillationSchedule() {
        distillationJob = pipelineScope.launch {
            while (isRunning) {
                delay(DISTILLATION_INTERVAL)
                if (queue.isEmpty()) {
                    Log.d(TAG, "[提炼] 系统空闲，开始公理提炼...")
                    performDistillation()
                }
            }
        }
    }

    /**
     * 执行公理提炼
     *
     * 从归档的知识条目中，提炼出高频因果关系，
     * 提升为公理层（第一层）的常驻知识。
     */
    private suspend fun performDistillation() {
        try {
            val archiveDir = java.io.File(context.filesDir, "knowledge_archive")
            if (!archiveDir.exists() || archiveDir.listFiles()?.isEmpty() != false) return

            val distilled = distiller.distillAxioms(archiveDir)
            stats.totalAxiomDistilled += distilled

            Log.i(TAG, "[提炼] 完成: 新增 $distilled 条公理")
        } catch (e: Exception) {
            Log.e(TAG, "[提炼] 失败: ${e.message}")
        }
    }

    // ============ 查询接口 ============

    /**
     * 获取流水线统计
     */
    fun getStats(): PipelineStats = stats.copy()

    /**
     * 获取当前队列长度
     */
    fun getQueueSize(): Int = queue.size

    /**
     * 获取自某个时间点以来的学习增量统计
     *
     * 通过比较当前统计与指定时间点的快照，计算出增量。
     * 如果没有找到对应的快照，则返回当前完整统计。
     *
     * @param startTimeMs 起始时间戳（毫秒）
     * @return LearnStats 自该时间点以来的学习增量
     */
    fun getLearnStatsSince(startTimeMs: Long): LearnStats {
        // 查找最接近 startTimeMs 的快照
        val snapshot = statsSnapshots
            .filter { it.first <= startTimeMs }
            .maxByOrNull { it.first }

        val baseline = snapshot?.second ?: PipelineStats()

        return LearnStats(
            neuronsLearned = (stats.totalEncoded - baseline.totalEncoded).toInt().coerceAtLeast(0),
            keywordsFound = (stats.totalCausalExtracted - baseline.totalCausalExtracted).toInt().coerceAtLeast(0),
            keywordEntries = (stats.totalCausalExtracted - baseline.totalCausalExtracted).toInt().coerceAtLeast(0),
            conceptNodes = (stats.totalArchived - baseline.totalArchived).toInt().coerceAtLeast(0),
            summaryIndex = (stats.totalInput - baseline.totalInput).toInt().coerceAtLeast(0)
        )
    }

    /**
     * 记录当前时间点（用于后续 getLearnStatsSince 的起始时间）
     *
     * @return 当前时间戳（毫秒）
     */
    fun markStatsTime(): Long {
        recordStatsSnapshot()
        return System.currentTimeMillis()
    }
}
