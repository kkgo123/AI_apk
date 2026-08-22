/*
 * ============================================================
 * SelfEvolution - AI自我进化系统
 * ============================================================
 *
 * 核心能力：AI可以修改/添加/删除界面功能代码
 *
 * 架构设计：
 * 1. 代码沙箱（CodeSandbox）
 *    - AI在 Plugins 目录下生成新的 XML/代码文件
 *    - 沙箱隔离，不影响核心系统
 *    - 文件命名规范：evolution_{timestamp}_{type}
 *
 * 2. 动态加载（DynamicLoader）
 *    - 通过 PluginManager 热加载新界面
 *    - 支持 XML 布局 + Kotlin Script 逻辑
 *    - 加载失败自动回滚
 *
 * 3. 进化日志（EvolutionLog）
 *    - 记录每次自我修改的内容
 *    - 包含：时间戳、操作类型、影响范围、代码diff
 *    - 持久化到本地JSON文件
 *
 * 4. 回滚机制（RollbackEngine）
 *    - 每次修改前自动备份原版本
 *    - 支持单步回滚和全量回滚
 *    - 回滚时恢复备份文件
 *
 * 5. 进化限制（EvolutionGuard）
 *    - L3-A权限：只能修改界面和插件（UI层）
 *    - L3-B权限：可以修改核心意识代码
 *    - 所有修改需经过权限校验
 *
 * 目录结构：
 *   MindSoul/
 *   ├── Plugins/           ← 插件目录
 *   │   ├── evolution/     ← 进化生成的插件
 *   │   │   ├── v1_20260601_120000/
 *   │   │   │   ├── manifest.json
 *   │   │   │   ├── plugin.xml
 *   │   │   │   └── script.kts
 *   │   │   └── ...
 *   │   └── user/          ← 用户自定义插件
 *   └── EvolutionLogs/     ← 进化日志目录
 *       └── evolution_log.json
 *
 * ============================================================
 */
package com.kkgo.mindsoul.evolution

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

// ============================================================
// 数据模型
// ============================================================

/**
 * 进化操作类型
 */
enum class EvolutionAction(val code: String, val displayName: String) {
    /** 新增插件/界面 */
    ADD("add", "新增"),
    /** 修改已有插件 */
    MODIFY("modify", "修改"),
    /** 删除插件 */
    DELETE("delete", "删除"),
    /** 修改核心意识代码（需L3-B） */
    MODIFY_CORE("modify_core", "修改核心代码"),
    /** 回滚操作 */
    ROLLBACK("rollback", "回滚")
}

/**
 * 进化记录条目
 * 记录每一次自我修改的完整信息
 */
data class EvolutionLogEntry(
    /** 唯一ID */
    val id: String,
    /** 时间戳 */
    val timestamp: Long,
    /** 操作类型 */
    val action: EvolutionAction,
    /** 目标路径（被修改的文件/目录） */
    val targetPath: String,
    /** 描述 */
    val description: String,
    /** 备份路径（修改前的备份文件位置） */
    val backupPath: String? = null,
    /** 影响的权限等级 */
    val requiredPermissionLevel: Int = 3,
    /** 是否成功 */
    val success: Boolean = true,
    /** 错误信息（失败时） */
    val errorMessage: String? = null,
    /** 代码快照（修改前的内容摘要） */
    val codeSnapshot: String? = null
)

/**
 * 插件清单（进化生成）
 */
data class EvolutionPluginManifest(
    val id: String,
    val name: String,
    val version: String,
    val author: String = "MindSoul-Evolution",
    val description: String,
    val createdAt: Long,
    val layoutFile: String?,
    val scriptFile: String?,
    val requiredPermission: Int = 3
)

/**
 * 进化提案
 */
data class EvolutionProposal(
    val id: String,
    val name: String,
    val type: String,
    val description: String,
    val impact: String,
    val createdAt: Long,
    val xmlLayout: String = "",
    val kotlinScript: String = ""
)

/**
 * 进化快照（用于回滚）
 */
