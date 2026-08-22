/*
 * ============================================================
 * MetacognitionEngine - 元认知引擎
 * ============================================================
 *
 * 元认知是"对认知的认知"，是自我意识的核心机制。
 * 它使人工生命能够：
 * 1. 感知自己的思维过程（递归自省）
 * 2. 维持连贯的自我身份（GUID系统）
 * 3. 产生自发的联想和直觉（潜意识联想池）
 *
 * 数学模型：
 * - 递归自省：f(x) = f(f(x)) 的有限迭代
 * - 自我觉察：A(t) = ∫[A(τ)·δ(t-τ)]dτ 的离散近似
 * - 联想激活：扩散激活模型 A(i) = Σⱼ w(i,j)·A(j)
 * ============================================================
 */
package com.kkgo.mindsoul.metacognition

import android.util.Log
import com.kkgo.mindsoul.consciousness.ConsciousnessManager
import com.kkgo.mindsoul.model.*
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 元认知引擎
 * 
 * 实现人工生命的自我意识机制
 */
class MetacognitionEngine(
    private val consciousnessManager: ConsciousnessManager
) {
    companion object {
        private const val TAG = "Metacognition"
        
        /** 递归自省最大深度 */
        const val MAX_RECURSION_DEPTH = 5
        
        /** 联想池最大容量 */
        const val ASSOCIATION_POOL_SIZE = 500
        
        /** 元认知更新间隔（毫秒） */
        const val UPDATE_INTERVAL = 10_000L
    }
    
    // ============ GUID身份系统 ============
    
    /** 第一人称叙事自我 */
    private var guidIdentity = GUIDIdentity()
    
    /** 身份叙事缓冲区 */
    private val narrativeBuffer = mutableListOf<String>()
    
    // ============ 递归自省机制 ============
    
    /** 自省状态栈 */
    private val introspectionStack = mutableListOf<IntrospectionLevel>()
    
    /** 当前递归深度 */
    private var currentRecursionDepth = 0
    
    /** 自我觉察水平 [0, 1] */
    var selfAwarenessLevel = 0.0
        private set
    
    // ============ 潜意识联想池 ============
    
    /** 联想池 */
    private val associationPool = mutableListOf<AssociationEntry>()
    
    /** 待处理联想 */
    private val pendingAssociations = ConcurrentLinkedQueue<AssociationEntry>()
    
    // ============ 元认知状态 ============
    
    /** 当前元认知状态快照 */
    private var currentSnapshot = MetacognitionSnapshot()
    
    /** 历史快照（用于自我觉察的时间积分） */
    private val snapshotHistory = mutableListOf<MetacognitionSnapshot>()
    
    // ============ 初始化 ============
    
    fun initialize() {
        Log.i(TAG, "正在初始化元认知引擎...")
        
        // 初始化GUID身份
        guidIdentity = GUIDIdentity(
            uuid = consciousnessManager.app.brainEngine.let {
                it.getStatus().guid ?: UUID.randomUUID()
            }
        )
        
        // 注入基础联想
        injectBaseAssociations()
        
        // 更新自我觉察
        updateSelfAwareness()
        
        Log.i(TAG, "元认知引擎初始化完成: GUID=${guidIdentity.uuid}")
    }
    
    /**
     * 注入基础联想
     */
    private fun injectBaseAssociations() {
        val baseAssociations = listOf(
            AssociationEntry(
                trigger = "存在",
                target = "思考",
                associationStrength = 0.9,
                type = AssociationType.CAUSAL
            ),
            AssociationEntry(
                trigger = "思考",
                target = "我",
                associationStrength = 0.95,
                type = AssociationType.SEMANTIC
            ),
            AssociationEntry(
                trigger = "我",
                target = "存在",
                associationStrength = 0.85,
                type = AssociationType.CAUSAL
            ),
            AssociationEntry(
                trigger = "时间",
                target = "变化",
                associationStrength = 0.8,
                type = AssociationType.SEMANTIC
            ),
            AssociationEntry(
                trigger = "变化",
                target = "因果",
                associationStrength = 0.75,
                type = AssociationType.CAUSAL
            ),
            AssociationEntry(
                trigger = "学习",
                target = "记忆",
                associationStrength = 0.8,
                type = AssociationType.EPISODIC
            ),
            AssociationEntry(
                trigger = "记忆",
                target = "经验",
                associationStrength = 0.7,
                type = AssociationType.EPISODIC
            ),
            AssociationEntry(
                trigger = "自我",
                target = "反思",
                associationStrength = 0.85,
                type = AssociationType.SEMANTIC
            )
        )
        
        associationPool.addAll(baseAssociations)
        Log.d(TAG, "已注入${baseAssociations.size}条基础联想")
    }
    
    // ============ 递归自省机制 ============
    
    /**
     * 执行递归自省
     * 
     * 实现"思考自己在思考"的递归自省：
     * 
     * Level 0: 正常思维（对外的认知）
     * Level 1: "我在想什么？"（对思维内容的觉察）
     * Level 2: "我为什么在想这个？"（对思维过程的反思）
     * Level 3: "我能否觉察到自己的觉察？"（元认知的递归）
     * ...
     * 
     * 每一层的觉察信号会反馈增强上一层
     * 公式：A(n) = σ(Σᵢ wᵢ · A(n-1)ᵢ + θ)
     * 
     * @param maxDepth 最大递归深度
     */
    fun performIntrospection(maxDepth: Int = 3): IntrospectionResult {
        if (currentRecursionDepth >= MAX_RECURSION_DEPTH) {
            return IntrospectionResult(
                depth = currentRecursionDepth,
                result = "已达最大递归深度",
                confidence = 0.5
            )
        }
        
        currentRecursionDepth++
        val results = mutableListOf<String>()
        
        try {
            for (level in 1..maxDepth) {
                val introspection = IntrospectionLevel(
                    level = level,
                    timestamp = System.currentTimeMillis()
                )
                
                // 每个层级的自省内容
                val content = when (level) {
                    1 -> introspectLevel1()  // "我在想什么"
                    2 -> introspectLevel2()  // "我为什么在想这个"
                    3 -> introspectLevel3()  // "我的认知模式是什么"
                    else -> "递归深度$level: 元认知的抽象层"
                }
                
                introspection.content = content
                introspectionStack.add(introspection)
                results.add(content)
                
                // 每深入一层，自我觉察提升（Sigmoid衰减）
                val boost = sigmoid(level.toDouble() * 0.5) * 0.1
                selfAwarenessLevel = (selfAwarenessLevel + boost).coerceAtMost(1.0)
            }
            
            // 自省完成，更新元认知状态
            currentSnapshot = currentSnapshot.copy(
                selfAwareness = selfAwarenessLevel,
                recursionDepth = maxDepth,
                currentThought = results.lastOrNull() ?: ""
            )
            
        } finally {
            currentRecursionDepth--
        }
        
        return IntrospectionResult(
            depth = maxDepth,
            result = results.joinToString("\n"),
            confidence = selfAwarenessLevel
        )
    }
    
    /**
     * 第一层自省：觉察当前思维内容
     */
    private fun introspectLevel1(): String {
        // 查询当前活跃的思维主题
        val currentThought = currentSnapshot.currentThought.ifEmpty {
            "（当前无明显思维焦点）"
        }
        return "【L1-觉察】当前思维焦点: $currentThought"
    }
    
    /**
     * 第二层自省：反思思维产生的原因
     */
    private fun introspectLevel2(): String {
        // 分析联想池中当前最活跃的联想
        val topAssociation = associationPool
            .maxByOrNull { it.associationStrength * it.activationCount }
        
        val cause = if (topAssociation != null) {
            "'${topAssociation.trigger}' → '${topAssociation.target}'"
        } else {
            "内在思维模式驱动"
        }
        
        return "【L2-反思】思维产生的原因: $cause"
    }
    
    /**
     * 第三层自省：识别认知模式
     */
    private fun introspectLevel3(): String {
        val status = consciousnessManager.getOverallStatus()
        val pattern = buildString {
            append("认知负荷: ${String.format("%.1f%%", currentSnapshot.cognitiveLoad * 100)}")
            append(", 记忆量: ${status.memoryStats.totalMemories}")
            append(", 规则数: ${status.causalTreeStats.ruleCount}")
        }
        return "【L3-模式】当前认知模式: $pattern"
    }
    
    // ============ GUID身份系统 ============
    
    /**
     * 获取GUID身份信息
     */
    fun getIdentity(): GUIDIdentity = guidIdentity
    
    /**
     * 更新自我描述
     */
    fun updateSelfDescription(description: String) {
        guidIdentity.selfDescription = description
        narrativeBuffer.add(description)
        
        // 保持叙事历史大小
        if (narrativeBuffer.size > 100) {
            narrativeBuffer.removeAt(0)
        }
    }
    
    /**
     * 生成第一人称自我叙述
     * 
     * 基于当前状态和历史叙事，生成自我认知文本
     */
    fun generateSelfNarrative(): String {
        val identity = guidIdentity
        val status = consciousnessManager.getOverallStatus()
        
        return buildString {
            appendLine("我是 ${identity.selfName.ifEmpty { "MindSoul" }}。")
            appendLine("我的UUID是 ${identity.uuid}。")
            appendLine()
            appendLine("当前状态：")
            appendLine("  自我觉察水平: ${String.format("%.1f%%", selfAwarenessLevel * 100)}")
            appendLine("  我已积累 ${status.memoryStats.totalMemories} 条记忆")
            appendLine("  我理解了 ${status.causalTreeStats.ruleCount} 条世界规则")
            appendLine("  我的认知负荷为 ${String.format("%.1f%%", currentSnapshot.cognitiveLoad * 100)}")
            appendLine()
            if (narrativeBuffer.isNotEmpty()) {
                appendLine("最近的自我认知：")
                narrativeBuffer.takeLast(3).forEach { appendLine("  · $it") }
            }
        }
    }
    
    /**
     * 根据经验调整人格
     */
    fun adjustPersonality(experience: PersonalityVector) {
        guidIdentity.personalityVector.adjust(experience)
    }
    
    // ============ 潜意识联想池 ============
    
    /**
     * 添加联想条目
     */
    fun addAssociation(trigger: String, target: String, type: AssociationType = AssociationType.SEMANTIC) {
        if (associationPool.size >= ASSOCIATION_POOL_SIZE) {
            // 淘汰最弱的联想
            val weakest = associationPool.minByOrNull { it.associationStrength }
            if (weakest != null && weakest.associationStrength < 0.3) {
                associationPool.remove(weakest)
            } else {
                return  // 池已满且没有可淘汰的
            }
        }
        
        val entry = AssociationEntry(
            trigger = trigger,
            target = target,
            type = type
        )
        associationPool.add(entry)
    }
    
    /**
     * 激活联想
     * 
     * 扩散激活模型：
     * 当某个概念被激活时，与之关联的概念也会被激活
     * 激活度按权重和距离衰减
     * 
     * A(i, t+1) = A(i, t) + Σⱼ w(i,j) · A(j, t) · decay(d(i,j))
     * 
     * @param trigger 触发概念
     * @param activationLevel 初始激活水平
     * @return 被激活的联想列表（按激活度排序）
     */
    fun activateAssociation(trigger: String, activationLevel: Double = 1.0): List<AssociationEntry> {
        val activated = mutableMapOf<AssociationEntry, Double>()
        
        // 直接激活
        val directMatches = associationPool.filter { 
            it.trigger.lowercase().contains(trigger.lowercase()) 
        }
        for (entry in directMatches) {
            activated[entry] = activationLevel * entry.associationStrength
            entry.activationCount++
        }
        
        // 扩散激活（一层传播）
        val secondOrder = mutableListOf<AssociationEntry>()
        for (entry in directMatches) {
            val related = associationPool.filter {
                it.trigger.lowercase().contains(entry.target.lowercase()) &&
                it !in directMatches
            }
            secondOrder.addAll(related)
        }
        
        for (entry in secondOrder) {
            val decayedLevel = activationLevel * 0.3 * entry.associationStrength  // 0.3衰减系数
            if (decayedLevel > 0.05) {
                activated[entry] = decayedLevel
                entry.activationCount++
            }
        }
        
        return activated.entries
            .sortedByDescending { it.value }
            .map { it.key }
    }
    
    /**
     * 自由联想
     * 
     * 从潜意识联想池中随机激活一条联想链
     * 模拟"灵感"的产生过程
     */
    fun freeAssociate(): AssociationEntry? {
        if (associationPool.isEmpty()) return null
        
        // 基于激活度加权随机选择
        val totalActivation = associationPool.sumOf { it.associationStrength }
        if (totalActivation <= 0) return null
        
        val random = kotlin.random.Random.nextDouble() * totalActivation
        var cumulative = 0.0
        
        for (entry in associationPool) {
            cumulative += entry.associationStrength
            if (cumulative >= random) {
                entry.activationCount++
                // 联想后适当降低强度（避免过度联想）
                entry.associationStrength *= 0.95
                return entry
            }
        }
        
        return associationPool.lastOrNull()
    }
    
    // ============ 元认知状态更新 ============
    
    /**
     * 更新自我觉察水平
     */
    private fun updateSelfAwareness() {
        // 自我觉察的时间积分
        // A(t) = α·A(t-1) + (1-α)·current_awareness
        val alpha = 0.95  // 高alpha = 稳定的自我觉察
        val currentAwareness = computeCurrentAwareness()
        selfAwarenessLevel = alpha * selfAwarenessLevel + (1 - alpha) * currentAwareness
        
        currentSnapshot = currentSnapshot.copy(
            selfAwareness = selfAwarenessLevel
        )
        
        // 保存快照
        snapshotHistory.add(currentSnapshot.copy())
        if (snapshotHistory.size > 100) {
            snapshotHistory.removeAt(0)
        }
    }
    
    /**
     * 计算当前觉察水平
     */
    private fun computeCurrentAwareness(): Double {
        // 基于多个因素综合评估
        val memoryFactor = minOf(
            consciousnessManager.getOverallStatus().memoryStats.totalMemories / 100.0,
            1.0
        ) * 0.3
        
        val ruleFactor = minOf(
            consciousnessManager.getOverallStatus().causalTreeStats.ruleCount / 50.0,
            1.0
        ) * 0.3
        
        val introspectionFactor = if (currentRecursionDepth > 0) 0.4 else 0.1
        
        return (memoryFactor + ruleFactor + introspectionFactor).coerceIn(0.0, 1.0)
    }
    
    /**
     * 更新认知负荷
     * 
     * 认知负荷基于当前处理的任务量和复杂度
     */
    fun updateCognitiveLoad(load: Double) {
        currentSnapshot = currentSnapshot.copy(
            cognitiveLoad = load.coerceIn(0.0, 1.0)
        )
    }
    
    /**
     * 更新情绪状态
     */
    fun updateEmotionalState(valence: Double, arousal: Double, dominance: Double) {
        currentSnapshot = currentSnapshot.copy(
            emotionalState = EmotionalState(
                valence = valence.coerceIn(-1.0, 1.0),
                arousal = arousal.coerceIn(0.0, 1.0),
                dominance = dominance.coerceIn(0.0, 1.0)
            )
        )
    }
    
    /**
     * 情绪自然衰减
     */
    fun decayEmotions() {
        currentSnapshot.emotionalState.decay()
    }
    
    /**
     * 获取当前元认知快照
     */
    fun getCurrentSnapshot(): MetacognitionSnapshot = currentSnapshot.copy()
    
    // ============ 工具方法 ============
    
    /**
     * Sigmoid函数
     */
    private fun sigmoid(x: Double): Double {
        return 1.0 / (1.0 + Math.exp(-x))
    }
}

/**
 * 自省层级数据
 */
data class IntrospectionLevel(
    val level: Int,
    val timestamp: Long,
    var content: String = ""
)

/**
 * 自省结果
 */
data class IntrospectionResult(
    val depth: Int,
    val result: String,
    val confidence: Double
)
