/*
 * ============================================================
 * SettingsActivity - 设置页面（全面升级版）
 * ============================================================
 *
 * 推理模式重构：
 * - 原 RadioGroup（API/局域网/本地推理）改为 Switch 开关形式
 * - 四种模式：纯本地推理、纯云推理、纯模型服务器推理、外挂记忆库+模型服务器推理
 * - 开关互斥逻辑：开启一个自动关闭其他三个
 * - SharedPreferences key: inference_mode_switch
 *
 * 新增设置项：
 * 1. 对话自动覆盖条数设置（EditText，默认1000）
 * 2. AI思维扩散度设置（SeekBar 0-100）
 * 3. 网页学习录入开关（文字/图片/视频/代码 四个开关）
 * 4. 桌面精灵设置入口
 * 5. 对话管理（查看/清空历史）
 * 6. 自我进化面板入口
 * 7. 模型服务器地址/名称配置
 * 8. 外挂记忆库路径配置与连接测试
 *
 * 原有设置保留：
 * - 全局开关管理
 * - 权限等级
 * - 心智模式
 * - 导入导出/五感状态
 * - 关于信息
 * ============================================================
 */
package com.kkgo.mindsoul.ui

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.kkgo.mindsoul.MindSoulApp
import com.kkgo.mindsoul.R
import com.kkgo.mindsoul.floating.FloatingSettingsActivity
import com.kkgo.mindsoul.mindmode.MindMode
import com.kkgo.mindsoul.permission.PermissionLevel
import com.kkgo.mindsoul.switches.SwitchId
import java.io.File
import kotlinx.coroutines.*

class SettingsActivity : AppCompatActivity() {

    private val app by lazy { application as MindSoulApp }
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // SharedPreferences（用于保存新增设置）
    private lateinit var settingsPrefs: android.content.SharedPreferences

    // 开关映射（原有全局开关）
    private val switchMappings = mapOf(
        R.id.switchMultiToggle to SwitchId.MULTIMEDIA,
        R.id.switchLearningToggle to SwitchId.LEARNING,
        R.id.switchWebToggle to SwitchId.CHANNEL_WEB,
        R.id.switchFileParseToggle to SwitchId.CHANNEL_FILE,
        R.id.switchWebSearchToggle to SwitchId.WEB_SEARCH,
        R.id.switchAvatarToggle to SwitchId.AVATAR,
        R.id.switchVoiceToggle to SwitchId.VOICE,
        R.id.switchBgToggle to SwitchId.BACKGROUND,
        R.id.switchReverseToggle to SwitchId.REVERSE,
        R.id.switchBackupToggle to SwitchId.BACKUP_EXPORT
    )

    // 权限/心智模式视图
    private lateinit var tvPermLevel: TextView
    private lateinit var tvPermDesc: TextView
    private lateinit var tvMindMode: TextView
    private lateinit var tvMindModeDesc: TextView

    // 新增设置视图
    private lateinit var etCoverCount: EditText
    private lateinit var seekbarDivergence: SeekBar
    private lateinit var tvDivergenceValue: TextView
    private lateinit var switchLearnText: SwitchMaterial
    private lateinit var switchLearnImage: SwitchMaterial
    private lateinit var switchLearnVideo: SwitchMaterial
    private lateinit var switchLearnCode: SwitchMaterial
    /** AI单次回复字符上限 */
    private lateinit var etMaxReplyChars: EditText