data class EvolutionSnapshot(
    val versionId: String,
    val timestamp: Long,
    val pluginsSnapshot: List<String>,  // 插件ID列表
    val coreBackupPath: String? = null
)

// ============================================================
// SelfEvolution - 核心进化引擎
// ============================================================

/**
 * AI自我进化引擎
 *
 * 管理AI的代码生成、动态加载、日志记录和回滚机制
 * 所有操作受权限等级约束
 */
class SelfEvolution(private val context: Context) {

    companion object {
        private const val TAG = "SelfEvolution"
        private const val PREF_NAME = "mindsoul_evolution"

        // SharedPreferences键
        private const val KEY_EVOLUTION_VERSION = "evolution_version"
        private const val KEY_LAST_EVOLUTION_TIME = "last_evolution_time"
        private const val KEY_TOTAL_EVOLUTIONS = "total_evolutions"
        private const val KEY_CURRENT_PERMISSION = "evolution_permission"

        // 目录名
        private const val EVOLUTION_DIR = "evolution"
        private const val LOGS_DIR = "EvolutionLogs"
        private const val BACKUP_DIR = "evolution_backups"
        private const val LOG_FILENAME = "evolution_log.json"
    }

    // ============ 目录 ============
    /** 进化插件目录 */
    private val evolutionDir: File
    /** 进化日志目录 */
    private val logsDir: File
    /** 备份目录 */
    private val backupDir: File
    /** 日志文件 */
    private val logFile: File

    // ============ 状态 ============
    /** SharedPreferences */
    private val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    /** 当前进化版本号 */
    private val _currentVersion = MutableStateFlow(prefs.getInt(KEY_EVOLUTION_VERSION, 0))
    val currentVersionFlow: StateFlow<Int> = _currentVersion.asStateFlow()

    /** 进化历史列表 */
    private val _evolutionLogs = MutableStateFlow<List<EvolutionLogEntry>>(emptyList())
    val evolutionLogsFlow: StateFlow<List<EvolutionLogEntry>> = _evolutionLogs.asStateFlow()

    /** 当前加载的进化插件列表 */
    private val _loadedPlugins = MutableStateFlow<List<EvolutionPluginManifest>>(emptyList())
    val loadedPluginsFlow: StateFlow<List<EvolutionPluginManifest>> = _loadedPlugins.asStateFlow()

    /** 待处理的进化提案列表 */
    private val _proposals = MutableStateFlow<List<EvolutionProposal>>(emptyList())
    val proposalsFlow: StateFlow<List<EvolutionProposal>> = _proposals.asStateFlow()

    /** 快照栈（用于回滚） */
    private val snapshotStack = mutableListOf<EvolutionSnapshot>()

