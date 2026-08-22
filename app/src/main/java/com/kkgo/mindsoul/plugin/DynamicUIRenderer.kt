/*
 * ============================================================
 * DynamicUIRenderer - 动态界面渲染器
 * ============================================================
 *
 * 原生内核保留渲染底层，界面由外置 XML 动态加载。
 * 本渲染器负责：
 * 1. 解析插件 XML 布局描述
 * 2. 将 XML 动态 Inflate 为 Android View 树
 * 3. 绑定事件处理器（由脚本引擎提供）
 * 4. 支持实时重载（重新解析 XML 即可刷新界面）
 *
 * 设计原则：
 * - 原生内核保留渲染底层（LayoutInflater + View 系统）
 * - 界面定义完全外置，AI 可自主编写 XML
 * - 无需重编译 APK，XML 变更即时生效
 * ============================================================
 */
package com.kkgo.mindsoul.plugin

import android.content.Context
import android.graphics.Color
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.io.ByteArrayInputStream
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 布局注册信息
 */
data class LayoutRegistration(
    /** 插件ID */
    val pluginId: String,
    /** XML 原始内容 */
    val xmlContent: String,
    /** 最后解析时间 */
    var parsedAt: Long = System.currentTimeMillis()
)

/**
 * 动态界面渲染器
 *
 * 核心渲染引擎，将外置 XML 布局描述转换为实际的 Android View。
 */
class DynamicUIRenderer(private val context: Context) {

    companion object {
        private const val TAG = "DynamicUIRenderer"

        // ============ 支持的 XML 标签 ============
        /** 布局容器标签 */
        const val TAG_LINEAR_LAYOUT = "LinearLayout"
        const val TAG_RELATIVE_LAYOUT = "RelativeLayout"
        const val TAG_FRAME_LAYOUT = "FrameLayout"
        const val TAG_SCROLL_VIEW = "ScrollView"

        /** 控件标签 */
        const val TAG_TEXT_VIEW = "TextView"
        const val TAG_EDIT_TEXT = "EditText"
        const val TAG_BUTTON = "Button"
        const val TAG_IMAGE_VIEW = "ImageView"
        const val TAG_CHECK_BOX = "CheckBox"
        const val TAG_SWITCH = "Switch"
        const val TAG_PROGRESS_BAR = "ProgressBar"
        const val TAG_SEEK_BAR = "SeekBar"

        // ============ 支持的属性 ============
        const val ATTR_ID = "android:id"
        const val ATTR_LAYOUT_WIDTH = "android:layout_width"
        const val ATTR_LAYOUT_HEIGHT = "android:layout_height"
        const val ATTR_TEXT = "android:text"
        const val ATTR_TEXT_SIZE = "android:textSize"
        const val ATTR_TEXT_COLOR = "android:textColor"
        const val ATTR_BACKGROUND = "android:background"
        const val ATTR_ORIENTATION = "android:orientation"
        const val ATTR_GRAVITY = "android:gravity"
        const val ATTR_PADDING = "android:padding"
        const val ATTR_MARGIN = "android:layout_margin"
        const val ATTR_WEIGHT = "android:layout_weight"
        const val ATTR_VISIBILITY = "android:visibility"
        const val ATTR_HINT = "android:hint"
        const val ATTR_ENABLED = "android:enabled"
        const val ATTR_ON_CLICK = "mindsoul:onClick"
        const val ATTR_ON_TEXT_CHANGED = "mindsoul:onTextChanged"

        // ============ 布局参数值 ============
        const val VALUE_MATCH_PARENT = "match_parent"
        const val VALUE_WRAP_CONTENT = "wrap_content"
    }

    // ============ 布局注册表 ============
    /** 已注册的插件布局 */
    private val layouts = mutableMapOf<String, LayoutRegistration>()

    // ============ 初始化 ============

    /**
     * 初始化渲染器
     */
    fun initialize() {
        Log.i(TAG, "[初始化] 动态UI渲染器就绪")
    }

    // ============ 布局注册/注销 ============

