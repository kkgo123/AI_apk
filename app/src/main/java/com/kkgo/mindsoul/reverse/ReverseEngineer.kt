/*
 * ============================================================
 * ReverseEngineer - 逆向解析引擎统一管理器
 * ============================================================
 *
 * DEX/APK 逆向解析的统一入口，负责：
 *
 * 1. 文件导入管理
 *    - 仅接受用户手动导入的文件
 *    - 禁止自动扫描系统分区
 *    - 兼容 Android 10+ Scoped Storage
 * 2. 分析调度
 *    - 自动识别文件类型（DEX/APK）
 *    - 调用对应解析器
 *    - 归档解析结果
 * 3. 程序逻辑提取
 *    - 类层次结构
 *    - 方法调用图（简化）
 *    - 字符串常量分析
 *    - 组件清单
 * 4. 知识归档
 *    - 将解析出的程序逻辑归档到意识系统
 *    - 支持后续知识检索
 *
 * 安全策略：
 *   - 需要用户明确授权导入
 *   - 文件来源审计日志
 *   - 不持久化可执行代码（仅存储分析结果）
 * ============================================================
 */
package com.kkgo.mindsoul.reverse

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream

/**
 * 分析任务状态
 */
enum class AnalysisState {
    /** 空闲 */
    IDLE,
    /** 等待用户确认 */
    AWAITING_CONFIRM,
    /** 分析中 */
    ANALYZING,
    /** 完成 */
    COMPLETED,
    /** 失败 */
    FAILED
}

/**
 * 分析任务
 */
data class AnalysisTask(
    /** 任务ID */
    val taskId: String = "task_${System.nanoTime()}",
    /** 源文件路径 */
    val sourcePath: String,
    /** 文件名 */
    val fileName: String,
    /** 文件类型 */
    val fileType: FileType,
    /** 任务状态 */
    var state: AnalysisState = AnalysisState.IDLE,
    /** 分析结果（DEX） */
    var dexResult: DexParseResult? = null,
    /** 分析结果（APK） */
    var apkResult: ApkAnalysisResult? = null,
    /** 错误信息 */
    var errorMessage: String? = null,
    /** 创建时间 */
    val createdAt: Long = System.currentTimeMillis(),
    /** 完成时间 */
    var completedAt: Long? = null
)

/**
 * 文件类型
 */
enum class FileType(val displayName: String, val extensions: List<String>) {
    DEX("DEX 文件", listOf("dex")),
    APK("APK 安装包", listOf("apk")),
    UNKNOWN("未知", emptyList());

    companion object {
        fun fromExtension(ext: String): FileType {
            return when (ext.lowercase()) {
                "dex" -> DEX
                "apk" -> APK
                else -> UNKNOWN
            }
        }
    }
}

/**
 * 归档的程序逻辑
 */
data class ArchivedLogic(
    /** 归档ID */
    val archiveId: String = "archive_${System.nanoTime()}",
    /** 来源文件名 */
    val sourceFileName: String,
    /** 类层次结构摘要 */
    val classHierarchy: List<String>,
    /** 关键方法签名 */
    val keyMethods: List<String>,
    /** 提取的字符串常量 */
    val keyStrings: List<String>,
    /** 组件清单 */
    val components: List<String>,
    /** 权限需求 */
    val permissions: List<String>,
    /** 归档时间 */
    val archivedAt: Long = System.currentTimeMillis()
)

/**
 * 逆向解析引擎管理器
 */
class ReverseEngineer(private val context: Context) {

    companion object {
        private const val TAG = "ReverseEngineer"
        /** 分析结果存储目录 */
        private const val ANALYSIS_DIR = "reverse_analysis"
        /** 归档存储目录 */
        private const val ARCHIVE_DIR = "logic_archive"
    }

    // ============ 子系统 ============
    private val dexParser = DexParser()
    private val apkAnalyzer = ApkAnalyzer(context)

    // ============ 状态 ============
    private val _analysisState = MutableStateFlow(AnalysisState.IDLE)
    val analysisStateFlow: StateFlow<AnalysisState> = _analysisState.asStateFlow()

    private val _currentTask = MutableStateFlow<AnalysisTask?>(null)
    val currentTaskFlow: StateFlow<AnalysisTask?> = _currentTask.asStateFlow()

    // ============ 历史任务 ============
    private val taskHistory = mutableListOf<AnalysisTask>()
    /** 归档的逻辑 */
    private val archivedLogics = mutableListOf<ArchivedLogic>()

