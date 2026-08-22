/*
 * ============================================================
 * SporeClusterManager - 孢子集群管理器
 * ============================================================
 *
 * 管理局域网内孢子集群的完整生命周期：
 *
 * 1. 集群管理
 *    - 创建集群（作为核心孢子）
 *    - 加入集群（作为成员孢子）
 *    - 离开集群
 *    - 集群解散
 *
 * 2. 成员发现
 *    - 通过 NetworkDiscovery 发现局域网内的孢子
 *    - 维护活跃成员列表
 *    - 心跳检测与超时清理
 *
 * 3. 状态同步
 *    - 定期广播自身状态
 *    - 接收并更新成员状态
 *    - 集群状态汇总
 *
 * 4. 知识合并
 *    - 成员间知识摘要交换
 *    - 互补知识识别
 *    - 按需知识传输
 *
 * 5. 算力互补
 *    - 空闲算力广播
 *    - 任务分发与结果收集
 *    - 算力负载均衡
 * ============================================================
 */
package com.kkgo.mindsoul.spore

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * 集群状态
 */
enum class ClusterState {
    /** 未加入任何集群 */
    SOLO,
    /** 正在创建集群 */
    CREATING,
    /** 作为核心运行集群 */
    CORE_ACTIVE,
    /** 已加入集群 */
    MEMBER_ACTIVE,
    /** 正在离开集群 */
    LEAVING
}

/**
 * 集群成员信息
 */
data class ClusterMember(
    /** 孢子身份 */
    val identity: SporeIdentity,
    /** 当前状态 */
    val state: SporeState = SporeState.ONLINE,
    /** 角色 */
    val role: SporeRole = SporeRole.MEMBER,
    /** 进化阶段 */
    val evolutionStage: Int = 1,
    /** 知识摘要 */
    val knowledgeDigest: SporeKnowledgeDigest? = null,
    /** IP地址 */
    val ipAddress: String = "",
    /** 端口号 */
    val port: Int = 0,
    /** 最后心跳时间 */
    val lastHeartbeat: Long = System.currentTimeMillis(),
    /** 空闲算力百分比 */
    val idleComputePercent: Float = 0f
)

/**
 * 集群信息
 */