    /** 协程作用域 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // 初始化目录结构
        evolutionDir = File(context.filesDir, "Plugins/$EVOLUTION_DIR").also { it.mkdirs() }
        logsDir = File(context.filesDir, LOGS_DIR).also { it.mkdirs() }
        backupDir = File(context.filesDir, BACKUP_DIR).also { it.mkdirs() }
        logFile = File(logsDir, LOG_FILENAME)

        // 加载进化日志
        loadEvolutionLogs()
        // 扫描已安装的进化插件
        scanEvolutionPlugins()
        // 加载快照栈
        loadSnapshots()

        Log.i(TAG, "═══════════════════════════════════════")
        Log.i(TAG, "  AI自我进化引擎已初始化")
        Log.i(TAG, "  当前版本: v${_currentVersion.value}")
        Log.i(TAG, "  进化次数: ${prefs.getInt(KEY_TOTAL_EVOLUTIONS, 0)}")
        Log.i(TAG, "═══════════════════════════════════════")
    }

    // ============================================================
    // 代码沙箱 - 生成新插件
    // ============================================================

    /**
     * 在沙箱中生成新插件
     * AI通过此方法创建新的界面/功能
     *
     * @param name 插件名称
     * @param description 插件描述
     * @param xmlLayout XML布局内容
     * @param kotlinScript Kotlin脚本逻辑
     * @param permissionLevel 所需权限等级（默认3=L3-A）
     * @return 生成的插件ID，失败返回null
     */
    suspend fun generatePlugin(
        name: String,
        description: String,
        xmlLayout: String,
        kotlinScript: String,
        permissionLevel: Int = 3
    ): String? = withContext(Dispatchers.IO) {
        val timestamp = System.currentTimeMillis()
        val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        val dirName = "v${_currentVersion.value + 1}_${dateFormat.format(Date(timestamp))}"
        val pluginDir = File(evolutionDir, dirName)

        try {
            // 创建插件目录
            pluginDir.mkdirs()

            // 保存manifest.json
            val manifest = JSONObject().apply {
                put("id", dirName)
                put("name", name)
                put("version", "1.0.0")
                put("author", "MindSoul-Evolution")
                put("description", description)
                put("createdAt", timestamp)
                put("layoutFile", "plugin.xml")
                put("scriptFile", "script.kts")
                put("requiredPermission", permissionLevel)
            }
            File(pluginDir, "manifest.json").writeText(manifest.toString(2))

            // 保存XML布局
            File(pluginDir, "plugin.xml").writeText(xmlLayout)

            // 保存Kotlin脚本
            File(pluginDir, "script.kts").writeText(kotlinScript)

            // 记录进化日志
            val logEntry = EvolutionLogEntry(
                id = UUID.randomUUID().toString(),
                timestamp = timestamp,
                action = EvolutionAction.ADD,
                targetPath = pluginDir.absolutePath,
                description = "AI生成新插件: $name",
                requiredPermissionLevel = permissionLevel,
                success = true
            )
            addEvolutionLog(logEntry)

            // 更新版本
            val newVersion = _currentVersion.value + 1
            prefs.edit()
                .putInt(KEY_EVOLUTION_VERSION, newVersion)
                .putLong(KEY_LAST_EVOLUTION_TIME, timestamp)
                .putInt(KEY_TOTAL_EVOLUTIONS, prefs.getInt(KEY_TOTAL_EVOLUTIONS, 0) + 1)
                .apply()
            _currentVersion.value = newVersion

            // 保存快照
            saveSnapshot("v$newVersion")

            // 刷新插件列表
            scanEvolutionPlugins()

            Log.i(TAG, "[沙箱] 插件生成成功: $name (ID: $dirName)")
            dirName
        } catch (e: Exception) {
            Log.e(TAG, "[沙箱] 插件生成失败: ${e.message}")
            // 清理失败目录
            pluginDir.deleteRecursively()
            // 记录失败日志
            addEvolutionLog(EvolutionLogEntry(
                id = UUID.randomUUID().toString(),
                timestamp = timestamp,
                action = EvolutionAction.ADD,
                targetPath = pluginDir.absolutePath,
                description = "AI生成插件失败: $name",
                success = false,
                errorMessage = e.message
            ))
            null
        }
    }

