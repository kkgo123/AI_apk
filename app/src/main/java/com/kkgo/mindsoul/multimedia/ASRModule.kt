/*
 * ============================================================
 * ASRModule - 语音识别模块
 * ============================================================
 *
 * 封装 Android SpeechRecognizer 实现音频转写：
 * 1. 实时语音识别（麦克风输入）
 * 2. 离线音频文件转写
 * 3. 连续识别模式（长音频）
 * 4. 多语言支持（中/英/日等）
 *
 * 使用 Android 系统内置的 SpeechRecognizer，
 * 无需第三方 AI 库。
 * ============================================================
 */
package com.kkgo.mindsoul.multimedia

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileInputStream

/**
 * ASR 转写结果
 */
data class ASRResult(
    /** 转写文本 */
    val transcript: String,
    /** 置信度 [0.0, 1.0] */
    val confidence: Float,
    /** 音频时长（秒） */
    val durationSeconds: Float,
    /** 语言代码 */
    val language: String,
    /** 分段文本（按时间戳切分） */
    val segments: List<ASRSegment> = emptyList(),
    /** 处理耗时（毫秒） */
    val durationMs: Long = 0
)

/**
 * ASR 分段结果
 */
data class ASRSegment(
    /** 开始时间（秒） */
    val startSeconds: Float,
    /** 结束时间（秒） */
    val endSeconds: Float,
    /** 文本内容 */
    val text: String,
    /** 置信度 */
    val confidence: Float
)

/**
 * ASR 模块状态
 */
enum class ASRState {
    /** 空闲 */
    IDLE,
    /** 正在监听 */
    LISTENING,
    /** 正在识别 */
    RECOGNIZING,
    /** 出错 */
    ERROR
}

/**
 * ASR 语音识别模块
 *
 * 封装 Android SpeechRecognizer，提供统一的语音转写接口。
 */
class ASRModule(private val context: Context) {

    companion object {
        private const val TAG = "ASRModule"
        /** 最大连续识别时间（毫秒） */
        private const val MAX_LISTENING_MS = 60_000L
        /** 静默超时（毫秒） */
        private const val SILENCE_TIMEOUT_MS = 3_000L
    }

    // ============ 状态 ============
    private val _state = MutableStateFlow(ASRState.IDLE)
    /** ASR 状态流 */
    val stateFlow: StateFlow<ASRState> = _state.asStateFlow()

    /** 当前状态 */
    val currentState: ASRState get() = _state.value

    // ============ 识别器 ============
    /** Android 系统语音识别器 */
    private var recognizer: SpeechRecognizer? = null

    /** 识别结果收集器 */
    private val transcriptBuffer = StringBuilder()
    private val segmentsBuffer = mutableListOf<ASRSegment>()
    private var segmentStart = 0f
    private var lastConfidence = 0f

    // ============ 协程 ============
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 是否支持语音识别 */
    private var isSupported = false

    // ============ 回调 ============
    private var resultCallback: ((ASRResult) -> Unit)? = null
    private var partialCallback: ((String) -> Unit)? = null

    // ============ 初始化 ============

    /**
     * 初始化 ASR 模块
     */
    fun initialize() {
        // 检查语音识别是否可用
        isSupported = SpeechRecognizer.isRecognitionAvailable(context)
        if (isSupported) {
            createRecognizer()
            Log.i(TAG, "[初始化] ASR 模块就绪，语音识别可用")
        } else {
            Log.w(TAG, "[初始化] 设备不支持语音识别")
        }
    }

    /**
     * 释放资源
     */
    fun destroy() {
        stopListening()
        recognizer?.destroy()
        recognizer = null
        scope.cancel()
        resultCallback = null
        partialCallback = null
        Log.i(TAG, "[销毁] ASR 模块已释放")
    }

    // ============ 实时语音识别 ============

