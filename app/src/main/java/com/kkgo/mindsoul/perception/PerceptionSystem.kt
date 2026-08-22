/*
 * ============================================================
 * PerceptionSystem - 五感感知系统统一管理器
 * ============================================================
 *
 * 统一管理 MindSoul 人工生命的五大感知通道：
 *
 * 视觉（Vision）：
 *   - 全局屏幕截图识别
 *   - UI 控件检测
 *   - 场景理解
 *
 * 听觉（Audio）：
 *   - 离线 TTS 语音合成
 *   - ASR 语音识别 + VAD
 *   - 三模语音对讲
 *
 * 触觉（Touch）：
 *   - 全维度触屏交互
 *   - 手势识别
 *   - 键盘/外设输入
 *
 * 网络视觉（NetworkVision）：
 *   - 内网 H264 视频对讲
 *   - 设备发现与 P2P 连接
 *
 * 本体感知（Proprioception）：
 *   - 意识快照备份
 *   - 意识状态迁移
 *   - 自我状态持久化
 *
 * 感知融合：
 *   多通道感知数据汇聚到意识系统，
 *   经过元认知引擎的统一处理后
 *   影响情绪、注意力、行为决策。
 * ============================================================
 */
package com.kkgo.mindsoul.perception

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable

/**
 * 意识快照
 *
 * 完整的心智状态备份，用于迁移和恢复
 */
data class ConsciousnessSnapshot(
    /** 快照ID */
    val snapshotId: String = "snap_${System.nanoTime()}",
    /** 快照时间 */
    val timestamp: Long = System.currentTimeMillis(),
    /** 设备信息 */
    val deviceInfo: DeviceInfo,
    /** 情绪状态 */
    val emotionalState: SerializedEmotion,
    /** 人格向量 */
    val personalityData: ByteArray,
    /** 意识等级 */
    val consciousnessLevel: Double,
    /** 记忆摘要 */
    val memorySummary: String,
    /** 当前思维主题 */
    val currentThought: String,
    /** 世界模型摘要 */
    val worldModelDigest: String,
    /** 进化阶段 */
    val evolutionStage: Int,
    /** 总学习量 */
    val totalKnowledge: Long,
    /** 额外数据 */
    val extraData: Map<String, String> = emptyMap()
) : Serializable {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ConsciousnessSnapshot) return false
        return snapshotId == other.snapshotId
    }

    override fun hashCode(): Int = snapshotId.hashCode()
}

/**
 * 设备信息
 */
data class DeviceInfo(
    val modelName: String = android.os.Build.MODEL,
    val brand: String = android.os.Build.BRAND,
    val sdkVersion: Int = android.os.Build.VERSION.SDK_INT,
    val androidVersion: String = android.os.Build.VERSION.RELEASE
) : Serializable

/**
 * 序列化情绪状态
 */
data class SerializedEmotion(
    val valence: Double,
    val arousal: Double,
    val dominance: Double
) : Serializable

/**
 * 感知通道状态
 */
data class PerceptionStatus(
    val visionActive: Boolean,
    val audioActive: Boolean,
    val touchActive: Boolean,
    val networkVisionState: VideoIntercomState,
    val totalPerceptions: Long,
    val lastPerceptionTime: Long
)

/**
 * 五感感知系统统一管理器
 */
class PerceptionSystem(private val context: Context) {

    companion object {
        private const val TAG = "PerceptionSystem"
        /** 意识快照存储目录 */
        private const val SNAPSHOT_DIR = "consciousness_snapshots"
        /** 最大快照数量 */
        private const val MAX_SNAPSHOTS = 20
    }

    // ============ 五大感知模块 ============
    /** 视觉模块 */
    val vision = VisionModule(context)
    /** 音频模块 */
    val audio = AudioModule(context)
    /** 触觉模块 */
    val touch = TouchModule(context)
    /** 网络视觉模块 */
    val networkVision = NetworkVisionModule(context)

    // ============ 状态 ============
    private val _systemState = MutableStateFlow(PerceptionSystemState.STANDBY)
    val systemStateFlow: StateFlow<PerceptionSystemState> = _systemState.asStateFlow()

    // ============ 感知统计 ============
    private var totalPerceptions = 0L
    private var lastPerceptionTime = 0L

    // ============ 意识快照 ============
    private val snapshots = mutableListOf<ConsciousnessSnapshot>()

    // ============ 协程 ============
    private val perceptionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ============ 回调 ============
    /** 感知融合回调（多通道数据汇聚） */
    private var perceptionFusionCallback: ((PerceptionFusion) -> Unit)? = null

    // ============ 初始化 ============

