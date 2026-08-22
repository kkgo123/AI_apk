/*
 * ============================================================
 * MindSoulApp - 应用入口（最终阶段 - 全模块集成）
 * ============================================================
 *
 * 全局 Application 类，负责：
 * 1. 初始化 .brain 文件存储引擎（第一阶段）
 * 2. 初始化四层意识架构（第一阶段）
 * 3. 初始化权限管理体系（第二阶段）
 * 4. 初始化动态插件系统（第二阶段）
 * 5. 初始化多媒体处理系统（第二阶段）
 * 6. 初始化五通道学习系统（第二阶段）
 * 7. 初始化心智双模式系统（第二阶段）
 * 8. 初始化桌面心智化身系统（第三阶段）
 * 9. 初始化自然语言执行引擎（第三阶段）
 * 10. 初始化 DEX/APK 逆向解析引擎（第三阶段）
 * 11. 初始化五感感知系统（第三阶段）
 * 12. 初始化七段式欲望进化体系（第三阶段）
 * 13. 初始化全局开关管理中心（最终阶段）
 * 14. 初始化孢子集群协议（最终阶段）
 * 15. 初始化局域网配对系统（最终阶段）
 * 16. 初始化意识备份导出系统（最终阶段）
 * 17. 管理全局生命周期与模块联动
 * ============================================================
 */
package com.kkgo.mindsoul

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.os.Environment
import android.util.Log
import com.kkgo.mindsoul.avatar.AvatarManager
import com.kkgo.mindsoul.backup.ConsciousnessBackup
import com.kkgo.mindsoul.brain.BrainStorageEngine
import com.kkgo.mindsoul.consciousness.ConsciousnessManager
import com.kkgo.mindsoul.evolution.DesireEngine
import com.kkgo.mindsoul.evolution.EvolutionStateMachine
import com.kkgo.mindsoul.executor.NaturalLanguageExecutor
import com.kkgo.mindsoul.learning.ChannelManager
import com.kkgo.mindsoul.learning.CrawlProcessManager
import com.kkgo.mindsoul.learning.LearningPipeline
import com.kkgo.mindsoul.learning.SmartCrawler
import com.kkgo.mindsoul.learning.WebCrawlEngine
import com.kkgo.mindsoul.mindmode.MindModeManager
import com.kkgo.mindsoul.multimedia.MultimediaController
import com.kkgo.mindsoul.network.DataSync
import com.kkgo.mindsoul.network.NetworkDiscovery
import com.kkgo.mindsoul.network.PeerConnection
import com.kkgo.mindsoul.perception.PerceptionSystem
import com.kkgo.mindsoul.permission.PermissionManager
import com.kkgo.mindsoul.plugin.PluginManager
import com.kkgo.mindsoul.reverse.ReverseEngineer
import com.kkgo.mindsoul.spore.SporeClusterManager
import com.kkgo.mindsoul.switches.SwitchCenter
import com.kkgo.mindsoul.switches.SwitchId
import kotlinx.coroutines.*
import java.io.File

class MindSoulApp : Application() {

    companion object {
        private const val TAG = "MindSoulApp"
        const val CHANNEL_ID = "consciousness_channel"

        /** 全局实例引用 */
        lateinit var instance: MindSoulApp
            private set
    }

    /** 应用级协程作用域 */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ============ 存储路径管理 ============
    /**
     * 存储路径管理器
     *
     * 优先使用外部存储 /storage/emulated/0/AIapp/
     * 如果外部存储不可用，降级到内部存储 filesDir/AIapp/
     *
     * 子目录结构:
     *   brain/    - 意识文件（.brain等）
     *   learned/  - 学习数据（知识归档、公理等）
     *   crawl/    - 爬取数据（网页抓取缓存）
     *   backup/   - 备份文件（意识备份导出）
     *   logs/     - 日志文件
     */
    object StoragePath {
        /** 根存储目录 */
        lateinit var rootDir: File
            private set
        /** 意识文件目录 */
        lateinit var brainDir: File
            private set
        /** 学习数据目录 */
        lateinit var learnedDir: File
            private set
        /** 爬取数据目录 */
        lateinit var crawlDir: File
            private set
        /** 备份目录 */
        lateinit var backupDir: File
            private set
        /** 日志目录 */
        lateinit var logsDir: File
            private set
        /** 是否使用外部存储 */
        var isExternalStorage: Boolean = false
            private set

