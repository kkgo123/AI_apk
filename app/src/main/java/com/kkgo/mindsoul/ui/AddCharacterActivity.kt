/*
 * ============================================================
 * AddCharacterActivity - 添加/编辑角色卡界面
 * ============================================================
 *
 * 核心功能：
 * 1. 头像选择（从相册选择 / 相机拍照）
 * 2. 输入角色姓名
 * 3. 性别选择（男/女/双性男/双性女）
 * 4. 年龄输入
 * 5. 角色设定（多行文本，描述性格、背景等）
 * 6. 保存到数据库
 *
 * 支持两种模式：
 * - 创建模式：全新创建角色卡
 * - 编辑模式：修改已有角色卡（通过 Intent 传入 edit_mode=true）
 *
 * 对接模块：
 * - CharacterDatabase（角色卡持久化）
 * ============================================================
 */
package com.kkgo.mindsoul.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import com.kkgo.mindsoul.R
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream

class AddCharacterActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "AddCharacterActivity"
        private const val TEMP_DIR = "mindsoul_char_temp"
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ============ 数据库 ============
    private lateinit var characterDatabase: CharacterDatabase

    // ============ 界面元素 ============
    private lateinit var ivAvatar: ImageView
    private lateinit var fabChangeAvatar: FloatingActionButton
    private lateinit var etName: TextInputEditText
    private lateinit var rgGender: RadioGroup
    private lateinit var rbMale: RadioButton
    private lateinit var rbFemale: RadioButton
    private lateinit var rbBiMale: RadioButton
    private lateinit var rbBiFemale: RadioButton
    private lateinit var etAge: TextInputEditText
    private lateinit var etPersonality: TextInputEditText
    private lateinit var btnSave: MaterialButton
    private lateinit var toolbar: MaterialToolbar

    // ============ 数据 ============
    /** 是否为编辑模式 */
    private var isEditMode = false
    /** 编辑模式下的角色ID */
    private var editCharacterId: Long = 0
    /** 当前头像路径 */
    private var currentAvatarPath: String? = null
    /** 拍照临时文件URI */
    private var tempPhotoUri: Uri? = null

    // ============ Activity Result 启动器 ============

    /** 从相册选择图片 */
    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { handleImageSelected(it) }
    }

    /** 拍照结果 */
    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && tempPhotoUri != null) {
            handleImageSelected(tempPhotoUri!!)
        }
        tempPhotoUri = null
    }

    /** 相机权限请求 */
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchCamera()
        else Toast.makeText(this, "需要相机权限才能拍照", Toast.LENGTH_SHORT).show()
    }

    // ============ 生命周期 ============

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_character)

        characterDatabase = CharacterDatabase(this)

        initViews()
        setupToolbar()
        setupAvatarActions()
        setupSaveButton()
        loadEditData()
    }

    override fun onDestroy() {
        scope.cancel()
        characterDatabase.close()
        super.onDestroy()
    }

    // ============ 初始化 ============

    private fun initViews() {
        ivAvatar = findViewById(R.id.ivAvatar)
        fabChangeAvatar = findViewById(R.id.fabChangeAvatar)
        etName = findViewById(R.id.etName)
        rgGender = findViewById(R.id.rgGender)
        rbMale = findViewById(R.id.rbMale)
        rbFemale = findViewById(R.id.rbFemale)
        rbBiMale = findViewById(R.id.rbBiMale)
        rbBiFemale = findViewById(R.id.rbBiFemale)
        etAge = findViewById(R.id.etAge)
        etPersonality = findViewById(R.id.etPersonality)
        btnSave = findViewById(R.id.btnSave)
        toolbar = findViewById(R.id.toolbar)

        // 默认选中"男"
        rbMale.isChecked = true
    }

    private fun setupToolbar() {
        toolbar.setNavigationOnClickListener { finish() }
    }

    // ============ 头像选择 ============

    private fun setupAvatarActions() {
        fabChangeAvatar.setOnClickListener {
            showAvatarPickerDialog()
        }

        ivAvatar.setOnClickListener {
            showAvatarPickerDialog()
        }
    }

    /**
     * 显示头像选择对话框（相册/相机）
     */
    private fun showAvatarPickerDialog() {
        val options = arrayOf("从相册选择", "拍照")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("选择头像")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openGallery()
                    1 -> requestCameraAndTakePhoto()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 打开相册选择图片
     */
    private fun openGallery() {
        galleryLauncher.launch("image/*")
    }

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
     */
    private fun launchCamera() {
        try {
            val tempDir = File(cacheDir, TEMP_DIR)
            if (!tempDir.exists()) tempDir.mkdirs()
            val photoFile = File(tempDir, "char_photo_${System.currentTimeMillis()}.jpg")
            tempPhotoUri = FileProvider.getUriForFile(
                this,
                "${packageName}.brainfile",
                photoFile
            )
            cameraLauncher.launch(tempPhotoUri!!)
        } catch (e: Exception) {
            Toast.makeText(this, "无法启动相机: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 处理选择的图片
     * 将图片复制到应用内部存储，并显示在头像 ImageView
     */
    private fun handleImageSelected(uri: Uri) {
        scope.launch {
            val savedPath = withContext(Dispatchers.IO) {
                copyUriToInternalStorage(uri)
            }
            if (savedPath != null) {
                currentAvatarPath = savedPath
                try {
                    ivAvatar.setImageURI(Uri.parse(savedPath))
                } catch (e: Exception) {
                    // URI 无效，使用默认背景
                }
            } else {
                // 如果复制失败，直接使用原始 URI
                currentAvatarPath = uri.toString()
                try {
                    ivAvatar.setImageURI(uri)
                } catch (e: Exception) {
                    // ignore
                }
            }
        }
    }

    /**
     * 将 URI 指向的图片复制到应用内部存储
     * 返回复制后的文件路径
     */
    private fun copyUriToInternalStorage(uri: Uri): String? {
        return try {
            val inputStream = contentResolver.openInputStream(uri) ?: return null
            val fileName = "avatar_${System.currentTimeMillis()}.jpg"
            val outFile = File(filesDir, "avatars/$fileName")
            outFile.parentFile?.mkdirs()
            FileOutputStream(outFile).use { output ->
                inputStream.copyTo(output)
            }
            inputStream.close()
            Uri.fromFile(outFile).toString()
        } catch (e: Exception) {
            null
        }
    }

    // ============ 编辑模式 ============

    /**
     * 加载编辑模式数据（如果 Intent 中包含 edit_mode=true）
     */
    private fun loadEditData() {
        isEditMode = intent.getBooleanExtra("edit_mode", false)
        if (!isEditMode) return

        editCharacterId = intent.getLongExtra("character_id", 0)
        val name = intent.getStringExtra("character_name") ?: ""
        val gender = intent.getStringExtra("character_gender") ?: "男"
        val age = intent.getStringExtra("character_age") ?: ""
        val avatar = intent.getStringExtra("character_avatar")
        val personality = intent.getStringExtra("character_personality") ?: ""

        // 填充表单
        etName.setText(name)
        etAge.setText(age)
        etPersonality.setText(personality)
        currentAvatarPath = avatar

        // 设置性别选中
        when (gender) {
            "男" -> rbMale.isChecked = true
            "女" -> rbFemale.isChecked = true
            "双性男" -> rbBiMale.isChecked = true
            "双性女" -> rbBiFemale.isChecked = true
        }

        // 加载头像
        if (!avatar.isNullOrEmpty()) {
            try {
                ivAvatar.setImageURI(Uri.parse(avatar))
            } catch (e: Exception) {
                // ignore
            }
        }

        // 更新标题
        toolbar.title = "编辑角色"
        btnSave.text = "💾 保存修改"
    }

    // ============ 保存 ============

    private fun setupSaveButton() {
        btnSave.setOnClickListener {
            saveCharacter()
        }
    }

    /**
     * 验证并保存角色卡
     */
    private fun saveCharacter() {
        val name = etName.text.toString().trim()
        val age = etAge.text.toString().trim()
        val personality = etPersonality.text.toString().trim()

        // 验证必填字段
        if (name.isEmpty()) {
            etName.error = "请输入角色姓名"
            etName.requestFocus()
            return
        }

        if (personality.isEmpty()) {
            etPersonality.error = "请输入角色设定"
            etPersonality.requestFocus()
            return
        }

        // 获取选中的性别
        val gender = when (rgGender.checkedRadioButtonId) {
            R.id.rbMale -> "男"
            R.id.rbFemale -> "女"
            R.id.rbBiMale -> "双性男"
            R.id.rbBiFemale -> "双性女"
            else -> "男"
        }

        val character = CharacterCard(
            id = if (isEditMode) editCharacterId else 0,
            name = name,
            gender = gender,
            age = age,
            avatarPath = currentAvatarPath,
            personality = personality
        )

        scope.launch {
            val success = withContext(Dispatchers.Default) {
                try {
                    if (isEditMode) {
                        characterDatabase.updateCharacter(character) > 0
                    } else {
                        characterDatabase.insertCharacter(character) > 0
                    }
                } catch (e: Exception) {
                    false
                }
            }

            if (success) {
                Toast.makeText(
                    this@AddCharacterActivity,
                    if (isEditMode) "角色已更新" else "角色创建成功",
                    Toast.LENGTH_SHORT
                ).show()
                setResult(RESULT_OK)
                finish()
            } else {
                Toast.makeText(
                    this@AddCharacterActivity,
                    "保存失败，请重试",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
}
