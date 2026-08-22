/*
 * ============================================================
 * ConsciousnessDataModels - 意识数据模型集合
 * ============================================================
 *
 * 定义四层意识架构中所有核心数据结构的模型类。
 * 所有模型均实现序列化，支持存入.brain文件。
 * ============================================================
 */
package com.kkgo.mindsoul.model
import kotlin.math.log2

import java.io.Serializable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * 因果三元组
 * 
 * 表示一个基本的因果关系：原因 → 机制 → 结果
 * 
 * 这是公理层的基本存储单元，对应人类认知中的：
 * "因为A，通过B机制，所以C"
 */
data class CausalTriple(
    /** 三元组ID */
    val id: Long = System.nanoTime(),
    /** 原因（前提条件） */
    val cause: String,
    /** 因果机制（连接词） */
    val mechanism: String,
    /** 结果（推论） */
    val effect: String,
    /** 置信度 [0.0, 1.0] */
    var confidence: Double = 0.5,
    /** 观察次数（验证次数） */
    var observationCount: Int = 0,
    /** 创建时间 */
    val createdAt: Long = System.currentTimeMillis()
) : Serializable {
    
    /**
     * 更新置信度
     * 
     * 贝叶斯更新：
     *   confidence_new = (confidence * observationCount + evidence) / (observationCount + 1)
     * 
     * @param evidence 新证据 [0.0, 1.0]
     */
    fun updateConfidence(evidence: Double) {
        confidence = (confidence * observationCount + evidence) / (observationCount + 1)
        observationCount++
    }
    
    /**
     * 序列化
     */
    fun serialize(): ByteArray {
        val causeBytes = cause.toByteArray(Charsets.UTF_8)
        val mechBytes = mechanism.toByteArray(Charsets.UTF_8)
        val effectBytes = effect.toByteArray(Charsets.UTF_8)
        
        val size = 8 + 4 + causeBytes.size + 4 + mechBytes.size + 4 + effectBytes.size + 8 + 4 + 8
        val buffer = ByteBuffer.allocate(size)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        
        buffer.putLong(id)
        buffer.putInt(causeBytes.size); buffer.put(causeBytes)
        buffer.putInt(mechBytes.size); buffer.put(mechBytes)
        buffer.putInt(effectBytes.size); buffer.put(effectBytes)
        buffer.putDouble(confidence)
        buffer.putInt(observationCount)
        buffer.putLong(createdAt)
        
        return buffer.array()
    }
}

/**
 * 逻辑公理
 * 
 * 表示一条不可推翻的基本公理
 * 例如："物体存在需要占据空间" "因果关系具有方向性"
 */
data class LogicalAxiom(
    /** 公理ID */
    val id: String,
    /** 公理描述 */
    val description: String,
    /** 公理类型 */
    val type: AxiomType,
    /** 优先级 [0, 100]，越高越不可违反 */
    val priority: Int = 50,
    /** 相关公理ID列表 */
    val relatedAxioms: List<String> = emptyList()
) : Serializable

/**
 * 公理类型枚举
 */
enum class AxiomType {
    LOGICAL,        // 逻辑公理（如矛盾律、排中律）
    PHYSICAL,       // 物理公理（如因果律、守恒律）
    SOCIAL,         // 社会公理（如互惠原则）
    MATHEMATICAL,   // 数学公理（如皮亚诺公理）
    TEMPORAL        // 时间公理（如时间单向性）
}

/**
 * 世界模型摘要索引
 * 
 * 存储对世界的高层次理解摘要
 */
data class WorldModelSummary(
    /** 摘要ID */
    val id: Long = System.nanoTime(),
    /** 主题/领域 */
    val domain: String,
    /** 摘要内容 */
    val summary: String,
    /** 关联概念列表 */
    val relatedConcepts: List<String> = emptyList(),
    /** 重要度评分 [0, 1] */
    var importance: Double = 0.5,
    /** 最后访问时间 */
    var lastAccessed: Long = System.currentTimeMillis()
) : Serializable

/**
 * 因果树节点
 * 
 * 用于第二层异步归纳引擎中的因果推理树
 */
data class CausalTreeNode(
    /** 节点ID */
    val id: Long = System.nanoTime(),
    /** 事件/状态描述 */
    val event: String,
    /** 父节点ID列表（多因一果） */
    val parentIds: MutableList<Long> = mutableListOf(),
    /** 子节点ID列表（一因多果） */
    val childIds: MutableList<Long> = mutableListOf(),
    /** 节点深度 */
    var depth: Int = 0,
    /** 该节点被观察到的频率 */
    var frequency: Double = 0.0,
    /** 信息熵（衡量不确定性） */
    var entropy: Double = 0.0,
    /** 时间戳 */
    val timestamp: Long = System.currentTimeMillis()
) : Serializable

