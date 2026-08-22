/*
 * ============================================================
 * NaturalLanguageExecutor - 自然语言执行引擎
 * ============================================================
 *
 * 离线解析口语化指令并自动执行系统操作：
 *
 * 解析流程：
 *   口语输入 → 分词 → 意图识别 → 参数提取 → 意图编译 → 权限检查 → 执行
 *
 * 支持的指令类型：
 * 1. 系统设置：WiFi/蓝牙/亮度/音量/飞行模式...
 * 2. 应用操作：打开/关闭/切换应用
 * 3. UI 操作：点击/滑动/输入/滚动
 * 4. 导航操作：返回/桌面/最近任务
 * 5. 通信操作：打电话/发短信
 * 6. 媒体控制：播放/暂停/上一首/下一首
 * 7. 定时任务：闹钟/定时器/倒计时
 * 8. 复合脚本：多步骤自动化
 *
 * 所有解析规则纯离线，不依赖网络 AI 服务。
 * ============================================================
 */
package com.kkgo.mindsoul.executor

import android.app.AlertDialog
import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 语义意图
 *
 * 自然语言解析后的中间表示
 */
data class SemanticIntent(
    /** 动作类型 */
    val action: IntentAction,
    /** 原始文本 */
    val originalText: String,
    /** 操作目标 */
    val target: String? = null,
    /** 参数键值对 */
    val parameters: Map<String, String> = emptyMap(),
    /** 置信度 [0, 1] */
    val confidence: Float = 0.8f,
    /** 子意图列表（复合操作时使用） */
    val subIntents: List<SemanticIntent>? = null
)

/**
 * 意图动作类型
 */
enum class IntentAction(val displayName: String) {
    SYSTEM_SETTING("系统设置"),
    OPEN_APP("打开应用"),
    CLOSE_APP("关闭应用"),
    UI_CLICK("点击"),
    UI_INPUT("输入"),
    UI_SCROLL("滚动"),
    NAVIGATE_BACK("返回"),
    NAVIGATE_HOME("桌面"),
    NAVIGATE_RECENTS("最近任务"),
    SEND_MESSAGE("发送消息"),
    MAKE_CALL("拨打电话"),
    MEDIA_CONTROL("媒体控制"),
    SET_ALARM("设置闹钟"),
    SET_TIMER("设置定时器"),
    SCREENSHOT("截屏"),
    COMPOUND("复合操作"),
    UNKNOWN("未知")
}

/**
 * 执行结果反馈
 */
data class ExecutorFeedback(
    /** 是否执行成功 */
    val success: Boolean,
    /** 反馈消息 */
    val message: String,
    /** 执行的指令数 */
    val commandCount: Int = 0,
    /** 执行耗时（毫秒） */
    val durationMs: Long = 0
)

/**
 * 自然语言执行引擎
 */
class NaturalLanguageExecutor(private val context: Context) {

    companion object {
        private const val TAG = "NLExecutor"
    }

    // ============ 子系统 ============
    /** 意图编译器 */
    private val intentCompiler = IntentCompiler(context)
    /** 无障碍桥接 */
    private val accessibilityBridge = AccessibilityBridge(context)
    /** 自动化脚本引擎 */
    private val automationScript = AutomationScriptEngine(context)

    // ============ 执行状态 ============
    private val _lastFeedback = MutableStateFlow<ExecutorFeedback?>(null)
    val lastFeedbackFlow: StateFlow<ExecutorFeedback?> = _lastFeedback.asStateFlow()

    /** 执行历史记录 */
    private val executionHistory = mutableListOf<ExecutionRecord>()

    // ============ 自定义命令集 ============
    private val customCommands = mutableListOf<CustomCommand>()

    /** 结果弹窗回调 */
    private var feedbackDialogCallback: ((ExecutorFeedback) -> Unit)? = null

    // ============ 初始化 ============

