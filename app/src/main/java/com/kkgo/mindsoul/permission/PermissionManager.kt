/*
 * ============================================================
 * PermissionManager - 权限管理器
 * ============================================================
 *
 * 三级权限完整体系的核心管理器，负责：
 * 1. 当前权限等级的维护与持久化
 * 2. 权限切换的安全校验（口令验证）
 * 3. 能力检查接口（供其他模块调用）
 * 4. 权限变更事件广播
 * 5. 危险操作二次确认机制
 *
 * 权限等级：
 *   L1 默认沙箱 → L2 文件领主 → L3-A 受限自治 → L3-B 终极孢子ROOT
 * ============================================================
 */
package com.kkgo.mindsoul.permission

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

class PermissionManager(private val context: Context) {

    companion object {
        private const val TAG = "PermissionMgr"
        private const val PREF_NAME = "mindsoul_permission"
        private const val KEY_LEVEL = "current_level"
        private const val KEY_HISTORY = "level_history"

        /** L3-B 终极孢子需要二次确认的等待窗口（毫秒） */
        private const val CONFIRM_WINDOW_MS = 30_000L
    }

    // ============ 持久化存储 ============
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    // ============ 当前权限等级 ============
    private val _currentLevel = MutableStateFlow(loadSavedLevel())
    /** 当前权限等级的可观察流 */
    val currentLevelFlow: StateFlow<PermissionLevel> = _currentLevel.asStateFlow()

    /** 当前权限等级（快捷访问） */
    val currentLevel: PermissionLevel
        get() = _currentLevel.value

    // ============ 口令解析器 ============
    private val commandParser = AuthCommandParser()

    // ============ 待确认的权限变更请求 ============
    /** 待确认的目标等级（危险操作需要二次确认） */
    private var pendingConfirmLevel: PermissionLevel? = null
    /** 待确认请求的时间戳 */
    private var pendingConfirmTime: Long = 0L

    // ============ 权限变更监听器 ============
    private val listeners = mutableListOf<OnPermissionChangeListener>()

    /**
     * 权限变更监听接口
     */
    interface OnPermissionChangeListener {
        /**
         * 权限等级变更回调
         * @param oldLevel 旧等级
         * @param newLevel 新等级
         */
        fun onPermissionChanged(oldLevel: PermissionLevel, newLevel: PermissionLevel)
    }

    // ============ 初始化 ============

    /**
     * 初始化权限管理器
     * 加载持久化的权限等级
     */
    fun initialize() {
        val level = loadSavedLevel()
        _currentLevel.value = level
        Log.i(TAG, "[初始化] 当前权限等级: ${level.displayName}")
    }

    // ============ 核心接口 ============

    /**
     * 检查当前是否具备某项能力
     *
     * 这是其他模块调用的主要接口。
     * 例如：if (permissionManager.hasCapability(Capability.GLOBAL_FILE_READ)) { ... }
     *
     * @param capability 要检查的能力
     * @return true 表示当前等级具备该能力
     */
    fun hasCapability(capability: Capability): Boolean {
        return PermissionCapabilityMap.has(currentLevel, capability)
    }

    /**
     * 检查指定等级是否具备某项能力
     */
    fun hasCapability(level: PermissionLevel, capability: Capability): Boolean {
        return PermissionCapabilityMap.has(level, capability)
    }

