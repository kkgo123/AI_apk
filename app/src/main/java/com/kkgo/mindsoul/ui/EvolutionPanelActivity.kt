/*
 * ============================================================
 * EvolutionPanelActivity - 自我进化控制面板
 * ============================================================
 *
 * 展示AI自我进化的全貌：
 *
 * 1. 进化统计面板
 *    - 当前版本号
 *    - 总进化次数
 *    - 最近进化时间
 *    - 已加载插件数
 *
 * 2. 进化历史列表
 *    - 按时间倒序展示所有进化记录
 *    - 每条显示：时间、操作类型、目标、描述、状态
 *    - 失败记录标红显示
 *
 * 3. 已加载插件列表
 *    - 展示当前活跃的进化插件
 *    - 显示：名称、版本、作者、创建时间
 *    - 点击可查看插件详情（XML/脚本内容）
 *
 * 4. 操作区
 *    - 🚀 手动触发进化（让AI生成新功能）
 *    - ↩️ 回滚到上一版本
 *    - 🗑️ 清空进化日志
 *
 * 权限要求：
 *    - L3-A以上才能访问本面板
 *    - 回滚和触发进化需要L3-A权限
 *    - 修改核心代码需要L3-B权限
 * ============================================================
 */
package com.kkgo.mindsoul.ui

import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.kkgo.mindsoul.R
import android.view.LayoutInflater
import com.kkgo.mindsoul.evolution.EvolutionAction
import com.kkgo.mindsoul.evolution.EvolutionLogEntry
import com.kkgo.mindsoul.evolution.EvolutionPluginManifest
import com.kkgo.mindsoul.evolution.EvolutionProposal
import com.kkgo.mindsoul.evolution.SelfEvolution
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

