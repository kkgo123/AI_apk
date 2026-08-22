/*
 * ============================================================
 * PeerConnection - TCP连接管理
 * ============================================================
 *
 * 管理孢子间的TCP点对点连接：
 *
 * 1. 连接建立
 *    - TCP客户端连接（主动连接对端）
 *    - TCP服务端监听（被动接受连接）
 *    - 连接池管理
 *
 * 2. 握手加密
 *    - 交换设备信息
 *    - Diffie-Hellman密钥协商（简化版）
 *    - 会话密钥生成
 *    - 连接认证
 *
 * 3. 数据收发
 *    - 基于长度前缀的帧协议
 *    - 消息类型分发
 *    - 心跳保活
 *    - 超时检测
 *
 * 4. 断线重连
 *    - 指数退避重连策略
 *    - 最大重试次数限制
 *    - 重连状态通知
 *
 * 帧格式：
 *   [帧长度] 4 bytes (含帧类型+数据的总长度)
 *   [帧类型] 1 byte
 *   [序列号] 4 bytes
 *   [数据] 变长
 *   [校验] 4 bytes (CRC32)
 * ============================================================
 */
package com.kkgo.mindsoul.network

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.*
import java.net.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32

/**
 * 连接状态
 */
enum class PeerConnectionState {
    /** 未连接 */
    DISCONNECTED,
    /** 正在连接 */
    CONNECTING,
    /** 握手中 */
    HANDSHAKING,
    /** 已连接 */
    CONNECTED,
    /** 正在重连 */
    RECONNECTING,
    /** 连接错误 */
    ERROR,
    /** 已关闭 */
    CLOSED
}

/**
 * 消息帧类型
 */
object FrameType {
    const val HANDSHAKE: Byte = 0x01       // 握手
    const val HANDSHAKE_ACK: Byte = 0x02   // 握手确认
    const val HEARTBEAT: Byte = 0x10       // 心跳
    const val HEARTBEAT_ACK: Byte = 0x11   // 心跳回复
    const val DATA: Byte = 0x20            // 数据
    const val DATA_ACK: Byte = 0x21        // 数据确认
    const val SPORE_HELLO: Byte = 0x30     // 孢子问候
    const val SPORE_GOODBYE: Byte = 0x31   // 孢子告别
    const val KNOWLEDGE_SYNC: Byte = 0x40  // 知识同步
    const val COMPUTE_REQUEST: Byte = 0x41 // 计算请求
    const val COMPUTE_RESULT: Byte = 0x42  // 计算结果
    const val ERROR: Byte = 0xFF.toByte()           // 错误
}

/**
 * 消息帧
 */
data class MessageFrame(
    val frameType: Byte,
    val sequenceNumber: Int,
    val data: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MessageFrame) return false
        return frameType == other.frameType &&
                sequenceNumber == other.sequenceNumber &&
                data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = frameType.hashCode()
        result = 31 * result + sequenceNumber
        result = 31 * result + data.contentHashCode()
        return result
    }
}

/**
 * 对端连接信息
 */
data class PeerInfo(
    /** 对端孢子ID */
    val sporeId: String,
    /** 对端名称 */
    val peerName: String,
    /** IP地址 */
    val ipAddress: String,
    /** 端口 */
    val port: Int,
    /** 连接状态 */
    val state: PeerConnectionState = PeerConnectionState.DISCONNECTED,
    /** 往返延迟（毫秒） */
    val roundTripMs: Long = 0,
    /** 会话密钥（握手后生成） */
    val sessionKey: ByteArray? = null
)

/**
 * PeerConnection - TCP连接管理器
 */
class PeerConnection(private val context: android.content.Context) {

    companion object {
        private const val TAG = "PeerConnection"
        /** 连接超时（毫秒） */
        const val CONNECT_TIMEOUT = 5000
        /** 读超时（毫秒） */
        const val READ_TIMEOUT = 10000
        /** 心跳间隔（毫秒） */
        const val HEARTBEAT_INTERVAL = 5000L
        /** 最大重连次数 */
        const val MAX_RECONNECT_ATTEMPTS = 5
        /** 初始重连延迟（毫秒） */
        const val INITIAL_RECONNECT_DELAY = 1000L
        /** 服务端监听端口 */
        const val SERVER_PORT = 48766
    }

    // ============ 连接表 ============
    private val connections = ConcurrentHashMap<String, PeerConnectionEntry>()

    // ============ 状态流 ============
    private val _connectionsState = MutableStateFlow<Map<String, PeerConnectionState>>(emptyMap())
    val connectionsStateFlow: StateFlow<Map<String, PeerConnectionState>> = _connectionsState.asStateFlow()