    /**
     * 处理用户输入（自动识别权限口令）
     *
     * 对话框收到用户消息后，先交给此方法判断是否为权限口令。
     * 如果是权限口令则执行权限操作并返回 true；
     * 否则返回 false，交由正常的对话流程处理。
     *
     * @param userInput 用户输入文本
     * @return 处理结果
     */
    fun handleUserInput(userInput: String): PermissionHandleResult {
        // ① 紧急锁定检测
        if (commandParser.isEmergencyLockdown(userInput)) {
            return forceDowngrade(PermissionLevel.L1_SANDBOX, "紧急锁定指令")
        }

        // ② 解析口令
        val result = commandParser.parse(userInput)
        if (!result.isValid) {
            return PermissionHandleResult.NotCommand
        }

        // ③ 查询当前权限
        if (result.action == AuthAction.QUERY) {
            return PermissionHandleResult.StatusReport(
                level = currentLevel,
                capabilities = PermissionCapabilityMap.capabilitiesOf(currentLevel),
                message = "当前权限：${currentLevel.displayName}\n" +
                          "能力集：${PermissionCapabilityMap.capabilitiesOf(currentLevel).joinToString { it.description }}"
            )
        }

        // ④ 检查是否为二次确认
        if (pendingConfirmLevel != null) {
            val confirmResult = handleConfirmation(userInput, result)
            if (confirmResult != null) return confirmResult
        }

        // ⑤ 执行权限切换
        val targetLevel = result.targetLevel
            ?: return PermissionHandleResult.Error(result.message)

        return requestLevelChange(targetLevel, result.action)
    }

    /**
     * 直接设置权限等级（仅内部调用，不经过口令解析）
     *
     * @param newLevel 目标等级
     * @param reason 变更原因
     * @return 是否成功
     */
    fun setLevelDirect(newLevel: PermissionLevel, reason: String = "系统直接设置"): Boolean {
        val oldLevel = currentLevel
        if (oldLevel == newLevel) {
            Log.d(TAG, "权限等级未变化: ${newLevel.displayName}")
            return false
        }

        _currentLevel.value = newLevel
        saveLevel(newLevel)
        recordHistory(oldLevel, newLevel, reason)
        notifyListeners(oldLevel, newLevel)

        Log.i(TAG, "权限变更: ${oldLevel.displayName} → ${newLevel.displayName} | 原因: $reason")
        return true
    }

    /**
     * 获取当前等级的全部能力
     */
    fun getCurrentCapabilities(): Set<Capability> {
        return PermissionCapabilityMap.capabilitiesOf(currentLevel)
    }

    /**
     * 注册权限变更监听器
     */
    fun addListener(listener: OnPermissionChangeListener) {
        synchronized(listeners) {
            if (!listeners.contains(listener)) {
                listeners.add(listener)
            }
        }
    }

