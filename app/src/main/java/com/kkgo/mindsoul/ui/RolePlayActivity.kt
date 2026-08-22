/*
 * ============================================================
 * RolePlayActivity - 角色扮演主界面（角色卡列表）
 * ============================================================
 *
 * 核心功能：
 * 1. RecyclerView 展示所有角色卡
 * 2. 右上角"+"按钮 → 跳转到 AddCharacterActivity
 * 3. 点击角色卡 → 跳转到 RolePlayChatActivity
 * 4. 长按角色卡 → 弹出菜单：修改角色、删除角色
 *
 * 对接模块：
 * - CharacterDatabase（角色卡持久化）
 * ============================================================
 */
package com.kkgo.mindsoul.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.kkgo.mindsoul.R
import kotlinx.coroutines.*

class RolePlayActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "RolePlayActivity"
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ============ 数据库 ============
    private lateinit var characterDatabase: CharacterDatabase

    // ============ 界面元素 ============
    private lateinit var recyclerCharacters: RecyclerView
    private lateinit var fabAddCharacter: FloatingActionButton
    private lateinit var layoutEmpty: View
    private lateinit var adapter: CharacterAdapter

    // ============ 数据 ============
    private val characters = mutableListOf<CharacterCard>()

    /** 从 AddCharacterActivity 返回时刷新列表 */
    private val addCharacterLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        // 不论结果如何都刷新列表
        refreshCharacterList()
    }

    // ============ 生命周期 ============

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_role_play)

        characterDatabase = CharacterDatabase(this)

        initViews()
        setupToolbar()
        setupRecyclerView()
        setupFab()
    }

    override fun onResume() {
        super.onResume()
        refreshCharacterList()
    }

    override fun onDestroy() {
        scope.cancel()
        characterDatabase.close()
        super.onDestroy()
    }

    // ============ 初始化 ============

    private fun initViews() {
        recyclerCharacters = findViewById(R.id.recyclerCharacters)
        fabAddCharacter = findViewById(R.id.fabAddCharacter)
        layoutEmpty = findViewById(R.id.layoutEmpty)
    }

    private fun setupToolbar() {
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupRecyclerView() {
        adapter = CharacterAdapter(
            characters = characters,
            onClick = { character -> openChat(character) },
            onLongClick = { character -> showCharacterMenu(character) }
        )
        recyclerCharacters.layoutManager = LinearLayoutManager(this)
        recyclerCharacters.adapter = adapter
    }

    private fun setupFab() {
        fabAddCharacter.setOnClickListener {
            val intent = Intent(this, AddCharacterActivity::class.java)
            addCharacterLauncher.launch(intent)
        }
    }

    // ============ 数据加载 ============

    private fun refreshCharacterList() {
        scope.launch {
            val data = withContext(Dispatchers.Default) {
                characterDatabase.getAllCharacters()
            }
            characters.clear()
            characters.addAll(data)
            adapter.notifyDataSetChanged()

            // 更新空状态显示
            layoutEmpty.visibility = if (characters.isEmpty()) View.VISIBLE else View.GONE
            recyclerCharacters.visibility = if (characters.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    // ============ 导航 ============

    /**
     * 打开角色聊天界面
     */
    private fun openChat(character: CharacterCard) {
        val intent = Intent(this, RolePlayChatActivity::class.java).apply {
            putExtra("character_id", character.id)
            putExtra("character_name", character.name)
            putExtra("character_gender", character.gender)
            putExtra("character_age", character.age)
            putExtra("character_avatar", character.avatarPath)
            putExtra("character_personality", character.personality)
        }
        startActivity(intent)
    }

    /**
     * 显示角色卡操作菜单
     */
    private fun showCharacterMenu(character: CharacterCard) {
        val options = arrayOf("修改角色", "删除角色")
        AlertDialog.Builder(this)
            .setTitle(character.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> editCharacter(character)
                    1 -> confirmDeleteCharacter(character)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 跳转到编辑角色界面
     */
    private fun editCharacter(character: CharacterCard) {
        val intent = Intent(this, AddCharacterActivity::class.java).apply {
            putExtra("edit_mode", true)
            putExtra("character_id", character.id)
            putExtra("character_name", character.name)
            putExtra("character_gender", character.gender)
            putExtra("character_age", character.age)
            putExtra("character_avatar", character.avatarPath)
            putExtra("character_personality", character.personality)
        }
        addCharacterLauncher.launch(intent)
    }

    /**
     * 确认删除角色
     */
    private fun confirmDeleteCharacter(character: CharacterCard) {
        AlertDialog.Builder(this)
            .setTitle("删除角色")
            .setMessage("确定要删除「${character.name}」吗？相关的聊天记录也将被删除。")
            .setPositiveButton("确认删除") { _, _ ->
                deleteCharacter(character)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 删除角色及其聊天记录
     */
    private fun deleteCharacter(character: CharacterCard) {
        scope.launch {
            withContext(Dispatchers.Default) {
                characterDatabase.deleteCharacter(character.id)
                // 同时删除该角色的聊天记录
                val rpChatDb = RolePlayChatDatabase(this@RolePlayActivity)
                rpChatDb.deleteMessagesByCharacter(character.id)
                rpChatDb.close()
            }
            refreshCharacterList()
        }
    }
}

// ============================================================
// CharacterAdapter - 角色卡列表适配器
// ============================================================

/**
 * 角色卡列表适配器
 *
 * @param characters 角色卡数据列表
 * @param onClick 点击角色卡回调
 * @param onLongClick 长按角色卡回调
 */
class CharacterAdapter(
    private val characters: MutableList<CharacterCard>,
    private val onClick: (CharacterCard) -> Unit,
    private val onLongClick: (CharacterCard) -> Unit
) : RecyclerView.Adapter<CharacterAdapter.CharacterViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CharacterViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_character_card, parent, false)
        return CharacterViewHolder(view)
    }

    override fun onBindViewHolder(holder: CharacterViewHolder, position: Int) {
        holder.bind(characters[position])
    }

    override fun getItemCount(): Int = characters.size

    /**
     * 角色卡 ViewHolder
     */
    inner class CharacterViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivAvatar: ImageView = itemView.findViewById(R.id.ivAvatar)
        private val tvName: TextView = itemView.findViewById(R.id.tvName)
        private val tvGender: TextView = itemView.findViewById(R.id.tvGender)
        private val tvAge: TextView = itemView.findViewById(R.id.tvAge)
        private val tvPersonality: TextView = itemView.findViewById(R.id.tvPersonality)

        fun bind(character: CharacterCard) {
            // 头像
            if (!character.avatarPath.isNullOrEmpty()) {
                try {
                    ivAvatar.setImageURI(Uri.parse(character.avatarPath))
                } catch (e: Exception) {
                    ivAvatar.setBackgroundColor(itemView.context.getColor(R.color.soul_surface_light))
                }
            } else {
                ivAvatar.setBackgroundColor(itemView.context.getColor(R.color.soul_surface_light))
            }

            // 姓名
            tvName.text = character.name

            // 性别标签（带颜色）
            tvGender.text = character.gender
            val genderColor = when (character.gender) {
                "男" -> Color.parseColor("#4A90D9")
                "女" -> Color.parseColor("#D94A90")
                "双性男" -> Color.parseColor("#9B4AD9")
                "双性女" -> Color.parseColor("#D94AD9")
                else -> Color.parseColor("#6C5CE7")
            }
            val genderBg = GradientDrawable().apply {
                setColor(genderColor)
                cornerRadius = 8f
            }
            tvGender.background = genderBg

            // 年龄
            tvAge.text = if (character.age.isNotEmpty()) "${character.age}岁" else ""

            // 角色简介（取前两行）
            tvPersonality.text = character.personality.ifBlank { "暂无设定" }

            // 点击事件
            itemView.setOnClickListener { onClick(character) }
            itemView.setOnLongClickListener {
                onLongClick(character)
                true
            }
        }
    }
}