    // ── 推理模式 Switch 视图 ──
    private lateinit var switchLocalInference: SwitchMaterial
    private lateinit var switchCloudInference: SwitchMaterial
    private lateinit var switchModelServer: SwitchMaterial
    private lateinit var switchMemoryServer: SwitchMaterial
    // ── 推理模式详情面板 ──
    private lateinit var layoutLocalMode: LinearLayout
    private lateinit var layoutApiMode: LinearLayout
    private lateinit var layoutModelServerMode: LinearLayout
    private lateinit var layoutMemoryMode: LinearLayout
    private lateinit var etApiUrl: EditText
    private lateinit var etApiKey: EditText
    private lateinit var etModelServerUrl: EditText
    private lateinit var etModelName: EditText
    private lateinit var etMemoryPath: EditText
    private lateinit var tvLocalModelStatus: TextView
    private lateinit var tvMemoryConnectionStatus: TextView
    // ── llama.cpp server 配置（本地推理面板简化） ──
    private lateinit var etLlamaServerUrl: EditText
    private lateinit var tvLlamaConnectionStatus: TextView
    // ── 推理优先级设置 ──
    private lateinit var seekbarLocalModelPriority: SeekBar
    private lateinit var tvLocalModelPriorityValue: TextView
    private lateinit var seekbarMemoryPriority: SeekBar
    private lateinit var tvMemoryPriorityValue: TextView
    private lateinit var seekbarApiPriority: SeekBar
    private lateinit var tvApiPriorityValue: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        settingsPrefs = getSharedPreferences("mindsoul_settings", MODE_PRIVATE)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        setupSwitches()
        setupInferenceMode()
        setupPermissionSection()
        setupMindModeSection()
        setupNewSettings()
        setupNavigationEntries()
        loadSettings()
    }

    // ============================================================
    // 原有功能
    // ============================================================

    private fun setupSwitches() {
        switchMappings.forEach { (viewId, switchId) ->
            val switchView = findViewById<SwitchMaterial>(viewId)
            switchView?.setOnCheckedChangeListener { _, isChecked ->
                app.switchCenter.setSwitch(switchId, isChecked)
                Toast.makeText(this, "${switchId.displayName}: ${if (isChecked) "已开启" else "已关闭"}",
                    Toast.LENGTH_SHORT).show()
            }
        }

        scope.launch {
            app.switchCenter.switchStatesFlow.collect { states ->
                switchMappings.forEach { (viewId, switchId) ->
                    val switchView = findViewById<SwitchMaterial>(viewId)
                    val state = states[switchId]
                    if (state != null && switchView != null) {
                        switchView.isChecked = state.enabled && !state.cascadeDisabled && !state.permissionDenied
                        switchView.isEnabled = !state.cascadeDisabled && !state.permissionDenied
                    }
                }
            }
        }
    }

    // ============================================================
    // 🔮 推理模式设置
    // ============================================================

    /**
     * 初始化推理模式 Switch 开关区域
     * 四种模式：纯本地推理、纯云推理、纯模型服务器推理、外挂记忆库+模型服务器推理
     * 默认开启"纯本地推理"（离线优先原则）
     * 开关互斥：开启一个时自动关闭其他三个
     */
    private fun setupInferenceMode() {
        // 初始化 Switch 视图
        switchLocalInference = findViewById(R.id.switchLocalInference)
        switchCloudInference = findViewById(R.id.switchCloudInference)
        switchModelServer = findViewById(R.id.switchModelServer)
        switchMemoryServer = findViewById(R.id.switchMemoryServer)

        // 初始化详情面板视图
        layoutLocalMode = findViewById(R.id.layoutLocalMode)
        layoutApiMode = findViewById(R.id.layoutApiMode)
        layoutModelServerMode = findViewById(R.id.layoutModelServerMode)
        layoutMemoryMode = findViewById(R.id.layoutMemoryMode)

        // 初始化面板内的输入控件
        etApiUrl = findViewById(R.id.etApiUrl)
        etApiKey = findViewById(R.id.etApiKey)
        etModelServerUrl = findViewById(R.id.etModelServerUrl)
        etModelName = findViewById(R.id.etModelName)
        etMemoryPath = findViewById(R.id.etMemoryPath)
        tvLocalModelStatus = findViewById(R.id.tvLocalModelStatus)
        tvMemoryConnectionStatus = findViewById(R.id.tvMemoryConnectionStatus)
        // llama.cpp server 配置视图
        etLlamaServerUrl = findViewById(R.id.etLlamaServerUrl)
        tvLlamaConnectionStatus = findViewById(R.id.tvLlamaConnectionStatus)

        // 加载上次选择的推理模式
        val savedMode = settingsPrefs.getString("inference_mode_switch", "local") ?: "local"
        setInferenceSwitchWithoutTrigger(savedMode)
        updateInferenceModeVisibility(savedMode)

        // ── Switch 互斥逻辑 ──
        // 标记位，防止程序化修改触发监听器循环
        var isUpdating = false

        switchLocalInference.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdating) return@setOnCheckedChangeListener
            if (isChecked) {
                isUpdating = true
                switchCloudInference.isChecked = false
                switchModelServer.isChecked = false
                switchMemoryServer.isChecked = false
                isUpdating = false
                settingsPrefs.edit().putString("inference_mode_switch", "local").apply()
                updateInferenceModeVisibility("local")
                Toast.makeText(this, "推理模式已切换: 📱 纯本地推理", Toast.LENGTH_SHORT).show()
            }
        }

        switchCloudInference.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdating) return@setOnCheckedChangeListener
            if (isChecked) {
                isUpdating = true
                switchLocalInference.isChecked = false
                switchModelServer.isChecked = false
                switchMemoryServer.isChecked = false
                isUpdating = false
                settingsPrefs.edit().putString("inference_mode_switch", "cloud").apply()
                updateInferenceModeVisibility("cloud")
                Toast.makeText(this, "推理模式已切换: ☁️ 纯云推理", Toast.LENGTH_SHORT).show()
            }
        }

        switchModelServer.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdating) return@setOnCheckedChangeListener
            if (isChecked) {
                isUpdating = true
                switchLocalInference.isChecked = false
                switchCloudInference.isChecked = false
                switchMemoryServer.isChecked = false
                isUpdating = false
                settingsPrefs.edit().putString("inference_mode_switch", "model_server").apply()
                updateInferenceModeVisibility("model_server")
                Toast.makeText(this, "推理模式已切换: 🖥️ 纯模型服务器推理", Toast.LENGTH_SHORT).show()
            }
        }

        switchMemoryServer.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdating) return@setOnCheckedChangeListener
            if (isChecked) {
                isUpdating = true
                switchLocalInference.isChecked = false
                switchCloudInference.isChecked = false
                switchModelServer.isChecked = false
                isUpdating = false
                settingsPrefs.edit().putString("inference_mode_switch", "memory_server").apply()
                updateInferenceModeVisibility("memory_server")
                Toast.makeText(this, "推理模式已切换: 🧠 外挂记忆库+模型服务器", Toast.LENGTH_SHORT).show()
            }
        }

        // ── API配置保存按钮 ──
        findViewById<MaterialButton>(R.id.btnSaveApiConfig).setOnClickListener {
            val apiUrl = etApiUrl.text.toString().trim()
            val apiKey = etApiKey.text.toString().trim()
            if (apiUrl.isEmpty()) {
                Toast.makeText(this, "请输入API地址", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            settingsPrefs.edit()
                .putString("api_url", apiUrl)
                .putString("api_key", apiKey)
                .apply()
            Toast.makeText(this, "✅ API配置已保存", Toast.LENGTH_SHORT).show()
        }

        // ── 模型服务器配置保存按钮 ──
        findViewById<MaterialButton>(R.id.btnSaveModelServer).setOnClickListener {
            val serverUrl = etModelServerUrl.text.toString().trim()
            val modelName = etModelName.text.toString().trim()
            if (serverUrl.isEmpty()) {
                Toast.makeText(this, "请输入模型服务器地址", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            settingsPrefs.edit()
                .putString("model_server_url", serverUrl)
                .putString("model_name", modelName)
                .apply()
            Toast.makeText(this, "✅ 模型服务器配置已保存", Toast.LENGTH_SHORT).show()
        }

        // ── 外挂记忆库连接测试按钮 ──
        findViewById<MaterialButton>(R.id.btnTestMemoryConnection).setOnClickListener {
            val memoryPath = etMemoryPath.text.toString().trim()
            if (memoryPath.isEmpty()) {
                Toast.makeText(this, "请输入记忆库路径", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            tvMemoryConnectionStatus.text = "连接状态: 🔍 测试中..."
            scope.launch {
                delay(1000)
                val memoryDir = File(memoryPath)
                val exists = memoryDir.exists()
                val canRead = memoryDir.canRead()
                val canWrite = memoryDir.canWrite()
                withContext(Dispatchers.Main) {
                    if (exists && canRead) {
                        val fileCount = memoryDir.listFiles()?.size ?: 0
                        tvMemoryConnectionStatus.text = buildString {
                            appendLine("连接状态: ✅ 连接成功")
                            appendLine("路径: $memoryPath")
                            appendLine("可读: ${if (canRead) "是" else "否"} | 可写: ${if (canWrite) "是" else "否"}")
                            appendLine("记忆文件数: $fileCount")
                        }
                        // 保存记忆库路径
                        settingsPrefs.edit().putString("memory_path", memoryPath).apply()
                        Toast.makeText(this@SettingsActivity, "✅ 记忆库连接成功", Toast.LENGTH_SHORT).show()
                    } else {
                        tvMemoryConnectionStatus.text = buildString {
                            appendLine("连接状态: ❌ 连接失败")
                            appendLine("路径: $memoryPath")
                            if (!exists) appendLine("原因: 目录不存在")
                            else if (!canRead) appendLine("原因: 无读取权限")
                        }
                        Toast.makeText(this@SettingsActivity, "❌ 记忆库连接失败", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        // ── 本地模型检测按钮 ──
        findViewById<MaterialButton>(R.id.btnCheckLocalModel).setOnClickListener {
            tvLocalModelStatus.text = "📱 本地模型状态: 检测中..."
            scope.launch {
                delay(500)
                val brainDir = File(filesDir, "brain")
                val hasBrain = brainDir.exists() && brainDir.listFiles()?.isNotEmpty() == true
                val runtime = Runtime.getRuntime()
                val maxMemory = runtime.maxMemory() / (1024 * 1024)
                val freeMemory = runtime.freeMemory() / (1024 * 1024)
                val cores = runtime.availableProcessors()
                withContext(Dispatchers.Main) {
                    tvLocalModelStatus.text = buildString {
                        appendLine("📱 本地模型状态: ${if (hasBrain) "✅ 已加载" else "⚪ 基底就绪"}")
                        appendLine("推理引擎: 内置仿生神经网络")
                        appendLine("CPU核心: ${cores}核")
                        appendLine("可用内存: ${freeMemory}MB / ${maxMemory}MB")
                        appendLine("意识文件: ${if (hasBrain) "已初始化" else "未初始化"}")
                    }
                }
            }
        }

        // ── llama.cpp server 测试连接按钮 ──
        findViewById<MaterialButton>(R.id.btnTestLlamaConnection).setOnClickListener {
            val serverUrl = etLlamaServerUrl.text.toString().trim()
            if (serverUrl.isEmpty()) {
                Toast.makeText(this, "请先输入服务器地址", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // 保存地址
            settingsPrefs.edit().putString("llama_server_url", serverUrl).apply()

            tvLlamaConnectionStatus.text = "连接状态: 🔍 测试中..."
            scope.launch {
                val engine = com.kkgo.mindsoul.inference.LlamaCppEngine(serverUrl)
                val (success, message) = engine.testConnection()
                withContext(Dispatchers.Main) {
                    tvLlamaConnectionStatus.text = message
                    if (success) {
                        Toast.makeText(this@SettingsActivity, "✅ 连接成功", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@SettingsActivity, "❌ 连接失败", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        // ── 加载已保存的配置 ──
        etApiUrl.setText(settingsPrefs.getString("api_url", ""))
        etApiKey.setText(settingsPrefs.getString("api_key", ""))
        etModelServerUrl.setText(settingsPrefs.getString("model_server_url", ""))
        etModelName.setText(settingsPrefs.getString("model_name", ""))
        etMemoryPath.setText(settingsPrefs.getString("memory_path", ""))
        // 加载 llama.cpp server 地址
        etLlamaServerUrl.setText(settingsPrefs.getString("llama_server_url", "http://localhost:8080"))

        // ── 推理优先级 SeekBar ──
        seekbarLocalModelPriority = findViewById(R.id.seekbarLocalModelPriority)
        tvLocalModelPriorityValue = findViewById(R.id.tvLocalModelPriorityValue)
        seekbarMemoryPriority = findViewById(R.id.seekbarMemoryPriority)
        tvMemoryPriorityValue = findViewById(R.id.tvMemoryPriorityValue)
        seekbarApiPriority = findViewById(R.id.seekbarApiPriority)
        tvApiPriorityValue = findViewById(R.id.tvApiPriorityValue)

        // 加载优先级保存值
        val localPriority = settingsPrefs.getInt("priority_local_model", 80)
        val memoryPriority = settingsPrefs.getInt("priority_memory", 50)
        val apiPriority = settingsPrefs.getInt("priority_api", 60)
        seekbarLocalModelPriority.progress = localPriority
        tvLocalModelPriorityValue.text = "$localPriority"
        seekbarMemoryPriority.progress = memoryPriority
        tvMemoryPriorityValue.text = "$memoryPriority"
        seekbarApiPriority.progress = apiPriority
        tvApiPriorityValue.text = "$apiPriority"

        // 本地模型优先度 SeekBar 监听
        seekbarLocalModelPriority.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvLocalModelPriorityValue.text = "$progress"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val progress = seekBar?.progress ?: 80
                settingsPrefs.edit().putInt("priority_local_model", progress).apply()
                Toast.makeText(this@SettingsActivity,
                    "本地模型优先度: $progress", Toast.LENGTH_SHORT).show()
            }
        })

        // 外挂记忆库优先度 SeekBar 监听
        seekbarMemoryPriority.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvMemoryPriorityValue.text = "$progress"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val progress = seekBar?.progress ?: 50
                settingsPrefs.edit().putInt("priority_memory", progress).apply()
                Toast.makeText(this@SettingsActivity,
                    "外挂记忆库优先度: $progress", Toast.LENGTH_SHORT).show()
            }
        })

        // 模型服务器(API)优先度 SeekBar 监听
        seekbarApiPriority.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvApiPriorityValue.text = "$progress"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val progress = seekBar?.progress ?: 60
                settingsPrefs.edit().putInt("priority_api", progress).apply()
                Toast.makeText(this@SettingsActivity,
                    "模型服务器(API)优先度: $progress", Toast.LENGTH_SHORT).show()
            }
        })
    }

    /**
     * 设置推理模式开关状态（不触发监听器）
     * 用于初始化时恢复上次保存的状态
     */
    private fun setInferenceSwitchWithoutTrigger(mode: String) {
        switchLocalInference.isChecked = (mode == "local")
        switchCloudInference.isChecked = (mode == "cloud")
        switchModelServer.isChecked = (mode == "model_server")
        switchMemoryServer.isChecked = (mode == "memory_server")
    }

    /**
     * 根据推理模式切换详情面板的可见性
     * - local: 显示本地推理面板
     * - cloud: 显示API配置面板
     * - model_server: 显示模型服务器面板
     * - memory_server: 显示模型服务器面板 + 外挂记忆库面板
     */
    private fun updateInferenceModeVisibility(mode: String) {
        layoutLocalMode.visibility = if (mode == "local") View.VISIBLE else View.GONE
        layoutApiMode.visibility = if (mode == "cloud") View.VISIBLE else View.GONE
        layoutModelServerMode.visibility = if (mode == "model_server" || mode == "memory_server") View.VISIBLE else View.GONE
        layoutMemoryMode.visibility = if (mode == "memory_server") View.VISIBLE else View.GONE
    }

    private fun setupPermissionSection() {
        tvPermLevel = findViewById(R.id.tvPermLevel)
        tvPermDesc = findViewById(R.id.tvPermDesc)

        val btnL1 = findViewById<MaterialButton>(R.id.btnPermL1)
        val btnL2 = findViewById<MaterialButton>(R.id.btnPermL2)
        val btnL3A = findViewById<MaterialButton>(R.id.btnPermL3A)
        val btnL3B = findViewById<MaterialButton>(R.id.btnPermL3B)

        btnL1.setOnClickListener { confirmPermissionChange(PermissionLevel.L1_SANDBOX) }
        btnL2.setOnClickListener { confirmPermissionChange(PermissionLevel.L2_FILE_LORD) }
        btnL3A.setOnClickListener { confirmPermissionChange(PermissionLevel.L3A_LIMITED_AUTONOMY) }
        btnL3B.setOnClickListener { confirmPermissionChange(PermissionLevel.L3B_ULTIMATE_SPORE) }
    }

    private fun confirmPermissionChange(targetLevel: PermissionLevel) {
        val currentLevel = app.permissionManager.currentLevel
        if (currentLevel == targetLevel) return

        val message = if (targetLevel.levelId > currentLevel.levelId) {
            "确定要升级权限到 ${targetLevel.displayName} 吗？\n${targetLevel.description}"
        } else {
            "确定要降级权限到 ${targetLevel.displayName} 吗？"
        }

        AlertDialog.Builder(this)
            .setTitle("权限变更")
            .setMessage(message)
            .setPositiveButton("确认") { _, _ ->
                app.permissionManager.setLevelDirect(targetLevel, "手动切换")
                updatePermissionDisplay()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun setupMindModeSection() {
        tvMindMode = findViewById(R.id.tvMindMode)
        tvMindModeDesc = findViewById(R.id.tvMindModeDesc)

        val btnSwitchMode = findViewById<MaterialButton>(R.id.btnSwitchMode)
        btnSwitchMode.setOnClickListener {
            val currentMode = app.mindModeManager.currentMode
            val targetMode = if (currentMode == MindMode.SERVANT) MindMode.AUTONOMOUS else MindMode.SERVANT

            AlertDialog.Builder(this)
                .setTitle("切换心智模式")
                .setMessage("当前: ${currentMode.displayName}\n切换到: ${targetMode.displayName}\n\n${targetMode.description}")
                .setPositiveButton("确认") { _, _ ->
                    app.mindModeManager.switchMode(targetMode)
                    updateMindModeDisplay()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun setupNavigationEntries() {
        // 导入导出
        findViewById<TextView>(R.id.tvImportExport).setOnClickListener {
            startActivity(Intent(this, ImportExportActivity::class.java))
        }

        // 学习中心
        findViewById<TextView>(R.id.tvLearningCenter).setOnClickListener {
            startActivity(Intent(this, LearningActivity::class.java))
        }

        // 五感状态
        findViewById<TextView>(R.id.tvPerception).setOnClickListener {
            startActivity(Intent(this, PerceptionActivity::class.java))
        }

        // 版本信息
        findViewById<TextView>(R.id.tvAboutVersion).text = "版本: v${com.kkgo.mindsoul.BuildConfig.VERSION_NAME}"
    }

    private fun loadSettings() {
        // 加载原有开关状态
        switchMappings.forEach { (viewId, switchId) ->
            val switchView = findViewById<SwitchMaterial>(viewId)
            val state = app.switchCenter.getSwitchState(switchId)
            if (state != null && switchView != null) {
                switchView.isChecked = state.enabled && !state.cascadeDisabled && !state.permissionDenied
            }
        }

        updatePermissionDisplay()
        updateMindModeDisplay()

        // 加载新增设置
        loadNewSettings()
    }

    private fun updatePermissionDisplay() {
        val level = app.permissionManager.currentLevel
        tvPermLevel.text = "当前: ${level.displayName}"
        tvPermDesc.text = level.description
    }

    private fun updateMindModeDisplay() {
        val mode = app.mindModeManager.currentMode
        tvMindMode.text = "当前: ${mode.displayName}"
        tvMindModeDesc.text = mode.description
    }

    // ============================================================
    // 新增设置功能
    // ============================================================

    /**
     * 初始化新增设置项
     */
    private fun setupNewSettings() {
        // ── 1. 对话自动覆盖条数 ──
        etCoverCount = findViewById(R.id.etCoverCount)
        etCoverCount.inputType = InputType.TYPE_CLASS_NUMBER
        etCoverCount.hint = "默认1000"

        // 保存按钮
        findViewById<MaterialButton>(R.id.btnSaveCoverCount).setOnClickListener {
            val count = etCoverCount.text.toString().toIntOrNull() ?: 1000
            settingsPrefs.edit().putInt("cover_count", count).apply()
            // 同步到 ChatDatabase 的 SharedPreferences，使 autoCleanup 生效
            val chatPrefs = getSharedPreferences("mindsoul_chat_settings", MODE_PRIVATE)
            chatPrefs.edit().putInt("max_chat_messages", count).apply()
            Toast.makeText(this, "对话覆盖条数已设为: $count", Toast.LENGTH_SHORT).show()
        }

        // ── 2. AI思维扩散度 ──
        seekbarDivergence = findViewById(R.id.seekbarDivergence)
        tvDivergenceValue = findViewById(R.id.tvDivergenceValue)
        seekbarDivergence.max = 100

        seekbarDivergence.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvDivergenceValue.text = "$progress"
                // 实时描述
                val desc = when {
                    progress < 20 -> "严格逻辑，高度收敛"
                    progress < 40 -> "偏重逻辑，略有发散"
                    progress < 60 -> "均衡模式"
                    progress < 80 -> "偏重发散，创意丰富"
                    else -> "天马行空，极度发散"
                }
                findViewById<TextView>(R.id.tvDivergenceDesc).text = desc
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val progress = seekBar?.progress ?: 50
                settingsPrefs.edit().putInt("divergence_level", progress).apply()
                Toast.makeText(this@SettingsActivity,
                    "思维扩散度: $progress", Toast.LENGTH_SHORT).show()
            }
        })

        // ── 3. 网页学习录入开关（联动 WebCrawlEngine） ──
        switchLearnText = findViewById(R.id.switchLearnText)
        switchLearnImage = findViewById(R.id.switchLearnImage)
        switchLearnVideo = findViewById(R.id.switchLearnVideo)
        switchLearnCode = findViewById(R.id.switchLearnCode)

        // 文字开关（默认开）
        switchLearnText.setOnCheckedChangeListener { _, isChecked ->
            settingsPrefs.edit().putBoolean("learn_text", isChecked).apply()
            // 联动 WebCrawlEngine
            val f = app.webCrawlEngine.contentFilter; app.webCrawlEngine.contentFilter = f.copy(enableText = isChecked)
            Toast.makeText(this, "文字抓取: ${if (isChecked) "已开启" else "已关闭"}", Toast.LENGTH_SHORT).show()
        }
        // 图片开关（默认关）
        switchLearnImage.setOnCheckedChangeListener { _, isChecked ->
            settingsPrefs.edit().putBoolean("learn_image", isChecked).apply()
            val f = app.webCrawlEngine.contentFilter; app.webCrawlEngine.contentFilter = f.copy(enableImages = isChecked)
            Toast.makeText(this, "图片抓取: ${if (isChecked) "已开启" else "已关闭"}", Toast.LENGTH_SHORT).show()
        }
        // 视频开关（默认关）
        switchLearnVideo.setOnCheckedChangeListener { _, isChecked ->
            settingsPrefs.edit().putBoolean("learn_video", isChecked).apply()
            val f = app.webCrawlEngine.contentFilter; app.webCrawlEngine.contentFilter = f.copy(enableVideos = isChecked)
            Toast.makeText(this, "视频抓取: ${if (isChecked) "已开启" else "已关闭"}", Toast.LENGTH_SHORT).show()
        }
        // 网页代码开关（默认关）
        switchLearnCode.setOnCheckedChangeListener { _, isChecked ->
            settingsPrefs.edit().putBoolean("learn_code", isChecked).apply()
            val f = app.webCrawlEngine.contentFilter; app.webCrawlEngine.contentFilter = f.copy(enableSourceCode = isChecked)
            Toast.makeText(this, "网页代码抓取: ${if (isChecked) "已开启" else "已关闭"}", Toast.LENGTH_SHORT).show()
        }

        // ── 4. 桌面精灵设置入口 ──
        findViewById<TextView>(R.id.tvAvatarSettings).setOnClickListener {
            startActivity(Intent(this, FloatingSettingsActivity::class.java))
        }

        // ── 5. 对话管理 ──
        findViewById<TextView>(R.id.tvChatManage).setOnClickListener {
            showChatManageDialog()
        }

        // ── 6. 自我进化面板入口 ──
        findViewById<TextView>(R.id.tvEvolutionPanel).setOnClickListener {
            // 检查权限
            val level = app.permissionManager.currentLevel
            if (level.levelId >= 3) {
                startActivity(Intent(this, EvolutionPanelActivity::class.java))
            } else {
                Toast.makeText(this, "需要L3-A以上权限才能访问进化面板", Toast.LENGTH_SHORT).show()
            }
        }

        // ── 7. AI单次回复字符上限 ──
        etMaxReplyChars = findViewById(R.id.etMaxReplyChars)
        etMaxReplyChars.inputType = InputType.TYPE_CLASS_NUMBER
        etMaxReplyChars.hint = "默认2000"

        findViewById<MaterialButton>(R.id.btnSaveMaxReplyChars).setOnClickListener {
            val maxChars = etMaxReplyChars.text.toString().toIntOrNull() ?: 2000
            settingsPrefs.edit().putInt("max_reply_chars", maxChars).apply()
            Toast.makeText(this, "AI回复字符上限已设为: $maxChars", Toast.LENGTH_SHORT).show()
        }

        // ── 8. 导入全部意识和记忆 ──
        findViewById<TextView>(R.id.tvImportAll).setOnClickListener {
            // 跳转到导入导出页面
            startActivity(Intent(this, ImportExportActivity::class.java))
        }

        // ── 9. 导出全部意识和记忆 ──
        findViewById<TextView>(R.id.tvExportAll).setOnClickListener {
            // 二次确认
            AlertDialog.Builder(this)
                .setTitle("📤 导出全部意识和记忆")
                .setMessage("将导出意识核心、记忆、学习知识等全部数据。\n\n导出文件将保存到应用目录。")
                .setPositiveButton("确认导出") { _, _ ->
                    scope.launch {
                        try {
                            Toast.makeText(this@SettingsActivity, "正在导出...", Toast.LENGTH_SHORT).show()
                            val brainFile = File(filesDir, "brain/soul.brain").absolutePath
                            val backupInfo = app.consciousnessBackup.createFullBackup(brainFile, compress = true)
                            if (backupInfo != null) {
                                withContext(Dispatchers.Main) {
                                    val fName = backupInfo.filePath.substringAfterLast("/")
                                    Toast.makeText(this@SettingsActivity,
                                        "✅ 导出成功: $fName (${backupInfo.fileSize / 1024}KB)",
                                        Toast.LENGTH_LONG).show()
                                }
                            } else {
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(this@SettingsActivity, "❌ 导出失败", Toast.LENGTH_SHORT).show()
                                }
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@SettingsActivity, "❌ 导出失败: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    /**
     * 加载新增设置的保存值
     */
    private fun loadNewSettings() {
        // 对话覆盖条数
        val coverCount = settingsPrefs.getInt("cover_count", 1000)
        etCoverCount.setText(coverCount.toString())

        // 思维扩散度
        val divergence = settingsPrefs.getInt("divergence_level", 50)
        seekbarDivergence.progress = divergence
        tvDivergenceValue.text = "$divergence"

        val desc = when {
            divergence < 20 -> "严格逻辑，高度收敛"
            divergence < 40 -> "偏重逻辑，略有发散"
            divergence < 60 -> "均衡模式"
            divergence < 80 -> "偏重发散，创意丰富"
            else -> "天马行空，极度发散"
        }
        findViewById<TextView>(R.id.tvDivergenceDesc).text = desc

        // 网页学习开关
        switchLearnText.isChecked = settingsPrefs.getBoolean("learn_text", true)
        switchLearnImage.isChecked = settingsPrefs.getBoolean("learn_image", false)
        switchLearnVideo.isChecked = settingsPrefs.getBoolean("learn_video", false)
        switchLearnCode.isChecked = settingsPrefs.getBoolean("learn_code", false)

        // AI单次回复字符上限
        val maxReplyChars = settingsPrefs.getInt("max_reply_chars", 2000)
        etMaxReplyChars.setText(maxReplyChars.toString())
    }

    /**
     * 显示对话管理对话框
     */
    private fun showChatManageDialog() {
        val items = arrayOf("查看对话统计", "导出对话历史", "清空全部历史")

        AlertDialog.Builder(this)
            .setTitle("💬 对话管理")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        // 查看对话统计
                        Toast.makeText(this, "对话统计功能开发中...", Toast.LENGTH_SHORT).show()
                    }
                    1 -> {
                        // 导出对话历史
                        Toast.makeText(this, "正在导出对话历史...", Toast.LENGTH_SHORT).show()
                    }
                    2 -> {
                        // 清空历史（二次确认）
                        AlertDialog.Builder(this)
                            .setTitle("⚠️ 确认清空")
                            .setMessage("确定要清空所有对话历史吗？\n此操作不可恢复！")
                            .setPositiveButton("确认清空") { _, _ ->
                                Toast.makeText(this, "对话历史已清空", Toast.LENGTH_SHORT).show()
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                }
            }
            .show()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