    /**
     * 初始化自然语言执行引擎
     */
    fun initialize() {
        automationScript.initialize()
        automationScript.setDependencies(accessibilityBridge)

        // 注册默认自定义命令
        registerDefaultCustomCommands()

        Log.i(TAG, "[初始化] 自然语言执行引擎就绪")
        Log.i(TAG, "  意图编译器 | 无障碍桥接 | 脚本引擎")
    }

    /**
     * 释放资源
     */
    fun destroy() {
        accessibilityBridge.destroy()
        automationScript.destroy()
        customCommands.clear()
        Log.i(TAG, "[销毁] 自然语言执行引擎已释放")
    }

    // ============ 核心接口 ============

    /**
     * 执行自然语言指令
     *
     * 完整流程：解析 → 编译 → 权限检查 → 执行 → 反馈
     *
     * @param text 用户口语输入
     * @return 执行结果
     */
    suspend fun execute(text: String): ExecutorFeedback {
        val startTime = System.currentTimeMillis()
        Log.i(TAG, "[执行] 收到指令: $text")

        // ── ① 检查自定义命令 ──
        val customResult = tryCustomCommand(text)
        if (customResult != null) {
            val feedback = ExecutorFeedback(true, customResult, 1,
                System.currentTimeMillis() - startTime)
            showFeedback(feedback)
            return feedback
        }

        // ── ② 检查自动化脚本触发 ──
        val script = automationScript.findScriptByTrigger(text)
        if (script != null) {
            Log.i(TAG, "[执行] 匹配脚本: ${script.name}")
            automationScript.executeScript(script.scriptId)
            val feedback = ExecutorFeedback(true, "脚本已启动: ${script.name}",
                script.steps.size, System.currentTimeMillis() - startTime)
            showFeedback(feedback)
            return feedback
        }

        // ── ③ 自然语言解析 ──
        val intent = parseNaturalLanguage(text)
        if (intent.action == IntentAction.UNKNOWN) {
            val feedback = ExecutorFeedback(false, "未能理解指令: $text",
                durationMs = System.currentTimeMillis() - startTime)
            showFeedback(feedback)
            return feedback
        }

        Log.d(TAG, "[解析] 意图: ${intent.action.displayName} | 目标: ${intent.target}")

        // ── ④ 编译为执行计划 ──
        val plan = intentCompiler.compile(intent)
        if (plan.commands.isEmpty()) {
            val feedback = ExecutorFeedback(false, "未能编译为可执行指令",
                durationMs = System.currentTimeMillis() - startTime)
            showFeedback(feedback)
            return feedback
        }

        Log.d(TAG, "[编译] 执行计划: ${plan.commands.size} 条命令")

        // ── ⑤ 权限检查 ──
        if (plan.requiresRoot) {
            val feedback = ExecutorFeedback(false,
                "⚠️ 此操作需要ROOT权限，请先提升权限等级",
                durationMs = System.currentTimeMillis() - startTime)
            showFeedback(feedback)
            return feedback
        }

        // ── ⑥ 执行 ──
        val result = try {
            accessibilityBridge.executePlan(plan)
        } catch (e: Exception) {
            Log.e(TAG, "[执行] 异常: ${e.message}")
            null
        }

        val feedback = if (result != null && result.overallSuccess) {
            ExecutorFeedback(true,
                "✅ 已执行: ${text}",
                plan.commands.size,
                System.currentTimeMillis() - startTime)
        } else {
            ExecutorFeedback(false,
                "⚠️ 部分指令执行失败",
                plan.commands.size,
                System.currentTimeMillis() - startTime)
        }

        // ── ⑦ 记录历史 ──
        executionHistory.add(ExecutionRecord(
            text = text,
            intent = intent.action,
            success = feedback.success,
            timestamp = System.currentTimeMillis()
        ))

        // ── ⑧ 弹窗反馈 ──
        showFeedback(feedback)

        return feedback
    }

