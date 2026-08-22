/*
 * ============================================================
 * RolePlayChatActivity - 角色扮演聊天界面
 * ============================================================
 *
 * 核心功能：
 * 1. 类似 ChatActivity 的聊天界面（纯文本消息）
 * 2. 顶部显示当前角色名称和头像
 * 3. AI回复基于角色设定（角色设定作为 system prompt）
 * 4. 不触发AI命令执行（不调用 NaturalLanguageExecutor）
 * 5. 使用独立的聊天记录存储（与主聊天隔离）
 *
 * 关键区别（vs ChatActivity）：
 * - 无文件/图片/语音/视频/附件功能
 * - 无知识库学习入口
 * - 无TTS自动朗读
 * - 不调用 NaturalLanguageExecutor
 * - 聊天记录按角色ID隔离
 *
 * 修复项：
 * - 协程 scope 生命周期绑定，避免 Activity 销毁后操作 UI 闪退
 * - 所有可能抛异常的调用添加 try-catch 保护
 * - 键盘弹起时输入框自动适配
 *
 * 对接模块：
 * - RolePlayChatDatabase（独立聊天持久化）
 * ============================================================
 */
package com.kkgo.mindsoul.ui

import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewTreeObserver
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kkgo.mindsoul.R
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

class RolePlayChatActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "RolePlayChatActivity"
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ============ 数据库 ============
    private lateinit var rpChatDatabase: RolePlayChatDatabase

    // ============ 角色信息 ============
    private var characterId: Long = 0
    private var characterName: String = ""
    private var characterGender: String = ""
    private var characterAge: String = ""
    private var characterAvatar: String? = null
    private var characterPersonality: String = ""

    // ============ 界面元素 ============
    private lateinit var ivCharAvatar: ImageView
    private lateinit var tvCharName: TextView
    private lateinit var tvCharGender: TextView
    private lateinit var btnBack: ImageButton
    private lateinit var btnClearChat: ImageButton
    private lateinit var recyclerMessages: RecyclerView
    private lateinit var etInput: EditText
    private lateinit var btnSend: ImageButton
    private lateinit var btnStopGenerate: ImageButton

    private lateinit var adapter: RpChatAdapter

    /** AI是否正在生成回复 */
    private var isGenerating: Boolean = false
    /** 当前AI生成任务的协程Job */
    private var currentGenerateJob: Job? = null
    /** Activity 是否已销毁 */
    private var isDestroyed = false

    /** 聊天上下文：保存最近的对话用于生成上下文 */
    private val chatHistory = mutableListOf<RpChatMessage>()

    // ============ 键盘适配 ============
    /** 根布局引用，用于键盘弹起时适配 */
    private var rootView: View? = null
    /** 键盘可见性监听器 */
    private var keyboardLayoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null

    // ============ 生命周期 ============

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_role_play_chat)
        isDestroyed = false

        rpChatDatabase = RolePlayChatDatabase(this)

        loadCharacterFromIntent()
        initViews()
        setupHeader()
        setupRecyclerView()
        setupInput()
        setupClearChat()
        setupKeyboardAdapter()
        loadChatHistory()
    }

    override fun onResume() {
        super.onResume()
        isDestroyed = false
    }

    override fun onDestroy() {
        isDestroyed = true
        currentGenerateJob?.cancel()
        scope.cancel()

        // 移除键盘监听
        keyboardLayoutListener?.let { listener ->
            rootView?.viewTreeObserver?.removeOnGlobalLayoutListener(listener)
        }
        keyboardLayoutListener = null
        rootView = null

        try {
            rpChatDatabase.close()
        } catch (_: Exception) {}
        super.onDestroy()
    }

    // ============ 初始化 ============

    /**
     * 从 Intent 加载角色信息
     */
    private fun loadCharacterFromIntent() {
        characterId = intent.getLongExtra("character_id", 0)
        characterName = intent.getStringExtra("character_name") ?: "未知角色"
        characterGender = intent.getStringExtra("character_gender") ?: ""
        characterAge = intent.getStringExtra("character_age") ?: ""
        characterAvatar = intent.getStringExtra("character_avatar")
        characterPersonality = intent.getStringExtra("character_personality") ?: ""
    }

    private fun initViews() {
        ivCharAvatar = findViewById(R.id.ivCharAvatar)
        tvCharName = findViewById(R.id.tvCharName)
        tvCharGender = findViewById(R.id.tvCharGender)
        btnBack = findViewById(R.id.btnBack)
        btnClearChat = findViewById(R.id.btnClearChat)
        recyclerMessages = findViewById(R.id.recyclerMessages)
        etInput = findViewById(R.id.etInput)
        btnSend = findViewById(R.id.btnSend)
        btnStopGenerate = findViewById(R.id.btnStopGenerate)
        // 根布局
        rootView = findViewById(android.R.id.content)
    }

    /**
     * 设置顶部角色信息栏
     */
    private fun setupHeader() {
        tvCharName.text = characterName
        val genderAge = buildString {
            if (characterGender.isNotEmpty()) append(characterGender)
            if (characterAge.isNotEmpty()) {
                if (isNotEmpty()) append(" · ")
                append("${characterAge}岁")
            }
        }
        tvCharGender.text = genderAge

        // 加载头像
        if (!characterAvatar.isNullOrEmpty()) {
            try {
                ivCharAvatar.setImageURI(Uri.parse(characterAvatar))
            } catch (e: Exception) {
                ivCharAvatar.setBackgroundColor(getColor(R.color.soul_surface_light))
            }
        } else {
            ivCharAvatar.setBackgroundColor(getColor(R.color.soul_surface_light))
        }

        // 返回按钮
        btnBack.setOnClickListener { finish() }
    }

    private fun setupRecyclerView() {
        adapter = RpChatAdapter()
        recyclerMessages.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        recyclerMessages.adapter = adapter
    }

    private fun setupInput() {
        // 发送按钮
        btnSend.setOnClickListener { sendTextMessage() }

        // 终止生成按钮
        btnStopGenerate.setOnClickListener {
            currentGenerateJob?.cancel()
            currentGenerateJob = null
            isGenerating = false
            btnStopGenerate.visibility = View.GONE
            btnSend.visibility = View.VISIBLE
            Toast.makeText(this, "已停止生成", Toast.LENGTH_SHORT).show()
        }

        // 输入框回车发送
        etInput.setOnEditorActionListener { _, _, _ ->
            sendTextMessage()
            true
        }
    }

    // ============ 键盘适配 ============

    /**
     * 设置键盘弹起/收起适配
     * 监听根布局高度变化，键盘弹起时滚动到底部
     */
    private fun setupKeyboardAdapter() {
        val root = rootView ?: return
        keyboardLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
            val rect = Rect()
            root.getWindowVisibleDisplayFrame(rect)
            val screenHeight = root.rootView.height
            val keypadHeight = screenHeight - rect.bottom

            // 键盘高度超过屏幕15%视为键盘弹起
            if (keypadHeight > screenHeight * 0.15) {
                // 键盘弹起：滚动到最新消息
                scrollToBottom()
            }
        }
        root.viewTreeObserver.addOnGlobalLayoutListener(keyboardLayoutListener)
    }

    private fun setupClearChat() {
        btnClearChat.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("清空聊天")
                .setMessage("确定要清空与「$characterName」的所有聊天记录吗？")
                .setPositiveButton("确认清空") { _, _ ->
                    clearChatHistory()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    // ============ 聊天历史 ============

    /**
     * 从数据库加载聊天记录
     */
    private fun loadChatHistory() {
        scope.launch {
            try {
                val messages = withContext(Dispatchers.Default) {
                    rpChatDatabase.getMessagesByCharacter(characterId)
                }

                if (isDestroyed) return@launch

                chatHistory.clear()
                chatHistory.addAll(messages)

                if (messages.isEmpty()) {
                    // 添加角色欢迎语
                    val welcomeText = generateWelcomeText()
                    val welcomeMsg = RpChatMessage(
                        characterId = characterId,
                        role = "ai",
                        text = welcomeText
                    )
                    chatHistory.add(welcomeMsg)
                    adapter.addMessage("ai", welcomeText)
                    saveMessageToDb(welcomeMsg)
                } else {
                    // 恢复历史消息
                    messages.forEach { msg ->
                        adapter.addMessage(msg.role, msg.text)
                    }
                }
                scrollToBottom()
            } catch (e: Exception) {
                Log.e(TAG, "加载聊天记录失败: ${e.message}")
            }
        }
    }

    /**
     * 生成角色欢迎语
     */
    private fun generateWelcomeText(): String {
        return when {
            characterPersonality.isNotBlank() -> {
                val name = characterName
                val gender = characterGender
                "*$name${getGenderAction(gender)}微笑着看向你*\n\n嗨~我是$name。很高兴认识你，想和我聊些什么呢？"
            }
            else -> "你好！我是$characterName，很高兴认识你。"
        }
    }

    /**
     * 根据性别返回动作描述词
     */
    private fun getGenderAction(gender: String): String {
        return when (gender) {
            "男" -> ""
            "女" -> ""
            "双性男" -> ""
            "双性女" -> ""
            else -> ""
        }
    }

    /**
     * 清空聊天记录
     */
    private fun clearChatHistory() {
        scope.launch {
            try {
                withContext(Dispatchers.Default) {
                    rpChatDatabase.deleteMessagesByCharacter(characterId)
                }
                if (isDestroyed) return@launch

                chatHistory.clear()
                adapter.clearMessages()

                // 重新添加欢迎语
                val welcomeText = generateWelcomeText()
                val welcomeMsg = RpChatMessage(
                    characterId = characterId,
                    role = "ai",
                    text = welcomeText
                )
                chatHistory.add(welcomeMsg)
                adapter.addMessage("ai", welcomeText)
                saveMessageToDb(welcomeMsg)
                scrollToBottom()

                Toast.makeText(this@RolePlayChatActivity, "聊天记录已清空", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e(TAG, "清空聊天记录失败: ${e.message}")
                Toast.makeText(this@RolePlayChatActivity, "清空失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ============ 消息发送 ============

    /**
     * 发送文字消息
     */
    private fun sendTextMessage() {
        val text = etInput.text.toString().trim()
        if (text.isEmpty()) return

        // 创建用户消息
        val message = RpChatMessage(
            characterId = characterId,
            role = "user",
            text = text
        )

        // 添加到界面和历史
        chatHistory.add(message)
        adapter.addMessage("user", text)
        saveMessageToDb(message)
        etInput.text.clear()
        scrollToBottom()

        // 隐藏键盘
        try {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(etInput.windowToken, 0)
        } catch (_: Exception) {}

        // 生成角色 AI 回复
        generateCharacterResponse(text)
    }

    /**
     * 保存消息到数据库
     */
    private fun saveMessageToDb(message: RpChatMessage) {
        scope.launch {
            try {
                withContext(Dispatchers.Default) {
                    rpChatDatabase.insertMessage(message)
                }
            } catch (e: Exception) {
                Log.e(TAG, "保存消息失败: ${e.message}")
            }
        }
    }

    // ============ AI 回复生成（角色扮演模式） ============

    /**
     * 生成角色 AI 回复
     *
     * 关键区别：
     * - 不调用 NaturalLanguageExecutor
     * - 不执行任何高级命令
     * - 纯粹的角色扮演对话
     * - 角色设定作为 system prompt 上下文
     *
     * 当前使用模拟回复，预留 LLM 对接接口：
     * 实际对接时，将 characterPersonality + chatHistory 发送给 LLM API
     */
    private fun generateCharacterResponse(userInput: String) {
        isGenerating = true
        btnStopGenerate.visibility = View.VISIBLE
        btnSend.visibility = View.GONE

        val job = scope.launch {
            try {
                // 模拟思考延迟
                delay(800)

                val response = withContext(Dispatchers.Default) {
                    generateInCharacterResponse(userInput)
                }

                // 检查是否被取消
                ensureActive()
                if (isDestroyed) return@launch

                val message = RpChatMessage(
                    characterId = characterId,
                    role = "ai",
                    text = response
                )
                chatHistory.add(message)
                adapter.addMessage("ai", response)
                saveMessageToDb(message)
                scrollToBottom()

            } catch (_: CancellationException) {
                Log.d(TAG, "[角色回复] 生成已被用户终止")
            } catch (e: Exception) {
                Log.e(TAG, "[角色回复] 生成失败: ${e.message}")
                if (!isDestroyed) {
                    val errorMsg = RpChatMessage(
                        characterId = characterId,
                        role = "ai",
                        text = "⚠️ 回复生成失败，请重试。"
                    )
                    chatHistory.add(errorMsg)
                    adapter.addMessage("ai", errorMsg.text)
                    saveMessageToDb(errorMsg)
                }
            } finally {
                if (!isDestroyed) {
                    isGenerating = false
                    btnStopGenerate.visibility = View.GONE
                    btnSend.visibility = View.VISIBLE
                }
                currentGenerateJob = null
            }
        }
        currentGenerateJob = job
    }

    /**
     * 生成角色内回复
     *
     * 构建上下文（预留 LLM 接口）：
     * system prompt = characterPersonality
     * messages = chatHistory (最近N条)
     *
     * 当前使用基于角色设定的模拟回复
     */
    private fun generateInCharacterResponse(userInput: String): String {
        val name = characterName
        val personality = characterPersonality

        // 构建发送给 LLM 的完整上下文（预留）
        val systemPrompt = buildString {
            appendLine("你是一个角色扮演AI。你必须完全扮演以下角色，不要跳出角色。")
            appendLine("这是纯娱乐对话，不需要执行任何命令或操作。")
            appendLine()
            appendLine("【角色信息】")
            appendLine("姓名：$name")
            appendLine("性别：$characterGender")
            if (characterAge.isNotEmpty()) appendLine("年龄：${characterAge}岁")
            appendLine()
            appendLine("【角色设定】")
            appendLine(personality)
            appendLine()
            appendLine("请保持角色一致性，用符合角色性格的方式回复。")
            appendLine("回复应自然、生动，可以适当加入动作描写（用*号包裹）。")
        }

        // 当前模拟回复逻辑
        // TODO: 对接真实 LLM API，传入 systemPrompt + chatHistory
        return generateSimulatedResponse(userInput, name, personality)
    }

    /**
     * 模拟角色回复（临时方案，待对接 LLM）
     */
    private fun generateSimulatedResponse(input: String, charName: String, personality: String): String {
        // 从角色设定中提取关键词，生成更相关的回复
        val isShy = personality.contains("害羞") || personality.contains("内向")
        val isOutgoing = personality.contains("开朗") || personality.contains("活泼") || personality.contains("外向")
        val isCold = personality.contains("冷淡") || personality.contains("高冷") || personality.contains("冷漠")
        val isGentle = personality.contains("温柔") || personality.contains("善良") || personality.contains("体贴")

        return when {
            // 问候类
            input.contains("你好") || input.contains("嗨") || input.contains("hi") || input.contains("hello") -> {
                when {
                    isShy -> "*${charName}微微低头，声音很小* 嗯...你好..."
                    isOutgoing -> "*${charName}开心地挥挥手* 嗨嗨！你来啦！好开心见到你~"
                    isCold -> "*${charName}微微点头* ...嗯。"
                    isGentle -> "*${charName}温柔地微笑* 你好呀，很高兴见到你~"
                    else -> "*${charName}看向你* 你好~"
                }
            }
            // 问名字
            input.contains("名字") || input.contains("叫什么") -> {
                when {
                    isOutgoing -> "我叫${charName}呀！你记住了吗？嘿嘿~"
                    isCold -> "$charName。"
                    isShy -> "*小声地* 我...我叫$charName..."
                    else -> "我是$charName，请多指教。"
                }
            }
            // 情感类
            input.contains("喜欢") || input.contains("爱") -> {
                when {
                    isShy -> "*${charName}脸红了* 你...你在说什么呢..."
                    isOutgoing -> "嘻嘻，你是在撩我吗？那我告诉你，我也挺喜欢你的~"
                    isCold -> "*${charName}移开了视线* ...无聊。"
                    isGentle -> "*${charName}微微一笑* 谢谢你，这让我很开心。"
                    else -> "*${charName}看着你，眼神有些复杂* ..."
                }
            }
            // 提问类
            input.contains("?") || input.contains("？") -> {
                "*${charName}想了想* 这个问题嘛...让我好好想想该怎么回答你。"
            }
            // 安慰/关心类
            input.contains("难过") || input.contains("伤心") || input.contains("不开心") -> {
                when {
                    isGentle -> "*${charName}轻轻拍了拍你的肩膀* 别难过了，一切都会好起来的。我会一直在你身边。"
                    isOutgoing -> "*${charName}抱住你* 别不开心啦！我陪你玩好不好？"
                    isCold -> "...别想太多。"
                    else -> "*${charName}默默陪在你身边*"
                }
            }
            // 默认回复
            else -> {
                val responses = listOf(
                    "*${charName}认真地听着你说的*",
                    "*${charName}点了点头* 我明白了。",
                    "*${charName}思考了一下你的话* 嗯...原来是这样啊。",
                    "*${charName}微微一笑* 继续说，我在听。",
                    "*${charName}歪了歪头* 嗯？然后呢？"
                )
                responses.random()
            }
        }
    }

    // ============ 工具方法 ============

    private fun scrollToBottom() {
        if (isDestroyed) return
        try {
            if (adapter.itemCount > 0) {
                recyclerMessages.smoothScrollToPosition(adapter.itemCount - 1)
            }
        } catch (e: Exception) {
            Log.w(TAG, "滚动失败: ${e.message}")
        }
    }
}

// ============================================================
// RpChatAdapter - 角色扮演聊天消息适配器
// ============================================================

/**
 * 角色扮演聊天消息列表适配器（简化版，仅支持文本）
 */
class RpChatAdapter : RecyclerView.Adapter<RpChatAdapter.MessageViewHolder>() {

    data class DisplayMessage(
        val role: String, // "user" 或 "ai"
        val text: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val messages = mutableListOf<DisplayMessage>()
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    fun addMessage(role: String, text: String) {
        messages.add(DisplayMessage(role, text))
        notifyItemInserted(messages.size - 1)
    }

    fun clearMessages() {
        val size = messages.size
        messages.clear()
        notifyItemRangeRemoved(0, size)
    }

    override fun getItemViewType(position: Int): Int {
        return when (messages[position].role) {
            "user" -> 0
            "ai" -> 1
            else -> 2 // system
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_rp_chat_message, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        holder.bind(messages[position])
    }

    override fun getItemCount(): Int = messages.size

    inner class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvSystem: TextView = itemView.findViewById(R.id.tvSystem)
        private val layoutAi: View = itemView.findViewById(R.id.layoutAi)
        private val tvAiText: TextView = itemView.findViewById(R.id.tvAiText)
        private val tvAiTime: TextView = itemView.findViewById(R.id.tvAiTime)
        private val layoutUser: View = itemView.findViewById(R.id.layoutUser)
        private val tvUserText: TextView = itemView.findViewById(R.id.tvUserText)
        private val tvUserTime: TextView = itemView.findViewById(R.id.tvUserTime)

        fun bind(msg: DisplayMessage) {
            // 隐藏所有
            tvSystem.visibility = View.GONE
            layoutAi.visibility = View.GONE
            layoutUser.visibility = View.GONE

            when (msg.role) {
                "ai" -> {
                    layoutAi.visibility = View.VISIBLE
                    tvAiText.text = msg.text
                    tvAiTime.text = timeFormat.format(Date(msg.timestamp))
                }
                "user" -> {
                    layoutUser.visibility = View.VISIBLE
                    tvUserText.text = msg.text
                    tvUserTime.text = timeFormat.format(Date(msg.timestamp))
                }
                else -> {
                    tvSystem.visibility = View.VISIBLE
                    tvSystem.text = msg.text
                }
            }
        }
    }
}
