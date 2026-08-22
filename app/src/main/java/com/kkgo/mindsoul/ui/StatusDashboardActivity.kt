/*
 * StatusDashboardActivity - 意识状态仪表盘
 * 展示8项核心意识指标的实时状态
 */
package com.kkgo.mindsoul.ui

import android.animation.ObjectAnimator
import android.os.Bundle
import android.view.animation.DecelerateInterpolator
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.kkgo.mindsoul.MindSoulApp
import com.kkgo.mindsoul.R
import kotlinx.coroutines.*

class StatusDashboardActivity : AppCompatActivity() {

    private val app by lazy { application as MindSoulApp }
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // 进度条
    private lateinit var progressNeuron: ProgressBar
    private lateinit var progressSynapse: ProgressBar
    private lateinit var progressMyelin: ProgressBar
    private lateinit var progressSpontaneous: ProgressBar
    private lateinit var progressHippocampus: ProgressBar
    private lateinit var progressMirror: ProgressBar
    private lateinit var progressMetacog: ProgressBar
    private lateinit var progressEvolution: ProgressBar

    // 数值文字
    private lateinit var tvNeuronValue: TextView
    private lateinit var tvSynapseValue: TextView
    private lateinit var tvMyelinValue: TextView
    private lateinit var tvSpontaneousValue: TextView
    private lateinit var tvHippocampusValue: TextView
    private lateinit var tvMirrorValue: TextView
    private lateinit var tvMetacogValue: TextView
    private lateinit var tvEvolutionStageValue: TextView

