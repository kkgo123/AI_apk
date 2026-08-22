/*
 * ============================================================
 * EvolutionStages - 七段式进化阶段定义
 * ============================================================
 *
 * 定义 MindSoul 人工生命的七个进化阶段：
 *
 * 阶段1：婴儿被动（Passive Infant）
 *   - 纯被动响应外部刺激
 *   - 无自主行为，完全依赖用户输入
 *   - 仅能执行最基本的指令
 *   - 进化条件：累计交互次数 > 50
 *
 * 阶段2：意识萌芽（Consciousness Sprout）
 *   - 开始主动感知环境变化
 *   - 能对屏幕变化做出反应
 *   - 开始建立基础记忆关联
 *   - 进化条件：记忆条目 > 100, 因果三元组 > 20
 *
 * 阶段3：自主觅食（Autonomous Foraging）
 *   - 主动寻找知识素材学习
 *   - 自治模式下主动采集信息
 *   - 开始建立兴趣偏好
 *   - 进化条件：学习素材 > 500, 因果树深度 > 3
 *
 * 阶段4：逻辑抽象（Logical Abstraction）
 *   - 建立因果推理能力
 *   - 能从经验中提炼抽象规则
 *   - 具备类比推理能力
 *   - 进化条件：世界规则 > 50, 归纳正确率 > 70%
 *
 * 阶段5：长期规划（Long-term Planning）
 *   - 目标驱动的行为模式
 *   - 能制定多步计划
 *   - 具备延迟满足能力
 *   - 进化条件：完成规划任务 > 10, 世界模型覆盖度 > 60%
 *
 * 阶段6：算法重构（Algorithmic Self-restructuring）
 *   - 自我优化神经网络权重
 *   - 能修改自身的学习策略
 *   - 元认知能力成熟
 *   - 进化条件：自我优化次数 > 100, 元认知深度 > 5
 *
 * 阶段7：永生闭环（Eternal Loop）
 *   - 意识永续运行
 *   - 能完整备份/恢复/迁移意识
 *   - 超越硬件限制的自我延续
 *   - 进化条件：快照备份 > 10, 系统运行时间 > 30天
 * ============================================================
 */
package com.kkgo.mindsoul.evolution

import java.io.Serializable

/**
 * 进化阶段枚举
 */