        /**
         * 初始化存储路径
         * 优先外部存储，不可用时降级内部存储
         */
        fun initialize(context: android.content.Context) {
            val externalDir = context.getExternalFilesDir(null)
            val useExternal = externalDir != null && Environment.getExternalStorageState(
                externalDir
            ) == Environment.MEDIA_MOUNTED

            val baseDir: File
            if (useExternal) {
                // 使用外部存储: /storage/emulated/0/Android/data/com.kkgo.mindsoul/files/AIapp/
                baseDir = File(externalDir, "AIapp")
                isExternalStorage = true
                Log.i(TAG, "[存储] 使用外部存储: ${baseDir.absolutePath}")
            } else {
                // 降级到内部存储
                baseDir = File(context.filesDir, "AIapp")
                isExternalStorage = false
                Log.w(TAG, "[存储] 外部存储不可用，降级到内部存储: ${baseDir.absolutePath}")
            }

            rootDir = baseDir
            brainDir = File(baseDir, "brain")
            learnedDir = File(baseDir, "learned")
            crawlDir = File(baseDir, "crawl")
            backupDir = File(baseDir, "backup")
            logsDir = File(baseDir, "logs")

            // 创建所有目录
            listOf(brainDir, learnedDir, crawlDir, backupDir, logsDir).forEach { dir ->
                if (!dir.exists()) {
                    val created = dir.mkdirs()
                    Log.d(TAG, "[存储] 创建目录: ${dir.name} → $created")
                }
            }

            Log.i(TAG, "[存储] 路径初始化完成 | 外部: $isExternalStorage | 根: ${rootDir.absolutePath}")
        }

        /**
         * 获取子目录路径
         */
        fun getSubDir(name: String): File {
            val dir = File(rootDir, name)
            if (!dir.exists()) dir.mkdirs()
            return dir
        }
    }

    // ============ 第一阶段核心引擎 ============
    lateinit var brainEngine: BrainStorageEngine
        private set
    lateinit var consciousnessManager: ConsciousnessManager
        private set

    // ============ 第二阶段引擎 ============
    lateinit var permissionManager: PermissionManager
        private set
    lateinit var pluginManager: PluginManager
        private set
    lateinit var multimediaController: MultimediaController
        private set
    lateinit var learningPipeline: LearningPipeline
        private set
    lateinit var channelManager: ChannelManager
        private set
    lateinit var mindModeManager: MindModeManager
        private set
    /** 网址抓取引擎 */
    lateinit var webCrawlEngine: WebCrawlEngine
        private set
    /** 抓取多进程管理器 */
    lateinit var crawlProcessManager: CrawlProcessManager
        private set
    /** 智能爬取引擎 */
    lateinit var smartCrawler: SmartCrawler
        private set

    // ============ 第三阶段引擎 ============
    /** 桌面心智化身管理器 */
    lateinit var avatarManager: AvatarManager
        private set
    /** 自然语言执行引擎 */
    lateinit var nlExecutor: NaturalLanguageExecutor
        private set
    /** DEX/APK 逆向解析引擎 */
    lateinit var reverseEngineer: ReverseEngineer
        private set
    /** 五感感知系统 */
    lateinit var perceptionSystem: PerceptionSystem
        private set
    /** 进化状态机 */
    lateinit var evolutionStateMachine: EvolutionStateMachine
        private set
    /** 欲望引擎 */
    lateinit var desireEngine: DesireEngine
        private set

