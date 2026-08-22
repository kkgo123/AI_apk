/*
 * ============================================================
 * AvatarAnimator - 化身骨骼动画系统
 * ============================================================
 *
 * 负责化身全身骨骼动画的驱动与管理：
 *
 * 功能：
 * 1. 骨骼层级树管理（根→脊柱→头→四肢）
 * 2. 关键帧动画插值（线性 / 贝塞尔 / 弹性）
 * 3. 动画混合与叠加（基础层 + 动作层 + 表情层）
 * 4. 循环动画驱动（呼吸、待机动、环境反应）
 * 5. 动作触发与过渡（打招呼、思考、行走等）
 *
 * 骨骼数据结构：
 *   每根骨骼 = 位置(x,y,z) + 旋转四元数(qx,qy,qz,qw) + 缩放(x,y,z)
 *   骨骼变换 = 父变换 × 本地变换（正向运动学）
 * ============================================================
 */
package com.kkgo.mindsoul.avatar

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.*

/**
 * 三维向量
 */
data class Vec3(
    var x: Float = 0f,
    var y: Float = 0f,
    var z: Float = 0f
) {
    operator fun plus(other: Vec3) = Vec3(x + other.x, y + other.y, z + other.z)
    operator fun minus(other: Vec3) = Vec3(x - other.x, y - other.y, z - other.z)
    operator fun times(scalar: Float) = Vec3(x * scalar, y * scalar, z * scalar)

    fun length(): Float = sqrt(x * x + y * y + z * z)

    fun normalized(): Vec3 {
        val len = length()
        return if (len > 0.0001f) Vec3(x / len, y / len, z / len) else Vec3()
    }

    companion object {
        val ZERO = Vec3(0f, 0f, 0f)
        val UP = Vec3(0f, 1f, 0f)
        val FORWARD = Vec3(0f, 0f, 1f)
    }
}

/**
 * 四元数旋转
 *
 * 用于表示骨骼的旋转状态，避免万向锁问题
 */
data class Quaternion(
    var x: Float = 0f,
    var y: Float = 0f,
    var z: Float = 0f,
    var w: Float = 1f
) {
    companion object {
        /** 单位四元数（无旋转） */
        val IDENTITY = Quaternion(0f, 0f, 0f, 1f)

        /**
         * 从欧拉角创建（弧度）
         */
        fun fromEuler(pitch: Float, yaw: Float, roll: Float): Quaternion {
            val cp = cos(pitch * 0.5f); val sp = sin(pitch * 0.5f)
            val cy = cos(yaw * 0.5f);   val sy = sin(yaw * 0.5f)
            val cr = cos(roll * 0.5f);  val sr = sin(roll * 0.5f)

            return Quaternion(
                x = sr * cp * cy - cr * sp * sy,
                y = cr * sp * cy + sr * cp * sy,
                z = cr * cp * sy - sr * sp * cy,
                w = cr * cp * cy + sr * sp * sy
            )
        }

        /**
         * 球面线性插值（SLERP）
         * 用于两个旋转之间的平滑过渡
         */
        fun slerp(a: Quaternion, b: Quaternion, t: Float): Quaternion {
            val clampedT = t.coerceIn(0f, 1f)

            // 计算点积
            var dot = a.x * b.x + a.y * b.y + a.z * b.z + a.w * b.w

            // 确保走最短路径
            val target = if (dot < 0) {
                dot = -dot
                Quaternion(-b.x, -b.y, -b.z, -b.w)
            } else {
                b
            }

            // 角度很小时使用线性插值（避免除零）
            return if (dot > 0.9995f) {
                Quaternion(
                    x = a.x + (target.x - a.x) * clampedT,
                    y = a.y + (target.y - a.y) * clampedT,
                    z = a.z + (target.z - a.z) * clampedT,
                    w = a.w + (target.w - a.w) * clampedT
                ).normalized()
            } else {
                val theta0 = acos(dot.coerceIn(-1f, 1f))
                val theta = theta0 * clampedT
                val sinTheta = sin(theta)
                val sinTheta0 = sin(theta0)

                val s0 = cos(theta) - dot * sinTheta / sinTheta0
                val s1 = sinTheta / sinTheta0

                Quaternion(
                    x = a.x * s0 + target.x * s1,
                    y = a.y * s0 + target.y * s1,
                    z = a.z * s0 + target.z * s1,
                    w = a.w * s0 + target.w * s1
                ).normalized()
            }
        }
    }

    operator fun times(other: Quaternion): Quaternion {
        return Quaternion(
            x = w * other.x + x * other.w + y * other.z - z * other.y,
            y = w * other.y - x * other.z + y * other.w + z * other.x,
            z = w * other.z + x * other.y - y * other.x + z * other.w,
            w = w * other.w - x * other.x - y * other.y - z * other.z
        )
    }

    fun normalized(): Quaternion {
        val len = sqrt(x * x + y * y + z * z + w * w)
        return if (len > 0.0001f) Quaternion(x / len, y / len, z / len, w / len) else IDENTITY
    }
}

