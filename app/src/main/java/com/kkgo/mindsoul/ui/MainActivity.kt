/*
 * ============================================================
 * MainActivity - 主界面（底部导航入口）
 * ============================================================
 *
 * 使用底部导航栏 (BottomNavigationView) 作为主要导航方式：
 *   - 首页: 意识状态概览 + GUID + 快捷操作
 *   - 聊天: 跳转ChatActivity
 *   - 化身: 跳转AvatarEditActivity
 *   - 状态: 跳转StatusDashboardActivity
 *   - 设置: 跳转SettingsActivity
 *
 * 首页直接在本Activity内展示，其他页面跳转到独立Activity。
 * ============================================================
 */
package com.kkgo.mindsoul.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.kkgo.mindsoul.MindSoulApp
import com.kkgo.mindsoul.R
import com.kkgo.mindsoul.consciousness.ConsciousnessService
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {

    private val app by lazy { application as MindSoulApp }
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // SAF文件夹选择器 - 用于备份目录选择
    private val backupFolderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            performBackupToFolder(uri)
        } else {
            Toast.makeText(this, "未选择备份目录", Toast.LENGTH_SHORT).show()
        }
    }

    // 首页控件
    private lateinit var tvStatus: TextView
    private lateinit var tvEvolution: TextView
    private lateinit var tvGuid: TextView
    private lateinit var btnIntrospect: com.google.android.material.button.MaterialButton
    private lateinit var btnAssociate: com.google.android.material.button.MaterialButton
    private lateinit var btnBackup: com.google.android.material.button.MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupBottomNavigation()
        startConsciousnessService()
        updateHomePage()
        startPeriodicRefresh()
    }

    private fun initViews() {
        tvStatus = findViewById(R.id.tvStatus)
        tvEvolution = findViewById(R.id.tvEvolution)
        tvGuid = findViewById(R.id.tvGuid)
        btnIntrospect = findViewById(R.id.btnIntrospect)
        btnAssociate = findViewById(R.id.btnAssociate)
        btnBackup = findViewById(R.id.btnBackup)

        // 意识面板 & 状态仪表盘入口
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnConsciousness).setOnClickListener {
            startActivity(Intent(this, ConsciousnessActivity::class.java))
        }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnStatus).setOnClickListener {
            startActivity(Intent(this, StatusDashboardActivity::class.java))
        }

        // 🎭 角色扮演入口
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnRolePlay).setOnClickListener {
            startActivity(Intent(this, RolePlayActivity::class.java))
        }

        // 快捷操作
        btnIntrospect.setOnClickListener { performIntrospection() }
        btnAssociate.setOnClickListener { performAssociation() }
        btnBackup.setOnClickListener { performBackup() }
    }

    private fun setupBottomNavigation() {
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNav)

        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    // 已在首页，无需操作
                    true
                }
                R.id.nav_chat -> {
                    startActivity(Intent(this, ChatActivity::class.java))
                    true
                }
                R.id.nav_learning -> {
                    startActivity(Intent(this, LearningActivity::class.java))
                    true
                }
                R.id.nav_evolution -> {
                    startActivity(Intent(this, EvolutionPanelActivity::class.java))
                    true
                }
                R.id.nav_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    true
                }
                else -> false
            }
        }
    }

    // ============ 首页内容更新 ============

    private fun updateHomePage() {
        scope.launch {
            try {
                updateStatusCard()
                updateEvolutionCard()
                updateGuidCard()
            } catch (e: Exception) {
                tvStatus.text = "状态更新异常: ${e.message}"
            }
        }
    }

    private suspend fun updateStatusCard() {
        val status = withContext(Dispatchers.Default) {
            app.consciousnessManager.getOverallStatus()
        }

        // 提取关键指标用于首页醒目展示
        val neuronTotal = status.memoryStats.totalMemories + status.memoryStats.totalSynapses
        val synapseTotal = status.memoryStats.totalSynapses
        val metacogLevel = status.metacognitionSnapshot.selfAwareness

        tvStatus.text = buildString {
            appendLine("┌─ 核心指标 ────────────")
            appendLine("│ 🧠 神经元: $neuronTotal  |  🔗 突触: $synapseTotal")
            appendLine("│ 🧭 元认知: ${String.format("%.1f%%", metacogLevel * 100)}")
            appendLine("├─ 公理层 ──────────────")
            appendLine("│ 因果三元组: ${status.axiomLayerStatus.causalTripleCount}")
            appendLine("│ 逻辑公理: ${status.axiomLayerStatus.axiomCount}")
            appendLine("├─ 归纳引擎 ────────────")
            appendLine("│ 因果树节点: ${status.causalTreeStats.nodeCount}")
            appendLine("│ 世界规则: ${status.causalTreeStats.ruleCount}")
            appendLine("├─ 记忆系统 ────────────")
            appendLine("│ 总记忆: ${status.memoryStats.totalMemories}")
            appendLine("│ 突触连接: ${status.memoryStats.totalSynapses}")
            appendLine("├─ 世界模型 ────────────")
            appendLine("│ 物理规则: ${status.worldModelStatus.physicalRuleCount}")
            appendLine("│ 空间实体: ${status.worldModelStatus.spatialEntityCount}")
            appendLine("└─ 元认知 ──────────────")
            appendLine("   自我觉察: ${String.format("%.1f%%", status.metacognitionSnapshot.selfAwareness * 100)}")
            appendLine()
            appendLine("═ ${app.switchCenter.getStatusSummary()}")
        }
    }

    private suspend fun updateEvolutionCard() {
        val stage = withContext(Dispatchers.Default) {
            app.evolutionStateMachine.currentStage
        }
        val progress = withContext(Dispatchers.Default) {
            app.evolutionStateMachine.getEvolutionProgress()
        }
        val desires = withContext(Dispatchers.Default) {
            app.desireEngine.getActiveDesires()
        }

        tvEvolution.text = buildString {
            appendLine("当前阶段: ${stage.displayName} (阶段${stage.stageId}/7)")
            appendLine("进化进度: ${"█".repeat((progress * 10).toInt())}${"░".repeat(10 - (progress * 10).toInt())} ${String.format("%.0f%%", progress * 100)}")
            appendLine("下一阶段: ${stage.evolutionConditions}")
            appendLine()
            if (desires.isNotEmpty()) {
                appendLine("活跃欲望:")
                desires.take(3).forEach { desire ->
                    val bar = "█".repeat((desire.intensity * 10).toInt()) +
                            "░".repeat(10 - (desire.intensity * 10).toInt())
                    appendLine("  ${desire.type.displayName}: [$bar]")
                }
            }
        }
    }

    private suspend fun updateGuidCard() {
        val identity = withContext(Dispatchers.Default) {
            app.consciousnessManager.metacognition.getIdentity()
        }

        tvGuid.text = buildString {
            appendLine("UUID: ${identity.uuid}")
            appendLine("觉察水平: ${String.format("%.1f%%", identity.consciousnessLevel * 100)}")
            if (identity.selfName.isNotEmpty()) {
                appendLine("自我命名: ${identity.selfName}")
            }
            appendLine()
            // 孢子身份
            val sporeId = app.sporeClusterManager.getSporeProtocol().getMyIdentity()
            if (sporeId != null) {
                appendLine("孢子: ${sporeId.displayName}")
                appendLine("孢子ID: ${sporeId.sporeId.take(8)}...")
            }
        }
    }

    // ============ 快捷操作 ============

    private fun performIntrospection() {
        scope.launch {
            val result = withContext(Dispatchers.Default) {
                app.consciousnessManager.metacognition.performIntrospection()
            }
            tvStatus.text = buildString {
                appendLine("=== 递归自省结果 ===")
                appendLine(result.result)
                appendLine()
                appendLine("置信度: ${String.format("%.1f%%", result.confidence * 100)}")
            }
        }
    }

    private fun performAssociation() {
        val association = app.consciousnessManager.metacognition.freeAssociate()
        if (association != null) {
            tvStatus.text = buildString {
                appendLine("=== 潜意识联想 ===")
                appendLine("'$association.trigger' → '$association.target'")
                appendLine("类型: ${association.type}")
                appendLine("强度: ${String.format("%.2f", association.associationStrength)}")
            }
        } else {
            tvStatus.text = "联想池暂空，等待更多经验积累..."
        }
    }

    private fun performBackup() {
        // 弹出SAF文件夹选择器，让用户选择备份存储位置
        backupFolderPicker.launch(null)
    }

    /**
     * 执行备份到用户选择的SAF目录
     * 先通过brainEngine创建备份到本地，然后将备份文件复制到用户选择的SAF目录
     */
    private fun performBackupToFolder(folderUri: Uri) {
        scope.launch {
            Toast.makeText(this@MainActivity, "正在备份...", Toast.LENGTH_SHORT).show()
            val result = withContext(Dispatchers.IO) {
                try {
                    // 1. 先执行本地备份
                    val localBackupPath = app.brainEngine.createBackup()
                    val localBackupFile = java.io.File(localBackupPath.toString())

                    if (!localBackupFile.exists()) {
                        return@withContext "备份失败: 本地备份文件不存在"
                    }

                    // 2. 将备份文件复制到用户选择的SAF目录
                    val fileName = "mindSoul_backup_${System.currentTimeMillis()}.brain"
                    contentResolver.takePersistableUriPermission(
                        folderUri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )

                    val docUri = DocumentsContract.buildDocumentUriUsingTree(
                        folderUri, DocumentsContract.getTreeDocumentId(folderUri)
                    )
                    val newFileUri = DocumentsContract.createDocument(
                        contentResolver, docUri, "application/octet-stream", fileName
                    )

                    if (newFileUri != null) {
                        contentResolver.openOutputStream(newFileUri)?.use { output ->
                            localBackupFile.inputStream().use { input ->
                                input.copyTo(output)
                            }
                        }
                        "备份完成: $fileName (${localBackupFile.length() / 1024}KB)"
                    } else {
                        "备份失败: 无法在目标目录创建文件"
                    }
                } catch (e: Exception) {
                    "备份失败: ${e.message}"
                }
            }
            Toast.makeText(this@MainActivity, "备份完成", Toast.LENGTH_SHORT).show()
            tvStatus.text = "脑文件备份完成\n$result"
        }
    }

    // ============ 生命周期 ============

    private fun startConsciousnessService() {
        val intent = Intent(this, ConsciousnessService::class.java)
        startForegroundService(intent)
    }

    private fun startPeriodicRefresh() {
        scope.launch {
            while (isActive) {
                delay(3000)
                updateHomePage()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateHomePage()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