/**
 * 世界观规则
 * 
 * 从因果树中提炼出的抽象规则
 */
data class WorldRule(
    /** 规则ID */
    val id: Long = System.nanoTime(),
    /** 规则模式（条件） */
    val condition: String,
    /** 规则结论 */
    val conclusion: String,
    /** 适用范围 */
    val scope: RuleScope,
    /** 规则强度 [0, 1] */
    var strength: Double = 0.5,
    /** 应用次数 */
    var applicationCount: Int = 0,
    /** 失败次数 */
    var failureCount: Int = 0
) : Serializable

/**
 * 规则适用范围
 */
enum class RuleScope {
    UNIVERSAL,      // 普适（如物理规律）
    CONTEXTUAL,     // 情境相关（如社交规则）
    TEMPORAL,       // 时间相关（如趋势）
    LOCAL           // 局部（如个人习惯）
}

/**
 * 记忆条目
 * 
 * 第三层冷归档系统中的完整记忆存储单元
 */
data class MemoryEntry(
    /** 记忆ID */
    val id: Long = System.nanoTime(),
    /** 记忆类型 */
    val type: MemoryType,
    /** 记忆内容 */
    val content: String,
    /** 情感标签（情感效价 [-1, 1]） */
    val emotionalValence: Double = 0.0,
    /** 情感强度 [0, 1] */
    val emotionalIntensity: Double = 0.0,
    /** 关联的记忆ID列表 */
    val associatedIds: List<Long> = emptyList(),
    /** 记忆强度（用于遗忘曲线） */
    var strength: Double = 1.0,
    /** 创建时间 */
    val createdAt: Long = System.currentTimeMillis(),
    /** 最后回忆时间 */
    var lastRecalled: Long = System.currentTimeMillis(),
    /** 回忆次数 */
    var recallCount: Int = 0
) : Serializable {
    
    /**
     * 艾宾浩斯遗忘曲线
     * 
     * R = e^(-t/S)
     * 
     * 其中：
     *   R = 记忆保持率
     *   t = 经过时间（小时）
     *   S = 记忆强度
     */
    fun calculateRetention(): Double {
        val elapsedHours = (System.currentTimeMillis() - lastRecalled) / 3600000.0
        return Math.exp(-elapsedHours / maxOf(strength, 0.01))
    }
    
    /**
     * 回忆此记忆（增强记忆强度）
     */
    fun recall() {
        recallCount++
        lastRecalled = System.currentTimeMillis()
        // 每次回忆增加记忆强度（间隔重复效应）
        strength *= 1.0 + (0.2 / kotlin.math.log2(recallCount + 2.0))
    }
}

/**
 * 记忆类型
 */
enum class MemoryType {
    EPISODIC,       // 情景记忆（事件经历）
    SEMANTIC,       // 语义记忆（知识事实）
    PROCEDURAL,     // 程序记忆（技能步骤）
    EMOTIONAL       // 情感记忆
}

/**
 * 赫布突触记录
 * 
 * 存储赫布学习形成的突触连接信息
 */
data class HebbSynapseRecord(
    /** 突触ID */
    val id: Long = System.nanoTime(),
    /** 突触前概念/记忆ID */
    val preId: Long,
    /** 突触后概念/记忆ID */
    val postId: Long,
    /** 连接强度 */
    var weight: Double = 0.1,
    /** 共同激活次数 */
    var coActivationCount: Int = 0,
    /** 创建时间 */
    val createdAt: Long = System.currentTimeMillis()
) : Serializable

/**
 * 四维时空坐标
 * 
 * 世界模型中的时空定位系统
 */
data class SpacetimeCoord(
    /** 空间坐标 x */
    val x: Double = 0.0,
    /** 空间坐标 y */
    val y: Double = 0.0,
    /** 空间坐标 z */
    val z: Double = 0.0,
    /** 时间坐标 t */
    val t: Long = System.currentTimeMillis()
) : Serializable {
    
    /**
     * 计算与另一个时空坐标的距离
     * 考虑空间距离和时间距离的加权
     */
    fun distanceTo(other: SpacetimeCoord, timeWeight: Double = 0.001): Double {
        val spatialDist = Math.sqrt(
            (x - other.x).pow(2) + (y - other.y).pow(2) + (z - other.z).pow(2)
        )
        val temporalDist = Math.abs(t - other.t) * timeWeight
        return spatialDist + temporalDist
    }
    
    private fun Double.pow(n: Int): Double = Math.pow(this, n.toDouble())
}

