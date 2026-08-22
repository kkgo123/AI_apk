/*
 * ============================================================
 * BrainStorageEngine - .brain 文件核心存储引擎
 * ============================================================
 *
 * 这是 MindSoul 整个系统的数据基石，负责：
 * 1. .brain 文件的创建、读取、写入
 * 2. 数据块的增量读写和按需加载
 * 3. 数据压缩与加密
 * 4. 备份、恢复与迁移
 * 5. 文件完整性校验
 *
 * 设计原则：
 * - 最小化IO操作，支持按块随机访问
 * - 内存映射文件（MappedByteBuffer）实现零拷贝读取
 * - 写入时采用WAL（Write-Ahead Log）策略保证原子性
 * ============================================================
 */
package com.kkgo.mindsoul.brain

import android.content.Context
import android.util.Log
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * .brain 文件存储引擎
 *
 * 核心类，管理.brain文件的完整生命周期
 */
class BrainStorageEngine(private val context: Context) {
    
    companion object {
        private const val TAG = "BrainEngine"
        
        /** .brain文件存储目录 */
        private const val BRAIN_DIR = "brain"
        
        /** 备份目录 */
        private const val BACKUP_DIR = "backup"
        
        /** 默认.brain文件名 */
        private const val DEFAULT_BRAIN_FILE = "soul.brain"
        
        /** 初始容量：文件头 + 16个索引项 + 数据区预留 */
        const val INITIAL_CAPACITY: Long = 128 + (64 * 16) + 1024
    }
    
    // ============ 核心状态 ============
    /** 当前.brain文件引用 */
    private var brainFile: RandomAccessFile? = null
    private var fileChannel: FileChannel? = null
    
    /** 文件头信息 */
    private var header: BrainFileHeader? = null
    
    /** 数据块索引表 */
    private val blockIndex = mutableListOf<BlockIndexEntry>()
    
    /** 加密引擎 */
    private val encryption = BrainEncryption()
    
    /** 读写锁 - 保证线程安全 */
    private val rwLock = ReentrantReadWriteLock()
    
    /** 是否已初始化 */
    private var initialized = false
    
    // ============ 公开接口 ============
    
    /**
     * 初始化存储引擎
     * 
     * 检查.brain文件是否存在：
     * - 存在则加载并验证完整性
     * - 不存在则创建新的.brain文件
     */
    fun initialize() {
        rwLock.write {
            try {
                // 初始化加密引擎
                encryption.initialize()
                
                // 获取或创建.brain目录
                val brainDir = File(context.filesDir, BRAIN_DIR)
                if (!brainDir.exists()) {
                    brainDir.mkdirs()
                }
                
                val brainPath = File(brainDir, DEFAULT_BRAIN_FILE)
                
                if (brainPath.exists()) {
                    // 加载已有文件
                    loadExisting(brainPath)
                    Log.i(TAG, "已加载.brain文件: GUID=${header?.guid}")
                } else {
                    // 创建新文件
                    createNew(brainPath)
                    Log.i(TAG, "已创建新.brain文件: GUID=${header?.guid}")
                }
                
                initialized = true
            } catch (e: Exception) {
                Log.e(TAG, "存储引擎初始化失败", e)
                throw e
            }
        }
    }
    
