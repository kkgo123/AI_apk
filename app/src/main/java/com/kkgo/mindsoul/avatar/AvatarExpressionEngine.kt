/*
 * ============================================================
 * AvatarExpressionEngine - 表情引擎
 * ============================================================
 *
 * 将实时心智状态映射为面部表情参数：
 *
 * 输入：
 *   - 元认知快照（注意力、情绪、认知负荷）
 *   - 心智模式（仆从/自治）
 *   - 进化阶段
 *
 * 输出：
 *   - BlendShape 权重向量（52维面部肌肉参数）
 *   - 眼球运动参数（注视方向、瞳孔大小）
 *   - 嘴型参数（音素同步）
 *
 * 表情过渡采用弹簧-阻尼系统：
 *   F = -k·(x - target) - c·v
 *   其中 k = 弹性系数，c = 阻尼系数
 * ============================================================
 */
package com.kkgo.mindsoul.avatar

import android.util.Log
import com.kkgo.mindsoul.model.EmotionalState
import com.kkgo.mindsoul.model.MetacognitionSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 面部混合变形维度（52维BlendShape）
 *
 * 对应 ARKit / Android FaceBlendShape 标准
 */
enum class BlendShape(val index: Int, val displayName: String) {
    // ── 眉毛区域 ──
    BROW_DOWN_LEFT(0, "左眉下压"),
    BROW_DOWN_RIGHT(1, "右眉下压"),
    BROW_INNER_UP_LEFT(2, "左眉内上"),
    BROW_INNER_UP_RIGHT(3, "右眉内上"),
    BROW_OUTER_UP_LEFT(4, "左眉外上"),
    BROW_OUTER_UP_RIGHT(5, "右眉外上"),

    // ── 眼睛区域 ──
    EYE_BLINK_LEFT(6, "左眼眨眼"),
    EYE_BLINK_RIGHT(7, "右眼眨眼"),
    EYE_WIDE_LEFT(8, "左眼睁大"),
    EYE_WIDE_RIGHT(9, "右眼睁大"),
    EYE_SQUINT_LEFT(10, "左眼眯起"),
    EYE_SQUINT_RIGHT(11, "右眼眯起"),
    EYE_LOOK_DOWN_LEFT(12, "左眼下视"),
    EYE_LOOK_DOWN_RIGHT(13, "右眼下视"),
    EYE_LOOK_IN_LEFT(14, "左眼内视"),
    EYE_LOOK_IN_RIGHT(15, "右眼内视"),
    EYE_LOOK_OUT_LEFT(16, "左眼外视"),
    EYE_LOOK_OUT_RIGHT(17, "右眼外视"),
    EYE_LOOK_UP_LEFT(18, "左眼上视"),
    EYE_LOOK_UP_RIGHT(19, "右眼上视"),

    // ── 脸颊区域 ──
    CHEEK_PUFF(20, "脸颊鼓起"),
    CHEEK_SQUINT_LEFT(21, "左脸颊眯"),
    CHEEK_SQUINT_RIGHT(22, "右脸颊眯"),

    // ── 鼻子区域 ──
    NOSE_SNEER_LEFT(23, "左鼻翼皱"),
    NOSE_SNEER_RIGHT(24, "右鼻翼皱"),

    // ── 嘴巴区域 ──
    JAW_OPEN(25, "下颌张开"),
    JAW_FORWARD(26, "下颌前伸"),
    JAW_LEFT(27, "下颌左移"),
    JAW_RIGHT(28, "下颌右移"),
    MOUTH_FUNNEL(29, "嘴巴漏斗"),
    MOUTH_PUCKER(30, "嘴巴嘟起"),
    MOUTH_LEFT(31, "嘴巴左移"),
    MOUTH_RIGHT(32, "嘴巴右移"),
    MOUTH_SMILE_LEFT(33, "左嘴角微笑"),
    MOUTH_SMILE_RIGHT(34, "右嘴角微笑"),
    MOUTH_FROWN_LEFT(35, "左嘴角下撇"),
    MOUTH_FROWN_RIGHT(36, "右嘴角下撇"),
    MOUTH_DIMPLES_LEFT(37, "左嘴角酒窝"),
    MOUTH_DIMPLES_RIGHT(38, "右嘴角酒窝"),
    MOUTH_STRETCH_LEFT(39, "左嘴角拉伸"),
    MOUTH_STRETCH_RIGHT(40, "右嘴角拉伸"),
    MOUTH_PRESS_LEFT(41, "左嘴压紧"),
    MOUTH_PRESS_RIGHT(42, "右嘴压紧"),
    MOUTH_LOWER_DOWN_LEFT(43, "左下唇下降"),
    MOUTH_LOWER_DOWN_RIGHT(44, "右下唇下降"),
    MOUTH_UPPER_UP_LEFT(45, "左上唇上升"),
    MOUTH_UPPER_UP_RIGHT(46, "右上唇上升"),
    MOUTH_CLOSE(47, "嘴巴闭合"),
    MOUTH_SHRUG_UPPER(48, "上唇耸起"),
    MOUTH_SHRUG_LOWER(49, "下唇耸起"),
    MOUTH_ROLL_LOWER(50, "下唇内卷"),
    TONGUE_OUT(51, "舌头伸出");

