/*
 * ============================================================
 * MultimediaController - 多媒体统一控制器
 * ============================================================
 *
 * 统一管控所有多媒体处理模块：
 * 1. 全局总开关（一键关闭所有多媒体处理）
 * 2. 子模块生命周期管理（OCR/ASR/文档解析）
 * 3. 统一交互话术逻辑（标准化输入输出提示）
 * 4. 资源调度（并发控制、内存管理）
 * 5. 处理结果聚合与分发
 *
 * 设计理念：
 * - 总开关由权限系统和用户偏好双重控制
 * - 各子模块独立初始化，按需加载
 * - 统一的结果回调和错误处理
 * ============================================================
 */
package com.kkgo.mindsoul.multimedia

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 多媒体处理类型
 */
enum class MediaType {
    /** 图片 OCR */
    IMAGE_OCR,
    /** 视频字幕提取 */
    VIDEO_SUBTITLE,
    /** 音频 ASR 转写 */
    AUDIO_ASR,
    /** 文档解析 */
    DOCUMENT_PARSE
}

/**
 * 处理状态
 */
enum class ProcessState {
    /** 空闲 */
    IDLE,
    /** 处理中 */
    PROCESSING,
    /** 完成 */
    COMPLETED,
    /** 失败 */
    FAILED,
    /** 已取消 */
    CANCELLED
}

/**
 * 多媒体处理结果
 */
data class MediaProcessResult(
    /** 处理类型 */
    val type: MediaType,
    /** 处理状态 */
    val state: ProcessState,
    /** 提取的文本内容 */
    val extractedText: String = "",
    /** 置信度 [0.0, 1.0] */
    val confidence: Float = 0f,
    /** 处理耗时（毫秒） */
    val durationMs: Long = 0,
    /** 错误信息 */
    val errorMessage: String? = null,
    /** 额外元数据 */
    val metadata: Map<String, String> = emptyMap()
)

/**
 * 交互话术
 */
data class InteractionPrompt(
    /** 开始处理时的提示 */
    val startPrompt: String,
    /** 处理中的进度提示 */
    val progressPrompt: String,
    /** 成功完成提示 */
    val successPrompt: String,
    /** 失败提示 */
    val failurePrompt: String
)

/**
 * 处理结果监听器
 */
interface MediaProcessListener {
    /** 处理开始 */
    fun onStart(type: MediaType, source: String)
    /** 进度更新 */
    fun onProgress(type: MediaType, progress: Float)
    /** 处理完成 */
    fun onComplete(result: MediaProcessResult)
    /** 发生错误 */
    fun onError(type: MediaType, error: String)
}

/**
 * 多媒体统一控制器
 */
class MultimediaController(private val context: Context) {

    companion object {
        private const val TAG = "MultimediaCtrl"
        private const val PREF_NAME = "mindsoul_multimedia"
        private const val KEY_MASTER_SWITCH = "master_enabled"
        /** 最大并发处理数 */
        private const val MAX_CONCURRENT = 3
    }

    // ============ 偏好设置 ============
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    // ============ 总开关 ============
    private val _masterEnabled = MutableStateFlow(loadMasterSwitch())
    /** 总开关状态流 */
    val masterEnabledFlow: StateFlow<Boolean> = _masterEnabled.asStateFlow()
    /** 总开关快捷访问 */
    val isMasterEnabled: Boolean get() = _masterEnabled.value

    // ============ 子模块 ============
    /** OCR 模块 */
    lateinit var ocrModule: OCRModule
        private set
    /** ASR 模块 */
    lateinit var asrModule: ASRModule
        private set
    /** 文档解析模块 */
    lateinit var documentParser: DocumentParser
        private set

    // ============ 并发控制 ============
    /** 信号量：限制并发数 */
    private val semaphore = kotlinx.coroutines.sync.Semaphore(MAX_CONCURRENT)

    // ============ 处理作用域 ============
    private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** 当前活跃的处理任务 */
    private val activeJobs = mutableMapOf<String, Job>()

    // ============ 监听器 ============
    private val listeners = mutableListOf<MediaProcessListener>()