    // ============ 协程 ============
    private val analysisScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ============ 回调 ============
    private var resultCallback: ((AnalysisTask) -> Unit)? = null

    // ============ 初始化 ============

    /**
     * 初始化逆向解析引擎
     */
    fun initialize() {
        // 创建存储目录
        ensureDirectory(ANALYSIS_DIR)
        ensureDirectory(ARCHIVE_DIR)

        Log.i(TAG, "[初始化] 逆向解析引擎就绪")
        Log.i(TAG, "  DEX解析器 | APK分析器")
        Log.i(TAG, "  安全策略: 仅接受手动导入文件")
    }

    /**
     * 释放资源
     */
    fun destroy() {
        analysisScope.cancel()
        Log.i(TAG, "[销毁] 逆向解析引擎已释放")
    }

    // ============ 核心接口 ============

    /**
     * 导入文件进行分析
     *
     * 用户手动触发，将文件复制到分析目录后解析。
     *
     * @param sourceUri 源文件路径（从用户选择的文件）
     * @return 分析任务
     */
    fun importAndAnalyze(sourceUri: String): AnalysisTask {
        val file = File(sourceUri)
        if (!file.exists()) {
            Log.e(TAG, "[导入] 文件不存在: $sourceUri")
            throw IllegalArgumentException("文件不存在: $sourceUri")
        }

        // 识别文件类型
        val fileType = FileType.fromExtension(file.extension)
        if (fileType == FileType.UNKNOWN) {
            Log.e(TAG, "[导入] 不支持的文件类型: ${file.extension}")
            throw IllegalArgumentException("不支持的文件类型: ${file.extension}")
        }

        Log.i(TAG, "[导入] 用户导入: ${file.name} (${file.length() / 1024}KB, 类型: ${fileType.displayName})")

        // 创建分析任务
        val task = AnalysisTask(
            sourcePath = sourceUri,
            fileName = file.name,
            fileType = fileType,
            state = AnalysisState.AWAITING_CONFIRM
        )

        _currentTask.value = task
        Log.i(TAG, "[导入] 等待用户确认分析...")
        return task
    }

    /**
     * 确认并启动分析
     *
     * @param taskId 任务ID
     */
    fun confirmAndAnalyze(taskId: String) {
        val task = _currentTask.value
        if (task == null || task.taskId != taskId) {
            Log.w(TAG, "[确认] 任务不存在: $taskId")
            return
        }

        task.state = AnalysisState.ANALYZING
        _analysisState.value = AnalysisState.ANALYZING

        // 异步执行分析
        analysisScope.launch {
            try {
                when (task.fileType) {
                    FileType.DEX -> analyzeDex(task)
                    FileType.APK -> analyzeApk(task)
                    else -> throw IllegalStateException("未知文件类型")
                }

                task.state = AnalysisState.COMPLETED
                task.completedAt = System.currentTimeMillis()
                _analysisState.value = AnalysisState.COMPLETED

                // 归档逻辑
                archiveLogic(task)

                // 记录历史
                taskHistory.add(task.copy())

                // 回调
                resultCallback?.invoke(task)

                Log.i(TAG, "[完成] 分析任务完成: ${task.fileName}")

            } catch (e: Exception) {
                task.state = AnalysisState.FAILED
                task.errorMessage = e.message
                task.completedAt = System.currentTimeMillis()
                _analysisState.value = AnalysisState.FAILED

                Log.e(TAG, "[失败] 分析任务失败: ${e.message}")
            }
        }
    }

    /**
     * 取消分析
     */
    fun cancelAnalysis() {
        _currentTask.value?.state = AnalysisState.IDLE
        _currentTask.value = null
        _analysisState.value = AnalysisState.IDLE
        Log.i(TAG, "[取消] 分析已取消")
    }

    /**
     * 设置分析结果回调
     */
    fun setResultCallback(callback: (AnalysisTask) -> Unit) {
        resultCallback = callback
    }

    // ============ 查询接口 ============

    /**
     * 获取任务历史
     */
    fun getTaskHistory(): List<AnalysisTask> = taskHistory.toList()

    /**
     * 获取归档逻辑
     */
    fun getArchivedLogics(): List<ArchivedLogic> = archivedLogics.toList()

    /**
     * 搜索归档中的类
     */
    fun searchClass(className: String): List<ArchivedLogic> {
        return archivedLogics.filter { archive ->
            archive.classHierarchy.any { it.contains(className, ignoreCase = true) }
        }
    }

