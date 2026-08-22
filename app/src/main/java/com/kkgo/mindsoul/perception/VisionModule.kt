/*
 * ============================================================
 * VisionModule - 全局UI视觉识别模块
 * ============================================================
 *
 * 实现全局屏幕视觉理解：
 *
 * 功能：
 * 1. 屏幕截图采集
 *    - 定期/按需截图
 *    - 截图区域选择
 * 2. 视觉元素识别
 *    - 文本区域检测（结合OCR）
 *    - UI 控件识别（按钮/输入框/列表）
 *    - 图像内容分类
 * 3. 场景理解
 *    - 当前应用识别
 *    - 页面布局分析
 *    - 内容语义提取
 * 4. 变化检测
 *    - 屏幕变化监测
 *    - UI 状态差异比较
 * ============================================================
 */
package com.kkgo.mindsoul.perception

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest

/**
 * 视觉识别结果
 */
data class VisionResult(
    /** 截图时间戳 */
    val timestamp: Long = System.currentTimeMillis(),
    /** 截图哈希（用于变化检测） */
    val imageHash: String,
    /** 识别的文本区域 */
    val textRegions: List<TextRegion> = emptyList(),
    /** 识别的UI控件 */
    val uiElements: List<UIElement> = emptyList(),
    /** 当前应用包名 */
    val currentApp: String = "",
    /** 页面标题 */
    val pageTitle: String = "",
    /** 场景分类 */
    val sceneType: SceneType = SceneType.UNKNOWN,
    /** 图片指纹差异度（与上一帧比较） */
    val changeScore: Float = 0f
)

/**
 * 文本区域
 */
data class TextRegion(
    /** 区域范围 */
    val bounds: Rect,
    /** 文本内容 */
    val text: String,
    /** 置信度 */
    val confidence: Float,
    /** 文本类型 */
    val type: TextRegionType = TextRegionType.BODY
)

/**
 * 文本区域类型
 */
enum class TextRegionType {
    TITLE,      // 标题
    BODY,       // 正文
    BUTTON,     // 按钮文本
    LABEL,      // 标签
    INPUT,      // 输入框内容
    NOTIFICATION // 通知文本
}

/**
 * UI 控件
 */
data class UIElement(
    /** 控件范围 */
    val bounds: Rect,
    /** 控件类型 */
    val type: UIElementType,
    /** 控件文本 */
    val text: String? = null,
    /** 控件描述 */
    val description: String? = null,
    /** 是否可交互 */
    val isInteractive: Boolean = false
)

/**
 * UI 控件类型
 */
enum class UIElementType {
    BUTTON,
    TEXT_INPUT,
    IMAGE,
    LIST,
    SCROLL_VIEW,
    CHECKBOX,
    SWITCH,
    SLIDER,
    TAB,
    MENU,
    DIALOG,
    TOOLBAR,
    STATUS_BAR,
    UNKNOWN
}

/**
 * 场景类型
 */
enum class SceneType(val displayName: String) {
    HOME_SCREEN("主屏幕"),
    APP_INTERFACE("应用界面"),
    DIALOG("对话框"),
    NOTIFICATION_PANEL("通知面板"),
    LOCK_SCREEN("锁屏"),
    SETTINGS("设置页面"),
    KEYBOARD("键盘"),
    VIDEO_PLAYER("视频播放"),
    GAME("游戏"),
    BROWSER("浏览器"),
    UNKNOWN("未知")
}

/**
 * 视觉识别模块
 */
class VisionModule(private val context: Context) {

    companion object {
        private const val TAG = "VisionModule"
        /** 自动截图间隔（毫秒） */
        private const val AUTO_CAPTURE_INTERVAL = 5000L
        /** 变化检测阈值 */
        private const val CHANGE_THRESHOLD = 0.05f
    }

    // ============ 状态 ============
    private val _isRunning = MutableStateFlow(false)
    val isRunningFlow: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _lastResult = MutableStateFlow<VisionResult?>(null)
    val lastResultFlow: StateFlow<VisionResult?> = _lastResult.asStateFlow()

    // ============ 协程 ============
    private val captureScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var captureJob: Job? = null

    // ============ 变化检测 ============
    private var lastImageHash: String = ""
    private var lastCaptureTime = 0L

    // ============ 回调 ============
    private var recognitionCallback: ((VisionResult) -> Unit)? = null
    private var changeDetectedCallback: ((Float) -> Unit)? = null

    // ============ 生命周期 ============

    /**
     * 初始化视觉模块
     */
    fun initialize() {
        Log.i(TAG, "[初始化] 视觉识别模块就绪")
    }

    /**
     * 启动自动截图识别
     */
    fun startAutoCapture() {
        if (_isRunning.value) return

        _isRunning.value = true
        captureJob?.cancel()
        captureJob = captureScope.launch {
            while (isActive) {
                try {
                    // 按需截图并分析
                    val bitmap = captureScreen()
                    if (bitmap != null) {
                        val result = analyzeFrame(bitmap)
                        _lastResult.value = result
                        recognitionCallback?.invoke(result)

                        // 变化检测
                        if (result.changeScore > CHANGE_THRESHOLD) {
                            changeDetectedCallback?.invoke(result.changeScore)
                            Log.d(TAG, "[视觉] 检测到变化: ${String.format("%.2f", result.changeScore)}")
                        }

                        bitmap.recycle()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "[视觉] 截图分析异常: ${e.message}")
                }
                delay(AUTO_CAPTURE_INTERVAL)
            }
        }
        Log.i(TAG, "[视觉] 自动截图已启动")
    }

