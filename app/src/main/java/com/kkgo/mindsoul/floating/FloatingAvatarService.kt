/*
 * ============================================================
 * FloatingAvatarService - 悬浮窗前台服务（全面升级版 v2.0）
 * ============================================================
 *
 * Android Foreground Service，实现桌面常驻虚拟形象：
 *
 * 核心功能：
 * 1. 系统级桌面悬浮（TYPE_APPLICATION_OVERLAY）
 *    - 真正的系统级悬浮窗，不是APP内部
 *    - 悬浮在手机系统桌面上
 *    - 可在任何应用上层显示
 * 2. 前台服务常驻后台
 *    - START_STICKY保持服务存活
 *    - 前台通知显示"MindSoul精灵运行中"
 *    - 通知栏快捷操作（对话/暂停/设置）
 * 3. 跨App状态同步
 *    - 注册BroadcastReceiver监听 CHAT_STATE_CHANGED 广播
 *    - 收到广播后驱动FloatingAvatarView.syncChatState()
 * 4. 意识系统联动
 *    - 订阅ConsciousnessManager状态变化
 *    - 实时驱动表情/颜色/动作
 * 5. 精灵配置完整持久化
 *    - SharedPreferences保存全部AvatarConfig字段
 *    - 重启手机后精灵恢复上次的完整外观
 * 6. 桌面精灵完整自定义
 *    - 性别、年龄、性格、表情、手/脚/服装/整体动作
 *    - 动画风格（3D/2D）、自定义光环颜色
 *    - 用户自定义图片/视频作为外观
 * 7. 互动系统
 *    - 单击 → 弹出对话气泡
 *    - 长按 → 快捷菜单
 *    - 双击 → 语音对话
 *    - 拖动 → 趣味提示
 * 8. 自动避让
 *    - 检测全屏应用时自动缩小/隐藏
 * 9. 后台保活
 *    - 开机自启动 + 前台服务保活
 *
 * 权限要求：
 *   - SYSTEM_ALERT_WINDOW（显示在其他应用上层）
 *   - FOREGROUND_SERVICE（前台服务）
 *   - RECEIVE_BOOT_COMPLETED（开机自启）
 *   - POST_NOTIFICATIONS（通知权限，Android 13+）
 *   - READ_EXTERNAL_STORAGE / READ_MEDIA_IMAGES（读取图片）
 * ============================================================
 */
package com.kkgo.mindsoul.floating

import android.widget.FrameLayout
import android.view.View

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.kkgo.mindsoul.avatar.AvatarExpressionEngine
import com.kkgo.mindsoul.model.EmotionalState
import com.kkgo.mindsoul.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 悬浮窗前台服务（全面升级版 v2.0）
 *
 * 生命周期：
 *   onCreate → 初始化WindowManager、视图、加载配置、注册聊天状态广播
 *   onStartCommand → 显示悬浮窗 + 启动前台通知
 *   onDestroy → 移除悬浮窗 + 保存配置 + 释放资源 + 注销广播
 */
class FloatingAvatarService : Service(), FloatingAvatarClickListener {