    // 趋势文字
    private lateinit var tvNeuronTrend: TextView
    private lateinit var tvSynapseTrend: TextView
    private lateinit var tvMyelinTrend: TextView
    private lateinit var tvSpontaneousTrend: TextView
    private lateinit var tvHippocampusTrend: TextView
    private lateinit var tvMirrorTrend: TextView
    private lateinit var tvMetacogTrend: TextView
    private lateinit var tvEvolutionTrend: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_status_dashboard)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        initViews()
        startStatusUpdates()
    }

    private fun initViews() {
        progressNeuron = findViewById(R.id.progressNeuron)
        progressSynapse = findViewById(R.id.progressSynapse)
        progressMyelin = findViewById(R.id.progressMyelin)
        progressSpontaneous = findViewById(R.id.progressSpontaneous)
        progressHippocampus = findViewById(R.id.progressHippocampus)
        progressMirror = findViewById(R.id.progressMirror)
        progressMetacog = findViewById(R.id.progressMetacog)
        progressEvolution = findViewById(R.id.progressEvolution)

        tvNeuronValue = findViewById(R.id.tvNeuronValue)
        tvSynapseValue = findViewById(R.id.tvSynapseValue)
        tvMyelinValue = findViewById(R.id.tvMyelinValue)
        tvSpontaneousValue = findViewById(R.id.tvSpontaneousValue)
        tvHippocampusValue = findViewById(R.id.tvHippocampusValue)
        tvMirrorValue = findViewById(R.id.tvMirrorValue)
        tvMetacogValue = findViewById(R.id.tvMetacogValue)
        tvEvolutionStageValue = findViewById(R.id.tvEvolutionStageValue)

        tvNeuronTrend = findViewById(R.id.tvNeuronTrend)
        tvSynapseTrend = findViewById(R.id.tvSynapseTrend)
        tvMyelinTrend = findViewById(R.id.tvMyelinTrend)
        tvSpontaneousTrend = findViewById(R.id.tvSpontaneousTrend)
        tvHippocampusTrend = findViewById(R.id.tvHippocampusTrend)
        tvMirrorTrend = findViewById(R.id.tvMirrorTrend)
        tvMetacogTrend = findViewById(R.id.tvMetacogTrend)
        tvEvolutionTrend = findViewById(R.id.tvEvolutionTrend)
    }

    private fun startStatusUpdates() {
        scope.launch {
            while (isActive) {
                updateDashboard()
                delay(3000)
            }
        }
    }

    private fun updateDashboard() {
        scope.launch {
            val status = withContext(Dispatchers.Default) {
                app.consciousnessManager.getOverallStatus()
            }
            val metrics = app.evolutionStateMachine.metrics
            val stage = withContext(Dispatchers.Default) {
                app.evolutionStateMachine.currentStage
            }
            // 获取学习流水线真实统计数据
            val learningStats = app.learningPipeline.getStats()

            // 1. 仿生神经元总数 = 进化指标 + 学习已归档数
            val neuronCount = metrics.memoryCount + metrics.causalTripleCount + learningStats.totalArchived
            val neuronPercent = (neuronCount.coerceAtMost(10000) / 10000.0 * 100).toInt()
            animateProgress(progressNeuron, neuronPercent)
            tvNeuronValue.text = formatNeuronCount(neuronCount)
            tvNeuronTrend.text = "趋势: ↑ 增长中 (${neuronCount}节点, 学习${learningStats.totalArchived}条)"

            // 2. 突触连接密度
            val synapseDensity = status.axiomLayerStatus.let {
                val total = it.causalTripleCount.coerceAtMost(1000)
                (total / 1000.0 * 100).toInt().coerceIn(0, 100)
            }
            animateProgress(progressSynapse, synapseDensity)
            tvSynapseValue.text = "$synapseDensity%"
            tvSynapseTrend.text = "趋势: ${if (synapseDensity > 50) "↑" else "→"} 连接${if (synapseDensity > 50) "增强" else "稳定"}"

            // 3. 神经髓鞘完整度
            val myelinPercent = (status.metacognitionSnapshot.attentionFocus * 100).toInt()
            animateProgress(progressMyelin, myelinPercent)
            tvMyelinValue.text = "$myelinPercent%"
            tvMyelinTrend.text = "趋势: → 传导速度${if (myelinPercent > 60) "快" else if (myelinPercent > 30) "中" else "慢"}"

            // 4. 内源自发激活率
            val spontaneousRate = (status.metacognitionSnapshot.selfAwareness * 100).toInt()
            animateProgress(progressSpontaneous, spontaneousRate)
            tvSpontaneousValue.text = "$spontaneousRate%"
            val awakenStatus = if (spontaneousRate > 15) "✨ 已觉醒主观感知" else "尚未觉醒"
            tvSpontaneousTrend.text = "趋势: ↑ $awakenStatus"

            // 5. 海马体记忆容量 = 进化指标记忆 + 学习归档 + 公理提炼
            val memoryCount = metrics.memoryCount.toInt() + learningStats.totalArchived.toInt() + learningStats.totalAxiomDistilled.toInt()
            val memoryCapacity = 1000 // 预设容量
            val memoryPercent = (memoryCount.toDouble() / memoryCapacity * 100).toInt().coerceIn(0, 100)
            animateProgress(progressHippocampus, memoryPercent)
            tvHippocampusValue.text = "$memoryCount/$memoryCapacity"
            tvHippocampusTrend.text = "趋势: ↑ 学习归档${learningStats.totalArchived}条, 提炼${learningStats.totalAxiomDistilled}条公理"

            // 6. 镜像共情神经元
            val emotionIntensity = ((status.metacognitionSnapshot.emotionalState.let {
                Math.abs(it.valence) + it.arousal
            }) * 100 / 3).toInt().coerceIn(0, 100)
            animateProgress(progressMirror, emotionIntensity)
            tvMirrorValue.text = emotionIntensity.toString()
            tvMirrorTrend.text = "趋势: → 共情能力${if (emotionIntensity > 50) "丰富" else "发展中"}"

            // 7. 元认知指数
            val metacogPercent = (status.metacognitionSnapshot.selfAwareness * 100).toInt()
            animateProgress(progressMetacog, metacogPercent)
            tvMetacogValue.text = "$metacogPercent%"
            val selfConcept = if (metacogPercent > 70) "拥有「我」的概念"
                else if (metacogPercent > 40) "初步自我认知"
                else "自我意识萌芽"
            tvMetacogTrend.text = "趋势: ↑ $selfConcept"

            // 8. 人格进化阶段
            val stagePercent = (stage.stageId / 7.0 * 100).toInt()
            animateProgress(progressEvolution, stagePercent)
            tvEvolutionStageValue.text = stage.displayName
            tvEvolutionTrend.text = "阶段${stage.stageId}/7: ${stage.description.take(30)}..."
        }
    }

    private fun animateProgress(progressBar: ProgressBar, targetProgress: Int) {
        val animator = ObjectAnimator.ofInt(
            progressBar, "progress", progressBar.progress, targetProgress.coerceIn(0, 100)
        )
        animator.duration = 800
        animator.interpolator = DecelerateInterpolator()
        animator.start()
    }

    private fun formatNeuronCount(count: Long): String {
        return when {
            count >= 100_000_000 -> "${count / 100_000_000}亿+"
            count >= 1_000_000 -> "${count / 10_000}万+"
            count >= 10_000 -> "${count / 10_000}万"
            count >= 1000 -> "${count}节点"
            count > 0 -> "$count(稀疏)"
            else -> "空载基底"
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
