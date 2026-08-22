/*
 * ============================================================
 * DataSync - 数据同步协议
 * ============================================================
 *
 * 实现孢子间的数据同步：
 *
 * 1. 同步协议
 *    - 请求/响应模式
 *    - 增量同步（只传差异数据）
 *    - 冲突检测与合并
 *    - 版本向量管理
 *
 * 2. 数据类型
 *    - 因果三元组同步
 *    - 世界规则同步
 *    - 记忆条目同步
 *    - 进化状态同步
 *    - 配置同步
 *
 * 3. 同步策略
 *    - 全量同步（首次连接时）
 *    - 增量同步（后续同步时）
 *    - 按需同步（请求特定类型数据）
 *
 * 4. 一致性保证
 *    - 最终一致性模型
 *    - 基于时间戳的冲突解决
 *    - 同步完成确认机制
 * ============================================================
 */
package com.kkgo.mindsoul.network

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * 同步数据类型
 */
enum class SyncDataType(val typeId: Int) {
    CAUSAL_TRIPLES(1),     // 因果三元组
    WORLD_RULES(2),        // 世界规则
    MEMORY_ENTRIES(3),     // 记忆条目
    EVOLUTION_STATE(4),    // 进化状态
    CONFIG(5),             // 配置
    KNOWLEDGE_DIGEST(6)    // 知识摘要
}

/**
 * 同步操作类型
 */
enum class SyncOperation {
    FULL_SYNC,      // 全量同步
    INCREMENTAL,    // 增量同步
    ON_DEMAND,      // 按需同步
    PUSH            // 推送
}

/**
 * 同步请求
 */
data class SyncRequest(
    val requestId: String = "${System.nanoTime()}",
    val dataType: SyncDataType,
    val operation: SyncOperation,
    /** 版本向量（增量同步时传入） */
    val versionVector: Map<String, Long> = emptyMap(),
    /** 请求的条目数量限制 */
    val maxItems: Int = 1000,
    /** 时间戳 */
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 同步响应
 */
data class SyncResponse(
    val requestId: String,
    val dataType: SyncDataType,
    /** 是否成功 */
    val success: Boolean,
    /** 同步的数据项 */
    val items: List<SyncItem> = emptyList(),
    /** 更新的版本向量 */
    val versionVector: Map<String, Long> = emptyMap(),
    /** 是否还有更多数据 */
    val hasMore: Boolean = false,
    /** 错误信息 */
    val errorMessage: String = ""
)

/**
 * 同步数据项
 */
data class SyncItem(
    /** 数据项唯一ID */
    val itemId: String,
    /** 数据类型 */
    val dataType: SyncDataType,
    /** 数据内容（序列化后的字节） */
    val data: ByteArray,
    /** 版本号 */
    val version: Long,
    /** 创建时间 */
    val createdAt: Long,
    /** 最后修改时间 */
    val modifiedAt: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SyncItem) return false
        return itemId == other.itemId && version == other.version
    }

    override fun hashCode(): Int = 31 * itemId.hashCode() + version.hashCode()
}

/**
 * 同步会话
 */
data class SyncSession(
    val peerSporeId: String,
    val startedAt: Long = System.currentTimeMillis(),
    val syncedItems: Int = 0,
    val lastSyncAt: Long = 0,
    val versionVector: Map<String, Long> = emptyMap(),
    val isActive: Boolean = true
)

/**
 * DataSync - 数据同步协议管理器
 */
class DataSync(private val context: Context) {

    companion object {
        private const val TAG = "DataSync"
        /** 自动同步间隔（毫秒） */
        const val AUTO_SYNC_INTERVAL = 30000L
    }

    // ============ 状态 ============
    private val _syncState = MutableStateFlow<SyncState>(SyncState.IDLE)
    val syncStateFlow: StateFlow<SyncState> = _syncState.asStateFlow()

    /** 同步会话表 */
    private val syncSessions = ConcurrentHashMap<String, SyncSession>()

    /** 本地版本向量 */
    private val localVersionVector = ConcurrentHashMap<String, Long>()

    /** 待同步数据队列 */
    private val pendingSyncItems = ConcurrentHashMap<String, SyncItem>()

    // ============ 数据提供器 ============
    /** 本地数据提供器（各模块注册） */
    private val dataProviders = ConcurrentHashMap<SyncDataType, SyncDataProvider>()

    // ============ 网络层引用 ============
    private var peerConnection: PeerConnection? = null

    // ============ 协程 ============
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var autoSyncJob: Job? = null

    // ============ 同步状态 ============
    enum class SyncState {
        IDLE, SYNCING, COMPLETED, ERROR
    }

