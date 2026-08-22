/*
 * PerceptionActivity - 五感状态页
 * 显示五感模块（视觉/听觉/TTS/触觉/网络）的实时状态
 */
package com.kkgo.mindsoul.ui

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.kkgo.mindsoul.MindSoulApp
import com.kkgo.mindsoul.R
import com.kkgo.mindsoul.perception.AudioState
import com.kkgo.mindsoul.perception.VideoIntercomState
import kotlinx.coroutines.*

class PerceptionActivity : AppCompatActivity() {

    private val app by lazy { application as MindSoulApp }
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var tvVisionStatus: TextView
    private lateinit var tvVisionDetail: TextView
    private lateinit var tvAudioStatus: TextView
    private lateinit var tvAudioDetail: TextView
    private lateinit var tvTtsStatus: TextView
    private lateinit var tvTtsDetail: TextView
    private lateinit var tvTouchStatus: TextView
    private lateinit var tvTouchDetail: TextView
    private lateinit var tvNetworkStatus: TextView
    private lateinit var tvNetworkDetail: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_perception)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        initViews()
        startStatusUpdates()
    }

    private fun initViews() {
        tvVisionStatus = findViewById(R.id.tvVisionStatus)
        tvVisionDetail = findViewById(R.id.tvVisionDetail)
        tvAudioStatus = findViewById(R.id.tvAudioStatus)
        tvAudioDetail = findViewById(R.id.tvAudioDetail)
        tvTtsStatus = findViewById(R.id.tvTtsStatus)
        tvTtsDetail = findViewById(R.id.tvTtsDetail)
        tvTouchStatus = findViewById(R.id.tvTouchStatus)
        tvTouchDetail = findViewById(R.id.tvTouchDetail)
        tvNetworkStatus = findViewById(R.id.tvNetworkStatus)
        tvNetworkDetail = findViewById(R.id.tvNetworkDetail)
    }

    private fun startStatusUpdates() {
        scope.launch {
            while (isActive) {
                updatePerceptionStatus()
                delay(3000)
            }
        }
    }

    private fun updatePerceptionStatus() {
        val perception = app.perceptionSystem

        // 1. 视觉模块
        val visionActive = perception.vision.isRunningFlow.value
        tvVisionStatus.text = if (visionActive) "运行中" else "待机中"
        tvVisionStatus.setTextColor(
            resources.getColor(
                if (visionActive) R.color.status_online else R.color.status_offline, theme
            )
        )
        tvVisionDetail.text = buildString {
            appendLine("状态: ${if (visionActive) "已启用" else "已禁用"}")
            appendLine("自动截图: ${if (visionActive) "运行中" else "已停止"}")
            appendLine("最近识别: —")
        }

        // 2. 听觉模块
        val audioState = perception.audio.audioStateFlow.value
        val audioRunning = audioState != AudioState.IDLE
        tvAudioStatus.text = if (audioRunning) "工作中" else "待机中"
        tvAudioStatus.setTextColor(
            resources.getColor(
                if (audioRunning) R.color.status_online else R.color.status_offline, theme
            )
        )
        tvAudioDetail.text = buildString {
            appendLine("ASR语音识别: ${if (visionActive) "已启用" else "已禁用"}")
            appendLine("VAD语音检测: ${if (audioRunning) "检测中" else "就绪"}")
            appendLine("当前状态: ${audioState.name}")
            appendLine("最近识别: —")
        }

        // 3. TTS语音输出
        val ttsEnabled = app.switchCenter.getSwitchState(
            com.kkgo.mindsoul.switches.SwitchId.VOICE
        )?.enabled ?: false
        tvTtsStatus.text = if (ttsEnabled) "就绪" else "已禁用"
        tvTtsStatus.setTextColor(
            resources.getColor(
                if (ttsEnabled) R.color.status_online else R.color.status_offline, theme
            )
        )
        tvTtsDetail.text = buildString {
            appendLine("TTS引擎: 系统默认")
            appendLine("语速: 1.0x")
            appendLine("状态: ${if (ttsEnabled) "就绪" else "已禁用"}")
        }

        // 4. 触觉模块
        val touchActive = true // 触觉始终可用
        tvTouchStatus.text = "运行中"
        tvTouchStatus.setTextColor(resources.getColor(R.color.status_online, theme))
        tvTouchDetail.text = buildString {
            appendLine("手势识别: 已启用")
            appendLine("触屏交互: 活跃")
            appendLine("最近手势: —")
        }

        // 5. 网络感知模块
        val networkState = perception.networkVision.stateFlow.value
        val networkConnected = networkState == VideoIntercomState.CONNECTED
        tvNetworkStatus.text = if (networkConnected) "已连接" else if (networkState == VideoIntercomState.DISCOVERING) "发现中" else "待机中"
        tvNetworkStatus.setTextColor(
            resources.getColor(
                when {
                    networkConnected -> R.color.status_online
                    networkState == VideoIntercomState.DISCOVERING -> R.color.status_warning
                    else -> R.color.status_offline
                }, theme
            )
        )
        val devices = app.networkDiscovery.getDevices()
        tvNetworkDetail.text = buildString {
            appendLine("局域网连接: ${if (networkConnected) "已连接" else "未连接"}")
            appendLine("发现设备: ${devices.size}")
            appendLine("P2P状态: ${networkState.name}")
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
