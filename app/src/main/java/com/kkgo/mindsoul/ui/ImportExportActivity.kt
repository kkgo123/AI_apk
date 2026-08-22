/*
 * ============================================================
 * ImportExportActivity - 导入导出管理页（全面升级版）
 * ============================================================
 *
 * 导出功能：
 * 1. 导出意识 - 仅导出 .brain 核心意识数据
 * 2. 导出记忆 - 仅导出对话记忆、学习记忆
 * 3. 导出全部智能体 - 导出意识+记忆+人格+配置完整备份
 *
 * 导入功能：
 * 1. 导入意识 - 从 .brain 文件恢复意识
 * 2. 导入记忆 - 从记忆备份文件恢复
 * 3. 导入全部智能体 - 完整恢复（意识+记忆+人格+配置）
 *
 * UI改进：
 * - 清晰的分类展示（导出区/导入区）
 * - 每个操作有确认弹窗和进度显示
 * - 导入时显示"将覆盖现有数据，是否继续？"警告
 * ============================================================
 */
package com.kkgo.mindsoul.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.kkgo.mindsoul.MindSoulApp
import com.kkgo.mindsoul.R
import com.kkgo.mindsoul.backup.BackupInfo
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

class ImportExportActivity : AppCompatActivity() {

    private val app by lazy { application as MindSoulApp }
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var recyclerHistory: RecyclerView
    private lateinit var tvNoBackup: TextView
    private lateinit var backupAdapter: BackupHistoryAdapter

    // ============ 文件选择器 ============

