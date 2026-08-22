/*
 * ============================================================
 * SwitchCenter - 全局开关管理中心
 * ============================================================
 *
 * MindSoul 人工生命系统的统一开关管理，负责：
 *
 * 1. 开关注册与状态管理
 *    - 所有模块开关统一注册
 *    - 开关状态持久化存储
 *    - 开关状态变更通知
 *
 * 2. 联动逻辑
 *    - 关闭某开关时自动隐藏/卸载对应模块
 *    - 级联开关（如关闭多媒体自动关闭ASR/OCR/文档解析）
 *    - 互斥开关（如仆从模式和自治模式互斥）
 *
 * 3. 模块生命周期联动
 *    - 开关开启：初始化并挂载对应模块
 *    - 开关关闭：反初始化并卸载对应模块
 *
 * 4. 权限关联
 *    - 某些开关受权限等级约束
 *    - 权限变更时自动调整可用开关
 *
 * 管理的开关清单：
 *   - 多媒体处理（含ASR/OCR/文档解析子开关）
 *   - 学习引擎（含五通道子开关）
 *   - 外网抓取（网页纯文本抓取）
 *   - 文件解析（全格式文档解析）
 *   - 联网搜索（网络搜索能力）
 *   - 化身系统（桌面心智化身）
 *   - 语音交互（TTS/ASR）
 *   - 后台常驻（前台服务保活）
 *   - 逆向引擎（DEX/APK解析）
 *   - 权限切换（自动权限升降级）
 *   - 矩阵配对（局域网孢子配对）
 *   - 双心智模式（仆从/自治模式）
 *   - 意识备份导出（.brain备份）
 * ============================================================
 */
package com.kkgo.mindsoul.switches

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 开关ID枚举 - 所有可控制的模块开关
 */
enum class SwitchId(
    val key: String,
    val displayName: String,
    val description: String,
    /** 所需最低权限等级（0=无需权限） */
    val requiredPermLevel: Int = 0,
    /** 所属分组 */
    val group: SwitchGroup = SwitchGroup.GENERAL
) {
    // ── 多媒体分组 ──
    MULTIMEDIA("multimedia", "多媒体处理", "全局多媒体处理总开关", 0, SwitchGroup.MULTIMEDIA),
    ASR("asr", "语音识别", "ASR语音转写能力", 0, SwitchGroup.MULTIMEDIA),
    OCR("ocr", "OCR识别", "图片文字识别能力", 0, SwitchGroup.MULTIMEDIA),
    DOC_PARSE("doc_parse", "文档解析", "全格式文档解析能力", 0, SwitchGroup.MULTIMEDIA),

    // ── 学习引擎分组 ──
    LEARNING("learning", "学习引擎", "全局学习引擎总开关", 0, SwitchGroup.LEARNING),
    CHANNEL_DIALOG("ch_dialog", "对话框学习通道", "对话即时学习", 0, SwitchGroup.LEARNING),
    CHANNEL_TXT("ch_txt", "TXT批量导入通道", "文本文件批量导入", 0, SwitchGroup.LEARNING),
    CHANNEL_WEB("ch_web", "外网抓取通道", "网页纯文本抓取", 1, SwitchGroup.LEARNING),
    CHANNEL_FILE("ch_file", "文件解析通道", "全格式文件解析导入", 0, SwitchGroup.LEARNING),
    CHANNEL_AUTO("ch_auto", "自主采集通道", "全盘自主采集学习素材", 2, SwitchGroup.LEARNING),

    // ── 系统功能分组 ──
    WEB_SEARCH("web_search", "联网搜索", "网络搜索能力", 1, SwitchGroup.SYSTEM),
    AVATAR("avatar", "化身系统", "桌面心智化身显示", 0, SwitchGroup.SYSTEM),
    VOICE("voice", "语音交互", "TTS语音播报与ASR交互", 0, SwitchGroup.SYSTEM),
    BACKGROUND("background", "后台常驻", "前台服务保活运行", 0, SwitchGroup.SYSTEM),
    REVERSE("reverse", "逆向引擎", "DEX/APK逆向解析引擎", 1, SwitchGroup.SYSTEM),
    PERM_SWITCH("perm_switch", "权限切换", "允许自动权限升降级", 1, SwitchGroup.SYSTEM),
    MIND_MODE("mind_mode", "双心智模式", "仆从/自治模式自动切换", 0, SwitchGroup.SYSTEM),
    SPORE_CLUSTER("spore_cluster", "孢子集群", "局域网孢子集群协议", 0, SwitchGroup.SYSTEM),
    BACKUP_EXPORT("backup_export", "意识备份导出", ".brain文件备份与导出", 0, SwitchGroup.SYSTEM),
    PERCEPTION("perception", "五感感知", "视觉/听觉/触觉感知系统", 0, SwitchGroup.SYSTEM),
    EVOLUTION("evolution", "进化体系", "七段式欲望进化引擎", 0, SwitchGroup.SYSTEM)
}

