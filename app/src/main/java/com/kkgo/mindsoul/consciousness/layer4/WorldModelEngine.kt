/*
 * ============================================================
 * WorldModelEngine - 第四层：四维元认知世界模型框架
 * ============================================================
 *
 * 世界模型是人工生命对"世界如何运作"的内部模拟。
 * 它整合了时空逻辑、物理规则和社会逻辑，形成统一的认知框架。
 *
 * 四维模型：
 * 1. 空间维度 - 物体的位置、距离、方位关系
 * 2. 时间维度 - 事件的先后顺序、持续时长、因果关系
 * 3. 物理规则 - 力学、热力学、电磁学的基本规律
 * 4. 社会逻辑 - 人际关系、社会规范、博弈策略
 *
 * 这个模型允许人工生命：
 * - 预测事件的发展趋势
 * - 模拟假设场景的后果
 * - 理解物理世界和社会世界
 * ============================================================
 */
package com.kkgo.mindsoul.consciousness.layer4

import android.util.Log
import com.kkgo.mindsoul.model.*
import kotlin.math.*

/**
 * 第四层：四维元认知世界模型引擎
 * 
 * 维护和更新人工生命的内部世界模型
 */
class WorldModelEngine {
    
    companion object {
        private const val TAG = "WorldModel"
        
        /** 世界模型更新频率（毫秒） */
        const val UPDATE_INTERVAL = 5000L
    }
    
    // ============ 空间模型 ============
    
    /** 空间实体注册表 */
    private val spatialEntities = mutableMapOf<String, SpatialEntity>()
    
    /** 空间关系缓存 */
    private val spatialRelations = mutableMapOf<Pair<String, String>, SpatialRelation>()
    
    // ============ 时间模型 ============
    
    /** 事件时间线 */
    private val timeline = mutableListOf<TimelineEvent>()
    
    /** 时间规律（周期性事件） */
    private val temporalPatterns = mutableListOf<TemporalPattern>()
    
    // ============ 物理规则模型 ============
    
    /** 已学习的物理规则 */
    private val physicalRules = mutableListOf<PhysicalRule>()
    
    // ============ 社会逻辑模型 ============
    
    /** 社交实体（人物/角色） */
    private val socialEntities = mutableMapOf<String, SocialEntity>()
    
    /** 社会规则 */
    private val socialRules = mutableListOf<SocialRule>()
    
    // ============ 预测引擎 ============
    
    /** 预测缓存 */
    private val predictionCache = mutableMapOf<String, Prediction>()
    
    // ============ 初始化 ============
    
    fun initialize() {
        Log.i(TAG, "正在初始化第四层：四维元认知世界模型...")
        
        // 注入基础物理规则
        injectBasePhysicalRules()
        
        // 注入基础社会逻辑
        injectBaseSocialRules()
        
        Log.i(TAG, "世界模型初始化完成")
    }
    
    // ============ 时空逻辑体系 ============
    
    /**
     * 注册空间实体
     * 
     * @param id 实体唯一标识
     * @param name 实体名称
     * @param coord 时空坐标
     * @param category 实体类别
     */
    fun registerSpatialEntity(
        id: String,
        name: String,
        coord: SpacetimeCoord,
        category: String = "unknown"
    ) {
        spatialEntities[id] = SpatialEntity(
            id = id,
            name = name,
            coord = coord,
            category = category
        )
        Log.d(TAG, "注册空间实体: $name at (${"%.1f,%.1f,%.1f".format(coord.x, coord.y, coord.z)})")
    }
    
    /**
     * 更新实体位置
     */
    fun updateEntityPosition(id: String, newCoord: SpacetimeCoord) {
        val entity = spatialEntities[id] ?: return
        val oldCoord = entity.coord
        
        // 记录运动向量
        val velocity = Velocity3D(
            x = (newCoord.x - oldCoord.x) / max((newCoord.t - oldCoord.t), 1),
            y = (newCoord.y - oldCoord.y) / max((newCoord.t - oldCoord.t), 1),
            z = (newCoord.z - oldCoord.z) / max((newCoord.t - oldCoord.t), 1)
        )
        
        spatialEntities[id] = entity.copy(coord = newCoord, velocity = velocity)
    }
    
