/*
 * ============================================================
 * FloatingSettingsActivity - 精灵设置界面（全面升级版 v2.0）
 * ============================================================
 *
 * 完整的桌面精灵自定义设置界面：
 *
 * 1. 权限状态显示与引导
 * 2. 总开关
 * 3. 性别选择（男/女/无性别）
 * 4. 年龄外观选择（儿童/少年/青年/中年/成熟/老年）
 * 5. 动画风格选择（3D立体/2D扁平）
 * 6. 表情手动切换预览
 * 7. 服装选择（校服/西装/运动装/汉服/机甲/奇幻法袍）
 * 8. 上传自定义图片按钮
 * 9. 上传自定义视频按钮（提取首帧）
 * 10. 大小/透明度/功能开关
 * ============================================================
 */
package com.kkgo.mindsoul.floating

import com.kkgo.mindsoul.R
import android.provider.Settings

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import java.io.File
import java.io.FileOutputStream

class FloatingSettingsActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "FloatingSettingsAct"
        private const val REQUEST_OVERLAY = 1001
        private const val REQUEST_PICK_IMAGE = 1002
        private const val REQUEST_PICK_VIDEO = 1003
        private const val REQUEST_PICK_3D_MODEL = 1004
        private const val REQUEST_PICK_EQUIPMENT_IMAGE = 1005
    }

    // ============ 权限与总开关 ============
    private lateinit var tvPermissionStatus: TextView
    private lateinit var btnGrantPermission: TextView
    private lateinit var switchEnabled: SwitchCompat

    // ============ 精灵外观设置 ============
    /** 性别选择 */
    private lateinit var rgGender: RadioGroup
    /** 年龄选择 */
    private lateinit var rgAge: RadioGroup
    /** 动画风格选择 */
    private lateinit var rgAnimStyle: RadioGroup
    /** 表情预览区域 */
    private lateinit var llExpressionContainer: LinearLayout
    private lateinit var tvCurrentExpression: TextView
    /** 服装选择 */
    private lateinit var rgCostume: RadioGroup

    // ============ 自定义上传 ============
    private lateinit var btnUploadImage: Button
    private lateinit var btnUploadVideo: Button
    private lateinit var btnClearCustom: Button
    private lateinit var tvCustomStatus: TextView

    // ============ 装备系统 ============
    private lateinit var btnAddEquipment: Button
    private lateinit var llEquipmentList: LinearLayout
    private lateinit var tvEquipmentEmpty: TextView
    /** 当前编辑的装备项（等待填写描述的临时路径） */
    private var pendingEquipmentPath: String? = null
    /** 编辑中的装备列表 */
    private val editingEquipmentList = mutableListOf<EquipmentItem>()

    // ============ 3D模型上传 ============
    private lateinit var btnUpload3DModel: Button
    private lateinit var btnClear3DModel: Button
    private lateinit var tv3DModelStatus: TextView

    // ============ 文生精灵 ============
    private lateinit var etTextToSpirit: android.widget.EditText
    private lateinit var btnGenerateSpirit: Button
    private lateinit var tvTextToSpiritResult: TextView

    // ============ 基础设置 ============
    private lateinit var rgSize: RadioGroup
    private lateinit var tvAlphaValue: TextView
    private lateinit var seekbarAlpha: SeekBar
    private lateinit var switchBubble: SwitchCompat
    private lateinit var switchAutoHide: SwitchCompat
    private lateinit var switchBootStart: SwitchCompat

    private lateinit var prefs: android.content.SharedPreferences
    /** 当前正在编辑的AvatarConfig */
    private var editingConfig: AvatarConfig = AvatarConfig()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_floating_settings)

        window.statusBarColor = Color.parseColor("#1A1A2E")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()

        prefs = getSharedPreferences(FloatingAvatarService.PREF_NAME, MODE_PRIVATE)
        loadEditingConfig()
        bindViews()
        initSettings()
        checkPermission()
    }

    override fun onResume() {
        super.onResume()
        checkPermission()
    }

    // ============================================================
    // 加载当前配置到编辑器
    // ============================================================

    private fun loadEditingConfig() {
        editingConfig = AvatarConfig(
            gender = AvatarGender.entries.firstOrNull {
                it.code == prefs.getString(FloatingAvatarService.KEY_AVATAR_GENDER, AvatarGender.NONE.code)
            } ?: AvatarGender.NONE,
            age = AvatarAge.entries.firstOrNull {
                it.code == prefs.getString(FloatingAvatarService.KEY_AVATAR_AGE, AvatarAge.YOUNG_ADULT.code)
            } ?: AvatarAge.YOUNG_ADULT,
            personality = AvatarPersonality.entries.firstOrNull {
                it.code == prefs.getString(FloatingAvatarService.KEY_AVATAR_PERSONALITY, AvatarPersonality.GENTLE.code)
            } ?: AvatarPersonality.GENTLE,
            expression = AvatarExpression.entries.firstOrNull {
                it.code == prefs.getString(FloatingAvatarService.KEY_AVATAR_EXPRESSION, AvatarExpression.NEUTRAL.code)
            } ?: AvatarExpression.NEUTRAL,
            handAction = HandAction.entries.firstOrNull {
                it.code == prefs.getString(FloatingAvatarService.KEY_AVATAR_HAND, HandAction.NONE.code)
            } ?: HandAction.NONE,
            footAction = FootAction.entries.firstOrNull {
                it.code == prefs.getString(FloatingAvatarService.KEY_AVATAR_FOOT, FootAction.STANDING.code)
            } ?: FootAction.STANDING,
            costume = AvatarCostume.entries.firstOrNull {
                it.code == prefs.getString(FloatingAvatarService.KEY_AVATAR_COSTUME, AvatarCostume.SUIT.code)
            } ?: AvatarCostume.SUIT,
            overallAction = OverallAction.entries.firstOrNull {
                it.code == prefs.getString(FloatingAvatarService.KEY_AVATAR_OVERALL, OverallAction.IDLE.code)
            } ?: OverallAction.IDLE,
            shape = AvatarShape.entries.firstOrNull {
                it.code == prefs.getString(FloatingAvatarService.KEY_AVATAR_SHAPE, AvatarShape.CIRCLE.code)
            } ?: AvatarShape.CIRCLE,
            customImagePath = prefs.getString(FloatingAvatarService.KEY_AVATAR_CUSTOM_IMAGE, null),
            customVideoPath = prefs.getString(FloatingAvatarService.KEY_AVATAR_CUSTOM_VIDEO, null),
            sizeMultiplier = prefs.getFloat(FloatingAvatarService.KEY_AVATAR_SIZE_MULTIPLIER, 1.0f),
            animationStyle = AnimationStyle.entries.firstOrNull {
                it.code == prefs.getString(FloatingAvatarService.KEY_AVATAR_ANIMATION_STYLE, AnimationStyle.ANIMATION_3D.code)
            } ?: AnimationStyle.ANIMATION_3D,
            glowColor = if (prefs.contains(FloatingAvatarService.KEY_AVATAR_GLOW_COLOR)) {
                prefs.getInt(FloatingAvatarService.KEY_AVATAR_GLOW_COLOR, 0).takeIf { it != 0 }
            } else null,
            equipmentList = deserializeEquipmentList(
                prefs.getString(FloatingAvatarService.KEY_AVATAR_EQUIPMENT_LIST, null)
            ),
            custom3DModelPath = prefs.getString(FloatingAvatarService.KEY_AVATAR_3D_MODEL, null),
            textToSpiritConfig = prefs.getString(FloatingAvatarService.KEY_AVATAR_TEXT_TO_SPIRIT, null)
        )
        // 同步编辑中的装备列表
        editingEquipmentList.clear()
        editingEquipmentList.addAll(editingConfig.equipmentList)
    }

    /**
     * 反序列化装备列表
     */
    private fun deserializeEquipmentList(json: String?): List<EquipmentItem> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val items = mutableListOf<EquipmentItem>()
            val regex = Regex("""\{"imagePath":"(.*?)","description":"(.*?)","id":(\d+)\}""")
            regex.findAll(json).forEach { match ->
                items.add(EquipmentItem(
                    imagePath = match.groupValues[1],
                    description = match.groupValues[2],
                    id = match.groupValues[3].toLong()
                ))
            }
            items
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * 序列化装备列表为JSON
     */
    private fun serializeEquipmentList(items: List<EquipmentItem>): String {
        if (items.isEmpty()) return ""
        return items.joinToString(",", prefix = "[", postfix = "]") { item ->
            """{"imagePath":"${item.imagePath}","description":"${item.description}","id":${item.id}}"""
        }
    }

    // ============================================================
    // 绑定视图
    // ============================================================

    private fun bindViews() {
        // ── 权限 ──
        tvPermissionStatus = findViewById(R.id.tvPermissionStatus)
        btnGrantPermission = findViewById(R.id.btnGrantPermission)
        btnGrantPermission.setOnClickListener { requestOverlayPermission() }

        // ── 总开关 ──
        switchEnabled = findViewById(R.id.switchEnabled)
        switchEnabled.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(FloatingAvatarService.KEY_ENABLED, isChecked).apply()
            if (isChecked) {
                if (hasOverlayPermission()) {
                    FloatingAvatarService.start(this)
                } else {
                    Toast.makeText(this, "请先开启悬浮窗权限", Toast.LENGTH_SHORT).show()
                    switchEnabled.isChecked = false
                    prefs.edit().putBoolean(FloatingAvatarService.KEY_ENABLED, false).apply()
                }
            } else {
                FloatingAvatarService.stop(this)
            }
        }

        // ── 性别选择 ──
        rgGender = findViewById(R.id.rgGender)
        rgGender.setOnCheckedChangeListener { _, checkedId ->
            editingConfig = editingConfig.copy(gender = when (checkedId) {
                R.id.rbMale -> AvatarGender.MALE
                R.id.rbFemale -> AvatarGender.FEMALE
                R.id.rbIntersexMale -> AvatarGender.INTERSEX_MALE
                R.id.rbIntersexFemale -> AvatarGender.INTERSEX_FEMALE
                else -> AvatarGender.NONE
            })
            saveAndNotify()
        }

        // ── 年龄选择 ──
        rgAge = findViewById(R.id.rgAge)
        rgAge.setOnCheckedChangeListener { _, checkedId ->
            editingConfig = editingConfig.copy(age = when (checkedId) {
                R.id.rbChild -> AvatarAge.CHILD
                R.id.rbTeen -> AvatarAge.TEEN
                R.id.rbMiddleAge -> AvatarAge.MIDDLE_AGE
                R.id.rbMature -> AvatarAge.MATURE
                R.id.rbElder -> AvatarAge.ELDER
                else -> AvatarAge.YOUNG_ADULT
            })
            saveAndNotify()
        }

        // ── 动画风格选择 ──
        rgAnimStyle = findViewById(R.id.rgAnimStyle)
        rgAnimStyle.setOnCheckedChangeListener { _, checkedId ->
            editingConfig = editingConfig.copy(animationStyle = when (checkedId) {
                R.id.rbAnim2D -> AnimationStyle.ANIMATION_2D
                else -> AnimationStyle.ANIMATION_3D
            })
            saveAndNotify()
        }

        // ── 表情手动切换预览 ──
        llExpressionContainer = findViewById(R.id.llExpressionContainer)
        tvCurrentExpression = findViewById(R.id.tvCurrentExpression)
        buildExpressionSelector()

        // ── 服装选择 ──
        rgCostume = findViewById(R.id.rgCostume)
        rgCostume.setOnCheckedChangeListener { _, checkedId ->
            editingConfig = editingConfig.copy(costume = when (checkedId) {
                R.id.rbSchoolUniform -> AvatarCostume.SCHOOL_UNIFORM
                R.id.rbSuit -> AvatarCostume.SUIT
                R.id.rbSportswear -> AvatarCostume.SPORTSWEAR
                R.id.rbHanfu -> AvatarCostume.HANFU
                R.id.rbMecha -> AvatarCostume.MECHA
                R.id.rbFantasyRobe -> AvatarCostume.FANTASY_ROBE
                else -> AvatarCostume.SUIT
            })
            saveAndNotify()
        }

        // ── 自定义上传 ──
        btnUploadImage = findViewById(R.id.btnUploadImage)
        btnUploadVideo = findViewById(R.id.btnUploadVideo)
        btnClearCustom = findViewById(R.id.btnClearCustom)
        tvCustomStatus = findViewById(R.id.tvCustomStatus)

        btnUploadImage.setOnClickListener { pickImage() }
        btnUploadVideo.setOnClickListener { pickVideo() }
        btnClearCustom.setOnClickListener {
            editingConfig = editingConfig.copy(customImagePath = null, customVideoPath = null)
            saveAndNotify()
            updateCustomStatus()
            Toast.makeText(this, "已清除自定义外观", Toast.LENGTH_SHORT).show()
        }

        // ── 装备系统 ──
        btnAddEquipment = findViewById(R.id.btnAddEquipment)
        llEquipmentList = findViewById(R.id.llEquipmentList)
        tvEquipmentEmpty = findViewById(R.id.tvEquipmentEmpty)
        btnAddEquipment.setOnClickListener { pickEquipmentImage() }

        // ── 3D模型上传 ──
        btnUpload3DModel = findViewById(R.id.btnUpload3DModel)
        btnClear3DModel = findViewById(R.id.btnClear3DModel)
        tv3DModelStatus = findViewById(R.id.tv3DModelStatus)
        btnUpload3DModel.setOnClickListener { pick3DModel() }
        btnClear3DModel.setOnClickListener {
            editingConfig = editingConfig.copy(custom3DModelPath = null)
            saveAndNotify()
            update3DModelStatus()
            Toast.makeText(this, "已清除3D模型", Toast.LENGTH_SHORT).show()
        }

        // ── 文生精灵 ──
        etTextToSpirit = findViewById(R.id.etTextToSpirit)
        btnGenerateSpirit = findViewById(R.id.btnGenerateSpirit)
        tvTextToSpiritResult = findViewById(R.id.tvTextToSpiritResult)
        btnGenerateSpirit.setOnClickListener { generateSpiritFromText() }

        // ── 大小选择 ──
        rgSize = findViewById(R.id.rgSize)
        rgSize.setOnCheckedChangeListener { _, checkedId ->
            val sizeLevel = when (checkedId) {
                R.id.rbSmall -> 0
                R.id.rbLarge -> 2
                else -> 1
            }
            prefs.edit().putInt(FloatingAvatarService.KEY_SIZE, sizeLevel).apply()
            Log.d(TAG, "[设置] 大小已更新: level=$sizeLevel")
        }

        // ── 自定义宽高(px) ──
        val etWidth = findViewById<android.widget.EditText>(R.id.etFloatingWidth)
        val etHeight = findViewById<android.widget.EditText>(R.id.etFloatingHeight)
        val btnApplySize = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnApplySize)

        // 加载已保存的自定义尺寸
        val savedWidth = prefs.getInt(FloatingAvatarService.KEY_CUSTOM_WIDTH, 0)
        val savedHeight = prefs.getInt(FloatingAvatarService.KEY_CUSTOM_HEIGHT, 0)
        if (savedWidth > 0) etWidth.setText(savedWidth.toString())
        if (savedHeight > 0) etHeight.setText(savedHeight.toString())

        btnApplySize.setOnClickListener {
            val w = etWidth.text.toString().trim().toIntOrNull()
            val h = etHeight.text.toString().trim().toIntOrNull()
            val editor = prefs.edit()
            if (w != null && w > 0) {
                editor.putInt(FloatingAvatarService.KEY_CUSTOM_WIDTH, w)
            } else {
                editor.remove(FloatingAvatarService.KEY_CUSTOM_WIDTH)
            }
            if (h != null && h > 0) {
                editor.putInt(FloatingAvatarService.KEY_CUSTOM_HEIGHT, h)
            } else {
                editor.remove(FloatingAvatarService.KEY_CUSTOM_HEIGHT)
            }
            editor.apply()
            saveAndNotify()
            Toast.makeText(this, "✅ 尺寸已更新，悬浮窗将自动刷新", Toast.LENGTH_SHORT).show()
            Log.d(TAG, "[设置] 自定义尺寸: ${w ?: "默认"}x${h ?: "默认"}px")
        }

        // ── 透明度 ──
        tvAlphaValue = findViewById(R.id.tvAlphaValue)
        seekbarAlpha = findViewById(R.id.seekbarAlpha)
        seekbarAlpha.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val alpha = (progress + 10) / 100f
                tvAlphaValue.text = "${progress + 10}%"
                if (fromUser) {
                    prefs.edit().putFloat(FloatingAvatarService.KEY_ALPHA, alpha).apply()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // ── 功能开关 ──
        switchBubble = findViewById(R.id.switchBubble)
        switchAutoHide = findViewById(R.id.switchAutoHide)
        switchBootStart = findViewById(R.id.switchBootStart)

        switchBubble.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(FloatingAvatarService.KEY_BUBBLE_ENABLED, isChecked).apply()
        }
        switchAutoHide.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(FloatingAvatarService.KEY_AUTO_HIDE_FULLSCREEN, isChecked).apply()
        }
        switchBootStart.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(FloatingAvatarService.KEY_BOOT_START, isChecked).apply()
            if (isChecked) Toast.makeText(this, "已设置开机自启动", Toast.LENGTH_SHORT).show()
        }
    }

    // ============================================================
    // 表情选择器
    // ============================================================

    /**
     * 构建表情选择按钮行
     * 每个表情一个按钮，点击后设置并预览
     */
    private fun buildExpressionSelector() {
        llExpressionContainer.removeAllViews()

        // 分两行排列
        val row1Expressions = AvatarExpression.entries.take(5)
        val row2Expressions = AvatarExpression.entries.drop(5)

        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
        }
        for (expr in row1Expressions) {
            val btn = createExpressionButton(expr)
            row1.addView(btn)
        }
        llExpressionContainer.addView(row1)

        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpToPx(8) }
        }
        for (expr in row2Expressions) {
            val btn = createExpressionButton(expr)
            row2.addView(btn)
        }
        llExpressionContainer.addView(row2)
    }

    private fun createExpressionButton(expr: AvatarExpression): Button {
        return Button(this).apply {
            text = "${expr.emoji} ${expr.displayName}"
            textSize = 12f
            setTextColor(Color.WHITE)
            setPadding(dpToPx(6), dpToPx(4), dpToPx(6), dpToPx(4))
            val margin = dpToPx(3)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(margin, 0, margin, 0) }
            setOnClickListener {
                editingConfig = editingConfig.copy(expression = expr)
                saveAndNotify()
                tvCurrentExpression.text = "当前表情: ${expr.emoji} ${expr.displayName}"
                Toast.makeText(this@FloatingSettingsActivity,
                    "表情已切换: ${expr.emoji} ${expr.displayName}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ============================================================
    // 初始化设置值
    // ============================================================

    private fun initSettings() {
        val enabled = prefs.getBoolean(FloatingAvatarService.KEY_ENABLED, true)
        switchEnabled.isChecked = enabled

        // 性别
        when (editingConfig.gender) {
            AvatarGender.MALE -> rgGender.check(R.id.rbMale)
            AvatarGender.FEMALE -> rgGender.check(R.id.rbFemale)
            AvatarGender.INTERSEX_MALE -> rgGender.check(R.id.rbIntersexMale)
            AvatarGender.INTERSEX_FEMALE -> rgGender.check(R.id.rbIntersexFemale)
            else -> rgGender.check(R.id.rbNone)
        }

        // 年龄
        when (editingConfig.age) {
            AvatarAge.CHILD -> rgAge.check(R.id.rbChild)
            AvatarAge.TEEN -> rgAge.check(R.id.rbTeen)
            AvatarAge.YOUNG_ADULT -> rgAge.check(R.id.rbYoungAdult)
            AvatarAge.MIDDLE_AGE -> rgAge.check(R.id.rbMiddleAge)
            AvatarAge.MATURE -> rgAge.check(R.id.rbMature)
            AvatarAge.ELDER -> rgAge.check(R.id.rbElder)
        }

        // 动画风格
        when (editingConfig.animationStyle) {
            AnimationStyle.ANIMATION_3D -> rgAnimStyle.check(R.id.rbAnim3D)
            AnimationStyle.ANIMATION_2D -> rgAnimStyle.check(R.id.rbAnim2D)
        }

        // 表情
        tvCurrentExpression.text = "当前表情: ${editingConfig.expression.emoji} ${editingConfig.expression.displayName}"

        // 服装
        when (editingConfig.costume) {
            AvatarCostume.SCHOOL_UNIFORM -> rgCostume.check(R.id.rbSchoolUniform)
            AvatarCostume.SUIT -> rgCostume.check(R.id.rbSuit)
            AvatarCostume.SPORTSWEAR -> rgCostume.check(R.id.rbSportswear)
            AvatarCostume.HANFU -> rgCostume.check(R.id.rbHanfu)
            AvatarCostume.MECHA -> rgCostume.check(R.id.rbMecha)
            AvatarCostume.FANTASY_ROBE -> rgCostume.check(R.id.rbFantasyRobe)
        }

        // 自定义状态
        updateCustomStatus()

        // 装备列表
        refreshEquipmentList()

        // 3D模型状态
        update3DModelStatus()

        // 文生精灵
        val savedTextConfig = editingConfig.textToSpiritConfig
        if (!savedTextConfig.isNullOrBlank()) {
            etTextToSpirit.setText(savedTextConfig)
            tvTextToSpiritResult.text = "上次生成: $savedTextConfig"
        }

        // 大小
        val sizeLevel = prefs.getInt(FloatingAvatarService.KEY_SIZE, 1)
        when (sizeLevel) {
            0 -> rgSize.check(R.id.rbSmall)
            2 -> rgSize.check(R.id.rbLarge)
            else -> rgSize.check(R.id.rbMedium)
        }

        // 透明度
        val alpha = prefs.getFloat(FloatingAvatarService.KEY_ALPHA, 0.9f)
        val progress = (alpha * 100).toInt() - 10
        seekbarAlpha.progress = progress.coerceIn(0, 90)
        tvAlphaValue.text = "${(alpha * 100).toInt()}%"

        // 功能开关
        switchBubble.isChecked = prefs.getBoolean(FloatingAvatarService.KEY_BUBBLE_ENABLED, true)
        switchAutoHide.isChecked = prefs.getBoolean(FloatingAvatarService.KEY_AUTO_HIDE_FULLSCREEN, true)
        switchBootStart.isChecked = prefs.getBoolean(FloatingAvatarService.KEY_BOOT_START, false)
    }

    private fun updateCustomStatus() {
        val status = buildString {
            append("自定义外观状态:\n")
            if (editingConfig.customImagePath != null) {
                append("📷 图片: ${File(editingConfig.customImagePath!!).name}\n")
            }
            if (editingConfig.customVideoPath != null) {
                append("🎬 视频: ${File(editingConfig.customVideoPath!!).name}\n")
            }
            if (editingConfig.customImagePath == null && editingConfig.customVideoPath == null) {
                append("暂无自定义外观（使用默认精灵造型）")
            }
        }
        tvCustomStatus.text = status
    }

    // ============================================================
    // 自定义文件选择
    // ============================================================

    private fun pickImage() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply {
            type = "image/*"
        }
        startActivityForResult(intent, REQUEST_PICK_IMAGE)
    }

    private fun pickVideo() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Video.Media.EXTERNAL_CONTENT_URI).apply {
            type = "video/*"
        }
        startActivityForResult(intent, REQUEST_PICK_VIDEO)
    }

    @Deprecated("使用ActivityResultContracts替代")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return

        when (requestCode) {
            REQUEST_OVERLAY -> {
                checkPermission()
                if (hasOverlayPermission()) {
                    Toast.makeText(this, "✅ 悬浮窗权限已开启！", Toast.LENGTH_SHORT).show()
                    if (prefs.getBoolean(FloatingAvatarService.KEY_ENABLED, true)) {
                        FloatingAvatarService.start(this)
                    }
                }
            }
            REQUEST_PICK_IMAGE -> {
                val uri = data.data ?: return
                val savedPath = copyUriToInternal(uri, "custom_avatar.png")
                if (savedPath != null) {
                    editingConfig = editingConfig.copy(
                        customImagePath = savedPath,
                        customVideoPath = null  // 图片和视频互斥
                    )
                    saveAndNotify()
                    updateCustomStatus()
                    Toast.makeText(this, "✅ 自定义图片已设置", Toast.LENGTH_SHORT).show()
                }
            }
            REQUEST_PICK_VIDEO -> {
                val uri = data.data ?: return
                val savedPath = copyUriToInternal(uri, "custom_avatar_video.mp4")
                if (savedPath != null) {
                    // 提取首帧验证
                    try {
                        val retriever = MediaMetadataRetriever()
                        retriever.setDataSource(savedPath)
                        val frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        retriever.release()
                        if (frame != null) {
                            editingConfig = editingConfig.copy(
                                customVideoPath = savedPath,
                                customImagePath = null  // 图片和视频互斥
                            )
                            saveAndNotify()
                            updateCustomStatus()
                            Toast.makeText(this, "✅ 自定义视频已设置（已提取首帧）", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this, "❌ 无法提取视频帧", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        Toast.makeText(this, "❌ 视频处理失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            REQUEST_PICK_3D_MODEL -> {
                val uri = data.data ?: return
                // 获取文件名
                val fileName = uri.lastPathSegment ?: "model.glb"
                val ext = fileName.substringAfterLast(".", "glb")
                val savedPath = copyUriToInternal(uri, "custom_3d_model.$ext")
                if (savedPath != null) {
                    // 验证文件扩展名
                    if (ext.lowercase() in listOf("glb", "obj")) {
                        editingConfig = editingConfig.copy(custom3DModelPath = savedPath)
                        saveAndNotify()
                        update3DModelStatus()
                        Toast.makeText(this, "✅ 3D模型已加载: $fileName", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "❌ 不支持的格式，仅支持 .glb / .obj", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            REQUEST_PICK_EQUIPMENT_IMAGE -> {
                val uri = data.data ?: return
                val fileName = uri.lastPathSegment ?: "equipment.png"
                val savedPath = copyUriToInternal(uri, "equipment_${System.currentTimeMillis()}.png")
                if (savedPath != null) {
                    // 弹出对话框让用户输入用途描述
                    showEquipmentDescDialog(savedPath, fileName)
                }
            }
        }
    }

    /**
     * 显示装备描述输入对话框
     */
    private fun showEquipmentDescDialog(imagePath: String, fileName: String) {
        val editText = android.widget.EditText(this).apply {
            hint = "描述这件装备的用途..."
            setText(fileName)
            setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(12))
        }
        AlertDialog.Builder(this)
            .setTitle("📎 装备用途描述")
            .setView(editText)
            .setPositiveButton("添加") { _, _ ->
                val desc = editText.text.toString().trim().ifEmpty { "装备" }
                val item = EquipmentItem(
                    imagePath = imagePath,
                    description = desc,
                    id = System.currentTimeMillis()
                )
                editingEquipmentList.add(item)
                saveAndNotify()
                refreshEquipmentList()
                Toast.makeText(this, "✅ 装备已添加: $desc", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 将Uri内容复制到应用内部存储
     */
    private fun copyUriToInternal(uri: Uri, fileName: String): String? {
        try {
            val dir = File(filesDir, "avatar_custom")
            if (!dir.exists()) dir.mkdirs()
            val outFile = File(dir, fileName)
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                }
            }
            return outFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "[文件] 复制失败: ${e.message}", e)
            return null
        }
    }

    // ============================================================
    // 装备系统功能
    // ============================================================

    /**
     * 选择装备图片
     */
    private fun pickEquipmentImage() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply {
            type = "image/*"
        }
        startActivityForResult(intent, REQUEST_PICK_EQUIPMENT_IMAGE)
    }

    /**
     * 显示装备列表并刷新UI
     */
    private fun refreshEquipmentList() {
        llEquipmentList.removeAllViews()
        if (editingEquipmentList.isEmpty()) {
            tvEquipmentEmpty.visibility = View.VISIBLE
            return
        }
        tvEquipmentEmpty.visibility = View.GONE

        for ((index, item) in editingEquipmentList.withIndex()) {
            val itemView = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dpToPx(4) }
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = dpToPx(8).toFloat()
                    setColor(Color.argb(40, 255, 255, 255))
                }
            }

            // 图片预览
            val imgView = android.widget.ImageView(this).apply {
                val imgFile = File(item.imagePath)
                if (imgFile.exists()) {
                    val bitmap = BitmapFactory.decodeFile(item.imagePath)
                    if (bitmap != null) {
                        val size = dpToPx(40)
                        layoutParams = LinearLayout.LayoutParams(size, size).apply {
                            marginEnd = dpToPx(8)
                        }
                        setImageBitmap(bitmap)
                        scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                    }
                } else {
                    val size = dpToPx(40)
                    layoutParams = LinearLayout.LayoutParams(size, size).apply {
                        marginEnd = dpToPx(8)
                    }
                    setImageResource(android.R.drawable.ic_menu_gallery)
                }
            }
            itemView.addView(imgView)

            // 描述文字
            val descView = TextView(this).apply {
                text = item.description.ifEmpty { "装备 #${index + 1}" }
                setTextColor(Color.WHITE)
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            }
            itemView.addView(descView)

            // 删除按钮
            val btnRemove = Button(this).apply {
                text = "❌"
                textSize = 12f
                setPadding(dpToPx(4), dpToPx(2), dpToPx(4), dpToPx(2))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setOnClickListener {
                    editingEquipmentList.removeAt(index)
                    saveAndNotify()
                    refreshEquipmentList()
                    Toast.makeText(this@FloatingSettingsActivity, "装备已移除", Toast.LENGTH_SHORT).show()
                }
            }
            itemView.addView(btnRemove)

            llEquipmentList.addView(itemView)
        }
    }

    // ============================================================
    // 3D模型上传功能
    // ============================================================

    /**
     * 选择3D模型文件（.glb, .obj）
     */
    private fun pick3DModel() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                "model/gltf-binary", "application/octet-stream"
            ))
        }
        startActivityForResult(intent, REQUEST_PICK_3D_MODEL)
    }

    /**
     * 更新3D模型状态显示
     */
    private fun update3DModelStatus() {
        val path = editingConfig.custom3DModelPath
        if (path != null && path.isNotBlank()) {
            val file = File(path)
            tv3DModelStatus.text = if (file.exists()) {
                "🎮 已加载: ${file.name} (${file.length() / 1024}KB)\n3D效果模拟渲染中"
            } else {
                "⚠️ 模型文件已丢失"
            }
        } else {
            tv3DModelStatus.text = "未加载3D模型（支持 .glb / .obj 文件）"
        }
    }

    // ============================================================
    // 文生精灵功能
    // ============================================================

    /**
     * 根据文本描述生成精灵外观配置
     * 通过关键词匹配预设模板，自动选择合适的外观参数
     */
    private fun generateSpiritFromText() {
        val text = etTextToSpirit.text.toString().trim()
        if (text.isBlank()) {
            Toast.makeText(this, "请输入精灵描述", Toast.LENGTH_SHORT).show()
            return
        }

        // 根据关键词匹配预设模板
        val matchedConfig = matchTemplateFromText(text)
        editingConfig = matchedConfig.copy(textToSpiritConfig = text)
        saveAndNotify()

        tvTextToSpiritResult.text = buildString {
            appendLine("✅ 已根据描述生成精灵配置:")
            appendLine("📝 描述: $text")
            appendLine("👤 性别: ${matchedConfig.gender.displayName}")
            appendLine("🎂 年龄: ${matchedConfig.age.displayName}")
            appendLine("🎭 性格: ${matchedConfig.personality.displayName}")
            appendLine("😊 表情: ${matchedConfig.expression.emoji} ${matchedConfig.expression.displayName}")
            appendLine("👔 服装: ${matchedConfig.costume.emoji} ${matchedConfig.costume.displayName}")
            appendLine("🎨 风格: ${matchedConfig.animationStyle.displayName}")
        }

        Toast.makeText(this, "✨ 文生精灵已应用", Toast.LENGTH_SHORT).show()
    }

    /**
     * 根据文本关键词匹配预设模板
     */
    private fun matchTemplateFromText(text: String): AvatarConfig {
        val lowerText = text.lowercase()
        var gender = AvatarGender.NONE
        var age = AvatarAge.YOUNG_ADULT
        var personality = AvatarPersonality.GENTLE
        var expression = AvatarExpression.NEUTRAL
        var costume = AvatarCostume.SUIT
        var animStyle = AnimationStyle.ANIMATION_3D

        // 性别匹配
        when {
            lowerText.contains("男") || lowerText.contains("boy") || lowerText.contains("male") ->
                gender = AvatarGender.MALE
            lowerText.contains("女") || lowerText.contains("girl") || lowerText.contains("female") ->
                gender = AvatarGender.FEMALE
            lowerText.contains("双性男") || lowerText.contains("intersex male") ->
                gender = AvatarGender.INTERSEX_MALE
            lowerText.contains("双性女") || lowerText.contains("intersex female") ->
                gender = AvatarGender.INTERSEX_FEMALE
            lowerText.contains("无性别") || lowerText.contains("中性") ->
                gender = AvatarGender.NONE
        }

        // 年龄匹配
        when {
            lowerText.contains("儿童") || lowerText.contains("小孩") || lowerText.contains("child") ->
                age = AvatarAge.CHILD
            lowerText.contains("少年") || lowerText.contains("teen") ->
                age = AvatarAge.TEEN
            lowerText.contains("青年") || lowerText.contains("young") ->
                age = AvatarAge.YOUNG_ADULT
            lowerText.contains("中年") || lowerText.contains("middle") ->
                age = AvatarAge.MIDDLE_AGE
            lowerText.contains("成熟") || lowerText.contains("mature") ->
                age = AvatarAge.MATURE
            lowerText.contains("老年") || lowerText.contains("old") || lowerText.contains("elder") ->
                age = AvatarAge.ELDER
        }

        // 性格匹配
        when {
            lowerText.contains("温柔") || lowerText.contains("gentle") ->
                personality = AvatarPersonality.GENTLE
            lowerText.contains("活泼") || lowerText.contains("lively") ->
                personality = AvatarPersonality.LIVELY
            lowerText.contains("高冷") || lowerText.contains("cool") ->
                personality = AvatarPersonality.COOL
            lowerText.contains("可爱") || lowerText.contains("cute") ->
                personality = AvatarPersonality.CUTE
            lowerText.contains("帅气") || lowerText.contains("handsome") ->
                personality = AvatarPersonality.HANDSOME
            lowerText.contains("神秘") || lowerText.contains("mysterious") || lowerText.contains("dark") ->
                personality = AvatarPersonality.MYSTERIOUS
        }

        // 表情匹配
        when {
            lowerText.contains("开心") || lowerText.contains("happy") || lowerText.contains("笑") ->
                expression = AvatarExpression.HAPPY
            lowerText.contains("难过") || lowerText.contains("sad") || lowerText.contains("哭") ->
                expression = AvatarExpression.SAD
            lowerText.contains("思考") || lowerText.contains("think") ->
                expression = AvatarExpression.THINKING
            lowerText.contains("愤怒") || lowerText.contains("angry") || lowerText.contains("生气") ->
                expression = AvatarExpression.ANGRY
            lowerText.contains("困") || lowerText.contains("sleep") || lowerText.contains("困倦") ->
                expression = AvatarExpression.SLEEPY
            lowerText.contains("害羞") || lowerText.contains("shy") ->
                expression = AvatarExpression.SHY
            lowerText.contains("兴奋") || lowerText.contains("excited") ->
                expression = AvatarExpression.EXCITED
            lowerText.contains("觉醒") || lowerText.contains("evolv") ->
                expression = AvatarExpression.EVOLVING
        }

        // 服装匹配
        when {
            lowerText.contains("校服") || lowerText.contains("school") ->
                costume = AvatarCostume.SCHOOL_UNIFORM
            lowerText.contains("西装") || lowerText.contains("suit") || lowerText.contains("正式") ->
                costume = AvatarCostume.SUIT
            lowerText.contains("运动") || lowerText.contains("sport") ->
                costume = AvatarCostume.SPORTSWEAR
            lowerText.contains("汉服") || lowerText.contains("hanfu") || lowerText.contains("古风") ->
                costume = AvatarCostume.HANFU
            lowerText.contains("机甲") || lowerText.contains("mecha") || lowerText.contains("机器") ->
                costume = AvatarCostume.MECHA
            lowerText.contains("法袍") || lowerText.contains("robe") || lowerText.contains("魔法") || lowerText.contains("wizard") ->
                costume = AvatarCostume.FANTASY_ROBE
        }

        // 风格匹配
        when {
            lowerText.contains("2d") || lowerText.contains("扁平") || lowerText.contains("flat") ->
                animStyle = AnimationStyle.ANIMATION_2D
            lowerText.contains("3d") || lowerText.contains("立体") ->
                animStyle = AnimationStyle.ANIMATION_3D
        }

        return AvatarConfig(
            gender = gender,
            age = age,
            personality = personality,
            expression = expression,
            costume = costume,
            animationStyle = animStyle
        )
    }

    // ============================================================
    // 保存并通知Service更新
    // ============================================================

    /**
     * 保存当前编辑配置到SharedPreferences并通知Service刷新
     * 包含装备列表、3D模型、文生精灵配置
     */
    private fun saveAndNotify() {
        // 同步装备列表到配置
        editingConfig = editingConfig.copy(equipmentList = editingEquipmentList.toList())

        prefs.edit()
            .putString(FloatingAvatarService.KEY_AVATAR_GENDER, editingConfig.gender.code)
            .putString(FloatingAvatarService.KEY_AVATAR_AGE, editingConfig.age.code)
            .putString(FloatingAvatarService.KEY_AVATAR_PERSONALITY, editingConfig.personality.code)
            .putString(FloatingAvatarService.KEY_AVATAR_EXPRESSION, editingConfig.expression.code)
            .putString(FloatingAvatarService.KEY_AVATAR_HAND, editingConfig.handAction.code)
            .putString(FloatingAvatarService.KEY_AVATAR_FOOT, editingConfig.footAction.code)
            .putString(FloatingAvatarService.KEY_AVATAR_COSTUME, editingConfig.costume.code)
            .putString(FloatingAvatarService.KEY_AVATAR_OVERALL, editingConfig.overallAction.code)
            .putString(FloatingAvatarService.KEY_AVATAR_SHAPE, editingConfig.shape.code)
            .putString(FloatingAvatarService.KEY_AVATAR_CUSTOM_IMAGE, editingConfig.customImagePath)
            .putString(FloatingAvatarService.KEY_AVATAR_CUSTOM_VIDEO, editingConfig.customVideoPath)
            .putFloat(FloatingAvatarService.KEY_AVATAR_SIZE_MULTIPLIER, editingConfig.sizeMultiplier)
            .putString(FloatingAvatarService.KEY_AVATAR_ANIMATION_STYLE, editingConfig.animationStyle.code)
            .putString(FloatingAvatarService.KEY_AVATAR_3D_MODEL, editingConfig.custom3DModelPath)
            .putString(FloatingAvatarService.KEY_AVATAR_EQUIPMENT_LIST, serializeEquipmentList(editingEquipmentList))
            .putString(FloatingAvatarService.KEY_AVATAR_TEXT_TO_SPIRIT, editingConfig.textToSpiritConfig)
            .apply {
                if (editingConfig.glowColor != null) {
                    putInt(FloatingAvatarService.KEY_AVATAR_GLOW_COLOR, editingConfig.glowColor!!)
                } else {
                    remove(FloatingAvatarService.KEY_AVATAR_GLOW_COLOR)
                }
            }
            .apply()

        // 通知Service重新加载配置
        val intent = Intent(this, FloatingAvatarService::class.java).apply {
            action = FloatingAvatarService.ACTION_UPDATE_CONFIG
        }
        try { startService(intent) } catch (_: Exception) { }
    }

    // ============================================================
    // 权限处理
    // ============================================================

    private fun checkPermission() {
        val hasPermission = hasOverlayPermission()
        val isNotificationEnabled = isNotificationEnabled()
        val statusText = buildString {
            append("悬浮窗权限: ")
            append(if (hasPermission) "✅ 已开启" else "❌ 未开启")
            append("\n通知权限: ")
            append(if (isNotificationEnabled) "✅ 已开启" else "⚠️ 未开启(可选)")
        }
        tvPermissionStatus.text = statusText
        btnGrantPermission.visibility = if (!hasPermission) View.VISIBLE else View.GONE
        if (!hasPermission) {
            switchEnabled.isEnabled = false
            tvPermissionStatus.setTextColor(Color.parseColor("#FF6B6B"))
        } else {
            switchEnabled.isEnabled = true
            tvPermissionStatus.setTextColor(Color.parseColor("#FFFFFF"))
        }
    }

    private fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else true
    }

    private fun isNotificationEnabled(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
        } else true
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, REQUEST_OVERLAY)
        }
    }

    private fun dpToPx(dp: Int): Int {
        return android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), resources.displayMetrics
        ).toInt()
    }
}