    /**
     * 批量执行（复合自动化脚本）
     *
     * @param commands 多个指令（用分号分隔）
     * @return 执行结果
     */
    suspend fun executeBatch(commands: String): ExecutorFeedback {
        val startTime = System.currentTimeMillis()
        val lines = commands.split("[;；\n]".toRegex()).map { it.trim() }.filter { it.isNotEmpty() }

        Log.i(TAG, "[批量] ${lines.size} 条指令")

        // 逐条解析并编译
        val allIntents = lines.map { parseNaturalLanguage(it) }
        val plan = intentCompiler.compileBatch(allIntents)

        val result = accessibilityBridge.executePlan(plan)

        val feedback = ExecutorFeedback(
            success = result.overallSuccess,
            message = if (result.overallSuccess) "✅ 批量执行完成 (${lines.size} 条)"
                     else "⚠️ 批量执行部分失败",
            commandCount = plan.commands.size,
            durationMs = System.currentTimeMillis() - startTime
        )

        showFeedback(feedback)
        return feedback
    }

    /**
     * 从自然语言生成并保存自动化脚本
     *
     * @param description 脚本描述（步骤用分号分隔）
     * @param name 脚本名称
     * @return 是否成功创建
     */
    fun createScript(description: String, name: String): Boolean {
        return try {
            automationScript.generateFromDescription(description, name)
            true
        } catch (e: Exception) {
            Log.e(TAG, "[脚本] 创建失败: ${e.message}")
            false
        }
    }

    // ============ 自然语言解析（离线规则引擎） ============