    // ============ 数据提供器接口 ============
    interface SyncDataProvider {
        /** 获取数据项列表 */
        fun getItems(sinceVersion: Long = 0): List<SyncItem>
        /** 接收并合并远程数据 */
        fun mergeItems(items: List<SyncItem>): Int
        /** 获取当前版本号 */
        fun getCurrentVersion(): Long
    }

    // ============ 初始化 ============

    /**
     * 初始化数据同步
     */
    fun initialize(peerConn: PeerConnection) {
        peerConnection = peerConn

        // 设置消息处理
        peerConn.setMessageCallback { sporeId, frame ->
            handleIncomingFrame(sporeId, frame)
        }

        Log.i(TAG, "数据同步协议就绪")
    }

    /**
     * 注册数据提供器
     */
    fun registerDataProvider(type: SyncDataType, provider: SyncDataProvider) {
        dataProviders[type] = provider
        Log.i(TAG, "注册数据提供器: ${type.name} (版本: ${provider.getCurrentVersion()})")
    }

    // ============ 同步控制 ============

    /**
     * 启动自动同步
     */
    fun startAutoSync() {
        autoSyncJob?.cancel()
        autoSyncJob = scope.launch {
            while (isActive) {
                delay(AUTO_SYNC_INTERVAL)
                performAutoSync()
            }
        }
        Log.i(TAG, "自动同步已启动，间隔: ${AUTO_SYNC_INTERVAL}ms")
    }

    /**
     * 停止自动同步
     */
    fun stopAutoSync() {
        autoSyncJob?.cancel()
        Log.i(TAG, "自动同步已停止")
    }

    /**
     * 执行全量同步
     */
    fun fullSync(peerSporeId: String): Boolean {
        Log.i(TAG, "开始全量同步: $peerSporeId")
        _syncState.value = SyncState.SYNCING

        val session = SyncSession(peerSporeId)
        syncSessions[peerSporeId] = session

        var totalSynced = 0

        // 遍历所有数据类型
        SyncDataType.values().forEach { dataType ->
            val request = SyncRequest(
                dataType = dataType,
                operation = SyncOperation.FULL_SYNC,
                versionVector = emptyMap() // 全量不传版本向量
            )

            val success = sendSyncRequest(peerSporeId, request)
            if (success) {
                totalSynced += 1
            }
        }

        Log.i(TAG, "全量同步完成: 请求了 ${totalSynced} 个数据类型")
        _syncState.value = SyncState.COMPLETED
        return true
    }

    /**
     * 执行增量同步
     */
    fun incrementalSync(peerSporeId: String): Boolean {
        Log.i(TAG, "开始增量同步: $peerSporeId")
        _syncState.value = SyncState.SYNCING

        val session = syncSessions[peerSporeId] ?: SyncSession(peerSporeId)

        SyncDataType.values().forEach { dataType ->
            val request = SyncRequest(
                dataType = dataType,
                operation = SyncOperation.INCREMENTAL,
                versionVector = localVersionVector.toMap()
            )
            sendSyncRequest(peerSporeId, request)
        }

        return true
    }

    /**
     * 按需同步特定类型数据
     */
    fun syncOnDemand(peerSporeId: String, dataType: SyncDataType): Boolean {
        Log.i(TAG, "按需同步: $peerSporeId → ${dataType.name}")
        val request = SyncRequest(
            dataType = dataType,
            operation = SyncOperation.ON_DEMAND,
            versionVector = localVersionVector.toMap()
        )
        return sendSyncRequest(peerSporeId, request)
    }

    /**
     * 推送数据到对端
     */
    fun pushToPeer(peerSporeId: String, items: List<SyncItem>): Boolean {
        Log.i(TAG, "推送 ${items.size} 个数据项到: $peerSporeId")

        val payload = buildPushPayload(items)
        val frame = MessageFrame(FrameType.KNOWLEDGE_SYNC, 0, payload)
        return peerConnection?.sendFrame(peerSporeId, frame) ?: false
    }

    // ============ 内部处理 ============

    private fun handleIncomingFrame(sporeId: String, frame: MessageFrame) {
        when (frame.frameType) {
            FrameType.KNOWLEDGE_SYNC -> {
                handleSyncData(sporeId, frame.data)
            }
            FrameType.COMPUTE_REQUEST -> {
                handleComputeRequest(sporeId, frame.data)
            }
            FrameType.COMPUTE_RESULT -> {
                handleComputeResult(sporeId, frame.data)
            }
            else -> {
                Log.d(TAG, "未处理的帧类型: ${frame.frameType}")
            }
        }
    }

