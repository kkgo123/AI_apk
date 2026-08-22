/*
 * ============================================================
 * CrawlForegroundService - 爬取前台服务
 * ============================================================
 *
 * 前台服务，确保息屏后爬取继续运行：
 *
 * 1. 持有 WakeLock（PARTIAL_WAKE_LOCK）防止CPU休眠
 * 2. 注册 NetworkCallback 监听网络状态：
 *    - onAvailable → 自动恢复爬取
 *    - onLost → 自动暂停爬取，保存断点
 * 3. 通知栏显示当前爬取进度
 * 4. 供 UrlCrawlFragment 绑定调用
 * ============================================================
 */
package com.kkgo.mindsoul.learning

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
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
import com.kkgo.mindsoul.ui.LearningActivity

/**
 * 爬取前台服务
 *
 * 通过 startForeground 保持服务常驻，息屏后爬取不中断。
 * 同时监听网络状态变化，自动暂停/恢复爬取。
 */
class CrawlForegroundService : Service() {

    companion object {
        private const val TAG = "CrawlFgService"
        const val NOTIFICATION_ID = 20001
        const val CHANNEL_ID = "crawl_channel"
        const val CHANNEL_NAME = "爬取服务"

        /** Action: 启动服务 */
        const val ACTION_START = "com.kkgo.mindsoul.action.CRAWL_START"
        /** Action: 停止服务 */
        const val ACTION_STOP = "com.kkgo.mindsoul.action.CRAWL_STOP"
        /** Action: 暂停爬取 */
        const val ACTION_PAUSE = "com.kkgo.mindsoul.action.CRAWL_PAUSE"
        /** Action: 恢复爬取 */
        const val ACTION_RESUME = "com.kkgo.mindsoul.action.CRAWL_RESUME"

        /** 广播: 网络状态变化 */
        const val BROADCAST_NETWORK_CHANGED = "com.kkgo.mindsoul.CRAWL_NETWORK_CHANGED"
        /** 广播Extra: 网络是否可用 */
        const val EXTRA_NETWORK_AVAILABLE = "network_available"
    }

    // ============ WakeLock ============
    private var wakeLock: PowerManager.WakeLock? = null

    // ============ 网络监听 ============
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // ============ 网络状态 ============
    private var isNetworkAvailable: Boolean = true