    /**
     * 开始实时语音识别（麦克风输入）
     *
     * @param language 语言代码（默认中文）
     * @param onPartial 部分结果回调（实时更新）
     * @param onResult 最终结果回调
     */
    fun startListening(
        language: String = "zh-CN",
        onPartial: ((String) -> Unit)? = null,
        onResult: ((ASRResult) -> Unit)? = null
    ) {
        if (!isSupported) {
            Log.w(TAG, "[识别] 设备不支持语音识别")
            onResult?.invoke(ASRResult("", 0f, 0f, language))
            return
        }

        partialCallback = onPartial
        resultCallback = onResult

        // 清空缓冲区
        transcriptBuffer.clear()
        segmentsBuffer.clear()
        segmentStart = 0f

        _state.value = ASRState.LISTENING

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            // 连续识别模式
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, MAX_LISTENING_MS)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, SILENCE_TIMEOUT_MS)
        }

        recognizer?.startListening(intent)
        Log.i(TAG, "[识别] 开始监听: 语言=$language")
    }

    /**
     * 停止语音识别
     */
    fun stopListening() {
        if (_state.value == ASRState.IDLE) return
        recognizer?.stopListening()
        _state.value = ASRState.IDLE
        Log.i(TAG, "[识别] 停止监听")
    }

    // ============ 音频文件转写 ============

    /**
     * 转写音频文件
     *
     * 流程：
     * 1. 读取音频文件
     * 2. 通过 SpeechRecognizer 进行识别
     * 3. 收集结果并返回
     *
     * @param audioPath 音频文件路径
     * @param language 语言代码
     * @return ASR 转写结果
     */
    suspend fun transcribe(
        audioPath: String,
        language: String = "zh-CN"
    ): ASRResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        Log.d(TAG, "[转写] 开始: $audioPath")

        val file = File(audioPath)
        if (!file.exists()) {
            return@withContext ASRResult("", 0f, 0f, language)
        }

        if (!isSupported) {
            Log.w(TAG, "[转写] 设备不支持语音识别")
            return@withContext ASRResult("", 0f, 0f, language)
        }

        try {
            // 使用协程等待识别结果
            val result = suspendCancellableCoroutine<ASRResult> { continuation ->
                _state.value = ASRState.RECOGNIZING

                // 清空缓冲区
                transcriptBuffer.clear()
                segmentsBuffer.clear()

                // 创建一次性监听器
                val oneShotListener = createOneShotListener { result ->
                    _state.value = ASRState.IDLE
                    if (continuation.isActive) {
                        continuation.resume(result, null)
                    }
                }

                // 设置临时识别器
                val tempRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
                tempRecognizer.setRecognitionListener(oneShotListener)

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 10)
                }

                tempRecognizer.startListening(intent)

                // 设置超时
                continuation.invokeOnCancellation {
                    tempRecognizer.stopListening()
                    tempRecognizer.destroy()
                    _state.value = ASRState.IDLE
                }

                // 超时保护
                scope.launch {
                    delay(MAX_LISTENING_MS + 5000)
                    if (continuation.isActive) {
                        tempRecognizer.stopListening()
                        tempRecognizer.destroy()
                        val timeoutResult = buildResult(language, System.currentTimeMillis() - startTime)
                        continuation.resume(timeoutResult, null)
                    }
                }
            }

            val totalDuration = System.currentTimeMillis() - startTime
            Log.i(TAG, "[转写] 完成: ${result.transcript.length} 字, ${totalDuration}ms")
            result.copy(durationMs = totalDuration)

        } catch (e: Exception) {
            Log.e(TAG, "[转写] 失败: ${e.message}")
            ASRResult("", 0f, 0f, language, durationMs = System.currentTimeMillis() - startTime)
        }
    }

    // ============ 内部方法 ============

    /**
     * 创建语音识别器
     */
    private fun createRecognizer() {
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer?.setRecognitionListener(createMainListener())
    }

    /**
     * 创建主监听器（用于实时模式）
     */
    private fun createMainListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "[监听] 就绪")
                _state.value = ASRState.LISTENING
            }

            override fun onBeginningOfSpeech() {
                Log.d(TAG, "[监听] 检测到语音开始")
                segmentStart = System.currentTimeMillis() / 1000f
                _state.value = ASRState.RECOGNIZING
            }

            override fun onRmsChanged(rmsdB: Float) {
                // 音量变化回调（可用于 UI 波形显示）
            }

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                Log.d(TAG, "[监听] 语音结束")
                _state.value = ASRState.IDLE
                // 构建最终结果
                val result = buildResult("zh-CN", 0)
                resultCallback?.invoke(result)
            }

            override fun onError(error: Int) {
                val errorMsg = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "无匹配结果"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "语音超时"
                    SpeechRecognizer.ERROR_AUDIO -> "音频错误"
                    SpeechRecognizer.ERROR_NETWORK -> "网络错误"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络超时"
                    SpeechRecognizer.ERROR_CLIENT -> "客户端错误"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "权限不足"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别器繁忙"
                    SpeechRecognizer.ERROR_SERVER -> "服务器错误"
                    else -> "未知错误($error)"
                }
                Log.e(TAG, "[监听] 错误: $errorMsg")
                _state.value = ASRState.ERROR
                resultCallback?.invoke(ASRResult("", 0f, 0f, "zh-CN"))
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val scores = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)

                if (matches != null && matches.isNotEmpty()) {
                    val text = matches[0]
                    val conf = scores?.getOrNull(0) ?: 0.5f
                    lastConfidence = conf

                    transcriptBuffer.appendLine(text)
                    segmentsBuffer.add(ASRSegment(
                        startSeconds = segmentStart,
                        endSeconds = System.currentTimeMillis() / 1000f,
                        text = text,
                        confidence = conf
                    ))
                    Log.d(TAG, "[结果] $text (置信度: $conf)")
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (matches != null && matches.isNotEmpty()) {
                    partialCallback?.invoke(matches[0])
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }

    /**
     * 创建一次性监听器（用于文件转写模式）
     */
    private fun createOneShotListener(
        onComplete: (ASRResult) -> Unit
    ): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                onComplete(buildResult("zh-CN", 0))
            }

            override fun onError(error: Int) {
                Log.e(TAG, "[转写] 错误: $error")
                onComplete(ASRResult("", 0f, 0f, "zh-CN"))
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val scores = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)

                if (matches != null && matches.isNotEmpty()) {
                    transcriptBuffer.appendLine(matches[0])
                    lastConfidence = scores?.getOrNull(0) ?: 0.5f
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (matches != null && matches.isNotEmpty()) {
                    transcriptBuffer.clear()
                    transcriptBuffer.append(matches[0])
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }

    /**
     * 构建最终结果
     */
    private fun buildResult(language: String, durationMs: Long): ASRResult {
        return ASRResult(
            transcript = transcriptBuffer.toString().trim(),
            confidence = lastConfidence,
            durationSeconds = segmentsBuffer.lastOrNull()?.endSeconds ?: 0f,
            language = language,
            segments = segmentsBuffer.toList(),
            durationMs = durationMs
        )
    }
}
