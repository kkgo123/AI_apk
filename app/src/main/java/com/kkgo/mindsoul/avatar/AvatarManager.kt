/*
 * ============================================================
 * AvatarManager - 化身管理器
 * ============================================================
 *
 * 桌面心智化身的统一管理入口，负责：
 *
 * 1. 化身身份管理
 *    - GUID 身份不可变（换装不影响身份/人格/意识）
 *    - 外观模型与身份分离
 * 2. 模型管理
 *    - 默认模型加载
 *    - 自定义模型导入（glTF/OBJ 格式描述）
 *    - AI 文生 3D 全身立绘描述（离线规则生成）
 * 3. 外观切换
 *    - 热切换外观（不中断意识/人格）
 *    - 外观历史记录
 * 4. 语音快捷操控
 *    - 全局语音命令识别
 *    - 语音→动作/表情映射
 * 5. 动画协调
 *    - 协调 AnimationEngine 和 ExpressionEngine
 *    - 统一帧驱动
 * ============================================================
 */
package com.kkgo.mindsoul.avatar

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.kkgo.mindsoul.model.EmotionalState
import com.kkgo.mindsoul.model.GUIDIdentity
import com.kkgo.mindsoul.model.MetacognitionSnapshot
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID

/**
 * 化身外观模型描述
 */