/**
 * 骨骼变换数据
 */
data class BoneTransform(
    /** 本地位置 */
    val position: Vec3 = Vec3.ZERO,
    /** 本地旋转（四元数） */
    val rotation: Quaternion = Quaternion.IDENTITY,
    /** 本地缩放 */
    val scale: Vec3 = Vec3(1f, 1f, 1f)
) {
    companion object {
        val IDENTITY = BoneTransform()
    }
}

/**
 * 骨骼节点
 *
 * 构成骨骼树的节点，每个骨骼持有本地变换
 */
data class BoneNode(
    /** 骨骼名称 */
    val name: String,
    /** 骨骼ID */
    val id: Int,
    /** 父骨骼ID（-1 为根骨骼） */
    val parentId: Int = -1,
    /** 本地变换（静止姿态） */
    val restTransform: BoneTransform = BoneTransform.IDENTITY,
    /** 当前动画变换 */
    var animatedTransform: BoneTransform = BoneTransform.IDENTITY,
    /** 子骨骼ID列表 */
    val childIds: MutableList<Int> = mutableListOf()
)

/**
 * 关键帧
 */
data class Keyframe(
    /** 时间（秒） */
    val time: Float,
    /** 骨骼变换 */
    val transform: BoneTransform,
    /** 插值类型 */
    val interpolation: InterpolationType = InterpolationType.LINEAR
)

/**
 * 插值类型
 */
enum class InterpolationType {
    /** 线性插值 */
    LINEAR,
    /** 贝塞尔曲线插值 */
    BEZIER,
    /** 弹性插值 */
    SPRING,
    /** 阶梯插值（无过渡） */
    STEP
}

/**
 * 动画剪辑
 *
 * 一段完整的骨骼动画数据
 */
data class AnimationClip(
    /** 动画名称 */
    val name: String,
    /** 总时长（秒） */
    val duration: Float,
    /** 是否循环 */
    val isLooping: Boolean = false,
    /** 每根骨骼的关键帧轨道 Map<骨骼名, 关键帧列表> */
    val tracks: Map<String, List<Keyframe>> = emptyMap(),
    /** 动画优先级（混合时使用） */
    val priority: Int = 0
)

/**
 * 动画层
 */
enum class AnimationLayer {
    /** 基础层（待机/行走等全身动画） */
    BASE,
    /** 动作层（手势/交互等上半身动画） */
    ACTION,
    /** 表情层（由 ExpressionEngine 驱动） */
    EXPRESSION
}

/**
 * 预定义动画动作
 */
enum class AvatarAction(val clipName: String, val displayName: String) {
    IDLE("idle", "待机"),
    IDLE_BREATHING("idle_breathing", "呼吸待机动"),
    WALK("walk", "行走"),
    WAVE("wave", "挥手"),
    THINK("think", "思考"),
    TALK("talk", "说话"),
    HAPPY("happy", "高兴"),
    SAD("sad_pose", "悲伤"),
    ANGRY("angry_pose", "愤怒"),
    SURPRISED("surprised_pose", "惊讶"),
    SLEEP("sleep", "睡眠"),
    WAKE_UP("wake_up", "醒来"),
    BOW("bow", "鞠躬"),
    LOOK_AROUND("look_around", "环顾四周");
}

/**
 * 化身骨骼动画器
 *
 * 管理骨骼树和动画播放
 */