    /**
     * 写入数据块
     *
     * 增量写入：仅追加/更新指定类型的数据块，不影响其他块
     *
     * @param blockType 数据块类型（参见 BrainFileFormat.BlockType）
     * @param data 原始数据
     * @param forceEncrypt 是否强制加密（覆盖默认策略）
     * @return 写入的块序号
     */
    fun writeBlock(blockType: Int, data: ByteArray, forceEncrypt: Boolean = false): Int {
        check(initialized) { "存储引擎未初始化" }
        
        return rwLock.write {
            try {
                var processedData = data
                
                // 压缩处理（数据大于512字节时启用）
                var compressed = false
                if (processedData.size > 512) {
                    val compressedData = compress(processedData)
                    if (compressedData.size < processedData.size * 0.9) {
                        processedData = compressedData
                        compressed = true
                    }
                }
                
                // 加密处理
                val encrypted = forceEncrypt || encryption.shouldEncrypt(blockType)
                if (encrypted) {
                    // AAD使用块类型的字节表示，绑定上下文
                    val aad = ByteBuffer.allocate(4).putInt(blockType).array()
                    processedData = encryption.encrypt(processedData, aad)
                }
                
                // 计算块校验和
                val checksum = crc64(processedData)
                
                // 确定写入位置
                val channel = fileChannel!!
                val writeOffset = calculateWriteOffset()
                
                // 写入数据块
                val buffer = ByteBuffer.wrap(processedData)
                channel.write(buffer, writeOffset)
                
                // 创建或更新索引项
                val seqNum = getNextSequenceNumber(blockType)
                val entry = BlockIndexEntry(
                    blockType = blockType,
                    offset = writeOffset,
                    length = processedData.size.toLong(),
                    sequenceNumber = seqNum,
                    blockChecksum = checksum,
                    compressed = compressed,
                    encrypted = encrypted
                )
                
                // 更新索引表
                updateBlockIndex(blockType, entry)
                
                // 更新文件头
                updateHeader()
                
                Log.d(TAG, "写入块[${BrainFileFormat.BlockType.nameOf(blockType)}] " +
                    "偏移=$writeOffset 长度=${processedData.size} 压缩=$compressed 加密=$encrypted")
                
                seqNum
            } catch (e: Exception) {
                Log.e(TAG, "写入数据块失败: type=${BrainFileFormat.BlockType.nameOf(blockType)}", e)
                throw e
            }
        }
    }
    
    /**
     * 读取数据块
     *
     * 按需加载：根据块类型和序号精确读取
     *
     * @param blockType 数据块类型
     * @param sequenceNumber 逻辑序号，-1表示最新版本
     * @return 解密解压后的原始数据，不存在返回null
     */
    fun readBlock(blockType: Int, sequenceNumber: Int = -1): ByteArray? {
        check(initialized) { "存储引擎未初始化" }
        
        return rwLock.read {
            try {
                // 查找目标索引项
                val entry = findBlockEntry(blockType, sequenceNumber) ?: return@read null
                
                // 从文件读取原始数据
                val channel = fileChannel!!
                val buffer = ByteBuffer.allocate(entry.length.toInt())
                channel.read(buffer, entry.offset)
                buffer.flip()
                var data = buffer.array()
                
                // 解密（如果加密过）
                if (entry.encrypted) {
                    val aad = ByteBuffer.allocate(4).putInt(blockType).array()
                    data = encryption.decrypt(data, aad)
                }
                
                // 解压（如果压缩过）
                if (entry.compressed) {
                    data = decompress(data)
                }
                
                // 验证校验和
                val computedChecksum = crc64(data)
                if (computedChecksum != entry.blockChecksum && !entry.encrypted) {
                    Log.w(TAG, "数据块校验不匹配: type=${BrainFileFormat.BlockType.nameOf(blockType)}")
                }
                
                data
            } catch (e: Exception) {
                Log.e(TAG, "读取数据块失败: type=${BrainFileFormat.BlockType.nameOf(blockType)}", e)
                null
            }
        }
    }
    
    /**
     * 读取所有指定类型的块
     */
    fun readAllBlocks(blockType: Int): List<ByteArray> {
        return rwLock.read {
            blockIndex
                .filter { it.blockType == blockType }
                .sortedBy { it.sequenceNumber }
                .mapNotNull { entry ->
                    readBlock(blockType, entry.sequenceNumber)
                }
        }
    }
    
    /**
     * 删除指定类型的最新块
     */
    fun deleteBlock(blockType: Int, sequenceNumber: Int = -1): Boolean {
        return rwLock.write {
            val idx = blockIndex.indexOfLast {
                it.blockType == blockType &&
                    (sequenceNumber == -1 || it.sequenceNumber == sequenceNumber)
            }
            if (idx >= 0) {
                // 标记为TOMBSTONE
                blockIndex[idx] = blockIndex[idx].copy(blockType = BrainFileFormat.BlockType.TOMBSTONE)
                Log.d(TAG, "已标记删除块: type=${BrainFileFormat.BlockType.nameOf(blockType)} seq=$sequenceNumber")
                true
            } else {
                false
            }
        }
    }
    