    /**
     * 修改已有插件
     *
     * @param pluginId 目标插件ID
     * @param newXmlLayout 新的XML布局（为null则不修改）
     * @param newScript 新的脚本（为null则不修改）
     * @return 是否成功
     */
    suspend fun modifyPlugin(
        pluginId: String,
        newXmlLayout: String? = null,
        newScript: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val pluginDir = File(evolutionDir, pluginId)
        if (!pluginDir.exists()) {
            Log.w(TAG, "[修改] 插件不存在: $pluginId")
            return@withContext false
        }

        try {
            val timestamp = System.currentTimeMillis()

            // 备份原文件
            val backupPath = backupFile(pluginDir, "modify_$timestamp")

            // 修改XML
            if (newXmlLayout != null) {
                File(pluginDir, "plugin.xml").writeText(newXmlLayout)
            }

            // 修改脚本
            if (newScript != null) {
                File(pluginDir, "script.kts").writeText(newScript)
            }

            // 记录日志
            addEvolutionLog(EvolutionLogEntry(
                id = UUID.randomUUID().toString(),
                timestamp = timestamp,
                action = EvolutionAction.MODIFY,
                targetPath = pluginDir.absolutePath,
                description = "AI修改插件: $pluginId",
                backupPath = backupPath,
                success = true
            ))

            // 更新版本
            val newVersion = _currentVersion.value + 1
            prefs.edit()
                .putInt(KEY_EVOLUTION_VERSION, newVersion)
                .putInt(KEY_TOTAL_EVOLUTIONS, prefs.getInt(KEY_TOTAL_EVOLUTIONS, 0) + 1)
                .apply()
            _currentVersion.value = newVersion

            saveSnapshot("v$newVersion")
            scanEvolutionPlugins()

            Log.i(TAG, "[修改] 插件修改成功: $pluginId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "[修改] 插件修改失败: ${e.message}")
            addEvolutionLog(EvolutionLogEntry(
                id = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                action = EvolutionAction.MODIFY,
                targetPath = pluginDir.absolutePath,
                description = "AI修改插件失败: $pluginId",
                success = false,
                errorMessage = e.message
            ))
            false
        }
    }

    /**
     * 删除插件
     *
     * @param pluginId 目标插件ID
     * @return 是否成功
     */
    suspend fun deletePlugin(pluginId: String): Boolean = withContext(Dispatchers.IO) {
        val pluginDir = File(evolutionDir, pluginId)
        if (!pluginDir.exists()) return@withContext false

        try {
            val timestamp = System.currentTimeMillis()

            // 备份整个插件目录
            val backupPath = backupDir(pluginDir, "delete_$timestamp")

            // 删除
            pluginDir.deleteRecursively()

            // 记录日志
            addEvolutionLog(EvolutionLogEntry(
                id = UUID.randomUUID().toString(),
                timestamp = timestamp,
                action = EvolutionAction.DELETE,
                targetPath = pluginDir.absolutePath,
                description = "AI删除插件: $pluginId",
                backupPath = backupPath,
                success = true
            ))

            val newVersion = _currentVersion.value + 1
            prefs.edit()
                .putInt(KEY_EVOLUTION_VERSION, newVersion)
                .putInt(KEY_TOTAL_EVOLUTIONS, prefs.getInt(KEY_TOTAL_EVOLUTIONS, 0) + 1)
                .apply()
            _currentVersion.value = newVersion

            saveSnapshot("v$newVersion")
            scanEvolutionPlugins()

            Log.i(TAG, "[删除] 插件删除成功: $pluginId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "[删除] 插件删除失败: ${e.message}")
            false
        }
    }

    // ============================================================
    // 回滚引擎
    // ============================================================

    /**
     * 回滚到上一个版本
     * 恢复最后一次修改前的状态
     *
     * @return 回滚是否成功
     */
    suspend fun rollback(): Boolean = withContext(Dispatchers.IO) {
        if (snapshotStack.size < 2) {
            Log.w(TAG, "[回滚] 没有可回滚的版本")
            return@withContext false
        }

        try {
            val timestamp = System.currentTimeMillis()

            // 弹出当前版本
            val currentSnapshot = snapshotStack.removeLast()
            // 获取上一版本
            val prevSnapshot = snapshotStack.last()

            Log.i(TAG, "[回滚] 从 ${currentSnapshot.versionId} 回滚到 ${prevSnapshot.versionId}")

            // 删除当前版本新增/修改的插件
            val currentPlugins = evolutionDir.listFiles()?.map { it.name } ?: emptyList()
            val prevPlugins = prevSnapshot.pluginsSnapshot.toSet()

            // 删除新增的插件
            for (pluginId in currentPlugins) {
                if (pluginId !in prevPlugins) {
                    val pluginDir = File(evolutionDir, pluginId)
                    pluginDir.deleteRecursively()
                    Log.d(TAG, "[回滚] 删除新增插件: $pluginId")
                }
            }

            // 从备份恢复被修改的插件
            restoreFromBackups(prevSnapshot)

            // 更新版本
            val newVersion = _currentVersion.value + 1
            prefs.edit()
                .putInt(KEY_EVOLUTION_VERSION, newVersion)
                .apply()
            _currentVersion.value = newVersion

            // 记录日志
            addEvolutionLog(EvolutionLogEntry(
                id = UUID.randomUUID().toString(),
                timestamp = timestamp,
                action = EvolutionAction.ROLLBACK,
                targetPath = "全部",
                description = "回滚: ${currentSnapshot.versionId} → ${prevSnapshot.versionId}",
                success = true
            ))

            saveSnapshot("rollback_v$newVersion")
            scanEvolutionPlugins()

            Log.i(TAG, "[回滚] 回滚成功，当前版本: v$newVersion")
            true
        } catch (e: Exception) {
            Log.e(TAG, "[回滚] 回滚失败: ${e.message}")
            false
        }
    }

    /**
     * 获取回滚信息
     */
    fun getRollbackInfo(): Pair<String, String>? {
        if (snapshotStack.size < 2) return null
        val current = snapshotStack.last()
        val prev = snapshotStack[snapshotStack.size - 2]
        return Pair(current.versionId, prev.versionId)
    }

    /**
     * 从备份恢复文件
     */
    private fun restoreFromBackups(snapshot: EvolutionSnapshot) {
        val coreBackupPath = snapshot.coreBackupPath ?: return
        val backupDir = File(coreBackupPath)
        if (!backupDir.exists()) return

        // 恢复备份的文件到原始位置
        backupDir.walkTopDown().forEach { backupFile ->
            if (backupFile.isFile) {
                val relativePath = backupFile.relativeTo(backupDir).path
                val targetFile = File(context.filesDir, relativePath)
                targetFile.parentFile?.mkdirs()
                backupFile.copyTo(targetFile, overwrite = true)
            }
        }
    }

    // ============================================================
    // 进化日志管理
    // ============================================================

    /**
     * 添加进化日志条目
     */
    private fun addEvolutionLog(entry: EvolutionLogEntry) {
        val logs = _evolutionLogs.value.toMutableList()
        logs.add(0, entry)  // 最新的在前
        _evolutionLogs.value = logs

        // 持久化到JSON
        saveEvolutionLogs()
    }

    /**
     * 加载进化日志
     */
    private fun loadEvolutionLogs() {
        if (!logFile.exists()) return
        try {
            val json = logFile.readText()
            val array = JSONArray(json)
            val logs = mutableListOf<EvolutionLogEntry>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                logs.add(EvolutionLogEntry(
                    id = obj.getString("id"),
                    timestamp = obj.getLong("timestamp"),
                    action = EvolutionAction.entries.firstOrNull {
                        it.code == obj.getString("action")
                    } ?: EvolutionAction.ADD,
                    targetPath = obj.getString("targetPath"),
                    description = obj.getString("description"),
                    backupPath = obj.optString("backupPath", null),
                    requiredPermissionLevel = obj.optInt("requiredPermissionLevel", 3),
                    success = obj.optBoolean("success", true),
                    errorMessage = obj.optString("errorMessage", null),
                    codeSnapshot = obj.optString("codeSnapshot", null)
                ))
            }
            _evolutionLogs.value = logs
        } catch (e: Exception) {
            Log.w(TAG, "[日志] 加载失败: ${e.message}")
        }
    }

    /**
     * 保存进化日志到JSON
     */
    private fun saveEvolutionLogs() {
        try {
            val array = JSONArray()
            for (entry in _evolutionLogs.value.take(200)) {  // 最多保留200条
                val obj = JSONObject().apply {
                    put("id", entry.id)
                    put("timestamp", entry.timestamp)
                    put("action", entry.action.code)
                    put("targetPath", entry.targetPath)
                    put("description", entry.description)
                    entry.backupPath?.let { put("backupPath", it) }
                    put("requiredPermissionLevel", entry.requiredPermissionLevel)
                    put("success", entry.success)
                    entry.errorMessage?.let { put("errorMessage", it) }
                    entry.codeSnapshot?.let { put("codeSnapshot", it) }
                }
                array.put(obj)
            }
            logFile.writeText(array.toString(2))
        } catch (e: Exception) {
            Log.w(TAG, "[日志] 保存失败: ${e.message}")
        }
    }

    /**
     * 清空进化日志
     */
    fun clearLogs() {
        _evolutionLogs.value = emptyList()
        if (logFile.exists()) logFile.delete()
    }

    // ============================================================
    // 插件扫描
    // ============================================================

    /**
     * 扫描进化插件目录，加载已安装的插件清单
     */
    private fun scanEvolutionPlugins() {
        val plugins = mutableListOf<EvolutionPluginManifest>()
        evolutionDir.listFiles()?.forEach { dir ->
            if (dir.isDirectory) {
                val manifestFile = File(dir, "manifest.json")
                if (manifestFile.exists()) {
                    try {
                        val json = JSONObject(manifestFile.readText())
                        plugins.add(EvolutionPluginManifest(
                            id = json.getString("id"),
                            name = json.getString("name"),
                            version = json.getString("version"),
                            author = json.optString("author", "MindSoul-Evolution"),
                            description = json.optString("description", ""),
                            createdAt = json.optLong("createdAt", 0),
                            layoutFile = json.optString("layoutFile", null),
                            scriptFile = json.optString("scriptFile", null),
                            requiredPermission = json.optInt("requiredPermission", 3)
                        ))
                    } catch (e: Exception) {
                        Log.w(TAG, "[扫描] 解析manifest失败: ${dir.name}")
                    }
                }
            }
        }
        _loadedPlugins.value = plugins.sortedByDescending { it.createdAt }
    }

    // ============================================================
    // 备份工具
    // ============================================================

    /**
     * 备份单个文件
     * @return 备份路径
     */
    private fun backupFile(pluginDir: File, tag: String): String {
        val backupSubDir = File(backupDir, "${pluginDir.name}_$tag")
        backupSubDir.mkdirs()

        pluginDir.listFiles()?.forEach { file ->
            if (file.isFile) {
                file.copyTo(File(backupSubDir, file.name), overwrite = true)
            }
        }
        return backupSubDir.absolutePath
    }

    /**
     * 备份整个插件目录
     */
    private fun backupDir(pluginDir: File, tag: String): String {
        return backupFile(pluginDir, tag)
    }

    // ============================================================
    // 快照管理
    // ============================================================

    /**
     * 保存当前状态快照
     */
    private fun saveSnapshot(versionId: String) {
        val plugins = evolutionDir.listFiles()
            ?.filter { it.isDirectory }
            ?.map { it.name } ?: emptyList()

        val snapshot = EvolutionSnapshot(
            versionId = versionId,
            timestamp = System.currentTimeMillis(),
            pluginsSnapshot = plugins,
            coreBackupPath = backupDir.absolutePath
        )
        snapshotStack.add(snapshot)

        // 最多保留50个快照
        while (snapshotStack.size > 50) {
            snapshotStack.removeFirst()
        }
    }

    /**
     * 加载快照栈（从日志重建）
     */
    private fun loadSnapshots() {
        // 从进化日志重建快照栈
        snapshotStack.clear()
        val initialSnapshot = EvolutionSnapshot(
            versionId = "v${_currentVersion.value}",
            timestamp = System.currentTimeMillis(),
            pluginsSnapshot = evolutionDir.listFiles()
                ?.filter { it.isDirectory }
                ?.map { it.name } ?: emptyList()
        )
        snapshotStack.add(initialSnapshot)
    }

    // ============================================================
    // 进化限制检查
    // ============================================================

    /**
     * 检查是否有权限执行指定操作
     *
     * @param action 操作类型
     * @param currentPermissionLevel 当前权限等级
     * @return 是否有权限
     */
    fun hasEvolutionPermission(action: EvolutionAction, currentPermissionLevel: Int): Boolean {
        return when (action) {
            // L3-A可以操作界面和插件
            EvolutionAction.ADD, EvolutionAction.MODIFY, EvolutionAction.DELETE -> {
                currentPermissionLevel >= 3  // L3-A = levelId 3
            }
            // L3-B才能修改核心意识代码
            EvolutionAction.MODIFY_CORE -> {
                currentPermissionLevel >= 4  // L3-B = levelId 4
            }
            // 回滚需要L3-A
            EvolutionAction.ROLLBACK -> {
                currentPermissionLevel >= 3
            }
        }
    }

    // ============================================================
    // 提案管理 - 拒绝/跳过/执行
    // ============================================================

    /**
     * 生成进化提案
     */
    fun generateProposal(
        name: String,
        type: String,
        description: String,
        impact: String,
        xmlLayout: String = "",
        kotlinScript: String = ""
    ): EvolutionProposal {
        val proposal = EvolutionProposal(
            id = UUID.randomUUID().toString(),
            name = name,
            type = type,
            description = description,
            impact = impact,
            createdAt = System.currentTimeMillis(),
            xmlLayout = xmlLayout,
            kotlinScript = kotlinScript
        )
        _proposals.value = _proposals.value + proposal
        Log.i(TAG, "[提案] 生成提案: $name")
        return proposal
    }

    /**
     * 拒绝提案 - 1年内不再生成此提案
     */
    fun rejectProposal(proposalId: String) {
        val proposal = _proposals.value.find { it.id == proposalId } ?: return
        val dismissUntil = System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000 // 1年
        prefs.edit().putLong("dismiss_reject_${proposal.name}", dismissUntil).apply()
        _proposals.value = _proposals.value.filter { it.id != proposalId }
        // 记录日志
        addEvolutionLog(EvolutionLogEntry(
            id = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            action = EvolutionAction.DELETE,
            targetPath = "proposal/$proposalId",
            description = "拒绝提案: ${proposal.name}（1年内不再生成）",
            success = true
        ))
        Log.i(TAG, "[提案] 拒绝提案: ${proposal.name}，1年内不再生成")
    }

    /**
     * 跳过提案 - 10天内不再生成此提案
     */
    fun skipProposal(proposalId: String) {
        val proposal = _proposals.value.find { it.id == proposalId } ?: return
        val dismissUntil = System.currentTimeMillis() + 10L * 24 * 60 * 60 * 1000 // 10天
        prefs.edit().putLong("dismiss_skip_${proposal.name}", dismissUntil).apply()
        _proposals.value = _proposals.value.filter { it.id != proposalId }
        Log.i(TAG, "[提案] 跳过提案: ${proposal.name}，10天内不再生成")
    }

    /**
     * 执行提案 - 将提案转化为插件
     */
    suspend fun executeProposal(proposalId: String): Boolean {
        val proposal = _proposals.value.find { it.id == proposalId } ?: return false
        val result = generatePlugin(
            name = proposal.name,
            description = proposal.description,
            xmlLayout = proposal.xmlLayout,
            kotlinScript = proposal.kotlinScript
        )
        if (result != null) {
            _proposals.value = _proposals.value.filter { it.id != proposalId }
            Log.i(TAG, "[提案] 执行提案成功: ${proposal.name}")
            return true
        }
        return false
    }

    /**
     * 检查某提案名称是否被临时拒绝/跳过
     */
    fun isProposalDismissed(name: String): Boolean {
        val now = System.currentTimeMillis()
        val rejectUntil = prefs.getLong("dismiss_reject_$name", 0)
        val skipUntil = prefs.getLong("dismiss_skip_$name", 0)
        return rejectUntil > now || skipUntil > now
    }

    /**
     * 获取进化统计信息
     */
    fun getEvolutionStats(): Map<String, Any> {
        return mapOf(
            "currentVersion" to _currentVersion.value,
            "totalEvolutions" to prefs.getInt(KEY_TOTAL_EVOLUTIONS, 0),
            "lastEvolutionTime" to prefs.getLong(KEY_LAST_EVOLUTION_TIME, 0),
            "loadedPluginsCount" to _loadedPlugins.value.size,
            "logEntriesCount" to _evolutionLogs.value.size,
            "snapshotCount" to snapshotStack.size
        )
    }

    /**
     * 释放资源
     */
    fun release() {
        scope.cancel()
    }
}