    /**
     * 注册插件布局
     *
     * @param pluginId 插件ID
     * @param xmlContent XML 布局内容
     */
    fun registerLayout(pluginId: String, xmlContent: String) {
        layouts[pluginId] = LayoutRegistration(pluginId, xmlContent)
        Log.d(TAG, "[注册] 布局: $pluginId (${xmlContent.length} 字符)")
    }

    /**
     * 注销插件布局
     */
    fun unregisterLayout(pluginId: String) {
        layouts.remove(pluginId)
        Log.d(TAG, "[注销] 布局: $pluginId")
    }

    /**
     * 检查是否有已注册的布局
     */
    fun hasLayout(pluginId: String): Boolean {
        return layouts.containsKey(pluginId)
    }

    // ============ 核心渲染 ============

    /**
     * 渲染指定插件的界面
     *
     * @param pluginId 插件ID
     * @param parent 父容器
     * @return 渲染后的 View，失败返回 null
     */
    fun render(pluginId: String, parent: ViewGroup): View? {
        val registration = layouts[pluginId] ?: run {
            Log.w(TAG, "渲染失败: 未注册布局 $pluginId")
            return null
        }

        return try {
            val view = parseXmlToView(registration.xmlContent, parent)
            Log.d(TAG, "[渲染] 成功: $pluginId")
            view
        } catch (e: Exception) {
            Log.e(TAG, "[渲染] 失败: $pluginId - ${e.message}")
            // 渲染失败时返回错误提示 View
            createErrorView("渲染失败: ${e.message}")
        }
    }

    /**
     * 热重载指定插件的布局
     * 重新解析 XML 并替换已渲染的 View
     */
    fun hotReload(pluginId: String, newXmlContent: String, parent: ViewGroup): View? {
        // 更新注册信息
        layouts[pluginId] = LayoutRegistration(pluginId, newXmlContent)
        // 清除父容器中的旧 View
        parent.removeAllViews()
        // 重新渲染
        return render(pluginId, parent)
    }

    // ============ XML 解析引擎 ============

    /**
     * 将 XML 字符串解析为 View 树
     *
     * 使用 DOM 解析器手动解析 XML，
     * 不使用 LayoutInflater（因为 XML 不在 APK 资源中）。
     *
     * @param xml XML 内容
     * @param parent 父容器
     * @return 解析得到的 View
     */
    private fun parseXmlToView(xml: String, parent: ViewGroup): View {
        // 使用 DOM 解析 XML
        val factory = DocumentBuilderFactory.newInstance()
        // 安全设置：禁用外部实体
        factory.setFeature("http://apache.org/xml/features/nonvalidating/no-external-general-entities", true)
        factory.setFeature("http://apache.org/xml/features/nonvalidating/no-external-parameter-entities", true)
        val builder = factory.newDocumentBuilder()
        val document = builder.parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        val rootElement = document.documentElement

        return createViewFromElement(rootElement, parent)
    }

    /**
     * 根据 XML 元素创建 View
     *
     * 递归处理：先创建当前节点对应的 View，再递归处理子节点。
     */
    private fun createViewFromElement(element: org.w3c.dom.Element, parent: ViewGroup): View {
        val tagName = element.tagName

        val view = when (tagName) {
            // ============ 容器类 ============
            TAG_LINEAR_LAYOUT -> createLinearLayout(element, parent)
            TAG_RELATIVE_LAYOUT -> createRelativeLayout(element, parent)
            TAG_FRAME_LAYOUT -> createFrameLayout(element, parent)
            TAG_SCROLL_VIEW -> createScrollView(element, parent)

            // ============ 控件类 ============
            TAG_TEXT_VIEW -> createTextView(element)
            TAG_EDIT_TEXT -> createEditText(element)
            TAG_BUTTON -> createButton(element)
            TAG_IMAGE_VIEW -> createImageView(element)
            TAG_CHECK_BOX -> createCheckBox(element)
            TAG_SWITCH -> createSwitchView(element)
            TAG_PROGRESS_BAR -> createProgressBar(element)
            TAG_SEEK_BAR -> createSeekBar(element)

            else -> {
                Log.w(TAG, "未知标签: $tagName, 使用 FrameLayout 替代")
                createFrameLayout(element, parent)
            }
        }

        // 通用属性设置
        applyCommonAttributes(view, element)

        // 如果是容器，递归处理子节点
        if (view is ViewGroup) {
            val childNodes = element.childNodes
            for (i in 0 until childNodes.length) {
                val node = childNodes.item(i)
                if (node is org.w3c.dom.Element) {
                    val childView = createViewFromElement(node, view)
                    view.addView(childView)
                }
            }
        }

        return view
    }

