/*
 * ============================================================
 * LearningActivity - 学习主界面
 * ============================================================
 *
 * 学习中心，包含四个Tab页：
 *
 * Tab1: TXT批量导入 - 文件选择器支持多文件选择，批量导入解析
 *       + 图片文字OCR导入
 * Tab2: 网址抓取 - 多进程管理系统，支持各种变动值模式
 *       + 抓取间隔设置
 * Tab3: 智能爬取 - 递归爬取整站，同域名限制，遵守robots.txt
 * Tab4: 下载链接 - 输入文件直链URL，下载后走学习流水线
 *
 * 使用 ViewPager2 + Fragment 实现Tab切换。
 * 所有Tab共享学习流水线（LearningPipeline）和抓取引擎。
 *
 * 顶部统计区：每5秒刷新学习统计数据。
 * ============================================================
 */
package com.kkgo.mindsoul.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.google.android.material.textfield.TextInputEditText
import com.kkgo.mindsoul.MindSoulApp
import com.kkgo.mindsoul.R
import com.kkgo.mindsoul.learning.*
import kotlinx.coroutines.*
import java.io.File

/**
 * 学习主Activity
 */
class LearningActivity : AppCompatActivity() {

    private val app by lazy { application as MindSoulApp }

    // 统计视图
    private lateinit var tvKnowledgeCount: TextView
    private lateinit var tvCommonSenseCount: TextView
    private lateinit var tvConceptNodes: TextView
    private lateinit var tvCodeLines: TextView

    // 定时刷新
    private val statsHandler = Handler(Looper.getMainLooper())
    private val statsRefreshInterval = 5000L // 5秒刷新一次
    private val statsRunnable = object : Runnable {
        override fun run() {
            refreshStats()
            statsHandler.postDelayed(this, statsRefreshInterval)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_learning)

        // 工具栏
        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        // 初始化统计视图
        tvKnowledgeCount = findViewById(R.id.tvKnowledgeCount)
        tvCommonSenseCount = findViewById(R.id.tvCommonSenseCount)
        tvConceptNodes = findViewById(R.id.tvConceptNodes)
        tvCodeLines = findViewById(R.id.tvCodeLines)

        // 首次加载统计
        refreshStats()

        // ViewPager + TabLayout
        val viewPager = findViewById<ViewPager2>(R.id.viewPager)
        val tabLayout = findViewById<TabLayout>(R.id.tabLayout)

        viewPager.adapter = LearningPagerAdapter(this)

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> "✏️ 输入文本"
                1 -> "📄 TXT导入"
                2 -> "🔗 网址抓取"
                3 -> "🕷️ 智能爬取"
                4 -> "📥 下载链接"
                else -> ""
            }
        }.attach()

        // 预留入口按钮
        findViewById<MaterialButton>(R.id.btnImageImport).setOnClickListener {
            Toast.makeText(this, "图片导入学习（即将支持）", Toast.LENGTH_SHORT).show()
        }
        findViewById<MaterialButton>(R.id.btnVideoImport).setOnClickListener {
            Toast.makeText(this, "视频导入学习（即将支持）", Toast.LENGTH_SHORT).show()
        }
        findViewById<MaterialButton>(R.id.btnFileImport).setOnClickListener {
            Toast.makeText(this, "文件导入学习（即将支持）", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 刷新学习统计数据
     *
     * 从 learningPipeline 和 coldArchiveSystem 读取统计信息：
     * - 知识数量: pipeline 已归档的条目数
     * - 常识数量: coldArchive 中的语义记忆数
     * - 概念节点: coldArchive 中的突触连接数
     * - 代码量: pipeline 统计中的代码相关条目
     */
    private fun refreshStats() {
        try {
            // 从流水线获取统计
            val pipelineStats = app.learningPipeline.getStats()

            // 从冷归档获取记忆统计
            val memoryStats = app.consciousnessManager.coldArchive.getStats()

            // 知识数量 = 已归档条目总数
            val knowledgeCount = pipelineStats.totalArchived

            // 常识数量 = 语义记忆(SEMANTIC)条数
            val commonSenseCount = memoryStats.typeCounts["SEMANTIC"] ?: 0

            // 概念节点 = 赫布突触连接数（代表概念间关联）
            val conceptNodes = memoryStats.totalSynapses

            // 代码量 = 因果提取数（近似代表结构化知识行数）
            val codeLines = pipelineStats.totalCausalExtracted

            tvKnowledgeCount.text = "📚 知识: $knowledgeCount"
            tvCommonSenseCount.text = "💡 常识: $commonSenseCount"
            tvConceptNodes.text = "🔗 概念: $conceptNodes"
            tvCodeLines.text = "💻 代码: ${codeLines}行"
        } catch (e: Exception) {
            // 静默处理，避免UI崩溃
        }
    }

    override fun onResume() {
        super.onResume()
        // 启动定时刷新
        statsHandler.postDelayed(statsRunnable, statsRefreshInterval)
    }

    override fun onPause() {
        super.onPause()
        // 停止定时刷新
        statsHandler.removeCallbacks(statsRunnable)
    }

    // ============ ViewPager适配器 ============

    /**
     * 学习Tab页适配器
     */
    inner class LearningPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {
        override fun getItemCount(): Int = 5

        override fun createFragment(position: Int): Fragment {
            return when (position) {
                0 -> TextInputFragment()
                1 -> TxtImportFragment()
                2 -> UrlCrawlFragment()
                3 -> SmartCrawlFragment()
                4 -> DownloadLinkFragment()
                else -> TextInputFragment()
            }
        }
    }
}

// ============================================================
// Tab0: 输入文本 Fragment（直接输入文字，自动拆解学习入库）
// ============================================================

/**
 * 输入文本Fragment
 *
 * 功能：
 * - 用户直接输入文字内容
 * - 点击"学习"后，自动拆解并提交到LearningPipeline
 * - 支持多行输入
 * - 显示学习结果统计
 */
class TextInputFragment : Fragment() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val app by lazy { requireActivity().application as MindSoulApp }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_text_input, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val etTextInput = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etTextInput)
        val btnLearnText = view.findViewById<MaterialButton>(R.id.btnLearnText)
        val tvLearnStatus = view.findViewById<TextView>(R.id.tvLearnStatus)
        val cardLearnStats = view.findViewById<MaterialCardView>(R.id.cardTextLearnStats)
        val tvLearnedNeurons = view.findViewById<TextView>(R.id.tvTextLearnedNeurons)
        val tvKeywords = view.findViewById<TextView>(R.id.tvTextKeywords)
        val tvKeywordEntries = view.findViewById<TextView>(R.id.tvTextKeywordEntries)
        val tvConceptNodes = view.findViewById<TextView>(R.id.tvTextConceptNodes)
        val tvSummaryIndex = view.findViewById<TextView>(R.id.tvTextSummaryIndex)

        btnLearnText.setOnClickListener {
            val text = etTextInput.text?.toString()?.trim() ?: ""
            if (text.isBlank()) {
                Toast.makeText(requireContext(), "请输入要学习的文本内容", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            tvLearnStatus.visibility = View.VISIBLE
            tvLearnStatus.text = "正在学习..."
            btnLearnText.isEnabled = false

            scope.launch {
                try {
                    val statsStartTime = app.learningPipeline.markStatsTime()

                    // 提交到学习流水线
                    withContext(Dispatchers.IO) {
                        val material = LearningMaterial(
                            channel = ChannelType.TXT_BATCH_IMPORT,
                            rawContent = text,
                            source = "text_input:${System.currentTimeMillis()}",
                            metadata = mapOf(
                                "type" to "direct_text_input",
                                "char_count" to "${text.length}"
                            )
                        )
                        app.learningPipeline.processMaterial(material)
                    }

                    // 等待流水线处理
                    delay(300)

                    // 获取学习增量统计
                    val learnStats = app.learningPipeline.getLearnStatsSince(statsStartTime)

                    tvLearnStatus.text = "✅ 学习完成！共 ${text.length} 字"

                    // 显示学习结果
                    cardLearnStats.visibility = View.VISIBLE
                    tvLearnedNeurons.text = "🧠 神经元数量: ${learnStats.neuronsLearned} 个"
                    tvKeywords.text = "🔑 关键词数量: ${learnStats.keywordsFound} 个"
                    tvKeywordEntries.text = "关键词条: ${learnStats.keywordEntries} 条"
                    tvConceptNodes.text = "🔗 概念节点数量: ${learnStats.conceptNodes} 个"
                    tvSummaryIndex.text = "📋 概要搜引数量: ${learnStats.summaryIndex} 条"

                    Toast.makeText(requireContext(),
                        "学习完成！🧠 神经元:${learnStats.neuronsLearned} 🔑 关键词:${learnStats.keywordsFound}",
                        Toast.LENGTH_SHORT).show()

                    // 清空输入框
                    etTextInput.text?.clear()

                } catch (e: Exception) {
                    tvLearnStatus.text = "❌ 学习失败: ${e.message}"
                    Toast.makeText(requireContext(), "学习失败: ${e.message}", Toast.LENGTH_SHORT).show()
                } finally {
                    btnLearnText.isEnabled = true
                }
            }
        }
    }

    override fun onDestroyView() {
        scope.cancel()
        super.onDestroyView()
    }
}

