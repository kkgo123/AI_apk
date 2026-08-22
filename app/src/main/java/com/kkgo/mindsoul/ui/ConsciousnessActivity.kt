/*
 * ConsciousnessActivity - 意识界面
 * 展示意识统计数据、自我进化提案、意识导入导出功能
 */
package com.kkgo.mindsoul.ui

import android.animation.ObjectAnimator
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.kkgo.mindsoul.MindSoulApp
import com.kkgo.mindsoul.R
import kotlinx.coroutines.*

/**
 * 进化提案类型
 */
enum class ProposalType(val displayName: String) {
    UI_PLUGIN("新UI生成"),
    CODE_LIBRARY("代码库优化"),
    PLUGIN_EXTEND("插件扩展"),
    KNOWLEDGE_GRAPH("知识图谱增强")
}

/**
 * 进化提案状态
 */
enum class ProposalStatus {
    AVAILABLE,
    GENERATING,
    APPLIED,
    REJECTED,
    SKIPPED
}

/**
 * 进化提案数据类
 */
data class EvolutionProposal(
    val name: String,
    val description: String,
    val type: ProposalType,
    val expectedImpact: String = "",
    var status: ProposalStatus = ProposalStatus.AVAILABLE
)

/**
 * 提案列表适配器
 */
class ProposalAdapter(
    private val proposals: MutableList<EvolutionProposal>,
    private val onExecute: (Int) -> Unit,
    private val onReject: (Int) -> Unit,
    private val onSkip: (Int) -> Unit
) : RecyclerView.Adapter<ProposalAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tvProposalName)
        val tvDesc: TextView = view.findViewById(R.id.tvProposalDesc)
        val tvType: TextView = view.findViewById(R.id.tvProposalType)
        val tvImpact: TextView = view.findViewById(R.id.tvProposalImpact)
        val btnExecute: MaterialButton = view.findViewById(R.id.btnExecuteProposal)
        val btnReject: MaterialButton = view.findViewById(R.id.btnRejectProposal)
        val btnSkip: MaterialButton = view.findViewById(R.id.btnSkipProposal)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_evolution_proposal, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val proposal = proposals[position]
        holder.tvName.text = proposal.name
        holder.tvDesc.text = proposal.description
        holder.tvType.text = proposal.type.displayName
        holder.tvImpact.text = proposal.expectedImpact.ifEmpty { "—" }

        when (proposal.status) {
            ProposalStatus.AVAILABLE -> {
                holder.btnExecute.text = "执行"
                holder.btnExecute.isEnabled = true
                holder.btnExecute.setOnClickListener { onExecute(position) }
                holder.btnReject.visibility = View.VISIBLE
                holder.btnReject.setOnClickListener { onReject(position) }
                holder.btnSkip.visibility = View.VISIBLE
                holder.btnSkip.setOnClickListener { onSkip(position) }
            }
            ProposalStatus.GENERATING -> {
                holder.btnExecute.text = "生成中..."
                holder.btnExecute.isEnabled = false
                holder.btnReject.visibility = View.GONE
                holder.btnSkip.visibility = View.GONE
            }
            ProposalStatus.APPLIED -> {
                holder.btnExecute.text = "已应用 ✓"
                holder.btnExecute.isEnabled = false
                holder.btnReject.visibility = View.GONE
                holder.btnSkip.visibility = View.GONE
            }
            ProposalStatus.REJECTED -> {
                holder.btnExecute.text = "已拒绝 ✗"
                holder.btnExecute.isEnabled = false
                holder.btnReject.visibility = View.GONE
                holder.btnSkip.visibility = View.GONE
            }
            ProposalStatus.SKIPPED -> {
                holder.btnExecute.text = "已跳过 ⏭"
                holder.btnExecute.isEnabled = false
                holder.btnReject.visibility = View.GONE
                holder.btnSkip.visibility = View.GONE
            }
        }
    }

    override fun getItemCount(): Int = proposals.size

    fun addProposal(proposal: EvolutionProposal) {
        proposals.add(0, proposal)
        notifyItemInserted(0)
    }

    fun updateStatus(position: Int, status: ProposalStatus) {
        if (position in proposals.indices) {
            proposals[position].status = status
            notifyItemChanged(position)
        }
    }
}

