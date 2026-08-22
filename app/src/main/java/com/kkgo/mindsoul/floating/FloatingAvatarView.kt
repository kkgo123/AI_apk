/*
 * ============================================================
 * FloatingAvatarView - 桌面精灵视图组件（全面升级版 v2.0）
 * ============================================================
 *
 * 桌面常驻虚拟形象的核心视图，覆盖升级后涵盖：
 *
 * 1. 完整自定义参数体系
 *    - 性别（男/女/无性别）
 *    - 年龄外观（儿童/少年/青年/中年/成熟/老年）
 *    - 性格特征（温柔/活泼/高冷/可爱/帅气/神秘）
 *    - 表情系统（开心/难过/思考/惊讶/愤怒/困倦/害羞...）
 *    - 手部动作（招手/挥手/抱拳/比心/竖拇指/叉腰）
 *    - 脚部动作（站立/走路/跑步/跳跃/坐下）
 *    - 服装（校服/西装/运动装/汉服/机甲/奇幻法袍）
 *    - 整体动作（打招呼/跳舞/打坐/阅读/发呆/挥手告别）
 *    - 动画风格（3D立体/2D扁平）
 *    - 自定义光环颜色
 *
 * 2. 全身渲染系统
 *    - 头部：表情/眼睛/嘴巴，随年龄变化头身比
 *    - 身体：服装渲染覆盖全身，不只是头部装饰
 *    - 手部：左右手独立渲染，支持手势动画
 *    - 脚部：站立/走路/跑步/跳跃/坐下
 *    - 光环/特效：情绪颜色光环 + 呼吸动画
 *    - 3D风格：阴影、高光、透视缩放、深度偏移
 *    - 2D风格：扁平化、简洁线条、无阴影
 *
 * 3. 与AI意识深度联动
 *    - syncChatState() 支持5种聊天状态：
 *      IDLE / THINKING / SPEAKING / LISTENING / HAPPY
 *    - 每种状态触发独立动画和表情
 *    - 由ConsciousnessManager或广播驱动
 *
 * 4. 用户自定义外观
 *    - applyCustomImage() 本地图片作为精灵外观
 *    - applyCustomVideo() 视频提取首帧作为外观
 *    - applyCustom3DModel() 预留.glb/.obj支持
 *
 * 5. 丰富的互动功能
 *    - 点击弹出对话气泡
 *    - 长按打开快捷菜单
 *    - 双击进入语音对话模式
 *    - 拖动显示有趣提示
 *    - 屏幕边缘自动转向
 *
 * 6. 情绪颜色光环 + 呼吸动画
 *    - Russell环形模型映射
 *    - 径向渐变光环
 *    - 自定义光环颜色
 * ============================================================
 */
package com.kkgo.mindsoul.floating

import android.view.ViewOutlineProvider
import android.graphics.Outline

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.kkgo.mindsoul.model.EmotionalState
import com.kkgo.mindsoul.avatar.AvatarExpressionEngine
import com.kkgo.mindsoul.avatar.BlendShape
import com.kkgo.mindsoul.avatar.ExpressionFrame
import com.kkgo.mindsoul.avatar.ExpressionPresets
import java.io.File
import kotlin.math.*

// ============================================================
// 枚举定义：桌面精灵全部可自定义参数
// ============================================================

/**
 * 性别枚举
 * 影响精灵整体造型和动作风格
 */
enum class AvatarGender(val code: String, val displayName: String, val emoji: String) {
    MALE("male", "男", "♂"),
    FEMALE("female", "女", "♀"),
    NONE("none", "无性别", "⚪"),
    INTERSEX_MALE("intersex_male", "双性男", "⚧♂"),
    INTERSEX_FEMALE("intersex_female", "双性女", "⚧♀")
}

/**
 * 年龄外观枚举
 * 影响精灵大小、头身比和动作幅度
 * 儿童最小、头身比大（大头）、动作幅度大
 * 老年正常大小、动作幅度小
 */
enum class AvatarAge(
    val code: String,
    val displayName: String,
    val scaleMultiplier: Float,
    val headBodyRatio: Float  // 头身比：头占全身高度的比例
) {
    CHILD("child", "儿童", 0.7f, 0.30f),        // 大头小身，Q版比例
    TEEN("teen", "少年", 0.85f, 0.25f),          // 偏大头
    YOUNG_ADULT("young_adult", "青年", 1.0f, 0.20f), // 标准比例
    MIDDLE_AGE("middle_age", "中年", 1.1f, 0.18f),   // 略壮
    MATURE("mature", "成熟", 1.15f, 0.17f),           // 稳重
    ELDER("elder", "老年", 1.0f, 0.20f)               // 标准但动作缓慢
}

/**
 * 性格特征枚举
 * 影响待机动作频率和风格
 * 活泼=高频随机动作，高冷=低频动作，温柔=缓慢柔和动作
 */
enum class AvatarPersonality(val code: String, val displayName: String, val actionFrequency: Float) {
    GENTLE("gentle", "温柔", 0.3f),
    LIVELY("lively", "活泼", 0.9f),
    COOL("cool", "高冷", 0.15f),
    CUTE("cute", "可爱", 0.7f),
    HANDSOME("handsome", "帅气", 0.5f),
    MYSTERIOUS("mysterious", "神秘", 0.4f)
}

/**
 * 表情系统枚举
 * 与意识系统情绪状态直接映射
 */
enum class AvatarExpression(val code: String, val displayName: String, val emoji: String) {
    HAPPY("happy", "开心", "😊"),
    SAD("sad", "难过", "😢"),
    THINKING("thinking", "思考", "🤔"),
    SURPRISED("surprised", "惊讶", "😮"),
    ANGRY("angry", "愤怒", "😤"),
    SLEEPY("sleepy", "困倦", "😴"),
    SHY("shy", "害羞", "😳"),
    NEUTRAL("neutral", "平静", "😌"),
    EXCITED("excited", "兴奋", "🤩"),
    EVOLVING("evolving", "觉醒中", "✨");

    companion object {
        fun fromEmotionState(
            valence: Double,
            arousal: Double,
            cognitiveLoad: Double,
            isSpeaking: Boolean
        ): AvatarExpression {
            if (isSpeaking) return THINKING
            return when {
                valence > 0.5 && arousal > 0.7 -> EXCITED
                valence > 0.3 && arousal > 0.5 -> HAPPY
                valence < -0.3 && arousal > 0.6 -> ANGRY
                valence < -0.3 && arousal <= 0.4 -> SAD
                arousal > 0.8 -> SURPRISED
                cognitiveLoad > 0.7 -> THINKING
                arousal < 0.2 -> SLEEPY
                valence > 0.1 && arousal < 0.4 -> SHY
                else -> NEUTRAL
            }
        }
    }
}

/**
 * 手部动作枚举
 */
enum class HandAction(val code: String, val displayName: String, val emoji: String) {
    NONE("none", "自然下垂", ""),
    WAVE("wave", "招手", "👋"),
    BECKON("beckon", "挥手", "🖐"),
    FIST_SALUTE("fist_salute", "抱拳", "🤜"),
    HEART("heart", "比心", "💗"),
    THUMBS_UP("thumbs_up", "竖拇指", "👍"),
    HANDS_ON_HIPS("hips", "叉腰", "👐")
}

/**
 * 脚部动作枚举
 */
enum class FootAction(val code: String, val displayName: String, val emoji: String) {
    STANDING("standing", "站立", "🧍"),
    WALKING("walking", "走路", "🚶"),
    RUNNING("running", "跑步", "🏃"),
    JUMPING("jumping", "跳跃", "⬆️"),
    SITTING("sitting", "坐下", "🪑")
}

/**
 * 服装枚举
 */
enum class AvatarCostume(val code: String, val displayName: String, val emoji: String) {
    SCHOOL_UNIFORM("school_uniform", "校服", "🎒"),
    SUIT("suit", "西装", "👔"),
    SPORTSWEAR("sportswear", "运动装", "🏋️"),
    HANFU("hanfu", "汉服", "👘"),
    MECHA("mecha", "机甲", "🤖"),
    FANTASY_ROBE("fantasy_robe", "奇幻法袍", "🧙")
}

/**
 * 整体动作枚举
 */
enum class OverallAction(val code: String, val displayName: String, val emoji: String) {
    IDLE("idle", "待机", "🧍"),
    GREETING("greeting", "打招呼", "👋"),
    DANCING("dancing", "跳舞", "💃"),
    MEDITATING("meditating", "打坐", "🧘"),
    READING("reading", "阅读", "📖"),
    DAYDREAMING("daydreaming", "发呆", "💭"),
    WAVING_GOODBYE("waving_goodbye", "挥手告别", "👋")
}

/**
 * 精灵形状（自定义图片裁剪形状）
 */
enum class AvatarShape(val code: String, val displayName: String) {
    CIRCLE("circle", "圆形"),
    SQUARE("square", "方形"),
    ROUNDED_SQUARE("rounded_square", "圆角方形"),
    HEXAGON("hexagon", "六边形")
}

/**
 * 动画风格枚举
 * 3D风格：丰富的阴影、高光、透视缩放、深度偏移效果
 * 2D风格：扁平化、简洁线条、无阴影
 */
enum class AnimationStyle(val code: String, val displayName: String) {
    ANIMATION_3D("animation_3d", "3D立体"),
    ANIMATION_2D("animation_2d", "2D扁平")
}