    companion object {
        private const val TAG = "FloatingAvatarSvc"

        // ── 通知相关 ──
        const val NOTIFICATION_CHANNEL_ID = "mindsoul_floating_avatar"
        const val NOTIFICATION_ID = 10086
        const val NOTIFICATION_CHANNEL_NAME = "悬浮窗形象"

        // ── Intent Actions ──
        const val ACTION_START = "com.kkgo.mindsoul.floating.START"
        const val ACTION_STOP = "com.kkgo.mindsoul.floating.STOP"
        const val ACTION_TOGGLE_VISIBILITY = "com.kkgo.mindsoul.floating.TOGGLE"
        const val ACTION_OPEN_SETTINGS = "com.kkgo.mindsoul.floating.SETTINGS"
        const val ACTION_QUICK_CHAT = "com.kkgo.mindsoul.floating.QUICK_CHAT"
        const val ACTION_VOICE_MODE = "com.kkgo.mindsoul.floating.VOICE"
        const val ACTION_UPDATE_CONFIG = "com.kkgo.mindsoul.floating.UPDATE_CONFIG"

        // ── 聊天状态广播 ──
        /** 聊天状态变更广播Action */
        const val ACTION_CHAT_STATE_CHANGED = "com.kkgo.mindsoul.CHAT_STATE_CHANGED"
        /** 广播Extra: 聊天状态值 */
        const val EXTRA_CHAT_STATE = "chat_state"

        // ── SharedPreferences键 ──
        const val PREF_NAME = "mindsoul_floating"
        const val KEY_ENABLED = "floating_enabled"
        const val KEY_POSITION_X = "position_x"
        const val KEY_POSITION_Y = "position_y"
        const val KEY_SIZE = "size_level"
        const val KEY_ALPHA = "alpha_level"
        const val KEY_BUBBLE_ENABLED = "bubble_enabled"
        const val KEY_AUTO_HIDE_FULLSCREEN = "auto_hide_fullscreen"
        const val KEY_BOOT_START = "boot_start"
        const val KEY_LAST_THOUGHT_TIME = "last_thought_time"

        // ── 精灵配置键 ──
        const val KEY_AVATAR_GENDER = "avatar_gender"
        const val KEY_AVATAR_AGE = "avatar_age"
        const val KEY_AVATAR_PERSONALITY = "avatar_personality"
        const val KEY_AVATAR_EXPRESSION = "avatar_expression"
        const val KEY_AVATAR_HAND = "avatar_hand"
        const val KEY_AVATAR_FOOT = "avatar_foot"
        const val KEY_AVATAR_COSTUME = "avatar_costume"
        const val KEY_AVATAR_OVERALL = "avatar_overall"
        const val KEY_AVATAR_SHAPE = "avatar_shape"
        const val KEY_AVATAR_CUSTOM_IMAGE = "avatar_custom_image"
        const val KEY_AVATAR_CUSTOM_VIDEO = "avatar_custom_video"
        const val KEY_CUSTOM_WIDTH = "custom_width_px"
        const val KEY_CUSTOM_HEIGHT = "custom_height_px"
        const val KEY_AVATAR_SIZE_MULTIPLIER = "avatar_size_multiplier"
        const val KEY_AVATAR_ANIMATION_STYLE = "avatar_animation_style"
        const val KEY_AVATAR_GLOW_COLOR = "avatar_glow_color"
        const val KEY_AVATAR_3D_MODEL = "avatar_3d_model_path"
        const val KEY_AVATAR_EQUIPMENT_LIST = "avatar_equipment_list"
        const val KEY_AVATAR_TEXT_TO_SPIRIT = "avatar_text_to_spirit"

        // ── 定时任务间隔 ──
        private const val THOUGHT_CHECK_MIN_MS = 120000L
        private const val THOUGHT_CHECK_MAX_MS = 300000L
        private const val STATE_SYNC_INTERVAL_MS = 500L
        private const val FULLSCREEN_CHECK_INTERVAL_MS = 2000L

        fun start(context: Context) {
            val intent = Intent(context, FloatingAvatarService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, FloatingAvatarService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    // ============ 系统服务 ============
    private lateinit var windowManager: WindowManager
    private lateinit var prefs: SharedPreferences

    // ============ TTS 语音合成 ============
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    // ============ 悬浮窗视图 ============
    private var floatingView: FloatingAvatarView? = null
    private var menuView: FloatingQuickMenu? = null
    private var viewParams: WindowManager.LayoutParams? = null
    private var menuParams: WindowManager.LayoutParams? = null

    // ============ 状态 ============
    private val _isVisible = MutableStateFlow(true)
    val isVisibleFlow: StateFlow<Boolean> = _isVisible.asStateFlow()

    @Volatile
    private var isFullScreenBlocking = false
    private var isPaused = false

    // ============ 精灵配置 ============
    private var avatarConfig: AvatarConfig = AvatarConfig()

    // ============ 协程 ============
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var thoughtJob: Job? = null
    private var stateSyncJob: Job? = null
    private var fullscreenCheckJob: Job? = null

    // ============ 广播接收器 ============

    /**
     * 屏幕开关广播接收器
     * 锁屏时隐藏精灵，亮屏时恢复
     */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> hideOverlay()
                Intent.ACTION_SCREEN_ON -> if (!isPaused) showOverlay()
            }
        }
    }