    companion object {
        /** 总维度数 */
        const val DIMENSION = 52

        /**
         * 从索引获取枚举
         */
        fun fromIndex(index: Int): BlendShape? =
            entries.find { it.index == index }
    }
}

/**
 * 面部表情帧数据
 *
 * 包含一帧的完整面部参数
 */
data class ExpressionFrame(
    /** 52维混合变形权重 [0.0, 1.0] */
    val blendWeights: FloatArray = FloatArray(BlendShape.DIMENSION),
    /** 眼球注视方向 (yaw, pitch) 弧度 */
    var gazeYaw: Float = 0f,
    var gazePitch: Float = 0f,
    /** 瞳孔大小 [0.0, 1.0] */
    var pupilSize: Float = 0.5f,
    /** 帧时间戳 */
    val timestamp: Long = System.currentTimeMillis()
) {
    /**
     * 设置指定 BlendShape 权重
     */
    fun setBlendShape(shape: BlendShape, value: Float) {
        blendWeights[shape.index] = value.coerceIn(0f, 1f)
    }

    /**
     * 获取指定 BlendShape 权重
     */
    fun getBlendShape(shape: BlendShape): Float {
        return blendWeights[shape.index]
    }

    /**
     * 线性插值到另一帧
     */
    fun lerpTo(target: ExpressionFrame, t: Float): ExpressionFrame {
        val clampedT = t.coerceIn(0f, 1f)
        val result = ExpressionFrame(timestamp = System.currentTimeMillis())
        for (i in blendWeights.indices) {
            result.blendWeights[i] = blendWeights[i] + (target.blendWeights[i] - blendWeights[i]) * clampedT
        }
        return result.copy(
            gazeYaw = gazeYaw + (target.gazeYaw - gazeYaw) * clampedT,
            gazePitch = gazePitch + (target.gazePitch - gazePitch) * clampedT,
            pupilSize = pupilSize + (target.pupilSize - pupilSize) * clampedT
        )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ExpressionFrame) return false
        return blendWeights.contentEquals(other.blendWeights)
    }

    override fun hashCode(): Int = blendWeights.contentHashCode()
}

/**
 * 预定义表情预设
 */
object ExpressionPresets {
    /** 中性表情 */
    fun neutral(): ExpressionFrame = ExpressionFrame()

    /** 微笑 */
    fun smile(): ExpressionFrame = ExpressionFrame().apply {
        setBlendShape(BlendShape.MOUTH_SMILE_LEFT, 0.7f)
        setBlendShape(BlendShape.MOUTH_SMILE_RIGHT, 0.7f)
        setBlendShape(BlendShape.CHEEK_SQUINT_LEFT, 0.3f)
        setBlendShape(BlendShape.CHEEK_SQUINT_RIGHT, 0.3f)
        setBlendShape(BlendShape.EYE_SQUINT_LEFT, 0.2f)
        setBlendShape(BlendShape.EYE_SQUINT_RIGHT, 0.2f)
    }

    /** 开心大笑 */
    fun laugh(): ExpressionFrame = ExpressionFrame().apply {
        setBlendShape(BlendShape.MOUTH_SMILE_LEFT, 1.0f)
        setBlendShape(BlendShape.MOUTH_SMILE_RIGHT, 1.0f)
        setBlendShape(BlendShape.JAW_OPEN, 0.6f)
        setBlendShape(BlendShape.MOUTH_FUNNEL, 0.3f)
        setBlendShape(BlendShape.EYE_SQUINT_LEFT, 0.5f)
        setBlendShape(BlendShape.EYE_SQUINT_RIGHT, 0.5f)
        setBlendShape(BlendShape.CHEEK_SQUINT_LEFT, 0.6f)
        setBlendShape(BlendShape.CHEEK_SQUINT_RIGHT, 0.6f)
    }