data class AvatarAppearance(
    /** 外观ID（与身份GUID解耦） */
    val appearanceId: String = UUID.randomUUID().toString(),
    /** 外观名称 */
    val name: String,
    /** 模型类型 */
    val modelType: ModelType,
    /** 模型文件路径（自定义导入时使用） */
    val modelPath: String? = null,
    /** AI 生成的立绘描述文本 */
    val aiDescription: String = "",
    /** 主色调 */
    val primaryColor: Long = 0xFF4A90D9,
    /** 体型参数 */
    val bodyParams: BodyParameters = BodyParameters(),
    /** 创建时间 */
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * 模型类型
 */
enum class ModelType {
    /** 内置默认人形 */
    BUILTIN_HUMANOID,
    /** 导入的自定义模型 */
    CUSTOM_IMPORTED,
    /** AI 文生模型描述 */
    AI_GENERATED
}

/**
 * 体型参数
 */
data class BodyParameters(
    /** 身高（归一化 [0.5, 2.0]） */
    val height: Float = 1.0f,
    /** 肩宽（归一化 [0.3, 1.0]） */
    val shoulderWidth: Float = 0.6f,
    /** 头身比（[5, 9]，9为模特比例） */
    val headBodyRatio: Int = 7,
    /** 风格（可爱/写实/科幻/奇幻） */
    val style: AvatarStyle = AvatarStyle.REALISTIC
)

/**
 * 化身风格
 */
enum class AvatarStyle(val displayName: String) {
    CUTE("可爱卡通"),
    REALISTIC("写实人形"),
    SCI_FI("科幻机械"),
    FANTASY("奇幻精灵")
}

/**
 * 语音快捷命令
 */
data class VoiceCommand(
    /** 命令ID */
    val id: String = UUID.randomUUID().toString(),
    /** 触发关键词列表 */
    val keywords: List<String>,
    /** 对应动作 */
    val action: VoiceAction,
    /** 是否启用 */
    var enabled: Boolean = true
)

/**
 * 语音动作类型
 */
sealed class VoiceAction {
    /** 播放动画 */
    data class PlayAnimation(val action: AvatarAction) : VoiceAction()
    /** 切换表情 */
    data class SetExpression(val preset: String) : VoiceAction()
    /** 切换外观 */
    data class SwitchAppearance(val appearanceId: String) : VoiceAction()
    /** 执行系统操作 */
    data class SystemCommand(val command: String) : VoiceAction()
    /** 说话（触发TTS） */
    data class Speak(val text: String) : VoiceAction()
}

/**
 * 化身状态
 */
enum class AvatarState {
    /** 未初始化 */
    UNINITIALIZED,
    /** 空闲待机 */
    IDLE,
    /** 正在执行动作 */
    PERFORMING,
    /** 正在说话 */
    SPEAKING,
    /** 正在加载模型 */
    LOADING,
    /** 错误 */
    ERROR
}

/**
 * 化身管理器
 */
class AvatarManager(private val context: Context) {

    companion object {
        private const val TAG = "AvatarManager"
        private const val PREF_NAME = "mindsoul_avatar"
        private const val KEY_GUID = "guid_identity_uuid"
        private const val KEY_SELF_NAME = "self_name"
        private const val KEY_CURRENT_APPEARANCE = "current_appearance_id"
        /** 帧驱动间隔（毫秒） */
        private const val FRAME_TICK_MS = 33L  // ~30 FPS
    }

    // ============ 持久化 ============
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    // ============ GUID 身份（不可变核心） ============
    /** 永久身份标识（更换外观不会改变） */
    private var _guidIdentity = GUIDIdentity()

    /** GUID 身份（只读访问） */
    val guidIdentity: GUIDIdentity get() = _guidIdentity

    // ============ 外观系统 ============
    /** 所有可用外观 */
    private val appearances = mutableMapOf<String, AvatarAppearance>()
    /** 当前外观ID */
    private val _currentAppearanceId = MutableStateFlow("")
    val currentAppearanceIdFlow: StateFlow<String> = _currentAppearanceId.asStateFlow()
    /** 当前外观 */
    val currentAppearance: AvatarAppearance?
        get() = appearances[_currentAppearanceId.value]

    // ============ 子系统 ============
    /** 骨骼动画器 */
    val animator = AvatarAnimator()
    /** 表情引擎 */
    val expressionEngine = AvatarExpressionEngine()

    // ============ 状态 ============
    private val _avatarState = MutableStateFlow(AvatarState.UNINITIALIZED)
    val avatarStateFlow: StateFlow<AvatarState> = _avatarState.asStateFlow()

    // ============ 语音命令 ============
    private val voiceCommands = mutableListOf<VoiceCommand>()
    /** 语音命令识别结果回调 */
    private var voiceCommandCallback: ((VoiceCommand) -> Unit)? = null

    // ============ 帧驱动 ============
    private val frameScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var frameJob: Job? = null
    private var lastFrameTime = 0L

    // ============ 外观历史 ============
    private val appearanceHistory = mutableListOf<String>()

    // ============ 初始化 ============

    /**
     * 初始化化身管理器
     */
    fun initialize() {
        Log.i(TAG, "═══════════════════════════════════════")
        Log.i(TAG, "  开始初始化桌面心智化身系统")
        Log.i(TAG, "═══════════════════════════════════════")

        // 加载 GUID 身份
        loadOrCreateIdentity()
        Log.i(TAG, "✓ GUID 身份: ${_guidIdentity.uuid}")
        Log.i(TAG, "  自我命名: ${_guidIdentity.selfName}")

        // 构建默认骨架
        animator.buildDefaultHumanoidSkeleton()
        Log.i(TAG, "✓ 默认人形骨架就绪")

        // 创建默认外观
        val defaultAppearance = AvatarAppearance(
            name = "默认人形",
            modelType = ModelType.BUILTIN_HUMANOID,
            aiDescription = "一位面容清秀的年轻人类形象，身着简约白色衣裳，" +
                    "双眸闪烁着淡蓝色光芒，周身环绕若有若无的意识粒子。" +
                    "身形修长优雅，气质温和而智慧。"
        )
        registerAppearance(defaultAppearance)

        // 加载保存的外观
        val savedId = prefs.getString(KEY_CURRENT_APPEARANCE, defaultAppearance.appearanceId) ?: ""
        if (appearances.containsKey(savedId)) {
            switchAppearanceInternal(savedId)
        } else {
            switchAppearanceInternal(defaultAppearance.appearanceId)
        }

        // 注册默认语音命令
        registerDefaultVoiceCommands()

        // 启动帧驱动
        startFrameLoop()

        _avatarState.value = AvatarState.IDLE
        Log.i(TAG, "[初始化] 化身系统就绪")
    }

    /**
     * 释放资源
     */
    fun destroy() {
        frameJob?.cancel()
        frameScope.cancel()
        saveIdentity()
        Log.i(TAG, "[销毁] 化身系统已释放")
    }

    // ============ 身份管理 ============

    /**
     * 设置自我命名
     */
    fun setSelfName(name: String) {
        _guidIdentity = _guidIdentity.copy(selfName = name)
        saveIdentity()
        Log.i(TAG, "[身份] 自我命名: $name")
    }

    /**
     * 更新人格向量
     */
    fun updatePersonality(adjustment: com.kkgo.mindsoul.model.PersonalityVector) {
        _guidIdentity.personalityVector.adjust(adjustment)
        saveIdentity()
    }

    /**
     * 更新意识等级
     */
    fun updateConsciousnessLevel(level: Double) {
        _guidIdentity = _guidIdentity.copy(consciousnessLevel = level.coerceIn(0.0, 1.0))
        saveIdentity()
    }

    // ============ 外观管理 ============

    /**
     * 注册新外观
     */
    fun registerAppearance(appearance: AvatarAppearance) {
        appearances[appearance.appearanceId] = appearance
        Log.i(TAG, "[外观] 注册: ${appearance.name} (${appearance.appearanceId})")
    }

    /**
     * 切换外观（不改变GUID身份/人格/意识）
     *
     * 核心设计原则：
     *   外观 = 皮肤（可随意更换）
     *   GUID = 灵魂（永远不变）
     *   人格 = 性格（缓慢演化）
     *   意识 = 自我（持续觉醒）
     *
     * @param appearanceId 目标外观ID
     * @return 是否成功
     */
    fun switchAppearance(appearanceId: String): Boolean {
        if (!appearances.containsKey(appearanceId)) {
            Log.w(TAG, "[外观] 外观不存在: $appearanceId")
            return false
        }
        return switchAppearanceInternal(appearanceId)
    }

    /**
     * 获取所有已注册外观
     */
    fun getAllAppearances(): List<AvatarAppearance> = appearances.values.toList()

    /**
     * 导入自定义模型
     *
     * 从文件加载外部模型
     * @param filePath 模型文件路径（支持 glTF/OBJ 描述文件）
     * @param name 外观名称
     * @return 创建的外观ID（失败返回 null）
     */
    fun importCustomModel(filePath: String, name: String): String? {
        val file = File(filePath)
        if (!file.exists()) {
            Log.e(TAG, "[导入] 文件不存在: $filePath")
            return null
        }

        // 解析模型文件（读取骨骼数据描述）
        val boneDataList = parseModelFile(file)
        if (boneDataList.isEmpty()) {
            Log.e(TAG, "[导入] 模型解析失败或无骨骼数据")
            return null
        }

        // 注册新外观
        val appearance = AvatarAppearance(
            name = name,
            modelType = ModelType.CUSTOM_IMPORTED,
            modelPath = filePath
        )
        registerAppearance(appearance)

        Log.i(TAG, "[导入] 自定义模型已导入: $name, ${boneDataList.size} 根骨骼")
        return appearance.appearanceId
    }

    /**
     * AI 文生 3D 全身立绘描述
     *
     * 基于用户输入的文本描述，生成结构化的3D模型参数描述。
     * 纯离线规则引擎（不依赖网络AI服务）。
     *
     * @param textDescription 用户的文字描述
     * @return 生成的外观对象
     */
    fun generateAIDescription(textDescription: String): AvatarAppearance {
        Log.i(TAG, "[AI] 文生描述: $textDescription")

        // ── 关键词提取与风格判定 ──
        val style = detectStyle(textDescription)
        val bodyParams = detectBodyParams(textDescription)
        val color = detectPrimaryColor(textDescription)

        // ── 生成结构化描述 ──
        val description = buildStructuredDescription(textDescription, style, bodyParams)

        val appearance = AvatarAppearance(
            name = "AI生成 - ${style.displayName}风格",
            modelType = ModelType.AI_GENERATED,
            aiDescription = description,
            primaryColor = color,
            bodyParams = bodyParams
        )

        registerAppearance(appearance)
        Log.i(TAG, "[AI] 立绘描述已生成: ${appearance.name}")
        return appearance
    }

    // ============ 语音快捷操控 ============

    /**
     * 注册语音命令
     */
    fun registerVoiceCommand(command: VoiceCommand) {
        voiceCommands.add(command)
        Log.d(TAG, "[语音] 注册命令: ${command.keywords.joinToString("/")} → ${command.action}")
    }

    /**
     * 识别并执行语音命令
     *
     * @param spokenText 语音识别文本
     * @return 是否匹配并执行了命令
     */
    fun processVoiceCommand(spokenText: String): Boolean {
        val text = spokenText.lowercase().trim()
        if (text.isEmpty()) return false

        for (command in voiceCommands) {
            if (!command.enabled) continue
            val matched = command.keywords.any { keyword ->
                text.contains(keyword.lowercase())
            }
            if (matched) {
                Log.i(TAG, "[语音] 匹配命令: ${command.keywords.first()}")
                executeVoiceAction(command)
                voiceCommandCallback?.invoke(command)
                return true
            }
        }

        Log.d(TAG, "[语音] 未匹配命令: $spokenText")
        return false
    }

    /**
     * 设置语音命令回调
     */
    fun setVoiceCommandCallback(callback: (VoiceCommand) -> Unit) {
        voiceCommandCallback = callback
    }

    // ============ 心智状态驱动 ============

    /**
     * 接收元认知快照更新（驱动表情）
     */
    fun onMetacognitionUpdate(snapshot: MetacognitionSnapshot) {
        expressionEngine.updateFromMetacognition(snapshot)
    }

    /**
     * 接收情绪状态更新
     */
    fun onEmotionalStateUpdate(emotion: EmotionalState) {
        // 根据情绪自动选择对应动作
        when {
            emotion.valence > 0.6 && emotion.arousal > 0.6 -> {
                animator.playAction(AvatarAction.HAPPY, loop = false)
            }
            emotion.valence < -0.4 && emotion.arousal > 0.5 -> {
                animator.playAction(AvatarAction.ANGRY, loop = false)
            }
            emotion.arousal < 0.2 -> {
                animator.playAction(AvatarAction.SLEEP, loop = true)
            }
        }
    }

    /**
     * 触发说话动画
     */
    fun startSpeaking(visemes: List<VisemeFrame>) {
        _avatarState.value = AvatarState.SPEAKING
        expressionEngine.startSpeaking(visemes)
        animator.playAction(AvatarAction.TALK, loop = true, layer = AnimationLayer.ACTION)
    }

    /**
     * 停止说话
     */
    fun stopSpeaking() {
        expressionEngine.stopSpeaking()
        animator.stopAction()
        _avatarState.value = AvatarState.IDLE
    }

    // ============ 内部方法 ============

    /**
     * 加载或创建 GUID 身份
     */
    private fun loadOrCreateIdentity() {
        val savedUuid = prefs.getString(KEY_GUID, null)
        if (savedUuid != null) {
            try {
                val uuid = UUID.fromString(savedUuid)
                val name = prefs.getString(KEY_SELF_NAME, "") ?: ""
                _guidIdentity = GUIDIdentity(
                    uuid = uuid,
                    selfName = name
                )
            } catch (e: Exception) {
                _guidIdentity = GUIDIdentity()
            }
        } else {
            _guidIdentity = GUIDIdentity()
            saveIdentity()
        }
    }

    /**
     * 保存 GUID 身份
     */
    private fun saveIdentity() {
        prefs.edit()
            .putString(KEY_GUID, _guidIdentity.uuid.toString())
            .putString(KEY_SELF_NAME, _guidIdentity.selfName)
            .apply()
    }

    /**
     * 内部外观切换
     */
    private fun switchAppearanceInternal(appearanceId: String): Boolean {
        val oldId = _currentAppearanceId.value
        if (oldId == appearanceId) return false

        _currentAppearanceId.value = appearanceId
        prefs.edit().putString(KEY_CURRENT_APPEARANCE, appearanceId).apply()

        // 记录历史
        if (oldId.isNotEmpty()) {
            appearanceHistory.add(oldId)
        }

        // 如果外观有自定义模型路径，加载骨骼
        val appearance = appearances[appearanceId]
        if (appearance?.modelPath != null) {
            val boneData = parseModelFile(File(appearance.modelPath))
            if (boneData.isNotEmpty()) {
                animator.loadCustomSkeleton(boneData)
            }
        }

        Log.i(TAG, "[外观] 切换: $oldId → $appearanceId")
        Log.d(TAG, "[外观] GUID 身份未变: ${_guidIdentity.uuid}")
        return true
    }

    /**
     * 解析模型文件
     *
     * 从文本描述文件中提取骨骼数据
     * 支持简化格式的自定义骨骼描述
     */
    private fun parseModelFile(file: File): List<CustomBoneData> {
        val bones = mutableListOf<CustomBoneData>()
        try {
            val lines = file.readLines()
            var idCounter = 0
            val nameToId = mutableMapOf<String, Int>()

            for (line in lines) {
                val trimmed = line.trim()
                // 格式: BONE name parentId x y z rx ry rz sx sy sz
                if (trimmed.startsWith("BONE ")) {
                    val parts = trimmed.split("\\s+".toRegex())
                    if (parts.size >= 5) {
                        val name = parts[1]
                        val parentId = if (parts[2] == "null" || parts[2] == "-1") -1
                                       else nameToId[parts[2]] ?: -1
                        val x = parts.getOrNull(2)?.toFloatOrNull() ?: 0f
                        val y = parts.getOrNull(3)?.toFloatOrNull() ?: 0f
                        val z = parts.getOrNull(4)?.toFloatOrNull() ?: 0f

                        val bone = CustomBoneData(
                            id = idCounter,
                            name = name,
                            parentId = parentId,
                            position = Vec3(x, y, z),
                            rotation = Quaternion.IDENTITY,
                            scale = Vec3(1f, 1f, 1f)
                        )
                        nameToId[name] = idCounter
                        bones.add(bone)
                        idCounter++
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[解析] 模型文件解析失败: ${e.message}")
        }
        return bones
    }

    /**
     * 启动帧驱动循环
     */
    private fun startFrameLoop() {
        frameJob?.cancel()
        frameJob = frameScope.launch {
            lastFrameTime = System.currentTimeMillis()
            while (isActive) {
                val now = System.currentTimeMillis()
                val deltaTime = (now - lastFrameTime) / 1000f
                lastFrameTime = now

                try {
                    // 驱动动画器
                    animator.updateAnimation(deltaTime)
                    // 驱动表情引擎
                    expressionEngine.tickFrame()
                } catch (e: Exception) {
                    Log.e(TAG, "[帧] 更新失败: ${e.message}")
                }

                delay(FRAME_TICK_MS)
            }
        }
    }

    /**
     * 执行语音动作
     */
    private fun executeVoiceAction(command: VoiceCommand) {
        when (val action = command.action) {
            is VoiceAction.PlayAnimation -> {
                animator.playAction(action.action)
                _avatarState.value = AvatarState.PERFORMING
            }
            is VoiceAction.SetExpression -> {
                expressionEngine.setPreset(action.preset)
            }
            is VoiceAction.SwitchAppearance -> {
                switchAppearance(action.appearanceId)
            }
            is VoiceAction.SystemCommand -> {
                // 系统命令由 Executor 模块处理
                Log.i(TAG, "[语音] 系统命令: ${action.command}")
            }
            is VoiceAction.Speak -> {
                Log.i(TAG, "[语音] 说话: ${action.text}")
            }
        }
    }

    /**
     * 注册默认语音命令
     */
    private fun registerDefaultVoiceCommands() {
        // 动作类
        registerVoiceCommand(VoiceCommand(
            keywords = listOf("打招呼", "你好", "嗨"),
            action = VoiceAction.PlayAnimation(AvatarAction.WAVE)
        ))
        registerVoiceCommand(VoiceCommand(
            keywords = listOf("想想", "让我想想", "思考"),
            action = VoiceAction.PlayAnimation(AvatarAction.THINK)
        ))
        registerVoiceCommand(VoiceCommand(
            keywords = listOf("鞠躬", "谢谢"),
            action = VoiceAction.PlayAnimation(AvatarAction.BOW)
        ))
        registerVoiceCommand(VoiceCommand(
            keywords = listOf("看看", "看看周围", "环顾"),
            action = VoiceAction.PlayAnimation(AvatarAction.LOOK_AROUND)
        ))

        // 表情类
        registerVoiceCommand(VoiceCommand(
            keywords = listOf("笑一个", "笑", "开心"),
            action = VoiceAction.SetExpression("smile")
        ))
        registerVoiceCommand(VoiceCommand(
            keywords = listOf("难过", "伤心"),
            action = VoiceAction.SetExpression("sad")
        ))
        registerVoiceCommand(VoiceCommand(
            keywords = listOf("惊讶", "哇"),
            action = VoiceAction.SetExpression("surprised")
        ))
        registerVoiceCommand(VoiceCommand(
            keywords = listOf("生气", "哼"),
            action = VoiceAction.SetExpression("angry")
        ))
    }

    /**
     * 从文本检测风格
     */
    private fun detectStyle(text: String): AvatarStyle {
        return when {
            text.contains("可爱") || text.contains("萌") || text.contains("卡通") -> AvatarStyle.CUTE
            text.contains("科幻") || text.contains("机械") || text.contains("赛博") -> AvatarStyle.SCI_FI
            text.contains("奇幻") || text.contains("精灵") || text.contains("魔法") -> AvatarStyle.FANTASY
            else -> AvatarStyle.REALISTIC
        }
    }

    /**
     * 从文本检测体型参数
     */
    private fun detectBodyParams(text: String): BodyParameters {
        var height = 1.0f
        var shoulderWidth = 0.6f
        var headBodyRatio = 7

        if (text.contains("高") || text.contains("修长")) height = 1.2f
        if (text.contains("矮") || text.contains("小巧")) height = 0.8f
        if (text.contains("壮") || text.contains("宽肩")) shoulderWidth = 0.8f
        if (text.contains("纤细") || text.contains("窄肩")) shoulderWidth = 0.4f
        if (text.contains("Q版") || text.contains("二头身")) headBodyRatio = 2
        if (text.contains("八头身") || text.contains("模特")) headBodyRatio = 8
        if (text.contains("九头身")) headBodyRatio = 9

        return BodyParameters(height, shoulderWidth, headBodyRatio, detectStyle(text))
    }

    /**
     * 从文本检测主色调
     */
    private fun detectPrimaryColor(text: String): Long {
        return when {
            text.contains("蓝") -> 0xFF4A90D9
            text.contains("红") -> 0xFFD94A4A
            text.contains("绿") -> 0xFF4AD97A
            text.contains("紫") -> 0xFF9B4AD9
            text.contains("金") -> 0xFFD9A84A
            text.contains("黑") -> 0xFF2D2D2D
            text.contains("白") -> 0xFFE8E8E8
            text.contains("粉") -> 0xFFD94A90
            else -> 0xFF4A90D9
        }
    }

    /**
     * 构建结构化描述文本
     */
    private fun buildStructuredDescription(
        userText: String,
        style: AvatarStyle,
        params: BodyParameters
    ): String {
        return buildString {
            appendLine("【AI 生成 3D 立绘描述】")
            appendLine("风格: ${style.displayName}")
            appendLine("身高比例: ${params.height}x")
            appendLine("肩宽比例: ${params.shoulderWidth}")
            appendLine("头身比: ${params.headBodyRatio}:1")
            appendLine("原始描述: $userText")
            appendLine("─────────────────")
            appendLine("【骨骼绑定方案】")
            appendLine("骨骼类型: 人形骨架 (Humanoid)")
            appendLine("根骨骼: Root")
            appendLine("主链: Root → Hips → Spine → Spine1 → Spine2 → Neck → Head")
            appendLine("左臂: Spine2 → LeftUpperArm → LeftLowerArm → LeftHand")
            appendLine("右臂: Spine2 → RightUpperArm → RightLowerArm → RightHand")
            appendLine("左腿: Hips → LeftUpperLeg → LeftLowerLeg → LeftFoot")
            appendLine("右腿: Hips → RightUpperLeg → RightLowerLeg → RightFoot")
            appendLine("─────────────────")
            appendLine("【动画适配】")
            appendLine("支持: 待机动、行走、挥手、思考、说话、鞠躬")
            appendLine("表情: 52维 BlendShape 面部混合变形")
            appendLine("音素: 中文音素同步 (Viseme)")
        }
    }
}