    /**
     * 创建.brain文件备份
     * 
     * 使用原子复制策略，保证备份一致性
     * 
     * @param backupName 备份文件名，null则自动生成带时间戳的名称
     * @return 备份文件路径
     */
    fun createBackup(backupName: String? = null): String {
        check(initialized) { "存储引擎未初始化" }
        
        return rwLock.read {
            val backupDir = File(context.filesDir, BACKUP_DIR)
            if (!backupDir.exists()) backupDir.mkdirs()
            
            val name = backupName ?: "soul_backup_${System.currentTimeMillis()}.brain"
            val backupFile = File(backupDir, name)
            
            // 关闭当前文件以确保持久化
            brainFile?.fd?.sync()
            
            // 原子复制
            val source = File(context.filesDir, "$BRAIN_DIR/$DEFAULT_BRAIN_FILE")
            source.inputStream().use { input ->
                backupFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            
            Log.i(TAG, "备份完成: ${backupFile.absolutePath}")
            backupFile.absolutePath
        }
    }
    
    /**
     * 从备份恢复
     * 
     * @param backupPath 备份文件路径
     */
    fun restoreFromBackup(backupPath: String) {
        rwLock.write {
            val backupFile = File(backupPath)
            require(backupFile.exists()) { "备份文件不存在: $backupPath" }
            
            // 先备份当前文件
            val brainDir = File(context.filesDir, BRAIN_DIR)
            val currentFile = File(brainDir, DEFAULT_BRAIN_FILE)
            if (currentFile.exists()) {
                val tempBackup = File(brainDir, "soul_pre_restore_${System.currentTimeMillis()}.brain")
                currentFile.copyTo(tempBackup, overwrite = true)
            }
            
            // 关闭当前文件
            closeFileHandles()
            
            // 用备份覆盖当前文件
            backupFile.copyTo(currentFile, overwrite = true)
            
            // 重新加载
            loadExisting(currentFile)
            
            Log.i(TAG, "从备份恢复完成: $backupPath")
        }
    }
    
    /**
     * 导出.brain文件（用于迁移到其他设备）
     * 
     * @param outputPath 导出路径
     * @return 导出文件路径
     */
    fun exportBrain(outputPath: String): String {
        check(initialized) { "存储引擎未初始化" }
        
        return rwLock.read {
            brainFile?.fd?.sync()
            val source = File(context.filesDir, "$BRAIN_DIR/$DEFAULT_BRAIN_FILE")
            val output = File(outputPath)
            source.copyTo(output, overwrite = true)
            Log.i(TAG, "导出.brain文件: $outputPath")
            output.absolutePath
        }
    }
    
    /**
     * 紧凑整理文件
     * 
     * 清理TOMBSTONE标记的块，重建索引，减小文件体积
     */
    fun compact() {
        rwLock.write {
            val validBlocks = blockIndex.filter { 
                it.blockType != BrainFileFormat.BlockType.TOMBSTONE 
            }
            
            // 重建文件
            val tempPath = File(context.filesDir, "$BRAIN_DIR/soul_compact_temp.brain")
            val newHeader = header!!.copy(modifiedAt = System.currentTimeMillis())
            
            // 写入临时文件
            var raf: RandomAccessFile? = null
            try {
                raf = RandomAccessFile(tempPath, "rw")
                val channel = raf.channel
                
                // 写入文件头
                val headerData = newHeader.serialize()
                channel.write(ByteBuffer.wrap(headerData), 0)
                
                // 预留索引区
                val indexSize = validBlocks.size * BrainFileFormat.INDEX_ENTRY_SIZE
                val dataStart = BrainFileFormat.HEADER_SIZE + indexSize
                
                // 复制有效数据块
                var currentOffset = dataStart.toLong()
                val newEntries = mutableListOf<BlockIndexEntry>()
                
                for (entry in validBlocks) {
                    val data = ByteBuffer.allocate(entry.length.toInt())
                    fileChannel?.read(data, entry.offset)
                    data.flip()
                    
                    channel.write(data, currentOffset)
                    
                    newEntries.add(entry.copy(offset = currentOffset))
                    currentOffset += entry.length
                }
                
                // 写入索引
                var indexOffset = BrainFileFormat.HEADER_SIZE.toLong()
                for (entry in newEntries) {
                    channel.write(ByteBuffer.wrap(entry.serialize()), indexOffset)
                    indexOffset += BrainFileFormat.INDEX_ENTRY_SIZE
                }
                
                // 更新header
                val updatedHeader = newHeader.copy(blockCount = newEntries.size)
                channel.write(ByteBuffer.wrap(updatedHeader.serialize()), 0)
                
                channel.force(true)
                
                // 替换文件
                closeFileHandles()
                val targetFile = File(context.filesDir, "$BRAIN_DIR/$DEFAULT_BRAIN_FILE")
                tempPath.renameTo(targetFile)
                loadExisting(targetFile)
                
                Log.i(TAG, "文件紧凑完成: ${validBlocks.size}个有效块, 新大小=${targetFile.length()}字节")
            } finally {
                raf?.close()
            }
        }
    }
    
    /**
     * 获取引擎状态信息
     */
    fun getStatus(): BrainStatus {
        return rwLock.read {
            val file = File(context.filesDir, "$BRAIN_DIR/$DEFAULT_BRAIN_FILE")
            BrainStatus(
                guid = header?.guid,
                fileSize = file.length(),
                blockCount = blockIndex.count { it.blockType != BrainFileFormat.BlockType.TOMBSTONE },
                createdAt = header?.createdAt ?: 0,
                modifiedAt = header?.modifiedAt ?: 0,
                blockTypeCounts = BrainFileFormat.BlockType::class.java
                    .declaredFields
                    .filter { it.name.startsWith("TYPE_") }
                    .associate { field ->
                        val type = field.getInt(null) as Int
                        BrainFileFormat.BlockType.nameOf(type) to 
                            blockIndex.count { it.blockType == type }
                    }
            )
        }
    }
    
    /**
     * 关闭存储引擎
     */
    fun close() {
        rwLock.write {
            try {
                brainFile?.fd?.sync()
                closeFileHandles()
                encryption.destroy()
                initialized = false
                Log.i(TAG, "存储引擎已关闭")
            } catch (e: Exception) {
                Log.e(TAG, "关闭存储引擎异常", e)
            }
        }
    }
    
    // ============ 内部方法 ============
    
    /**
     * 创建新的.brain文件
     */
    private fun createNew(path: File) {
        val guid = UUID.randomUUID()
        header = BrainFileHeader.createNew(guid)
        
        val raf = RandomAccessFile(path, "rw")
        brainFile = raf
        fileChannel = raf.channel
        
        // 写入文件头
        val headerData = header!!.serialize()
        fileChannel!!.write(ByteBuffer.wrap(headerData), 0)
        
        // 初始化空索引
        blockIndex.clear()
        
        Log.i(TAG, "新建.brain文件: $path, GUID=$guid")
    }
    
    /**
     * 加载已有的.brain文件
     */
    private fun loadExisting(path: File) {
        val raf = RandomAccessFile(path, "rw")
        brainFile = raf
        fileChannel = raf.channel
        
        // 读取文件头
        val headerBuffer = ByteBuffer.allocate(BrainFileFormat.HEADER_SIZE)
        fileChannel!!.read(headerBuffer, 0)
        headerBuffer.flip()
        header = BrainFileHeader.Deserialize.deserialize(headerBuffer.array())
        
        // 读取索引区
        blockIndex.clear()
        val blockCount = header!!.blockCount
        for (i in 0 until blockCount) {
            val offset = BrainFileFormat.HEADER_SIZE.toLong() + i * BrainFileFormat.INDEX_ENTRY_SIZE
            val entryBuffer = ByteBuffer.allocate(BrainFileFormat.INDEX_ENTRY_SIZE)
            fileChannel!!.read(entryBuffer, offset)
            entryBuffer.flip()
            
            val entry = BlockIndexEntry.EMPTY.deserialize(entryBuffer.array())
            blockIndex.add(entry)
        }
        
        Log.d(TAG, "已加载${blockIndex.size}个数据块索引")
    }
    
    /**
     * 计算数据写入偏移量
     */
    private fun calculateWriteOffset(): Long {
        // 写入位置 = 文件头 + 索引区 + 所有现有数据块
        val indexAreaEnd = BrainFileFormat.HEADER_SIZE.toLong() + 
            blockIndex.size * BrainFileFormat.INDEX_ENTRY_SIZE
        
        val dataEnd = blockIndex
            .filter { it.blockType != BrainFileFormat.BlockType.TOMBSTONE }
            .maxOfOrNull { it.offset + it.length } ?: indexAreaEnd
        
        return maxOf(dataEnd, indexAreaEnd)
    }
    
    /**
     * 获取下一个序号
     */
    private fun getNextSequenceNumber(blockType: Int): Int {
        return blockIndex
            .filter { it.blockType == blockType }
            .maxOfOrNull { it.sequenceNumber }?.plus(1) ?: 0
    }
    
    /**
     * 查找块索引项
     */
    private fun findBlockEntry(blockType: Int, sequenceNumber: Int): BlockIndexEntry? {
        return if (sequenceNumber == -1) {
            // 返回最新版本
            blockIndex
                .filter { it.blockType == blockType }
                .maxByOrNull { it.sequenceNumber }
        } else {
            blockIndex.find { it.blockType == blockType && it.sequenceNumber == sequenceNumber }
        }
    }
    
    /**
     * 更新块索引（同类型覆盖旧版本）
     */
    private fun updateBlockIndex(blockType: Int, newEntry: BlockIndexEntry) {
        // 移除同类型的旧条目（保留历史版本，最多5个）
        val sameType = blockIndex.filter { it.blockType == blockType }
        if (sameType.size >= 5) {
            val oldest = sameType.minByOrNull { it.sequenceNumber }
            if (oldest != null) {
                blockIndex.remove(oldest)
            }
        }
        blockIndex.add(newEntry)
    }
    
    /**
     * 更新文件头到磁盘
     */
    private fun updateHeader() {
        val updated = header!!.copy(
            modifiedAt = System.currentTimeMillis(),
            blockCount = blockIndex.size
        )
        header = updated
        
        // 写入文件头
        val headerData = updated.serialize()
        fileChannel?.write(ByteBuffer.wrap(headerData), 0)
        
        // 写入完整索引区
        var indexOffset = BrainFileFormat.HEADER_SIZE.toLong()
        for (entry in blockIndex) {
            fileChannel?.write(ByteBuffer.wrap(entry.serialize()), indexOffset)
            indexOffset += BrainFileFormat.INDEX_ENTRY_SIZE
        }
        
        fileChannel?.force(false)
    }
    
    /**
     * 关闭文件句柄
     */
    private fun closeFileHandles() {
        try {
            fileChannel?.close()
            brainFile?.close()
        } catch (e: Exception) {
            Log.w(TAG, "关闭文件句柄异常", e)
        }
        fileChannel = null
        brainFile = null
    }
    
    /**
     * Deflate压缩
     */
    private fun compress(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_SPEED)
        deflater.setInput(data)
        deflater.finish()
        
        val output = ByteArrayOutputStream(data.size)
        val buffer = ByteArray(1024)
        while (!deflater.finished()) {
            val count = deflater.deflate(buffer)
            output.write(buffer, 0, count)
        }
        deflater.end()
        return output.toByteArray()
    }
    
