/*
 * AvatarEditActivity - 化身编辑页
 * 可修改化身外观参数，不变更GUID身份/人格/意识
 */
package com.kkgo.mindsoul.ui

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.ChipGroup
import com.kkgo.mindsoul.MindSoulApp
import com.kkgo.mindsoul.R
import com.kkgo.mindsoul.avatar.AvatarAppearance
import com.kkgo.mindsoul.avatar.AvatarStyle
import com.kkgo.mindsoul.avatar.BodyParameters
import com.kkgo.mindsoul.avatar.ModelType
import com.kkgo.mindsoul.model.PersonalityVector

class AvatarEditActivity : AppCompatActivity() {

    private val app by lazy { application as MindSoulApp }

    // 控件引用
    private lateinit var tvAvatarPreview: TextView
    private lateinit var tvAvatarDesc: TextView
    private lateinit var tvAvatarStyle: TextView
    private lateinit var rgGender: RadioGroup
    private lateinit var rgAge: RadioGroup
    private lateinit var chipGroupStyle: ChipGroup
    private lateinit var seekOpenness: SeekBar
    private lateinit var seekConscientiousness: SeekBar
    private lateinit var seekExtraversion: SeekBar
    private lateinit var seekAgreeableness: SeekBar
    private lateinit var seekNeuroticism: SeekBar
    private lateinit var spinnerOutfit: Spinner
    private lateinit var seekColorHue: SeekBar
    private lateinit var viewColorPreview: View
    private lateinit var etHair: EditText
    private lateinit var etEyeColor: EditText
    private lateinit var etCustomDesc: EditText
    private lateinit var btnSave: MaterialButton
    private lateinit var btnReset: MaterialButton

