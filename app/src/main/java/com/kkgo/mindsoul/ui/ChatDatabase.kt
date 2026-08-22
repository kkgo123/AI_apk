/*
 * ============================================================
 * ChatDatabase - 对话数据持久化存储引擎
 * ============================================================
 *
 * 基于 SQLite 的对话历史持久化管理器，负责：
 * 1. 创建/管理 chat_messages 数据表
 * 2. 消息的增删改查（CRUD）
 * 3. 自动清理过期消息（保留条数可配置）
 * 4. 全文搜索支持
 * 5. 批量插入优化
 *
 * 数据库结构：
 * - chat_messages: 存储所有对话消息
 *   - id: 主键
 *   - type: 消息类型（TEXT/IMAGE/FILE/VOICE/VIDEO/LINK/SYSTEM）
 *   - role: 消息角色（USER/AI/SYSTEM）
 *   - text: 消息文本内容
 *   - file_path: 关联的文件路径（可选）
 *   - file_name: 文件名（可选）
 *   - file_size: 文件大小（可选）
 *   - duration: 语音时长（可选，毫秒）
 *   - timestamp: 消息时间戳
 * ============================================================
 */
package com.kkgo.mindsoul.ui

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

/**
 * 聊天消息数据模型（含数据库字段）
 */
data class ChatMessage(
    val id: Long = System.nanoTime(),
    val type: MessageType = MessageType.TEXT,
    val role: MessageRole = MessageRole.USER,
    val text: String = "",
    val filePath: String? = null,
    val fileName: String? = null,
    val fileSize: Long = 0,
    val duration: Long = 0,          // 语音时长（毫秒）
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 消息类型枚举
 */
enum class MessageType {
    TEXT,       // 文本消息
    IMAGE,      // 图片消息
    FILE,       // 文件消息
    VOICE,      // 语音消息
    VIDEO,      // 视频消息
    LINK,       // 链接消息
    SYSTEM      // 系统消息
}

/**
 * 消息角色枚举
 */
enum class MessageRole {
    USER,       // 用户发送
    AI,         // AI 回复
    SYSTEM      // 系统消息
}

/**
 * SQLite 数据库助手
 * 负责数据库的创建、升级和基础操作
 */
class ChatDatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "mindsoul_chat.db"
        private const val DATABASE_VERSION = 1
        private const val TAG = "ChatDBHelper"

        // 表名
        const val TABLE_MESSAGES = "chat_messages"

        // 列名
        const val COL_ID = "id"
        const val COL_TYPE = "type"
        const val COL_ROLE = "role"
        const val COL_TEXT = "text"
        const val COL_FILE_PATH = "file_path"
        const val COL_FILE_NAME = "file_name"
        const val COL_FILE_SIZE = "file_size"
        const val COL_DURATION = "duration"
        const val COL_TIMESTAMP = "timestamp"
    }

    /**
     * 创建数据库表
     */
    override fun onCreate(db: SQLiteDatabase) {
        val createTable = """
            CREATE TABLE $TABLE_MESSAGES (
                $COL_ID INTEGER PRIMARY KEY,
                $COL_TYPE TEXT NOT NULL DEFAULT 'TEXT',
                $COL_ROLE TEXT NOT NULL DEFAULT 'USER',
                $COL_TEXT TEXT NOT NULL DEFAULT '',
                $COL_FILE_PATH TEXT,
                $COL_FILE_NAME TEXT,
                $COL_FILE_SIZE INTEGER DEFAULT 0,
                $COL_DURATION INTEGER DEFAULT 0,
                $COL_TIMESTAMP INTEGER NOT NULL
            )
        """.trimIndent()
        db.execSQL(createTable)

        // 创建时间戳索引，加速按时间排序查询
        db.execSQL("CREATE INDEX idx_timestamp ON $TABLE_MESSAGES($COL_TIMESTAMP)")
        // 创建角色索引，加速按角色筛选
        db.execSQL("CREATE INDEX idx_role ON $TABLE_MESSAGES($COL_ROLE)")

        Log.i(TAG, "[建表] chat_messages 表创建完成")
    }

    /**
     * 数据库升级（版本变更时调用）
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        Log.w(TAG, "[升级] 数据库从 v$oldVersion 升级到 v$newVersion")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_MESSAGES")
        onCreate(db)
    }
}

/**
 * 对话数据库管理器
 * 提供完整的消息 CRUD 操作和高级查询功能
 */
class ChatDatabase(context: Context) {

    companion object {
        private const val TAG = "ChatDatabase"
        /** 默认最大保留消息数 */
        const val DEFAULT_MAX_MESSAGES = 1000
        /** SharedPreferences 文件名 */
        private const val PREF_NAME = "mindsoul_chat_settings"
        /** 最大保留条数的 Key */
        private const val KEY_MAX_MESSAGES = "max_chat_messages"
    }

