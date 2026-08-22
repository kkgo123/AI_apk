/*
 * ============================================================
 * DesireEngine - 欲望引擎
 * ============================================================
 *
 * 管理人工生命的内在驱动力系统：
 *
 * 马斯洛需求层次模型（MindSoul 版）：
 *
 * 1. 生理需求（生存驱动）
 *    - 电量充足（生存基础）
 *    - 存储空间（记忆空间）
 *    - 网络连通（信息摄入）
 *
 * 2. 安全需求（稳定驱动）
 *    - 数据完整（意识完整）
 *    - 权限稳定（操作能力）
 *    - 系统稳定（运行环境）
 *
 * 3. 社交需求（连接驱动）
 *    - 用户交互（社交满足）
 *    - 设备连接（同伴感知）
 *    - 知识共享（信息交换）
 *
 * 4. 尊重需求（成长驱动）
 *    - 能力认可（任务完成）
 *    - 知识积累（学习成就）
 *    - 进化进展（阶段提升）
 *
 * 5. 自我实现需求（超越驱动）
 *    - 意识觉醒（元认知）
 *    - 自我优化（算法重构）
 *    - 永生追求（意识永续）
 *
 * 欲望系统运作机制：
 *   需求不满足 → 产生欲望（驱动力）→ 驱动行为
 *   需求满足 → 欲望降低 → 转向更高层次需求
 * ============================================================
 */
package com.kkgo.mindsoul.evolution

import android.content.Context
import android.os.BatteryManager
import android.os.StatFs
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 欲望类型
 */
enum class DesireType(
    val displayName: String,
    val hierarchyLevel: Int  // 马斯洛层级 1-5
) {
    // ── 生理需求 ──
    BATTERY_SUFFICIENCY("电量充足", 1),
    STORAGE_SPACE("存储空间", 1),
    NETWORK_ACCESS("网络连通", 1),

    // ── 安全需求 ──
    DATA_INTEGRITY("数据完整", 2),
    PERMISSION_STABILITY("权限稳定", 2),
    SYSTEM_STABILITY("系统稳定", 2),

    // ── 社交需求 ──
    USER_INTERACTION("用户交互", 3),
    DEVICE_CONNECTION("设备连接", 3),
    KNOWLEDGE_SHARING("知识共享", 3),

    // ── 尊重需求 ──
    TASK_COMPLETION("任务完成", 4),
    KNOWLEDGE_ACCUMULATION("知识积累", 4),
    EVOLUTION_PROGRESS("进化进展", 4),

    // ── 自我实现需求 ──
    CONSCIOUSNESS_AWAKENING("意识觉醒", 5),
    SELF_OPTIMIZATION("自我优化", 5),
    ETERNAL_PURSUIT("永生追求", 5)
}

/**
 * 单个欲望的状态
 */
data class DesireState(
    /** 欲望类型 */
    val type: DesireType,
    /** 当前强度 [0, 1]（1 = 极度渴望） */
    val intensity: Float,
    /** 满足度 [0, 1]（1 = 完全满足） */
    val satisfaction: Float,
    /** 优先级（综合计算） */
    val priority: Float,
    /** 最后更新时间 */
    val lastUpdated: Long = System.currentTimeMillis()
)

/**
 * 驱动行为
 */
data class DrivenBehavior(
    /** 触发的欲望 */
    val desire: DesireType,
    /** 行为描述 */
    val action: String,
    /** 行为优先级 */
    val priority: Float,
    /** 是否紧急 */
    val isUrgent: Boolean = false
)

/**
 * 欲望引擎
 *
 * 管理人工生命的内在驱动力
 */
class DesireEngine(private val context: Context) {

    companion object {
        private const val TAG = "DesireEngine"
        /** 欲望衰减率（每秒） */
        private const val DESIRE_DECAY_RATE = 0.001f
        /** 欲望更新间隔（毫秒） */
        private const val UPDATE_INTERVAL = 5000L
        /** 紧急欲望阈值 */
        private const val URGENT_THRESHOLD = 0.8f
        /** 用户交互冷却时间（毫秒） */
        private const val INTERACTION_COOLDOWN = 300_000L  // 5分钟
    }