    // 服装预设
    private val outfitOptions = listOf(
        "默认白袍", "科技装甲", "学院风制服", "古风汉服",
        "赛博朋克装", "精灵斗篷", "运动休闲", "正装礼服"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_avatar_edit)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        initViews()
        loadCurrentSettings()
        setupListeners()
    }

    private fun initViews() {
        tvAvatarPreview = findViewById(R.id.tvAvatarPreview)
        tvAvatarDesc = findViewById(R.id.tvAvatarDesc)
        tvAvatarStyle = findViewById(R.id.tvAvatarStyle)
        rgGender = findViewById(R.id.rgGender)
        rgAge = findViewById(R.id.rgAge)
        chipGroupStyle = findViewById(R.id.chipGroupStyle)
        seekOpenness = findViewById(R.id.seekOpenness)
        seekConscientiousness = findViewById(R.id.seekConscientiousness)
        seekExtraversion = findViewById(R.id.seekExtraversion)
        seekAgreeableness = findViewById(R.id.seekAgreeableness)
        seekNeuroticism = findViewById(R.id.seekNeuroticism)
        spinnerOutfit = findViewById(R.id.spinnerOutfit)
        seekColorHue = findViewById(R.id.seekColorHue)
        viewColorPreview = findViewById(R.id.viewColorPreview)
        etHair = findViewById(R.id.etHair)
        etEyeColor = findViewById(R.id.etEyeColor)
        etCustomDesc = findViewById(R.id.etCustomDesc)
        btnSave = findViewById(R.id.btnSave)
        btnReset = findViewById(R.id.btnReset)

        // 设置服装Spinner
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, outfitOptions)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerOutfit.adapter = adapter
    }

    private fun loadCurrentSettings() {
        val appearance = app.avatarManager.currentAppearance
        val personality = app.avatarManager.guidIdentity.personalityVector

        if (appearance != null) {
            tvAvatarDesc.text = appearance.name
            tvAvatarStyle.text = appearance.bodyParams.style.displayName

            // 更新预览emoji
            tvAvatarPreview.text = when (appearance.bodyParams.style) {
                AvatarStyle.CUTE -> "🧸"
                AvatarStyle.REALISTIC -> "🧑"
                AvatarStyle.SCI_FI -> "🤖"
                AvatarStyle.FANTASY -> "🧝"
            }
        }

        // 加载性格参数
        seekOpenness.progress = (personality.openness * 100).toInt()
        seekConscientiousness.progress = (personality.conscientiousness * 100).toInt()
        seekExtraversion.progress = (personality.extraversion * 100).toInt()
        seekAgreeableness.progress = (personality.agreeableness * 100).toInt()
        seekNeuroticism.progress = (personality.neuroticism * 100).toInt()
    }

    private fun setupListeners() {
        // 颜色选择器
        seekColorHue.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val color = Color.HSVToColor(floatArrayOf(progress.toFloat(), 0.8f, 0.9f))
                    viewColorPreview.setBackgroundColor(color)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // 风格切换
        chipGroupStyle.setOnCheckedStateChangeListener { group, checkedIds ->
            if (checkedIds.isNotEmpty()) {
                val style = when (checkedIds.first()) {
                    R.id.chipCute -> "🧸 可爱卡通"
                    R.id.chipRealistic -> "🧑 写实人形"
                    R.id.chipSciFi -> "🤖 科幻机械"
                    R.id.chipFantasy -> "🧝 奇幻精灵"
                    else -> "🧑 写实人形"
                }
                tvAvatarStyle.text = style
                tvAvatarPreview.text = style.split(" ")[0]
            }
        }

        // 保存按钮
        btnSave.setOnClickListener { saveAvatarSettings() }

        // 重置按钮
        btnReset.setOnClickListener { loadCurrentSettings() }
    }

    private fun saveAvatarSettings() {
        // 获取选择的风格
        val avatarStyle = when (chipGroupStyle.checkedChipId) {
            R.id.chipCute -> AvatarStyle.CUTE
            R.id.chipSciFi -> AvatarStyle.SCI_FI
            R.id.chipFantasy -> AvatarStyle.FANTASY
            else -> AvatarStyle.REALISTIC
        }

        // 构建体型参数
        val bodyParams = BodyParameters(
            style = avatarStyle,
            height = when (rgAge.checkedRadioButtonId) {
                R.id.rbYoung -> 0.8f
                R.id.rbYouth -> 1.0f
                R.id.rbMiddle -> 1.1f
                R.id.rbMature -> 1.15f
                else -> 1.0f
            }
        )

        // 构建新外观
        val customDesc = etCustomDesc.text.toString()
        val appearance = AvatarAppearance(
            name = customDesc.ifEmpty { "${avatarStyle.displayName}外观" },
            modelType = if (customDesc.isNotEmpty()) ModelType.AI_GENERATED else ModelType.BUILTIN_HUMANOID,
            aiDescription = customDesc.ifEmpty {
                app.avatarManager.currentAppearance?.aiDescription ?: "默认人形描述"
            },
            primaryColor = Color.HSVToColor(
                floatArrayOf(seekColorHue.progress.toFloat(), 0.8f, 0.9f)
            ).toLong(),
            bodyParams = bodyParams
        )

        // 注册并切换外观
        app.avatarManager.registerAppearance(appearance)
        app.avatarManager.switchAppearance(appearance.appearanceId)

        // 更新性格向量
        val personalityDelta = PersonalityVector(
            openness = (seekOpenness.progress / 100.0 - app.avatarManager.guidIdentity.personalityVector.openness),
            conscientiousness = (seekConscientiousness.progress / 100.0 - app.avatarManager.guidIdentity.personalityVector.conscientiousness),
            extraversion = (seekExtraversion.progress / 100.0 - app.avatarManager.guidIdentity.personalityVector.extraversion),
            agreeableness = (seekAgreeableness.progress / 100.0 - app.avatarManager.guidIdentity.personalityVector.agreeableness),
            neuroticism = (seekNeuroticism.progress / 100.0 - app.avatarManager.guidIdentity.personalityVector.neuroticism)
        )
        app.avatarManager.updatePersonality(personalityDelta)

        Toast.makeText(this, "化身外观已保存（GUID身份未变更）", Toast.LENGTH_SHORT).show()
        finish()
    }
}