    /**
     * 停止自动截图
     */
    fun stopAutoCapture() {
        _isRunning.value = false
        captureJob?.cancel()
        Log.i(TAG, "[视觉] 自动截图已停止")
    }

    /**
     * 手动截图分析（一次性）
     */
    suspend fun captureAndAnalyze(): VisionResult? = withContext(Dispatchers.Default) {
        val bitmap = captureScreen() ?: return@withContext null
        val result = analyzeFrame(bitmap)
        bitmap.recycle()
        _lastResult.value = result
        result
    }

    /**
     * 设置识别结果回调
     */
    fun setRecognitionCallback(callback: (VisionResult) -> Unit) {
        recognitionCallback = callback
    }

    /**
     * 设置变化检测回调
     */
    fun setChangeDetectedCallback(callback: (Float) -> Unit) {
        changeDetectedCallback = callback
    }

    /**
     * 释放资源
     */
    fun destroy() {
        stopAutoCapture()
        captureScope.cancel()
        Log.i(TAG, "[销毁] 视觉模块已释放")
    }

    // ============ 内部方法 ============

    /**
     * 截取屏幕（需要 MediaProjection 权限）
     *
     * 实际实现需要配合 MediaProjectionManager
     * 此处为框架实现
     */
    private fun captureScreen(): Bitmap? {
        // 实际需要通过 MediaProjection API 获取屏幕
        // 此处返回 null 表示框架占位
        lastCaptureTime = System.currentTimeMillis()
        return null  // 需要外部注入实际的截图实现
    }

    /**
     * 分析一帧图像
     */
    private fun analyzeFrame(bitmap: Bitmap): VisionResult {
        val imageHash = computeImageHash(bitmap)
        val changeScore = computeChangeScore(imageHash)
        lastImageHash = imageHash

        // 提取文本区域（简化实现）
        val textRegions = extractTextRegions(bitmap)

        // 识别UI控件
        val uiElements = detectUIElements(bitmap)

        // 场景分类
        val sceneType = classifyScene(bitmap)

        return VisionResult(
            imageHash = imageHash,
            textRegions = textRegions,
            uiElements = uiElements,
            sceneType = sceneType,
            changeScore = changeScore
        )
    }

    /**
     * 计算图像哈希（用于变化检测）
     *
     * 使用缩略图 + 均值哈希（pHash简化版）
     */
    private fun computeImageHash(bitmap: Bitmap): String {
        try {
            // 缩小到 8x8 灰度
            val small = Bitmap.createScaledBitmap(bitmap, 8, 8, true)
            val pixels = IntArray(64)
            small.getPixels(pixels, 0, 8, 0, 0, 8, 8)

            // 计算均值
            var sum = 0L
            for (p in pixels) {
                val gray = (android.graphics.Color.red(p) * 0.299 +
                        android.graphics.Color.green(p) * 0.587 +
                        android.graphics.Color.blue(p) * 0.114).toLong()
                sum += gray
            }
            val avg = sum / 64

            // 生成哈希
            val hashBits = StringBuilder()
            for (p in pixels) {
                val gray = (android.graphics.Color.red(p) * 0.299 +
                        android.graphics.Color.green(p) * 0.587 +
                        android.graphics.Color.blue(p) * 0.114).toLong()
                hashBits.append(if (gray >= avg) '1' else '0')
            }

            small.recycle()
            return hashBits.toString()
        } catch (e: Exception) {
            return ""
        }
    }

    /**
     * 计算变化分数（汉明距离 / 总位数）
     */
    private fun computeChangeScore(currentHash: String): Float {
        if (lastImageHash.isEmpty() || currentHash.isEmpty()) return 1f
        if (lastImageHash.length != currentHash.length) return 1f

        var diff = 0
        for (i in currentHash.indices) {
            if (currentHash[i] != lastImageHash[i]) diff++
        }
        return diff.toFloat() / currentHash.length.toFloat()
    }

    /**
     * 提取文本区域（简化实现）
     *
     * 实际应集成 OCR 引擎（如 Tesseract）
     */
    private fun extractTextRegions(bitmap: Bitmap): List<TextRegion> {
        // 框架实现 - 实际需配合 OCR 模块
        return emptyList()
    }

    /**
     * 检测UI控件（简化实现）
     */
    private fun detectUIElements(bitmap: Bitmap): List<UIElement> {
        // 框架实现 - 可通过边缘检测/颜色分析识别控件
        return emptyList()
    }

    /**
     * 场景分类（简化实现）
     */
    private fun classifyScene(bitmap: Bitmap): SceneType {
        // 框架实现 - 基于颜色直方图/布局特征分类
        return SceneType.UNKNOWN
    }
}
