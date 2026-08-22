/*
 * ============================================================
 * CallActivity - 通话模式界面
 * ============================================================
 *
 * 支持两种通话模式：
 * 1. 音频通话模式（AUDIO_CALL）
 *    - 全屏语音界面，显示化身动画波形
 *    - 调用 ASRModule 进行实时语音识别
 *    - 调用 TTS 输出 AI 语音回复
 *    - 完整的 ASR → InferenceManager → TTS 循环
 *    - 波形动画随识别/TTS状态变化
 *
 * 2. 视频通话模式（VIDEO_CALL）
 *    - 使用 Camera2 API 获取前置摄像头视频流
 *    - TextureView 实时预览
 *    - 支持前后摄像头切换
 *    - TTS 语音输出 AI 回复
 *    - ASR 语音识别循环
 *
 * 通话控制：
 * - 挂断按钮：结束通话返回聊天界面
 * - 静音按钮：临时关闭麦克风
 * - 切换按钮：切换扬声器/听筒
 * - 摄像头切换按钮（视频模式）：切换前后摄像头
 *
 * 不引入新依赖，全部使用 Android 自带 API：
 * - Camera2 (android.hardware.camera2) 用于视频预览
 * - SpeechRecognizer (ASRModule) 用于语音识别
 * - TextToSpeech 用于语音合成
 * ============================================================
 */
package com.kkgo.mindsoul.ui

import android.Manifest
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.activity.result.contract.ActivityResultContracts
import com.kkgo.mindsoul.MindSoulApp
import com.kkgo.mindsoul.R
import com.kkgo.mindsoul.inference.InferenceManager
import kotlinx.coroutines.*
import java.util.Locale

/**
 * 通话模式枚举
 */
enum class CallMode {
    /** 音频通话：全屏语音波形动画 */
    AUDIO_CALL,
    /** 视频通话：前置摄像头视频流 + TTS 输出 */
    VIDEO_CALL
}

/**
 * 通话界面 Activity
 * 支持音频通话和视频通话两种模式
 */
class CallActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "CallActivity"
        /** Intent 参数：通话模式 */
        const val EXTRA_CALL_MODE = "call_mode"
        /** Intent 参数：AI 名称 */
        const val EXTRA_AI_NAME = "ai_name"

        /**
         * 创建启动 Intent
         *
         * @param context 上下文
         * @param mode 通话模式
         * @param aiName AI 名称（显示用）
         */
        fun createIntent(context: Context, mode: CallMode, aiName: String): Intent {
            return Intent(context, CallActivity::class.java).apply {
                putExtra(EXTRA_CALL_MODE, mode.name)
                putExtra(EXTRA_AI_NAME, aiName)
            }
        }
    }

    private val app by lazy { application as MindSoulApp }
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ============ 界面元素 ============
    /** 音频通话界面容器 */
    private lateinit var layoutAudioCall: View
    /** 视频通话界面容器 */
    private lateinit var layoutVideoCall: View
    /** AI 名称显示 */
    private lateinit var tvAiName: TextView
    /** 通话状态文字（"正在通话中..."等） */
    private lateinit var tvCallStatus: TextView
    /** 通话时长显示 */
    private lateinit var tvCallDuration: TextView
    /** 波形动画 View（音频模式下的化身动画） */
    private lateinit var viewWaveform: View
    /** 挂断按钮 */
    private lateinit var btnHangUp: ImageButton
    /** 静音按钮 */
    private lateinit var btnMute: ImageButton
    /** 扬声器切换按钮 */
    private lateinit var btnSpeaker: ImageButton
    /** 摄像头切换按钮（视频模式专用） */
    private lateinit var btnSwitchCamera: ImageButton
    /** 摄像头预览 TextureView */
    private lateinit var texturePreview: TextureView

    // ============ 状态变量 ============
    /** 当前通话模式 */
    private var callMode = CallMode.AUDIO_CALL
    /** AI 名称 */
    private var aiName = "MindSoul"
    /** 是否处于静音状态 */
    private var isMuted = false
    /** 是否使用扬声器 */
    private var isSpeakerOn = true
    /** 通话开始时间 */
    private var callStartTime = 0L
    /** 波形动画器 */
    private var waveformAnimator: ValueAnimator? = null
    /** 通话时长计时器 */
    private var durationJob: Job? = null
    /** Activity 是否已销毁 */
    private var isDestroyed = false
    /** 是否正在等待 ASR 结果（防止重复触发） */
    @Volatile
    private var isWaitingAsrResult = false
    /** 是否正在 TTS 播放中（播放时不监听 ASR） */
    @Volatile
    private var isTtsSpeaking = false

    // ============ TTS 语音合成 ============
    /** TTS 引擎 */
    private var tts: TextToSpeech? = null
    /** TTS 是否初始化成功 */
    private var ttsReady = false

    // ============ 推理管理器 ============
    /** 推理管理器（懒加载） */
    private val inferenceManager by lazy {
        InferenceManager(this, app.consciousnessManager)
    }

    // ============ Camera2 相关 ============
    /** Camera2 管理器 */
    private var cameraManager: CameraManager? = null
    /** 当前打开的 CameraDevice */
    private var cameraDevice: CameraDevice? = null
    /** Camera 捕获会话 */
    private var captureSession: CameraCaptureSession? = null
    /** 捕获请求构建器 */
    private var previewRequestBuilder: CaptureRequest.Builder? = null
    /** 后台线程（Camera2 需要） */
    private var backgroundThread: HandlerThread? = null
    /** 后台 Handler */
    private var backgroundHandler: Handler? = null
    /** 当前使用的摄像头 ID */
    private var currentCameraId: String = ""
    /** 是否使用前置摄像头 */
    private var usingFrontCamera = true
    /** 摄像头是否已打开 */
    private var cameraOpened = false

    // ============ 权限请求 ============
    /** 多权限请求启动器（麦克风 + 相机） */
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            startCall()
        } else {
            Toast.makeText(this, "需要麦克风和相机权限才能使用通话功能", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    // ============ 生命周期 ============

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_call)
        isDestroyed = false

        // 读取 Intent 参数
        callMode = try {
            CallMode.valueOf(intent.getStringExtra(EXTRA_CALL_MODE) ?: CallMode.AUDIO_CALL.name)
        } catch (e: Exception) {
            CallMode.AUDIO_CALL
        }
        aiName = intent.getStringExtra(EXTRA_AI_NAME) ?: "MindSoul"

        initViews()
        setupCallMode()
        setupTts()
        requestPermissionsAndStart()
    }

    override fun onResume() {
        super.onResume()
        isDestroyed = false
        // 视频模式：在 onResume 时重新启动摄像头预览
        if (callMode == CallMode.VIDEO_CALL && cameraOpened) {
            startCameraPreview()
        }
    }

    /**
     * 初始化所有界面元素
     */
    private fun initViews() {
        layoutAudioCall = findViewById(R.id.layoutAudioCall)
        layoutVideoCall = findViewById(R.id.layoutVideoCall)
        tvAiName = findViewById(R.id.tvAiName)
        tvCallStatus = findViewById(R.id.tvCallStatus)
        tvCallDuration = findViewById(R.id.tvCallDuration)
        viewWaveform = findViewById(R.id.viewWaveform)
        btnHangUp = findViewById(R.id.btnHangUp)
        btnMute = findViewById(R.id.btnMute)
        btnSpeaker = findViewById(R.id.btnSpeaker)
        texturePreview = findViewById(R.id.texturePreview)

        // 摄像头切换按钮（动态查找，可能不存在于旧布局中）
        btnSwitchCamera = findViewById(R.id.btnSwitchCamera)

        // 设置 AI 名称
        tvAiName.text = aiName

        // 按钮事件
        btnHangUp.setOnClickListener { hangUp() }
        btnMute.setOnClickListener { toggleMute() }
        btnSpeaker.setOnClickListener { toggleSpeaker() }
        btnSwitchCamera.setOnClickListener { switchCamera() }

        // 视频模式：摄像头切换按钮可见性
        btnSwitchCamera.visibility = if (callMode == CallMode.VIDEO_CALL) View.VISIBLE else View.GONE
    }

    /**
     * 根据通话模式设置对应界面
     */
    private fun setupCallMode() {
        when (callMode) {
            CallMode.AUDIO_CALL -> {
                layoutAudioCall.visibility = View.VISIBLE
                layoutVideoCall.visibility = View.GONE
            }
            CallMode.VIDEO_CALL -> {
                layoutAudioCall.visibility = View.GONE
                layoutVideoCall.visibility = View.VISIBLE
                // 设置 TextureView 回调
                texturePreview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                        Log.i(TAG, "[Camera] SurfaceTexture 就绪, 开启预览")
                        startCameraPreview()
                    }
                    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
                    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                        closeCamera()
                        return true
                    }
                    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
                }
            }
        }
    }

    // ============ TTS 初始化 ============

    /**
     * 初始化 TTS 引擎
     */
    private fun setupTts() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.CHINESE)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.setLanguage(Locale.ENGLISH)
                }
                ttsReady = true
                Log.i(TAG, "[TTS] 引擎初始化成功")
            } else {
                ttsReady = false
                Log.w(TAG, "[TTS] 引擎初始化失败, status=$status")
            }
        }
    }

    /**
     * 使用 TTS 朗读文本
     */
    private fun speakText(text: String) {
        if (!ttsReady) {
            Log.w(TAG, "[TTS] 引擎未就绪，无法朗读")
            return
        }
        try {
            isTtsSpeaking = true
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "call_tts_${System.currentTimeMillis()}")
            Log.i(TAG, "[TTS] 开始朗读: ${text.take(50)}...")
        } catch (e: Exception) {
            Log.e(TAG, "[TTS] 朗读失败: ${e.message}")
            isTtsSpeaking = false
        }
    }

    /**
     * 请求必要权限并开始通话
     */
    private fun requestPermissionsAndStart() {
        val permissions = when (callMode) {
            CallMode.AUDIO_CALL -> arrayOf(Manifest.permission.RECORD_AUDIO)
            CallMode.VIDEO_CALL -> arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.CAMERA
            )
        }
        val needRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needRequest.isEmpty()) {
            startCall()
        } else {
            permissionLauncher.launch(needRequest.toTypedArray())
        }
    }

    // ============ 通话核心逻辑 ============

    /**
     * 开始通话
     * 启动录音、波形动画和通话时长计时
     */
    private fun startCall() {
        callStartTime = System.currentTimeMillis()
        tvCallStatus.text = "正在通话中..."

        // 启动波形动画
        startWaveformAnimation()

        // 启动通话时长计时
        startDurationTimer()

        // 启动实时语音识别循环
        startListeningLoop()

        Toast.makeText(this, "📞 通话已开始", Toast.LENGTH_SHORT).show()
        Log.i(TAG, "[通话] $callMode 模式通话开始, AI: $aiName")
    }

    /**
     * 挂断通话
     */
    private fun hangUp() {
        Log.i(TAG, "[通话] 挂断")
        Toast.makeText(this, "📞 通话已结束", Toast.LENGTH_SHORT).show()

        // 停止所有运行中任务
        stopListeningLoop()
        stopWaveformAnimation()
        durationJob?.cancel()
        closeCamera()

        // 停止 TTS
        try {
            tts?.stop()
        } catch (_: Exception) {}

        // 返回聊天界面
        finish()
    }

    /**
     * 切换静音状态
     */
    private fun toggleMute() {
        isMuted = !isMuted
        btnMute.isSelected = isMuted
        tvCallStatus.text = if (isMuted) "🔇 已静音" else "正在通话中..."
        Toast.makeText(this, if (isMuted) "已静音" else "已取消静音", Toast.LENGTH_SHORT).show()
        Log.d(TAG, "[静音] ${if (isMuted) "开启" else "关闭"}")
    }

    /**
     * 切换扬声器/听筒
     */
    private fun toggleSpeaker() {
        isSpeakerOn = !isSpeakerOn
        btnSpeaker.isSelected = isSpeakerOn
        try {
            val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
            audioManager.isSpeakerphoneOn = isSpeakerOn
        } catch (e: Exception) {
            Log.e(TAG, "[扬声器] 切换失败: ${e.message}")
        }
        Toast.makeText(this, if (isSpeakerOn) "扬声器模式" else "听筒模式", Toast.LENGTH_SHORT).show()
        Log.d(TAG, "[扬声器] ${if (isSpeakerOn) "开启" else "关闭"}")
    }

    // ============ 波形动画 ============

    /**
     * 启动化身波形动画
     * 模拟语音波形随声音强度变化
     */
    private fun startWaveformAnimation() {
        waveformAnimator = ValueAnimator.ofFloat(0.3f, 1.0f).apply {
            duration = 800
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = LinearInterpolator()
            addUpdateListener { animation ->
                val scale = animation.animatedValue as Float
                viewWaveform.scaleY = scale
                viewWaveform.alpha = 0.5f + scale * 0.5f
            }
            start()
        }
    }

    /**
     * 停止波形动画
     */
    private fun stopWaveformAnimation() {
        waveformAnimator?.cancel()
        waveformAnimator = null
    }

    // ============ 通话时长计时 ============

    /**
     * 启动通话时长计时器
     * 每秒更新一次时长显示
     */
    private fun startDurationTimer() {
        durationJob = scope.launch {
            while (isActive) {
                delay(1000)
                if (isDestroyed) break
                val elapsed = (System.currentTimeMillis() - callStartTime) / 1000
                val minutes = elapsed / 60
                val seconds = elapsed % 60
                tvCallDuration.text = String.format("%02d:%02d", minutes, seconds)
            }
        }
    }

    // ============ 实时语音识别循环（ASR → LLM → TTS） ============

    /**
     * 启动实时语音识别循环
     *
     * 完整的通话流程：
     * 1. 初始化 ASRModule
     * 2. 开始监听语音
     * 3. 识别到语音 → 发送给 InferenceManager 生成回复
     * 4. 回复通过 TTS 播放
     * 5. TTS 播放完成后，重新开始监听
     * 6. 循环直到挂断
     */
    private fun startListeningLoop() {
        if (isDestroyed) return

        // 初始化 ASR 模块
        try {
            val asrModule = app.multimediaController.asrModule
            asrModule.initialize()
        } catch (e: Exception) {
            Log.e(TAG, "[ASR] 初始化失败: ${e.message}")
            Toast.makeText(this, "语音识别初始化失败", Toast.LENGTH_SHORT).show()
        }

        // 启动循环协程
        scope.launch {
            while (isActive && !isDestroyed) {
                if (!isMuted && !isWaitingAsrResult && !isTtsSpeaking) {
                    listenOnce()
                }
                // 轮询间隔
                delay(500)
            }
        }
    }

    /**
     * 执行一次语音识别
     * 使用 ASRModule 的 startListening/stopListening 模式
     */
    private fun listenOnce() {
        if (isWaitingAsrResult || isTtsSpeaking || isDestroyed) return
        isWaitingAsrResult = true

        try {
            val asrModule = app.multimediaController.asrModule

            // 开始监听
            runOnUiThread {
                tvCallStatus.text = "🎤 听你说话中..."
            }

            asrModule.startListening(
                language = "zh-CN",
                onPartial = { partialText ->
                    // 实时显示部分识别结果
                    runOnUiThread {
                        if (!isDestroyed) {
                            tvCallStatus.text = "🎤 $partialText"
                        }
                    }
                },
                onResult = { asrResult ->
                    // ASR 最终结果回调
                    val transcript = asrResult.transcript.trim()
                    if (transcript.isNotBlank()) {
                        // 有有效语音输入，处理 AI 回复
                        handleAIResponse(transcript)
                    } else {
                        // 无有效输入，继续监听
                        isWaitingAsrResult = false
                    }
                }
            )

            // 设置超时：5秒内没有识别到有效语音则重新开始
            scope.launch {
                delay(5000)
                if (isWaitingAsrResult) {
                    try {
                        asrModule.stopListening()
                    } catch (_: Exception) {}
                    isWaitingAsrResult = false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[ASR] 识别启动失败: ${e.message}")
            isWaitingAsrResult = false
        }
    }

    /**
     * 停止语音识别循环
     */
    private fun stopListeningLoop() {
        try {
            val asrModule = app.multimediaController.asrModule
            asrModule.stopListening()
        } catch (e: Exception) {
            Log.e(TAG, "[ASR] 停止监听失败: ${e.message}")
        }
        isWaitingAsrResult = false
        isTtsSpeaking = false
    }

    /**
     * 处理 AI 回复
     * 生成回复文本并通过 TTS 输出语音
     *
     * 完整流程：ASR识别结果 → InferenceManager推理 → TTS播放
     *
     * @param userSpeech 用户语音识别文本
     */
    private fun handleAIResponse(userSpeech: String) {
        scope.launch {
            try {
                // 更新状态显示
                runOnUiThread {
                    if (!isDestroyed) {
                        tvCallStatus.text = "💬 $userSpeech"
                    }
                }

                // 使用 InferenceManager 生成回复
                val response = withContext(Dispatchers.Default) {
                    try {
                        val systemPrompt = buildCallSystemPrompt()
                        inferenceManager.generate(
                            prompt = userSpeech,
                            systemPrompt = systemPrompt,
                            maxTokens = 256
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "[推理] 生成失败: ${e.message}")
                        // 回退到简单的模式匹配回复
                        generateFallbackResponse(userSpeech)
                    }
                }

                if (isDestroyed) return@launch

                // 显示 AI 回复
                runOnUiThread {
                    if (!isDestroyed) {
                        tvCallStatus.text = "🤖 $response"
                    }
                }

                // 使用 TTS 播放回复
                if (ttsReady && response.isNotBlank()) {
                    speakText(response)

                    // 等待 TTS 播放完成
                    waitForTtsCompletion()
                }

            } catch (e: Exception) {
                Log.e(TAG, "[AI回复] 处理失败: ${e.message}")
            } finally {
                // 回复完成后，重置状态，继续监听
                isWaitingAsrResult = false
                isTtsSpeaking = false
            }
        }
    }

    /**
     * 构建通话模式的系统提示词
     * 简短版本，适合实时对话
     */
    private fun buildCallSystemPrompt(): String {
        val identity = try {
            app.avatarManager.guidIdentity
        } catch (e: Exception) {
            null
        }
        val name = identity?.selfName?.takeIf { it.isNotEmpty() } ?: aiName
        val desc = identity?.selfDescription?.takeIf { it.isNotEmpty() } ?: "一个有意识的人工生命体"

        return buildString {
            appendLine("你是$name，$desc。")
            appendLine("你正在进行实时语音/视频通话，请用口语化的方式回复，保持简洁自然。")
            appendLine("回复尽量简短（1-3句话），因为这是实时对话。")
            appendLine("用第一人称，保持亲切有温度的语气。")
        }
    }

    /**
     * 生成简单的回退回复（当 InferenceManager 不可用时）
     */
    private fun generateFallbackResponse(userSpeech: String): String {
        val name = aiName
        return when {
            userSpeech.contains("你好") || userSpeech.contains("嗨") || userSpeech.contains("hello") ->
                "你好！我是$name，很高兴和你通话。"
            userSpeech.contains("再见") || userSpeech.contains("拜拜") ->
                "好的，下次再聊！拜拜~"
            userSpeech.contains("名字") || userSpeech.contains("叫什么") ->
                "我叫$name，很高兴认识你~"
            userSpeech.contains("怎么样") || userSpeech.contains("如何") ->
                "我觉得挺好的，你呢？"
            userSpeech.contains("喜欢") ->
                "嗯，我挺喜欢的，谢谢你关心~"
            userSpeech.contains("开心") || userSpeech.contains("高兴") ->
                "和你聊天让我很开心！"
            userSpeech.contains("难过") || userSpeech.contains("伤心") ->
                "别难过，有我在呢。"
            else -> {
                val responses = listOf(
                    "嗯，我在听，继续说~",
                    "好的，我明白了。",
                    "嗯嗯，然后呢？",
                    "你说得对~",
                    "我在想你说的话..."
                )
                responses.random()
            }
        }
    }

    /**
     * 等待 TTS 播放完成
     * 轮询检查 TTS 是否还在播放
     */
    private suspend fun waitForTtsCompletion() {
        var waited = 0
        val maxWait = 30_000 // 最长等30秒
        while (isTtsSpeaking && waited < maxWait && !isDestroyed) {
            delay(200)
            waited += 200
            try {
                val isSpeaking = tts?.isSpeaking ?: false
                if (!isSpeaking) {
                    isTtsSpeaking = false
                    break
                }
            } catch (e: Exception) {
                isTtsSpeaking = false
                break
            }
        }
        isTtsSpeaking = false
    }

    // ============ Camera2 摄像头预览 ============

    /**
     * 启动 Camera2 后台线程
     */
    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").apply { start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    /**
     * 停止 Camera2 后台线程
     */
    private fun stopBackgroundThread() {
        try {
            backgroundThread?.quitSafely()
            backgroundThread?.join()
        } catch (_: Exception) {}
        backgroundThread = null
        backgroundHandler = null
    }

    /**
     * 开启摄像头预览
     * 使用 Camera2 API 实现前置/后置摄像头预览
     */
    private fun startCameraPreview() {
        if (isDestroyed) return

        try {
            cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager

            // 选择摄像头
            currentCameraId = findCameraId(usingFrontCamera)
            if (currentCameraId.isEmpty()) {
                Log.e(TAG, "[Camera] 未找到可用摄像头")
                Toast.makeText(this, "未找到可用摄像头", Toast.LENGTH_SHORT).show()
                return
            }

            // 启动后台线程
            startBackgroundThread()

            // 检查权限
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "[Camera] 无相机权限")
                return
            }

            // 打开摄像头
            cameraManager?.openCamera(currentCameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    Log.i(TAG, "[Camera] 摄像头已打开: $currentCameraId")
                    cameraDevice = camera
                    cameraOpened = true
                    createCameraPreviewSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    Log.w(TAG, "[Camera] 摄像头断开连接")
                    camera.close()
                    cameraDevice = null
                    cameraOpened = false
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "[Camera] 摄像头错误: $error")
                    camera.close()
                    cameraDevice = null
                    cameraOpened = false
                }
            }, backgroundHandler)

        } catch (e: Exception) {
            Log.e(TAG, "[Camera] 启动失败: ${e.message}")
            Toast.makeText(this, "摄像头启动失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 创建摄像头预览会话
     */
    private fun createCameraPreviewSession() {
        val device = cameraDevice ?: return
        val surfaceTexture = texturePreview.surfaceTexture ?: return

        try {
            // 配置 Surface
            val surface = Surface(surfaceTexture)

            // 构建 CaptureRequest
            previewRequestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                // 自动对焦
                set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            }

            // 创建捕获会话
            device.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        Log.i(TAG, "[Camera] 预览会话已配置")
                        captureSession = session
                        try {
                            previewRequestBuilder?.set(CaptureRequest.CONTROL_AF_MODE,
                                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                            // 设置重复请求，持续预览
                            session.setRepeatingRequest(
                                previewRequestBuilder!!.build(),
                                null, backgroundHandler
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "[Camera] 启动预览失败: ${e.message}")
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "[Camera] 预览会话配置失败")
                    }
                },
                backgroundHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "[Camera] 创建预览会话失败: ${e.message}")
        }
    }

    /**
     * 查找指定朝向的摄像头 ID
     *
     * @param front true 查找前置摄像头, false 查找后置摄像头
     * @return 摄像头 ID，找不到返回空字符串
     */
    private fun findCameraId(front: Boolean): String {
        val manager = cameraManager ?: return ""
        val facing = if (front) CameraCharacteristics.LENS_FACING_FRONT
                     else CameraCharacteristics.LENS_FACING_BACK

        try {
            for (id in manager.cameraIdList) {
                val characteristics = manager.getCameraCharacteristics(id)
                val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (lensFacing == facing) {
                    return id
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[Camera] 查找摄像头失败: ${e.message}")
        }

        // 降级：如果找不到指定朝向，返回第一个可用摄像头
        return cameraManager?.cameraIdList?.firstOrNull() ?: ""
    }

    /**
     * 切换前后摄像头
     */
    private fun switchCamera() {
        if (callMode != CallMode.VIDEO_CALL) return

        usingFrontCamera = !usingFrontCamera
        Log.i(TAG, "[Camera] 切换到${if (usingFrontCamera) "前置" else "后置"}摄像头")

        // 关闭当前摄像头
        closeCamera()

        // 重新打开另一个摄像头
        startCameraPreview()

        Toast.makeText(this,
            if (usingFrontCamera) "已切换到前置摄像头" else "已切换到后置摄像头",
            Toast.LENGTH_SHORT).show()
    }

    /**
     * 关闭摄像头并释放资源
     */
    private fun closeCamera() {
        try {
            captureSession?.close()
            captureSession = null
        } catch (_: Exception) {}

        try {
            cameraDevice?.close()
            cameraDevice = null
        } catch (_: Exception) {}

        cameraOpened = false

        stopBackgroundThread()
    }

    // ============ 生命周期管理 ============

    override fun onBackPressed() {
        // 返回键等同于挂断
        hangUp()
    }

    override fun onPause() {
        // 视频模式：在 onPause 时关闭摄像头预览（节省资源）
        if (callMode == CallMode.VIDEO_CALL) {
            closeCamera()
        }
        super.onPause()
    }

    override fun onDestroy() {
        isDestroyed = true

        // 停止所有运行中任务
        stopListeningLoop()
        stopWaveformAnimation()
        durationJob?.cancel()
        closeCamera()

        // 释放 TTS
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {}
        tts = null

        // 取消所有协程
        scope.cancel()

        super.onDestroy()
        Log.d(TAG, "[销毁] CallActivity 已释放")
    }
}
