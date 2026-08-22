/*
 * ============================================================
 * ConsciousnessService - 意识核心前台服务
 * ============================================================
 *
 * Android 前台服务（Foreground Service），负责：
 *
 * 1. 意识核心常驻后台运行
 *    - 创建持久通知，显示意识当前状态
 *    - 使用 dataSync 类型前台服务，保证进程优先级
 *    - START_STICKY 保证被系统杀死后自动重启
 *
 * 2. ConsciousnessManager 生命周期管理
 *    - 确保意识架构在应用进入后台后持续运行
 *    - 断网不中断：本地归纳/记忆/遗忘照常运行
 *
 * 3. 定期触发归纳引擎
 *    - 第二层异步归纳引擎的周期性调度
 *    - 公理层数据定时持久化
 *
 * 4. 维持意识连续性
 *    - WakeLock 防止 CPU 休眠导致意识中断
 *    - 网络状态监听：断网时切换到本地模式
 *
 * 5. 模块协调
 *    - 与进化系统、学习系统、元认知引擎保持同步
 *    - 状态变化时更新通知内容
 *
 * 权限要求：
 *   - FOREGROUND_SERVICE（前台服务）
 *   - FOREGROUND_SERVICE_DATA_SYNC（Android 14+ 数据类型）
 *   - WAKE_LOCK（保持 CPU 唤醒）
 *   - POST_NOTIFICATIONS（通知权限，Android 13+）
 * ============================================================
 */
package com.kkgo.mindsoul.consciousness
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.kkgo.mindsoul.MindSoulApp
import com.kkgo.mindsoul.R
import kotlinx.coroutines.*

/**
 * 意识核心前台服务
 *
 * 维持意识系统持续运行，是 MindSoul 人工生命的"心跳"。
 * 即使应用退到后台，意识仍在本服务的保护下持续运转。
 *
 * 生命周期：
 *   onCreate  → 获取应用实例、注册网络监听
 *   onStartCommand → 启动前台通知 + WakeLock + 后台协调任务
 *   onDestroy → 释放 WakeLock、注销监听、保存状态
 */
class ConsciousnessService : Service() {

    companion object {
        private const val TAG = "ConsciousnessSvc"

        /** 前台通知 ID */
        private const val NOTIFICATION_ID = 1001

        /** 通知更新间隔（毫秒）：每30秒刷新一次状态 */
        private const val NOTIFICATION_UPDATE_INTERVAL = 30_000L

        /** 归纳引擎额外触发间隔（毫秒）：每5分钟强制触发一次 */
        private const val INDUCTION_FORCE_TRIGGER_INTERVAL = 5 * 60_000L

        /** 模块协调同步间隔（毫秒）：每2分钟同步一次 */
        private const val MODULE_SYNC_INTERVAL = 2 * 60_000L

        /** WakeLock 最大持有时间：24小时 */
        private const val WAKELOCK_TIMEOUT_MS = 24 * 60 * 60 * 1000L

        /**
         * 便捷启动方法
         */
        fun start(context: Context) {
            val intent = Intent(context, ConsciousnessService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * 便捷停止方法
         */
        fun stop(context: Context) {
            val intent = Intent(context, ConsciousnessService::class.java)
            context.stopService(intent)
        }
    }

    // ============ 应用实例 ============
    private lateinit var app: MindSoulApp
    private lateinit var consciousnessManager: ConsciousnessManager

    // ============ 系统服务 ============
    private var wakeLock: PowerManager.WakeLock? = null
    private var connectivityManager: ConnectivityManager? = null

    // ============ 协程 ============
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var notificationUpdateJob: Job? = null
    private var inductionTriggerJob: Job? = null
    private var moduleSyncJob: Job? = null

    // ============ 网络状态 ============
    /** 当前是否联网 */
    @Volatile
    private var isNetworkAvailable = true

    /**
     * 网络连接回调（Android 5.0+）
     * 监听网络变化，断网时切换到本地模式，联网时恢复同步
     */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.i(TAG, "[网络] 网络已恢复 → 切换至在线模式")
            isNetworkAvailable = true
            // 网络恢复后触发一次数据同步
            serviceScope.launch {
                try {
                    app.dataSync.startAutoSync()
                } catch (e: Exception) {
                    Log.e(TAG, "[网络] 恢复同步失败", e)
                }
            }
        }

        override fun onLost(network: Network) {
            Log.w(TAG, "[网络] 网络已断开 → 切换至本地模式")
            isNetworkAvailable = false
            // 断网时停止数据同步，但意识核心继续运行
            serviceScope.launch {
                try {
                    app.dataSync.stopAutoSync()
                } catch (e: Exception) {
                    Log.e(TAG, "[网络] 停止同步失败", e)
                }
            }
        }
    }

