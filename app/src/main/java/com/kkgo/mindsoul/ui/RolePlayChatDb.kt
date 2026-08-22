/*
 * ============================================================
 * RolePlayChatDb - 角色扮演聊天数据库管理
 * ============================================================
 *
 * 基于 SQLite 的角色扮演聊天持久化管理器，负责：
 * 1. 创建/管理 rp_chat_messages 数据表
 * 2. 按角色卡 ID 隔离聊天记录
 * 3. 消息的增删改查
 *
 * 与主聊天数据库（ChatDatabase）完全隔离，
 * 每个角色卡拥有独立的聊天记录空间。
 * ============================================================
 */
package com.kkgo.mindsoul.ui

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

/**
 * 角色扮演聊天消息数据模型
 */
data class RpChatMessage(
    val id: Long = System.nanoTime(),
    val characterId: Long,
    val role: String = "user",   // "user" 或 "ai"
    val text: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 角色扮演聊天 SQLite 数据库助手
 */
class RpChatDbHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "mindsoul_rp_chat.db"
        private const val DATABASE_VERSION = 1
        private const val TAG = "RpChatDB"

        const val TABLE_MESSAGES = "rp_chat_messages"

        const val COL_ID = "id"
        const val COL_CHARACTER_ID = "character_id"
        const val COL_ROLE = "role"
        const val COL_TEXT = "text"
        const val COL_TIMESTAMP = "timestamp"
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createTable = """
            CREATE TABLE $TABLE_MESSAGES (
                $COL_ID INTEGER PRIMARY KEY,
                $COL_CHARACTER_ID INTEGER NOT NULL,
                $COL_ROLE TEXT NOT NULL DEFAULT 'user',
                $COL_TEXT TEXT NOT NULL DEFAULT '',
                $COL_TIMESTAMP INTEGER NOT NULL
            )
        """.trimIndent()
        db.execSQL(createTable)
        db.execSQL("CREATE INDEX idx_rp_char ON $TABLE_MESSAGES($COL_CHARACTER_ID)")
        db.execSQL("CREATE INDEX idx_rp_time ON $TABLE_MESSAGES($COL_TIMESTAMP)")
        Log.i(TAG, "[建表] rp_chat_messages 表创建完成")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        Log.w(TAG, "[升级] 数据库从 v$oldVersion 升级到 v$newVersion")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_MESSAGES")
        onCreate(db)
    }
}

/**
 * 角色扮演聊天数据库管理器
 */
class RolePlayChatDatabase(context: Context) {

    companion object {
        private const val TAG = "RpChatDatabase"
    }

    private val dbHelper = RpChatDbHelper(context)

    /**
     * 插入一条聊天消息
     */
    fun insertMessage(message: RpChatMessage): Long {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put(RpChatDbHelper.COL_ID, message.id)
            put(RpChatDbHelper.COL_CHARACTER_ID, message.characterId)
            put(RpChatDbHelper.COL_ROLE, message.role)
            put(RpChatDbHelper.COL_TEXT, message.text)
            put(RpChatDbHelper.COL_TIMESTAMP, message.timestamp)
        }
        return db.insert(RpChatDbHelper.TABLE_MESSAGES, null, values)
    }

    /**
     * 获取指定角色的所有聊天消息（按时间升序）
     */
    fun getMessagesByCharacter(characterId: Long): List<RpChatMessage> {
        val db = dbHelper.readableDatabase
        val messages = mutableListOf<RpChatMessage>()
        val cursor = db.query(
            RpChatDbHelper.TABLE_MESSAGES,
            null,
            "${RpChatDbHelper.COL_CHARACTER_ID} = ?",
            arrayOf(characterId.toString()),
            null, null,
            "${RpChatDbHelper.COL_TIMESTAMP} ASC"
        )
        cursor.use {
            while (it.moveToNext()) {
                messages.add(cursorToMessage(it))
            }
        }
        return messages
    }

    /**
     * 删除指定角色的所有聊天记录
     */
    fun deleteMessagesByCharacter(characterId: Long): Int {
        val db = dbHelper.writableDatabase
        return db.delete(
            RpChatDbHelper.TABLE_MESSAGES,
            "${RpChatDbHelper.COL_CHARACTER_ID} = ?",
            arrayOf(characterId.toString())
        )
    }

    /**
     * 将 Cursor 当前行转换为 RpChatMessage
     */
    private fun cursorToMessage(cursor: android.database.Cursor): RpChatMessage {
        return RpChatMessage(
            id = cursor.getLong(cursor.getColumnIndexOrThrow(RpChatDbHelper.COL_ID)),
            characterId = cursor.getLong(cursor.getColumnIndexOrThrow(RpChatDbHelper.COL_CHARACTER_ID)),
            role = cursor.getString(cursor.getColumnIndexOrThrow(RpChatDbHelper.COL_ROLE)),
            text = cursor.getString(cursor.getColumnIndexOrThrow(RpChatDbHelper.COL_TEXT)),
            timestamp = cursor.getLong(cursor.getColumnIndexOrThrow(RpChatDbHelper.COL_TIMESTAMP))
        )
    }

    /**
     * 关闭数据库连接
     */
    fun close() {
        dbHelper.close()
    }
}