class AvatarAnimator {

    companion object {
        private const val TAG = "AvatarAnimator"
        /** 动画更新频率（FPS） */
        const val ANIMATION_FPS = 30
        /** 动画过渡时间（秒） */
        const val TRANSITION_DURATION = 0.3f
    }

    // ============ 骨骼树 ============
    /** 所有骨骼 Map<ID, BoneNode> */
    private val bones = mutableMapOf<Int, BoneNode>()
    /** 骨骼名→ID 映射 */
    private val boneNameMap = mutableMapOf<String, Int>()
    /** 根骨骼ID */
    private var rootBoneId = -1

    // ============ 动画状态 ============
    /** 当前基础层动画 */
    private var baseClip: AnimationClip? = null
    /** 当前动作层动画 */
    private var actionClip: AnimationClip? = null
    /** 基础层播放时间（秒） */
    private var baseTime = 0f
    /** 动作层播放时间 */
    private var actionTime = 0f
    /** 动作层权重 [0, 1]（用于与基础层混合） */
    private var actionWeight = 0f
    /** 动作层目标权重 */
    private var actionTargetWeight = 0f

    // ============ 过渡状态 ============
    /** 是否在过渡中 */
    private var isTransitioning = false
    /** 过渡进度 [0, 1] */
    private var transitionProgress = 0f
    /** 过渡前姿态 */
    private var transitionFromPoses = mutableMapOf<Int, BoneTransform>()

    // ============ 输出姿态 ============
    /** 最终骨骼姿态（供渲染使用） */
    private val _finalPose = MutableStateFlow<Map<Int, BoneTransform>>(emptyMap())
    val finalPoseFlow: StateFlow<Map<Int, BoneTransform>> = _finalPose.asStateFlow()

    // ============ 初始化 ============

    /**
     * 构建默认人体骨骼树
     *
     * 标准人形骨骼结构：
     *   Root
     *   ├─ Hips
     *   │  ├─ Spine → Spine1 → Spine2
     *   │  │  └─ Neck → Head
     *   │  ├─ LeftUpperArm → LeftLowerArm → LeftHand
     *   │  └─ RightUpperArm → RightLowerArm → RightHand
     *   ├─ LeftUpperLeg → LeftLowerLeg → LeftFoot
     *   └─ RightUpperLeg → RightLowerLeg → RightFoot
     */
    fun buildDefaultHumanoidSkeleton() {
        bones.clear()
        boneNameMap.clear()

        var id = 0
        fun addBone(name: String, parentId: Int, restPos: Vec3 = Vec3.ZERO): Int {
            val boneId = id++
            val bone = BoneNode(name, boneId, parentId, BoneTransform(restPos))
            bones[boneId] = bone
            boneNameMap[name] = boneId

            // 添加到父骨骼的子列表
            if (parentId >= 0) {
                bones[parentId]?.childIds?.add(boneId)
            }
            return boneId
        }

        // 构建骨骼层级
        val root = addBone("Root", -1)
        rootBoneId = root

        val hips = addBone("Hips", root, Vec3(0f, 1f, 0f))
        val spine = addBone("Spine", hips, Vec3(0f, 0.15f, 0f))
        val spine1 = addBone("Spine1", spine, Vec3(0f, 0.15f, 0f))
        val spine2 = addBone("Spine2", spine1, Vec3(0f, 0.15f, 0f))
        val neck = addBone("Neck", spine2, Vec3(0f, 0.15f, 0f))
        val head = addBone("Head", neck, Vec3(0f, 0.1f, 0f))

        // 左臂
        val lUpperArm = addBone("LeftUpperArm", spine2, Vec3(-0.15f, 0.1f, 0f))
        val lLowerArm = addBone("LeftLowerArm", lUpperArm, Vec3(-0.25f, 0f, 0f))
        val lHand = addBone("LeftHand", lLowerArm, Vec3(-0.2f, 0f, 0f))

        // 右臂
        val rUpperArm = addBone("RightUpperArm", spine2, Vec3(0.15f, 0.1f, 0f))
        val rLowerArm = addBone("RightLowerArm", rUpperArm, Vec3(0.25f, 0f, 0f))
        val rHand = addBone("RightHand", rLowerArm, Vec3(0.2f, 0f, 0f))

        // 左腿
        val lUpperLeg = addBone("LeftUpperLeg", hips, Vec3(-0.1f, 0f, 0f))
        val lLowerLeg = addBone("LeftLowerLeg", lUpperLeg, Vec3(0f, -0.4f, 0f))
        val lFoot = addBone("LeftFoot", lLowerLeg, Vec3(0f, -0.4f, 0f))

        // 右腿
        val rUpperLeg = addBone("RightUpperLeg", hips, Vec3(0.1f, 0f, 0f))
        val rLowerLeg = addBone("RightLowerLeg", rUpperLeg, Vec3(0f, -0.4f, 0f))
        val rFoot = addBone("RightFoot", rLowerLeg, Vec3(0f, -0.4f, 0f))

        Log.i(TAG, "[骨架] 默认人形骨架已构建: ${bones.size} 根骨骼")
    }

