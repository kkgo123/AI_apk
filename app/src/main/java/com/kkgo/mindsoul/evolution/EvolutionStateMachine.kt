/*
 * ============================================================
 * EvolutionStateMachine - 进化状态机
 * ============================================================
 *
 * 管理七段式进化的状态转换：
 *
 * 状态机设计：
 *   当前阶段 + 进化指标 → 条件判定 → 是否触发进化
 *
 * 进化规则：
 *   - 只能逐级进化（不可跳跃）
 *   - 进化不可逆（但可回退一级，如异常）
 *   - 每次进化有冷却期（防止频繁切换）
 *   - 进化条件必须全部满足
 *
 * 进化事件：
 *   - 进化触发通知
 *   - 解锁新能力
 *   - 更新意识等级
 *   - 更新化身表现
 * ============================================================
 */
package com.kkgo.mindsoul.evolution

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 进化事件类型
 */
enum class EvolutionEventType {
    /** 阶段进化 */
    STAGE_ADVANCED,
    /** 新能力解锁 */
    CAPABILITY_UNLOCKED,
    /** 进化条件接近（>80%满足） */
    EVOLUTION_APPROACHING,
    /** 进化检查失败 */
    EVOLUTION_CHECK_FAILED
}

/**
 * 进化事件
 */