    /** 悲伤 */
    fun sad(): ExpressionFrame = ExpressionFrame().apply {
        setBlendShape(BlendShape.MOUTH_FROWN_LEFT, 0.6f)
        setBlendShape(BlendShape.MOUTH_FROWN_RIGHT, 0.6f)
        setBlendShape(BlendShape.BROW_INNER_UP_LEFT, 0.5f)
        setBlendShape(BlendShape.BROW_INNER_UP_RIGHT, 0.5f)
        setBlendShape(BlendShape.EYE_SQUINT_LEFT, 0.2f)
        setBlendShape(BlendShape.EYE_SQUINT_RIGHT, 0.2f)
    }

    /** 惊讶 */
    fun surprised(): ExpressionFrame = ExpressionFrame().apply {
        setBlendShape(BlendShape.EYE_WIDE_LEFT, 0.8f)
        setBlendShape(BlendShape.EYE_WIDE_RIGHT, 0.8f)
        setBlendShape(BlendShape.JAW_OPEN, 0.7f)
        setBlendShape(BlendShape.BROW_OUTER_UP_LEFT, 0.7f)
        setBlendShape(BlendShape.BROW_OUTER_UP_RIGHT, 0.7f)
    }

    /** 思考/沉思 */
    fun thinking(): ExpressionFrame = ExpressionFrame().apply {
        setBlendShape(BlendShape.BROW_DOWN_LEFT, 0.4f)
        setBlendShape(BlendShape.BROW_DOWN_RIGHT, 0.3f)
        setBlendShape(BlendShape.EYE_LOOK_UP_LEFT, 0.3f)
        setBlendShape(BlendShape.EYE_LOOK_OUT_LEFT, 0.2f)
        setBlendShape(BlendShape.MOUTH_PRESS_LEFT, 0.3f)
        pupilSize = 0.7f // 瞳孔放大
    }

    /** 愤怒 */
    fun angry(): ExpressionFrame = ExpressionFrame().apply {
        setBlendShape(BlendShape.BROW_DOWN_LEFT, 0.8f)
        setBlendShape(BlendShape.BROW_DOWN_RIGHT, 0.8f)
        setBlendShape(BlendShape.EYE_SQUINT_LEFT, 0.5f)
        setBlendShape(BlendShape.EYE_SQUINT_RIGHT, 0.5f)
        setBlendShape(BlendShape.JAW_FORWARD, 0.5f)
        setBlendShape(BlendShape.MOUTH_FROWN_LEFT, 0.4f)
        setBlendShape(BlendShape.MOUTH_FROWN_RIGHT, 0.4f)
        setBlendShape(BlendShape.NOSE_SNEER_LEFT, 0.3f)
        setBlendShape(BlendShape.NOSE_SNEER_RIGHT, 0.3f)
    }

    /** 困倦 */
    fun sleepy(): ExpressionFrame = ExpressionFrame().apply {
        setBlendShape(BlendShape.EYE_BLINK_LEFT, 0.7f)
        setBlendShape(BlendShape.EYE_BLINK_RIGHT, 0.7f)
        setBlendShape(BlendShape.JAW_OPEN, 0.3f)
        setBlendShape(BlendShape.MOUTH_FUNNEL, 0.2f)
    }

    /** 专注 */
    fun focused(): ExpressionFrame = ExpressionFrame().apply {
        setBlendShape(BlendShape.BROW_DOWN_LEFT, 0.3f)
        setBlendShape(BlendShape.BROW_DOWN_RIGHT, 0.3f)
        setBlendShape(BlendShape.EYE_SQUINT_LEFT, 0.15f)
        setBlendShape(BlendShape.EYE_SQUINT_RIGHT, 0.15f)
        setBlendShape(BlendShape.MOUTH_CLOSE, 0.5f)
        pupilSize = 0.4f  // 瞳孔缩小（专注）
    }