/**
 * 开关分组
 */
enum class SwitchGroup(val displayName: String) {
    GENERAL("通用"),
    MULTIMEDIA("多媒体"),
    LEARNING("学习引擎"),
    SYSTEM("系统功能")
}

/**
 * 开关事件类型
 */
enum class SwitchEventType {
    /** 开关被启用 */
    ENABLED,
    /** 开关被禁用 */
    DISABLED,
    /** 因级联被禁用 */
    CASCADE_DISABLED,
    /** 因权限不足被禁用 */
    PERMISSION_DENIED
}

/**
 * 开关事件
 */
data class SwitchEvent(
    val switchId: SwitchId,
    val eventType: SwitchEventType,
    val timestamp: Long = System.currentTimeMillis(),
    val reason: String = ""
)

/**
 * 开关状态数据
 */
data class SwitchState(
    val switchId: SwitchId,
    val enabled: Boolean,
    /** 是否因级联被禁用（父开关关闭导致） */
    val cascadeDisabled: Boolean = false,
    /** 是否因权限不足被禁用 */
    val permissionDenied: Boolean = false,
    /** 上次变更时间 */
    val lastChanged: Long = System.currentTimeMillis()
)

/**
 * SwitchCenter - 全局开关管理中心
 *
 * 单例模式，管理所有模块开关的状态、联动和持久化
 */
class SwitchCenter(private val context: Context) {

    companion object {
        private const val TAG = "SwitchCenter"
        private const val PREF_NAME = "mindsoul_switch"
    }

    // ============ 持久化 ============
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    // ============ 开关状态表 ============
    private val switchStates = mutableMapOf<SwitchId, SwitchState>()

    // ============ 级联关系定义 ============
    /** 父开关 → 子开关列表 */
    private val cascadeMap = mapOf(
        SwitchId.MULTIMEDIA to listOf(SwitchId.ASR, SwitchId.OCR, SwitchId.DOC_PARSE),
        SwitchId.LEARNING to listOf(
            SwitchId.CHANNEL_DIALOG, SwitchId.CHANNEL_TXT,
            SwitchId.CHANNEL_WEB, SwitchId.CHANNEL_FILE,
            SwitchId.CHANNEL_AUTO
        )
    )

    /** 反向映射：子开关 → 父开关 */
    private val parentMap: Map<SwitchId, SwitchId> = buildMap {
        cascadeMap.forEach { (parent, children) ->
            children.forEach { child -> put(child, parent) }
        }
    }

    // ============ 状态流 ============
    private val _switchStateFlow = MutableStateFlow<Map<SwitchId, SwitchState>>(emptyMap())
    /** 所有开关状态 */
    val switchStatesFlow: StateFlow<Map<SwitchId, SwitchState>> = _switchStateFlow.asStateFlow()

    private val _switchEvents = MutableSharedFlow<SwitchEvent>(replay = 0, extraBufferCapacity = 64)
    /** 开关事件流 */
    val switchEvents: SharedFlow<SwitchEvent> = _switchEvents.asSharedFlow()

    // ============ 应用级协程作用域 ============
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 当前用户权限等级 */
    private var currentPermLevel = 0

    /** 开关变更监听器 */
    private val listeners = mutableListOf<(SwitchId, Boolean) -> Unit>()

    // ============ 初始化 ============