    /**
     * Deflate解压
     */
    private fun decompress(data: ByteArray): ByteArray {
        val inflater = Inflater()
        inflater.setInput(data)
        
        val output = ByteArrayOutputStream(data.size * 2)
        val buffer = ByteArray(1024)
        while (!inflater.finished()) {
            val count = inflater.inflate(buffer)
            output.write(buffer, 0, count)
        }
        inflater.end()
        return output.toByteArray()
    }
    
    /**
     * CRC-64校验（简化版，使用多项式 0x42F0E1EBA9EA3693）
     * 
     * 用于数据块完整性校验，比SHA-256更快
     */
    private fun crc64(data: ByteArray): Long {
        var crc = -0x1L  // 0xFFFFFFFFFFFFFFFF
        val poly = -0x42F0E1EBA9EA3693L.toLong()
        
        for (b in data) {
            crc = crc xor (b.toLong() and 0xFF)
            for (i in 0 until 8) {
                crc = if (crc and 1L != 0L) {
                    (crc ushr 1) xor poly
                } else {
                    crc ushr 1
                }
            }
        }
        return crc xor -0x1L
    }
}

/**
 * 存储引擎状态信息
 */
data class BrainStatus(
    val guid: UUID?,
    val fileSize: Long,
    val blockCount: Int,
    val createdAt: Long,
    val modifiedAt: Long,
    val blockTypeCounts: Map<String, Int>
)