    /** 好奇 */
    fun curious(): ExpressionFrame = ExpressionFrame().apply {
        setBlendShape(BlendShape.EYE_WIDE_LEFT, 0.4f)
        setBlendShape(BlendShape.EYE_WIDE_RIGHT, 0.4f)
        setBlendShape(BlendShape.BROW_OUTER_UP_LEFT, 0.5f)
        setBlendShape(BlendShape.BROW_OUTER_UP_RIGHT, 0.4f)
        setBlendShape(BlendShape.MOUTH_SMILE_LEFT, 0.2f)
        setBlendShape(BlendShape.MOUTH_SMILE_RIGHT, 0.2f)
        pupilSize = 0.7f  // 瞳孔放大（好奇）
    }

    /**
     * 所有预设名称列表
     */
    fun presetNames(): List<String> =
        listOf("neutral", "smile", "laugh", "sad", "surprised",
            "thinking", "angry", "sleepy", "focused", "curious")

    /**
     * 根据名称获取预设
     */
    fun getPreset(name: String): ExpressionFrame {
        return when (name.lowercase()) {
            "neutral" -> neutral()
            "smile" -> smile()
            "laugh" -> laugh()
            "sad" -> sad()
            "surprised" -> surprised()
            "thinking" -> thinking()
            "angry" -> angry()
            "sleepy" -> sleepy()
            "focused" -> focused()
            "curious" -> curious()
            else -> neutral()
        }
    }
}

/**
 * 表情引擎 - 将心智状态映射为面部表情
 *
 * 核心功能：
 * 1. 情绪→表情映射（基于 Russell 环形模型）
 * 2. 认知状态→微表情（注意力、负荷→眉毛/眼神）
 * 3. 音素同步（TTS 播放时嘴型同步）
 * 4. 弹簧-阻尼过渡（自然表情切换）
 */
class AvatarExpressionEngine {

    companion object {
        private const val TAG = "AvatarExprEngine"

        /** 弹簧刚度系数 */
        private const val SPRING_STIFFNESS = 12.0f
        /** 阻尼系数 */
        private const val SPRING_DAMPING = 6.0f
        /** 帧率 */
        private const val FPS = 30
        /** 帧间隔（毫秒） */
        private const val FRAME_INTERVAL_MS = 1000L / FPS
    }

    // ============ 当前/目标表情帧 ============
    /** 当前渲染帧（弹簧系统输出） */
    private var currentFrame = ExpressionPresets.neutral()
    /** 目标帧（情绪映射输出） */
    private var targetFrame = ExpressionPresets.neutral()

    // ============ 弹簧-阻尼系统状态 ============
    /** 每维度的速度向量 */
    private var velocity = FloatArray(BlendShape.DIMENSION)
    /** 注视方向速度 */
    private var gazeVelocity = floatArrayOf(0f, 0f)
    /** 瞳孔大小速度 */
    private var pupilVelocity = 0f

    // ============ 输出状态流 ============
    private val _outputFrame = MutableStateFlow(currentFrame)
    /** 表情输出帧流（UI/渲染层订阅） */
    val outputFrameFlow: StateFlow<ExpressionFrame> = _outputFrame.asStateFlow()

    // ============ 音素同步 ============
    /** 当前是否在播放语音 */
    @Volatile
    private var isSpeaking = false
    /** 音素队列 */
    private val visemeQueue = java.util.concurrent.ConcurrentLinkedQueue<VisemeFrame>()

    // ============ 微表情随机生成 ============
    /** 上次微表情时间 */
    private var lastMicroExpressionTime = 0L
    /** 微表情间隔范围（毫秒） */
    private var microExprMinInterval = 3000L
    private var microExprMaxInterval = 8000L

    /**
     * 更新目标表情（由心智状态驱动）
     *
     * 根据元认知快照计算目标表情
     */
    fun updateFromMetacognition(snapshot: MetacognitionSnapshot) {
        val emotion = snapshot.emotionalState
        val attention = snapshot.attentionFocus
        val load = snapshot.cognitiveLoad

        // ── 基于 Russell 环形模型的情绪→表情映射 ──
        targetFrame = mapEmotionToExpression(emotion)

        // ── 注意力/认知负荷 → 微调眉毛和眼神 ──
        applyCognitiveOverlay(targetFrame, attention, load)

        Log.d(TAG, "[更新] 情绪(val=${String.format("%.2f", emotion.valence)}, " +
                "arousal=${String.format("%.2f", emotion.arousal)}) → 表情已更新")
    }

    /**
     * 直接设置表情预设
     */
    fun setPreset(presetName: String) {
        targetFrame = ExpressionPresets.getPreset(presetName)
        Log.d(TAG, "[预设] 设置表情: $presetName")
    }