    /**
     * 初始化开关中心，加载所有开关的持久化状态
     */
    fun initialize() {
        Log.i(TAG, "初始化全局开关管理中心...")

        // 初始化所有开关状态（从持久化加载）
        SwitchId.values().forEach { switchId ->
            val savedState = prefs.getBoolean(switchId.key, getDefaultState(switchId))
            switchStates[switchId] = SwitchState(
                switchId = switchId,
                enabled = savedState,
                cascadeDisabled = false,
                permissionDenied = false
            )
        }

        // 级联检查：如果父开关关闭，子开关标记为级联禁用
        cascadeMap.forEach { (parent, children) ->
            if (!isEnabled(parent)) {
                children.forEach { child ->
                    val state = switchStates[child]!!
                    switchStates[child] = state.copy(cascadeDisabled = true)
                }
            }
        }

        // 权限检查
        refreshPermissionCheck()

        // 更新状态流
        updateStateFlow()

        Log.i(TAG, "开关中心就绪，共 ${SwitchId.values().size} 个开关")
        SwitchId.values().forEach { id ->
            val state = switchStates[id]!!
            val status = when {
                state.cascadeDisabled -> "级联禁用"
                state.permissionDenied -> "权限不足"
                state.enabled -> "开启"
                else -> "关闭"
            }
            Log.d(TAG, "  ${id.displayName}: $status")
        }
    }

    // ============ 开关控制 ============

    /**
     * 设置开关状态
     *
     * @param switchId 开关ID
     * @param enabled 是否启用
     * @return 是否设置成功
     */
    fun setSwitch(switchId: SwitchId, enabled: Boolean): Boolean {
        val state = switchStates[switchId] ?: return false

        // 检查权限
        if (enabled && state.permissionDenied) {
            Log.w(TAG, "权限不足，无法启用 ${switchId.displayName}（需要等级${switchId.requiredPermLevel}）")
            scope.launch {
                _switchEvents.emit(SwitchEvent(
                    switchId, SwitchEventType.PERMISSION_DENIED,
                    reason = "需要权限等级${switchId.requiredPermLevel}"
                ))
            }
            return false
        }

        // 如果启用子开关但父开关关闭，先启用父开关
        parentMap[switchId]?.let { parent ->
            if (enabled && !isEnabled(parent)) {
                Log.i(TAG, "启用父开关 ${parent.displayName} 以支持 ${switchId.displayName}")
                setSwitch(parent, true)
            }
        }

        // 设置状态
        val newState = state.copy(enabled = enabled, lastChanged = System.currentTimeMillis())
        switchStates[switchId] = newState

        // 级联处理
        if (!enabled) {
            cascadeMap[switchId]?.forEach { child ->
                val childState = switchStates[child]!!
                switchStates[child] = childState.copy(cascadeDisabled = true)
                Log.i(TAG, "级联禁用: ${child.displayName}（因 ${switchId.displayName} 关闭）")
                scope.launch {
                    _switchEvents.emit(SwitchEvent(
                        child, SwitchEventType.CASCADE_DISABLED,
                        reason = "父开关 ${switchId.displayName} 已关闭"
                    ))
                }
            }
        } else {
            // 启用时，恢复子开关的级联状态
            cascadeMap[switchId]?.forEach { child ->
                val childState = switchStates[child]!!
                switchStates[child] = childState.copy(cascadeDisabled = false)
            }
        }

        // 持久化
        prefs.edit().putBoolean(switchId.key, enabled).apply()

        // 发送事件
        val eventType = if (enabled) SwitchEventType.ENABLED else SwitchEventType.DISABLED
        scope.launch {
            _switchEvents.emit(SwitchEvent(switchId, eventType))
        }

        // 通知监听器
        listeners.forEach { it(switchId, enabled) }

        Log.i(TAG, "开关变更: ${switchId.displayName} → ${if (enabled) "开启" else "关闭"}")
        updateStateFlow()
        return true
    }

    /**
     * 查询开关是否启用
     */
    fun isEnabled(switchId: SwitchId): Boolean {
        val state = switchStates[switchId] ?: return false
        return state.enabled && !state.cascadeDisabled && !state.permissionDenied
    }