    /**
     * 加载自定义骨骼（从导入的模型数据）
     *
     * @param boneData 骨骼数据列表
     */
    fun loadCustomSkeleton(boneData: List<CustomBoneData>) {
        bones.clear()
        boneNameMap.clear()

        for (data in boneData) {
            val bone = BoneNode(
                name = data.name,
                id = data.id,
                parentId = data.parentId,
                restTransform = BoneTransform(data.position, data.rotation, data.scale)
            )
            bones[data.id] = bone
            boneNameMap[data.name] = data.id
        }

        // 构建父子关系
        for (bone in bones.values) {
            if (bone.parentId >= 0 && bone.parentId != -1) {
                bones[bone.parentId]?.childIds?.add(bone.id)
            }
        }

        rootBoneId = boneData.firstOrNull { it.parentId == -1 }?.id ?: 0
        Log.i(TAG, "[骨架] 自定义骨架已加载: ${bones.size} 根骨骼")
    }

    // ============ 动画控制 ============

    /**
     * 播放动作
     *
     * @param action 动作类型
     * @param loop 是否循环
     * @param layer 动画层
     */
    fun playAction(action: AvatarAction, loop: Boolean = false, layer: AnimationLayer = AnimationLayer.ACTION) {
        val clip = generateBuiltinClip(action, loop)
        when (layer) {
            AnimationLayer.BASE -> {
                startTransition()
                baseClip = clip
                baseTime = 0f
            }
            AnimationLayer.ACTION -> {
                actionClip = clip
                actionTime = 0f
                actionTargetWeight = 1f
            }
            AnimationLayer.EXPRESSION -> {
                // 表情层由 ExpressionEngine 管理
                Log.d(TAG, "[动画] 表情层由 ExpressionEngine 管理")
            }
        }
        Log.i(TAG, "[动画] 播放: ${action.displayName} | 层: $layer | 循环: $loop")
    }

    /**
     * 停止动作层动画
     */
    fun stopAction() {
        actionTargetWeight = 0f
    }

    /**
     * 推进动画帧
     *
     * @param deltaTime 帧间隔（秒）
     */
    fun updateAnimation(deltaTime: Float) {
        // ── 更新动作层权重过渡 ──
        if (abs(actionWeight - actionTargetWeight) > 0.01f) {
            actionWeight += (actionTargetWeight - actionWeight) * minOf(deltaTime * 8f, 1f)
        } else {
            actionWeight = actionTargetWeight
            if (actionTargetWeight == 0f) {
                actionClip = null
            }
        }

        // ── 更新过渡 ──
        if (isTransitioning) {
            transitionProgress += deltaTime / TRANSITION_DURATION
            if (transitionProgress >= 1f) {
                transitionProgress = 1f
                isTransitioning = false
            }
        }

        // ── 基础层动画播放 ──
        if (baseClip != null) {
            baseTime += deltaTime
            if (baseTime >= (baseClip?.duration ?: 0f)) {
                if (baseClip?.isLooping == true) {
                    baseTime %= baseClip?.duration ?: 1f
                } else {
                    baseTime = baseClip?.duration ?: 0f
                }
            }
        }

        // ── 动作层动画播放 ──
        if (actionClip != null && actionWeight > 0.01f) {
            actionTime += deltaTime
            if (actionTime >= (actionClip?.duration ?: 0f)) {
                if (actionClip?.isLooping == true) {
                    actionTime %= actionClip?.duration ?: 1f
                } else {
                    actionTime = actionClip?.duration ?: 0f
                    actionTargetWeight = 0f  // 非循环动画播放完自动淡出
                }
            }
        }

        // ── 计算最终姿态 ──
        computeFinalPose()
    }

