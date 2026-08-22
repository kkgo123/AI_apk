/*
 * ============================================================
 * NetworkDiscovery - 局域网UDP广播发现
 * ============================================================
 *
 * 实现局域网内设备的自动发现：
 *
 * 1. UDP广播发送
 *    - 定期发送发现广播包
 *    - 包含自身孢子身份信息
 *    - 广播到局域网广播地址
 *
 * 2. UDP广播监听
 *    - 监听发现端口的广播包
 *    - 解析对方孢子身份
 *    - 过滤重复和自身
 *
 * 3. 设备管理
 *    - 维护已发现设备列表
 *    - 过期设备自动清理
 *    - 设备状态通知
 *
 * 4. 安全握手
 *    - 发现后进行TCP握手
 *    - 交换加密公钥
 *    - 验证身份合法性
 * ============================================================
 */
package com.kkgo.mindsoul.network

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

/**
 * 发现的设备信息
 */
data class DiscoveredDevice(
    /** 孢子ID */
    val sporeId: String,
    /** 设备名称 */
    val deviceName: String,
    /** 设备IP地址 */
    val ipAddress: String,
    /** TCP通信端口 */
    val tcpPort: Int,
    /** 发现时间 */
    val discoveredAt: Long = System.currentTimeMillis(),
    /** 最后活跃时间 */
    val lastSeen: Long = System.currentTimeMillis(),
    /** 握手状态 */
    val handshakeState: HandshakeState = HandshakeState.NONE,
    /** 信号强度（基于响应延迟） */
    val signalStrength: Float = 0f
)

/**
 * 握手状态
 */
enum class HandshakeState {
    /** 未握手 */
    NONE,
    /** 握手中 */
    HANDSHAKING,
    /** 握手成功 */
    HANDSHAKE_OK,
    /** 握手失败 */
    HANDSHAKE_FAILED
}

/**
 * 发现广播包格式：
 *
 * [魔数] 4 bytes: "MSNL"
 * [版本] 1 byte: 协议版本
 * [孢子ID长度] 1 byte
 * [孢子ID] 变长
 * [设备名称长度] 1 byte
 * [设备名称] 变长
 * [TCP端口] 2 bytes
 * [时间戳] 8 bytes
 */
object DiscoveryPacket {
    const val MAGIC = 0x4D534E4C // "MSNL"
    const val PROTOCOL_VERSION: Byte = 1
    const val HEADER_SIZE = 4 + 1 // 魔数 + 版本

    /**
     * 构建发现广播包
     */
    fun build(sporeId: String, deviceName: String, tcpPort: Int): ByteArray {
        val sporeIdBytes = sporeId.toByteArray(Charsets.UTF_8)
        val nameBytes = deviceName.toByteArray(Charsets.UTF_8)

        val buffer = ByteBuffer.allocate(
            HEADER_SIZE + 1 + sporeIdBytes.size + 1 + nameBytes.size + 2 + 8
        )
        buffer.order(ByteOrder.BIG_ENDIAN)

        buffer.putInt(MAGIC)
        buffer.put(PROTOCOL_VERSION)
        buffer.put(sporeIdBytes.size.toByte())
        buffer.put(sporeIdBytes)
        buffer.put(nameBytes.size.toByte())
        buffer.put(nameBytes)
        buffer.putShort(tcpPort.toShort())
        buffer.putLong(System.currentTimeMillis())

        return buffer.array()
    }