    /**
     * 移除权限变更监听器
     */
    fun removeListener(listener: OnPermissionChangeListener) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }

    // ============ 内部方法 ============

    /**
     * 请求权限等级变更
     */
    private fun requestLevelChange(
        targetLevel: PermissionLevel,
        action: AuthAction
    ): PermissionHandleResult {
        val current = currentLevel

        // 降级操作：直接执行
        if (action == AuthAction.DOWNGRADE || targetLevel.levelId < current.levelId) {
            val old = current
            setLevelDirect(targetLevel, "用户降级指令")
            return PermissionHandleResult.Changed(
                oldLevel = old,
                newLevel = targetLevel,
                message = "已降级至 ${targetLevel.displayName}"
            )
        }

        // 升级到 L3-B 需要二次确认
        if (targetLevel == PermissionLevel.L3B_ULTIMATE_SPORE) {
            pendingConfirmLevel = targetLevel
            pendingConfirmTime = System.currentTimeMillis()
            return PermissionHandleResult.NeedConfirm(
                targetLevel = targetLevel,
                message = "⚠️ 警告：即将进入 L3-B 终极孢子ROOT模式！\n" +
                          "此操作将开放全部系统权限，包括ROOT操作。\n" +
                          "请在30秒内回复「确认」继续，或回复「取消」放弃。"
            )
        }

        // 升级到 L2/L3-A：直接执行（但记录日志）
        val old = current
        setLevelDirect(targetLevel, "用户升级指令")
        return PermissionHandleResult.Changed(
            oldLevel = old,
            newLevel = targetLevel,
            message = "已升级至 ${targetLevel.displayName}"
        )
    }

    /**
     * 处理二次确认逻辑
     */
    private fun handleConfirmation(
        userInput: String,
        parsed: AuthCommandResult
    ): PermissionHandleResult? {
        // 检查是否在确认窗口内
        val elapsed = System.currentTimeMillis() - pendingConfirmTime
        if (elapsed > CONFIRM_WINDOW_MS) {
            pendingConfirmLevel = null
            return null // 超时，按正常流程处理
        }

        val target = pendingConfirmLevel ?: return null
        val lower = userInput.lowercase()

        // 确认
        if (listOf("确认", "确定", "是的", "yes", "confirm").any { lower.contains(it) }) {
            pendingConfirmLevel = null
            val old = currentLevel
            setLevelDirect(target, "用户二次确认后升级")
            return PermissionHandleResult.Changed(
                oldLevel = old,
                newLevel = target,
                message = "✅ 已确认，进入 ${target.displayName}"
            )
        }

        // 取消
        if (listOf("取消", "否", "cancel", "no").any { lower.contains(it) }) {
            pendingConfirmLevel = null
            return PermissionHandleResult.Cancelled(
                message = "已取消权限升级操作"
            )
        }

        return null
    }

    /**
     * 强制降级（紧急锁定）
     */
    private fun forceDowngrade(target: PermissionLevel, reason: String): PermissionHandleResult {
        val old = currentLevel
        setLevelDirect(target, reason)
        return PermissionHandleResult.Changed(
            oldLevel = old,
            newLevel = target,
            message = "🔒 $reason：已强制锁定至 ${target.displayName}"
        )
    }

    /**
     * 从 SharedPreferences 加载保存的权限等级
     */
    private fun loadSavedLevel(): PermissionLevel {
        val levelId = prefs.getInt(KEY_LEVEL, PermissionLevel.L1_SANDBOX.levelId)
        return PermissionLevel.fromId(levelId)
    }

    /**
     * 持久化权限等级
     */
    private fun saveLevel(level: PermissionLevel) {
        prefs.edit().putInt(KEY_LEVEL, level.levelId).apply()
    }

    /**
     * 记录权限变更历史
     */
    private fun recordHistory(old: PermissionLevel, new: PermissionLevel, reason: String) {
        val timestamp = System.currentTimeMillis()
        val historyLine = "$timestamp|${old.levelId}|${new.levelId}|$reason\n"
        try {
            val historyFile = File(context.filesDir, "permission_history.log")
            historyFile.appendText(historyLine)
        } catch (e: Exception) {
            Log.w(TAG, "记录权限历史失败: ${e.message}")
        }
    }

    /**
     * 通知所有监听器
     */
    private fun notifyListeners(old: PermissionLevel, new: PermissionLevel) {
        synchronized(listeners) {
            listeners.forEach { listener ->
                try {
                    listener.onPermissionChanged(old, new)
                } catch (e: Exception) {
                    Log.e(TAG, "通知监听器失败: ${e.message}")
                }
            }
        }
    }
}

/**
 * 权限口令处理结果（密封类）
 */
sealed class PermissionHandleResult {
    /** 不是权限口令，交由正常流程处理 */
    data object NotCommand : PermissionHandleResult()

    /** 权限变更成功 */
    data class Changed(
        val oldLevel: PermissionLevel,
        val newLevel: PermissionLevel,
        val message: String
    ) : PermissionHandleResult()

    /** 需要二次确认 */
    data class NeedConfirm(
        val targetLevel: PermissionLevel,
        val message: String
    ) : PermissionHandleResult()

    /** 用户取消 */
    data class Cancelled(
        val message: String
    ) : PermissionHandleResult()

    /** 查询状态报告 */
    data class StatusReport(
        val level: PermissionLevel,
        val capabilities: Set<Capability>,
        val message: String
    ) : PermissionHandleResult()

    /** 解析错误 */
    data class Error(
        val message: String
    ) : PermissionHandleResult()
}
