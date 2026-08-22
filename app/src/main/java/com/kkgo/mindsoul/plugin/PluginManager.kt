/*
 * ============================================================
 * PluginManager - 动态虚拟机插件管理器
 * ============================================================
 *
 * 管理 Plugins 目录下的所有外置插件：
 * 1. 扫描 Plugins 目录，发现新增/删除/变更的插件
 * 2. 加载插件 XML 布局定义 + 脚本逻辑
 * 3. 支持热重载（文件变更即时生效）
 * 4. 插件生命周期管理（安装/启用/禁用/卸载）
 *
 * 插件目录结构（每个插件一个子目录）：
 *   Plugins/
 *   ├── chat_bubble/
 *   │   ├── plugin.xml        ← 界面布局定义
 *   │   ├── script.kts        ← 交互脚本（Kotlin Script子集）
 *   │   └── manifest.json     ← 插件元数据
 *   └── status_bar/
 *       ├── plugin.xml
 *       ├── script.kts
 *       └── manifest.json
 * ============================================================
 */
package com.kkgo.mindsoul.plugin

import android.content.Context
import android.os.FileObserver
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * 插件状态枚举
 */
enum class PluginState {
    /** 已发现但未加载 */
    DISCOVERED,
    /** 已加载并启用 */
    ACTIVE,
    /** 已禁用 */
    DISABLED,
    /** 加载失败 */
    ERROR
}

/**
 * 插件元数据（对应 manifest.json 解析结果）
 */
data class PluginManifest(
    /** 插件唯一标识 */
    val id: String,
    /** 显示名称 */
    val name: String,
    /** 版本号 */
    val version: String = "1.0.0",
    /** 作者 */
    val author: String = "MindSoul",
    /** 描述 */
    val description: String = "",
    /** 最低权限要求 */
    val minPermissionLevel: Int = 1,
    /** 是否启用 */
    val enabled: Boolean = true
)

/**
 * 插件信息（运行时对象）
 */
data class PluginInfo(
    /** 元数据 */
    val manifest: PluginManifest,
    /** 插件根目录 */
    val pluginDir: File,
    /** XML 布局文件 */
    val layoutFile: File?,
    /** 脚本文件 */
    val scriptFile: File?,
    /** 当前状态 */
    var state: PluginState = PluginState.DISCOVERED,
    /** 最后修改时间（用于热重载检测） */
    var lastModified: Long = 0L,
    /** 错误信息（加载失败时） */
    var errorMessage: String? = null
)

/**
 * 动态虚拟机插件管理器
 */
class PluginManager(private val context: Context) {

    companion object {
        private const val TAG = "PluginMgr"
        /** 插件根目录名 */
        const val PLUGINS_DIR_NAME = "Plugins"
        /** 插件布局文件名 */
        const val LAYOUT_FILE_NAME = "plugin.xml"
        /** 插件脚本文件名 */
        const val SCRIPT_FILE_NAME = "script.kts"
        /** 插件清单文件名 */
        const val MANIFEST_FILE_NAME = "manifest.json"
        /** 热重载扫描间隔（毫秒） */
        const val HOT_RELOAD_INTERVAL = 3000L
    }

    // ============ 插件目录 ============
    /** 插件根目录 */
    val pluginsDir: File = File(context.filesDir, PLUGINS_DIR_NAME)

    // ============ 插件注册表 ============
    /** 已注册的全部插件 */
    private val _plugins = MutableStateFlow<Map<String, PluginInfo>>(emptyMap())
    val pluginsFlow: StateFlow<Map<String, PluginInfo>> = _plugins.asStateFlow()

    /** 快捷访问当前插件列表 */
    val plugins: Map<String, PluginInfo>
        get() = _plugins.value

    // ============ 脚本引擎 ============
    lateinit var scriptEngine: PluginScriptEngine
        private set

    // ============ UI渲染器 ============
    lateinit var uiRenderer: DynamicUIRenderer
        private set

    // ============ 热重载 ============
    /** 热重载协程作用域 */
    private val reloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** 热重载任务 */
    private var reloadJob: Job? = null
    /** 每个插件的最后已知修改时间 */
    private val lastModifiedMap = mutableMapOf<String, Long>()

    // ============ 监听器 ============
    private val listeners = mutableListOf<PluginLifecycleListener>()

    /**
     * 插件生命周期监听器
     */
    interface PluginLifecycleListener {
        fun onPluginLoaded(plugin: PluginInfo)
        fun onPluginUnloaded(pluginId: String)
        fun onPluginError(pluginId: String, error: String)
        fun onPluginReloaded(plugin: PluginInfo)
    }

    // ============ 初始化 ============

    /**
     * 初始化插件管理器
     * 创建目录、初始化子模块、启动首次扫描和热重载
     */
    fun initialize() {
        // 确保插件目录存在（全开读写）
        if (!pluginsDir.exists()) {
            pluginsDir.mkdirs()
            Log.i(TAG, "[初始化] 创建插件目录: ${pluginsDir.absolutePath}")
        }

        // 初始化脚本引擎
        scriptEngine = PluginScriptEngine(context)
        scriptEngine.initialize()
        Log.i(TAG, "[初始化] 脚本引擎就绪")

        // 初始化UI渲染器
        uiRenderer = DynamicUIRenderer(context)
        uiRenderer.initialize()
        Log.i(TAG, "[初始化] UI渲染器就绪")

        // 首次扫描
        scanPlugins()

        // 启动热重载监控
        startHotReload()

        Log.i(TAG, "[初始化] 插件管理器就绪，已发现 ${plugins.size} 个插件")
    }