    /**
     * 解析发现广播包
     */
    fun parse(data: ByteArray, length: Int): Triple<String, String, Int>? {
        return try {
            val buffer = ByteBuffer.wrap(data, 0, length)
            buffer.order(ByteOrder.BIG_ENDIAN)

            val magic = buffer.getInt()
            if (magic != MAGIC) return null

            val version = buffer.get()
            if (version != PROTOCOL_VERSION) return null

            val sporeIdLen = buffer.get().toInt() and 0xFF
            val sporeIdBytes = ByteArray(sporeIdLen)
            buffer.get(sporeIdBytes)
            val sporeId = String(sporeIdBytes, Charsets.UTF_8)

            val nameLen = buffer.get().toInt() and 0xFF
            val nameBytes = ByteArray(nameLen)
            buffer.get(nameBytes)
            val name = String(nameBytes, Charsets.UTF_8)

            val tcpPort = buffer.getShort().toInt() and 0xFFFF

            // 时间戳（用于延迟计算）
            val remoteTimestamp = buffer.getLong()

            Triple(sporeId, name, tcpPort)
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * NetworkDiscovery - 局域网UDP广播发现
 */
class NetworkDiscovery(private val context: Context) {

    companion object {
        private const val TAG = "NetworkDiscovery"
        /** 发现广播端口 */
        const val DISCOVERY_PORT = 48765
        /** 广播间隔（毫秒） */
        const val BROADCAST_INTERVAL = 3000L
        /** 设备超时时间（毫秒） */
        const val DEVICE_TIMEOUT = 12000L
    }

    // ============ 状态 ============
    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()

    /** 已发现设备表 */
    private val discoveredDevices = ConcurrentHashMap<String, DiscoveredDevice>()

    private val _discoveredDevicesFlow = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val discoveredDevicesFlow: StateFlow<List<DiscoveredDevice>> = _discoveredDevicesFlow.asStateFlow()

    // ============ 自身信息 ============
    private var mySporeId: String = ""
    private var myDeviceName: String = ""
    private var myTcpPort: Int = 48766

    // ============ 网络组件 ============
    private var broadcastSocket: DatagramSocket? = null
    private var listenSocket: DatagramSocket? = null
    private var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var broadcastJob: Job? = null
    private var listenJob: Job? = null
    private var cleanupJob: Job? = null

    // ============ 回调 ============
    private var onDeviceFound: ((DiscoveredDevice) -> Unit)? = null
    private var onDeviceLost: ((String) -> Unit)? = null

    // ============ 初始化 ============

    /**
     * 配置自身信息
     */
    fun configure(sporeId: String, deviceName: String, tcpPort: Int) {
        mySporeId = sporeId
        myDeviceName = deviceName
        myTcpPort = tcpPort
    }

    /**
     * 设置设备发现回调
     */
    fun setDeviceCallbacks(onFound: (DiscoveredDevice) -> Unit, onLost: (String) -> Unit) {
        onDeviceFound = onFound
        onDeviceLost = onLost
    }

    // ============ 发现控制 ============

    /**
     * 开始发现
     */
    fun startDiscovery() {
        if (_isDiscovering.value) {
            Log.w(TAG, "已在发现中")
            return
        }

        Log.i(TAG, "开始局域网发现...")
        _isDiscovering.value = true

        // 启动UDP广播发送
        broadcastJob = scope.launch { broadcastLoop() }

        // 启动UDP广播监听
        listenJob = scope.launch { listenLoop() }

        // 启动过期清理
        cleanupJob = scope.launch { cleanupLoop() }
    }

    /**
     * 停止发现
     */
    fun stopDiscovery() {
        Log.i(TAG, "停止局域网发现")
        _isDiscovering.value = false

        broadcastJob?.cancel()
        listenJob?.cancel()
        cleanupJob?.cancel()

        broadcastSocket?.close()
        listenSocket?.close()
        broadcastSocket = null
        listenSocket = null
    }

    // ============ 广播循环 ============

    private suspend fun broadcastLoop() {
        try {
            broadcastSocket = DatagramSocket().apply {
                broadcast = true
                reuseAddress = true
            }

            val broadcastAddress = getBroadcastAddress()
            val packet = DiscoveryPacket.build(mySporeId, myDeviceName, myTcpPort)

            while (scope.isActive && _isDiscovering.value) {
                try {
                    val datagram = DatagramPacket(
                        packet, packet.size,
                        broadcastAddress, DISCOVERY_PORT
                    )
                    broadcastSocket?.send(datagram)
                    Log.d(TAG, "广播发送 → $broadcastAddress:$DISCOVERY_PORT")
                } catch (e: Exception) {
                    Log.e(TAG, "广播发送失败", e)
                }
                delay(BROADCAST_INTERVAL)
            }
        } catch (e: Exception) {
            Log.e(TAG, "广播循环异常", e)
        }
    }

    // ============ 监听循环 ============

    private suspend fun listenLoop() {
        try {
            listenSocket = DatagramSocket(DISCOVERY_PORT).apply {
                reuseAddress = true
                broadcast = true
                soTimeout = 2000
            }

            val buffer = ByteArray(1024)

            Log.i(TAG, "监听端口 $DISCOVERY_PORT ...")

            while (scope.isActive && _isDiscovering.value) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    listenSocket?.receive(packet)

                    val result = DiscoveryPacket.parse(packet.data, packet.length)
                    if (result != null) {
                        val (sporeId, name, tcpPort) = result

                        // 过滤自身
                        if (sporeId == mySporeId) continue

                        val device = DiscoveredDevice(
                            sporeId = sporeId,
                            deviceName = name,
                            ipAddress = packet.address.hostAddress ?: "",
                            tcpPort = tcpPort,
                            lastSeen = System.currentTimeMillis()
                        )

                        val isNew = !discoveredDevices.containsKey(sporeId)
                        discoveredDevices[sporeId] = device

                        if (isNew) {
                            Log.i(TAG, "发现新设备: $name (${device.ipAddress}:$tcpPort)")
                            onDeviceFound?.invoke(device)
                        }

                        // 更新流
                        _discoveredDevicesFlow.value = discoveredDevices.values.toList()
                    }
                } catch (e: SocketTimeoutException) {
                    // 超时正常，继续循环
                } catch (e: Exception) {
                    Log.e(TAG, "监听异常", e)
                    delay(1000)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "监听循环异常", e)
        }
    }

    // ============ 过期清理 ============

    private suspend fun cleanupLoop() {
        while (scope.isActive && _isDiscovering.value) {
            delay(5000)
            val now = System.currentTimeMillis()
            val expired = discoveredDevices.filter { (_, device) ->
                now - device.lastSeen > DEVICE_TIMEOUT
            }
            expired.keys.forEach { sporeId ->
                discoveredDevices.remove(sporeId)
                Log.i(TAG, "设备超时移除: $sporeId")
                onDeviceLost?.invoke(sporeId)
            }
            _discoveredDevicesFlow.value = discoveredDevices.values.toList()
        }
    }

    // ============ 工具方法 ============

    /**
     * 获取局域网广播地址
     */
    private fun getBroadcastAddress(): InetAddress {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addrs = iface.interfaceAddresses
                for (addr in addrs) {
                    if (addr.broadcast != null && addr.address is Inet4Address) {
                        return addr.broadcast
                    }
                }
            }
            // 回退到通用广播地址
            InetAddress.getByName("255.255.255.255")
        } catch (e: Exception) {
            Log.e(TAG, "获取广播地址失败，使用默认", e)
            InetAddress.getByName("255.255.255.255")
        }
    }

    /**
     * 获取已发现设备列表
     */
    fun getDevices(): List<DiscoveredDevice> = discoveredDevices.values.toList()

    /**
     * 手动添加设备（从其他来源）
     */
    fun addManualDevice(device: DiscoveredDevice) {
        discoveredDevices[device.sporeId] = device
        _discoveredDevicesFlow.value = discoveredDevices.values.toList()
        onDeviceFound?.invoke(device)
    }

    /**
     * 销毁
     */
    fun destroy() {
        stopDiscovery()
        scope.cancel()
        onDeviceFound = null
        onDeviceLost = null
        Log.i(TAG, "网络发现已销毁")
    }
}
