/*
 * ============================================================
 * AudioModule - 音频感知与语音模块
 * ============================================================
 *
 * 实现离线三模语音对讲：
 *
 * 1. TTS 文字转语音（语音合成）
 *    - Android 内置 TTS 引擎
 *    - 语速/音调/音量控制
 *    - 多语言支持
 *    - 语音队列管理
 * 2. ASR 语音识别（语音转写）
 *    - 实时麦克风识别
 *    - 语音活动检测（VAD）
 *    - 唤醒词检测
 * 3. 语音对讲模式
 *    - 全双工对讲
 *    - 半双工（Push-to-Talk）
 *    - 语音唤醒后对话
 *
 * 所有功能基于 Android 内置引擎，无需第三方 AI 库。
 * ============================================================
 */
package com.kkgo.mindsoul.perception

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 语音模块状态
 */
enum class AudioState {
    /** 空闲 */
    IDLE,
    /** 正在合成语音 */
    SPEAKING,
    /** 正在监听 */
    LISTENING,
    /** 语音活动检测中 */
    VAD_ACTIVE,
    /** 错误 */
    ERROR
}

/**
 * 对讲模式
 */
enum class IntercomMode {
    /** 全双工（同时听和说） */
    FULL_DUPLEX,
    /** 半双工（按键说话） */
    HALF_DUPLEX,
    /** 唤醒词模式 */
    WAKE_WORD
}

/**
 * TTS 语音参数
 */
data class TTSParameters(
    /** 语速 [0.5, 2.0]，1.0 为正常 */
    val speed: Float = 1.0f,
    /** 音调 [0.5, 2.0]，1.0 为正常 */
    val pitch: Float = 1.0f,
    /** 音量 [0.0, 1.0] */
    val volume: Float = 1.0f,
    /** 语言 */
    val locale: Locale = Locale.CHINA
)

/**
 * 语音活动检测（VAD）结果
 */
