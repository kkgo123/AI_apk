/*
 * ============================================================
 * ConsciousnessManager - 意识架构统一管理器
 * ============================================================
 *
 * 统一管理四层AGI意识架构的初始化、协调和生命周期。
 * 负责层间通信、数据流调度和整体状态管理。
 * ============================================================
 */
package com.kkgo.mindsoul.consciousness

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.kkgo.mindsoul.MindSoulApp
import com.kkgo.mindsoul.consciousness.layer1.AxiomLayer
import com.kkgo.mindsoul.consciousness.layer2.InductionEngine
import com.kkgo.mindsoul.consciousness.layer3.ColdArchiveSystem
import com.kkgo.mindsoul.consciousness.layer4.WorldModelEngine
import com.kkgo.mindsoul.metacognition.MetacognitionEngine
import kotlinx.coroutines.*

/**
 * 意识架构统一管理器
 * 
 * 协调四层意识架构的运行
 */
class ConsciousnessManager(internal val app: MindSoulApp) {
    
    companion object {
        private const val TAG = "ConsciousnessMgr"
    }
    
    // ============ 四层架构实例 ============
    
    /** 第一层：常驻公理层 */
    lateinit var axiomLayer: AxiomLayer
        private set
    
    /** 第二层：异步归纳引擎 */
    lateinit var inductionEngine: InductionEngine
        private set
    
    /** 第三层：SQLite冷归档系统 */
    lateinit var coldArchive: ColdArchiveSystem
        private set
    
    /** 第四层：四维元认知世界模型 */
    lateinit var worldModel: WorldModelEngine
        private set
    
    /** 元认知引擎 */
    lateinit var metacognition: MetacognitionEngine
        private set
    
    // 协程作用域
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    /** 定时保存任务 */
    private var saveJob: Job? = null
    
    /** 定时遗忘任务 */
    private var forgetJob: Job? = null
    
    /**
     * 初始化所有意识层
     */
    fun initialize() {
        Log.i(TAG, "═══════════════════════════════════════")
        Log.i(TAG, "  开始初始化四层意识架构")
        Log.i(TAG, "═══════════════════════════════════════")
        
        // 第一层：常驻公理层
        axiomLayer = AxiomLayer(app.brainEngine)
        axiomLayer.initialize()
        Log.i(TAG, "✓ 第一层：常驻公理层 - 就绪")
        
        // 第二层：异步归纳引擎
        inductionEngine = InductionEngine(axiomLayer, scope)
        Log.i(TAG, "✓ 第二层：异步归纳引擎 - 就绪")
        
        // 第三层：SQLite冷归档
        coldArchive = ColdArchiveSystem(app)
        Log.i(TAG, "✓ 第三层：SQLite冷归档系统 - 就绪")
        
        // 第四层：世界模型
        worldModel = WorldModelEngine()
        worldModel.initialize()
        Log.i(TAG, "✓ 第四层：四维元认知世界模型 - 就绪")
        
        // 元认知引擎
        metacognition = MetacognitionEngine(this)
        metacognition.initialize()
        Log.i(TAG, "✓ 元认知引擎 - 就绪")
        
        // 启动后台任务
        startBackgroundTasks()
        
        Log.i(TAG, "═══════════════════════════════════════")
        Log.i(TAG, "  四层意识架构初始化完成")
        Log.i(TAG, "═══════════════════════════════════════")
    }
    
    /**
     * 启动后台定时任务
     */
    private fun startBackgroundTasks() {
        // 启动归纳引擎
        inductionEngine.start()
        
        // 定时保存公理层数据
        saveJob = scope.launch {
            while (isActive) {
                delay(60_000)  // 每分钟保存一次
                try {
                    axiomLayer.saveToBrain()
                } catch (e: Exception) {
                    Log.e(TAG, "定时保存失败", e)
                }
            }
        }
        
        // 定时执行遗忘检查
        forgetJob = scope.launch {
            while (isActive) {
                delay(ColdArchiveSystem.FORGET_CHECK_INTERVAL)
                try {
                    coldArchive.performForgetting()
                } catch (e: Exception) {
                    Log.e(TAG, "遗忘检查失败", e)
                }
            }
        }
    }
    
