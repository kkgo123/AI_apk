/*
 * ============================================================
 * AutomationScript - 自动化脚本引擎
 * ============================================================
 *
 * 支持将多个指令组合为一键复合自动化脚本：
 *
 * 功能：
 * 1. 脚本定义（声明式 DSL）
 *    - 顺序执行、条件分支、循环
 *    - 等待、延时、重试
 * 2. 脚本存储与加载
 *    - 本地持久化
 *    - 脚本导入/导出
 * 3. 执行引擎
 *    - 按步骤顺序执行
 *    - 异常处理与恢复
 *    - 执行进度回调
 * 4. 自定义命令集生成
 *    - 从操作录制生成脚本
 *    - 参数化模板脚本
 * ============================================================
 */
package com.kkgo.mindsoul.executor

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 脚本步骤类型
 */
sealed class ScriptStep {
    /** 步骤ID */
    abstract val stepId: String
    /** 步骤描述 */
    abstract val description: String

    /**
     * 执行命令步骤
     */
    data class CommandStep(
        override val stepId: String = UUID.randomUUID().toString(),
        override val description: String,
        /** 编译后的命令 */
        val command: CompiledCommand
    ) : ScriptStep()

    /**
     * 等待步骤
     */
    data class WaitStep(
        override val stepId: String = UUID.randomUUID().toString(),
        override val description: String = "等待",
        /** 等待时间（毫秒） */
        val durationMs: Long
    ) : ScriptStep()

    /**
     * 条件分支步骤
     */
    data class ConditionStep(
        override val stepId: String = UUID.randomUUID().toString(),
        override val description: String = "条件判断",
        /** 条件类型 */
        val conditionType: ConditionType,
        /** 条件参数 */
        val conditionParam: String,
        /** 为真时执行的步骤 */
        val thenSteps: List<ScriptStep>,
        /** 为假时执行的步骤 */
        val elseSteps: List<ScriptStep> = emptyList()
    ) : ScriptStep()

    /**
     * 循环步骤
     */
    data class LoopStep(
        override val stepId: String = UUID.randomUUID().toString(),
        override val description: String = "循环",
        /** 循环次数（-1 为无限循环） */
        val count: Int,
        /** 循环体步骤 */
        val bodySteps: List<ScriptStep>
    ) : ScriptStep()

    /**
     * 等待条件满足步骤
     */
    data class WaitForStep(
        override val stepId: String = UUID.randomUUID().toString(),
        override val description: String = "等待条件",
        /** 等待条件 */
        val conditionType: ConditionType,
        /** 条件参数 */
        val conditionParam: String,
        /** 超时（毫秒） */
        val timeoutMs: Long = 30_000L
    ) : ScriptStep()
}

/**
 * 条件类型
 */
enum class ConditionType {
    /** 当前前台应用匹配 */
    FOREGROUND_APP,
    /** 屏幕上存在指定文本 */
    TEXT_EXISTS,
    /** 屏幕上不存在指定文本 */
    TEXT_NOT_EXISTS,
    /** WiFi 已开启 */
    WIFI_ON,
    /** 蓝牙已开启 */
    BLUETOOTH_ON,
    /** 屏幕亮度大于 */
    BRIGHTNESS_ABOVE,
    /** 固定延时后 */
    AFTER_DELAY
}

/**
 * 自动化脚本
 */
data class AutomationScript(
    /** 脚本ID */
    val scriptId: String = UUID.randomUUID().toString(),
    /** 脚本名称 */
    val name: String,
    /** 脚本描述 */
    val description: String = "",
    /** 步骤列表 */
    val steps: List<ScriptStep>,
    /** 触发关键词（可选） */
    val triggerKeywords: List<String> = emptyList(),
    /** 参数模板 */
    val parameters: Map<String, String> = emptyMap(),
    /** 创建时间 */
    val createdAt: Long = System.currentTimeMillis(),
    /** 最后执行时间 */
    var lastExecutedAt: Long = 0L,
    /** 执行次数 */
    var executionCount: Int = 0
)

/**
 * 脚本执行状态
 */
enum class ScriptExecutionState {
    IDLE,
    RUNNING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED
}

/**
 * 脚本执行进度
 */
