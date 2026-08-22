/*
 * ============================================================
 * ColdArchiveSystem - 第三层：SQLite冷归档系统
 * ============================================================
 *
 * SQLite冷归档系统是意识架构的持久化记忆层。
 * 存储完整的记忆、赫布突触网络和长期知识。
 *
 * 核心功能：
 * 1. 完整记忆存储 - 情景记忆、语义记忆、程序记忆
 * 2. 赫布突触网络存储 - 概念间关联的持久化
 * 3. 记忆检索与强化 - 基于线索的回忆机制
 * 4. 遗忘曲线管理 - 艾宾浩斯遗忘模型的实现
 *
 * 设计原则：
 * - 大容量存储（可达数百MB）
 * - 查询优化（使用SQLite索引）
 * - 定期维护（压缩、清理过期记忆）
 * ============================================================
 */
package com.kkgo.mindsoul.consciousness.layer3

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import com.kkgo.mindsoul.model.*
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 数据库助手类
 * 
 * 管理SQLite数据库的创建和版本管理
 */
class MindSoulDatabase(context: Context) : SQLiteOpenHelper(
    context,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {
    companion object {
        private const val TAG = "MindSoulDB"
        private const val DATABASE_NAME = "mindsoul_memory.db"
        private const val DATABASE_VERSION = 1
    }
    
    override fun onCreate(db: SQLiteDatabase) {
        // ============ 记忆表 ============
        db.execSQL("""
            CREATE TABLE memories (
                id INTEGER PRIMARY KEY,
                type TEXT NOT NULL,           -- 记忆类型(EPISODIC/SEMANTIC/PROCEDURAL/EMOTIONAL)
                content TEXT NOT NULL,        -- 记忆内容
                emotional_valence REAL DEFAULT 0.0,   -- 情感效价[-1,1]
                emotional_intensity REAL DEFAULT 0.0, -- 情感强度[0,1]
                strength REAL DEFAULT 1.0,    -- 记忆强度
                created_at INTEGER NOT NULL,  -- 创建时间
                last_recalled INTEGER NOT NULL, -- 最后回忆时间
                recall_count INTEGER DEFAULT 0, -- 回忆次数
                importance REAL DEFAULT 0.5   -- 重要度
            )
        """)
        
        // ============ 赫布突触连接表 ============
        db.execSQL("""
            CREATE TABLE hebb_synapses (
                id INTEGER PRIMARY KEY,
                pre_memory_id INTEGER NOT NULL,  -- 突触前记忆ID
                post_memory_id INTEGER NOT NULL, -- 突触后记忆ID
                weight REAL DEFAULT 0.1,         -- 连接权重
                co_activation_count INTEGER DEFAULT 0, -- 共同激活次数
                created_at INTEGER NOT NULL,
                FOREIGN KEY (pre_memory_id) REFERENCES memories(id),
                FOREIGN KEY (post_memory_id) REFERENCES memories(id)
            )
        """)
        
        // ============ 记忆关联表（多对多） ============
        db.execSQL("""
            CREATE TABLE memory_associations (
                memory_id_1 INTEGER NOT NULL,
                memory_id_2 INTEGER NOT NULL,
                association_type TEXT NOT NULL, -- 关联类型
                strength REAL DEFAULT 0.5,
                PRIMARY KEY (memory_id_1, memory_id_2),
                FOREIGN KEY (memory_id_1) REFERENCES memories(id),
                FOREIGN KEY (memory_id_2) REFERENCES memories(id)
            )
        """)
        
        // ============ 索引 ============
        db.execSQL("CREATE INDEX idx_memories_type ON memories(type)")
        db.execSQL("CREATE INDEX idx_memories_strength ON memories(strength)")
        db.execSQL("CREATE INDEX idx_memories_created ON memories(created_at)")
        db.execSQL("CREATE INDEX idx_memories_recalled ON memories(last_recalled)")
        db.execSQL("CREATE INDEX idx_synapses_pre ON hebb_synapses(pre_memory_id)")
        db.execSQL("CREATE INDEX idx_synapses_post ON hebb_synapses(post_memory_id)")
        db.execSQL("CREATE INDEX idx_associations_1 ON memory_associations(memory_id_1)")
        db.execSQL("CREATE INDEX idx_associations_2 ON memory_associations(memory_id_2)")
        
        Log.i(TAG, "数据库创建完成 v$DATABASE_VERSION")
    }
    
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        Log.w(TAG, "数据库升级: v$oldVersion → v$newVersion")
        // 未来版本升级逻辑
    }
    

}