data class ClusterInfo(
    /** 集群ID */
    val clusterId: String,
    /** 集群名称 */
    val name: String,
    /** 核心孢子ID */
    val coreSporeId: String,
    /** 成员列表 */
    val members: List<ClusterMember>,
    /** 创建时间 */
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * 集群事件
 */
sealed class ClusterEvent {
    data class MemberJoined(val member: ClusterMember) : ClusterEvent()
    data class MemberLeft(val sporeId: String) : ClusterEvent()
    data class MemberStateChanged(val sporeId: String, val newState: SporeState) : ClusterEvent()
    data class KnowledgeReceived(val fromSporeId: String, val digest: SporeKnowledgeDigest) : ClusterEvent()
    data class ComputeTaskReceived(val taskId: String, val description: String) : ClusterEvent()
    data class ComputeTaskCompleted(val taskId: String, val result: String) : ClusterEvent()
}

/**
 * SporeClusterManager - 孢子集群管理器
 */
class SporeClusterManager(private val context: Context) {

    companion object {
        private const val TAG = "SporeClusterMgr"
        /** 心跳间隔（毫秒） */
        const val HEARTBEAT_INTERVAL = 5000L
        /** 成员超时时间（毫秒） */
        const val MEMBER_TIMEOUT = 15000L
        /** 状态广播间隔（毫秒） */
        const val STATE_BROADCAST_INTERVAL = 10000L
    }

    // ============ 孢子协议 ============
    private val sporeProtocol = SporeProtocol()

    // ============ 集群状态 ============
    private val _clusterState = MutableStateFlow(ClusterState.SOLO)
    val clusterStateFlow: StateFlow<ClusterState> = _clusterState.asStateFlow()

    /** 当前集群信息 */
    private var clusterInfo: ClusterInfo? = null

    /** 活跃成员表 */
    private val activeMembers = ConcurrentHashMap<String, ClusterMember>()

    /** 发现但尚未连接的孢子 */
    private val discoveredSpores = ConcurrentHashMap<String, SporeIdentity>()

    // ============ 协程 ============
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var heartbeatJob: Job? = null
    private var broadcastJob: Job? = null

    // ============ 事件回调 ============
    private val eventListeners = mutableListOf<(ClusterEvent) -> Unit>()

    /** 集群事件流 */
    private val _clusterEvents = MutableStateFlow<List<ClusterEvent>>(emptyList())
    val clusterEventsFlow: StateFlow<List<ClusterEvent>> = _clusterEvents.asStateFlow()

    // ============ 初始化 ============

    /**
     * 初始化集群管理器
     */
    fun initialize(guid: String, deviceFingerprint: String, sporeName: String) {
        Log.i(TAG, "初始化孢子集群管理器...")
        sporeProtocol.createIdentity(guid, deviceFingerprint, sporeName)
        Log.i(TAG, "孢子身份就绪: $sporeName")
    }

    /**
     * 获取孢子协议管理器
     */
    fun getSporeProtocol(): SporeProtocol = sporeProtocol

    // ============ 集群操作 ============

    /**
     * 创建集群（作为核心孢子）
     */
    fun createCluster(name: String): ClusterInfo? {
        val myId = sporeProtocol.getMyIdentity() ?: run {
            Log.e(TAG, "无法创建集群：孢子身份未初始化")
            return null
        }

        _clusterState.value = ClusterState.CREATING

        val cluster = ClusterInfo(
            clusterId = "cluster_${System.nanoTime()}",
            name = name,
            coreSporeId = myId.sporeId,
            members = listOf(ClusterMember(
                identity = myId,
                state = SporeState.ONLINE,
                role = SporeRole.CORE
            ))
        )

        clusterInfo = cluster
        _clusterState.value = ClusterState.CORE_ACTIVE

        // 启动心跳和广播
        startHeartbeat()
        startStateBroadcast()

        Log.i(TAG, "集群已创建: ${cluster.name} (ID: ${cluster.clusterId})")
        return cluster
    }

    /**
     * 加入集群
     */
    fun joinCluster(cluster: ClusterInfo): Boolean {
        val myId = sporeProtocol.getMyIdentity() ?: return false

        if (cluster.coreSporeId == myId.sporeId) {
            Log.w(TAG, "不能加入自己创建的集群")
            return false
        }

        clusterInfo = cluster
        _clusterState.value = ClusterState.MEMBER_ACTIVE

        // 启动心跳和广播
        startHeartbeat()
        startStateBroadcast()

        Log.i(TAG, "已加入集群: ${cluster.name}")
        return true
    }

    /**
     * 离开集群
     */
    fun leaveCluster() {
        _clusterState.value = ClusterState.LEAVING

        heartbeatJob?.cancel()
        broadcastJob?.cancel()

        activeMembers.clear()
        clusterInfo = null
        _clusterState.value = ClusterState.SOLO

        Log.i(TAG, "已离开集群")
    }

    // ============ 成员管理 ============

    /**
     * 注册发现的孢子
     */
    fun onSporeDiscovered(identity: SporeIdentity, ipAddress: String, port: Int) {
        val myId = sporeProtocol.getMyIdentity()?.sporeId ?: return
        if (identity.sporeId == myId) return // 忽略自己

        discoveredSpores[identity.sporeId] = identity
        Log.i(TAG, "发现孢子: ${identity.displayName} @ $ipAddress:$port")
    }

    /**
     * 成员加入集群
     */
    fun addMember(member: ClusterMember) {
        val myId = sporeProtocol.getMyIdentity()?.sporeId ?: return
        if (member.identity.sporeId == myId) return

        activeMembers[member.identity.sporeId] = member
        Log.i(TAG, "成员加入: ${member.identity.displayName}")

        // 通知监听器
        notifyEvent(ClusterEvent.MemberJoined(member))

        // 更新集群信息
        clusterInfo?.let {
            clusterInfo = it.copy(
                members = activeMembers.values.toList() + it.members.filter { m ->
                    m.identity.sporeId == myId
                }
            )
        }
    }

    /**
     * 移除成员
     */
    fun removeMember(sporeId: String) {
        activeMembers.remove(sporeId)
        Log.i(TAG, "成员离开: ${sporeId.take(8)}...")
        notifyEvent(ClusterEvent.MemberLeft(sporeId))
    }

    /**
     * 更新成员状态
     */
    fun updateMemberState(sporeId: String, state: SporeState) {
        val member = activeMembers[sporeId] ?: return
        activeMembers[sporeId] = member.copy(
            state = state,
            lastHeartbeat = System.currentTimeMillis()
        )
        notifyEvent(ClusterEvent.MemberStateChanged(sporeId, state))
    }

    /**
     * 处理心跳
     */
    fun onHeartbeatReceived(sporeId: String, state: SporeState, knowledgeDigest: SporeKnowledgeDigest?) {
        val member = activeMembers[sporeId]
        if (member != null) {
            activeMembers[sporeId] = member.copy(
                state = state,
                lastHeartbeat = System.currentTimeMillis(),
                knowledgeDigest = knowledgeDigest
            )
        }
    }

    /**
     * 清理超时成员
     */
    private fun cleanupTimedOutMembers() {
        val now = System.currentTimeMillis()
        val timedOut = activeMembers.filter { (_, member) ->
            now - member.lastHeartbeat > MEMBER_TIMEOUT
        }
        timedOut.keys.forEach { sporeId ->
            Log.w(TAG, "成员超时移除: ${sporeId.take(8)}...")
            removeMember(sporeId)
        }
    }

    // ============ 知识合并 ============

    /**
     * 获取互补知识列表（我有的对方没有）
     */
    fun getComplementaryKnowledge(myDigest: SporeKnowledgeDigest, targetDigest: SporeKnowledgeDigest): List<String> {
        // 简化版：返回对方擅长但我没有的领域
        return targetDigest.expertiseTags.filter { it !in myDigest.expertiseTags }
    }

    /**
     * 请求知识传输
     */
    fun requestKnowledgeTransfer(targetSporeId: String, knowledgeTypes: List<String>) {
        Log.i(TAG, "向 ${targetSporeId.take(8)}... 请求知识: $knowledgeTypes")
        // 实际传输通过网络层完成
    }

    /**
     * 合并收到的知识
     */
    fun mergeReceivedKnowledge(fromSporeId: String, digest: SporeKnowledgeDigest) {
        Log.i(TAG, "合并来自 ${fromSporeId.take(8)}... 的知识摘要")
        notifyEvent(ClusterEvent.KnowledgeReceived(fromSporeId, digest))
    }

    // ============ 算力互补 ============

    /**
     * 广播空闲算力
     */
    fun broadcastIdleCompute(idlePercent: Float) {
        val myId = sporeProtocol.getMyIdentity()?.sporeId ?: return
        val member = activeMembers[myId] ?: return
        activeMembers[myId] = member.copy(idleComputePercent = idlePercent)
    }

    /**
     * 分发计算任务到空闲孢子
     */
    fun distributeComputeTask(taskId: String, description: String, requiredCompute: Float): String? {
        val idleMembers = activeMembers.values.filter {
            it.state == SporeState.ONLINE && it.idleComputePercent >= requiredCompute
        }

        if (idleMembers.isEmpty()) {
            Log.w(TAG, "无空闲成员可分配任务: $taskId")
            return null
        }

        // 选择最空闲的成员
        val target = idleMembers.maxByOrNull { it.idleComputePercent }!!
        Log.i(TAG, "任务 $taskId 分配给: ${target.identity.displayName} (空闲${target.idleComputePercent}%)")

        notifyEvent(ClusterEvent.ComputeTaskReceived(taskId, description))
        return target.identity.sporeId
    }

    /**
     * 任务完成回调
     */
    fun onComputeTaskCompleted(taskId: String, result: String, completedBySporeId: String) {
        Log.i(TAG, "任务 $taskId 由 ${completedBySporeId.take(8)}... 完成")
        notifyEvent(ClusterEvent.ComputeTaskCompleted(taskId, result))
    }

    // ============ 状态查询 ============

    /**
     * 获取当前集群信息
     */
    fun getClusterInfo(): ClusterInfo? = clusterInfo

    /**
     * 获取活跃成员列表
     */
    fun getActiveMembers(): List<ClusterMember> = activeMembers.values.toList()

    /**
     * 获取已发现的孢子列表
     */
    fun getDiscoveredSpores(): List<SporeIdentity> = discoveredSpores.values.toList()

    /**
     * 获取集群状态摘要
     */
    fun getClusterSummary(): String {
        return buildString {
            appendLine("═══ 孢子集群状态 ═══")
            appendLine("状态: ${_clusterState.value}")
            clusterInfo?.let {
                appendLine("集群: ${it.name}")
                appendLine("成员数: ${activeMembers.size + 1}")
                appendLine("核心: ${it.coreSporeId.take(8)}...")
            }
            activeMembers.values.forEach { member ->
                appendLine("  - ${member.identity.displayName}: ${member.state}")
            }
        }
    }

    // ============ 心跳与广播 ============

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL)
                cleanupTimedOutMembers()
            }
        }
    }

    private fun startStateBroadcast() {
        broadcastJob?.cancel()
        broadcastJob = scope.launch {
            while (isActive) {
                delay(STATE_BROADCAST_INTERVAL)
                broadcastMyState()
            }
        }
    }

    private fun broadcastMyState() {
        val myId = sporeProtocol.getMyIdentity() ?: return
        val digest = sporeProtocol.getKnowledgeDigest()
        Log.d(TAG, "广播状态: ${myId.displayName}, 知识: ${digest?.causalTripleCount ?: 0}三元组")
    }

    // ============ 事件通知 ============

    private fun notifyEvent(event: ClusterEvent) {
        _clusterEvents.value = _clusterEvents.value + event
        eventListeners.forEach { it(event) }
    }

    fun addEventListener(listener: (ClusterEvent) -> Unit) {
        eventListeners.add(listener)
    }

    fun removeEventListener(listener: (ClusterEvent) -> Unit) {
        eventListeners.remove(listener)
    }

    // ============ 销毁 ============

    fun destroy() {
        leaveCluster()
        scope.cancel()
        eventListeners.clear()
        Log.i(TAG, "孢子集群管理器已销毁")
    }
}
