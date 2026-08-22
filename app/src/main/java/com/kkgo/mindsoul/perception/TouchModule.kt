/*
 * ============================================================
 * TouchModule - 触屏躯体执行模块
 * ============================================================
 *
 * 全维度触屏交互模块：
 *
 * 功能：
 * 1. 触屏事件拦截与分发
 *    - 全局触摸事件监听
 *    - 手势模式识别（单指/多指/长按/滑动）
 * 2. 躯体化响应
 *    - 化身触觉反馈
 *    - 触摸→情绪映射
 * 3. 输入设备管理
 *    - 键盘事件监听
 *    - 外接设备（手柄/鼠标）
 * 4. 文件/外设交互
 *    - 文件拖拽处理
 *    - USB 设备检测
 *    - 外部存储访问
 * ============================================================
 */
package com.kkgo.mindsoul.perception

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import android.view.MotionEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * 手势类型
 */
enum class GestureType(val displayName: String) {
    SINGLE_TAP("单击"),
    DOUBLE_TAP("双击"),
    LONG_PRESS("长按"),
    SWIPE_UP("上滑"),
    SWIPE_DOWN("下滑"),
    SWIPE_LEFT("左滑"),
    SWIPE_RIGHT("右滑"),
    PINCH_IN("捏合缩小"),
    PINCH_OUT("捏合放大"),
    ROTATE("旋转"),
    DRAG("拖拽"),
    MULTI_TOUCH("多点触控")
}

/**
 * 手势事件
 */
