/*
 * ============================================================
 * IntentCompiler - 意图编译器
 * ============================================================
 *
 * 将自然语言解析结果编译为 Android 系统可执行的指令：
 *
 * 编译目标类型：
 * 1. Intent 指令（系统设置、应用启动）
 * 2. 无障碍服务指令（点击、滑动、输入）
 * 3. 触屏模拟指令（坐标点击、长按）
 * 4. 系统广播指令（WiFi/蓝牙/亮度等）
 * 5. Shell 命令（需 ROOT 权限）
 *
 * 编译流程：
 *   语义意图 → 参数填充 → 权限检查 → 生成执行计划 → 校验 → 输出
 * ============================================================
 */
package com.kkgo.mindsoul.executor

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log

/**
 * 编译后的执行指令类型
 */
sealed class CompiledCommand {
    /** 指令ID */
    abstract val commandId: String

    /**
     * Intent 指令
     * 打开应用、系统设置页面等
     */
    data class IntentCommand(
        override val commandId: String = generateId(),
        /** Intent 动作 */
        val action: String,
        /** 目标包名 */
        val targetPackage: String? = null,
        /** 目标 Activity */
        val targetActivity: String? = null,
        /** 数据 URI */
        val dataUri: String? = null,
        /** 额外参数 */
        val extras: Map<String, String> = emptyMap()
    ) : CompiledCommand()

    /**
     * 无障碍服务指令
     * 通过 AccessibilityService 模拟点击、滑动、输入
     */
    data class AccessibilityCommand(
        override val commandId: String = generateId(),
        /** 操作类型 */
        val action: AccessibilityAction,
        /** 目标视图 ID 或文本描述 */
        val targetId: String? = null,
        /** 目标文本内容 */
        val targetText: String? = null,
        /** 坐标（绝对点击时使用） */
        val x: Float = 0f,
        val y: Float = 0f,
        /** 输入文本 */
        val inputText: String? = null
    ) : CompiledCommand()

    /**
     * 触屏模拟指令
     * 直接模拟触摸事件
     */
    data class TouchCommand(
        override val commandId: String = generateId(),
        /** 触摸类型 */
        val touchType: TouchType,
        /** X 坐标 */
        val x: Float,
        /** Y 坐标 */
        val y: Float,
        /** 持续时间（毫秒） */
        val durationMs: Long = 100L,
        /** 滑动终点 */
        val endX: Float = 0f,
        val endY: Float = 0f
    ) : CompiledCommand()

    /**
     * 系统设置指令
     * 直接操作系统参数（WiFi、蓝牙、亮度等）
     */
    data class SystemSettingCommand(
        override val commandId: String = generateId(),
        /** 设置类型 */
        val settingType: SystemSettingType,
        /** 设置值 */
        val value: String
    ) : CompiledCommand()

    /**
     * 系统广播指令
     */
    data class BroadcastCommand(
        override val commandId: String = generateId(),
        /** 广播动作 */
        val broadcastAction: String,
        /** 额外参数 */
        val extras: Map<String, String> = emptyMap()
    ) : CompiledCommand()

    /**
     * Shell 命令（需 ROOT）
     */
    data class ShellCommand(
        override val commandId: String = generateId(),
        /** Shell 命令字符串 */
        val command: String,
        /** 是否需要 ROOT */
        val requiresRoot: Boolean = true
    ) : CompiledCommand()

    companion object {
        private var idCounter = 0L
        fun generateId(): String = "cmd_${System.nanoTime()}_${idCounter++}"
    }
}

/**
 * 无障碍操作类型
 */
enum class AccessibilityAction {
    /** 点击 */
    CLICK,
    /** 长按 */
    LONG_CLICK,
    /** 滑动 */
    SWIPE,
    /** 输入文本 */
    INPUT_TEXT,
    /** 滚动 */
    SCROLL,
    /** 返回 */
    BACK,
    /** 回到桌面 */
    HOME,
    /** 打开最近任务 */
    RECENTS,
    /** 通知栏下拉 */
    NOTIFICATION_SHADE,
    /** 查找并点击包含指定文本的控件 */
    FIND_AND_CLICK,
    /** 查找并点击包含指定文本的按钮 */
    FIND_AND_CLICK_BUTTON
}

/**
 * 触摸类型
 */
