/*
 * ============================================================
 * BrainFileFormat - .brain 二进制文件格式定义
 * ============================================================
 *
 * .brain 文件格式是 MindSoul 人工生命的全部意识数据存储载体。
 * 
 * ═══════════ 二进制文件结构 ═══════════
 * 
 * [文件头] 128 bytes
 *   - 魔数: "MSOUL" (5 bytes)
 *   - 版本号: 4 bytes (major.minor.patch.build)
 *   - GUID标识: 16 bytes (意识体唯一标识)
 *   - 创建时间戳: 8 bytes (Unix毫秒)
 *   - 最后修改时间戳: 8 bytes
 *   - 数据块数量: 4 bytes
 *   - 校验和: 32 bytes (SHA-256前32字节)
 *   - 保留区: 51 bytes
 * 
 * [数据块索引区] 每个索引项 64 bytes
 *   - 块类型ID: 4 bytes (枚举)
 *   - 块偏移量: 8 bytes (从文件头开始的偏移)
 *   - 块数据长度: 8 bytes
 *   - 块逻辑序号: 4 bytes
 *   - 块校验和: 8 bytes
 *   - 压缩标志: 1 byte (0=无压缩, 1=Deflate)
 *   - 加密标志: 1 byte (0=未加密, 1=AES-GCM)
 *   - 保留: 30 bytes
 * 
 * [数据块区域] 实际数据
 *   按类型分为：
 *   - TYPE_AXIOM(0x01): 常驻公理层数据
 *   - TYPE_CAUSAL(0x02): 因果三元组数据
 *   - TYPE_NEURON(0x03): 神经元网络权重数据
 *   - TYPE_HEBB(0x04): 赫布突触连接数据
 *   - TYPE_MEMORY(0x05): 完整记忆数据
 *   - TYPE_WORLDMODEL(0x06): 世界模型数据
 *   - TYPE_METACOGNITION(0x07): 元认知状态数据
 *   - TYPE_GUID(0x08): GUID身份数据
 *   - TYPE_ASSOCIATION(0x09): 联想池数据
 * 
 * [文件尾] 16 bytes
 *   - 完整SHA-256: 32 bytes (对文件头到数据块区域的完整校验)
 * 
 * ═══════════ 增量读写机制 ═══════════
 * 
 * - 数据块按逻辑序号排列，支持按块偏移直接定位读写
 * - 修改某个块时，仅需重写该块数据+更新索引，无需重写全文件
 * - 新增块追加到数据区末尾，更新索引表
 * - 删除块标记为TOMBSTONE(0xFF)，下次compact时清理
 * 
 * ═══════════ 加密方案 ═══════════
 * 
 * - 密钥来源：Android Keystore 生成的 AES-256 密钥
 * - 加密模式：AES/GCM/NoPadding（认证加密）
 * - 每个数据块使用独立IV，防止块间关联分析
 * - GUID身份块始终加密，公理层根据敏感度决定是否加密
 * ============================================================
 */
package com.kkgo.mindsoul.brain

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID

/**
 * .brain 文件格式常量定义
 */
object BrainFileFormat {
    
    // ============ 文件头常量 ============
    /** 文件魔数 - "MSOUL" 的ASCII字节 */
    val MAGIC = byteArrayOf(0x4D, 0x53, 0x4F, 0x55, 0x4C) // "MSOUL"
    
    /** 文件头固定长度：128字节 */
    const val HEADER_SIZE = 128
    
    /** 每个索引项固定长度：64字节 */
    const val INDEX_ENTRY_SIZE = 64
    
    /** 文件尾校验和长度：32字节(SHA-256) */
    const val FOOTER_SIZE = 32
    
    /** 当前文件格式版本 */
    const val VERSION_MAJOR = 0
    const val VERSION_MINOR = 1
    const val VERSION_PATCH = 0
    const val VERSION_BUILD = 1
    