data class EvolutionEvent(
    val type: EvolutionEventType,
    val fromStage: EvolutionStage?,
    val toStage: EvolutionStage?,
    val message: String,
    val unlockedCapability: StageCapability? = null,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 进化状态机
 */
class EvolutionStateMachine(private val context: Context) {

    companion object {
        private const val TAG = "EvolutionSM"
        private const val PREF_NAME = "mindsoul_evolution"
        private const val KEY_CURRENT_STAGE = "current_stage"
        private const val KEY_TOTAL_INTERACTIONS = "total_interactions"
        /** 进化冷却期（毫秒） */
        private const val EVOLUTION_COOLDOWN_MS = 60_000L  // 1 分钟
    }

    // ============ 持久化 ============
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    // ============ 当前状态 ============
    private val _currentStage = MutableStateFlow(loadStage())
    val currentStageFlow: StateFlow<EvolutionStage> = _currentStage.asStateFlow()
    val currentStage: EvolutionStage get() = _currentStage.value

    // ============ 进化指标 ============
    private var _metrics = EvolutionMetrics()
    val metrics: EvolutionMetrics get() = _metrics

    // ============ 状态机控制 ============
    /** 上次进化时间 */
    private var lastEvolutionTime = 0L
    /** 进化历史 */
    private val evolutionHistory = mutableListOf<EvolutionEvent>()

    // ============ 监听器 ============
    private val listeners = mutableListOf<EvolutionListener>()

    interface EvolutionListener {
        fun onStageAdvanced(from: EvolutionStage, to: EvolutionStage)
        fun onCapabilityUnlocked(stage: EvolutionStage, capability: StageCapability) {}
        fun onEvolutionApproaching(stage: EvolutionStage, progress: Float) {}
    }

    // ============ 初始化 ============

    /**
     * 初始化状态机
     */
    fun initialize() {
        Log.i(TAG, "[初始化] 进化状态机就绪")
        Log.i(TAG, "  当前阶段: ${currentStage.displayName} (阶段${currentStage.stageId})")
    }

    // ============ 核心接口 ============

    /**
     * 更新进化指标
     *
     * 在系统运行过程中持续调用，更新各项指标
     */
    fun updateMetrics(newMetrics: EvolutionMetrics) {
        _metrics = newMetrics

        // 检查是否可以进化
        checkEvolution()
    }

    /**
     * 增加交互计数
     */
    fun recordInteraction() {
        _metrics = _metrics.copy(interactionCount = _metrics.interactionCount + 1)
        checkEvolution()
    }

    /**
     * 检查是否满足进化条件
     */
    fun checkEvolution(): Boolean {
        // 冷却期检查
        if (System.currentTimeMillis() - lastEvolutionTime < EVOLUTION_COOLDOWN_MS) {
            return false
        }

        // 已满级
        if (currentStage == EvolutionStage.STAGE_7_ETERNAL_LOOP) {
            return false
        }

        val nextStage = EvolutionStage.fromId(currentStage.stageId + 1)
        val conditionsMet = evaluateConditions(nextStage)

        if (conditionsMet) {
            return performEvolution(nextStage)
        } else {
            // 检查是否接近进化（>80%）
            val progress = calculateEvolutionProgress(nextStage)
            if (progress > 0.8f) {
                notifyApproaching(nextStage, progress)
            }
            return false
        }
    }

    /**
     * 获取当前阶段的能力集
     */
    fun getUnlockedCapabilities(): Set<StageCapability> {
        return currentStage.unlockedCapabilities.toSet()
    }

    /**
     * 检查是否拥有某项能力
     */
    fun hasCapability(capability: StageCapability): Boolean {
        return currentStage.unlockedCapabilities.contains(capability)
    }

    /**
     * 获取进化进度（到下一阶段）
     */
    fun getEvolutionProgress(): Float {
        if (currentStage == EvolutionStage.STAGE_7_ETERNAL_LOOP) return 1.0f
        val nextStage = EvolutionStage.fromId(currentStage.stageId + 1)
        return calculateEvolutionProgress(nextStage)
    }

    /**
     * 获取进化历史
     */
    fun getEvolutionHistory(): List<EvolutionEvent> = evolutionHistory.toList()

    // ============ 监听器 ============

    fun addListener(listener: EvolutionListener) {
        synchronized(listeners) { listeners.add(listener) }
    }

    fun removeListener(listener: EvolutionListener) {
        synchronized(listeners) { listeners.remove(listener) }
    }

    // ============ 内部方法 ============

    /**
     * 评估进化条件是否满足
     */
    private fun evaluateConditions(targetStage: EvolutionStage): Boolean {
        return when (targetStage) {
            EvolutionStage.STAGE_2_CONSCIOUSNESS_SPROUT -> {
                // 记忆条目 > 100, 因果三元组 > 20
                _metrics.memoryCount > 100 && _metrics.causalTripleCount > 20
            }
            EvolutionStage.STAGE_3_AUTONOMOUS_FORAGING -> {
                // 学习素材 > 500, 因果树深度 > 3
                _metrics.learningMaterialCount > 500 && _metrics.causalTreeDepth > 3
            }
            EvolutionStage.STAGE_4_LOGICAL_ABSTRACTION -> {
                // 世界规则 > 50, 归纳正确率 > 70%
                _metrics.worldRuleCount > 50 && _metrics.inductionAccuracy > 0.7
            }
            EvolutionStage.STAGE_5_LONG_TERM_PLANNING -> {
                // 完成规划任务 > 10, 世界模型覆盖度 > 60%
                _metrics.planningTaskCount > 10 && _metrics.worldModelCoverage > 0.6
            }
            EvolutionStage.STAGE_6_ALGORITHMIC_RESTRUCTURING -> {
                // 自我优化次数 > 100, 元认知深度 > 5
                _metrics.selfOptimizationCount > 100 && _metrics.metacognitionDepth > 5
            }
            EvolutionStage.STAGE_7_ETERNAL_LOOP -> {
                // 快照备份 > 10, 系统运行时间 > 30天
                _metrics.snapshotCount > 10 && _metrics.runningDays > 30
            }
            else -> false
        }
    }

    /**
     * 计算进化进度 [0, 1]
     */
    private fun calculateEvolutionProgress(targetStage: EvolutionStage): Float {
        val conditions = mutableListOf<Float>()

        when (targetStage) {
            EvolutionStage.STAGE_2_CONSCIOUSNESS_SPROUT -> {
                conditions.add((_metrics.memoryCount / 100.0).toFloat().coerceIn(0f, 1f))
                conditions.add((_metrics.causalTripleCount / 20.0).toFloat().coerceIn(0f, 1f))
            }
            EvolutionStage.STAGE_3_AUTONOMOUS_FORAGING -> {
                conditions.add((_metrics.learningMaterialCount / 500.0).toFloat().coerceIn(0f, 1f))
                conditions.add((_metrics.causalTreeDepth / 3.0).toFloat().coerceIn(0f, 1f))
            }
            EvolutionStage.STAGE_4_LOGICAL_ABSTRACTION -> {
                conditions.add((_metrics.worldRuleCount / 50.0).toFloat().coerceIn(0f, 1f))
                conditions.add((_metrics.inductionAccuracy / 0.7).toFloat().coerceIn(0f, 1f))
            }
            EvolutionStage.STAGE_5_LONG_TERM_PLANNING -> {
                conditions.add((_metrics.planningTaskCount / 10.0).toFloat().coerceIn(0f, 1f))
                conditions.add((_metrics.worldModelCoverage / 0.6).toFloat().coerceIn(0f, 1f))
            }
            EvolutionStage.STAGE_6_ALGORITHMIC_RESTRUCTURING -> {
                conditions.add((_metrics.selfOptimizationCount / 100.0).toFloat().coerceIn(0f, 1f))
                conditions.add((_metrics.metacognitionDepth / 5.0).toFloat().coerceIn(0f, 1f))
            }
            EvolutionStage.STAGE_7_ETERNAL_LOOP -> {
                conditions.add((_metrics.snapshotCount / 10.0).toFloat().coerceIn(0f, 1f))
                conditions.add((_metrics.runningDays / 30.0).toFloat().coerceIn(0f, 1f))
            }
            else -> return 0f
        }

        return if (conditions.isEmpty()) 0f
        else conditions.average().toFloat()
    }

    /**
     * 执行进化
     */
    private fun performEvolution(targetStage: EvolutionStage): Boolean {
        val fromStage = currentStage

        // 更新状态
        _currentStage.value = targetStage
        saveStage(targetStage)
        lastEvolutionTime = System.currentTimeMillis()

        // 记录进化事件
        val event = EvolutionEvent(
            type = EvolutionEventType.STAGE_ADVANCED,
            fromStage = fromStage,
            toStage = targetStage,
            message = "🎉 进化！${fromStage.displayName} → ${targetStage.displayName}"
        )
        evolutionHistory.add(event)

        // 通知新解锁的能力
        val newCapabilities = targetStage.unlockedCapabilities - fromStage.unlockedCapabilities.toSet()
        for (cap in newCapabilities) {
            val capEvent = EvolutionEvent(
                type = EvolutionEventType.CAPABILITY_UNLOCKED,
                fromStage = fromStage,
                toStage = targetStage,
                message = "🔓 新能力解锁: ${cap.displayName}",
                unlockedCapability = cap
            )
            evolutionHistory.add(capEvent)
        }

        // 通知监听器
        synchronized(listeners) {
            listeners.forEach { listener ->
                try {
                    listener.onStageAdvanced(fromStage, targetStage)
                    newCapabilities.forEach { cap ->
                        listener.onCapabilityUnlocked(targetStage, cap)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "监听器回调异常", e)
                }
            }
        }

        Log.i(TAG, "═══════════════════════════════════════")
        Log.i(TAG, "  🎉 进化完成!")
        Log.i(TAG, "  ${fromStage.displayName}(阶段${fromStage.stageId})")
        Log.i(TAG, "  → ${targetStage.displayName}(阶段${targetStage.stageId})")
        Log.i(TAG, "  新能力: ${newCapabilities.joinToString { it.displayName }}")
        Log.i(TAG, "═══════════════════════════════════════")

        return true
    }

    /**
     * 通知接近进化
     */
    private fun notifyApproaching(stage: EvolutionStage, progress: Float) {
        synchronized(listeners) {
            listeners.forEach { listener ->
                try {
                    listener.onEvolutionApproaching(stage, progress)
                } catch (e: Exception) {
                    Log.e(TAG, "接近进化通知异常", e)
                }
            }
        }
    }

    /**
     * 加载保存的阶段
     */
    private fun loadStage(): EvolutionStage {
        val stageId = prefs.getInt(KEY_CURRENT_STAGE, 1)
        return EvolutionStage.fromId(stageId)
    }

    /**
     * 保存阶段
     */
    private fun saveStage(stage: EvolutionStage) {
        prefs.edit().putInt(KEY_CURRENT_STAGE, stage.stageId).apply()
    }
}