    // ============ 内部方法 ============

    /**
     * 开始过渡
     */
    private fun startTransition() {
        transitionFromPoses.clear()
        for ((id, bone) in bones) {
            transitionFromPoses[id] = bone.animatedTransform
        }
        isTransitioning = true
        transitionProgress = 0f
    }

    /**
     * 计算最终骨骼姿态
     */
    private fun computeFinalPose() {
        val result = mutableMapOf<Int, BoneTransform>()

        for ((id, bone) in bones) {
            var transform = bone.restTransform

            // 基础层动画
            val basePose = sampleClip(baseClip, baseTime, bone.name)
            if (basePose != null) {
                transform = basePose
            }

            // 动作层叠加（按权重混合）
            if (actionClip != null && actionWeight > 0.01f) {
                val actionPose = sampleClip(actionClip, actionTime, bone.name)
                if (actionPose != null) {
                    transform = blendTransforms(transform, actionPose, actionWeight)
                }
            }

            // 过渡混合
            if (isTransitioning) {
                val fromPose = transitionFromPoses[id]
                if (fromPose != null) {
                    transform = blendTransforms(fromPose, transform, transitionProgress)
                }
            }

            bone.animatedTransform = transform
            result[id] = transform
        }

        _finalPose.value = result
    }

    /**
     * 从动画剪辑中采样指定骨骼在指定时间的姿态
     */
    private fun sampleClip(clip: AnimationClip?, time: Float, boneName: String): BoneTransform? {
        if (clip == null) return null
        val track = clip.tracks[boneName] ?: return null
        if (track.isEmpty()) return null

        // 找到当前时间所在的关键帧区间
        if (track.size == 1) return track[0].transform

        // 找到前一个和后一个关键帧
        var prevKf = track[0]
        var nextKf = track[track.size - 1]

        for (i in 0 until track.size - 1) {
            if (time >= track[i].time && time <= track[i + 1].time) {
                prevKf = track[i]
                nextKf = track[i + 1]
                break
            }
        }

        // 计算区间内插值因子
        val segmentDuration = nextKf.time - prevKf.time
        val t = if (segmentDuration > 0.001f) {
            ((time - prevKf.time) / segmentDuration).coerceIn(0f, 1f)
        } else {
            0f
        }

        // 根据插值类型计算
        return interpolateTransform(prevKf, nextKf, t)
    }

    /**
     * 关键帧插值
     */
    private fun interpolateTransform(prev: Keyframe, next: Keyframe, t: Float): BoneTransform {
        val adjustedT = when (prev.interpolation) {
            InterpolationType.LINEAR -> t
            InterpolationType.BEZIER -> cubicBezierEase(t)
            InterpolationType.SPRING -> springEase(t)
            InterpolationType.STEP -> if (t < 1f) 0f else 1f
        }

        return BoneTransform(
            position = Vec3(
                x = prev.transform.position.x + (next.transform.position.x - prev.transform.position.x) * adjustedT,
                y = prev.transform.position.y + (next.transform.position.y - prev.transform.position.y) * adjustedT,
                z = prev.transform.position.z + (next.transform.position.z - prev.transform.position.z) * adjustedT
            ),
            rotation = Quaternion.slerp(prev.transform.rotation, next.transform.rotation, adjustedT),
            scale = Vec3(
                x = prev.transform.scale.x + (next.transform.scale.x - prev.transform.scale.x) * adjustedT,
                y = prev.transform.scale.y + (next.transform.scale.y - prev.transform.scale.y) * adjustedT,
                z = prev.transform.scale.z + (next.transform.scale.z - prev.transform.scale.z) * adjustedT
            )
        )
    }