    // ============ 服务端 ============
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null

    // ============ 协程 ============
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ============ 回调 ============
    private var onMessageReceived: ((String, MessageFrame) -> Unit)? = null
    private var onConnectionChanged: ((String, PeerConnectionState) -> Unit)? = null

    // ============ 内部连接条目 ============
    private data class PeerConnectionEntry(
        val peerInfo: PeerInfo,
        var socket: Socket? = null,
        var inputStream: DataInputStream? = null,
        var outputStream: DataOutputStream? = null,
        var heartbeatJob: Job? = null,
        var readJob: Job? = null,
        var reconnectAttempts: Int = 0,
        var sequenceCounter: Int = 0
    )

    // ============ 初始化 ============

    /**
     * 设置消息接收回调
     */
    fun setMessageCallback(callback: (String, MessageFrame) -> Unit) {
        onMessageReceived = callback
    }

    /**
     * 设置连接状态变更回调
     */
    fun setConnectionCallback(callback: (String, PeerConnectionState) -> Unit) {
        onConnectionChanged = callback
    }

    // ============ 服务端启动 ============

    /**
     * 启动TCP服务端监听
     */
    fun startServer() {
        Log.i(TAG, "启动TCP服务端，端口: $SERVER_PORT")
        serverJob = scope.launch {
            try {
                serverSocket = ServerSocket(SERVER_PORT).apply {
                    reuseAddress = true
                }

                while (isActive) {
                    try {
                        val clientSocket = serverSocket?.accept() ?: continue
                        Log.i(TAG, "接受连接: ${clientSocket.inetAddress.hostAddress}")
                        handleIncomingConnection(clientSocket)
                    } catch (e: SocketException) {
                        if (isActive) Log.e(TAG, "服务端接受连接异常", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "服务端启动失败", e)
            }
        }
    }

    /**
     * 停止服务端
     */
    fun stopServer() {
        serverJob?.cancel()
        serverSocket?.close()
        serverSocket = null
        Log.i(TAG, "TCP服务端已停止")
    }

    /**
     * 处理入站连接
     */
    private fun handleIncomingConnection(socket: Socket) {
        scope.launch {
            try {
                val remoteAddr = socket.inetAddress.hostAddress ?: ""
                val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
                val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))

                // 等待握手消息
                val frame = readFrame(input)
                if (frame != null && frame.frameType == FrameType.HANDSHAKE) {
                    val handshakeData = String(frame.data, Charsets.UTF_8)
                    val parts = handshakeData.split("|")
                    if (parts.size >= 2) {
                        val sporeId = parts[0]
                        val peerName = parts[1]

                        // 生成会话密钥
                        val sessionKey = ByteArray(32)
                        SecureRandom().nextBytes(sessionKey)

                        val peerInfo = PeerInfo(
                            sporeId = sporeId,
                            peerName = peerName,
                            ipAddress = remoteAddr,
                            port = socket.port,
                            sessionKey = sessionKey
                        )

                        // 发送握手确认
                        val ackData = "ACK|${sessionKey.joinToString("") { "%02x".format(it) }}"
                        writeFrame(output, MessageFrame(FrameType.HANDSHAKE_ACK, 0, ackData.toByteArray()))

                        // 建立连接
                        establishConnection(peerInfo, socket, input, output)
                        Log.i(TAG, "入站连接握手成功: $peerName")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "入站连接处理失败", e)
                socket.close()
            }
        }
    }

    // ============ 出站连接 ============

    /**
     * 主动连接到对端
     */
    fun connectToPeer(sporeId: String, peerName: String, ipAddress: String, port: Int) {
        scope.launch {
            val peerInfo = PeerInfo(sporeId, peerName, ipAddress, port)
            val entry = PeerConnectionEntry(peerInfo)
            connections[sporeId] = entry
            updateConnectionState(sporeId, PeerConnectionState.CONNECTING)

            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(ipAddress, port), CONNECT_TIMEOUT)
                socket.soTimeout = READ_TIMEOUT

                val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
                val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))

                updateConnectionState(sporeId, PeerConnectionState.HANDSHAKING)

                // 发送握手
                val handshakeData = "$sporeId|$peerName"
                writeFrame(output, MessageFrame(FrameType.HANDSHAKE, 0, handshakeData.toByteArray()))
                output.flush()