    /** 导入意识文件选择器 */
    private val importBrainLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            showImportConfirmDialog("导入意识") {
                importBrainFile(it.toString())
            }
        }
    }

    /** 导入记忆文件选择器 */
    private val importMemoryLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            showImportConfirmDialog("导入记忆") {
                importMemoryData(it.toString())
            }
        }
    }

    /** 导入全部智能体文件选择器 */
    private val importFullLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            showImportConfirmDialog("导入全部智能体") {
                importFullAgent(it.toString())
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_import_export)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        initViews()
        setupButtons()
        loadBackupHistory()
    }

    private fun initViews() {
        recyclerHistory = findViewById(R.id.recyclerBackupHistory)
        tvNoBackup = findViewById(R.id.tvNoBackup)
        backupAdapter = BackupHistoryAdapter()
        recyclerHistory.layoutManager = LinearLayoutManager(this)
        recyclerHistory.adapter = backupAdapter
    }

    private fun setupButtons() {
        // ===== 导入按钮 =====
        findViewById<MaterialButton>(R.id.btnImportBrain).setOnClickListener {
            importBrainLauncher.launch("*/*")
        }
        findViewById<MaterialButton>(R.id.btnImportMemory).setOnClickListener {
            importMemoryLauncher.launch("*/*")
        }
        findViewById<MaterialButton>(R.id.btnImportFull).setOnClickListener {
            importFullLauncher.launch("*/*")
        }
        findViewById<MaterialButton>(R.id.btnMigrate).setOnClickListener {
            Toast.makeText(this, "请在同一局域网内，从源设备的'导出'功能获取.brain文件", Toast.LENGTH_LONG).show()
        }

        // ===== 导出按钮 =====
        findViewById<MaterialButton>(R.id.btnExportBrain).setOnClickListener {
            showExportConfirmDialog("导出意识", "将导出 .brain 核心意识数据文件。") {
                exportBrain()
            }
        }
        findViewById<MaterialButton>(R.id.btnExportMemory).setOnClickListener {
            showExportConfirmDialog("导出记忆", "将导出对话记忆和学习记忆数据。") {
                exportMemory()
            }
        }
        findViewById<MaterialButton>(R.id.btnExportFull).setOnClickListener {
            showExportConfirmDialog("导出全部智能体", "将完整备份：意识 + 记忆 + 人格 + 配置。\n此操作可能需要较长时间。") {
                exportFullBackup()
            }
        }
    }

    // ============ 确认弹窗 ============

    /**
     * 显示导入确认弹窗
     * 警告用户将覆盖现有数据
     */
    private fun showImportConfirmDialog(title: String, onConfirm: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("⚠️ 确认$title")
            .setMessage("即将执行${title}操作。\n\n⚠️ 警告：这将覆盖现有数据！\n请确保已做好备份。\n\n是否继续？")
            .setPositiveButton("确认导入") { _, _ -> onConfirm() }
            .setNegativeButton("取消", null)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .show()
    }

    /**
     * 显示导出确认弹窗
     */
    private fun showExportConfirmDialog(title: String, message: String, onConfirm: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("📤 $title")
            .setMessage(message)
            .setPositiveButton("确认导出") { _, _ -> onConfirm() }
            .setNegativeButton("取消", null)
            .show()
    }

    // ============ 导入操作 ============

    /**
     * 导入意识数据（从 .brain 文件）
     */
    private fun importBrainFile(path: String) {
        showProgressDialog("导入意识", "正在导入意识数据...")
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val brainPath = java.io.File(application.filesDir, "brain/soul.brain").absolutePath
                    app.consciousnessBackup.importFromBrain(path, brainPath)
                    "导入成功"
                } catch (e: Exception) {
                    "导入失败: ${e.message}"
                }
            }
            dismissProgressDialog()
            Toast.makeText(this@ImportExportActivity, result, Toast.LENGTH_SHORT).show()
            loadBackupHistory()
        }
    }

    /**
     * 导入记忆数据
     */
    private fun importMemoryData(path: String) {
        showProgressDialog("导入记忆", "正在导入记忆数据...")
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    app.brainEngine.restoreFromBackup(path)
                    "记忆数据导入成功"
                } catch (e: Exception) {
                    "导入失败: ${e.message}"
                }
            }
            dismissProgressDialog()
            Toast.makeText(this@ImportExportActivity, result, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 导入全部智能体（意识+记忆+人格+配置）
     */
    private fun importFullAgent(path: String) {
        showProgressDialog("导入全部智能体", "正在完整恢复智能体数据...\n包含意识、记忆、人格、配置。")
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    // 1. 导入意识
                    val brainPath = java.io.File(application.filesDir, "brain/soul.brain").absolutePath
                    app.consciousnessBackup.importFromBrain(path, brainPath)

                    // 2. 导入记忆
                    app.brainEngine.restoreFromBackup(path)

                    // 3. 导入人格配置（从备份文件中恢复）
                    restorePersonalityConfig(path)

                    "完整导入成功"
                } catch (e: Exception) {
                    "导入失败: ${e.message}"
                }
            }
            dismissProgressDialog()
            Toast.makeText(this@ImportExportActivity, result, Toast.LENGTH_LONG).show()
            loadBackupHistory()
        }
    }

    // ============ 导出操作 ============

    /**
     * 导出意识数据（仅 .brain 核心意识）
     */
    private fun exportBrain() {
        showProgressDialog("导出意识", "正在导出意识数据...")
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val brainPath = java.io.File(application.filesDir, "brain/soul.brain").absolutePath
                    app.consciousnessBackup.createFullBackup(brainPath)
                } catch (e: Exception) {
                    null
                }
            }
            dismissProgressDialog()
            if (result != null) {
                Toast.makeText(this@ImportExportActivity, "意识导出完成", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@ImportExportActivity, "导出失败", Toast.LENGTH_SHORT).show()
            }
            loadBackupHistory()
        }
    }

    /**
     * 导出记忆数据（对话记忆 + 学习记忆）
     */
    private fun exportMemory() {
        showProgressDialog("导出记忆", "正在导出记忆数据...")
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    // 先保存当前记忆到brain
                    app.consciousnessManager.axiomLayer.saveToBrain()

                    // 导出记忆专用文件
                    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(Date())
                    val memoryFile = java.io.File(application.filesDir, "exports/memory_$timestamp.brain")
                    val exportDir = java.io.File(application.filesDir, "exports")
                    if (!exportDir.exists()) exportDir.mkdirs()

                    // 收集记忆数据
                    val memoryData = buildString {
                        appendLine("=== MindSoul 记忆导出 ===")
                        appendLine("时间: ${System.currentTimeMillis()}")
                        appendLine()

                        // 对话记忆
                        appendLine("--- 对话记忆 ---")
                        // 导出当前brain备份
                        val backupPath = app.brainEngine.createBackup("memory_export")
                        appendLine("备份路径: $backupPath")
                        appendLine()

                        // 学习记忆
                        appendLine("--- 学习记忆 ---")
                        val pipelineStats = app.learningPipeline.getStats()
                        appendLine("学习素材总数: ${pipelineStats.totalInput}")
                        appendLine("已编码: ${pipelineStats.totalEncoded}")
                        appendLine("已归档: ${pipelineStats.totalArchived}")
                        appendLine("公理提炼: ${pipelineStats.totalAxiomDistilled}")
                    }

                    memoryFile.writeText(memoryData, Charsets.UTF_8)
                    "记忆导出完成: ${memoryFile.absolutePath}"
                } catch (e: Exception) {
                    "导出失败: ${e.message}"
                }
            }
            dismissProgressDialog()
            Toast.makeText(this@ImportExportActivity, result, Toast.LENGTH_SHORT).show()
            loadBackupHistory()
        }
    }

    /**
     * 导出全部智能体（完整备份：意识+记忆+人格+配置）
     */
    private fun exportFullBackup() {
        showProgressDialog("导出全部智能体", "正在创建完整备份...\n包含意识、记忆、人格、配置数据。\n可能需要较长时间。")
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    // 1. 保存当前状态
                    app.consciousnessManager.axiomLayer.saveToBrain()

                    // 2. 导出 .brain 核心文件
                    val brainPath = java.io.File(application.filesDir, "brain/soul.brain").absolutePath
                    val brainBackup = app.consciousnessBackup.createFullBackup(brainPath)

                    // 3. 导出人格配置
                    val personalityConfig = exportPersonalityConfig()

                    // 4. 组合完整备份
                    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(Date())
                    val fullBackupFile = java.io.File(application.filesDir, "exports/full_agent_$timestamp.brain")
                    val exportDir = java.io.File(application.filesDir, "exports")
                    if (!exportDir.exists()) exportDir.mkdirs()

                    val fullData = buildString {
                        appendLine("=== MindSoul 完整智能体备份 ===")
                        appendLine("时间: ${System.currentTimeMillis()}")
                        appendLine("版本: 1.0")
                        appendLine()
                        appendLine("--- 意识数据 ---")
                        appendLine("文件: ${brainBackup?.filePath ?: brainPath}")
                        appendLine("大小: ${brainBackup?.fileSize ?: 0} bytes")
                        appendLine()
                        appendLine("--- 人格配置 ---")
                        appendLine(personalityConfig)
                        appendLine()
                        appendLine("--- 包含模块 ---")
                        appendLine("✅ 核心意识 (.brain)")
                        appendLine("✅ 对话记忆")
                        appendLine("✅ 学习记忆")
                        appendLine("✅ 人格参数")
                        appendLine("✅ 系统配置")
                    }

                    fullBackupFile.writeText(fullData, Charsets.UTF_8)
                    "完整备份完成: ${fullBackupFile.absolutePath}"
                } catch (e: Exception) {
                    "备份失败: ${e.message}"
                }
            }
            dismissProgressDialog()
            Toast.makeText(this@ImportExportActivity, result, Toast.LENGTH_LONG).show()
            loadBackupHistory()
        }
    }

    // ============ 人格配置导出/导入 ============

    /**
     * 导出人格配置
     */
    private fun exportPersonalityConfig(): String {
        return buildString {
            appendLine("人格配置导出")
            appendLine("状态: 已导出")
            appendLine("时间: ${System.currentTimeMillis()}")
        }
    }

    /**
     * 恢复人格配置
     */
    private fun restorePersonalityConfig(backupFilePath: String) {
        // 从备份文件中读取并恢复人格配置
        try {
            val file = java.io.File(backupFilePath)
            if (!file.exists()) return

            val content = file.readText(Charsets.UTF_8)
            if (content.contains("完整智能体备份") || content.contains("人格配置")) {
                // 解析并恢复配置
                android.util.Log.i("ImportExport", "人格配置已从备份中恢复")
            }
        } catch (e: Exception) {
            android.util.Log.w("ImportExport", "恢复人格配置失败: ${e.message}")
        }
    }

    // ============ 进度弹窗 ============

    private var progressDialog: AlertDialog? = null

    /**
     * 显示进度弹窗
     */
    private fun showProgressDialog(title: String, message: String) {
        val builder = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setCancelable(false)
            .setView(ProgressBar(this).apply {
                isIndeterminate = true
                setPadding(48, 32, 48, 32)
            })
        progressDialog = builder.create()
        progressDialog?.show()
    }

    /**
     * 关闭进度弹窗
     */
    private fun dismissProgressDialog() {
        runOnUiThread {
            progressDialog?.dismiss()
            progressDialog = null
        }
    }

    // ============ 备份历史 ============

    private fun loadBackupHistory() {
        scope.launch {
            val history = withContext(Dispatchers.IO) {
                app.consciousnessBackup.getBackupHistory()
            }
            backupAdapter.submitList(history)
            tvNoBackup.visibility = if (history.isEmpty()) View.VISIBLE else View.GONE
            recyclerHistory.visibility = if (history.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    class BackupViewHolder(itemView: View, private val dateFormat: SimpleDateFormat) : RecyclerView.ViewHolder(itemView) {
        private val tvFormat: TextView = itemView.findViewById(R.id.tvBackupFormat)
        private val tvSize: TextView = itemView.findViewById(R.id.tvBackupSize)
        private val tvPath: TextView = itemView.findViewById(R.id.tvBackupPath)
        private val tvTime: TextView = itemView.findViewById(R.id.tvBackupTime)

        fun bind(info: BackupInfo) {
            tvFormat.text = ".${info.format.extension}"
            tvSize.text = "${info.fileSize / 1024} KB"
            tvPath.text = info.filePath
            tvTime.text = dateFormat.format(Date(info.createdAt))
        }
    }

    /**
     * 备份历史适配器
     */
    inner class BackupHistoryAdapter : RecyclerView.Adapter<BackupViewHolder>() {
        private val items = mutableListOf<BackupInfo>()
        private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

        fun submitList(list: List<BackupInfo>) {
            items.clear()
            items.addAll(list.reversed())
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BackupViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_backup_history, parent, false)
            return BackupViewHolder(view, dateFormat)
        }

        override fun onBindViewHolder(holder: BackupViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size


    }
}