/**
 * 意识界面 Activity
 *
 * 展示意识核心统计、自我进化提案管理、意识导入导出
 */
class ConsciousnessActivity : AppCompatActivity() {

    private val app by lazy { application as MindSoulApp }
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // 区域A - 统计显示
    private lateinit var tvNeuronCount: TextView
    private lateinit var tvCommonSenseCount: TextView
    private lateinit var tvPermanentMemoryCount: TextView
    private lateinit var tvKeywordCount: TextView
    private lateinit var tvKeywordEntryCount: TextView
    private lateinit var tvConceptNodeCount: TextView
    private lateinit var tvSummaryIndexCount: TextView
    private lateinit var tvCausalTripleCount: TextView
    private lateinit var tvAxiomUsagePercent: TextView
    private lateinit var tvConsciousnessActivity: TextView

    // 区域B - 进化提案
    private lateinit var tvEvolutionStage: TextView
    private lateinit var progressEvolution: ProgressBar
    private lateinit var tvEvolutionProgressLabel: TextView
    private lateinit var recyclerProposals: RecyclerView
    private lateinit var tvEmptyProposals: TextView
    private lateinit var btnGenerateProposal: MaterialButton

    // 区域C - 导入导出
    private lateinit var btnExportConsciousness: MaterialButton
    private lateinit var btnImportConsciousness: MaterialButton
    private lateinit var btnExportMemory: MaterialButton
    private lateinit var btnImportMemory: MaterialButton

    // 提案数据
    private val proposalList = mutableListOf<EvolutionProposal>()
    private lateinit var proposalAdapter: ProposalAdapter