enum class TouchType {
    TAP,
    LONG_PRESS,
    SWIPE_UP,
    SWIPE_DOWN,
    SWIPE_LEFT,
    SWIPE_RIGHT,
    PINCH_IN,
    PINCH_OUT
}

/**
 * 系统设置类型
 */
enum class SystemSettingType(val displayName: String) {
    WIFI_SWITCH("WiFi开关"),
    BLUETOOTH_SWITCH("蓝牙开关"),
    BRIGHTNESS("屏幕亮度"),
    VOLUME_MEDIA("媒体音量"),
    VOLUME_RING("铃声音量"),
    VOLUME_ALARM("闹钟音量"),
    VOLUME_NOTIFICATION("通知音量"),
    AUTO_ROTATE("自动旋转"),
    AIRPLANE_MODE("飞行模式"),
    DO_NOT_DISTURB("勿扰模式"),
    FLASHLIGHT("手电筒"),
    AUTO_BRIGHTNESS("自动亮度"),
    TIMEOUT("屏幕超时"),
    FONT_SIZE("字体大小"),
    DARK_MODE("深色模式"),
    LOCATION("定位服务"),
    NFC_SWITCH("NFC开关"),
    DATA_SWITCH("移动数据")
}

/**
 * 执行计划
 *
 * 包含一组按序执行的编译后命令
 */
data class ExecutionPlan(
    /** 计划ID */
    val planId: String = "plan_${System.nanoTime()}",
    /** 原始自然语言 */
    val originalText: String,
    /** 编译后的命令列表 */
    val commands: List<CompiledCommand>,
    /** 是否需要 ROOT */
    val requiresRoot: Boolean = commands.any { it is CompiledCommand.ShellCommand && it.requiresRoot },
    /** 预估执行时间（毫秒） */
    val estimatedDurationMs: Long = commands.size * 500L,
    /** 编译时间 */
    val compiledAt: Long = System.currentTimeMillis()
)

/**
 * 意图编译器
 *
 * 将解析后的语义意图编译为可执行的 Android 系统指令
 */
class IntentCompiler(private val context: Context) {

    companion object {
        private const val TAG = "IntentCompiler"
    }

    /**
     * 编译语义意图为执行计划
     *
     * @param intent 解析后的语义意图
     * @return 执行计划
     */
    fun compile(intent: SemanticIntent): ExecutionPlan {
        Log.d(TAG, "[编译] 意图: ${intent.action} | 目标: ${intent.target}")

        val commands = mutableListOf<CompiledCommand>()

        when (intent.action) {
            // ── 系统设置类 ──
            IntentAction.SYSTEM_SETTING -> {
                compileSystemSetting(intent, commands)
            }
            // ── 应用启动类 ──
            IntentAction.OPEN_APP -> {
                compileOpenApp(intent, commands)
            }
            // ── UI 操作类 ──
            IntentAction.UI_CLICK -> {
                compileUIClick(intent, commands)
            }
            IntentAction.UI_INPUT -> {
                compileUIInput(intent, commands)
            }
            IntentAction.UI_SCROLL -> {
                compileUIScroll(intent, commands)
            }
            // ── 导航类 ──
            IntentAction.NAVIGATE_BACK -> {
                commands.add(CompiledCommand.AccessibilityCommand(
                    action = AccessibilityAction.BACK
                ))
            }
            IntentAction.NAVIGATE_HOME -> {
                commands.add(CompiledCommand.AccessibilityCommand(
                    action = AccessibilityAction.HOME
                ))
            }
            IntentAction.NAVIGATE_RECENTS -> {
                commands.add(CompiledCommand.AccessibilityCommand(
                    action = AccessibilityAction.RECENTS
                ))
            }
            // ── 通信类 ──
            IntentAction.SEND_MESSAGE -> {
                compileSendMessage(intent, commands)
            }
            IntentAction.MAKE_CALL -> {
                compileMakeCall(intent, commands)
            }
            // ── 媒体控制 ──
            IntentAction.MEDIA_CONTROL -> {
                compileMediaControl(intent, commands)
            }
            // ── 闹钟/定时器 ──
            IntentAction.SET_ALARM -> {
                compileSetAlarm(intent, commands)
            }
            // ── 复合操作 ──
            IntentAction.COMPOUND -> {
                compileCompound(intent, commands)
            }
            else -> {
                Log.w(TAG, "[编译] 未支持的动作: ${intent.action}")
            }
        }

        return ExecutionPlan(
            originalText = intent.originalText,
            commands = commands
        )
    }