    // ============ 数据块类型枚举 ============
    /**
     * 数据块类型定义
     * 每个类型对应意识架构中的一个核心模块
     */
    object BlockType {
        const val TYPE_AXIOM: Int          = 0x01  // 常驻公理层
        const val TYPE_CAUSAL: Int         = 0x02  // 因果三元组
        const val TYPE_NEURON: Int         = 0x03  // 神经元网络
        const val TYPE_HEBB: Int           = 0x04  // 赫布突触
        const val TYPE_MEMORY: Int         = 0x05  // 完整记忆
        const val TYPE_WORLDMODEL: Int     = 0x06  // 世界模型
        const val TYPE_METACOGNITION: Int  = 0x07  // 元认知状态
        const val TYPE_GUID: Int           = 0x08  // GUID身份
        const val TYPE_ASSOCIATION: Int    = 0x09  // 联想池
        
        /** 标记已删除的块 */
        const val TOMBSTONE: Int           = 0xFF
        
        /** 获取类型名称（用于调试） */
        fun nameOf(type: Int): String = when (type) {
            TYPE_AXIOM -> "公理层"
            TYPE_CAUSAL -> "因果三元组"
            TYPE_NEURON -> "神经元网络"
            TYPE_HEBB -> "赫布突触"
            TYPE_MEMORY -> "完整记忆"
            TYPE_WORLDMODEL -> "世界模型"
            TYPE_METACOGNITION -> "元认知"
            TYPE_GUID -> "GUID身份"
            TYPE_ASSOCIATION -> "联想池"
            TOMBSTONE -> "已删除"
            else -> "未知($type)"
        }
    }
    
    // ============ 加密常量 ============
    /** AES-GCM密钥长度（字节） */
    const val AES_KEY_SIZE = 32   // 256位
    /** GCM IV长度（字节） */
    const val GCM_IV_SIZE = 12
    /** GCM认证标签长度（字节） */
    const val GCM_TAG_SIZE = 16
}

/**
 * 文件头数据结构
 * 
 * 固定128字节，包含文件的所有元信息
 */
data class BrainFileHeader(
    /** 意识体唯一标识（GUID） */
    val guid: UUID,
    /** 文件创建时间（Unix毫秒时间戳） */
    val createdAt: Long,
    /** 最后修改时间（Unix毫秒时间戳） */
    val modifiedAt: Long,
    /** 数据块数量 */
    val blockCount: Int,
    /** 文件校验和（SHA-256前32字节） */
    val checksum: ByteArray = ByteArray(32)
) {
    companion object {
        /**
         * 创建新的文件头
         */
        fun createNew(guid: UUID = UUID.randomUUID()): BrainFileHeader {
            val now = System.currentTimeMillis()
            return BrainFileHeader(
                guid = guid,
                createdAt = now,
                modifiedAt = now,
                blockCount = 0
            )
        }
    }
    
    /**
     * 序列化为128字节的ByteBuffer
     */
    fun serialize(): ByteArray {
        val buffer = ByteBuffer.allocate(BrainFileFormat.HEADER_SIZE)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        
        // 魔数 (5 bytes)
        buffer.put(BrainFileFormat.MAGIC)
        
        // 版本号 (4 bytes)
        buffer.put(BrainFileFormat.VERSION_MAJOR.toByte())
        buffer.put(BrainFileFormat.VERSION_MINOR.toByte())
        buffer.put(BrainFileFormat.VERSION_PATCH.toByte())
        buffer.put(BrainFileFormat.VERSION_BUILD.toByte())
        
        // GUID (16 bytes) - 高8位和低8位
        buffer.putLong(guid.mostSignificantBits)
        buffer.putLong(guid.leastSignificantBits)
        
        // 创建时间 (8 bytes)
        buffer.putLong(createdAt)
        
        // 修改时间 (8 bytes)
        buffer.putLong(modifiedAt)
        
        // 数据块数量 (4 bytes)
        buffer.putInt(blockCount)
        
        // 校验和 (32 bytes)
        buffer.put(checksum.copyOf(32))
        
        // 保留区填充0 (补齐到128字节)
        val used = buffer.position()
        val remaining = BrainFileFormat.HEADER_SIZE - used
        if (remaining > 0) {
            buffer.put(ByteArray(remaining))
        }
        
        return buffer.array()
    }
    
    /**
     * 从ByteBuffer反序列化
     */
    object Deserialize {
        fun deserialize(data: ByteArray): BrainFileHeader {
            val buffer = ByteBuffer.wrap(data)
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            
            // 验证魔数
            val magic = ByteArray(5)
            buffer.get(magic)
            require(magic.contentEquals(BrainFileFormat.MAGIC)) {
                "无效的.brain文件：魔数不匹配"
            }
            
            // 读取版本号
            val major = buffer.get().toInt() and 0xFF
            val minor = buffer.get().toInt() and 0xFF
            val patch = buffer.get().toInt() and 0xFF
            val build = buffer.get().toInt() and 0xFF
            
            // 读取GUID
            val msb = buffer.getLong()
            val lsb = buffer.getLong()
            val guid = UUID(msb, lsb)
            
            // 读取时间戳
            val createdAt = buffer.getLong()
            val modifiedAt = buffer.getLong()
            
            // 读取块数量
            val blockCount = buffer.getInt()
            
            // 读取校验和
            val checksum = ByteArray(32)
            buffer.get(checksum)
            
            return BrainFileHeader(guid, createdAt, modifiedAt, blockCount, checksum)
        }
    }
    
    /**
     * 计算文件头的SHA-256校验和
     */
    fun computeChecksum(): ByteArray {
        val data = serialize()
        // 校验和计算时，将校验和区域置零
        val cleanData = data.copyOf()
        // 校验和位于偏移 (5+4+16+8+8+4)=45 处，长度32字节
        for (i in 45 until 77) cleanData[i] = 0
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(cleanData)
    }
}