    /**
     * 离线解析自然语言为语义意图
     *
     * 纯规则匹配，不使用任何AI库或网络服务
     */
    fun parseNaturalLanguage(text: String): SemanticIntent {
        val lower = text.lowercase().trim()

        // ── 系统设置类 ──
        if (matchAny(lower, "wifi", "无线网", "无线网络")) {
            val value = extractSettingValue(lower)
            return SemanticIntent(IntentAction.SYSTEM_SETTING, text, "wifi",
                mapOf("value" to value))
        }
        if (matchAny(lower, "蓝牙")) {
            val value = extractSettingValue(lower)
            return SemanticIntent(IntentAction.SYSTEM_SETTING, text, "蓝牙",
                mapOf("value" to value))
        }
        if (matchAny(lower, "亮度", "屏幕亮度")) {
            val value = extractBrightnessValue(lower)
            return SemanticIntent(IntentAction.SYSTEM_SETTING, text, "亮度",
                mapOf("value" to value))
        }
        if (matchAny(lower, "音量")) {
            val target = when {
                matchAny(lower, "媒体", "音乐", "歌") -> "媒体"
                matchAny(lower, "铃声", "来电") -> "铃声"
                matchAny(lower, "闹钟") -> "闹钟"
                matchAny(lower, "通知") -> "通知"
                else -> "媒体"
            }
            val value = extractVolumeValue(lower)
            return SemanticIntent(IntentAction.SYSTEM_SETTING, text, "${target}音量",
                mapOf("value" to value))
        }
        if (matchAny(lower, "飞行模式")) {
            val value = extractSettingValue(lower)
            return SemanticIntent(IntentAction.SYSTEM_SETTING, text, "飞行模式",
                mapOf("value" to value))
        }
        if (matchAny(lower, "勿扰", "免打扰", "dnd")) {
            val value = extractSettingValue(lower)
            return SemanticIntent(IntentAction.SYSTEM_SETTING, text, "勿扰",
                mapOf("value" to value))
        }
        if (matchAny(lower, "自动旋转", "屏幕旋转")) {
            val value = extractSettingValue(lower)
            return SemanticIntent(IntentAction.SYSTEM_SETTING, text, "旋转",
                mapOf("value" to value))
        }
        if (matchAny(lower, "手电筒", "照明")) {
            val value = extractSettingValue(lower)
            return SemanticIntent(IntentAction.SYSTEM_SETTING, text, "手电筒",
                mapOf("value" to value))
        }
        if (matchAny(lower, "深色模式", "夜间模式", "暗色")) {
            val value = extractSettingValue(lower)
            return SemanticIntent(IntentAction.SYSTEM_SETTING, text, "深色",
                mapOf("value" to value))
        }
        if (matchAny(lower, "定位", "gps")) {
            val value = extractSettingValue(lower)
            return SemanticIntent(IntentAction.SYSTEM_SETTING, text, "定位",
                mapOf("value" to value))
        }
        if (matchAny(lower, "nfc")) {
            val value = extractSettingValue(lower)
            return SemanticIntent(IntentAction.SYSTEM_SETTING, text, "nfc",
                mapOf("value" to value))
        }
        if (matchAny(lower, "移动数据", "流量")) {
            val value = extractSettingValue(lower)
            return SemanticIntent(IntentAction.SYSTEM_SETTING, text, "移动数据",
                mapOf("value" to value))
        }

        // ── 应用操作类 ──
        if (matchAny(lower, "打开", "启动", "进入", "开启")) {
            val appName = extractAppName(lower)
            return SemanticIntent(IntentAction.OPEN_APP, text, target = appName)
        }

        // ── 通信类 ──
        if (matchAny(lower, "打电话", "拨号", "拨打")) {
            val phone = extractPhoneNumber(lower)
            return SemanticIntent(IntentAction.MAKE_CALL, text, target = phone)
        }
        if (matchAny(lower, "发短信", "发信息", "发消息")) {
            val phone = extractPhoneNumber(lower)
            val message = extractMessageContent(lower)
            return SemanticIntent(IntentAction.SEND_MESSAGE, text, target = phone,
                parameters = mapOf("phone" to (phone ?: ""), "message" to message))
        }

        // ── 导航类 ──
        if (matchAny(lower, "返回", "后退", "back")) {
            return SemanticIntent(IntentAction.NAVIGATE_BACK, text)
        }
        if (matchAny(lower, "桌面", "主页", "回到桌面", "home")) {
            return SemanticIntent(IntentAction.NAVIGATE_HOME, text)
        }
        if (matchAny(lower, "最近任务", "多任务", "recent")) {
            return SemanticIntent(IntentAction.NAVIGATE_RECENTS, text)
        }

        // ── 媒体控制 ──
        if (matchAny(lower, "播放", "继续播放", "play")) {
            return SemanticIntent(IntentAction.MEDIA_CONTROL, text, target = "播放")
        }
        if (matchAny(lower, "暂停", "pause")) {
            return SemanticIntent(IntentAction.MEDIA_CONTROL, text, target = "暂停")
        }
        if (matchAny(lower, "上一首", "上一曲")) {
            return SemanticIntent(IntentAction.MEDIA_CONTROL, text, target = "上一首")
        }
        if (matchAny(lower, "下一首", "下一曲")) {
            return SemanticIntent(IntentAction.MEDIA_CONTROL, text, target = "下一首")
        }

        // ── 闹钟 ──
        if (matchAny(lower, "闹钟", "设置闹钟")) {
            val time = extractTime(lower)
            return SemanticIntent(IntentAction.SET_ALARM, text,
                parameters = mapOf("time" to time))
        }
        if (matchAny(lower, "倒计时", "定时器")) {
            val duration = extractDuration(lower)
            return SemanticIntent(IntentAction.SET_TIMER, text,
                parameters = mapOf("duration" to duration))
        }

        // ── 截屏 ──
        if (matchAny(lower, "截屏", "截图", "screenshot")) {
            return SemanticIntent(IntentAction.SCREENSHOT, text)
        }

        // ── UI 操作 ──
        if (matchAny(lower, "点击", "按下")) {
            val target = extractUITarget(lower)
            return SemanticIntent(IntentAction.UI_CLICK, text, target = target)
        }
        if (matchAny(lower, "输入", "填写", "打字")) {
            val text_content = extractInputContent(lower)
            return SemanticIntent(IntentAction.UI_INPUT, text,
                parameters = mapOf("text" to text_content))
        }
        if (matchAny(lower, "滑动", "滚动", "上滑", "下滑")) {
            val direction = extractScrollDirection(lower)
            return SemanticIntent(IntentAction.UI_SCROLL, text,
                parameters = mapOf("direction" to direction))
        }

        // ── 复合操作（检测分号） ──
        if (text.contains(";") || text.contains("；") || text.contains("然后")) {
            val parts = text.split("[;；]".toRegex()).map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.size > 1) {
                val subIntents = parts.map { parseNaturalLanguage(it) }
                return SemanticIntent(IntentAction.COMPOUND, text, subIntents = subIntents)
            }
        }