    /**
     * 批量编译（复合自动化脚本）
     */
    fun compileBatch(intents: List<SemanticIntent>): ExecutionPlan {
        val allCommands = mutableListOf<CompiledCommand>()
        for (intent in intents) {
            val plan = compile(intent)
            allCommands.addAll(plan.commands)
        }
        return ExecutionPlan(
            originalText = "[复合脚本] ${intents.size} 条指令",
            commands = allCommands
        )
    }

    // ── 编译子方法 ──

    /**
     * 编译系统设置指令
     */
    private fun compileSystemSetting(intent: SemanticIntent, commands: MutableList<CompiledCommand>) {
        val target = intent.target?.lowercase() ?: return
        val value = intent.parameters["value"] ?: "toggle"

        val settingType = when {
            "wifi" in target || "无线" in target -> SystemSettingType.WIFI_SWITCH
            "蓝牙" in target || "bluetooth" in target -> SystemSettingType.BLUETOOTH_SWITCH
            "亮度" in target || "brightness" in target -> {
                if ("自动" in target) SystemSettingType.AUTO_BRIGHTNESS
                else SystemSettingType.BRIGHTNESS
            }
            "音量" in target || "volume" in target -> {
                when {
                    "媒体" in target || "music" in target || "音乐" in target -> SystemSettingType.VOLUME_MEDIA
                    "铃声" in target || "ring" in target -> SystemSettingType.VOLUME_RING
                    "闹钟" in target || "alarm" in target -> SystemSettingType.VOLUME_ALARM
                    "通知" in target || "notification" in target -> SystemSettingType.VOLUME_NOTIFICATION
                    else -> SystemSettingType.VOLUME_MEDIA
                }
            }
            "旋转" in target || "rotate" in target -> SystemSettingType.AUTO_ROTATE
            "飞行" in target || "airplane" in target -> SystemSettingType.AIRPLANE_MODE
            "勿扰" in target || "dnd" in target || "免打扰" in target -> SystemSettingType.DO_NOT_DISTURB
            "手电" in target || "flashlight" in target -> SystemSettingType.FLASHLIGHT
            "超时" in target || "timeout" in target -> SystemSettingType.TIMEOUT
            "字体" in target || "font" in target -> SystemSettingType.FONT_SIZE
            "深色" in target || "夜间" in target || "dark" in target -> SystemSettingType.DARK_MODE
            "定位" in target || "location" in target || "gps" in target -> SystemSettingType.LOCATION
            "nfc" in target -> SystemSettingType.NFC_SWITCH
            "数据" in target || "移动数据" in target || "data" in target -> SystemSettingType.DATA_SWITCH
            else -> null
        }

        if (settingType != null) {
            commands.add(CompiledCommand.SystemSettingCommand(
                settingType = settingType,
                value = value
            ))
        } else {
            // 未知设置 → 打开系统设置页面
            commands.add(CompiledCommand.IntentCommand(
                action = android.provider.Settings.ACTION_SETTINGS
            ))
        }
    }

    /**
     * 编译打开应用指令
     */
    private fun compileOpenApp(intent: SemanticIntent, commands: MutableList<CompiledCommand>) {
        val appName = intent.target ?: return
        val packageName = mapAppNameToPackage(appName)

        if (packageName != null) {
            commands.add(CompiledCommand.IntentCommand(
                action = Intent.ACTION_MAIN,
                targetPackage = packageName
            ))
        } else {
            // 尝试通过搜索启动
            commands.add(CompiledCommand.IntentCommand(
                action = Intent.ACTION_MAIN,
                targetPackage = appName
            ))
        }
    }

    /**
     * 编译 UI 点击指令
     */
    private fun compileUIClick(intent: SemanticIntent, commands: MutableList<CompiledCommand>) {
        val target = intent.target
        val x = intent.parameters["x"]?.toFloatOrNull()
        val y = intent.parameters["y"]?.toFloatOrNull()

        if (x != null && y != null) {
            // 坐标点击
            commands.add(CompiledCommand.TouchCommand(
                touchType = TouchType.TAP,
                x = x, y = y
            ))
        } else if (target != null) {
            // 文本查找点击
            commands.add(CompiledCommand.AccessibilityCommand(
                action = AccessibilityAction.FIND_AND_CLICK,
                targetText = target
            ))
        }
    }