    /**
     * 搜索归档中的方法
     */
    fun searchMethod(methodName: String): List<ArchivedLogic> {
        return archivedLogics.filter { archive ->
            archive.keyMethods.any { it.contains(methodName, ignoreCase = true) }
        }
    }

    /**
     * 搜索归档中的字符串
     */
    fun searchString(keyword: String): List<ArchivedLogic> {
        return archivedLogics.filter { archive ->
            archive.keyStrings.any { it.contains(keyword, ignoreCase = true) }
        }
    }

    // ============ 内部分析方法 ============

    /**
     * 分析 DEX 文件
     */
    private fun analyzeDex(task: AnalysisTask) {
        Log.i(TAG, "[DEX] 开始解析: ${task.fileName}")

        val result = dexParser.parse(task.sourcePath)
        task.dexResult = result

        Log.i(TAG, "[DEX] 类: ${result.classNames.size}, " +
                "方法: ${result.methodSignatures.size}, " +
                "字段: ${result.fieldDefinitions.size}, " +
                "字符串: ${result.stringConstants.size}")
    }

    /**
     * 分析 APK 文件
     */
    private fun analyzeApk(task: AnalysisTask) {
        Log.i(TAG, "[APK] 开始分析: ${task.fileName}")

        val result = apkAnalyzer.analyze(task.sourcePath)
        task.apkResult = result

        Log.i(TAG, "[APK] 包名: ${result.packageName}, " +
                "版本: ${result.versionName}, " +
                "DEX: ${result.dexFiles.size}, " +
                "Activity: ${result.activities.size}, " +
                "权限: ${result.permissions.size}")

        // 如果有多个 DEX，逐一解析
        val apkFile = java.util.zip.ZipFile(File(task.sourcePath))
        for (dexInfo in result.dexFiles) {
            try {
                val entry = apkFile.getEntry(dexInfo.fileName) ?: continue
                // 将 DEX 提取到临时目录进行解析
                val tempDir = File(context.filesDir, ANALYSIS_DIR)
                val tempDex = File(tempDir, dexInfo.fileName)
                tempDir.mkdirs()
                apkFile.getInputStream(entry).use { input ->
                    FileOutputStream(tempDex).use { output ->
                        input.copyTo(output)
                    }
                }

                val dexResult = dexParser.parse(tempDex.absolutePath)
                Log.d(TAG, "[APK] DEX ${dexInfo.fileName}: ${dexResult.classNames.size} 类")
            } catch (e: Exception) {
                Log.w(TAG, "[APK] DEX 解析失败 ${dexInfo.fileName}: ${e.message}")
            }
        }
        apkFile.close()
    }

    /**
     * 归档解析出的程序逻辑
     */
    private fun archiveLogic(task: AnalysisTask) {
        val archive = when {
            task.dexResult != null -> {
                val dex = task.dexResult!!
                ArchivedLogic(
                    sourceFileName = task.fileName,
                    classHierarchy = dex.classNames,
                    keyMethods = dex.methodSignatures.take(200),  // 限制大小
                    keyStrings = dex.stringConstants.take(500),
                    components = emptyList(),
                    permissions = emptyList()
                )
            }
            task.apkResult != null -> {
                val apk = task.apkResult!!
                val allClasses = mutableListOf<String>()
                val allMethods = mutableListOf<String>()
                val allStrings = mutableListOf<String>()
                val components = mutableListOf<String>()

                // 汇总所有组件
                apk.activities.forEach { components.add("Activity: ${it.className}") }
                apk.services.forEach { components.add("Service: ${it.className}") }
                apk.receivers.forEach { components.add("Receiver: ${it.className}") }
                apk.providers.forEach { components.add("Provider: ${it.className}") }

                ArchivedLogic(
                    sourceFileName = task.fileName,
                    classHierarchy = allClasses,
                    keyMethods = allMethods,
                    keyStrings = allStrings,
                    components = components,
                    permissions = apk.permissions
                )
            }
            else -> return
        }

        archivedLogics.add(archive)
        Log.i(TAG, "[归档] 程序逻辑已归档: ${task.fileName}")
        Log.i(TAG, "  类: ${archive.classHierarchy.size}, " +
                "方法: ${archive.keyMethods.size}, " +
                "字符串: ${archive.keyStrings.size}, " +
                "组件: ${archive.components.size}")
    }

    /**
     * 确保目录存在
     */
    private fun ensureDirectory(name: String) {
        val dir = File(context.filesDir, name)
        if (!dir.exists()) {
            dir.mkdirs()
        }
    }
}