    /**
     * 混合两个骨骼变换
     */
    private fun blendTransforms(a: BoneTransform, b: BoneTransform, weight: Float): BoneTransform {
        val w = weight.coerceIn(0f, 1f)
        return BoneTransform(
            position = Vec3(
                x = a.position.x + (b.position.x - a.position.x) * w,
                y = a.position.y + (b.position.y - a.position.y) * w,
                z = a.position.z + (b.position.z - a.position.z) * w
            ),
            rotation = Quaternion.slerp(a.rotation, b.rotation, w),
            scale = Vec3(
                x = a.scale.x + (b.scale.x - a.scale.x) * w,
                y = a.scale.y + (b.scale.y - a.scale.y) * w,
                z = a.scale.z + (b.scale.z - a.scale.z) * w
            )
        )
    }

    /**
     * 三次贝塞尔缓动函数
     */
    private fun cubicBezierEase(t: Float): Float {
        // 简化的 ease-in-out 贝塞尔
        return t * t * (3f - 2f * t)
    }

    /**
     * 弹性缓动函数
     */
    private fun springEase(t: Float): Float {
        return 1f - exp(-6f * t) * cos(8f * t)
    }

    /**
     * 生成内置动画剪辑
     */
    private fun generateBuiltinClip(action: AvatarAction, loop: Boolean): AnimationClip {
        return when (action) {
            AvatarAction.IDLE_BREATHING -> createBreathingClip(loop = true)
            AvatarAction.WAVE -> createWaveClip()
            AvatarAction.THINK -> createThinkClip(loop = true)
            AvatarAction.TALK -> createTalkClip(loop = true)
            AvatarAction.WALK -> createWalkClip(loop = true)
            AvatarAction.BOW -> createBowClip()
            AvatarAction.LOOK_AROUND -> createLookAroundClip(loop = true)
            else -> AnimationClip(action.clipName, 2f, loop, emptyMap())
        }
    }

    // ── 内置动画生成 ──

    /**
     * 呼吸待机动动画
     * 脊柱微微上下起伏 + 手臂自然摆动
     */
    private fun createBreathingClip(loop: Boolean = true): AnimationClip {
        val duration = 4f
        val tracks = mutableMapOf<String, List<Keyframe>>()

        // 脊柱微微前倾/后仰
        tracks["Spine"] = listOf(
            Keyframe(0f, BoneTransform(rotation = Quaternion.fromEuler(0.01f, 0f, 0f))),
            Keyframe(duration / 2, BoneTransform(rotation = Quaternion.fromEuler(-0.01f, 0f, 0f))),
            Keyframe(duration, BoneTransform(rotation = Quaternion.fromEuler(0.01f, 0f, 0f)))
        )

        // 头部微微点头
        tracks["Head"] = listOf(
            Keyframe(0f, BoneTransform(rotation = Quaternion.fromEuler(0.005f, 0f, 0f))),
            Keyframe(duration / 2, BoneTransform(rotation = Quaternion.fromEuler(-0.005f, 0f, 0f))),
            Keyframe(duration, BoneTransform(rotation = Quaternion.fromEuler(0.005f, 0f, 0f)))
        )

        // 肩膀微动
        tracks["LeftUpperArm"] = listOf(
            Keyframe(0f, BoneTransform(rotation = Quaternion.fromEuler(0f, 0f, 0.01f))),
            Keyframe(duration / 2, BoneTransform(rotation = Quaternion.fromEuler(0f, 0f, -0.01f))),
            Keyframe(duration, BoneTransform(rotation = Quaternion.fromEuler(0f, 0f, 0.01f)))
        )
        tracks["RightUpperArm"] = listOf(
            Keyframe(0f, BoneTransform(rotation = Quaternion.fromEuler(0f, 0f, -0.01f))),
            Keyframe(duration / 2, BoneTransform(rotation = Quaternion.fromEuler(0f, 0f, 0.01f))),
            Keyframe(duration, BoneTransform(rotation = Quaternion.fromEuler(0f, 0f, -0.01f)))
        )

        return AnimationClip("idle_breathing", duration, loop, tracks)
    }