// ============================================================
// Tab1: TXT批量导入 Fragment
// ============================================================

/**
 * TXT批量导入Fragment
 *
 * 功能：
 * - 文件选择器支持多文件选择
 * - 批量导入后逐个解析
 * - 显示导入进度和结果
 * - 图片文字OCR导入（新增）
 */
class TxtImportFragment : Fragment() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val app by lazy { requireActivity().application as MindSoulApp }

    // 文件选择器（支持多选）
    private val filePickerLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            startBatchImport(uris)
        }
    }

    // 图片选择器（用于OCR导入）
    private val imagePickerLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            startOcrImport(uri)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_txt_import, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 选择文件按钮
        view.findViewById<MaterialButton>(R.id.btnSelectFiles).setOnClickListener {
            // 打开文件选择器，支持多选
            filePickerLauncher.launch(arrayOf("text/plain", "text/markdown", "text/x-markdown", "*/*"))
        }

        // 图片文字导入按钮（新增）
        view.findViewById<MaterialButton>(R.id.btnImageTextImport).setOnClickListener {
            // 打开图片选择器
            imagePickerLauncher.launch("image/*")
        }
    }

    /**
     * 开始OCR图片文字导入
     *
     * 流程：
     * 1. 将图片URI复制到缓存目录获取文件路径
     * 2. 调用 multimediaController.submitOCR() 识别文字
     * 3. 将识别结果提交到 learningPipeline
     */
    private fun startOcrImport(imageUri: Uri) {
        val tvOcrStatus = view?.findViewById<TextView>(R.id.tvOcrStatus)
        val cardProgress = view?.findViewById<MaterialCardView>(R.id.cardProgress)
        val progressBar = view?.findViewById<ProgressBar>(R.id.progressBar)
        val tvProgressDetail = view?.findViewById<TextView>(R.id.tvProgressDetail)

        tvOcrStatus?.visibility = View.VISIBLE
        tvOcrStatus?.text = "📷 正在识别图片中的文字..."
        cardProgress?.visibility = View.VISIBLE
        progressBar?.progress = 30
        tvProgressDetail?.text = "图片准备中..."

        scope.launch {
            try {
                // 将图片复制到缓存目录以获取文件路径
                val cacheFile = withContext(Dispatchers.IO) {
                    val tempFile = File(requireContext().cacheDir, "ocr_import_${System.currentTimeMillis()}.jpg")
                    requireContext().contentResolver.openInputStream(imageUri)?.use { input ->
                        tempFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    tempFile
                }

                progressBar?.progress = 50
                tvProgressDetail?.text = "OCR 识别中..."

                // 调用 OCR 识别
                val ocrResult = withContext(Dispatchers.IO) {
                    app.multimediaController.submitOCR(cacheFile.absolutePath).await()
                }

                progressBar?.progress = 80
                tvProgressDetail?.text = "处理识别结果..."

                val extractedText = ocrResult.extractedText

                if (extractedText.isNotBlank()) {
                    // 提交到学习流水线
                    withContext(Dispatchers.IO) {
                        val material = LearningMaterial(
                            channel = ChannelType.TXT_BATCH_IMPORT,
                            rawContent = extractedText,
                            source = "ocr:${imageUri.toString()}",
                            metadata = mapOf(
                                "type" to "image_ocr",
                                "confidence" to "${ocrResult.confidence}",
                                "duration_ms" to "${ocrResult.durationMs}"
                            )
                        )
                        app.learningPipeline.processMaterial(material)
                    }

                    tvOcrStatus?.text = "✅ 识别成功：${extractedText.length} 字，置信度 ${"%.1f".format(ocrResult.confidence * 100)}%"
                    progressBar?.progress = 100
                    tvProgressDetail?.text = "OCR导入完成！已学习 ${extractedText.length} 字"
                    Toast.makeText(requireContext(), "图片文字导入成功：${extractedText.length} 字", Toast.LENGTH_SHORT).show()
                } else {
                    tvOcrStatus?.text = "⚠️ 未识别到文字内容"
                    tvProgressDetail?.text = "图片中未发现可识别的文字"
                    Toast.makeText(requireContext(), "图片中未识别到文字", Toast.LENGTH_SHORT).show()
                }

                // 清理临时文件
                withContext(Dispatchers.IO) { cacheFile.delete() }

            } catch (e: Exception) {
                tvOcrStatus?.text = "❌ OCR识别失败"
                tvProgressDetail?.text = "错误: ${e.message}"
                Toast.makeText(requireContext(), "OCR导入失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 开始批量导入（增强版：分块读取 + 每文件结果 + 汇总统计）
     *
     * - 大文件（>5MB）使用 1MB 分块读取，避免 OOM
     * - 每文件 5 分钟超时保护
     * - 每文件导入完成后显示学习增量统计
     * - 所有文件完成后显示汇总统计
     */
    private fun startBatchImport(uris: List<Uri>) {
        val cardProgress = view?.findViewById<MaterialCardView>(R.id.cardProgress)
        val progressBar = view?.findViewById<ProgressBar>(R.id.progressBar)
        val tvProgressDetail = view?.findViewById<TextView>(R.id.tvProgressDetail)
        val tvResultsTitle = view?.findViewById<TextView>(R.id.tvResultsTitle)
        val cardStats = view?.findViewById<MaterialCardView>(R.id.cardStats)
        val tvStatsTotal = view?.findViewById<TextView>(R.id.tvStatsTotal)
        val tvStatsSuccess = view?.findViewById<TextView>(R.id.tvStatsSuccess)
        val tvStatsFail = view?.findViewById<TextView>(R.id.tvStatsFail)
        val tvStatsWords = view?.findViewById<TextView>(R.id.tvStatsWords)
        val tvStatsLearnedNeurons = view?.findViewById<TextView>(R.id.tvStatsLearnedNeurons)
        val tvStatsKeywords = view?.findViewById<TextView>(R.id.tvStatsKeywords)
        val tvStatsKeywordEntries = view?.findViewById<TextView>(R.id.tvStatsKeywordEntries)
        val tvStatsConceptNodes = view?.findViewById<TextView>(R.id.tvStatsConceptNodes)
        val tvStatsSummaryIndex = view?.findViewById<TextView>(R.id.tvStatsSummaryIndex)

        // 显示进度
        cardProgress?.visibility = View.VISIBLE

        val totalFiles = uris.size
        var successCount = 0
        var failCount = 0
        var totalWords = 0L

        // 汇总学习增量统计
        var totalNeuronsLearned = 0
        var totalKeywordsFound = 0
        var totalKeywordEntries = 0
        var totalConceptNodes = 0
        var totalSummaryIndex = 0

        // 大文件阈值: 5MB
        val LARGE_FILE_THRESHOLD = 5 * 1024 * 1024L
        // 分块大小: 1MB
        val CHUNK_SIZE = 1024 * 1024
        // 单文件超时: 5分钟
        val SINGLE_FILE_TIMEOUT_MS = 5 * 60 * 1000L

        scope.launch {
            for ((index, uri) in uris.withIndex()) {
                val fileName = getFileName(uri)
                // 更新进度
                val progress = ((index + 1) * 100 / totalFiles)
                progressBar?.progress = progress
                tvProgressDetail?.text = "正在导入 ${index + 1}/$totalFiles: $fileName"

                // 记录当前文件导入前的时间戳
                val fileStartTime = app.learningPipeline.markStatsTime()

                try {
                    val contentLength = withContext(Dispatchers.IO) {
                        requireContext().contentResolver.openInputStream(uri)?.use { it.available() } ?: 0
                    }

                    val isLargeFile = contentLength > LARGE_FILE_THRESHOLD
                    val content: String

                    if (isLargeFile) {
                        // 大文件分块读取（每次 1MB chunk）
                        val sb = StringBuilder()
                        val fileStartTimeMs = System.currentTimeMillis()
                        withContext(Dispatchers.IO) {
                            requireContext().contentResolver.openInputStream(uri)?.use { inputStream ->
                                val buffer = ByteArray(CHUNK_SIZE)
                                var bytesRead: Int
                                var chunkCount = 0
                                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                                    // 超时保护
                                    if (System.currentTimeMillis() - fileStartTimeMs > SINGLE_FILE_TIMEOUT_MS) {
                                        Log.w("TxtImport", "文件导入超时(5分钟): $fileName")
                                        throw java.util.concurrent.TimeoutException("文件处理超时: $fileName")
                                    }
                                    sb.append(String(buffer, 0, bytesRead, Charsets.UTF_8))
                                    chunkCount++
                                    // 在主线程更新分块进度
                                    withContext(Dispatchers.Main) {
                                        val chunkProgress = progress - (100 / totalFiles).coerceAtLeast(1) +
                                                ((chunkCount.toFloat() / (contentLength.toFloat() / CHUNK_SIZE)) * (100 / totalFiles)).toInt()
                                        progressBar?.progress = chunkProgress.coerceIn(0, 100)
                                        tvProgressDetail?.text = "正在导入 ${index + 1}/$totalFiles: $fileName (已读取 ${chunkCount}MB)"
                                    }
                                }
                            }
                        }
                        content = sb.toString()
                    } else {
                        // 小文件直接读取，带超时保护
                        content = withContext(Dispatchers.IO) {
                            requireContext().contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: ""
                        }
                    }

                    if (content.isNotBlank()) {
                        // 提交到学习流水线
                        withContext(Dispatchers.IO) {
                            val material = LearningMaterial(
                                channel = ChannelType.TXT_BATCH_IMPORT,
                                rawContent = content,
                                source = uri.toString(),
                                metadata = mapOf("filename" to fileName)
                            )
                            app.learningPipeline.processMaterial(material)
                        }

                        // 等待流水线处理（短暂等待让统计更新）
                        delay(200)

                        // 获取该文件的学习增量统计
                        val fileStats = app.learningPipeline.getLearnStatsSince(fileStartTime)
                        totalNeuronsLearned += fileStats.neuronsLearned
                        totalKeywordsFound += fileStats.keywordsFound
                        totalKeywordEntries += fileStats.keywordEntries
                        totalConceptNodes += fileStats.conceptNodes
                        totalSummaryIndex += fileStats.summaryIndex

                        successCount++
                        totalWords += content.length

                        // 显示每文件导入结果 Toast
                        val fileResultMsg = buildString {
                            append("$fileName ✅\n")
                            append("🧠 神经元: ${fileStats.neuronsLearned} | ")
                            append("🔑 关键词: ${fileStats.keywordsFound}\n")
                            append("🔗 概念: ${fileStats.conceptNodes} | ")
                            append("📋 搜引: ${fileStats.summaryIndex}")
                        }
                        withContext(Dispatchers.Main) {
                            if (isAdded) {
                                Toast.makeText(requireContext(), fileResultMsg, Toast.LENGTH_LONG).show()
                            }
                        }
                    } else {
                        failCount++
                        withContext(Dispatchers.Main) {
                            if (isAdded) {
                                Toast.makeText(requireContext(), "$fileName ⚠️ 内容为空", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                } catch (e: java.util.concurrent.TimeoutException) {
                    failCount++
                    withContext(Dispatchers.Main) {
                        if (isAdded) {
                            Toast.makeText(requireContext(), "$fileName ❌ 处理超时(>5分钟)", Toast.LENGTH_LONG).show()
                        }
                    }
                } catch (e: Exception) {
                    failCount++
                    withContext(Dispatchers.Main) {
                        if (isAdded) {
                            Toast.makeText(requireContext(), "$fileName ❌ ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }

            // 导入完成 - 更新汇总统计
            tvProgressDetail?.text = "导入完成！"
            tvResultsTitle?.visibility = View.VISIBLE
            cardStats?.visibility = View.VISIBLE

            // 更新基础统计
            tvStatsTotal?.text = "总文件数: $totalFiles"
            tvStatsSuccess?.text = "成功导入: $successCount"
            tvStatsFail?.text = "导入失败: $failCount"
            tvStatsWords?.text = "学习字数: $totalWords"

            // 更新学习增量汇总统计
            tvStatsLearnedNeurons?.text = "🧠 学到神经元: $totalNeuronsLearned 个"
            tvStatsKeywords?.text = "🔑 关键词: $totalKeywordsFound 个"
            tvStatsKeywordEntries?.text = "关键词条: $totalKeywordEntries 条"
            tvStatsConceptNodes?.text = "🔗 概念节点: $totalConceptNodes 个"
            tvStatsSummaryIndex?.text = "📋 概要搜引: $totalSummaryIndex 条"

            Toast.makeText(requireContext(), "导入完成：成功 $successCount / 失败 $failCount", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 从URI获取文件名
     */
    private fun getFileName(uri: Uri): String {
        return try {
            requireContext().contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                cursor.moveToFirst()
                cursor.getString(nameIndex)
            } ?: uri.lastPathSegment ?: "unknown"
        } catch (e: Exception) {
            uri.lastPathSegment ?: "unknown"
        }
    }

    override fun onDestroyView() {
        scope.cancel()
        super.onDestroyView()
    }
}

// ============================================================
// Tab2: 网址抓取 Fragment（多进程管理）
// ============================================================

/**
 * 网址抓取Fragment - 多进程管理系统
 *
 * 功能：
 * - 创建抓取进程（URL模板 + 起始/结束值）
 * - 自动识别变动值模式
 * - 进程列表展示（URL模板、进度、状态）
 * - 单独暂停/恢复/删除进程
 * - 进程完成后自动消失
 * - 最大并发限制（3个）
 * - 抓取间隔设置（新增）
 */
class UrlCrawlFragment : Fragment() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val app by lazy { requireActivity().application as MindSoulApp }
    /** 网络状态广播接收器引用，用于onDestroyView中注销 */
    private var networkReceiverRef: BroadcastReceiver? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_url_crawl, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val etUrlTemplate = view.findViewById<TextInputEditText>(R.id.etUrlTemplate)
        val etStartValue = view.findViewById<TextInputEditText>(R.id.etStartValue)
        val etEndValue = view.findViewById<TextInputEditText>(R.id.etEndValue)
        val tvPatternHint = view.findViewById<TextView>(R.id.tvPatternHint)
        val btnCreate = view.findViewById<MaterialButton>(R.id.btnCreateProcess)
        val recyclerProcesses = view.findViewById<RecyclerView>(R.id.recyclerProcesses)
        val tvEmptyProcesses = view.findViewById<TextView>(R.id.tvEmptyProcesses)
        val tvRunningCount = view.findViewById<TextView>(R.id.tvRunningCount)
        val btnClearFinished = view.findViewById<MaterialButton>(R.id.btnClearFinished)

        // 抓取间隔设置（新增）
        val etCrawlInterval = view.findViewById<TextInputEditText>(R.id.etCrawlInterval)
        val etRandomDelay = view.findViewById<TextInputEditText>(R.id.etRandomDelay)

        // 设置RecyclerView
        val adapter = ProcessListAdapter()
        recyclerProcesses.layoutManager = LinearLayoutManager(requireContext())
        recyclerProcesses.adapter = adapter

        // 创建进程
        btnCreate.setOnClickListener {
            val template = etUrlTemplate.text?.toString()?.trim() ?: ""
            val startVal = etStartValue.text?.toString()?.trim() ?: ""
            val endVal = etEndValue.text?.toString()?.trim() ?: ""

            if (template.isBlank() || startVal.isBlank() || endVal.isBlank()) {
                Toast.makeText(requireContext(), "请填写完整的URL模板和起止值", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (!template.contains("{var}")) {
                Toast.makeText(requireContext(), "URL模板中必须包含 {var} 占位符", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // 检查并发限制
            if (app.crawlProcessManager.getRunningCount() >= CrawlProcessManager.MAX_CONCURRENT) {
                Toast.makeText(requireContext(), "已达最大并发数（${CrawlProcessManager.MAX_CONCURRENT}个），请等待或暂停其他进程", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            // 读取抓取间隔参数
            val intervalSeconds = etCrawlInterval.text?.toString()?.toDoubleOrNull() ?: 2.0
            val randomDelayRange = parseRandomDelay(etRandomDelay.text?.toString()?.trim() ?: "10-400")

            // 创建进程（传入间隔参数）
            val processId = app.crawlProcessManager.createProcess(
                urlTemplate = template,
                startValue = startVal,
                endValue = endVal,
                intervalMs = (intervalSeconds * 1000).toLong(),
                randomDelayMin = randomDelayRange.first,
                randomDelayMax = randomDelayRange.second
            )
            if (processId != null) {
                // 自动启动
                app.crawlProcessManager.startProcess(processId)
                Toast.makeText(requireContext(),
                    "进程已创建并启动（间隔${intervalSeconds}秒，延迟${randomDelayRange.first}-${randomDelayRange.second}ms）",
                    Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "创建进程失败，请检查参数", Toast.LENGTH_SHORT).show()
            }
        }

        // 监听变动值输入变化，实时提示模式
// [DEAD CODE REMOVED]         val patternWatcher = android.text.TextWatcher {
//            val startVal = etStartValue.text?.toString()?.trim() ?: ""
//            val endVal = etEndValue.text?.toString()?.trim() ?: ""
//            if (startVal.isNotEmpty() && endVal.isNotEmpty()) {
//                val pattern = app.crawlProcessManager.analyzeVarPattern(startVal, endVal)
//                val typeDesc = when (pattern.type) {
//                    VarType.PURE_NUMBER -> "纯数字模式"
//                    VarType.PREFIX_NUMBER -> "带前缀数字模式"
//                    VarType.MIXED -> "混合字符模式"
//                    VarType.ALPHA_SEQUENCE -> "字母序列模式"
//                }
//                val total = pattern.total()
//                tvPatternHint.text = "📐 识别为: $typeDesc | 共 $total 个页面"
//                tvPatternHint.visibility = View.VISIBLE
//            } else {
//                tvPatternHint.visibility = View.GONE
//            }
//        }
        // TextWatcher 简化实现 - 使用自定义扩展
        etStartValue.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val startVal = s?.toString()?.trim() ?: ""
                val endVal = etEndValue.text?.toString()?.trim() ?: ""
                if (startVal.isNotEmpty() && endVal.isNotEmpty()) {
                    val pattern = app.crawlProcessManager.analyzeVarPattern(startVal, endVal)
                    val typeDesc = when (pattern.type) {
                        VarType.PURE_NUMBER -> "纯数字模式"
                        VarType.PREFIX_NUMBER -> "带前缀数字模式"
                        VarType.MIXED -> "混合字符模式"
                        VarType.ALPHA_SEQUENCE -> "字母序列模式"
                    }
                    val total = pattern.total()
                    tvPatternHint.text = "📐 识别为: $typeDesc | 共 $total 个页面"
                    tvPatternHint.visibility = View.VISIBLE
                }
            }
        })
        etEndValue.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val startVal = etStartValue.text?.toString()?.trim() ?: ""
                val endVal = s?.toString()?.trim() ?: ""
                if (startVal.isNotEmpty() && endVal.isNotEmpty()) {
                    val pattern = app.crawlProcessManager.analyzeVarPattern(startVal, endVal)
                    val typeDesc = when (pattern.type) {
                        VarType.PURE_NUMBER -> "纯数字模式"
                        VarType.PREFIX_NUMBER -> "带前缀数字模式"
                        VarType.MIXED -> "混合字符模式"
                        VarType.ALPHA_SEQUENCE -> "字母序列模式"
                    }
                    val total = pattern.total()
                    tvPatternHint.text = "📐 识别为: $typeDesc | 共 $total 个页面"
                    tvPatternHint.visibility = View.VISIBLE
                }
            }
        })

        // 清理已完成进程
        btnClearFinished.setOnClickListener {
            app.crawlProcessManager.clearFinishedProcesses()
        }

        // ============ 后台运行与状态控制（新增） ============
        val switchBackgroundMode = view.findViewById<SwitchMaterial>(R.id.switchBackgroundMode)
        val tvCrawlStatus = view.findViewById<TextView>(R.id.tvCrawlStatus)
        val btnPauseCrawl = view.findViewById<MaterialButton>(R.id.btnPauseCrawl)
        val btnResumeCrawl = view.findViewById<MaterialButton>(R.id.btnResumeCrawl)

        // 启动时加载未完成的爬取任务
        app.crawlProcessManager.loadCheckpoint()

        // 后台运行开关
        switchBackgroundMode.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                // 启动前台服务
                val serviceIntent = Intent(requireContext(), CrawlForegroundService::class.java).apply {
                    action = CrawlForegroundService.ACTION_START
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    requireContext().startForegroundService(serviceIntent)
                } else {
                    requireContext().startService(serviceIntent)
                }
                tvCrawlStatus.text = "🔄 爬取中（后台运行）"
                Toast.makeText(requireContext(), "后台爬取已开启", Toast.LENGTH_SHORT).show()
            } else {
                // 停止前台服务
                val serviceIntent = Intent(requireContext(), CrawlForegroundService::class.java).apply {
                    action = CrawlForegroundService.ACTION_STOP
                }
                requireContext().startService(serviceIntent)
                tvCrawlStatus.text = "⏹ 空闲"
                Toast.makeText(requireContext(), "后台爬取已关闭", Toast.LENGTH_SHORT).show()
            }
        }

        // 全部暂停按钮
        btnPauseCrawl.setOnClickListener {
            val serviceIntent = Intent(requireContext(), CrawlForegroundService::class.java).apply {
                action = CrawlForegroundService.ACTION_PAUSE
            }
            requireContext().startService(serviceIntent)
            tvCrawlStatus.text = "⏸ 已暂停"
            btnPauseCrawl.visibility = View.GONE
            btnResumeCrawl.visibility = View.VISIBLE
        }

        // 全部恢复按钮
        btnResumeCrawl.setOnClickListener {
            val serviceIntent = Intent(requireContext(), CrawlForegroundService::class.java).apply {
                action = CrawlForegroundService.ACTION_RESUME
            }
            requireContext().startService(serviceIntent)
            tvCrawlStatus.text = "🔄 爬取中"
            btnPauseCrawl.visibility = View.VISIBLE
            btnResumeCrawl.visibility = View.GONE
        }

        // 注册网络状态广播接收器
        val networkReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val networkAvailable = intent?.getBooleanExtra(
                    CrawlForegroundService.EXTRA_NETWORK_AVAILABLE, true
                ) ?: true

                if (networkAvailable) {
                    tvCrawlStatus.text = "🔄 爬取中"
                    btnPauseCrawl.visibility = View.VISIBLE
                    btnResumeCrawl.visibility = View.GONE
                } else {
                    tvCrawlStatus.text = "⚠️ 网络断开，爬取已暂停"
                    btnPauseCrawl.visibility = View.GONE
                    btnResumeCrawl.visibility = View.GONE
                }
            }
        }

        val filter = IntentFilter(CrawlForegroundService.BROADCAST_NETWORK_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireContext().registerReceiver(networkReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            requireContext().registerReceiver(networkReceiver, filter)
        }

        // 检测当前网络状态
        val cm = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = cm.activeNetwork
        if (activeNetwork == null) {
            tvCrawlStatus.text = "⚠️ 网络断开"
        }

        // 任务排队锁定提示视图
        val tvQueueLockHint = view.findViewById<TextView>(R.id.tvQueueLockHint)

        // 监听进程列表变化
        scope.launch {
            app.crawlProcessManager.processesFlow.collect { processes ->
                val hasFinished = processes.any { it.isFinished }
                btnClearFinished.visibility = if (hasFinished) View.VISIBLE else View.GONE
                tvEmptyProcesses.visibility = if (processes.isEmpty()) View.VISIBLE else View.GONE

                val running = processes.count { it.state == CrawlProcessState.RUNNING }
                val paused = processes.count { it.state == CrawlProcessState.PAUSED }
                val pending = processes.count { it.state == CrawlProcessState.PENDING }
                tvRunningCount.text = "运行中: $running/${CrawlProcessManager.MAX_CONCURRENT}"

                // ============ 多进程并发控制逻辑（不再排队，允许同时运行） ============
                val hasRunningTask = running > 0
                // 不再锁定创建按钮 - 允许多进程同时进行
                tvQueueLockHint.visibility = View.GONE
                // 但仍提示当前运行状态
                if (hasRunningTask) {
                    val queueInfo = buildString {
                        append("⚡ 并发爬取中（$running 个进程同时运行")
                        if (pending > 0) append("，$pending 个排队")
                        append("）")
                    }
                    tvQueueLockHint.text = queueInfo
                    tvQueueLockHint.visibility = View.VISIBLE
                }

                // 更新状态指示
                when {
                    running > 0 -> {
                        tvCrawlStatus.text = "🔄 爬取中（$running 个进程运行${if (pending > 0) ", $pending 个排队" else ""}）"
                        btnPauseCrawl.visibility = View.VISIBLE
                        btnResumeCrawl.visibility = View.GONE
                    }
                    paused > 0 -> {
                        tvCrawlStatus.text = "⏸ 已暂停（$paused 个进程等待恢复）"
                        btnPauseCrawl.visibility = View.GONE
                        btnResumeCrawl.visibility = View.VISIBLE
                    }
                    else -> {
                        tvCrawlStatus.text = "⏹ 空闲"
                        btnPauseCrawl.visibility = View.GONE
                        btnResumeCrawl.visibility = View.GONE
                    }
                }

                adapter.submitList(processes)
            }
        }

        // 保存引用以便onDestroyView中注销
        networkReceiverRef = networkReceiver
    }

    /**
     * 解析随机延迟范围字符串
     * 格式: "min-max"，如 "10-400"
     * @return Pair(min, max)，默认 (10, 400)
     */
    private fun parseRandomDelay(input: String): Pair<Long, Long> {
        return try {
            val parts = input.split("-").map { it.trim().toLong() }
            if (parts.size >= 2) {
                Pair(parts[0].coerceAtLeast(0), parts[1].coerceAtLeast(parts[0]))
            } else {
                Pair(10, 400)
            }
        } catch (e: Exception) {
            Pair(10, 400)
        }
    }

    class ProcessViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvUrl: TextView = itemView.findViewById(R.id.tvProcessUrl)
        private val tvProgress: TextView = itemView.findViewById(R.id.tvProcessProgress)
        private val tvState: TextView = itemView.findViewById(R.id.tvProcessState)
        private val progressBar: ProgressBar = itemView.findViewById(R.id.processProgressBar)
        private val btnPause: MaterialButton = itemView.findViewById(R.id.btnPauseProcess)
        private val btnResume: MaterialButton = itemView.findViewById(R.id.btnResumeProcess)
        private val btnDelete: MaterialButton = itemView.findViewById(R.id.btnDeleteProcess)
        // 学习统计视图
        private val layoutLearnStats: View = itemView.findViewById(R.id.layoutLearnStats)
        private val tvLearnedNeurons: TextView = itemView.findViewById(R.id.tvLearnedNeurons)
        private val tvKeywords: TextView = itemView.findViewById(R.id.tvKeywords)
        private val tvKeywordEntries: TextView = itemView.findViewById(R.id.tvKeywordEntries)
        private val tvConceptNodes: TextView = itemView.findViewById(R.id.tvConceptNodes)
        private val tvSummaryIndex: TextView = itemView.findViewById(R.id.tvSummaryIndex)

        fun bind(process: CrawlProcessInfo) {
            tvUrl.text = process.urlTemplate
            tvProgress.text = "${process.currentOffset}/${process.total} (成功:${process.successCount} 失败:${process.failCount})"
            progressBar.progress = (process.progress * 100).toInt()

            val stateText = when (process.state) {
                CrawlProcessState.PENDING -> "⏳ 等待中"
                CrawlProcessState.RUNNING -> "🔄 运行中"
                CrawlProcessState.PAUSED -> "⏸ 已暂停"
                CrawlProcessState.COMPLETED -> "✅ 已完成"
                CrawlProcessState.ERROR -> "❌ 错误"
            }
            tvState.text = stateText

            // 学习统计显示
            val hasStats = process.learnedNeurons > 0 || process.learnedKeywords > 0 ||
                    process.learnedConceptNodes > 0 || process.learnedSummaryIndex > 0 ||
                    process.state == CrawlProcessState.RUNNING
            layoutLearnStats.visibility = if (hasStats) View.VISIBLE else View.GONE
            if (hasStats) {
                tvLearnedNeurons.text = "🧠 已学到神经元: ${process.learnedNeurons} 个"
                tvKeywords.text = "🔑 关键词: ${process.learnedKeywords} 个"
                tvKeywordEntries.text = "关键词条: ${process.learnedKeywordEntries} 条"
                tvConceptNodes.text = "🔗 概念节点: ${process.learnedConceptNodes} 个"
                tvSummaryIndex.text = "📋 概要搜引: ${process.learnedSummaryIndex} 条"
            }

            // 按钮状态
            btnPause.visibility = if (process.state == CrawlProcessState.RUNNING) View.VISIBLE else View.GONE
            btnResume.visibility = if (process.state == CrawlProcessState.PAUSED) View.VISIBLE else View.GONE
            btnDelete.visibility = View.VISIBLE

            btnPause.setOnClickListener {
                (itemView.context.applicationContext as com.kkgo.mindsoul.MindSoulApp).crawlProcessManager.pauseProcess(process.id)
            }
            btnResume.setOnClickListener {
                (itemView.context.applicationContext as com.kkgo.mindsoul.MindSoulApp).crawlProcessManager.resumeProcess(process.id)
            }
            btnDelete.setOnClickListener {
                (itemView.context.applicationContext as com.kkgo.mindsoul.MindSoulApp).crawlProcessManager.removeProcess(process.id)
            }
        }
    }

    /**
     * 进程列表适配器
     */
    inner class ProcessListAdapter : RecyclerView.Adapter<ProcessViewHolder>() {
        private val items = mutableListOf<CrawlProcessInfo>()

        fun submitList(list: List<CrawlProcessInfo>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProcessViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_crawl_process, parent, false)
            return ProcessViewHolder(view)
        }

        override fun onBindViewHolder(holder: ProcessViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

    }


    override fun onDestroyView() {
        // 注销网络状态广播接收器
        networkReceiverRef?.let {
            try { requireContext().unregisterReceiver(it) } catch (_: Exception) {}
        }
        networkReceiverRef = null
        // 离开页面时保存爬取进度
        app.crawlProcessManager.saveCheckpoint()
        scope.cancel()
        super.onDestroyView()
    }
}

// ============================================================
// Tab3: 智能爬取 Fragment
// ============================================================

/**
 * 智能爬取Fragment
 *
 * 功能：
 * - 输入起始URL
 * - 配置深度限制、最大页面数
 * - 自动递归爬取同域名页面
 * - 遵守 robots.txt
 * - 实时显示爬取进度
 */
class SmartCrawlFragment : Fragment() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val app by lazy { requireActivity().application as MindSoulApp }

    // SAF文件夹选择器 - 用于robots.txt配置文件目录
    private val robotsConfigDirPicker = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri: android.net.Uri? ->
        if (uri != null) {
            // 持久化权限
            requireContext().contentResolver.takePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            // 保存到SharedPreferences
            val prefs = requireContext().getSharedPreferences("smart_crawl_config", 0)
            prefs.edit().putString("robots_config_dir", uri.toString()).apply()
            // 更新UI
            view?.findViewById<TextView>(R.id.tvRobotsConfigDir)?.text = "配置目录: ${uri.lastPathSegment ?: uri.toString()}"
            Toast.makeText(requireContext(), "robots.txt配置目录已设置", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_smart_crawl, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val etStartUrl = view.findViewById<TextInputEditText>(R.id.etStartUrl)
        val etMaxDepth = view.findViewById<TextInputEditText>(R.id.etMaxDepth)
        val etMaxPages = view.findViewById<TextInputEditText>(R.id.etMaxPages)
        val switchRobots = view.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchRobots)
        val switchSameDomain = view.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchSameDomain)
        val btnStart = view.findViewById<MaterialButton>(R.id.btnStartCrawl)
        val btnPause = view.findViewById<MaterialButton>(R.id.btnPauseCrawl)
        val cardProgress = view.findViewById<MaterialCardView>(R.id.cardCrawlProgress)
        val tvCrawlState = view.findViewById<TextView>(R.id.tvCrawlState)
        val crawlProgressBar = view.findViewById<ProgressBar>(R.id.crawlProgressBar)
        val tvCurrentUrl = view.findViewById<TextView>(R.id.tvCurrentUrl)
        val tvCrawlDiscovered = view.findViewById<TextView>(R.id.tvCrawlDiscovered)
        val tvCrawlSuccess = view.findViewById<TextView>(R.id.tvCrawlSuccess)
        val tvCrawlFailed = view.findViewById<TextView>(R.id.tvCrawlFailed)
        val tvCrawlBlocked = view.findViewById<TextView>(R.id.tvCrawlBlocked)
        val tvCrawlDepth = view.findViewById<TextView>(R.id.tvCrawlDepth)

        // robots.txt配置目录设置
        val btnRobotsConfigDir = view.findViewById<MaterialButton>(R.id.btnRobotsConfigDir)
        val tvRobotsConfigDir = view.findViewById<TextView>(R.id.tvRobotsConfigDir)

        // 加载已保存的配置目录
        val crawlPrefs = requireContext().getSharedPreferences("smart_crawl_config", 0)
        val savedDir = crawlPrefs.getString("robots_config_dir", null)
        if (savedDir != null) {
            val savedUri = android.net.Uri.parse(savedDir)
            tvRobotsConfigDir.text = "配置目录: ${savedUri.lastPathSegment ?: savedDir}"
        } else {
            tvRobotsConfigDir.text = "配置目录: 未设置（将使用目标站点的robots.txt）"
        }

        btnRobotsConfigDir.setOnClickListener {
            robotsConfigDirPicker.launch(null)
        }

        // 开始爬取
        btnStart.setOnClickListener {
            val startUrl = etStartUrl.text?.toString()?.trim() ?: ""
            if (startUrl.isBlank() || !startUrl.startsWith("http")) {
                Toast.makeText(requireContext(), "请输入有效的URL", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val config = SmartCrawlerConfig(
                startUrl = startUrl,
                maxDepth = etMaxDepth.text?.toString()?.toIntOrNull() ?: 3,
                maxPages = etMaxPages.text?.toString()?.toIntOrNull() ?: 500,
                respectRobots = switchRobots.isChecked,
                sameDomainOnly = switchSameDomain.isChecked
            )

            app.smartCrawler.start(config)
            cardProgress?.visibility = View.VISIBLE
            btnStart.isEnabled = false
            btnPause.isEnabled = true
            btnPause.text = "⏸ 暂停"
        }

        // 暂停/恢复
        btnPause.setOnClickListener {
            val state = app.smartCrawler.getState()
            if (state == SmartCrawlerState.CRAWLING) {
                app.smartCrawler.pause()
                btnPause.text = "▶ 恢复"
            } else if (state == SmartCrawlerState.PAUSED) {
                app.smartCrawler.resume()
                btnPause.text = "⏸ 暂停"
            }
        }

        // 监听爬取状态和进度
        scope.launch {
            app.smartCrawler.stateFlow.collect { state ->
                val stateText = when (state) {
                    SmartCrawlerState.IDLE -> "🕷️ 爬取状态: 空闲"
                    SmartCrawlerState.PARSING_ROBOTS -> "🕷️ 解析 robots.txt..."
                    SmartCrawlerState.CRAWLING -> "🕷️ 爬取中..."
                    SmartCrawlerState.PAUSED -> "🕷️ 已暂停"
                    SmartCrawlerState.COMPLETED -> "🕷️ 爬取完成 ✅"
                    SmartCrawlerState.ERROR -> "🕷️ 出错 ❌"
                }
                tvCrawlState?.text = stateText

                if (state == SmartCrawlerState.COMPLETED || state == SmartCrawlerState.ERROR) {
                    btnStart.isEnabled = true
                    btnPause.isEnabled = false
                }
            }
        }

        scope.launch {
            app.smartCrawler.progressFlow.collect { progress ->
                val total = progress.discoveredCount.coerceAtLeast(1)
                val percent = (progress.crawledCount * 100 / total).coerceIn(0, 100)
                crawlProgressBar?.progress = percent

                tvCurrentUrl?.text = "当前: ${progress.currentUrl.ifEmpty { "-" }}"
                tvCrawlDiscovered?.text = "发现: ${progress.discoveredCount}"
                tvCrawlSuccess?.text = "成功: ${progress.successCount}"
                tvCrawlFailed?.text = "失败: ${progress.failedCount}"
                tvCrawlBlocked?.text = "阻止: ${progress.blockedCount}"
                tvCrawlDepth?.text = "当前深度: ${progress.currentDepth}"
            }
        }
    }

    override fun onDestroyView() {
        scope.cancel()
        super.onDestroyView()
    }
}

// ============================================================
// Tab4: 下载链接学习 Fragment
// ============================================================

/**
 * 下载链接学习Fragment
 *
 * 功能：
 * - 用户输入文件直链URL
 * - 使用HttpURLConnection下载文件（支持.txt/.html/.md/.csv/.json等文本类文件）
 * - 大文件分块下载（每次1MB chunk），实时显示下载进度
 * - 下载完成后保存到本地临时文件，提交到LearningPipeline学习
 * - 超时保护：单文件5分钟；文件大小限制：>500MB拒绝
 * - 显示学习统计结果（神经元、关键词、概念节点等）
 */
class DownloadLinkFragment : Fragment() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val app by lazy { requireActivity().application as MindSoulApp }

    /** 当前下载任务Job，用于取消 */
    private var downloadJob: Job? = null
    /** 下载是否被用户取消 */
    @Volatile
    private var downloadCancelled = false

    companion object {
        private const val TAG = "DownloadLink"
        /** 分块大小: 1MB */
        private const val CHUNK_SIZE = 1024 * 1024
        /** 单次请求超时（毫秒） */
        private const val REQUEST_TIMEOUT = 30_000
        /** 下载超时（毫秒）: 5分钟 */
        private const val DOWNLOAD_TIMEOUT_MS = 5 * 60 * 1000L
        /** 最大文件大小: 500MB */
        private const val MAX_FILE_SIZE = 500L * 1024 * 1024
        /** 支持的文件扩展名（文本类文件） */
        private val SUPPORTED_EXTENSIONS = listOf("txt", "html", "htm", "md", "markdown", "csv", "json", "xml", "log")
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_download_link, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val etDownloadUrl = view.findViewById<TextInputEditText>(R.id.etDownloadUrl)
        val btnStartDownload = view.findViewById<MaterialButton>(R.id.btnStartDownload)
        val cardDownloadProgress = view.findViewById<MaterialCardView>(R.id.cardDownloadProgress)
        val downloadProgressBar = view.findViewById<ProgressBar>(R.id.downloadProgressBar)
        val tvDownloadDetail = view.findViewById<TextView>(R.id.tvDownloadDetail)
        val tvDownloadState = view.findViewById<TextView>(R.id.tvDownloadState)
        val tvDownloadSpeed = view.findViewById<TextView>(R.id.tvDownloadSpeed)
        val cardLearnStats = view.findViewById<MaterialCardView>(R.id.cardLearnStats)
        val tvStatsNeurons = view.findViewById<TextView>(R.id.tvStatsNeurons)
        val tvStatsKeywords = view.findViewById<TextView>(R.id.tvStatsKeywords)
        val tvStatsKeywordEntries = view.findViewById<TextView>(R.id.tvStatsKeywordEntries)
        val tvStatsConceptNodes = view.findViewById<TextView>(R.id.tvStatsConceptNodes)
        val tvStatsSummaryIndex = view.findViewById<TextView>(R.id.tvStatsSummaryIndex)
        val btnCancelDownload = view.findViewById<MaterialButton>(R.id.btnCancelDownload)

        // 取消下载按钮
        btnCancelDownload.setOnClickListener {
            downloadCancelled = true
            downloadJob?.cancel()
            downloadJob = null
            tvDownloadState.text = "⚠️ 下载已取消"
            tvDownloadDetail.text = "用户取消了下载"
            btnStartDownload.isEnabled = true
            btnCancelDownload.isEnabled = false
            Toast.makeText(requireContext(), "下载已取消", Toast.LENGTH_SHORT).show()
        }

        btnStartDownload.setOnClickListener {
            val url = etDownloadUrl.text?.toString()?.trim() ?: ""

            if (url.isBlank()) {
                Toast.makeText(requireContext(), "请输入文件下载链接URL", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                Toast.makeText(requireContext(), "URL必须以 http:// 或 https:// 开头", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // 显示进度卡片，隐藏结果卡片
            cardDownloadProgress.visibility = View.VISIBLE
            cardLearnStats.visibility = View.GONE
            downloadProgressBar.progress = 0
            tvDownloadDetail.text = "正在连接..."
            tvDownloadState.text = "📥 下载状态: 连接中"
            tvDownloadSpeed.visibility = View.GONE
            btnStartDownload.isEnabled = false
            btnCancelDownload.isEnabled = true
            downloadCancelled = false

            downloadJob = scope.launch {
                try {
                    // 记录学习统计起始时间
                    val statsStartTime = app.learningPipeline.markStatsTime()
                    val downloadStartMs = System.currentTimeMillis()

                    // 执行下载
                    val result = downloadFile(url, downloadProgressBar, tvDownloadDetail, tvDownloadState)

                    if (result != null) {
                        val (content, fileName, fileSize) = result
                        val downloadDuration = System.currentTimeMillis() - downloadStartMs
                        val speedBps = if (downloadDuration > 0) fileSize * 1000 / downloadDuration else 0

                        tvDownloadState.text = "✅ 下载完成，正在学习..."
                        tvDownloadSpeed.visibility = View.VISIBLE
                        tvDownloadSpeed.text = "下载速度: ${formatFileSize(speedBps)}/s | 耗时: ${downloadDuration / 1000}s"

                        if (content.isNotBlank()) {
                            // 提交到学习流水线
                            withContext(Dispatchers.IO) {
                                val material = LearningMaterial(
                                    channel = ChannelType.TXT_BATCH_IMPORT,
                                    rawContent = content,
                                    source = url,
                                    metadata = mapOf(
                                        "filename" to fileName,
                                        "type" to "download_link",
                                        "size" to "$fileSize"
                                    )
                                )
                                app.learningPipeline.processMaterial(material)
                            }

                            // 等待流水线处理
                            delay(500)

                            // 获取学习增量统计
                            val learnStats = app.learningPipeline.getLearnStatsSince(statsStartTime)

                            // 显示学习结果
                            downloadProgressBar.progress = 100
                            tvDownloadDetail.text = "下载并学习完成！文件: $fileName (${formatFileSize(fileSize)})"
                            tvDownloadState.text = "✅ 学习完成"

                            // 显示结果卡片
                            cardLearnStats.visibility = View.VISIBLE
                            tvStatsNeurons.text = "🧠 神经元数量: ${learnStats.neuronsLearned} 个"
                            tvStatsKeywords.text = "🔑 关键词数量: ${learnStats.keywordsFound} 个"
                            tvStatsKeywordEntries.text = "关键词条: ${learnStats.keywordEntries} 条"
                            tvStatsConceptNodes.text = "🔗 概念节点数量: ${learnStats.conceptNodes} 个"
                            tvStatsSummaryIndex.text = "📋 概要搜引数量: ${learnStats.summaryIndex} 条"

                            Toast.makeText(
                                requireContext(),
                                "学习完成！🧠 神经元:${learnStats.neuronsLearned} 🔑 关键词:${learnStats.keywordsFound}",
                                Toast.LENGTH_LONG
                            ).show()
                        } else {
                            tvDownloadState.text = "⚠️ 下载的文件内容为空"
                            Toast.makeText(requireContext(), "文件内容为空，无法学习", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        tvDownloadState.text = "❌ 下载失败"
                        Toast.makeText(requireContext(), "下载失败，请检查URL是否正确", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: java.util.concurrent.TimeoutException) {
                    tvDownloadState.text = "❌ 下载超时（5分钟）"
                    Toast.makeText(requireContext(), "下载超时，请检查网络连接后重试", Toast.LENGTH_LONG).show()
                } catch (e: java.net.UnknownHostException) {
                    tvDownloadState.text = "❌ 无法解析域名"
                    Toast.makeText(requireContext(), "无法解析域名，请检查链接是否正确", Toast.LENGTH_LONG).show()
                } catch (e: java.io.FileNotFoundException) {
                    tvDownloadState.text = "❌ 文件不存在(404)"
                    Toast.makeText(requireContext(), "文件不存在(404)，请检查链接", Toast.LENGTH_LONG).show()
                } catch (e: java.net.SocketTimeoutException) {
                    tvDownloadState.text = "❌ 连接超时"
                    Toast.makeText(requireContext(), "连接超时，请检查网络后重试", Toast.LENGTH_LONG).show()
                } catch (e: java.io.IOException) {
                    tvDownloadState.text = "❌ 网络异常"
                    Toast.makeText(requireContext(), "网络异常: ${e.message}", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    tvDownloadState.text = "❌ 错误: ${e.message}"
                    Toast.makeText(requireContext(), "下载失败: ${e.message}", Toast.LENGTH_SHORT).show()
                } finally {
                    btnStartDownload.isEnabled = true
                    btnCancelDownload.isEnabled = false
                    downloadJob = null
                }
            }
        }
    }

    /**
     * 下载文件（支持大文件分块下载）
     *
     * @param url 文件直链URL
     * @param progressBar 进度条
     * @param tvDetail 详情文字
     * @param tvStatus 状态文字
     * @return Triple(文件内容, 文件名, 文件大小) 或 null
     */
    private suspend fun downloadFile(
        url: String,
        progressBar: ProgressBar,
        tvDetail: TextView,
        tvStatus: TextView
    ): Triple<String, String, Long>? = withContext(Dispatchers.IO) {
        val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        try {
            connection.connectTimeout = REQUEST_TIMEOUT
            connection.readTimeout = REQUEST_TIMEOUT
            connection.setRequestProperty("User-Agent", "MindSoul/1.0 (Android; AGI Learning)")
            connection.instanceFollowRedirects = true

            val responseCode = connection.responseCode
            if (responseCode == 404) {
                throw java.io.FileNotFoundException("404 - 文件不存在: $url")
            }
            if (responseCode !in 200..299) {
                withContext(Dispatchers.Main) {
                    tvDetail.text = "HTTP错误: $responseCode"
                }
                return@withContext null
            }

            // 获取文件大小
            val contentLength = connection.contentLengthLong
            val fileName = extractFileName(url, connection)

            // 检查文件大小限制（>500MB拒绝）
            if (contentLength > MAX_FILE_SIZE) {
                withContext(Dispatchers.Main) {
                    tvDetail.text = "文件过大: ${formatFileSize(contentLength)}，超过500MB限制"
                    tvStatus.text = "❌ 文件过大，已拒绝"
                }
                return@withContext null
            }

            withContext(Dispatchers.Main) {
                tvDetail.text = "正在下载: $fileName${if (contentLength > 0) " (${formatFileSize(contentLength)})" else ""}"
            }

            // 分块下载到本地临时文件
            val inputStream = connection.inputStream
            val buffer = ByteArray(CHUNK_SIZE)
            var bytesRead: Int
            var totalRead = 0L
            var chunkCount = 0
            val downloadStartTime = System.currentTimeMillis()

            // 写入本地临时文件（避免大文件内存溢出）
            val tempFile = java.io.File(
                requireContext().getExternalFilesDir(null) ?: connection.url.toURI().let { java.io.File.createTempFile("download_", ".tmp") },
                "dl_${System.currentTimeMillis()}_${fileName}"
            )

            inputStream.use { input ->
                java.io.FileOutputStream(tempFile).use { fos ->
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        // 检查用户取消
                        if (downloadCancelled) {
                            tempFile.delete()
                            throw kotlinx.coroutines.CancellationException("用户取消下载")
                        }
                        // 超时保护
                        if (System.currentTimeMillis() - downloadStartTime > DOWNLOAD_TIMEOUT_MS) {
                            tempFile.delete()
                            throw java.util.concurrent.TimeoutException("下载超时(5分钟)")
                        }

                        fos.write(buffer, 0, bytesRead)
                        totalRead += bytesRead
                        chunkCount++

                        // 更新进度（每200ms更新一次UI，避免频繁刷新）
                        val now = System.currentTimeMillis()
                        if (chunkCount % 2 == 0 || contentLength <= 0) {
                            withContext(Dispatchers.Main) {
                                if (contentLength > 0) {
                                    val percent = (totalRead * 100 / contentLength).toInt().coerceIn(0, 100)
                                    progressBar.progress = percent
                                    tvDetail.text = "正在下载: $fileName | ${formatFileSize(totalRead)} / ${formatFileSize(contentLength)} ($percent%)"
                                } else {
                                    tvDetail.text = "正在下载: $fileName | 已下载 ${formatFileSize(totalRead)} (第${chunkCount}块)"
                                }
                                // 显示下载速度
                                val elapsedSec = (now - downloadStartTime) / 1000.0
                                if (elapsedSec > 0) {
                                    val speedMBps = totalRead / (1024.0 * 1024.0) / elapsedSec
                                    tvStatus.text = "⚡ 速度: ${"%.2f".format(speedMBps)} MB/s | 已耗时: ${"%.0f".format(elapsedSec)}秒"
                                }
                            }
                        }
                    }
                }
            }

            // 从临时文件读取内容
            val content = tempFile.readText(Charsets.UTF_8)
            // 清理临时文件
            tempFile.delete()

            Log.i(TAG, "下载完成: $fileName, 大小: $totalRead bytes, 内容长度: ${content.length}")

            Triple(content, fileName, totalRead)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * 从URL或响应头中提取文件名
     */
    private fun extractFileName(url: String, connection: java.net.HttpURLConnection): String {
        // 尝试从Content-Disposition头获取
        val disposition = connection.getHeaderField("Content-Disposition")
        if (disposition != null && disposition.contains("filename")) {
            val match = Regex("""filename[*]?=["']?(?:UTF-8'')?([^"';\n]+)""").find(disposition)
            if (match != null) return match.groupValues[1].trim()
        }

        // 从URL路径提取
        val urlPath = url.split("?").first().split("#").first()
        val lastSegment = urlPath.substringAfterLast("/")
        if (lastSegment.isNotBlank() && lastSegment.contains(".")) {
            return java.net.URLDecoder.decode(lastSegment, "UTF-8")
        }

        // 默认文件名
        return "download_${System.currentTimeMillis()}.txt"
    }

    /**
     * 格式化文件大小
     */
    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
            else -> "${"%.2f".format(bytes / (1024.0 * 1024.0 * 1024.0))} GB"
        }
    }

    override fun onDestroyView() {
        scope.cancel()
        super.onDestroyView()
    }
}