data class ScriptProgress(
    /** 当前步骤索引 */
    val currentStepIndex: Int,
    /** 总步骤数 */
    val totalSteps: Int,
    /** 当前步骤描述 */
    val currentStepDescription: String,
    /** 执行状态 */
    val state: ScriptExecutionState,
    /** 进度百分比 [0, 100] */
    val progressPercent: Int = if (totalSteps > 0) (currentStepIndex * 100 / totalSteps) else 0
)

/**
 * 自动化脚本引擎
 */
class AutomationScriptEngine(private val context: Context) {

    companion object {
        private const val TAG = "AutomationScript"
        private const val PREF_NAME = "mindsoul_scripts"
        private const val KEY_SCRIPT_IDS = "script_ids"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    // ============ 脚本仓库 ============
    /** 已保存的脚本 Map<scriptId, Script> */
    private val scriptRepository = mutableMapOf<String, AutomationScript>()

    // ============ 执行状态 ============
    private val _executionState = MutableStateFlow(ScriptExecutionState.IDLE)
    val executionStateFlow: StateFlow<ScriptExecutionState> = _executionState.asStateFlow()

    private val _progress = MutableStateFlow(ScriptProgress(0, 0, "", ScriptExecutionState.IDLE))
    val progressFlow: StateFlow<ScriptProgress> = _progress.asStateFlow()

    /** 执行协程 */
    private var executionJob: Job? = null
    private val execScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ============ 依赖 ============
    /** 无障碍桥接（由外部注入） */
    private var accessibilityBridge: AccessibilityBridge? = null
    /** 意图编译器 */
    private val intentCompiler = IntentCompiler(context)

    /** 执行结果回调 */
    private var resultCallback: ((ScriptResult) -> Unit)? = null

    // ============ 初始化 ============

    /**
     * 初始化脚本引擎
     */
    fun initialize() {
        loadSavedScripts()
        Log.i(TAG, "[初始化] 脚本引擎就绪, 已加载 ${scriptRepository.size} 个脚本")
    }

    /**
     * 注入依赖
     */
    fun setDependencies(bridge: AccessibilityBridge) {
        accessibilityBridge = bridge
    }

    /**
     * 设置执行结果回调
     */
    fun setResultCallback(callback: (ScriptResult) -> Unit) {
        resultCallback = callback
    }

    /**
     * 释放资源
     */
    fun destroy() {
        stopExecution()
        execScope.cancel()
        Log.i(TAG, "[销毁] 脚本引擎已释放")
    }

    // ============ 脚本管理 ============

    /**
     * 保存脚本
     */
    fun saveScript(script: AutomationScript) {
        scriptRepository[script.scriptId] = script
        persistScriptIds()
        Log.i(TAG, "[脚本] 保存: ${script.name} (${script.steps.size} 步)")
    }

    /**
     * 删除脚本
     */
    fun deleteScript(scriptId: String) {
        scriptRepository.remove(scriptId)
        persistScriptIds()
        Log.i(TAG, "[脚本] 删除: $scriptId")
    }

    /**
     * 获取所有脚本
     */
    fun getAllScripts(): List<AutomationScript> = scriptRepository.values.toList()

    /**
     * 根据触发词查找脚本
     */
    fun findScriptByTrigger(text: String): AutomationScript? {
        val lower = text.lowercase()
        return scriptRepository.values.find { script ->
            script.triggerKeywords.any { keyword -> lower.contains(keyword.lowercase()) }
        }
    }

    // ============ 脚本生成 ============

    /**
     * 从自然语言描述生成脚本
     *
     * @param description 自然语言描述
     * @param name 脚本名称
     * @return 生成的脚本
     */
    fun generateFromDescription(description: String, name: String): AutomationScript {
        Log.i(TAG, "[生成] 从描述生成脚本: $description")

        val steps = mutableListOf<ScriptStep>()
        val lines = description.split("[;；\n]".toRegex()).map { it.trim() }.filter { it.isNotEmpty() }

        for (line in lines) {
            when {
                line.startsWith("等待") || line.startsWith("延时") -> {
                    val ms = extractDurationMs(line)
                    steps.add(ScriptStep.WaitStep(description = line, durationMs = ms))
                }
                line.startsWith("如果") || line.startsWith("当") -> {
                    // 简化条件分支
                    steps.add(ScriptStep.ConditionStep(
                        description = line,
                        conditionType = ConditionType.TEXT_EXISTS,
                        conditionParam = line.removePrefix("如果").removePrefix("当").trim(),
                        thenSteps = listOf(ScriptStep.CommandStep(
                            description = "条件满足执行",
                            command = CompiledCommand.AccessibilityCommand(
                                action = AccessibilityAction.CLICK,
                                targetText = line.removePrefix("如果").removePrefix("当").trim()
                            )
                        ))
                    ))
                }
                line.startsWith("循环") || line.startsWith("重复") -> {
                    val count = extractNumber(line) ?: 3
                    steps.add(ScriptStep.LoopStep(
                        description = line,
                        count = count,
                        bodySteps = emptyList()  // 循环体由后续步骤填充
                    ))
                }
                else -> {
                    // 解析为自然语言意图并编译
                    val intent = parseSimpleIntent(line)
                    val plan = intentCompiler.compile(intent)
                    for (cmd in plan.commands) {
                        steps.add(ScriptStep.CommandStep(
                            description = line,
                            command = cmd
                        ))
                    }
                }
            }
        }

        val script = AutomationScript(
            name = name,
            description = description,
            steps = steps
        )

        saveScript(script)
        return script
    }

    /**
     * 从操作录制生成脚本
     *
     * @param recordedActions 录制的操作序列
     * @param name 脚本名称
     * @return 生成的脚本
     */
    fun generateFromRecording(recordedActions: List<RecordedAction>, name: String): AutomationScript {
        val steps = recordedActions.map { action ->
            when (action.type) {
                RecordedActionType.CLICK -> ScriptStep.CommandStep(
                    description = "点击 (${action.x.toInt()}, ${action.y.toInt()})",
                    command = CompiledCommand.TouchCommand(
                        touchType = TouchType.TAP,
                        x = action.x, y = action.y
                    )
                )
                RecordedActionType.LONG_PRESS -> ScriptStep.CommandStep(
                    description = "长按 (${action.x.toInt()}, ${action.y.toInt()})",
                    command = CompiledCommand.TouchCommand(
                        touchType = TouchType.LONG_PRESS,
                        x = action.x, y = action.y,
                        durationMs = 600L
                    )
                )
                RecordedActionType.SWIPE -> ScriptStep.CommandStep(
                    description = "滑动",
                    command = CompiledCommand.TouchCommand(
                        touchType = TouchType.SWIPE_UP,
                        x = action.x, y = action.y,
                        endX = action.endX, endY = action.endY
                    )
                )
                RecordedActionType.INPUT -> ScriptStep.CommandStep(
                    description = "输入: ${action.text}",
                    command = CompiledCommand.AccessibilityCommand(
                        action = AccessibilityAction.INPUT_TEXT,
                        inputText = action.text
                    )
                )
                RecordedActionType.WAIT -> ScriptStep.WaitStep(
                    description = "等待 ${action.durationMs}ms",
                    durationMs = action.durationMs
                )
                RecordedActionType.BACK -> ScriptStep.CommandStep(
                    description = "返回",
                    command = CompiledCommand.AccessibilityCommand(
                        action = AccessibilityAction.BACK
                    )
                )
                RecordedActionType.HOME -> ScriptStep.CommandStep(
                    description = "回到桌面",
                    command = CompiledCommand.AccessibilityCommand(
                        action = AccessibilityAction.HOME
                    )
                )
            }
        }

        val script = AutomationScript(
            name = name,
            description = "录制脚本: ${recordedActions.size} 个操作",
            steps = steps
        )

        saveScript(script)
        Log.i(TAG, "[录制] 脚本已生成: $name, ${steps.size} 步")
        return script
    }

    // ============ 执行控制 ============

    /**
     * 执行脚本
     *
     * @param scriptId 脚本ID
     */
    fun executeScript(scriptId: String) {
        val script = scriptRepository[scriptId]
        if (script == null) {
            Log.e(TAG, "[执行] 脚本不存在: $scriptId")
            return
        }

        executionJob?.cancel()
        executionJob = execScope.launch {
            _executionState.value = ScriptExecutionState.RUNNING
            script.lastExecutedAt = System.currentTimeMillis()
            script.executionCount++

            Log.i(TAG, "[执行] 开始: ${script.name} (${script.steps.size} 步)")

            var stepIndex = 0
            val totalSteps = script.steps.size

            try {
                for (step in script.steps) {
                    if (_executionState.value == ScriptExecutionState.CANCELLED) break

                    _progress.value = ScriptProgress(
                        currentStepIndex = stepIndex,
                        totalSteps = totalSteps,
                        currentStepDescription = step.description,
                        state = ScriptExecutionState.RUNNING
                    )

                    executeStep(step)
                    stepIndex++
                }

                _executionState.value = ScriptExecutionState.COMPLETED
                _progress.value = ScriptProgress(
                    totalSteps, totalSteps, "完成", ScriptExecutionState.COMPLETED, 100
                )

                resultCallback?.invoke(ScriptResult(scriptId, true, "脚本执行完成"))
                Log.i(TAG, "[执行] 完成: ${script.name}")

            } catch (e: Exception) {
                _executionState.value = ScriptExecutionState.FAILED
                resultCallback?.invoke(ScriptResult(scriptId, false, "执行失败: ${e.message}"))
                Log.e(TAG, "[执行] 失败: ${script.name} - ${e.message}")
            }
        }
    }

    /**
     * 停止执行
     */
    fun stopExecution() {
        executionJob?.cancel()
        _executionState.value = ScriptExecutionState.CANCELLED
        Log.i(TAG, "[执行] 已停止")
    }

    /**
     * 暂停执行
     */
    fun pauseExecution() {
        _executionState.value = ScriptExecutionState.PAUSED
        Log.i(TAG, "[执行] 已暂停")
    }

    /**
     * 恢复执行
     */
    fun resumeExecution() {
        _executionState.value = ScriptExecutionState.RUNNING
        Log.i(TAG, "[执行] 已恢复")
    }

    // ============ 内部方法 ============

    /**
     * 执行单个步骤
     */
    private suspend fun executeStep(step: ScriptStep) {
        val bridge = accessibilityBridge
        when (step) {
            is ScriptStep.CommandStep -> {
                val plan = ExecutionPlan(
                    originalText = step.description,
                    commands = listOf(step.command)
                )
                bridge?.executePlan(plan)
            }
            is ScriptStep.WaitStep -> {
                delay(step.durationMs)
            }
            is ScriptStep.ConditionStep -> {
                val conditionMet = evaluateCondition(step.conditionType, step.conditionParam)
                val branchSteps = if (conditionMet) step.thenSteps else step.elseSteps
                for (s in branchSteps) {
                    executeStep(s)
                }
            }
            is ScriptStep.LoopStep -> {
                val count = if (step.count == -1) Int.MAX_VALUE else step.count
                for (i in 0 until count) {
                    if (_executionState.value == ScriptExecutionState.CANCELLED) break
                    for (s in step.bodySteps) {
                        executeStep(s)
                    }
                }
            }
            is ScriptStep.WaitForStep -> {
                val startTime = System.currentTimeMillis()
                while (System.currentTimeMillis() - startTime < step.timeoutMs) {
                    if (evaluateCondition(step.conditionType, step.conditionParam)) break
                    delay(500)
                }
            }
        }
    }

    /**
     * 评估条件
     */
    private fun evaluateCondition(type: ConditionType, param: String): Boolean {
        val bridge = accessibilityBridge
        return when (type) {
            ConditionType.FOREGROUND_APP -> {
                bridge?.getCurrentForegroundPackage() == param
            }
            ConditionType.TEXT_EXISTS -> {
                val snapshot = bridge?.getScreenSnapshot()
                snapshotContainsText(snapshot, param)
            }
            ConditionType.TEXT_NOT_EXISTS -> {
                val snapshot = bridge?.getScreenSnapshot()
                !snapshotContainsText(snapshot, param)
            }
            ConditionType.WIFI_ON -> {
                try {
                    android.provider.Settings.Global.getInt(
                        context.contentResolver,
                        android.provider.Settings.Global.WIFI_ON
                    ) == 1
                } catch (e: Exception) { false }
            }
            ConditionType.BLUETOOTH_ON -> false // 需要蓝牙适配器
            ConditionType.BRIGHTNESS_ABOVE -> {
                try {
                    val brightness = android.provider.Settings.System.getInt(
                        context.contentResolver,
                        android.provider.Settings.System.SCREEN_BRIGHTNESS
                    )
                    brightness > (param.toIntOrNull() ?: 0)
                } catch (e: Exception) { false }
            }
            ConditionType.AFTER_DELAY -> true
        }
    }

    /**
     * 在快照中搜索文本
     */
    private fun snapshotContainsText(snapshot: ViewSnapshot?, text: String): Boolean {
        if (snapshot == null) return false
        if (snapshot.text?.contains(text) == true) return true
        if (snapshot.contentDescription?.contains(text) == true) return true
        return snapshot.children.any { snapshotContainsText(it, text) }
    }

    /**
     * 解析简单自然语言意图
     */
    private fun parseSimpleIntent(text: String): SemanticIntent {
        return when {
            "打开" in text || "启动" in text -> {
                val appName = text.removePrefix("打开").removePrefix("启动").trim()
                SemanticIntent(IntentAction.OPEN_APP, text, target = appName)
            }
            "wifi" in text.lowercase() || "无线" in text -> {
                SemanticIntent(IntentAction.SYSTEM_SETTING, text, target = "wifi",
                    parameters = mapOf("value" to if ("开" in text) "on" else if ("关" in text) "off" else "toggle"))
            }
            "蓝牙" in text -> {
                SemanticIntent(IntentAction.SYSTEM_SETTING, text, target = "蓝牙",
                    parameters = mapOf("value" to if ("开" in text) "on" else "off"))
            }
            "亮度" in text -> {
                val value = extractNumber(text)?.toString() ?: "auto"
                SemanticIntent(IntentAction.SYSTEM_SETTING, text, target = "亮度",
                    parameters = mapOf("value" to value))
            }
            "返回" in text -> SemanticIntent(IntentAction.NAVIGATE_BACK, text)
            "桌面" in text || "主页" in text -> SemanticIntent(IntentAction.NAVIGATE_HOME, text)
            else -> SemanticIntent(IntentAction.UI_CLICK, text, target = text)
        }
    }

    /**
     * 从文本中提取时长
     */
    private fun extractDurationMs(text: String): Long {
        val seconds = "(\\d+)\\s*秒".toRegex().find(text)?.groupValues?.get(1)?.toLongOrNull()
        if (seconds != null) return seconds * 1000
        val minutes = "(\\d+)\\s*分".toRegex().find(text)?.groupValues?.get(1)?.toLongOrNull()
        if (minutes != null) return minutes * 60_000
        val ms = "(\\d+)\\s*毫秒".toRegex().find(text)?.groupValues?.get(1)?.toLongOrNull()
        if (ms != null) return ms
        return 1000L  // 默认 1 秒
    }

    /**
     * 从文本中提取数字
     */
    private fun extractNumber(text: String): Int? {
        return "(\\d+)".toRegex().find(text)?.groupValues?.get(1)?.toIntOrNull()
    }

    /**
     * 加载已保存的脚本ID列表
     */
    private fun loadSavedScripts() {
        val ids = prefs.getString(KEY_SCRIPT_IDS, "")?.split(",")?.filter { it.isNotEmpty() } ?: emptyList()
        // 实际应从文件加载完整脚本数据（此处简化为初始化空列表）
        Log.d(TAG, "[加载] 脚本ID列表: ${ids.size} 个")
    }

    /**
     * 持久化脚本ID列表
     */
    private fun persistScriptIds() {
        val ids = scriptRepository.keys.joinToString(",")
        prefs.edit().putString(KEY_SCRIPT_IDS, ids).apply()
    }
}

/**
 * 录制操作类型
 */
enum class RecordedActionType {
    CLICK, LONG_PRESS, SWIPE, INPUT, WAIT, BACK, HOME
}

/**
 * 录制的操作
 */
data class RecordedAction(
    val type: RecordedActionType,
    val x: Float = 0f,
    val y: Float = 0f,
    val endX: Float = 0f,
    val endY: Float = 0f,
    val text: String = "",
    val durationMs: Long = 0L,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 脚本执行结果
 */
data class ScriptResult(
    val scriptId: String,
    val success: Boolean,
    val message: String
)