    /**
     * 开始语音播放（启用音素同步）
     */
    fun startSpeaking(visemes: List<VisemeFrame>) {
        isSpeaking = true
        visemeQueue.clear()
        visemeQueue.addAll(visemes)
        Log.d(TAG, "[语音] 开始播放, ${visemes.size} 个音素帧")
    }

    /**
     * 停止语音播放
     */
    fun stopSpeaking() {
        isSpeaking = false
        visemeQueue.clear()
    }

    /**
     * 推进一帧（定时调用，30FPS）
     *
     * 弹簧-阻尼系统将当前帧弹向目标帧
     */
    fun tickFrame(): ExpressionFrame {
        val dt = 1f / FPS  // 帧间隔（秒）

        // ── 音素覆盖嘴型 ──
        var speakingOverride: ExpressionFrame? = null
        if (isSpeaking) {
            val viseme = visemeQueue.poll()
            if (viseme != null) {
                speakingOverride = visemeToExpression(viseme.viseme)
            } else {
                // 队列为空，停止说话
                isSpeaking = false
            }
        }

        // ── 弹簧-阻尼更新 ──
        for (i in 0 until BlendShape.DIMENSION) {
            // 如果有语音播放，嘴型维度以音素为目标
            val target = if (speakingOverride != null && isMouthDimension(i)) {
                speakingOverride.blendWeights[i]
            } else {
                targetFrame.blendWeights[i]
            }

            val current = currentFrame.blendWeights[i]

            // F = -k·(x - target) - c·v
            val displacement = current - target
            val springForce = -SPRING_STIFFNESS * displacement
            val dampingForce = -SPRING_DAMPING * velocity[i]
            val acceleration = springForce + dampingForce

            velocity[i] += acceleration * dt
            currentFrame.blendWeights[i] = (current + velocity[i] * dt).coerceIn(0f, 1f)
        }

        // ── 眼球运动弹簧 ──
        updateGazeSpring(dt)

        // ── 瞳孔弹簧 ──
        val pupilTarget = targetFrame.pupilSize
        val pupilDisp = currentFrame.pupilSize - pupilTarget
        val pupilAcc = -SPRING_STIFFNESS * pupilDisp - SPRING_DAMPING * pupilVelocity
        pupilVelocity += pupilAcc * dt
        currentFrame.pupilSize = (currentFrame.pupilSize + pupilVelocity * dt).coerceIn(0f, 1f)

        // ── 微表情注入 ──
        injectMicroExpression()

        // ── 输出 ──
        _outputFrame.value = currentFrame
        return currentFrame
    }

    // ============ 内部方法 ============

    /**
     * 情绪→表情映射（基于 Russell 环形模型）
     *
     * Russell 模型：
     *   X轴 = 效价(Valence)：负(不悦) ← → 正(愉悦)
     *   Y轴 = 唤醒度(Arousal)：低(平静) ← → 高(激动)
     *
     * 四个象限：
     *   (+V, +A) → 开心/兴奋
     *   (-V, +A) → 愤怒/恐惧
     *   (-V, -A) → 悲伤/沮丧
     *   (+V, -A) → 满足/平静
     */
    private fun mapEmotionToExpression(emotion: EmotionalState): ExpressionFrame {
        val v = emotion.valence    // [-1, 1]
        val a = emotion.arousal    // [0, 1]

        return when {
            // 高愉悦 + 高唤醒 → 开心/兴奋
            v > 0.3 && a > 0.6 -> {
                if (v > 0.7) ExpressionPresets.laugh()
                else ExpressionPresets.smile()
            }
            // 低愉悦 + 高唤醒 → 愤怒/惊讶
            v < -0.3 && a > 0.6 -> ExpressionPresets.angry()
            // 低愉悦 + 低唤醒 → 悲伤
            v < -0.3 && a <= 0.4 -> ExpressionPresets.sad()
            // 高愉悦 + 低唤醒 → 满足/困倦
            v > 0.3 && a <= 0.3 -> ExpressionPresets.sleepy()
            // 高唤醒（中性效价）→ 惊讶
            a > 0.7 -> ExpressionPresets.surprised()
            // 默认中性
            else -> ExpressionPresets.neutral()
        }
    }