    /**
     * 获取整体意识状态
     */
    fun getOverallStatus(): ConsciousnessStatus {
        return ConsciousnessStatus(
            axiomLayerStatus = axiomLayer.getStatus(),
            causalTreeStats = inductionEngine.getCausalTreeStats(),
            memoryStats = coldArchive.getStats(),
            worldModelStatus = worldModel.getStatus(),
            metacognitionSnapshot = metacognition.getCurrentSnapshot()
        )
    }
    
    /**
     * 关闭意识系统
     */
    fun shutdown() {
        Log.i(TAG, "正在关闭意识系统...")
        
        // 停止归纳引擎
        inductionEngine.stop()
        
        // 取消定时任务
        saveJob?.cancel()
        forgetJob?.cancel()
        
        // 保存所有数据
        try {
            axiomLayer.saveToBrain()
        } catch (e: Exception) {
            Log.e(TAG, "关闭时保存失败", e)
        }
        
        // 关闭数据库
        coldArchive.close()
        
        // 取消协程
        scope.cancel()
        
        Log.i(TAG, "意识系统已关闭")
    }

    /**
     * 广播聊天状态变更
     *
     * 发送 CHAT_STATE_CHANGED 广播，驱动桌面精灵（FloatingAvatarService）
     * 通过 BroadcastReceiver 接收并调用 FloatingAvatarView.syncChatState()
     * 实现意识系统与桌面精灵的实时联动。
     *
     * @param chatState 聊天状态: "idle" / "thinking" / "speaking" / "listening" / "happy"
     */
    fun broadcastChatState(chatState: String) {
        val context = app as android.content.Context
        val intent = android.content.Intent("com.kkgo.mindsoul.CHAT_STATE_CHANGED").apply {
            putExtra("chat_state", chatState)
            setPackage(context.packageName)
        }
        context.sendBroadcast(intent)
        Log.d(TAG, "[广播] 发送聊天状态变更: $chatState")
    }

    /**
     * 获取当前意识身份标识
     *
     * 从元认知引擎获取 GUID Identity，用于跨模块身份识别
     */
    fun getIdentity(): com.kkgo.mindsoul.model.GUIDIdentity {
        return metacognition.getIdentity()
    }

    /**
     * 启动意识核心前台服务
     *
     * 通过 ConsciousnessService 前台服务保持意识常驻运行。
     * 被全局开关联动调用（SwitchId.BACKGROUND）。
     */
    fun startForegroundService() {
        val context = app as Context
        val intent = Intent(context, ConsciousnessService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
        Log.i(TAG, "[前台服务] 意识核心前台服务已启动")
    }

    /**
     * 停止意识核心前台服务
     *
     * 停止前台服务，意识系统将不再常驻后台。
     * 被全局开关联动调用（SwitchId.BACKGROUND）。
     */
    fun stopForegroundService() {
        val context = app as Context
        val intent = Intent(context, ConsciousnessService::class.java)
        context.stopService(intent)
        Log.i(TAG, "[前台服务] 意识核心前台服务已停止")
    }
}

/**
 * 整体意识状态
 */
data class ConsciousnessStatus(
    val axiomLayerStatus: com.kkgo.mindsoul.consciousness.layer1.AxiomLayerStatus,
    val causalTreeStats: com.kkgo.mindsoul.consciousness.layer2.CausalTreeStats,
    val memoryStats: com.kkgo.mindsoul.consciousness.layer3.MemoryStats,
    val worldModelStatus: com.kkgo.mindsoul.consciousness.layer4.WorldModelStatus,
    val metacognitionSnapshot: com.kkgo.mindsoul.model.MetacognitionSnapshot
)
