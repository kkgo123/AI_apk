/*
 * ============================================================
 * AccessibilityBridge - 无障碍服务桥接
 * ============================================================
 *
 * 封装 Android AccessibilityService，实现：
 *
 * 1. 控件树遍历与查找
 *    - 根据 ID/文本/类型查找目标控件
 *    - 构建屏幕控件快照
 * 2. 操作模拟
 *    - 点击、长按、滑动、输入
 *    - 全局手势（返回、桌面、最近任务）
 * 3. 屏幕内容监听
 *    - 窗口变化事件
 *    - 通知栏内容读取
 * 4. 指令执行队列
 *    - 按序执行 CompiledCommand
 *    - 失败重试与超时处理
 *
 * 注意：用户需在系统设置中手动开启无障碍权限
 * ============================================================
 */
package com.kkgo.mindsoul.executor

import android.provider.Settings

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 无障碍桥接状态
 */
enum class AccessibilityBridgeState {
    /** 未连接（服务未启动） */
    DISCONNECTED,
    /** 已连接就绪 */
    CONNECTED,
    /** 正在执行指令 */
    EXECUTING,
    /** 出错 */
    ERROR
}

/**
 * 控件快照节点
 */
data class ViewSnapshot(
    /** 控件ID */
    val viewId: String?,
    /** 控件文本 */
    val text: String?,
    /** 控件类名 */
    val className: String?,
    /** 控件描述 */
    val contentDescription: String?,
    /** 控件范围 */
    val bounds: Rect?,
    /** 是否可点击 */
    val isClickable: Boolean,
    /** 是否可聚焦 */
    val isFocusable: Boolean,
    /** 是否已启用 */
    val isEnabled: Boolean,
    /** 子节点 */
    val children: List<ViewSnapshot> = emptyList()
)

/**
 * 指令执行结果
 */
data class CommandResult(
    /** 命令ID */
    val commandId: String,
    /** 是否成功 */
    val success: Boolean,
    /** 执行耗时（毫秒） */
    val durationMs: Long,
    /** 错误信息 */
    val errorMessage: String? = null
)

/**
 * 执行计划结果
 */
data class ExecutionResult(
    /** 计划ID */
    val planId: String,
    /** 各命令结果 */
    val commandResults: List<CommandResult>,
    /** 总体是否成功 */
    val overallSuccess: Boolean = commandResults.all { it.success },
    /** 总耗时（毫秒） */
    val totalDurationMs: Long = commandResults.sumOf { it.durationMs }
)

/**
 * 无障碍服务桥接器
 *
 * 管理 AccessibilityService 的连接与指令执行
 */
class AccessibilityBridge(private val context: Context) {

    companion object {
        private const val TAG = "AccessibilityBridge"
        /** 点击操作延迟（毫秒），等待 UI 渲染 */
        const val CLICK_DELAY_MS = 100L
        /** 滑动操作持续时间 */
        const val SWIPE_DURATION_MS = 300L
        /** 查找控件超时（毫秒） */
        const val FIND_TIMEOUT_MS = 3000L
        /** 重试次数 */
        const val MAX_RETRY = 2
    }

    // ============ 服务引用 ============
    /** 当前连接的 AccessibilityService 实例 */
    @Volatile
    private var service: AccessibilityService? = null

    // ============ 状态 ============
    private val _state = MutableStateFlow(AccessibilityBridgeState.DISCONNECTED)
    val stateFlow: StateFlow<AccessibilityBridgeState> = _state.asStateFlow()
    val currentState: AccessibilityBridgeState get() = _state.value

    // ============ 执行队列 ============
    private val commandQueue = ConcurrentLinkedQueue<CompiledCommand>()
    @Volatile
    private var isExecuting = false
    private val execScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ============ 屏幕快照 ============
    @Volatile
    private var lastScreenSnapshot: ViewSnapshot? = null
    @Volatile
    private var lastRootPackageName: String = ""

    // ============ 事件监听 ============
    private val eventListeners = mutableListOf<AccessibilityEventListener>()