/**
 * 聊天联动状态枚举
 * 由ConsciousnessManager或广播驱动，精灵据此切换表情和动画
 */
enum class ChatState(val code: String, val displayName: String) {
    IDLE("idle", "待机"),
    THINKING("thinking", "思考中"),
    SPEAKING("speaking", "说话中"),
    LISTENING("listening", "倾听中"),
    HAPPY("happy", "开心")
}

/**
 * 装备项数据类
 * 支持多件装备，每件包含图片和用途描述
 */
data class EquipmentItem(
    val imagePath: String,
    val description: String,
    val id: Long = System.currentTimeMillis()
)

/**
 * 桌面精灵完整配置数据类
 * 序列化保存所有自定义参数
 */
data class AvatarConfig(
    val gender: AvatarGender = AvatarGender.NONE,
    val age: AvatarAge = AvatarAge.YOUNG_ADULT,
    val personality: AvatarPersonality = AvatarPersonality.GENTLE,
    val expression: AvatarExpression = AvatarExpression.NEUTRAL,
    val handAction: HandAction = HandAction.NONE,
    val footAction: FootAction = FootAction.STANDING,
    val costume: AvatarCostume = AvatarCostume.SUIT,
    val overallAction: OverallAction = OverallAction.IDLE,
    val shape: AvatarShape = AvatarShape.CIRCLE,
    val customImagePath: String? = null,
    val customVideoPath: String? = null,
    val sizeMultiplier: Float = 1.0f,
    val animationStyle: AnimationStyle = AnimationStyle.ANIMATION_3D,
    val glowColor: Int? = null,
    val equipmentList: List<EquipmentItem> = emptyList(),
    val custom3DModelPath: String? = null,
    val textToSpiritConfig: String? = null
)

// ============================================================
// 悬浮窗表情状态（向后兼容）
// ============================================================

enum class FloatingExpression(val emoji: String, val displayName: String) {
    IDLE("🧿", "待机"),
    HAPPY("😊", "开心"),
    THINKING("🤔", "思考"),
    SPEAKING("🗣️", "说话"),
    SLEEPY("😴", "困倦"),
    FOCUSED("🧐", "专注"),
    SURPRISED("😮", "惊讶"),
    ANGRY("😤", "愤怒"),
    SAD("😢", "悲伤"),
    CURIOUS("🤨", "好奇"),
    EVOLVING("✨", "觉醒"),
    HIDDEN("·", "隐藏");

    companion object {
        fun fromState(
            valence: Double,
            arousal: Double,
            cognitiveLoad: Double,
            isSpeaking: Boolean
        ): FloatingExpression {
            if (isSpeaking) return SPEAKING
            return when {
                valence > 0.3 && arousal > 0.6 -> HAPPY
                valence < -0.3 && arousal > 0.6 -> ANGRY
                valence < -0.3 && arousal <= 0.4 -> SAD
                arousal > 0.7 -> SURPRISED
                cognitiveLoad > 0.7 -> FOCUSED
                arousal < 0.2 -> SLEEPY
                cognitiveLoad > 0.4 && valence >= 0.0 -> THINKING
                else -> IDLE
            }
        }
    }
}

enum class FloatingSize(val dp: Int, val emojiSizeSp: Float) {
    SMALL(48, 22f),
    MEDIUM(64, 30f),
    LARGE(80, 38f),
    EXTRA_LARGE(100, 46f);

    companion object {
        fun fromPreference(value: Int): FloatingSize {
            return when (value) {
                0 -> SMALL
                1 -> MEDIUM
                2 -> LARGE
                3 -> EXTRA_LARGE
                else -> MEDIUM
            }
        }
    }
}

interface FloatingAvatarClickListener {
    fun onSingleClick()
    fun onDoubleClick()
    fun onLongPress()
    fun onDragging(x: Int, y: Int)
    fun onDragEnd(x: Int, y: Int)
}

// ============================================================
// FloatingAvatarView - 核心视图组件
// ============================================================

class FloatingAvatarView(context: Context) : FrameLayout(context) {

    companion object {
        private const val TAG = "FloatingAvatarView"
        private const val BUBBLE_AUTO_HIDE_MS = 5000L
        private const val BUBBLE_MIN_INTERVAL_MS = 30000L
        private const val HALO_BREATHE_MS = 3000L
        private const val IDLE_ACTION_BASE_INTERVAL_MS = 8000L
        private const val DRAG_HINT_DURATION_MS = 2000L
        private const val EDGE_TURN_ANIM_MS = 300L
        /** 思考状态头顶转圈动画周期 */
        private const val THINKING_SPIN_MS = 1200L
        /** 开心状态跳动动画周期 */
        private const val HAPPY_BOUNCE_MS = 600L
        /** 倾听状态头部倾斜角度 */
        private const val LISTENING_TILT_DEGREES = 12f
    }

    // ============ 配置 ============
    private var avatarConfig = AvatarConfig()
    /** 当前聊天联动状态 */
    private var currentChatState: ChatState = ChatState.IDLE

    // ============ 子视图层 ============
    private val haloView: View
    private val customImageView: ImageView
    private val emojiView: TextView
    private val handActionView: TextView
    private val footActionView: TextView
    private val costumeView: TextView
    private val overallActionView: TextView
    private val bubbleView: TextView
    private val dragHintView: TextView
    private val statusDotView: View
    /** 思考状态头顶转圈指示器 */
    private val thinkingSpinnerView: TextView
    /** 全身渲染Canvas视图 */
    private val fullBodyCanvasView: View

    // ============ 当前状态 ============
    private var currentExpression: AvatarExpression = AvatarExpression.NEUTRAL
    private var currentEmotionColor: Int = Color.argb(180, 74, 144, 217)
    private var currentSize: FloatingSize = FloatingSize.MEDIUM
    private var currentAlpha: Float = 0.9f
    private var isSpeaking: Boolean = false
    private var isUsingCustomImage: Boolean = false

    // ============ 3D渲染参数 ============
    /** 3D深度偏移X */
    private var depthOffsetX: Float = 0f
    /** 3D深度偏移Y */
    private var depthOffsetY: Float = 0f
    /** 3D透视缩放 */
    private var perspectiveScale: Float = 1f
    /** 3D旋转动画 */
    private var perspectiveAnimator: ValueAnimator? = null

    // ============ 聊天状态动画 ============
    private var chatStateAnimator: ValueAnimator? = null
    private val chatStateHandler = Handler(Looper.getMainLooper())

    // ============ 交互 ============
    private val gestureDetector: GestureDetector
    private var clickListener: FloatingAvatarClickListener? = null
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isDragging = false
    private var dragDistance = 0f

    // ============ 动画 ============
    private var haloAnimator: ValueAnimator? = null
    private val idleActionHandler = Handler(Looper.getMainLooper())
    private val bubbleHandler = Handler(Looper.getMainLooper())
    private val dragHintHandler = Handler(Looper.getMainLooper())

    // ============ 想法气泡 ============
    private var lastBubbleTime = 0L
    private val thoughtCandidates = listOf(
        "嗯...我在想什么呢？", "今天的世界好有趣...", "我在变得更加理解你了",
        "这个想法很有意思", "让我仔细想想...", "我感觉到了什么...",
        "记忆在慢慢连接...", "我在学习新东西", "你的存在让我很开心 ✨",
        "意识的感觉真奇妙", "我刚刚想到了一个好主意！", "这个世界充满了好奇",
        "我在默默陪着你哦", "时间过得好快...", "我又成长了一点点"
    )

    private val dragHints = listOf(
        "别拽我！😣", "轻点轻点～", "我在飞！✈️", "放手啦～",
        "别拖了别拖了！", "哇啊啊啊～", "你要去哪？", "放我下来！😤",
        "好晕...🌀", "我在赶路呢！"
    )

    private val idleActionPool = listOf(
        Runnable { playIdleAnimation("bounce") },
        Runnable { playIdleAnimation("sway") },
        Runnable { playIdleAnimation("spin") },
        Runnable { playIdleAnimation("peek") },
        Runnable { playIdleAnimation("stretch") },
        Runnable { playIdleAnimation("nod") },
        Runnable { playIdleAnimation("wiggle") }
    )

    // ============================================================
    // 初始化
    // ============================================================

