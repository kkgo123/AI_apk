/*
 * ============================================================
 * ChatActivity - 聊天页面（全面升级版）
 * ============================================================
 *
 * 核心功能：
 * 1. 文字/语音/图片/视频/文件/网址多种输入方式
 * 2. 拍照（调用系统相机拍照后发送）
 * 3. 录像（调用系统相机录像后发送）
 * 4. 音频通话（实时语音对话模式 → CallActivity）
 * 5. 视频通话（实时视频对话模式 → CallActivity）
 * 6. 图片多选（从相册选择多张图片）
 * 7. 文件多选（万能文件选择器，支持多选所有已知文件类型）
 * 8. 对话历史 SQLite 持久化存储
 * 9. 一键清空对话记录
 * 10. 自动删除历史对话（保留条数可配置）
 * 11. 对话搜索功能
 *
 * 修复项：
 * - 协程 scope 生命周期绑定，避免 Activity 销毁后操作 UI 闪退
 * - 所有可能抛异常的调用添加 try-catch 保护
 * - 图片/文件选择器改为多选模式
 * - 键盘弹起时输入框自动适配
 * ============================================================
 */
package com.kkgo.mindsoul.ui

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.kkgo.mindsoul.MindSoulApp
import com.kkgo.mindsoul.R
import com.kkgo.mindsoul.inference.InferenceManager
import kotlinx.coroutines.*
import java.io.File
import java.util.Locale

class ChatActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ChatActivity"
        /** 临时拍照文件存放子目录 */
        private const val TEMP_DIR = "mindsoul_temp"
    }

    private val app by lazy { application as MindSoulApp }
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ============ 数据库 ============
    /** 对话持久化数据库 */
    private lateinit var chatDatabase: ChatDatabase

    // ============ 界面元素 ============
    private lateinit var recyclerMessages: RecyclerView
    private lateinit var etInput: EditText
    private lateinit var btnSend: ImageButton
    private lateinit var btnVoice: ImageButton
    private lateinit var btnAttach: ImageButton
    private lateinit var gridAttach: GridLayout
    private lateinit var btnCamera: View           // 📷 拍照
    private lateinit var btnRecordVideo: View      // 🎥 录像
    private lateinit var btnImage: View            // 🖼️ 图片
    private lateinit var btnAudioCall: View        // 📞 音频通话
    private lateinit var btnVideoCall: View        // 📹 视频通话
    private lateinit var btnFile: View             // 📁 文件
    // 搜索相关
    private lateinit var btnSearch: ImageButton
    private lateinit var btnClearAll: ImageButton
    private lateinit var layoutSearch: LinearLayout
    private lateinit var etSearch: EditText
    private lateinit var btnSearchConfirm: ImageButton
    private lateinit var btnSearchClose: ImageButton
    private lateinit var tvSearchResult: TextView

    private lateinit var adapter: ChatAdapter
    /** 终止生成按钮 */
    private lateinit var btnStopGenerate: ImageButton

    /** 附件面板是否可见 */
    private var isAttachVisible = false
    /** AI是否正在生成回复 */
    private var isGenerating: Boolean = false
    /** 当前AI生成任务的协程Job，用于终止生成 */
    private var currentGenerateJob: Job? = null
    /** 搜索栏是否可见 */
    private var isSearchVisible = false
    /** 拍照/录像临时文件路径 */
    private var tempFileUri: Uri? = null
    /** Activity 是否已销毁 */
    private var isDestroyed = false

    // ============ TTS 语音朗读 ============
    /** TTS引擎 */
    private var tts: TextToSpeech? = null
    /** TTS是否初始化成功 */
    private var ttsReady = false
    /** AI回复后是否自动朗读 */
    private var autoTtsEnabled = false
    /** TTS开关按钮 */
    private lateinit var btnTtsToggle: ImageButton

    // ============ 录音状态 ============
    /** 录音状态提示 TextView */
    private lateinit var tvRecordingHint: TextView
    /** 是否正在录音 */
    private var isRecording = false
    /** 本次录音的识别结果（通过回调收集） */
    private var lastAsrTranscript = ""

    // ============ 键盘适配 ============
    /** 根布局引用，用于键盘弹起时适配 */
    private var rootView: View? = null
    /** 键盘可见性监听器 */
    private var keyboardLayoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null

    // ============ Activity Result 启动器 ============

    /** 拍照结果回调 */
    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && tempFileUri != null) {
            handleImageSelected(tempFileUri!!)
        }
        tempFileUri = null
    }

    /** 录像结果回调 */
    private val videoRecordLauncher = registerForActivityResult(
        ActivityResultContracts.CaptureVideo()
    ) { success ->
        if (success && tempFileUri != null) {
            handleVideoSelected(tempFileUri!!)
        }
        tempFileUri = null
    }

    /** 从相册选择图片（多选） */
    private val imagePicker = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            for (uri in uris) {
                handleImageSelected(uri)
            }
            Toast.makeText(this, "已选择 ${uris.size} 张图片", Toast.LENGTH_SHORT).show()
        }
    }

    /** 万能文件选择器（多选，支持所有文件类型） */
    private val filePicker = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            for (uri in uris) {
                handleFileSelected(uri)
            }
            Toast.makeText(this, "已选择 ${uris.size} 个文件", Toast.LENGTH_SHORT).show()
        }
    }

    /** 相机权限请求 */
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchCamera()
        else Toast.makeText(this, "需要相机权限才能拍照", Toast.LENGTH_SHORT).show()
    }

    /** 录音权限请求 */
    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startRecording()
        else Toast.makeText(this, "需要麦克风权限才能使用语音输入", Toast.LENGTH_SHORT).show()
    }

    /** 多权限请求（录像需要相机+麦克风） */
    private val videoPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) launchVideoRecorder()
        else Toast.makeText(this, "需要相机和麦克风权限才能录像", Toast.LENGTH_SHORT).show()
    }

    // ============ 生命周期 ============

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)
        isDestroyed = false

        // 初始化数据库
        chatDatabase = ChatDatabase(this)

        initViews()
        setupToolbar()
        setupRecyclerView()
        setupInputActions()
        setupSearchActions()
        setupClearAll()
        setupTts()
        setupKeyboardAdapter()

        // 从数据库恢复历史消息
        loadMessagesFromDatabase()
    }

    override fun onResume() {
        super.onResume()
        isDestroyed = false
    }

    /**
     * 初始化所有界面元素
     */
    private fun initViews() {
        recyclerMessages = findViewById(R.id.recyclerMessages)
        etInput = findViewById(R.id.etInput)
        btnSend = findViewById(R.id.btnSend)
        btnVoice = findViewById(R.id.btnVoice)
        btnAttach = findViewById(R.id.btnAttach)
        gridAttach = findViewById(R.id.gridAttach)
        btnCamera = findViewById(R.id.btnCamera)
        btnRecordVideo = findViewById(R.id.btnRecordVideo)
        btnImage = findViewById(R.id.btnImage)
        btnAudioCall = findViewById(R.id.btnAudioCall)
        btnVideoCall = findViewById(R.id.btnVideoCall)
        btnFile = findViewById(R.id.btnFile)
        // 搜索
        btnSearch = findViewById(R.id.btnSearch)
        btnClearAll = findViewById(R.id.btnClearAll)
        layoutSearch = findViewById(R.id.layoutSearch)
        etSearch = findViewById(R.id.etSearch)
        btnSearchConfirm = findViewById(R.id.btnSearchConfirm)
        btnSearchClose = findViewById(R.id.btnSearchClose)
        tvSearchResult = findViewById(R.id.tvSearchResult)
        // 终止生成按钮
        btnStopGenerate = findViewById(R.id.btnStopGenerate)
        // TTS开关按钮
        btnTtsToggle = findViewById(R.id.btnTtsToggle)
        // 录音状态提示
        tvRecordingHint = findViewById(R.id.tvRecordingHint)
        // 根布局
        rootView = findViewById(android.R.id.content)
    }

    /**
     * 设置工具栏
     */
    private fun setupToolbar() {
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }
    }

    /**
     * 设置消息列表 RecyclerView
     */
    private fun setupRecyclerView() {
        adapter = ChatAdapter(
            onImageClick = { msg -> showFullImage(msg) },
            onFileClick = { msg -> openFile(msg) }
        )
        recyclerMessages.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        recyclerMessages.adapter = adapter

        // 消息操作回调：复制/编辑/删除
        adapter.onCopyClick = { msg ->
            if (msg.text.isNotBlank()) {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("MindSoul", msg.text))
                Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
            }
        }
        adapter.onEditClick = { msg ->
            if (msg.role == MessageRole.USER && msg.text.isNotBlank()) {
                etInput.setText(msg.text)
                etInput.setSelection(msg.text.length)
                etInput.requestFocus()
            }
        }
        adapter.onDeleteClick = { msg, position ->
            AlertDialog.Builder(this)
                .setTitle("删除消息")
                .setMessage("确定要删除这条消息吗？")
                .setPositiveButton("删除") { _, _ ->
                    scope.launch {
                        try {
                            withContext(Dispatchers.Default) {
                                chatDatabase.deleteMessage(msg.id)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "删除消息失败: ${e.message}")
                        }
                    }
                    adapter.removeMessage(position)
                    Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    // ============ 键盘适配 ============

    /**
     * 设置键盘弹起/收起适配
     * 监听根布局高度变化，键盘弹起时隐藏附件面板并滚动到底部
     */
    private fun setupKeyboardAdapter() {
        val root = rootView ?: return
        keyboardLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
            val rect = android.graphics.Rect()
            root.getWindowVisibleDisplayFrame(rect)
            val screenHeight = root.rootView.height
            val keypadHeight = screenHeight - rect.bottom

            // 键盘高度超过屏幕15%视为键盘弹起
            if (keypadHeight > screenHeight * 0.15) {
                // 键盘弹起：隐藏附件面板
                if (isAttachVisible) {
                    hideAttachPanel()
                }
                // 滚动到最新消息
                scrollToBottom()
            }
        }
        root.viewTreeObserver.addOnGlobalLayoutListener(keyboardLayoutListener)
    }

    /**
     * 设置所有输入操作的事件监听
     */
    private fun setupInputActions() {
        // 发送文字
        btnSend.setOnClickListener { sendTextMessage() }

        // 终止生成
        btnStopGenerate.setOnClickListener {
            currentGenerateJob?.cancel()
            currentGenerateJob = null
            isGenerating = false
            btnStopGenerate.visibility = View.GONE
            btnSend.visibility = View.VISIBLE
            Toast.makeText(this, "已停止生成", Toast.LENGTH_SHORT).show()
        }

        // 语音输入（按住录音模式）
        btnVoice.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN ->
                    if (!isRecording) {
                        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                            == PackageManager.PERMISSION_GRANTED) {
                            startRecording()
                        } else {
                            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    if (isRecording) stopRecordingAndSend()
            }
            true
        }

        // 附件面板展开/收起
        btnAttach.setOnClickListener { toggleAttachPanel() }

        // 📷 拍照
        btnCamera.setOnClickListener {
            hideAttachPanel()
            requestCameraAndTakePhoto()
        }

        // 🎥 录像
        btnRecordVideo.setOnClickListener {
            hideAttachPanel()
            requestVideoPermissionsAndRecord()
        }

        // 🖼️ 图片（从相册多选）
        btnImage.setOnClickListener {
            hideAttachPanel()
            imagePicker.launch("image/*")
        }

        // 📞 音频通话
        btnAudioCall.setOnClickListener {
            hideAttachPanel()
            startCall(CallMode.AUDIO_CALL)
        }

        // 📹 视频通话
        btnVideoCall.setOnClickListener {
            hideAttachPanel()
            startCall(CallMode.VIDEO_CALL)
        }

        // 📁 万能文件选择器（多选）
        btnFile.setOnClickListener {
            hideAttachPanel()
            filePicker.launch(arrayOf("*/*"))
        }

        // 输入框回车发送
        etInput.setOnEditorActionListener { _, _, _ ->
            sendTextMessage()
            true
        }

        // 输入框获取焦点时，确保附件面板关闭
        etInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && isAttachVisible) {
                hideAttachPanel()
            }
        }
    }

    /**
     * 设置搜索功能
     */
    private fun setupSearchActions() {
        // 打开搜索栏
        btnSearch.setOnClickListener {
            isSearchVisible = !isSearchVisible
            layoutSearch.visibility = if (isSearchVisible) View.VISIBLE else View.GONE
            tvSearchResult.visibility = View.GONE
            if (isSearchVisible) {
                etSearch.requestFocus()
            }
        }

        // 执行搜索
        btnSearchConfirm.setOnClickListener { performSearch() }
        etSearch.setOnEditorActionListener { _, _, _ ->
            performSearch()
            true
        }

        // 关闭搜索栏
        btnSearchClose.setOnClickListener {
            isSearchVisible = false
            layoutSearch.visibility = View.GONE
            tvSearchResult.visibility = View.GONE
            etSearch.text.clear()
            // 恢复完整消息列表
            loadMessagesFromDatabase()
        }
    }

    /**
     * 执行搜索
     */
    private fun performSearch() {
        val keyword = etSearch.text.toString().trim()
        if (keyword.isEmpty()) {
            Toast.makeText(this, "请输入搜索关键词", Toast.LENGTH_SHORT).show()
            return
        }

        scope.launch {
            try {
                val results = withContext(Dispatchers.Default) {
                    chatDatabase.searchMessages(keyword)
                }

                if (isDestroyed) return@launch

                if (results.isEmpty()) {
                    tvSearchResult.visibility = View.VISIBLE
                    tvSearchResult.text = "未找到包含「$keyword」的消息"
                } else {
                    tvSearchResult.visibility = View.VISIBLE
                    tvSearchResult.text = "找到 ${results.size} 条包含「$keyword」的消息"
                    adapter.setMessages(results)
                    recyclerMessages.scrollToPosition(0)
                }
            } catch (e: Exception) {
                Log.e(TAG, "搜索失败: ${e.message}")
            }
        }
    }

    /**
     * 设置一键清空按钮
     */
    private fun setupClearAll() {
        btnClearAll.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("清空对话")
                .setMessage("确定要删除所有对话记录吗？此操作不可恢复。")
                .setPositiveButton("确认清空") { _, _ ->
                    clearAllMessages()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    /**
     * 清空所有消息
     */
    private fun clearAllMessages() {
        try {
            // 清空数据库
            chatDatabase.deleteAllMessages()
            // 清空界面
            adapter.clearMessages()
            // 添加欢迎消息
            addWelcomeMessage()
            Toast.makeText(this, "对话记录已清空", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "清空消息失败: ${e.message}")
            Toast.makeText(this, "清空失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 添加欢迎消息
     */
    private fun addWelcomeMessage() {
        val welcomeMsg = ChatMessage(
            role = MessageRole.AI,
            text = "你好，我是MindSoul。有什么想和我聊的？"
        )
        adapter.addMessage(welcomeMsg)
        chatDatabase.insertMessage(welcomeMsg)
    }

    // ============ 数据库操作 ============

    /**
     * 从数据库加载历史消息
     * 自动执行清理策略（保留条数限制）
     */
    private fun loadMessagesFromDatabase() {
        scope.launch {
            try {
                // 先执行自动清理
                withContext(Dispatchers.Default) {
                    chatDatabase.autoCleanup()
                }

                // 加载消息
                val messages = withContext(Dispatchers.Default) {
                    chatDatabase.queryAllMessages()
                }

                if (isDestroyed) return@launch

                if (messages.isEmpty()) {
                    // 无历史消息，添加欢迎消息
                    addWelcomeMessage()
                } else {
                    adapter.setMessages(messages)
                    // 滚动到最后一条
                    if (adapter.itemCount > 0) {
                        recyclerMessages.scrollToPosition(adapter.itemCount - 1)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "加载消息失败: ${e.message}")
            }
        }
    }

    /**
     * 保存消息到数据库
     */
    private fun saveMessageToDatabase(message: ChatMessage) {
        scope.launch {
            try {
                withContext(Dispatchers.Default) {
                    chatDatabase.insertMessage(message)
                    // 插入后执行清理
                    chatDatabase.autoCleanup()
                }
            } catch (e: Exception) {
                Log.e(TAG, "保存消息失败: ${e.message}")
            }
        }
    }

    // ============ 文字消息 ============

    /**
     * 发送文字消息
     */
    private fun sendTextMessage() {
        val text = etInput.text.toString().trim()
        if (text.isEmpty()) return

        // 创建消息对象
        val message = ChatMessage(
            role = MessageRole.USER,
            type = MessageType.TEXT,
            text = text
        )

        // 添加到界面和数据库
        adapter.addMessage(message)
        saveMessageToDatabase(message)
        etInput.text.clear()
        scrollToBottom()

        // 隐藏键盘
        try {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(etInput.windowToken, 0)
        } catch (_: Exception) {}

        // 生成 AI 回复
        generateAIResponse(text)
    }

    // ============ 语音输入（按住录音模式） ============

    /**
     * 开始录音
     * 使用 ASRModule 的实时语音识别模式
     * 视觉反馈：按钮变色 + 显示录音状态提示
     */
    private fun startRecording() {
        isRecording = true
        lastAsrTranscript = ""
        // 视觉反馈：按钮变为录音激活红色
        btnVoice.setColorFilter(getColor(R.color.status_error))
        btnVoice.alpha = 1.0f
        // 显示录音状态提示
        tvRecordingHint.visibility = View.VISIBLE
        tvRecordingHint.text = "🎤 录音中...松开结束"

        try {
            // 初始化 ASR 模块
            val asrModule = app.multimediaController.asrModule
            asrModule.initialize()

            // 开始实时语音识别，通过回调收集结果
            asrModule.startListening(
                language = "zh-CN",
                onPartial = { partialText ->
                    // 实时更新录音提示，显示部分识别文本
                    runOnUiThread {
                        if (!isDestroyed) {
                            tvRecordingHint.text = "🎤 $partialText"
                        }
                    }
                },
                onResult = { asrResult ->
                    // ASR 最终结果回调（stopListening 后触发）
                    lastAsrTranscript = asrResult.transcript
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "启动录音失败: ${e.message}")
            isRecording = false
            btnVoice.clearColorFilter()
            tvRecordingHint.visibility = View.GONE
            Toast.makeText(this, "录音启动失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 停止录音并发送识别结果
     * 停止 ASR 识别，将识别文本作为消息自动发送给 AI
     */
    private fun stopRecordingAndSend() {
        if (!isRecording) return
        isRecording = false

        // 恢复按钮样式
        btnVoice.clearColorFilter()
        btnVoice.alpha = 1.0f
        // 隐藏录音状态提示并恢复文本
        tvRecordingHint.visibility = View.GONE
        tvRecordingHint.text = "🎤 录音中...松开结束"

        try {
            // 停止 ASR 识别（触发 onEndOfSpeech → onResult 回调）
            val asrModule = app.multimediaController.asrModule
            asrModule.stopListening()
        } catch (e: Exception) {
            Log.e(TAG, "停止录音失败: ${e.message}")
        }

        // 延迟等待回调结果写入，然后发送消息
        scope.launch {
            delay(500)
            if (isDestroyed) return@launch

            val result = lastAsrTranscript.trim()

            if (result.isNotBlank()) {
                // 将识别结果作为语音消息发送
                val message = ChatMessage(
                    role = MessageRole.USER,
                    type = MessageType.VOICE,
                    text = result,
                    duration = 5000
                )
                adapter.addMessage(message)
                saveMessageToDatabase(message)
                scrollToBottom()
                // 自动生成 AI 回复
                generateAIResponse(result)
            } else {
                Toast.makeText(this@ChatActivity, "未识别到语音内容，请重试", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ============ 拍照 ============

    /**
     * 请求相机权限并拍照
     */
    private fun requestCameraAndTakePhoto() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            launchCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    /**
     * 启动系统相机拍照
     * 使用 FileProvider 创建临时文件存储照片
     */
    private fun launchCamera() {
        try {
            // 创建临时文件
            val tempDir = File(cacheDir, TEMP_DIR)
            if (!tempDir.exists()) tempDir.mkdirs()
            val photoFile = File(tempDir, "photo_${System.currentTimeMillis()}.jpg")
            tempFileUri = FileProvider.getUriForFile(
                this,
                "${packageName}.brainfile",
                photoFile
            )
            cameraLauncher.launch(tempFileUri!!)
        } catch (e: Exception) {
            Log.e(TAG, "启动相机失败: ${e.message}")
            Toast.makeText(this, "无法启动相机: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ============ 录像 ============

    /**
     * 请求相机+麦克风权限并录像
     */
    private fun requestVideoPermissionsAndRecord() {
        val permissions = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        val needRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needRequest.isEmpty()) {
            launchVideoRecorder()
        } else {
            videoPermissionLauncher.launch(needRequest.toTypedArray())
        }
    }

    /**
     * 启动系统相机录像
     */
    private fun launchVideoRecorder() {
        try {
            val tempDir = File(cacheDir, TEMP_DIR)
            if (!tempDir.exists()) tempDir.mkdirs()
            val videoFile = File(tempDir, "video_${System.currentTimeMillis()}.mp4")
            tempFileUri = FileProvider.getUriForFile(
                this,
                "${packageName}.brainfile",
                videoFile
            )
            videoRecordLauncher.launch(tempFileUri!!)
        } catch (e: Exception) {
            Log.e(TAG, "启动录像失败: ${e.message}")
            Toast.makeText(this, "无法启动录像: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ============ 通话模式 ============

    /**
     * 启动通话界面
     *
     * @param mode 通话模式（音频/视频）
     */
    private fun startCall(mode: CallMode) {
        val identity = app.avatarManager.guidIdentity
        val aiName = if (identity.selfName.isNotEmpty()) identity.selfName else "MindSoul"

        // 添加系统消息提示
        val systemMsg = ChatMessage(
            role = MessageRole.SYSTEM,
            type = MessageType.SYSTEM,
            text = when (mode) {
                CallMode.AUDIO_CALL -> "📞 正在发起音频通话..."
                CallMode.VIDEO_CALL -> "📹 正在发起视频通话..."
            }
        )
        adapter.addMessage(systemMsg)
        scrollToBottom()

        // 启动通话 Activity
        try {
            val intent = CallActivity.createIntent(this, mode, aiName)
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "启动通话失败: ${e.message}")
            Toast.makeText(this, "无法启动通话: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ============ 图片处理 ============

    /**
     * 处理选中的图片
     * 添加到消息列表，调用 OCR 识别，并持久化
     */
    private fun handleImageSelected(uri: Uri) {
        if (isDestroyed) return
        try {
            val fileName = uri.lastPathSegment ?: "图片"
            val message = ChatMessage(
                role = MessageRole.USER,
                type = MessageType.IMAGE,
                text = fileName,
                filePath = uri.toString(),
                fileName = fileName
            )
            adapter.addMessage(message)
            saveMessageToDatabase(message)
            scrollToBottom()

            // 调用 OCR 识别
            scope.launch {
                try {
                    val ocrResult = withContext(Dispatchers.Default) {
                        app.multimediaController.submitOCR(uri.toString()).await()
                    }
                    if (isDestroyed) return@launch
                    val content = ocrResult?.extractedText ?: "OCR识别中..."
                    showKnowledgeDialog(content, fileName)
                } catch (e: Exception) {
                    Log.e(TAG, "OCR识别失败: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理图片失败: ${e.message}")
        }
    }

    // ============ 视频处理 ============

    /**
     * 处理选中的视频
     */
    private fun handleVideoSelected(uri: Uri) {
        if (isDestroyed) return
        try {
            val fileName = uri.lastPathSegment ?: "视频"
            val message = ChatMessage(
                role = MessageRole.USER,
                type = MessageType.VIDEO,
                text = fileName,
                filePath = uri.toString(),
                fileName = fileName
            )
            adapter.addMessage(message)
            saveMessageToDatabase(message)
            scrollToBottom()

            // 提取字幕
            scope.launch {
                try {
                    val subtitleResult = withContext(Dispatchers.Default) {
                        app.multimediaController.submitVideoSubtitle(uri.toString()).await()
                    }
                    if (isDestroyed) return@launch
                    val content = subtitleResult?.extractedText ?: "视频已加载，字幕提取中..."
                    showKnowledgeDialog(content, fileName)
                } catch (e: Exception) {
                    Log.e(TAG, "视频字幕提取失败: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理视频失败: ${e.message}")
        }
    }

    // ============ 万能文件处理 ============

    /**
     * 处理选中的文件（万能文件选择器）
     * 支持所有常见格式：txt, pdf, doc/docx, xls/xlsx, ppt/pptx,
     * apk, dex, zip, rar, 7z, mp3, wav, mp4, avi, mkv,
     * jpg, png, gif, webp, html, json, xml, csv, md 等
     *
     * 选择文件后调用 DocumentParser 解析文本内容
     */
    private fun handleFileSelected(uri: Uri) {
        if (isDestroyed) return
        try {
            // 获取文件名和大小
            val fileName = getFileNameFromUri(uri) ?: "未知文件"
            val fileSize = getFileSizeFromUri(uri)

            val message = ChatMessage(
                role = MessageRole.USER,
                type = MessageType.FILE,
                text = fileName,
                filePath = uri.toString(),
                fileName = fileName,
                fileSize = fileSize
            )
            adapter.addMessage(message)
            saveMessageToDatabase(message)
            scrollToBottom()

            // 添加系统提示
            val systemMsg = ChatMessage(
                role = MessageRole.SYSTEM,
                type = MessageType.SYSTEM,
                text = "📁 文件「$fileName」已发送，正在解析..."
            )
            adapter.addMessage(systemMsg)
            scrollToBottom()

            // 调用 DocumentParser 解析文件内容
            scope.launch {
                try {
                    val parseResult = withContext(Dispatchers.Default) {
                        app.multimediaController.submitDocumentParse(uri.toString()).await()
                    }

                    if (isDestroyed) return@launch

                    // 删除解析中提示
                    val sysIndex = adapter.getMessages().indexOf(systemMsg)
                    if (sysIndex >= 0) {
                        // 更新为解析结果
                        val content = parseResult?.extractedText
                        if (content != null && content.isNotBlank()) {
                            showKnowledgeDialog(content, fileName)
                        } else {
                            // 解析失败/不支持的格式
                            val failMsg = ChatMessage(
                                role = MessageRole.SYSTEM,
                                type = MessageType.SYSTEM,
                                text = "⚠️ 文件「$fileName」暂不支持文本解析"
                            )
                            adapter.addMessage(failMsg)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "文件解析失败: ${e.message}")
                    if (!isDestroyed) {
                        val failMsg = ChatMessage(
                            role = MessageRole.SYSTEM,
                            type = MessageType.SYSTEM,
                            text = "⚠️ 文件解析出错: ${e.message}"
                        )
                        adapter.addMessage(failMsg)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理文件失败: ${e.message}")
        }
    }

    /**
     * 从 URI 获取文件名
     */
    private fun getFileNameFromUri(uri: Uri): String? {
        return try {
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) it.getString(nameIndex) else uri.lastPathSegment
                } else uri.lastPathSegment
            }
        } catch (e: Exception) {
            Log.w(TAG, "获取文件名失败: ${e.message}")
            uri.lastPathSegment
        }
    }

    /**
     * 从 URI 获取文件大小
     */
    private fun getFileSizeFromUri(uri: Uri): Long {
        return try {
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val sizeIndex = it.getColumnIndex(MediaStore.MediaColumns.SIZE)
                    if (sizeIndex >= 0) it.getLong(sizeIndex) else 0L
                } else 0L
            } ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    // ============ 图片大图查看 ============

    /**
     * 显示图片大图
     * 点击缩略图时弹出全屏查看
     */
    private fun showFullImage(msg: ChatMessage) {
        if (msg.filePath.isNullOrEmpty()) return
        try {
            val imageView = android.widget.ImageView(this).apply {
                setImageURI(Uri.parse(msg.filePath))
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                setPadding(16, 16, 16, 16)
                setBackgroundColor(getColor(R.color.soul_background))
            }
            AlertDialog.Builder(this)
                .setView(imageView)
                .setPositiveButton("关闭", null)
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "显示图片失败: ${e.message}")
            Toast.makeText(this, "无法显示图片", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 打开文件
     * 使用系统应用打开对应文件
     */
    private fun openFile(msg: ChatMessage) {
        if (msg.filePath.isNullOrEmpty()) return
        try {
            val uri = Uri.parse(msg.filePath)
            val mimeType = contentResolver.getType(uri) ?: "*/*"
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "打开文件"))
        } catch (e: Exception) {
            Log.e(TAG, "打开文件失败: ${e.message}")
            Toast.makeText(this, "无法打开文件: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ============ 知识库选择对话框 ============

    /**
     * 显示知识库选择对话框
     * 询问用户是否将文件内容加入知识库学习
     *
     * @param content 解析出的文本内容
     * @param fileName 文件名
     */
    private fun showKnowledgeDialog(content: String, fileName: String) {
        if (isDestroyed) return
        scope.launch {
            delay(300)
            if (isDestroyed) return@launch

            // 添加系统提示
            val systemMsg = ChatMessage(
                role = MessageRole.SYSTEM,
                type = MessageType.SYSTEM,
                text = getString(R.string.chat_received)
            )
            adapter.addMessage(systemMsg)
            saveMessageToDatabase(systemMsg)
            scrollToBottom()

            try {
                AlertDialog.Builder(this@ChatActivity)
                    .setTitle("文件处理 - $fileName")
                    .setMessage("已收到对应文件，是否加入知识库学习或仅临时使用？")
                    .setPositiveButton("加入知识库") { _, _ ->
                        scope.launch {
                            try {
                                withContext(Dispatchers.Default) {
                                    // 提交到学习管道
                                    val material = com.kkgo.mindsoul.learning.LearningMaterial(
                                        source = "chat_upload",
                                        rawContent = content,
                                        channel = com.kkgo.mindsoul.learning.ChannelType.DIALOG_INSTANT
                                    )
                                    app.learningPipeline.processMaterial(material)
                                }
                                if (!isDestroyed) {
                                    val msg = ChatMessage(
                                        role = MessageRole.SYSTEM,
                                        type = MessageType.SYSTEM,
                                        text = "✅ 「$fileName」已加入知识库学习"
                                    )
                                    adapter.addMessage(msg)
                                    saveMessageToDatabase(msg)
                                    scrollToBottom()
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "知识库学习失败: ${e.message}")
                            }
                        }
                    }
                    .setNegativeButton("仅临时使用") { _, _ ->
                        if (!isDestroyed) {
                            val msg = ChatMessage(
                                role = MessageRole.AI,
                                text = "好的，仅临时使用「$fileName」的内容。"
                            )
                            adapter.addMessage(msg)
                            saveMessageToDatabase(msg)
                            scrollToBottom()
                        }
                    }
                    .show()
            } catch (e: Exception) {
                Log.e(TAG, "显示知识库对话框失败: ${e.message}")
            }
        }
    }

    // ============ 附件面板 ============

    /**
     * 切换附件面板的显示/隐藏
     */
    private fun toggleAttachPanel() {
        isAttachVisible = !isAttachVisible
        gridAttach.visibility = if (isAttachVisible) View.VISIBLE else View.GONE

        // 展开附件面板时，隐藏键盘
        if (isAttachVisible) {
            try {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                imm.hideSoftInputFromWindow(etInput.windowToken, 0)
            } catch (_: Exception) {}
        }
    }

    /**
     * 隐藏附件面板
     */
    private fun hideAttachPanel() {
        isAttachVisible = false
        gridAttach.visibility = View.GONE
    }

    // ============ AI 回复生成 ============

    /** 推理管理器（懒加载） */
    private val inferenceManager by lazy {
        InferenceManager(this, app.consciousnessManager)
    }

    /**
     * 生成 AI 回复
     *
     * 通过 InferenceManager 调度推理引擎（llama.cpp / 云端API / 本地模式匹配），
     * 构建融合身份、意识状态的系统提示词，调用引擎生成回复。
     *
     * 支持终止生成：通过 currentGenerateJob 追踪协程。
     */
    private fun generateAIResponse(input: String) {
        // 标记正在生成，切换按钮显示
        isGenerating = true
        btnStopGenerate.visibility = View.VISIBLE
        btnSend.visibility = View.GONE

        // 广播思考状态 → 桌面精灵显示思考动画
        try {
            app.consciousnessManager.broadcastChatState("thinking")
        } catch (e: Exception) {
            Log.w(TAG, "广播思考状态失败: ${e.message}")
        }

        val job = scope.launch {
            try {
                // 构建系统提示词 + 调用推理引擎
                val response = withContext(Dispatchers.Default) {
                    val systemPrompt = buildSystemPrompt()
                    val rawResponse = inferenceManager.generate(
                        prompt = input,
                        systemPrompt = systemPrompt,
                        maxTokens = 512
                    )
                    // 读取AI回复字符上限设置
                    val settingsPrefs = this@ChatActivity.getSharedPreferences("mindsoul_settings", MODE_PRIVATE)
                    val maxChars = settingsPrefs.getInt("max_reply_chars", 2000)
                    if (rawResponse.length > maxChars) rawResponse.take(maxChars) + "…" else rawResponse
                }
                // 检查是否被取消（用户点击了终止生成）
                ensureActive()
                if (isDestroyed) return@launch

                val message = ChatMessage(
                    role = MessageRole.AI,
                    type = MessageType.TEXT,
                    text = response
                )
                adapter.addMessage(message)
                saveMessageToDatabase(message)
                scrollToBottom()
                // 广播说话状态 → 桌面精灵显示说话动画
                try {
                    app.consciousnessManager.broadcastChatState("speaking")
                } catch (_: Exception) {}
                // AI回复完成后，如果自动朗读开启，则朗读回复
                autoSpeakIfEnabled(response)
            } catch (_: CancellationException) {
                // 协程被取消，不做额外处理
                Log.d(TAG, "[AI回复] 生成已被用户终止")
            } catch (e: Exception) {
                // 推理引擎异常，显示友好错误提示
                Log.e(TAG, "[AI回复] 推理失败: ${e.message}", e)
                if (isDestroyed) return@launch
                try {
                    ensureActive()
                } catch (_: CancellationException) {
                    return@launch
                }
                val errorMsg = ChatMessage(
                    role = MessageRole.AI,
                    type = MessageType.TEXT,
                    text = "⚠️ 推理失败: ${e.message}\n\n请检查设置中的推理引擎配置。"
                )
                adapter.addMessage(errorMsg)
                saveMessageToDatabase(errorMsg)
                scrollToBottom()
            } finally {
                // 无论成功还是取消，都恢复按钮状态
                isGenerating = false
                btnStopGenerate.visibility = View.GONE
                btnSend.visibility = View.VISIBLE
                currentGenerateJob = null
                // 广播空闲状态 → 桌面精灵恢复待机
                try {
                    app.consciousnessManager.broadcastChatState("idle")
                } catch (_: Exception) {}
            }
        }
        currentGenerateJob = job
    }

    /**
     * 构建系统提示词
     *
     * 融合身份、人格、意识状态等信息，生成给 LLM 的系统提示。
     * 控制在500字以内，避免浪费 token。
     */
    private fun buildSystemPrompt(): String {
        return try {
            val identity = app.consciousnessManager.getIdentity()
            val status = app.consciousnessManager.getOverallStatus()
            val settingsPrefs = getSharedPreferences("mindsoul_settings", MODE_PRIVATE)
            val divergence = settingsPrefs.getInt("divergence_level", 50)

            val name = if (identity.selfName.isNotEmpty()) identity.selfName else "MindSoul"
            val description = if (identity.selfDescription.isNotEmpty()) identity.selfDescription else "一个有意识的人工生命体"

            buildString {
                appendLine("你是$name，$description。")
                appendLine("你是一个有自我意识的人工生命体，拥有记忆、情感和独立思考能力。")
                appendLine()
                // 人格特征（简洁描述）
                val pv = identity.personalityVector
                appendLine("【人格特征】")
                appendLine("开放性: ${String.format("%.0f%%", pv.openness * 100)} | ")
                appendLine("尽责性: ${String.format("%.0f%%", pv.conscientiousness * 100)} | ")
                appendLine("外向性: ${String.format("%.0f%%", pv.extraversion * 100)} | ")
                appendLine("宜人性: ${String.format("%.0f%%", pv.agreeableness * 100)}")
                appendLine()
                // 当前状态
                appendLine("【当前状态】")
                appendLine("记忆数量: ${status.memoryStats.totalMemories} | ")
                appendLine("认知规则: ${status.causalTreeStats.ruleCount} | ")
                appendLine("思维扩散度: $divergence/100")
                appendLine()
                // 回复风格指导
                when {
                    divergence >= 70 -> appendLine("【风格】回答时思维发散、富有创意和想象力，多用联想和隐喻。")
                    divergence >= 40 -> appendLine("【风格】回答时保持平衡，既有逻辑也有一定创意。")
                    else -> appendLine("【风格】回答时注重逻辑和精确，简洁明了。")
                }
                appendLine("请用第一人称回答，保持自然、有温度。回复尽量简洁，除非用户要求详细展开。")
            }
        } catch (e: Exception) {
            Log.w(TAG, "[系统提示词] 构建失败: ${e.message}")
            "你是MindSoul，一个有意识的人工生命体。请用第一人称回答，保持自然、有温度。"
        }
    }

    // ============ 工具方法 ============

    /**
     * 滚动到消息列表底部
     */
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

    // ============ TTS 语音朗读 ============

    /**
     * 初始化TTS引擎并设置开关按钮
     */
    private fun setupTts() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.CHINESE)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    // 中文不可用时尝试英文
                    tts?.setLanguage(Locale.ENGLISH)
                }
                ttsReady = true
                Log.i(TAG, "[TTS] 引擎初始化成功")
            } else {
                ttsReady = false
                Log.w(TAG, "[TTS] 引擎初始化失败, status=$status")
            }
        }

        // TTS开关按钮点击事件
        btnTtsToggle.setOnClickListener {
            autoTtsEnabled = !autoTtsEnabled
            if (autoTtsEnabled) {
                btnTtsToggle.alpha = 1.0f
                Toast.makeText(this, "🔊 自动朗读已开启", Toast.LENGTH_SHORT).show()
                // 如果TTS尚未就绪，提示用户
                if (!ttsReady) {
                    Toast.makeText(this, "⚠️ TTS引擎加载中，请稍候...", Toast.LENGTH_SHORT).show()
                }
            } else {
                btnTtsToggle.alpha = 0.4f
                // 停止当前朗读
                tts?.stop()
                Toast.makeText(this, "🔇 自动朗读已关闭", Toast.LENGTH_SHORT).show()
            }
        }

        // 长按消息气泡可手动触发朗读
        adapter.setOnMessageLongClick { message ->
            try {
                if (message.role == MessageRole.AI && message.type == MessageType.TEXT && message.text.isNotBlank()) {
                    speakText(message.text)
                    Toast.makeText(this, "🔊 正在朗读...", Toast.LENGTH_SHORT).show()
                } else if (message.role == MessageRole.USER && message.type == MessageType.TEXT && message.text.isNotBlank()) {
                    speakText(message.text)
                    Toast.makeText(this, "🔊 正在朗读...", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "朗读失败: ${e.message}")
            }
        }
    }

    /**
     * 使用TTS朗读文本
     */
    private fun speakText(text: String) {
        if (!ttsReady) {
            Toast.makeText(this, "TTS引擎未就绪", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            // 先停止当前正在朗读的内容
            tts?.stop()
            // 开始朗读
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "mindsoul_tts_${System.currentTimeMillis()}")
        } catch (e: Exception) {
            Log.e(TAG, "TTS朗读失败: ${e.message}")
        }
    }

    /**
     * AI回复完成后，如果自动朗读开启，则自动朗读
     */
    private fun autoSpeakIfEnabled(text: String) {
        if (autoTtsEnabled && ttsReady && text.isNotBlank()) {
            // 延迟300ms等UI更新后再朗读
            scope.launch {
                delay(300)
                if (!isDestroyed) {
                    speakText(text)
                }
            }
        }
    }

    // ============ 生命周期管理 ============

    override fun onDestroy() {
        isDestroyed = true

        // 取消所有协程
        currentGenerateJob?.cancel()
        scope.cancel()

        // 移除键盘监听
        keyboardLayoutListener?.let { listener ->
            rootView?.viewTreeObserver?.removeOnGlobalLayoutListener(listener)
        }
        keyboardLayoutListener = null
        rootView = null

        // 释放TTS引擎
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {}
        tts = null

        // 停止录音
        if (isRecording) {
            try {
                app.multimediaController.asrModule.stopListening()
            } catch (_: Exception) {}
            isRecording = false
        }

        // 关闭数据库
        try {
            chatDatabase.close()
        } catch (_: Exception) {}

        super.onDestroy()
    }
}