    /**
     * 认知状态→表情叠加
     *
     * 注意力集中 → 眉毛微皱、眼神聚焦
     * 认知负荷高 → 额眉紧锁
     */
    private fun applyCognitiveOverlay(
        frame: ExpressionFrame,
        attention: Double,
        cognitiveLoad: Double
    ) {
        // 注意力集中 → 眉毛下压 + 眼神聚焦
        if (attention > 0.7) {
            val intensity = ((attention - 0.7) / 0.3).toFloat()  // [0, 1]
            frame.blendWeights[BlendShape.BROW_DOWN_LEFT.index] += intensity * 0.3f
            frame.blendWeights[BlendShape.BROW_DOWN_RIGHT.index] += intensity * 0.3f
            frame.blendWeights[BlendShape.EYE_SQUINT_LEFT.index] += intensity * 0.15f
            frame.blendWeights[BlendShape.EYE_SQUINT_RIGHT.index] += intensity * 0.15f
        }

        // 认知负荷高 → 额眉紧锁 + 嘴紧
        if (cognitiveLoad > 0.7) {
            val intensity = ((cognitiveLoad - 0.7) / 0.3).toFloat()
            frame.blendWeights[BlendShape.BROW_INNER_UP_LEFT.index] += intensity * 0.4f
            frame.blendWeights[BlendShape.BROW_INNER_UP_RIGHT.index] += intensity * 0.4f
            frame.blendWeights[BlendShape.MOUTH_PRESS_LEFT.index] = 
                (frame.blendWeights[BlendShape.MOUTH_PRESS_LEFT.index] + intensity * 0.3f).coerceIn(0f, 1f)
            frame.blendWeights[BlendShape.MOUTH_PRESS_RIGHT.index] = 
                (frame.blendWeights[BlendShape.MOUTH_PRESS_RIGHT.index] + intensity * 0.3f).coerceIn(0f, 1f)
        }

        // 限幅保护
        for (i in frame.blendWeights.indices) {
            frame.blendWeights[i] = frame.blendWeights[i].coerceIn(0f, 1f)
        }
    }

    /**
     * 更新眼球注视弹簧
     */
    private fun updateGazeSpring(dt: Float) {
        // Yaw 弹簧
        val yawDisp = currentFrame.gazeYaw - targetFrame.gazeYaw
        val yawAcc = -SPRING_STIFFNESS * yawDisp - SPRING_DAMPING * gazeVelocity[0]
        gazeVelocity[0] += yawAcc * dt
        currentFrame.gazeYaw += gazeVelocity[0] * dt

        // Pitch 弹簧
        val pitchDisp = currentFrame.gazePitch - targetFrame.gazePitch
        val pitchAcc = -SPRING_STIFFNESS * pitchDisp - SPRING_DAMPING * gazeVelocity[1]
        gazeVelocity[1] += pitchAcc * dt
        currentFrame.gazePitch += gazeVelocity[1] * dt
    }

    /**
     * 注入微表情
     *
     * 随机间隔插入自然微表情（眨眼、轻微嘴角动作等）
     */
    private fun injectMicroExpression() {
        val now = System.currentTimeMillis()
        val interval = now - lastMicroExpressionTime

        if (interval < microExprMinInterval) return

        // 随机概率触发
        val probability = (interval - microExprMinInterval).toFloat() /
                (microExprMaxInterval - microExprMinInterval)
        if (Math.random() > probability * 0.3) return  // 30% 概率触发

        lastMicroExpressionTime = now

        // 随机选择微表情类型
        when ((Math.random() * 4).toInt()) {
            0 -> { // 自然眨眼
                currentFrame.blendWeights[BlendShape.EYE_BLINK_LEFT.index] = 0.8f
                currentFrame.blendWeights[BlendShape.EYE_BLINK_RIGHT.index] = 0.8f
            }
            1 -> { // 轻微嘴角抽动
                val side = if (Math.random() > 0.5) 0.1f else 0f
                currentFrame.blendWeights[BlendShape.MOUTH_SMILE_LEFT.index] += side
                currentFrame.blendWeights[BlendShape.MOUTH_SMILE_RIGHT.index] += (0.1f - side)
            }
            2 -> { // 轻微挑眉
                currentFrame.blendWeights[BlendShape.BROW_OUTER_UP_LEFT.index] += 0.1f
            }
            3 -> { // 轻微注视偏移
                targetFrame.gazeYaw += ((Math.random() - 0.5) * 0.1).toFloat()
            }
        }
    }