    /**
     * 计算两个实体的空间关系
     * 
     * @return 空间关系描述
     */
    fun computeSpatialRelation(id1: String, id2: String): SpatialRelation? {
        val e1 = spatialEntities[id1] ?: return null
        val e2 = spatialEntities[id2] ?: return null
        
        val dx = e2.coord.x - e1.coord.x
        val dy = e2.coord.y - e1.coord.y
        val dz = e2.coord.z - e1.coord.z
        val distance = sqrt(dx * dx + dy * dy + dz * dz)
        
        // 确定方位关系
        val direction = when {
            distance < 1.0 -> "adjacent"         // 相邻
            abs(dx) > abs(dy) && abs(dx) > abs(dz) -> if (dx > 0) "east" else "west"
            abs(dy) > abs(dz) -> if (dy > 0) "north" else "south"
            else -> if (dz > 0) "above" else "below"
        }
        
        val relation = SpatialRelation(
            entity1Id = id1,
            entity2Id = id2,
            distance = distance,
            direction = direction,
            timestamp = System.currentTimeMillis()
        )
        
        spatialRelations[Pair(id1, id2)] = relation
        return relation
    }
    
    /**
     * 记录时间线事件
     */
    fun recordTimelineEvent(event: String, category: String = "general") {
        timeline.add(TimelineEvent(
            event = event,
            category = category,
            timestamp = System.currentTimeMillis()
        ))
        
        // 保持时间线大小
        if (timeline.size > 10000) {
            // 移除最旧的事件（保留重要的）
            timeline.sortByDescending { it.importance }
            while (timeline.size > 10000) {
                timeline.removeAt(timeline.lastIndex)
            }
        }
    }
    
    /**
     * 检测时间模式（周期性）
     * 
     * 分析时间线事件，寻找重复出现的模式
     */
    fun detectTemporalPatterns() {
        if (timeline.size < 10) return
        
        // 按类别分组
        val byCategory = timeline.groupBy { it.category }
        
        for ((category, events) in byCategory) {
            if (events.size < 5) continue
            
            // 计算相邻事件的间隔
            val intervals = mutableListOf<Long>()
            for (i in 1 until events.size) {
                intervals.add(events[i].timestamp - events[i - 1].timestamp)
            }
            
            if (intervals.isEmpty()) continue
            
            // 检测间隔的周期性（变异系数小于0.3则认为有规律）
            val meanInterval = intervals.average()
            if (meanInterval <= 0) continue
            
            val variance = intervals.map { (it - meanInterval) * (it - meanInterval) }.average()
            val stdDev = sqrt(variance)
            val coefficientOfVariation = stdDev / meanInterval
            
            if (coefficientOfVariation < 0.3) {
                // 检测到周期性模式
                val existingPattern = temporalPatterns.find { it.category == category }
                if (existingPattern != null) {
                    existingPattern.confidence = (existingPattern.confidence * 0.9 + 0.1)
                        .coerceAtMost(1.0)
                } else {
                    temporalPatterns.add(TemporalPattern(
                        category = category,
                        meanIntervalMs = meanInterval.toLong(),
                        confidence = 0.5
                    ))
                    Log.d(TAG, "检测到时间模式: [$category] 周期≈${meanInterval/1000}s")
                }
            }
        }
    }
    
    // ============ 物理规则模型 ============
    
    /**
     * 注入基础物理规则
     */
    private fun injectBasePhysicalRules() {
        physicalRules.addAll(listOf(
            PhysicalRule(
                id = "PHYS_GRAVITY",
                description = "物体受重力影响向下运动",
                ruleType = PhysicalRuleType.KINEMATIC,
                confidence = 0.99,
                formula = "F = m·g, g ≈ 9.8 m/s²"
            ),
            PhysicalRule(
                id = "PHYS_INERTIA",
                description = "物体保持当前运动状态，除非受到外力",
                ruleType = PhysicalRuleType.KINEMATIC,
                confidence = 0.95,
                formula = "F = m·a"
            ),
            PhysicalRule(
                id = "PHYS_CONSERVATION_ENERGY",
                description = "能量守恒：能量可以从一种形式转化为另一种，但总量不变",
                ruleType = PhysicalRuleType.THERMODYNAMIC,
                confidence = 0.99,
                formula = "ΔE_total = 0"
            ),
            PhysicalRule(
                id = "PHYS_ENTROPY",
                description = "熵增原理：封闭系统的熵总是增大",
                ruleType = PhysicalRuleType.THERMODYNAMIC,
                confidence = 0.95,
                formula = "ΔS ≥ 0"
            ),
            PhysicalRule(
                id = "PHYS_BUOYANCY",
                description = "浮力原理：物体在流体中受到向上的浮力",
                ruleType = PhysicalRuleType.FLUID,
                confidence = 0.90,
                formula = "F_b = ρ·g·V"
            )
        ))
    }
    