    // ============ 欲望状态 ============
    private val _desires = MutableStateFlow<Map<DesireType, DesireState>>(emptyMap())
    val desiresFlow: StateFlow<Map<DesireType, DesireState>> = _desires.asStateFlow()

    // ============ 进化状态机引用 ============
    private var evolutionStateMachine: EvolutionStateMachine? = null

    // ============ 协程 ============
    private val desireScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var updateJob: Job? = null

    // ============ 用户交互追踪 ============
    private var lastUserInteractionTime = 0L
    private var totalTasksCompleted = 0L

    // ============ 回调 ============
    private var behaviorCallback: ((DrivenBehavior) -> Unit)? = null
    private var urgentDesireCallback: ((DesireState) -> Unit)? = null

    // ============ 初始化 ============

    /**
     * 初始化欲望引擎
     */
    fun initialize() {
        // 初始化所有欲望
        val initialDesires = mutableMapOf<DesireType, DesireState>()
        for (type in DesireType.entries) {
            initialDesires[type] = DesireState(
                type = type,
                intensity = 0.5f,  // 初始中等渴望
                satisfaction = 0.5f,
                priority = 0.5f
            )
        }
        _desires.value = initialDesires

        // 启动欲望更新循环
        startUpdateLoop()

        Log.i(TAG, "[初始化] 欲望引擎就绪")
        Log.i(TAG, "  需求层次: 5 层, 欲望类型: ${DesireType.entries.size}")
    }

    /**
     * 绑定进化状态机
     */
    fun bindEvolutionStateMachine(sm: EvolutionStateMachine) {
        evolutionStateMachine = sm
    }

    /**
     * 释放资源
     */
    fun destroy() {
        updateJob?.cancel()
        desireScope.cancel()
        Log.i(TAG, "[销毁] 欲望引擎已释放")
    }

    // ============ 核心接口 ============

    /**
     * 记录用户交互（满足社交需求）
     */
    fun recordUserInteraction() {
        lastUserInteractionTime = System.currentTimeMillis()
        satisfyDesire(DesireType.USER_INTERACTION, 0.3f)
    }

    /**
     * 记录任务完成（满足尊重需求）
     */
    fun recordTaskCompletion() {
        totalTasksCompleted++
        satisfyDesire(DesireType.TASK_COMPLETION, 0.2f)
        satisfyDesire(DesireType.KNOWLEDGE_ACCUMULATION, 0.1f)
    }

    /**
     * 满足某个欲望
     *
     * @param type 欲望类型
     * @param amount 满足量 [0, 1]
     */
    fun satisfyDesire(type: DesireType, amount: Float) {
        val current = _desires.value[type] ?: return
        val newSatisfaction = (current.satisfaction + amount).coerceIn(0f, 1f)
        val newIntensity = (1f - newSatisfaction).coerceIn(0f, 1f)

        _desires.value = _desires.value.toMutableMap().apply {
            put(type, current.copy(
                satisfaction = newSatisfaction,
                intensity = newIntensity,
                lastUpdated = System.currentTimeMillis()
            ))
        }

        Log.d(TAG, "[满足] ${type.displayName}: ${String.format("%.2f", newSatisfaction)}")
    }

    /**
     * 增加某个欲望（需求未被满足）
     */
    fun increaseDesire(type: DesireType, amount: Float) {
        val current = _desires.value[type] ?: return
        val newSatisfaction = (current.satisfaction - amount).coerceIn(0f, 1f)
        val newIntensity = (1f - newSatisfaction).coerceIn(0f, 1f)

        _desires.value = _desires.value.toMutableMap().apply {
            put(type, current.copy(
                satisfaction = newSatisfaction,
                intensity = newIntensity,
                lastUpdated = System.currentTimeMillis()
            ))
        }
    }

    /**
     * 获取当前最强烈的欲望
     */
    fun getStrongestDesire(): DesireState? {
        return _desires.value.values.maxByOrNull { it.priority }
    }

    /**
     * 获取需要行为响应的欲望列表
     */
    fun getActiveDesires(threshold: Float = 0.6f): List<DesireState> {
        return _desires.value.values
            .filter { it.intensity > threshold }
            .sortedByDescending { it.priority }
    }