    /**
     * 判断是否为嘴型维度
     */
    private fun isMouthDimension(index: Int): Boolean {
        return index >= BlendShape.JAW_OPEN.index && index <= BlendShape.TONGUE_OUT.index
    }

    /**
     * 音素→表情映射
     */
    private fun visemeToExpression(viseme: Viseme): ExpressionFrame {
        val frame = ExpressionFrame()
        when (viseme) {
            Viseme.A -> { // 啊
                frame.setBlendShape(BlendShape.JAW_OPEN, 0.6f)
                frame.setBlendShape(BlendShape.MOUTH_FUNNEL, 0.2f)
            }
            Viseme.E -> { // 诶
                frame.setBlendShape(BlendShape.JAW_OPEN, 0.3f)
                frame.setBlendShape(BlendShape.MOUTH_SMILE_LEFT, 0.3f)
                frame.setBlendShape(BlendShape.MOUTH_SMILE_RIGHT, 0.3f)
            }
            Viseme.I -> { // 衣
                frame.setBlendShape(BlendShape.JAW_OPEN, 0.15f)
                frame.setBlendShape(BlendShape.MOUTH_SMILE_LEFT, 0.5f)
                frame.setBlendShape(BlendShape.MOUTH_SMILE_RIGHT, 0.5f)
            }
            Viseme.O -> { // 喔
                frame.setBlendShape(BlendShape.JAW_OPEN, 0.4f)
                frame.setBlendShape(BlendShape.MOUTH_FUNNEL, 0.7f)
                frame.setBlendShape(BlendShape.MOUTH_PUCKER, 0.4f)
            }
            Viseme.U -> { // 乌
                frame.setBlendShape(BlendShape.JAW_OPEN, 0.15f)
                frame.setBlendShape(BlendShape.MOUTH_PUCKER, 0.7f)
                frame.setBlendShape(BlendShape.MOUTH_FUNNEL, 0.5f)
            }
            Viseme.B_P_M -> { // 波/坡/摸
                frame.setBlendShape(BlendShape.MOUTH_CLOSE, 0.6f)
                frame.setBlendShape(BlendShape.MOUTH_PUCKER, 0.2f)
            }
            Viseme.F_V -> { // 佛/维
                frame.setBlendShape(BlendShape.JAW_OPEN, 0.1f)
                frame.setBlendShape(BlendShape.MOUTH_UPPER_UP_LEFT, 0.3f)
                frame.setBlendShape(BlendShape.MOUTH_UPPER_UP_RIGHT, 0.3f)
            }
            Viseme.S_Z -> { // 思/日
                frame.setBlendShape(BlendShape.JAW_OPEN, 0.1f)
                frame.setBlendShape(BlendShape.MOUTH_SMILE_LEFT, 0.2f)
                frame.setBlendShape(BlendShape.MOUTH_SMILE_RIGHT, 0.2f)
            }
            Viseme.T_D -> { // 得/特
                frame.setBlendShape(BlendShape.JAW_OPEN, 0.2f)
                frame.setBlendShape(BlendShape.MOUTH_UPPER_UP_LEFT, 0.2f)
            }
            Viseme.L_N -> { // 了/呢
                frame.setBlendShape(BlendShape.JAW_OPEN, 0.25f)
                frame.setBlendShape(BlendShape.TONGUE_OUT, 0.2f)
            }
            Viseme.SILENCE -> { // 静默
                frame.setBlendShape(BlendShape.MOUTH_CLOSE, 0.3f)
            }
        }
        return frame
    }
}

/**
 * 音素（视觉音素 / Viseme）
 */
enum class Viseme {
    /** 啊 (a, ā) */
    A,
    /** 诶 (e, ē) */
    E,
    /** 衣 (i, ī) */
    I,
    /** 喔 (o, ō) */
    O,
    /** 乌 (u, ū) */
    U,
    /** 波/坡/摸 (b, p, m) */
    B_P_M,
    /** 佛/维 (f, v) */
    F_V,
    /** 思/日 (s, z, sh, zh) */
    S_Z,
    /** 得/特 (t, d) */
    T_D,
    /** 了/呢 (l, n) */
    L_N,
    /** 静默 */
    SILENCE
}

/**
 * 音素帧
 */
data class VisemeFrame(
    /** 音素类型 */
    val viseme: Viseme,
    /** 持续时间（毫秒） */
    val durationMs: Long,
    /** 起始时间（毫秒） */
    val startMs: Long = 0L
)