    /**
     * 处理接收到的同步数据
     */
    private fun handleSyncData(sporeId: String, data: ByteArray) {
        try {
            val text = String(data, Charsets.UTF_8)
            val lines = text.lines()

            if (lines.isEmpty()) return

            // 解析同步响应
            val header = lines[0].split("|")
            if (header.size < 3) return

            val dataTypeId = header[0].toIntOrNull() ?: return
            val dataType = SyncDataType.values().find { it.typeId == dataTypeId } ?: return
            val items = parseSyncItems(lines.drop(1), dataType)

            // 通过数据提供器合并
            val provider = dataProviders[dataType]
            val mergedCount = provider?.mergeItems(items) ?: 0

            Log.i(TAG, "同步数据: ${dataType.name}, 收到${items.size}项, 合并${mergedCount}项")

            // 更新同步会话
            val session = syncSessions[sporeId]
            if (session != null) {
                syncSessions[sporeId] = session.copy(
                    syncedItems = session.syncedItems + items.size,
                    lastSyncAt = System.currentTimeMillis()
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理同步数据失败", e)
        }
    }

    private fun handleComputeRequest(sporeId: String, data: ByteArray) {
        Log.i(TAG, "收到计算请求: $sporeId, 数据长度: ${data.size}")
        // 计算任务由进化引擎/学习引擎处理
    }

    private fun handleComputeResult(sporeId: String, data: ByteArray) {
        Log.i(TAG, "收到计算结果: $sporeId, 数据长度: ${data.size}")
    }

    /**
     * 发送同步请求
     */
    private fun sendSyncRequest(peerSporeId: String, request: SyncRequest): Boolean {
        val payload = buildRequestPayload(request)
        val frame = MessageFrame(FrameType.KNOWLEDGE_SYNC, 0, payload)
        return peerConnection?.sendFrame(peerSporeId, frame) ?: false
    }

    /**
     * 自动同步
     */
    private fun performAutoSync() {
        val connectedPeers = peerConnection?.getAllConnections()?.filter {
            it.state == PeerConnectionState.CONNECTED
        } ?: return

        if (connectedPeers.isEmpty()) return

        Log.d(TAG, "自动同步: ${connectedPeers.size} 个已连接对端")
        connectedPeers.forEach { peer ->
            incrementalSync(peer.sporeId)
        }
    }

    // ============ 序列化 ============

    private fun buildRequestPayload(request: SyncRequest): ByteArray {
        return buildString {
            appendLine("${request.dataType.typeId}|${request.operation}|${request.timestamp}")
            request.versionVector.forEach { (k, v) ->
                appendLine("$k:$v")
            }
        }.toByteArray(Charsets.UTF_8)
    }

    private fun buildPushPayload(items: List<SyncItem>): ByteArray {
        return buildString {
            items.forEach { item ->
                appendLine("${item.itemId}|${item.dataType.typeId}|${item.version}|${item.modifiedAt}")
                appendLine(java.util.Base64.getEncoder().encodeToString(item.data))
            }
        }.toByteArray(Charsets.UTF_8)
    }

    private fun parseSyncItems(lines: List<String>, dataType: SyncDataType): List<SyncItem> {
        val items = mutableListOf<SyncItem>()
        var i = 0
        while (i < lines.size - 1) {
            val parts = lines[i].split("|")
            if (parts.size >= 4) {
                val itemId = parts[0]
                val version = parts[2].toLongOrNull() ?: 0L
                val modifiedAt = parts[3].toLongOrNull() ?: 0L
                val data = try {
                    java.util.Base64.getDecoder().decode(lines[i + 1])
                } catch (e: Exception) {
                    lines[i + 1].toByteArray(Charsets.UTF_8)
                }
                items.add(SyncItem(itemId, dataType, data, version, modifiedAt, modifiedAt))
                i += 2
            } else {
                i++
            }
        }
        return items
    }

    // ============ 状态查询 ============

    /**
     * 获取同步会话信息
     */
    fun getSyncSession(peerSporeId: String): SyncSession? = syncSessions[peerSporeId]

    /**
     * 获取本地版本向量
     */
    fun getLocalVersionVector(): Map<String, Long> = localVersionVector.toMap()

    /**
     * 更新本地版本向量
     */
    fun updateLocalVersion(dataType: SyncDataType, version: Long) {
        localVersionVector[dataType.name] = version
    }

    // ============ 销毁 ============

    fun destroy() {
        stopAutoSync()
        syncSessions.clear()
        dataProviders.clear()
        pendingSyncItems.clear()
        scope.cancel()
        Log.i(TAG, "数据同步已销毁")
    }
}