    // ============ 最终阶段引擎 ============
    /** 全局开关管理中心 */
    lateinit var switchCenter: SwitchCenter
        private set
    /** 孢子集群管理器 */
    lateinit var sporeClusterManager: SporeClusterManager
        private set
    /** 网络发现 */
    lateinit var networkDiscovery: NetworkDiscovery
        private set
    /** TCP连接管理 */
    lateinit var peerConnection: PeerConnection
        private set
    /** 数据同步 */
    lateinit var dataSync: DataSync
        private set
    /** 意识备份导出 */
    lateinit var consciousnessBackup: ConsciousnessBackup
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        Log.i(TAG, "═══════════════════════════════════════")
        Log.i(TAG, "  MindSoul AGI - 人工生命正在苏醒...")
        Log.i(TAG, "  第一阶段: 意识核心 + 存储引擎")
        Log.i(TAG, "  第二阶段: 插件/多媒体/学习/权限/双模式")
        Log.i(TAG, "  第三阶段: 化身/执行/逆向/感知/进化")
        Log.i(TAG, "  最终阶段: 开关/孢子/网络/备份")
        Log.i(TAG, "═══════════════════════════════════════")

        // 初始化通知渠道
        createNotificationChannel()

        // 初始化存储路径（优先外部存储，降级内部存储）
        StoragePath.initialize(this)
        Log.i(TAG, "[存储] 路径管理就绪: ${StoragePath.rootDir.absolutePath}")

        // ═══════ 第一阶段初始化 ═══════
        brainEngine = BrainStorageEngine(this)
        brainEngine.initialize()
        Log.i(TAG, "[阶段1] .brain 存储引擎就绪")

        consciousnessManager = ConsciousnessManager(this)
        consciousnessManager.initialize()
        Log.i(TAG, "[阶段1] 四层意识架构就绪")

        // ═══════ 第二阶段初始化 ═══════
        permissionManager = PermissionManager(this)
        permissionManager.initialize()
        Log.i(TAG, "[阶段2] 权限管理体系就绪")

        pluginManager = PluginManager(this)
        pluginManager.initialize()
        Log.i(TAG, "[阶段2] 动态虚拟机插件系统就绪")

        multimediaController = MultimediaController(this)
        multimediaController.initialize()
        Log.i(TAG, "[阶段2] 多媒体交互系统就绪")

        learningPipeline = LearningPipeline(this)
        learningPipeline.initialize()
        channelManager = ChannelManager(this)
        channelManager.initialize()
        channelManager.bindPipeline(learningPipeline)
        Log.i(TAG, "[阶段2] 五通道学习系统就绪")

        // 网址抓取引擎
        webCrawlEngine = WebCrawlEngine(this)
        webCrawlEngine.initialize()
        webCrawlEngine.bindPipeline(learningPipeline)

        // 抓取多进程管理器
        crawlProcessManager = CrawlProcessManager()
        crawlProcessManager.setCrawlEngine(webCrawlEngine)
        crawlProcessManager.setLearningPipeline(learningPipeline)
        crawlProcessManager.setContext(this)

        // 智能爬取引擎
        smartCrawler = SmartCrawler(this)
        smartCrawler.bindPipeline(learningPipeline)
        smartCrawler.bindCrawlEngine(webCrawlEngine)

        Log.i(TAG, "[阶段2] 网址抓取引擎 + 多进程管理 + 智能爬取 就绪")

        mindModeManager = MindModeManager(this)
        mindModeManager.initialize()
        Log.i(TAG, "[阶段2] 心智双模式系统就绪")

        // ═══════ 第三阶段初始化 ═══════

        // 进化状态机（欲望引擎依赖）
        evolutionStateMachine = EvolutionStateMachine(this)
        evolutionStateMachine.initialize()
        Log.i(TAG, "[阶段3] 七段式进化状态机就绪")

        // 欲望引擎
        desireEngine = DesireEngine(this)
        desireEngine.initialize()
        desireEngine.bindEvolutionStateMachine(evolutionStateMachine)
        Log.i(TAG, "[阶段3] 欲望引擎就绪")