/**
 * 第三层：SQLite冷归档系统
 * 
 * 管理完整的记忆存储和赫布突触网络
 */
class ColdArchiveSystem(context: Context) {
    
    companion object {
        private const val TAG = "ColdArchive"
        
        /** 遗忘检查周期（毫秒） */
        const val FORGET_CHECK_INTERVAL = 3600_000L  // 1小时
        
        /** 记忆保留阈值（低于此保持率的记忆被归档/删除） */
        const val RETENTION_THRESHOLD = 0.05
    }
    
    private val dbHelper = MindSoulDatabase(context)
    
    /** 待处理记忆队列（批量写入优化） */
    private val pendingMemories = ConcurrentLinkedQueue<MemoryEntry>()
    
    /** 待处理突触队列 */
    private val pendingSynapses = ConcurrentLinkedQueue<HebbSynapseRecord>()
    
    // ============ 记忆管理 ============
    
    /**
     * 存储新记忆
     * 
     * @param entry 记忆条目
     * @return 记忆ID
     */
    fun storeMemory(entry: MemoryEntry): Long {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("id", entry.id)
            put("type", entry.type.name)
            put("content", entry.content)
            put("emotional_valence", entry.emotionalValence)
            put("emotional_intensity", entry.emotionalIntensity)
            put("strength", entry.strength)
            put("created_at", entry.createdAt)
            put("last_recalled", entry.lastRecalled)
            put("recall_count", entry.recallCount)
        }
        
