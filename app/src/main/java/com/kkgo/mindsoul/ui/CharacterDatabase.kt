/*
 * ============================================================
 * CharacterDatabase - 角色卡数据库管理
 * ============================================================
 *
 * 基于 SQLite 的角色卡持久化管理器，负责：
 * 1. 创建/管理 character_cards 数据表
 * 2. 角色卡的增删改查（CRUD）
 * 3. 按创建时间倒序排列
 *
 * 数据库结构：
 * - character_cards: 存储所有角色卡
 *   - id: 主键（自增）
 *   - name: 角色名称
 *   - gender: 性别（男/女/双性男/双性女）
 *   - age: 年龄描述
 *   - avatar_path: 头像文件路径
 *   - personality: 角色设定（详细描述）
 *   - created_at: 创建时间戳
 *   - updated_at: 更新时间戳
 * ============================================================
 */
package com.kkgo.mindsoul.ui

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

/**
 * 角色卡数据模型
 */
data class CharacterCard(
    val id: Long = 0,
    val name: String,
    val gender: String,
    val age: String,
    val avatarPath: String? = null,
    val personality: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * 角色卡 SQLite 数据库助手
 */
class CharacterDbHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "mindsoul_characters.db"
        private const val DATABASE_VERSION = 1
        private const val TAG = "CharacterDB"

        // 表名
        const val TABLE_CHARACTERS = "character_cards"

        // 列名
        const val COL_ID = "id"
        const val COL_NAME = "name"
        const val COL_GENDER = "gender"
        const val COL_AGE = "age"
        const val COL_AVATAR_PATH = "avatar_path"
        const val COL_PERSONALITY = "personality"
        const val COL_CREATED_AT = "created_at"
        const val COL_UPDATED_AT = "updated_at"
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createTable = """
            CREATE TABLE $TABLE_CHARACTERS (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_NAME TEXT NOT NULL,
                $COL_GENDER TEXT NOT NULL DEFAULT '男',
                $COL_AGE TEXT NOT NULL DEFAULT '',
                $COL_AVATAR_PATH TEXT,
                $COL_PERSONALITY TEXT NOT NULL DEFAULT '',
                $COL_CREATED_AT INTEGER NOT NULL,
                $COL_UPDATED_AT INTEGER NOT NULL
            )
        """.trimIndent()
        db.execSQL(createTable)
        db.execSQL("CREATE INDEX idx_char_created ON $TABLE_CHARACTERS($COL_CREATED_AT)")
        Log.i(TAG, "[建表] character_cards 表创建完成")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        Log.w(TAG, "[升级] 数据库从 v$oldVersion 升级到 v$newVersion")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_CHARACTERS")
        onCreate(db)
    }
}

/**
 * 角色卡数据库管理器
 * 提供完整的角色卡 CRUD 操作
 */
class CharacterDatabase(context: Context) {

    companion object {
        private const val TAG = "CharacterDatabase"
    }

    private val dbHelper = CharacterDbHelper(context)