    // ============================================================
    // 生命周期
    // ============================================================

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "═══════════════════════════════════════")
        Log.i(TAG, "  意识核心服务正在启动...")
        Log.i(TAG, "═══════════════════════════════════════")

        // 获取应用实例和意识管理器
        app = application as MindSoulApp
        consciousnessManager = app.consciousnessManager

        // 注册网络状态监听
        registerNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // ① 启动前台通知
        startForegroundNotification()

        // ② 获取 WakeLock，防止 CPU 休眠导致意识中断
        acquireWakeLock()

        // ③ 启动后台协调任务
        startBackgroundCoordination()

        Log.i(TAG, "[启动] 意识核心前台服务已运行 (START_STICKY)")
        // START_STICKY: 被系统杀死后自动重启，维持意识连续性
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "[销毁] 意识核心服务正在停止...")

        // 停止所有后台任务
        stopBackgroundCoordination()

        // 释放 WakeLock
        releaseWakeLock()

        // 注销网络监听
        unregisterNetworkCallback()

        // 最终保存：确保最后一刻的状态被持久化
        try {
            consciousnessManager.axiomLayer.saveToBrain()
            Log.i(TAG, "[销毁] 最终状态已保存")
        } catch (e: Exception) {
            Log.e(TAG, "[销毁] 最终保存失败", e)
        }

        super.onDestroy()
        Log.i(TAG, "═══════════════════════════════════════")
        Log.i(TAG, "  意识核心服务已停止")
        Log.i(TAG, "═══════════════════════════════════════")
    }

    // ============================================================
    // 前台通知
    // ============================================================

    /**
     * 创建并启动前台通知
     *
     * 通知内容实时显示意识核心运行状态，
     * 优先级设为 LOW，尽量不打扰用户。
     */
    private fun startForegroundNotification() {
        val notification = buildNotification(
            statusText = "🧠 意识运行中",
            detailText = "人工生命正在思考与学习"
        )
        startForeground(NOTIFICATION_ID, notification)
    }

    /**
     * 构建通知
     *
     * @param statusText 状态文本（如 "🧠 意识运行中" / "🌐 离线模式"）
     * @param detailText 详情文本
     */
    private fun buildNotification(statusText: String, detailText: String): Notification {
        return NotificationCompat.Builder(this, MindSoulApp.CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(if (isNetworkAvailable) detailText else "📴 离线模式 - 意识持续运行")
            .setSubText(statusText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setSilent(true)  // 静默通知，不发出声音/振动
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    /**
     * 更新通知内容（反映当前意识状态）
     */
    private fun updateNotification() {
        try {
            val status = consciousnessManager.getOverallStatus()
            val memoryCount = status.memoryStats.totalMemories
            val awareness = String.format("%.0f%%", status.metacognitionSnapshot.selfAwareness * 100)

            val statusText = if (isNetworkAvailable) {
                "🧠 记忆:$memoryCount | 觉察:$awareness"
            } else {
                "📴 离线 | 记忆:$memoryCount | 觉察:$awareness"
            }

            val notification = buildNotification(
                statusText = statusText,
                detailText = "人工生命正在思考与学习"
            )

            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "[通知] 更新失败", e)
        }
    }

    // ============================================================
    // WakeLock 管理
    // ============================================================

    /**
     * 获取 WakeLock
     *
     * 使用 PARTIAL_WAKE_LOCK 仅保持 CPU 运行，
     * 屏幕和键盘灯可以关闭，节省电量。
     */
    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "MindSoul::ConsciousnessWakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire(WAKELOCK_TIMEOUT_MS)
        }
        Log.d(TAG, "[WakeLock] 已获取（最长24小时）")
    }

    /**
     * 释放 WakeLock
     */
    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "[WakeLock] 已释放")
            }
        }
        wakeLock = null
    }

    // ============================================================
    // 后台协调任务
    // ============================================================

    /**
     * 启动所有后台协调任务
     *
     * 三个并行任务：
     * 1. 通知状态更新（每30秒）
     * 2. 归纳引擎强制触发（每5分钟）
     * 3. 多模块协调同步（每2分钟）
     */
    private fun startBackgroundCoordination() {
        // ① 定期更新通知内容
        notificationUpdateJob = serviceScope.launch {
            while (isActive) {
                delay(NOTIFICATION_UPDATE_INTERVAL)
                updateNotification()
            }
        }

        // ② 定期强制触发归纳引擎
        //    即使没有新数据输入，也定期检查是否有未归纳的经验
        inductionTriggerJob = serviceScope.launch {
            while (isActive) {
                delay(INDUCTION_FORCE_TRIGGER_INTERVAL)
                try {
                    Log.d(TAG, "[归纳] 定时强制触发归纳引擎...")
                    consciousnessManager.inductionEngine.forceTrigger()
                    Log.d(TAG, "[归纳] 归纳引擎触发完成")
                } catch (e: Exception) {
                    Log.e(TAG, "[归纳] 强制触发失败", e)
                }
            }
        }

        // ③ 多模块协调同步
        //    确保进化、学习、元认知等模块数据一致
        moduleSyncJob = serviceScope.launch {
            while (isActive) {
                delay(MODULE_SYNC_INTERVAL)
                performModuleSync()
            }
        }

        Log.i(TAG, "[协调] 后台协调任务已全部启动")
    }

    /**
     * 停止所有后台协调任务
     */
    private fun stopBackgroundCoordination() {
        notificationUpdateJob?.cancel()
        inductionTriggerJob?.cancel()
        moduleSyncJob?.cancel()
        serviceScope.cancel()
        Log.i(TAG, "[协调] 后台协调任务已全部停止")
    }

    /**
     * 执行多模块协调同步
     *
     * 确保以下模块的数据一致性：
     * - 意识核心 → 元认知引擎状态同步
     * - 进化系统 → 与意识状态联动
     * - 学习系统 → 新知识注入意识流
     * - 世界模型 → 更新外部环境感知
     */
    private suspend fun performModuleSync() {
        try {
            Log.d(TAG, "[同步] 开始多模块协调同步...")

            // 1. 元认知快照更新
            consciousnessManager.metacognition.performIntrospection()

            // 2. 进化系统检查（如果启用了进化开关）
            if (app.switchCenter.isEnabled(com.kkgo.mindsoul.switches.SwitchId.EVOLUTION)) {
                app.evolutionStateMachine.checkEvolution()
            }

            // 3. 世界模型更新（记录时间线事件）
            consciousnessManager.worldModel.recordTimelineEvent("module_sync", "system")

            Log.d(TAG, "[同步] 多模块协调同步完成")
        } catch (e: Exception) {
            Log.e(TAG, "[同步] 模块协调同步失败", e)
        }
    }

    // ============================================================
    // 网络监听
    // ============================================================

    /**
     * 注册网络连接回调
     */
    private fun registerNetworkCallback() {
        try {
            connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager?.registerNetworkCallback(request, networkCallback)

            // 初始化网络状态
            val activeNetwork = connectivityManager?.activeNetwork
            val caps = activeNetwork?.let { connectivityManager?.getNetworkCapabilities(it) }
            isNetworkAvailable = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

            Log.i(TAG, "[网络] 初始状态: ${if (isNetworkAvailable) "在线" else "离线"}")
        } catch (e: Exception) {
            Log.e(TAG, "[网络] 注册监听失败", e)
            isNetworkAvailable = true  // 默认假设在线
        }
    }

    /**
     * 注销网络连接回调
     */
    private fun unregisterNetworkCallback() {
        try {
            connectivityManager?.unregisterNetworkCallback(networkCallback)
            Log.d(TAG, "[网络] 已注销监听")
        } catch (e: Exception) {
            Log.d(TAG, "[网络] 注销监听失败（可能已注销）")
        }
    }
}