    /**
     * 挥手动画
     */
    private fun createWaveClip(): AnimationClip {
        val duration = 2f
        val tracks = mutableMapOf<String, List<Keyframe>>()

        // 右臂抬起
        tracks["RightUpperArm"] = listOf(
            Keyframe(0f, BoneTransform(rotation = Quaternion.fromEuler(0f, 0f, 0f))),
            Keyframe(0.5f, BoneTransform(rotation = Quaternion.fromEuler(-1.2f, 0f, -1.5f))),
            Keyframe(1.8f, BoneTransform(rotation = Quaternion.fromEuler(-1.2f, 0f, -1.5f))),
            Keyframe(duration, BoneTransform(rotation = Quaternion.fromEuler(0f, 0f, 0f)))
        )

        // 右前臂弯曲+左右摆动
        tracks["RightLowerArm"] = listOf(
            Keyframe(0f, BoneTransform(rotation = Quaternion.fromEuler(0f, 0f, 0f))),
            Keyframe(0.5f, BoneTransform(rotation = Quaternion.fromEuler(-1.0f, 0f, 0f))),
            Keyframe(0.8f, BoneTransform(rotation = Quaternion.fromEuler(-1.0f, 0.3f, 0f))),
            Keyframe(1.1f, BoneTransform(rotation = Quaternion.fromEuler(-1.0f, -0.3f, 0f))),
            Keyframe(1.4f, BoneTransform(rotation = Quaternion.fromEuler(-1.0f, 0.3f, 0f))),
            Keyframe(1.8f, BoneTransform(rotation = Quaternion.fromEuler(-1.0f, 0f, 0f))),
            Keyframe(duration, BoneTransform(rotation = Quaternion.fromEuler(0f, 0f, 0f)))
        )

        return AnimationClip("wave", duration, false, tracks)
    }

    /**
     * 思考动画
     */
    private fun createThinkClip(loop: Boolean = true): AnimationClip {
        val duration = 3f
        val tracks = mutableMapOf<String, List<Keyframe>>()

        // 右手抬到下巴
        tracks["RightUpperArm"] = listOf(
            Keyframe(0f, BoneTransform()),
            Keyframe(1f, BoneTransform(rotation = Quaternion.fromEuler(-0.8f, 0.2f, -0.5f))),
            Keyframe(duration, BoneTransform(rotation = Quaternion.fromEuler(-0.8f, 0.2f, -0.5f)))
        )
        tracks["RightLowerArm"] = listOf(
            Keyframe(0f, BoneTransform()),
            Keyframe(1f, BoneTransform(rotation = Quaternion.fromEuler(-1.5f, 0f, 0f))),
            Keyframe(duration, BoneTransform(rotation = Quaternion.fromEuler(-1.5f, 0f, 0f)))
        )

        // 头微微偏向右手
        tracks["Head"] = listOf(
            Keyframe(0f, BoneTransform()),
            Keyframe(1f, BoneTransform(rotation = Quaternion.fromEuler(0.05f, 0.1f, 0.08f))),
            Keyframe(duration, BoneTransform(rotation = Quaternion.fromEuler(0.05f, 0.1f, 0.08f)))
        )

        return AnimationClip("think", duration, loop, tracks)
    }

    /**
     * 说话动画（下颌开合）
     */
    private fun createTalkClip(loop: Boolean = true): AnimationClip {
        val duration = 1f
        val tracks = mutableMapOf<String, List<Keyframe>>()

        tracks["Head"] = listOf(
            Keyframe(0f, BoneTransform(rotation = Quaternion.fromEuler(0.03f, 0f, 0f))),
            Keyframe(0.25f, BoneTransform(rotation = Quaternion.fromEuler(-0.02f, 0.02f, 0f))),
            Keyframe(0.5f, BoneTransform(rotation = Quaternion.fromEuler(0.03f, -0.01f, 0f))),
            Keyframe(0.75f, BoneTransform(rotation = Quaternion.fromEuler(-0.02f, 0.01f, 0f))),
            Keyframe(duration, BoneTransform(rotation = Quaternion.fromEuler(0.03f, 0f, 0f)))
        )

        return AnimationClip("talk", duration, loop, tracks)
    }