    /**
     * 插入新角色卡
     *
     * @param character 角色卡对象
     * @return 插入行的 ID，失败返回 -1
     */
    fun insertCharacter(character: CharacterCard): Long {
        val db = dbHelper.writableDatabase
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put(CharacterDbHelper.COL_NAME, character.name)
            put(CharacterDbHelper.COL_GENDER, character.gender)
            put(CharacterDbHelper.COL_AGE, character.age)
            put(CharacterDbHelper.COL_AVATAR_PATH, character.avatarPath)
            put(CharacterDbHelper.COL_PERSONALITY, character.personality)
            put(CharacterDbHelper.COL_CREATED_AT, now)
            put(CharacterDbHelper.COL_UPDATED_AT, now)
        }
        val rowId = db.insert(CharacterDbHelper.TABLE_CHARACTERS, null, values)
        Log.d(TAG, "[插入] 角色 '${character.name}', rowId=$rowId")
        return rowId
    }

    /**
     * 更新角色卡
     *
     * @param character 角色卡对象（需包含有效 id）
     * @return 更新的行数
     */
    fun updateCharacter(character: CharacterCard): Int {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put(CharacterDbHelper.COL_NAME, character.name)
            put(CharacterDbHelper.COL_GENDER, character.gender)
            put(CharacterDbHelper.COL_AGE, character.age)
            put(CharacterDbHelper.COL_AVATAR_PATH, character.avatarPath)
            put(CharacterDbHelper.COL_PERSONALITY, character.personality)
            put(CharacterDbHelper.COL_UPDATED_AT, System.currentTimeMillis())
        }
        val count = db.update(
            CharacterDbHelper.TABLE_CHARACTERS,
            values,
            "${CharacterDbHelper.COL_ID} = ?",
            arrayOf(character.id.toString())
        )
        Log.d(TAG, "[更新] 角色 id=${character.id}, 影响行数=$count")
        return count
    }

    /**
     * 删除角色卡
     *
     * @param characterId 角色卡 ID
     * @return 删除的行数
     */
    fun deleteCharacter(characterId: Long): Int {
        val db = dbHelper.writableDatabase
        val count = db.delete(
            CharacterDbHelper.TABLE_CHARACTERS,
            "${CharacterDbHelper.COL_ID} = ?",
            arrayOf(characterId.toString())
        )
        Log.d(TAG, "[删除] 角色 id=$characterId, 影响行数=$count")
        return count
    }

    /**
     * 获取所有角色卡（按创建时间倒序）
     *
     * @return 角色卡列表
     */
    fun getAllCharacters(): List<CharacterCard> {
        val db = dbHelper.readableDatabase
        val characters = mutableListOf<CharacterCard>()
        val cursor = db.query(
            CharacterDbHelper.TABLE_CHARACTERS,
            null, null, null, null, null,
            "${CharacterDbHelper.COL_CREATED_AT} DESC"
        )
        cursor.use {
            while (it.moveToNext()) {
                characters.add(cursorToCharacter(it))
            }
        }
        Log.d(TAG, "[查询] 获取 ${characters.size} 个角色卡")
        return characters
    }

    /**
     * 按 ID 获取角色卡
     *
     * @param characterId 角色卡 ID
     * @return 角色卡对象，未找到返回 null
     */
    fun getCharacterById(characterId: Long): CharacterCard? {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            CharacterDbHelper.TABLE_CHARACTERS,
            null,
            "${CharacterDbHelper.COL_ID} = ?",
            arrayOf(characterId.toString()),
            null, null, null
        )
        cursor.use {
            return if (it.moveToFirst()) cursorToCharacter(it) else null
        }
    }

    /**
     * 获取角色卡总数
     */
    fun getCharacterCount(): Int {
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery(
            "SELECT COUNT(*) FROM ${CharacterDbHelper.TABLE_CHARACTERS}", null
        )
        cursor.use {
            return if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    /**
     * 将 Cursor 当前行转换为 CharacterCard 对象
     */
    private fun cursorToCharacter(cursor: android.database.Cursor): CharacterCard {
        return CharacterCard(
            id = cursor.getLong(cursor.getColumnIndexOrThrow(CharacterDbHelper.COL_ID)),
            name = cursor.getString(cursor.getColumnIndexOrThrow(CharacterDbHelper.COL_NAME)),
            gender = cursor.getString(cursor.getColumnIndexOrThrow(CharacterDbHelper.COL_GENDER)),
            age = cursor.getString(cursor.getColumnIndexOrThrow(CharacterDbHelper.COL_AGE)),
            avatarPath = cursor.getString(cursor.getColumnIndexOrThrow(CharacterDbHelper.COL_AVATAR_PATH)),
            personality = cursor.getString(cursor.getColumnIndexOrThrow(CharacterDbHelper.COL_PERSONALITY)),
            createdAt = cursor.getLong(cursor.getColumnIndexOrThrow(CharacterDbHelper.COL_CREATED_AT)),
            updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow(CharacterDbHelper.COL_UPDATED_AT))
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