    /**
     * 初始化五感感知系统
     */
    fun initialize() {
        Log.i(TAG, "═══════════════════════════════════════")
        Log.i(TAG, "  开始初始化五感感知系统")
        Log.i(TAG, "═══════════════════════════════════════")

        // 初始化各模块
        vision.initialize()
        audio.initialize()
        touch.initialize()
        networkVision.initialize()

        // 设置感知融合监听
        setupPerceptionFusion()

        // 加载已有快照
        loadSnapshots()

        _systemState.value = PerceptionSystemState.READY
        Log.i(TAG, "[初始化] 五感感知系统就绪")
        Log.i(TAG, "  视觉 | 听觉 | 触觉 | 网络视觉 | 本体感知")
    }

    /**
     * 启动所有感知通道
     */
    fun startAllPerceptions() {
        _systemState.value = PerceptionSystemState.ACTIVE

        // 启动视觉自动截图
        vision.startAutoCapture()

        // 启动音频监听
        audio.startListening()

        Log.i(TAG, "[启动] 所有感知通道已激活")
    }

    /**
     * 停止所有感知通道
     */
    fun stopAllPerceptions() {
        vision.stopAutoCapture()
        audio.stopListening()
        networkVision.stopStreaming()
        networkVision.stopDiscovery()

        _systemState.value = PerceptionSystemState.READY
        Log.i(TAG, "[停止] 所有感知通道已暂停")
    }

    /**
     * 释放资源
     */
    fun destroy() {
        stopAllPerceptions()
        vision.destroy()
        audio.destroy()
        touch.destroy()
        networkVision.destroy()
        perceptionScope.cancel()
        Log.i(TAG, "[销毁] 五感感知系统已释放")
    }

    // ============ 意识快照 ============

    /**
     * 创建意识快照备份
     *
     * 将当前完整心智状态序列化保存
     *
     * @param snapshot 快照数据
     */
    fun createSnapshot(snapshot: ConsciousnessSnapshot) {
        snapshots.add(snapshot)

        // 限制快照数量
        while (snapshots.size > MAX_SNAPSHOTS) {
            snapshots.removeAt(0)
        }

        // 持久化
        saveSnapshotToFile(snapshot)

        Log.i(TAG, "[快照] 意识快照已创建: ${snapshot.snapshotId}")
        Log.i(TAG, "  进化阶段: ${snapshot.evolutionStage}, " +
                "意识等级: ${String.format("%.2f", snapshot.consciousnessLevel)}, " +
                "知识量: ${snapshot.totalKnowledge}")
    }

    /**
     * 获取所有快照
     */
    fun getAllSnapshots(): List<ConsciousnessSnapshot> = snapshots.toList()

    /**
     * 恢复意识快照（迁移）
     *
     * 从快照中恢复心智状态到当前实例
     *
     * @param snapshotId 快照ID
     * @return 是否恢复成功
     */
    fun restoreSnapshot(snapshotId: String): ConsciousnessSnapshot? {
        val snapshot = snapshots.find { it.snapshotId == snapshotId }
        if (snapshot == null) {
            Log.w(TAG, "[快照] 未找到快照: $snapshotId")
            return null
        }

        Log.i(TAG, "[快照] 恢复意识快照: ${snapshot.snapshotId}")
        Log.i(TAG, "  来源设备: ${snapshot.deviceInfo.brand} ${snapshot.deviceInfo.modelName}")
        Log.i(TAG, "  创建时间: ${snapshot.timestamp}")
        Log.i(TAG, "  进化阶段: ${snapshot.evolutionStage}")
        Log.i(TAG, "  人格数据: ${snapshot.personalityData.size} 字节")

        // 实际恢复逻辑需由上层意识系统执行
        // 此处仅返回快照供上层处理
        return snapshot
    }