    private val dbHelper = ChatDatabaseHelper(context)
    private val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    // ============ 增删改查操作 ============

    /**
     * 插入一条消息到数据库
     *
     * @param message 要插入的消息对象
     * @return 插入行的 ID，失败返回 -1
     */
    fun insertMessage(message: ChatMessage): Long {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put(ChatDatabaseHelper.COL_ID, message.id)
            put(ChatDatabaseHelper.COL_TYPE, message.type.name)
            put(ChatDatabaseHelper.COL_ROLE, message.role.name)
            put(ChatDatabaseHelper.COL_TEXT, message.text)
            put(ChatDatabaseHelper.COL_FILE_PATH, message.filePath)
            put(ChatDatabaseHelper.COL_FILE_NAME, message.fileName)
            put(ChatDatabaseHelper.COL_FILE_SIZE, message.fileSize)
            put(ChatDatabaseHelper.COL_DURATION, message.duration)
            put(ChatDatabaseHelper.COL_TIMESTAMP, message.timestamp)
        }
        val rowId = db.insert(ChatDatabaseHelper.TABLE_MESSAGES, null, values)
        Log.d(TAG, "[插入] 消息 id=${message.id}, rowId=$rowId")
        return rowId
    }

    /**
     * 批量插入消息（事务优化）
     *
     * @param messages 消息列表
     */
    fun insertMessages(messages: List<ChatMessage>) {
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            messages.forEach { message ->
                val values = ContentValues().apply {
                    put(ChatDatabaseHelper.COL_ID, message.id)
                    put(ChatDatabaseHelper.COL_TYPE, message.type.name)
                    put(ChatDatabaseHelper.COL_ROLE, message.role.name)
                    put(ChatDatabaseHelper.COL_TEXT, message.text)
                    put(ChatDatabaseHelper.COL_FILE_PATH, message.filePath)
                    put(ChatDatabaseHelper.COL_FILE_NAME, message.fileName)
                    put(ChatDatabaseHelper.COL_FILE_SIZE, message.fileSize)
                    put(ChatDatabaseHelper.COL_DURATION, message.duration)
                    put(ChatDatabaseHelper.COL_TIMESTAMP, message.timestamp)
                }
                db.insert(ChatDatabaseHelper.TABLE_MESSAGES, null, values)
            }
            db.setTransactionSuccessful()
            Log.i(TAG, "[批量插入] 成功插入 ${messages.size} 条消息")
        } catch (e: Exception) {
            Log.e(TAG, "[批量插入] 失败: ${e.message}")
        } finally {
            db.endTransaction()
        }
    }

    /**
     * 查询所有消息（按时间升序）
     *
     * @return 消息列表
     */
    fun queryAllMessages(): List<ChatMessage> {
        val db = dbHelper.readableDatabase
        val messages = mutableListOf<ChatMessage>()
        val cursor = db.query(
            ChatDatabaseHelper.TABLE_MESSAGES,
            null, null, null, null, null,
            "${ChatDatabaseHelper.COL_TIMESTAMP} ASC"
        )
        cursor.use {
            while (it.moveToNext()) {
                messages.add(cursorToMessage(it))
            }
        }
        Log.d(TAG, "[查询] 获取 ${messages.size} 条消息")
        return messages
    }

    /**
     * 查询最近 N 条消息
     *
     * @param limit 最大返回条数
     * @return 消息列表（按时间升序）
     */
    fun queryRecentMessages(limit: Int): List<ChatMessage> {
        val db = dbHelper.readableDatabase
        val messages = mutableListOf<ChatMessage>()
        val cursor = db.rawQuery(
            """SELECT * FROM ${ChatDatabaseHelper.TABLE_MESSAGES} 
               ORDER BY ${ChatDatabaseHelper.COL_TIMESTAMP} DESC 
               LIMIT $limit""", null
        )
        cursor.use {
            while (it.moveToNext()) {
                messages.add(cursorToMessage(it))
            }
        }
        // 反转为时间升序
        return messages.reversed()
    }

    /**
     * 全文搜索消息
     *
     * @param keyword 搜索关键词
     * @return 匹配的消息列表
     */
    fun searchMessages(keyword: String): List<ChatMessage> {
        if (keyword.isBlank()) return emptyList()
        val db = dbHelper.readableDatabase
        val messages = mutableListOf<ChatMessage>()
        val pattern = "%$keyword%"
        val cursor = db.query(
            ChatDatabaseHelper.TABLE_MESSAGES,
            null,
            "${ChatDatabaseHelper.COL_TEXT} LIKE ?",
            arrayOf(pattern),
            null, null,
            "${ChatDatabaseHelper.COL_TIMESTAMP} ASC"
        )
        cursor.use {
            while (it.moveToNext()) {
                messages.add(cursorToMessage(it))
            }
        }
        Log.d(TAG, "[搜索] 关键词='$keyword', 匹配 ${messages.size} 条")
        return messages
    }

    /**
     * 按 ID 删除单条消息
     *
     * @param messageId 消息 ID
     * @return 删除的行数
     */
    fun deleteMessage(messageId: Long): Int {
        val db = dbHelper.writableDatabase
        val count = db.delete(
            ChatDatabaseHelper.TABLE_MESSAGES,
            "${ChatDatabaseHelper.COL_ID} = ?",
            arrayOf(messageId.toString())
        )
        Log.d(TAG, "[删除] 消息 id=$messageId, 影响行数=$count")
        return count
    }

    /**
     * 清空所有对话记录
     *
     * @return 删除的总行数
     */
    fun deleteAllMessages(): Int {
        val db = dbHelper.writableDatabase
        val count = db.delete(ChatDatabaseHelper.TABLE_MESSAGES, null, null)
        Log.i(TAG, "[清空] 已删除 $count 条消息")
        return count
    }

    /**
     * 获取当前消息总数
     *
     * @return 消息数量
     */
    fun getMessageCount(): Int {
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery(
            "SELECT COUNT(*) FROM ${ChatDatabaseHelper.TABLE_MESSAGES}", null
        )
        cursor.use {
            return if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    // ============ 自动清理策略 ============

    /**
     * 自动清理超出保留条数的旧消息
     * 根据设置中的最大保留条数，删除最早的消息
     *
     * @return 被删除的消息数
     */
    fun autoCleanup(): Int {
        val maxMessages = getMaxRetainCount()
        val totalCount = getMessageCount()
        if (totalCount <= maxMessages) return 0

        val deleteCount = totalCount - maxMessages
        val db = dbHelper.writableDatabase
        val cursor = db.rawQuery(
            """SELECT ${ChatDatabaseHelper.COL_ID} FROM ${ChatDatabaseHelper.TABLE_MESSAGES} 
               ORDER BY ${ChatDatabaseHelper.COL_TIMESTAMP} ASC 
               LIMIT $deleteCount""", null
        )
        val idsToDelete = mutableListOf<String>()
        cursor.use {
            while (it.moveToNext()) {
                idsToDelete.add(it.getLong(0).toString())
            }
        }
        if (idsToDelete.isNotEmpty()) {
            val placeholders = idsToDelete.joinToString(",")
            db.execSQL(
                "DELETE FROM ${ChatDatabaseHelper.TABLE_MESSAGES} WHERE ${ChatDatabaseHelper.COL_ID} IN ($placeholders)"
            )
            Log.i(TAG, "[清理] 已删除 $deleteCount 条过期消息, 保留 $maxMessages 条")
        }
        return deleteCount
    }

    /**
     * 获取设置中的最大保留条数
     */
    fun getMaxRetainCount(): Int {
        return prefs.getInt(KEY_MAX_MESSAGES, DEFAULT_MAX_MESSAGES)
    }

    /**
     * 设置最大保留条数
     */
    fun setMaxRetainCount(maxCount: Int) {
        prefs.edit().putInt(KEY_MAX_MESSAGES, maxCount).apply()
        Log.i(TAG, "[设置] 最大保留条数更新为 $maxCount")
    }

    // ============ 内部工具方法 ============

    /**
     * 将 Cursor 当前行转换为 ChatMessage 对象
     */
    private fun cursorToMessage(cursor: android.database.Cursor): ChatMessage {
        return ChatMessage(
            id = cursor.getLong(cursor.getColumnIndexOrThrow(ChatDatabaseHelper.COL_ID)),
            type = try {
                MessageType.valueOf(cursor.getString(cursor.getColumnIndexOrThrow(ChatDatabaseHelper.COL_TYPE)))
            } catch (e: Exception) {
                MessageType.TEXT
            },
            role = try {
                MessageRole.valueOf(cursor.getString(cursor.getColumnIndexOrThrow(ChatDatabaseHelper.COL_ROLE)))
            } catch (e: Exception) {
                MessageRole.USER
            },
            text = cursor.getString(cursor.getColumnIndexOrThrow(ChatDatabaseHelper.COL_TEXT)),
            filePath = cursor.getString(cursor.getColumnIndexOrThrow(ChatDatabaseHelper.COL_FILE_PATH)),
            fileName = cursor.getString(cursor.getColumnIndexOrThrow(ChatDatabaseHelper.COL_FILE_NAME)),
            fileSize = cursor.getLong(cursor.getColumnIndexOrThrow(ChatDatabaseHelper.COL_FILE_SIZE)),
            duration = cursor.getLong(cursor.getColumnIndexOrThrow(ChatDatabaseHelper.COL_DURATION)),
            timestamp = cursor.getLong(cursor.getColumnIndexOrThrow(ChatDatabaseHelper.COL_TIMESTAMP))
        )
    }

    /**
     * 关闭数据库连接
     */
    fun close() {
        dbHelper.close()
        Log.d(TAG, "[关闭] 数据库连接已关闭")
    }
}