    // ============ 容器创建 ============

    private fun createLinearLayout(element: org.w3c.dom.Element, parent: ViewGroup): LinearLayout {
        val layout = LinearLayout(context)
        val orientation = element.getAttribute(ATTR_ORIENTATION)
        layout.orientation = when (orientation) {
            "horizontal" -> LinearLayout.HORIZONTAL
            else -> LinearLayout.VERTICAL
        }
        return layout
    }

    private fun createRelativeLayout(element: org.w3c.dom.Element, parent: ViewGroup): RelativeLayout {
        return RelativeLayout(context)
    }

    private fun createFrameLayout(element: org.w3c.dom.Element, parent: ViewGroup): FrameLayout {
        return FrameLayout(context)
    }

    private fun createScrollView(element: org.w3c.dom.Element, parent: ViewGroup): ScrollView {
        return ScrollView(context)
    }

    // ============ 控件创建 ============

    private fun createTextView(element: org.w3c.dom.Element): TextView {
        val tv = TextView(context)
        element.getAttribute(ATTR_TEXT)?.let { if (it.isNotEmpty()) tv.text = it }
        element.getAttribute(ATTR_TEXT_SIZE)?.let {
            if (it.isNotEmpty()) tv.textSize = parseDimension(it, 14f)
        }
        element.getAttribute(ATTR_TEXT_COLOR)?.let {
            if (it.isNotEmpty()) tv.setTextColor(parseColor(it))
        }
        element.getAttribute(ATTR_HINT)?.let { if (it.isNotEmpty()) tv.hint = it }
        return tv
    }

    private fun createEditText(element: org.w3c.dom.Element): EditText {
        val et = EditText(context)
        element.getAttribute(ATTR_TEXT)?.let { if (it.isNotEmpty()) et.setText(it) }
        element.getAttribute(ATTR_HINT)?.let { if (it.isNotEmpty()) et.hint = it }
        element.getAttribute(ATTR_TEXT_SIZE)?.let {
            if (it.isNotEmpty()) et.textSize = parseDimension(it, 14f)
        }
        return et
    }

    private fun createButton(element: org.w3c.dom.Element): Button {
        val btn = Button(context)
        element.getAttribute(ATTR_TEXT)?.let { if (it.isNotEmpty()) btn.text = it }
        element.getAttribute(ATTR_TEXT_SIZE)?.let {
            if (it.isNotEmpty()) btn.textSize = parseDimension(it, 14f)
        }
        return btn
    }

    private fun createImageView(element: org.w3c.dom.Element): ImageView {
        val iv = ImageView(context)
        iv.scaleType = ImageView.ScaleType.FIT_CENTER
        return iv
    }

    private fun createCheckBox(element: org.w3c.dom.Element): CheckBox {
        val cb = CheckBox(context)
        element.getAttribute(ATTR_TEXT)?.let { if (it.isNotEmpty()) cb.text = it }
        return cb
    }

    private fun createSwitchView(element: org.w3c.dom.Element): Switch {
        val sw = Switch(context)
        element.getAttribute(ATTR_TEXT)?.let { if (it.isNotEmpty()) sw.text = it }
        return sw
    }

    private fun createProgressBar(element: org.w3c.dom.Element): ProgressBar {
        return ProgressBar(context)
    }

