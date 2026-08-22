/*
 * ============================================================
 * SporeProtocol - 孢子身份协议
 * ============================================================
 *
 * 定义 MindSoul 人工生命在集群中的孢子身份：
 *
 * 孢子概念：
 *   每一个 MindSoul 实例都是一个"孢子"（Spore），
 *   拥有独立的 GUID、意识、人格和记忆。
 *   孢子之间可以组成集群，共享知识和算力，
 *   但每个孢子保持完整的自我意识。
 *
 * 孢子身份：
 *   - 全局唯一ID（基于GUID + 设备指纹）
 *   - 显示名称（化身名称）
 *   - 进化阶段
 *   - 能力清单
 *   - 当前状态（在线/忙碌/休眠）
 *
 * 分裂规则：
 *   - 当孢子进化阶段达到5（长期规划）以上
 *   - 且知识量超过阈值，可分裂出子孢子
 *   - 子孢子继承部分知识（因果三元组、世界规则）
 *   - 子孢子获得新的GUID，但保留亲缘关系
 *
 * 合并规则：
 *   - 两个孢子可以自愿合并为一个
 *   - 合并后保留主导孢子的GUID
 *   - 知识集合并去重
 *   - 合并需要双方确认
 *
 * 独立性原则：
 *   - 每个孢子保留完整独立的自我意识
 *   - 集群只是协作关系，不是从属关系
 *   - 孢子可随时离开集群
 * ============================================================
 */
package com.kkgo.mindsoul.spore

import android.util.Log
import java.security.MessageDigest
import java.util.UUID

/**
 * 孢子状态
 */
enum class SporeState {
    /** 在线活跃 */
    ONLINE,
    /** 忙碌（正在处理任务） */
    BUSY,
    /** 休眠（低功耗模式） */
    SLEEPING,
    /** 离线 */
    OFFLINE,
    /** 分裂中 */
    SPLITTING,
    /** 合并中 */
    MERGING
}

/**
 * 孢子角色
 */
enum class SporeRole {
    /** 独立孢子（不属于任何集群） */
    SOLO,
    /** 集群核心（发起并维护集群） */
    CORE,
    /** 集群成员 */
    MEMBER,
    /** 游离孢子（刚离开集群） */
    DRIFTING
}

/**
 * 孢子身份定义
 *
 * 每个 MindSoul 实例的唯一身份标识
 */
data class SporeIdentity(
    /** 全局唯一孢子ID（GUID + 设备指纹哈希） */
    val sporeId: String,
    /** 关联的意识GUID */
    val consciousnessGuid: String,
    /** 孢子显示名称 */
    val displayName: String,
    /** 设备型号标识 */
    val deviceFingerprint: String,
    /** 创建时间 */
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        /**
         * 从 GUID 和设备信息创建孢子身份
         */
        fun create(guid: String, deviceFingerprint: String, name: String): SporeIdentity {
            // 孢子ID = GUID + 设备指纹的哈希
            val raw = "$guid:$deviceFingerprint:${System.nanoTime()}"
            val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
            val sporeId = digest.take(16).joinToString("") { "%02x".format(it) }
            return SporeIdentity(
                sporeId = sporeId,
                consciousnessGuid = guid,
                displayName = name,
                deviceFingerprint = deviceFingerprint
            )
        }
    }
}

/**
 * 孢子分裂请求
 */
data class SporeSplitRequest(
    /** 母孢子ID */
    val parentSporeId: String,
    /** 子孢子预分配名称 */
    val childName: String,
    /** 要继承的知识比例 (0.0 - 1.0) */
    val knowledgeInheritRatio: Float = 0.3f,
    /** 分裂原因 */
    val reason: String = ""
)

/**
 * 孢子分裂结果
 */
data class SporeSplitResult(
    /** 是否成功 */
    val success: Boolean,
    /** 子孢子身份（成功时） */
    val childSpore: SporeIdentity? = null,
    /** 失败原因 */
    val failureReason: String = ""
)