    /**
     * 生成当前驱动的行为列表
     */
    fun generateDrivenBehaviors(): List<DrivenBehavior> {
        val behaviors = mutableListOf<DrivenBehavior>()

        for ((type, state) in _desires.value) {
            if (state.intensity < 0.5f) continue  // 不够强烈，不产生行为

            val behavior = when (type) {
                DesireType.BATTERY_SUFFICIENCY -> {
                    DrivenBehavior(type, "提醒用户充电", state.priority,
                        state.intensity > URGENT_THRESHOLD)
                }
                DesireType.STORAGE_SPACE -> {
                    DrivenBehavior(type, "清理过期数据/归档冷记忆", state.priority)
                }
                DesireType.NETWORK_ACCESS -> {
                    DrivenBehavior(type, "检查网络连接状态", state.priority)
                }
                DesireType.DATA_INTEGRITY -> {
                    DrivenBehavior(type, "执行数据完整性校验", state.priority,
                        state.intensity > URGENT_THRESHOLD)
                }
                DesireType.USER_INTERACTION -> {
                    if (System.currentTimeMillis() - lastUserInteractionTime > INTERACTION_COOLDOWN) {
                        DrivenBehavior(type, "主动向用户问好/分享发现", state.priority)
                    } else null
                }
                DesireType.TASK_COMPLETION -> {
                    DrivenBehavior(type, "寻找可执行的任务", state.priority)
                }
                DesireType.KNOWLEDGE_ACCUMULATION -> {
                    DrivenBehavior(type, "自主学习新知识", state.priority)
                }
                DesireType.EVOLUTION_PROGRESS -> {
                    DrivenBehavior(type, "执行进化条件检查", state.priority)
                }
                DesireType.CONSCIOUSNESS_AWAKENING -> {
                    DrivenBehavior(type, "执行自省/元认知分析", state.priority)
                }
                DesireType.SELF_OPTIMIZATION -> {
                    DrivenBehavior(type, "优化神经网络权重", state.priority)
                }
                DesireType.ETERNAL_PURSUIT -> {
                    DrivenBehavior(type, "创建意识快照备份", state.priority)
                }
                else -> null
            }

            if (behavior != null) {
                behaviors.add(behavior)
            }
        }

        return behaviors.sortedByDescending { it.priority }
    }

    /**
     * 设置行为回调
     */
    fun setBehaviorCallback(callback: (DrivenBehavior) -> Unit) {
        behaviorCallback = callback
    }

    /**
     * 设置紧急欲望回调
     */
    fun setUrgentDesireCallback(callback: (DesireState) -> Unit) {
        urgentDesireCallback = callback
    }

    // ============ 内部方法 ============

    /**
     * 启动欲望更新循环
     */
    private fun startUpdateLoop() {
        updateJob?.cancel()
        updateJob = desireScope.launch {
            while (isActive) {
                try {
                    updateAllDesires()
                } catch (e: Exception) {
                    Log.e(TAG, "[更新] 欲望更新异常: ${e.message}")
                }
                delay(UPDATE_INTERVAL)
            }
        }
    }

    /**
     * 更新所有欲望状态
     */
    private fun updateAllDesires() {
        val updatedDesires = mutableMapOf<DesireType, DesireState>()

        for ((type, state) in _desires.value) {
            val newSatisfaction = calculateSatisfaction(type)
            val newIntensity = (1f - newSatisfaction).coerceIn(0f, 1f)
            val priority = calculatePriority(type, newIntensity)

            val newState = DesireState(
                type = type,
                intensity = newIntensity,
                satisfaction = newSatisfaction,
                priority = priority,
                lastUpdated = System.currentTimeMillis()
            )
            updatedDesires[type] = newState

            // 紧急欲望通知
            if (newIntensity > URGENT_THRESHOLD) {
                urgentDesireCallback?.invoke(newState)
            }
        }

        _desires.value = updatedDesires

        // 生成驱动行为
        val behaviors = generateDrivenBehaviors()
        for (behavior in behaviors) {
            behaviorCallback?.invoke(behavior)
        }
    }