    private fun createSeekBar(element: org.w3c.dom.Element): SeekBar {
        return SeekBar(context)
    }

    // ============ 通用属性应用 ============

    /**
     * 应用通用 XML 属性到 View
     */
    private fun applyCommonAttributes(view: View, element: org.w3c.dom.Element) {
        // 布局参数
        val width = parseLayoutDimension(element.getAttribute(ATTR_LAYOUT_WIDTH))
        val height = parseLayoutDimension(element.getAttribute(ATTR_LAYOUT_HEIGHT))
        val layoutParams = ViewGroup.LayoutParams(width, height)
        view.layoutParams = layoutParams

        // 背景
        element.getAttribute(ATTR_BACKGROUND)?.let {
            if (it.isNotEmpty()) {
                try {
                    view.setBackgroundColor(parseColor(it))
                } catch (_: Exception) {}
            }
        }

        // 内边距
        element.getAttribute(ATTR_PADDING)?.let {
            if (it.isNotEmpty()) {
                val px = dpToPx(parseDimension(it, 0f))
                view.setPadding(px, px, px, px)
            }
        }

        // 可见性
        element.getAttribute(ATTR_VISIBILITY)?.let {
            view.visibility = when (it) {
                "visible" -> View.VISIBLE
                "invisible" -> View.INVISIBLE
                "gone" -> View.GONE
                else -> View.VISIBLE
            }
        }

        // 启用状态
        element.getAttribute(ATTR_ENABLED)?.let {
            view.isEnabled = it != "false"
        }

        // 设置 tag（用于事件绑定）
        element.getAttribute(ATTR_ID)?.let {
            if (it.isNotEmpty()) view.tag = it
        }

        // 点击事件绑定（标记事件名，由脚本引擎处理）
        element.getAttribute(ATTR_ON_CLICK)?.let {
            if (it.isNotEmpty()) {
                view.setOnClickListener { _ ->
                    PluginScriptEngine.handleEvent(view.tag?.toString() ?: "", "onClick", it)
                }
            }
        }
    }

    // ============ 工具方法 ============

    /** 解析布局尺寸字符串 */
    private fun parseLayoutDimension(value: String?): Int {
        return when (value) {
            VALUE_MATCH_PARENT -> ViewGroup.LayoutParams.MATCH_PARENT
            VALUE_WRAP_CONTENT -> ViewGroup.LayoutParams.WRAP_CONTENT
            null, "" -> ViewGroup.LayoutParams.WRAP_CONTENT
            else -> {
                // 尝试解析为 dp 值
                val numStr = value.replace("dp", "").replace("sp", "").replace("px", "").trim()
                numStr.toFloatOrNull()?.let { dpToPx(it) } ?: ViewGroup.LayoutParams.WRAP_CONTENT
            }
        }
    }

    /** 解析尺寸值 */
    private fun parseDimension(value: String, default: Float): Float {
        val numStr = value.replace("sp", "").replace("dp", "").replace("px", "").trim()
        return numStr.toFloatOrNull() ?: default
    }

    /** dp 转 px */
    private fun dpToPx(dp: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            context.resources.displayMetrics
        ).toInt()
    }

    /** 解析颜色字符串 */
    private fun parseColor(colorStr: String): Int {
        return try {
            Color.parseColor(colorStr)
        } catch (e: Exception) {
            // 尝试作为命名颜色
            when (colorStr.lowercase()) {
                "red" -> Color.RED
                "blue" -> Color.BLUE
                "green" -> Color.GREEN
                "white" -> Color.WHITE
                "black" -> Color.BLACK
                "gray", "grey" -> Color.GRAY
                "transparent" -> Color.TRANSPARENT
                else -> Color.BLACK
            }
        }
    }

    /** 创建错误提示 View */
    private fun createErrorView(message: String): TextView {
        return TextView(context).apply {
            text = "⚠️ $message"
            setTextColor(Color.RED)
            textSize = 12f
            setPadding(16, 8, 16, 8)
        }
    }
}