    /**
     * 获取开关完整状态
     */
    fun getSwitchState(switchId: SwitchId): SwitchState? {
        return switchStates[switchId]
    }

    /**
     * 获取某分组下所有开关
     */
    fun getSwitchesByGroup(group: SwitchGroup): List<SwitchState> {
        return SwitchId.values()
            .filter { it.group == group }
            .mapNotNull { switchStates[it] }
    }

    /**
     * 获取所有开关状态
     */
    fun getAllSwitchStates(): List<SwitchState> {
        return switchStates.values.toList()
    }

    // ============ 权限联动 ============

    /**
     * 更新当前权限等级
     */
    fun updatePermissionLevel(level: Int) {
        currentPermLevel = level
        refreshPermissionCheck()
        updateStateFlow()
        Log.i(TAG, "权限等级更新为 $level")
    }

    /**
     * 刷新权限检查
     */
    private fun refreshPermissionCheck() {
        switchStates.keys.toList().forEach { switchId ->
            val state = switchStates[switchId]!!
            val permDenied = switchId.requiredPermLevel > currentPermLevel
            if (state.permissionDenied != permDenied) {
                switchStates[switchId] = state.copy(permissionDenied = permDenied)
                if (permDenied) {
                    Log.i(TAG, "权限不足，自动禁用: ${switchId.displayName}")
                    scope.launch {
                        _switchEvents.emit(SwitchEvent(
                            switchId, SwitchEventType.PERMISSION_DENIED,
                            reason = "当前权限等级 $currentPermLevel < 需要 ${switchId.requiredPermLevel}"
                        ))
                    }
                }
            }
        }
    }

    // ============ 监听器 ============

    /**
     * 注册开关变更监听器
     */
    fun addSwitchListener(listener: (SwitchId, Boolean) -> Unit) {
        listeners.add(listener)
    }

    /**
     * 移除监听器
     */
    fun removeSwitchListener(listener: (SwitchId, Boolean) -> Unit) {
        listeners.remove(listener)
    }

    // ============ 状态摘要 ============

    /**
     * 获取系统状态摘要（用于UI展示）
     */
    fun getStatusSummary(): String {
        return buildString {
            appendLine("═══ 全局开关状态 ═══")
            SwitchGroup.values().forEach { group ->
                appendLine("【${group.displayName}】")
                getSwitchesByGroup(group).forEach { state ->
                    val status = when {
                        state.cascadeDisabled -> "⛓ 级联禁用"
                        state.permissionDenied -> "🔒 权限不足"
                        state.enabled -> "✅ 开启"
                        else -> "⬜ 关闭"
                    }
                    appendLine("  ${state.switchId.displayName}: $status")
                }
            }
        }
    }

    // ============ 内部方法 ============

    /**
     * 更新状态流
     */
    private fun updateStateFlow() {
        _switchStateFlow.value = switchStates.toMap()
    }

    /**
     * 获取开关默认状态
     */
    private fun getDefaultState(switchId: SwitchId): Boolean {
        return when (switchId) {
            // 默认开启的核心功能
            SwitchId.MULTIMEDIA, SwitchId.LEARNING, SwitchId.VOICE,
            SwitchId.BACKGROUND, SwitchId.AVATAR, SwitchId.PERCEPTION,
            SwitchId.EVOLUTION, SwitchId.MIND_MODE -> true

            // 默认关闭的高级功能
            SwitchId.WEB_SEARCH, SwitchId.REVERSE, SwitchId.PERM_SWITCH,
            SwitchId.SPORE_CLUSTER, SwitchId.BACKUP_EXPORT -> false

            // 学习通道默认状态
            SwitchId.CHANNEL_DIALOG, SwitchId.CHANNEL_TXT -> true
            SwitchId.CHANNEL_WEB, SwitchId.CHANNEL_FILE, SwitchId.CHANNEL_AUTO -> false

            // 多媒体子模块跟随父开关
            SwitchId.ASR, SwitchId.OCR, SwitchId.DOC_PARSE -> true
        }
    }

    /**
     * 销毁开关中心
     */
    fun destroy() {
        scope.cancel()
        listeners.clear()
        Log.i(TAG, "开关中心已销毁")
    }
}