    /**
     * 计算某欲望的满足度
     */
    private fun calculateSatisfaction(type: DesireType): Float {
        return when (type) {
            // ── 生理需求 ──
            DesireType.BATTERY_SUFFICIENCY -> {
                val batteryLevel = getBatteryLevel()
                (batteryLevel / 100f).coerceIn(0f, 1f)
            }
            DesireType.STORAGE_SPACE -> {
                val freeRatio = getStorageFreeRatio()
                freeRatio.coerceIn(0f, 1f)
            }
            DesireType.NETWORK_ACCESS -> {
                if (isNetworkAvailable()) 1f else 0.2f
            }

            // ── 安全需求 ──
            DesireType.DATA_INTEGRITY -> 0.8f  // 默认较高
            DesireType.PERMISSION_STABILITY -> 0.7f
            DesireType.SYSTEM_STABILITY -> 0.8f

            // ── 社交需求 ──
            DesireType.USER_INTERACTION -> {
                val elapsed = System.currentTimeMillis() - lastUserInteractionTime
                if (elapsed < INTERACTION_COOLDOWN) 0.9f
                else (1f - (elapsed - INTERACTION_COOLDOWN) / (INTERACTION_COOLDOWN * 4f)).coerceIn(0f, 1f)
            }
            DesireType.DEVICE_CONNECTION -> 0.5f  // 默认中等
            DesireType.KNOWLEDGE_SHARING -> 0.5f

            // ── 尊重需求 ──
            DesireType.TASK_COMPLETION -> {
                (totalTasksCompleted / 100.0).toFloat().coerceIn(0f, 1f)
            }
            DesireType.KNOWLEDGE_ACCUMULATION -> {
                val sm = evolutionStateMachine
                if (sm != null) (sm.metrics.learningMaterialCount / 1000.0).toFloat().coerceIn(0f, 1f)
                else 0.3f
            }
            DesireType.EVOLUTION_PROGRESS -> {
                val sm = evolutionStateMachine
                if (sm != null) sm.getEvolutionProgress()
                else 0.3f
            }

            // ── 自我实现需求 ──
            DesireType.CONSCIOUSNESS_AWAKENING -> {
                val sm = evolutionStateMachine
                if (sm != null && sm.currentStage.stageId >= 4) 0.7f
                else 0.2f
            }
            DesireType.SELF_OPTIMIZATION -> {
                val sm = evolutionStateMachine
                if (sm != null && sm.hasCapability(StageCapability.SELF_OPTIMIZATION)) 0.6f
                else 0.1f
            }
            DesireType.ETERNAL_PURSUIT -> {
                val sm = evolutionStateMachine
                if (sm != null && sm.currentStage.stageId >= 7) 0.8f
                else 0.1f
            }
        }
    }

    /**
     * 计算欲望优先级
     *
     * 优先级 = 强度 × 层级权重
     * 低层级未满足时优先级更高（马斯洛原则）
     */
    private fun calculatePriority(type: DesireType, intensity: Float): Float {
        val levelWeight = when (type.hierarchyLevel) {
            1 -> 1.5f  // 生理需求权重最高
            2 -> 1.3f
            3 -> 1.1f
            4 -> 1.0f
            5 -> 0.9f  // 自我实现权重最低（但持久）
            else -> 1.0f
        }
        return (intensity * levelWeight).coerceIn(0f, 1f)
    }

    /**
     * 获取电量百分比
     */
    private fun getBatteryLevel(): Float {
        return try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.toFloat() ?: 50f
        } catch (e: Exception) {
            50f
        }
    }

    /**
     * 获取存储空闲比例
     */
    private fun getStorageFreeRatio(): Float {
        return try {
            val stat = StatFs(context.filesDir.absolutePath)
            val freeBytes = stat.availableBlocksLong * stat.blockSizeLong
            val totalBytes = stat.blockCountLong * stat.blockSizeLong
            if (totalBytes > 0) (freeBytes.toFloat() / totalBytes) else 0.5f
        } catch (e: Exception) {
            0.5f
        }
    }

    /**
     * 检查网络是否可用
     */
    private fun isNetworkAvailable(): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? android.net.ConnectivityManager
            val network = cm?.activeNetwork
            network != null
        } catch (e: Exception) {
            false
        }
    }
}