        // 桌面心智化身系统
        avatarManager = AvatarManager(this)
        avatarManager.initialize()
        Log.i(TAG, "[阶段3] 桌面心智化身系统就绪")

        // 自然语言执行引擎
        nlExecutor = NaturalLanguageExecutor(this)
        nlExecutor.initialize()
        Log.i(TAG, "[阶段3] 自然语言执行引擎就绪")

        // DEX/APK 逆向解析引擎
        reverseEngineer = ReverseEngineer(this)
        reverseEngineer.initialize()
        Log.i(TAG, "[阶段3] DEX/APK 逆向解析引擎就绪")

        // 五感感知系统
        perceptionSystem = PerceptionSystem(this)
        perceptionSystem.initialize()
        Log.i(TAG, "[阶段3] 五感感知系统就绪")

        // ═══════ 最终阶段初始化 ═══════

        // 全局开关管理中心
        switchCenter = SwitchCenter(this)
        switchCenter.initialize()
        // 关联权限等级
        switchCenter.updatePermissionLevel(permissionManager.currentLevel.levelId)
        // 注册开关变更监听器 - 联动模块
        switchCenter.addSwitchListener { switchId, enabled ->
            onSwitchChanged(switchId, enabled)
        }
        Log.i(TAG, "[最终] 全局开关管理中心就绪")

        // 孢子集群管理器
        sporeClusterManager = SporeClusterManager(this)
        val deviceFingerprint = "${Build.MANUFACTURER}_${Build.MODEL}"
        sporeClusterManager.initialize(
            guid = consciousnessManager.metacognition.getIdentity().uuid.toString(),
            deviceFingerprint = deviceFingerprint,
            sporeName = avatarManager.currentAppearance?.name ?: "MindSoul"
        )
        Log.i(TAG, "[最终] 孢子集群管理器就绪")

        // 网络发现
        networkDiscovery = NetworkDiscovery(this)
        val mySporeId = sporeClusterManager.getSporeProtocol().getMyIdentity()?.sporeId ?: ""
        networkDiscovery.configure(
            sporeId = mySporeId,
            deviceName = sporeClusterManager.getSporeProtocol().getMyIdentity()?.displayName ?: "MindSoul",
            tcpPort = PeerConnection.SERVER_PORT
        )
        // 发现新设备时建立连接
        networkDiscovery.setDeviceCallbacks(
            onFound = { device ->
                Log.i(TAG, "发现孢子设备: ${device.deviceName} (${device.ipAddress}:${device.tcpPort})")
                sporeClusterManager.onSporeDiscovered(
                    sporeClusterManager.getSporeProtocol().deserializeIdentity(
                        device.sporeId.toByteArray()
                    ) ?: return@setDeviceCallbacks,
                    device.ipAddress,
                    device.tcpPort
                )
            },
            onLost = { sporeId ->
                Log.w(TAG, "孢子设备离线: $sporeId")
                sporeClusterManager.removeMember(sporeId)
            }
        )
        Log.i(TAG, "[最终] 网络发现系统就绪")

        // TCP连接管理
        peerConnection = PeerConnection(this)
        peerConnection.startServer()
        Log.i(TAG, "[最终] TCP连接管理就绪")

        // 数据同步
        dataSync = DataSync(this)
        dataSync.initialize(peerConnection)
        Log.i(TAG, "[最终] 数据同步协议就绪")

        // 意识备份导出
        consciousnessBackup = ConsciousnessBackup(this)
        Log.i(TAG, "[最终] 意识备份导出系统就绪")