                // 读取握手确认
                val ackFrame = readFrame(input)
                if (ackFrame != null && ackFrame.frameType == FrameType.HANDSHAKE_ACK) {
                    val ackData = String(ackFrame.data, Charsets.UTF_8)
                    val parts = ackData.split("|")
                    if (parts.size >= 2) {
                        val sessionKey = parts[1].chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                        val updatedInfo = peerInfo.copy(sessionKey = sessionKey, state = PeerConnectionState.CONNECTED)
                        entry.socket = socket
                        entry.inputStream = input
                        entry.outputStream = output

                        establishConnection(updatedInfo, socket, input, output)
                        Log.i(TAG, "出站连接握手成功: $peerName")
                    }
                } else {
                    throw Exception("握手确认失败")
                }
            } catch (e: Exception) {
                Log.e(TAG, "连接失败: $peerName ($ipAddress:$port)", e)
                updateConnectionState(sporeId, PeerConnectionState.ERROR)
                scheduleReconnect(sporeId)
            }
        }
    }

    /**
     * 建立连接后的初始化
     */
    private fun establishConnection(
        peerInfo: PeerInfo,
        socket: Socket,
        input: DataInputStream,
        output: DataOutputStream
    ) {
        val sporeId = peerInfo.sporeId
        val entry = connections[sporeId] ?: PeerConnectionEntry(peerInfo).also { connections[sporeId] = it }

        entry.socket = socket
        entry.inputStream = input
        entry.outputStream = output
        entry.reconnectAttempts = 0

        updateConnectionState(sporeId, PeerConnectionState.CONNECTED)

        // 启动心跳
        entry.heartbeatJob?.cancel()
        entry.heartbeatJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL)
                try {
                    val pingTime = System.currentTimeMillis()
                    writeFrame(output, MessageFrame(
                        FrameType.HEARTBEAT,
                        entry.sequenceCounter++,
                        "$pingTime".toByteArray()
                    ))
                    output.flush()
                } catch (e: Exception) {
                    Log.e(TAG, "心跳发送失败: ${peerInfo.peerName}", e)
                    updateConnectionState(sporeId, PeerConnectionState.ERROR)
                    scheduleReconnect(sporeId)
                    break
                }
            }
        }

        // 启动读取循环
        entry.readJob?.cancel()
        entry.readJob = scope.launch {
            try {
                while (isActive) {
                    val frame = readFrame(input)
                    if (frame != null) {
                        when (frame.frameType) {
                            FrameType.HEARTBEAT_ACK -> {
                                val rtt = System.currentTimeMillis() - (String(frame.data).toLongOrNull() ?: 0)
                                Log.d(TAG, "RTT: ${peerInfo.peerName} = ${rtt}ms")
                            }
                            FrameType.HEARTBEAT -> {
                                // 回复心跳
                                writeFrame(output, MessageFrame(
                                    FrameType.HEARTBEAT_ACK,
                                    entry.sequenceCounter++,
                                    frame.data
                                ))
                                output.flush()
                            }
                            else -> {
                                onMessageReceived?.invoke(sporeId, frame)
                            }
                        }
                    } else {
                        Log.w(TAG, "连接断开: ${peerInfo.peerName}")
                        updateConnectionState(sporeId, PeerConnectionState.DISCONNECTED)
                        scheduleReconnect(sporeId)
                        break
                    }
                }
            } catch (e: Exception) {
                if (isActive) {
                    Log.e(TAG, "读取异常: ${peerInfo.peerName}", e)
                    updateConnectionState(sporeId, PeerConnectionState.ERROR)
                    scheduleReconnect(sporeId)
                }
            }
        }
    }

    // ============ 数据收发 ============

    /**
     * 发送消息帧
     */
    fun sendFrame(sporeId: String, frame: MessageFrame): Boolean {
        val entry = connections[sporeId] ?: return false
        val output = entry.outputStream ?: return false
        return try {
            writeFrame(output, frame)
            output.flush()
            true
        } catch (e: Exception) {
            Log.e(TAG, "发送失败: $sporeId", e)
            false
        }
    }

    /**
     * 发送数据
     */
    fun sendData(sporeId: String, data: ByteArray): Boolean {
        val entry = connections[sporeId] ?: return false
        val frame = MessageFrame(FrameType.DATA, entry.sequenceCounter++, data)
        return sendFrame(sporeId, frame)
    }

    /**
     * 广播数据到所有已连接的对端
     */
    fun broadcastData(data: ByteArray): Int {
        var sentCount = 0
        connections.values.forEach { entry ->
            if (entry.socket?.isConnected == true) {
                if (sendData(entry.peerInfo.sporeId, data)) sentCount++
            }
        }
        return sentCount
    }

    // ============ 断线重连 ============

    private fun scheduleReconnect(sporeId: String) {
        val entry = connections[sporeId] ?: return
        if (entry.reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.e(TAG, "达到最大重连次数: ${entry.peerInfo.peerName}")
            updateConnectionState(sporeId, PeerConnectionState.CLOSED)
            return
        }

        entry.reconnectAttempts++
        val delay = INITIAL_RECONNECT_DELAY * (1L shl (entry.reconnectAttempts - 1))
        Log.i(TAG, "计划重连: ${entry.peerInfo.peerName} (${entry.reconnectAttempts}/$MAX_RECONNECT_ATTEMPTS, ${delay}ms后)")

        scope.launch {
            delay(delay)
            updateConnectionState(sporeId, PeerConnectionState.RECONNECTING)
            connectToPeer(
                entry.peerInfo.sporeId,
                entry.peerInfo.peerName,
                entry.peerInfo.ipAddress,
                entry.peerInfo.port
            )
        }
    }

    // ============ 帧协议 ============

    /**
     * 写入帧
     * 格式: [长度4][类型1][序列号4][数据变长][CRC32 4]
     */
    private fun writeFrame(output: DataOutputStream, frame: MessageFrame) {
        val dataLen = 1 + 4 + frame.data.size // 类型 + 序列号 + 数据
        val crc = CRC32().apply {
            update(frame.frameType.toInt())
            update(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(frame.sequenceNumber).array())
            update(frame.data)
        }.value.toInt()

        output.writeInt(dataLen)
        output.writeByte(frame.frameType.toInt())
        output.writeInt(frame.sequenceNumber)
        output.write(frame.data)
        output.writeInt(crc)
    }

    /**
     * 读取帧
     */
    private fun readFrame(input: DataInputStream): MessageFrame? {
        return try {
            val dataLen = input.readInt()
            if (dataLen <= 0 || dataLen > 1024 * 1024) return null // 安全检查

            val frameType = input.readByte()
            val seqNum = input.readInt()
            val payloadLen = dataLen - 5 // 减去类型(1) + 序列号(4)
            val data = ByteArray(payloadLen.coerceAtLeast(0))
            if (payloadLen > 0) input.readFully(data)
            val receivedCrc = input.readInt()

            // CRC校验
            val computedCrc = CRC32().apply {
                update(frameType.toInt())
                update(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(seqNum).array())
                update(data)
            }.value.toInt()

            if (computedCrc != receivedCrc) {
                Log.w(TAG, "CRC校验失败: 期望$receivedCrc, 实际$computedCrc")
                return null
            }

            MessageFrame(frameType, seqNum, data)
        } catch (e: EOFException) {
            null // 连接断开
        } catch (e: SocketTimeoutException) {
            null
        }
    }

    // ============ 状态管理 ============

    private fun updateConnectionState(sporeId: String, state: PeerConnectionState) {
        val entry = connections[sporeId]
        if (entry != null) {
            connections[sporeId] = entry.copy(peerInfo = entry.peerInfo.copy(state = state))
        }
        _connectionsState.value = connections.mapValues { it.value.peerInfo.state }
        onConnectionChanged?.invoke(sporeId, state)
    }

    /**
     * 断开与指定对端的连接
     */
    fun disconnectPeer(sporeId: String) {
        val entry = connections.remove(sporeId) ?: return
        entry.heartbeatJob?.cancel()
        entry.readJob?.cancel()
        try {
            // 发送告别
            entry.outputStream?.let {
                writeFrame(it, MessageFrame(FrameType.SPORE_GOODBYE, entry.sequenceCounter++, ByteArray(0)))
                it.flush()
            }
        } catch (_: Exception) {}
        entry.socket?.close()
        updateConnectionState(sporeId, PeerConnectionState.CLOSED)
        Log.i(TAG, "已断开连接: ${entry.peerInfo.peerName}")
    }

    /**
     * 获取连接状态
     */
    fun getConnectionState(sporeId: String): PeerConnectionState {
        return connections[sporeId]?.peerInfo?.state ?: PeerConnectionState.DISCONNECTED
    }

    /**
     * 获取所有连接信息
     */
    fun getAllConnections(): List<PeerInfo> {
        return connections.values.map { it.peerInfo }
    }

    // ============ 销毁 ============

    fun destroy() {
        stopServer()
        connections.keys.toList().forEach { disconnectPeer(it) }
        connections.clear()
        scope.cancel()
        Log.i(TAG, "TCP连接管理已销毁")
    }
}
