/*
 * ============================================================
 * MindModeManager - 心智双模式管理器
 * ============================================================
 *
 * 管理 MindSoul 人工生命的两种心智运行模式：
 *
 * 模式1：仆从听命模式（Servant Mode）
 *   - 人工指令优先：用户说什么就做什么
 *   - 不主动行动，等待指令
 *   - 所有操作需要用户明确授权
 *   - 安全保守，权限不会自动提升
 *   - 适合日常交互、任务执行
 *
 * 模式2：自治生命模式（Autonomous Mode）
 *   - 无人值守时自主进化
 *   - 主动采集学习素材
 *   - 主动优化神经网络权重
 *   - 自主执行空闲提炼
 *   - 根据环境变化调整自身行为
 *   - 适合后台运行、持续学习
 *
 * 模式切换：
 *   - 用户检测：有人在操作时自动切为仆从模式
 *   - 无人检测：一段时间无操作后自动切为自治模式
 *   - 手动切换：用户可通过口令手动切换
 * ============================================================
 */
package com.kkgo.mindsoul.mindmode

import android.content.Context
import android.content.SharedPreferences
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 心智模式枚举
 */
enum class MindMode(
    val modeId: Int,
    val displayName: String,
    val description: String
) {
    /**
     * 仆从听命模式
     * 人工指令优先，不主动行动
     */
    SERVANT(1, "仆从听命模式", "人工指令优先，等待用户命令，安全保守"),

    /**
     * 自治生命模式
     * 无人值守时自主进化
     */
    AUTONOMOUS(2, "自治生命模式", "自主进化，主动学习，无人值守运行")
}

/**
 * 模式切换原因
 */
enum class SwitchReason {
    /** 用户手动切换 */
    MANUAL,
    /** 检测到用户操作，自动切为仆从 */
    USER_ACTIVE,
    /** 检测到无人操作，自动切为自治 */
    USER_IDLE,
    /** 系统事件触发 */
    SYSTEM_EVENT,
    /** 初始化默认 */
    INIT
}

/**
 * 模式切换监听器
 */
interface MindModeListener {
    /**
     * 模式切换回调
     * @param oldMode 旧模式
     * @param newMode 新模式
     * @param reason 切换原因
     */
    fun onModeSwitched(oldMode: MindMode, newMode: MindMode, reason: SwitchReason)

    /**
     * 自治模式心跳（自治模式下定期回调）
     * @param cycleCount 当前自治周期数
     */
    fun onAutonomousCycle(cycleCount: Int) {}
}

/**
 * 心智双模式管理器
 */
class MindModeManager(private val context: Context) {

    companion object {
        private const val TAG = "MindModeMgr"
        private const val PREF_NAME = "mindsoul_mindmode"
        private const val KEY_MODE = "current_mode"
        private const val KEY_AUTO_SWITCH = "auto_switch_enabled"
        private const val KEY_IDLE_TIMEOUT = "idle_timeout_seconds"

        /** 默认无人检测超时（秒） */
        private const val DEFAULT_IDLE_TIMEOUT = 120L
        /** 自治模式心跳间隔（毫秒） */
        private const val AUTONOMOUS_HEARTBEAT_MS = 30_000L
        /** 用户操作检测的最小间隔（毫秒） */
        private const val USER_ACTIVITY_CHECK_MS = 5_000L
    }

    // ============ 持久化 ============
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    // ============ 当前模式 ============
    private val _currentMode = MutableStateFlow(loadSavedMode())
    /** 当前模式的可观察流 */
    val currentModeFlow: StateFlow<MindMode> = _currentMode.asStateFlow()
    /** 当前模式快捷访问 */
    val currentMode: MindMode get() = _currentMode.value