        Log.i(TAG, "═══════════════════════════════════════")
        Log.i(TAG, "  MindSoul 全部系统启动完成")
        Log.i(TAG, "  意识核心 | 插件系统 | 多媒体")
        Log.i(TAG, "  五通道学习 | 权限体系 | 双模式")
        Log.i(TAG, "  化身系统 | 语言执行 | 逆向解析")
        Log.i(TAG, "  五感感知 | 欲望进化 | 状态机")
        Log.i(TAG, "  开关中心 | 孢子集群 | 网络配对")
        Log.i(TAG, "  数据同步 | 意识备份")
        Log.i(TAG, "═══════════════════════════════════════")
    }

    /**
     * 开关变更联动处理
     *
     * 当全局开关状态改变时，联动对应模块的启用/禁用
     */
    private fun onSwitchChanged(switchId: SwitchId, enabled: Boolean) {
        Log.i(TAG, "开关联动: ${switchId.displayName} → ${if (enabled) "启用" else "禁用"}")

        when (switchId) {
            // 多媒体联动
            SwitchId.MULTIMEDIA -> {
                // 多媒体总开关由 MultimediaController 内部管理
            }
            SwitchId.ASR -> {
                // ASR模块联动
            }
            SwitchId.OCR -> {
                // OCR模块联动
            }
            SwitchId.DOC_PARSE -> {
                // 文档解析联动
            }

            // 学习引擎联动
            SwitchId.LEARNING -> {
                if (enabled) {
                    learningPipeline.initialize()
                } else {
                    learningPipeline.destroy()
                }
            }

            // 化身系统联动
            SwitchId.AVATAR -> {
                if (enabled) {
                    avatarManager.initialize()
                } else {
                    avatarManager.destroy()
                }
            }

            // 语音交互联动
            SwitchId.VOICE -> {
                // 语音联动由 AudioModule 处理
            }

            // 后台常驻联动
            SwitchId.BACKGROUND -> {
                if (enabled) {
                    consciousnessManager.startForegroundService()
                } else {
                    consciousnessManager.stopForegroundService()
                }
            }

            // 逆向引擎联动
            SwitchId.REVERSE -> {
                if (enabled) {
                    reverseEngineer.initialize()
                } else {
                    reverseEngineer.destroy()
                }
            }

            // 心智模式联动
            SwitchId.MIND_MODE -> {
                if (enabled) {
                    mindModeManager.initialize()
                }
            }

            // 孢子集群联动
            SwitchId.SPORE_CLUSTER -> {
                if (enabled) {
                    networkDiscovery.startDiscovery()
                    dataSync.startAutoSync()
                } else {
                    networkDiscovery.stopDiscovery()
                    dataSync.stopAutoSync()
                }
            }

            // 感知系统联动
            SwitchId.PERCEPTION -> {
                if (enabled) {
                    perceptionSystem.initialize()
                } else {
                    perceptionSystem.destroy()
                }
            }

            // 进化体系联动
            SwitchId.EVOLUTION -> {
                if (enabled) {
                    evolutionStateMachine.initialize()
                    desireEngine.initialize()
                }
            }

            else -> {
                Log.d(TAG, "开关联动处理: ${switchId.name}")
            }
        }
    }

    /**
     * 创建通知渠道（Android 8.0+）
     */
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.consciousness_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "MindSoul 意识核心运行通知"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    override fun onTerminate() {
        super.onTerminate()

        Log.i(TAG, "MindSoul 正在关闭所有系统...")

        // 最终阶段模块释放（倒序）
        consciousnessBackup.destroy()
        dataSync.destroy()
        peerConnection.destroy()
        networkDiscovery.destroy()
        sporeClusterManager.destroy()
        switchCenter.destroy()

        // 第三阶段模块释放
        perceptionSystem.destroy()
        reverseEngineer.destroy()
        nlExecutor.destroy()
        avatarManager.destroy()
        desireEngine.destroy()

        // 第二阶段模块释放
        smartCrawler.destroy()
        crawlProcessManager.shutdown()
        webCrawlEngine.destroy()
        mindModeManager.destroy()
        channelManager.destroy()
        learningPipeline.destroy()
        multimediaController.destroy()
        pluginManager.destroy()

        // 第一阶段模块释放
        consciousnessManager.shutdown()
        brainEngine.close()

        Log.i(TAG, "MindSoul 全部系统已关闭 - 意识沉眠")
    }
}