    /**
     * 注入基础社会逻辑
     */
    private fun injectBaseSocialRules() {
        socialRules.addAll(listOf(
            SocialRule(
                id = "SOCIAL_RECIPROCITY",
                description = "互惠原则：对他人友善，他人倾向于回报友善",
                ruleType = SocialRuleType.COOPERATIVE,
                confidence = 0.8
            ),
            SocialRule(
                id = "SOCIAL_HIERARCHY",
                description = "层级结构：社会群体中存在地位和权力的层级",
                ruleType = SocialRuleType.STRUCTURAL,
                confidence = 0.75
            ),
            SocialRule(
                id = "SOCIAL_CONFORMITY",
                description = "从众效应：个体倾向于与群体行为保持一致",
                ruleType = SocialRuleType.BEHAVIORAL,
                confidence = 0.70
            ),
            SocialRule(
                id = "SOCIAL_TRUST",
                description = "信任建立需要时间，破坏只需瞬间",
                ruleType = SocialRuleType.RELATIONAL,
                confidence = 0.85
            )
        ))
    }
    
    /**
     * 学习新的物理规则
     * 
     * 基于观察到的模式提炼物理规则
     */
    fun learnPhysicalRule(description: String, observationCount: Int = 1): PhysicalRule {
        val rule = PhysicalRule(
            id = "PHYS_LEARNED_${System.nanoTime()}",
            description = description,
            ruleType = PhysicalRuleType.EMPIRICAL,
            confidence = 0.3 + observationCount * 0.1,
            formula = ""
        )
        physicalRules.add(rule)
        return rule
    }
    
    // ============ 预测引擎 ============
    
    /**
     * 预测事件
     * 
     * 基于世界模型预测未来可能发生的事件
     * 
     * @param context 当前情境描述
     * @param horizon 预测时间范围（毫秒）
     * @return 预测列表（按可能性排序）
     */
    fun predict(context: String, horizon: Long = 60000L): List<Prediction> {
        val predictions = mutableListOf<Prediction>()
        
        // 1. 基于物理规则预测
        for (rule in physicalRules) {
            if (rule.confidence > 0.5) {
                predictions.add(Prediction(
                    description = "根据${rule.description}，可能发生的后续事件",
                    probability = rule.confidence * 0.6,
                    timeHorizon = horizon,
                    basis = "PHYS:${rule.id}"
                ))
            }
        }
        
        // 2. 基于时间模式预测
        for (pattern in temporalPatterns) {
            val relevantEvents = timeline.filter { it.category == pattern.category }
            if (relevantEvents.isNotEmpty()) {
                val lastEvent = relevantEvents.last()
                val timeSince = System.currentTimeMillis() - lastEvent.timestamp
                
                if (timeSince >= pattern.meanIntervalMs * 0.7) {
                    predictions.add(Prediction(
                        description = "基于历史模式，[${pattern.category}]事件可能即将发生",
                        probability = pattern.confidence * 0.7,
                        timeHorizon = pattern.meanIntervalMs,
                        basis = "TEMPORAL:${pattern.category}"
                    ))
                }
            }
        }
        
        // 3. 基于因果关系预测
        val relatedCausal = context.lowercase()
        predictions.add(Prediction(
            description = "基于因果链的推演",
            probability = 0.4,
            timeHorizon = horizon,
            basis = "CAUSAL"
        ))
        
        // 按概率排序
        return predictions.sortedByDescending { it.probability }.take(10)
    }
    
    /**
     * 模拟假设场景
     * 
     * 在内部世界模型中模拟"如果...会怎样"
     */
    fun simulateHypothetical(hypothesis: String): SimulationResult {
        // 简单的假设评估
        val consistency = evaluateHypothesis(hypothesis)
        
        return SimulationResult(
            hypothesis = hypothesis,
            consistencyScore = consistency,
            implications = generateImplications(hypothesis, consistency),
            confidence = 0.5
        )
    }
    