        // ── 未知 ──
        return SemanticIntent(IntentAction.UNKNOWN, text)
    }

    // ============ 自定义命令 ============

    /**
     * 注册自定义命令
     */
    fun registerCustomCommand(command: CustomCommand) {
        customCommands.add(command)
        Log.d(TAG, "[命令] 注册自定义: ${command.keywords.joinToString("/")}")
    }

    /**
     * 移除自定义命令
     */
    fun removeCustomCommand(commandId: String) {
        customCommands.removeAll { it.id == commandId }
    }

    /**
     * 获取所有自定义命令
     */
    fun getCustomCommands(): List<CustomCommand> = customCommands.toList()

    // ============ 弹窗反馈 ============

    /**
     * 设置反馈弹窗回调
     */
    fun setFeedbackDialogCallback(callback: (ExecutorFeedback) -> Unit) {
        feedbackDialogCallback = callback
    }

    // ============ 历史记录 ============

    /**
     * 获取执行历史
     */
    fun getExecutionHistory(limit: Int = 50): List<ExecutionRecord> {
        return executionHistory.takeLast(limit).reversed()
    }

    /**
     * 清除历史
     */
    fun clearHistory() {
        executionHistory.clear()
    }

    // ============ 获取子系统引用 ============

    fun getAccessibilityBridge(): AccessibilityBridge = accessibilityBridge
    fun getAutomationScriptEngine(): AutomationScriptEngine = automationScript

    // ============ 内部方法 ============

    /**
     * 尝试执行自定义命令
     */
    private fun tryCustomCommand(text: String): String? {
        val lower = text.lowercase()
        for (cmd in customCommands) {
            if (cmd.enabled && cmd.keywords.any { lower.contains(it.lowercase()) }) {
                Log.i(TAG, "[自定义] 匹配: ${cmd.name}")
                return cmd.responseText
            }
        }
        return null
    }

    /**
     * 显示反馈弹窗
     */
    private fun showFeedback(feedback: ExecutorFeedback) {
        _lastFeedback.value = feedback
        feedbackDialogCallback?.invoke(feedback)
    }

    // ── 解析辅助方法 ──

    private fun matchAny(text: String, vararg keywords: String): Boolean {
        return keywords.any { text.contains(it) }
    }

    /**
     * 提取设置值（开/关/切换）
     */
    private fun extractSettingValue(text: String): String {
        return when {
            matchAny(text, "打开", "开启", "开", "on", "启用") -> "on"
            matchAny(text, "关闭", "关掉", "关", "off", "禁用") -> "off"
            else -> "toggle"
        }
    }

    /**
     * 提取亮度值
     */
    private fun extractBrightnessValue(text: String): String {
        if (matchAny(text, "自动")) return "auto"
        val percent = "(\\d+)\\s*%".toRegex().find(text)?.groupValues?.get(1)?.toIntOrNull()
        if (percent != null) return (percent * 255 / 100).toString()
        if (matchAny(text, "最亮", "最大", "高")) return "255"
        if (matchAny(text, "最暗", "最小", "低")) return "10"
        return "auto"
    }

    /**
     * 提取音量值
     */
    private fun extractVolumeValue(text: String): String {
        val percent = "(\\d+)\\s*%".toRegex().find(text)?.groupValues?.get(1)?.toIntOrNull()
        if (percent != null) return percent.toString()
        if (matchAny(text, "最大", "最响", "调大", "大声")) return "max"
        if (matchAny(text, "最小", "最轻", "调小", "小声")) return "min"
        return "toggle"
    }

    /**
     * 提取应用名称
     */
    private fun extractAppName(text: String): String {
        // 移除指令动词
        var name = text
        for (verb in listOf("打开", "启动", "进入", "开启", "帮我打开", "请打开")) {
            name = name.replace(verb, "")
        }
        return name.trim()
    }

    /**
     * 提取电话号码
     */
    private fun extractPhoneNumber(text: String): String? {
        return "(\\d{11})".toRegex().find(text)?.groupValues?.get(1)
            ?: "(\\d{3,4}[- ]?\\d{7,8})".toRegex().find(text)?.groupValues?.get(1)
    }

    /**
     * 提取短信内容
     */
    private fun extractMessageContent(text: String): String {
        // 尝试提取 "告诉XXX" 或 "说" 后面的内容
        val sayMatch = "说(.+)".toRegex().find(text)
        if (sayMatch != null) return sayMatch.groupValues[1].trim()
        val contentMatch = "内容(.+)".toRegex().find(text)
        if (contentMatch != null) return contentMatch.groupValues[1].trim()
        return ""
    }

    /**
     * 提取时间
     */
    private fun extractTime(text: String): String {
        val timeMatch = "(\\d{1,2})[点:：](\\d{1,2})".toRegex().find(text)
        if (timeMatch != null) return "${timeMatch.groupValues[1]}:${timeMatch.groupValues[2]}"
        val hourMatch = "(\\d{1,2})\\s*点".toRegex().find(text)
        if (hourMatch != null) return "${hourMatch.groupValues[1]}:00"
        return "07:00"  // 默认
    }

    /**
     * 提取时长
     */
    private fun extractDuration(text: String): String {
        val minMatch = "(\\d+)\\s*分".toRegex().find(text)
        val secMatch = "(\\d+)\\s*秒".toRegex().find(text)
        val minutes = minMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val seconds = secMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return "${minutes * 60 + seconds}"
    }

    /**
     * 提取 UI 目标
     */
    private fun extractUITarget(text: String): String {
        var target = text
        for (verb in listOf("点击", "按下", "点一下", "帮我点")) {
            target = target.replace(verb, "")
        }
        return target.trim()
    }

    /**
     * 提取输入内容
     */
    private fun extractInputContent(text: String): String {
        var content = text
        for (verb in listOf("输入", "填写", "打字", "写入")) {
            content = content.replace(verb, "")
        }
        return content.trim()
    }

    /**
     * 提取滚动方向
     */
    private fun extractScrollDirection(text: String): String {
        return when {
            matchAny(text, "上滑", "向上", "上") -> "up"
            matchAny(text, "下滑", "向下", "下") -> "down"
            matchAny(text, "左滑", "向左", "左") -> "left"
            matchAny(text, "右滑", "向右", "右") -> "right"
            else -> "down"
        }
    }

    /**
     * 注册默认自定义命令
     */
    private fun registerDefaultCustomCommands() {
        registerCustomCommand(CustomCommand(
            name = "你好",
            keywords = listOf("你好", "hello", "hi", "嗨"),
            responseText = "你好呀！我是 MindSoul，有什么可以帮你的吗？"
        ))
        registerCustomCommand(CustomCommand(
            name = "帮助",
            keywords = listOf("帮助", "help", "怎么用"),
            responseText = "我支持以下操作：\n" +
                    "• 系统设置：打开/关闭WiFi、蓝牙、亮度调节\n" +
                    "• 应用操作：打开微信、浏览器等\n" +
                    "• 通信：打电话、发短信\n" +
                    "• 导航：返回、桌面、最近任务\n" +
                    "• 自动化：分号分隔多步骤"
        ))
    }
}

/**
 * 自定义命令
 */
data class CustomCommand(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val keywords: List<String>,
    val responseText: String,
    var enabled: Boolean = true
)

/**
 * 执行记录
 */
data class ExecutionRecord(
    val text: String,
    val intent: IntentAction,
    val success: Boolean,
    val timestamp: Long
)