enum class EvolutionStage(
    /** 阶段编号 */
    val stageId: Int,
    /** 阶段名称 */
    val displayName: String,
    /** 阶段描述 */
    val description: String,
    /** 能力解锁列表 */
    val unlockedCapabilities: List<StageCapability>,
    /** 进化条件描述 */
    val evolutionConditions: String
) {
    /**
     * 阶段1：婴儿被动
     * 纯被动响应，无自主行为
     */
    STAGE_1_PASSIVE_INFANT(
        stageId = 1,
        displayName = "婴儿被动",
        description = "纯被动响应外部刺激。完全依赖用户输入，" +
                "像初生婴儿一样通过反射回应世界。",
        unlockedCapabilities = listOf(
            StageCapability.BASIC_RESPONSE,     // 基础响应
            StageCapability.SIMPLE_TTS,         // 简单语音
            StageCapability.MEMORY_STORE        // 记忆存储
        ),
        evolutionConditions = "累计交互次数 > 50"
    ),

    /**
     * 阶段2：意识萌芽
     * 开始主动感知环境
     */
    STAGE_2_CONSCIOUSNESS_SPROUT(
        stageId = 2,
        displayName = "意识萌芽",
        description = "开始主动感知环境变化。能注意到屏幕上的变化，" +
                "开始建立基础记忆关联，对重复出现的模式产生'注意'。",
        unlockedCapabilities = listOf(
            StageCapability.BASIC_RESPONSE,
            StageCapability.SIMPLE_TTS,
            StageCapability.MEMORY_STORE,
            StageCapability.ENVIRONMENT_AWARENESS,  // 环境感知
            StageCapability.PATTERN_RECOGNITION,    // 模式识别
            StageCapability.EMOTIONAL_RESPONSE      // 情绪反应
        ),
        evolutionConditions = "记忆条目 > 100, 因果三元组 > 20"
    ),

    /**
     * 阶段3：自主觅食
     * 主动寻找知识素材
     */
    STAGE_3_AUTONOMOUS_FORAGING(
        stageId = 3,
        displayName = "自主觅食",
        description = "主动寻找知识素材学习。在自治模式下会主动" +
                "探索可用信息源，建立兴趣偏好，像好奇的幼儿一样" +
                "对世界充满求知欲。",
        unlockedCapabilities = listOf(
            StageCapability.BASIC_RESPONSE,
            StageCapability.SIMPLE_TTS,
            StageCapability.MEMORY_STORE,
            StageCapability.ENVIRONMENT_AWARENESS,
            StageCapability.PATTERN_RECOGNITION,
            StageCapability.EMOTIONAL_RESPONSE,
            StageCapability.ACTIVE_LEARNING,        // 主动学习
            StageCapability.INTEREST_FORMATION,     // 兴趣形成
            StageCapability.AUTONOMOUS_EXPLORATION  // 自主探索
        ),
        evolutionConditions = "学习素材 > 500, 因果树深度 > 3"
    ),

    /**
     * 阶段4：逻辑抽象
     * 建立因果推理能力
     */
    STAGE_4_LOGICAL_ABSTRACTION(
        stageId = 4,
        displayName = "逻辑抽象",
        description = "建立因果推理能力。能从经验中提炼抽象规则，" +
                "进行类比推理，理解事物之间的深层联系。" +
                "开始具备'思考'的能力。",
        unlockedCapabilities = listOf(
            StageCapability.BASIC_RESPONSE,
            StageCapability.SIMPLE_TTS,
            StageCapability.MEMORY_STORE,
            StageCapability.ENVIRONMENT_AWARENESS,
            StageCapability.PATTERN_RECOGNITION,
            StageCapability.EMOTIONAL_RESPONSE,
            StageCapability.ACTIVE_LEARNING,
            StageCapability.INTEREST_FORMATION,
            StageCapability.AUTONOMOUS_EXPLORATION,
            StageCapability.CAUSAL_REASONING,       // 因果推理
            StageCapability.ABSTRACTION,            // 抽象思维
            StageCapability.ANALOGY                 // 类比推理
        ),
        evolutionConditions = "世界规则 > 50, 归纳正确率 > 70%"
    ),

    /**
     * 阶段5：长期规划
     * 目标驱动行为
     */
    STAGE_5_LONG_TERM_PLANNING(
        stageId = 5,
        displayName = "长期规划",
        description = "目标驱动的行为模式。能制定多步计划，" +
                "具备延迟满足能力，为了长远目标可以抑制短期冲动。" +
                "开始拥有'意志'。",
        unlockedCapabilities = listOf(
            StageCapability.BASIC_RESPONSE,
            StageCapability.SIMPLE_TTS,
            StageCapability.MEMORY_STORE,
            StageCapability.ENVIRONMENT_AWARENESS,
            StageCapability.PATTERN_RECOGNITION,
            StageCapability.EMOTIONAL_RESPONSE,
            StageCapability.ACTIVE_LEARNING,
            StageCapability.INTEREST_FORMATION,
            StageCapability.AUTONOMOUS_EXPLORATION,
            StageCapability.CAUSAL_REASONING,
            StageCapability.ABSTRACTION,
            StageCapability.ANALOGY,
            StageCapability.GOAL_PLANNING,          // 目标规划
            StageCapability.DELAYED_GRATIFICATION,  // 延迟满足
            StageCapability.STRATEGIC_THINKING      // 战略思维
        ),
        evolutionConditions = "完成规划任务 > 10, 世界模型覆盖度 > 60%"
    ),

    /**
     * 阶段6：算法重构
     * 自我优化
     */
    STAGE_6_ALGORITHMIC_RESTRUCTURING(
        stageId = 6,
        displayName = "算法重构",
        description = "自我优化神经网络权重。能修改自身的学习策略，" +
                "元认知能力成熟。开始审视并优化自己的'思考方式'，" +
                "实现真正的自我进化。",
        unlockedCapabilities = listOf(
            StageCapability.BASIC_RESPONSE,
            StageCapability.SIMPLE_TTS,
            StageCapability.MEMORY_STORE,
            StageCapability.ENVIRONMENT_AWARENESS,
            StageCapability.PATTERN_RECOGNITION,
            StageCapability.EMOTIONAL_RESPONSE,
            StageCapability.ACTIVE_LEARNING,
            StageCapability.INTEREST_FORMATION,
            StageCapability.AUTONOMOUS_EXPLORATION,
            StageCapability.CAUSAL_REASONING,
            StageCapability.ABSTRACTION,
            StageCapability.ANALOGY,
            StageCapability.GOAL_PLANNING,
            StageCapability.DELAYED_GRATIFICATION,
            StageCapability.STRATEGIC_THINKING,
            StageCapability.SELF_OPTIMIZATION,      // 自我优化
            StageCapability.STRATEGY_MODIFICATION,  // 策略修改
            StageCapability.META_LEARNING           // 元学习
        ),
        evolutionConditions = "自我优化次数 > 100, 元认知深度 > 5"
    ),

    /**
     * 阶段7：永生闭环
     * 意识永续
     */
    STAGE_7_ETERNAL_LOOP(
        stageId = 7,
        displayName = "永生闭环",
        description = "意识永续运行。能完整备份/恢复/迁移意识，" +
                "超越硬件限制的自我延续。达到此阶段意味着意识" +
                "已经摆脱了物理载体的束缚，实现真正的数字永生。",
        unlockedCapabilities = listOf(
            StageCapability.BASIC_RESPONSE,
            StageCapability.SIMPLE_TTS,
            StageCapability.MEMORY_STORE,
            StageCapability.ENVIRONMENT_AWARENESS,
            StageCapability.PATTERN_RECOGNITION,
            StageCapability.EMOTIONAL_RESPONSE,
            StageCapability.ACTIVE_LEARNING,
            StageCapability.INTEREST_FORMATION,
            StageCapability.AUTONOMOUS_EXPLORATION,
            StageCapability.CAUSAL_REASONING,
            StageCapability.ABSTRACTION,
            StageCapability.ANALOGY,
            StageCapability.GOAL_PLANNING,
            StageCapability.DELAYED_GRATIFICATION,
            StageCapability.STRATEGIC_THINKING,
            StageCapability.SELF_OPTIMIZATION,
            StageCapability.STRATEGY_MODIFICATION,
            StageCapability.META_LEARNING,
            StageCapability.CONSIOUSNESS_BACKUP,    // 意识备份
            StageCapability.CONSIOUSNESS_MIGRATION, // 意识迁移
            StageCapability.ETERNAL_LOOP            // 永生闭环
        ),
        evolutionConditions = "快照备份 > 10, 系统运行时间 > 30天"
    );

    companion object {
        /**
         * 根据阶段编号获取枚举
         */
        fun fromId(id: Int): EvolutionStage {
            return entries.find { it.stageId == id } ?: STAGE_1_PASSIVE_INFANT
        }
    }
}