    /**
     * 释放资源
     */
    fun destroy() {
        reloadJob?.cancel()
        scriptEngine.destroy()
        listeners.clear()
        Log.i(TAG, "[销毁] 插件管理器已释放")
    }

    // ============ 插件扫描 ============

    /**
     * 扫描插件目录，发现所有有效插件
     */
    fun scanPlugins() {
        val newPlugins = mutableMapOf<String, PluginInfo>()
        val existingIds = plugins.keys.toMutableSet()

        // 遍历插件子目录
        val subDirs = pluginsDir.listFiles { file -> file.isDirectory } ?: emptyArray()
        for (dir in subDirs) {
            try {
                val pluginInfo = loadPluginFromDir(dir)
                if (pluginInfo != null) {
                    newPlugins[pluginInfo.manifest.id] = pluginInfo
                    existingIds.remove(pluginInfo.manifest.id)
                }
            } catch (e: Exception) {
                Log.e(TAG, "加载插件失败 [${dir.name}]: ${e.message}")
            }
        }

        // 移除已删除的插件
        for (removedId in existingIds) {
            unloadPlugin(removedId)
        }

        // 更新注册表
        _plugins.value = newPlugins
        Log.d(TAG, "扫描完成: ${newPlugins.size} 个插件")
    }

    /**
     * 从目录加载单个插件
     */
    private fun loadPluginFromDir(dir: File): PluginInfo? {
        // 解析 manifest.json
        val manifestFile = File(dir, MANIFEST_FILE_NAME)
        val manifest = if (manifestFile.exists()) {
            parseManifest(manifestFile)
        } else {
            // 无 manifest 则使用目录名作为默认 ID
            PluginManifest(
                id = dir.name,
                name = dir.name,
                description = "自动发现的插件"
            )
        }

        // 查找布局和脚本文件
        val layoutFile = File(dir, LAYOUT_FILE_NAME).takeIf { it.exists() }
        val scriptFile = File(dir, SCRIPT_FILE_NAME).takeIf { it.exists() }

        if (layoutFile == null && scriptFile == null) {
            Log.w(TAG, "插件目录 [${dir.name}] 无有效文件，跳过")
            return null
        }

        val info = PluginInfo(
            manifest = manifest,
            pluginDir = dir,
            layoutFile = layoutFile,
            scriptFile = scriptFile,
            lastModified = dir.lastModified()
        )

        // 尝试加载
        return try {
            if (manifest.enabled) {
                loadPlugin(info)
            } else {
                info.state = PluginState.DISABLED
                info
            }
        } catch (e: Exception) {
            info.state = PluginState.ERROR
            info.errorMessage = e.message
            info
        }
    }

    /**
     * 加载并激活单个插件
     */
    private fun loadPlugin(info: PluginInfo): PluginInfo {
        // 加载脚本到引擎
        info.scriptFile?.let { script ->
            scriptEngine.loadScript(info.manifest.id, script.readText())
        }

        // 解析布局到渲染器
        info.layoutFile?.let { layout ->
            uiRenderer.registerLayout(info.manifest.id, layout.readText())
        }

        info.state = PluginState.ACTIVE
        lastModifiedMap[info.manifest.id] = info.lastModified

        // 通知监听器
        listeners.forEach { it.onPluginLoaded(info) }
        Log.i(TAG, "[加载] 插件: ${info.manifest.name} (${info.manifest.id})")
        return info
    }

    /**
     * 卸载插件
     */
    private fun unloadPlugin(pluginId: String) {
        scriptEngine.unloadScript(pluginId)
        uiRenderer.unregisterLayout(pluginId)
        lastModifiedMap.remove(pluginId)
        listeners.forEach { it.onPluginUnloaded(pluginId) }
        Log.i(TAG, "[卸载] 插件: $pluginId")
    }

    // ============ 热重载 ============

    /**
     * 启动热重载监控
     * 每隔 HOT_RELOAD_INTERVAL 毫秒扫描一次文件变更
     */
    fun startHotReload() {
        reloadJob?.cancel()
        reloadJob = reloadScope.launch {
            while (isActive) {
                delay(HOT_RELOAD_INTERVAL)
                checkForChanges()
            }
        }
        Log.i(TAG, "[热重载] 已启动，间隔 ${HOT_RELOAD_INTERVAL}ms")
    }

    /**
     * 停止热重载
     */
    fun stopHotReload() {
        reloadJob?.cancel()
        reloadJob = null
        Log.i(TAG, "[热重载] 已停止")
    }