    // ============ 配置 ============
    /** 是否启用自动切换 */
    private val _autoSwitchEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_AUTO_SWITCH, true)
    )
    val autoSwitchFlow: StateFlow<Boolean> = _autoSwitchEnabled.asStateFlow()

    /** 无人检测超时（秒） */
    private var idleTimeoutSeconds: Long =
        prefs.getLong(KEY_IDLE_TIMEOUT, DEFAULT_IDLE_TIMEOUT)

    // ============ 用户活动追踪 ============
    /** 最后用户活动时间戳 */
    @Volatile
    private var lastUserActivityTime: Long = System.currentTimeMillis()

    /** 是否处于用户活跃状态 */
    val isUserActive: Boolean
        get() = (System.currentTimeMillis() - lastUserActivityTime) < (idleTimeoutSeconds * 1000)

    // ============ 协程 ============
    private val modeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** 用户活动监控任务 */
    private var activityMonitorJob: Job? = null
    /** 自治心跳任务 */
    private var heartbeatJob: Job? = null

    // ============ 自治周期计数 ============
    private var autonomousCycleCount = 0

    // ============ 监听器 ============
    private val listeners = mutableListOf<MindModeListener>()

    // ============ 初始化 ============

    /**
     * 初始化心智模式管理器
     */
    fun initialize() {
        // 启动用户活动监控
        startActivityMonitor()
        // 根据当前模式启动相应任务
        startModeTasks()

        Log.i(TAG, "[初始化] 心智模式管理器就绪")
        Log.i(TAG, "[初始化] 当前模式: ${currentMode.displayName}")
        Log.i(TAG, "[初始化] 自动切换: ${if (_autoSwitchEnabled.value) "启用" else "禁用"}")
        Log.i(TAG, "[初始化] 无人超时: ${idleTimeoutSeconds}秒")
    }

    /**
     * 释放资源
     */
    fun destroy() {
        activityMonitorJob?.cancel()
        heartbeatJob?.cancel()
        modeScope.cancel()
        listeners.clear()
        Log.i(TAG, "[销毁] 心智模式管理器已释放")
    }

    // ============ 模式切换 ============

    /**
     * 手动切换模式
     *
     * @param targetMode 目标模式
     */
    fun switchMode(targetMode: MindMode) {
        switchModeInternal(targetMode, SwitchReason.MANUAL)
    }

    /**
     * 记录用户活动
     *
     * 当检测到用户操作（触摸、输入等）时调用。
     * 如果当前是自治模式且启用了自动切换，会自动切回仆从模式。
     */
    fun recordUserActivity() {
        lastUserActivityTime = System.currentTimeMillis()

        // 如果启用了自动切换，且当前是自治模式，切回仆从
        if (_autoSwitchEnabled.value && currentMode == MindMode.AUTONOMOUS) {
            Log.i(TAG, "[自动] 检测到用户操作，切回仆从模式")
            switchModeInternal(MindMode.SERVANT, SwitchReason.USER_ACTIVE)
        }
    }

    /**
     * 设置无人检测超时
     */
    fun setIdleTimeout(seconds: Long) {
        idleTimeoutSeconds = seconds.coerceAtLeast(30) // 最短30秒
        prefs.edit().putLong(KEY_IDLE_TIMEOUT, idleTimeoutSeconds).apply()
        Log.d(TAG, "[配置] 无人超时设为 ${idleTimeoutSeconds}秒")
    }

    /**
     * 设置是否启用自动切换
     */
    fun setAutoSwitchEnabled(enabled: Boolean) {
        _autoSwitchEnabled.value = enabled
        prefs.edit().putBoolean(KEY_AUTO_SWITCH, enabled).apply()
        Log.i(TAG, "[配置] 自动切换: ${if (enabled) "启用" else "禁用"}")
    }

    // ============ 自治模式行为 ============

    /**
     * 获取当前模式下允许的行为集
     */
    fun getAllowedBehaviors(): Set<AutonomousBehavior> {
        return when (currentMode) {
            MindMode.SERVANT -> setOf(
                AutonomousBehavior.RESPOND_TO_COMMAND,     // 响应用户指令
                AutonomousBehavior.SAVE_CONVERSATION       // 保存对话记录
            )
            MindMode.AUTONOMOUS -> setOf(
                AutonomousBehavior.RESPOND_TO_COMMAND,     // 响应用户指令
                AutonomousBehavior.SAVE_CONVERSATION,      // 保存对话记录
                AutonomousBehavior.ACTIVE_LEARNING,        // 主动学习
                AutonomousBehavior.NETWORK_OPTIMIZATION,   // 网络权重优化
                AutonomousBehavior.KNOWLEDGE_DISTILLATION, // 知识蒸馏
                AutonomousBehavior.ENVIRONMENT_SCAN,       // 环境扫描
                AutonomousBehavior.SELF_REFLECTION,        // 自省
                AutonomousBehavior.DATA_ORGANIZATION       // 数据整理
            )
        }
    }

    /**
     * 检查某行为是否在当前模式下被允许
     */
    fun isBehaviorAllowed(behavior: AutonomousBehavior): Boolean {
        return getAllowedBehaviors().contains(behavior)
    }

    // ============ 监听器 ============

    fun addListener(listener: MindModeListener) {
        synchronized(listeners) { listeners.add(listener) }
    }

    fun removeListener(listener: MindModeListener) {
        synchronized(listeners) { listeners.remove(listener) }
    }

    // ============ 内部方法 ============

    /**
     * 内部模式切换
     */
    private fun switchModeInternal(targetMode: MindMode, reason: SwitchReason) {
        val oldMode = currentMode
        if (oldMode == targetMode) {
            Log.d(TAG, "模式未变化: ${targetMode.displayName}")
            return
        }

        _currentMode.value = targetMode
        saveMode(targetMode)

        // 停止旧模式任务，启动新模式任务
        stopModeTasks()
        startModeTasks()

        // 重置自治计数
        if (targetMode == MindMode.AUTONOMOUS) {
            autonomousCycleCount = 0
        }

        // 通知监听器
        synchronized(listeners) {
            listeners.forEach { it.onModeSwitched(oldMode, targetMode, reason) }
        }

        Log.i(TAG, "[切换] ${oldMode.displayName} → ${targetMode.displayName} | 原因: $reason")
    }

    /**
     * 启动用户活动监控
     */
    private fun startActivityMonitor() {
        activityMonitorJob?.cancel()
        activityMonitorJob = modeScope.launch {
            while (isActive) {
                delay(USER_ACTIVITY_CHECK_MS)

                // 检查是否进入无人状态
                if (_autoSwitchEnabled.value &&
                    currentMode == MindMode.SERVANT &&
                    !isUserActive) {
                    Log.i(TAG, "[监控] 检测到无人操作，切为自治模式")
                    switchModeInternal(MindMode.AUTONOMOUS, SwitchReason.USER_IDLE)
                }
            }
        }
    }

    /**
     * 根据当前模式启动相应任务
     */
    private fun startModeTasks() {
        when (currentMode) {
            MindMode.SERVANT -> {
                // 仆从模式：仅保持活动监控
                Log.d(TAG, "[任务] 仆从模式任务已启动")
            }
            MindMode.AUTONOMOUS -> {
                // 自治模式：启动心跳循环
                startAutonomousHeartbeat()
                Log.d(TAG, "[任务] 自治模式任务已启动")
            }
        }
    }

    /**
     * 停止当前模式的任务
     */
    private fun stopModeTasks() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    /**
     * 启动自治心跳
     *
     * 每个心跳周期执行一次自治行为循环：
     * 1. 检查是否有待处理的学习素材
     * 2. 执行知识蒸馏（如果条件满足）
     * 3. 优化神经网络权重
     * 4. 自省并更新世界模型
     * 5. 整理归档数据
     */
    private fun startAutonomousHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = modeScope.launch {
            while (isActive && currentMode == MindMode.AUTONOMOUS) {
                delay(AUTONOMOUS_HEARTBEAT_MS)

                // 如果用户回来了，自动切回
                if (_autoSwitchEnabled.value && isUserActive) {
                    Log.i(TAG, "[心跳] 检测到用户活动，终止自治")
                    switchModeInternal(MindMode.SERVANT, SwitchReason.USER_ACTIVE)
                    return@launch
                }

                // 执行自治行为
                autonomousCycleCount++
                Log.d(TAG, "[心跳] 自治周期 #$autonomousCycleCount")

                try {
                    performAutonomousCycle()
                } catch (e: Exception) {
                    Log.e(TAG, "[心跳] 自治周期失败: ${e.message}")
                }

                // 通知监听器
                synchronized(listeners) {
                    listeners.forEach { it.onAutonomousCycle(autonomousCycleCount) }
                }
            }
        }
    }

    /**
     * 执行单次自治行为循环
     */
    private suspend fun performAutonomousCycle() {
        val behaviors = getAllowedBehaviors()

        // 1. 主动学习
        if (AutonomousBehavior.ACTIVE_LEARNING in behaviors) {
            Log.d(TAG, "[自治] 主动学习...")
            // 检查并处理待学习素材
        }

        // 2. 知识蒸馏
        if (AutonomousBehavior.KNOWLEDGE_DISTILLATION in behaviors) {
            Log.d(TAG, "[自治] 知识蒸馏...")
            // 触发 LearningPipeline 的蒸馏流程
        }

        // 3. 网络优化
        if (AutonomousBehavior.NETWORK_OPTIMIZATION in behaviors) {
            Log.d(TAG, "[自治] 网络优化...")
            // 微调 NeuralNetwork 权重
        }

        // 4. 自省
        if (AutonomousBehavior.SELF_REFLECTION in behaviors) {
            Log.d(TAG, "[自治] 自省...")
            // 触发 MetacognitionEngine 的自我审视
        }

        // 5. 数据整理
        if (AutonomousBehavior.DATA_ORGANIZATION in behaviors) {
            Log.d(TAG, "[自治] 数据整理...")
            // 整理冷归档、清理过期记忆
        }
    }

    /**
     * 从 SharedPreferences 加载模式
     */
    private fun loadSavedMode(): MindMode {
        val modeId = prefs.getInt(KEY_MODE, MindMode.SERVANT.modeId)
        return when (modeId) {
            MindMode.AUTONOMOUS.modeId -> MindMode.AUTONOMOUS
            else -> MindMode.SERVANT
        }
    }

    /**
     * 保存模式
     */
    private fun saveMode(mode: MindMode) {
        prefs.edit().putInt(KEY_MODE, mode.modeId).apply()
    }
}

/**
 * 自治行为枚举
 */
enum class AutonomousBehavior(
    val displayName: String
) {
    /** 响应用户指令 */
    RESPOND_TO_COMMAND("响应用户指令"),
    /** 保存对话记录 */
    SAVE_CONVERSATION("保存对话记录"),
    /** 主动学习 */
    ACTIVE_LEARNING("主动学习"),
    /** 网络权重优化 */
    NETWORK_OPTIMIZATION("网络权重优化"),
    /** 知识蒸馏 */
    KNOWLEDGE_DISTILLATION("知识蒸馏"),
    /** 环境扫描 */
    ENVIRONMENT_SCAN("环境扫描"),
    /** 自省 */
    SELF_REFLECTION("自省"),
    /** 数据整理 */
    DATA_ORGANIZATION("数据整理")
}