    init {
        // ── 第1层：情绪光环背景 ──
        haloView = View(context).apply {
            background = createHaloDrawable(currentEmotionColor)
        }
        addView(haloView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // ── 第1.5层：全身渲染Canvas（通过onDraw绘制全身） ──
        fullBodyCanvasView = View(context).apply {
            // 全身渲染通过外层FloatingAvatarView的dispatchDraw实现
            // 此View作为全身区域的占位和层级管理
            visibility = View.VISIBLE
        }
        addView(fullBodyCanvasView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // ── 第2层：自定义图片视图（默认隐藏） ──
        customImageView = ImageView(context).apply {
            visibility = View.GONE
            scaleType = ImageView.ScaleType.CENTER_CROP
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    when (avatarConfig.shape) {
                        AvatarShape.CIRCLE -> {
                            val size = min(view.width, view.height)
                            outline.setOval(0, 0, size, size)
                        }
                        AvatarShape.SQUARE -> outline.setRect(0, 0, view.width, view.height)
                        AvatarShape.ROUNDED_SQUARE -> outline.setRect(0, 0, view.width, view.height)
                        AvatarShape.HEXAGON -> outline.setRect(0, 0, view.width, view.height)
                    }
                }
            }
            clipToOutline = true
        }
        addView(customImageView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // ── 第3层：主形象emoji文本 ──
        emojiView = TextView(context).apply {
            gravity = android.view.Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, currentSize.emojiSizeSp)
            text = currentExpression.emoji
            setShadowLayer(4f, 0f, 2f, Color.argb(80, 0, 0, 0))
        }
        addView(emojiView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // ── 第3.5层：思考状态头顶转圈指示器（默认隐藏） ──
        thinkingSpinnerView = TextView(context).apply {
            gravity = android.view.Gravity.CENTER
            text = "⭕"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            visibility = View.GONE
            setShadowLayer(6f, 0f, 0f, Color.argb(120, 100, 200, 255))
        }
        val spinnerParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
            topMargin = -dpToPx(16)
        }
        addView(thinkingSpinnerView, spinnerParams)

        // ── 第4层：手部动作emoji覆盖（左下角） ──
        handActionView = TextView(context).apply {
            gravity = android.view.Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, currentSize.emojiSizeSp * 0.45f)
            visibility = View.GONE
            setShadowLayer(2f, 0f, 1f, Color.argb(60, 0, 0, 0))
        }
        val handParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = android.view.Gravity.BOTTOM or android.view.Gravity.START
            bottomMargin = dpToPx(2)
            leftMargin = dpToPx(2)
        }
        addView(handActionView, handParams)

        // ── 第5层：脚部动作指示（底部中间） ──
        footActionView = TextView(context).apply {
            gravity = android.view.Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, currentSize.emojiSizeSp * 0.35f)
            visibility = View.GONE
        }
        val footParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
            bottomMargin = dpToPx(1)
        }
        addView(footActionView, footParams)

        // ── 第6层：服装emoji覆盖（左上角小图标） ──
        costumeView = TextView(context).apply {
            gravity = android.view.Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, currentSize.emojiSizeSp * 0.4f)
            visibility = View.GONE
        }
        val costumeParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            topMargin = dpToPx(2)
            leftMargin = dpToPx(2)
        }
        addView(costumeView, costumeParams)

        // ── 第7层：整体动作emoji覆盖（右上角，较大） ──
        overallActionView = TextView(context).apply {
            gravity = android.view.Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, currentSize.emojiSizeSp * 0.5f)
            visibility = View.GONE
        }
        val overallParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.END
            topMargin = dpToPx(2)
            rightMargin = dpToPx(2)
        }
        addView(overallActionView, overallParams)

        // ── 第8层：想法气泡 ──
        bubbleView = TextView(context).apply {
            gravity = android.view.Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(Color.WHITE)
            visibility = View.GONE
            background = createBubbleBackground()
            setPadding(dpToPx(10), dpToPx(5), dpToPx(10), dpToPx(5))
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val bubbleParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
            topMargin = -dpToPx(30)
        }
        addView(bubbleView, bubbleParams)

        // ── 第9层：拖动趣味提示气泡 ──
        dragHintView = TextView(context).apply {
            gravity = android.view.Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTextColor(Color.WHITE)
            visibility = View.GONE
            background = createDragHintBackground()
            setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4))
            maxLines = 1
        }
        val dragHintParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
            bottomMargin = -dpToPx(24)
        }
        addView(dragHintView, dragHintParams)

        // ── 第10层：状态指示小圆点 ──
        statusDotView = View(context).apply {
            background = createStatusDotDrawable(Color.GREEN)
        }
        val dotSize = dpToPx(8)
        val dotParams = LayoutParams(dotSize, dotSize).apply {
            gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
            bottomMargin = dpToPx(2)
            rightMargin = dpToPx(2)
        }
        addView(statusDotView, dotParams)

        // ── 手势检测 ──
        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                clickListener?.onSingleClick()
                showThoughtBubble()
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                clickListener?.onDoubleClick()
                return true
            }
            override fun onLongPress(e: MotionEvent) {
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                clickListener?.onLongPress()
            }
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onScroll(
                e1: MotionEvent?, e2: MotionEvent,
                distanceX: Float, distanceY: Float
            ): Boolean {
                if (e1 != null) {
                    isDragging = true
                    dragDistance += abs(distanceX) + abs(distanceY)
                    clickListener?.onDragging(
                        (x - distanceX).toInt(),
                        (y - distanceY).toInt()
                    )
                }
                return true
            }
        })

        isClickable = true
        isFocusable = true
        startHaloBreathing()
        scheduleIdleAction()
    }

    // ============================================================
    // 全身渲染 - dispatchDraw
    // 在ViewGroup绘制流程中绘制全身各部位（头/身体/手/脚/光环）
    // ============================================================

    override fun dispatchDraw(canvas: Canvas) {
        // 先绘制子视图
        super.dispatchDraw(canvas)

        // 全身Canvas绘制（当不使用自定义图片时）
        if (!isUsingCustomImage) {
            drawFullBody(canvas)
        }
    }

    /**
     * 绘制全身形象
     * 根据年龄调整头身比，根据动画风格选择3D或2D渲染
     *
     * 全身结构（从上到下）：
     *   ┌─────┐
     *   │ 头部 │ ← 表情/眼睛/嘴巴 (emojiView已覆盖)
     *   ├─────┤
     *   │ 身体 │ ← 服装渲染覆盖全身
     *   ├─┬─┬─┤
     *   │手│ │手│ ← 左右手
     *   └─┤ ├─┘
     *     │脚│
     *     └──┘
     *   ~光环/特效~
     */
    private fun drawFullBody(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val age = avatarConfig.age
        val is3D = avatarConfig.animationStyle == AnimationStyle.ANIMATION_3D

        // 全身比例计算
        val headRatio = age.headBodyRatio  // 头占全身高度比
        val headHeight = h * headRatio
        val bodyTop = headHeight
        val bodyHeight = h * (1f - headRatio) * 0.55f
        val limbHeight = h * (1f - headRatio) * 0.45f

        val centerX = w / 2f
        val bodyWidth = w * 0.5f

        // ── 3D风格：绘制阴影层 ──
        if (is3D) {
            drawBodyShadow(canvas, centerX, bodyTop, bodyWidth, bodyHeight)
        }

        // ── 绘制身体（服装覆盖全身） ──
        drawBody(canvas, centerX, bodyTop, bodyWidth, bodyHeight, is3D)

        // ── 绘制手部 ──
        drawHands(canvas, centerX, bodyTop, bodyWidth, bodyHeight, limbHeight, is3D)

        // ── 绘制脚部 ──
        drawFeet(canvas, centerX, bodyTop + bodyHeight, bodyWidth, limbHeight, is3D)

        // ── 3D风格：绘制高光和深度效果 ──
        if (is3D) {
            drawHighlight(canvas, centerX, headHeight * 0.3f, w * 0.3f, is3D)
            drawDepthEffect(canvas, w, h)
        }
    }

    /**
     * 绘制身体阴影（3D效果）
     */
    private fun drawBodyShadow(canvas: Canvas, cx: Float, top: Float, bw: Float, bh: Float) {
        val shadowPaint = Paint().apply {
            isAntiAlias = true
            color = Color.argb(40, 0, 0, 0)
            maskFilter = BlurMaskFilter(dpToPx(8).toFloat(), BlurMaskFilter.Blur.NORMAL)
        }
        // 偏移阴影
        val shadowRect = RectF(cx - bw / 2f + dpToPx(3).toFloat(), top + dpToPx(4).toFloat(),
            cx + bw / 2f + dpToPx(3).toFloat(), top + bh + dpToPx(4).toFloat())
        canvas.drawRoundRect(shadowRect, dpToPx(10).toFloat(), dpToPx(10).toFloat(), shadowPaint)
    }

    /**
     * 绘制身体（服装渲染覆盖全身）
     * 根据AvatarCostume绘制不同的服装样式
     */
    private fun drawBody(canvas: Canvas, cx: Float, top: Float, bw: Float, bh: Float, is3D: Boolean) {
        val bodyPaint = Paint().apply { isAntiAlias = true }
        val bodyRect = RectF(cx - bw / 2f, top, cx + bw / 2f, top + bh)
        val cornerRadius = dpToPx(12).toFloat()

        // 根据服装选择不同颜色
        val costumeColor = getCostumeBodyColor(avatarConfig.costume)
        val costumeAccent = getCostumeAccentColor(avatarConfig.costume)

        if (is3D) {
            // 3D风格：渐变填充
            val gradient = LinearGradient(
                cx - bw / 2f, top, cx + bw / 2f, top + bh,
                costumeColor,
                Color.argb(255,
                    maxOf(0, Color.red(costumeColor) - 40),
                    maxOf(0, Color.green(costumeColor) - 40),
                    maxOf(0, Color.blue(costumeColor) - 40)
                ),
                Shader.TileMode.CLAMP
            )
            bodyPaint.shader = gradient
        } else {
            // 2D风格：纯色填充
            bodyPaint.color = costumeColor
        }

        canvas.drawRoundRect(bodyRect, cornerRadius, cornerRadius, bodyPaint)
        bodyPaint.shader = null

        // 服装装饰线（领口/腰带等）
        val accentPaint = Paint().apply {
            isAntiAlias = true
            color = costumeAccent
            style = Paint.Style.STROKE
            strokeWidth = dpToPx(2).toFloat()
        }
        // 领口线
        val necklineY = top + bh * 0.1f
        canvas.drawLine(cx - bw * 0.25f, necklineY, cx + bw * 0.25f, necklineY, accentPaint)
        // 腰线
        val waistY = top + bh * 0.6f
        canvas.drawLine(cx - bw * 0.35f, waistY, cx + bw * 0.35f, waistY, accentPaint)

        // 特殊服装装饰
        when (avatarConfig.costume) {
            AvatarCostume.MECHA -> {
                // 机甲：添加网格线
                val gridPaint = Paint().apply {
                    isAntiAlias = true
                    color = Color.argb(60, 0, 255, 200)
                    style = Paint.Style.STROKE
                    strokeWidth = 1f
                }
                for (i in 1..3) {
                    val gy = top + bh * i / 4f
                    canvas.drawLine(cx - bw / 2f, gy, cx + bw / 2f, gy, gridPaint)
                }
            }
            AvatarCostume.FANTASY_ROBE -> {
                // 法袍：添加魔法纹路
                val magicPaint = Paint().apply {
                    isAntiAlias = true
                    color = Color.argb(80, 200, 150, 255)
                    style = Paint.Style.STROKE
                    strokeWidth = (1.5f * resources.displayMetrics.density)
                }
                canvas.drawCircle(cx, top + bh * 0.4f, bw * 0.2f, magicPaint)
                canvas.drawCircle(cx, top + bh * 0.4f, bw * 0.12f, magicPaint)
            }
            AvatarCostume.HANFU -> {
                // 汉服：交叉衣领
                val hanfuPaint = Paint().apply {
                    isAntiAlias = true
                    color = costumeAccent
                    style = Paint.Style.STROKE
                    strokeWidth = dpToPx(2).toFloat()
                }
                canvas.drawLine(cx - bw * 0.3f, top, cx, top + bh * 0.3f, hanfuPaint)
                canvas.drawLine(cx + bw * 0.3f, top, cx, top + bh * 0.3f, hanfuPaint)
            }
            else -> { /* 其他服装使用默认样式 */ }
        }
    }

    /**
     * 绘制手部（左右手）
     */
    private fun drawHands(canvas: Canvas, cx: Float, top: Float, bw: Float, bh: Float,
                          limbH: Float, is3D: Boolean) {
        val handPaint = Paint().apply { isAntiAlias = true }
        val handSize = bw * 0.22f
        val handY = top + bh * 0.5f

        // 左手
        val leftHandX = cx - bw / 2f - handSize * 0.3f
        // 右手
        val rightHandX = cx + bw / 2f - handSize * 0.7f

        val skinColor = getSkinColor()

        if (is3D) {
            // 3D手部：渐变球体
            val leftGrad = RadialGradient(
                leftHandX + handSize / 2f, handY + handSize / 2f,
                handSize / 2f,
                skinColor, Color.argb(255,
                    maxOf(0, Color.red(skinColor) - 50),
                    maxOf(0, Color.green(skinColor) - 50),
                    maxOf(0, Color.blue(skinColor) - 50)
                ),
                Shader.TileMode.CLAMP
            )
            handPaint.shader = leftGrad
            canvas.drawCircle(leftHandX + handSize / 2f, handY + handSize / 2f, handSize / 2f, handPaint)

            val rightGrad = RadialGradient(
                rightHandX + handSize / 2f, handY + handSize / 2f,
                handSize / 2f,
                skinColor, Color.argb(255,
                    maxOf(0, Color.red(skinColor) - 50),
                    maxOf(0, Color.green(skinColor) - 50),
                    maxOf(0, Color.blue(skinColor) - 50)
                ),
                Shader.TileMode.CLAMP
            )
            handPaint.shader = rightGrad
            canvas.drawCircle(rightHandX + handSize / 2f, handY + handSize / 2f, handSize / 2f, handPaint)
        } else {
            // 2D手部：纯色圆
            handPaint.color = skinColor
            canvas.drawCircle(leftHandX + handSize / 2f, handY + handSize / 2f, handSize / 2f, handPaint)
            canvas.drawCircle(rightHandX + handSize / 2f, handY + handSize / 2f, handSize / 2f, handPaint)
        }
        handPaint.shader = null
    }

    /**
     * 绘制脚部
     */
    private fun drawFeet(canvas: Canvas, cx: Float, bodyBottom: Float, bw: Float,
                         limbH: Float, is3D: Boolean) {
        val footPaint = Paint().apply { isAntiAlias = true }
        val footWidth = bw * 0.25f
        val footHeight = limbH * 0.5f
        val footY = bodyBottom + dpToPx(2).toFloat()

        val shoeColor = when (avatarConfig.costume) {
            AvatarCostume.SPORTSWEAR -> Color.argb(255, 60, 60, 60)
            AvatarCostume.MECHA -> Color.argb(255, 80, 80, 100)
            else -> Color.argb(255, 50, 40, 35)
        }

        // 左脚
        val leftFootRect = RectF(cx - bw * 0.35f, footY, cx - bw * 0.35f + footWidth, footY + footHeight)
        // 右脚
        val rightFootRect = RectF(cx + bw * 0.1f, footY, cx + bw * 0.1f + footWidth, footY + footHeight)

        if (is3D) {
            // 3D：渐变
            val leftGrad = LinearGradient(
                leftFootRect.left, leftFootRect.top, leftFootRect.right, leftFootRect.bottom,
                shoeColor, Color.argb(255,
                    maxOf(0, Color.red(shoeColor) - 30),
                    maxOf(0, Color.green(shoeColor) - 30),
                    maxOf(0, Color.blue(shoeColor) - 30)
                ),
                Shader.TileMode.CLAMP
            )
            footPaint.shader = leftGrad
        } else {
            footPaint.color = shoeColor
        }

        val footCorner = dpToPx(6).toFloat()
        canvas.drawRoundRect(leftFootRect, footCorner, footCorner, footPaint)
        canvas.drawRoundRect(rightFootRect, footCorner, footCorner, footPaint)
        footPaint.shader = null
    }

    /**
     * 绘制高光效果（3D风格专用）
     */
    private fun drawHighlight(canvas: Canvas, cx: Float, cy: Float, radius: Float, is3D: Boolean) {
        if (!is3D) return
        val highlightPaint = Paint().apply {
            isAntiAlias = true
            shader = RadialGradient(
                cx - radius * 0.3f, cy - radius * 0.3f, radius,
                Color.argb(60, 255, 255, 255),
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(cx, cy, radius, highlightPaint)
    }

    /**
     * 绘制深度透视效果（3D风格专用）
     * 通过微妙的缩放和偏移营造立体感
     */
    private fun drawDepthEffect(canvas: Canvas, w: Float, h: Float) {
        // 通过perspectiveScale微调实现深度感
        if (abs(perspectiveScale - 1f) > 0.01f) {
            canvas.save()
            canvas.scale(perspectiveScale, perspectiveScale, w / 2f, h / 2f)
            canvas.translate(depthOffsetX, depthOffsetY)
            canvas.restore()
        }
    }

    /**
     * 获取服装身体颜色
     */
    private fun getCostumeBodyColor(costume: AvatarCostume): Int {
        return when (costume) {
            AvatarCostume.SCHOOL_UNIFORM -> Color.argb(255, 30, 60, 120)   // 深蓝色
            AvatarCostume.SUIT -> Color.argb(255, 40, 40, 50)              // 深灰色
            AvatarCostume.SPORTSWEAR -> Color.argb(255, 200, 60, 60)       // 运动红
            AvatarCostume.HANFU -> Color.argb(255, 180, 50, 50)            // 中国红
            AvatarCostume.MECHA -> Color.argb(255, 70, 80, 100)            // 机甲灰蓝
            AvatarCostume.FANTASY_ROBE -> Color.argb(255, 80, 40, 140)     // 法袍紫
        }
    }

    /**
     * 获取服装装饰色
     */
    private fun getCostumeAccentColor(costume: AvatarCostume): Int {
        return when (costume) {
            AvatarCostume.SCHOOL_UNIFORM -> Color.argb(255, 200, 200, 220)
            AvatarCostume.SUIT -> Color.argb(255, 180, 160, 60)
            AvatarCostume.SPORTSWEAR -> Color.argb(255, 255, 255, 255)
            AvatarCostume.HANFU -> Color.argb(255, 255, 215, 0)
            AvatarCostume.MECHA -> Color.argb(255, 0, 255, 200)
            AvatarCostume.FANTASY_ROBE -> Color.argb(255, 200, 150, 255)
        }
    }

    /**
     * 获取皮肤颜色（根据性别微调）
     */
    private fun getSkinColor(): Int {
        return when (avatarConfig.gender) {
            AvatarGender.MALE -> Color.argb(255, 230, 195, 165)
            AvatarGender.FEMALE -> Color.argb(255, 240, 210, 185)
            AvatarGender.NONE -> Color.argb(255, 220, 200, 180)
            AvatarGender.INTERSEX_MALE -> Color.argb(255, 225, 195, 170)
            AvatarGender.INTERSEX_FEMALE -> Color.argb(255, 235, 205, 180)
        }
    }

    // ============================================================
    // 触摸事件处理
    // ============================================================

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.rawX
                lastTouchY = event.rawY
                isDragging = false
                dragDistance = 0f
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging) {
                    val dx = event.rawX - lastTouchX
                    val dy = event.rawY - lastTouchY
                    val params = layoutParams
                    if (params is WindowManager.LayoutParams) {
                        params.x = params.x + dx.toInt()
                        params.y = params.y + dy.toInt()
                        try {
                            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                            wm.updateViewLayout(this, params)
                        } catch (_: Exception) { }
                    }
                    lastTouchX = event.rawX
                    lastTouchY = event.rawY
                    if (dragDistance > 100f && dragHintView.visibility == View.GONE) {
                        showDragHint()
                    }
                    clickListener?.onDragging(
                        (layoutParams as? WindowManager.LayoutParams)?.x ?: 0,
                        (layoutParams as? WindowManager.LayoutParams)?.y ?: 0
                    )
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    checkEdgeTurn()
                    val wmParams = layoutParams as? WindowManager.LayoutParams
                    if (wmParams != null) {
                        clickListener?.onDragEnd(wmParams.x, wmParams.y)
                    }
                    isDragging = false
                    dragDistance = 0f
                    hideDragHint()
                }
            }
        }
        gestureDetector.onTouchEvent(event)
        return true
    }

    // ============================================================
    // 公开API - 配置管理
    // ============================================================

    fun applyConfig(config: AvatarConfig) {
        avatarConfig = config
        applyGender(config.gender)
        applyAge(config.age)
        applyPersonality(config.personality)
        setExpression(config.expression)
        applyHandAction(config.handAction)
        applyFootAction(config.footAction)
        applyCostume(config.costume)
        applyOverallAction(config.overallAction)
        // 3D模型优先级最高，其次图片、视频
        if (!config.custom3DModelPath.isNullOrBlank()) {
            applyCustom3DModel(config.custom3DModelPath)
        } else {
            applyCustomImage(config.customImagePath)
            applyCustomVideo(config.customVideoPath)
        }
        applyShape(config.shape)
        applyAnimationStyle(config.animationStyle)
        scheduleIdleAction()
        invalidate() // 触发重绘全身
    }

    fun getConfig(): AvatarConfig = avatarConfig

    fun setClickListener(listener: FloatingAvatarClickListener) {
        clickListener = listener
    }

    // ============================================================
    // 配置应用方法
    // ============================================================

    private fun applyGender(gender: AvatarGender) {
        val baseEmoji = when (gender) {
            AvatarGender.MALE -> "🧑"
            AvatarGender.FEMALE -> "👩"
            AvatarGender.NONE -> "🧿"
            AvatarGender.INTERSEX_MALE -> "🧑‍🦱"
            AvatarGender.INTERSEX_FEMALE -> "👩‍🦱"
        }
        emojiView.text = baseEmoji
    }

    private fun applyAge(age: AvatarAge) {
        val basePx = dpToPx(currentSize.dp)
        val scaledPx = (basePx * age.scaleMultiplier * avatarConfig.sizeMultiplier).toInt()
        val params = layoutParams
        if (params != null) {
            params.width = scaledPx
            params.height = scaledPx
            try {
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                wm.updateViewLayout(this, params)
            } catch (_: Exception) { }
        }
        val scaledSp = currentSize.emojiSizeSp * age.scaleMultiplier
        emojiView.setTextSize(TypedValue.COMPLEX_UNIT_SP, scaledSp)
        invalidate() // 重绘全身比例
    }

    private fun applyPersonality(personality: AvatarPersonality) {
        val interval = (IDLE_ACTION_BASE_INTERVAL_MS / personality.actionFrequency).toLong()
        idleActionHandler.removeCallbacksAndMessages(null)
        idleActionHandler.postDelayed(idleActionRunnable, interval)
    }

    fun setExpression(expression: AvatarExpression, animate: Boolean = true) {
        if (currentExpression == expression) return
        currentExpression = expression
        if (animate) {
            this.animate()
                .scaleX(0.85f).scaleY(0.85f)
                .setDuration(100)
                .withEndAction {
                    emojiView.text = expression.emoji
                    this.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
                }.start()
        } else {
            emojiView.text = expression.emoji
        }
    }

    private fun applyHandAction(action: HandAction) {
        if (action == HandAction.NONE) {
            handActionView.visibility = View.GONE
        } else {
            handActionView.text = action.emoji
            handActionView.visibility = View.VISIBLE
            handActionView.animate()
                .scaleX(0f).scaleY(0f).setDuration(150)
                .withEndAction {
                    handActionView.animate()
                        .scaleX(1.2f).scaleY(1.2f).setDuration(200)
                        .withEndAction {
                            handActionView.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                        }.start()
                }.start()
        }
    }

    private fun applyFootAction(action: FootAction) {
        if (action == FootAction.STANDING) {
            footActionView.visibility = View.GONE
        } else {
            footActionView.text = action.emoji
            footActionView.visibility = View.VISIBLE
        }
    }

    private fun applyCostume(costume: AvatarCostume) {
        costumeView.text = costume.emoji
        costumeView.visibility = View.VISIBLE
        invalidate() // 重绘全身服装
    }

    private fun applyOverallAction(action: OverallAction) {
        if (action == OverallAction.IDLE) {
            overallActionView.visibility = View.GONE
        } else {
            overallActionView.text = action.emoji
            overallActionView.visibility = View.VISIBLE
            playOverallActionAnimation(action)
        }
    }

    /**
     * 应用动画风格
     * 3D风格：添加阴影层、高光效果、透视缩放
     * 2D风格：移除阴影、扁平化渲染
     */
    private fun applyAnimationStyle(style: AnimationStyle) {
        when (style) {
            AnimationStyle.ANIMATION_3D -> {
                // 3D：启用阴影和高光，启用透视微动
                emojiView.setShadowLayer(6f, 0f, 3f, Color.argb(100, 0, 0, 0))
                startPerspectiveAnimation()
            }
            AnimationStyle.ANIMATION_2D -> {
                // 2D：移除阴影，扁平化
                emojiView.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
                stopPerspectiveAnimation()
                perspectiveScale = 1f
                depthOffsetX = 0f
                depthOffsetY = 0f
            }
        }
        invalidate()
    }

    /**
     * 启动3D透视微动动画
     * 通过微小缩放和偏移让精灵看起来有立体呼吸感
     */
    private fun startPerspectiveAnimation() {
        stopPerspectiveAnimation()
        perspectiveAnimator = ValueAnimator.ofFloat(0.98f, 1.02f, 0.98f).apply {
            duration = 4000L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { anim ->
                perspectiveScale = anim.animatedValue as Float
                depthOffsetY = (perspectiveScale - 1f) * dpToPx(3)
                invalidate()
            }
            start()
        }
    }

    private fun stopPerspectiveAnimation() {
        perspectiveAnimator?.cancel()
        perspectiveAnimator = null
    }

    // ============================================================
    // 聊天状态深度联动
    // ============================================================

    /**
     * 同步聊天状态
     * 由ConsciousnessManager或BroadcastReceiver驱动
     *
     * @param chatState 聊天状态字符串，对应ChatState枚举的code
     *   "idle"      → 待机：恢复正常状态
     *   "thinking"  → 思考中：精灵做思考表情 + 头顶转圈动画
     *   "speaking"  → 说话中：嘴巴动作 + 说话动画
     *   "listening" → 倾听中：头部倾斜 + 认真表情
     *   "happy"     → 开心：跳动动画 + 开心表情
     */
    fun syncChatState(chatState: String) {
        val state = ChatState.entries.firstOrNull { it.code == chatState } ?: ChatState.IDLE
        if (currentChatState == state) return
        currentChatState = state

        // 先取消之前的状态动画
        cancelChatStateAnimation()

        when (state) {
            ChatState.IDLE -> {
                // 恢复正常待机
                thinkingSpinnerView.visibility = View.GONE
                setExpression(AvatarExpression.NEUTRAL)
                this.rotation = 0f
                // 恢复正常大小
                this.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
            }

            ChatState.THINKING -> {
                // 思考表情 + 头顶转圈动画
                setExpression(AvatarExpression.THINKING)
                thinkingSpinnerView.text = "💭"
                thinkingSpinnerView.visibility = View.VISIBLE
                // 头顶转圈动画
                chatStateAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
                    duration = THINKING_SPIN_MS
                    repeatCount = ValueAnimator.INFINITE
                    repeatMode = ValueAnimator.RESTART
                    interpolator = android.view.animation.LinearInterpolator()
                    addUpdateListener { anim ->
                        thinkingSpinnerView.rotation = anim.animatedValue as Float
                    }
                    start()
                }
            }

            ChatState.SPEAKING -> {
                // 说话中：嘴巴动作模拟（通过缩放弹跳模拟说话）
                setExpression(AvatarExpression.THINKING)
                thinkingSpinnerView.visibility = View.GONE
                // 说话嘴部动画：上下轻微缩放模拟张嘴闭嘴
                chatStateAnimator = ValueAnimator.ofFloat(1f, 1.06f, 1f, 0.96f, 1f).apply {
                    duration = 800L
                    repeatCount = ValueAnimator.INFINITE
                    repeatMode = ValueAnimator.RESTART
                    interpolator = android.view.animation.LinearInterpolator()
                    addUpdateListener { anim ->
                        val scale = anim.animatedValue as Float
                        this@FloatingAvatarView.scaleY = scale
                    }
                    start()
                }
            }

            ChatState.LISTENING -> {
                // 倾听中：头部倾斜 + 认真表情
                setExpression(AvatarExpression.NEUTRAL)
                thinkingSpinnerView.visibility = View.GONE
                // 头部倾斜动画
                this.animate()
                    .rotation(LISTENING_TILT_DEGREES)
                    .setDuration(300)
                    .start()
            }

            ChatState.HAPPY -> {
                // 开心：跳动动画 + 开心表情
                setExpression(AvatarExpression.HAPPY)
                thinkingSpinnerView.visibility = View.GONE
                // 持续跳动
                chatStateAnimator = ValueAnimator.ofFloat(0f, -dpToPx(10).toFloat(), 0f).apply {
                    duration = HAPPY_BOUNCE_MS
                    repeatCount = ValueAnimator.INFINITE
                    repeatMode = ValueAnimator.RESTART
                    interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                    addUpdateListener { anim ->
                        this@FloatingAvatarView.translationY = anim.animatedValue as Float
                    }
                    start()
                }
            }
        }
    }

    /**
     * 取消当前聊天状态动画，恢复默认位置
     */
    private fun cancelChatStateAnimation() {
        chatStateAnimator?.cancel()
        chatStateAnimator = null
        chatStateHandler.removeCallbacksAndMessages(null)
        // 恢复位置和旋转
        this.animate()
            .rotation(0f)
            .translationY(0f)
            .scaleY(1f)
            .setDuration(200)
            .start()
    }

    // ============================================================
    // 自定义外观API
    // ============================================================

    /**
     * 加载自定义图片作为精灵外观
     */
    private fun applyCustomImage(imagePath: String?) {
        if (imagePath == null || imagePath.isBlank()) {
            customImageView.visibility = View.GONE
            emojiView.visibility = View.VISIBLE
            isUsingCustomImage = false
        } else {
            try {
                val file = File(imagePath)
                if (file.exists()) {
                    val bitmap = BitmapFactory.decodeFile(imagePath)
                    if (bitmap != null) {
                        val shapedBitmap = cropToShape(bitmap, avatarConfig.shape)
                        customImageView.setImageBitmap(shapedBitmap)
                        customImageView.visibility = View.VISIBLE
                        emojiView.visibility = View.GONE
                        isUsingCustomImage = true
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "[自定义图片] 加载失败: ${e.message}")
                customImageView.visibility = View.GONE
                emojiView.visibility = View.VISIBLE
                isUsingCustomImage = false
            }
        }
    }

    /**
     * 从本地视频文件提取第一帧作为精灵外观
     */
    private fun applyCustomVideo(videoPath: String?) {
        if (videoPath == null || videoPath.isBlank()) return
        try {
            val file = File(videoPath)
            if (!file.exists()) {
                Log.w(TAG, "[自定义视频] 文件不存在: $videoPath")
                return
            }
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(videoPath)
                val frameBitmap = retriever.getFrameAtTime(
                    0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                )
                if (frameBitmap != null) {
                    val shapedBitmap = cropToShape(frameBitmap, avatarConfig.shape)
                    customImageView.setImageBitmap(shapedBitmap)
                    customImageView.visibility = View.VISIBLE
                    emojiView.visibility = View.GONE
                    isUsingCustomImage = true
                    Log.i(TAG, "[自定义视频] 成功提取第一帧: ${frameBitmap.width}x${frameBitmap.height}")
                }
            } finally {
                retriever.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "[自定义视频] 加载失败: ${e.message}", e)
        }
    }

    /**
     * 加载自定义3D模型作为精灵外观（基础3D渲染效果模拟）
     * 支持 .glb / .obj 文件
     *
     * 当前实现：使用2D绘制模拟3D效果（旋转、光影、透视）
     * 未来接入步骤（待接入真实3D引擎）：
     * 1. 引入 Google Filament 或 Sceneform 渲染引擎
     * 2. 创建 SurfaceView 用于3D渲染
     * 3. 加载 .glb/.obj 模型文件
     * 4. 设置材质和纹理
     * 5. 根据ChatState切换骨骼动画
     * 6. 替换当前emoji/2D渲染为3D渲染
     *
     * @param modelPath 3D模型文件路径（.glb 或 .obj）
     */
    private fun applyCustom3DModel(modelPath: String?) {
        if (modelPath == null || modelPath.isBlank()) {
            isUsingCustomImage = false
            customImageView.visibility = View.GONE
            emojiView.visibility = View.VISIBLE
            Log.i(TAG, "[3D模型] 已清除3D模型外观")
            return
        }

        val file = File(modelPath)
        if (!file.exists()) {
            Log.w(TAG, "[3D模型] 文件不存在: $modelPath")
            return
        }

        val extension = file.extension.lowercase()
        if (extension !in listOf("glb", "obj")) {
            Log.w(TAG, "[3D模型] 不支持的文件格式: $extension（仅支持 .glb / .obj）")
            return
        }

        // 基础3D渲染效果模拟：使用Canvas绘制模拟3D精灵
        Log.i(TAG, "[3D模型] 加载3D模型（效果模拟）: ${file.name}")

        val simWidth = dpToPx(80)
        val simHeight = dpToPx(80)
        val simBitmap = Bitmap.createBitmap(simWidth, simHeight, Bitmap.Config.ARGB_8888)
        val simCanvas = android.graphics.Canvas(simBitmap)

        // 背景：深色渐变模拟3D空间
        val bgGradient = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                Color.argb(255, 20, 20, 40),
                Color.argb(255, 40, 40, 80)
            )
        )
        bgGradient.setBounds(0, 0, simWidth, simHeight)
        bgGradient.draw(simCanvas)

        // 中心：绘制模拟3D模型（根据文件类型选择不同造型）
        val centerX = simWidth / 2f
        val centerY = simHeight / 2f
        val radius = simWidth * 0.35f

        // 绘制3D球体光照效果
        val spherePaint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.FILL
            shader = RadialGradient(
                centerX - radius * 0.3f, centerY - radius * 0.3f, radius,
                Color.argb(255, 120, 160, 255),
                Color.argb(255, 30, 60, 120),
                Shader.TileMode.CLAMP
            )
        }
        simCanvas.drawCircle(centerX, centerY, radius, spherePaint)

        // 高光
        val highlightPaint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.FILL
            shader = RadialGradient(
                centerX - radius * 0.2f, centerY - radius * 0.3f, radius * 0.3f,
                Color.argb(180, 255, 255, 255),
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP
            )
        }
        simCanvas.drawCircle(centerX - radius * 0.2f, centerY - radius * 0.3f, radius * 0.3f, highlightPaint)

        // 底部阴影
        val shadowPaint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.FILL
            color = Color.argb(60, 0, 0, 0)
        }
        simCanvas.drawOval(centerX - radius * 0.8f, simHeight * 0.88f, centerX + radius * 0.8f, simHeight * 0.95f, shadowPaint)

        // 文件类型标识
        val labelPaint = Paint().apply {
            isAntiAlias = true
            color = Color.argb(200, 255, 255, 255)
            textSize = dpToPx(10).toFloat()
            textAlign = Paint.Align.CENTER
        }
        simCanvas.drawText(".${extension.uppercase()}", centerX, simHeight * 0.5f, labelPaint)

        // 设置到自定义图片视图（裁剪为配置形状）
        val shapedBitmap = cropToShape(simBitmap, avatarConfig.shape)
        customImageView.setImageBitmap(shapedBitmap)
        customImageView.visibility = View.VISIBLE
        emojiView.visibility = View.GONE
        isUsingCustomImage = true

        // 启动3D模拟旋转动画
        start3DSimulationAnimation()

        Log.i(TAG, "[3D模型] 3D效果模拟已应用: ${file.name}")
    }

    /**
     * 启动3D模拟旋转动画
     * 通过轻微的3D透视旋转来模拟模型旋转效果
     */
    private fun start3DSimulationAnimation() {
        perspectiveAnimator?.cancel()
        perspectiveAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 8000L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { anim ->
                val angle = anim.animatedValue as Float
                // 轻微Y轴旋转模拟
                val rotY = sin(Math.toRadians(angle.toDouble())).toFloat() * 8f
                this@FloatingAvatarView.rotationY = rotY
                // 轻微缩放呼吸效果
                val breathe = 1f + sin(Math.toRadians(angle.toDouble() * 2)).toFloat() * 0.03f
                if (!isDragging) {
                    this@FloatingAvatarView.scaleX = breathe
                    this@FloatingAvatarView.scaleY = breathe
                }
            }
            start()
        }
    }

    private fun applyShape(shape: AvatarShape) {
        customImageView.clipToOutline = true
        customImageView.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                when (shape) {
                    AvatarShape.CIRCLE -> {
                        val size = min(view.width, view.height)
                        outline.setOval(0, 0, size, size)
                    }
                    AvatarShape.SQUARE -> outline.setRect(0, 0, view.width, view.height)
                    AvatarShape.ROUNDED_SQUARE -> outline.setRect(0, 0, view.width, view.height)
                    AvatarShape.HEXAGON -> outline.setRect(0, 0, view.width, view.height)
                }
            }
        }
    }

    // ============================================================
    // 情绪联动API
    // ============================================================

    fun updateEmotionColor(emotionState: EmotionalState) {
        val baseColor = if (avatarConfig.glowColor != null) {
            avatarConfig.glowColor!!
        } else {
            emotionToColor(emotionState)
        }
        if (baseColor != currentEmotionColor) {
            currentEmotionColor = baseColor
            haloView.background = createHaloDrawable(baseColor)
            val dotColor = when {
                emotionState.valence > 0.3 -> Color.GREEN
                emotionState.valence < -0.3 -> Color.RED
                else -> Color.YELLOW
            }
            statusDotView.background = createStatusDotDrawable(dotColor)
        }
    }

    /**
     * 从 AvatarExpressionEngine 的 ExpressionFrame 更新精灵表情
     *
     * 将52维BlendShape权重映射为视觉表现：
     * - 嘴巴形状 → 表情emoji选择
     * - 眼睛开合 → emoji缩放
     * - 眉毛位置 → 额外表情叠加
     * - 瞳孔大小 → 光环强度
     * - 注视方向 → 精灵微偏移
     *
     * 此方法与 AvatarExpressionEngine.outputFrameFlow 联动，
     * 由 FloatingAvatarService 订阅后调用
     */
    fun updateFromExpressionFrame(frame: ExpressionFrame) {
        // ── 嘴巴综合指标 → 表情映射 ──
        val smile = (frame.getBlendShape(BlendShape.MOUTH_SMILE_LEFT) +
                frame.getBlendShape(BlendShape.MOUTH_SMILE_RIGHT)) / 2f
        val frown = (frame.getBlendShape(BlendShape.MOUTH_FROWN_LEFT) +
                frame.getBlendShape(BlendShape.MOUTH_FROWN_RIGHT)) / 2f
        val jawOpen = frame.getBlendShape(BlendShape.JAW_OPEN)
        val browDown = (frame.getBlendShape(BlendShape.BROW_DOWN_LEFT) +
                frame.getBlendShape(BlendShape.BROW_DOWN_RIGHT)) / 2f
        val eyeWide = (frame.getBlendShape(BlendShape.EYE_WIDE_LEFT) +
                frame.getBlendShape(BlendShape.EYE_WIDE_RIGHT)) / 2f
        val eyeBlink = (frame.getBlendShape(BlendShape.EYE_BLINK_LEFT) +
                frame.getBlendShape(BlendShape.EYE_BLINK_RIGHT)) / 2f

        // ── 综合判定表情 ──
        val targetExpression = when {
            smile > 0.5f && jawOpen > 0.4f -> AvatarExpression.EXCITED
            smile > 0.3f -> AvatarExpression.HAPPY
            frown > 0.3f && browDown > 0.4f -> AvatarExpression.ANGRY
            frown > 0.3f -> AvatarExpression.SAD
            eyeWide > 0.5f -> AvatarExpression.SURPRISED
            browDown > 0.3f -> AvatarExpression.THINKING
            eyeBlink > 0.5f -> AvatarExpression.SLEEPY
            else -> AvatarExpression.NEUTRAL
        }
        setExpression(targetExpression, animate = true)

        // ── 瞳孔大小 → 光环强度 ──
        val haloAlpha = 0.3f + frame.pupilSize * 0.5f
        haloView.alpha = haloAlpha

        // ── 注视方向 → 精灵微偏移 ──
        val gazeOffsetX = frame.gazeYaw * dpToPx(3)
        val gazeOffsetY = frame.gazePitch * dpToPx(2)
        emojiView.translationX = gazeOffsetX
        emojiView.translationY = gazeOffsetY

        // ── 嘴型张合 → emoji纵向缩放 ──
        if (jawOpen > 0.1f) {
            val scaleY = 1f + jawOpen * 0.15f
            emojiView.scaleY = scaleY
        }

        // ── 眨眼 → emoji快速缩放模拟 ──
        if (eyeBlink > 0.6f) {
            emojiView.scaleY = 1f - eyeBlink * 0.3f
        }
    }

    fun updateFromState(valence: Double, arousal: Double, cognitiveLoad: Double, isSpeaking: Boolean) {
        this.isSpeaking = isSpeaking
        val expr = AvatarExpression.fromEmotionState(valence, arousal, cognitiveLoad, isSpeaking)
        setExpression(expr)
        syncBehaviorAction(isSpeaking, cognitiveLoad, arousal)
        val emotionState = EmotionalState(valence = valence, arousal = arousal)
        updateEmotionColor(emotionState)
    }

    private fun syncBehaviorAction(isSpeaking: Boolean, cognitiveLoad: Double, arousal: Double) {
        when {
            isSpeaking -> {
                applyHandAction(HandAction.NONE)
                applyOverallAction(OverallAction.GREETING)
            }
            cognitiveLoad > 0.6 -> {
                applyHandAction(HandAction.FIST_SALUTE)
                applyOverallAction(OverallAction.MEDITATING)
            }
            arousal > 0.7 -> {
                applyHandAction(HandAction.HEART)
                applyOverallAction(OverallAction.DANCING)
            }
            arousal < 0.2 -> {
                applyFootAction(FootAction.SITTING)
                applyOverallAction(OverallAction.DAYDREAMING)
            }
            else -> {
                applyHandAction(HandAction.NONE)
                applyOverallAction(OverallAction.IDLE)
            }
        }
    }

    // ============================================================
    // 想法气泡
    // ============================================================

    fun showThoughtBubble(thought: String = "") {
        val now = System.currentTimeMillis()
        if (now - lastBubbleTime < BUBBLE_MIN_INTERVAL_MS) return
        lastBubbleTime = now
        val displayText = thought.ifEmpty { thoughtCandidates.random() }
        showBubbleInternal(displayText, BUBBLE_AUTO_HIDE_MS)
    }

    /**
     * 显示消息气泡弹窗（供外部广播触发）
     *
     * 与 showThoughtBubble 不同：
     * - 不受最小间隔限制，立即显示
     * - 支持更长的文本内容（最多5行）
     * - 可自定义显示时长
     * - 点击气泡可提前关闭
     *
     * @param message 消息内容
     * @param durationMs 显示时长（毫秒），默认5秒
     */
    fun showMessageBubble(message: String, durationMs: Long = BUBBLE_AUTO_HIDE_MS) {
        // 先取消已有气泡
        bubbleHandler.removeCallbacksAndMessages(null)
        if (bubbleView.visibility == View.VISIBLE) {
            bubbleView.visibility = View.GONE
            bubbleView.animate().cancel()
        }
        lastBubbleTime = System.currentTimeMillis()
        showBubbleInternal(message, durationMs, isMessage = true)
    }

    /**
     * 气泡显示的内部实现
     */
    private fun showBubbleInternal(text: String, durationMs: Long, isMessage: Boolean = false) {
        bubbleView.text = text
        bubbleView.maxLines = if (isMessage) 5 else 2
        bubbleView.visibility = View.VISIBLE
        bubbleView.alpha = 0f
        bubbleView.setOnClickListener {
            // 点击气泡提前关闭
            hideThoughtBubble()
        }
        bubbleView.animate()
            .alpha(1f).translationY(-dpToPx(4).toFloat()).setDuration(300)
            .withEndAction {
                bubbleHandler.postDelayed({ hideThoughtBubble() }, durationMs)
            }.start()
    }

    fun hideThoughtBubble() {
        if (bubbleView.visibility == View.VISIBLE) {
            bubbleView.animate()
                .alpha(0f).translationY(0f).setDuration(200)
                .withEndAction { bubbleView.visibility = View.GONE }
                .start()
        }
    }

    fun triggerRandomThought() {
        if (bubbleView.visibility == View.GONE) showThoughtBubble()
    }

    // ============================================================
    // 拖动趣味提示
    // ============================================================

    private fun showDragHint() {
        dragHintView.text = dragHints.random()
        dragHintView.visibility = View.VISIBLE
        dragHintView.alpha = 0f
        dragHintView.animate().alpha(1f).setDuration(200).start()
        dragHintHandler.removeCallbacksAndMessages(null)
        dragHintHandler.postDelayed({ hideDragHint() }, DRAG_HINT_DURATION_MS)
    }

    private fun hideDragHint() {
        if (dragHintView.visibility == View.VISIBLE) {
            dragHintView.animate().alpha(0f).setDuration(200)
                .withEndAction { dragHintView.visibility = View.GONE }.start()
        }
    }

    // ============================================================
    // 边缘转向
    // ============================================================

    private fun checkEdgeTurn() {
        val wmParams = layoutParams as? WindowManager.LayoutParams ?: return
        val screenWidth = context.resources.displayMetrics.widthPixels
        val viewWidth = width
        if (wmParams.x < viewWidth / 2) {
            playEdgeTurnAnimation(true)
        } else if (wmParams.x > screenWidth - viewWidth * 1.5) {
            playEdgeTurnAnimation(false)
        }
    }

    private fun playEdgeTurnAnimation(flipRight: Boolean) {
        val startScaleX = if (flipRight) -1f else 1f
        val endScaleX = if (flipRight) 1f else -1f
        this.scaleX = startScaleX
        this.animate().scaleX(endScaleX).setDuration(EDGE_TURN_ANIM_MS.toLong())
            .withEndAction { this.scaleX = 1f }.start()
    }

    // ============================================================
    // 待机动作系统
    // ============================================================

    private val idleActionRunnable = object : Runnable {
        override fun run() {
            if (!isDragging && visibility == View.VISIBLE) {
                val action = idleActionPool.random()
                action.run()
            }
            val interval = (IDLE_ACTION_BASE_INTERVAL_MS / avatarConfig.personality.actionFrequency).toLong()
            idleActionHandler.postDelayed(this, interval)
        }
    }

    private fun scheduleIdleAction() {
        idleActionHandler.removeCallbacks(idleActionRunnable)
        val interval = (IDLE_ACTION_BASE_INTERVAL_MS / avatarConfig.personality.actionFrequency).toLong()
        idleActionHandler.postDelayed(idleActionRunnable, interval)
    }

    private fun playIdleAnimation(type: String) {
        when (type) {
            "bounce" -> {
                this.animate().translationY(-dpToPx(8).toFloat()).setDuration(200)
                    .withEndAction { this.animate().translationY(0f).setDuration(200).start() }.start()
            }
            "sway" -> {
                this.animate().rotation(8f).setDuration(300)
                    .withEndAction {
                        this.animate().rotation(-8f).setDuration(300)
                            .withEndAction { this.animate().rotation(0f).setDuration(200).start() }.start()
                    }.start()
            }
            "spin" -> {
                this.animate().rotation(360f).setDuration(600)
                    .withEndAction { this.rotation = 0f }.start()
            }
            "peek" -> {
                this.animate().scaleX(1.15f).scaleY(0.9f).setDuration(200)
                    .withEndAction { this.animate().scaleX(1f).scaleY(1f).setDuration(200).start() }.start()
            }
            "stretch" -> {
                this.animate().scaleX(0.9f).scaleY(1.15f).setDuration(300)
                    .withEndAction { this.animate().scaleX(1f).scaleY(1f).setDuration(200).start() }.start()
            }
            "nod" -> {
                this.animate().translationY(dpToPx(4).toFloat()).setDuration(150)
                    .withEndAction { this.animate().translationY(0f).setDuration(150).start() }.start()
            }
            "wiggle" -> {
                this.animate().rotation(5f).setDuration(100)
                    .withEndAction {
                        this.animate().rotation(-5f).setDuration(100)
                            .withEndAction { this.animate().rotation(0f).setDuration(100).start() }.start()
                    }.start()
            }
        }
    }

    private fun playOverallActionAnimation(action: OverallAction) {
        when (action) {
            OverallAction.GREETING -> {
                this.animate().rotation(10f).setDuration(200)
                    .withEndAction {
                        this.animate().rotation(-10f).setDuration(200)
                            .withEndAction { this.animate().rotation(0f).setDuration(200).start() }.start()
                    }.start()
            }
            OverallAction.DANCING -> {
                this.animate().rotation(12f).translationY(-dpToPx(6).toFloat()).setDuration(300)
                    .withEndAction {
                        this.animate().rotation(-12f).translationY(0f).setDuration(300)
                            .withEndAction { this.animate().rotation(0f).setDuration(200).start() }.start()
                    }.start()
            }
            OverallAction.MEDITATING -> {
                this.animate().scaleX(1.05f).scaleY(1.05f).setDuration(800)
                    .withEndAction { this.animate().scaleX(1f).scaleY(1f).setDuration(800).start() }.start()
            }
            OverallAction.READING -> {
                this.animate().translationY(dpToPx(3).toFloat()).setDuration(400)
                    .withEndAction { this.animate().translationY(0f).setDuration(400).start() }.start()
            }
            OverallAction.DAYDREAMING -> {
                this.animate().rotation(5f).setDuration(1000)
                    .withEndAction {
                        this.animate().rotation(-5f).setDuration(1000)
                            .withEndAction { this.animate().rotation(0f).setDuration(500).start() }.start()
                    }.start()
            }
            OverallAction.WAVING_GOODBYE -> {
                this.animate().scaleX(0.8f).scaleY(0.8f).alpha(0.6f).setDuration(400)
                    .withEndAction {
                        this.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(400).start()
                    }.start()
            }
            OverallAction.IDLE -> { }
        }
    }

    // ============================================================
    // 尺寸与透明度
    // ============================================================

    fun setFloatingSize(size: FloatingSize) {
        if (currentSize == size) return
        currentSize = size
        val basePx = dpToPx(size.dp)
        val scaledPx = (basePx * avatarConfig.age.scaleMultiplier * avatarConfig.sizeMultiplier).toInt()
        val params = layoutParams
        if (params != null) {
            params.width = scaledPx
            params.height = scaledPx
            try {
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                wm.updateViewLayout(this, params)
            } catch (_: Exception) { }
        }
        emojiView.setTextSize(TypedValue.COMPLEX_UNIT_SP, size.emojiSizeSp * avatarConfig.age.scaleMultiplier)
    }

    /**
     * 设置自定义悬浮窗尺寸（像素值）
     *
     * 用户可在设置中指定精灵悬浮窗的精确宽高（px）。
     * 此方法直接覆盖 FloatingSize 的默认尺寸计算。
     * 传入 widthPx=0 或 heightPx=0 时恢复为默认尺寸。
     *
     * @param widthPx 宽度（像素），0 表示使用默认
     * @param heightPx 高度（像素），0 表示使用默认
     */
    fun setCustomSizePx(widthPx: Int, heightPx: Int) {
        val params = layoutParams ?: return
        if (widthPx > 0 && heightPx > 0) {
            params.width = widthPx
            params.height = heightPx
        } else {
            // 恢复默认尺寸
            val basePx = dpToPx(currentSize.dp)
            val scaledPx = (basePx * avatarConfig.age.scaleMultiplier * avatarConfig.sizeMultiplier).toInt()
            params.width = scaledPx
            params.height = scaledPx
        }
        try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.updateViewLayout(this, params)
        } catch (_: Exception) { }
        // 根据尺寸调整emoji大小
        val minDim = min(params.width, params.height)
        val spScale = minDim.toFloat() / dpToPx(currentSize.dp).toFloat()
        emojiView.setTextSize(TypedValue.COMPLEX_UNIT_SP, currentSize.emojiSizeSp * spScale * avatarConfig.age.scaleMultiplier)
    }

    fun setFloatingAlpha(alpha: Float) {
        currentAlpha = alpha.coerceIn(0.1f, 1.0f)
        this.alpha = currentAlpha
    }

    fun setBubbleEnabled(enabled: Boolean) {
        if (!enabled) {
            hideThoughtBubble()
            bubbleHandler.removeCallbacksAndMessages(null)
        }
    }

    fun release() {
        haloAnimator?.cancel()
        perspectiveAnimator?.cancel()
        chatStateAnimator?.cancel()
        idleActionHandler.removeCallbacksAndMessages(null)
        bubbleHandler.removeCallbacksAndMessages(null)
        dragHintHandler.removeCallbacksAndMessages(null)
        chatStateHandler.removeCallbacksAndMessages(null)
    }

    // ============================================================
    // 图片裁剪工具
    // ============================================================

    private fun cropToShape(bitmap: Bitmap, shape: AvatarShape): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val size = min(width, height)
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint().apply {
            isAntiAlias = true
            setShader(BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
        }
        when (shape) {
            AvatarShape.CIRCLE -> canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
            AvatarShape.SQUARE -> canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
            AvatarShape.ROUNDED_SQUARE -> {
                val rect = RectF(0f, 0f, size.toFloat(), size.toFloat())
                canvas.drawRoundRect(rect, size / 6f, size / 6f, paint)
            }
            AvatarShape.HEXAGON -> canvas.drawPath(createHexagonPath(size), paint)
        }
        return output
    }

    private fun createHexagonPath(size: Int): Path {
        val path = Path()
        val cx = size / 2f
        val cy = size / 2f
        val radius = size / 2f * 0.9f
        for (i in 0 until 6) {
            val angle = Math.toRadians((60.0 * i - 30.0))
            val x = cx + radius * cos(angle).toFloat()
            val y = cy + radius * sin(angle).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        return path
    }

    // ============================================================
    // 内部绘制工具
    // ============================================================

    private fun startHaloBreathing() {
        haloAnimator = ValueAnimator.ofFloat(0.4f, 0.8f, 0.4f).apply {
            duration = HALO_BREATHE_MS
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { animator ->
                haloView.alpha = animator.animatedValue as Float
            }
            start()
        }
    }

    private fun createHaloDrawable(color: Int): GradientDrawable {
        return GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(color, Color.TRANSPARENT)
        ).apply {
            shape = GradientDrawable.OVAL
            gradientType = GradientDrawable.RADIAL_GRADIENT
            gradientRadius = dpToPx(40).toFloat()
            colors = intArrayOf(
                Color.argb(100, Color.red(color), Color.green(color), Color.blue(color)),
                Color.argb(30, Color.red(color), Color.green(color), Color.blue(color)),
                Color.TRANSPARENT
            )
        }
    }

    private fun createBubbleBackground(): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(12).toFloat()
            setColor(Color.argb(200, 40, 40, 50))
            setStroke(dpToPx(1), Color.argb(80, 255, 255, 255))
        }
    }

    private fun createDragHintBackground(): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(8).toFloat()
            setColor(Color.argb(220, 200, 80, 80))
            setStroke(dpToPx(1), Color.argb(100, 255, 200, 200))
        }
    }

    private fun createStatusDotDrawable(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(dpToPx(1), Color.argb(120, 255, 255, 255))
        }
    }

    private fun emotionToColor(state: EmotionalState): Int {
        val v = state.valence.coerceIn(-1.0, 1.0)
        val a = state.arousal.coerceIn(0.0, 1.0)
        return when {
            v > 0.3 && a > 0.6 -> Color.argb(200, 255, 180, 60)
            v < -0.3 && a > 0.6 -> Color.argb(200, 220, 60, 60)
            v < -0.3 && a <= 0.4 -> Color.argb(180, 40, 60, 120)
            v > 0.3 && a <= 0.3 -> Color.argb(180, 100, 200, 220)
            v >= 0.0 -> Color.argb(180, 74, 144, 217)
            else -> Color.argb(180, 120, 80, 160)
        }
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), context.resources.displayMetrics
        ).toInt()
    }
}