    // ============ App引用 ============
    private lateinit var app: MindSoulApp

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        app = application as MindSoulApp
        Log.i(TAG, "[创建] 爬取前台服务已创建")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart()
            ACTION_STOP -> handleStop()
            ACTION_PAUSE -> handlePause()
            ACTION_RESUME -> handleResume()
            else -> handleStart()
        }
        return START_STICKY
    }

    // ============ 启动处理 ============

    /**
     * 处理启动命令
     */
    private fun handleStart() {
        // 启动前台通知
        startForeground(NOTIFICATION_ID, buildNotification("爬取服务运行中..."))

        // 获取WakeLock
        acquireWakeLock()

        // 注册网络监听
        registerNetworkCallback()

        // 尝试加载上次未完成的爬取任务
        loadSavedTasks()

        Log.i(TAG, "[启动] 爬取前台服务已启动")
    }

    /**
     * 处理停止命令
     */
    private fun handleStop() {
        // 保存当前进度
        saveAllProgress()

        // 停止所有爬取
        app.crawlProcessManager.shutdown()

        // 释放WakeLock
        releaseWakeLock()

        // 注销网络监听
        unregisterNetworkCallback()

        // 停止服务
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()

        Log.i(TAG, "[停止] 爬取前台服务已停止")
    }

    /**
     * 处理暂停命令
     */
    private fun handlePause() {
        // 保存当前进度
        saveAllProgress()

        // 通知引擎网络不可用（暂停爬取）
        app.webCrawlEngine.setNetworkAvailable(false)

        // 暂停所有运行中的进程
        val runningProcesses = app.crawlProcessManager.getAllProcesses()
            .filter { it.state == CrawlProcessState.RUNNING }
        runningProcesses.forEach { process ->
            app.crawlProcessManager.pauseProcess(process.id)
        }

        // 暂停智能爬取
        if (app.smartCrawler.getState() == SmartCrawlerState.CRAWLING) {
            app.smartCrawler.pause()
        }

        updateNotification("爬取已暂停")
        Log.i(TAG, "[暂停] 所有爬取任务已暂停")
    }

    /**
     * 处理恢复命令
     */
    private fun handleResume() {
        // 通知引擎网络可用
        app.webCrawlEngine.setNetworkAvailable(true)

        // 恢复所有暂停的进程
        val pausedProcesses = app.crawlProcessManager.getAllProcesses()
            .filter { it.state == CrawlProcessState.PAUSED }
        pausedProcesses.forEach { process ->
            app.crawlProcessManager.resumeProcess(process.id)
        }

        // 恢复智能爬取
        if (app.smartCrawler.getState() == SmartCrawlerState.PAUSED) {
            app.smartCrawler.resume()
        }

        updateNotification("爬取服务运行中...")
        Log.i(TAG, "[恢复] 所有爬取任务已恢复")
    }

    // ============ WakeLock ============

    /**
     * 获取WakeLock防止CPU休眠
     */
    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "MindSoul::CrawlWakeLock"
            ).apply {
                setReferenceCounted(false)
                acquire(24 * 60 * 60 * 1000L) // 最长24小时
            }
            Log.d(TAG, "WakeLock 已获取")
        }
    }

    /**
     * 释放WakeLock
     */
    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "WakeLock 已释放")
            }
        }
        wakeLock = null
    }

    // ============ 网络监听 ============

    /**
     * 注册网络状态回调
     */
    private fun registerNetworkCallback() {
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                Log.i(TAG, "[网络] 网络已恢复")
                isNetworkAvailable = true

                // 通知引擎网络可用
                app.webCrawlEngine.setNetworkAvailable(true)

                // 自动恢复爬取
                autoResumeCrawling()

                // 发送广播通知UI更新
                sendNetworkBroadcast(true)
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                Log.w(TAG, "[网络] 网络已断开")
                isNetworkAvailable = false

                // 通知引擎网络不可用
                app.webCrawlEngine.setNetworkAvailable(false)

                // 自动暂停爬取并保存断点
                autoPauseAndSave()

                // 发送广播通知UI更新
                sendNetworkBroadcast(false)
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                super.onCapabilitiesChanged(network, networkCapabilities)
                val hasInternet = networkCapabilities.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_INTERNET
                )
                if (hasInternet != isNetworkAvailable) {
                    isNetworkAvailable = hasInternet
                    app.webCrawlEngine.setNetworkAvailable(hasInternet)
                    if (!hasInternet) {
                        autoPauseAndSave()
                    } else {
                        autoResumeCrawling()
                    }
                    sendNetworkBroadcast(hasInternet)
                }
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        connectivityManager?.registerNetworkCallback(request, networkCallback!!)
        Log.d(TAG, "网络监听回调已注册")
    }

    /**
     * 注销网络状态回调
     */
    private fun unregisterNetworkCallback() {
        try {
            networkCallback?.let {
                connectivityManager?.unregisterNetworkCallback(it)
            }
            Log.d(TAG, "网络监听回调已注销")
        } catch (e: Exception) {
            Log.w(TAG, "注销网络回调失败: ${e.message}")
        }
        networkCallback = null
    }

    // ============ 自动暂停/恢复 ============

    /**
     * 自动暂停爬取并保存断点
     */
    private fun autoPauseAndSave() {
        Log.i(TAG, "[自动暂停] 网络断开，暂停所有爬取并保存进度")

        // 保存进度
        saveAllProgress()

        // 暂停所有运行中的进程
        val runningProcesses = app.crawlProcessManager.getAllProcesses()
            .filter { it.state == CrawlProcessState.RUNNING }
        runningProcesses.forEach { process ->
            app.crawlProcessManager.pauseProcess(process.id)
        }

        // 暂停智能爬取
        if (app.smartCrawler.getState() == SmartCrawlerState.CRAWLING) {
            app.smartCrawler.pause()
        }

        updateNotification("⚠️ 网络断开，爬取已暂停")
    }

    /**
     * 自动恢复爬取
     */
    private fun autoResumeCrawling() {
        Log.i(TAG, "[自动恢复] 网络已恢复，恢复所有爬取")

        // 恢复所有暂停的进程
        val pausedProcesses = app.crawlProcessManager.getAllProcesses()
            .filter { it.state == CrawlProcessState.PAUSED }
        pausedProcesses.forEach { process ->
            app.crawlProcessManager.resumeProcess(process.id)
        }

        // 恢复智能爬取
        if (app.smartCrawler.getState() == SmartCrawlerState.PAUSED) {
            app.smartCrawler.resume()
        }

        updateNotification("爬取服务运行中...")
    }

    // ============ 进度保存/加载 ============

    /**
     * 保存所有爬取进度
     */
    private fun saveAllProgress() {
        app.crawlProcessManager.saveCheckpoint()
        Log.d(TAG, "所有爬取进度已保存")
    }

    /**
     * 加载上次未完成的爬取任务
     */
    private fun loadSavedTasks() {
        app.crawlProcessManager.loadCheckpoint()
        val processes = app.crawlProcessManager.getAllProcesses()
        val unfinishedCount = processes.count { !it.isFinished }
        if (unfinishedCount > 0) {
            Log.i(TAG, "已加载 $unfinishedCount 个未完成的爬取任务")
            updateNotification("已恢复 $unfinishedCount 个爬取任务")
        }
    }

    // ============ 通知 ============

    /**
     * 构建通知
     */
    private fun buildNotification(content: String): Notification {
        // 点击通知跳转到学习页面
        val pendingIntent = Intent(this, LearningActivity::class.java).let { intent ->
            PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        return NotificationCompat.Builder(this, MindSoulApp.CHANNEL_ID)
            .setContentTitle("MindSoul 爬取服务")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /**
     * 更新通知内容
     */
    private fun updateNotification(content: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, buildNotification(content))
    }

    // ============ 广播 ============

    /**
     * 发送网络状态变化广播
     */
    private fun sendNetworkBroadcast(available: Boolean) {
        val intent = Intent(BROADCAST_NETWORK_CHANGED).apply {
            putExtra(EXTRA_NETWORK_AVAILABLE, available)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    // ============ 查询接口 ============

    /**
     * 获取当前网络状态
     */
    fun isNetworkOk(): Boolean = isNetworkAvailable

    // ============ 销毁 ============

    override fun onDestroy() {
        releaseWakeLock()
        unregisterNetworkCallback()
        Log.i(TAG, "[销毁] 爬取前台服务已销毁")
        super.onDestroy()
    }
}