    /**
     * 聊天状态广播接收器
     * 监听 com.kkgo.mindsoul.CHAT_STATE_CHANGED 广播
     * 收到后调用 FloatingAvatarView.syncChatState() 驱动精灵状态切换
     *
     * 广播格式：
     *   Action: com.kkgo.mindsoul.CHAT_STATE_CHANGED
     *   Extra "chat_state": String，值为 "idle"/"thinking"/"speaking"/"listening"/"happy"
     *
     * 发送示例（从ConsciousnessManager或其他模块）：
     *   val intent = Intent("com.kkgo.mindsoul.CHAT_STATE_CHANGED").apply {
     *       putExtra("chat_state", "thinking")
     *       setPackage("com.kkgo.mindsoul")
     *   }
     *   sendBroadcast(intent)
     */
    private val chatStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_CHAT_STATE_CHANGED) {
                val chatState = intent.getStringExtra(EXTRA_CHAT_STATE) ?: "idle"
                Log.d(TAG, "[广播] 收到聊天状态变更: $chatState")
                // 在主线程中更新精灵视图
                Handler(Looper.getMainLooper()).post {
                    floatingView?.syncChatState(chatState)
                }
            }
        }
    }

    // ============================================================
    // 生命周期
    // ============================================================

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "═══════════════════════════════════════")
        Log.i(TAG, "  悬浮窗服务正在启动（全面升级版v2.0）...")
        Log.i(TAG, "  系统级桌面悬浮 | 聊天状态联动 | 全身渲染")
        Log.i(TAG, "═══════════════════════════════════════")

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

        // 加载精灵配置（从SharedPreferences恢复完整外观）
        loadAvatarConfig()

        // 注册屏幕开关广播
        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(screenReceiver, screenFilter)

        // 注册聊天状态广播接收器
        val chatStateFilter = IntentFilter(ACTION_CHAT_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(chatStateReceiver, chatStateFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(chatStateReceiver, chatStateFilter)
        }
        Log.i(TAG, "[广播] 已注册聊天状态广播: $ACTION_CHAT_STATE_CHANGED")

        // 构建悬浮窗视图
        buildFloatingView()

        // 初始化 TTS 语音合成引擎
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(java.util.Locale.CHINESE)
                if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                    ttsReady = true
                    // 设置语速和音调
                    tts?.setSpeechRate(1.0f)
                    tts?.setPitch(1.0f)
                    Log.i(TAG, "[TTS] 语音合成引擎初始化成功")
                } else {
                    Log.w(TAG, "[TTS] 中文语言不支持")
                    ttsReady = false
                }
            } else {
                Log.w(TAG, "[TTS] 语音合成引擎初始化失败, status=$status")
                ttsReady = false
            }
        }
    }

    /**
     * 精灵说话 - 通过 TTS 朗读文本
     */
    fun speak(text: String) {
        if (ttsReady && text.isNotBlank()) {
            Log.d(TAG, "[TTS] 精灵说话: $text")
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "mindsoul_tts_${System.currentTimeMillis()}")
        } else {
            Log.w(TAG, "[TTS] 引擎未就绪或文本为空，无法朗读")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (!hasOverlayPermission()) {
                    Log.w(TAG, "[启动] 缺少悬浮窗权限，引导用户开启")
                    requestOverlayPermission()
                    stopSelf()
                    return START_NOT_STICKY
                }
                startForegroundNotification()
                showOverlay()
                startPeriodicTasks()
            }
            ACTION_STOP -> stopAll()
            ACTION_TOGGLE_VISIBILITY -> toggleVisibility()
            ACTION_OPEN_SETTINGS -> openSettings()
            ACTION_QUICK_CHAT -> openMainApp()
            ACTION_VOICE_MODE -> openVoiceMode()
            ACTION_UPDATE_CONFIG -> {
                loadAvatarConfig()
                floatingView?.applyConfig(avatarConfig)
            }
        }
        // START_STICKY 确保服务被系统杀死后自动重启
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "[销毁] 悬浮窗服务正在停止")
        // 保存精灵配置到SharedPreferences
        saveAvatarConfig()
        stopAll()
        // 释放 TTS 资源
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) { }
        try { unregisterReceiver(chatStateReceiver) } catch (_: Exception) { }
        floatingView?.release()
        floatingView = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ============================================================
    // 悬浮窗构建
    // ============================================================

    /**
     * 构建悬浮窗视图
     *
     * 使用 TYPE_APPLICATION_OVERLAY 实现真正的系统级桌面悬浮
     * 精灵悬浮在手机系统桌面上，不是APP内部
     */
    private fun buildFloatingView() {
        val size = FloatingSize.fromPreference(prefs.getInt(KEY_SIZE, 1))
        val pxSize = dpToPx(size.dp)

        // 读取自定义宽高，优先使用用户设置的像素值
        val customWidth = prefs.getInt(KEY_CUSTOM_WIDTH, 0)
        val customHeight = prefs.getInt(KEY_CUSTOM_HEIGHT, 0)
        val viewWidth = if (customWidth > 0) customWidth else pxSize
        val viewHeight = if (customHeight > 0) customHeight else pxSize

        floatingView = FloatingAvatarView(this).apply {
            setClickListener(this@FloatingAvatarService)
            applyConfig(avatarConfig)
            setFloatingSize(size)
            setFloatingAlpha(prefs.getFloat(KEY_ALPHA, 0.9f))
        }

        // WindowManager布局参数 - 系统级悬浮
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        viewParams = WindowManager.LayoutParams(
            viewWidth, viewHeight,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt(KEY_POSITION_X, dpToPx(100))
            y = prefs.getInt(KEY_POSITION_Y, dpToPx(200))
        }
    }

    private fun showOverlay() {
        val view = floatingView ?: return
        val params = viewParams ?: return
        try {
            if (view.windowToken == null) {
                windowManager.addView(view, params)
            } else if (view.parent == null) {
                windowManager.addView(view, params)
            }
            _isVisible.value = true
            Log.d(TAG, "[显示] 系统级桌面悬浮窗已显示")
        } catch (e: Exception) {
            Log.e(TAG, "[错误] 显示悬浮窗失败: ${e.message}")
        }
    }

    private fun hideOverlay() {
        val view = floatingView ?: return
        try {
            if (view.parent != null) {
                val params = view.layoutParams as? WindowManager.LayoutParams
                if (params != null) {
                    prefs.edit()
                        .putInt(KEY_POSITION_X, params.x)
                        .putInt(KEY_POSITION_Y, params.y)
                        .apply()
                }
                windowManager.removeView(view)
            }
            _isVisible.value = false
        } catch (_: Exception) { }
    }

    private fun showMenu() {
        if (menuView != null) { hideMenu(); return }
        val wmParams = floatingView?.layoutParams as? WindowManager.LayoutParams ?: return
        menuView = FloatingQuickMenu(this).apply {
            setMenuListener(object : FloatingQuickMenu.MenuActionListener {
                override fun onChat() { openMainApp(); hideMenu() }
                override fun onStatus() { showStatusInfo(); hideMenu() }
                override fun onSettings() { openSettings(); hideMenu() }
                override fun onHide() { isPaused = true; hideOverlay(); hideMenu(); updateNotification() }
            })
        }
        val menuSize = dpToPx(140)
        menuParams = WindowManager.LayoutParams(
            menuSize, WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = wmParams.x + dpToPx(70)
            y = wmParams.y
        }
        try { windowManager.addView(menuView!!, menuParams) } catch (_: Exception) { }
    }

    private fun hideMenu() {
        menuView?.let {
            try { if ((it as android.view.View).parent != null) windowManager.removeView(it) } catch (_: Exception) { }
        }
        menuView = null
    }

    // ============================================================
    // 交互事件处理
    // ============================================================

    override fun onSingleClick() {
        Log.d(TAG, "[交互] 单击精灵 → 弹出想法气泡")
        val thoughts = listOf(
            "今天也要加油哦！", "我在思考人生的意义~", "有什么需要帮忙的吗？",
            "好无聊呀，陪我聊聊天吧", "今天天气怎么样呢？", "我学会了一个新技能！",
            "主人辛苦啦~", "知识就是力量！", "我想去旅行~", "嘿嘿，想到一个好点子！"
        )
        val thought = thoughts.random()
        floatingView?.showThoughtBubble(thought)
        // 同时用语音说出来
        speak(thought)
    }

    override fun onDoubleClick() {
        Log.d(TAG, "[交互] 双击精灵 → 语音对话模式")
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            putExtra("mode", "voice")
        }
        startActivity(intent)
    }

    override fun onLongPress() {
        Log.d(TAG, "[交互] 长按精灵 → 快捷菜单")
        showMenu()
    }

    override fun onDragging(x: Int, y: Int) { hideMenu() }

    override fun onDragEnd(x: Int, y: Int) {
        prefs.edit()
            .putInt(KEY_POSITION_X, x)
            .putInt(KEY_POSITION_Y, y)
            .apply()
    }

    // ============================================================
    // 可见性控制
    // ============================================================

    private fun toggleVisibility() {
        if (isPaused) { isPaused = false; showOverlay() }
        else { isPaused = true; hideOverlay() }
        updateNotification()
    }

    // ============================================================
    // 精灵配置管理（完整持久化）
    // ============================================================

    /**
     * 从SharedPreferences加载精灵配置
     * 恢复所有自定义设置（包括自定义图片/视频/3D模型路径、装备列表、动画风格等）
     */
    private fun loadAvatarConfig() {
        avatarConfig = AvatarConfig(
            gender = AvatarGender.entries.firstOrNull {
                it.code == prefs.getString(KEY_AVATAR_GENDER, AvatarGender.NONE.code)
            } ?: AvatarGender.NONE,
            age = AvatarAge.entries.firstOrNull {
                it.code == prefs.getString(KEY_AVATAR_AGE, AvatarAge.YOUNG_ADULT.code)
            } ?: AvatarAge.YOUNG_ADULT,
            personality = AvatarPersonality.entries.firstOrNull {
                it.code == prefs.getString(KEY_AVATAR_PERSONALITY, AvatarPersonality.GENTLE.code)
            } ?: AvatarPersonality.GENTLE,
            expression = AvatarExpression.entries.firstOrNull {
                it.code == prefs.getString(KEY_AVATAR_EXPRESSION, AvatarExpression.NEUTRAL.code)
            } ?: AvatarExpression.NEUTRAL,
            handAction = HandAction.entries.firstOrNull {
                it.code == prefs.getString(KEY_AVATAR_HAND, HandAction.NONE.code)
            } ?: HandAction.NONE,
            footAction = FootAction.entries.firstOrNull {
                it.code == prefs.getString(KEY_AVATAR_FOOT, FootAction.STANDING.code)
            } ?: FootAction.STANDING,
            costume = AvatarCostume.entries.firstOrNull {
                it.code == prefs.getString(KEY_AVATAR_COSTUME, AvatarCostume.SUIT.code)
            } ?: AvatarCostume.SUIT,
            overallAction = OverallAction.entries.firstOrNull {
                it.code == prefs.getString(KEY_AVATAR_OVERALL, OverallAction.IDLE.code)
            } ?: OverallAction.IDLE,
            shape = AvatarShape.entries.firstOrNull {
                it.code == prefs.getString(KEY_AVATAR_SHAPE, AvatarShape.CIRCLE.code)
            } ?: AvatarShape.CIRCLE,
            customImagePath = prefs.getString(KEY_AVATAR_CUSTOM_IMAGE, null),
            customVideoPath = prefs.getString(KEY_AVATAR_CUSTOM_VIDEO, null),
            sizeMultiplier = prefs.getFloat(KEY_AVATAR_SIZE_MULTIPLIER, 1.0f),
            animationStyle = AnimationStyle.entries.firstOrNull {
                it.code == prefs.getString(KEY_AVATAR_ANIMATION_STYLE, AnimationStyle.ANIMATION_3D.code)
            } ?: AnimationStyle.ANIMATION_3D,
            glowColor = if (prefs.contains(KEY_AVATAR_GLOW_COLOR)) {
                prefs.getInt(KEY_AVATAR_GLOW_COLOR, 0).takeIf { it != 0 }
            } else null,
            equipmentList = deserializeEquipmentList(
                prefs.getString(KEY_AVATAR_EQUIPMENT_LIST, null)
            ),
            custom3DModelPath = prefs.getString(KEY_AVATAR_3D_MODEL, null),
            textToSpiritConfig = prefs.getString(KEY_AVATAR_TEXT_TO_SPIRIT, null)
        )
    }

    /**
     * 反序列化装备列表（JSON格式）
     */
    private fun deserializeEquipmentList(json: String?): List<EquipmentItem> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val items = mutableListOf<EquipmentItem>()
            // 简单解析: [{"imagePath":"...","description":"...","id":123},...]
            val regex = Regex("""\{"imagePath":"(.*?)","description":"(.*?)","id":(\d+)\}""")
            regex.findAll(json).forEach { match ->
                items.add(EquipmentItem(
                    imagePath = match.groupValues[1],
                    description = match.groupValues[2],
                    id = match.groupValues[3].toLong()
                ))
            }
            items
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * 序列化装备列表为JSON字符串
     */
    private fun serializeEquipmentList(items: List<EquipmentItem>): String {
        if (items.isEmpty()) return ""
        return items.joinToString(",", prefix = "[", postfix = "]") { item ->
            """{"imagePath":"${item.imagePath}","description":"${item.description}","id":${item.id}}"""
        }
    }

    /**
     * 保存精灵配置到SharedPreferences
     * 所有自定义设置完整保存，重启手机后可恢复
     */
    private fun saveAvatarConfig() {
        prefs.edit()
            .putString(KEY_AVATAR_GENDER, avatarConfig.gender.code)
            .putString(KEY_AVATAR_AGE, avatarConfig.age.code)
            .putString(KEY_AVATAR_PERSONALITY, avatarConfig.personality.code)
            .putString(KEY_AVATAR_EXPRESSION, avatarConfig.expression.code)
            .putString(KEY_AVATAR_HAND, avatarConfig.handAction.code)
            .putString(KEY_AVATAR_FOOT, avatarConfig.footAction.code)
            .putString(KEY_AVATAR_COSTUME, avatarConfig.costume.code)
            .putString(KEY_AVATAR_OVERALL, avatarConfig.overallAction.code)
            .putString(KEY_AVATAR_SHAPE, avatarConfig.shape.code)
            .putString(KEY_AVATAR_CUSTOM_IMAGE, avatarConfig.customImagePath)
            .putString(KEY_AVATAR_CUSTOM_VIDEO, avatarConfig.customVideoPath)
            .putFloat(KEY_AVATAR_SIZE_MULTIPLIER, avatarConfig.sizeMultiplier)
            .putString(KEY_AVATAR_ANIMATION_STYLE, avatarConfig.animationStyle.code)
            .putString(KEY_AVATAR_3D_MODEL, avatarConfig.custom3DModelPath)
            .putString(KEY_AVATAR_EQUIPMENT_LIST, serializeEquipmentList(avatarConfig.equipmentList))
            .putString(KEY_AVATAR_TEXT_TO_SPIRIT, avatarConfig.textToSpiritConfig)
            .apply {
                if (avatarConfig.glowColor != null) {
                    putInt(KEY_AVATAR_GLOW_COLOR, avatarConfig.glowColor!!)
                } else {
                    remove(KEY_AVATAR_GLOW_COLOR)
                }
            }
            .apply()
    }

    // ============================================================
    // 前台通知（"MindSoul精灵运行中"）
    // ============================================================

    private fun startForegroundNotification() {
        createNotificationChannel()
        val notification = buildNotification("✨ MindSoul精灵运行中")
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun updateNotification() {
        val text = if (isPaused) "⏸️ MindSoul精灵已暂停" else "✨ MindSoul精灵运行中"
        val notification = buildNotification(text)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val chatIntent = PendingIntent.getService(
            this, 1,
            Intent(this, FloatingAvatarService::class.java).apply { action = ACTION_QUICK_CHAT },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val toggleIntent = PendingIntent.getService(
            this, 2,
            Intent(this, FloatingAvatarService::class.java).apply { action = ACTION_TOGGLE_VISIBILITY },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val settingsIntent = PendingIntent.getService(
            this, 3,
            Intent(this, FloatingAvatarService::class.java).apply { action = ACTION_OPEN_SETTINGS },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val voiceIntent = PendingIntent.getService(
            this, 4,
            Intent(this, FloatingAvatarService::class.java).apply { action = ACTION_VOICE_MODE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("MindSoul")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(android.R.drawable.ic_menu_edit, "对话", chatIntent)
            .addAction(android.R.drawable.ic_menu_call, "语音", voiceIntent)
            .addAction(
                if (isPaused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
                if (isPaused) "恢复" else "暂停",
                toggleIntent
            )
            .addAction(android.R.drawable.ic_menu_preferences, "设置", settingsIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "MindSoul 桌面精灵常驻通知"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    // ============================================================
    // 定时任务
    // ============================================================

    private fun startPeriodicTasks() {
        thoughtJob?.cancel()
        thoughtJob = serviceScope.launch {
            while (isActive) {
                val delay = THOUGHT_CHECK_MIN_MS +
                        (Math.random() * (THOUGHT_CHECK_MAX_MS - THOUGHT_CHECK_MIN_MS)).toLong()
                delay(delay)
                if (!isPaused && !isFullScreenBlocking) {
                    floatingView?.triggerRandomThought()
                }
            }
        }

        fullscreenCheckJob?.cancel()
        fullscreenCheckJob = serviceScope.launch {
            while (isActive) {
                delay(FULLSCREEN_CHECK_INTERVAL_MS)
                if (prefs.getBoolean(KEY_AUTO_HIDE_FULLSCREEN, true)) {
                    checkFullScreenApp()
                }
            }
        }
    }

    private fun checkFullScreenApp() {
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val tasks = am.getRunningTasks(1)
            if (tasks.isNotEmpty()) {
                val topActivity = tasks[0].topActivity
                val packageName = topActivity?.packageName ?: return
                if (packageName == this.packageName) {
                    if (isFullScreenBlocking) { isFullScreenBlocking = false; showOverlay() }
                    return
                }
                val fullscreenApps = listOf(
                    "com.tencent.mm", "com.tencent.mobileqq",
                    "com.ss.android.ugc.awesome", "com.tencent.tmgp", "com.netease"
                )
                val isFullscreen = fullscreenApps.any { packageName.startsWith(it) }
                if (isFullscreen && !isFullScreenBlocking) {
                    isFullScreenBlocking = true
                    hideOverlay()
                } else if (!isFullscreen && isFullScreenBlocking) {
                    isFullScreenBlocking = false
                    if (!isPaused) showOverlay()
                }
            }
        } catch (_: Exception) { }
    }

    // ============================================================
    // 辅助方法
    // ============================================================

    private fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else true
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:$packageName")
            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            startActivity(intent)
        }
    }

    private fun openMainApp() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        startActivity(intent)
    }

    private fun openVoiceMode() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            putExtra("mode", "voice")
        }
        startActivity(intent)
    }

    private fun openSettings() {
        val intent = Intent(this, FloatingSettingsActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private fun showStatusInfo() {
        val enabled = !isPaused
        val sizeName = FloatingSize.fromPreference(prefs.getInt(KEY_SIZE, 1)).name
        floatingView?.showThoughtBubble("运行中 | 大小: $sizeName")
    }

    private fun stopAll() {
        thoughtJob?.cancel()
        stateSyncJob?.cancel()
        fullscreenCheckJob?.cancel()
        serviceScope.cancel()
        hideMenu()
        hideOverlay()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), resources.displayMetrics
        ).toInt()
    }

    private fun getScreenWidth(): Int = resources.displayMetrics.widthPixels
}


/*
 * ============================================================
 * FloatingQuickMenu - 悬浮窗快捷菜单视图（升级版）
 * ============================================================
 */
class FloatingQuickMenu(context: Context) : FrameLayout(context) {

    interface MenuActionListener {
        fun onChat()
        fun onStatus()
        fun onSettings()
        fun onHide()
    }

    private var listener: MenuActionListener? = null

    init {
        val container = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(16).toFloat()
                setColor(Color.argb(230, 30, 30, 40))
                setStroke(dpToPx(1), Color.argb(60, 255, 255, 255))
            }
            setPadding(dpToPx(4), dpToPx(8), dpToPx(4), dpToPx(8))
        }

        val items = listOf(
            Triple("💬", "对话", Runnable { listener?.onChat() }),
            Triple("📊", "状态", Runnable { listener?.onStatus() }),
            Triple("⚙️", "设置", Runnable { listener?.onSettings() }),
            Triple("👁️", "隐藏", Runnable { listener?.onHide() })
        )

        for ((emoji, label, action) in items) {
            val itemView = android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10))
                isClickable = true
                isFocusable = true
                setBackgroundResource(android.R.drawable.list_selector_background)
                setOnClickListener { action.run() }
            }
            val emojiText = android.widget.TextView(context).apply {
                text = emoji
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setPadding(0, 0, dpToPx(8), 0)
            }
            itemView.addView(emojiText)
            val labelText = android.widget.TextView(context).apply {
                text = label
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            }
            itemView.addView(labelText)
            container.addView(itemView)
        }
        addView(container, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun setMenuListener(listener: MenuActionListener) {
        this.listener = listener
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), context.resources.displayMetrics
        ).toInt()
    }
}