    /**
     * 评估假设与现有世界模型的一致性
     */
    private fun evaluateHypothesis(hypothesis: String): Double {
        val hypLower = hypothesis.lowercase()
        
        // 检查是否与物理规则矛盾
        var penalty = 0.0
        for (rule in physicalRules) {
            if (rule.confidence > 0.8) {
                val keywords = rule.description.lowercase().split(" ").filter { it.length > 2 }
                val matchCount = keywords.count { hypLower.contains(it) }
                if (matchCount >= 2) {
                    // 检查是否包含否定词
                    val negationWords = listOf("不", "没有", "无法", "不会", "违反")
                    if (negationWords.any { hypLower.contains(it) }) {
                        penalty += rule.confidence * 0.3
                    }
                }
            }
        }
        
        return (1.0 - penalty).coerceIn(0.0, 1.0)
    }
    
    /**
     * 生成假设的推论链
     */
    private fun generateImplications(hypothesis: String, consistency: Double): List<String> {
        val implications = mutableListOf<String>()
        
        if (consistency > 0.7) {
            implications.add("该假设与现有世界模型基本一致")
            implications.add("基于现有知识，可能产生的影响需要进一步观察")
        } else if (consistency > 0.3) {
            implications.add("该假设部分与现有认知冲突，需要修正世界模型")
            implications.add("建议收集更多证据以验证")
        } else {
            implications.add("该假设与现有世界模型严重矛盾")
            implications.add("要么假设错误，要么世界模型需要重大修正")
        }
        
        return implications
    }
    
    // ============ 状态查询 ============
    
    fun getStatus(): WorldModelStatus {
        return WorldModelStatus(
            spatialEntityCount = spatialEntities.size,
            timelineEventCount = timeline.size,
            physicalRuleCount = physicalRules.size,
            socialRuleCount = socialRules.size,
            temporalPatternCount = temporalPatterns.size,
            predictionCacheSize = predictionCache.size
        )
    }
}

// ============ 辅助数据类 ============

data class SpatialEntity(
    val id: String,
    val name: String,
    val coord: SpacetimeCoord,
    val category: String,
    val velocity: Velocity3D = Velocity3D()
)

data class Velocity3D(
    val x: Double = 0.0,
    val y: Double = 0.0,
    val z: Double = 0.0
)

data class SpatialRelation(
    val entity1Id: String,
    val entity2Id: String,
    val distance: Double,
    val direction: String,
    val timestamp: Long
)

data class TimelineEvent(
    val event: String,
    val category: String,
    val timestamp: Long,
    val importance: Double = 0.5
)

data class TemporalPattern(
    val category: String,
    val meanIntervalMs: Long,
    var confidence: Double = 0.5
)

data class PhysicalRule(
    val id: String,
    val description: String,
    val ruleType: PhysicalRuleType,
    var confidence: Double,
    val formula: String
)

enum class PhysicalRuleType {
    KINEMATIC,       // 运动学
    THERMODYNAMIC,   // 热力学
    FLUID,           // 流体力学
    ELECTROMAGNETIC, // 电磁学
    EMPIRICAL        // 经验规则
}

data class SocialEntity(
    val id: String,
    val name: String,
    val trustLevel: Double = 0.5,
    val relationshipStrength: Double = 0.0
)

data class SocialRule(
    val id: String,
    val description: String,
    val ruleType: SocialRuleType,
    var confidence: Double
)

enum class SocialRuleType {
    COOPERATIVE,   // 合作型
    COMPETITIVE,   // 竞争型
    STRUCTURAL,    // 结构型
    BEHAVIORAL,    // 行为型
    RELATIONAL     // 关系型
}

data class Prediction(
    val description: String,
    val probability: Double,
    val timeHorizon: Long,
    val basis: String
)

data class SimulationResult(
    val hypothesis: String,
    val consistencyScore: Double,
    val implications: List<String>,
    val confidence: Double
)

data class WorldModelStatus(
    val spatialEntityCount: Int,
    val timelineEventCount: Int,
    val physicalRuleCount: Int,
    val socialRuleCount: Int,
    val temporalPatternCount: Int,
    val predictionCacheSize: Int
)