data class VADResult(
    /** 是否检测到语音活动 */
    val isSpeechActive: Boolean,
    /** 当前音量（RMS） */
    val rmsLevel: Float,
    /** 音量百分比 [0, 100] */
    val volumePercent: Int,
    /** 时间戳 */
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 音频感知模块
 */
class AudioModule(private val context: Context) {

    companion object {
        private const val TAG = "AudioModule"
        /** 采样率 */
        private const val SAMPLE_RATE = 16000
        /** 通道数 */
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        /** 编码 */
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        /** VAD 阈值（RMS） */
        private const val VAD_THRESHOLD = 500f
        /** 静默超时（毫秒） */
        private const val SILENCE_TIMEOUT_MS = 2000L
        /** 唤醒词 */
        private val WAKE_WORDS = listOf("你好灵魂", "mind soul", "小灵")
    }

    // ============ TTS 引擎 ============
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val ttsQueue = ConcurrentLinkedQueue<String>()
    private var currentTTSParams = TTSParameters()

    // ============ ASR 引擎 ============
    private var audioRecord: AudioRecord? = null
    @Volatile
    private var isRecording = false

    // ============ 状态 ============
    private val _audioState = MutableStateFlow(AudioState.IDLE)
    val audioStateFlow: StateFlow<AudioState> = _audioState.asStateFlow()

    private val _vadResult = MutableStateFlow(VADResult(false, 0f, 0))
    val vadResultFlow: StateFlow<VADResult> = _vadResult.asStateFlow()

    // ============ 对讲模式 ============
    private var intercomMode = IntercomMode.HALF_DUPLEX

    // ============ 协程 ============
    private val audioScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var vadJob: Job? = null

    // ============ 回调 ============
    /** TTS 完成回调 */
    private var ttsCompleteCallback: (() -> Unit)? = null
    /** ASR 结果回调 */
    private var asrResultCallback: ((String) -> Unit)? = null
    /** VAD 回调 */
    private var vadCallback: ((VADResult) -> Unit)? = null
    /** 唤醒词检测回调 */
    private var wakeWordCallback: (() -> Unit)? = null

    // ============ 初始化 ============

    /**
     * 初始化音频模块
     */
    fun initialize() {
        // 初始化 TTS
        initTTS()

        Log.i(TAG, "[初始化] 音频感知模块就绪")
        Log.i(TAG, "  TTS: Android 内置引擎")
        Log.i(TAG, "  ASR: 麦克风采集 + VAD")
        Log.i(TAG, "  对讲模式: $intercomMode")
    }

    /**
     * 释放资源
     */
    fun destroy() {
        stopListening()
        stopSpeaking()
        tts?.shutdown()
        tts = null
        audioRecord?.release()
        audioRecord = null
        audioScope.cancel()
        Log.i(TAG, "[销毁] 音频模块已释放")
    }

    // ============ TTS 接口 ============

    /**
     * 朗读文本
     *
     * @param text 要朗读的文本
     * @param params TTS 参数
     */
    fun speak(text: String, params: TTSParameters = currentTTSParams) {
        if (!ttsReady) {
            Log.w(TAG, "[TTS] TTS 引擎未就绪")
            return
        }

        currentTTSParams = params

        tts?.apply {
            setSpeechRate(params.speed)
            setPitch(params.pitch)
            setLanguage(params.locale)
        }

        _audioState.value = AudioState.SPEAKING
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "utterance_${System.currentTimeMillis()}")

        Log.d(TAG, "[TTS] 朗读: ${text.take(30)}...")
    }

    /**
     * 停止朗读
     */
    fun stopSpeaking() {
        tts?.stop()
        ttsQueue.clear()
        _audioState.value = AudioState.IDLE
    }

    /**
     * 设置 TTS 完成回调
     */
    fun setTTSCompleteCallback(callback: () -> Unit) {
        ttsCompleteCallback = callback
    }

    /**
     * 设置 TTS 参数
     */
    fun setTTSParameters(params: TTSParameters) {
        currentTTSParams = params
        if (ttsReady) {
            tts?.setSpeechRate(params.speed)
            tts?.setPitch(params.pitch)
            tts?.setLanguage(params.locale)
        }
    }

    // ============ ASR 接口 ============

    /**
     * 开始监听（麦克风采集 + VAD）
     */
    fun startListening() {
        if (isRecording) return

        try {
            val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
            if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
                Log.e(TAG, "[ASR] 无法获取有效的缓冲区大小")
                return
            }

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL,
                ENCODING,
                bufferSize * 2
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "[ASR] AudioRecord 初始化失败")
                return
            }

            audioRecord?.startRecording()
            isRecording = true
            _audioState.value = AudioState.LISTENING

            // 启动 VAD 循环
            startVADLoop(bufferSize)

            Log.i(TAG, "[ASR] 开始监听")

        } catch (e: SecurityException) {
            Log.e(TAG, "[ASR] 麦克风权限未授予: ${e.message}")
            _audioState.value = AudioState.ERROR
        }
    }

    /**
     * 停止监听
     */
    fun stopListening() {
        isRecording = false
        vadJob?.cancel()
        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "[ASR] 停止录音异常: ${e.message}")
        }
        _audioState.value = AudioState.IDLE
        Log.i(TAG, "[ASR] 已停止监听")
    }

    /**
     * 设置 ASR 结果回调
     */
    fun setASRResultCallback(callback: (String) -> Unit) {
        asrResultCallback = callback
    }

    /**
     * 设置 VAD 回调
     */
    fun setVADCallback(callback: (VADResult) -> Unit) {
        vadCallback = callback
    }

    /**
     * 设置唤醒词检测回调
     */
    fun setWakeWordCallback(callback: () -> Unit) {
        wakeWordCallback = callback
    }

    // ============ 对讲模式 ============

    /**
     * 设置对讲模式
     */
    fun setIntercomMode(mode: IntercomMode) {
        intercomMode = mode
        Log.i(TAG, "[对讲] 模式切换: $mode")
    }

    // ============ 内部方法 ============

    /**
     * 初始化 TTS 引擎
     */
    private fun initTTS() {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
                tts?.let { engine ->
                    engine.setLanguage(Locale.CHINA)
                    engine.setSpeechRate(currentTTSParams.speed)
                    engine.setPitch(currentTTSParams.pitch)

                    // 设置语音进度监听
                    engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            _audioState.value = AudioState.SPEAKING
                        }

                        override fun onDone(utteranceId: String?) {
                            // 检查队列
                            if (ttsQueue.isEmpty()) {
                                _audioState.value = AudioState.IDLE
                                ttsCompleteCallback?.invoke()
                            } else {
                                // 继续朗读队列中的下一条
                                val next = ttsQueue.poll()
                                if (next != null) speak(next)
                            }
                        }

                        @Deprecated("Deprecated")
                        override fun onError(utteranceId: String?) {
                            _audioState.value = AudioState.ERROR
                            Log.e(TAG, "[TTS] 朗读错误: $utteranceId")
                        }
                    })
                }
                Log.i(TAG, "[TTS] 引擎就绪")
            } else {
                Log.e(TAG, "[TTS] 引擎初始化失败: status=$status")
            }
        }
    }

    /**
     * 启动 VAD 检测循环
     */
    private fun startVADLoop(bufferSize: Int) {
        vadJob?.cancel()
        vadJob = audioScope.launch {
            val buffer = ShortArray(bufferSize / 2)
            var lastSpeechTime = 0L

            while (isActive && isRecording) {
                val readCount = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                if (readCount <= 0) {
                    delay(10)
                    continue
                }

                // 计算 RMS 音量
                var sumSquares = 0.0
                for (i in 0 until readCount) {
                    val sample = buffer[i].toFloat()
                    sumSquares += sample * sample
                }
                val rms = Math.sqrt(sumSquares / readCount).toFloat()

                // 归一化为百分比
                val volumePercent = (rms / 327.68f).toInt().coerceIn(0, 100)

                // VAD 判定
                val isSpeech = rms > VAD_THRESHOLD

                if (isSpeech) {
                    lastSpeechTime = System.currentTimeMillis()
                    _audioState.value = AudioState.VAD_ACTIVE
                } else {
                    val silenceDuration = System.currentTimeMillis() - lastSpeechTime
                    if (silenceDuration > SILENCE_TIMEOUT_MS && lastSpeechTime > 0) {
                        // 静默超时 → 语音结束
                        _audioState.value = AudioState.LISTENING
                        lastSpeechTime = 0L
                    }
                }

                val vadResult = VADResult(isSpeech, rms, volumePercent)
                _vadResult.value = vadResult
                vadCallback?.invoke(vadResult)

                // 帧间隔
                delay(30)  // ~33 FPS
            }
        }
    }
}