data class GestureEvent(
    /** 手势类型 */
    val type: GestureType,
    /** 起始坐标 */
    val startX: Float = 0f,
    val startY: Float = 0f,
    /** 结束坐标 */
    val endX: Float = 0f,
    val endY: Float = 0f,
    /** 手指数量 */
    val fingerCount: Int = 1,
    /** 持续时间（毫秒） */
    val durationMs: Long = 0,
    /** 时间戳 */
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 触摸事件数据
 */
data class TouchEventData(
    /** 动作类型 */
    val action: Int,
    /** X 坐标 */
    val x: Float,
    /** Y 坐标 */
    val y: Float,
    /** 压力值 [0, 1] */
    val pressure: Float,
    /** 触摸面积 */
    val size: Float,
    /** 手指数量 */
    val pointerCount: Int,
    /** 时间戳 */
    val eventTime: Long,
    /** 事件时间 */
    val downTime: Long
)

/**
 * 键盘事件数据
 */
data class KeyEventData(
    /** 按键码 */
    val keyCode: Int,
    /** 按键标签 */
    val label: String,
    /** 是否按下（true=按下，false=释放） */
    val isDown: Boolean,
    /** 是否含 Ctrl */
    val hasCtrl: Boolean = false,
    /** 是否含 Shift */
    val hasShift: Boolean = false,
    /** 是否含 Alt */
    val hasAlt: Boolean = false,
    /** 时间戳 */
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 外设信息
 */
data class PeripheralInfo(
    /** 设备名称 */
    val name: String,
    /** 设备类型 */
    val type: PeripheralType,
    /** 设备地址/路径 */
    val address: String,
    /** 是否已连接 */
    val isConnected: Boolean,
    /** 额外信息 */
    val extra: Map<String, String> = emptyMap()
)

/**
 * 外设类型
 */
enum class PeripheralType {
    USB_DEVICE,
    EXTERNAL_STORAGE,
    KEYBOARD,
    MOUSE,
    GAMEPAD,
    UNKNOWN
}

/**
 * 触屏躯体执行模块
 */
class TouchModule(private val context: Context) {

    companion object {
        private const val TAG = "TouchModule"
        /** 双击时间阈值（毫秒） */
        private const val DOUBLE_TAP_THRESHOLD = 300L
        /** 长按时间阈值（毫秒） */
        private const val LONG_PRESS_THRESHOLD = 500L
        /** 滑动距离阈值（像素） */
        private const val SWIPE_DISTANCE_THRESHOLD = 100f
    }

    // ============ 手势识别状态 ============
    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var touchDownTime = 0L
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var isLongPressDetected = false

    // ============ 状态 ============
    private val _lastGesture = MutableStateFlow<GestureEvent?>(null)
    val lastGestureFlow: StateFlow<GestureEvent?> = _lastGesture.asStateFlow()

    private val _connectedPeripherals = MutableStateFlow<List<PeripheralInfo>>(emptyList())
    val connectedPeripheralsFlow: StateFlow<List<PeripheralInfo>> = _connectedPeripherals.asStateFlow()

    // ============ 回调 ============
    private var gestureCallback: ((GestureEvent) -> Unit)? = null
    private var touchCallback: ((TouchEventData) -> Unit)? = null
    private var keyCallback: ((KeyEventData) -> Unit)? = null
    private var fileDropCallback: ((String) -> Unit)? = null

    /** 触摸→情绪映射权重 */
    private val touchEmotionMap = mutableMapOf<GestureType, FloatArray>()  // [valence, arousal]

    // ============ 初始化 ============

    /**
     * 初始化触屏模块
     */
    fun initialize() {
        // 初始化触摸→情绪映射
        initTouchEmotionMap()

        // 扫描已连接外设
        scanPeripherals()

        Log.i(TAG, "[初始化] 触屏躯体模块就绪")
        Log.i(TAG, "  手势识别 | 键盘监听 | 外设管理")
    }

    /**
     * 释放资源
     */
    fun destroy() {
        gestureCallback = null
        touchCallback = null
        keyCallback = null
        Log.i(TAG, "[销毁] 触屏模块已释放")
    }

    // ============ 触屏事件处理 ============

    /**
     * 处理触摸事件
     *
     * 由 Activity/View 的 onTouchEvent 调用
     */
    fun onTouchEvent(event: MotionEvent): GestureEvent? {
        val now = System.currentTimeMillis()
        val x = event.x
        val y = event.y
        val pointerCount = event.pointerCount

        // 构建触摸数据
        val touchData = TouchEventData(
            action = event.action,
            x = x,
            y = y,
            pressure = event.pressure,
            size = event.size,
            pointerCount = pointerCount,
            eventTime = event.eventTime,
            downTime = event.downTime
        )
        touchCallback?.invoke(touchData)

        var gesture: GestureEvent? = null

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownTime = now
                touchDownX = x
                touchDownY = y
                isLongPressDetected = false
            }

            MotionEvent.ACTION_UP -> {
                val duration = now - touchDownTime
                val dx = x - touchDownX
                val dy = y - touchDownY
                val distance = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()

                gesture = when {
                    // 双击检测
                    duration < 100 && distance < 50 &&
                            (now - lastTapTime) < DOUBLE_TAP_THRESHOLD &&
                            Math.abs(x - lastTapX) < 100 && Math.abs(y - lastTapY) < 100 -> {
                        GestureEvent(GestureType.DOUBLE_TAP, x, y, fingerCount = 1, durationMs = duration)
                    }
                    // 长按
                    isLongPressDetected -> {
                        GestureEvent(GestureType.LONG_PRESS, x, y, fingerCount = 1, durationMs = duration)
                    }
                    // 滑动
                    distance > SWIPE_DISTANCE_THRESHOLD -> {
                        val swipeType = when {
                            Math.abs(dx) > Math.abs(dy) && dx > 0 -> GestureType.SWIPE_RIGHT
                            Math.abs(dx) > Math.abs(dy) && dx < 0 -> GestureType.SWIPE_LEFT
                            dy > 0 -> GestureType.SWIPE_DOWN
                            else -> GestureType.SWIPE_UP
                        }
                        GestureEvent(swipeType, touchDownX, touchDownY, x, y, 1, duration)
                    }
                    // 单击
                    else -> {
                        GestureEvent(GestureType.SINGLE_TAP, x, y, fingerCount = 1, durationMs = duration)
                    }
                }

                lastTapTime = now
                lastTapX = x
                lastTapY = y
            }

            MotionEvent.ACTION_MOVE -> {
                val duration = now - touchDownTime
                if (duration > LONG_PRESS_THRESHOLD && !isLongPressDetected) {
                    val dx = Math.abs(x - touchDownX)
                    val dy = Math.abs(y - touchDownY)
                    if (dx < 30 && dy < 30) {
                        isLongPressDetected = true
                        gesture = GestureEvent(GestureType.LONG_PRESS, x, y,
                            fingerCount = 1, durationMs = duration)
                    }
                }

                // 多点触控
                if (pointerCount >= 2) {
                    gesture = GestureEvent(GestureType.MULTI_TOUCH, x, y,
                        fingerCount = pointerCount)
                }
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (pointerCount >= 2) {
                    gesture = GestureEvent(GestureType.MULTI_TOUCH, x, y,
                        fingerCount = pointerCount)
                }
            }
        }

        if (gesture != null) {
            _lastGesture.value = gesture
            gestureCallback?.invoke(gesture)
        }

        return gesture
    }

    /**
     * 处理键盘事件
     */
    fun onKeyEvent(keyCode: Int, label: String, isDown: Boolean,
                   hasCtrl: Boolean = false, hasShift: Boolean = false, hasAlt: Boolean = false) {
        val keyData = KeyEventData(keyCode, label, isDown, hasCtrl, hasShift, hasAlt)
        keyCallback?.invoke(keyData)
        Log.d(TAG, "[键盘] ${if (isDown) "按下" else "释放"}: $label " +
                "${if (hasCtrl) "+Ctrl" else ""}${if (hasShift) "+Shift" else ""}${if (hasAlt) "+Alt" else ""}")
    }

    // ============ 文件交互 ============

    /**
     * 处理文件拖拽/导入
     */
    fun onFileDropped(filePath: String) {
        val file = File(filePath)
        if (file.exists()) {
            Log.i(TAG, "[文件] 收到文件: ${file.name} (${file.length() / 1024}KB)")
            fileDropCallback?.invoke(filePath)
        }
    }

    /**
     * 扫描已连接外设
     */
    fun scanPeripherals() {
        val peripherals = mutableListOf<PeripheralInfo>()

        // USB 设备
        try {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            usbManager?.deviceList?.forEach { (name, device) ->
                peripherals.add(PeripheralInfo(
                    name = device.deviceName,
                    type = PeripheralType.USB_DEVICE,
                    address = name,
                    isConnected = true,
                    extra = mapOf(
                        "vendorId" to device.vendorId.toString(),
                        "productId" to device.productId.toString()
                    )
                ))
            }
        } catch (e: Exception) {
            Log.w(TAG, "[外设] USB 扫描异常: ${e.message}")
        }

        // 外部存储
        val externalFiles = context.getExternalFilesDirs(null)
        for (dir in externalFiles) {
            if (dir != null && File(dir.path).canRead()) {
                val rootPath = dir.path.substringBefore("/Android")
                peripherals.add(PeripheralInfo(
                    name = File(rootPath).name,
                    type = PeripheralType.EXTERNAL_STORAGE,
                    address = rootPath,
                    isConnected = true
                ))
            }
        }

        _connectedPeripherals.value = peripherals
        Log.i(TAG, "[外设] 扫描完成: ${peripherals.size} 个设备")
    }

    // ============ 触摸→情绪映射 ============

    /**
     * 获取触摸事件对应的情绪影响
     *
     * @return FloatArray[2] = [valence变化, arousal变化]
     */
    fun getTouchEmotion(gesture: GestureType): FloatArray {
        return touchEmotionMap[gesture] ?: floatArrayOf(0f, 0f)
    }

    // ============ 回调设置 ============

    fun setGestureCallback(callback: (GestureEvent) -> Unit) { gestureCallback = callback }
    fun setTouchCallback(callback: (TouchEventData) -> Unit) { touchCallback = callback }
    fun setKeyCallback(callback: (KeyEventData) -> Unit) { keyCallback = callback }
    fun setFileDropCallback(callback: (String) -> Unit) { fileDropCallback = callback }

    // ============ 内部方法 ============

    /**
     * 初始化触摸→情绪映射
     *
     * 不同的触摸方式对应不同的情绪影响
     */
    private fun initTouchEmotionMap() {
        // [valence(愉悦度), arousal(唤醒度)]
        touchEmotionMap[GestureType.SINGLE_TAP] = floatArrayOf(0.05f, 0.02f)     // 轻触 → 微愉悦
        touchEmotionMap[GestureType.DOUBLE_TAP] = floatArrayOf(0.1f, 0.05f)      // 双击 → 愉悦+注意
        touchEmotionMap[GestureType.LONG_PRESS] = floatArrayOf(0.0f, -0.05f)     // 长按 → 安抚
        touchEmotionMap[GestureType.SWIPE_UP] = floatArrayOf(0.1f, 0.1f)         // 上滑 → 积极+活跃
        touchEmotionMap[GestureType.SWIPE_DOWN] = floatArrayOf(-0.05f, -0.1f)    // 下滑 → 消极+平静
        touchEmotionMap[GestureType.SWIPE_LEFT] = floatArrayOf(0.0f, 0.05f)      // 左滑 → 中性+微注意
        touchEmotionMap[GestureType.SWIPE_RIGHT] = floatArrayOf(0.0f, 0.05f)     // 右滑 → 中性+微注意
        touchEmotionMap[GestureType.PINCH_IN] = floatArrayOf(-0.05f, -0.1f)      // 缩小 → 消极+平静
        touchEmotionMap[GestureType.PINCH_OUT] = floatArrayOf(0.1f, 0.15f)       // 放大 → 积极+活跃
        touchEmotionMap[GestureType.MULTI_TOUCH] = floatArrayOf(0.15f, 0.2f)     // 多指 → 积极+兴奋
    }
}