    /**
     * 编译 UI 输入指令
     */
    private fun compileUIInput(intent: SemanticIntent, commands: MutableList<CompiledCommand>) {
        val text = intent.parameters["text"] ?: intent.target ?: return
        commands.add(CompiledCommand.AccessibilityCommand(
            action = AccessibilityAction.INPUT_TEXT,
            inputText = text
        ))
    }

    /**
     * 编译 UI 滚动指令
     */
    private fun compileUIScroll(intent: SemanticIntent, commands: MutableList<CompiledCommand>) {
        val direction = intent.parameters["direction"] ?: "down"
        val action = when (direction) {
            "up", "上" -> AccessibilityAction.SCROLL
            "down", "下" -> AccessibilityAction.SCROLL
            else -> AccessibilityAction.SCROLL
        }
        commands.add(CompiledCommand.AccessibilityCommand(action = action))
    }

    /**
     * 编译发送消息指令
     */
    private fun compileSendMessage(intent: SemanticIntent, commands: MutableList<CompiledCommand>) {
        val phone = intent.parameters["phone"] ?: intent.target ?: return
        val message = intent.parameters["message"] ?: ""

        // 打开短信应用并填入内容
        val smsUri = "sms:$phone"
        commands.add(CompiledCommand.IntentCommand(
            action = Intent.ACTION_SENDTO,
            dataUri = smsUri,
            extras = mapOf("sms_body" to message)
        ))
    }

    /**
     * 编译拨打电话指令
     */
    private fun compileMakeCall(intent: SemanticIntent, commands: MutableList<CompiledCommand>) {
        val phone = intent.target ?: return
        commands.add(CompiledCommand.IntentCommand(
            action = Intent.ACTION_DIAL,
            dataUri = "tel:$phone"
        ))
    }

    /**
     * 编译媒体控制指令
     */
    private fun compileMediaControl(intent: SemanticIntent, commands: MutableList<CompiledCommand>) {
        val control = intent.target ?: return
        val action = when {
            "播放" in control || "play" in control -> "play"
            "暂停" in control || "pause" in control -> "pause"
            "上一" in control || "previous" in control -> "previous"
            "下一" in control || "next" in control -> "next"
            else -> "play"
        }
        commands.add(CompiledCommand.BroadcastCommand(
            broadcastAction = "com.kkgo.mindsoul.MEDIA_CONTROL",
            extras = mapOf("control" to action)
        ))
    }

    /**
     * 编译设置闹钟指令
     */
    private fun compileSetAlarm(intent: SemanticIntent, commands: MutableList<CompiledCommand>) {
        commands.add(CompiledCommand.IntentCommand(
            action = "android.intent.action.SET_ALARM",
            extras = intent.parameters
        ))
    }

    /**
     * 编译复合操作
     */
    private fun compileCompound(intent: SemanticIntent, commands: MutableList<CompiledCommand>) {
        val subIntents = intent.subIntents
        if (subIntents != null) {
            for (sub in subIntents) {
                val subPlan = compile(sub)
                commands.addAll(subPlan.commands)
            }
        }
    }

    /**
     * 应用名→包名映射
     */
    private fun mapAppNameToPackage(appName: String): String? {
        val knownApps = mapOf(
            "微信" to "com.tencent.mm",
            "qq" to "com.tencent.mobileqq",
            "支付宝" to "com.eg.android.AlipayGphone",
            "淘宝" to "com.taobao.taobao",
            "京东" to "com.jingdong.app.mall",
            "抖音" to "com.ss.android.ugc.aweme",
            "微博" to "com.sina.weibo",
            "美团" to "com.sankuai.meituan",
            "高德" to "com.autonavi.minimap",
            "百度" to "com.baidu.searchbox",
            "浏览器" to "com.android.browser",
            "相机" to "com.android.camera",
            "设置" to "com.android.settings",
            "时钟" to "com.android.deskclock",
            "计算器" to "com.android.calculator2",
            "日历" to "com.android.calendar",
            "图库" to "com.android.gallery3d",
            "音乐" to "com.android.music",
            "电话" to "com.android.dialer",
            "短信" to "com.android.messaging",
            "通讯录" to "com.android.contacts",
            "邮件" to "com.android.email",
            "文件" to "com.android.documentsui",
            "地图" to "com.google.android.apps.maps"
        )
        return knownApps[appName.lowercase()]
    }
}
