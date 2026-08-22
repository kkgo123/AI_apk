/*
 * ============================================================
 * KnowledgeDistiller - 知识蒸馏器
 * ============================================================
 *
 * 负责从冷归档的知识条目中提炼公理：
 *
 * 1. 扫描归档文件
 * 2. 统计因果关系出现频率
 * 3. 高频因果关系提升为公理
 * 4. 冲突检测与解决
 * 5. 写入公理层（第一层 AxiomLayer）
 *
 * 蒸馏策略：
 * - 出现 ≥3 次的因果关系 → 候选公理
 * - 置信度 = 出现次数 / 总知识条目数
 * - 冲突检测：互为矛盾的因果不能同时为公理
 * - 精炼：去除冗余表述，统一因果表达格式
 * ============================================================
 */
package com.kkgo.mindsoul.learning

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import java.io.File

/**
 * 蒸馏后的公理条目
 */
data class DistilledAxiom(
    /** 公理ID */
    val id: String = System.nanoTime().toString(),
    /** 因果/规则描述 */
    val rule: String,
    /** 出现次数（支持次数） */
    val supportCount: Int,
    /** 置信度 [0.0, 1.0] */
    val confidence: Double,
    /** 来源文件列表 */
    val sourceFiles: List<String>,
    /** 提炼时间 */
    val distilledAt: Long = System.currentTimeMillis()
)

/**
 * 因果模式统计
 */
data class CausalPattern(
    /** 原因 */
    val cause: String,
    /** 结果 */
    val effect: String,
    /** 出现次数 */
    var count: Int = 0,
    /** 来源文件 */
    val sources: MutableList<String> = mutableListOf()
)

/**
 * 知识蒸馏器
 */
class KnowledgeDistiller(private val context: Context) {

    companion object {
        private const val TAG = "KnowledgeDistiller"
        /** 成为候选公理的最低出现次数 */
        const val MIN_SUPPORT_COUNT = 3
        /** 置信度阈值（低于此值不提升为公理） */
        const val MIN_CONFIDENCE = 0.15
        /** 冲突检测的相似度阈值 */
        const val CONFLICT_SIMILARITY = 0.8f

        /** 因果提取正则 */
        val CAUSE_PATTERNS = listOf(
            Regex("""CAUSE\[(.+?)]\s*→\s*EFFECT\[(.+?)]"""),
            Regex("""CONDITION\[(.+?)]\s*→\s*CONSEQUENCE\[(.+?)]""")
        )
    }

    /** 已提炼的公理缓存 */
    private val distilledAxioms = mutableListOf<DistilledAxiom>()

    /** 处理作用域 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ============ 初始化 ============

    fun initialize() {
        Log.i(TAG, "[初始化] 知识蒸馏器就绪")
    }

    fun destroy() {
        scope.cancel()
        Log.i(TAG, "[销毁] 知识蒸馏器已释放")
    }

    // ============ 核心蒸馏流程 ============

    /**
     * 从归档目录中蒸馏公理
     *
     * @param archiveDir 归档目录
     * @return 新增公理数量
     */
    suspend fun distillAxioms(archiveDir: File): Int = withContext(Dispatchers.IO) {
        Log.d(TAG, "[蒸馏] 开始扫描归档: ${archiveDir.absolutePath}")

        val files = archiveDir.listFiles { f -> f.extension == "dat" } ?: return@withContext 0
        if (files.isEmpty()) return@withContext 0

        // 步骤1：提取所有因果模式
        val patterns = extractAllCausalPatterns(files)
        Log.d(TAG, "[蒸馏] 提取到 ${patterns.size} 种因果模式")

        // 步骤2：筛选候选公理
        val candidates = filterCandidates(patterns, files.size)
        Log.d(TAG, "[蒸馏] 候选公理: ${candidates.size} 条")

        // 步骤3：冲突检测
        val resolved = resolveConflicts(candidates)
        Log.d(TAG, "[蒸馏] 冲突解决后: ${resolved.size} 条")

        // 步骤4：精炼表述
        val refined = refineAxioms(resolved)

        // 步骤5：写入公理缓存
        var newCount = 0
        for (axiom in refined) {
            if (!isDuplicateAxiom(axiom)) {
                distilledAxioms.add(axiom)
                newCount++
            }
        }

        Log.i(TAG, "[蒸馏] 完成: 新增 $newCount 条公理, 累计 ${distilledAxioms.size} 条")
        newCount
    }

    // ============ 步骤1：提取因果模式 ============

