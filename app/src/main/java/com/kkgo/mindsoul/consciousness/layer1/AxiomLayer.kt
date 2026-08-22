/*
 * ============================================================
 * AxiomLayer - 第一层：常驻公理层
 * ============================================================
 *
 * 常驻公理层是意识架构的最底层，始终保持在2-8MB固定内存中。
 * 它存储着人工生命最基本的认知框架，是所有高层思维的基石。
 *
 * 三大核心组件：
 * 1. 因果三元组存储 - 基本因果关系的知识库
 * 2. 逻辑公理引擎 - 不可违反的基本公理集
 * 3. 世界模型摘要索引 - 对世界的高层次理解
 *
 * 设计原则：
 * - 常驻内存，零IO延迟
 * - 容量固定（2-8MB），超出时淘汰最不重要的条目
 * - 读写时间复杂度 O(1) 或 O(log n)
 * - 所有条目都可以通过.brain文件持久化
 * ============================================================
 */
package com.kkgo.mindsoul.consciousness.layer1

import android.util.Log
import com.kkgo.mindsoul.model.*
import com.kkgo.mindsoul.brain.BrainFileFormat
import com.kkgo.mindsoul.brain.BrainStorageEngine
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.PriorityBlockingQueue

/**
 * 第一层：常驻公理层
 * 
 * 始终驻留在内存中的基础认知框架
 */