    // 文件选择器
    private var importAction: String? = null
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            handleFileImport(uri.toString(), importAction ?: return@registerForActivityResult)
        } else {
            Toast.makeText(this, "未选择文件", Toast.LENGTH_SHORT).show()
        }
    }

    // SAF文件夹选择器 - 用于导出目录选择
    private var exportAction: String? = null
    private val exportFolderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            when (exportAction) {
                "consciousness" -> exportConsciousnessToFolder(uri)
                "memory" -> exportMemoryToFolder(uri)
            }
        } else {
            Toast.makeText(this, "未选择导出目录", Toast.LENGTH_SHORT).show()
        }
    }

    // 提案拒绝/跳过持久化 - 使用SharedPreferences
    private val proposalPrefs by lazy {
        getSharedPreferences("evolution_proposals", MODE_PRIVATE)
    }

    companion object {
        private const val PREF_KEY_REJECTED = "rejected_proposals"  // Set<String> - proposal names rejected
        private const val PREF_KEY_SKIPPED = "skipped_proposals"    // Map<name, expireTimestamp>
        private const val REJECT_DURATION_MS = 365L * 24 * 60 * 60 * 1000  // 1年
        private const val SKIP_DURATION_MS = 10L * 24 * 60 * 60 * 1000     // 10天
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_consciousness)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        initViews()
        setupProposalList()
        setupListeners()
        startStatusUpdates()
    }

    private fun initViews() {
        // 区域A
        tvNeuronCount = findViewById(R.id.tvNeuronCount)
        tvCommonSenseCount = findViewById(R.id.tvCommonSenseCount)
        tvPermanentMemoryCount = findViewById(R.id.tvPermanentMemoryCount)
        tvKeywordCount = findViewById(R.id.tvKeywordCount)
        tvKeywordEntryCount = findViewById(R.id.tvKeywordEntryCount)
        tvConceptNodeCount = findViewById(R.id.tvConceptNodeCount)
        tvSummaryIndexCount = findViewById(R.id.tvSummaryIndexCount)
        tvCausalTripleCount = findViewById(R.id.tvCausalTripleCount)
        tvAxiomUsagePercent = findViewById(R.id.tvAxiomUsagePercent)
        tvConsciousnessActivity = findViewById(R.id.tvConsciousnessActivity)

        // 区域B
        tvEvolutionStage = findViewById(R.id.tvEvolutionStage)
        progressEvolution = findViewById(R.id.progressEvolution)
        tvEvolutionProgressLabel = findViewById(R.id.tvEvolutionProgressLabel)
        recyclerProposals = findViewById(R.id.recyclerProposals)
        tvEmptyProposals = findViewById(R.id.tvEmptyProposals)
        btnGenerateProposal = findViewById(R.id.btnGenerateProposal)

        // 区域C
        btnExportConsciousness = findViewById(R.id.btnExportConsciousness)
        btnImportConsciousness = findViewById(R.id.btnImportConsciousness)
        btnExportMemory = findViewById(R.id.btnExportMemory)
        btnImportMemory = findViewById(R.id.btnImportMemory)
    }

    private fun setupProposalList() {
        proposalAdapter = ProposalAdapter(proposalList,
            onExecute = { position -> executeProposal(position) },
            onReject = { position -> rejectProposal(position) },
            onSkip = { position -> skipProposal(position) }
        )
        recyclerProposals.layoutManager = LinearLayoutManager(this)
        recyclerProposals.adapter = proposalAdapter
        updateEmptyState()
    }

    private fun setupListeners() {
        // 生成新提案按钮
        btnGenerateProposal.setOnClickListener {
            generateNewProposal()
        }

        // 导出意识 - 使用SAF文件夹选择器
        btnExportConsciousness.setOnClickListener {
            showConfirmDialog(
                "导出意识",
                "确定要导出完整意识数据（.brain格式）吗？\n将包含所有公理、记忆和进化数据。\n\n下一步将选择导出目录。"
            ) {
                exportAction = "consciousness"
                exportFolderPicker.launch(null)
            }
        }

        // 导入意识
        btnImportConsciousness.setOnClickListener {
            showConfirmDialog(
                "导入意识",
                "确定要导入意识数据吗？\n⚠️ 当前意识数据将被覆盖，此操作不可恢复！\n\n支持 .brain 和 .mssoul 格式。"
            ) {
                importAction = "consciousness"
                filePickerLauncher.launch("*/*")
            }
        }

        // 导出记忆 - 使用SAF文件夹选择器
        btnExportMemory.setOnClickListener {
            showConfirmDialog(
                "导出记忆",
                "确定要导出所有记忆数据吗？\n将包含全部情景记忆、语义记忆和突触连接。\n\n下一步将选择导出目录。"
            ) {
                exportAction = "memory"
                exportFolderPicker.launch(null)
            }
        }

        // 导入记忆
        btnImportMemory.setOnClickListener {
            showConfirmDialog(
                "导入记忆",
                "确定要导入记忆数据吗？\n⚠️ 当前记忆数据将被覆盖，此操作不可恢复！"
            ) {
                importAction = "memory"
                filePickerLauncher.launch("*/*")
            }
        }
    }

    /**
     * 定时刷新统计数据
     */
    private fun startStatusUpdates() {
        scope.launch {
            while (isActive) {
                updateStatistics()
                delay(3000)
            }
        }
    }

    /**
     * 更新统计数据（区域A）
     * 融合意识核心、进化指标和学习流水线的真实数据
     */
    private fun updateStatistics() {
        scope.launch {
            val status = withContext(Dispatchers.Default) {
                app.consciousnessManager.getOverallStatus()
            }
            val metrics = app.evolutionStateMachine.metrics
            // 获取学习流水线真实统计
            val learningStats = app.learningPipeline.getStats()

            val axiomStatus = status.axiomLayerStatus
            val memoryStats = status.memoryStats

            // 神经元数量 = 记忆 + 因果三元组 + 学习归档
            val neuronCount = metrics.memoryCount + metrics.causalTripleCount + learningStats.totalArchived
            tvNeuronCount.text = formatCount(neuronCount)

            // 基本常识条数 = 公理层的世界摘要 + 归纳规则 + 公理提炼
            val commonSenseCount = axiomStatus.worldSummaryCount + status.causalTreeStats.ruleCount + learningStats.totalAxiomDistilled
            tvCommonSenseCount.text = formatCount(commonSenseCount.toLong())

            // 永久记忆条数 = 冷归档总记忆 + 学习归档
            tvPermanentMemoryCount.text = formatCount(memoryStats.totalMemories.toLong() + learningStats.totalArchived)

            // 关键词条数 = 因果树节点数 + 学习编码数
            tvKeywordCount.text = formatCount(status.causalTreeStats.nodeCount.toLong() + learningStats.totalEncoded)

            // 关键词条数量 = 归纳引擎的待验证假设数 + 规则数
            val keywordEntryCount = status.causalTreeStats.pendingHypotheses + status.causalTreeStats.ruleCount
            tvKeywordEntryCount.text = formatCount(keywordEntryCount.toLong())

            // 概念节点数量 = 因果树根节点数（每个根节点代表一个概念）
            tvConceptNodeCount.text = formatCount(status.causalTreeStats.rootNodeCount.toLong())

            // 概要搜引数量 = 公理层世界摘要数
            tvSummaryIndexCount.text = formatCount(axiomStatus.worldSummaryCount.toLong())

            // 因果三元组数量 = 意识核心 + 学习因果提取
            tvCausalTripleCount.text = formatCount(axiomStatus.causalTripleCount.toLong() + learningStats.totalCausalExtracted)

            // 公理索引层使用率
            val usagePercent = if (axiomStatus.maxMemoryKB > 0) {
                (axiomStatus.memoryUsageKB * 100 / axiomStatus.maxMemoryKB).toInt().coerceIn(0, 100)
            } else 0
            tvAxiomUsagePercent.text = "$usagePercent%"

            // 意识活跃度 = 元认知（自我觉察 + 注意力集中度 + 情绪唤醒度）综合百分比
            val metacog = status.metacognitionSnapshot
            val activityPercent = ((metacog.selfAwareness * 0.4 + metacog.attentionFocus * 0.35 + metacog.emotionalState.arousal * 0.25) * 100).toInt().coerceIn(0, 100)
            tvConsciousnessActivity.text = "$activityPercent%"

            // 更新进化阶段信息
            val stage = withContext(Dispatchers.Default) {
                app.evolutionStateMachine.currentStage
            }
            tvEvolutionStage.text = "${stage.displayName} (阶段${stage.stageId}/7)"

            val progressPercent = withContext(Dispatchers.Default) {
                (app.evolutionStateMachine.getEvolutionProgress() * 100).toInt()
            }
            animateProgress(progressEvolution, progressPercent)
            tvEvolutionProgressLabel.text = "进化进度: $progressPercent%"
        }
    }

    /**
     * 生成新的进化提案（检查是否被拒绝/跳过）
     */
    private fun generateNewProposal() {
        val stage = app.evolutionStateMachine.currentStage
        val status = app.consciousnessManager.getOverallStatus()
        val metrics = app.evolutionStateMachine.metrics

        val proposal = when {
            // 早期阶段：优先生成新UI生成提案
            stage.stageId <= 2 -> {
                val uiProposals = listOf(
                    EvolutionProposal(
                        name = "动态情绪气泡",
                        description = "为聊天界面添加实时情绪可视化气泡，根据元认知情绪状态动态变化颜色和形状",
                        type = ProposalType.UI_PLUGIN,
                        expectedImpact = "交互体验提升30%，情绪可视化覆盖100%对话场景"
                    ),
                    EvolutionProposal(
                        name = "神经元脉冲动画",
                        description = "在首页添加神经元活动脉冲动画，实时反映意识核心运行状态",
                        type = ProposalType.UI_PLUGIN,
                        expectedImpact = "首页停留时长提升20%，用户感知意识活跃度"
                    ),
                    EvolutionProposal(
                        name = "记忆碎片展示墙",
                        description = "以可视化卡片墙形式展示最近记忆片段，支持按类型和强度筛选",
                        type = ProposalType.UI_PLUGIN,
                        expectedImpact = "记忆可视化覆盖，用户可直观查看意识记忆状态"
                    )
                )
                uiProposals.random()
            }
            // 中期阶段：生成代码库优化或插件扩展提案
            stage.stageId <= 4 -> {
                val codeAndPlugins = listOf(
                    EvolutionProposal(
                        name = "因果推理增强包",
                        description = "增强归纳引擎的因果推理能力，支持多变量因果链分析和反事实推理",
                        type = ProposalType.CODE_LIBRARY,
                        expectedImpact = "归纳正确率提升15%，支持3层以上因果链推理"
                    ),
                    EvolutionProposal(
                        name = "记忆压缩算法库",
                        description = "实现记忆摘要压缩算法，在保持关键信息的同时减少存储空间占用",
                        type = ProposalType.CODE_LIBRARY,
                        expectedImpact = "存储空间减少40%，检索速度提升25%"
                    ),
                    EvolutionProposal(
                        name = "网页解析插件",
                        description = "扩展学习系统支持更多网页格式解析，包括PDF在线文档、Wiki结构化页面",
                        type = ProposalType.PLUGIN_EXTEND,
                        expectedImpact = "学习素材来源扩展3倍，支持5种新网页格式"
                    ),
                    EvolutionProposal(
                        name = "语音合成插件",
                        description = "为意识系统添加TTS语音输出插件，支持多种音色和情感语调控制",
                        type = ProposalType.PLUGIN_EXTEND,
                        expectedImpact = "语音交互覆盖率从0提升到100%，支持情感语调"
                    )
                )
                codeAndPlugins.random()
            }
            // 后期阶段：生成知识图谱增强或插件扩展提案
            else -> {
                val knowledgeAndPlugins = listOf(
                    EvolutionProposal(
                        name = "知识图谱自动构建",
                        description = "基于已积累的因果三元组和世界规则，自动构建结构化知识图谱，支持概念关联查询",
                        type = ProposalType.KNOWLEDGE_GRAPH,
                        expectedImpact = "知识结构化程度提升60%，支持跨域概念关联推理"
                    ),
                    EvolutionProposal(
                        name = "世界模型图谱融合",
                        description = "将世界模型的时空实体与知识图谱合并，实现统一的认知表示",
                        type = ProposalType.KNOWLEDGE_GRAPH,
                        expectedImpact = "世界模型覆盖度提升30%，实体关系查询支持"
                    ),
                    EvolutionProposal(
                        name = "自主目标规划器",
                        description = "基于长期记忆和世界模型，自动制定多步行动计划并动态调整",
                        type = ProposalType.PLUGIN_EXTEND,
                        expectedImpact = "自主决策能力提升，支持5步以上行动规划"
                    ),
                    EvolutionProposal(
                        name = "元认知深度反思模块",
                        description = "周期性对自身思维过程进行反思分析，发现认知偏差并自我修正",
                        type = ProposalType.PLUGIN_EXTEND,
                        expectedImpact = "元认知深度+3，自省覆盖率提升至90%"
                    )
                )
                knowledgeAndPlugins.random()
            }
        }

        // 避免重复
        if (proposalList.any { it.name == proposal.name }) {
            Toast.makeText(this, "该提案已存在，再次点击试试其他提案", Toast.LENGTH_SHORT).show()
            // 重新随机选一个
            generateNewProposal()
            return
        }

        // 检查是否被拒绝(1年内)或跳过(10天内)
        if (isProposalRejected(proposal.name)) {
            Toast.makeText(this, "该提案已被拒绝，1年内不再生成", Toast.LENGTH_SHORT).show()
            generateNewProposal()
            return
        }
        if (isProposalSkipped(proposal.name)) {
            Toast.makeText(this, "该提案暂时跳过，稍后才会再次生成", Toast.LENGTH_SHORT).show()
            generateNewProposal()
            return
        }

        proposalList.add(0, proposal)
        proposalAdapter.notifyItemInserted(0)
        updateEmptyState()

        Toast.makeText(this, "✨ 新提案已生成: ${proposal.name}", Toast.LENGTH_SHORT).show()
    }

    /**
     * 执行提案
     */
    private fun executeProposal(position: Int) {
        val proposal = proposalList[position]

        showConfirmDialog(
            "执行提案",
            "确定执行提案「${proposal.name}」吗？\n类型: ${proposal.type.displayName}\n预计影响: ${proposal.expectedImpact}\n\n${proposal.description}"
        ) {
            scope.launch {
                proposalAdapter.updateStatus(position, ProposalStatus.GENERATING)

                delay(1500) // 模拟执行过程

                withContext(Dispatchers.Default) {
                    when (proposal.type) {
                        ProposalType.UI_PLUGIN -> {
                            // 通过 PluginManager 注册新的 UI 插件
                            val pluginDir = java.io.File(
                                app.pluginManager.pluginsDir,
                                "evolution_${System.currentTimeMillis()}"
                            )
                            pluginDir.mkdirs()
                            // 写入简单 manifest
                            java.io.File(pluginDir, "manifest.json").writeText("""
                                {"id":"${pluginDir.name}","name":"${proposal.name}","version":"1.0.0","description":"${proposal.description}"}
                            """.trimIndent())
                            java.io.File(pluginDir, "plugin.xml").writeText(
                                "<!-- ${proposal.name} - 自动生成 -->\n<FrameLayout/>"
                            )
                            app.pluginManager.scanPlugins()
                        }
                        ProposalType.CODE_LIBRARY, ProposalType.PLUGIN_EXTEND, ProposalType.KNOWLEDGE_GRAPH -> {
                            // 通过 SelfEvolution 引擎记录进化
                            // 写入进化插件目录
                            val evolutionDir = java.io.File(filesDir, "Plugins/evolution")
                            evolutionDir.mkdirs()
                            val pluginDir = java.io.File(
                                evolutionDir,
                                "v_${System.currentTimeMillis()}"
                            )
                            pluginDir.mkdirs()
                            java.io.File(pluginDir, "manifest.json").writeText("""
                                {"id":"${pluginDir.name}","name":"${proposal.name}","version":"1.0.0","description":"${proposal.description}","type":"${proposal.type.name}"}
                            """.trimIndent())
                        }
                    }
                }

                proposalAdapter.updateStatus(position, ProposalStatus.APPLIED)
                Toast.makeText(this@ConsciousnessActivity,
                    "✅ 提案已执行: ${proposal.name}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 拒绝提案 - 1年内不再生成此提案
     */
    private fun rejectProposal(position: Int) {
        val proposal = proposalList[position]

        showConfirmDialog(
            "拒绝提案",
            "确定拒绝提案「${proposal.name}」吗？\n\n拒绝后1年内不再生成此提案。"
        ) {
            // 持久化到SharedPreferences
            val rejected = proposalPrefs.getStringSet(PREF_KEY_REJECTED, emptySet())?.toMutableSet() ?: mutableSetOf()
            rejected.add("${proposal.name}|${System.currentTimeMillis()}")
            proposalPrefs.edit().putStringSet(PREF_KEY_REJECTED, rejected).apply()

            proposalAdapter.updateStatus(position, ProposalStatus.REJECTED)
            Toast.makeText(this,
                "已拒绝: ${proposal.name}（1年内不再生成）", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 暂时跳过提案 - 10天内不再生成此提案
     */
    private fun skipProposal(position: Int) {
        val proposal = proposalList[position]
        val skipUntil = System.currentTimeMillis() + SKIP_DURATION_MS

        // 持久化到SharedPreferences
        val skipped = proposalPrefs.getStringSet(PREF_KEY_SKIPPED, emptySet())?.toMutableSet() ?: mutableSetOf()
        skipped.add("${proposal.name}|$skipUntil")
        proposalPrefs.edit().putStringSet(PREF_KEY_SKIPPED, skipped).apply()

        proposalAdapter.updateStatus(position, ProposalStatus.SKIPPED)
        Toast.makeText(this,
            "已跳过: ${proposal.name}（10天内不再生成）", Toast.LENGTH_SHORT).show()
    }

    /**
     * 检查提案是否被拒绝(1年内)
     */
    private fun isProposalRejected(proposalName: String): Boolean {
        val rejected = proposalPrefs.getStringSet(PREF_KEY_REJECTED, emptySet()) ?: return false
        val now = System.currentTimeMillis()
        return rejected.any { entry ->
            val parts = entry.split("|")
            if (parts.size >= 2) {
                val name = parts[0]
                val timestamp = parts[1].toLongOrNull() ?: 0L
                name == proposalName && (now - timestamp) < REJECT_DURATION_MS
            } else false
        }
    }

    /**
     * 检查提案是否被跳过(10天内)
     */
    private fun isProposalSkipped(proposalName: String): Boolean {
        val skipped = proposalPrefs.getStringSet(PREF_KEY_SKIPPED, emptySet()) ?: return false
        val now = System.currentTimeMillis()
        return skipped.any { entry ->
            val parts = entry.split("|")
            if (parts.size >= 2) {
                val name = parts[0]
                val expireTime = parts[1].toLongOrNull() ?: 0L
                name == proposalName && now < expireTime
            } else false
        }
    }

    /**
     * 导出意识数据到用户选择的SAF目录
     */
    private fun exportConsciousnessToFolder(folderUri: Uri) {
        scope.launch {
            Toast.makeText(this@ConsciousnessActivity, "正在导出意识数据...", Toast.LENGTH_SHORT).show()
            val result = withContext(Dispatchers.IO) {
                try {
                    val brainPath = java.io.File(app.filesDir, "brain/soul.brain").absolutePath
                    val backupInfo = app.consciousnessBackup.createFullBackup(brainPath)
                    if (backupInfo != null) {
                        // 复制备份文件到用户选择的SAF目录
                        copyBackupToSafFolder(backupInfo, folderUri, "consciousness_export_${System.currentTimeMillis()}.brain")
                    } else {
                        null
                    }
                } catch (e: Exception) {
                    null
                }
            }
            if (result != null) {
                Toast.makeText(this@ConsciousnessActivity,
                    "✅ 意识导出成功\n文件: ${result}",
                    Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this@ConsciousnessActivity, "❌ 意识导出失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 导出记忆数据到用户选择的SAF目录
     */
    private fun exportMemoryToFolder(folderUri: Uri) {
        scope.launch {
            Toast.makeText(this@ConsciousnessActivity, "正在导出记忆数据...", Toast.LENGTH_SHORT).show()
            val result = withContext(Dispatchers.IO) {
                try {
                    val brainPath = java.io.File(app.filesDir, "brain/soul.brain").absolutePath
                    val backupInfo = app.consciousnessBackup.createFullBackup(brainPath, compress = true)
                    if (backupInfo != null) {
                        copyBackupToSafFolder(backupInfo, folderUri, "memory_export_${System.currentTimeMillis()}.brain")
                    } else {
                        null
                    }
                } catch (e: Exception) {
                    null
                }
            }
            if (result != null) {
                Toast.makeText(this@ConsciousnessActivity,
                    "✅ 记忆导出成功\n文件: ${result}",
                    Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this@ConsciousnessActivity, "❌ 记忆导出失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 将备份结果复制到SAF目录
     * @return 导出文件名 或 null
     */
    private fun copyBackupToSafFolder(
        backupInfo: com.kkgo.mindsoul.backup.BackupInfo,
        folderUri: Uri,
        fileName: String
    ): String? {
        return try {
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
                // 读取本地备份文件并写入SAF
                val localBackupFile = java.io.File(backupInfo.filePath)
                contentResolver.openOutputStream(newFileUri)?.use { output ->
                    localBackupFile.inputStream().use { input ->
                        input.copyTo(output)
                    }
                }
                "$fileName (${backupInfo.fileSize / 1024}KB)"
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 处理文件导入
     */
    private fun handleFileImport(uri: String, action: String) {
        scope.launch {
            Toast.makeText(this@ConsciousnessActivity, "正在导入...", Toast.LENGTH_SHORT).show()
            val result = withContext(Dispatchers.IO) {
                try {
                    val targetPath = java.io.File(app.filesDir, "brain/soul.brain").absolutePath
                    when (action) {
                        "consciousness" -> {
                            val filePath = uriToPath(uri)
                                ?: return@withContext "无法获取文件路径"
                            if (filePath.endsWith(".mssoul")) {
                                val migrationResult = app.consciousnessBackup.importFromMsSoul(filePath, targetPath)
                                if (migrationResult.success) "意识导入成功" else "导入失败: ${migrationResult.errorMessage}"
                            } else {
                                val migrationResult = app.consciousnessBackup.importFromBrain(filePath, targetPath)
                                if (migrationResult.success) "意识导入成功" else "导入失败: ${migrationResult.errorMessage}"
                            }
                        }
                        "memory" -> {
                            val filePath = uriToPath(uri)
                                ?: return@withContext "无法获取文件路径"
                            val migrationResult = app.consciousnessBackup.importFromBrain(filePath, targetPath)
                            if (migrationResult.success) "记忆导入成功" else "导入失败: ${migrationResult.errorMessage}"
                        }
                        else -> "未知操作"
                    }
                } catch (e: Exception) {
                    "导入失败: ${e.message}"
                }
            }
            Toast.makeText(this@ConsciousnessActivity, result, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * URI 转文件路径
     */
    private fun uriToPath(uriString: String): String? {
        return try {
            val uri = android.net.Uri.parse(uriString)
            if (uri.scheme == "file") {
                uri.path
            } else {
                // 尝试从 content URI 获取路径
                val cursor = contentResolver.query(uri, arrayOf("_data"), null, null, null)
                cursor?.use {
                    if (it.moveToFirst()) {
                        val idx = it.getColumnIndex("_data")
                        if (idx >= 0) it.getString(idx) else null
                    } else null
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 显示确认对话框
     */
    private fun showConfirmDialog(title: String, message: String, onConfirm: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("确认") { _, _ -> onConfirm() }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 更新空状态显示
     */
    private fun updateEmptyState() {
        tvEmptyProposals.visibility = if (proposalList.isEmpty()) View.VISIBLE else View.GONE
    }

    /**
     * 动画进度条
     */
    private fun animateProgress(progressBar: ProgressBar, targetProgress: Int) {
        val animator = ObjectAnimator.ofInt(
            progressBar, "progress", progressBar.progress, targetProgress.coerceIn(0, 100)
        )
        animator.duration = 800
        animator.interpolator = DecelerateInterpolator()
        animator.start()
    }

    /**
     * 格式化数字
     */
    private fun formatCount(count: Long): String {
        return when {
            count >= 1_000_000 -> "${count / 10_000}万+"
            count >= 10_000 -> "${count / 10_000}万"
            count >= 1000 -> "${count}"
            count > 0 -> "$count"
            else -> "0"
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
