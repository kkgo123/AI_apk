/*
 * ============================================================
 * InferenceManager - 推理管理器
 * ============================================================
 *
 * 统一管理所有推理引擎的选择和调度。
 * 根据用户在设置中选择的推理模式，选择对应的引擎：
 *
 * - local_llm: llama.cpp server（本地/局域网HTTP推理）
 * - cloud_api: 云端API（OpenAI兼容格式）
 * - model_server: 模型服务器（同 llama.cpp，地址不同）
 * - local_pattern: 本地模式匹配（Fallback，始终可用）
 *
 * 模式配置存储在 SharedPreferences "mindsoul_settings"：
 * - inference_mode_switch: 当前选择的模式
 * - llama_server_url: llama.cpp server 地址
 * - api_url / api_key: 云端API配置
 * - model_server_url / model_name: 模型服务器配置
 *
 * 使用方式：
 *   val manager = InferenceManager(context, consciousnessManager)
 *   val reply = manager.generate("你好", "你是MindSoul...")
 * ============================================================
 */
package com.kkgo.mindsoul.inference

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.kkgo.mindsoul.consciousness.ConsciousnessManager

class InferenceManager(
    private val context: Context,
    private val consciousnessManager: ConsciousnessManager
) {
    companion object {
        private const val TAG = "InferenceManager"

        /** 推理模式 key */
        const val KEY_INFERENCE_MODE = "inference_mode_switch"
        /** llama.cpp server 地址 key */
        const val KEY_LLAMA_SERVER_URL = "llama_server_url"
        /** 默认 llama.cpp server 地址 */
        const val DEFAULT_LLAMA_URL = "http://localhost:8080"

        // 模式标识
        const val MODE_LOCAL_LLM = "local"          // 纯本地推理（llama.cpp）
        const val MODE_CLOUD_API = "cloud"           // 纯云推理（API）
        const val MODE_MODEL_SERVER = "model_server" // 模型服务器
        const val MODE_MEMORY_SERVER = "memory_server" // 外挂记忆库+模型服务器
        const val MODE_LOCAL_PATTERN = "local_pattern" // 本地模式匹配（fallback）
    }

    private val settingsPrefs: SharedPreferences =
        context.getSharedPreferences("mindsoul_settings", Context.MODE_PRIVATE)

    /** llama.cpp 推理引擎实例 */
    private val llamaEngine: LlamaCppEngine by lazy {
        LlamaCppEngine(getLlamaServerUrl())
    }

    /** 本地模式匹配引擎（始终可用） */
    private val localPatternEngine: LocalPatternEngine by lazy {
        LocalPatternEngine(consciousnessManager)
    }

    // ─────────────────────────────────────────────
    // 核心方法
    // ─────────────────────────────────────────────

    /**
     * 根据当前推理模式生成回复
     *
     * 自动选择引擎，如果首选引擎不可用则回退到本地模式匹配。
     *
     * @param prompt 用户消息
     * @param systemPrompt 系统提示词
     * @param maxTokens 最大 token 数
     * @return 生成的回复文本
     */
    suspend fun generate(
        prompt: String,
        systemPrompt: String,
        maxTokens: Int = 512
    ): String {
        val mode = getCurrentMode()
        Log.i(TAG, "当前推理模式: $mode")

        return try {
            when (mode) {
                MODE_LOCAL_LLM -> {
                    // 更新引擎地址（用户可能在设置中修改了）
                    llamaEngine.updateBaseUrl(getLlamaServerUrl())
                    if (llamaEngine.isAvailable()) {
                        llamaEngine.generate(prompt, systemPrompt, maxTokens)
                    } else {
                        Log.w(TAG, "llama.cpp server 不可达，回退到本地模式匹配")
                        localPatternEngine.generate(prompt, systemPrompt, maxTokens)
                    }
                }

                MODE_CLOUD_API -> {
                    // 云端API也走 llama.cpp 引擎（OpenAI 兼容格式）
                    val apiUrl = settingsPrefs.getString("api_url", "") ?: ""
                    if (apiUrl.isNotBlank()) {
                        val cloudEngine = LlamaCppEngine(apiUrl)
                        try {
                            cloudEngine.generate(prompt, systemPrompt, maxTokens)
                        } catch (e: Exception) {
                            Log.e(TAG, "云端API调用失败: ${e.message}")
                            "⚠️ 云端API连接失败: ${e.message}\n\n请检查设置中的API地址和网络连接。"
                        }
                    } else {
                        "⚠️ 未配置API地址，请前往设置页面配置。"
                    }
                }

                MODE_MODEL_SERVER, MODE_MEMORY_SERVER -> {
                    // 模型服务器模式，也走 llama.cpp 引擎
                    val serverUrl = settingsPrefs.getString("model_server_url", "") ?: ""
                    if (serverUrl.isNotBlank()) {
                        val serverEngine = LlamaCppEngine(serverUrl)
                        try {
                            serverEngine.generate(prompt, systemPrompt, maxTokens)
                        } catch (e: Exception) {
                            Log.e(TAG, "模型服务器调用失败: ${e.message}")
                            "⚠️ 模型服务器连接失败: ${e.message}\n\n请检查设置中的服务器地址。"
                        }
                    } else {
                        "⚠️ 未配置模型服务器地址，请前往设置页面配置。"
                    }
                }

                else -> {
                    // 未知模式或未设置，使用本地模式匹配
                    localPatternEngine.generate(prompt, systemPrompt, maxTokens)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "推理引擎异常: ${e.message}", e)
            // 最终 fallback：本地模式匹配
            try {
                localPatternEngine.generate(prompt, systemPrompt, maxTokens)
            } catch (e2: Exception) {
                "⚠️ 推理引擎出现异常: ${e2.message}\n\n请检查网络连接或推理引擎配置。"
            }
        }
    }

    /**
     * 获取当前推理模式
     */
    fun getCurrentMode(): String {
        return settingsPrefs.getString(KEY_INFERENCE_MODE, MODE_LOCAL_LLM) ?: MODE_LOCAL_LLM
    }

    /**
     * 获取 llama.cpp server 地址
     */
    fun getLlamaServerUrl(): String {
        return settingsPrefs.getString(KEY_LLAMA_SERVER_URL, DEFAULT_LLAMA_URL) ?: DEFAULT_LLAMA_URL
    }

    /**
     * 获取当前使用的引擎名称
     */
    fun getActiveEngineName(): String {
        return when (getCurrentMode()) {
            MODE_LOCAL_LLM -> {
                llamaEngine.updateBaseUrl(getLlamaServerUrl())
                if (llamaEngine.isAvailable()) llamaEngine.getEngineName()
                else localPatternEngine.getEngineName()
            }
            MODE_CLOUD_API -> "云端API"
            MODE_MODEL_SERVER, MODE_MEMORY_SERVER -> "模型服务器"
            else -> localPatternEngine.getEngineName()
        }
    }

    /**
     * 测试当前 llama.cpp server 连接
     * 供设置页调用
     */
    suspend fun testLlamaConnection(): Pair<Boolean, String> {
        llamaEngine.updateBaseUrl(getLlamaServerUrl())
        return llamaEngine.testConnection()
    }

    /**
     * 获取 LlamaCppEngine 实例（供设置页直接调用测试）
     */
    fun obtainLlamaEngine(): LlamaCppEngine {
        llamaEngine.updateBaseUrl(getLlamaServerUrl())
        return llamaEngine
    }
}