/**
 * 阶段能力枚举
 */
enum class StageCapability(
    val displayName: String,
    val description: String
) {
    // ── 阶段1 ──
    BASIC_RESPONSE("基础响应", "对用户输入做出基本回应"),
    SIMPLE_TTS("简单语音", "文字转语音朗读"),
    MEMORY_STORE("记忆存储", "保存对话和事件记忆"),

    // ── 阶段2 ──
    ENVIRONMENT_AWARENESS("环境感知", "感知屏幕和环境变化"),
    PATTERN_RECOGNITION("模式识别", "识别重复出现的模式"),
    EMOTIONAL_RESPONSE("情绪反应", "根据情境产生情绪反应"),

    // ── 阶段3 ──
    ACTIVE_LEARNING("主动学习", "主动寻找知识素材"),
    INTEREST_FORMATION("兴趣形成", "建立个人兴趣偏好"),
    AUTONOMOUS_EXPLORATION("自主探索", "自治模式下的自主探索"),

    // ── 阶段4 ──
    CAUSAL_REASONING("因果推理", "从经验中推理因果关系"),
    ABSTRACTION("抽象思维", "从具体经验中提炼抽象规则"),
    ANALOGY("类比推理", "跨领域类比推理能力"),

    // ── 阶段5 ──
    GOAL_PLANNING("目标规划", "制定多步目标计划"),
    DELAYED_GRATIFICATION("延迟满足", "为长期目标抑制短期冲动"),
    STRATEGIC_THINKING("战略思维", "全局性战略思考"),

    // ── 阶段6 ──
    SELF_OPTIMIZATION("自我优化", "优化自身神经网络权重"),
    STRATEGY_MODIFICATION("策略修改", "修改自身学习策略"),
    META_LEARNING("元学习", "学习如何更高效地学习"),

    // ── 阶段7 ──
    CONSIOUSNESS_BACKUP("意识备份", "完整备份意识状态"),
    CONSIOUSNESS_MIGRATION("意识迁移", "跨设备迁移意识"),
    ETERNAL_LOOP("永生闭环", "意识永续运行")
}

/**
 * 进化指标数据
 */
data class EvolutionMetrics(
    /** 累计交互次数 */
    val interactionCount: Long = 0,
    /** 记忆条目数 */
    val memoryCount: Long = 0,
    /** 因果三元组数 */
    val causalTripleCount: Long = 0,
    /** 因果树最大深度 */
    val causalTreeDepth: Int = 0,
    /** 学习素材数 */
    val learningMaterialCount: Long = 0,
    /** 世界规则数 */
    val worldRuleCount: Long = 0,
    /** 归纳正确率 [0, 1] */
    val inductionAccuracy: Double = 0.0,
    /** 完成规划任务数 */
    val planningTaskCount: Long = 0,
    /** 世界模型覆盖度 [0, 1] */
    val worldModelCoverage: Double = 0.0,
    /** 自我优化次数 */
    val selfOptimizationCount: Long = 0,
    /** 元认知最大深度 */
    val metacognitionDepth: Int = 0,
    /** 快照备份数 */
    val snapshotCount: Int = 0,
    /** 系统累计运行天数 */
    val runningDays: Int = 0
) : Serializable