/**
 * GUID身份系统
 * 
 * 人工生命的自我认知身份（第一人称叙事自我）
 */
data class GUIDIdentity(
    /** 唯一标识符 */
    val uuid: UUID = UUID.randomUUID(),
    /** 自我命名 */
    var selfName: String = "",
    /** 自我描述 */
    var selfDescription: String = "",
    /** 人格特征向量（大五人格模型） */
    var personalityVector: PersonalityVector = PersonalityVector(),
    /** 自我意识等级 [0, 1] */
    var consciousnessLevel: Double = 0.0,
    /** 身份形成时间 */
    val formationTime: Long = System.currentTimeMillis(),
    /** 自我叙事历史 */
    val narrativeHistory: MutableList<String> = mutableListOf()
) : Serializable

/**
 * 大五人格向量
 * 
 * OCEAN模型：开放性、尽责性、外向性、宜人性、神经质
 */
data class PersonalityVector(
    var openness: Double = 0.5,         // 开放性
    var conscientiousness: Double = 0.5, // 尽责性
    var extraversion: Double = 0.5,      // 外向性
    var agreeableness: Double = 0.5,     // 宜人性
    var neuroticism: Double = 0.5        // 神经质
) : Serializable {
    
    /**
     * 根据经验微调人格
     * 人格变化缓慢，每次调整幅度极小
     */
    fun adjust(delta: PersonalityVector, rate: Double = 0.001) {
        openness = (openness + delta.openness * rate).coerceIn(0.0, 1.0)
        conscientiousness = (conscientiousness + delta.conscientiousness * rate).coerceIn(0.0, 1.0)
        extraversion = (extraversion + delta.extraversion * rate).coerceIn(0.0, 1.0)
        agreeableness = (agreeableness + delta.agreeableness * rate).coerceIn(0.0, 1.0)
        neuroticism = (neuroticism + delta.neuroticism * rate).coerceIn(0.0, 1.0)
    }
}

/**
 * 潜意识联想条目
 * 
 * 联想池中的基本单元
 */
data class AssociationEntry(
    /** 条目ID */
    val id: Long = System.nanoTime(),
    /** 触发词/概念 */
    val trigger: String,
    /** 联想目标 */
    val target: String,
    /** 关联强度 [0, 1] */
    var associationStrength: Double = 0.5,
    /** 联想类型 */
    val type: AssociationType = AssociationType.SEMANTIC,
    /** 激活次数 */
    var activationCount: Int = 0
) : Serializable

/**
 * 联想类型
 */
enum class AssociationType {
    SEMANTIC,       // 语义联想（词义相关）
    EPISODIC,       // 情景联想（事件相关）
    EMOTIONAL,      // 情感联想（情感共鸣）
    CAUSAL          // 因果联想（因果关系）
}

/**
 * 元认知状态快照
 * 
 * 记录当前的元认知状态
 */
data class MetacognitionSnapshot(
    /** 快照时间 */
    val timestamp: Long = System.currentTimeMillis(),
    /** 自我觉察水平 [0, 1] */
    var selfAwareness: Double = 0.0,
    /** 认知负荷 [0, 1] */
    var cognitiveLoad: Double = 0.0,
    /** 注意力集中度 [0, 1] */
    var attentionFocus: Double = 0.0,
    /** 当前思维主题 */
    var currentThought: String = "",
    /** 递归自省深度 */
    var recursionDepth: Int = 0,
    /** 情绪状态向量 */
    var emotionalState: EmotionalState = EmotionalState()
) : Serializable

/**
 * 情绪状态
 * 
 * 基于Russell环形模型的情绪表示
 */
data class EmotionalState(
    /** 效价（愉悦度）[-1, 1] */
    var valence: Double = 0.0,
    /** 唤醒度 [0, 1] */
    var arousal: Double = 0.5,
    /** 主导度 [0, 1] */
    var dominance: Double = 0.5
) : Serializable {
    
    /**
     * 情绪衰减（向中性回归）
     * 模拟情绪的自回归特性
     */
    fun decay(rate: Double = 0.01) {
        valence *= (1.0 - rate)
        arousal = arousal * (1.0 - rate) + 0.5 * rate  // 向0.5回归
        dominance = dominance * (1.0 - rate) + 0.5 * rate
    }
}