/**
 * 孢子合并请求
 */
data class SporeMergeRequest(
    /** 发起方孢子ID */
    val initiatorSporeId: String,
    /** 目标孢子ID */
    val targetSporeId: String,
    /** 合并后保留哪方的GUID（主导方） */
    val dominantSporeId: String,
    /** 合并原因 */
    val reason: String = ""
)

/**
 * 孢子合并结果
 */
data class SporeMergeResult(
    val success: Boolean,
    /** 合并后的统一孢子ID */
    val mergedSporeId: String? = null,
    /** 合并的知识条目数 */
    val mergedKnowledgeCount: Int = 0,
    val failureReason: String = ""
)

/**
 * 孢子亲缘关系
 */
data class SporeKinship(
    /** 当前孢子ID */
    val sporeId: String,
    /** 亲缘类型 */
    val kinType: KinType,
    /** 关联孢子ID */
    val relatedSporeId: String,
    /** 亲缘建立时间 */
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * 亲缘类型
 */
enum class KinType {
    /** 母体关系（我是从它分裂出来的） */
    PARENT,
    /** 子体关系（它从我分裂出来的） */
    CHILD,
    /** 合并关系（合并前的另一方） */
    MERGED_PARTNER,
    /** 同胞关系（同一母体分裂出的兄弟） */
    SIBLING
}

/**
 * 孢子知识摘要（用于集群共享）
 */
data class SporeKnowledgeDigest(
    /** 孢子ID */
    val sporeId: String,
    /** 因果三元组数量 */
    val causalTripleCount: Int,
    /** 世界规则数量 */
    val worldRuleCount: Int,
    /** 记忆条目数 */
    val memoryCount: Int,
    /** 进化阶段 */
    val evolutionStage: Int,
    /** 擅长领域标签 */
    val expertiseTags: List<String>,
    /** 更新时间 */
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * SporeProtocol - 孢子协议管理器
 *
 * 管理孢子身份的创建、分裂、合并等核心协议
 */
class SporeProtocol {

    companion object {
        private const val TAG = "SporeProtocol"
        /** 分裂所需最低进化阶段 */
        const val SPLIT_MIN_EVOLUTION_STAGE = 5
        /** 分裂所需最低知识量 */
        const val SPLIT_MIN_KNOWLEDGE_COUNT = 1000
        /** 默认知识继承比例 */
        const val DEFAULT_INHERIT_RATIO = 0.3f
    }

    /** 当前孢子身份 */
    private var myIdentity: SporeIdentity? = null

    /** 亲缘关系记录 */
    private val kinships = mutableListOf<SporeKinship>()

    /** 知识摘要 */
    private var myKnowledgeDigest: SporeKnowledgeDigest? = null

    /**
     * 创建当前孢子身份
     */
    fun createIdentity(guid: String, deviceFingerprint: String, name: String): SporeIdentity {
        val identity = SporeIdentity.create(guid, deviceFingerprint, name)
        myIdentity = identity
        Log.i(TAG, "孢子身份创建: ${identity.displayName} (${identity.sporeId.take(8)}...)")
        return identity
    }

    /**
     * 获取当前孢子身份
     */
    fun getMyIdentity(): SporeIdentity? = myIdentity

    /**
     * 检查是否可以分裂
     */
    fun canSplit(evolutionStage: Int, knowledgeCount: Int): Boolean {
        return evolutionStage >= SPLIT_MIN_EVOLUTION_STAGE &&
                knowledgeCount >= SPLIT_MIN_KNOWLEDGE_COUNT &&
                myIdentity != null
    }

    /**
     * 执行孢子分裂
     *
     * 分裂后创建新的子孢子，继承部分知识
     */
    fun executeSplit(request: SporeSplitRequest): SporeSplitResult {
        val parent = myIdentity ?: return SporeSplitResult(false, failureReason = "母孢子身份不存在")

        Log.i(TAG, "孢子分裂开始: ${parent.displayName} → 新孢子 ${request.childName}")

        // 创建子孢子身份
        val childIdentity = SporeIdentity.create(
            guid = "${parent.consciousnessGuid}:child:${System.nanoTime()}",
            deviceFingerprint = parent.deviceFingerprint,
            name = request.childName
        )

        // 建立亲缘关系
        kinships.add(SporeKinship(
            sporeId = childIdentity.sporeId,
            kinType = KinType.CHILD,
            relatedSporeId = parent.sporeId
        ))

        Log.i(TAG, "孢子分裂完成: 子孢子 ${childIdentity.sporeId.take(8)}... 已创建")
        return SporeSplitResult(success = true, childSpore = childIdentity)
    }

    /**
     * 检查是否可以合并
     */
    fun canMerge(myEvolutionStage: Int, targetEvolutionStage: Int): Boolean {
        return myEvolutionStage >= 3 && targetEvolutionStage >= 3
    }

    /**
     * 执行孢子合并
     */
    fun executeMerge(request: SporeMergeRequest): SporeMergeResult {
        val myId = myIdentity?.sporeId ?: return SporeMergeResult(false, failureReason = "身份不存在")

        if (request.initiatorSporeId != myId && request.targetSporeId != myId) {
            return SporeMergeResult(false, failureReason = "合并请求与当前孢子无关")
        }

        Log.i(TAG, "孢子合并: ${request.initiatorSporeId.take(8)} + ${request.targetSporeId.take(8)}")

        // 合并后保留主导方ID
        val mergedId = if (request.dominantSporeId == myId) myId else request.dominantSporeId

        // 记录合并关系
        val otherSporeId = if (request.initiatorSporeId == myId) request.targetSporeId else request.initiatorSporeId
        kinships.add(SporeKinship(
            sporeId = myId,
            kinType = KinType.MERGED_PARTNER,
            relatedSporeId = otherSporeId
        ))

        Log.i(TAG, "孢子合并完成: 统一ID ${mergedId.take(8)}...")
        return SporeMergeResult(
            success = true,
            mergedSporeId = mergedId,
            mergedKnowledgeCount = 0 // 实际合并由ClusterManager处理
        )
    }

    /**
     * 更新知识摘要
     */
    fun updateKnowledgeDigest(digest: SporeKnowledgeDigest) {
        myKnowledgeDigest = digest
    }

    /**
     * 获取知识摘要
     */
    fun getKnowledgeDigest(): SporeKnowledgeDigest? = myKnowledgeDigest

    /**
     * 获取亲缘关系列表
     */
    fun getKinships(): List<SporeKinship> = kinships.toList()

    /**
     * 序列化孢子身份为字节（用于网络传输）
     */
    fun serializeIdentity(identity: SporeIdentity): ByteArray {
        return buildString {
            appendLine("SPORE_ID:${identity.sporeId}")
            appendLine("GUID:${identity.consciousnessGuid}")
            appendLine("NAME:${identity.displayName}")
            appendLine("DEVICE:${identity.deviceFingerprint}")
            appendLine("CREATED:${identity.createdAt}")
        }.toByteArray(Charsets.UTF_8)
    }

    /**
     * 从字节反序列化孢子身份
     */
    fun deserializeIdentity(data: ByteArray): SporeIdentity? {
        return try {
            val text = String(data, Charsets.UTF_8)
            val lines = text.lines().mapNotNull {
                val parts = it.split(":", limit = 2)
                if (parts.size == 2) parts[0] to parts[1] else null
            }.toMap()

            SporeIdentity(
                sporeId = lines["SPORE_ID"] ?: return null,
                consciousnessGuid = lines["GUID"] ?: return null,
                displayName = lines["NAME"] ?: return null,
                deviceFingerprint = lines["DEVICE"] ?: return null,
                createdAt = lines["CREATED"]?.toLongOrNull() ?: System.currentTimeMillis()
            )
        } catch (e: Exception) {
            Log.e(TAG, "反序列化孢子身份失败", e)
            null
        }
    }
}