    /**
     * 检查文件变更并热重载
     */
    private fun checkForChanges() {
        val subDirs = pluginsDir.listFiles { file -> file.isDirectory } ?: return

        for (dir in subDirs) {
            val currentModified = dir.lastModified()
            val pluginId = dir.name
            val lastKnown = lastModifiedMap[pluginId]

            if (lastKnown == null) {
                // 新增插件
                Log.i(TAG, "[热重载] 发现新插件: $pluginId")
                scanPlugins()
                return
            }

            if (currentModified > lastKnown) {
                // 文件已修改，重新加载
                Log.i(TAG, "[热重载] 插件变更: $pluginId")
                unloadPlugin(pluginId)
                val updated = plugins.toMutableMap()
                val info = loadPluginFromDir(dir)
                if (info != null) {
                    updated[info.manifest.id] = info
                    _plugins.value = updated
                    listeners.forEach { it.onPluginReloaded(info) }
                }
                return
            }
        }
    }

    // ============ 手动操作接口 ============

    /**
     * 手动重载指定插件
     */
    fun reloadPlugin(pluginId: String): Boolean {
        val plugin = plugins[pluginId] ?: return false
        unloadPlugin(pluginId)
        val info = loadPluginFromDir(plugin.pluginDir) ?: return false
        val updated = plugins.toMutableMap()
        updated[pluginId] = info
        _plugins.value = updated
        return true
    }

    /**
     * 禁用指定插件
     */
    fun disablePlugin(pluginId: String): Boolean {
        val plugin = plugins[pluginId] ?: return false
        unloadPlugin(pluginId)
        plugin.state = PluginState.DISABLED
        val updated = plugins.toMutableMap()
        updated[pluginId] = plugin
        _plugins.value = updated
        return true
    }

    /**
     * 启用指定插件
     */
    fun enablePlugin(pluginId: String): Boolean {
        val plugin = plugins[pluginId] ?: return false
        return try {
            loadPlugin(plugin)
            val updated = plugins.toMutableMap()
            updated[pluginId] = plugin
            _plugins.value = updated
            true
        } catch (e: Exception) {
            plugin.state = PluginState.ERROR
            plugin.errorMessage = e.message
            false
        }
    }

    /**
     * 删除插件（从磁盘移除）
     */
    fun deletePlugin(pluginId: String): Boolean {
        val plugin = plugins[pluginId] ?: return false
        unloadPlugin(pluginId)
        val deleted = plugin.pluginDir.deleteRecursively()
        scanPlugins()
        return deleted
    }

    // ============ 渲染接口 ============

    /**
     * 渲染指定插件的界面
     * @param pluginId 插件ID
     * @param parent 父容器
     * @return 渲染后的 View，失败返回 null
     */
    fun renderPlugin(pluginId: String, parent: android.view.ViewGroup): android.view.View? {
        val plugin = plugins[pluginId] ?: run {
            Log.w(TAG, "渲染失败: 插件不存在 $pluginId")
            return null
        }
        if (plugin.state != PluginState.ACTIVE) {
            Log.w(TAG, "渲染失败: 插件未激活 $pluginId (状态: ${plugin.state})")
            return null
        }
        return uiRenderer.render(pluginId, parent)
    }

    // ============ 监听器管理 ============

    fun addListener(listener: PluginLifecycleListener) {
        synchronized(listeners) { listeners.add(listener) }
    }

    fun removeListener(listener: PluginLifecycleListener) {
        synchronized(listeners) { listeners.remove(listener) }
    }

    // ============ manifest.json 简易解析 ============

    /**
     * 解析 manifest.json
     *
     * 使用简单的字符串解析，避免引入第三方 JSON 库。
     * 格式: { "id": "xxx", "name": "xxx", "version": "1.0", ... }
     */
    private fun parseManifest(file: File): PluginManifest {
        val content = file.readText()
        return PluginManifest(
            id = extractJsonString(content, "id") ?: file.parentFile?.name ?: "unknown",
            name = extractJsonString(content, "name") ?: file.parentFile?.name ?: "Unknown",
            version = extractJsonString(content, "version") ?: "1.0.0",
            author = extractJsonString(content, "author") ?: "MindSoul",
            description = extractJsonString(content, "description") ?: "",
            minPermissionLevel = extractJsonInt(content, "minPermissionLevel") ?: 1,
            enabled = extractJsonBool(content, "enabled") ?: true
        )
    }

    /** 从 JSON 字符串中提取字符串值 */
    private fun extractJsonString(json: String, key: String): String? {
        val pattern = "\"$key\"\\s*:\\s*\"([^\"]*)\""
        val match = Regex(pattern).find(json)
        return match?.groupValues?.get(1)
    }

    /** 从 JSON 字符串中提取整数值 */
    private fun extractJsonInt(json: String, key: String): Int? {
        val pattern = "\"$key\"\\s*:\\s*(\\d+)"
        val match = Regex(pattern).find(json)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }

    /** 从 JSON 字符串中提取布尔值 */
    private fun extractJsonBool(json: String, key: String): Boolean? {
        val pattern = "\"$key\"\\s*:\\s*(true|false)"
        val match = Regex(pattern).find(json)
        return match?.groupValues?.get(1)?.toBooleanStrictOrNull()
    }
}