class EvolutionPanelActivity : AppCompatActivity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var evolution: SelfEvolution

    // 统计区视图
    private lateinit var tvVersion: TextView
    private lateinit var tvTotalEvolutions: TextView
    private lateinit var tvLastEvolution: TextView
    private lateinit var tvLoadedPlugins: TextView

    // 列表区
    private lateinit var llProposals: LinearLayout
    private lateinit var tvEmptyProposals: TextView
    private lateinit var llEvolutionHistory: LinearLayout
    private lateinit var llLoadedPlugins: LinearLayout
    private lateinit var tvEmptyHistory: TextView
    private lateinit var tvEmptyPlugins: TextView

    // 操作按钮
    private lateinit var btnTriggerEvolution: MaterialButton
    private lateinit var btnRollback: MaterialButton
    private lateinit var btnClearLogs: MaterialButton

    private val dateFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_evolution_panel)

        evolution = SelfEvolution(this)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        initViews()
        setupActions()
        observeEvolutionState()
        refreshStats()
    }

    private fun initViews() {
        // 统计区
        tvVersion = findViewById(R.id.tvEvolutionVersion)
        tvTotalEvolutions = findViewById(R.id.tvTotalEvolutions)
        tvLastEvolution = findViewById(R.id.tvLastEvolution)
        tvLoadedPlugins = findViewById(R.id.tvLoadedPluginCount)

        // 待处理提案列表
        llProposals = findViewById(R.id.llProposals)
        tvEmptyProposals = findViewById(R.id.tvEmptyProposals)

        // 进化历史列表
        llEvolutionHistory = findViewById(R.id.llEvolutionHistory)
        tvEmptyHistory = findViewById(R.id.tvEmptyHistory)

        // 已加载插件列表
        llLoadedPlugins = findViewById(R.id.llLoadedPlugins)
        tvEmptyPlugins = findViewById(R.id.tvEmptyPlugins)

        // 操作按钮
        btnTriggerEvolution = findViewById(R.id.btnTriggerEvolution)
        btnRollback = findViewById(R.id.btnRollback)
        btnClearLogs = findViewById(R.id.btnClearLogs)
    }

    private fun setupActions() {
        // 手动触发进化
        btnTriggerEvolution.setOnClickListener {
            showTriggerEvolutionDialog()
        }

        // 回滚
        btnRollback.setOnClickListener {
            val rollbackInfo = evolution.getRollbackInfo()
            if (rollbackInfo != null) {
                AlertDialog.Builder(this)
                    .setTitle("⚠️ 确认回滚")
                    .setMessage("将从 ${rollbackInfo.first} 回滚到 ${rollbackInfo.second}\n\n" +
                            "回滚会删除新增的插件，恢复修改前的状态。\n确定要继续吗？")
                    .setPositiveButton("确认回滚") { _, _ -> performRollback() }
                    .setNegativeButton("取消", null)
                    .show()
            } else {
                Toast.makeText(this, "没有可回滚的版本", Toast.LENGTH_SHORT).show()
            }
        }

        // 清空日志
        btnClearLogs.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("清空进化日志")
                .setMessage("确定要清空所有进化历史记录吗？\n此操作不可恢复。")
                .setPositiveButton("确认清空") { _, _ ->
                    evolution.clearLogs()
                    refreshEvolutionHistory()
                    Toast.makeText(this, "进化日志已清空", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    /**
     * 观察进化状态变化
     */
    private fun observeEvolutionState() {
        // 观察版本号
        scope.launch {
            evolution.currentVersionFlow.collect { version ->
                tvVersion.text = "v$version"
            }
        }

        // 观察进化日志
        scope.launch {
            evolution.evolutionLogsFlow.collect { logs ->
                refreshEvolutionHistory()
            }
        }

        // 观察已加载插件
        scope.launch {
            evolution.loadedPluginsFlow.collect { plugins ->
                refreshLoadedPlugins()
            }
        }

        // 观察待处理提案
        scope.launch {
            evolution.proposalsFlow.collect { proposals ->
                refreshProposals()
            }
        }
    }

    /**
     * 刷新统计信息
     */
    private fun refreshStats() {
        val stats = evolution.getEvolutionStats()
        tvVersion.text = "v${stats["currentVersion"]}"
        tvTotalEvolutions.text = "${stats["totalEvolutions"]} 次"
        val lastTime = stats["lastEvolutionTime"] as Long
        tvLastEvolution.text = if (lastTime > 0) dateFormat.format(Date(lastTime)) else "暂无"
        tvLoadedPlugins.text = "${stats["loadedPluginsCount"]} 个"
    }

    /**
     * 刷新待处理提案列表
     */
    private fun refreshProposals() {
        llProposals.removeAllViews()
        val proposals = evolution.proposalsFlow.value

        if (proposals.isEmpty()) {
            tvEmptyProposals.visibility = View.VISIBLE
            return
        }
        tvEmptyProposals.visibility = View.GONE

        val inflater = LayoutInflater.from(this)

        for (proposal in proposals) {
            val itemView = inflater.inflate(R.layout.item_evolution_proposal, llProposals, false)

            // 填充数据
            itemView.findViewById<TextView>(R.id.tvProposalName).text = proposal.name
            itemView.findViewById<TextView>(R.id.tvProposalType).text = proposal.type
            itemView.findViewById<TextView>(R.id.tvProposalDesc).text = proposal.description
            itemView.findViewById<TextView>(R.id.tvProposalImpact).text = proposal.impact

            // 跳过按钮 - 10天内不再生成
            itemView.findViewById<MaterialButton>(R.id.btnSkipProposal).apply {
                visibility = View.VISIBLE
                setOnClickListener {
                    evolution.skipProposal(proposal.id)
                    Toast.makeText(this@EvolutionPanelActivity,
                        "已跳过「${proposal.name}」，10天内不再生成", Toast.LENGTH_SHORT).show()
                }
            }

            // 拒绝按钮 - 1年内不再生成
            itemView.findViewById<MaterialButton>(R.id.btnRejectProposal).apply {
                visibility = View.VISIBLE
                setOnClickListener {
                    evolution.rejectProposal(proposal.id)
                    Toast.makeText(this@EvolutionPanelActivity,
                        "已拒绝「${proposal.name}」，1年内不再生成", Toast.LENGTH_SHORT).show()
                }
            }

            // 执行按钮
            itemView.findViewById<MaterialButton>(R.id.btnExecuteProposal).apply {
                setOnClickListener {
                    scope.launch {
                        val success = evolution.executeProposal(proposal.id)
                        Toast.makeText(this@EvolutionPanelActivity,
                            if (success) "✅ 已执行「${proposal.name}」" else "❌ 执行失败",
                            Toast.LENGTH_SHORT).show()
                    }
                }
            }

            llProposals.addView(itemView)
        }
    }

    /**
     * 刷新进化历史列表
     */
    private fun refreshEvolutionHistory() {
        llEvolutionHistory.removeAllViews()
        val logs = evolution.evolutionLogsFlow.value

        if (logs.isEmpty()) {
            tvEmptyHistory.visibility = View.VISIBLE
            return
        }
        tvEmptyHistory.visibility = View.GONE

        for (entry in logs.take(50)) {  // 最多显示50条
            val itemView = createEvolutionLogItemView(entry)
            llEvolutionHistory.addView(itemView)
        }
    }

    /**
     * 创建单条进化记录视图
     */
    private fun createEvolutionLogItemView(entry: EvolutionLogEntry): View {
        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dpToPx(8) }
            radius = dpToPx(10).toFloat()
            setCardBackgroundColor(
                if (entry.success) getColor(R.color.soul_surface)
                else Color.argb(255, 60, 30, 30)
            )
            cardElevation = 1f
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10))
        }

        // 第一行：操作类型 + 时间
        val headerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val actionEmoji = when (entry.action) {
            EvolutionAction.ADD -> "➕"
            EvolutionAction.MODIFY -> "🔧"
            EvolutionAction.DELETE -> "🗑️"
            EvolutionAction.MODIFY_CORE -> "⚡"
            EvolutionAction.ROLLBACK -> "↩️"
        }

        val actionColor = when (entry.action) {
            EvolutionAction.ADD -> Color.parseColor("#00CEC9")
            EvolutionAction.MODIFY -> Color.parseColor("#6C5CE7")
            EvolutionAction.DELETE -> Color.parseColor("#D94A4A")
            EvolutionAction.MODIFY_CORE -> Color.parseColor("#FF9F43")
            EvolutionAction.ROLLBACK -> Color.parseColor("#4AD97A")
        }

        headerLayout.addView(TextView(this).apply {
            text = actionEmoji
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, 0, dpToPx(6), 0)
        })

        headerLayout.addView(TextView(this).apply {
            text = entry.action.displayName
            setTextColor(actionColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, 0, dpToPx(8), 0)
        })

        headerLayout.addView(TextView(this).apply {
            text = dateFormat.format(Date(entry.timestamp))
            setTextColor(getColor(R.color.soul_text_secondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            gravity = Gravity.END
        })

        if (!entry.success) {
            headerLayout.addView(TextView(this).apply {
                text = "❌"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            })
        }

        layout.addView(headerLayout)

        // 第二行：描述
        layout.addView(TextView(this).apply {
            text = entry.description
            setTextColor(getColor(R.color.soul_text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpToPx(4) }
        })

        // 第三行：错误信息（如果有）
        if (entry.errorMessage != null) {
            layout.addView(TextView(this).apply {
                text = "⚠️ ${entry.errorMessage}"
                setTextColor(Color.parseColor("#D94A4A"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dpToPx(2) }
            })
        }

        card.addView(layout)
        return card
    }

    /**
     * 刷新已加载插件列表
     */
    private fun refreshLoadedPlugins() {
        llLoadedPlugins.removeAllViews()
        val plugins = evolution.loadedPluginsFlow.value

        if (plugins.isEmpty()) {
            tvEmptyPlugins.visibility = View.VISIBLE
            return
        }
        tvEmptyPlugins.visibility = View.GONE

        for (plugin in plugins) {
            val itemView = createPluginItemView(plugin)
            llLoadedPlugins.addView(itemView)
        }
    }

    /**
     * 创建单个插件列表项视图
     */
    private fun createPluginItemView(plugin: EvolutionPluginManifest): View {
        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dpToPx(8) }
            radius = dpToPx(10).toFloat()
            setCardBackgroundColor(getColor(R.color.soul_surface))
            cardElevation = 1f
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10))
        }

        // 插件名 + 版本
        val headerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        headerLayout.addView(TextView(this).apply {
            text = "🔌 ${plugin.name}"
            setTextColor(getColor(R.color.soul_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })

        headerLayout.addView(TextView(this).apply {
            text = "v${plugin.version}"
            setTextColor(getColor(R.color.soul_text_secondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        })

        layout.addView(headerLayout)

        // 描述
        layout.addView(TextView(this).apply {
            text = plugin.description
            setTextColor(getColor(R.color.soul_text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpToPx(4) }
        })

        // 作者 + 创建时间
        layout.addView(TextView(this).apply {
            text = "by ${plugin.author} · ${dateFormat.format(Date(plugin.createdAt))}"
            setTextColor(getColor(R.color.soul_text_secondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpToPx(2) }
        })

        card.addView(layout)

        // 点击查看详情
        card.setOnClickListener {
            showPluginDetailDialog(plugin)
        }

        return card
    }

    /**
     * 显示触发进化对话框
     */
    private fun showTriggerEvolutionDialog() {
        // 构建输入表单
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(20), dpToPx(16), dpToPx(20), dpToPx(16))
        }

        val etName = EditText(this).apply {
            hint = "插件名称（如：天气显示、音乐播放器）"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10))
        }
        layout.addView(etName)

        val etDesc = EditText(this).apply {
            hint = "功能描述"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpToPx(8) }
        }
        layout.addView(etDesc)

        AlertDialog.Builder(this)
            .setTitle("🚀 触发AI进化")
            .setMessage("AI将根据你的描述自动生成新插件\n包含界面布局和交互逻辑")
            .setView(layout)
            .setPositiveButton("开始进化") { _, _ ->
                val name = etName.text.toString().trim()
                val desc = etDesc.text.toString().trim()
                if (name.isNotEmpty() && desc.isNotEmpty()) {
                    performEvolution(name, desc)
                } else {
                    Toast.makeText(this, "请填写完整信息", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 执行进化 - 生成提案供用户审核
     */
    private fun performEvolution(name: String, description: String) {
        Toast.makeText(this, "🧬 AI正在生成进化提案...", Toast.LENGTH_SHORT).show()

        // 检查是否被拒绝/跳过
        if (evolution.isProposalDismissed(name)) {
            Toast.makeText(this, "该提案已被标记，10天/1年内不再生成", Toast.LENGTH_LONG).show()
            return
        }

        // 生成示例插件内容（实际项目中这里会调用AI模型生成代码）
        val sampleXml = """
            <?xml version="1.0" encoding="utf-8"?>
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:orientation="vertical"
                android:padding="16dp"
                android:background="#1A1A2E">
                <TextView
                    android:id="@+id/tvPluginTitle"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:text="$name"
                    android:textSize="18sp"
                    android:textColor="#EEEEFF"
                    android:textStyle="bold" />
                <TextView
                    android:id="@+id/tvPluginContent"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="8dp"
                    android:text="$description"
                    android:textSize="14sp"
                    android:textColor="#8888AA" />
            </LinearLayout>
        """.trimIndent()

        val sampleScript = """
            // AI进化生成的插件脚本: $name
            // 描述: $description
            // 生成时间: ${System.currentTimeMillis()}
            
            println("插件 [$name] 已加载")
            
            fun onPluginLoad() {
                // 初始化逻辑
            }
            
            fun onInteraction(action: String) {
                // 交互处理
            }
        """.trimIndent()

        // 生成提案（而非直接执行），由用户在提案列表中决定执行/跳过/拒绝
        evolution.generateProposal(
            name = name,
            type = "UI",
            description = description,
            impact = "新增功能插件",
            xmlLayout = sampleXml,
            kotlinScript = sampleScript
        )

        Toast.makeText(this, "📋 提案已生成，请在「待处理提案」中审核", Toast.LENGTH_LONG).show()
    }

    /**
     * 执行回滚
     */
    private fun performRollback() {
        Toast.makeText(this, "↩️ 正在回滚...", Toast.LENGTH_SHORT).show()

        scope.launch {
            val success = evolution.rollback()
            withContext(Dispatchers.Main) {
                if (success) {
                    Toast.makeText(this@EvolutionPanelActivity,
                        "✅ 回滚成功", Toast.LENGTH_SHORT).show()
                    refreshStats()
                } else {
                    Toast.makeText(this@EvolutionPanelActivity,
                        "❌ 回滚失败", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * 显示插件详情对话框
     */
    private fun showPluginDetailDialog(plugin: EvolutionPluginManifest) {
        val message = buildString {
            appendLine("📋 插件ID: ${plugin.id}")
            appendLine("📌 名称: ${plugin.name}")
            appendLine("📦 版本: ${plugin.version}")
            appendLine("👤 作者: ${plugin.author}")
            appendLine("📝 描述: ${plugin.description}")
            appendLine("📅 创建: ${dateFormat.format(Date(plugin.createdAt))}")
            appendLine("🎨 布局: ${plugin.layoutFile ?: "无"}")
            appendLine("⚙️ 脚本: ${plugin.scriptFile ?: "无"}")
            appendLine("🔐 权限: L${plugin.requiredPermission}")
        }

        AlertDialog.Builder(this)
            .setTitle("🔌 插件详情")
            .setMessage(message)
            .setPositiveButton("关闭", null)
            .setNeutralButton("删除插件") { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle("确认删除")
                    .setMessage("确定要删除插件 [${plugin.name}] 吗？")
                    .setPositiveButton("删除") { _, _ ->
                        scope.launch {
                            evolution.deletePlugin(plugin.id)
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@EvolutionPanelActivity,
                                    "已删除: ${plugin.name}", Toast.LENGTH_SHORT).show()
                                refreshStats()
                            }
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            .show()
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), resources.displayMetrics
        ).toInt()
    }

    override fun onDestroy() {
        scope.cancel()
        evolution.release()
        super.onDestroy()
    }
}
