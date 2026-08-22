/*
 * ============================================================
 * InductionEngine - 第二层：异步归纳引擎
 * ============================================================
 *
 * 异步归纳引擎负责从经验数据中提炼因果规律和世界观规则。
 * 它在后台异步运行，不阻塞主意识流程。
 *
 * 两大核心功能：
 * 1. 因果树构建 - 将零散经验组织成因果推理链
 * 2. 世界观规则提炼 - 从因果树中归纳出抽象规律
 *
 * 工作原理：
 * - 接收第一层的因果三元组作为基础数据
 * - 构建因果树（DAG结构）
 * - 分析因果树中的模式，提炼规则
 * - 将规则反馈给第一层和世界模型
 * ============================================================
 */
package com.kkgo.mindsoul.consciousness.layer2

import android.util.Log
import com.kkgo.mindsoul.consciousness.layer1.AxiomLayer
import com.kkgo.mindsoul.model.*
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 第二层：异步归纳引擎
 * 
 * 在后台运行，从经验中归纳因果规律
 */
class InductionEngine(
    /** 第一层公理层引用 */
    private val axiomLayer: AxiomLayer,
    /** 协程作用域 */
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "InductionEngine"
        
        /** 归纳周期（毫秒） */
        const val INDUCTION_INTERVAL = 30_000L  // 30秒
        
        /** 因果树最大深度 */
        const val MAX_TREE_DEPTH = 10
        
        /** 规则提炼最小样本数 */
        const val MIN_SAMPLES_FOR_RULE = 3
    }
    
    // ============ 因果树 ============
    
    /** 因果树节点存储 */
    private val causalTreeNodes = ConcurrentHashMap<Long, CausalTreeNode>()
    
    /** 根节点集合（没有父节点的节点） */
    private val rootNodes = mutableListOf<Long>()
    
    /** 待处理事件队列 */
    private val eventQueue = ConcurrentLinkedQueue<String>()
    
    // ============ 世界观规则 ============
    
    /** 提炼出的规则集 */
    private val worldRules = ConcurrentHashMap<Long, WorldRule>()
    
    /** 规则提炼候选（待验证的假设） */
    private val ruleHypotheses = ConcurrentLinkedQueue<WorldRule>()
    
    // ============ 运行状态 ============
    
    private var isRunning = false
    private var inductionJob: Job? = null
    private var totalInductionCycles = 0L
    
    /**
     * 启动归纳引擎
     */
    fun start() {
        if (isRunning) return
        isRunning = true
        
        inductionJob = scope.launch(Dispatchers.Default) {
            Log.i(TAG, "异步归纳引擎启动")
            
            while (isRunning && isActive) {
                try {
                    // 执行归纳周期
                    performInductionCycle()
                    totalInductionCycles++
                    
                    // 等待下一个周期
                    delay(INDUCTION_INTERVAL)
                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "归纳周期异常", e)
                    delay(5000)  // 异常后等待5秒重试
                }
            }
            
            Log.i(TAG, "异步归纳引擎停止")
        }
    }
    
    /**
     * 停止归纳引擎
     */
    fun stop() {
        isRunning = false
        inductionJob?.cancel()
        inductionJob = null
    }
    
    /**
     * 提交新事件到归纳引擎
     * 
     * @param event 事件描述
     */
    fun submitEvent(event: String) {
        eventQueue.add(event)
        Log.d(TAG, "新事件入队: ${event.take(50)}...")
    }
    
    /**
     * 执行一次归纳周期
     * 
     * 步骤：
     * 1. 处理事件队列，更新因果树
     * 2. 分析因果树结构，寻找模式
     * 3. 提炼世界观规则
     * 4. 验证和更新规则
     */
    private suspend fun performInductionCycle() {
        withContext(Dispatchers.Default) {
            // 步骤1：处理待处理事件
            processEventQueue()
            
            // 步骤2：分析因果树，构建归纳
            analyzeCausalTree()
            
            // 步骤3：提炼规则
            distillRules()
            
            // 步骤4：验证规则
            validateRules()
            
            if (totalInductionCycles % 10 == 0L) {
                Log.d(TAG, "归纳周期#${totalInductionCycles}完成: " +
                    "因果树节点=${causalTreeNodes.size}, 规则=${worldRules.size}")
            }
        }
    }
    
    /**
     * 处理事件队列
     * 
     * 将新事件转化为因果树节点
     */
    private suspend fun processEventQueue() {
        var processed = 0
        while (processed < 100) {  // 每个周期最多处理100个事件
            val event = eventQueue.poll() ?: break
            
            // 创建因果树节点
            val node = CausalTreeNode(
                event = event,
                timestamp = System.currentTimeMillis()
            )
            
            // 尝试找到因果关系，连接到已有节点
            val relatedNodes = findRelatedNodes(event)
            if (relatedNodes.isNotEmpty()) {
                // 建立因果连接
                for (relatedId in relatedNodes) {
                    val related = causalTreeNodes[relatedId]
                    if (related != null) {
                        // 判断因果方向（基于时间顺序）
                        if (related.timestamp <= node.timestamp) {
                            // related 是原因，node 是结果
                            related.childIds.add(node.id)
                            node.parentIds.add(related.id)
                            node.depth = maxOf(node.depth, related.depth + 1)
                        } else {
                            // node 是原因，related 是结果
                            node.childIds.add(related.id)
                            related.parentIds.add(node.id)
                        }
                    }
                }
            } else {
                // 没有关联，作为新的根节点
                rootNodes.add(node.id)
            }
            
            // 限制树深度
            if (node.depth > MAX_TREE_DEPTH) {
                node.depth = MAX_TREE_DEPTH
            }
            
            causalTreeNodes[node.id] = node
            processed++
        }
    }
    
    /**
     * 分析与当前事件相关的因果树节点
     * 
     * 使用简单的关键词重叠度衡量相关性
     */
    private fun findRelatedNodes(event: String, maxResults: Int = 5): List<Long> {
        val eventWords = event.split("\\s+".toRegex())
            .map { it.lowercase() }
            .filter { it.length > 1 }
            .toSet()
        
        if (eventWords.isEmpty()) return emptyList()
        
        return causalTreeNodes.values
            .map { node ->
                val nodeWords = node.event.split("\\s+".toRegex())
                    .map { it.lowercase() }
                    .filter { it.length > 1 }
                    .toSet()
                
                // 计算词集重叠度（Jaccard相似度）
                val intersection = eventWords.intersect(nodeWords).size
                val union = eventWords.union(nodeWords).size
                val similarity = if (union > 0) intersection.toDouble() / union else 0.0
                
                Pair(node.id, similarity)
            }
            .filter { it.second > 0.1 }  // 相似度阈值
            .sortedByDescending { it.second }
            .take(maxResults)
            .map { it.first }
    }
    
    /**
     * 分析因果树结构
     * 
     * 寻找树中的模式：
     * 1. 高频因果链（反复出现的原因-结果对）
     * 2. 分支模式（一个原因导致多个结果）
     * 3. 收敛模式（多个原因导致同一结果）
     */
    private suspend fun analyzeCausalTree() {
        // 计算每个节点的频率和熵
        for (node in causalTreeNodes.values) {
            // 频率 = 自身观察次数 + 子节点传递的频率
            val childFrequency = node.childIds
                .mapNotNull { causalTreeNodes[it] }
                .sumOf { it.frequency }
            node.frequency = node.frequency * 0.9 + childFrequency * 0.1
            
            // 熵：衡量该节点结果的不确定性
            if (node.childIds.size > 1) {
                val totalChildFreq = node.childIds
                    .mapNotNull { causalTreeNodes[it] }
                    .sumOf { maxOf(it.frequency, 0.001) }
                
                node.entropy = -node.childIds
                    .mapNotNull { causalTreeNodes[it] }
                    .sumOf { 
                        val p = maxOf(it.frequency, 0.001) / totalChildFreq
                        if (p > 0) p * Math.log(p) / Math.log(2.0) else 0.0
                    }
            }
        }
        
        // 寻找高频因果链，生成规则假设
        for (node in causalTreeNodes.values) {
            if (node.childIds.size >= MIN_SAMPLES_FOR_RULE) {
                // 这个节点有足够多的子节点，可能可以提炼规则
                val children = node.childIds.mapNotNull { causalTreeNodes[it] }
                if (children.isNotEmpty()) {
                    val hypothesis = WorldRule(
                        condition = node.event,
                        conclusion = children.joinToString("; ") { it.event }.take(200),
                        scope = determineScope(node),
                        strength = 0.3
                    )
                    ruleHypotheses.add(hypothesis)
                }
            }
        }
    }
    
    /**
     * 确定规则适用范围
     */
    private fun determineScope(node: CausalTreeNode): RuleScope {
        return when {
            node.childIds.size > 10 -> RuleScope.UNIVERSAL    // 大量实例→普适
            node.childIds.size > 5 -> RuleScope.CONTEXTUAL    // 中等实例→情境相关
            node.depth > 5 -> RuleScope.LOCAL                 // 深层节点→局部
            else -> RuleScope.TEMPORAL                        // 默认→时间相关
        }
    }
    
    /**
     * 提炼规则
     * 
     * 将假设提升为正式规则（当积累足够证据时）
     */
    private suspend fun distillRules() {
        while (ruleHypotheses.isNotEmpty()) {
            val hypothesis = ruleHypotheses.poll() ?: break
            
            // 检查是否与已有规则重复
            val similar = worldRules.values.any { existing ->
                similarity(existing.condition, hypothesis.condition) > 0.7
            }
            
            if (!similar) {
                worldRules[hypothesis.id] = hypothesis
                Log.d(TAG, "提炼新规则: ${hypothesis.condition.take(40)}...")
            }
        }
    }
    
    /**
     * 验证已有规则
     * 
     * 检查规则是否与新的因果树数据一致
     */
    private suspend fun validateRules() {
        for (rule in worldRules.values) {
            // 在因果树中查找与规则条件匹配的模式
            val matches = causalTreeNodes.values.count { 
                similarity(it.event, rule.condition) > 0.5 
            }
            
            if (matches > 0) {
                rule.applicationCount++
                // 增加规则强度
                rule.strength = (rule.strength * 0.95 + 0.05).coerceIn(0.0, 1.0)
            } else if (rule.applicationCount > 0) {
                // 规则未被验证，衰减
                rule.failureCount++
                rule.strength *= 0.99
            }
        }
        
        // 移除过弱的规则
        val weakRules = worldRules.filter { it.value.strength < 0.1 }
        for (rule in weakRules) {
            worldRules.remove(rule.key)
        }
    }
    
    /**
     * 计算两个字符串的简单相似度
     * 
     * 基于词集Jaccard相似度
     */
    private fun similarity(a: String, b: String): Double {
        val wordsA = a.lowercase().split("\\s+".toRegex()).filter { it.length > 1 }.toSet()
        val wordsB = b.lowercase().split("\\s+".toRegex()).filter { it.length > 1 }.toSet()
        
        if (wordsA.isEmpty() || wordsB.isEmpty()) return 0.0
        
        val intersection = wordsA.intersect(wordsB).size.toDouble()
        val union = wordsA.union(wordsB).size.toDouble()
        
        return intersection / union
    }
    
    // ============ 公开接口 ============
    
    /**
     * 获取所有已提炼的世界观规则
     */
    fun forceTrigger() {
        // 强制触发一次归纳周期
        scope.launch {
            performInductionCycle()
        }
    }

    fun getWorldRules(): List<WorldRule> {
        return worldRules.values.sortedByDescending { it.strength }
    }
    
    /**
     * 查询特定领域的规则
     */
    fun getRulesByScope(scope: RuleScope): List<WorldRule> {
        return worldRules.values.filter { it.scope == scope }
    }
    
    /**
     * 获取因果树统计信息
     */
    fun getCausalTreeStats(): CausalTreeStats {
        val totalNodes = causalTreeNodes.size
        val maxDepth = causalTreeNodes.values.maxOfOrNull { it.depth } ?: 0
        val avgChildren = if (totalNodes > 0) {
            causalTreeNodes.values.sumOf { it.childIds.size }.toDouble() / totalNodes
        } else 0.0
        
        return CausalTreeStats(
            nodeCount = totalNodes,
            rootNodeCount = rootNodes.size,
            maxDepth = maxDepth,
            avgChildrenPerNode = avgChildren,
            ruleCount = worldRules.size,
            pendingHypotheses = ruleHypotheses.size,
            inductionCycles = totalInductionCycles
        )
    }
}

/**
 * 因果树统计信息
 */
data class CausalTreeStats(
    val nodeCount: Int,
    val rootNodeCount: Int,
    val maxDepth: Int,
    val avgChildrenPerNode: Double,
    val ruleCount: Int,
    val pendingHypotheses: Int,
    val inductionCycles: Long
)