    interface AccessibilityEventListener {
        fun onWindowStateChanged(packageName: String, className: String)
        fun onNotificationPosted(packageName: String, text: String?) {}
        fun onScreenContentChanged() {}
    }

    // ============ 连接管理 ============

    /**
     * 绑定无障碍服务（由 MindSoulAccessibilityService 调用）
     */
    fun onServiceConnected(accessibilityService: AccessibilityService) {
        service = accessibilityService
        _state.value = AccessibilityBridgeState.CONNECTED
        Log.i(TAG, "[连接] 无障碍服务已连接")
    }

    /**
     * 服务断开
     */
    fun onServiceDisconnected() {
        service = null
        _state.value = AccessibilityBridgeState.DISCONNECTED
        Log.i(TAG, "[断开] 无障碍服务已断开")
    }

    /**
     * 处理无障碍事件
     */
    fun onAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = event.packageName?.toString() ?: ""
                val cls = event.className?.toString() ?: ""
                lastRootPackageName = pkg

                // 通知监听器
                eventListeners.forEach { listener ->
                    try { listener.onWindowStateChanged(pkg, cls) }
                    catch (e: Exception) { Log.e(TAG, "事件监听器异常", e) }
                }
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // 刷新屏幕快照
                refreshScreenSnapshot()
                eventListeners.forEach { listener ->
                    try { listener.onScreenContentChanged() }
                    catch (e: Exception) { Log.e(TAG, "内容变化监听器异常", e) }
                }
            }
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                val pkg = event.packageName?.toString() ?: ""
                val text = event.text?.joinToString(" ")
                eventListeners.forEach { listener ->
                    try { listener.onNotificationPosted(pkg, text) }
                    catch (e: Exception) { Log.e(TAG, "通知监听器异常", e) }
                }
            }
        }
    }

    // ============ 指令执行 ============

    /**
     * 执行编译后的执行计划
     *
     * @param plan 执行计划
     * @return 执行结果
     */
    suspend fun executePlan(plan: ExecutionPlan): ExecutionResult = withContext(Dispatchers.IO) {
        if (service == null) {
            Log.e(TAG, "[执行] 无障碍服务未连接")
            return@withContext ExecutionResult(
                planId = plan.planId,
                commandResults = plan.commands.map {
                    CommandResult(it.commandId, false, 0, "无障碍服务未连接")
                }
            )
        }

        _state.value = AccessibilityBridgeState.EXECUTING
        val results = mutableListOf<CommandResult>()

        for (command in plan.commands) {
            val result = executeSingleCommand(command)
            results.add(result)

            if (!result.success) {
                Log.w(TAG, "[执行] 命令失败: ${command.commandId} - ${result.errorMessage}")
                // 失败重试
                if (MAX_RETRY > 0) {
                    for (retry in 1..MAX_RETRY) {
                        delay(CLICK_DELAY_MS * retry)
                        val retryResult = executeSingleCommand(command)
                        results[results.lastIndex] = retryResult
                        if (retryResult.success) break
                    }
                }
            }

            // 命令间等待
            delay(CLICK_DELAY_MS)
        }

        _state.value = AccessibilityBridgeState.CONNECTED
        ExecutionResult(plan.planId, results)
    }

    /**
     * 执行单条命令
     */
    private suspend fun executeSingleCommand(command: CompiledCommand): CommandResult {
        val startTime = System.currentTimeMillis()
        val svc = service

        if (svc == null) {
            return CommandResult(command.commandId, false, 0, "无障碍服务未连接")
        }

        return try {
            when (command) {
                is CompiledCommand.AccessibilityCommand -> {
                    executeAccessibilityCommand(svc, command)
                    CommandResult(command.commandId, true, System.currentTimeMillis() - startTime)
                }
                is CompiledCommand.TouchCommand -> {
                    executeTouchCommand(svc, command)
                    CommandResult(command.commandId, true, System.currentTimeMillis() - startTime)
                }
                is CompiledCommand.IntentCommand -> {
                    executeIntentCommand(command)
                    CommandResult(command.commandId, true, System.currentTimeMillis() - startTime)
                }
                is CompiledCommand.SystemSettingCommand -> {
                    executeSystemSettingCommand(command)
                    CommandResult(command.commandId, true, System.currentTimeMillis() - startTime)
                }
                is CompiledCommand.BroadcastCommand -> {
                    executeBroadcastCommand(command)
                    CommandResult(command.commandId, true, System.currentTimeMillis() - startTime)
                }
                is CompiledCommand.ShellCommand -> {
                    executeShellCommand(command)
                    CommandResult(command.commandId, true, System.currentTimeMillis() - startTime)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[执行] 命令异常: ${e.message}")
            CommandResult(command.commandId, false, System.currentTimeMillis() - startTime, e.message)
        }
    }

    /**
     * 执行无障碍操作命令
     */
    private suspend fun executeAccessibilityCommand(
        svc: AccessibilityService,
        command: CompiledCommand.AccessibilityCommand
    ) {
        when (command.action) {
            AccessibilityAction.BACK -> svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            AccessibilityAction.HOME -> svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            AccessibilityAction.RECENTS -> svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
            AccessibilityAction.NOTIFICATION_SHADE -> svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)

            AccessibilityAction.CLICK -> {
                if (command.targetId != null) {
                    val node = findNodeById(svc.rootInActiveWindow, command.targetId!!)
                    node?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
            }
            AccessibilityAction.LONG_CLICK -> {
                if (command.x > 0 && command.y > 0) {
                    performGesture(svc, command.x, command.y, isLongPress = true)
                }
            }
            AccessibilityAction.FIND_AND_CLICK -> {
                val text = command.targetText
                if (text != null) {
                    val node = findNodeByText(svc.rootInActiveWindow, text)
                    if (node != null) {
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    } else {
                        Log.w(TAG, "[查找] 未找到文本: $text")
                    }
                }
            }
            AccessibilityAction.FIND_AND_CLICK_BUTTON -> {
                val text = command.targetText
                if (text != null) {
                    val node = findNodeByButton(svc.rootInActiveWindow, text)
                    node?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
            }
            AccessibilityAction.INPUT_TEXT -> {
                val text = command.inputText
                if (text != null) {
                    // 找到当前焦点控件并输入
                    val focused = svc.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    if (focused != null) {
                        val args = Bundle()
                        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                        focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                    }
                }
            }
            AccessibilityAction.SWIPE -> {
                // 屏幕中央向下滑动
                performGestureSwipe(svc, 540f, 1600f, 540f, 800f)
            }
            AccessibilityAction.SCROLL -> {
                // 尝试滚动当前窗口
                val rootNode = svc.rootInActiveWindow
                rootNode?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            }
        }
        delay(CLICK_DELAY_MS)
    }

    /**
     * 执行触屏模拟命令
     */
    private suspend fun executeTouchCommand(
        svc: AccessibilityService,
        command: CompiledCommand.TouchCommand
    ) {
        when (command.touchType) {
            TouchType.TAP -> {
                performGesture(svc, command.x, command.y, isLongPress = false)
            }
            TouchType.LONG_PRESS -> {
                performGesture(svc, command.x, command.y, isLongPress = true)
            }
            TouchType.SWIPE_UP -> {
                performGestureSwipe(svc, command.x, command.y, command.x, command.y - 500f)
            }
            TouchType.SWIPE_DOWN -> {
                performGestureSwipe(svc, command.x, command.y, command.x, command.y + 500f)
            }
            TouchType.SWIPE_LEFT -> {
                performGestureSwipe(svc, command.x, command.y, command.x - 500f, command.y)
            }
            TouchType.SWIPE_RIGHT -> {
                performGestureSwipe(svc, command.x, command.y, command.x + 500f, command.y)
            }
            TouchType.PINCH_IN, TouchType.PINCH_OUT -> {
                // 捏合手势（双指操作）简化处理
                Log.d(TAG, "[手势] 捏合操作待扩展")
            }
        }
        delay(CLICK_DELAY_MS)
    }

    /**
     * 执行 Intent 命令
     */
    private suspend fun executeIntentCommand(command: CompiledCommand.IntentCommand) {
        val intent = android.content.Intent(command.action).apply {
            if (command.targetPackage != null) setPackage(command.targetPackage)
            if (command.targetActivity != null) setClassName(
                command.targetPackage ?: "",
                command.targetActivity
            )
            if (command.dataUri != null) data = android.net.Uri.parse(command.dataUri)
            for ((key, value) in command.extras) {
                putExtra(key, value)
            }
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        delay(500) // 等待应用启动
    }

    /**
     * 执行系统设置命令
     */
    private suspend fun executeSystemSettingCommand(command: CompiledCommand.SystemSettingCommand) {
        val resolver = context.contentResolver
        val value = command.value

        when (command.settingType) {
            SystemSettingType.BRIGHTNESS -> {
                val brightness = value.toIntOrNull()?.coerceIn(0, 255) ?: 128
                Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, brightness)
            }
            SystemSettingType.WIFI_SWITCH -> {
                val enabled = parseToggleValue(value)
                Settings.Global.putInt(resolver, Settings.Global.WIFI_ON, if (enabled) 1 else 0)
            }
            SystemSettingType.BLUETOOTH_SWITCH -> {
                // 蓝牙开关通过 BluetoothAdapter 操作（需要权限）
                Log.i(TAG, "[设置] 蓝牙: $value")
            }
            SystemSettingType.AUTO_BRIGHTNESS -> {
                val enabled = parseToggleValue(value)
                Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                    if (enabled) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                    else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            }
            SystemSettingType.AUTO_ROTATE -> {
                val enabled = parseToggleValue(value)
                Settings.System.putInt(resolver, Settings.System.ACCELEROMETER_ROTATION,
                    if (enabled) 1 else 0)
            }
            SystemSettingType.AIRPLANE_MODE -> {
                val enabled = parseToggleValue(value)
                Settings.Global.putInt(resolver, Settings.Global.AIRPLANE_MODE_ON,
                    if (enabled) 1 else 0)
            }
            else -> {
                Log.i(TAG, "[设置] ${command.settingType.displayName}: $value")
            }
        }
    }

    /**
     * 执行广播命令
     */
    private suspend fun executeBroadcastCommand(command: CompiledCommand.BroadcastCommand) {
        val intent = android.content.Intent(command.broadcastAction).apply {
            for ((key, value) in command.extras) {
                putExtra(key, value)
            }
        }
        context.sendBroadcast(intent)
    }

    /**
     * 执行 Shell 命令
     */
    private suspend fun executeShellCommand(command: CompiledCommand.ShellCommand) {
        if (command.requiresRoot) {
            Log.w(TAG, "[Shell] ROOT 命令: ${command.command}")
            // 通过 su 执行
            try {
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command.command))
                process.waitFor()
            } catch (e: Exception) {
                Log.e(TAG, "[Shell] ROOT 执行失败: ${e.message}")
            }
        } else {
            try {
                val process = Runtime.getRuntime().exec(command.command)
                process.waitFor()
            } catch (e: Exception) {
                Log.e(TAG, "[Shell] 执行失败: ${e.message}")
            }
        }
    }

    // ============ 控件查找 ============

    /**
     * 按 ID 查找节点
     */
    private fun findNodeById(root: AccessibilityNodeInfo?, viewId: String): AccessibilityNodeInfo? {
        if (root == null) return null
        val results = root.findAccessibilityNodeInfosByViewId(viewId)
        return results?.firstOrNull()
    }

    /**
     * 按文本查找节点（递归搜索）
     */
    private fun findNodeByText(root: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        if (root == null) return null
        return findNodeRecursive(root) { node ->
            val nodeText = node.text?.toString() ?: ""
            val desc = node.contentDescription?.toString() ?: ""
            nodeText.contains(text) || desc.contains(text)
        }
    }

    /**
     * 按按钮文本查找节点
     */
    private fun findNodeByButton(root: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        if (root == null) return null
        return findNodeRecursive(root) { node ->
            val nodeText = node.text?.toString() ?: ""
            val className = node.className?.toString() ?: ""
            nodeText.contains(text) && (className.contains("Button") || node.isClickable)
        }
    }

    /**
     * 递归查找节点
     */
    private fun findNodeRecursive(
        node: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        if (predicate(node)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeRecursive(child, predicate)
            if (found != null) return found
        }
        return null
    }

    /**
     * 刷新屏幕快照
     */
    fun refreshScreenSnapshot() {
        val root = service?.rootInActiveWindow
        if (root != null) {
            lastScreenSnapshot = buildViewSnapshot(root)
        }
    }

    /**
     * 构建控件树快照
     */
    private fun buildViewSnapshot(node: AccessibilityNodeInfo, depth: Int = 0): ViewSnapshot {
        if (depth > 10) return ViewSnapshot(null, null, null, null, null, false, false, false)

        val children = mutableListOf<ViewSnapshot>()
        for (i in 0 until minOf(node.childCount, 50)) {
            val child = node.getChild(i) ?: continue
            children.add(buildViewSnapshot(child, depth + 1))
        }

        return ViewSnapshot(
            viewId = node.viewIdResourceName,
            text = node.text?.toString(),
            className = node.className?.toString(),
            contentDescription = node.contentDescription?.toString(),
            bounds = Rect().also { node.getBoundsInScreen(it) },
            isClickable = node.isClickable,
            isFocusable = node.isFocusable,
            isEnabled = node.isEnabled,
            children = children
        )
    }

    // ============ 手势执行 ============

    /**
     * 执行点击/长按手势
     */
    private suspend fun performGesture(
        svc: AccessibilityService,
        x: Float, y: Float,
        isLongPress: Boolean = false
    ) {
        val path = Path().apply { moveTo(x, y) }
        val duration = if (isLongPress) 600L else 100L

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()

        svc.dispatchGesture(gesture, null, null)
        delay(duration + 50)
    }

    /**
     * 执行滑动手势
     */
    private suspend fun performGestureSwipe(
        svc: AccessibilityService,
        startX: Float, startY: Float,
        endX: Float, endY: Float
    ) {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, SWIPE_DURATION_MS))
            .build()

        svc.dispatchGesture(gesture, null, null)
        delay(SWIPE_DURATION_MS + 50)
    }

    // ============ 工具方法 ============

    /**
     * 解析开关值
     */
    private fun parseToggleValue(value: String): Boolean {
        return when (value.lowercase()) {
            "on", "开", "打开", "true", "1", "enable", "启用" -> true
            "off", "关", "关闭", "false", "0", "disable", "禁用" -> false
            "toggle", "切换" -> !getWifiState() // 默认取反
            else -> false
        }
    }

    /**
     * 获取WiFi状态
     */
    private fun getWifiState(): Boolean {
        return try {
            Settings.Global.getInt(context.contentResolver, Settings.Global.WIFI_ON) == 1
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 获取当前前台应用包名
     */
    fun getCurrentForegroundPackage(): String = lastRootPackageName

    /**
     * 获取最新屏幕快照
     */
    fun getScreenSnapshot(): ViewSnapshot? = lastScreenSnapshot

    /**
     * 添加事件监听器
     */
    fun addEventListener(listener: AccessibilityEventListener) {
        eventListeners.add(listener)
    }

    /**
     * 移除事件监听器
     */
    fun removeEventListener(listener: AccessibilityEventListener) {
        eventListeners.remove(listener)
    }

    /**
     * 释放资源
     */
    fun destroy() {
        execScope.cancel()
        eventListeners.clear()
        service = null
        Log.i(TAG, "[销毁] 无障碍桥接已释放")
    }
}