    /**
     * 行走动画
     */
    private fun createWalkClip(loop: Boolean = true): AnimationClip {
        val duration = 1f
        val tracks = mutableMapOf<String, List<Keyframe>>()

        val swingAngle = 0.4f
        tracks["LeftUpperLeg"] = listOf(
            Keyframe(0f, BoneTransform(rotation = Quaternion.fromEuler(swingAngle, 0f, 0f))),
            Keyframe(duration / 2, BoneTransform(rotation = Quaternion.fromEuler(-swingAngle, 0f, 0f))),
            Keyframe(duration, BoneTransform(rotation = Quaternion.fromEuler(swingAngle, 0f, 0f)))
        )
        tracks["RightUpperLeg"] = listOf(
            Keyframe(0f, BoneTransform(rotation = Quaternion.fromEuler(-swingAngle, 0f, 0f))),
            Keyframe(duration / 2, BoneTransform(rotation = Quaternion.fromEuler(swingAngle, 0f, 0f))),
            Keyframe(duration, BoneTransform(rotation = Quaternion.fromEuler(-swingAngle, 0f, 0f)))
        )
        tracks["LeftUpperArm"] = listOf(
            Keyframe(0f, BoneTransform(rotation = Quaternion.fromEuler(-0.3f, 0f, 0f))),
            Keyframe(duration / 2, BoneTransform(rotation = Quaternion.fromEuler(0.3f, 0f, 0f))),
            Keyframe(duration, BoneTransform(rotation = Quaternion.fromEuler(-0.3f, 0f, 0f)))
        )
        tracks["RightUpperArm"] = listOf(
            Keyframe(0f, BoneTransform(rotation = Quaternion.fromEuler(0.3f, 0f, 0f))),
            Keyframe(duration / 2, BoneTransform(rotation = Quaternion.fromEuler(-0.3f, 0f, 0f))),
            Keyframe(duration, BoneTransform(rotation = Quaternion.fromEuler(0.3f, 0f, 0f)))
        )

        return AnimationClip("walk", duration, loop, tracks)
    }

    /**
     * 鞠躬动画
     */
    private fun createBowClip(): AnimationClip {
        val duration = 2f
        val tracks = mutableMapOf<String, List<Keyframe>>()

        tracks["Spine"] = listOf(
            Keyframe(0f, BoneTransform()),
            Keyframe(0.6f, BoneTransform(rotation = Quaternion.fromEuler(0.5f, 0f, 0f))),
            Keyframe(1.4f, BoneTransform(rotation = Quaternion.fromEuler(0.5f, 0f, 0f))),
            Keyframe(duration, BoneTransform())
        )
        tracks["Head"] = listOf(
            Keyframe(0f, BoneTransform()),
            Keyframe(0.6f, BoneTransform(rotation = Quaternion.fromEuler(0.3f, 0f, 0f))),
            Keyframe(1.4f, BoneTransform(rotation = Quaternion.fromEuler(0.3f, 0f, 0f))),
            Keyframe(duration, BoneTransform())
        )

        return AnimationClip("bow", duration, false, tracks)
    }

    /**
     * 环顾四周动画
     */
    private fun createLookAroundClip(loop: Boolean = true): AnimationClip {
        val duration = 6f
        val tracks = mutableMapOf<String, List<Keyframe>>()

        tracks["Head"] = listOf(
            Keyframe(0f, BoneTransform()),
            Keyframe(1f, BoneTransform(rotation = Quaternion.fromEuler(0f, -0.5f, 0f))),
            Keyframe(2f, BoneTransform(rotation = Quaternion.fromEuler(0.1f, -0.5f, 0f))),
            Keyframe(3f, BoneTransform(rotation = Quaternion.fromEuler(0f, 0.5f, 0f))),
            Keyframe(4f, BoneTransform(rotation = Quaternion.fromEuler(-0.1f, 0.5f, 0f))),
            Keyframe(5f, BoneTransform(rotation = Quaternion.fromEuler(0.05f, 0f, 0f))),
            Keyframe(duration, BoneTransform())
        )

        return AnimationClip("look_around", duration, loop, tracks)
    }
}

/**
 * 自定义骨骼数据（用于导入外部模型）
 */
data class CustomBoneData(
    val id: Int,
    val name: String,
    val parentId: Int,
    val position: Vec3,
    val rotation: Quaternion,
    val scale: Vec3
)