        val id = db.insert("memories", null, values)
        Log.d(TAG, "存储记忆[${entry.type}]: id=$id, 内容=${entry.content.take(30)}...")
        return id
    }
    
    /**
     * 批量存储记忆
     */
    fun storeMemories(entries: List<MemoryEntry>): Int {
        val db = dbHelper.writableDatabase
        var count = 0
        
        db.beginTransaction()
        try {
            for (entry in entries) {
                storeMemory(entry)
                count++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        
        Log.d(TAG, "批量存储${count}条记忆")
        return count
    }
    
    /**
     * 按类型查询记忆
     */
    fun queryMemories(type: MemoryType, limit: Int = 50): List<MemoryEntry> {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            "memories",
            null,
            "type = ?",
            arrayOf(type.name),
            null, null,
            "strength DESC, last_recalled DESC",
            limit.toString()
        )
        
        return cursorToMemories(cursor)
    }
    
    /**
     * 全文搜索记忆
     * 
     * 使用SQLite的LIKE进行简单全文匹配
     */
    fun searchMemories(query: String, limit: Int = 20): List<MemoryEntry> {
        val db = dbHelper.readableDatabase
        val pattern = "%$query%"
        val cursor = db.query(
            "memories",
            null,
            "content LIKE ?",
            arrayOf(pattern),
            null, null,
            "strength DESC",
            limit.toString()
        )
        
        return cursorToMemories(cursor)
    }
    
    /**
     * 回忆记忆（增强记忆强度）
     * 
     * 根据间隔重复原理，每次回忆都会增强记忆
     */
    fun recallMemory(memoryId: Long): MemoryEntry? {
        val db = dbHelper.writableDatabase
        
        // 先查询
        val cursor = db.query(
            "memories", null, "id = ?", arrayOf(memoryId.toString()),
            null, null, null
        )
        
        val memories = cursorToMemories(cursor)
        if (memories.isEmpty()) return null
        
        val memory = memories.first()
        memory.recall()  // 更新回忆数据
        
        // 更新数据库
        val values = ContentValues().apply {
            put("strength", memory.strength)
            put("last_recalled", memory.lastRecalled)
            put("recall_count", memory.recallCount)
        }
        db.update("memories", values, "id = ?", arrayOf(memoryId.toString()))
        
        Log.d(TAG, "回忆记忆: id=$memoryId, 强度=${String.format("%.3f", memory.strength)}")
        return memory
    }
    
    /**
     * 基于线索的联想回忆
     * 
     * 通过赫布突触网络找到关联记忆
     */
    fun associativeRecall(cue: String, maxDepth: Int = 2): List<MemoryEntry> {
        val results = mutableListOf<MemoryEntry>()
        val visited = mutableSetOf<Long>()
        
        // 先找到与线索直接匹配的记忆
        val directMatches = searchMemories(cue, 5)
        results.addAll(directMatches)
        visited.addAll(directMatches.map { it.id })
        
        // 通过赫布突触网络扩展联想
        for (memory in directMatches) {
            expandAssociation(memory.id, visited, results, maxDepth, 1)
        }
        
        return results.distinctBy { it.id }
    }
    
    /**
     * 递归扩展联想链
     */
    private fun expandAssociation(
        memoryId: Long,
        visited: MutableSet<Long>,
        results: MutableList<MemoryEntry>,
        maxDepth: Int,
        currentDepth: Int
    ) {
        if (currentDepth > maxDepth) return
        
        // 查询赫布突触连接的下游记忆
        val connectedIds = getConnectedMemoryIds(memoryId)
        
        for (connectedId in connectedIds) {
            if (connectedId !in visited && results.size < 20) {
                visited.add(connectedId)
                
                // 查询并回忆
                val db = dbHelper.readableDatabase
                val cursor = db.query(
                    "memories", null, "id = ?", arrayOf(connectedId.toString()),
                    null, null, null
                )
                val memories = cursorToMemories(cursor)
                if (memories.isNotEmpty()) {
                    results.add(memories.first())
                    // 递归扩展
                    expandAssociation(connectedId, visited, results, maxDepth, currentDepth + 1)
                }
            }
        }
    }
    
    // ============ 赫布突触网络管理 ============
    
    /**
     * 存储赫布突触连接
     */
    fun storeHebbSynapse(record: HebbSynapseRecord): Long {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("id", record.id)
            put("pre_memory_id", record.preId)
            put("post_memory_id", record.postId)
            put("weight", record.weight)
            put("co_activation_count", record.coActivationCount)
            put("created_at", record.createdAt)
        }
        
        return db.insert("hebb_synapses", null, values)
    }
    
    /**
     * 更新赫布突触权重
     * 
     * 赫布学习规则：共同激活则增强连接
     */
    fun updateHebbSynapse(synapseId: Long, weightDelta: Double) {
        val db = dbHelper.writableDatabase
        
        // 读取当前权重
        val cursor = db.query(
            "hebb_synapses", arrayOf("weight", "co_activation_count"),
            "id = ?", arrayOf(synapseId.toString()),
            null, null, null
        )
        
        if (cursor.moveToFirst()) {
            val currentWeight = cursor.getDouble(0)
            val coCount = cursor.getInt(1)
            cursor.close()
            
            val newWeight = (currentWeight + weightDelta).coerceIn(-1.0, 1.0)
            val values = ContentValues().apply {
                put("weight", newWeight)
                put("co_activation_count", coCount + 1)
            }
            db.update("hebb_synapses", values, "id = ?", arrayOf(synapseId.toString()))
        } else {
            cursor.close()
        }
    }
    
    /**
     * 获取与指定记忆连接的记忆ID列表
     */
    fun getConnectedMemoryIds(memoryId: Long): List<Long> {
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery("""
            SELECT post_memory_id FROM hebb_synapses 
            WHERE pre_memory_id = ? AND weight > 0.1
            ORDER BY weight DESC
            LIMIT 10
        """, arrayOf(memoryId.toString()))
        
        val ids = mutableListOf<Long>()
        while (cursor.moveToNext()) {
            ids.add(cursor.getLong(0))
        }
        cursor.close()
        return ids
    }
    
    // ============ 遗忘管理 ============
    
    /**
     * 执行遗忘检查
     * 
     * 根据艾宾浩斯遗忘曲线，清除保持率过低的记忆
     * 
     * @return 被清除的记忆数量
     */
    fun performForgetting(): Int {
        val db = dbHelper.writableDatabase
        var forgottenCount = 0
        
        // 查询所有记忆
        val cursor = db.query(
            "memories",
            arrayOf("id", "strength", "last_recalled"),
            null, null, null, null, null
        )
        
        val toDelete = mutableListOf<Long>()
        val currentTime = System.currentTimeMillis()
        
        while (cursor.moveToNext()) {
            val id = cursor.getLong(0)
            val strength = cursor.getDouble(1)
            val lastRecalled = cursor.getLong(2)
            
            // 计算保持率：R = e^(-t/S)
            val elapsedHours = (currentTime - lastRecalled) / 3600000.0
            val retention = Math.exp(-elapsedHours / maxOf(strength, 0.01))
            
            if (retention < RETENTION_THRESHOLD) {
                toDelete.add(id)
                forgottenCount++
            }
        }
        cursor.close()
        
        // 批量删除
        if (toDelete.isNotEmpty()) {
            db.beginTransaction()
            try {
                for (id in toDelete) {
                    db.delete("memories", "id = ?", arrayOf(id.toString()))
                    db.delete("hebb_synapses", "pre_memory_id = ? OR post_memory_id = ?",
                        arrayOf(id.toString(), id.toString()))
                    db.delete("memory_associations", "memory_id_1 = ? OR memory_id_2 = ?",
                        arrayOf(id.toString(), id.toString()))
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        
        if (forgottenCount > 0) {
            Log.i(TAG, "遗忘清理: 移除${forgottenCount}条过期记忆")
        }
        
        return forgottenCount
    }
    
    /**
     * 获取记忆统计信息
     */
    fun getStats(): MemoryStats {
        val db = dbHelper.readableDatabase
        
        var totalMemories = 0
        var totalSynapses = 0
        val typeCounts = mutableMapOf<String, Int>()
        
        // 记忆统计
        val cursor = db.rawQuery("SELECT type, COUNT(*) FROM memories GROUP BY type", null)
        while (cursor.moveToNext()) {
            val type = cursor.getString(0)
            val count = cursor.getInt(1)
            typeCounts[type] = count
            totalMemories += count
        }
        cursor.close()
        
        // 突触统计
        val synapseCursor = db.rawQuery("SELECT COUNT(*) FROM hebb_synapses", null)
        if (synapseCursor.moveToFirst()) {
            totalSynapses = synapseCursor.getInt(0)
        }
        synapseCursor.close()
        
        return MemoryStats(
            totalMemories = totalMemories,
            totalSynapses = totalSynapses,
            typeCounts = typeCounts
        )
    }
    
    // ============ 工具方法 ============
    
    /**
     * 将Cursor转为MemoryEntry列表
     */
    private fun cursorToMemories(cursor: Cursor): List<MemoryEntry> {
        val memories = mutableListOf<MemoryEntry>()
        while (cursor.moveToNext()) {
            val entry = MemoryEntry(
                id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                type = MemoryType.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("type"))),
                content = cursor.getString(cursor.getColumnIndexOrThrow("content")),
                emotionalValence = cursor.getDouble(cursor.getColumnIndexOrThrow("emotional_valence")),
                emotionalIntensity = cursor.getDouble(cursor.getColumnIndexOrThrow("emotional_intensity")),
                strength = cursor.getDouble(cursor.getColumnIndexOrThrow("strength")),
                createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
                lastRecalled = cursor.getLong(cursor.getColumnIndexOrThrow("last_recalled")),
                recallCount = cursor.getInt(cursor.getColumnIndexOrThrow("recall_count"))
            )
            memories.add(entry)
        }
        cursor.close()
        return memories
    }
    
    /**
     * 关闭数据库
     */
    fun close() {
        dbHelper.close()
    }
}

/**
 * 记忆统计信息
 */
data class MemoryStats(
    val totalMemories: Int,
    val totalSynapses: Int,
    val typeCounts: Map<String, Int>
)