    // ============ 话术模板 ============
    private val prompts = mapOf(
        MediaType.IMAGE_OCR to InteractionPrompt(
            startPrompt = "📷 正在识别图片中的文字...",
            progressPrompt = "📷 OCR 识别中，请稍候...",
            successPrompt = "✅ 图片文字识别完成",
            failurePrompt = "❌ 图片识别失败"
        ),
        MediaType.VIDEO_SUBTITLE to InteractionPrompt(
            startPrompt = "🎬 正在提取视频字幕...",
            progressPrompt = "🎬 视频帧分析中...",
            successPrompt = "✅ 视频字幕提取完成",
            failurePrompt = "❌ 字幕提取失败"
        ),
        MediaType.AUDIO_ASR to InteractionPrompt(
            startPrompt = "🎤 正在转录音频内容...",
            progressPrompt = "🎤 语音识别中...",
            successPrompt = "✅ 音频转写完成",
            failurePrompt = "❌ 语音识别失败"
        ),
        MediaType.DOCUMENT_PARSE to InteractionPrompt(
            startPrompt = "📄 正在解析文档...",
            progressPrompt = "📄 文档解析中...",
            successPrompt = "✅ 文档解析完成",
            failurePrompt = "❌ 文档解析失败"
        )
    )

    // ============ 初始化 ============

    /**
     * 初始化多媒体控制器及所有子模块
     */
    fun initialize() {
        // 初始化子模块
        ocrModule = OCRModule(context)
        ocrModule.initialize()
        Log.i(TAG, "[初始化] OCR 模块就绪")

        asrModule = ASRModule(context)
        asrModule.initialize()
        Log.i(TAG, "[初始化] ASR 模块就绪")

        documentParser = DocumentParser(context)
        documentParser.initialize()
        Log.i(TAG, "[初始化] 文档解析模块就绪")

        Log.i(TAG, "[初始化] 多媒体控制器就绪, 总开关: ${if (isMasterEnabled) "开" else "关"}")
    }

    /**
     * 释放资源
     */
    fun destroy() {
        activeJobs.values.forEach { it.cancel() }
        activeJobs.clear()
        processScope.cancel()
        ocrModule.destroy()
        asrModule.destroy()
        documentParser.destroy()
        listeners.clear()
        Log.i(TAG, "[销毁] 多媒体控制器已释放")
    }

    // ============ 总开关控制 ============

    /**
     * 设置总开关
     */
    fun setMasterEnabled(enabled: Boolean) {
        _masterEnabled.value = enabled
        prefs.edit().putBoolean(KEY_MASTER_SWITCH, enabled).apply()
        Log.i(TAG, "[总开关] ${if (enabled) "已开启" else "已关闭"}")

        // 关闭时取消所有正在进行的处理
        if (!enabled) {
            cancelAll()
        }
    }

    // ============ 处理接口 ============

    /**
     * 提交图片 OCR 处理
     *
     * @param imagePath 图片文件路径
     * @param taskId 任务ID（用于跟踪和取消）
     * @return 处理结果 Deferred
     */
    fun submitOCR(imagePath: String, taskId: String = "ocr_${System.nanoTime()}"): Deferred<MediaProcessResult> {
        return submitProcess(taskId, MediaType.IMAGE_OCR, imagePath) {
            val startTime = System.currentTimeMillis()
            val ocrResult = ocrModule.recognize(imagePath)
            MediaProcessResult(
                type = MediaType.IMAGE_OCR,
                state = ProcessState.COMPLETED,
                extractedText = ocrResult.text,
                confidence = ocrResult.confidence,
                durationMs = System.currentTimeMillis() - startTime,
                metadata = mapOf("source" to imagePath)
            )
        }
    }

    /**
     * 提交音频 ASR 处理
     *
     * @param audioPath 音频文件路径
     * @param taskId 任务ID
     * @return 处理结果 Deferred
     */
    fun submitASR(audioPath: String, taskId: String = "asr_${System.nanoTime()}"): Deferred<MediaProcessResult> {
        return submitProcess(taskId, MediaType.AUDIO_ASR, audioPath) {
            val startTime = System.currentTimeMillis()
            val asrResult = asrModule.transcribe(audioPath)
            MediaProcessResult(
                type = MediaType.AUDIO_ASR,
                state = ProcessState.COMPLETED,
                extractedText = asrResult.transcript,
                confidence = asrResult.confidence,
                durationMs = System.currentTimeMillis() - startTime,
                metadata = mapOf("source" to audioPath, "duration" to asrResult.durationSeconds.toString())
            )
        }
    }

    /**
     * 提交文档解析
     *
     * @param filePath 文件路径
     * @param taskId 任务ID
     * @return 处理结果 Deferred
     */
    fun submitDocumentParse(filePath: String, taskId: String = "doc_${System.nanoTime()}"): Deferred<MediaProcessResult> {
        return submitProcess(taskId, MediaType.DOCUMENT_PARSE, filePath) {
            val startTime = System.currentTimeMillis()
            val docResult = documentParser.parse(filePath)
            MediaProcessResult(
                type = MediaType.DOCUMENT_PARSE,
                state = ProcessState.COMPLETED,
                extractedText = docResult.text,
                confidence = 1.0f,
                durationMs = System.currentTimeMillis() - startTime,
                metadata = mapOf(
                    "source" to filePath,
                    "format" to docResult.format,
                    "pages" to docResult.pageCount.toString()
                )
            )
        }
    }