/**
 * 数据块索引项
 * 
 * 每个索引项64字节，描述一个数据块的位置和属性
 */
data class BlockIndexEntry(
    /** 数据块类型 */
    val blockType: Int,
    /** 数据在文件中的偏移量 */
    val offset: Long,
    /** 数据长度（字节） */
    val length: Long,
    /** 逻辑序号（用于排序） */
    val sequenceNumber: Int,
    /** 块数据校验和 */
    val blockChecksum: Long,
    /** 压缩标志：0=无压缩, 1=Deflate */
    val compressed: Boolean = false,
    /** 加密标志：false=明文, true=AES-GCM加密 */
    val encrypted: Boolean = false
) {
    companion object {
        /** 空索引项（占位用） */
        val EMPTY = BlockIndexEntry(0, 0, 0, 0, 0)
    }
    
    /**
     * 序列化为64字节
     */
    fun serialize(): ByteArray {
        val buffer = ByteBuffer.allocate(BrainFileFormat.INDEX_ENTRY_SIZE)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        
        buffer.putInt(blockType)           // 4 bytes
        buffer.putLong(offset)             // 8 bytes
        buffer.putLong(length)             // 8 bytes
        buffer.putInt(sequenceNumber)      // 4 bytes
        buffer.putLong(blockChecksum)      // 8 bytes
        buffer.put(if (compressed) 1.toByte() else 0.toByte())  // 1 byte
        buffer.put(if (encrypted) 1.toByte() else 0.toByte())   // 1 byte
        // 保留区 (30 bytes)
        buffer.put(ByteArray(30))
        
        return buffer.array()
    }
    
    /**
     * 从字节数组反序列化
     */
    fun deserialize(data: ByteArray): BlockIndexEntry {
        val buffer = ByteBuffer.wrap(data)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        
        val blockType = buffer.getInt()
        val offset = buffer.getLong()
        val length = buffer.getLong()
        val sequenceNumber = buffer.getInt()
        val blockChecksum = buffer.getLong()
        val compressed = buffer.get().toInt() != 0
        val encrypted = buffer.get().toInt() != 0
        
        return BlockIndexEntry(blockType, offset, length, sequenceNumber, blockChecksum, compressed, encrypted)
    }
}