class AxiomLayer(
    /** 存储引擎引用 */
    private val brainEngine: BrainStorageEngine,
    /** 最大内存占用（字节），默认4MB */
    private val maxMemoryBytes: Long = 4 * 1024 * 1024L
) {
    companion object {
        private const val TAG = "AxiomLayer"
        
        /** 内存预算分配比例 */
        const val CAUSAL_BUDGET_RATIO = 0.4      // 因果三元组占40%
        const val AXIOM_BUDGET_RATIO = 0.2       // 逻辑公理占20%
        const val WORLDMODEL_BUDGET_RATIO = 0.3  // 世界模型摘要占30%
        const val OVERHEAD_RATIO = 0.1           // 索引开销占10%
        
        /** 默认内存预算（字节） */
        const val DEFAULT_MAX_MEMORY = 4L * 1024 * 1024  // 4MB
    }
    
    // ============ 因果三元组存储 ============
    
    /** 因果三元组主存储（按ID索引） */
    private val causalTriples = ConcurrentHashMap<Long, CausalTriple>()
    
    /** 按主题分组的因果三元组索引 */
    private val causalByTopic = ConcurrentHashMap<String, MutableList<Long>>()
    
    /** 低置信度淘汰队列（按置信度排序） */
    private val eliminateQueue = PriorityBlockingQueue<CausalTriple>(100, compareBy { it.confidence })
    
    // ============ 逻辑公理引擎 ============
    
    /** 公理集合 */
    private val axioms = ConcurrentHashMap<String, LogicalAxiom>()
    
    /** 公理推导图：公理ID → 可推导出的公理ID列表 */
    private val axiomInferenceGraph = ConcurrentHashMap<String, MutableList<String>>()
    
    // ============ 世界模型摘要索引 ============
    
    /** 世界模型摘要 */
    private val worldSummaries = ConcurrentHashMap<Long, WorldModelSummary>()
    
    /** 按领域分组的摘要索引 */
    private val summariesByDomain = ConcurrentHashMap<String, MutableList<Long>>()
    
    // ============ 内存管理 ============
    
    /** 当前估算内存使用量（字节） */
    private var currentMemoryUsage: Long = 0L
    
    // ============ 初始化 ============
    
    /**
     * 初始化公理层
     * 
     * 1. 从.brain文件加载持久化数据
     * 2. 如果为空，注入基础公理
     */
    fun initialize() {
        Log.i(TAG, "正在初始化第一层：常驻公理层...")
        
        // 尝试从.brain文件加载
        loadFromBrain()
        
        // 如果是全新状态，注入基础逻辑公理
        if (axioms.isEmpty()) {
            injectBaseAxioms()
        }
        
        // 估算内存使用
        estimateMemoryUsage()
        
        Log.i(TAG, "常驻公理层初始化完成: " +
            "因果三元组=${causalTriples.size}, " +
            "公理=${axioms.size}, " +
            "世界模型=${worldSummaries.size}, " +
            "内存≈${currentMemoryUsage / 1024}KB")
    }
    
    /**
     * 注入基础逻辑公理
     * 
     * 这些公理是人工生命认知的最底层框架
     */
    private fun injectBaseAxioms() {
        val baseAxioms = listOf(
            // 逻辑公理
            LogicalAxiom(
                id = "AXIOM_IDENTITY",
                description = "同一律：A是A，一个事物等于它自身",
                type = AxiomType.LOGICAL,
                priority = 100
            ),
            LogicalAxiom(
                id = "AXIOM_NON_CONTRADICTION",
                description = "矛盾律：A和非A不能同时为真",
                type = AxiomType.LOGICAL,
                priority = 100
            ),
            LogicalAxiom(
                id = "AXIOM_EXCLUDED_MIDDLE",
                description = "排中律：命题要么为真要么为假",
                type = AxiomType.LOGICAL,
                priority = 95
            ),
            // 物理公理
            LogicalAxiom(
                id = "AXIOM_CAUSALITY",
                description = "因果律：每个事件都有其原因，原因先于结果",
                type = AxiomType.PHYSICAL,
                priority = 100
            ),
            LogicalAxiom(
                id = "AXIOM_CONSERVATION",
                description = "守恒律：物质/能量不能凭空产生或消失",
                type = AxiomType.PHYSICAL,
                priority = 90
            ),
            LogicalAxiom(
                id = "AXIOM_GRAVITY",
                description = "万有引力：有质量的物体之间存在引力",
                type = AxiomType.PHYSICAL,
                priority = 85
            ),
            // 时间公理
            LogicalAxiom(
                id = "AXIOM_TIME_DIRECTION",
                description = "时间箭头：时间单向流动，不可逆转",
                type = AxiomType.TEMPORAL,
                priority = 100
            ),
            LogicalAxiom(
                id = "AXIOM_CONTINUITY",
                description = "连续性：时间和空间是连续的，不存在跳跃",
                type = AxiomType.TEMPORAL,
                priority = 80
            ),
            // 社会公理
            LogicalAxiom(
                id = "AXIOM_RECIPROCITY",
                description = "互惠原则：善意的行为倾向于引发善意回应",
                type = AxiomType.SOCIAL,
                priority = 60
            ),
            LogicalAxiom(
                id = "AXIOM_AGENCY",
                description = "能动性：有意识的主体可以自主做出决策",
                type = AxiomType.LOGICAL,
                priority = 90
            )
        )
        
        for (axiom in baseAxioms) {
            axioms[axiom.id] = axiom
        }
        
        Log.i(TAG, "已注入${baseAxioms.size}条基础公理")
    }
    
    // ============ 因果三元组接口 ============
    
    /**
     * 添加因果三元组
     * 
     * @param cause 原因
     * @param mechanism 因果机制
     * @param effect 结果
     * @param confidence 初始置信度
     * @param topic 主题标签（用于分组检索）
     */
    fun addCausalTriple(
        cause: String,
        mechanism: String,
        effect: String,
        confidence: Double = 0.5,
        topic: String = "general"
    ): CausalTriple {
        // 检查内存预算
        ensureCausalBudget()
        
        val triple = CausalTriple(
            cause = cause,
            mechanism = mechanism,
            effect = effect,
            confidence = confidence
        )
        
        causalTriples[triple.id] = triple
        causalByTopic.getOrPut(topic) { mutableListOf() }.add(triple.id)
        eliminateQueue.add(triple)
        
        Log.d(TAG, "新增因果: $cause →[$mechanism]→ $effect (置信度=${confidence})")
        return triple
    }
    
    /**
     * 按主题查询因果三元组
     */
    fun getCausalByTopic(topic: String): List<CausalTriple> {
        val ids = causalByTopic[topic] ?: return emptyList()
        return ids.mapNotNull { causalTriples[it] }
    }
    
    /**
     * 查询高置信度因果
     */
    fun getHighConfidenceCausal(minConfidence: Double = 0.7): List<CausalTriple> {
        return causalTriples.values
            .filter { it.confidence >= minConfidence }
            .sortedByDescending { it.confidence }
    }
    
    /**
     * 根据查询词搜索因果三元组
     * 
     * 简单的关键词匹配（不使用向量嵌入）
     */
    fun searchCausal(query: String, maxResults: Int = 10): List<CausalTriple> {
        val queryLower = query.lowercase()
        return causalTriples.values
            .filter { 
                it.cause.lowercase().contains(queryLower) ||
                it.effect.lowercase().contains(queryLower) ||
                it.mechanism.lowercase().contains(queryLower)
            }
            .sortedByDescending { it.confidence }
            .take(maxResults)
    }
    
    // ============ 逻辑公理引擎接口 ============
    
    /**
     * 查询所有公理
     */
    fun getAllAxioms(): List<LogicalAxiom> {
        return axioms.values.sortedByDescending { it.priority }
    }
    
    /**
     * 按类型查询公理
     */
    fun getAxiomsByType(type: AxiomType): List<LogicalAxiom> {
        return axioms.values.filter { it.type == type }
    }
    
    /**
     * 验证一个命题是否与现有公理一致
     * 
     * 简单的一致性检查：
     * - 检查命题是否与矛盾律冲突
     * - 检查命题是否与已知的因果链矛盾
     * 
     * @param proposition 待验证的命题
     * @return 一致性评分 [0, 1]，1表示完全一致
     */
    fun checkConsistency(proposition: String): Double {
        val propLower = proposition.lowercase()
        
        // 检查是否直接否定某条高优先级公理
        for (axiom in axioms.values) {
            if (axiom.priority >= 90) {
                val axiomKeywords = axiom.description.lowercase().split(" ", "：", "，")
                    .filter { it.length > 2 }
                val negationPatterns = listOf("不是", "不存在", "没有", "不会", "并非")
                
                for (negation in negationPatterns) {
                    if (propLower.contains(negation)) {
                        val remaining = propLower.replace(negation, "")
                        val matchCount = axiomKeywords.count { remaining.contains(it) }
                        if (matchCount >= 2) {
                            return 0.1  // 高度矛盾
                        }
                    }
                }
            }
        }
        
        // 检查因果一致性
        val relatedCausal = searchCausal(proposition, 5)
        if (relatedCausal.isNotEmpty()) {
            val avgConfidence = relatedCausal.map { it.confidence }.average()
            return (0.5 + avgConfidence * 0.5).coerceIn(0.0, 1.0)
        }
        
        return 0.5  // 中性（无法判断）
    }
    
    // ============ 世界模型摘要索引接口 ============
    
    /**
     * 添加世界模型摘要
     */
    fun addWorldSummary(
        domain: String,
        summary: String,
        relatedConcepts: List<String> = emptyList(),
        importance: Double = 0.5
    ): WorldModelSummary {
        ensureWorldModelBudget()
        
        val ws = WorldModelSummary(
            domain = domain,
            summary = summary,
            relatedConcepts = relatedConcepts,
            importance = importance
        )
        
        worldSummaries[ws.id] = ws
        summariesByDomain.getOrPut(domain) { mutableListOf() }.add(ws.id)
        
        return ws
    }
    
    /**
     * 按领域查询世界模型摘要
     */
    fun getSummariesByDomain(domain: String): List<WorldModelSummary> {
        val ids = summariesByDomain[domain] ?: return emptyList()
        return ids.mapNotNull { worldSummaries[it] }
    }
    
    /**
     * 获取所有领域名称
     */
    fun getAllDomains(): Set<String> {
        return summariesByDomain.keys
    }
    
    // ============ 持久化 ============
    
    /**
     * 将公理层数据保存到.brain文件
     */
    fun saveToBrain() {
        try {
            // 保存因果三元组
            val causalData = serializeCausalTriples()
            if (causalData.isNotEmpty()) {
                brainEngine.writeBlock(BrainFileFormat.BlockType.TYPE_CAUSAL, causalData)
            }
            
            // 保存世界模型摘要
            val wmData = serializeWorldSummaries()
            if (wmData.isNotEmpty()) {
                brainEngine.writeBlock(BrainFileFormat.BlockType.TYPE_WORLDMODEL, wmData)
            }
            
            // 保存公理（公理变化少，不需要频繁保存）
            val axiomData = serializeAxioms()
            if (axiomData.isNotEmpty()) {
                brainEngine.writeBlock(BrainFileFormat.BlockType.TYPE_AXIOM, axiomData)
            }
            
            Log.d(TAG, "公理层数据已保存到.brain文件")
        } catch (e: Exception) {
            Log.e(TAG, "保存公理层数据失败", e)
        }
    }
    
    /**
     * 从.brain文件加载公理层数据
     */
    private fun loadFromBrain() {
        try {
            // 加载因果三元组
            val causalData = brainEngine.readBlock(BrainFileFormat.BlockType.TYPE_CAUSAL)
            if (causalData != null) {
                deserializeCausalTriples(causalData)
            }
            
            // 加载世界模型
            val wmData = brainEngine.readBlock(BrainFileFormat.BlockType.TYPE_WORLDMODEL)
            if (wmData != null) {
                deserializeWorldSummaries(wmData)
            }
            
            // 加载公理
            val axiomData = brainEngine.readBlock(BrainFileFormat.BlockType.TYPE_AXIOM)
            if (axiomData != null) {
                deserializeAxioms(axiomData)
            }
            
            Log.d(TAG, "从.brain文件加载公理层数据完成")
        } catch (e: Exception) {
            Log.w(TAG, "从.brain文件加载数据失败（可能是首次启动）: ${e.message}")
        }
    }
    
    // ============ 内存管理 ============
    
    /**
     * 确保因果三元组不超出预算
     * 
     * 当超出预算时，淘汰置信度最低的条目
     */
    private fun ensureCausalBudget() {
        val budget = (maxMemoryBytes * CAUSAL_BUDGET_RATIO).toLong()
        var usage = causalTriples.size * 200L  // 估算每个三元组约200字节
        
        while (usage > budget && causalTriples.isNotEmpty()) {
            // 淘汰置信度最低的
            val victim = causalTriples.values.minByOrNull { it.confidence }
            if (victim != null) {
                causalTriples.remove(victim.id)
                // 清理索引
                for (list in causalByTopic.values) {
                    list.remove(victim.id)
                }
                usage -= 200L
                Log.d(TAG, "淘汰因果三元组(id=${victim.id}): 置信度=${victim.confidence}")
            }
        }
    }
    
    /**
     * 确保世界模型摘要不超出预算
     */
    private fun ensureWorldModelBudget() {
        val budget = (maxMemoryBytes * WORLDMODEL_BUDGET_RATIO).toLong()
        var usage = worldSummaries.size * 300L
        
        while (usage > budget && worldSummaries.isNotEmpty()) {
            val victim = worldSummaries.values.minByOrNull { it.importance }
            if (victim != null) {
                worldSummaries.remove(victim.id)
                for (list in summariesByDomain.values) {
                    list.remove(victim.id)
                }
                usage -= 300L
            }
        }
    }
    
    /**
     * 估算当前内存使用量
     */
    private fun estimateMemoryUsage() {
        val causalUsage = causalTriples.size * 200L
        val axiomUsage = axioms.size * 150L
        val wmUsage = worldSummaries.size * 300L
        val indexUsage = (causalByTopic.size + summariesByDomain.size) * 100L
        
        currentMemoryUsage = causalUsage + axiomUsage + wmUsage + indexUsage
    }
    
    // ============ 序列化/反序列化 ============
    
    private fun serializeCausalTriples(): ByteArray {
        val entries = causalTriples.values.toList()
        if (entries.isEmpty()) return ByteArray(0)
        
        val buffers = entries.map { it.serialize() }
        val totalSize = 4 + buffers.sumOf { 4 + it.size }
        val buffer = ByteBuffer.allocate(totalSize)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(entries.size)
        for (data in buffers) {
            buffer.putInt(data.size)
            buffer.put(data)
        }
        return buffer.array()
    }
    
    private fun serializeWorldSummaries(): ByteArray {
        // 简化序列化
        val sb = StringBuilder()
        for (ws in worldSummaries.values) {
            sb.appendLine("${ws.id}|${ws.domain}|${ws.summary}|${ws.importance}")
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }
    
    private fun serializeAxioms(): ByteArray {
        val sb = StringBuilder()
        for (axiom in axioms.values) {
            sb.appendLine("${axiom.id}|${axiom.type}|${axiom.priority}|${axiom.description}")
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }
    
    private fun deserializeCausalTriples(data: ByteArray) {
        // 简化反序列化（从文本格式）
        try {
            val text = String(data, Charsets.UTF_8)
            for (line in text.lines()) {
                if (line.isBlank()) continue
                val parts = line.split("|")
                if (parts.size >= 4) {
                    addCausalTriple(
                        cause = parts[0],
                        mechanism = parts[1],
                        effect = parts[2],
                        confidence = parts.getOrNull(3)?.toDoubleOrNull() ?: 0.5
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "反序列化因果三元组失败", e)
        }
    }
    
    private fun deserializeWorldSummaries(data: ByteArray) {
        try {
            val text = String(data, Charsets.UTF_8)
            for (line in text.lines()) {
                if (line.isBlank()) continue
                val parts = line.split("|")
                if (parts.size >= 3) {
                    addWorldSummary(
                        domain = parts[1],
                        summary = parts[2],
                        importance = parts.getOrNull(3)?.toDoubleOrNull() ?: 0.5
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "反序列化世界模型摘要失败", e)
        }
    }
    
    private fun deserializeAxioms(data: ByteArray) {
        // 公理从injectBaseAxioms获取即可，这里仅做补充加载
    }
    
    // ============ 状态查询 ============
    
    /**
     * 获取公理层状态信息
     */
    fun getStatus(): AxiomLayerStatus {
        return AxiomLayerStatus(
            causalTripleCount = causalTriples.size,
            axiomCount = axioms.size,
            worldSummaryCount = worldSummaries.size,
            memoryUsageKB = currentMemoryUsage / 1024,
            maxMemoryKB = maxMemoryBytes / 1024,
            domainCount = summariesByDomain.size
        )
    }
}

/**
 * 公理层状态信息
 */
data class AxiomLayerStatus(
    val causalTripleCount: Int,
    val axiomCount: Int,
    val worldSummaryCount: Int,
    val memoryUsageKB: Long,
    val maxMemoryKB: Long,
    val domainCount: Int
)