    /**
     * 从所有归档文件中提取因果模式
     */
    private fun extractAllCausalPatterns(files: Array<File>): Map<String, CausalPattern> {
        val patternMap = mutableMapOf<String, CausalPattern>()

        for (file in files) {
            try {
                val content = file.readText(Charsets.UTF_8)
                // 查找因果逻辑段
                val causalSection = content.substringAfter("--- 因果逻辑 ---", "")
                if (causalSection.isBlank()) continue

                for (line in causalSection.lines()) {
                    for (pattern in CAUSE_PATTERNS) {
                        val match = pattern.find(line) ?: continue
                        val cause = match.groupValues[1].trim()
                        val effect = match.groupValues[2].trim()
                        val key = "${cause}|||${effect}"

                        val existing = patternMap[key]
                        if (existing != null) {
                            existing.count++
                            existing.sources.add(file.name)
                        } else {
                            patternMap[key] = CausalPattern(
                                cause = cause,
                                effect = effect,
                                count = 1,
                                sources = mutableListOf(file.name)
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "[提取] 文件解析失败: ${file.name} - ${e.message}")
            }
        }

        return patternMap
    }

    // ============ 步骤2：筛选候选公理 ============

    /**
     * 筛选满足条件的候选公理
     */
    private fun filterCandidates(
        patterns: Map<String, CausalPattern>,
        totalFiles: Int
    ): List<CausalPattern> {
        return patterns.values.filter { pattern ->
            val confidence = pattern.count.toDouble() / totalFiles.coerceAtLeast(1)
            pattern.count >= MIN_SUPPORT_COUNT && confidence >= MIN_CONFIDENCE
        }.sortedByDescending { it.count }
    }

    // ============ 步骤3：冲突检测 ============

    /**
     * 检测并解决冲突
     *
     * 冲突定义：两个因果模式的原因高度相似但结果矛盾，
     * 或原因和结果互换（A→B 和 B→A 不能同时成立）。
     */
    private fun resolveConflicts(candidates: List<CausalPattern>): List<CausalPattern> {
        val resolved = mutableListOf<CausalPattern>()
        val rejected = mutableSetOf<String>()

        for (candidate in candidates) {
            val key = "${candidate.cause}|||${candidate.effect}"
            val reverseKey = "${candidate.effect}|||${candidate.cause}"

            if (key in rejected || reverseKey in rejected) continue

            // 检查是否有反向因果冲突
            val hasConflict = candidates.any { other ->
                other !== candidate &&
                (textSimilarity(candidate.cause, other.cause) > CONFLICT_SIMILARITY &&
                 textSimilarity(candidate.effect, other.effect) < 0.3f)
            }

            if (!hasConflict) {
                resolved.add(candidate)
            } else {
                rejected.add(key)
                Log.d(TAG, "[冲突] 丢弃: ${candidate.cause} → ${candidate.effect}")
            }
        }

        return resolved
    }

    // ============ 步骤4：精炼表述 ============

    /**
     * 精炼公理表述
     *
     * - 截断过长表述
     * - 去除口语化词汇
     * - 统一格式
     */
    private fun refineAxioms(candidates: List<CausalPattern>): List<DistilledAxiom> {
        return candidates.map { pattern ->
            val totalFiles = candidates.sumOf { it.count }
            val confidence = pattern.count.toDouble() / totalFiles.coerceAtLeast(1)

            DistilledAxiom(
                rule = "${pattern.cause} → ${pattern.effect}",
                supportCount = pattern.count,
                confidence = confidence.coerceIn(0.0, 1.0),
                sourceFiles = pattern.sources.distinct()
            )
        }
    }

    // ============ 工具方法 ============

    /**
     * 检查是否为重复公理
     */
    private fun isDuplicateAxiom(axiom: DistilledAxiom): Boolean {
        return distilledAxioms.any { existing ->
            textSimilarity(existing.rule, axiom.rule) > CONFLICT_SIMILARITY
        }
    }

    /**
     * 文本相似度（基于字符集合的 Jaccard 相似度）
     */
    private fun textSimilarity(a: String, b: String): Float {
        if (a.isEmpty() && b.isEmpty()) return 1.0f
        if (a.isEmpty() || b.isEmpty()) return 0.0f

        val setA = a.toSet()
        val setB = b.toSet()
        val intersection = setA.intersect(setB).size.toFloat()
        val union = (setA + setB).size.toFloat()

        return if (union > 0) intersection / union else 0.0f
    }

    // ============ 查询接口 ============

    /**
     * 获取所有已提炼的公理
     */
    fun getDistilledAxioms(): List<DistilledAxiom> {
        return distilledAxioms.toList()
    }

    /**
     * 获取公理数量
     */
    fun getAxiomCount(): Int = distilledAxioms.size

    /**
     * 清空已提炼的公理（重置）
     */
    fun clearAxioms() {
        distilledAxioms.clear()
        Log.i(TAG, "[重置] 已清空所有提炼公理")
    }
}