    /**
     * 提交视频字幕提取
     */
    fun submitVideoSubtitle(videoPath: String, taskId: String = "vid_${System.nanoTime()}"): Deferred<MediaProcessResult> {
        return submitProcess(taskId, MediaType.VIDEO_SUBTITLE, videoPath) {
            val startTime = System.currentTimeMillis()
            val result = ocrModule.extractVideoSubtitles(videoPath)
            MediaProcessResult(
                type = MediaType.VIDEO_SUBTITLE,
                state = ProcessState.COMPLETED,
                extractedText = result,
                confidence = 0.8f,
                durationMs = System.currentTimeMillis() - startTime,
                metadata = mapOf("source" to videoPath)
            )
        }
    }

    /**
     * 取消指定任务
     */
    fun cancelTask(taskId: String) {
        activeJobs[taskId]?.cancel()
        activeJobs.remove(taskId)
        Log.d(TAG, "[取消] 任务: $taskId")
    }

    /**
     * 取消所有任务
     */
    fun cancelAll() {
        activeJobs.values.forEach { it.cancel() }
        activeJobs.clear()
        Log.i(TAG, "[取消] 所有任务已取消")
    }

    // ============ 话术接口 ============

    /**
     * 获取指定类型的交互话术
     */
    fun getPrompt(type: MediaType): InteractionPrompt {
        return prompts[type] ?: InteractionPrompt(
            "处理中...", "处理中...", "处理完成", "处理失败"
        )
    }

    /**
     * 生成标准化的结果播报话术
     */
    fun formatResultSpeech(result: MediaProcessResult): String {
        val prompt = getPrompt(result.type)
        return when (result.state) {
            ProcessState.COMPLETED -> {
                val wordCount = result.extractedText.length
                "${prompt.successPrompt}，共提取 ${wordCount} 字，" +
                "耗时 ${result.durationMs}ms，置信度 ${"%.1f".format(result.confidence * 100)}%"
            }
            ProcessState.FAILED -> "${prompt.failurePrompt}: ${result.errorMessage ?: "未知错误"}"
            ProcessState.CANCELLED -> "已取消 ${result.type.name} 处理"
            else -> prompt.progressPrompt
        }
    }

    // ============ 监听器管理 ============

    fun addListener(listener: MediaProcessListener) {
        synchronized(listeners) { listeners.add(listener) }
    }

    fun removeListener(listener: MediaProcessListener) {
        synchronized(listeners) { listeners.remove(listener) }
    }

    // ============ 内部方法 ============

    /**
     * 通用提交流程
     */
    private fun submitProcess(
        taskId: String,
        type: MediaType,
        source: String,
        block: suspend () -> MediaProcessResult
    ): Deferred<MediaProcessResult> {
        if (!isMasterEnabled) {
            Log.w(TAG, "[拒绝] 总开关关闭，拒绝处理: $type")
            return processScope.async {
                MediaProcessResult(type, ProcessState.FAILED, errorMessage = "多媒体总开关已关闭")
            }
        }

        val deferred = processScope.async {
            // 并发控制
            semaphore.acquire()
            try {
                // 通知监听器
                notifyStart(type, source)

                val result = try {
                    block()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "[处理失败] $type: ${e.message}")
                    notifyError(type, e.message ?: "未知错误")
                    MediaProcessResult(
                        type = type,
                        state = ProcessState.FAILED,
                        errorMessage = e.message,
                        durationMs = 0
                    )
                }

                notifyComplete(result)
                result
            } finally {
                semaphore.release()
                activeJobs.remove(taskId)
            }
        }

        activeJobs[taskId] = deferred
        return deferred
    }

    private fun notifyStart(type: MediaType, source: String) {
        synchronized(listeners) { listeners.forEach { it.onStart(type, source) } }
    }

    private fun notifyProgress(type: MediaType, progress: Float) {
        synchronized(listeners) { listeners.forEach { it.onProgress(type, progress) } }
    }

    private fun notifyComplete(result: MediaProcessResult) {
        synchronized(listeners) { listeners.forEach { it.onComplete(result) } }
    }

    private fun notifyError(type: MediaType, error: String) {
        synchronized(listeners) { listeners.forEach { it.onError(type, error) } }
    }

    /**
     * 加载总开关状态
     */
    private fun loadMasterSwitch(): Boolean {
        return prefs.getBoolean(KEY_MASTER_SWITCH, true) // 默认开启
    }
}