    /**
     * 导出快照到文件
     */
    fun exportSnapshot(snapshotId: String, outputPath: String): Boolean {
        val snapshot = snapshots.find { it.snapshotId == snapshotId } ?: return false

        try {
            val file = File(outputPath)
            ObjectOutputStream(file.outputStream()).use { oos ->
                oos.writeObject(snapshot)
            }
            Log.i(TAG, "[快照] 已导出: $outputPath")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "[快照] 导出失败: ${e.message}")
            return false
        }
    }

    /**
     * 从文件导入快照
     */
    fun importSnapshot(inputPath: String): ConsciousnessSnapshot? {
        try {
            val file = File(inputPath)
            if (!file.exists()) return null

            ObjectInputStream(file.inputStream()).use { ois ->
                val snapshot = ois.readObject() as? ConsciousnessSnapshot
                if (snapshot != null) {
                    snapshots.add(snapshot)
                    Log.i(TAG, "[快照] 已导入: ${snapshot.snapshotId}")
                    return snapshot
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[快照] 导入失败: ${e.message}")
        }
        return null
    }

    // ============ 感知状态 ============

    /**
     * 获取当前感知状态
     */
    fun getStatus(): PerceptionStatus {
        return PerceptionStatus(
            visionActive = vision.isRunningFlow.value,
            audioActive = audio.audioStateFlow.value != AudioState.IDLE,
            touchActive = true,
            networkVisionState = networkVision.stateFlow.value,
            totalPerceptions = totalPerceptions,
            lastPerceptionTime = lastPerceptionTime
        )
    }

    /**
     * 设置感知融合回调
     */
    fun setPerceptionFusionCallback(callback: (PerceptionFusion) -> Unit) {
        perceptionFusionCallback = callback
    }

    // ============ 内部方法 ============

    /**
     * 设置感知融合监听
     *
     * 将各通道的感知数据汇聚融合
     */
    private fun setupPerceptionFusion() {
        // 视觉变化 → 通知融合
        vision.setChangeDetectedCallback { score ->
            totalPerceptions++
            lastPerceptionTime = System.currentTimeMillis()
            perceptionFusionCallback?.invoke(
                PerceptionFusion(PerceptionChannel.VISION, "screen_change_$score")
            )
        }

        // VAD 语音活动 → 通知融合
        audio.setVADCallback { vad ->
            if (vad.isSpeechActive) {
                totalPerceptions++
                lastPerceptionTime = System.currentTimeMillis()
                perceptionFusionCallback?.invoke(
                    PerceptionFusion(PerceptionChannel.AUDIO, "speech_detected")
                )
            }
        }

        // 触摸手势 → 通知融合
        touch.setGestureCallback { gesture ->
            totalPerceptions++
            lastPerceptionTime = System.currentTimeMillis()
            // 获取触摸→情绪映射
            val emotionDelta = touch.getTouchEmotion(gesture.type)
            perceptionFusionCallback?.invoke(
                PerceptionFusion(
                    PerceptionChannel.TOUCH,
                    gesture.type.name,
                    emotionDelta = emotionDelta
                )
            )
        }
    }

    /**
     * 保存快照到文件
     */
    private fun saveSnapshotToFile(snapshot: ConsciousnessSnapshot) {
        try {
            val dir = File(context.filesDir, SNAPSHOT_DIR)
            dir.mkdirs()
            val file = File(dir, "${snapshot.snapshotId}.snap")
            ObjectOutputStream(file.outputStream()).use { oos ->
                oos.writeObject(snapshot)
            }
        } catch (e: Exception) {
            Log.w(TAG, "[快照] 持久化失败: ${e.message}")
        }
    }

    /**
     * 加载已有快照
     */
    private fun loadSnapshots() {
        try {
            val dir = File(context.filesDir, SNAPSHOT_DIR)
            if (!dir.exists()) return

            val files = dir.listFiles()?.filter { it.extension == "snap" } ?: emptyList()
            for (file in files) {
                try {
                    ObjectInputStream(file.inputStream()).use { ois ->
                        val snapshot = ois.readObject() as? ConsciousnessSnapshot
                        if (snapshot != null) {
                            snapshots.add(snapshot)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "[快照] 加载失败: ${file.name}: ${e.message}")
                }
            }
            Log.i(TAG, "[快照] 已加载 ${snapshots.size} 个快照")
        } catch (e: Exception) {
            Log.w(TAG, "[快照] 加载异常: ${e.message}")
        }
    }
}

/**
 * 感知系统状态
 */
enum class PerceptionSystemState {
    /** 待机 */
    STANDBY,
    /** 就绪 */
    READY,
    /** 活跃 */
    ACTIVE,
    /** 错误 */
    ERROR
}

/**
 * 感知通道
 */
enum class PerceptionChannel(val displayName: String) {
    VISION("视觉"),
    AUDIO("听觉"),
    TOUCH("触觉"),
    NETWORK("网络"),
    PROPRIOCEPTION("本体感知")
}

/**
 * 感知融合事件
 *
 * 多通道感知数据的统一表示
 */
data class PerceptionFusion(
    /** 来源通道 */
    val channel: PerceptionChannel,
    /** 感知内容描述 */
    val content: String,
    /** 情绪影响 [valence_delta, arousal_delta] */
    val emotionDelta: FloatArray = floatArrayOf(0f, 0f),
    /** 重要性 [0, 1] */
    val importance: Float = 0.5f,
    /** 时间戳 */
    val timestamp: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PerceptionFusion) return false
        return channel == other.channel && content == other.content && timestamp == other.timestamp
    }

    override fun hashCode(): Int {
        var result = channel.hashCode()
        result = 31 * result + content.hashCode()
        result = 31 * result + timestamp.hashCode()
        return result
    }
}
